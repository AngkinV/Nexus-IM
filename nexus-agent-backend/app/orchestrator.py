"""Agent orchestrator: runs the model + tool loop and yields events.

Algorithm (see Agent 设计说明.md §11):
  1. PRECHECK: validated by FastAPI dependency (HMAC, headers).
  2. BUILD_CONTEXT: pull short-term memory + system/business prompt.
  3. MODEL_CALL: ask the configured LLM client (or mock) - if tool calls, execute and loop.
  4. FINALIZE: emit answer, write short-term memory.

The orchestrator emits a sequence of `Event` records so both the streaming and
non-streaming entrypoints share one source of truth.
"""
from __future__ import annotations

import asyncio
import dataclasses
import json
import logging
import time
from typing import Any, AsyncIterator

from .config import get_settings
from .knowledge.qa import build_kb_context
from .llm import ProviderConfig, build_client
from .memory import get_memory
from .mock import mock_answer
from .prompts import build_messages
from .rag import knowledge_rag, memory_rag
from .schemas import InvokeRequest, InvokeResult, ToolCallSummary, TokenUsage
from .tools import TOOL_SCHEMAS, ToolError, ToolExecutor

log = logging.getLogger(__name__)

# Background tasks for fire-and-forget RAG writes. We hold strong refs here so
# CPython's GC can't cancel them while they're awaiting embedding/IO.
_pending_rag_writes: set[asyncio.Task] = set()


@dataclasses.dataclass
class Event:
    name: str  # meta | stage | reasoning | tool_call | tool_result | delta | usage | done | error
    data: dict[str, Any]


def _operation_required_tool(op: str) -> str | None:
    if op in {"CHAT_SUMMARY", "TODO_EXTRACT"}:
        return "get_recent_messages"
    return None


def _resolve_provider_config(provider: dict | None) -> ProviderConfig | None:
    """Decide which provider to use for the current request.

    Priority:
      1. Provider info forwarded from Java (`X-Model-*` headers).
      2. The Python-side fallback `OPENAI_API_KEY` env (covers single-tenant deploys).
      3. None — caller must use the mock pathway.
    """
    if provider and provider.get("apiKey"):
        return ProviderConfig(
            name=provider.get("name") or "openai-compatible",
            base_url=provider.get("baseUrl"),
            model=provider.get("model"),
            api_key=provider["apiKey"],
        )

    settings = get_settings()
    if settings.openai_api_key:
        return ProviderConfig(
            name="openai",
            base_url=settings.openai_base_url,
            model=settings.model_name,
            api_key=settings.openai_api_key,
        )
    return None


async def run_agent(request: InvokeRequest, provider: dict | None = None) -> AsyncIterator[Event]:
    """Drive the agent loop end-to-end.

    `provider` is the per-request provider context forwarded by Java (decoded from
    `X-Model-*` headers). When None, we fall back to env-configured defaults, and
    finally to the mock pathway.
    """
    settings = get_settings()
    # Announce the turn before any retrieval so the client can show progress
    # while the memory / knowledge lookups are still running.
    yield Event("meta", {
        "traceId": request.traceId,
        "sessionId": request.session.sessionId,
        "operationType": request.session.operationType,
    })

    context_started = time.monotonic()
    memory = get_memory()
    short_term = memory.recent(request.actor.userId, request.session.sessionId)
    long_term: list[dict[str, Any]] = []  # filled by Java side via memory_context if needed

    # Module A: semantic recall over older conversations beyond the short-term
    # sliding window. Failures degrade silently to the short-term-only path.
    relevant_history: list[str] = []
    if settings.memory_rag_enabled and request.input.text:
        try:
            relevant_history = await memory_rag.retrieve(
                request.actor.userId,
                request.input.text,
                top_k=settings.memory_rag_top_k,
                provider=provider,
            )
        except Exception:
            log.exception("memory rag retrieve failed; degrading to short-term only")

    # Module B: knowledge-base retrieval. Only runs when the chat request
    # bound this turn to a specific kbId; userId is forwarded as a
    # second-line guardrail so a forged kbId from another tenant returns
    # zero hits even if it slipped past the Java ownership check.
    kb_context: str = ""
    kb_chunks: int | None = None
    linked_kb_id = getattr(request.session, "linkedKbId", None)
    if settings.knowledge_rag_enabled and linked_kb_id and request.input.text:
        try:
            chunks = await knowledge_rag.retrieve(
                kb_id=linked_kb_id,
                query=request.input.text,
                user_id=request.actor.userId,
                provider=provider,
            )
            kb_context = build_kb_context(chunks)
            kb_chunks = len(chunks)
        except Exception:
            log.exception("knowledge rag retrieve failed; degrading without kb context")

    base_messages = build_messages(
        request, short_term, long_term,
        relevant_history=relevant_history,
        kb_context=kb_context,
    )

    yield Event("stage", {
        "phase": "context",
        "shortTerm": len(short_term),
        "recalled": len(relevant_history),
        "ms": int((time.monotonic() - context_started) * 1000),
    })
    if linked_kb_id:
        # chunks is None when retrieval was skipped or failed and the turn degraded.
        yield Event("stage", {"phase": "knowledge", "kbId": linked_kb_id, "chunks": kb_chunks})

    tool_calls_summary: list[ToolCallSummary] = []
    tool_payloads: list[dict[str, Any]] = []
    final_answer: str = ""
    structured: dict[str, Any] = {}

    cfg = _resolve_provider_config(provider)
    finish_reason = "stop"

    if cfg is None:
        # ----- Mock pathway (no provider, no API key) -----
        yield Event("stage", {"phase": "mock"})
        forced = _operation_required_tool(request.session.operationType)
        if forced and request.input.chatId is not None:
            async with ToolExecutor(request.actor.userId, request.traceId) as ex:
                args = {"chat_id": request.input.chatId, "limit": 80}
                yield Event("tool_call", {"toolName": forced, "args": args})
                try:
                    payload, latency = await ex.execute(forced, args)
                    tool_calls_summary.append(ToolCallSummary(toolName=forced, status="SUCCESS", latencyMs=latency))
                    tool_payloads.append({"tool": forced, "payload": payload})
                    yield Event("tool_result", {"toolName": forced, "status": "SUCCESS", "latencyMs": latency})
                except ToolError as exc:
                    tool_calls_summary.append(ToolCallSummary(toolName=forced, status="FAILED", latencyMs=0, error=str(exc)))
                    yield Event("tool_result", {"toolName": forced, "status": "FAILED", "error": str(exc)})

        ans = mock_answer(request, tool_payloads)
        final_answer = ans["answer"]
        structured = {k: v for k, v in ans.items() if k not in {"answer", "_meta"}}
        cut = max(len(final_answer) // 2, 1)
        for chunk in (final_answer[:cut], final_answer[cut:]):
            if chunk:
                yield Event("delta", {"text": chunk})
                await asyncio.sleep(0.01)

        usage = TokenUsage(inputTokens=320, outputTokens=max(8, len(final_answer)),
                           totalTokens=320 + max(8, len(final_answer)))
    else:
        # ----- Real LLM pathway via abstraction layer -----
        # Text deltas are emitted live inside _run_real_loop; the answer here
        # is exactly the concatenation of what was streamed.
        async for ev in _run_real_loop(request, base_messages, cfg):
            if ev.name != "__loop__":
                yield ev
                continue
            usage = ev.data["usage"]
            final_answer = ev.data["answer"]
            tool_calls_summary = ev.data["toolCalls"]
            structured = ev.data["structured"]
            finish_reason = ev.data["finishReason"]

    yield Event("usage", usage.model_dump())
    memory.append(request.actor.userId, request.session.sessionId, "user", request.input.text)
    # Failure/timeout placeholders and partial output must not pollute memory:
    # only a cleanly finished turn is remembered.
    if final_answer and finish_reason == "stop":
        memory.append(request.actor.userId, request.session.sessionId, "assistant", final_answer)

    # Module A: persist this exchange as a long-term semantic memory chunk.
    # Fire-and-forget so the SSE `done` event isn't blocked by the embedding call;
    # errors are already swallowed inside memory_rag.write.
    if settings.memory_rag_enabled and final_answer and request.input.text and finish_reason == "stop":
        task = asyncio.create_task(memory_rag.write(
            request.actor.userId,
            request.session.sessionId,
            request.input.text,
            final_answer,
            trace_id=request.traceId,
            provider=provider,
        ))
        _pending_rag_writes.add(task)
        task.add_done_callback(_pending_rag_writes.discard)

    yield Event("done", {"finishReason": finish_reason})

    yield Event("__final__", {
        "result": InvokeResult(
            answer=final_answer,
            toolCalls=tool_calls_summary,
            usage=usage,
            finishReason=finish_reason,
            todos=structured.get("todos"),
            draft=structured.get("draft"),
            alternatives=structured.get("alternatives"),
        ).model_dump()
    })


async def _run_real_loop(
    request: InvokeRequest,
    base_messages: list[dict[str, Any]],
    cfg: ProviderConfig,
) -> AsyncIterator[Event]:
    """Run the agent loop against a real LLM provider.

    Text deltas are yielded live as the model streams; `stage` / `reasoning` /
    `tool_call` / `tool_result` / `error` events fire the moment they happen.
    The last event is the internal `__loop__` record carrying
    (usage, answer, toolCalls, structured, finishReason) for the caller.
    `answer` is exactly the concatenation of every streamed text delta.
    """
    settings = get_settings()
    client = build_client(cfg)

    messages = list(base_messages)
    usage_in = 0
    usage_out = 0
    streamed_parts: list[str] = []
    finish_reason = "stop"
    structured: dict[str, Any] = {}
    tool_calls_summary: list[ToolCallSummary] = []
    forced = _operation_required_tool(request.session.operationType)

    async with ToolExecutor(request.actor.userId, request.traceId) as ex:
        for iteration in range(min(request.options.maxIterations, settings.max_iterations)):
            tool_choice = "required" if (iteration == 0 and forced) else "auto"
            saw_tool_call = False
            # Reset per iteration: adapters may omit the finish chunk, and a
            # stale "tool_calls" from the previous turn must not leak into the
            # final result (it gates memory writes).
            finish_reason = "stop"
            iteration_text_parts: list[str] = []
            iteration_tool_calls: list = []

            yield Event("stage", {
                "phase": "model",
                "status": "start",
                "iteration": iteration + 1,
                "provider": cfg.name,
                "model": cfg.model,
            })
            call_started = time.monotonic()
            iteration_in = 0
            iteration_out = 0
            try:
                async for chunk in client.complete(
                    messages,
                    tools=TOOL_SCHEMAS,
                    tool_choice=tool_choice,
                    temperature=request.options.temperature,
                    max_tokens=min(request.options.maxOutputTokens, settings.max_output_tokens),
                    timeout_sec=settings.model_timeout_sec,
                ):
                    if chunk.kind == "text" and chunk.text:
                        iteration_text_parts.append(chunk.text)
                        streamed_parts.append(chunk.text)
                        yield Event("delta", {"text": chunk.text})
                    elif chunk.kind == "reasoning" and chunk.text:
                        yield Event("reasoning", {"iteration": iteration + 1, "text": chunk.text})
                    elif chunk.kind == "tool_call" and chunk.tool_call:
                        saw_tool_call = True
                        iteration_tool_calls.append(chunk.tool_call)
                    elif chunk.kind == "usage" and chunk.usage:
                        iteration_in += chunk.usage.input_tokens
                        iteration_out += chunk.usage.output_tokens
                    elif chunk.kind == "error":
                        yield Event("error", {
                            "code": "AGENT_MODEL_50202",
                            "message": (chunk.text or "model call failed")[:200],
                        })
                    elif chunk.kind == "finish":
                        finish_reason = chunk.finish_reason or "stop"
            except Exception as exc:
                log.warning("LLM client failed: %s", exc)
                yield Event("error", {"code": "AGENT_MODEL_50202", "message": str(exc)[:200]})
                finish_reason = "error"
            usage_in += iteration_in
            usage_out += iteration_out
            yield Event("stage", {
                "phase": "model",
                "status": "failed" if finish_reason in ("error", "timeout") else "done",
                "iteration": iteration + 1,
                "ms": int((time.monotonic() - call_started) * 1000),
                "inputTokens": iteration_in,
                "outputTokens": iteration_out,
                "toolCalls": len(iteration_tool_calls),
                "finishReason": finish_reason,
            })

            if finish_reason in ("error", "timeout"):
                break

            if saw_tool_call:
                # Append the assistant turn (with tool_calls) to history for the next iteration
                tool_calls_for_history = []
                for tc in iteration_tool_calls:
                    tool_calls_for_history.append({
                        "id": tc.id,
                        "type": "function",
                        "function": {"name": tc.name, "arguments": json.dumps(tc.arguments)},
                    })
                messages.append({
                    "role": "assistant",
                    "content": "".join(iteration_text_parts),
                    "tool_calls": tool_calls_for_history,
                })

                for tc in iteration_tool_calls:
                    yield Event("tool_call", {"toolName": tc.name, "args": tc.arguments})
                    try:
                        payload, latency = await ex.execute(tc.name, tc.arguments)
                        tool_calls_summary.append(ToolCallSummary(toolName=tc.name, status="SUCCESS", latencyMs=latency))
                        yield Event("tool_result", {"toolName": tc.name, "status": "SUCCESS", "latencyMs": latency})
                        messages.append({
                            "role": "tool",
                            "tool_call_id": tc.id,
                            "name": tc.name,
                            "content": json.dumps(payload, ensure_ascii=False),
                        })
                    except ToolError as exc:
                        tool_calls_summary.append(ToolCallSummary(toolName=tc.name, status="FAILED", latencyMs=0, error=str(exc)))
                        yield Event("tool_result", {"toolName": tc.name, "status": "FAILED", "error": str(exc)})
                        messages.append({
                            "role": "tool",
                            "tool_call_id": tc.id,
                            "name": tc.name,
                            "content": json.dumps({"toolError": exc.code, "message": str(exc)}),
                        })
                continue

            # No tool calls — everything streamed so far is the final answer
            structured = _parse_structured(request.session.operationType, "".join(streamed_parts))
            break
        else:
            finish_reason = "max_iterations"
            if not streamed_parts:
                note = "（已达到最大工具调用轮数，返回当前已知信息）"
                streamed_parts.append(note)
                yield Event("delta", {"text": note})

    usage = TokenUsage(inputTokens=usage_in, outputTokens=usage_out, totalTokens=usage_in + usage_out)
    yield Event("__loop__", {
        "usage": usage,
        "answer": "".join(streamed_parts),
        "toolCalls": tool_calls_summary,
        "structured": structured,
        "finishReason": finish_reason,
    })


def _parse_structured(op: str, answer: str) -> dict[str, Any]:
    """Best-effort extract a JSON block from the model output for Mode B."""
    if op not in {"TODO_EXTRACT", "REPLY_SUGGEST"} or not answer:
        return {}
    text = answer
    if "```" in text:
        for c in text.split("```"):
            stripped = c.strip()
            if stripped.startswith("json"):
                stripped = stripped[4:].strip()
            if stripped.startswith("{") or stripped.startswith("["):
                try:
                    return json.loads(stripped)
                except Exception:
                    continue
    try:
        return json.loads(text)
    except Exception:
        return {}

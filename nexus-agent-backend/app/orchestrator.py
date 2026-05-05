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
from typing import Any, AsyncIterator

from .config import get_settings
from .knowledge.qa import build_kb_context
from .llm import LLMChunk, ProviderConfig, build_client
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
    name: str  # meta | tool_call | tool_result | delta | usage | done | error
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
        except Exception:
            log.exception("knowledge rag retrieve failed; degrading without kb context")

    base_messages = build_messages(
        request, short_term, long_term,
        relevant_history=relevant_history,
        kb_context=kb_context,
    )

    yield Event("meta", {
        "traceId": request.traceId,
        "sessionId": request.session.sessionId,
        "operationType": request.session.operationType,
    })

    tool_calls_summary: list[ToolCallSummary] = []
    tool_payloads: list[dict[str, Any]] = []
    final_answer: str = ""
    structured: dict[str, Any] = {}

    cfg = _resolve_provider_config(provider)

    if cfg is None:
        # ----- Mock pathway (no provider, no API key) -----
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
        usage, final_answer, tool_calls_summary, structured, real_events = await _run_real_loop(
            request, base_messages, cfg
        )
        for ev in real_events:
            yield ev
        if final_answer:
            yield Event("delta", {"text": final_answer})

    yield Event("usage", usage.model_dump())
    memory.append(request.actor.userId, request.session.sessionId, "user", request.input.text)
    if final_answer:
        memory.append(request.actor.userId, request.session.sessionId, "assistant", final_answer)

    # Module A: persist this exchange as a long-term semantic memory chunk.
    # Fire-and-forget so the SSE `done` event isn't blocked by the embedding call;
    # errors are already swallowed inside memory_rag.write.
    if settings.memory_rag_enabled and final_answer and request.input.text:
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

    yield Event("done", {"finishReason": "stop"})

    yield Event("__final__", {
        "result": InvokeResult(
            answer=final_answer,
            toolCalls=tool_calls_summary,
            usage=usage,
            finishReason="stop",
            todos=structured.get("todos"),
            draft=structured.get("draft"),
            alternatives=structured.get("alternatives"),
        ).model_dump()
    })


async def _run_real_loop(
    request: InvokeRequest,
    base_messages: list[dict[str, Any]],
    cfg: ProviderConfig,
) -> tuple[TokenUsage, str, list[ToolCallSummary], dict[str, Any], list[Event]]:
    """Run the agent loop against a real LLM provider.

    Returns:
      (usage, final_answer, tool_call_summaries, structured_payload, side_events)
      where `side_events` are tool_call/tool_result events to forward to the SSE stream
      *before* the final delta.
    """
    settings = get_settings()
    client = build_client(cfg)

    messages = list(base_messages)
    usage_in = 0
    usage_out = 0
    final_answer = ""
    structured: dict[str, Any] = {}
    tool_calls_summary: list[ToolCallSummary] = []
    side_events: list[Event] = []
    forced = _operation_required_tool(request.session.operationType)

    async with ToolExecutor(request.actor.userId, request.traceId) as ex:
        for iteration in range(min(request.options.maxIterations, settings.max_iterations)):
            tool_choice = "required" if (iteration == 0 and forced) else "auto"
            saw_tool_call = False
            iteration_text_parts: list[str] = []

            chunks: list[LLMChunk] = []
            try:
                async for chunk in client.complete(
                    messages,
                    tools=TOOL_SCHEMAS,
                    tool_choice=tool_choice,
                    temperature=request.options.temperature,
                    max_tokens=min(request.options.maxOutputTokens, settings.max_output_tokens),
                    timeout_sec=settings.model_timeout_sec,
                ):
                    chunks.append(chunk)
            except Exception as exc:
                log.warning("LLM client failed: %s", exc)
                final_answer = f"（模型调用失败：{exc}）"
                break

            for chunk in chunks:
                if chunk.kind == "text" and chunk.text:
                    iteration_text_parts.append(chunk.text)
                elif chunk.kind == "tool_call" and chunk.tool_call:
                    saw_tool_call = True
                elif chunk.kind == "usage" and chunk.usage:
                    usage_in += chunk.usage.input_tokens
                    usage_out += chunk.usage.output_tokens

            if saw_tool_call:
                # Append the assistant turn (with tool_calls) to history for the next iteration
                tool_calls_for_history = []
                for chunk in chunks:
                    if chunk.kind != "tool_call" or not chunk.tool_call:
                        continue
                    tc = chunk.tool_call
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

                for chunk in chunks:
                    if chunk.kind != "tool_call" or not chunk.tool_call:
                        continue
                    tc = chunk.tool_call
                    side_events.append(Event("tool_call", {"toolName": tc.name, "args": tc.arguments}))
                    try:
                        payload, latency = await ex.execute(tc.name, tc.arguments)
                        tool_calls_summary.append(ToolCallSummary(toolName=tc.name, status="SUCCESS", latencyMs=latency))
                        side_events.append(Event("tool_result", {"toolName": tc.name, "status": "SUCCESS", "latencyMs": latency}))
                        messages.append({
                            "role": "tool",
                            "tool_call_id": tc.id,
                            "name": tc.name,
                            "content": json.dumps(payload, ensure_ascii=False),
                        })
                    except ToolError as exc:
                        tool_calls_summary.append(ToolCallSummary(toolName=tc.name, status="FAILED", latencyMs=0, error=str(exc)))
                        side_events.append(Event("tool_result", {"toolName": tc.name, "status": "FAILED", "error": str(exc)}))
                        messages.append({
                            "role": "tool",
                            "tool_call_id": tc.id,
                            "name": tc.name,
                            "content": json.dumps({"toolError": exc.code, "message": str(exc)}),
                        })
                continue

            # No tool calls - this is the final answer
            final_answer = "".join(iteration_text_parts)
            structured = _parse_structured(request.session.operationType, final_answer)
            break
        else:
            if not final_answer:
                final_answer = "（已达到最大工具调用轮数，返回当前已知信息）"

    usage = TokenUsage(inputTokens=usage_in, outputTokens=usage_out, totalTokens=usage_in + usage_out)
    return usage, final_answer, tool_calls_summary, structured, side_events


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

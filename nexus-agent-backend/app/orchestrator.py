"""Agent orchestrator: one LangGraph StateGraph drives every turn.

    prepare_context -> reason <-> tools -> validate_output -> settle
                    \\-> mock (no provider configured) ----^

Tools are discovered and called over MCP (see mcp_tools). Events are streamed
live from inside the nodes. Each step is checkpointed, so a
turn that dies in a model call resumes from that call instead of redoing the
context lookups and tool calls before it. Credentials and SDK clients travel in
the per-request runtime context and are never part of the checkpointed state.
"""
from __future__ import annotations

import asyncio
import contextlib
import dataclasses
import json
import logging
import operator
import time
import uuid
from typing import Annotated, Any, AsyncIterator, Awaitable, Callable, TypedDict

from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime
from opentelemetry import trace
from pydantic import BaseModel, ValidationError

from .artifacts import ArtifactError, extract_artifacts
from .checkpoint import open_run_store, thread_id_for
from .collaboration import run_collaboration
from .config import SUPPORTED_ENGINE, get_settings
from .knowledge.qa import build_kb_context
from .llm import LLMClient, ProviderConfig, build_client
from .memory import ShortTermMemory, get_memory
from .mock import mock_answer
from .prompts import build_messages
from .rag import knowledge_rag, memory_rag
from .web_search import WebSearchError, search_web
from .schemas import (
    InvokeRequest,
    InvokeResult,
    ReplySuggestOutput,
    ResumeRequest,
    TodoExtractOutput,
    TokenUsage,
    ToolCallSummary,
)
from .mcp_tools import ToolError, ToolExecutor, ToolTransportError
from .tracing import tracer

log = logging.getLogger(__name__)

_TRACE_CONTENT_LIMIT = 4000

# Background tasks for fire-and-forget RAG writes. We hold strong refs here so
# CPython's GC can't cancel them while they're awaiting embedding/IO.
_pending_rag_writes: set[asyncio.Task] = set()

# Runs executing in this process. A run has exactly one executor at a time.
_active_runs: set[str] = set()

_MAX_ITERATIONS_NOTE = "（已达到最大工具调用轮数，返回当前已知信息）"

_OUTPUT_MODELS: dict[str, type[BaseModel]] = {
    "TODO_EXTRACT": TodoExtractOutput,
    "REPLY_SUGGEST": ReplySuggestOutput,
}
_OUTPUT_SHAPES: dict[str, str] = {
    "TODO_EXTRACT": '{"todos":[{"owner":"...","task":"...","dueAt":null,"confidence":0.8}]}',
    "REPLY_SUGGEST": '{"draft":"...","alternatives":["...","..."]}',
}


@dataclasses.dataclass
class Event:
    name: str  # meta | stage | reasoning | tool_call | tool_result | delta | usage | done | error
    data: dict[str, Any]


class AgentState(TypedDict, total=False):
    """Checkpointed between steps, so it holds plain data only — no credentials."""
    request: dict[str, Any]
    messages: Annotated[list[dict[str, Any]], operator.add]
    pending_tool_calls: list[dict[str, Any]]
    tool_calls: Annotated[list[dict[str, Any]], operator.add]
    model_calls: int
    answer: str
    last_text: str
    usage_in: int
    usage_out: int
    finish_reason: str
    structured: dict[str, Any]
    output_error: str
    repairs: int
    repairing: bool
    sources: list[dict[str, Any]]
    artifacts: list[dict[str, Any]]
    usage_reported: bool


class _RunFailed(Exception):
    """Ends the run with its checkpoint left at the step that failed."""

    def __init__(self, finish_reason: str, usage_in: int = 0, usage_out: int = 0) -> None:
        super().__init__(finish_reason)
        self.finish_reason = finish_reason
        self.usage_in = usage_in
        self.usage_out = usage_out


class _ConsumerStalled(Exception):
    pass


class _Outbox:
    """Pauses the producing node once the consumer falls too far behind, instead
    of buffering without bound or dropping text."""

    def __init__(self, limit: int, stall_timeout_sec: float) -> None:
        self._limit = max(1, limit)
        self._stall_timeout_sec = stall_timeout_sec
        self._unread = 0
        self._caught_up = asyncio.Event()
        self.producers: set[asyncio.Task] = set()

    def cancel(self) -> None:
        for task in self.producers:
            if not task.done():
                task.cancel()
        self._caught_up.set()

    async def emit(self, name: str, data: dict[str, Any]) -> None:
        get_stream_writer()({"name": name, "data": data})
        self._unread += 1
        if self._unread >= self._limit:
            self._caught_up.clear()
            try:
                await asyncio.wait_for(self._caught_up.wait(), self._stall_timeout_sec)
            except asyncio.TimeoutError:
                raise _ConsumerStalled(
                    f"stream consumer read nothing for {self._stall_timeout_sec}s") from None

    def delivered(self) -> None:
        self._unread -= 1
        if self._unread < self._limit:
            self._caught_up.set()


@dataclasses.dataclass
class _RunContext:
    """Per-request, never checkpointed."""
    run_id: str
    request: InvokeRequest
    provider: dict | None
    cfg: ProviderConfig | None
    client: LLMClient | None
    tools: ToolExecutor
    memory: ShortTermMemory
    outbox: _Outbox
    max_model_calls: int
    span_context: Any
    # Tools offered to the model, read once per execution of the run.
    tool_schemas: list[dict[str, Any]] | None = None
    # When this run started, so every public event can say how far into it it happened.
    started: float = dataclasses.field(default_factory=time.monotonic)


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
    if provider is not None:
        name = (provider.get("name") or "").strip()
        model = (provider.get("model") or "").strip()
        key = provider.get("apiKey") or ("ollama" if name.lower() == "ollama" else None)
        if not name or not model or not key:
            raise ValueError("Selected channel is missing a provider, model or credential")
        return ProviderConfig(name=name, base_url=provider.get("baseUrl"), model=model, api_key=key)

    settings = get_settings()
    if settings.openai_api_key:
        return ProviderConfig(
            name="openai",
            base_url=settings.openai_base_url,
            model=settings.model_name,
            api_key=settings.openai_api_key,
        )
    return None


def _public(event: Event, sequence: int, *, run_id: str | None = None,
            started: float | None = None) -> Event:
    """Attach the run identity every public event carries.

    `sequence` orders events inside one run; `at` is how far into the run it
    happened, measured from the run's own start.
    """
    data = dict(event.data)
    data.setdefault("sequence", sequence)
    if run_id is not None:
        data.setdefault("runId", run_id)
    if started is not None:
        data.setdefault("at", int((time.monotonic() - started) * 1000))
    return Event(event.name, data)


def _public_message(exc: BaseException, cfg: ProviderConfig | None) -> str:
    """An error summary that is safe to show: bounded, and never carrying the provider key."""
    message = str(exc) or type(exc).__name__
    if cfg is not None and cfg.api_key:
        message = message.replace(cfg.api_key, "***")
    return message[:200]


def _parse_structured(op: str, text: str) -> dict[str, Any]:
    """Extract and validate the JSON a structured operation must return.

    Raises ValueError with a reason short enough to hand back to the model.
    """
    data: Any = None
    found = False
    for segment in [*text.split("```"), text]:
        segment = segment.strip()
        if segment.startswith("json"):
            segment = segment[4:].strip()
        if not segment.startswith(("{", "[")):
            continue
        try:
            data = json.loads(segment)
        except ValueError:
            continue
        found = True
        break
    if not found:
        raise ValueError("输出中没有合法的 JSON")

    if op == "TODO_EXTRACT" and isinstance(data, list):
        data = {"todos": data}
    try:
        return _OUTPUT_MODELS[op].model_validate(data).model_dump()
    except ValidationError as exc:
        problems = "; ".join(
            f"{'.'.join(str(part) for part in error['loc']) or '<root>'}: {error['msg']}"
            for error in exc.errors()[:3]
        )
        raise ValueError(f"JSON 结构不符合要求（{problems}）") from exc


# ----- graph nodes -----


async def _prepare_context(state: AgentState, ctx: _RunContext) -> dict[str, Any]:
    settings = get_settings()
    request = ctx.request
    user_id = request.actor.userId
    started = time.monotonic()

    short_term = await ctx.memory.recent(user_id, request.session.sessionId)

    # Module A: semantic recall over older conversations beyond the short-term
    # sliding window. Failures degrade silently to the short-term-only path.
    relevant_history: list[str] = []
    if settings.memory_rag_enabled and request.input.text:
        with tracer.start_as_current_span("rag.retrieve", attributes={"nexus.rag.kind": "memory"}) as span:
            try:
                relevant_history = await memory_rag.retrieve(
                    user_id,
                    request.input.text,
                    top_k=settings.memory_rag_top_k,
                    provider=ctx.provider,
                )
            except Exception:
                log.exception("memory rag retrieve failed; degrading to short-term only")
            span.set_attribute("nexus.rag.hits", len(relevant_history))

    # Module B: knowledge-base retrieval. Only runs when the chat request
    # bound this turn to a specific kbId; userId is forwarded as a
    # second-line guardrail so a forged kbId from another tenant returns
    # zero hits even if it slipped past the Java ownership check.
    kb_context = ""
    kb_chunks: int | None = None
    linked_kb_id = request.session.linkedKbId
    if settings.knowledge_rag_enabled and linked_kb_id and request.input.text:
        with tracer.start_as_current_span("rag.retrieve", attributes={"nexus.rag.kind": "knowledge"}) as span:
            try:
                chunks = await knowledge_rag.retrieve(
                    kb_id=linked_kb_id,
                    query=request.input.text,
                    user_id=user_id,
                    provider=ctx.provider,
                )
                kb_context = build_kb_context(chunks)
                kb_chunks = len(chunks)
            except Exception:
                log.exception("knowledge rag retrieve failed; degrading without kb context")
            span.set_attribute("nexus.rag.hits", kb_chunks or 0)

    # Uploaded files for this turn: the model reads their text, and the client can cite
    # each one back as A1, A2… in the order they were attached.
    attachments = [a.model_dump() for a in request.input.attachments]
    sources: list[dict[str, Any]] = [
        {"id": f"A{index}", "kind": "attachment", "title": item["fileName"],
         "attachmentId": item["id"], "excerpt": (item.get("content") or "")[:280]}
        for index, item in enumerate(attachments, start=1)
    ]
    messages = build_messages(
        request, short_term, [],
        relevant_history=relevant_history,
        kb_context=kb_context,
        attachments=attachments,
    )

    await ctx.outbox.emit("stage", {
        "phase": "context",
        "shortTerm": len(short_term),
        "recalled": len(relevant_history),
        "ms": int((time.monotonic() - started) * 1000),
    })
    if linked_kb_id:
        # chunks is None when retrieval was skipped or failed and the turn degraded.
        await ctx.outbox.emit("stage", {"phase": "knowledge", "kbId": linked_kb_id, "chunks": kb_chunks})
    if attachments:
        await ctx.outbox.emit("stage", {"phase": "attachments", "count": len(attachments)})
    if sources:
        await ctx.outbox.emit("sources", {"items": sources})

    # Recorded here, after the history read, so the turn does not see itself.
    await ctx.memory.append(user_id, request.session.sessionId, "user", request.input.text, run_id=ctx.run_id)
    return {"messages": messages, "sources": sources}


async def _tool_service_failed(ctx: _RunContext, exc: Exception) -> _RunFailed:
    log.warning("tool service failed for run %s: %s", ctx.run_id, exc)
    await ctx.outbox.emit("error", {"code": "AGENT_TOOL_50301", "message": str(exc)[:200]})
    return _RunFailed("error")


async def _offered_tools(ctx: _RunContext, required: str | None) -> list[dict[str, Any]]:
    """The tools to offer the model. A turn that does not depend on a tool goes on without them."""
    if ctx.tool_schemas is None:
        try:
            ctx.tool_schemas = await ctx.tools.schemas()
        except ToolTransportError as exc:
            if required:
                raise await _tool_service_failed(ctx, exc) from exc
            log.warning("tools unavailable for run %s: %s", ctx.run_id, exc)
            await ctx.outbox.emit("stage", {"phase": "tools", "status": "unavailable", "message": str(exc)[:200]})
            ctx.tool_schemas = []
    if required and required not in {schema["function"]["name"] for schema in ctx.tool_schemas}:
        raise await _tool_service_failed(ctx, ToolTransportError(f"required tool {required} is not available"))
    return ctx.tool_schemas


async def _reason(state: AgentState, ctx: _RunContext) -> dict[str, Any]:
    settings = get_settings()
    request = ctx.request
    emit = ctx.outbox.emit
    iteration = state.get("model_calls", 0) + 1

    if ctx.client is None or ctx.cfg is None:
        await emit("error", {"code": "AGENT_MODEL_50202", "message": "no model provider configured for this run"})
        raise _RunFailed("error")

    forced = _operation_required_tool(request.session.operationType)
    offered = await _offered_tools(ctx, forced if iteration == 1 else None)
    text_parts: list[str] = []
    tool_calls: list[dict[str, Any]] = []
    usage_in = 0
    usage_out = 0
    finish_reason = "stop"

    node_id = f"model-{iteration}"
    # What this call hangs off: the context step, then the tool calls it was given.
    parent_ids = ["context"] if iteration == 1 else [
        f"tool-{call['id']}" for call in (state.get("pending_tool_calls") or [])] or ["context"]
    await emit("stage", {
        "phase": "model",
        "status": "start",
        "iteration": iteration,
        "nodeId": node_id,
        "parentIds": parent_ids,
        "provider": ctx.cfg.name,
        "model": ctx.cfg.model,
    })
    started = time.monotonic()
    with tracer.start_as_current_span("llm.call", attributes={
        "gen_ai.system": ctx.cfg.name or "",
        "gen_ai.request.model": ctx.cfg.model or "",
        "nexus.iteration": iteration,
    }) as span:
        try:
            completion = ctx.client.complete(
                state["messages"],
                tools=offered,
                tool_choice="required" if (iteration == 1 and forced) else "auto",
                temperature=request.options.temperature,
                max_tokens=min(request.options.maxOutputTokens, settings.max_output_tokens),
                timeout_sec=settings.model_timeout_sec,
            )
            # Closing the stream promptly is what releases the provider connection
            # when this node is cancelled or the consumer stalls.
            async with contextlib.aclosing(completion) as chunks:
                async for chunk in chunks:
                    if chunk.kind == "text" and chunk.text:
                        text_parts.append(chunk.text)
                        await emit("delta", {"text": chunk.text})
                    elif chunk.kind == "reasoning" and chunk.text:
                        await emit("reasoning", {"iteration": iteration, "nodeId": node_id, "text": chunk.text})
                    elif chunk.kind == "tool_call" and chunk.tool_call:
                        tool_calls.append(dataclasses.asdict(chunk.tool_call))
                    elif chunk.kind == "usage" and chunk.usage:
                        usage_in += chunk.usage.input_tokens
                        usage_out += chunk.usage.output_tokens
                    elif chunk.kind == "error":
                        finish_reason = "timeout" if finish_reason == "timeout" else "error"
                        await emit("error", {
                            "code": "AGENT_MODEL_50202",
                            "message": (chunk.text or "model call failed")[:200],
                            **(chunk.error.as_payload() if chunk.error else {}),
                        })
                    elif chunk.kind == "finish":
                        # A later clean finish cannot erase failure; keep timeout specific.
                        if finish_reason not in ("error", "timeout") or chunk.finish_reason == "timeout":
                            finish_reason = chunk.finish_reason or "stop"
        except _ConsumerStalled:
            raise
        except Exception as exc:
            log.warning("LLM client failed: %s", _public_message(exc, ctx.cfg))
            await emit("error", {"code": "AGENT_MODEL_50202", "message": _public_message(exc, ctx.cfg)})
            finish_reason = "timeout" if finish_reason == "timeout" else "error"

        failed = finish_reason in ("error", "timeout")
        span.set_attribute("gen_ai.usage.input_tokens", usage_in)
        span.set_attribute("gen_ai.usage.output_tokens", usage_out)
        span.set_attribute("gen_ai.response.finish_reasons", [finish_reason])
        if settings.trace_capture_content:
            span.set_attribute(
                "nexus.llm.input", json.dumps(state["messages"], ensure_ascii=False)[:_TRACE_CONTENT_LIMIT])
            span.set_attribute("nexus.llm.output", "".join(text_parts)[:_TRACE_CONTENT_LIMIT])
        if failed:
            span.set_status(trace.StatusCode.ERROR, finish_reason)

    await emit("stage", {
        "phase": "model",
        "status": "failed" if failed else "done",
        "iteration": iteration,
        "nodeId": node_id,
        "parentIds": parent_ids,
        "ms": int((time.monotonic() - started) * 1000),
        "inputTokens": usage_in,
        "outputTokens": usage_out,
        "toolCalls": len(tool_calls),
        "finishReason": finish_reason,
    })
    if failed:
        raise _RunFailed(finish_reason, usage_in, usage_out)

    if finish_reason not in ("stop", "tool_calls"):
        tool_calls = []

    text = "".join(text_parts)
    assistant: dict[str, Any] = {"role": "assistant", "content": text}
    if tool_calls:
        assistant["tool_calls"] = [{
            "id": call["id"],
            "type": "function",
            "function": {"name": call["name"], "arguments": json.dumps(call["arguments"])},
            # Only Gemini sets one, and only Gemini reads it back.
            **({"signature": call["signature"]} if call.get("signature") else {}),
        } for call in tool_calls]
    return {
        "messages": [assistant],
        "pending_tool_calls": tool_calls,
        "model_calls": iteration,
        "answer": state.get("answer", "") + text,
        "last_text": text,
        "usage_in": state.get("usage_in", 0) + usage_in,
        "usage_out": state.get("usage_out", 0) + usage_out,
        "usage_reported": True if (usage_in or usage_out) else state.get("usage_reported", False),
        "finish_reason": "tool_calls" if tool_calls else finish_reason,
        "repairing": False,
    }


async def _run_tool(ctx: _RunContext, call: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    """Execute one tool call; returns (summary, tool message for the model)."""
    name = call["name"]
    node_id = f"tool-{call['id']}"
    with tracer.start_as_current_span("tool.call", attributes={"gen_ai.tool.name": name}) as span:
        try:
            payload, latency = await ctx.tools.execute(name, call["arguments"])
        except ToolError as exc:
            span.set_attribute("nexus.tool.status", "FAILED")
            span.set_status(trace.StatusCode.ERROR, exc.code)
            await ctx.outbox.emit("tool_result", {
                "toolName": name, "status": "FAILED", "error": str(exc), "callId": call["id"], "nodeId": node_id})
            summary = ToolCallSummary(toolName=name, status="FAILED", latencyMs=0, error=str(exc))
            content = json.dumps({"toolError": exc.code, "message": str(exc)}, ensure_ascii=False)
        except ToolTransportError as exc:
            # Close the call in the client's view; the node itself fails and stays resumable.
            span.set_attribute("nexus.tool.status", "UNAVAILABLE")
            span.set_status(trace.StatusCode.ERROR, "transport")
            await ctx.outbox.emit("tool_result", {
                "toolName": name, "status": "FAILED", "error": "tool service unavailable", "callId": call["id"]})
            raise
        else:
            span.set_attribute("nexus.tool.status", "SUCCESS")
            await ctx.outbox.emit("tool_result", {
                "toolName": name, "status": "SUCCESS", "latencyMs": latency, "callId": call["id"], "nodeId": node_id})
            summary = ToolCallSummary(toolName=name, status="SUCCESS", latencyMs=latency)
            content = json.dumps(payload, ensure_ascii=False)
    message = {"role": "tool", "tool_call_id": call["id"], "name": name, "content": content}
    return summary.model_dump(), message


async def _collaborate(state: AgentState, ctx: _RunContext) -> dict[str, Any]:
    """Opt-in: three analysts run concurrently, then one synthesis becomes the answer."""
    if ctx.client is None or ctx.cfg is None:
        await ctx.outbox.emit("error", {"code": "AGENT_MODEL_50202", "message": "no model provider configured for this run"})
        raise _RunFailed("error")
    await _offered_tools(ctx, None)  # keeps the tool-service status visible even without tool use
    outcome = await run_collaboration(
        state["messages"], ctx.client, ctx.tools, ctx.outbox.emit,
        run_id=ctx.run_id, options=ctx.request.options, node_id="collaboration",
    )
    update: dict[str, Any] = {
        "usage_in": state.get("usage_in", 0) + outcome["usage_in"],
        "usage_out": state.get("usage_out", 0) + outcome["usage_out"],
        "usage_reported": outcome["usage_in"] + outcome["usage_out"] > 0 or state.get("usage_reported", False),
        "model_calls": state.get("model_calls", 0) + 1,
    }
    if outcome.get("failed") or not outcome.get("answer"):
        raise _RunFailed("error", outcome["usage_in"], outcome["usage_out"])
    update.update({
        "messages": outcome["messages"],
        "answer": state.get("answer", "") + outcome["answer"],
        "last_text": outcome["answer"],
        "finish_reason": "stop",
        "repairing": False,
    })
    return update


async def _tools(state: AgentState, ctx: _RunContext) -> dict[str, Any]:
    settings = get_settings()
    calls = state.get("pending_tool_calls") or []
    for call in calls:
        await ctx.outbox.emit("tool_call", {
            "toolName": call["name"], "args": call["arguments"], "callId": call["id"],
            "nodeId": f"tool-{call['id']}"})

    # Every tool is a read, so calls from one model turn are independent.
    slots = asyncio.Semaphore(
        max(1, settings.tool_max_concurrency) if settings.enable_parallel_tool_calls else 1)

    async def run(call: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
        async with slots:
            return await _run_tool(ctx, call)

    tasks = [asyncio.create_task(run(call)) for call in calls]
    try:
        results = await asyncio.gather(*tasks)
    except ToolTransportError as exc:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        raise await _tool_service_failed(ctx, exc) from exc
    except BaseException:
        for task in tasks:
            task.cancel()
        raise

    update: dict[str, Any] = {
        # History keeps the order the model asked in, whatever order they finished in.
        "messages": [message for _, message in results],
        "tool_calls": [summary for summary, _ in results],
        "pending_tool_calls": [],
    }
    if state.get("model_calls", 0) >= ctx.max_model_calls:
        update["finish_reason"] = "max_iterations"
        if not state.get("answer"):
            await ctx.outbox.emit("delta", {"text": _MAX_ITERATIONS_NOTE})
            update["answer"] = _MAX_ITERATIONS_NOTE
    return update


async def _mock(state: AgentState, ctx: _RunContext) -> dict[str, Any]:
    """Deterministic answers when no provider is configured."""
    request = ctx.request
    emit = ctx.outbox.emit
    await emit("stage", {"phase": "mock"})

    summaries: list[dict[str, Any]] = []
    payloads: list[dict[str, Any]] = []
    forced = _operation_required_tool(request.session.operationType)
    if forced and request.input.chatId is not None:
        call = {"id": "mock_1", "name": forced, "arguments": {"chat_id": request.input.chatId, "limit": 80}}
        await emit("tool_call", {"toolName": forced, "args": call["arguments"], "callId": call["id"]})
        try:
            summary, message = await _run_tool(ctx, call)
        except ToolTransportError as exc:
            raise await _tool_service_failed(ctx, exc) from exc
        summaries.append(summary)
        if summary["status"] == "SUCCESS":
            payloads.append({"tool": forced, "payload": json.loads(message["content"])})

    answer = mock_answer(request, payloads)["answer"]
    cut = max(len(answer) // 2, 1)
    for part in (answer[:cut], answer[cut:]):
        if part:
            await emit("delta", {"text": part})
            await asyncio.sleep(0.01)

    return {
        "answer": answer,
        "last_text": answer,
        "tool_calls": summaries,
        "usage_in": 320,
        "usage_out": max(8, len(answer)),
        "finish_reason": "stop",
    }


async def _validate_output(state: AgentState, ctx: _RunContext) -> dict[str, Any]:
    op = ctx.request.session.operationType
    if op not in _OUTPUT_MODELS or state.get("finish_reason") != "stop":
        return {}
    try:
        return {"structured": _parse_structured(op, state.get("last_text", ""))}
    except ValueError as exc:
        reason = str(exc)

    can_repair = (
        ctx.client is not None
        and not state.get("repairs")
        and state.get("model_calls", 0) < ctx.max_model_calls
    )
    if can_repair:
        # The rejected output is withdrawn: the client redraws from what stood
        # before it, and it never reaches memory.
        answer = state.get("answer", "")
        rejected = state.get("last_text", "")
        committed = answer[:len(answer) - len(rejected)] if rejected and answer.endswith(rejected) else answer
        await ctx.outbox.emit("delta", {"text": committed, "replayed": True})
        return {
            "answer": committed,
            "last_text": "",
            "messages": [{
                "role": "user",
                "content": (
                    f"上一条输出未通过结构校验：{reason}。"
                    f"请只输出修正后的 JSON，形如 {_OUTPUT_SHAPES[op]}，不要任何解释。"
                ),
            }],
            "repairs": 1,
            "repairing": True,
        }
    return {"finish_reason": "invalid_output", "output_error": reason}


async def _emit_artifacts(state: AgentState, ctx: _RunContext) -> list[dict[str, Any]]:
    """Complete fenced blocks in the answer, tied to the run and to the sources it used."""
    request = ctx.request
    answer = state.get("answer", "")
    if not answer or state.get("finish_reason") != "stop":
        return []
    sources = state.get("sources") or []
    call_ids = [call.get("callId") for call in (state.get("tool_calls") or []) if call.get("callId")]
    found = extract_artifacts(
        answer,
        run_id=ctx.request.runId or ctx.run_id.rsplit(":", 1)[-1],
        agent_id="assistant",
        node_id=f"model-{state.get('model_calls', 1)}",
        source_ids=[item["id"] for item in sources],
        call_ids=call_ids,
    )
    for artifact in found:
        await ctx.outbox.emit("artifact", {"artifact": artifact})
    return found


def _run_graph(state: AgentState, ctx: _RunContext, artifacts: list[dict[str, Any]]) -> dict[str, Any]:
    """The real execution graph of this turn, as an artifact the client can save."""
    calls = state.get("model_calls", 0)
    nodes = [{"id": "context", "kind": "context"}]
    for iteration in range(1, calls + 1):
        nodes.append({"id": f"model-{iteration}", "kind": "model", "iteration": iteration})
    for call in state.get("tool_calls") or []:
        if call.get("callId"):
            nodes.append({"id": f"tool-{call['callId']}", "kind": "tool", "toolName": call.get("toolName")})
    edges = []
    for index in range(1, calls + 1):
        parents = ["context"] if index == 1 else [f"model-{index - 1}"]
        edges.extend({"from": parent, "to": f"model-{index}"} for parent in parents)
    content = json.dumps({"runId": ctx.run_id, "nodes": nodes, "edges": edges,
                          "artifacts": [a["id"] for a in artifacts]}, ensure_ascii=False, indent=2)
    import hashlib as _hashlib
    return {
        "id": f"graph-{ctx.run_id.rsplit(':', 1)[-1]}",
        "name": "execution-graph.json",
        "language": "json",
        "kind": "graph",
        "content": content,
        "sizeBytes": len(content.encode("utf-8")),
        "sha256": _hashlib.sha256(content.encode("utf-8")).hexdigest(),
        "runId": ctx.request.runId or ctx.run_id.rsplit(":", 1)[-1],
        "agentId": "assistant",
        "nodeId": f"model-{calls}",
        "sourceIds": [item["id"] for item in (state.get("sources") or [])],
        "callIds": [call.get("callId") for call in (state.get("tool_calls") or []) if call.get("callId")],
    }


async def _settle(state: AgentState, ctx: _RunContext) -> dict[str, Any]:
    """Failure/timeout placeholders and partial output must not pollute memory:
    only a cleanly finished turn is remembered."""
    settings = get_settings()
    request = ctx.request
    answer = state.get("answer", "")
    if not answer or state.get("finish_reason") != "stop":
        return {}
    artifacts = await _emit_artifacts(state, ctx)
    # The execution graph of this turn is real whether or not the answer held a fenced
    # block, so it is always reported; it names the artifacts it produced.
    graph = _run_graph(state, ctx, artifacts)
    await ctx.outbox.emit("artifact", {"artifact": graph})
    artifacts = [*artifacts, graph]
    await ctx.memory.append(request.actor.userId, request.session.sessionId, "assistant", answer, run_id=ctx.run_id)

    # Module A: persist this exchange as a long-term semantic memory chunk.
    # Fire-and-forget so the SSE `done` event isn't blocked by the embedding call;
    # errors are already swallowed inside memory_rag.write.
    if settings.memory_rag_enabled and request.input.text:
        task = asyncio.create_task(memory_rag.write(
            request.actor.userId,
            request.session.sessionId,
            request.input.text,
            answer,
            trace_id=request.traceId,
            run_id=ctx.run_id,
            provider=ctx.provider,
        ))
        _pending_rag_writes.add(task)
        task.add_done_callback(_pending_rag_writes.discard)
    return {"artifacts": artifacts}


def _node(name: str, fn: Callable[[AgentState, _RunContext], Awaitable[dict[str, Any]]]):
    async def run(state: AgentState, runtime: Runtime[_RunContext]) -> dict[str, Any]:
        ctx = runtime.context
        ctx.outbox.producers.add(asyncio.current_task())
        with tracer.start_as_current_span(f"graph.node.{name}", context=ctx.span_context):
            return await fn(state, ctx)
    return run


def _after_prepare(state: AgentState, runtime: Runtime[_RunContext]) -> str:
    ctx = runtime.context
    if ctx.cfg is None:
        return "mock"
    # Collaboration replaces the ordinary single-assistant turn; it is only for plain chat.
    if ctx.request.options.collaborationEnabled and ctx.request.session.operationType == "ASSISTANT_CHAT":
        return "collaborate"
    return "reason"


def _after_reason(state: AgentState) -> str:
    return "tools" if state.get("pending_tool_calls") else "validate_output"


def _after_tools(state: AgentState) -> str:
    return "settle" if state.get("finish_reason") == "max_iterations" else "reason"


def _after_validate(state: AgentState) -> str:
    return "reason" if state.get("repairing") else "settle"


_builder: StateGraph | None = None


def get_graph_builder() -> StateGraph:
    """Built once; compiled per run because the checkpointer is per-connection."""
    global _builder
    if _builder is None:
        g = StateGraph(AgentState, context_schema=_RunContext)
        g.add_node("prepare_context", _node("prepare_context", _prepare_context))
        g.add_node("reason", _node("reason", _reason))
        g.add_node("tools", _node("tools", _tools))
        g.add_node("mock", _node("mock", _mock))
        g.add_node("collaborate", _node("collaborate", _collaborate))
        g.add_node("validate_output", _node("validate_output", _validate_output))
        g.add_node("settle", _node("settle", _settle))
        g.add_edge(START, "prepare_context")
        g.add_conditional_edges("prepare_context", _after_prepare, ["mock", "collaborate", "reason"])
        g.add_edge("collaborate", "validate_output")
        g.add_conditional_edges("reason", _after_reason, ["tools", "validate_output"])
        g.add_conditional_edges("tools", _after_tools, ["reason", "settle"])
        g.add_edge("mock", "validate_output")
        g.add_conditional_edges("validate_output", _after_validate, ["reason", "settle"])
        g.add_edge("settle", END)
        _builder = g
    return _builder


# ----- public entry points -----


def run_agent(
    request: InvokeRequest,
    provider: dict | None = None,
    *,
    keep_failed: bool = True,
) -> AsyncIterator[Event]:
    """Run one turn.

    `provider` is the per-request provider context forwarded by Java (decoded from
    `X-Model-*` headers). When None, we fall back to env-configured defaults, and
    finally to the mock pathway. With `keep_failed=False` a failed turn leaves no
    checkpoint behind.
    """
    return _drive(
        run_id=request.runId or uuid.uuid4().hex,
        actor_user_id=request.actor.userId,
        session_id=request.session.sessionId,
        trace_id=request.traceId,
        provider=provider,
        request=request,
        keep_failed=keep_failed,
    )


def resume_agent(resume: ResumeRequest, provider: dict | None = None) -> AsyncIterator[Event]:
    """Continue an unfinished run from its last completed step.

    The caller re-authenticates and re-supplies credentials: nothing sensitive
    was stored with the run.
    """
    return _drive(
        run_id=resume.runId,
        actor_user_id=resume.actor.userId,
        session_id=resume.sessionId,
        trace_id=resume.traceId,
        provider=provider,
        request=None,
        keep_failed=True,
    )


def is_run_active(actor_user_id: int, run_id: str) -> bool:
    return thread_id_for(actor_user_id, run_id) in _active_runs


def active_thread_ids() -> set[str]:
    return set(_active_runs)


async def _drive(
    *,
    run_id: str,
    actor_user_id: int,
    session_id: str,
    trace_id: str,
    provider: dict | None,
    request: InvokeRequest | None,
    keep_failed: bool,
) -> AsyncIterator[Event]:
    settings = get_settings()
    thread_id = thread_id_for(actor_user_id, run_id)
    rejected = {"finishReason": "error", "runId": run_id, "resumable": False}
    done_sent = False  # `done` is sent exactly once, whatever fails

    try:
        cfg = _resolve_provider_config(provider)
    except ValueError as exc:
        yield Event("error", {"code": "AGENT_PARAM_40001", "reason": "CONFIG", "message": str(exc)})
        yield Event("done", rejected)
        return

    if thread_id in _active_runs:
        yield Event("error", {"code": "AGENT_RUN_40901", "message": "run is already executing"})
        yield Event("done", rejected)
        return

    _active_runs.add(thread_id)
    span = tracer.start_span("agent.run", attributes={
        "nexus.trace_id": trace_id,
        "nexus.run_id": run_id,
        "nexus.engine": SUPPORTED_ENGINE,
    })
    try:
        async with open_run_store() as store:
            graph = get_graph_builder().compile(checkpointer=store.saver)
            config: dict[str, Any] = {"configurable": {"thread_id": thread_id}}

            resumed = request is None
            replay = ""
            if resumed:
                record = await store.get(thread_id)
                saved = None
                if record is not None and record.session_id == session_id:
                    snapshot = await graph.aget_state(config)
                    saved = snapshot.values.get("request")
                    replay = snapshot.values.get("answer", "")
                if not saved:
                    finished = await store.result(thread_id, session_id)
                    done_sent = True
                    if finished is None:
                        yield Event("error", {
                            "code": "AGENT_RUN_40401",
                            "message": "run not found or expired",
                        })
                        yield Event("done", rejected)
                        return
                    # The run ended while nobody was listening: hand over what it
                    # produced. No model call, nothing written twice.
                    result = finished["result"]
                    span.set_attribute("nexus.resumed", True)
                    span.set_attribute("nexus.finish_reason", result["finishReason"])
                    yield Event("meta", {
                        "traceId": trace_id,
                        "sessionId": session_id,
                        "operationType": finished["operationType"],
                        "runId": run_id,
                        "engine": SUPPORTED_ENGINE,
                        "resumed": True,
                    })
                    if result["answer"]:
                        yield Event("delta", {"text": result["answer"], "replayed": True})
                    yield Event("usage", result["usage"])
                    yield Event("done", {"finishReason": result["finishReason"], "runId": run_id, "resumable": False})
                    yield Event("__final__", {"result": result})
                    return
                request = InvokeRequest(**{**saved, "traceId": trace_id})
                graph_input = None
            else:
                registered = await store.register(
                    thread_id, run_id, actor_user_id, session_id, request.session.operationType)
                if not registered:
                    done_sent = True
                    yield Event("error", {"code": "AGENT_RUN_40901", "message": "run id is already in use"})
                    yield Event("done", rejected)
                    return
                graph_input = {"request": request.model_dump()}

            span.set_attribute("nexus.operation", request.session.operationType)
            span.set_attribute("nexus.resumed", resumed)

            run_started = time.monotonic()
            meta = {
                "traceId": trace_id,
                "sessionId": session_id,
                "operationType": request.session.operationType,
                "runId": run_id,
                "engine": SUPPORTED_ENGINE,
                # Which model actually answered, as reported by this run's channel.
                "provider": cfg.name if cfg else None,
                "model": cfg.model if cfg else None,
                "executionMode": "collaboration" if request.options.collaborationEnabled
                                 and request.session.operationType == "ASSISTANT_CHAT" else "single",
            }
            if resumed:
                meta["resumed"] = True
            answer_parts: list[str] = []
            # Public events say which run they belong to and where in it they happened.
            sequence = 1
            # Announce the turn before any retrieval so the client can show progress
            # while the memory / knowledge lookups are still running.
            yield _public(Event("meta", meta), sequence, run_id=run_id, started=run_started)
            sequence += 1
            if replay:
                # The client drops whatever partial text it showed before the
                # interruption and starts again from what was committed.
                answer_parts.append(replay)
                yield _public(Event("delta", {"text": replay, "replayed": True}), sequence,
                              run_id=run_id, started=run_started)
                sequence += 1

            max_model_calls = max(1, min(request.options.maxIterations, settings.max_iterations))
            # Up to reason + tools per model call, plus context, validation, one repair and settle.
            config["recursion_limit"] = 3 * max_model_calls + 6
            outbox = _Outbox(settings.stream_buffer_events, settings.stream_stall_timeout_sec)

            failure: _RunFailed | None = None
            try:
                run_context = trace.set_span_in_context(span)
                async with ToolExecutor(actor_user_id, trace_id, context=run_context) as executor:
                    ctx = _RunContext(
                        run_id=thread_id,
                        request=request,
                        provider=provider,
                        cfg=cfg,
                        client=build_client(cfg) if cfg is not None else None,
                        tools=executor,
                        memory=get_memory(),
                        outbox=outbox,
                        max_model_calls=max_model_calls,
                        span_context=run_context,
                        started=run_started,
                    )
                    stream = graph.astream(
                        graph_input, config, context=ctx,
                        # "sync": a step is on disk before the next one starts.
                        stream_mode="custom", durability="sync", version="v2",
                    )
                    try:
                        async for part in stream:
                            outbox.delivered()
                            event = _public(Event(part["data"]["name"], part["data"]["data"]), sequence,
                                            run_id=run_id, started=run_started)
                            sequence += 1
                            if event.name == "delta":
                                if event.data.get("replayed"):
                                    answer_parts.clear()  # the client starts over from this text
                                answer_parts.append(event.data["text"])
                            yield event
                    finally:
                        outbox.cancel()
                        await stream.aclose()
            except _RunFailed as exc:
                failure = exc
            except _ConsumerStalled as exc:
                log.warning("run %s stopped: %s", run_id, exc)
                yield Event("error", {"code": "AGENT_STREAM_50003", "message": str(exc)})
                failure = _RunFailed("error")
            except Exception as exc:
                log.exception("agent graph failed for run %s", run_id)
                yield Event("error", {"code": "AGENT_SYS_50001", "message": _public_message(exc, cfg)})
                failure = _RunFailed("error")

            snapshot = await graph.aget_state(config)
            values = snapshot.values
            resumable = keep_failed and bool(snapshot.next)
            if resumable:
                await store.touch(thread_id)
            else:
                await store.forget(thread_id)

            finish_reason = failure.finish_reason if failure else (values.get("finish_reason") or "stop")
            usage_in = values.get("usage_in", 0) + (failure.usage_in if failure else 0)
            usage_out = values.get("usage_out", 0) + (failure.usage_out if failure else 0)
            usage = TokenUsage(inputTokens=usage_in, outputTokens=usage_out, totalTokens=usage_in + usage_out)
            structured = values.get("structured") or {}

            span.set_attribute("nexus.finish_reason", finish_reason)
            span.set_attribute("gen_ai.usage.input_tokens", usage_in)
            span.set_attribute("gen_ai.usage.output_tokens", usage_out)
            if failure:
                span.set_status(trace.StatusCode.ERROR, finish_reason)

            result = InvokeResult(
                # Exactly the text the client ends up showing, including a failed call's partial output.
                answer="".join(answer_parts),
                toolCalls=[ToolCallSummary(**summary) for summary in values.get("tool_calls", [])],
                usage=usage,
                finishReason=finish_reason,
                todos=structured.get("todos"),
                draft=structured.get("draft"),
                alternatives=structured.get("alternatives"),
                runId=run_id,
                resumable=resumable,
                outputError=values.get("output_error"),
            ).model_dump()
            if keep_failed and not resumable:
                # A client that lost the connection before `done` can still ask for the outcome.
                await store.save_result(
                    thread_id, actor_user_id, session_id, request.session.operationType, result)

            done_sent = True
            yield _public(Event("usage", {**usage.model_dump(),
                                          "usageReported": bool(values.get("usage_reported"))}),
                          sequence, run_id=run_id, started=run_started)
            sequence += 1
            yield _public(Event("done", {"finishReason": finish_reason, "resumable": resumable}), sequence,
                          run_id=run_id, started=run_started)
            yield Event("__final__", {"result": result})
    except Exception as exc:
        # Opening or reading the run store failed. The stream still ends properly.
        if done_sent:
            raise
        log.exception("run %s failed outside the graph", run_id)
        span.set_status(trace.StatusCode.ERROR, "error")
        yield Event("error", {"code": "AGENT_SYS_50001", "message": _public_message(exc, cfg)})
        yield Event("done", rejected)
    finally:
        _active_runs.discard(thread_id)
        span.end()

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
import hashlib
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
from .scope import AuthorizedScope, ScopeClient, ScopeDenied, resume_scope, ScopedModel
from .resume_authorization import refresh_checkpoint_scope
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
_run_controls: dict[str, tuple[str, asyncio.Task, asyncio.Event]] = {}
_SESSION_WAIT_SEC = 30.0
# T2/T4B install a realtime authorization callback; absent means cache reuse denied.
reuse_authorizer = None
# T4B decides whether an uncommitted remote attempt is safe to retry.
replay_authorizer = None
# What a result was authorized under (scope key, policy version). A result recorded
# under one value is never reused under another. Absent means there is nothing to bind to.
authorization_binding = None
_session_locks: dict[tuple[int, str], asyncio.Lock] = {}

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
    # Node ids of the tool calls the last tool step ran, for the model step that reads them.
    tool_nodes: list[str]
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
    agent_results: list[dict[str, Any]]


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
    store: Any = None
    attempt_id: str = ""
    # Tools offered to the model, read once per execution of the run.
    tool_schemas: list[dict[str, Any]] | None = None
    # When this run started, so every public event can say how far into it it happened.
    started: float = dataclasses.field(default_factory=time.monotonic)


def _all_must_allow(parties):
    async def authorize(request, kind, call) -> bool:
        allowed = True
        # Every party is asked, so none can stand in for another's refusal.
        for party in parties:
            try:
                if await party(request, kind, call) is not True:
                    allowed = False
            except Exception:
                log.warning("an authorization policy failed and is treated as a refusal", exc_info=True)
                allowed = False
        return allowed
    authorize.parties = parties
    return authorize


def _all_bindings(parties):
    def binding(request):
        return [party(request) for party in parties]
    binding.parties = parties
    return binding


def install_authorizers(reuse=None, replay=None, *, binding=None) -> None:
    """Add a party's policy at start-up. It narrows what is installed; it never replaces it."""
    global reuse_authorizer, replay_authorizer, authorization_binding

    def joined(installed, added, join):
        earlier = getattr(installed, "parties", (installed,) if installed is not None else ())
        return join((*earlier, added))

    if reuse is not None:
        reuse_authorizer = joined(reuse_authorizer, reuse, _all_must_allow)
    if replay is not None:
        replay_authorizer = joined(replay_authorizer, replay, _all_must_allow)
    if binding is not None:
        authorization_binding = joined(authorization_binding, binding, _all_bindings)


class _MeteredClient:
    """Count the model calls that were sent, durably, and retain only each call's final usage.

    A call is counted when its request is about to leave the process. Preparing a
    call that never leaves (a missing SDK, a client that cannot be built, a stop
    before sending) is not a model call.
    """
    runtime_metered = True
    def __init__(self, client, store, thread_id, stats):
        self.client, self.store, self.thread_id, self.stats = client, store, thread_id, stats

    async def complete(self, *args, **kwargs):
        start_event = kwargs.pop("_runtime_start", None)
        call_id = None
        usage = None

        async def dispatched():
            nonlocal call_id
            if call_id is not None:
                return
            self.stats["modelCalls"] += 1
            call_id = str(self.stats["modelCalls"])
            await self.store.commit(self.thread_id, "model_attempt", call_id, "v1", {})

        # The step is announced before anything it produces. The stream is opened and read by
        # this task alone: the SDKs' timeouts and connection scopes belong to the task that opened it.
        if getattr(self.client, "signals_dispatch", False):
            kwargs["on_dispatch"] = dispatched
        else:
            # A client that cannot say when it sends is counted when it is called.
            await dispatched()
        if start_event:
            await start_event[0]("stage", start_event[1])
        try:
            async with contextlib.aclosing(self.client.complete(*args, **kwargs)) as chunks:
                async for chunk in chunks:
                    if chunk.kind == "usage" and chunk.usage is not None:
                        usage = chunk.usage
                    yield chunk
        finally:
            if usage is not None and call_id is not None:
                self.stats["reportedCalls"] += 1
                self.stats["inputTokens"] += usage.input_tokens
                self.stats["outputTokens"] += usage.output_tokens
                await self.store.commit(self.thread_id, "model_usage", call_id, "v1",
                    {"inputTokens": usage.input_tokens, "outputTokens": usage.output_tokens})


async def _call_stats(store, thread_id):
    stats = {"modelCalls": 0, "reportedCalls": 0, "inputTokens": 0, "outputTokens": 0}
    # Receipts are consecutive, run-local and cannot contain credentials.
    while await store.committed(thread_id, "model_attempt", str(stats["modelCalls"] + 1), "v1") is not None:
        stats["modelCalls"] += 1
        receipt = await store.committed(thread_id, "model_usage", str(stats["modelCalls"]), "v1")
        if receipt is not None:
            stats["reportedCalls"] += 1
            stats["inputTokens"] += receipt["inputTokens"]
            stats["outputTokens"] += receipt["outputTokens"]
    return stats


def _needs_resource_authorization(request, values):
    return bool(request.session.linkedKbId or request.input.chatId is not None
                or request.input.attachments or values.get("tool_calls") or values.get("toolCalls")
                or values.get("pending_tool_calls"))


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

    short_term = await ctx.memory.recent(user_id, request.session.sessionId, scope=request.scope)

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
                    scope=request.scope,
                    provider=ctx.provider,
                )
            except ScopeDenied:
                raise
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

    # Each of these is announced once the step is over, so it says how the step ended.
    await ctx.outbox.emit("stage", {
        "phase": "context",
        "status": "done",
        "shortTerm": len(short_term),
        "recalled": len(relevant_history),
        "ms": int((time.monotonic() - started) * 1000),
    })
    if linked_kb_id:
        # chunks is None when retrieval was skipped or failed and the turn degraded.
        await ctx.outbox.emit("stage", {"phase": "knowledge", "kbId": linked_kb_id, "chunks": kb_chunks,
                                        "status": "skipped" if kb_chunks is None else "done"})
    if attachments:
        await ctx.outbox.emit("stage", {"phase": "attachments", "status": "done", "count": len(attachments)})
    if sources:
        await ctx.outbox.emit("sources", {"items": sources})

    # Recorded here, after the history read, so the turn does not see itself.
    await ctx.memory.append(user_id, request.session.sessionId, "user", request.input.text, run_id=ctx.run_id, scope=request.scope)
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
    async def emit(name, data):
        if name in ("stage", "delta", "reasoning", "error"):
            data = {"agentId": "assistant", "nodeId": node_id, **data}
        if name == "error":
            data.setdefault("scope", "run")
        await ctx.outbox.emit(name, data)
    # What this call hangs off: the context step, then the tool calls it was given.
    parent_ids = ["context"] if iteration == 1 else list(state.get("tool_nodes") or []) or ["context"]
    start_data = {
        "phase": "model",
        "status": "start",
        "iteration": iteration,
        "nodeId": node_id,
        "parentIds": parent_ids,
        "provider": ctx.cfg.name,
        "model": ctx.cfg.model,
    }
    started = time.monotonic()
    with tracer.start_as_current_span("llm.call", attributes={
        "gen_ai.system": ctx.cfg.name or "",
        "gen_ai.request.model": ctx.cfg.model or "",
        "nexus.iteration": iteration,
    }) as span:
        try:
            completion = ctx.client.complete(
                state["messages"],
                _runtime_start=(emit, start_data),
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
                        usage_in = chunk.usage.input_tokens
                        usage_out = chunk.usage.output_tokens
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
    _own_call_ids(tool_calls, {c.get("callId") for c in state.get("tool_calls") or []}, iteration)

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


def _own_call_ids(calls: list[dict[str, Any]], used: set, iteration: int) -> None:
    """Give every tool call an id no other call of the run has.

    Providers that do not issue ids are numbered per step ("call_0", "gem_0_name"),
    so a later step repeats an earlier one's id. The id is fixed here, before the
    step is recorded: the transcript, the events, the call record and the tool
    service all see the same one from then on.
    """
    seen = set(used)
    for index, call in enumerate(calls):
        base = call["id"] or f"call_{index}"
        call_id, suffix = base, f"_s{iteration}"
        while call_id in seen:
            call_id, suffix = base + suffix, suffix + f"_{index}"
        seen.add(call_id)
        call["id"] = call_id


def _authorization(request: InvokeRequest):
    """What this request's reads are authorized under, as the installed parties state it."""
    try:
        return authorization_binding(request) if authorization_binding is not None else None
    except Exception as exc:
        raise ToolTransportError("the current authorization could not be determined") from exc


def _tool_fingerprint(request: InvokeRequest, call: dict[str, Any], producer: str) -> str:
    """What makes two tool calls the same call: the tool and its arguments, the turn's
    input, the node that asked for it, and what the read was authorized under."""
    authorization = _authorization(request)
    return hashlib.sha256(json.dumps({
        "name": call["name"], "arguments": call["arguments"],
        "sessionId": request.session.sessionId, "input": request.input.model_dump(),
        "options": request.options.model_dump(),
        "agentId": "assistant", "producer": producer, "authorization": authorization,
    }, sort_keys=True, ensure_ascii=False, default=str).encode()).hexdigest()


async def _run_tool(ctx: _RunContext, call: dict[str, Any], producer: str) -> tuple[dict[str, Any], dict[str, Any]]:
    """Execute one tool call; returns (summary, tool message for the model).

    `producer` is the model step that asked for the call.
    """
    name = call["name"]
    node_id = f"tool-{call['id']}"
    fingerprint = _tool_fingerprint(ctx.request, call, producer)
    record = call["id"]
    if ctx.store:
        held = await ctx.store.claim_call(ctx.run_id, record, fingerprint, ctx.attempt_id)
        if held["status"] not in ("claimed", "committed", "inflight"):
            # Tried before without a recorded result, refused, withdrawn, or read under a
            # different authorization: sending it again needs leave to repeat it.
            if replay_authorizer is None or not await replay_authorizer(ctx.request, "tool", call):
                raise ToolTransportError("uncommitted remote result requires safe replay authorization")
            held = await ctx.store.claim_call(ctx.run_id, record, fingerprint, ctx.attempt_id, takeover=True)
        if held["status"] == "committed":
            if reuse_authorizer is None or not await reuse_authorizer(ctx.request, "tool", call):
                raise ToolTransportError("cached tool result requires current resource authorization")
            cached = held["result"]
            await ctx.store.commit(ctx.run_id, "tool_reuse", uuid.uuid4().hex, fingerprint, {})
            await ctx.outbox.emit("tool_result", {"toolName": name, "status": "SUCCESS",
                "latencyMs": cached["latencyMs"], "callId": call["id"], "nodeId": node_id,
                "agentId": "assistant", "reused": True, "producerAttemptId": cached["producerAttemptId"]})
            return cached["summary"], cached["message"]
        if held["status"] != "claimed":
            raise ToolTransportError("the tool call is held by another execution")
    with tracer.start_as_current_span("tool.call", attributes={"gen_ai.tool.name": name}) as span:
        try:
            if ctx.store:
                await ctx.store.commit(ctx.run_id, "tool_attempt", uuid.uuid4().hex, fingerprint, {})
            payload, latency = await ctx.tools.execute(name, call["arguments"], call_id=call["id"])
        except ToolError as exc:
            span.set_attribute("nexus.tool.status", "FAILED")
            span.set_status(trace.StatusCode.ERROR, exc.code)
            await ctx.outbox.emit("tool_result", {
                "toolName": name, "status": "FAILED", "error": str(exc), "callId": call["id"],
                "nodeId": node_id, "agentId": "assistant"})
            if ctx.store:
                await ctx.store.fail_call(ctx.run_id, record, fingerprint, ctx.attempt_id)
            summary = ToolCallSummary(toolName=name, status="FAILED", latencyMs=0, error=str(exc))
            content = json.dumps({"toolError": exc.code, "message": str(exc)}, ensure_ascii=False)
        except ScopeDenied:
            # Out of scope, or the grant is gone: the call was not made and nothing is
            # returned. The model is told only that it was refused; a revoked grant
            # still ends the run at the next authorization check.
            span.set_attribute("nexus.tool.status", "DENIED")
            span.set_status(trace.StatusCode.ERROR, "scope")
            await ctx.outbox.emit("tool_result", {
                "toolName": name, "status": "FAILED", "error": "scope authorization denied",
                "callId": call["id"], "nodeId": node_id, "agentId": "assistant"})
            if ctx.store:
                # Refused before it was sent: known not to have run.
                await ctx.store.fail_call(ctx.run_id, record, fingerprint, ctx.attempt_id)
            summary = ToolCallSummary(toolName=name, status="FAILED", latencyMs=0, error="scope authorization denied")
            content = json.dumps({"toolError": "SCOPE_DENIED", "message": "scope authorization denied"})
        except ToolTransportError as exc:
            # Close the call in the client's view; the node itself fails and stays resumable.
            span.set_attribute("nexus.tool.status", "UNAVAILABLE")
            span.set_status(trace.StatusCode.ERROR, "transport")
            await ctx.outbox.emit("tool_result", {
                "toolName": name, "status": "FAILED", "error": "tool service unavailable", "callId": call["id"],
                "nodeId": node_id, "agentId": "assistant"})
            # Whether it reached the service is not known: the record stays claimed.
            raise
        else:
            span.set_attribute("nexus.tool.status", "SUCCESS")
            await ctx.outbox.emit("tool_result", {
                "toolName": name, "status": "SUCCESS", "latencyMs": latency, "callId": call["id"],
                "nodeId": node_id, "agentId": "assistant"})
            summary = ToolCallSummary(toolName=name, status="SUCCESS", latencyMs=latency)
            content = json.dumps(payload, ensure_ascii=False)
    message = {"role": "tool", "tool_call_id": call["id"], "name": name, "content": content}
    summary_data = {**summary.model_dump(), "callId": call["id"]}
    if summary.status == "SUCCESS" and ctx.store and not await ctx.store.finish_call(
            ctx.run_id, record, fingerprint, ctx.attempt_id,
            {"summary": summary_data, "message": message, "latencyMs": latency,
             "producerAttemptId": ctx.attempt_id}):
        # Another execution took the call over: its result is the one that stands.
        raise ToolTransportError("the tool call was taken over by another execution")
    return summary_data, message


async def _collaborate(state: AgentState, ctx: _RunContext) -> dict[str, Any]:
    """Opt-in: three analysts run concurrently, then one synthesis becomes the answer."""
    if ctx.client is None or ctx.cfg is None:
        await ctx.outbox.emit("error", {"code": "AGENT_MODEL_50202", "message": "no model provider configured for this run"})
        raise _RunFailed("error")
    await _offered_tools(ctx, None)  # keeps the tool-service status visible even without tool use
    async def authorize_agent(agent_id):
        return reuse_authorizer is not None and await reuse_authorizer(ctx.request, "agent", {"agentId": agent_id})
    outcome = await run_collaboration(
        state["messages"], ctx.client, ctx.tools, ctx.outbox.emit,
        run_id=ctx.run_id, options=ctx.request.options, node_id="collaboration",
        store=ctx.store, attempt_id=ctx.attempt_id, authorize_reuse=authorize_agent,
        authorization=_authorization(ctx.request),
    )
    update: dict[str, Any] = {
        "usage_in": state.get("usage_in", 0) + outcome["usage_in"],
        "usage_out": state.get("usage_out", 0) + outcome["usage_out"],
        "usage_reported": outcome["usage_in"] + outcome["usage_out"] > 0 or state.get("usage_reported", False),
        "model_calls": state.get("model_calls", 0) + 1,
        "agent_results": outcome.get("agent_results", []),
    }
    if outcome.get("failed") or not outcome.get("answer"):
        raise _RunFailed(outcome.get("finish_reason", "error") if outcome.get("failed") else "error", outcome["usage_in"], outcome["usage_out"])
    update.update({
        "messages": outcome["messages"],
        "answer": state.get("answer", "") + outcome["answer"],
        "last_text": outcome["answer"],
        "finish_reason": outcome["finish_reason"],
        "repairing": False,
    })
    return update


async def _tools(state: AgentState, ctx: _RunContext) -> dict[str, Any]:
    settings = get_settings()
    calls = state.get("pending_tool_calls") or []
    # The model step that asked for these calls.
    producer = f"model-{state.get('model_calls', 0)}"
    for call in calls:
        await ctx.outbox.emit("tool_call", {
            "toolName": call["name"], "args": call["arguments"], "callId": call["id"],
            "nodeId": f"tool-{call['id']}", "agentId": "assistant", "parentIds": [producer]})

    # T4B owns side-effect/concurrency policy; this only bounds scheduling.
    slots = asyncio.Semaphore(
        max(1, settings.tool_max_concurrency) if settings.enable_parallel_tool_calls else 1)

    async def run(call: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
        async with slots:
            return await _run_tool(ctx, call, producer)

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
        await asyncio.gather(*tasks, return_exceptions=True)
        raise

    update: dict[str, Any] = {
        # History keeps the order the model asked in, whatever order they finished in.
        "messages": [message for _, message in results],
        "tool_calls": [summary for summary, _ in results],
        "pending_tool_calls": [],
        "tool_nodes": [f"tool-{call['id']}" for call in calls],
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
        await emit("tool_call", {"toolName": forced, "args": call["arguments"], "callId": call["id"],
                                 "nodeId": f"tool-{call['id']}"})
        try:
            summary, message = await _run_tool(ctx, call, "mock")
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
    if ctx.request.options.collaborationEnabled:
        nodes = [{"id": "context", "kind": "context"}, {"id": "collaboration", "kind": "collaboration"}]
        edges = [{"from": "context", "to": "collaboration"}]
        for result in state.get("agent_results", []):
            role = result["agentId"]
            nodes.append({"id": f"collaboration-{role}", "kind": "model", "agentId": role})
            edges.append({"from": "collaboration", "to": f"collaboration-{role}"})
            if result.get("text") and not result.get("error"):
                edges.append({"from": f"collaboration-{role}", "to": "collaboration-synth"})
        nodes.append({"id": "collaboration-synth", "kind": "model", "agentId": "synth"})
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
    await ctx.memory.append(request.actor.userId, request.session.sessionId, "assistant", answer, run_id=ctx.run_id, scope=request.scope)

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
            scope=request.scope,
            run_id=ctx.run_id,
            provider=ctx.provider,
        ))
        _pending_rag_writes.add(task)
        task.add_done_callback(_pending_rag_writes.discard)
    return {"artifacts": artifacts}


def _node(name: str, fn: Callable[[AgentState, _RunContext], Awaitable[dict[str, Any]]]):
    async def run(state: AgentState, runtime: Runtime[_RunContext]) -> dict[str, Any]:
        ctx = runtime.context
        await ScopeClient().validate(ctx.request.scope)
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
        scope=request.scope,
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
        scope=resume.scope,
        keep_failed=True,
    )


def is_run_active(actor_user_id: int, run_id: str) -> bool:
    return thread_id_for(actor_user_id, run_id) in _active_runs


async def cancel_run(actor_user_id: int, session_id: str, run_id: str) -> str:
    thread_id = thread_id_for(actor_user_id, run_id)
    control = _run_controls.get(thread_id)
    if control is None or control[0] != session_id or control[1].done():
        return "not_active"
    control[1].cancel()
    await control[2].wait()
    return "cancelled"


def active_thread_ids() -> set[str]:
    return set(_active_runs)


async def _drive(**kwargs) -> AsyncIterator[Event]:
    """One identity boundary for every public frame, including early refusals."""
    attempt_id = uuid.uuid4().hex
    started = time.monotonic()
    sequence = 0
    reported_errors = set()
    # Refusing to continue a run changes nothing about it: its checkpoint is still there.
    refused = {"finishReason": "error", "resumable": kwargs["request"] is None}
    if is_run_active(kwargs["actor_user_id"], kwargs["run_id"]):
        for seq, event in enumerate((Event("error", {"code": "AGENT_RUN_40901", "scope": "run",
            "message": "run is already executing"}), Event("done", refused)), 1):
            yield Event(event.name, {**event.data, "runtimeContractVersion": 1,
                "runId": kwargs["run_id"], "attemptId": attempt_id, "sequence": seq, "at": 0})
        return
    key = (asyncio.get_running_loop(), kwargs["actor_user_id"], kwargs["session_id"])
    lock = _session_locks.setdefault(key, asyncio.Lock())
    try:
        await asyncio.wait_for(lock.acquire(), _SESSION_WAIT_SEC)
    except asyncio.TimeoutError:
        for seq, event in enumerate((
            Event("error", {"code": "AGENT_RUN_40902", "scope": "run", "message": "session is busy"}),
            Event("done", refused)), 1):
            yield Event(event.name, {**event.data, "runId": kwargs["run_id"], "attemptId": attempt_id,
                "sequence": seq, "at": 0, "runtimeContractVersion": 1})
        return
    try:
        async with contextlib.aclosing(_drive_execution(**kwargs, attempt_id=attempt_id)) as events:
            async for event in events:
                if event.name == "__final__":
                    yield event
                    continue
                sequence += 1
                data = dict(event.data)
                data.update(runtimeContractVersion=1, runId=kwargs["run_id"],
                            attemptId=data.get("attemptId", attempt_id), sequence=sequence)
                data.setdefault("at", max(0, int((time.monotonic() - started) * 1000)))
                if event.name == "error":
                    data.setdefault("scope", "run")
                    error_key = (data["scope"], data.get("agentId"), data.get("nodeId"), data.get("code"))
                    if error_key in reported_errors:
                        sequence -= 1
                        continue
                    reported_errors.add(error_key)
                yield Event(event.name, data)
    finally:
        lock.release()


async def _drive_execution(
    *,
    run_id: str,
    actor_user_id: int,
    session_id: str,
    trace_id: str,
    provider: dict | None,
    request: InvokeRequest | None,
    scope: AuthorizedScope | None,
    keep_failed: bool,
    attempt_id: str,
) -> AsyncIterator[Event]:
    settings = get_settings()
    thread_id = thread_id_for(actor_user_id, run_id)
    rejected = {"finishReason": "error", "runId": run_id, "resumable": False}
    sealed_result = None
    done_sent = False  # `done` is sent exactly once, whatever fails

    cfg = None
    try:
        if scope is None:
            raise ScopeDenied()
        scope.bind(actor_user_id, session_id, run_id)
        scope = await ScopeClient().validate(scope)
        # A completed handover is data retrieval, not a model invocation. Do not even
        # resolve provider configuration until a resume is known to require execution.
        if request is not None:
            if request.input.chatId is not None and request.input.chatId not in scope.chatIds:
                raise ScopeDenied()
            # Unproven preloaded message bodies cannot bypass Java projection gate.
            if request.input.targetMessageContent is not None:
                raise ScopeDenied()
            cfg = _resolve_provider_config(provider)
    except ScopeDenied:
        yield Event("error", {"code": "AGENT_AUTHZ_40301", "message": "scope authorization denied"})
        yield Event("done", rejected)
        return
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
                finished = await store.result(thread_id, session_id)
                if finished is not None:
                    # A sealed outcome takes precedence over interrupted cleanup.
                    result = dict(finished["result"])
                    saved_request = result.pop("_request", None)
                    if saved_request:
                        # The outcome is released under the grant this fetch carries, not the one it was produced under.
                        saved_request = {**saved_request, "scope": scope.model_dump()}
                    if saved_request and _needs_resource_authorization(InvokeRequest(**saved_request), result):
                        if reuse_authorizer is None or not await reuse_authorizer(InvokeRequest(**saved_request), "handover", {"runId": run_id}):
                            # Denied, not lost: the outcome stays stored for an authorized fetch.
                            done_sent = True
                            yield Event("error", {"code": "AGENT_AUTHZ_40301",
                                "message": "stored outcome requires current resource authorization"})
                            yield Event("done", {**rejected, "resumable": True})
                            return
                    if "scope" in result:
                        resume_scope(result, scope)
                    scope = await ScopeClient().validate(scope)
                    result["scope"] = scope.model_dump()
                    await store.save_result(thread_id, actor_user_id, session_id, finished["operationType"],
                                            {**finished["result"], "scope": result["scope"]})
                    await store.forget(thread_id)
                    handover = "handover-" + run_id
                    common = {"attemptId": handover, "at": 0}
                    yield Event("meta", {**common, "traceId": trace_id,
                        "sessionId": session_id, "operationType": finished["operationType"],
                        "engine": SUPPORTED_ENGINE, "resumed": True,
                        "deliveryMode": "handover", "producerAttemptId": result.get("producerAttemptId"),
                        "scope": result["scope"]})
                    yield Event("delta", {**common, "text": result["answer"], "replayed": True})
                    yield Event("usage", {**common, **result["usage"],
                        **{k: result[k] for k in ("modelCalls", "usageReported", "usageComplete") if k in result}})
                    yield Event("done", {**common, "finishReason": result["finishReason"], "resumable": False})
                    yield Event("__final__", {"result": result})
                    done_sent = True
                    return
                record = await store.get(thread_id)
                saved = None
                if record is not None and record.session_id == session_id:
                    await ScopeClient().validate(scope)
                    snapshot = await graph.aget_state(config)
                    saved = snapshot.values.get("request")
                    resume_scope(saved or {}, scope)
                    scope = await ScopeClient().validate(scope)
                    replay = snapshot.values.get("answer", "")
                if not saved:
                    done_sent = True
                    yield Event("error", {"code": "AGENT_RUN_40401", "message": "run not found or expired"})
                    yield Event("done", rejected)
                    return
                try:
                    saved, scope = await refresh_checkpoint_scope(graph, store.saver, config, scope)
                except ScopeDenied:
                    stored = await store.saver.aget_tuple(config)
                    if stored is None or isinstance(stored.checkpoint.get("channel_values", {}).get("request"), dict):
                        raise
                    # The turn stopped before its first step was folded into a checkpoint: the
                    # request is on disk only as a pending write, so there is nothing to rewrite.
                    # It was checked against the current grant above; the run goes on under that grant.
                    saved = {**saved, "scope": scope.model_dump()}
                request = InvokeRequest(**{**saved, "traceId": trace_id})
                if _needs_resource_authorization(request, snapshot.values):
                    if reuse_authorizer is None or not await reuse_authorizer(request, "resume", {"runId": run_id}):
                        done_sent = True
                        yield Event("error", {"code": "AGENT_AUTHZ_40301",
                            "message": "resume requires current resource authorization"})
                        yield Event("done", {**rejected, "resumable": True})
                        return
                graph_input = None
                try:
                    cfg = _resolve_provider_config(provider)
                except ValueError as exc:
                    done_sent = True
                    yield Event("error", {"code": "AGENT_PARAM_40001", "reason": "CONFIG", "message": str(exc)})
                    yield Event("done", {**rejected, "resumable": True})
                    return
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
                "scope": scope.model_dump(),
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
            scope = await ScopeClient().validate(scope)
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

            stats = await _call_stats(store, thread_id)
            failure: _RunFailed | None = None
            queue = asyncio.Queue()
            released = asyncio.Event()

            async def produce():
                stream = None
                try:
                    run_context = trace.set_span_in_context(span)
                    async with ToolExecutor(actor_user_id, trace_id, context=run_context, scope=scope) as executor:
                        model = None
                        if cfg is not None:
                            # The grant is confirmed around every provider call; the call is
                            # counted when the adapter underneath says its request is leaving.
                            real = build_client(cfg)
                            model = ScopedModel(real, scope)
                            model.signals_dispatch = getattr(real, "signals_dispatch", False)
                        ctx = _RunContext(
                            run_id=thread_id,
                            request=request,
                            provider=provider,
                            cfg=cfg,
                            client=_MeteredClient(model, store, thread_id, stats) if model is not None else None,
                            tools=executor,
                            memory=get_memory(),
                            outbox=outbox,
                            max_model_calls=max_model_calls,
                            span_context=run_context,
                            started=run_started,
                            store=store, attempt_id=attempt_id,
                        )
                        stream = graph.astream(
                            graph_input, config, context=ctx,
                            # "sync": a step is on disk before the next one starts.
                            stream_mode="custom", durability="sync", version="v2",
                        )
                        async with contextlib.aclosing(stream) as parts:
                            async for part in parts:
                                await queue.put(part)
                except asyncio.CancelledError:
                    await queue.put(_RunFailed("cancelled"))
                except BaseException as exc:
                    await queue.put(exc)
                finally:
                    outbox.cancel()
                    released.set()
                    await queue.put(None)

            producer = asyncio.create_task(produce())
            _run_controls[thread_id] = (session_id, producer, released)
            try:
                while True:
                    part = await queue.get()
                    if part is None:
                        break
                    if isinstance(part, _RunFailed):
                        failure = part
                        continue
                    if isinstance(part, ScopeDenied):
                        raise part  # one authorization error below, not a system failure first
                    if isinstance(part, BaseException):
                        failure = _RunFailed("error")
                        yield Event("error", {"code": "AGENT_SYS_50001", "message": _public_message(part, cfg)})
                        continue
                    await ScopeClient().validate(scope)
                    outbox.delivered()
                    event = _public(Event(part["data"]["name"], part["data"]["data"]), sequence,
                                    run_id=run_id, started=run_started)
                    sequence += 1
                    if event.name == "delta":
                        if event.data.get("replayed"):
                            answer_parts.clear()
                        answer_parts.append(event.data["text"])
                    yield event
            finally:
                if not producer.done():
                    producer.cancel()
                await asyncio.gather(producer, return_exceptions=True)
                _run_controls.pop(thread_id, None)

            await ScopeClient().validate(scope)
            snapshot = await graph.aget_state(config)
            values = snapshot.values
            # `tasks`, not `next`: a step whose result is on disk but whose checkpoint is not
            # (a failed write, or a stop between the two) has left `next` and is still unfinished.
            resumable = keep_failed and bool(snapshot.tasks) and bool(values.get("request"))
            if resumable:
                await store.touch(thread_id)

            finish_reason = failure.finish_reason if failure else (values.get("finish_reason") or "stop")
            usage_in = stats["inputTokens"]
            usage_out = stats["outputTokens"]
            known = stats["reportedCalls"] > 0 or stats["modelCalls"] == 0
            usage = TokenUsage(inputTokens=usage_in if known else None,
                outputTokens=usage_out if known else None, totalTokens=usage_in + usage_out if known else None)
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
            tool_count = await store.receipt_count(thread_id, "tool_attempt")
            reused_count = await store.receipt_count(thread_id, "tool_reuse")
            result.update(toolCallCount=tool_count, reusedToolCallCount=reused_count, modelCalls=stats["modelCalls"],
                usageReported=stats["reportedCalls"] > 0,
                usageComplete=stats["reportedCalls"] == stats["modelCalls"],
                producerAttemptId=attempt_id)
            await ScopeClient().validate(scope)
            result["scope"] = scope.model_dump()
            if keep_failed and not resumable:
                # A client that lost the connection before `done` can still ask for the outcome.
                await store.save_result(
                    thread_id, actor_user_id, session_id, request.session.operationType,
                    {**result, "_request": request.model_dump()})
                sealed_result = result
            if not resumable:
                await store.forget(thread_id)

            done_sent = True
            yield _public(Event("usage", {**usage.model_dump(),
                                          "modelCalls": stats["modelCalls"], "usageScope": "run",
                                          "toolCallCount": tool_count, "reusedToolCallCount": reused_count,
                                          "usageReported": stats["reportedCalls"] > 0,
                                          "usageComplete": stats["reportedCalls"] == stats["modelCalls"]}),
                          sequence, run_id=run_id, started=run_started)
            sequence += 1
            yield _public(Event("done", {"finishReason": finish_reason, "resumable": resumable}), sequence,
                          run_id=run_id, started=run_started)
            yield Event("__final__", {"result": result})
    except ScopeDenied:
        yield Event("error", {"code": "AGENT_AUTHZ_40301", "message": "scope authorization denied"})
        yield Event("done", rejected)
    except Exception as exc:
        # Opening or reading the run store failed. The stream still ends properly.
        if done_sent:
            raise
        log.exception("run %s failed outside the graph", run_id)
        span.set_status(trace.StatusCode.ERROR, "error")
        yield Event("error", {"code": "AGENT_SYS_50001", "message": _public_message(exc, cfg),
            "scope": "transport" if sealed_result else "run"})
        yield Event("done", {"finishReason": sealed_result["finishReason"], "resumable": False} if sealed_result else rejected)
    finally:
        _active_runs.discard(thread_id)
        span.end()

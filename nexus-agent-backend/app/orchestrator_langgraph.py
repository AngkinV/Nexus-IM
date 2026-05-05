"""LangGraph alternative engine for the agent loop.

Why two engines:
  - the original orchestrator.py is a hand-rolled ReAct loop. Easy to
    debug, easy to test, easy to instrument — the production path.
  - this file is the same control flow expressed as a LangGraph
    StateGraph. It exists so the project can demonstrate familiarity
    with the 2024+ industry-standard agent compiler, with an apples-to-
    apples comparison: same prompts, same tools, same RAG context, same
    SSE event names.

Selected via settings.engine == "langgraph" (Day 17 wires the switch
in routes.py).

Tool layer: as of the Day 17 follow-up, this engine derives its tool
schemas from ``langchain_tools.convert_langchain_tools_to_openai(...)``
rather than ``tools.TOOL_SCHEMAS`` directly, so the LangChain ``@tool``
wrappers are the live source of truth on this path. ``ToolExecutor``
still owns the wire-level Java dispatch, so both engines call the
same internal API endpoints under the same tool names.
"""
from __future__ import annotations

import asyncio
import json
import logging
import operator
from typing import Annotated, Any, AsyncIterator, Optional, TypedDict

from langgraph.graph import END, StateGraph

from .config import get_settings
from .knowledge.qa import build_kb_context
from .langchain_tools import convert_langchain_tools_to_openai
from .llm import LLMChunk, ProviderConfig, build_client
from .memory import get_memory
from .mock import mock_answer
from .orchestrator import (
    Event,
    _operation_required_tool,
    _parse_structured,
    _pending_rag_writes,
    _resolve_provider_config,
)
from .prompts import build_messages
from .rag import knowledge_rag, memory_rag
from .schemas import InvokeRequest, InvokeResult, ToolCallSummary, TokenUsage
from .tools import ToolError, ToolExecutor

log = logging.getLogger(__name__)


class AgentState(TypedDict, total=False):
    """Shared state across nodes.

    `messages` and accumulator-typed fields use ``operator.add`` so each
    node returns *new* items only and LangGraph concatenates. Scalar
    fields (iteration / final_answer / usage_in / usage_out / structured)
    are simply replaced by the latest node return value.

    Carried context (request / cfg / forced_tool / actor_user_id /
    trace_id / max_iterations) is set in the initial state and never
    mutated; nodes read it to decide what to do.
    """
    messages: Annotated[list[dict[str, Any]], operator.add]
    iteration: int
    final_answer: str
    structured: dict[str, Any]
    usage_in: int
    usage_out: int
    tool_calls_summary: Annotated[list[ToolCallSummary], operator.add]
    side_events: Annotated[list[Event], operator.add]

    # Carried context (set once at run start)
    request: InvokeRequest
    cfg: Optional[ProviderConfig]
    forced_tool: Optional[str]
    actor_user_id: int
    trace_id: str
    max_iterations: int


# ----------------------------------------------------------------------
# Nodes
# ----------------------------------------------------------------------


async def reason_node(state: AgentState) -> dict[str, Any]:
    """Call the LLM with the current message stack.

    Two outcomes:
    - tool_calls present → produce an assistant message with tool_calls
      (no final_answer); the route function will dispatch to tool_node.
    - plain text → set final_answer + structured; the route will end.
    """
    settings = get_settings()
    cfg = state.get("cfg")

    # Mock pathway: no provider, return mock_answer once and finish.
    # First-iteration mock includes the forced-tool side-effect so the
    # SSE stream still contains tool_call + tool_result for parity with
    # the handcrafted engine; we delegate that to mock_pathway() below.
    if cfg is None:
        return await _mock_reason(state)

    client = build_client(cfg)
    forced = state.get("forced_tool")
    iteration = state.get("iteration", 0)
    tool_choice = "required" if (iteration == 0 and forced) else "auto"

    # Source the tool schemas from the LangChain @tool wrappers — that
    # way this engine actually exercises the langchain_tools.py layer
    # rather than reusing the handcrafted dicts. ToolExecutor (still
    # the same one used by tools.py) is invoked in tool_node below, so
    # the Java internal-API contract is unchanged.
    derived_tool_schemas = convert_langchain_tools_to_openai(
        state["actor_user_id"], state["trace_id"],
    )

    chunks: list[LLMChunk] = []
    try:
        async for chunk in client.complete(
            state["messages"],
            tools=derived_tool_schemas,
            tool_choice=tool_choice,
            temperature=state["request"].options.temperature,
            max_tokens=min(
                state["request"].options.maxOutputTokens,
                settings.max_output_tokens,
            ),
            timeout_sec=settings.model_timeout_sec,
        ):
            chunks.append(chunk)
    except Exception as exc:
        log.warning("LLM client failed in langgraph engine: %s", exc)
        msg = f"（模型调用失败：{exc}）"
        return {
            "messages": [{"role": "assistant", "content": msg}],
            "iteration": iteration + 1,
            "final_answer": msg,
        }

    text_parts: list[str] = []
    raw_tool_calls: list[dict[str, Any]] = []
    usage_in = 0
    usage_out = 0
    for chunk in chunks:
        if chunk.kind == "text" and chunk.text:
            text_parts.append(chunk.text)
        elif chunk.kind == "tool_call" and chunk.tool_call:
            raw_tool_calls.append({
                "id": chunk.tool_call.id,
                "type": "function",
                "function": {
                    "name": chunk.tool_call.name,
                    "arguments": json.dumps(chunk.tool_call.arguments),
                },
            })
        elif chunk.kind == "usage" and chunk.usage:
            usage_in += chunk.usage.input_tokens
            usage_out += chunk.usage.output_tokens

    text = "".join(text_parts)

    if raw_tool_calls:
        # Loop continues. Emit tool_call side-events now so the SSE stream
        # surfaces them before the (slower) tool execution in tool_node.
        side_events: list[Event] = []
        for tc in raw_tool_calls:
            side_events.append(Event("tool_call", {
                "toolName": tc["function"]["name"],
                "args": json.loads(tc["function"]["arguments"]),
            }))
        return {
            "messages": [{
                "role": "assistant",
                "content": text,
                "tool_calls": raw_tool_calls,
            }],
            "iteration": iteration + 1,
            "usage_in": state.get("usage_in", 0) + usage_in,
            "usage_out": state.get("usage_out", 0) + usage_out,
            "side_events": side_events,
        }

    # Final assistant answer.
    structured = _parse_structured(state["request"].session.operationType, text)
    return {
        "messages": [{"role": "assistant", "content": text}],
        "iteration": iteration + 1,
        "final_answer": text,
        "structured": structured,
        "usage_in": state.get("usage_in", 0) + usage_in,
        "usage_out": state.get("usage_out", 0) + usage_out,
    }


async def _mock_reason(state: AgentState) -> dict[str, Any]:
    """No-provider pathway: produce mock_answer in one shot.

    Mirrors orchestrator.run_agent's mock branch, including the
    forced-tool side effect for CHAT_SUMMARY/TODO_EXTRACT so the
    LangGraph trace still shows a tool call.
    """
    request = state["request"]
    forced = state.get("forced_tool")
    side_events: list[Event] = []
    tool_payloads: list[dict[str, Any]] = []
    new_summaries: list[ToolCallSummary] = []

    if forced and request.input.chatId is not None:
        async with ToolExecutor(state["actor_user_id"], state["trace_id"]) as ex:
            args = {"chat_id": request.input.chatId, "limit": 80}
            side_events.append(Event("tool_call", {"toolName": forced, "args": args}))
            try:
                payload, latency = await ex.execute(forced, args)
                new_summaries.append(ToolCallSummary(toolName=forced, status="SUCCESS", latencyMs=latency))
                tool_payloads.append({"tool": forced, "payload": payload})
                side_events.append(Event("tool_result", {"toolName": forced, "status": "SUCCESS", "latencyMs": latency}))
            except ToolError as exc:
                new_summaries.append(ToolCallSummary(toolName=forced, status="FAILED", latencyMs=0, error=str(exc)))
                side_events.append(Event("tool_result", {"toolName": forced, "status": "FAILED", "error": str(exc)}))

    ans = mock_answer(request, tool_payloads)
    final_answer = ans["answer"]
    structured = {k: v for k, v in ans.items() if k not in {"answer", "_meta"}}
    return {
        "messages": [{"role": "assistant", "content": final_answer}],
        "iteration": state.get("iteration", 0) + 1,
        "final_answer": final_answer,
        "structured": structured,
        "usage_in": state.get("usage_in", 0) + 320,
        "usage_out": state.get("usage_out", 0) + max(8, len(final_answer)),
        "tool_calls_summary": new_summaries,
        "side_events": side_events,
    }


async def tool_node(state: AgentState) -> dict[str, Any]:
    """Execute the tool_calls in the last assistant message.

    Each call goes through the existing ToolExecutor so the Day 11
    Java/Python contract (HMAC + actor-bound internal API) is shared.
    Failures become assistant-readable tool error blobs rather than
    aborting the graph — the model gets one more turn to recover.
    """
    last = state["messages"][-1] if state["messages"] else {}
    tool_calls = last.get("tool_calls") or []
    if not tool_calls:
        return {}

    new_messages: list[dict[str, Any]] = []
    new_summaries: list[ToolCallSummary] = []
    new_events: list[Event] = []

    async with ToolExecutor(state["actor_user_id"], state["trace_id"]) as ex:
        for tc in tool_calls:
            name = tc["function"]["name"]
            args = json.loads(tc["function"]["arguments"])
            try:
                payload, latency = await ex.execute(name, args)
                new_summaries.append(ToolCallSummary(toolName=name, status="SUCCESS", latencyMs=latency))
                new_events.append(Event("tool_result", {
                    "toolName": name, "status": "SUCCESS", "latencyMs": latency,
                }))
                new_messages.append({
                    "role": "tool",
                    "tool_call_id": tc["id"],
                    "name": name,
                    "content": json.dumps(payload, ensure_ascii=False),
                })
            except ToolError as exc:
                new_summaries.append(ToolCallSummary(toolName=name, status="FAILED", latencyMs=0, error=str(exc)))
                new_events.append(Event("tool_result", {
                    "toolName": name, "status": "FAILED", "error": str(exc),
                }))
                new_messages.append({
                    "role": "tool",
                    "tool_call_id": tc["id"],
                    "name": name,
                    "content": json.dumps({"toolError": exc.code, "message": str(exc)}),
                })

    return {
        "messages": new_messages,
        "tool_calls_summary": new_summaries,
        "side_events": new_events,
    }


def route_after_reason(state: AgentState) -> str:
    """Decide what to do after reason_node.

    - hard cap reached → finish
    - last message has tool_calls → dispatch to tool node
    - otherwise → finish (model produced a final answer)
    """
    if state.get("iteration", 0) >= state.get("max_iterations", 6):
        return "finish"
    last = state["messages"][-1] if state["messages"] else None
    if last and last.get("tool_calls"):
        return "tool"
    return "finish"


# ----------------------------------------------------------------------
# Compiled graph
# ----------------------------------------------------------------------


_compiled_graph: Any = None


def _build_graph() -> Any:
    g = StateGraph(AgentState)
    g.add_node("reason", reason_node)
    g.add_node("tool", tool_node)
    g.set_entry_point("reason")
    g.add_conditional_edges(
        "reason",
        route_after_reason,
        {"tool": "tool", "finish": END},
    )
    g.add_edge("tool", "reason")
    return g.compile()


def get_graph() -> Any:
    """Lazy-compile the StateGraph on first use; reuse forever after."""
    global _compiled_graph
    if _compiled_graph is None:
        _compiled_graph = _build_graph()
    return _compiled_graph


# ----------------------------------------------------------------------
# Public entrypoint
# ----------------------------------------------------------------------


async def run_agent(request: InvokeRequest, provider: dict | None = None) -> AsyncIterator[Event]:
    """Drop-in replacement for orchestrator.run_agent backed by LangGraph.

    Emits the same SSE event sequence the handcrafted engine emits, so
    Java's gateway and the frontend SSE parser are oblivious to the
    engine choice.
    """
    settings = get_settings()
    memory = get_memory()
    short_term = memory.recent(request.actor.userId, request.session.sessionId)
    long_term: list[dict[str, Any]] = []

    # Shared RAG retrieval — same code path the handcrafted engine uses.
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
            log.exception("memory rag retrieve failed in langgraph engine; degrading")

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
            log.exception("knowledge rag retrieve failed in langgraph engine; degrading")

    base_messages = build_messages(
        request, short_term, long_term,
        relevant_history=relevant_history,
        kb_context=kb_context,
    )

    yield Event("meta", {
        "traceId": request.traceId,
        "sessionId": request.session.sessionId,
        "operationType": request.session.operationType,
        "engine": "langgraph",  # so traces are distinguishable when both engines exist in logs
        "toolLayer": "langchain",  # @tool wrappers are the live source of truth on this engine
    })

    cfg = _resolve_provider_config(provider)
    forced = _operation_required_tool(request.session.operationType)

    initial_state: AgentState = {
        "messages": list(base_messages),
        "iteration": 0,
        "final_answer": "",
        "structured": {},
        "usage_in": 0,
        "usage_out": 0,
        "tool_calls_summary": [],
        "side_events": [],
        "request": request,
        "cfg": cfg,
        "forced_tool": forced,
        "actor_user_id": request.actor.userId,
        "trace_id": request.traceId,
        "max_iterations": min(request.options.maxIterations, settings.max_iterations),
    }

    graph = get_graph()
    final_state: dict[str, Any] = {}
    emitted_event_count = 0

    # stream_mode="values" yields the FULL accumulated state after each
    # node returns; we diff against emitted_event_count to find newly
    # appended side_events without re-emitting old ones.
    try:
        async for snapshot in graph.astream(initial_state, stream_mode="values"):
            events = snapshot.get("side_events") or []
            for ev in events[emitted_event_count:]:
                yield ev
            emitted_event_count = len(events)
            final_state = snapshot
    except Exception as exc:
        log.exception("langgraph engine raised; surfacing as error event")
        yield Event("error", {"code": "AGENT_SYS_50001", "message": str(exc)[:200]})
        return

    final_answer = final_state.get("final_answer") or ""
    if not final_answer:
        # Hard-cap reached without a clean answer — keep parity with the
        # handcrafted engine's for-else fallback.
        final_answer = "（已达到最大工具调用轮数，返回当前已知信息）"

    yield Event("delta", {"text": final_answer})

    usage = TokenUsage(
        inputTokens=final_state.get("usage_in", 0),
        outputTokens=final_state.get("usage_out", 0),
        totalTokens=final_state.get("usage_in", 0) + final_state.get("usage_out", 0),
    )
    yield Event("usage", usage.model_dump())

    # Persist the turn to short-term + long-term memory, same as the
    # handcrafted path. Memory writes are fire-and-forget so the SSE
    # `done` event isn't blocked on the embedding call.
    memory.append(request.actor.userId, request.session.sessionId, "user", request.input.text)
    if final_answer:
        memory.append(request.actor.userId, request.session.sessionId, "assistant", final_answer)
        if settings.memory_rag_enabled and request.input.text:
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

    structured = final_state.get("structured") or {}
    yield Event("__final__", {
        "result": InvokeResult(
            answer=final_answer,
            toolCalls=list(final_state.get("tool_calls_summary") or []),
            usage=usage,
            finishReason="stop",
            todos=structured.get("todos"),
            draft=structured.get("draft"),
            alternatives=structured.get("alternatives"),
        ).model_dump()
    })

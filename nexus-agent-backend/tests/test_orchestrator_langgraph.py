"""Day 15 tests: LangGraph engine skeleton.

Goal: prove the StateGraph compiles, the public run_agent generator
emits the canonical SSE event sequence (meta → tool_call/tool_result
→ delta → usage → done → __final__), and the route function picks
the right edge under each condition. We deliberately do not call a
real LLM here — the mock pathway is enough to exercise control flow.

A separate Day-16 test will exercise the real-provider path with a
fake LLMClient so we can verify the multi-iteration tool-loop ends
correctly.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

os.environ.pop("OPENAI_API_KEY", None)
os.environ["USE_REAL_MODEL"] = "false"
os.environ["REDIS_URL"] = "redis://127.0.0.1:1"


from app import orchestrator_langgraph as og  # noqa: E402
from app.config import get_settings  # noqa: E402
from app.rag import knowledge_rag, memory_rag  # noqa: E402
from app.schemas import Actor, InputPayload, InvokeRequest, Options, Session  # noqa: E402


class _FakeToolExecutor:
    def __init__(self, *_a, **_kw):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def execute(self, name, args):
        return {"chatId": args.get("chat_id"), "messages": [
            {"messageId": 1, "senderId": 10, "senderName": "alice", "content": "hello", "createdAt": "2026-05-01"},
        ]}, 7


def _req(*, op="ASSISTANT_CHAT", text="你好", chat_id=None, kb_id=None):
    return InvokeRequest(
        traceId="tr_lg",
        actor=Actor(userId=42, username="alice"),
        session=Session(sessionId="s_lg", operationType=op, linkedKbId=kb_id),
        input=InputPayload(text=text, chatId=chat_id),
        options=Options(),
    )


@pytest.fixture(autouse=True)
def _patch_tools(monkeypatch):
    monkeypatch.setattr(og, "ToolExecutor", _FakeToolExecutor)


@pytest.fixture(autouse=True)
def _disable_rag(monkeypatch):
    """Module A/B retrieval is exercised on the handcrafted engine; here
    we only want to prove the LangGraph runner itself works."""
    s = get_settings()
    monkeypatch.setattr(s, "memory_rag_enabled", False)
    monkeypatch.setattr(s, "knowledge_rag_enabled", False)


@pytest.fixture(autouse=True)
def _stub_memory_writes(monkeypatch):
    async def _noop_write(*a, **kw):
        return None
    monkeypatch.setattr(memory_rag, "write", _noop_write)


@pytest.fixture(autouse=True)
def _isolate_pending_writes():
    from app import orchestrator
    orchestrator._pending_rag_writes.clear()
    yield
    orchestrator._pending_rag_writes.clear()


# ---------------- graph compile ----------------


def test_graph_compiles_lazily_and_is_cached():
    g1 = og.get_graph()
    g2 = og.get_graph()
    assert g1 is g2  # same instance — module-level cache works


def test_graph_has_reason_and_tool_nodes():
    g = og.get_graph()
    # langgraph's compiled graph exposes its nodes via .nodes; assert names.
    nodes = set(g.nodes.keys()) if hasattr(g, "nodes") else set()
    # Older langgraph versions may stash them in get_graph().nodes — be lenient.
    if not nodes and hasattr(g, "get_graph"):
        nodes = set(g.get_graph().nodes.keys())
    assert "reason" in nodes and "tool" in nodes


# ---------------- route ----------------


def test_route_returns_finish_when_iteration_caps_out():
    state = {
        "messages": [{"role": "assistant", "tool_calls": [{"id": "t1"}]}],
        "iteration": 6,
        "max_iterations": 6,
    }
    assert og.route_after_reason(state) == "finish"


def test_route_returns_tool_when_assistant_has_tool_calls():
    state = {
        "messages": [{"role": "assistant", "tool_calls": [{"id": "t1"}]}],
        "iteration": 1,
        "max_iterations": 6,
    }
    assert og.route_after_reason(state) == "tool"


def test_route_returns_finish_when_assistant_has_no_tool_calls():
    state = {
        "messages": [{"role": "assistant", "content": "done"}],
        "iteration": 1,
        "max_iterations": 6,
    }
    assert og.route_after_reason(state) == "finish"


def test_route_returns_finish_when_messages_empty():
    state = {"messages": [], "iteration": 0, "max_iterations": 6}
    assert og.route_after_reason(state) == "finish"


# ---------------- end-to-end via mock ----------------


async def test_run_agent_emits_canonical_event_sequence_under_mock():
    """Mock pathway (no provider) — should still emit meta + delta +
    usage + done + __final__ in the expected order."""
    events = [e async for e in og.run_agent(_req(text="你好"))]
    names = [e.name for e in events]
    assert names[0] == "meta"
    assert "delta" in names
    assert "usage" in names
    assert names[-2] == "done"
    assert names[-1] == "__final__"

    # meta payload includes engine identifier for trace correlation.
    assert events[0].data.get("engine") == "langgraph"


async def test_run_agent_emits_tool_events_for_chat_summary():
    """CHAT_SUMMARY forces a tool call even on the mock pathway,
    same as the handcrafted engine."""
    events = [e async for e in og.run_agent(_req(op="CHAT_SUMMARY", chat_id=20001))]
    names = [e.name for e in events]
    assert "tool_call" in names
    assert "tool_result" in names
    # tool_result must follow tool_call in the stream (same order the
    # frontend renders them).
    assert names.index("tool_call") < names.index("tool_result")


async def test_final_event_carries_invoke_result():
    events = [e async for e in og.run_agent(_req(op="CHAT_SUMMARY", chat_id=20001))]
    final = events[-1]
    assert final.name == "__final__"
    result = final.data["result"]
    assert isinstance(result.get("answer"), str)
    assert isinstance(result.get("toolCalls"), list)
    assert isinstance(result.get("usage"), dict)
    # Forced tool call must be present in toolCalls summary.
    assert any(tc["toolName"] == "get_recent_messages" for tc in result["toolCalls"])


async def test_run_agent_handles_assistant_chat_without_chat_id():
    """No tool calls expected when ASSISTANT_CHAT has no chatId."""
    events = [e async for e in og.run_agent(_req(text="hi"))]
    names = [e.name for e in events]
    assert all(n != "tool_call" for n in names), "should not call tools without chatId"
    assert "delta" in names and "done" in names


# ---------------- Day 17 follow-up: LangChain @tool layer integration ----------------


async def test_meta_event_advertises_langchain_tool_layer():
    """Resume claim 'wrap 7 tools with @tool' should be falsifiable: the
    meta event explicitly tags this engine as using the LangChain tool
    layer so traces / dashboards can prove it at runtime."""
    events = [e async for e in og.run_agent(_req(text="hi"))]
    meta = events[0]
    assert meta.name == "meta"
    assert meta.data.get("toolLayer") == "langchain"


async def test_reason_node_uses_langchain_derived_schemas_when_provider_present(monkeypatch):
    """The reason_node must call client.complete with schemas produced
    by convert_langchain_tools_to_openai — proves langchain_tools is
    the live source of truth on this engine, not a parallel demo."""
    captured: dict[str, Any] = {}

    class _FakeChunk:
        def __init__(self):
            self.kind = "text"
            self.text = "ok"
            self.tool_call = None
            self.usage = None

    class _FakeClient:
        async def complete(self, messages, *, tools, tool_choice, temperature,
                           max_tokens, timeout_sec):
            captured["tools"] = tools
            captured["tool_choice"] = tool_choice
            yield _FakeChunk()

    monkeypatch.setattr(og, "build_client", lambda cfg: _FakeClient())

    # Force the reason_node off the mock pathway by giving it a
    # ProviderConfig — the fake client above ignores it.
    state: og.AgentState = {
        "messages": [{"role": "user", "content": "hi"}],
        "iteration": 0,
        "final_answer": "",
        "structured": {},
        "usage_in": 0,
        "usage_out": 0,
        "tool_calls_summary": [],
        "side_events": [],
        "request": _req(text="hi"),
        "cfg": og.ProviderConfig(name="openai", base_url=None,
                                 model="gpt-4.1-mini", api_key="sk-test"),
        "forced_tool": None,
        "actor_user_id": 42,
        "trace_id": "tr_test",
        "max_iterations": 6,
    }
    await og.reason_node(state)

    from app.langchain_tools import convert_langchain_tools_to_openai
    expected_names = {s["function"]["name"]
                      for s in convert_langchain_tools_to_openai(42, "tr_test")}
    actual_names = {s["function"]["name"] for s in captured["tools"]}
    assert actual_names == expected_names
    assert len(captured["tools"]) == 7

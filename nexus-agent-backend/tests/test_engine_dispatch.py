"""Day 17 tests: settings.engine dispatcher + parity smoke.

Verifies that:
  - settings.engine="handcrafted" routes to orchestrator.run_agent
  - settings.engine="langgraph" routes to orchestrator_langgraph.run_agent
  - unknown values fall back to handcrafted (operator-config safety)
  - both engines, when invoked with the same request, emit the same
    canonical SSE event names (parity smoke under mock pathway)

We patch the routes' internal lookup function rather than the engines
themselves so we don't have to spin up FastAPI's TestClient for a
control-flow check.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

os.environ.pop("OPENAI_API_KEY", None)
os.environ["USE_REAL_MODEL"] = "false"
os.environ["REDIS_URL"] = "redis://127.0.0.1:1"


from app import orchestrator, orchestrator_langgraph, routes  # noqa: E402
from app.config import get_settings  # noqa: E402
from app.rag import memory_rag  # noqa: E402
from app.schemas import Actor, InputPayload, InvokeRequest, Options, Session  # noqa: E402


@pytest.fixture(autouse=True)
def _disable_rag(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "memory_rag_enabled", False)
    monkeypatch.setattr(s, "knowledge_rag_enabled", False)


@pytest.fixture(autouse=True)
def _stub_memory_writes(monkeypatch):
    async def _noop(*a, **kw):
        return None
    monkeypatch.setattr(memory_rag, "write", _noop)


@pytest.fixture(autouse=True)
def _isolate_pending_writes():
    orchestrator._pending_rag_writes.clear()
    yield
    orchestrator._pending_rag_writes.clear()


class _FakeToolExecutor:
    def __init__(self, *_a, **_kw): pass
    async def __aenter__(self): return self
    async def __aexit__(self, *exc): return False
    async def execute(self, name, args):
        return {"chatId": args.get("chat_id"), "messages": []}, 5


@pytest.fixture(autouse=True)
def _patch_tools(monkeypatch):
    monkeypatch.setattr(orchestrator, "ToolExecutor", _FakeToolExecutor)
    monkeypatch.setattr(orchestrator_langgraph, "ToolExecutor", _FakeToolExecutor)


def _req():
    return InvokeRequest(
        traceId="tr_disp",
        actor=Actor(userId=1, username="alice"),
        session=Session(sessionId="s_disp", operationType="ASSISTANT_CHAT"),
        input=InputPayload(text="hi"),
        options=Options(),
    )


# ---------------- dispatcher ----------------


def test_default_engine_resolves_to_handcrafted(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "engine", "handcrafted")
    runner = routes._resolve_engine()
    assert runner is orchestrator.run_agent


def test_engine_langgraph_resolves_to_langgraph_runner(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "engine", "langgraph")
    runner = routes._resolve_engine()
    assert runner is orchestrator_langgraph.run_agent


def test_engine_unknown_value_falls_back_to_handcrafted(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "engine", "experimental-graph-3")
    runner = routes._resolve_engine()
    assert runner is orchestrator.run_agent


def test_engine_value_is_case_insensitive(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "engine", "LangGraph")
    runner = routes._resolve_engine()
    assert runner is orchestrator_langgraph.run_agent


def test_engine_value_with_whitespace_normalises(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "engine", "  langgraph  ")
    runner = routes._resolve_engine()
    assert runner is orchestrator_langgraph.run_agent


# ---------------- parity smoke ----------------


async def test_both_engines_emit_canonical_event_sequence_for_same_request(monkeypatch):
    """Same request, two engines — both must emit meta + delta + usage +
    done + __final__ in the same order. Tool / RAG diffs are out of
    scope for this smoke (covered by their own tests)."""
    s = get_settings()

    monkeypatch.setattr(s, "engine", "handcrafted")
    handcrafted_events = [e async for e in routes._resolve_engine()(_req())]

    monkeypatch.setattr(s, "engine", "langgraph")
    langgraph_events = [e async for e in routes._resolve_engine()(_req())]

    canonical = {"meta", "delta", "usage", "done", "__final__"}
    h_names = [e.name for e in handcrafted_events]
    lg_names = [e.name for e in langgraph_events]
    assert canonical.issubset(set(h_names))
    assert canonical.issubset(set(lg_names))
    assert h_names[0] == "meta" and lg_names[0] == "meta"
    assert h_names[-1] == "__final__" and lg_names[-1] == "__final__"
    assert h_names[-2] == "done" and lg_names[-2] == "done"


async def test_health_endpoint_reports_current_engine(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "engine", "langgraph")
    body = routes.health()
    assert body["engine"] == "langgraph"

    monkeypatch.setattr(s, "engine", "handcrafted")
    body = routes.health()
    assert body["engine"] == "handcrafted"

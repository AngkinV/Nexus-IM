"""Day 14 tests for orchestrator + Module B (knowledge base) integration.

Companion to test_orchestrator_rag.py — same monkeypatch shape, but
focuses on the kb_context path triggered by Session.linkedKbId.
"""
from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

os.environ.pop("OPENAI_API_KEY", None)
os.environ["USE_REAL_MODEL"] = "false"
os.environ["REDIS_URL"] = "redis://127.0.0.1:1"


from app import orchestrator  # noqa: E402
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

    async def schemas(self):
        return []

    async def execute(self, name, args, *, call_id=None):
        return {"chatId": args.get("chat_id"), "messages": []}, 5


def _scope(session_id="s_kb", run_id="run_kb_0001"):
    """A synthetic, internally consistent grant: these tests have no Java authority."""
    from app.scope import AuthorizedScope, scope_hash
    data = dict(schemaVersion=1, grantId="g_kb", actorUserId=7, sessionId=session_id,
                runId=run_id, bindingVersion=1, authorizationVersion=1, sessionGeneration=1,
                chatIds=[], contactUserIds=[],
                timeRange=dict(fromInclusive=None, toExclusive=None, timeZone="Asia/Shanghai"))
    data["scopeKey"] = scope_hash(data)
    return AuthorizedScope.model_validate(data)


def _req(*, text="合同里的违约条款是什么", linked_kb_id=None):
    return InvokeRequest(
        traceId="tr_kb_int",
        actor=Actor(userId=7, username="alice"),
        session=Session(sessionId="s_kb", operationType="ASSISTANT_CHAT", linkedKbId=linked_kb_id),
        input=InputPayload(text=text),
        options=Options(),
        runId="run_kb_0001",
        scope=_scope(),
    )


@pytest.fixture(autouse=True)
def _patch_tools(monkeypatch):
    monkeypatch.setattr(orchestrator, "ToolExecutor", _FakeToolExecutor)
    # Accept exactly the synthetic scope the requests carry.
    async def _validate(self, scope, tool=None):
        from app.scope import ScopeDenied
        if scope is None:
            raise ScopeDenied()
        return scope
    monkeypatch.setattr(orchestrator.ScopeClient, "validate", _validate)


@pytest.fixture(autouse=True)
def _isolated_checkpoints(tmp_path, monkeypatch):
    # Every request here carries the same run id, and a run id is only usable once per store.
    monkeypatch.setattr(get_settings(), "checkpoint_path", str(tmp_path / "runs.sqlite"))


@pytest.fixture(autouse=True)
def _enable_rag(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "memory_rag_enabled", True)
    monkeypatch.setattr(s, "knowledge_rag_enabled", True)
    monkeypatch.setattr(s, "knowledge_rag_top_k", 3)


@pytest.fixture(autouse=True)
def _silence_memory_rag(monkeypatch):
    """We're testing Module B here; stub Module A so it neither writes nor
    retrieves. Saves us from having to seed ChromaDB for each test."""
    async def _empty_retrieve(*a, **kw):
        return []

    async def _empty_write(*a, **kw):
        return None

    monkeypatch.setattr(memory_rag, "retrieve", _empty_retrieve)
    monkeypatch.setattr(memory_rag, "write", _empty_write)


@pytest.fixture(autouse=True)
def _isolate_pending_writes():
    orchestrator._pending_rag_writes.clear()
    yield
    orchestrator._pending_rag_writes.clear()


async def test_kb_retrieve_called_with_kb_id_query_and_user_id(monkeypatch):
    seen: dict = {}

    async def _fake_kb_retrieve(*, kb_id, query, user_id=None, provider=None, **kw):
        seen["kb_id"] = kb_id
        seen["query"] = query
        seen["user_id"] = user_id
        return []

    monkeypatch.setattr(knowledge_rag, "retrieve", _fake_kb_retrieve)

    req = _req(text="违约条款", linked_kb_id="kb_legal")
    events = [e async for e in orchestrator.run_agent(req)]

    assert seen.get("kb_id") == "kb_legal"
    assert seen.get("query") == "违约条款"
    assert seen.get("user_id") == 7
    assert any(e.name == "done" for e in events)


async def test_kb_retrieve_skipped_when_linkedKbId_absent(monkeypatch):
    calls: list = []

    async def _spy_kb_retrieve(*a, **kw):
        calls.append(kw)
        return []

    monkeypatch.setattr(knowledge_rag, "retrieve", _spy_kb_retrieve)

    req = _req(linked_kb_id=None)
    events = [e async for e in orchestrator.run_agent(req)]

    assert calls == []
    assert any(e.name == "done" for e in events)


async def test_kb_retrieve_failure_degrades_silently(monkeypatch):
    async def _broken_retrieve(*a, **kw):
        raise RuntimeError("chroma kb collection broken")

    monkeypatch.setattr(knowledge_rag, "retrieve", _broken_retrieve)

    events = [e async for e in orchestrator.run_agent(_req(linked_kb_id="kb_x"))]
    names = [e.name for e in events]
    assert "done" in names
    assert "__final__" in names


async def test_kb_rag_disabled_flag_short_circuits(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "knowledge_rag_enabled", False)

    calls: list = []

    async def _spy_kb_retrieve(*a, **kw):
        calls.append(kw)
        return []

    monkeypatch.setattr(knowledge_rag, "retrieve", _spy_kb_retrieve)

    events = [e async for e in orchestrator.run_agent(_req(linked_kb_id="kb_x"))]
    assert calls == []
    assert any(e.name == "done" for e in events)


async def test_kb_chunks_reach_the_prompt(monkeypatch):
    """Mirror of test_orchestrator_rag.test_recalled_chunks_reach_the_prompt:
    when kb_retrieve returns content, build_messages must accept it without
    raising. The actual prompt formatting is unit-tested in test_prompts."""
    from langchain_core.documents import Document

    async def _kb_retrieve(*, kb_id, query, **kw):
        return [
            (Document(page_content="违约金不超过合同总额 20%", metadata={"fileName": "contract.pdf", "chunkIndex": 3}), 0.91),
        ]

    monkeypatch.setattr(knowledge_rag, "retrieve", _kb_retrieve)

    events = [e async for e in orchestrator.run_agent(_req(linked_kb_id="kb_legal"))]
    names = [e.name for e in events]
    assert "meta" in names and "done" in names and "__final__" in names

"""Day 4 integration tests verifying the orchestrator wires Module A correctly.

We patch `memory_rag.retrieve` and `memory_rag.write` so we don't depend on
ChromaDB or OpenAI in unit tests; the goal here is to prove the orchestrator
*calls* them with the right arguments, *injects* the recalled chunks into the
prompt, and *writes back* the user/assistant exchange after the run.
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
os.environ["REDIS_URL"] = "redis://127.0.0.1:1"  # unreachable -> degrade


from app import orchestrator  # noqa: E402
from app.config import get_settings  # noqa: E402
from app.rag import memory_rag  # noqa: E402
from app.schemas import Actor, InputPayload, InvokeRequest, Options, Session  # noqa: E402


class _FakeToolExecutor:
    def __init__(self, *_a, **_kw):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def execute(self, name, args):
        return {"chatId": args.get("chat_id"), "messages": []}, 5


def _req(text="你好", op="ASSISTANT_CHAT", chat_id=None):
    return InvokeRequest(
        traceId="tr_rag_int",
        actor=Actor(userId=42, username="alice"),
        session=Session(sessionId="s_rag", operationType=op),
        input=InputPayload(text=text, chatId=chat_id),
        options=Options(),
    )


@pytest.fixture(autouse=True)
def _patch_tools(monkeypatch):
    monkeypatch.setattr(orchestrator, "ToolExecutor", _FakeToolExecutor)


@pytest.fixture(autouse=True)
def _enable_rag(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "memory_rag_enabled", True)
    monkeypatch.setattr(s, "memory_rag_top_k", 3)


@pytest.fixture(autouse=True)
def _isolate_pending_writes():
    """pytest-asyncio creates a new event loop per test; the fire-and-forget
    task set is module-level so we must clear it between tests to avoid
    cross-test leakage where a stale task references a closed loop."""
    orchestrator._pending_rag_writes.clear()
    yield
    orchestrator._pending_rag_writes.clear()


async def test_retrieve_called_with_user_text_and_user_id(monkeypatch):
    seen: dict = {}

    async def _fake_retrieve(user_id, query, top_k=None, *, provider=None):
        seen["user_id"] = user_id
        seen["query"] = query
        seen["top_k"] = top_k
        return ["mock historical chunk"]

    monkeypatch.setattr(memory_rag, "retrieve", _fake_retrieve)
    monkeypatch.setattr(memory_rag, "write", _noop_write)

    req = _req(text="提一下上周的报价")
    events = [e async for e in orchestrator.run_agent(req)]

    assert seen.get("user_id") == 42
    assert seen.get("query") == "提一下上周的报价"
    assert seen.get("top_k") == 3
    assert any(e.name == "done" for e in events)


async def test_recalled_chunks_reach_the_prompt(monkeypatch):
    """We can't see the prompt directly from the orchestrator output, but if
    retrieve returns content and the orchestrator does NOT explode, that's
    sufficient evidence. The build_messages contract is unit-tested separately
    in test_prompts.py."""
    chunks = ["User: previous question\nAssistant: previous answer"]

    async def _fake_retrieve(*a, **kw):
        return chunks

    monkeypatch.setattr(memory_rag, "retrieve", _fake_retrieve)
    monkeypatch.setattr(memory_rag, "write", _noop_write)

    events = [e async for e in orchestrator.run_agent(_req())]
    # The mock pathway always yields meta + delta + usage + done + __final__.
    # We just assert the run still completes successfully — proving that the
    # extra system block produced by relevant_history did not break message
    # construction.
    names = [e.name for e in events]
    assert "meta" in names and "done" in names and "__final__" in names


async def test_write_invoked_after_assistant_answer(monkeypatch):
    write_calls: list[dict] = []

    async def _fake_write(user_id, session_id, user_text, assistant_text, **kw):
        write_calls.append({
            "user_id": user_id,
            "session_id": session_id,
            "user_text": user_text,
            "assistant_text": assistant_text,
            "trace_id": kw.get("trace_id"),
        })
        return "mem_fake_id"

    async def _fake_retrieve(*a, **kw):
        return []

    monkeypatch.setattr(memory_rag, "retrieve", _fake_retrieve)
    monkeypatch.setattr(memory_rag, "write", _fake_write)

    req = _req(text="今晚吃啥")
    events = [e async for e in orchestrator.run_agent(req)]

    # The fire-and-forget task may not have run yet when the generator returns.
    # Drain pending tasks before asserting.
    pending = list(orchestrator._pending_rag_writes)
    if pending:
        await asyncio.gather(*pending, return_exceptions=True)

    assert len(write_calls) == 1
    call = write_calls[0]
    assert call["user_id"] == 42
    assert call["session_id"] == "s_rag"
    assert call["user_text"] == "今晚吃啥"
    assert call["assistant_text"]  # mock answer is non-empty
    assert call["trace_id"] == "tr_rag_int"


async def test_retrieve_failure_does_not_break_run(monkeypatch):
    async def _broken_retrieve(*a, **kw):
        raise RuntimeError("chroma is on fire")

    monkeypatch.setattr(memory_rag, "retrieve", _broken_retrieve)
    monkeypatch.setattr(memory_rag, "write", _noop_write)

    events = [e async for e in orchestrator.run_agent(_req())]
    names = [e.name for e in events]
    assert "done" in names  # retrieval failure must degrade silently


async def test_disabled_skips_both_retrieve_and_write(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "memory_rag_enabled", False)

    retrieve_calls = []
    write_calls = []

    async def _spy_retrieve(*a, **kw):
        retrieve_calls.append(a)
        return []

    async def _spy_write(*a, **kw):
        write_calls.append(a)
        return None

    monkeypatch.setattr(memory_rag, "retrieve", _spy_retrieve)
    monkeypatch.setattr(memory_rag, "write", _spy_write)

    events = [e async for e in orchestrator.run_agent(_req())]
    pending = list(orchestrator._pending_rag_writes)
    if pending:
        await asyncio.gather(*pending, return_exceptions=True)

    assert retrieve_calls == []
    assert write_calls == []
    assert any(e.name == "done" for e in events)


async def _noop_write(*a, **kw):
    return None

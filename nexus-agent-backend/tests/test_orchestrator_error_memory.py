"""P0-A regression: failed / timed-out turns must never reach memory,
and the persisted answer must equal the concatenation of streamed deltas.

Drives orchestrator.run_agent on the REAL pathway with a fake LLM client
(no network), asserting the SSE contract and the memory side effects.
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

from app import orchestrator  # noqa: E402
from app.config import get_settings  # noqa: E402
from app.llm import LLMChunk, ProviderConfig  # noqa: E402
from app.rag import memory_rag  # noqa: E402
from app.schemas import Actor, InputPayload, InvokeRequest, Options, Session  # noqa: E402


class _FakeMemory:
    def __init__(self):
        self.turns: list[tuple[str, str]] = []

    async def recent(self, *a, **kw):
        return []

    async def append(self, user_id, session_id, role, text, **kwargs):
        self.turns.append((role, text))


class _FakeToolExecutor:
    def __init__(self, *_a, **_kw): pass
    async def __aenter__(self): return self
    async def __aexit__(self, *exc): return False
    async def schemas(self): return []
    async def execute(self, name, args):
        return {}, 1


def _req(text="hi"):
    return InvokeRequest(
        traceId="tr_err",
        actor=Actor(userId=1, username="alice"),
        session=Session(sessionId="s_err", operationType="ASSISTANT_CHAT"),
        input=InputPayload(text=text),
        options=Options(),
    )


@pytest.fixture(autouse=True)
def _env(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "memory_rag_enabled", False)
    monkeypatch.setattr(s, "knowledge_rag_enabled", False)
    monkeypatch.setattr(orchestrator, "ToolExecutor", _FakeToolExecutor)
    monkeypatch.setattr(
        orchestrator, "_resolve_provider_config",
        lambda provider: ProviderConfig(name="openai", base_url=None,
                                        model="m", api_key="sk-test"),
    )

    async def _noop(*a, **kw):
        return None
    monkeypatch.setattr(memory_rag, "write", _noop)
    orchestrator._pending_rag_writes.clear()
    yield
    orchestrator._pending_rag_writes.clear()


@pytest.fixture
def fake_memory(monkeypatch):
    mem = _FakeMemory()
    monkeypatch.setattr(orchestrator, "get_memory", lambda: mem)
    return mem


def _install_client(monkeypatch, chunks_or_exc):
    class _FakeClient:
        async def complete(self, messages, tools=None, **kw):
            for item in chunks_or_exc:
                if isinstance(item, Exception):
                    raise item
                yield item

    monkeypatch.setattr(orchestrator, "build_client", lambda cfg: _FakeClient())


async def test_error_turn_keeps_partial_text_and_stays_out_of_memory(monkeypatch, fake_memory):
    _install_client(monkeypatch, [
        LLMChunk(kind="text", text="部分回答"),
        RuntimeError("provider exploded"),
    ])
    events = [e async for e in orchestrator.run_agent(_req())]
    names = [e.name for e in events]

    assert "error" in names
    done = next(e for e in events if e.name == "done")
    assert done.data["finishReason"] == "error"
    final = events[-1].data["result"]
    assert final["answer"] == "部分回答"
    assert final["finishReason"] == "error"
    # The user turn is remembered; the failed assistant turn is not.
    assert fake_memory.turns == [("user", "hi")]


async def test_timeout_turn_not_remembered(monkeypatch, fake_memory):
    _install_client(monkeypatch, [
        LLMChunk(kind="text", text="部分回答"),
        LLMChunk(kind="finish", finish_reason="timeout"),
    ])
    events = [e async for e in orchestrator.run_agent(_req())]
    done = next(e for e in events if e.name == "done")
    assert done.data["finishReason"] == "timeout"
    assert events[-1].data["result"]["answer"] == "部分回答"
    assert fake_memory.turns == [("user", "hi")]


async def test_clean_turn_is_remembered(monkeypatch, fake_memory):
    _install_client(monkeypatch, [
        LLMChunk(kind="text", text="完整回答"),
        LLMChunk(kind="finish", finish_reason="stop"),
    ])
    events = [e async for e in orchestrator.run_agent(_req())]
    done = next(e for e in events if e.name == "done")
    assert done.data["finishReason"] == "stop"
    assert fake_memory.turns == [("user", "hi"), ("assistant", "完整回答")]


async def test_final_answer_equals_concat_of_streamed_deltas(monkeypatch, fake_memory):
    _install_client(monkeypatch, [
        LLMChunk(kind="text", text="你好"),
        LLMChunk(kind="text", text="世界"),
        LLMChunk(kind="finish", finish_reason="stop"),
    ])
    events = [e async for e in orchestrator.run_agent(_req())]
    deltas = [e.data["text"] for e in events if e.name == "delta"]
    assert deltas == ["你好", "世界"]
    assert events[-1].data["result"]["answer"] == "你好世界"


async def test_error_without_any_text_reports_failure(monkeypatch, fake_memory):
    _install_client(monkeypatch, [RuntimeError("bad key")])
    events = [e async for e in orchestrator.run_agent(_req())]
    assert any(e.name == "error" for e in events)
    final = events[-1].data["result"]
    assert final["answer"] == ""
    assert final["finishReason"] == "error"
    assert fake_memory.turns == [("user", "hi")]


async def test_error_chunk_turn_stays_out_of_memory(monkeypatch, fake_memory):
    _install_client(monkeypatch, [
        LLMChunk(kind="text", text="部分回答"),
        LLMChunk(kind="error", text="provider exploded"),
        LLMChunk(kind="finish", finish_reason="error"),
    ])
    events = [e async for e in orchestrator.run_agent(_req())]
    names = [e.name for e in events]
    assert "error" in names
    done = next(e for e in events if e.name == "done")
    assert done.data["finishReason"] == "error"
    assert events[-1].data["result"]["answer"] == "部分回答"
    assert fake_memory.turns == [("user", "hi")]


async def test_non_stream_invoke_synthesizes_display_only_error_note(monkeypatch, fake_memory):
    from app import routes

    _install_client(monkeypatch, [RuntimeError("bad key")])
    monkeypatch.setattr(orchestrator, "get_memory", lambda: fake_memory)
    ctx = {"actorUserId": 1, "traceId": "tr_err", "provider": None}
    resp = await routes.invoke(_req(), ctx=ctx)
    assert "模型调用失败" in resp.result.answer
    assert "bad key" in resp.result.answer
    assert resp.result.finishReason == "error"
    # The note is display-only: memory still holds just the user turn.
    assert fake_memory.turns == [("user", "hi")]
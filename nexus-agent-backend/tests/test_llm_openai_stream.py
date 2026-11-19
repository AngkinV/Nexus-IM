"""P0-A adapter tests: true streaming, idle timeout, error semantics.

Fakes the AsyncOpenAI SDK so no network / httpx client is involved.
Proves:
  - text deltas reach the consumer as they arrive (not buffered)
  - tool-call argument fragments are accumulated and emitted once,
    and only when the stream ended cleanly
  - timeout_sec is an idle timeout: partial text survives, finish
    reason is "timeout"
  - mid-stream / create failures surface as display-only error chunks
"""
from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

os.environ.pop("OPENAI_API_KEY", None)
os.environ["USE_REAL_MODEL"] = "false"

import openai  # noqa: E402

from app.llm import LLMChunk, ProviderConfig  # noqa: E402
from app.llm.openai_like import OpenAILikeClient  # noqa: E402


def _cfg() -> ProviderConfig:
    return ProviderConfig(name="openai", base_url=None, model="gpt-4.1-mini", api_key="sk-test")


def _text_chunk(text: str, finish: str | None = None):
    return SimpleNamespace(
        usage=None,
        choices=[SimpleNamespace(
            finish_reason=finish,
            delta=SimpleNamespace(content=text, tool_calls=None),
        )],
    )


def _tool_delta_chunk(*, id=None, name=None, args=None, finish=None, index=0):
    tc = SimpleNamespace(index=index, id=id, function=SimpleNamespace(name=name, arguments=args))
    return SimpleNamespace(
        usage=None,
        choices=[SimpleNamespace(
            finish_reason=finish,
            delta=SimpleNamespace(content=None, tool_calls=[tc]),
        )],
    )


def _usage_chunk(prompt=10, completion=5):
    return SimpleNamespace(
        usage=SimpleNamespace(prompt_tokens=prompt, completion_tokens=completion,
                              total_tokens=prompt + completion),
        choices=[],
    )


class _FakeStream:
    """Items are chunk objects, Exception instances (raised), or
    ("sleep", seconds) tuples (silence). `log` records production order."""

    def __init__(self, items, log=None):
        self._items = list(items)
        self._log = log
        self.closed = False

    def __aiter__(self):
        return self

    async def __anext__(self):
        if not self._items:
            raise StopAsyncIteration
        item = self._items.pop(0)
        if isinstance(item, tuple) and item[0] == "sleep":
            await asyncio.sleep(item[1])
            return await self.__anext__()
        if isinstance(item, Exception):
            raise item
        if self._log is not None and item.choices[0].delta.content:
            self._log.append(("prod", item.choices[0].delta.content))
        return item

    async def close(self):
        self.closed = True


@pytest.fixture
def patch_openai(monkeypatch):
    holder: dict = {}

    def _install(items, create_exc=None, log=None):
        class _FakeCompletions:
            async def create(self, **kwargs):
                if create_exc is not None:
                    raise create_exc
                stream = _FakeStream(items, log)
                holder["stream"] = stream
                holder["kwargs"] = kwargs
                return stream

        class _FakeAsyncOpenAI:
            def __init__(self, **kwargs):
                self.chat = SimpleNamespace(completions=_FakeCompletions())

        monkeypatch.setattr(openai, "AsyncOpenAI", _FakeAsyncOpenAI)
        return holder

    return _install


async def _collect(client, timeout_sec=5):
    return [c async for c in client.complete([], tools=None, timeout_sec=timeout_sec)]


async def test_text_deltas_are_yielded_live_not_buffered(patch_openai):
    log: list = []
    patch_openai([_text_chunk("a"), ("sleep", 0.05), _text_chunk("b"),
                  _text_chunk("", finish="stop")], log=log)
    client = OpenAILikeClient(_cfg())
    async for chunk in client.complete([], tools=None, timeout_sec=5):
        if chunk.kind == "text":
            log.append(("cons", chunk.text))
    # Buffered implementation would produce both chunks before consuming either.
    assert log == [("prod", "a"), ("cons", "a"), ("prod", "b"), ("cons", "b")]


async def test_stream_is_requested_with_stream_and_usage_options(patch_openai):
    holder = patch_openai([_text_chunk("ok", finish="stop")])
    await _collect(OpenAILikeClient(_cfg()))
    assert holder["kwargs"]["stream"] is True
    assert holder["kwargs"]["stream_options"] == {"include_usage": True}


async def test_tool_call_arguments_accumulated_across_deltas(patch_openai):
    patch_openai([
        _tool_delta_chunk(id="call_1", name="get_recent_messages", args='{"chat_id":'),
        _tool_delta_chunk(args=" 20001}", finish="tool_calls"),
    ])
    chunks = await _collect(OpenAILikeClient(_cfg()))
    tool_chunks = [c for c in chunks if c.kind == "tool_call"]
    assert len(tool_chunks) == 1
    tc = tool_chunks[0].tool_call
    assert tc.id == "call_1"
    assert tc.name == "get_recent_messages"
    assert tc.arguments == {"chat_id": 20001}
    assert chunks[-1].finish_reason == "tool_calls"


async def test_idle_timeout_keeps_partial_text_and_marks_timeout(patch_openai):
    holder = patch_openai([_text_chunk("部分回答"), ("sleep", 0.5)])
    chunks = await _collect(OpenAILikeClient(_cfg()), timeout_sec=0.2)
    kinds = [c.kind for c in chunks]
    assert kinds == ["text", "error", "finish"]
    assert chunks[0].text == "部分回答"
    assert chunks[-1].finish_reason == "timeout"
    assert holder["stream"].closed is True


async def test_incomplete_tool_args_not_emitted_on_timeout(patch_openai):
    patch_openai([_tool_delta_chunk(id="call_1", name="t", args='{"chat_id":'),
                  ("sleep", 0.5)])
    chunks = await _collect(OpenAILikeClient(_cfg()), timeout_sec=0.2)
    assert all(c.kind != "tool_call" for c in chunks)
    assert chunks[-1].finish_reason == "timeout"


async def test_mid_stream_exception_yields_error_chunk(patch_openai):
    patch_openai([_text_chunk("部分回答"), RuntimeError("provider exploded")])
    chunks = await _collect(OpenAILikeClient(_cfg()))
    kinds = [c.kind for c in chunks]
    assert kinds == ["text", "error", "finish"]
    assert "provider exploded" in chunks[1].text
    assert chunks[0].text == "部分回答"
    assert chunks[-1].finish_reason == "error"


async def test_create_failure_yields_error_and_finish(patch_openai):
    patch_openai([], create_exc=RuntimeError("invalid api key"))
    chunks = await _collect(OpenAILikeClient(_cfg()))
    assert [c.kind for c in chunks] == ["error", "finish"]
    assert "invalid api key" in chunks[0].text
    assert chunks[-1].finish_reason == "error"


async def test_create_idle_timeout_yields_timeout(monkeypatch):
    class _SlowCompletions:
        async def create(self, **kwargs):
            await asyncio.sleep(0.5)
            raise AssertionError("should have timed out")

    class _SlowOpenAI:
        def __init__(self, **kwargs):
            self.chat = SimpleNamespace(completions=_SlowCompletions())

    monkeypatch.setattr(openai, "AsyncOpenAI", _SlowOpenAI)
    chunks = await _collect(OpenAILikeClient(_cfg()), timeout_sec=0.2)
    assert [c.kind for c in chunks] == ["error", "finish"]
    assert chunks[-1].finish_reason == "timeout"


async def test_usage_chunk_forwarded(patch_openai):
    patch_openai([_text_chunk("ok"), _usage_chunk(prompt=10, completion=5),
                  _text_chunk("", finish="stop")])
    chunks = await _collect(OpenAILikeClient(_cfg()))
    usage_chunks = [c for c in chunks if c.kind == "usage"]
    assert len(usage_chunks) == 1
    u = usage_chunks[0].usage
    assert (u.input_tokens, u.output_tokens, u.total_tokens) == (10, 5, 15)


async def test_reasoning_deltas_forwarded_separately(patch_openai):
    reasoning_chunk = SimpleNamespace(
        usage=None,
        choices=[SimpleNamespace(
            finish_reason=None,
            delta=SimpleNamespace(content=None, tool_calls=None, reasoning_content="想一想"),
        )],
    )
    patch_openai([reasoning_chunk, _text_chunk("答", finish="stop")])
    chunks = await _collect(OpenAILikeClient(_cfg()))
    assert chunks[0].kind == "reasoning" and chunks[0].text == "想一想"
    assert chunks[1].kind == "text" and chunks[1].text == "答"
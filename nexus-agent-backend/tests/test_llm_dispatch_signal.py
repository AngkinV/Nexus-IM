"""Each adapter says when its request is about to leave the process, and only then.

The run counts a model call, and admits it against its budget, at that moment:
an SDK that is missing or a client that cannot be built is not a model call.
"""
from __future__ import annotations

import pytest

from app.llm import ProviderConfig
from app.llm.anthropic_client import AnthropicClient
from app.llm.gemini_client import GeminiClient
from app.llm.openai_like import OpenAILikeClient
from fake_provider import FakeProvider, sse
import test_llm_anthropic_stream as anthropic_case
import test_llm_gemini_stream as gemini_case


def _openai_chunk(delta, finish=None):
    return {"id": "c1", "object": "chat.completion.chunk", "created": 1, "model": "gpt-test",
            "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}


ADAPTERS = {
    "openai": (OpenAILikeClient, sse(_openai_chunk({"role": "assistant", "content": "你好"}), _openai_chunk({}, "stop"))),
    "anthropic": (AnthropicClient, sse(*anthropic_case.TEXT_TURN, named=True)),
    "gemini": (GeminiClient, sse(*gemini_case.TEXT_TURN)),
}
MESSAGES = [{"role": "user", "content": "hi"}]


def _client(name, server):
    return ADAPTERS[name][0](ProviderConfig(name=name, base_url=server.base_url, model=f"{name}-test", api_key="k-test"))


@pytest.mark.parametrize("name", sorted(ADAPTERS))
async def test_the_request_leaves_only_after_the_adapter_says_so(name):
    async with FakeProvider([{"body": ADAPTERS[name][1]}]) as server:
        sent_before = []

        async def on_dispatch():
            sent_before.append(len(server.requests))

        client = _client(name, server)
        assert client.signals_dispatch is True
        chunks = [c async for c in client.complete(MESSAGES, None, on_dispatch=on_dispatch, timeout_sec=5)]
        assert sent_before == [0] and len(server.requests) == 1
    assert chunks[-1].kind == "finish" and chunks[-1].finish_reason == "stop"


@pytest.mark.parametrize("name", sorted(ADAPTERS))
async def test_a_refused_dispatch_sends_nothing_and_is_not_reported_as_a_provider_failure(name):
    async with FakeProvider([{"body": ADAPTERS[name][1]}]) as server:
        async def refuse():
            raise PermissionError("no budget left for another model call")

        with pytest.raises(PermissionError):
            [c async for c in _client(name, server).complete(MESSAGES, None, on_dispatch=refuse, timeout_sec=5)]
        assert server.requests == []

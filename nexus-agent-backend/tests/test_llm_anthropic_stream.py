"""Anthropic adapter, driven through the real SDK against a local scripted server."""
from __future__ import annotations

import pytest

from app.llm import ProviderConfig
from app.llm.anthropic_client import AnthropicClient
from fake_provider import FakeProvider, sse

TOOLS = [{"type": "function", "function": {
    "name": "get_recent_messages",
    "description": "Fetch recent messages",
    "parameters": {"type": "object", "properties": {"chat_id": {"type": "integer"}}, "required": ["chat_id"]},
}}]


def _start(input_tokens=11):
    return {"type": "message_start", "message": {
        "id": "msg_1", "type": "message", "role": "assistant", "model": "claude-test", "content": [],
        "stop_reason": None, "stop_sequence": None,
        "usage": {"input_tokens": input_tokens, "output_tokens": 1}}}


def _text(index, text):
    return {"type": "content_block_delta", "index": index, "delta": {"type": "text_delta", "text": text}}


def _block_start(index, block):
    return {"type": "content_block_start", "index": index, "content_block": block}


def _stop(reason, output_tokens=5):
    return {"type": "message_delta", "delta": {"stop_reason": reason, "stop_sequence": None},
            "usage": {"output_tokens": output_tokens}}


TEXT_TURN = [_start(), _block_start(0, {"type": "text", "text": ""}), _text(0, "你好"), _text(0, "世界"),
             {"type": "content_block_stop", "index": 0}, _stop("end_turn"), {"type": "message_stop"}]


def _client(server: FakeProvider) -> AnthropicClient:
    return AnthropicClient(ProviderConfig(
        name="anthropic", base_url=server.base_url, model="claude-test", api_key="sk-ant-test"))


async def _collect(client, messages=None, **kwargs):
    kwargs.setdefault("timeout_sec", 5)
    return [c async for c in client.complete(messages or [{"role": "user", "content": "hi"}], **kwargs)]


async def test_text_is_streamed_and_usage_reported():
    async with FakeProvider([{"body": sse(*TEXT_TURN, named=True)}]) as server:
        chunks = await _collect(_client(server), tools=None)
    assert [c.text for c in chunks if c.kind == "text"] == ["你好", "世界"]
    usage = next(c.usage for c in chunks if c.kind == "usage")
    assert (usage.input_tokens, usage.output_tokens) == (11, 5)
    assert chunks[-1].kind == "finish" and chunks[-1].finish_reason == "stop"
    [request] = server.requests
    assert request["target"] == "/v1/messages"
    assert request["headers"]["authorization"] == "Bearer sk-ant-test"
    assert request["json"]["stream"] is True
    assert "tools" not in request["json"]


async def test_first_delta_arrives_before_the_stream_ends():
    body = sse(*TEXT_TURN[:3], named=True) + [0.3] + sse(*TEXT_TURN[3:], named=True)
    async with FakeProvider([{"body": body}]) as server:
        stream = _client(server).complete([{"role": "user", "content": "hi"}], tools=None, timeout_sec=5)
        first = await stream.__anext__()
        # The server is still paused before the second delta.
        assert first.kind == "text" and first.text == "你好"
        rest = [c async for c in stream]
    assert rest[-1].finish_reason == "stop"


async def test_tool_call_arguments_are_assembled_from_json_deltas():
    events = [
        _start(),
        _block_start(0, {"type": "tool_use", "id": "toolu_1", "name": "get_recent_messages", "input": {}}),
        {"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": '{"chat_id":'}},
        {"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": " 42}"}},
        {"type": "content_block_stop", "index": 0},
        _stop("tool_use"),
        {"type": "message_stop"},
    ]
    async with FakeProvider([{"body": sse(*events, named=True)}]) as server:
        chunks = await _collect(_client(server), tools=TOOLS, tool_choice="required")
    [call] = [c.tool_call for c in chunks if c.kind == "tool_call"]
    assert (call.id, call.name, call.arguments) == ("toolu_1", "get_recent_messages", {"chat_id": 42})
    assert chunks[-1].finish_reason == "tool_calls"
    sent = server.requests[0]["json"]
    assert sent["tool_choice"] == {"type": "any"}
    assert sent["tools"][0]["name"] == "get_recent_messages"
    assert sent["tools"][0]["input_schema"]["required"] == ["chat_id"]


async def test_tool_history_is_sent_as_tool_use_and_tool_result_blocks():
    messages = [
        {"role": "system", "content": "be brief"},
        {"role": "user", "content": "总结"},
        {"role": "assistant", "content": "", "tool_calls": [
            {"id": "toolu_a", "type": "function", "function": {"name": "get_chat_profile", "arguments": '{"chat_id": 5}'}},
            {"id": "toolu_b", "type": "function", "function": {"name": "get_user_profile", "arguments": '{"user_id": 9}'}},
        ]},
        {"role": "tool", "tool_call_id": "toolu_a", "name": "get_chat_profile", "content": '{"chatName": "A"}'},
        {"role": "tool", "tool_call_id": "toolu_b", "name": "get_user_profile", "content": '{"username": "bob"}'},
    ]
    async with FakeProvider([{"body": sse(*TEXT_TURN, named=True)}]) as server:
        await _collect(_client(server), messages, tools=TOOLS)
    sent = server.requests[0]["json"]
    assert sent["system"] == "be brief"
    assert [m["role"] for m in sent["messages"]] == ["user", "assistant", "user"]
    assert sent["messages"][1]["content"] == [
        {"type": "tool_use", "id": "toolu_a", "name": "get_chat_profile", "input": {"chat_id": 5}},
        {"type": "tool_use", "id": "toolu_b", "name": "get_user_profile", "input": {"user_id": 9}},
    ]
    assert [(b["type"], b["tool_use_id"]) for b in sent["messages"][2]["content"]] == [
        ("tool_result", "toolu_a"), ("tool_result", "toolu_b")]
    assert sent["tool_choice"] == {"type": "auto"}


async def test_rate_limit_is_reported_once_without_retry():
    response = {"status": 429, "headers": {"content-type": "application/json", "retry-after": "12"},
                "body": [b'{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}']}
    async with FakeProvider([response, response]) as server:
        chunks = await _collect(_client(server), tools=None)
        assert len(server.requests) == 1
    assert [c.kind for c in chunks] == ["error", "finish"]
    error = chunks[0].error
    assert (error.code, error.http_status, error.retry_after_seconds) == ("RATE_LIMIT", 429, 12)
    assert chunks[-1].finish_reason == "error"


async def test_idle_timeout_keeps_partial_text():
    body = sse(*TEXT_TURN[:3], named=True) + [2.0] + sse(*TEXT_TURN[3:], named=True)
    async with FakeProvider([{"body": body}]) as server:
        chunks = await _collect(_client(server), tools=None, timeout_sec=0.3)
    assert [c.kind for c in chunks] == ["text", "usage", "error", "finish"]
    assert chunks[0].text == "你好"
    assert chunks[2].error.code == "TIMEOUT"
    assert chunks[-1].finish_reason == "timeout"


async def test_stream_cut_before_stop_reason_is_not_a_clean_stop():
    async with FakeProvider([{"body": sse(*TEXT_TURN[:3], named=True)}]) as server:
        chunks = await _collect(_client(server), tools=None)
    assert chunks[-1].finish_reason == "error"
    assert next(c for c in chunks if c.kind == "error").error.code == "INCOMPLETE"


@pytest.mark.parametrize("reason,expected", [("max_tokens", "length"), ("refusal", "content_filter")])
async def test_stop_reasons_are_normalised(reason, expected):
    events = TEXT_TURN[:5] + [_stop(reason), {"type": "message_stop"}]
    async with FakeProvider([{"body": sse(*events, named=True)}]) as server:
        chunks = await _collect(_client(server), tools=None)
    assert chunks[-1].finish_reason == expected

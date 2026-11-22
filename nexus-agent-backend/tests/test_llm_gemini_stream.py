"""Gemini adapter, driven through the real google-genai SDK against a local scripted server."""
from __future__ import annotations

import asyncio
import base64

from app.llm import ProviderConfig
from app.llm.gemini_client import GeminiClient
from fake_provider import FakeProvider, sse

TOOLS = [{"type": "function", "function": {
    "name": "get_recent_messages",
    "description": "Fetch recent messages",
    "parameters": {"type": "object", "properties": {"chat_id": {"type": "integer"}}, "required": ["chat_id"]},
}}]

USAGE = {"promptTokenCount": 9, "candidatesTokenCount": 4, "totalTokenCount": 13}


def _part(parts, finish=None, usage=None):
    candidate = {"content": {"role": "model", "parts": parts}, "index": 0}
    if finish:
        candidate["finishReason"] = finish
    event = {"candidates": [candidate]}
    if usage:
        event["usageMetadata"] = usage
    return event


TEXT_TURN = [_part([{"text": "你好"}]), _part([{"text": "世界"}], finish="STOP", usage=USAGE)]


def _client(server: FakeProvider, api_key="g-test") -> GeminiClient:
    return GeminiClient(ProviderConfig(name="gemini", base_url=server.base_url, model="gemini-test", api_key=api_key))


async def _collect(client, messages=None, **kwargs):
    kwargs.setdefault("timeout_sec", 5)
    return [c async for c in client.complete(messages or [{"role": "user", "content": "hi"}], **kwargs)]


async def test_text_is_streamed_and_usage_reported():
    async with FakeProvider([{"body": sse(*TEXT_TURN)}]) as server:
        chunks = await _collect(_client(server), [{"role": "system", "content": "be brief"},
                                                  {"role": "user", "content": "hi"}], tools=None)
    assert [c.text for c in chunks if c.kind == "text"] == ["你好", "世界"]
    usage = next(c.usage for c in chunks if c.kind == "usage")
    assert (usage.input_tokens, usage.output_tokens, usage.total_tokens) == (9, 4, 13)
    assert chunks[-1].finish_reason == "stop"
    [request] = server.requests
    assert request["target"].startswith("/v1/models/gemini-test:streamGenerateContent")
    assert request["headers"]["x-goog-api-key"] == "g-test"
    assert request["json"]["systemInstruction"]["parts"][0]["text"] == "be brief"
    assert request["json"]["contents"] == [{"role": "user", "parts": [{"text": "hi"}]}]


async def test_first_delta_arrives_before_the_stream_ends():
    body = sse(TEXT_TURN[0]) + [0.3] + sse(TEXT_TURN[1])
    async with FakeProvider([{"body": body}]) as server:
        stream = _client(server).complete([{"role": "user", "content": "hi"}], tools=None, timeout_sec=5)
        first = await stream.__anext__()
        assert first.kind == "text" and first.text == "你好"
        rest = [c async for c in stream]
    assert rest[-1].finish_reason == "stop"


async def test_tool_call_carries_its_thought_signature_and_sdk_does_not_execute_it():
    signature = base64.b64encode(b"sig-bytes").decode()
    events = [_part([{"functionCall": {"name": "get_recent_messages", "args": {"chat_id": 42}},
                      "thoughtSignature": signature}], finish="STOP", usage=USAGE)]
    async with FakeProvider([{"body": sse(*events)}]) as server:
        chunks = await _collect(_client(server), tools=TOOLS, tool_choice="required")
        assert len(server.requests) == 1
    [call] = [c.tool_call for c in chunks if c.kind == "tool_call"]
    assert (call.name, call.arguments, call.signature) == ("get_recent_messages", {"chat_id": 42}, signature)
    assert chunks[-1].finish_reason == "tool_calls"
    sent = server.requests[0]["json"]
    assert sent["toolConfig"]["functionCallingConfig"]["mode"] == "ANY"
    declaration = sent["tools"][0]["functionDeclarations"][0]
    assert declaration["name"] == "get_recent_messages"
    assert declaration["parameters_json_schema"]["required"] == ["chat_id"]


async def test_tool_history_round_trips_call_signature_and_response():
    signature = base64.b64encode(b"sig-bytes").decode()
    messages = [
        {"role": "user", "content": "总结"},
        {"role": "assistant", "content": "", "tool_calls": [{
            "id": "gem_0", "type": "function", "signature": signature,
            "function": {"name": "get_recent_messages", "arguments": '{"chat_id": 42}'}}]},
        {"role": "tool", "tool_call_id": "gem_0", "name": "get_recent_messages", "content": '{"messages": []}'},
    ]
    async with FakeProvider([{"body": sse(*TEXT_TURN)}]) as server:
        await _collect(_client(server), messages, tools=TOOLS)
    contents = server.requests[0]["json"]["contents"]
    assert [c["role"] for c in contents] == ["user", "model", "user"]
    model_part = contents[1]["parts"][0]
    assert model_part["functionCall"] == {"name": "get_recent_messages", "args": {"chat_id": 42}}
    assert model_part["thoughtSignature"] == signature
    assert contents[2]["parts"][0]["functionResponse"] == {
        "name": "get_recent_messages", "response": {"messages": []}}
    assert server.requests[0]["json"]["toolConfig"]["functionCallingConfig"]["mode"] == "AUTO"


async def test_rate_limit_is_reported_once_without_retry():
    response = {"status": 429, "headers": {"content-type": "application/json"},
                "body": [b'{"error":{"code":429,"message":"quota exceeded","status":"RESOURCE_EXHAUSTED"}}']}
    async with FakeProvider([response] * 5) as server:
        chunks = await _collect(_client(server), tools=None)
        assert len(server.requests) == 1
    assert [c.kind for c in chunks] == ["error", "finish"]
    error = chunks[0].error
    assert (error.code, error.http_status, error.upstream_code) == ("RATE_LIMIT", 429, "RESOURCE_EXHAUSTED")


async def test_idle_timeout_keeps_partial_text():
    body = sse(TEXT_TURN[0]) + [2.0] + sse(TEXT_TURN[1])
    async with FakeProvider([{"body": body}]) as server:
        chunks = await _collect(_client(server), tools=None, timeout_sec=0.3)
    assert [c.kind for c in chunks] == ["text", "error", "finish"]
    assert chunks[1].error.code == "TIMEOUT"
    assert chunks[-1].finish_reason == "timeout"


async def test_abnormal_finish_reason_is_an_error_not_a_stop():
    events = [_part([{"text": "部分"}], finish="MALFORMED_FUNCTION_CALL")]
    async with FakeProvider([{"body": sse(*events)}]) as server:
        chunks = await _collect(_client(server), tools=TOOLS)
    assert chunks[-1].finish_reason == "error"
    assert next(c for c in chunks if c.kind == "error").error.upstream_code == "MALFORMED_FUNCTION_CALL"


async def test_concurrent_users_keep_their_own_key_and_endpoint():
    async with FakeProvider([{"body": sse(TEXT_TURN[0]) + [0.1] + sse(TEXT_TURN[1])}]) as server_a, \
            FakeProvider([{"body": sse(TEXT_TURN[0]) + [0.1] + sse(TEXT_TURN[1])}]) as server_b:
        await asyncio.gather(
            _collect(_client(server_a, api_key="key-of-user-a"), tools=None),
            _collect(_client(server_b, api_key="key-of-user-b"), tools=None),
        )
        assert [r["headers"]["x-goog-api-key"] for r in server_a.requests] == ["key-of-user-a"]
        assert [r["headers"]["x-goog-api-key"] for r in server_b.requests] == ["key-of-user-b"]

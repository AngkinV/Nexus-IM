"""Anthropic Claude adapter.

The Messages API differs from the OpenAI shape used internally: system prompts
go in a top-level `system` field, an assistant's tool calls are `tool_use`
content blocks, and their results are `tool_result` blocks in the next user turn.
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, AsyncIterator

import httpx2

from .base import AnthropicTransport, LLMChunk, LLMClient, LLMError, LLMToolCall, LLMUsage, classify_error, error_chunks

log = logging.getLogger(__name__)

_FINISH = {"end_turn": "stop", "stop_sequence": "stop", "pause_turn": "stop", "tool_use": "tool_calls",
           "max_tokens": "length", "model_context_window_exceeded": "length", "refusal": "content_filter"}


def _to_anthropic(messages: list[dict[str, Any]]) -> tuple[str, list[dict[str, Any]]]:
    system: list[str] = []
    turns: list[dict[str, Any]] = []
    for m in messages:
        role = m.get("role")
        content = m.get("content") or ""
        if role == "system":
            system.append(content)
        elif role == "user":
            turns.append({"role": "user", "content": content})
        elif role == "assistant":
            blocks: list[dict[str, Any]] = [{"type": "text", "text": content}] if content else []
            for call in m.get("tool_calls") or []:
                function = call["function"]
                blocks.append({
                    "type": "tool_use",
                    "id": call["id"],
                    "name": function["name"],
                    "input": json.loads(function["arguments"] or "{}"),
                })
            if blocks:
                turns.append({"role": "assistant", "content": blocks})
        elif role == "tool":
            result = {"type": "tool_result", "tool_use_id": m.get("tool_call_id", ""), "content": content}
            previous = turns[-1] if turns else None
            # Every result for one assistant turn must share a single user turn.
            if previous and previous["role"] == "user" and isinstance(previous["content"], list):
                previous["content"].append(result)
            else:
                turns.append({"role": "user", "content": [result]})
    return "\n\n".join(system), turns


class AnthropicClient(LLMClient):
    signals_dispatch = True

    async def complete(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None,
        *,
        tool_choice: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 1024,
        timeout_sec: int = 30,
        on_dispatch=None,
    ) -> AsyncIterator[LLMChunk]:
        try:
            from anthropic import AsyncAnthropic
        except ImportError:
            for chunk in error_chunks(LLMError("DEPENDENCY", "anthropic SDK is not installed")):
                yield chunk
            return

        system, turns = _to_anthropic(messages)
        # `temperature` is deliberately not sent: the Messages API in this SDK
        # version has no sampling parameters at all.
        kwargs: dict[str, Any] = {
            "model": self.cfg.model,
            "messages": turns,
            "max_tokens": max_tokens,
            "stream": True,
        }
        if system:
            kwargs["system"] = system
        if tools:
            kwargs["tools"] = [{
                "name": t["function"]["name"],
                "description": t["function"].get("description", ""),
                "input_schema": t["function"].get("parameters", {"type": "object", "properties": {}}),
            } for t in tools]
            kwargs["tool_choice"] = {"type": "any" if tool_choice == "required" else "auto"}

        client = AsyncAnthropic(api_key=self.cfg.api_key, base_url=self.cfg.base_url or None, max_retries=0,
                                http_client=httpx2.AsyncClient(transport=AnthropicTransport(), follow_redirects=False, trust_env=False))
        stream = None
        blocks: dict[int, dict[str, str]] = {}
        usage_in = 0
        usage_out = 0
        stop_reason: str | None = None
        error: LLMError | None = None
        try:
            if on_dispatch is not None:
                # Outside the provider error handling: a refusal is the caller's, not the provider's.
                await on_dispatch()
            try:
                stream = await asyncio.wait_for(client.messages.create(**kwargs), timeout=timeout_sec)
                while True:
                    try:
                        event = await asyncio.wait_for(stream.__anext__(), timeout=timeout_sec)
                    except StopAsyncIteration:
                        break
                    if event.type == "message_start":
                        usage_in = getattr(event.message.usage, "input_tokens", 0) or 0
                    elif event.type == "content_block_start":
                        block = event.content_block
                        if block.type == "tool_use":
                            blocks[event.index] = {"id": block.id, "name": block.name, "arguments": ""}
                    elif event.type == "content_block_delta":
                        delta = event.delta
                        if delta.type == "text_delta" and delta.text:
                            yield LLMChunk(kind="text", text=delta.text)
                        elif delta.type == "thinking_delta" and delta.thinking:
                            yield LLMChunk(kind="reasoning", text=delta.thinking)
                        elif delta.type == "input_json_delta" and event.index in blocks:
                            blocks[event.index]["arguments"] += delta.partial_json or ""
                    elif event.type == "message_delta":
                        stop_reason = event.delta.stop_reason or stop_reason
                        usage_out = getattr(event.usage, "output_tokens", 0) or usage_out
            except asyncio.TimeoutError:
                error = LLMError("TIMEOUT", f"model stream idle timeout after {timeout_sec}s")
            except Exception as exc:
                error = classify_error(exc, self.cfg)
                log.warning("Anthropic stream failed: code=%s http=%s", error.code, error.http_status)
        finally:
            if stream is not None:
                await stream.close()
            await client.close()

        if error is None and stop_reason is None:
            error = LLMError("INCOMPLETE", "model stream ended without a stop reason")
        finish = _FINISH.get(stop_reason or "", "stop")

        calls: list[LLMToolCall] = []
        if error is None and finish in ("stop", "tool_calls"):
            for index in sorted(blocks):
                block = blocks[index]
                try:
                    args = json.loads(block["arguments"]) if block["arguments"].strip() else {}
                    if not isinstance(args, dict):
                        raise ValueError("arguments are not a JSON object")
                except ValueError:
                    error = LLMError("BAD_TOOL_CALL", f"model returned malformed arguments for tool {block['name']!r}")
                    break
                calls.append(LLMToolCall(id=block["id"], name=block["name"], arguments=args))

        if usage_in or usage_out:
            yield LLMChunk(kind="usage", usage=LLMUsage(
                input_tokens=usage_in, output_tokens=usage_out, total_tokens=usage_in + usage_out))
        if error is not None:
            for chunk in error_chunks(error):
                yield chunk
            return
        for call in calls:
            yield LLMChunk(kind="tool_call", tool_call=call)
        yield LLMChunk(kind="finish", finish_reason="tool_calls" if calls else finish)

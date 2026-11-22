"""OpenAI-compatible adapter — covers OpenAI, DeepSeek, Moonshot, Zhipu (compat
mode), Tongyi (compat mode), Together, Groq, Ollama, and any other provider that
exposes the OpenAI Chat Completions interface.
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, AsyncIterator

import httpx

from .base import LLMChunk, LLMClient, LLMError, LLMToolCall, LLMUsage, ModelTransport, classify_error, error_chunks

log = logging.getLogger(__name__)

_FINISH = {"stop": "stop", "tool_calls": "tool_calls", "function_call": "tool_calls",
           "length": "length", "content_filter": "content_filter"}


class OpenAILikeClient(LLMClient):
    async def complete(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None,
        *,
        tool_choice: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 1024,
        timeout_sec: int = 30,
    ) -> AsyncIterator[LLMChunk]:
        try:
            from openai import AsyncOpenAI
        except ImportError as exc:
            raise RuntimeError("openai SDK not installed") from exc

        kwargs: dict[str, Any] = {
            "model": self.cfg.model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": True,
            "stream_options": {"include_usage": True},
        }
        if tools:
            kwargs["tools"] = tools
            if tool_choice:
                kwargs["tool_choice"] = tool_choice

        # max_retries=0: a silent retry of a 429 only deepens the rate limit.
        client = AsyncOpenAI(api_key=self.cfg.api_key, base_url=self.cfg.base_url or None, max_retries=0,
                             http_client=httpx.AsyncClient(transport=ModelTransport(), follow_redirects=False, trust_env=False))
        stream = None
        tool_acc: dict[int, dict[str, str]] = {}
        usage: Any = None
        finish_reason: str | None = None
        error: LLMError | None = None
        try:
            try:
                stream = await asyncio.wait_for(client.chat.completions.create(**kwargs), timeout=timeout_sec)
                while True:
                    try:
                        chunk = await asyncio.wait_for(stream.__anext__(), timeout=timeout_sec)
                    except StopAsyncIteration:
                        break
                    if getattr(chunk, "usage", None):
                        usage = chunk.usage
                    if not chunk.choices:
                        continue
                    choice = chunk.choices[0]
                    if choice.finish_reason:
                        finish_reason = choice.finish_reason
                    delta = choice.delta
                    if delta is None:
                        continue
                    reasoning = getattr(delta, "reasoning_content", None)
                    if isinstance(reasoning, str) and reasoning:
                        yield LLMChunk(kind="reasoning", text=reasoning)
                    if delta.content:
                        yield LLMChunk(kind="text", text=delta.content)
                    for tc in delta.tool_calls or []:
                        acc = tool_acc.setdefault(tc.index, {"id": "", "name": "", "arguments": ""})
                        if tc.id:
                            acc["id"] = tc.id
                        if tc.function:
                            if tc.function.name:
                                acc["name"] += tc.function.name
                            if tc.function.arguments:
                                acc["arguments"] += tc.function.arguments
            except asyncio.TimeoutError:
                error = LLMError("TIMEOUT", f"model stream idle timeout after {timeout_sec}s")
            except Exception as exc:
                error = classify_error(exc, self.cfg)
                log.warning("OpenAI-compatible stream failed: code=%s http=%s", error.code, error.http_status)
        finally:
            if stream is not None:
                await stream.close()
            await client.close()

        if error is None and finish_reason is None:
            # The connection closed without the provider saying the turn was over.
            error = LLMError("INCOMPLETE", "model stream ended without a finish reason")
        finish = _FINISH.get(finish_reason or "", finish_reason or "stop")

        calls: list[LLMToolCall] = []
        if error is None and finish in ("stop", "tool_calls"):
            for idx in sorted(tool_acc):
                acc = tool_acc[idx]
                try:
                    args = json.loads(acc["arguments"]) if acc["arguments"].strip() else {}
                    if not isinstance(args, dict):
                        raise ValueError("arguments are not a JSON object")
                except ValueError:
                    error = LLMError("BAD_TOOL_CALL", f"model returned malformed arguments for tool {acc['name']!r}")
                    break
                calls.append(LLMToolCall(id=acc["id"] or f"call_{idx}", name=acc["name"], arguments=args))

        if usage is not None:
            yield LLMChunk(
                kind="usage",
                usage=LLMUsage(
                    input_tokens=getattr(usage, "prompt_tokens", 0) or 0,
                    output_tokens=getattr(usage, "completion_tokens", 0) or 0,
                    total_tokens=getattr(usage, "total_tokens", 0) or 0,
                ),
            )
        if error is not None:
            for chunk in error_chunks(error):
                yield chunk
            return
        for call in calls:
            yield LLMChunk(kind="tool_call", tool_call=call)
        yield LLMChunk(kind="finish", finish_reason="tool_calls" if calls else finish)

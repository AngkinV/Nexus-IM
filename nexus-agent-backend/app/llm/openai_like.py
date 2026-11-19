"""OpenAI-compatible adapter — covers OpenAI, DeepSeek, Moonshot, Zhipu (compat
mode), Tongyi (compat mode), Together, Groq, Ollama, and any other provider that
exposes the OpenAI Chat Completions interface.

True token streaming: text/reasoning deltas are yielded as they arrive so the
SSE pipeline can forward them live. Tool-call deltas are accumulated and yielded
once the stream ends (arguments must be complete JSON before execution).

`timeout_sec` is an idle timeout: max silence to first byte and between chunks,
not a cap on the whole generation — long answers stream fine as long as tokens
keep flowing.
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, AsyncIterator

from .base import LLMChunk, LLMClient, LLMToolCall, LLMUsage, ProviderConfig

log = logging.getLogger(__name__)


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

        client = AsyncOpenAI(
            api_key=self.cfg.api_key,
            base_url=self.cfg.base_url or None,
        )

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

        try:
            stream = await asyncio.wait_for(
                client.chat.completions.create(**kwargs),
                timeout=timeout_sec,
            )
        except asyncio.TimeoutError:
            yield LLMChunk(kind="error", text=f"model stream idle timeout after {timeout_sec}s")
            yield LLMChunk(kind="finish", finish_reason="timeout")
            return
        except Exception as exc:
            log.warning("openai-like call failed: %s", exc)
            yield LLMChunk(kind="error", text=str(exc))
            yield LLMChunk(kind="finish", finish_reason="error")
            return

        tool_acc: dict[int, dict[str, str]] = {}
        usage: Any = None
        finish_reason: str | None = None
        try:
            while True:
                try:
                    chunk = await asyncio.wait_for(
                        stream.__anext__(), timeout=timeout_sec,
                    )
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
            yield LLMChunk(kind="error", text=f"model stream idle timeout after {timeout_sec}s")
            finish_reason = "timeout"
        except Exception as exc:
            log.warning("openai-like stream failed mid-turn: %s", exc)
            yield LLMChunk(kind="error", text=str(exc))
            finish_reason = "error"
        finally:
            await stream.close()

        # Partial tool-call arguments are not executable; only emit them when
        # the stream ended cleanly.
        if finish_reason in ("stop", "tool_calls"):
            for idx in sorted(tool_acc):
                acc = tool_acc[idx]
                try:
                    args = json.loads(acc["arguments"] or "{}")
                except Exception:
                    args = {}
                yield LLMChunk(
                    kind="tool_call",
                    tool_call=LLMToolCall(
                        id=acc["id"] or f"call_{idx}",
                        name=acc["name"],
                        arguments=args,
                    ),
                )

        if usage is not None:
            yield LLMChunk(
                kind="usage",
                usage=LLMUsage(
                    input_tokens=getattr(usage, "prompt_tokens", 0) or 0,
                    output_tokens=getattr(usage, "completion_tokens", 0) or 0,
                    total_tokens=getattr(usage, "total_tokens", 0) or 0,
                ),
            )

        yield LLMChunk(kind="finish", finish_reason=finish_reason or "stop")
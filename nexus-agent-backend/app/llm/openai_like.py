"""OpenAI-compatible adapter — covers OpenAI, DeepSeek, Moonshot, Zhipu (compat
mode), Tongyi (compat mode), Together, Groq, Ollama, and any other provider that
exposes the OpenAI Chat Completions interface.

We intentionally use the openai SDK so retries/streaming/tool-call parsing are
handled for us. The provider's `base_url` and `api_key` are passed into AsyncOpenAI.
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
        }
        if tools:
            kwargs["tools"] = tools
            if tool_choice:
                kwargs["tool_choice"] = tool_choice

        try:
            response = await asyncio.wait_for(
                client.chat.completions.create(**kwargs),
                timeout=timeout_sec,
            )
        except asyncio.TimeoutError:
            yield LLMChunk(kind="text", text="（模型调用超时，已返回部分结果）")
            yield LLMChunk(kind="finish", finish_reason="timeout")
            return
        except Exception as exc:
            log.warning("openai-like call failed: %s", exc)
            yield LLMChunk(kind="text", text=f"（模型调用失败：{exc}）")
            yield LLMChunk(kind="finish", finish_reason="error")
            return

        msg = response.choices[0].message
        if msg.tool_calls:
            for tc in msg.tool_calls:
                try:
                    args = json.loads(tc.function.arguments or "{}")
                except Exception:
                    args = {}
                yield LLMChunk(
                    kind="tool_call",
                    tool_call=LLMToolCall(
                        id=tc.id,
                        name=tc.function.name,
                        arguments=args,
                    ),
                    raw=tc,
                )
        elif msg.content:
            yield LLMChunk(kind="text", text=msg.content)

        if response.usage:
            yield LLMChunk(
                kind="usage",
                usage=LLMUsage(
                    input_tokens=response.usage.prompt_tokens or 0,
                    output_tokens=response.usage.completion_tokens or 0,
                    total_tokens=(response.usage.prompt_tokens or 0) + (response.usage.completion_tokens or 0),
                ),
            )

        yield LLMChunk(kind="finish", finish_reason=response.choices[0].finish_reason or "stop")

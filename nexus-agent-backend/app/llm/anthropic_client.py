"""Anthropic Claude adapter.

The Anthropic API uses a different message shape: system prompts go in a top-level
``system`` field, role alternation must be strict (user/assistant), and tool calls
use ``tool_use`` / ``tool_result`` blocks. This adapter normalizes both directions.

Requires: ``pip install anthropic``  (kept as a soft dependency: only loaded when
the user actually configures an Anthropic provider).
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any, AsyncIterator

from .base import LLMChunk, LLMClient, LLMToolCall, LLMUsage, ProviderConfig

log = logging.getLogger(__name__)


class AnthropicClient(LLMClient):
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
            from anthropic import AsyncAnthropic
        except ImportError:
            yield LLMChunk(kind="text", text="（未安装 anthropic SDK，请运行 pip install anthropic）")
            yield LLMChunk(kind="finish", finish_reason="error")
            return

        # Split system from user/assistant turns
        system_blocks: list[str] = []
        turns: list[dict[str, Any]] = []
        for m in messages:
            role = m.get("role")
            content = m.get("content", "")
            if role == "system":
                system_blocks.append(content)
            elif role in {"user", "assistant"}:
                turns.append({"role": role, "content": content})
            elif role == "tool":
                # Anthropic expects tool results to be wrapped in a user turn
                turns.append({
                    "role": "user",
                    "content": [{
                        "type": "tool_result",
                        "tool_use_id": m.get("tool_call_id", ""),
                        "content": content,
                    }],
                })

        anthropic_tools = None
        if tools:
            anthropic_tools = []
            for t in tools:
                fn = t.get("function", {})
                anthropic_tools.append({
                    "name": fn.get("name"),
                    "description": fn.get("description", ""),
                    "input_schema": fn.get("parameters", {"type": "object", "properties": {}}),
                })

        client = AsyncAnthropic(
            api_key=self.cfg.api_key,
            base_url=self.cfg.base_url or None,
        )
        try:
            response = await asyncio.wait_for(
                client.messages.create(
                    model=self.cfg.model,
                    system="\n\n".join(system_blocks) if system_blocks else None,
                    messages=turns,
                    tools=anthropic_tools,
                    temperature=temperature,
                    max_tokens=max_tokens,
                ),
                timeout=timeout_sec,
            )
        except asyncio.TimeoutError:
            yield LLMChunk(kind="text", text="（模型调用超时）")
            yield LLMChunk(kind="finish", finish_reason="timeout")
            return
        except Exception as exc:
            log.warning("anthropic call failed: %s", exc)
            yield LLMChunk(kind="text", text=f"（Claude 调用失败：{exc}）")
            yield LLMChunk(kind="finish", finish_reason="error")
            return

        text_parts: list[str] = []
        had_tool = False
        for block in response.content:
            btype = getattr(block, "type", None)
            if btype == "text":
                text_parts.append(block.text or "")
            elif btype == "tool_use":
                had_tool = True
                yield LLMChunk(
                    kind="tool_call",
                    tool_call=LLMToolCall(
                        id=block.id,
                        name=block.name,
                        arguments=dict(block.input or {}),
                    ),
                    raw=block,
                )

        if not had_tool and text_parts:
            yield LLMChunk(kind="text", text="".join(text_parts))

        usage = getattr(response, "usage", None)
        if usage:
            yield LLMChunk(
                kind="usage",
                usage=LLMUsage(
                    input_tokens=getattr(usage, "input_tokens", 0) or 0,
                    output_tokens=getattr(usage, "output_tokens", 0) or 0,
                    total_tokens=(getattr(usage, "input_tokens", 0) or 0) + (getattr(usage, "output_tokens", 0) or 0),
                ),
            )

        yield LLMChunk(kind="finish", finish_reason=response.stop_reason or "stop")

"""Google Gemini adapter.

Uses the google-generativeai SDK. Like Anthropic, Gemini has its own message and
tool-call shape; this adapter normalizes both directions.

Requires: ``pip install google-generativeai``  (soft dependency).
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any, AsyncIterator

from .base import LLMChunk, LLMClient, LLMToolCall, LLMUsage, ProviderConfig

log = logging.getLogger(__name__)


class GeminiClient(LLMClient):
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
            import google.generativeai as genai
        except ImportError:
            yield LLMChunk(kind="text", text="（未安装 google-generativeai，请运行 pip install google-generativeai）")
            yield LLMChunk(kind="finish", finish_reason="error")
            return

        genai.configure(api_key=self.cfg.api_key)

        # Translate messages → Gemini's "contents" + system_instruction
        system_text = "\n\n".join(m.get("content", "") for m in messages if m.get("role") == "system")
        contents: list[dict[str, Any]] = []
        for m in messages:
            role = m.get("role")
            if role == "system":
                continue
            if role == "user":
                contents.append({"role": "user", "parts": [{"text": m.get("content", "")}]})
            elif role == "assistant":
                contents.append({"role": "model", "parts": [{"text": m.get("content", "")}]})
            elif role == "tool":
                contents.append({
                    "role": "function",
                    "parts": [{
                        "function_response": {
                            "name": m.get("name", "unknown"),
                            "response": {"content": m.get("content", "")},
                        }
                    }],
                })

        gemini_tools = None
        if tools:
            gemini_tools = [{
                "function_declarations": [
                    {
                        "name": t["function"]["name"],
                        "description": t["function"].get("description", ""),
                        "parameters": t["function"].get("parameters", {"type": "object", "properties": {}}),
                    }
                    for t in tools
                ]
            }]

        model = genai.GenerativeModel(
            model_name=self.cfg.model,
            system_instruction=system_text or None,
            tools=gemini_tools,
            generation_config={
                "temperature": temperature,
                "max_output_tokens": max_tokens,
            },
        )

        try:
            response = await asyncio.wait_for(
                model.generate_content_async(contents),
                timeout=timeout_sec,
            )
        except asyncio.TimeoutError:
            yield LLMChunk(kind="text", text="（模型调用超时）")
            yield LLMChunk(kind="finish", finish_reason="timeout")
            return
        except Exception as exc:
            log.warning("gemini call failed: %s", exc)
            yield LLMChunk(kind="text", text=f"（Gemini 调用失败：{exc}）")
            yield LLMChunk(kind="finish", finish_reason="error")
            return

        had_tool = False
        text_parts: list[str] = []
        try:
            for cand in response.candidates or []:
                for part in cand.content.parts:
                    fn_call = getattr(part, "function_call", None)
                    if fn_call and fn_call.name:
                        had_tool = True
                        yield LLMChunk(
                            kind="tool_call",
                            tool_call=LLMToolCall(
                                id=f"gem_{fn_call.name}",
                                name=fn_call.name,
                                arguments=dict(fn_call.args or {}),
                            ),
                            raw=fn_call,
                        )
                    elif getattr(part, "text", None):
                        text_parts.append(part.text)
        except Exception as exc:
            log.warning("gemini response parse failed: %s", exc)

        if not had_tool and text_parts:
            yield LLMChunk(kind="text", text="".join(text_parts))

        usage = getattr(response, "usage_metadata", None)
        if usage:
            yield LLMChunk(
                kind="usage",
                usage=LLMUsage(
                    input_tokens=getattr(usage, "prompt_token_count", 0) or 0,
                    output_tokens=getattr(usage, "candidates_token_count", 0) or 0,
                    total_tokens=getattr(usage, "total_token_count", 0) or 0,
                ),
            )

        yield LLMChunk(kind="finish", finish_reason="stop")

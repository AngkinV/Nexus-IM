"""Google Gemini adapter (google-genai SDK).

Each call builds its own client: the previous SDK configured the API key and
endpoint process-wide, so two users' concurrent requests could cross over.
"""
from __future__ import annotations

import asyncio
import base64
import json
import logging
from typing import Any, AsyncIterator

from .base import LLMChunk, LLMClient, LLMError, LLMToolCall, LLMUsage, ModelTransport, classify_error, error_chunks

log = logging.getLogger(__name__)

_FINISH = {"STOP": "stop", "MAX_TOKENS": "length", "SAFETY": "content_filter", "RECITATION": "content_filter",
           "BLOCKLIST": "content_filter", "PROHIBITED_CONTENT": "content_filter", "SPII": "content_filter"}


def _to_gemini(messages: list[dict[str, Any]], types: Any) -> tuple[str, list[Any]]:
    system: list[str] = []
    contents: list[Any] = []
    for m in messages:
        role = m.get("role")
        text = m.get("content") or ""
        if role == "system":
            system.append(text)
        elif role == "user":
            contents.append(types.Content(role="user", parts=[types.Part(text=text)]))
        elif role == "assistant":
            parts = [types.Part(text=text)] if text else []
            for call in m.get("tool_calls") or []:
                function = call["function"]
                signature = call.get("signature")
                parts.append(types.Part(
                    function_call=types.FunctionCall(
                        name=function["name"], args=json.loads(function["arguments"] or "{}")),
                    # Gemini rejects a replayed function call that lost its thought signature.
                    thought_signature=base64.b64decode(signature) if signature else None,
                ))
            if parts:
                contents.append(types.Content(role="model", parts=parts))
        elif role == "tool":
            try:
                response = json.loads(text)
            except ValueError:
                response = None
            if not isinstance(response, dict):
                response = {"content": text}
            part = types.Part(function_response=types.FunctionResponse(
                name=m.get("name", "unknown"), response=response))
            previous = contents[-1] if contents else None
            if previous is not None and previous.role == "user" and previous.parts[0].function_response:
                previous.parts.append(part)
            else:
                contents.append(types.Content(role="user", parts=[part]))
    return "\n\n".join(system), contents


class GeminiClient(LLMClient):
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
            from google import genai
            from google.genai import types
        except ImportError:
            for chunk in error_chunks(LLMError("DEPENDENCY", "google-genai SDK is not installed")):
                yield chunk
            return

        system, contents = _to_gemini(messages, types)
        config: dict[str, Any] = {"temperature": temperature, "max_output_tokens": max_tokens}
        if system:
            config["system_instruction"] = system
        if tools:
            config["tools"] = [types.Tool(function_declarations=[
                types.FunctionDeclaration(
                    name=t["function"]["name"],
                    description=t["function"].get("description", ""),
                    parameters_json_schema=t["function"].get("parameters", {"type": "object", "properties": {}}),
                ) for t in tools
            ])]
            config["tool_config"] = types.ToolConfig(function_calling_config=types.FunctionCallingConfig(
                mode="ANY" if tool_choice == "required" else "AUTO"))
            # The agent graph executes tools; the SDK must not call anything itself.
            config["automatic_function_calling"] = types.AutomaticFunctionCallingConfig(disable=True)

        client = genai.Client(api_key=self.cfg.api_key, http_options=types.HttpOptions(
            # cfg.base_url (normalized to the API root by the Java gateway) must be
            # honored, otherwise a proxy endpoint silently falls back to Google.
            base_url=self.cfg.base_url or None,
            client_args={'trust_env': False, 'follow_redirects': False},
            async_client_args={'transport': ModelTransport(), 'follow_redirects': False, 'trust_env': False},
            retry_options=types.HttpRetryOptions(attempts=1),
        ))
        stream = None
        calls: list[LLMToolCall] = []
        usage: Any = None
        finish_name: str | None = None
        error: LLMError | None = None
        try:
            if on_dispatch is not None:
                # Outside the provider error handling: a refusal is the caller's, not the provider's.
                await on_dispatch()
            try:
                stream = await asyncio.wait_for(
                    client.aio.models.generate_content_stream(
                        model=self.cfg.model, contents=contents,
                        config=types.GenerateContentConfig(**config)),
                    timeout=timeout_sec,
                )
                while True:
                    try:
                        chunk = await asyncio.wait_for(stream.__anext__(), timeout=timeout_sec)
                    except StopAsyncIteration:
                        break
                    if chunk.usage_metadata:
                        usage = chunk.usage_metadata
                    for candidate in chunk.candidates or []:
                        if candidate.finish_reason:
                            finish_name = getattr(candidate.finish_reason, "name", str(candidate.finish_reason))
                        for part in (candidate.content.parts if candidate.content else None) or []:
                            if part.function_call and part.function_call.name:
                                call = part.function_call
                                calls.append(LLMToolCall(
                                    id=call.id or f"gem_{len(calls)}_{call.name}",
                                    name=call.name,
                                    arguments=dict(call.args or {}),
                                    signature=(base64.b64encode(part.thought_signature).decode("ascii")
                                               if part.thought_signature else None),
                                ))
                            elif part.text:
                                yield LLMChunk(kind="reasoning" if part.thought else "text", text=part.text)
            except asyncio.TimeoutError:
                error = LLMError("TIMEOUT", f"model stream idle timeout after {timeout_sec}s")
            except Exception as exc:
                error = classify_error(exc, self.cfg)
                log.warning("Gemini stream failed: code=%s http=%s", error.code, error.http_status)
        finally:
            if stream is not None:
                await stream.aclose()
            try:
                await client.aio.aclose()
            finally:
                client.close()

        if error is None and finish_name is None:
            error = LLMError("INCOMPLETE", "model stream ended without a finish reason")
        elif error is None and finish_name not in _FINISH:
            error = LLMError("UPSTREAM", f"model stopped abnormally: {finish_name}", upstream_code=finish_name)

        if usage is not None:
            yield LLMChunk(kind="usage", usage=LLMUsage(
                input_tokens=usage.prompt_token_count or 0,
                output_tokens=usage.candidates_token_count or 0,
                total_tokens=usage.total_token_count or 0,
            ))
        if error is not None:
            for chunk in error_chunks(error):
                yield chunk
            return
        finish = _FINISH[finish_name]
        if finish == "stop":
            for call in calls:
                yield LLMChunk(kind="tool_call", tool_call=call)
            if calls:
                finish = "tool_calls"
        yield LLMChunk(kind="finish", finish_reason=finish)

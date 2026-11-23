"""Common types for the LLM abstraction layer."""
from __future__ import annotations

import abc
import asyncio
import dataclasses
import ipaddress
import math
import socket
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

import httpx
import httpx2

from ..config import get_settings
from typing import Any, AsyncIterator, Awaitable, Callable


@dataclasses.dataclass
class ProviderConfig:
    """Per-request provider configuration injected by the Java gateway."""
    name: str
    base_url: str | None
    model: str | None
    api_key: str | None


@dataclasses.dataclass
class LLMUsage:
    input_tokens: int = 0
    output_tokens: int = 0
    total_tokens: int = 0


@dataclasses.dataclass
class LLMToolCall:
    """A normalized tool invocation requested by the model."""
    id: str
    name: str
    arguments: dict[str, Any]
    # Opaque provider token that must be sent back with this call (Gemini thought signature).
    signature: str | None = None


@dataclasses.dataclass
class LLMError:
    """Why a model call failed, in terms a caller can act on.

    code, from a model call:
          AUTH | PERMISSION | MODEL_NOT_FOUND | RATE_LIMIT | CONTEXT_LENGTH |
          BAD_REQUEST | TIMEOUT | NETWORK | UPSTREAM | INCOMPLETE | BAD_TOOL_CALL |
          DEPENDENCY | UNKNOWN
    and additionally from the model API (catalog, test, complete):
          NOT_FOUND (the endpoint itself answered 404) | INVALID_RESPONSE | CONFIG |
          UNSUPPORTED | OUTPUT_LIMIT | CONTENT_FILTER | EMPTY_RESPONSE
    """
    code: str
    summary: str
    http_status: int | None = None
    upstream_code: str | None = None
    retry_after_seconds: int | None = None

    def as_payload(self) -> dict[str, Any]:
        return {
            "reason": self.code,
            "httpStatus": self.http_status,
            "upstreamCode": self.upstream_code,
            "retryAfterSeconds": self.retry_after_seconds,
        }


@dataclasses.dataclass
class LLMChunk:
    """One streaming event produced by an LLM client.

    Discriminator is `kind`:
      - "text"      : text is a partial assistant answer (delta)
      - "reasoning" : text is the model's own thinking, shown but never part of the answer
      - "tool_call" : tool_call holds a complete, parsed call
      - "usage"     : usage holds prompt/completion tokens
      - "error"     : text is the failure summary and error its classification;
                      display-only, never memory
      - "finish"    : always last; finish_reason is "stop" / "tool_calls" /
                      "length" / "content_filter" / "timeout" / "error"
    """
    kind: str
    text: str | None = None
    tool_call: LLMToolCall | None = None
    usage: LLMUsage | None = None
    finish_reason: str | None = None
    error: LLMError | None = None


def error_chunks(error: LLMError) -> list[LLMChunk]:
    return [
        LLMChunk(kind="error", text=error.summary, error=error),
        LLMChunk(kind="finish", finish_reason="timeout" if error.code == "TIMEOUT" else "error"),
    ]


_SUMMARY_LIMIT = 300
CONTEXT_HINTS = ("context length", "context_length", "context window", "maximum context", "too many tokens")


def _upstream_code(exc: BaseException) -> str | None:
    for attr in ("code", "status"):  # openai: code; google-genai: status
        value = getattr(exc, attr, None)
        if isinstance(value, str) and value:
            return value
    body = getattr(exc, "body", None)
    if isinstance(body, dict):
        detail = body["error"] if isinstance(body.get("error"), dict) else body
        for key in ("code", "type"):
            value = detail.get(key)
            if isinstance(value, str) and value:
                return value
    return None


def retry_after(value: str | None) -> int | None:
    if not value:
        return None
    try:
        return max(0, math.ceil(float(value)))
    except (ValueError, OverflowError):
        try:
            return max(0, math.ceil((parsedate_to_datetime(value) - datetime.now(timezone.utc)).total_seconds()))
        except (TypeError, ValueError, OverflowError):
            return None


def _retry_after(exc: BaseException) -> int | None:
    headers = getattr(getattr(exc, "response", None), "headers", None)
    return retry_after(headers.get("retry-after") if headers is not None else None)


def classify_error(exc: BaseException, cfg: ProviderConfig) -> LLMError:
    """Map any provider SDK exception onto one LLMError."""
    status = getattr(exc, "status_code", None)  # openai / anthropic
    if not isinstance(status, int):
        code = getattr(exc, "code", None)  # google-genai puts the HTTP status here
        status = code if isinstance(code, int) else None

    summary = str(exc) or type(exc).__name__
    if cfg.api_key:
        summary = summary.replace(cfg.api_key, "***")
    lowered = summary.lower()
    kind = type(exc).__name__.lower()

    if status == 401:
        code = "AUTH"
    elif status == 403:
        code = "PERMISSION"
    elif status == 404:
        code = "MODEL_NOT_FOUND"
    elif status == 429:
        code = "RATE_LIMIT"
    elif status in (408, 504):
        code = "TIMEOUT"
    elif status in (400, 413, 422):
        code = "CONTEXT_LENGTH" if any(hint in lowered for hint in CONTEXT_HINTS) else "BAD_REQUEST"
    elif status is not None and status >= 500:
        code = "UPSTREAM"
    elif isinstance(exc, TimeoutError) or "timeout" in kind:
        code = "TIMEOUT"
    elif "connect" in kind:
        code = "NETWORK"
    else:
        code = "UNKNOWN"

    return LLMError(
        code=code,
        summary=summary[:_SUMMARY_LIMIT],
        http_status=status,
        upstream_code=_upstream_code(exc),
        retry_after_seconds=_retry_after(exc),
    )


class UnsafeEndpoint(ValueError):
    pass


class _PinnedDestination:
    async def handle_async_request(self, request):
        url = request.url
        if url.scheme not in ('http', 'https') or not url.host or url.userinfo or '%' in url.host:
            raise UnsafeEndpoint('Invalid provider endpoint')
        addresses = await asyncio.get_running_loop().getaddrinfo(url.host, url.port or (443 if url.scheme == 'https' else 80),
                                                                 type=socket.SOCK_STREAM)
        allow_private = get_settings().model_endpoint_allow_private_network
        for _, _, _, _, addr in addresses:
            ip = ipaddress.ip_address(addr[0])
            ip = getattr(ip, 'ipv4_mapped', None) or ip
            if (ip.is_unspecified or ip.is_link_local or ip.is_multicast or (ip.is_reserved and not ip.is_loopback)
                    or str(ip) in ('100.100.100.200', 'fd00:ec2::254') or (not allow_private and not ip.is_global)):
                raise UnsafeEndpoint('Provider endpoint resolves to a disallowed network')
        addresses.sort(key=lambda address: address[0] != socket.AF_INET)
        # Use the checked IP for the actual connection; Host and TLS certificate validation retain the original hostname.
        pinned = type(request)(request.method, url.copy_with(host=addresses[0][4][0]), headers=request.headers,
                               stream=request.stream, extensions={**request.extensions, 'sni_hostname': url.raw_host.decode('ascii')})
        return await super().handle_async_request(pinned)


class ModelTransport(_PinnedDestination, httpx.AsyncHTTPTransport):
    pass


class AnthropicTransport(_PinnedDestination, httpx2.AsyncHTTPTransport):
    pass


class LLMClient(abc.ABC):
    """Abstract base for all LLM adapters."""

    # True for an adapter that awaits `on_dispatch` immediately before its request leaves.
    signals_dispatch = False

    def __init__(self, cfg: ProviderConfig) -> None:
        self.cfg = cfg

    @abc.abstractmethod
    async def complete(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None,
        *,
        tool_choice: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 1024,
        timeout_sec: int = 30,
        on_dispatch: Callable[[], Awaitable[None]] | None = None,
    ) -> AsyncIterator[LLMChunk]:
        """Stream one model turn.

        Text and reasoning are yielded as they arrive. Tool calls are yielded
        only once complete and only when the turn ended cleanly. The request is
        attempted exactly once — no SDK-level retries — and the provider
        connection is released when the generator finishes or is closed.
        `timeout_sec` is an idle timeout: the longest silence allowed before the
        first byte and between chunks.
        `on_dispatch` is awaited once, after the request is built and before it is
        sent. If it raises, nothing is sent and the exception reaches the caller.
        """
        if False:  # pragma: no cover - mark as async generator
            yield LLMChunk(kind="text", text="")

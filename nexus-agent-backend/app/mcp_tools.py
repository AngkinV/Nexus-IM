"""Agent tools, discovered and called over MCP.

The Java backend publishes its tools at `<JAVA_INTERNAL_BASE_URL>/mcp`. Nothing
about them is written down here: names, descriptions and argument schemas come
from `tools/list`, so a tool added in Java is offered to the model without a
change in this service.

Java re-authorizes every call. Identity travels in request headers
(`Authorization`, `X-Actor-User-Id`), never in tool arguments, so the model
cannot choose who it acts as.

Extra MCP servers can be allow-listed by the operator (`MCP_EXTERNAL_SERVERS`).
They never receive the internal token or the actor, and what they return is
handed to the model as untrusted, length-limited text.
"""
from __future__ import annotations

import asyncio
import contextlib
import dataclasses
import json
import logging
import re
import time
import uuid
import warnings
from typing import Any
from urllib.parse import urlsplit

import httpx2
from fastmcp import Client
from fastmcp.client.transports import StreamableHttpTransport
from langchain_core.tools import BaseTool
from langchain_core.utils.function_calling import convert_to_openai_tool
from mcp import types as mcp_types
from mcp.shared.exceptions import MCPError
from opentelemetry import propagate

from .config import get_settings

with warnings.catch_warnings():
    # langchain.mcp announces that it is beta on import; that is known and pinned.
    warnings.simplefilter("ignore")
    from langchain.mcp import MCPAdapter

log = logging.getLogger(__name__)

_SERVER_NAME = re.compile(r"[a-z][a-z0-9]{0,23}")
_TOOL_NAME = re.compile(r"[A-Za-z0-9_-]{1,64}")
_ERROR_TEXT_LIMIT = 300


class ToolError(RuntimeError):
    """A tool ran and refused or failed. The model is told and can react."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class ToolTransportError(RuntimeError):
    """The tool server could not be reached or rejected this service. Not something a model can fix."""


@dataclasses.dataclass(frozen=True)
class _Entry:
    server: str | None  # None = the Java backend
    remote_name: str
    schema: dict[str, Any]  # OpenAI function-calling shape, as the LLM adapters expect


@dataclasses.dataclass
class _CatalogState:
    entries: dict[str, _Entry] | None = None
    expires_at: float = 0.0
    lock: asyncio.Lock | None = None
    loop: asyncio.AbstractEventLoop | None = None
    failed_at: float = -1.0
    failure: str = ""


_state = _CatalogState()


def reset_catalog() -> None:
    """Forget the discovered tools, so the next run asks the servers again."""
    _state.entries = None
    _state.expires_at = 0.0
    _state.failed_at = -1.0


def external_servers() -> dict[str, str]:
    """The operator's allow-list of extra MCP servers. Raises ValueError when it is malformed."""
    raw = (get_settings().mcp_external_servers or "").strip()
    if not raw:
        return {}
    try:
        servers = json.loads(raw)
    except ValueError as exc:
        raise ValueError("MCP_EXTERNAL_SERVERS must be a JSON object of name -> URL") from exc
    if not isinstance(servers, dict):
        raise ValueError("MCP_EXTERNAL_SERVERS must be a JSON object of name -> URL")
    for name, url in servers.items():
        if not _SERVER_NAME.fullmatch(name):
            raise ValueError(f"MCP server name {name!r} must be lowercase letters and digits, starting with a letter")
        parts = urlsplit(url) if isinstance(url, str) else None
        # A URL only: anything else could select a local program to launch.
        if parts is None or parts.scheme not in ("http", "https") or not parts.hostname or parts.username:
            raise ValueError(f"MCP server {name!r} must be a plain http(s) URL")
    return servers


def _http_client(headers=None, timeout=None, auth=None, **_ignored) -> httpx2.AsyncClient:
    """No redirects (the token and actor headers must not follow one) and no proxy environment."""
    return httpx2.AsyncClient(
        headers=headers, auth=auth, timeout=timeout or httpx2.Timeout(30.0, read=300.0),
        follow_redirects=False, trust_env=False,
    )


def _adapter(url: str, headers: dict[str, str], timeout: float) -> MCPAdapter:
    # The classic handshake: none of these servers needs the newer discovery probe.
    client = Client(
        StreamableHttpTransport(url, headers=headers, httpx_client_factory=lambda **kw: _http_client(**kw)),
        mode="legacy", timeout=timeout, init_timeout=timeout,
    )
    return MCPAdapter(client)


def _internal_url() -> str:
    return get_settings().java_internal_base_url.rstrip("/") + "/mcp"


def _why(exc: BaseException) -> str:
    return f"{type(exc).__name__}: {str(exc)[:120]}"


async def _discover(url: str, headers: dict[str, str]) -> list[BaseTool]:
    timeout = get_settings().tool_timeout_sec
    async with asyncio.timeout(timeout + 2):  # a server that accepts the connection and then says nothing
        return await _adapter(url, headers, timeout).list_tools()


async def _load_catalog() -> dict[str, _Entry]:
    settings = get_settings()
    entries: dict[str, _Entry] = {}
    try:
        internal = await _discover(_internal_url(), {"Authorization": f"Bearer {settings.java_internal_token}"})
    except Exception as exc:
        raise ToolTransportError(f"tool service unavailable ({_why(exc)})") from exc
    for tool in internal:
        entries[tool.name] = _Entry(None, tool.name, convert_to_openai_tool(tool))

    for server, url in external_servers().items():
        try:
            offered = await _discover(url, {})
        except Exception as exc:
            log.warning("external MCP server %s skipped: %s", server, _why(exc))
            continue
        for tool in offered:
            name = f"{server}__{tool.name}"
            if not _TOOL_NAME.fullmatch(name):
                log.warning("external tool %s skipped: name is not usable as a function name", name)
                continue
            schema = convert_to_openai_tool(tool)
            function = {**schema["function"], "name": name}
            function["description"] = f"[external:{server}] {function.get('description') or ''}".strip()
            entries[name] = _Entry(server, tool.name, {**schema, "function": function})
    return entries


async def catalog() -> dict[str, _Entry]:
    """Tools currently on offer. Shared by all users; refreshed after `tool_catalog_ttl_sec`."""
    loop = asyncio.get_running_loop()
    if _state.lock is None or _state.loop is not loop:
        _state.lock, _state.loop = asyncio.Lock(), loop
    arrived = time.monotonic()
    async with _state.lock:  # concurrent first runs discover once
        if _state.failed_at >= arrived:
            # The attempt this run queued behind has just failed; it does not repeat
            # it, so runs do not pile up one timeout after another behind a hung server.
            raise ToolTransportError(_state.failure)
        if _state.entries is None or time.monotonic() >= _state.expires_at:
            try:
                _state.entries = await _load_catalog()
            except ToolTransportError as exc:
                _state.failed_at, _state.failure = time.monotonic(), str(exc)
                raise
            _state.expires_at = time.monotonic() + max(0, get_settings().tool_catalog_ttl_sec)
        return _state.entries


def _text(content: Any) -> str:
    if isinstance(content, str):
        return content
    return "".join(block.get("text", "") for block in content or [] if isinstance(block, dict))


class ToolExecutor:
    """Calls tools for one run, as one user. Connections open on first use and close with the run."""

    def __init__(self, actor_user_id: int, trace_id: str, *, context: Any = None) -> None:
        self._actor_user_id = actor_user_id
        self._trace_id = trace_id
        self._context = context
        self._timeout = get_settings().tool_timeout_sec
        self._stack = contextlib.AsyncExitStack()
        self._bound: dict[str | None, dict[str, BaseTool]] = {}
        self._connecting = asyncio.Lock()

    async def __aenter__(self) -> "ToolExecutor":
        return self

    async def __aexit__(self, *exc_info) -> None:
        await self._stack.aclose()

    async def schemas(self) -> list[dict[str, Any]]:
        """What to offer the model. Raises ToolTransportError when the Java tool server is unreachable."""
        return [entry.schema for entry in (await catalog()).values()]

    def _headers(self, server: str | None) -> dict[str, str]:
        if server is not None:
            return {}  # nothing of ours leaves for an external server
        headers = {
            "Authorization": f"Bearer {get_settings().java_internal_token}",
            "X-Actor-User-Id": str(self._actor_user_id),
            "X-Trace-Id": self._trace_id,
        }
        propagate.inject(headers, context=self._context)
        return headers

    async def _tools_of(self, server: str | None) -> dict[str, BaseTool]:
        async with self._connecting:
            if server not in self._bound:
                url = _internal_url() if server is None else external_servers()[server]
                # Slightly longer than the per-call deadline, which is enforced in execute().
                adapter = _adapter(url, self._headers(server), self._timeout + 2)
                try:
                    async with asyncio.timeout(self._timeout + 4):
                        await self._stack.enter_async_context(adapter)
                        self._bound[server] = {tool.name: tool for tool in await adapter.list_tools()}
                except Exception as exc:
                    label = "tool service" if server is None else f"external tool server {server}"
                    raise ToolTransportError(f"{label} unavailable ({_why(exc)})") from exc
            return self._bound[server]

    async def execute(self, tool_name: str, args: dict[str, Any]) -> tuple[dict[str, Any], int]:
        """Returns (result, latency_ms). Raises ToolError when the tool fails, ToolTransportError when its server does."""
        started = time.perf_counter()
        entry = (await catalog()).get(tool_name)
        if entry is None:
            raise ToolError("UNKNOWN_TOOL", f"unregistered tool: {tool_name}")
        tool = (await self._tools_of(entry.server)).get(entry.remote_name)
        if tool is None:  # withdrawn by the server since the catalog was read
            raise ToolError("UNKNOWN_TOOL", f"unregistered tool: {tool_name}")
        try:
            async with asyncio.timeout(self._timeout):
                message = await tool.ainvoke({
                    "type": "tool_call", "id": uuid.uuid4().hex, "name": entry.remote_name, "args": args})
        except TimeoutError as exc:
            raise ToolError("TIMEOUT", f"{tool_name} timeout") from exc
        except MCPError as exc:
            if exc.code == mcp_types.REQUEST_TIMEOUT:
                raise ToolError("TIMEOUT", f"{tool_name} timeout") from exc
            if exc.code in (mcp_types.INVALID_PARAMS, mcp_types.METHOD_NOT_FOUND):
                raise ToolError("BAD_ARG", f"{tool_name} was rejected: {str(exc)[:_ERROR_TEXT_LIMIT]}") from exc
            raise ToolTransportError(f"{tool_name} failed in transport ({_why(exc)})") from exc
        except Exception as exc:
            raise ToolTransportError(f"{tool_name} failed in transport ({_why(exc)})") from exc

        text = _text(message.content)
        if message.status == "error":
            try:
                reported = json.loads(text)
            except ValueError:
                reported = None
            if isinstance(reported, dict) and isinstance(reported.get("toolError"), str):
                raise ToolError(reported["toolError"], str(reported.get("message") or reported["toolError"])[:_ERROR_TEXT_LIMIT])
            raise ToolError("TOOL_ERROR", text[:_ERROR_TEXT_LIMIT] or "the tool reported an error")

        structured = (message.artifact or {}).get("structured_content")
        latency_ms = int((time.perf_counter() - started) * 1000)
        if entry.server is not None:
            limit = get_settings().mcp_external_result_max_chars
            content = json.dumps(structured, ensure_ascii=False) if structured is not None else text
            return {
                "source": f"external:{entry.server}",
                "untrusted": True,
                "content": content[:limit],
                "truncated": len(content) > limit,
            }, latency_ms
        if isinstance(structured, dict):
            return structured, latency_ms
        try:
            parsed = json.loads(text)
        except ValueError:
            return {"text": text}, latency_ms
        return (parsed if isinstance(parsed, dict) else {"result": parsed}), latency_ms

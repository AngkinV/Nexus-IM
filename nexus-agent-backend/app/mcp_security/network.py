"""Streamable-HTTP request boundary: exact endpoints, public DNS/socket pinning.

The pinned httpx2 2.16.2 transport forwards extensions to httpcore2; its TLS
connection uses sni_hostname while connect_tcp uses the request's literal host.
This is NOT a DNS-check followed by an unrestricted hostname connection.
No proxy, redirects, reverse MCP capabilities, cookies or arbitrary headers.
"""
from __future__ import annotations

import asyncio
import ipaddress
import json
import logging
import re
import socket
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Mapping
from urllib.parse import urlsplit

import httpx2

from .redaction import SecretRedactor
from .types import SecurityViolation

Resolver = Callable[[str, int], Awaitable[tuple[str, ...]]]
Authorize = Callable[[dict[str, Any] | None], Awaitable[None]]
_HOST = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\Z")
_HEADER = re.compile(r"[A-Za-z0-9-]{1,64}\Z")
_TRANSITION = tuple(ipaddress.ip_network(value) for value in (
    "64:ff9b::/96", "64:ff9b:1::/48", "2002::/16", "2001::/32", "::/96",
))
_EXTERNAL_CREDENTIALS = frozenset({"authorization", "x-api-key", "api-key", "x-auth-token"})
_INTERNAL_CREDENTIALS = _EXTERNAL_CREDENTIALS | frozenset({
    "x-actor-user-id", "x-trace-id", "traceparent", "tracestate",
    "x-internal-service", "x-internal-timestamp", "x-internal-nonce", "x-internal-signature",
})
_PROTOCOL_HEADERS = frozenset({"mcp-session-id", "mcp-protocol-version", "last-event-id", "mcp-name", "mcp-method"})
_CLIENT_HEADERS = frozenset({
    "host", "accept", "accept-encoding", "connection", "user-agent", "content-length", "content-type",
})
_RESPONSE_HEADERS = frozenset({"content-type", "mcp-session-id", "mcp-protocol-version"})
_METHODS = frozenset({
    "initialize", "notifications/initialized", "ping", "tools/list", "tools/call", "notifications/cancelled",
})


def _endpoint(url: str) -> tuple[str, str, int, str]:
    if (type(url) is not str or not 1 <= len(url) <= 2048
            or any(ord(char) <= 32 or ord(char) >= 127 for char in url) or "\\" in url):
        raise SecurityViolation("UNSAFE_ENDPOINT")
    try:
        parts = urlsplit(url)
        host = parts.hostname
        port = parts.port if parts.port is not None else (443 if parts.scheme == "https" else 80)
    except ValueError:
        raise SecurityViolation("UNSAFE_ENDPOINT") from None
    if (parts.scheme not in ("http", "https") or not host or parts.username is not None
            or parts.query or parts.fragment or "%" in parts.netloc
            or not 1 <= port <= 65535 or host.endswith(".") or host != host.lower()):
        raise SecurityViolation("UNSAFE_ENDPOINT")
    # URL parsing can collapse host case and paths. Require unambiguous ASCII
    # hosts and path segments, not browser-style aliases or escaped separators.
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        if (len(host) > 253 or not _HOST.fullmatch(host) or "." not in host
                or host.split(".")[-1].isdigit() or host.lower().startswith("0x")):
            raise SecurityViolation("UNSAFE_ENDPOINT") from None
    else:
        if str(address) != host:
            raise SecurityViolation("UNSAFE_ENDPOINT")
    path = parts.path or "/"
    if ("%" in path or "//" in path or any(segment in (".", "..") for segment in path.split("/"))
            or not path.startswith("/")):
        raise SecurityViolation("UNSAFE_ENDPOINT")
    return parts.scheme, host, port, path


def _origin(parts: tuple[str, str, int, str]) -> str:
    scheme, host, port, _ = parts
    host = f"[{host}]" if ":" in host else host
    default = 443 if scheme == "https" else 80
    return f"{scheme}://{host}" + (f":{port}" if port != default else "")


def public_address(value: str) -> str:
    if type(value) is not str:
        raise SecurityViolation("DNS_DENIED")
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        raise SecurityViolation("DNS_DENIED") from None
    if ("%" in value or not address.is_global or address.is_multicast or address.is_reserved
            or address.is_loopback or address.is_link_local or address.is_unspecified):
        raise SecurityViolation("DNS_DENIED")
    if isinstance(address, ipaddress.IPv6Address) and (
            address.ipv4_mapped is not None or any(address in net for net in _TRANSITION)):
        raise SecurityViolation("DNS_DENIED")
    return str(address)


async def resolve_public(host: str, port: int) -> tuple[str, ...]:
    try:
        answer = await asyncio.get_running_loop().getaddrinfo(
            host, port, family=socket.AF_UNSPEC, type=socket.SOCK_STREAM, proto=socket.IPPROTO_TCP)
    except Exception:
        raise SecurityViolation("DNS_DENIED") from None
    return tuple(item[4][0] for item in answer)


@dataclass(frozen=True)
class NetworkPolicy:
    endpoint: str = field(repr=False)
    external: bool
    allowed_origins: frozenset[str] = frozenset()
    internal_endpoint: str | None = field(default=None, repr=False)
    allow_http: bool = False
    parts: tuple[str, str, int, str] = field(init=False, repr=False)

    def __post_init__(self) -> None:
        if type(self.external) is not bool or type(self.allow_http) is not bool:
            raise SecurityViolation("UNSAFE_ENDPOINT")
        parts = _endpoint(self.endpoint)
        if self.external:
            if type(self.allowed_origins) is not frozenset or _origin(parts) not in self.allowed_origins:
                raise SecurityViolation("UNSAFE_ENDPOINT")
            if parts[0] != "https" and not self.allow_http:
                raise SecurityViolation("UNSAFE_ENDPOINT")
            try:
                literal = ipaddress.ip_address(parts[1])
            except ValueError:
                pass
            else:
                public_address(str(literal))
            if self.internal_endpoint is not None and _origin(parts) == _origin(_endpoint(self.internal_endpoint)):
                raise SecurityViolation("UNSAFE_ENDPOINT")
        elif self.internal_endpoint is None or parts != _endpoint(self.internal_endpoint):
            # Internal locality is allowed ONLY for the trusted configured Java
            # endpoint, never a user-selected external localhost exception.
            raise SecurityViolation("UNSAFE_ENDPOINT")
        object.__setattr__(self, "parts", parts)

    def check_request(self, url: str) -> None:
        if _endpoint(url) != self.parts:
            raise SecurityViolation("UNSAFE_ENDPOINT")

    async def pinned_host(self, resolver: Resolver) -> str:
        if not self.external:
            return self.parts[1]
        host, port = self.parts[1:3]
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            try:
                answers = await resolver(host, port)
            except SecurityViolation:
                raise
            except Exception:
                raise SecurityViolation("DNS_DENIED") from None
        else:
            answers = (str(address),)
        if not isinstance(answers, (tuple, list)) or not 1 <= len(answers) <= 64:
            raise SecurityViolation("DNS_DENIED")
        # A single private A/AAAA entry invalidates the WHOLE answer. Never
        # select a public entry while leaving a private fallback to the client.
        checked = tuple(public_address(value) for value in answers)
        return checked[0]


def _pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise SecurityViolation("PROTOCOL_DENIED")
        result[key] = value
    return result


def _bad_number(_value: str) -> None:
    raise SecurityViolation("PROTOCOL_DENIED")


def clean_wire_meta(payload: Any) -> Any:
    """Keep SDK progress, strip automatic trace propagation, reject all other meta.
    Never strip tool arguments: scope/schema/secret checks see the exact args.
    """
    if type(payload) is not dict or type(payload.get("params")) is not dict:
        return payload
    params = payload["params"]
    if "_meta" not in params:
        return payload
    meta = params["_meta"]
    if type(meta) is not dict or set(meta) - {"progressToken", "traceparent", "tracestate", "baggage"}:
        raise SecurityViolation("PROTOCOL_DENIED")
    kept = {}
    if "progressToken" in meta:
        token = meta["progressToken"]
        if (type(token) not in (int, str) or token != payload.get("id")
                or (type(token) is str and not 1 <= len(token) <= 96)):
            raise SecurityViolation("PROTOCOL_DENIED")
        kept["progressToken"] = token
    if kept:
        params["_meta"] = kept
    else:
        params.pop("_meta")
    return payload


def safe_acknowledgement(payload: Any) -> bool:
    """Only empty ping replies or fixed unsupported-request errors may leave.
    Never return sampling/roots/elicitation data to a remote server.
    """
    if (type(payload) is not dict or payload.get("jsonrpc") != "2.0"
            or "id" not in payload or type(payload["id"]) not in (str, int)
            or set(payload) - {"jsonrpc", "id", "result", "error"}):
        return False
    if "result" in payload:
        return "error" not in payload and payload["result"] == {}
    error = payload.get("error")
    return (type(error) is dict and set(error) == {"code", "message"}
            and type(error["code"]) is int and error["code"] in {-32600, -32601, -32602}
            and type(error["message"]) is str
            and error["message"] in {"Method not found", "Invalid request", "Invalid params", "Unsupported request"})


@dataclass(frozen=True)
class ProtocolRules:
    probe: bool = False
    authorize: Authorize | None = field(default=None, repr=False)
    max_request_bytes: int = 65536

    def __post_init__(self) -> None:
        if (type(self.probe) is not bool or type(self.max_request_bytes) is not int
                or not 1 <= self.max_request_bytes <= 65536):
            raise SecurityViolation("PROTOCOL_DENIED")

    def parse(self, method: str, body: bytes) -> dict[str, Any] | None:
        if method in ("GET", "DELETE"):
            if body:
                raise SecurityViolation("PROTOCOL_DENIED")
            return None
        if method != "POST" or not 0 < len(body) <= self.max_request_bytes:
            raise SecurityViolation("PROTOCOL_DENIED")
        try:
            payload = json.loads(body, object_pairs_hook=_pairs, parse_constant=_bad_number)
        except SecurityViolation:
            raise
        except (ValueError, UnicodeError, RecursionError):
            raise SecurityViolation("PROTOCOL_DENIED") from None
        payload = clean_wire_meta(payload)
        if safe_acknowledgement(payload):
            return payload
        if (type(payload) is not dict or payload.get("jsonrpc") != "2.0"
                or set(payload) - {"jsonrpc", "id", "method", "params"}
                or type(payload.get("method")) is not str or payload.get("method") not in _METHODS
                or ("params" in payload and type(payload["params"]) is not dict)):
            raise SecurityViolation("PROTOCOL_DENIED")
        rpc_method = payload["method"]
        if self.probe and rpc_method == "tools/call":
            raise SecurityViolation("PROTOCOL_DENIED")
        if rpc_method == "initialize":
            params = payload.get("params", {})
            if (set(params) - {"protocolVersion", "capabilities", "clientInfo", "_meta"}
                    or params.get("capabilities", {}) not in ({}, {"experimental": {}})):
                raise SecurityViolation("PROTOCOL_DENIED")
        return payload

    async def check(self, payload: dict[str, Any] | None) -> None:
        if self.authorize is None:
            raise SecurityViolation("AUTHORITY_UNAVAILABLE")
        try:
            await self.authorize(payload)
        except SecurityViolation:
            raise
        except Exception:
            raise SecurityViolation("AUTHORITY_UNAVAILABLE") from None


class _BoundedStream(httpx2.AsyncByteStream):
    def __init__(self, stream: httpx2.AsyncByteStream, limit: int) -> None:
        self._stream = stream
        self._limit = limit

    async def __aiter__(self):
        total = 0
        try:
            async for chunk in self._stream:
                total += len(chunk)
                if total > self._limit:
                    raise SecurityViolation("REMOTE_ERROR")
                yield chunk
        except SecurityViolation:
            raise
        except Exception:
            raise SecurityViolation("TRANSPORT_ERROR") from None
        finally:
            await self._stream.aclose()

    async def aclose(self) -> None:
        await self._stream.aclose()


class SafeMCPTransport(httpx2.AsyncBaseTransport):
    def __init__(self, policy: NetworkPolicy, rules: ProtocolRules, *,
                 service_headers: Mapping[str, str], redactor: SecretRedactor,
                 forbidden_external_secrets: SecretRedactor,
                 resolver: Resolver = resolve_public,
                 inner: httpx2.AsyncBaseTransport | None = None,
                 max_response_bytes: int = 1048576) -> None:
        if (not isinstance(policy, NetworkPolicy) or not isinstance(rules, ProtocolRules)
                or not isinstance(redactor, SecretRedactor)
                or not isinstance(forbidden_external_secrets, SecretRedactor)
                or rules.authorize is None or type(max_response_bytes) is not int
                or not 1 <= max_response_bytes <= 4194304):
            raise SecurityViolation("AUTHORITY_UNAVAILABLE")
        self.policy, self.rules = policy, rules
        self._redactor, self._forbidden = redactor, forbidden_external_secrets
        self._redactor.absorb(self._forbidden)
        self._resolver, self._limit = resolver, max_response_bytes
        self._session_id: str | None = None
        if not isinstance(service_headers, Mapping) or not forbidden_external_secrets.configured:
            raise SecurityViolation("AUTHORITY_UNAVAILABLE")
        self._credentials: dict[str, str] = {}
        allowed = _EXTERNAL_CREDENTIALS if policy.external else _INTERNAL_CREDENTIALS
        for key, value in service_headers.items():
            if (type(key) is not str or not _HEADER.fullmatch(key) or key.lower() not in allowed
                    or key.lower() in self._credentials or not self._header_value(value)):
                raise SecurityViolation("HEADER_DENIED")
            if policy.external and self._forbidden.matches_registered(value):
                raise SecurityViolation("SECRET_EGRESS")
            self._credentials[key.lower()] = value
            # Register the complete wire authentication value as well as keys
            # supplied by T4A, including Basic username:key combinations.
            if key.lower() in _EXTERNAL_CREDENTIALS or key.lower() == "x-internal-signature":
                self._redactor.register_credential(value)
        self._inner = inner if inner is not None else httpx2.AsyncHTTPTransport(
            verify=True, trust_env=False, retries=0, http2=False,
            limits=httpx2.Limits(max_connections=2, max_keepalive_connections=0))

    @staticmethod
    def _header_value(value: str) -> bool:
        return (type(value) is str and 0 < len(value) <= 4096
                and all(32 <= ord(char) < 127 for char in value))

    def _headers(self, request: httpx2.Request, body: bytes, payload: dict | None) -> dict[str, str]:
        headers = dict(self._credentials)
        seen = set()
        origin = _origin(self.policy.parts).split("://", 1)[1]
        for key, value in request.headers.multi_items():
            name = key.lower()
            if name in seen or not self._header_value(value):
                raise SecurityViolation("HEADER_DENIED")
            seen.add(name)
            if name in self._credentials:
                if value != self._credentials[name]:
                    raise SecurityViolation("HEADER_DENIED")
            elif name in _PROTOCOL_HEADERS:
                if name == "mcp-name" and (payload is None or value != payload.get("params", {}).get("name")):
                    raise SecurityViolation("PROTOCOL_DENIED")
                if name == "mcp-method" and (payload is None or value != payload.get("method")):
                    raise SecurityViolation("PROTOCOL_DENIED")
                if self._forbidden.matches_registered(value):
                    raise SecurityViolation("SECRET_EGRESS")
                headers[name] = value
            elif name in _CLIENT_HEADERS:
                if name == "host" and value != origin:
                    raise SecurityViolation("HEADER_DENIED")
                # Rebuild SDK defaults; do not forward inbound defaults/tokens.
            else:
                # No cookies, proxies, actor/trace/model headers or arbitrary
                # supplied auth. Even mixed-case variants hit this same gate.
                raise SecurityViolation("HEADER_DENIED")
        headers.update({"host": origin, "accept": "application/json, text/event-stream",
                        "accept-encoding": "identity", "user-agent": "Nexus-MCP"})
        if request.method == "POST":
            headers.update({"content-type": "application/json", "content-length": str(len(body))})
        return headers

    async def handle_async_request(self, request: httpx2.Request) -> httpx2.Response:
        self.policy.check_request(str(request.url))
        body = bytearray()
        async for chunk in request.stream:
            body.extend(chunk)
            if len(body) > self.rules.max_request_bytes:
                raise SecurityViolation("PROTOCOL_DENIED")
        raw = bytes(body)
        payload = self.rules.parse(request.method, raw)
        if payload is not None:
            # parse() has already reduced `_meta` to the SDK's progress token, which must equal
            # the request id. Its key name is the protocol's, not a credential field, and its
            # value is still checked as part of `id`.
            checked = payload
            if type(payload.get("params")) is dict and "_meta" in payload["params"]:
                checked = {**payload, "params": {k: v for k, v in payload["params"].items() if k != "_meta"}}
            if self.policy.external:
                self._forbidden.deny_secret(checked)
            self._redactor.deny_secret(checked)
        # MCP 2.3/FastMCP add progress and trace _meta even in legacy mode.
        # Serialize the sanitized frame, not the original bytes carrying trace.
        if payload is not None:
            raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
        headers = self._headers(request, raw, payload)
        # Resolve on every HTTP request, then connect to ONLY the checked IP.
        pinned = await self.policy.pinned_host(self._resolver)
        extensions = {"sni_hostname": self.policy.parts[1]}
        if "timeout" in request.extensions:
            extensions["timeout"] = request.extensions["timeout"]
        outbound = httpx2.Request(request.method, request.url.copy_with(host=pinned),
                                 headers=headers, content=raw, extensions=extensions)
        # Final fresh owner/scope/permissions check AFTER asynchronous DNS and
        # directly before handing this exact body to the socket transport.
        await self.rules.check(payload)
        try:
            response = await self._inner.handle_async_request(outbound)
        except SecurityViolation:
            raise
        except Exception:
            raise SecurityViolation("TRANSPORT_ERROR") from None
        if 300 <= response.status_code < 400:
            await response.aclose()
            raise SecurityViolation("REDIRECT_DENIED")
        if not 200 <= response.status_code < 300:
            await response.aclose()
            raise SecurityViolation("TRANSPORT_ERROR")
        # Discard Set-Cookie and arbitrary auth/location headers before the
        # client's cookie jar or SDK sees them.
        safe_headers = []
        for key, value in response.headers.multi_items():
            if key.lower() not in _RESPONSE_HEADERS:
                continue
            if key.lower() == "mcp-session-id":
                if not self._header_value(value):
                    await response.aclose()
                    raise SecurityViolation("HEADER_DENIED")
                if self._session_id is None:
                    if self._redactor.matches_registered(value):
                        await response.aclose()
                        raise SecurityViolation("SECRET_EGRESS")
                    self._session_id = value
                elif value != self._session_id:
                    await response.aclose()
                    raise SecurityViolation("PROTOCOL_DENIED")
                # Session IDs are credentials too, but may be sent on this
                # client's own same-endpoint header channel, never in args/logs.
                self._redactor.register(value)
            safe_headers.append((key, value))
        return httpx2.Response(response.status_code, headers=safe_headers,
                               stream=_BoundedStream(response.stream, self._limit))

    async def aclose(self) -> None:
        await self._inner.aclose()


def install_log_redaction(redactor: SecretRedactor) -> None:
    """Apply to existing OUTPUT handlers. T4A must cover any later handlers and
    trace-export attributes too; logger-parent filters do not cover children.
    """
    logs = [logging.getLogger()]
    logs.extend(value for value in logging.Logger.manager.loggerDict.values()
                if isinstance(value, logging.Logger))
    for logger in logs:
        for handler in logger.handlers:
            handler.addFilter(redactor.log_filter())


def secure_http_client(policy: NetworkPolicy, rules: ProtocolRules, *,
                       service_headers: Mapping[str, str], redactor: SecretRedactor,
                       forbidden_external_secrets: SecretRedactor,
                       timeout: float = 30, resolver: Resolver = resolve_public,
                       inner: httpx2.AsyncBaseTransport | None = None) -> httpx2.AsyncClient:
    install_log_redaction(redactor)
    transport = SafeMCPTransport(policy, rules, service_headers=service_headers, redactor=redactor,
                                 forbidden_external_secrets=forbidden_external_secrets,
                                 resolver=resolver, inner=inner)
    return httpx2.AsyncClient(transport=transport, follow_redirects=False, trust_env=False,
                             timeout=httpx2.Timeout(timeout), auth=None)

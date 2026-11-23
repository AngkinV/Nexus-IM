"""Managed MCP v1. Java owns configuration; all external IO requires T4B hooks.

No fallback policy, DNS-only check, invented run identity or call record lives here.
"""
from __future__ import annotations

import asyncio
import contextlib
import copy
import dataclasses
import hashlib
import hmac
import json
import re
import time
import uuid
from datetime import datetime, timezone
from typing import Any, Protocol
from urllib.parse import urlsplit

import httpx2

from .config import get_settings
from .mcp_tools import Client, MCPAdapter, StreamableHttpTransport, _text


class ManagedMcpError(RuntimeError):
    def __init__(self, code: str = "MCP_DENIED"):
        self.code = code
        super().__init__(code)  # Never include transport, config, args or cause text.


def bounded_json(value: Any, limit: int = 65536) -> str:
    try:
        text = json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True)
        if len(text.encode("utf-8")) > limit:
            raise ManagedMcpError("MCP_SIZE_LIMIT")
        return text
    except (TypeError, ValueError, UnicodeError, RecursionError):
        raise ManagedMcpError("MCP_INVALID_JSON") from None


@dataclasses.dataclass(frozen=True, repr=False)
class Server:
    data: dict[str, Any] = dataclasses.field(repr=False)

    def __repr__(self):
        return "Server(<private configuration>)"

    @classmethod
    def parse(cls, raw: dict[str, Any], actor: int) -> "Server":
        try:
            data = copy.deepcopy(raw)
            sid = str(uuid.UUID(data["serverId"]))
            if sid != data["serverId"] or type(actor) is not int or actor <= 0:
                raise ValueError()
            if type(data["ownerUserId"]) is not int or data["ownerUserId"] != actor:
                raise ManagedMcpError("MCP_OWNER_CONFLICT")
            for key in ("configVersion", "authorizationVersion"):
                if type(data[key]) is not int or data[key] < 1:
                    raise ValueError()
            if data["transport"] != "streamable_http" or type(data["enabled"]) is not bool:
                raise ValueError()
            parts = urlsplit(data["url"])
            if parts.scheme not in ("https", "http") or not parts.hostname or "@" in parts.netloc or parts.fragment or parts.query:
                raise ValueError()
            timeout = data.get("timeoutSec", 10)
            result_limit = data.get("resultMaxChars", 8000)
            if (type(timeout) is not int or not 1 <= timeout <= 60 or
                    type(result_limit) is not int or not 256 <= result_limit <= 64000):
                raise ValueError()
            ns = "srv_" + uuid.UUID(sid).hex
            if data.get("namespace", ns) != ns:
                raise ValueError()
            data["namespace"] = ns
            data.setdefault("timeoutSec", timeout)
            data.setdefault("resultMaxChars", result_limit)
            data.setdefault("allowedTools", [])
            if (not isinstance(data["allowedTools"], list) or len(data["allowedTools"]) > 128 or
                    not all(isinstance(t, str) and t.startswith(sid + ":") for t in data["allowedTools"])):
                raise ValueError()
            if not isinstance(data.get("displayName"), str) or not data["displayName"].strip() or len(data["displayName"]) > 80:
                raise ValueError()
            credential = data.get("credential")
            if credential is not None:
                if not isinstance(credential, dict) or set(credential) - {"kind", "headerName", "secret"}:
                    raise ValueError()
                secret = credential.get("secret")
                if (not isinstance(secret, str) or not secret.strip() or len(secret) > 4000 or
                        not all(32 <= ord(char) < 127 for char in secret) or credential.get("kind") not in {"bearer", "header"}):
                    raise ValueError()
                if credential["kind"] == "header" and credential.get("headerName", "").lower() not in {"x-api-key", "api-key"}:
                    raise ValueError()
                if credential["kind"] == "bearer" and credential.get("headerName") is not None:
                    raise ValueError()
            bounded_json(data, 1048576)
            return cls(data)
        except ManagedMcpError:
            raise
        except Exception:
            raise ManagedMcpError("MCP_INVALID_CONFIG") from None

    @property
    def id(self):
        return self.data["serverId"]

    @property
    def version(self):
        return self.data["configVersion"]

    def same_configuration(self, other: "Server") -> bool:
        # Probe/status/catalog fields may change without a configuration revision.
        keys = ("serverId", "ownerUserId", "namespace", "displayName", "transport", "url",
                "enabled", "configVersion", "authorizationVersion", "timeoutSec",
                "resultMaxChars", "allowedTools", "credentialKind", "credentialHeaderName", "credential")
        return all(self.data.get(key) == other.data.get(key) for key in keys)

    def identity(self) -> dict:
        return {"serviceId": self.id, "ownerUserId": self.data["ownerUserId"],
                "configVersion": self.version, "enabled": self.data["enabled"],
                "transport": self.data["transport"], "internal": False,
                "external": True, "endpoint": self.data["url"]}

    def public(self) -> dict:
        keys = ("serverId", "ownerUserId", "namespace", "displayName", "transport", "url", "enabled",
                "configVersion", "authorizationVersion", "timeoutSec", "resultMaxChars", "allowedTools",
                "credentialConfigured", "credentialKind", "credentialHeaderName", "createdAt", "updatedAt")
        return {k: copy.deepcopy(self.data[k]) for k in keys if k in self.data}


@dataclasses.dataclass(frozen=True, repr=False)
class McpContext:
    actorUserId: int
    agentId: str
    runId: str
    attemptId: str
    scope: Any = dataclasses.field(repr=False)
    permissionSnapshot: Any = dataclasses.field(repr=False)
    allowExternalData: Any = dataclasses.field(repr=False)
    traceId: str = ""

    def __repr__(self):
        return "McpContext(<authenticated context>)"

    def valid(self):
        return (type(self.actorUserId) is int and self.actorUserId > 0 and
                all(isinstance(v, str) and v for v in (self.agentId, self.runId, self.attemptId, self.traceId)) and
                self.scope is not None and self.permissionSnapshot is not None and self.allowExternalData is not None)


class PolicyHooks(Protocol):
    """T4B adapter. All methods mandatory; exceptions deny without raw diagnostics.

    http_client_factory returns the synchronous factory expected by FastMCP.
    It MUST pin checked DNS addresses to socket/Host/SNI, reject every redirect,
    recheck every HTTP/SSE target, disable proxies and reconstruct headers.
    It receives private configuration solely to resolve dedicated credentials.
    authorize_call must check snapshot ∩ live user/agent/run, T2 scope, strict
    argument schema, secrets and explicit egress. dispatch implements T1's
    permission-before-replay and persistent claim/replay/commit using real callId.
    """
    async def management(self, actor: int, service: dict, action: str) -> bool: ...
    async def http_client_factory(self, actor: int, ctx: McpContext | None, server: Server, action: str) -> Any: ...
    async def catalog_visible(self, ctx: McpContext, service: dict, tool: dict) -> bool: ...
    async def authorize_call(self, ctx: McpContext, snapshot: dict, current: dict, tool: dict, args: dict, call_id: str) -> bool: ...
    async def execute_managed(self, ctx: McpContext, server: Server, tool: dict, args: dict, call_id: str) -> tuple[dict, int]: ...
    async def dispatch(self, ctx: McpContext, service: dict, tool: dict, args: dict, call_id: str, invoke: Any) -> Any: ...
    async def sanitize(self, server: Server, value: Any) -> Any: ...


async def gate(policy: PolicyHooks | None, method: str, *args):
    if policy is None:
        raise ManagedMcpError()
    try:
        value = await getattr(policy, method)(*args)
        if value is not True:
            raise ManagedMcpError()
    except Exception:
        raise ManagedMcpError() from None


class JavaRegistryClient:
    """Dedicated signed data-only channel. Internal headers never reach provider."""
    async def read(self, actor: int, trace: str, operation: str, server_id: str | None = None):
        if operation not in ("list", "get") or not trace or type(actor) is not int or actor <= 0:
            raise ManagedMcpError("MCP_INVALID_CONTEXT")
        settings = get_settings()
        if (settings.java_internal_token in ("", "dev-internal-token-change-me") or
                settings.internal_signing_secret in ("", "dev-signing-secret-change-me")):
            raise ManagedMcpError()
        body = {"actorUserId": actor, "traceId": trace, "operation": operation}
        if server_id is not None:
            body["serverId"] = server_id
        encoded = bounded_json(body).encode("utf-8")
        timestamp, nonce = str(int(time.time() * 1000)), str(uuid.uuid4())
        signature_input = timestamp + "." + nonce + "." + hashlib.sha256(encoded).hexdigest()
        headers = {"Authorization": "Bearer " + settings.java_internal_token,
                   "Content-Type": "application/json", "X-Internal-Service": "nexus-agent-backend",
                   "X-Internal-Timestamp": timestamp, "X-Internal-Nonce": nonce,
                   "X-Internal-Signature": hmac.new(settings.internal_signing_secret.encode(), signature_input.encode(), hashlib.sha256).hexdigest(),
                   "X-Trace-Id": trace, "X-Actor-User-Id": str(actor)}
        try:
            async with httpx2.AsyncClient(trust_env=False, follow_redirects=False, timeout=10) as client:
                async with client.stream("POST", settings.java_internal_base_url.rstrip("/") + "/mcp-management/registry",
                                         content=encoded, headers=headers) as response:
                    if response.status_code != 200:
                        raise ManagedMcpError("MCP_REGISTRY_UNAVAILABLE")
                    chunks, size = [], 0
                    async for chunk in response.aiter_bytes():
                        size += len(chunk)
                        if size > 2097152:
                            raise ManagedMcpError("MCP_SIZE_LIMIT")
                        chunks.append(chunk)
                    payload = json.loads(b"".join(chunks))
            if operation == "list":
                values = payload["servers"]
                if not isinstance(values, list) or len(values) > 128:
                    raise ManagedMcpError("MCP_SIZE_LIMIT")
                return [Server.parse(v, actor) for v in values]
            return Server.parse(payload["server"], actor)
        except ManagedMcpError:
            raise
        except Exception:
            raise ManagedMcpError("MCP_REGISTRY_UNAVAILABLE") from None


def schema_check(schema: Any):
    bounded_json(schema, 32768)
    if not isinstance(schema, dict) or schema.get("type") != "object":
        raise ManagedMcpError("MCP_INVALID_SCHEMA")
    try:
        from jsonschema import Draft202012Validator
        Draft202012Validator.check_schema(schema)
    except Exception:
        # Missing validator or invalid meta-schema is not a usable catalog.
        raise ManagedMcpError("MCP_INVALID_SCHEMA") from None
    # External references are not fetched; only bounded local definitions are supported.
    def visit(node, depth=0):
        if depth > 24:
            raise ManagedMcpError("MCP_INVALID_SCHEMA")
        if isinstance(node, dict):
            if "$ref" in node and (not isinstance(node["$ref"], str) or not node["$ref"].startswith("#/")):
                raise ManagedMcpError("MCP_INVALID_SCHEMA")
            for value in node.values():
                visit(value, depth + 1)
        elif isinstance(node, list):
            for value in node:
                visit(value, depth + 1)
    visit(schema)
    if "properties" in schema and not isinstance(schema["properties"], dict):
        raise ManagedMcpError("MCP_INVALID_SCHEMA")
    if "required" in schema and (not isinstance(schema["required"], list) or not all(isinstance(x, str) for x in schema["required"])):
        raise ManagedMcpError("MCP_INVALID_SCHEMA")


def tool_views(server: Server, tools: list[Any]) -> tuple[str, list[dict]]:
    if len(tools) > 128:
        raise ManagedMcpError("MCP_SIZE_LIMIT")
    result, seen = [], set()
    for tool in tools:
        remote = tool.name
        if not isinstance(remote, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", remote) or remote in seen:
            raise ManagedMcpError("MCP_INVALID_SCHEMA")
        seen.add(remote)
        schema = copy.deepcopy(tool.input_schema)
        schema_check(schema)
        # Full server namespace plus digest: <=64 model-name chars, independent of label.
        name = server.data["namespace"] + "__" + hashlib.sha256(remote.encode()).hexdigest()[:26]
        annotations = getattr(tool, "annotations", None)
        result.append({"toolId": server.id + ":" + remote, "serverId": server.id, "name": name,
                       "remoteName": remote, "description": (tool.description or "")[:4096],
                       "inputSchema": schema, "readOnlyHint": getattr(annotations, "read_only_hint", None),
                       "risk": "unclassified", "allowed": server.id + ":" + remote in server.data.get("allowedTools", []),
                       "timeoutSec": server.data.get("timeoutSec", 10), "resultMaxChars": server.data.get("resultMaxChars", 8000)})
    result.sort(key=lambda t: t["toolId"])
    # Authorization is not schema version: toggling allowedTools does not change directory digest.
    names = [tool["name"] for tool in result]
    if len(set(names)) != len(names):
        raise ManagedMcpError("MCP_INVALID_SCHEMA")
    return catalog_digest(result), result


def catalog_digest(tools: list[dict]) -> str:
    from .mcp_catalog import catalog_version
    bounded_json(tools, 524288)
    try:
        return catalog_version(tools)
    except Exception:
        raise ManagedMcpError("MCP_INVALID_SCHEMA") from None


class MCPProvider:
    @contextlib.asynccontextmanager
    async def session(self, server: Server, policy: PolicyHooks | None, ctx: McpContext | None, action: str, *, actor: int):
        if (policy is None or type(actor) is not int or actor <= 0 or
                actor != server.data["ownerUserId"] or (ctx is not None and ctx.actorUserId != actor)):
            raise ManagedMcpError()
        try:
            factory = await policy.http_client_factory(actor, ctx, server, action)
            if not callable(factory):
                raise ManagedMcpError()
            # No inbound/internal header dict is accepted here. T4B rebuilds credentials.
            client = Client(StreamableHttpTransport(server.data["url"], headers={}, httpx_client_factory=factory),
                            mode="legacy", timeout=server.data.get("timeoutSec", 10), init_timeout=server.data.get("timeoutSec", 10))
            adapter = MCPAdapter(client)
            async with contextlib.AsyncExitStack() as stack:
                await stack.enter_async_context(adapter)
                # MCPAdapter clones Client; use the one its context actually opened.
                active_client = adapter.client
                yield active_client, adapter
        except asyncio.CancelledError:
            raise
        except ManagedMcpError:
            raise
        except Exception:
            raise ManagedMcpError("MCP_TRANSPORT_ERROR") from None

    async def discover(self, server: Server, policy: PolicyHooks | None, actor: int):
        await gate(policy, "management", actor, server.identity(), "discover")
        async with asyncio.timeout(server.data.get("timeoutSec", 10)):
            async with self.session(server, policy, None, "discover", actor=actor) as (client, _adapter):
                # Client's context performs initialize; tools/list is the only explicit RPC.
                tools = await client.list_tools()
                version, views = tool_views(server, tools)
                clean = await policy.sanitize(server, views)
                bounded_json(clean, 524288)
                if clean != views:
                    # Secret-bearing schema cannot be altered silently and then executed.
                    raise ManagedMcpError("MCP_UNSAFE_CATALOG")
                return version, views


class ToolRegistry:
    def __init__(self, authority=None, provider=None, policy=None):
        self.authority = authority or JavaRegistryClient()
        self.provider = provider or MCPProvider()
        self.policy = policy
        self._servers: dict[str, Server] = {}
        self._catalogs: dict[tuple[str, int, str], list[dict]] = {}
        self._status: dict[tuple[str, int], dict] = {}
        self._tombstones: dict[str, tuple[int, int]] = {}

    def register(self, server: Server):
        old = self._servers.get(server.id)
        tomb = self._tombstones.get(server.id)
        if tomb and (tomb[0] != server.data["ownerUserId"] or server.version <= tomb[1]):
            raise ManagedMcpError("MCP_VERSION_CONFLICT")
        if old:
            if old.data["ownerUserId"] != server.data["ownerUserId"]:
                raise ManagedMcpError("MCP_OWNER_CONFLICT")
            if server.version < old.version or (server.version == old.version and not old.same_configuration(server)):
                raise ManagedMcpError("MCP_VERSION_CONFLICT")

        # Validate before mutating caches, including an observed empty directory.
        digest = server.data.get("catalogVersion")
        views = server.data.get("tools", [])
        if digest is not None:
            if not isinstance(views, list) or len(views) > 128:
                raise ManagedMcpError("MCP_SIZE_LIMIT")
            names, ids = set(), set()
            for view in views:
                schema_check(view["inputSchema"])
                remote = view["remoteName"]
                if (not isinstance(remote, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", remote) or
                        view["serverId"] != server.id or view["toolId"] != server.id + ":" + remote):
                    raise ManagedMcpError("MCP_INVALID_SCHEMA")
                expected = server.data["namespace"] + "__" + hashlib.sha256(remote.encode()).hexdigest()[:26]
                if view["name"] != expected or view["name"] in names or view["toolId"] in ids:
                    raise ManagedMcpError("MCP_INVALID_SCHEMA")
                names.add(view["name"]); ids.add(view["toolId"])
            if digest != catalog_digest(views):
                raise ManagedMcpError("MCP_INVALID_SCHEMA")
            bounded_json(views, 524288)

        if old is None or old.version != server.version:
            self._catalogs = {k: v for k, v in self._catalogs.items() if k[0] != server.id}
            self._status = {k: v for k, v in self._status.items() if k[0] != server.id}
        self._servers[server.id] = server
        if digest is not None:
            self._catalogs[(server.id, server.version, digest)] = copy.deepcopy(views)
        persisted = server.data.get("status")
        status_key = (server.id, server.version)
        if status_key not in self._status and isinstance(persisted, dict) and persisted.get("configVersion") == server.version:
            state = persisted.get("state")
            if state in ("connected", "error"):
                try:
                    at = datetime.fromisoformat(persisted["checkedAt"].replace("Z", "+00:00"))
                    if at.tzinfo is None:
                        raise ValueError()
                    latency = persisted.get("latencyMs")
                    if latency is not None and (type(latency) is not int or latency < 0):
                        raise ValueError()
                    self._status[status_key] = {"state": state, "configVersion": server.version,
                        "checkedAt": persisted["checkedAt"], "latencyMs": latency,
                        "error": {"code": "MCP_PROBE_FAILED", "summary": "MCP probe failed"} if state == "error" else None}
                except (KeyError, TypeError, ValueError):
                    pass  # No trustworthy probe timestamp means unknown, never success.
        return server

    def delete(self, actor: int, sid: str, version: int):
        old = self._servers.get(sid)
        tomb = self._tombstones.get(sid)
        if old and (old.data["ownerUserId"] != actor or version < old.version):
            raise ManagedMcpError("MCP_VERSION_CONFLICT")
        if tomb and (tomb[0] != actor or version < tomb[1]):
            raise ManagedMcpError("MCP_VERSION_CONFLICT")
        self._tombstones[sid] = (actor, version)
        self._servers.pop(sid, None)
        self._catalogs = {k: v for k, v in self._catalogs.items() if k[0] != sid}
        self._status = {k: v for k, v in self._status.items() if k[0] != sid}

    async def current(self, actor: int, trace: str, sid: str, action: str = "get"):
        server = await self.authority.read(actor, trace, "get", sid)
        await gate(self.policy, "management", actor, server.identity(), action)
        return self.register(server)

    async def list_current(self, actor: int, trace: str):
        servers = await self.authority.read(actor, trace, "list")
        # Validate the whole returned set before changing any local cache.
        for server in servers:
            await gate(self.policy, "management", actor, server.identity(), "list")
        return [self.register(server) for server in servers]

    def status(self, server: Server):
        return copy.deepcopy(self._status.get((server.id, server.version), {
            "state": "unknown", "configVersion": server.version, "checkedAt": None, "latencyMs": None, "error": None}))

    def tools(self, server: Server):
        # Execution uses the Java-committed catalog, not an in-flight refresh.
        version = server.data.get("catalogVersion")
        return version, copy.deepcopy(self._catalogs.get((server.id, server.version, version), []))

    def view(self, server: Server):
        result = server.public()
        result.update(registrationState="registered", status=self.status(server), catalogVersion=self.tools(server)[0])
        return result

    async def probe(self, actor: int, trace: str, sid: str, expected: int):
        server = await self.current(actor, trace, sid)
        if expected != server.version:
            raise ManagedMcpError("MCP_VERSION_CONFLICT")
        started = time.perf_counter()
        await gate(self.policy, "management", actor, server.identity(), "test")
        try:
            version, tools = await self.provider.discover(server, self.policy, actor)
            error = None
        except asyncio.CancelledError:
            raise
        except Exception:
            version, tools, error = None, [], {"code": "MCP_PROBE_FAILED", "summary": "MCP probe failed"}
        latest = await self.current(actor, trace, sid)
        if latest.version != expected:
            raise ManagedMcpError("MCP_VERSION_CONFLICT")
        state = {"state": "error" if error else "connected", "configVersion": expected,
                 "checkedAt": datetime.now(timezone.utc).isoformat(), "latencyMs": int((time.perf_counter() - started) * 1000), "error": error}
        self._status[(sid, expected)] = state
        if error is None:
            self._catalogs[(sid, expected, version)] = tools
        return {"serverId": sid, "configVersion": expected, "ok": error is None, "status": state,
                "catalogVersion": version, "catalogValidation": "draft2020-12-local-ref-v1" if error is None else None,
                "tools": tools, "toolsCount": len(tools)}


class ManagedMcpExecutor:
    def __init__(self, registry: ToolRegistry, ctx: McpContext | None):
        self.registry = registry
        self.ctx = copy.deepcopy(ctx)
        self._snapshot: dict[str, tuple[Server, dict, str]] | None = None
        self._lock = asyncio.Lock()
        self._closed = False
        self._active_calls: set[asyncio.Task] = set()

    async def __aenter__(self):
        if self._closed:
            raise ManagedMcpError("MCP_CLOSED")
        return self

    async def __aexit__(self, *exc):
        self._closed = True
        self._snapshot = None
        current = asyncio.current_task()
        pending = [task for task in self._active_calls if task is not current and not task.done()]
        for task in pending:
            task.cancel()
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)

    async def schemas(self):
        if self._closed or not isinstance(self.ctx, McpContext) or not self.ctx.valid() or self.registry.policy is None:
            return []
        async with self._lock:
            if self._snapshot is None:
                snapshot = {}
                for server in await self.registry.list_current(self.ctx.actorUserId, self.ctx.traceId):
                    version, tools = self.registry.tools(server)
                    if not server.data["enabled"] or not version:
                        continue
                    for tool in tools:
                        if tool["toolId"] not in server.data.get("allowedTools", []):
                            continue
                        identity = {"serviceId": server.id, "toolId": tool["toolId"], "schemaVersion": version, "risk": "unclassified",
                                    "remoteName": tool["remoteName"], "name": tool["name"], "inputSchema": copy.deepcopy(tool["inputSchema"])}
                        try:
                            await gate(self.registry.policy, "catalog_visible", self.ctx, server.identity(), identity)
                        except ManagedMcpError:
                            continue
                        snapshot[tool["name"]] = (copy.deepcopy(server), copy.deepcopy(tool), version)
                self._snapshot = snapshot
            return [{"type": "function", "function": {"name": t["name"], "description": t["description"], "parameters": copy.deepcopy(t["inputSchema"])}}
                    for _, t, _ in self._snapshot.values()]

    async def execute(self, name: str, args: dict, call_id: str | None = None):
        if self._closed:
            raise ManagedMcpError("MCP_CLOSED")
        task = asyncio.current_task()
        if task is not None:
            self._active_calls.add(task)
        try:
            return await self._execute(name, args, call_id)
        finally:
            if task is not None:
                self._active_calls.discard(task)

    async def _execute(self, name: str, args: dict, call_id: str | None = None):
        await self.schemas()
        if not call_id or not isinstance(call_id, str) or not isinstance(args, dict) or not self._snapshot or name not in self._snapshot:
            raise ManagedMcpError()
        args = copy.deepcopy(args)
        bounded_json(args, 65536)
        server, tool, version = self._snapshot[name]
        ctx, policy = self.ctx, self.registry.policy
        identity = {"serviceId": server.id, "toolId": tool["toolId"], "schemaVersion": version, "risk": "unclassified",
                    "remoteName": tool["remoteName"], "name": tool["name"], "inputSchema": copy.deepcopy(tool["inputSchema"])}

        async def current_gate():
            if self._closed:
                raise ManagedMcpError("MCP_CLOSED")
            current = await self.registry.current(ctx.actorUserId, ctx.traceId, server.id)
            live_version, live_tools = self.registry.tools(current)
            if (current.version != server.version or not current.data["enabled"] or live_version != version or
                    tool["toolId"] not in current.data.get("allowedTools", []) or not any(t["toolId"] == tool["toolId"] for t in live_tools)):
                raise ManagedMcpError("MCP_REVOKED")
            await gate(policy, "authorize_call", ctx, server.identity(), current.identity(), identity, args, call_id)
            return current

        started = time.perf_counter()
        try:
            async with asyncio.timeout(server.data.get("timeoutSec", 10)):
                await current_gate()  # Immediately before connection, never cached authority.
                native = getattr(policy, "execute_managed", None)
                if callable(native):
                    # Production T4B owns the ENTIRE wire/journal lifecycle, including replay before connect.
                    result, latency = await native(ctx, server, identity, copy.deepcopy(args), call_id)
                    await current_gate()
                    clean = await policy.sanitize(server, result)
                    expected_key = "t_" + hashlib.sha256(tool["remoteName"].encode()).hexdigest()
                    if (not isinstance(result, dict) or result != clean or result.get("untrusted") is not True or
                            result.get("source") != "mcp:" + server.id + "/" + expected_key or
                            result.get("dataClass") != "mcp_external" or not isinstance(result.get("content"), str) or
                            len(result["content"]) > server.data["resultMaxChars"] or type(result.get("truncated")) is not bool or
                            type(latency) is not int or latency < 0):
                        raise ManagedMcpError("MCP_UNSAFE_RESULT")
                    return result, latency
                # Compatibility hooks are injectable only; the shipped production policy always delegates above.
                async with self.registry.provider.session(server, policy, ctx, "call", actor=ctx.actorUserId) as (client, adapter):
                    offered = {t.name: t for t in await client.list_tools()}
                    descriptor = offered.get(tool["remoteName"])
                    if descriptor is None:
                        raise ManagedMcpError("MCP_TOOL_WITHDRAWN")
                    schema_check(descriptor.input_schema)
                    if descriptor.input_schema != tool["inputSchema"]:
                        raise ManagedMcpError("MCP_SCHEMA_CHANGED")
                    bound = {t.name: t for t in await adapter.list_tools()}
                    remote = bound.get(tool["remoteName"])
                    if remote is None:
                        raise ManagedMcpError("MCP_TOOL_WITHDRAWN")
                    # Compare the real MCP schema above, not a lossy OpenAI conversion.
                    await current_gate()  # After connection/discovery, directly before T1 dispatch.

                    async def invoke():
                        message = await remote.ainvoke({"type": "tool_call", "id": call_id, "name": tool["remoteName"], "args": args})
                        if message.status == "error":
                            raise ManagedMcpError("MCP_TOOL_ERROR")
                        structured = (message.artifact or {}).get("structured_content")
                        value = structured if structured is not None else _text(message.content)
                        await current_gate()  # Revocation during a slow call also blocks commit/output.
                        clean = await policy.sanitize(server, value)
                        content = clean if isinstance(clean, str) else bounded_json(clean, 1048576)
                        # Redact after JSON serialization as well as on the raw data.
                        content = await policy.sanitize(server, content)
                        if not isinstance(content, str) or len(content.encode("utf-8")) > 1048576:
                            raise ManagedMcpError("MCP_UNSAFE_RESULT")
                        limit = server.data.get("resultMaxChars", 8000)
                        return {"source": "external:" + server.id, "dataClass": "external_tool",
                                "untrusted": True, "content": content[:limit], "truncated": len(content) > limit}

                    # Dispatcher/journal commits the already sanitized, bounded envelope.
                    result = await policy.dispatch(ctx, server.identity(), identity, args, call_id, invoke)
                    await current_gate()  # A replay is not exempt from fresh authorization.
                    clean = await policy.sanitize(server, result)
                    if (not isinstance(result, dict) or clean != result or
                            set(result) != {"source", "dataClass", "untrusted", "content", "truncated"} or
                            result.get("source") != "external:" + server.id or result.get("dataClass") != "external_tool" or
                            result.get("untrusted") is not True or type(result.get("truncated")) is not bool or
                            not isinstance(result.get("content"), str) or len(result["content"]) > server.data.get("resultMaxChars", 8000)):
                        raise ManagedMcpError("MCP_UNSAFE_RESULT")
                    return result, int((time.perf_counter() - started) * 1000)
        except asyncio.CancelledError:
            raise
        except TimeoutError:
            raise ManagedMcpError("MCP_TIMEOUT") from None
        except ManagedMcpError:
            raise
        except Exception:
            raise ManagedMcpError("MCP_EXECUTION_FAILED") from None


# T4B installs a real adapter on this instance at application composition time.
managed_registry = ToolRegistry()

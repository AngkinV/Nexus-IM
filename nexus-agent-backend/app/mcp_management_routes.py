"""Signed Java-only MCP management RPC. Never expose private records/errors."""
from __future__ import annotations

import json
import re
import time

from fastapi import APIRouter, Depends, HTTPException, Request

from .config import get_settings
from .security import verify_internal_signature
from .managed_mcp import ManagedMcpError, Server, bounded_json, gate, managed_registry

router = APIRouter(prefix="/v1/agent/mcp", tags=["internal-mcp-management"])
_mcp_nonces: dict[str, int] = {}


async def bounded_body(request: Request) -> bytes:
    # Bound chunked bodies before the existing signature dependency reads them.
    if not hasattr(request, "_body"):
        chunks, size = [], 0
        async for chunk in request.stream():
            size += len(chunk)
            if size > 1048576:
                raise HTTPException(413, detail={"code": "MCP_SIZE_LIMIT", "summary": "MCP request too large"})
            chunks.append(chunk)
        request._body = b"".join(chunks)
    return await request.body()


async def authenticate(request: Request):
    # Reuse Java→Python HMAC; no extra bearer requirement absent from the gateway contract.
    if get_settings().internal_signing_secret in ("", "dev-signing-secret-change-me"):
        raise HTTPException(403, detail={"code": "MCP_DENIED", "summary": "Internal authentication required"})
    await bounded_body(request)
    if any(k.lower().startswith("x-model-") for k in request.headers):
        raise HTTPException(403, detail={"code": "MCP_DENIED", "summary": "Model headers forbidden"})
    try:
        auth = await verify_internal_signature(
            request,
            x_internal_service=request.headers.get("X-Internal-Service", ""),
            x_internal_timestamp=request.headers.get("X-Internal-Timestamp", ""),
            x_internal_nonce=request.headers.get("X-Internal-Nonce", ""),
            x_internal_signature=request.headers.get("X-Internal-Signature", ""),
            x_trace_id=request.headers.get("X-Trace-Id", ""),
            x_actor_user_id=request.headers.get("X-Actor-User-Id", ""),
            x_model_provider=None, x_model_base_url=None, x_model_name=None, x_model_api_key=None)
        nonce = request.headers.get("X-Internal-Nonce", "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", nonce):
            raise ValueError()
        now = int(time.time() * 1000)
        timestamp = int(request.headers["X-Internal-Timestamp"])
        for saved in [key for key, expiry in _mcp_nonces.items() if expiry <= now]:
            del _mcp_nonces[saved]
        if nonce in _mcp_nonces or len(_mcp_nonces) >= 10000:
            raise ValueError()
        # The timestamp acceptance interval is CLOSED. Retain through its last millisecond.
        _mcp_nonces[nonce] = timestamp + get_settings().nonce_skew_ms + 1
        return auth
    except Exception:
        raise HTTPException(403, detail={"code": "MCP_DENIED", "summary": "Internal authentication required"}) from None


async def handle(request: Request, auth: dict, operation: str, sid: str | None = None):
    try:
        raw = await request.body()
        if len(raw) > 1048576:
            raise ManagedMcpError("MCP_SIZE_LIMIT")
        body = json.loads(raw)
        bounded_json(body, 1048576)
        if not isinstance(body, dict) or set(body) - {"actorUserId", "traceId", "operation", "serverId", "expectedConfigVersion", "server"}:
            raise ManagedMcpError("MCP_INVALID_REQUEST")
        actor, trace = body.get("actorUserId"), body.get("traceId")
        if (type(actor) is not int or actor <= 0 or actor != auth["actorUserId"] or not isinstance(trace, str) or not 1 <= len(trace) <= 128 or
                trace != auth["traceId"] or body.get("operation") != operation or (sid is not None and body.get("serverId") != sid)):
            raise ManagedMcpError("MCP_INVALID_REQUEST")
        registry = managed_registry
        if operation == "list":
            servers = await registry.list_current(actor, trace)
            for server in servers:
                await gate(registry.policy, "management", actor, server.identity(), "list")
            return {"ok": True, "servers": [registry.view(s) for s in servers]}
        if operation in ("register", "update"):
            server = Server.parse(body["server"], actor)
            if body.get("serverId") != server.id or (sid and sid != server.id):
                raise ManagedMcpError("MCP_INVALID_REQUEST")
            if operation == "update":
                expected = body.get("expectedConfigVersion")
                # Java already performed optimistic PATCH and committed this target version.
                if type(expected) is not int or expected < 1 or server.version != expected:
                    raise ManagedMcpError("MCP_VERSION_CONFLICT")
            await gate(registry.policy, "management", actor, server.identity(), operation)
            registry.register(server)  # Never connects, probes or calls.
            return {"ok": True, "serverId": server.id, "configVersion": server.version, "server": registry.view(server)}
        expected = body.get("expectedConfigVersion")
        if operation in ("delete", "test", "refresh") and (type(expected) is not int or expected < 1):
            raise ManagedMcpError("MCP_VERSION_CONFLICT")
        if operation == "delete":
            # Java has already deleted its durable row; signed owner/version remains.
            server = Server.parse(body["server"], actor) if body.get("server") else registry._servers.get(sid)
            if server is None or server.id != sid or server.data["ownerUserId"] != actor or expected < server.version:
                raise ManagedMcpError("MCP_VERSION_CONFLICT")
            await gate(registry.policy, "management", actor, server.identity(), "delete")
            registry.delete(actor, sid, expected)
            return {"ok": True, "deleted": True, "serverId": sid, "configVersion": expected}
        server = await registry.current(actor, trace, sid)
        await gate(registry.policy, "management", actor, server.identity(), operation)
        if operation in ("test", "refresh"):
            return await registry.probe(actor, trace, sid, expected)
        if operation == "tools":
            version, tools = registry.tools(server)
            if registry.policy is None:
                raise ManagedMcpError()
            clean = await registry.policy.sanitize(server, tools)
            if clean != tools:
                raise ManagedMcpError("MCP_UNSAFE_CATALOG")
            return {"ok": True, "serverId": sid, "configVersion": server.version, "catalogVersion": version, "tools": tools}
        return {"ok": True, "serverId": sid, "configVersion": server.version,
                "registrationState": "registered", "status": registry.status(server)}
    except ManagedMcpError as exc:
        # Only locally controlled codes are rendered. No raw exception/cause/config.
        allowed = {"MCP_DENIED", "MCP_SIZE_LIMIT", "MCP_INVALID_REQUEST", "MCP_INVALID_CONFIG", "MCP_OWNER_CONFLICT",
                   "MCP_VERSION_CONFLICT", "MCP_REGISTRY_UNAVAILABLE", "MCP_INVALID_SCHEMA", "MCP_UNSAFE_CATALOG"}
        code = exc.code if exc.code in allowed else "MCP_MANAGEMENT_FAILED"
        return {"ok": False, "error": {"code": code, "summary": "MCP management request rejected"}}
    except Exception:
        return {"ok": False, "error": {"code": "MCP_MANAGEMENT_FAILED", "summary": "MCP management request rejected"}}


@router.post("/servers/query")
async def query(request: Request, auth=Depends(authenticate)):
    return await handle(request, auth, "list")


@router.post("/servers")
async def register(request: Request, auth=Depends(authenticate)):
    return await handle(request, auth, "register")


@router.patch("/servers/{server_id}")
async def update(server_id: str, request: Request, auth=Depends(authenticate)):
    return await handle(request, auth, "update", server_id)


@router.delete("/servers/{server_id}")
async def delete(server_id: str, request: Request, auth=Depends(authenticate)):
    return await handle(request, auth, "delete", server_id)


@router.post("/servers/{server_id}/test")
async def test(server_id: str, request: Request, auth=Depends(authenticate)):
    return await handle(request, auth, "test", server_id)


@router.post("/servers/{server_id}/tools/query")
async def tools(server_id: str, request: Request, auth=Depends(authenticate)):
    return await handle(request, auth, "tools", server_id)


@router.post("/servers/{server_id}/tools/refresh")
async def refresh(server_id: str, request: Request, auth=Depends(authenticate)):
    return await handle(request, auth, "refresh", server_id)


@router.post("/servers/{server_id}/status/query")
async def status(server_id: str, request: Request, auth=Depends(authenticate)):
    return await handle(request, auth, "status", server_id)

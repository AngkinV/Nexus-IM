"""Signed MCP management RPC regression fixtures; not real Java/MCP E2E evidence.

Only T1 executes these tests. No model, embedding, real credentials or service
process is started. The test policy below never ships in app/.
"""
from __future__ import annotations

import copy
import hashlib
import hmac
import json
import time
import uuid

import httpx2
import pytest
from fastapi import FastAPI

from app import mcp_management_routes as routes
from app import security
from app.config import get_settings
from app.managed_mcp import Server, ToolRegistry

SID = "714e3483-cf38-42e4-88cd-6cf56af62f71"
SIGNER = "fixture-only-hmac-material-for-unit-tests"
SECRET = "fixture-only-write-credential"


def record(version=1, owner=41):
    return {"serverId": SID, "ownerUserId": owner, "configVersion": version, "authorizationVersion": version,
            "namespace": "srv_" + SID.replace("-", ""), "displayName": "Synthetic service",
            "transport": "streamable_http", "url": "https://mcp.example.invalid/mcp", "enabled": True,
            "timeoutSec": 10, "resultMaxChars": 8000, "allowedTools": [], "credentialConfigured": True,
            "credentialKind": "bearer", "credentialHeaderName": None,
            "credential": {"kind": "bearer", "secret": SECRET}, "catalogVersion": None, "tools": []}


class Policy:
    async def management(self, actor, server, _operation):
        return actor == server["ownerUserId"]

    async def sanitize(self, _server, value):
        return copy.deepcopy(value)


class Authority:
    def __init__(self):
        self.data = record()

    async def read(self, actor, _trace, operation, _sid=None):
        server = Server.parse(copy.deepcopy(self.data), actor)
        return [server] if operation == "list" else server


@pytest.fixture
def setup(monkeypatch):
    monkeypatch.setattr(get_settings(), "internal_signing_secret", SIGNER)
    monkeypatch.setattr(get_settings(), "expected_caller", "nexus-chat-backend")
    monkeypatch.setattr(get_settings(), "nonce_skew_ms", 1000)
    registry = ToolRegistry(Authority(), policy=Policy())
    monkeypatch.setattr(routes, "managed_registry", registry)
    security._seen_nonces.clear(); routes._mcp_nonces.clear()
    app = FastAPI(); app.include_router(routes.router)
    return app, registry


def signed(body, *, nonce=None, timestamp=None, actor=41):
    content = json.dumps(body, ensure_ascii=False).encode("utf-8")
    timestamp = str(timestamp if timestamp is not None else int(time.time() * 1000))
    nonce = nonce or uuid.uuid4().hex
    value = timestamp + "." + nonce + "." + hashlib.sha256(content).hexdigest()
    headers = {"Content-Type": "application/json", "X-Internal-Service": "nexus-chat-backend",
               "X-Internal-Timestamp": timestamp, "X-Internal-Nonce": nonce,
               "X-Internal-Signature": hmac.new(SIGNER.encode(), value.encode(), hashlib.sha256).hexdigest(),
               "X-Actor-User-Id": str(actor), "X-Trace-Id": "tr_fixture"}
    return content, headers


def body(operation, version=1):
    return {"actorUserId": 41, "traceId": "tr_fixture", "operation": operation,
            "serverId": SID, "expectedConfigVersion": version, "server": record(version)}


async def send(app, method, path, payload, **signing):
    content, headers = signed(payload, **signing)
    async with httpx2.AsyncClient(transport=httpx2.ASGITransport(app=app), base_url="http://fixture") as client:
        return await client.request(method, "/v1/agent/mcp" + path, content=content, headers=headers)


async def test_register_is_signed_without_extra_bearer_and_never_exposes_credential(setup):
    app, registry = setup
    response = await send(app, "POST", "/servers", body("register"))
    result = response.json()
    assert response.status_code == 200 and result["ok"] is True
    assert result["serverId"] == SID and result["configVersion"] == 1
    assert result["server"]["status"]["state"] == "unknown"
    assert SECRET not in response.text and "credential" not in result["server"]
    assert registry._servers[SID].version == 1


async def test_update_uses_already_persisted_target_version_not_target_plus_one(setup):
    app, registry = setup
    await send(app, "POST", "/servers", body("register"))
    response = await send(app, "PATCH", f"/servers/{SID}", body("update", 2))
    assert response.json()["ok"] is True and registry._servers[SID].version == 2


async def test_owner_and_path_mismatches_mutate_zero_registry_data(setup):
    app, registry = setup
    foreign = body("register"); foreign["server"]["ownerUserId"] = 42
    response = await send(app, "POST", "/servers", foreign)
    assert response.json()["ok"] is False and registry._servers == {}
    response = await send(app, "PATCH", "/servers/another_server", body("update", 2))
    assert response.json()["ok"] is False and registry._servers == {}


async def test_bad_signature_and_model_headers_are_denied_without_echo(setup):
    app, registry = setup
    content, headers = signed(body("register")); headers["X-Internal-Signature"] = "0" * 64
    async with httpx2.AsyncClient(transport=httpx2.ASGITransport(app=app), base_url="http://fixture") as client:
        response = await client.post("/v1/agent/mcp/servers", content=content, headers=headers)
        assert response.status_code == 403 and SECRET not in response.text
    assert registry._servers == {}
    content, headers = signed(body("register")); headers["X-Model-Api-Key"] = SECRET
    async with httpx2.AsyncClient(transport=httpx2.ASGITransport(app=app), base_url="http://fixture") as client:
        response = await client.post("/v1/agent/mcp/servers", content=content, headers=headers)
        assert response.status_code == 403 and SECRET not in response.text


async def test_future_timestamp_replay_remains_denied_after_baseline_nonce_expiry(setup, monkeypatch):
    app, _registry = setup
    clock = [1_800_000_000_000]
    monkeypatch.setattr(time, "time", lambda: clock[0] / 1000)
    timestamp = clock[0] + 500
    response = await send(app, "POST", "/servers", body("register"), nonce="nonce_future", timestamp=timestamp)
    assert response.json()["ok"] is True
    clock[0] += 1001
    response = await send(app, "POST", "/servers", body("register"), nonce="nonce_future", timestamp=timestamp)
    assert response.status_code == 403
    clock[0] = timestamp + 1000
    response = await send(app, "POST", "/servers", body("register"), nonce="nonce_future", timestamp=timestamp)
    assert response.status_code == 403


async def test_signed_tombstone_invalidates_previous_registration_without_secrets(setup):
    app, registry = setup
    await send(app, "POST", "/servers", body("register"))
    deleted = body("delete", 2)
    deleted["server"].update(enabled=False, deleted=True, credentialConfigured=False)
    deleted["server"].pop("credential")
    response = await send(app, "DELETE", f"/servers/{SID}", deleted)
    assert response.json()["ok"] is True and registry._servers == {}
    assert registry._tombstones[SID] == (41, 2)
    replay = await send(app, "POST", "/servers", body("register"))
    assert replay.json()["ok"] is False and registry._servers == {}

"""T4A owner-patch integration regressions, queued for T1 only.

Uses the real MCPAdapter/client over a counting in-process HTTP fixture when
T4A applies the executor patch. It does NOT prove a real Java/MCP deployment.
The baseline lacks the new factory API, so these cases explicitly skip there.
"""
from __future__ import annotations

import inspect
import json

import httpx2
import pytest

from app import mcp_tools
from app.mcp_security import (
    ExecutionGate, GuardedExecutor, MCPBinding, NetworkPolicy, SecureSessionFactory,
    SecretRedactor, SecurityViolation,
)
from app.mcp_security import bridge, network
from mcp_security_fixtures import EXTERNAL, INTERNAL, KEY, PROVIDER, SCHEMA, setup

PATCHED = "security" in inspect.signature(mcp_tools.ToolExecutor).parameters
# Integration took the other of the two delivered routes: ToolExecutor hands every managed
# (srv_*) call to T4A's ManagedMcpExecutor, which delegates to this guard through
# T4BPolicyAdapter, and the legacy external path is closed. `ToolExecutor(security=...)` was
# therefore not added; the guarded route is covered by test_mcp_policy_adapter.py,
# test_managed_mcp.py and test_mcp_tools.py.
pytestmark = pytest.mark.skipif(not PATCHED, reason="ToolExecutor(security=) route not adopted; managed calls reach the guard through T4BPolicyAdapter")


async def test_missing_adapters_refuse_real_executor_before_catalog_or_connection(monkeypatch):
    calls = []
    async def catalog():
        calls.append("catalog")
        raise AssertionError("unguarded discovery must not occur")
    monkeypatch.setattr(mcp_tools, "catalog", catalog)
    async with mcp_tools.ToolExecutor(7, "trace_unit") as executor:
        with pytest.raises(mcp_tools.ToolError) as refused:
            await executor.schemas()
        assert refused.value.code == "FORBIDDEN"
        with pytest.raises(mcp_tools.ToolError):
            await executor.execute("get_chat_profile", {"chat_id": 5}, call_id="call_0001")
    assert calls == []


async def test_existing_executor_identity_must_match_authenticated_guard():
    *_, executor = setup()
    with pytest.raises(mcp_tools.ToolError) as refused:
        mcp_tools.ToolExecutor(8, "trace_unit", security=executor)
    assert refused.value.code == "FORBIDDEN"


class Server(httpx2.AsyncBaseTransport):
    """Stateless synthetic MCP wire fixture; real client code parses every frame."""
    def __init__(self, authority):
        self.authority = authority
        self.requests = []
        self.tools_called = []
        self.closed = 0
        self.revoke_on_list = False

    async def handle_async_request(self, request):
        self.requests.append(request)
        if request.method in {"GET", "DELETE"}:
            return httpx2.Response(200, content=b"")
        rpc = json.loads(request.content)
        method = rpc["method"]
        if method.startswith("notifications/"):
            return httpx2.Response(202, content=b"")
        if method == "initialize":
            result = {"protocolVersion": rpc["params"]["protocolVersion"], "capabilities": {"tools": {}},
                      "serverInfo": {"name": "synthetic-mcp", "version": "1"}}
        elif method == "tools/list":
            result = {"tools": [{"name": KEY.tool_id, "description": "authorized read", "inputSchema": SCHEMA}]}
            if self.revoke_on_list:
                self.authority.revoke()
        elif method == "tools/call":
            self.tools_called.append(rpc["params"])
            content = {"chatId": 5, "messages": []}
            result = {"content": [{"type": "text", "text": json.dumps(content)}],
                      "structuredContent": content, "isError": False}
        else:
            raise AssertionError("unexpected fixture protocol method")
        return httpx2.Response(200, json={"jsonrpc": "2.0", "id": rpc["id"], "result": result})

    async def aclose(self):
        self.closed += 1


async def prepared(monkeypatch, *, external):
    authority, scope, provenance, journal, unused, redactor, snapshot, executor = setup(external=external)
    server = Server(authority)
    service = authority.service_identity
    policy = NetworkPolicy(service.endpoint, external, frozenset({"https://docs.example"}),
                           internal_endpoint=service.endpoint if not external else None)
    headers = ({"Authorization": "Bearer " + EXTERNAL} if external else
               {"Authorization": "Bearer " + INTERNAL, "X-Actor-User-Id": "7"})
    binding = MCPBinding(service, policy, headers, redactor, SecretRedactor((INTERNAL, PROVIDER)))

    class Bindings:
        async def binding(self, service, principal, context):
            return binding

    async def resolver(_host, _port):
        return ("8.8.8.8",)

    # Only the TEST injects an in-process wire. Production SecureSessionFactory
    # calls the same guarded network client with its real socket transport.
    real_client = network.secure_http_client
    def client(*args, **kwargs):
        return real_client(*args, **kwargs, resolver=resolver, inner=server)
    monkeypatch.setattr(bridge, "secure_http_client", client)
    guard = GuardedExecutor(ExecutionGate(authority, scope, provenance), authority.context, snapshot,
                            journal, redactor, SecureSessionFactory(Bindings(), mcp_tools._adapter))
    return authority, journal, server, guard


@pytest.mark.parametrize("external", [False, True])
async def test_real_adapter_dispatch_is_guarded_for_internal_and_configured_external(monkeypatch, external):
    authority, journal, server, guard = await prepared(monkeypatch, external=external)
    async with mcp_tools.ToolExecutor(7, "trace_unit", security=guard) as executor:
        schemas = await executor.schemas()
        assert schemas[0]["function"]["name"] == KEY.tool_id
        result, _ = await executor.execute(KEY.tool_id, {"chat_id": 5}, call_id="call_0001")
    assert result["untrusted"] is True
    assert json.loads(result["content"]) == {"chatId": 5, "messages": []}
    assert len(server.tools_called) == 1
    assert server.closed >= 1
    if external:
        assert all(request.url.host == "8.8.8.8" for request in server.requests)
        assert all("x-actor-user-id" not in request.headers and "traceparent" not in request.headers
                   for request in server.requests)
    else:
        assert all(request.headers["x-actor-user-id"] == "7" for request in server.requests)


async def test_revoke_during_real_mcp_listing_never_sends_tools_call(monkeypatch):
    authority, journal, server, guard = await prepared(monkeypatch, external=True)
    server.revoke_on_list = True
    async with mcp_tools.ToolExecutor(7, "trace_unit", security=guard) as executor:
        with pytest.raises(mcp_tools.ToolError):
            await executor.execute(KEY.tool_id, {"chat_id": 5}, call_id="call_0001")
    assert server.tools_called == []
    assert journal.events[-1]["dispatchCount"] == 0
    assert server.closed >= 1


async def test_legacy_adapter_has_no_unprotected_client_fallback():
    with pytest.raises(mcp_tools.ToolTransportError):
        mcp_tools._adapter("https://docs.example/mcp", {}, 1)

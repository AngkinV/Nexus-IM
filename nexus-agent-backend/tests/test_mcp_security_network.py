"""Wire-level counting transport tests. No real DNS, socket or MCP service.

T1 owns execution. These assertions are authored, not observed passing results.
"""
from __future__ import annotations

import json

import httpx2
import pytest

from app.mcp_security import NetworkPolicy, ProtocolRules, SecretRedactor, SecurityViolation, secure_http_client
from app.mcp_security.network import public_address
from mcp_security_fixtures import EXTERNAL, INTERNAL, PROVIDER

URL = "https://docs.example/mcp"
INIT = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "nexus", "version": "1"}}}


async def public_dns(_host, _port):
    return ("8.8.8.8", "1.1.1.1")


async def allowed(_payload):
    return None


class Wire:
    def __init__(self, *, status=200, headers=None):
        self.requests = []
        self.status = status
        self.headers = headers or {}
        self.transport_closed = 0
        owner = self

        class Transport(httpx2.AsyncBaseTransport):
            async def handle_async_request(self, request):
                owner.requests.append(request)
                return httpx2.Response(owner.status, json={"jsonrpc": "2.0", "id": 1, "result": {}}, headers=owner.headers)

            async def aclose(self):
                owner.transport_closed += 1

        self.transport = Transport()


def client(wire, *, rules=None, resolver=public_dns, headers=None, internal=False):
    endpoint = "http://127.0.0.1:8080/internal/agent/mcp" if internal else URL
    policy = NetworkPolicy(endpoint, not internal, frozenset({"https://docs.example"}),
                           internal_endpoint=endpoint if internal else None)
    return secure_http_client(policy, rules or ProtocolRules(authorize=allowed),
                              service_headers=headers or {},
                              redactor=SecretRedactor((INTERNAL, PROVIDER, EXTERNAL)),
                              forbidden_external_secrets=SecretRedactor((INTERNAL, PROVIDER)),
                              resolver=resolver, inner=wire.transport)


@pytest.mark.parametrize("address", [
    "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "169.254.169.254", "0.0.0.0",
    "100.64.0.1", "224.0.0.1", "240.0.0.1", "192.0.2.1", "198.18.0.1", "::1", "::",
    "fe80::1", "fc00::1", "ff02::1", "::ffff:8.8.8.8", "::8.8.8.8", "64:ff9b::808:808",
    "64:ff9b:1::808:808", "2002:808:808::1", "2001::1", "fe80::1%en0",
])
async def test_private_special_and_ipv6_transition_dns_answers_never_send(address):
    wire = Wire()
    async def unsafe(_host, _port):
        return (address,)
    async with client(wire, resolver=unsafe) as http:
        with pytest.raises(SecurityViolation) as error:
            await http.post(URL, json=INIT)
    assert error.value.code == "DNS_DENIED"
    assert wire.requests == []
    assert wire.transport_closed == 1


async def test_mixed_public_and_private_answer_is_denied_as_a_whole():
    wire = Wire()
    async def mixed(_host, _port):
        return ("8.8.8.8", "169.254.169.254")
    async with client(wire, resolver=mixed) as http:
        with pytest.raises(SecurityViolation):
            await http.post(URL, json=INIT)
    assert wire.requests == []


async def test_literal_ip_is_the_transport_destination_and_hostname_remains_host_and_sni():
    wire = Wire()
    async with client(wire) as http:
        response = await http.post(URL, json=INIT)
        assert response.status_code == 200
    assert len(wire.requests) == 1
    request = wire.requests[0]
    assert request.url.host == "8.8.8.8"  # no second hostname DNS lookup at TCP connect
    assert request.headers["host"] == "docs.example"
    assert request.extensions["sni_hostname"] == "docs.example"
    assert "trace" not in request.extensions


async def test_dns_rebinding_after_first_request_never_dispatches_second_request():
    wire = Wire()
    calls = 0
    async def rebinding(_host, _port):
        nonlocal calls
        calls += 1
        return ("8.8.8.8",) if calls == 1 else ("127.0.0.1",)
    async with client(wire, resolver=rebinding) as http:
        await http.post(URL, json=INIT)
        with pytest.raises(SecurityViolation):
            await http.post(URL, json=INIT)
    assert calls == 2
    assert len(wire.requests) == 1


async def test_final_permission_callback_runs_after_dns_and_before_transport():
    wire = Wire()
    revoked = False
    async def dns(_host, _port):
        nonlocal revoked
        revoked = True
        return ("8.8.8.8",)
    async def authorize(_payload):
        if revoked:
            raise SecurityViolation("FORBIDDEN")
    async with client(wire, rules=ProtocolRules(authorize=authorize), resolver=dns) as http:
        with pytest.raises(SecurityViolation):
            await http.post(URL, json=INIT)
    assert wire.requests == []


@pytest.mark.parametrize("status", [300, 301, 302, 303, 304, 307, 308, 399])
async def test_every_3xx_is_refused_and_follow_target_is_never_requested(status):
    wire = Wire(status=status, headers={"Location": "http://169.254.169.254/latest/meta-data"})
    async with client(wire) as http:
        with pytest.raises(SecurityViolation) as error:
            await http.post(URL, json=INIT)
    assert error.value.code == "REDIRECT_DENIED"
    assert len(wire.requests) == 1  # initial allowed handshake only; zero redirects
    assert all(json.loads(request.content)["method"] != "tools/call" for request in wire.requests)
    assert wire.requests[0].url.host == "8.8.8.8"


@pytest.mark.parametrize("unsafe", [
    "file:///etc/passwd", "stdio", "ftp://docs.example/mcp", "http://docs.example/mcp",
    "https://a:b@docs.example/mcp", "https://docs.example/mcp?api_key=x", "https://docs.example/mcp#x",
    "https://docs.example:0/mcp", "https://docs.example:99999/mcp", "https://docs.example./mcp",
    "https://localhost/mcp", "https://2130706433/mcp", "https://0177.0.0.1/mcp", "https://0x7f000001/mcp",
    "https://docs.example/mcp/../other", "https://docs.example/%2emcp", "https://docs.example\\@127.0.0.1/mcp",
    "https://例子.example/mcp",
])
def test_config_urls_must_be_unambiguous_allowlisted_https(unsafe):
    with pytest.raises(SecurityViolation):
        NetworkPolicy(unsafe, True, frozenset({"https://docs.example"}))


@pytest.mark.parametrize("url", ["https://other.example/mcp", "https://docs.example/other", "https://docs.example/mcp?x=1"])
async def test_sdk_next_or_sse_endpoint_cannot_leave_exact_service_endpoint(url):
    wire = Wire()
    async with client(wire) as http:
        with pytest.raises(SecurityViolation):
            await http.get(url)
    assert wire.requests == []


@pytest.mark.parametrize("name", ["X-Actor-User-Id", "x-TrAcE-id", "X-Internal-Signature", "X-Model-Api-Key",
                                  "traceparent", "baggage", "Cookie", "Proxy-Authorization", "X-Custom-Secret"])
async def test_internal_identity_and_nonallowlisted_request_headers_never_reach_external(name):
    wire = Wire()
    async with client(wire) as http:
        with pytest.raises(SecurityViolation):
            await http.post(URL, json=INIT, headers={name: "not-forwarded"})
    assert wire.requests == []


@pytest.mark.parametrize("credential", [INTERNAL, "Bearer " + INTERNAL, PROVIDER])
def test_external_service_cannot_reuse_any_internal_or_provider_credential(credential):
    wire = Wire()
    with pytest.raises(SecurityViolation) as error:
        client(wire, headers={"Authorization": credential})
    assert error.value.code == "SECRET_EGRESS"
    assert wire.requests == []


async def test_explicit_external_own_credential_has_no_actor_model_or_trace_headers():
    wire = Wire()
    async with client(wire, headers={"Authorization": "Bearer " + EXTERNAL}) as http:
        await http.post(URL, json=INIT)
    [request] = wire.requests
    assert request.headers["authorization"] == "Bearer " + EXTERNAL
    assert all(not key.startswith(("x-actor", "x-internal", "x-model", "x-trace")) for key in request.headers)
    assert "traceparent" not in request.headers and "baggage" not in request.headers


async def test_internal_endpoint_is_separate_and_actor_number_is_not_misregistered_as_secret():
    wire = Wire()
    url = "http://127.0.0.1:8080/internal/agent/mcp"
    payload = {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "read", "arguments": {"chat_id": 7}}}
    async with client(wire, internal=True, headers={"Authorization": "Bearer " + INTERNAL, "X-Actor-User-Id": "7"}) as http:
        await http.post(url, json=payload)
    assert wire.requests[0].headers["x-actor-user-id"] == "7"
    assert wire.requests[0].url.host == "127.0.0.1"
    with pytest.raises(SecurityViolation):
        NetworkPolicy(url, True, frozenset({"http://127.0.0.1:8080"}), allow_http=True)
        # Local external DNS rejection is exercised by the parameterized test.


async def test_probe_cannot_issue_tools_call_even_when_its_callback_allows():
    wire = Wire()
    payload = {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "write", "arguments": {}}}
    async with client(wire, rules=ProtocolRules(probe=True, authorize=allowed)) as http:
        await http.post(URL, json=INIT)
        with pytest.raises(SecurityViolation) as error:
            await http.post(URL, json=payload)
    assert error.value.code == "PROTOCOL_DENIED"
    assert len(wire.requests) == 1
    assert all(json.loads(request.content)["method"] != "tools/call" for request in wire.requests)


@pytest.mark.parametrize("payload", [
    {"jsonrpc": "2.0", "id": 1, "method": "sampling/createMessage", "params": {}},
    {"jsonrpc": "2.0", "id": 1, "method": "elicitation/create", "params": {}},
    {"jsonrpc": "2.0", "id": 1, "method": "roots/list", "params": {}},
    [{"jsonrpc": "2.0", "id": 1, "method": "ping"}],
])
async def test_reverse_capabilities_and_batch_rpc_are_not_implicitly_supported(payload):
    wire = Wire()
    async with client(wire) as http:
        with pytest.raises(SecurityViolation):
            await http.post(URL, json=payload)
    assert wire.requests == []


async def test_duplicate_json_keys_cannot_swap_checked_rpc_arguments():
    wire = Wire()
    body = b'{"jsonrpc":"2.0","id":1,"method":"ping","method":"tools/call"}'
    async with client(wire) as http:
        with pytest.raises(SecurityViolation):
            await http.post(URL, content=body)
    assert wire.requests == []


async def test_server_cookie_is_not_saved_and_incoming_cookie_is_not_forwarded():
    wire = Wire(headers={"Set-Cookie": "other=secret; Path=/"})
    async with client(wire) as http:
        await http.post(URL, json=INIT)
        assert list(http.cookies) == []
        await http.post(URL, json=INIT)
    assert len(wire.requests) == 2
    assert all("cookie" not in request.headers for request in wire.requests)


@pytest.mark.parametrize("status", [401, 403, 500])
async def test_http_failure_is_controlled_without_remote_error_or_headers(status):
    wire = Wire(status=status, headers={"X-Debug-Key": INTERNAL})
    async with client(wire) as http:
        with pytest.raises(SecurityViolation) as error:
            await http.post(URL, json=INIT)
    assert error.value.code == "TRANSPORT_ERROR"
    assert INTERNAL not in str(error.value)

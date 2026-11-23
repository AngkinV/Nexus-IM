"""Real SDK metadata/trace regressions; authored for T1, not run here."""
from __future__ import annotations

import json
from types import SimpleNamespace

import httpx2
import pytest
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import Event, ReadableSpan
from opentelemetry.trace import SpanContext, Status, StatusCode, TraceState

from app.mcp_security import NetworkPolicy, ProtocolRules, SecretRedactor, SecurityViolation, secure_http_client
from app.mcp_security.telemetry import RedactingSpanExporter, require_safe_trace_sinks
from mcp_security_fixtures import EXTERNAL, INTERNAL, PROVIDER


async def test_sdk_progress_and_matching_tool_header_are_allowed_but_trace_never_leaves():
    requests = []
    async def handler(request):
        requests.append(request)
        return httpx2.Response(200, json={"jsonrpc": "2.0", "id": 2, "result": {}})
    async def resolver(host, port):
        return ("8.8.8.8",)
    async def authorize(payload):
        assert set(payload["params"]["_meta"]) == {"progressToken"}
    policy = NetworkPolicy("https://docs.example/mcp", True, frozenset({"https://docs.example"}))
    payload = {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {
        "name": "read", "arguments": {"q": "public"},
        "_meta": {"progressToken": 2, "traceparent": "private-trace", "tracestate": INTERNAL, "baggage": INTERNAL},
    }}
    async with secure_http_client(policy, ProtocolRules(authorize=authorize), service_headers={},
                                  redactor=SecretRedactor((INTERNAL,)),
                                  forbidden_external_secrets=SecretRedactor((INTERNAL,)),
                                  resolver=resolver, inner=httpx2.MockTransport(handler)) as client:
        await client.post(policy.endpoint, json=payload, headers={"Mcp-Name": "read", "Mcp-Method": "tools/call"})
    assert len(requests) == 1
    sent = json.loads(requests[0].content)
    assert sent["params"]["_meta"] == {"progressToken": 2}
    assert INTERNAL not in requests[0].content.decode()
    assert "private-trace" not in requests[0].content.decode()


@pytest.mark.parametrize("case", ["name", "method", "meta", "progress"])
async def test_wrong_routing_header_unknown_meta_and_wrong_progress_id_send_nothing(case):
    requests = []
    async def handler(request):
        requests.append(request)
        return httpx2.Response(200, json={})
    async def resolver(host, port):
        return ("8.8.8.8",)
    async def authorize(payload):
        return None
    policy = NetworkPolicy("https://docs.example/mcp", True, frozenset({"https://docs.example"}))
    payload = {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "read", "arguments": {}}}
    headers = {"Mcp-Name": "read", "Mcp-Method": "tools/call"}
    if case == "name":
        headers["Mcp-Name"] = "write"
    elif case == "method":
        headers["Mcp-Method"] = "tools/list"
    elif case == "meta":
        payload["params"]["_meta"] = {"actorUserId": 8}
    else:
        payload["params"]["_meta"] = {"progressToken": 99}
    async with secure_http_client(policy, ProtocolRules(authorize=authorize), service_headers={},
                                  redactor=SecretRedactor((INTERNAL,)),
                                  forbidden_external_secrets=SecretRedactor((INTERNAL,)),
                                  resolver=resolver, inner=httpx2.MockTransport(handler)) as client:
        with pytest.raises(SecurityViolation):
            await client.post(policy.endpoint, json=payload, headers=headers)
    assert requests == []


@pytest.mark.parametrize("wire", ["Bearer " + EXTERNAL])
def test_registering_full_wire_auth_also_redacts_raw_credential_echo(wire):
    redactor = SecretRedactor()
    redactor.register_credential(wire)
    assert EXTERNAL not in redactor.text("echo " + EXTERNAL)
    with pytest.raises(SecurityViolation):
        redactor.deny_secret(EXTERNAL)


def test_trace_export_drops_headers_results_exception_events_session_and_scope_resource_secrets():
    redactor = SecretRedactor((INTERNAL, PROVIDER, EXTERNAL))
    captured = []
    class Exporter:
        def export(self, spans):
            captured.extend(spans)
            return None
        def shutdown(self):
            return None
        def force_flush(self, timeout_millis=30000):
            return True
    context = SpanContext(1, 2, False, trace_state=TraceState([("vendor", EXTERNAL)]))
    span = ReadableSpan("MCP " + EXTERNAL, context=context,
                        attributes={"runId": "run_0001", "args": INTERNAL, "session_id": EXTERNAL,
                                    "authorization": PROVIDER, "url": "https://docs.example/" + EXTERNAL},
                        resource=Resource({"service.name": "nexus", "process.command_args": INTERNAL}),
                        events=(Event("exception", {"exception.message": INTERNAL}),),
                        status=Status(StatusCode.ERROR, description=INTERNAL), start_time=1, end_time=2)
    exporter = RedactingSpanExporter(Exporter(), redactor)
    exporter.export((span,))
    assert len(captured) == 1
    clean = captured[0]
    exported = clean.to_json()
    assert all(secret not in exported for secret in (INTERNAL, PROVIDER, EXTERNAL))
    assert clean.attributes == {"runId": "run_0001"}
    assert clean.events == ()
    assert len(clean.context.trace_state) == 0
    assert span.attributes["args"] == INTERNAL  # original not mutated; sinks receive only the copy


def test_mcp_refuses_unprotected_trace_sink_and_accepts_wrapped_existing_sink(monkeypatch):
    from app.mcp_security import telemetry
    exporter = SimpleNamespace(export=lambda spans: None)
    processor = SimpleNamespace(span_exporter=exporter)
    provider = SimpleNamespace(_active_span_processor=SimpleNamespace(_span_processors=(processor,)))
    monkeypatch.setattr(telemetry.trace, "get_tracer_provider", lambda: provider)
    with pytest.raises(SecurityViolation):
        require_safe_trace_sinks(SecretRedactor((INTERNAL,)))
    processor.span_exporter = RedactingSpanExporter(exporter, SecretRedactor())
    require_safe_trace_sinks(SecretRedactor((INTERNAL,)))
    assert INTERNAL not in processor.span_exporter.redactor.text(INTERNAL)

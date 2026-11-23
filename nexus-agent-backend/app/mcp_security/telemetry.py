"""Reuse T1's trace pipeline without exporting SDK credentials or raw payloads.

Not a new tracer/provider/store. T1 wraps each existing SpanExporter with this
adapter. MCP refuses to start if a configured SDK sink is not protected.
"""
from __future__ import annotations

from typing import Any

from opentelemetry import trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import ReadableSpan
from opentelemetry.sdk.trace.export import SpanExporter
from opentelemetry.sdk.util.instrumentation import InstrumentationScope
from opentelemetry.trace import Link, SpanContext, Status, StatusCode, TraceState

from .redaction import SecretRedactor
from .types import SecurityViolation

_ATTRIBUTES = frozenset({
    "runId", "attemptId", "agentId", "callId", "serviceId", "toolId", "nodeId",
    "run.id", "attempt.id", "agent.id", "call.id", "service.id", "tool.id", "node.id",
    "mcp.method.name", "rpc.method", "rpc.system", "rpc.response.status_code", "jsonrpc.request.id",
    "status", "code", "classification", "dispatchCount", "latencyMs",
})
_RESOURCE = frozenset({"service.name", "service.version", "deployment.environment", "deployment.environment.name",
                       "telemetry.sdk.name", "telemetry.sdk.language", "telemetry.sdk.version"})


def _attributes(values, allowed, redactor):
    result = {}
    for key, value in (values or {}).items():
        if key not in allowed:
            continue
        if type(value) is str:
            result[key] = redactor.text(value)
        elif type(value) in (int, float, bool):
            result[key] = value
    return result


def _context(context):
    if context is None:
        return None
    return SpanContext(context.trace_id, context.span_id, context.is_remote,
                       trace_flags=context.trace_flags, trace_state=TraceState())


class RedactingSpanExporter(SpanExporter):
    """Decorator for an existing exporter; only sanitized ReadableSpan copies leave.

    No args/results/headers/URLs/session IDs/raw exception events, even when an
    SDK added them before its network callback. Original spans are not mutated.
    """

    def __init__(self, exporter: SpanExporter, redactor: SecretRedactor) -> None:
        if exporter is None or not isinstance(redactor, SecretRedactor):
            raise SecurityViolation("AUTHORITY_UNAVAILABLE")
        self._exporter, self.redactor = exporter, redactor

    def clean(self, span: ReadableSpan) -> ReadableSpan:
        scope = span.instrumentation_scope
        safe_scope = (None if scope is None else InstrumentationScope(
            self.redactor.text(scope.name), self.redactor.text(scope.version) if scope.version else None,
            schema_url=None, attributes={}))
        status = Status(span.status.status_code,
                        "MCP operation failed" if span.status.status_code == StatusCode.ERROR else None)
        return ReadableSpan(
            name=self.redactor.text(span.name), context=_context(span.context), parent=_context(span.parent),
            resource=Resource(_attributes(span.resource.attributes, _RESOURCE, self.redactor)),
            attributes=_attributes(span.attributes, _ATTRIBUTES, self.redactor), events=(),
            links=tuple(Link(_context(link.context), attributes={}) for link in span.links),
            kind=span.kind, status=status, start_time=span.start_time, end_time=span.end_time,
            instrumentation_scope=safe_scope,
        )

    def export(self, spans):
        return self._exporter.export(tuple(self.clean(span) for span in spans))

    def shutdown(self):
        return self._exporter.shutdown()

    def force_flush(self, timeout_millis=30000):
        return self._exporter.force_flush(timeout_millis)


def require_safe_trace_sinks(redactor: SecretRedactor) -> None:
    """Pinned SDK integration check, before the MCP adapter creates any spans.

    A Proxy/NoOp provider has no exported spans and needs no wrapper. Unknown
    processors fail closed. T1 must keep sink configuration stable during runs.
    """
    provider = trace.get_tracer_provider()
    if type(provider).__name__ in {"ProxyTracerProvider", "NoOpTracerProvider"}:
        return
    active = getattr(provider, "_active_span_processor", None)
    processors = getattr(active, "_span_processors", None)
    if not isinstance(processors, (tuple, list)):
        raise SecurityViolation("AUTHORITY_UNAVAILABLE")
    for processor in processors:
        exporter = getattr(processor, "span_exporter", None)
        if exporter is None:
            exporter = getattr(getattr(processor, "_batch_processor", None), "_exporter", None)
        if not isinstance(exporter, RedactingSpanExporter):
            raise SecurityViolation("AUTHORITY_UNAVAILABLE")
        exporter.redactor.absorb(redactor)

"""OpenTelemetry tracing.

Spans are always created, so every run has a trace id that is passed on to the
Java backend (`traceparent`). Nothing leaves the process unless an OTLP
endpoint is configured.
"""
from __future__ import annotations

import logging

from opentelemetry import trace

from .config import get_settings

log = logging.getLogger(__name__)

tracer = trace.get_tracer("nexus.agent")


_installed = False


def init_tracing() -> bool:
    """Install the tracer provider once. Returns True when spans are exported."""
    global _installed
    settings = get_settings()
    endpoint = (settings.otel_exporter_otlp_endpoint or "").strip()
    if _installed:
        return bool(endpoint)

    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider

    provider = TracerProvider(resource=Resource.create({"service.name": settings.service_name}))
    if endpoint:
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
        from opentelemetry.sdk.trace.export import BatchSpanProcessor

        provider.add_span_processor(BatchSpanProcessor(
            OTLPSpanExporter(endpoint=endpoint.rstrip("/") + "/v1/traces")))
        log.info("tracing enabled, exporting to %s", endpoint)
    trace.set_tracer_provider(provider)
    _installed = True
    return bool(endpoint)

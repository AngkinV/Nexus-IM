"""Guarded execution of one authorized read-only MCP tool call (T4B).

Order: trusted identity + user/agent/run ∩ snapshot + scope precheck → strict
arguments → secret quarantine → T1 lookup (re-gated before reuse) → atomic T1
claim → gate → connect → gate → tools/call, whose wire request is authorized
again by ``authorize_request`` right before it is sent → gate → sanitize → commit.

A read that has been sent cannot be rolled back. When permission is revoked
after dispatch the result is dropped and never committed, and later calls are
denied; the remote read itself did happen and is recorded with dispatchCount=1.

No MCP manager, store or in-memory ledger lives here: identity and grants come
from T2/T4A through ExecutionGate, call records from the T1 CallJournal, and the
wire from the SessionFactory built on SafeMCPTransport + ProtocolRules.
"""
from __future__ import annotations

import asyncio
import copy
import hashlib
import json
import time
from contextlib import AbstractAsyncContextManager
from dataclasses import dataclass, field, replace
from typing import Any, Awaitable, Callable, Mapping, Protocol, TypeVar

from .policy import ExecutionGate
from .redaction import SecretRedactor, untrusted_envelope
from .schema import clone_arguments, validate_arguments
from .network import safe_acknowledgement
from .types import (
    AuthorizedCall, CallIdentity, CallJournal, CommittedCall, ExecutionContext,
    RunSnapshot, SecurityViolation, ServiceIdentity, ToolIdentity, ToolKey, plain_json, require_id,
)

T = TypeVar("T")

# Final per-request hook: the JSON-RPC body of a POST, or None for GET/DELETE.
AuthorizeRequest = Callable[[dict[str, Any] | None], Awaitable[None]]

# The only non-call frames one call's session may send. A reply to a
# server-initiated request (sampling, roots, elicitation) has no method: denied.
_SESSION_METHODS = frozenset({
    "initialize", "notifications/initialized", "notifications/cancelled", "ping", "tools/list",
})
_FAILED = frozenset({"TIMEOUT", "TRANSPORT_ERROR", "REMOTE_ERROR", "JOURNAL_UNAVAILABLE"})


@dataclass(frozen=True)
class SessionResult:
    """Built by the trusted session adapter from the SDK result object.

    ``is_error`` is the SDK's own flag (CallToolResult.isError or a JSON-RPC
    error), never a status/permission field found inside ``data``.
    """

    data: Any = field(repr=False)
    is_error: bool = False


class ToolSession(Protocol):
    async def call(self, tool: str, arguments: dict[str, Any], call_id: str) -> SessionResult: ...


class SessionFactory(Protocol):
    """Opens a session for exactly one call, on the real guarded transport.

    The implementation must pass ``authorize_request`` to
    ProtocolRules(probe=False, authorize=...) of a SafeMCPTransport, so it runs
    for every HTTP request after the vetted DNS answer and before the send.
    Leaving the context closes the session. A factory that does not wire the
    callback is detected: its result is discarded as PROTOCOL_DENIED.
    """

    def open(self, service: ServiceIdentity, context: ExecutionContext,
             authorize_request: AuthorizeRequest) -> AbstractAsyncContextManager[ToolSession]: ...


@dataclass
class _Wire:
    classification: str = "unclassified"
    armed: bool = False     # session.call was entered: one tools/call may be sent
    claimed: bool = False   # the single tools/call slot is taken
    journal_claimed: bool = False  # only the CAS winner may invalidate its record
    closed: bool = False    # the call is over: the callback authorizes nothing more
    dispatches: int = 0
    violation: str | None = None


def _digest(arguments: Any) -> str:
    # Canonical JSON text, so 1, 1.0 and true are different arguments.
    return hashlib.sha256(json.dumps(
        arguments, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False,
    ).encode("utf-8")).hexdigest()


async def _journaled(pending: Awaitable[T]) -> T:
    try:
        return await pending
    except Exception:
        pass
    # Raised outside the handler: no reference to the store's own error.
    raise SecurityViolation("JOURNAL_UNAVAILABLE")


class GuardedExecutor:
    """Executes tools for one run attempt of one agent; a missing adapter denies."""

    def __init__(self, gate: ExecutionGate, context: ExecutionContext, snapshot: RunSnapshot,
                 journal: CallJournal, redactor: SecretRedactor, session_factory: SessionFactory,
                 timeout_sec: float = 30, max_result_chars: int = 8000) -> None:
        if (not isinstance(gate, ExecutionGate) or not isinstance(context, ExecutionContext)
                or not isinstance(snapshot, RunSnapshot) or snapshot.context != context
                or not isinstance(redactor, SecretRedactor)
                or journal is None or session_factory is None):
            raise SecurityViolation("AUTHORITY_UNAVAILABLE")
        if (type(timeout_sec) not in (int, float) or not 0 < timeout_sec <= 300
                or type(max_result_chars) is not int or not 1 <= max_result_chars <= 64000):
            raise SecurityViolation("BAD_ARGUMENTS")
        self._gate = gate
        self._context = context
        self._snapshot = snapshot
        self._journal = journal
        self._redactor = redactor
        self._factory = session_factory
        self._timeout = timeout_sec
        self._max_chars = max_result_chars
        self._closed = False
        self._active: set[asyncio.Task] = set()

    @property
    def actor_user_id(self) -> int:
        return self._context.principal.user_id

    async def aclose(self) -> None:
        self._closed = True
        current = asyncio.current_task()
        pending = [task for task in self._active if task is not current and not task.done()]
        for task in pending:
            task.cancel()
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)

    async def schemas(self) -> tuple[ToolIdentity, ...]:
        """Tools this run may be offered: trusted catalog ∩ live grants ∩ snapshot."""
        if self._closed:
            raise SecurityViolation("CANCELLED")
        try:
            async with asyncio.timeout(self._timeout):
                return await self._gate.schemas(self._context, self._snapshot)
        except TimeoutError:
            raise SecurityViolation("TIMEOUT") from None
        except SecurityViolation:
            raise
        except Exception:
            raise SecurityViolation("AUTHORITY_UNAVAILABLE") from None

    async def public_schemas(self) -> list[dict[str, Any]]:
        """Remote descriptions/defaults remain untrusted, secret-scrubbed data."""
        offered = []
        for tool in await self.schemas():
            if self._redactor.contains_secret(tool.public_name):
                continue
            offered.append({"type": "function", "function": {
                "name": tool.public_name, "description": self._redactor.text(tool.description),
                "parameters": self._redactor.scrub(plain_json(tool.input_schema)),
            }})
        return offered

    async def execute_named(self, public_name: str, args: dict[str, Any],
                            call_id: str) -> tuple[dict[str, Any], int]:
        """Resolve a model-facing name through the authorized catalog, never a remote discovery."""
        require_id(call_id)
        started = time.monotonic()
        code = "FORBIDDEN"
        keys: list[ToolKey] = []
        try:
            keys = [tool.key for tool in await self.schemas() if tool.public_name == public_name]
        except SecurityViolation as exc:
            code = exc.code
        if len(keys) != 1:
            raise SecurityViolation(await self._record(code, call_id, None, _Wire(), started))
        return await self.execute(keys[0], args, call_id)

    async def execute(self, key: ToolKey, args: dict[str, Any],
                      call_id: str) -> tuple[dict[str, Any], int]:
        task = asyncio.current_task()
        if self._closed:
            raise SecurityViolation("CANCELLED")
        if task is not None:
            self._active.add(task)
        try:
            return await self._execute_recorded(key, args, call_id)
        finally:
            if task is not None:
                self._active.discard(task)

    async def _execute_recorded(self, key: ToolKey, args: dict[str, Any],
                                call_id: str) -> tuple[dict[str, Any], int]:
        """Returns (untrusted envelope, latency_ms); raises only SecurityViolation or CancelledError."""
        if not isinstance(key, ToolKey):
            raise SecurityViolation("FORBIDDEN")
        require_id(call_id)
        started = time.monotonic()
        wire = _Wire()
        try:
            async with asyncio.timeout(self._timeout):
                # Bound authorization, lookup and claim too, not just MCP I/O.
                return await self._call(key, args, call_id, wire, started)
        except TimeoutError:
            code = "TIMEOUT"
        except SecurityViolation as exc:
            code = exc.code
        except asyncio.CancelledError:
            await self._record("CANCELLED", call_id, key, wire, started)
            raise
        except Exception:
            code = "TRANSPORT_ERROR"
        # Raised outside the handler: the error carries no cause or context.
        raise SecurityViolation(await self._record(code, call_id, key, wire, started))

    async def _call(self, key: ToolKey, args: dict[str, Any], call_id: str,
                    wire: _Wire, started: float) -> tuple[dict[str, Any], int]:
        if type(args) is not dict:
            raise SecurityViolation("BAD_ARGUMENTS")
        args = clone_arguments(args)
        decision = await self._authorize(key, args, None)
        wire.classification = decision.classification
        clean = validate_arguments(decision.tool.input_schema, args)
        self._redactor.deny_secret(clean)
        identity = CallIdentity(self._context, call_id, key, decision.service.config_version,
                                decision.tool.schema_digest, _digest(clean))

        cached = await _journaled(self._journal.lookup(identity))
        if cached is not None:
            if (not isinstance(cached, CommittedCall) or cached.replayable is not True
                    or replace(cached.identity, context=replace(cached.identity.context,
                               attempt_id=identity.context.attempt_id)) != identity
                    or not isinstance(cached.result, Mapping)):
                raise SecurityViolation("CALL_CONFLICT")
            # A grant revoked since the commit must not be bypassed by the record.
            await self._authorize(key, clean, decision)
            result = self._redactor.scrub(cached.result)
            if (result.get("untrusted") is not True
                    or result.get("source") != f"mcp:{key.service_id}/{key.tool_id}"):
                raise SecurityViolation("CALL_CONFLICT")
            await self._authorize(key, clean, decision)
            return result, cached.latency_ms

        claimed = await _journaled(self._journal.begin(
            identity, self._event(call_id, key, wire, "started", "PENDING", started)))
        if claimed is not True:
            raise SecurityViolation("CALL_CONFLICT")
        wire.journal_claimed = True

        async def authorize_request(payload: dict[str, Any] | None) -> None:
            try:
                await self._authorize_wire(payload, key, clean, identity, decision, wire)
            except SecurityViolation as exc:
                # The SDK may wrap or swallow this; execute() reports the first denial.
                wire.violation = wire.violation or exc.code
                raise

        failure = None
        result = None
        try:
            async with asyncio.timeout(self._timeout):
                await self._authorize(key, clean, decision)  # across the claim await
                async with self._factory.open(decision.service, self._context, authorize_request) as session:
                    await self._authorize(key, clean, decision)  # across the handshake
                    wire.armed = True
                    result = await session.call(decision.tool.remote_name, copy.deepcopy(clean), call_id)
                await self._authorize(key, clean, decision)  # revoked meanwhile: drop the result
        except TimeoutError:
            failure = "TIMEOUT"
        except SecurityViolation as exc:
            failure = exc.code
        except Exception:
            failure = "TRANSPORT_ERROR"
        finally:
            wire.closed = True
        failure = wire.violation or failure
        if failure is not None:
            raise SecurityViolation(failure)
        if not isinstance(result, SessionResult) or wire.dispatches != 1:
            # A reply nobody authorized on the wire: assume it was sent, never return it.
            wire.dispatches = 1
            raise SecurityViolation("PROTOCOL_DENIED")
        if result.is_error is not False:
            raise SecurityViolation("REMOTE_ERROR")

        envelope = untrusted_envelope(
            result.data, source=f"mcp:{key.service_id}/{key.tool_id}",
            data_class="mcp_external" if decision.service.external else "mcp_internal",
            redactor=self._redactor, max_chars=self._max_chars)
        latency_ms = int((time.monotonic() - started) * 1000)
        await _journaled(self._journal.finish(
            CommittedCall(identity, envelope, latency_ms),
            self._event(call_id, key, wire, "succeeded", "OK", started)))
        # Recheck across the durable commit await before exporting any data.
        await self._authorize(key, clean, decision)
        return envelope, latency_ms

    async def _authorize(self, key: ToolKey, arguments: Mapping[str, Any],
                         expected: AuthorizedCall | None) -> AuthorizedCall:
        if self._closed:
            raise SecurityViolation("CANCELLED")
        decision = await self._gate.authorize(self._context, self._snapshot, key, arguments)
        if self._closed:
            raise SecurityViolation("CANCELLED")
        if not isinstance(decision, AuthorizedCall) or (expected is not None and (
                decision.service, decision.tool, decision.classification)
                != (expected.service, expected.tool, expected.classification)):
            raise SecurityViolation("FORBIDDEN")
        return decision

    async def _authorize_wire(self, payload: dict[str, Any] | None, key: ToolKey,
                              clean: dict[str, Any], identity: CallIdentity,
                              decision: AuthorizedCall, wire: _Wire) -> None:
        if self._closed or wire.closed or (payload is not None and not isinstance(payload, dict)):
            raise SecurityViolation("PROTOCOL_DENIED")
        method = None if payload is None else payload.get("method")
        if method != "tools/call":
            if payload is not None and method not in _SESSION_METHODS and not safe_acknowledgement(payload):
                raise SecurityViolation("PROTOCOL_DENIED")
            # Handshake, listing, GET and DELETE exist only for this call: same
            # gate, so a foreign owner or a disabled service sends nothing at all.
            await self._authorize(key, clean, decision)
            return
        params = payload.get("params")
        if (not wire.armed or wire.claimed or not isinstance(params, dict)
                or set(params) - {"name", "arguments", "_meta"}
                or params.get("name") != decision.tool.remote_name):
            raise SecurityViolation("PROTOCOL_DENIED")
        meta = params.get("_meta", {})
        if (type(meta) is not dict or set(meta) - {"progressToken"}
                or ("progressToken" in meta and (type(meta["progressToken"]) not in (int, str)
                    or meta["progressToken"] != payload.get("id")))):
            raise SecurityViolation("PROTOCOL_DENIED")
        # Taken before any await: an SDK retry or a second frame is refused.
        wire.claimed = True
        sent = validate_arguments(decision.tool.input_schema, params.get("arguments", {}))
        if _digest(sent) != identity.argument_digest:
            raise SecurityViolation("PROTOCOL_DENIED")
        self._redactor.deny_secret(sent)
        await self._authorize(key, sent, decision)
        if wire.closed:  # timed out or cancelled while the gate was awaited
            raise SecurityViolation("PROTOCOL_DENIED")
        wire.dispatches = 1

    async def _record(self, code: str, call_id: str, key: ToolKey | None,
                      wire: _Wire, started: float) -> str:
        status = ("cancelled" if code == "CANCELLED"
                  else "failed" if wire.dispatches or code in _FAILED else "denied")
        try:
            async with asyncio.timeout(min(self._timeout, 5)):
                await self._journal.reject(self._context, call_id,
                    self._event(call_id, key, wire, status, code, started),
                    invalidate=wire.journal_claimed)
        except Exception:
            return "JOURNAL_UNAVAILABLE"
        return code

    def _event(self, call_id: str, key: ToolKey | None, wire: _Wire,
               status: str, code: str, started: float) -> dict[str, Any]:
        # Allowlist only: no arguments, digest, result, URL or description.
        text = self._redactor.text
        return {
            "runId": text(self._context.run_id),
            "attemptId": text(self._context.attempt_id),
            "agentId": text(self._context.agent_id),
            "callId": text(call_id),
            "serviceId": "unknown" if key is None else text(key.service_id),
            "toolId": "unknown" if key is None else text(key.tool_id),
            "classification": wire.classification,
            "status": status,
            "code": code,
            "dispatchCount": wire.dispatches,
            "latencyMs": int((time.monotonic() - started) * 1000),
        }

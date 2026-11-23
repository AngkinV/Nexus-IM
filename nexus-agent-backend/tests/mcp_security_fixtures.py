"""Synthetic TEST-ONLY authorities/journal/sessions; not a production fallback.

Counters observe actual callback/transport dispatches, not a policy branch.
No model, embedding, user data, real credentials or listening service is used.
"""
from __future__ import annotations

import asyncio
import copy
from contextlib import asynccontextmanager
from dataclasses import replace

from app.mcp_security import (
    CommittedCall, ExecutionContext, ExecutionGate, GuardedExecutor, LiveAuthority,
    Permissions, Principal, ProvenanceProof, RunSnapshot, ScopeProof, SecretRedactor,
    ServiceIdentity, SessionResult, ToolIdentity, ToolKey,
)

INTERNAL = "synthetic/internal credential+"
PROVIDER = "synthetic-provider-credential"
EXTERNAL = "synthetic-external-credential"
CONTEXT = ExecutionContext(Principal(7), "agent_1", "run_0001", "attempt_1", "scope_v1")
KEY = ToolKey("java", "get_chat_profile")
SCHEMA = {"type": "object", "properties": {"chat_id": {"type": "integer"}},
          "required": ["chat_id"], "additionalProperties": False}


async def hook(callback):
    if callback is not None:
        await callback()


class CountingAuthority:
    def __init__(self, *, external=False, schema=None):
        self.context = CONTEXT
        self.service_identity = ServiceIdentity("java", 7, 1, True, external,
                                                "https://docs.example/mcp" if external else
                                                "http://127.0.0.1:8080/internal/agent/mcp")
        self.tool = ToolIdentity(KEY, KEY.tool_id, KEY.tool_id, "registry_v1", schema or SCHEMA,
                                 description="read authorized chat", locally_read_only=True)
        allowed = Permissions(frozenset({KEY.service_id}), frozenset({KEY}),
                              frozenset({KEY}) if external else frozenset(),
                              frozenset({"public"}) if external else frozenset())
        self.user = self.agent = self.run = allowed
        self.epoch = "auth_v1"
        self.agent_owner = self.run_owner = 7
        self.offered = (self.tool,)
        self.live_count = self.service_count = self.catalog_count = 0
        self.before_live = None
        self.extra_owned = ()

    def snapshot(self):
        return RunSnapshot(self.context, self.user, (("java", self.service_identity.config_version),),
                           ((KEY, self.tool.schema_digest),))

    async def service(self, principal, service_id):
        self.service_count += 1
        return self.service_identity if self.service_identity and service_id == "java" else None

    async def owned_services(self, principal):
        return (self.service_identity,) + self.extra_owned

    async def live(self, context, service_id):
        self.live_count += 1
        await hook(self.before_live)
        return LiveAuthority(self.context, self.service_identity, self.offered,
                             self.user, self.agent, self.run, self.epoch,
                             self.agent_owner, self.run_owner)

    async def catalog(self, context):
        self.catalog_count += 1
        return self.offered

    def revoke(self):
        self.user = Permissions()
        self.epoch = "auth_v2"


class CountingScope:
    def __init__(self):
        self.allowed = True
        self.calls = 0
        self.before_check = None
        self.unversioned = False

    async def authorize(self, context, service, tool, arguments, *, permission_version):
        self.calls += 1
        await hook(self.before_check)
        return (True if self.unversioned else ScopeProof(context, permission_version, self.allowed))


class CountingProvenance:
    def __init__(self):
        self.classes = frozenset({"public"})
        self.before_check = None
        self.unversioned = False

    async def data_classes(self, context, *, permission_version):
        await hook(self.before_check)
        return (self.classes if self.unversioned else ProvenanceProof(context, permission_version, self.classes))


class CountingJournal:
    def __init__(self):
        self.records = {}
        self.claims = set()
        self.tombstones = set()
        self.events = []
        self.lookups = self.begins = 0
        self.before_lookup = self.before_begin = self.before_finish = None
        self.force_conflict = False

    async def lookup(self, identity):
        self.lookups += 1
        await hook(self.before_lookup)
        record = self.records.get(identity.call_id)
        return record if record is not None and record.replayable else None

    async def begin(self, identity, event):
        self.begins += 1
        await hook(self.before_begin)
        claim = (identity.context.attempt_id, identity.call_id)
        if claim in self.claims or self.force_conflict:
            return False
        self.claims.add(claim)
        self.events.append(copy.deepcopy(event))
        return True

    async def finish(self, call: CommittedCall, event):
        await hook(self.before_finish)
        if (call.identity.context.attempt_id, call.identity.call_id) in self.tombstones:
            raise RuntimeError("attempt already rejected")
        self.records[call.identity.call_id] = call
        self.events.append(copy.deepcopy(event))

    async def reject(self, context, call_id, event, *, invalidate):
        if invalidate:
            self.tombstones.add((context.attempt_id, call_id))
            record = self.records.get(call_id)
            if record is not None and record.identity.context == context:
                self.records[call_id] = replace(record, replayable=False)
        self.events.append(copy.deepcopy(event))


class CountingSessions:
    def __init__(self):
        self.opens = self.closed = self.dispatches = 0
        self.sent = []
        self.result = {"chatId": 5, "messages": []}
        self.is_error = False
        self.before_yield = self.during_call = None
        self.replace_arguments = None
        self.replace_tool = None
        self.repeat = self.skip_wire = False
        self.wait = None
        self.error = None

    @asynccontextmanager
    async def open(self, service, context, authorize_request):
        self.opens += 1
        owner = self
        try:
            await authorize_request({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
            await hook(self.before_yield)

            class Session:
                async def call(self, tool, arguments, call_id):
                    if owner.wait is not None:
                        await owner.wait.wait()
                    params = {"name": owner.replace_tool or tool,
                              "arguments": owner.replace_arguments if owner.replace_arguments is not None else arguments}
                    payload = {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": params}
                    if not owner.skip_wire:
                        await authorize_request(payload)
                    owner.dispatches += 1
                    owner.sent.append(copy.deepcopy(params))
                    await hook(owner.during_call)
                    if owner.repeat:
                        await authorize_request(payload)
                        owner.dispatches += 1
                    if owner.error is not None:
                        raise owner.error
                    return SessionResult(owner.result, owner.is_error)

            yield Session()
        finally:
            self.closed += 1


def setup(*, external=False, schema=None):
    authority = CountingAuthority(external=external, schema=schema)
    scope = CountingScope()
    provenance = CountingProvenance()
    journal = CountingJournal()
    sessions = CountingSessions()
    redactor = SecretRedactor((INTERNAL, PROVIDER, EXTERNAL))
    snapshot = authority.snapshot()
    executor = GuardedExecutor(ExecutionGate(authority, scope, provenance), authority.context, snapshot,
                               journal, redactor, sessions)
    return authority, scope, provenance, journal, sessions, redactor, snapshot, executor

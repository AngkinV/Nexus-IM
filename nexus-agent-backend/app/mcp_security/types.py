"""T4B trust-boundary types; supplied by T2/T4A/T1, never by tool arguments.

No in-memory production authority or journal is provided here. Java remains
responsible for authenticated identity, ownership and persisted permissions.
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from types import MappingProxyType
from typing import Any, Mapping, Protocol


def frozen_json(value: Any, *, depth: int = 0) -> Any:
    """Copy JSON at the trust boundary; no caller-owned mutable descendants."""
    if depth > 32:
        raise SecurityViolation("BAD_SCHEMA")
    if value is None or type(value) in (str, bool, int, float):
        if ((type(value) is int and value.bit_length() > 256)
                or (type(value) is str and len(value) > 65536)):
            raise SecurityViolation("BAD_SCHEMA")
        if type(value) is float and (value != value or abs(value) == float("inf")):
            raise SecurityViolation("BAD_SCHEMA")
        return value
    if isinstance(value, Mapping):
        if len(value) > 1024 or any(type(key) is not str for key in value):
            raise SecurityViolation("BAD_SCHEMA")
        return MappingProxyType({key: frozen_json(item, depth=depth + 1)
                                 for key, item in value.items()})
    if type(value) in (list, tuple):
        if len(value) > 4096:
            raise SecurityViolation("BAD_SCHEMA")
        return tuple(frozen_json(item, depth=depth + 1) for item in value)
    raise SecurityViolation("BAD_SCHEMA")


def plain_json(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {key: plain_json(item) for key, item in value.items()}
    if type(value) in (tuple, list):
        return [plain_json(item) for item in value]
    return value

_ID = re.compile(r"[A-Za-z0-9_-]{1,96}\Z")
_CODES = {
    "FORBIDDEN": "MCP operation is not authorized",
    "AUTHORITY_UNAVAILABLE": "MCP authorization could not be verified",
    "SCOPE_DENIED": "MCP operation is outside the authorized scope",
    "EGRESS_DENIED": "External data transfer is not authorized",
    "SECRET_EGRESS": "Credential data cannot be transferred",
    "BAD_SCHEMA": "MCP tool schema is not supported or valid",
    "BAD_ARGUMENTS": "MCP tool arguments are not valid",
    "UNSAFE_ENDPOINT": "MCP endpoint is not permitted",
    "DNS_DENIED": "MCP endpoint address could not be verified",
    "REDIRECT_DENIED": "MCP redirects are not permitted",
    "HEADER_DENIED": "MCP request headers are not permitted",
    "PROTOCOL_DENIED": "MCP protocol operation is not permitted",
    "TIMEOUT": "MCP operation timed out",
    "TRANSPORT_ERROR": "MCP transport failed",
    "REMOTE_ERROR": "MCP tool reported an error",
    "JOURNAL_UNAVAILABLE": "MCP call record could not be verified",
    "CALL_CONFLICT": "MCP call identity has already been used",
    "CANCELLED": "MCP operation was cancelled",
}


class SecurityViolation(RuntimeError):
    """Only controlled text may cross an event/log boundary; no remote cause."""

    def __init__(self, code: str) -> None:
        self.code = code if code in _CODES else "FORBIDDEN"
        super().__init__(_CODES[self.code])


def require_id(value: str) -> None:
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise SecurityViolation("FORBIDDEN")


@dataclass(frozen=True)
class Principal:
    user_id: int

    def __post_init__(self) -> None:
        if type(self.user_id) is not int or self.user_id <= 0:
            raise SecurityViolation("FORBIDDEN")


@dataclass(frozen=True)
class ExecutionContext:
    principal: Principal
    agent_id: str
    run_id: str
    attempt_id: str
    # Opaque T2 scope/cache identity, including its authorization version.
    # Not a new scope schema; the T2 adapter owns interpretation and narrowing.
    scope_key: str

    def __post_init__(self) -> None:
        for value in (self.agent_id, self.run_id, self.attempt_id, self.scope_key):
            require_id(value)
        if not isinstance(self.principal, Principal):
            raise SecurityViolation("FORBIDDEN")


@dataclass(frozen=True, order=True)
class ToolKey:
    service_id: str
    tool_id: str

    def __post_init__(self) -> None:
        require_id(self.service_id)
        require_id(self.tool_id)


@dataclass(frozen=True)
class ServiceIdentity:
    service_id: str
    owner_user_id: int
    config_version: int
    enabled: bool
    external: bool
    endpoint: str = field(repr=False)
    transport: str = "streamable_http"

    def __post_init__(self) -> None:
        require_id(self.service_id)
        if (type(self.owner_user_id) is not int or self.owner_user_id <= 0
                or type(self.config_version) is not int or self.config_version < 1
                or type(self.enabled) is not bool or type(self.external) is not bool
                or self.transport != "streamable_http"):
            raise SecurityViolation("FORBIDDEN")


@dataclass(frozen=True)
class ToolIdentity:
    key: ToolKey
    remote_name: str
    public_name: str
    schema_version: str
    input_schema: Mapping[str, Any] = field(repr=False, compare=False)
    schema_digest: str = field(init=False, repr=False)
    description: str = field(default="", repr=False, compare=False)
    # Trusted local risk classification. A remote readOnlyHint is NOT this field.
    locally_read_only: bool = False

    def __post_init__(self) -> None:
        for value in (self.remote_name, self.public_name, self.schema_version):
            require_id(value)
        if (not isinstance(self.key, ToolKey) or len(self.public_name) > 64
                or type(self.locally_read_only) is not bool or not isinstance(self.input_schema, Mapping)):
            raise SecurityViolation("FORBIDDEN")
        schema = frozen_json(self.input_schema)
        encoded = json.dumps(plain_json(schema), sort_keys=True, separators=(",", ":"),
                             ensure_ascii=True, allow_nan=False).encode("utf-8")
        if len(encoded) > 65536:
            raise SecurityViolation("BAD_SCHEMA")
        object.__setattr__(self, "input_schema", schema)
        object.__setattr__(self, "schema_digest", hashlib.sha256(encoded).hexdigest())


@dataclass(frozen=True)
class Permissions:
    services: frozenset[str] = frozenset()
    tools: frozenset[ToolKey] = frozenset()
    egress_tools: frozenset[ToolKey] = frozenset()
    egress_data_classes: frozenset[str] = frozenset()

    def __post_init__(self) -> None:
        # Freeze caller-owned sets as well as the dataclass itself.
        for name in ("services", "tools", "egress_tools", "egress_data_classes"):
            value = getattr(self, name)
            if type(value) not in (set, frozenset, tuple, list):
                raise SecurityViolation("FORBIDDEN")
            object.__setattr__(self, name, frozenset(value))
        for service in self.services:
            require_id(service)
        if any(not isinstance(key, ToolKey) for key in self.tools | self.egress_tools):
            raise SecurityViolation("FORBIDDEN")
        for data_class in self.egress_data_classes:
            require_id(data_class)
        if not self.egress_tools <= self.tools:
            raise SecurityViolation("FORBIDDEN")


@dataclass(frozen=True)
class RunSnapshot:
    context: ExecutionContext
    permissions: Permissions
    # Tuples, not mutable dicts supplied by a checkpoint/model.
    service_versions: tuple[tuple[str, int], ...] = ()
    tool_versions: tuple[tuple[ToolKey, str], ...] = ()

    def __post_init__(self) -> None:
        if not isinstance(self.context, ExecutionContext) or not isinstance(self.permissions, Permissions):
            raise SecurityViolation("FORBIDDEN")
        try:
            services = tuple((key, version) for key, version in self.service_versions)
            tools = tuple((key, version) for key, version in self.tool_versions)
        except (ValueError, TypeError):
            raise SecurityViolation("FORBIDDEN") from None
        for key, version in services:
            require_id(key)
            if type(version) is not int or version < 1:
                raise SecurityViolation("FORBIDDEN")
        for key, version in tools:
            if not isinstance(key, ToolKey):
                raise SecurityViolation("FORBIDDEN")
            require_id(version)
        if len(dict(services)) != len(services) or len(dict(tools)) != len(tools):
            raise SecurityViolation("FORBIDDEN")
        object.__setattr__(self, "service_versions", services)
        object.__setattr__(self, "tool_versions", tools)


@dataclass(frozen=True)
class LiveAuthority:
    context: ExecutionContext
    service: ServiceIdentity | None
    tools: tuple[ToolIdentity, ...] = ()
    user: Permissions = field(default_factory=Permissions)
    agent: Permissions = field(default_factory=Permissions)
    run: Permissions = field(default_factory=Permissions)
    permission_version: str = "unverified"
    agent_owner_user_id: int | None = None
    run_owner_user_id: int | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.context, ExecutionContext):
            raise SecurityViolation("FORBIDDEN")
        object.__setattr__(self, "tools", tuple(self.tools))
        if any(not isinstance(tool, ToolIdentity) for tool in self.tools):
            raise SecurityViolation("FORBIDDEN")
        if len({tool.key for tool in self.tools}) != len(self.tools):
            raise SecurityViolation("FORBIDDEN")
        if any(not isinstance(layer, Permissions) for layer in (self.user, self.agent, self.run)):
            raise SecurityViolation("FORBIDDEN")
        require_id(self.permission_version)
        for owner in (self.agent_owner_user_id, self.run_owner_user_id):
            if owner is not None and (type(owner) is not int or owner <= 0):
                raise SecurityViolation("FORBIDDEN")


class AuthorityProvider(Protocol):
    """T4A registry + fresh signed Java authorization, never a TTL grants cache.

    Implementations authenticate the principal against Java; they must not
    accept owner/grants from an HTTP body or fall back on a discovery hint.
    """

    async def service(self, principal: Principal, service_id: str) -> ServiceIdentity | None: ...
    async def owned_services(self, principal: Principal) -> tuple[ServiceIdentity, ...]: ...
    async def live(self, context: ExecutionContext, service_id: str) -> LiveAuthority: ...
    async def catalog(self, context: ExecutionContext) -> tuple[ToolIdentity, ...]: ...


@dataclass(frozen=True)
class ScopeProof:
    context: ExecutionContext
    permission_version: str
    allowed: bool

    def __post_init__(self) -> None:
        require_id(self.permission_version)
        if not isinstance(self.context, ExecutionContext) or type(self.allowed) is not bool:
            raise SecurityViolation("SCOPE_DENIED")


@dataclass(frozen=True)
class ProvenanceProof:
    context: ExecutionContext
    permission_version: str
    classes: frozenset[str]

    def __post_init__(self) -> None:
        require_id(self.permission_version)
        if not isinstance(self.context, ExecutionContext):
            raise SecurityViolation("EGRESS_DENIED")
        object.__setattr__(self, "classes", frozenset(self.classes))
        for data_class in self.classes:
            require_id(data_class)


class ScopeAuthorizer(Protocol):
    """T2 verifies membership/scope at the supplied authority epoch.

    The trusted authority advances permission_version for grants, membership,
    scope revocation and monotone context taint. A boolean without a version
    cannot bridge asynchronous checks and is deliberately rejected.
    """

    async def authorize(self, context: ExecutionContext, service: ServiceIdentity,
                        tool: ToolIdentity, arguments: Mapping[str, Any],
                        *, permission_version: str) -> ScopeProof: ...


class ProvenanceProvider(Protocol):
    """Trusted, monotone context taint; not a label asserted by the model.

    Reading/sharing chat, file, recall or tool data adds classes to the whole
    context. A model-generated query cannot claim to be public to erase them.
    """

    async def data_classes(self, context: ExecutionContext,
                           *, permission_version: str) -> ProvenanceProof: ...


@dataclass(frozen=True)
class AuthorizedCall:
    service: ServiceIdentity
    tool: ToolIdentity
    permission_version: str
    classification: str


@dataclass(frozen=True)
class CallIdentity:
    context: ExecutionContext
    call_id: str
    key: ToolKey
    service_version: int
    schema_version: str
    argument_digest: str = field(repr=False)

    def __post_init__(self) -> None:
        require_id(self.call_id)
        require_id(self.schema_version)
        if (not isinstance(self.context, ExecutionContext) or not isinstance(self.key, ToolKey)
                or type(self.service_version) is not int or self.service_version < 1
                or not isinstance(self.argument_digest, str)
                or not re.fullmatch(r"[0-9a-f]{64}", self.argument_digest)):
            raise SecurityViolation("CALL_CONFLICT")


@dataclass(frozen=True)
class CommittedCall:
    identity: CallIdentity
    result: Mapping[str, Any] = field(repr=False)
    latency_ms: int = 0
    replayable: bool = True

    def __post_init__(self) -> None:
        if (not isinstance(self.identity, CallIdentity) or not isinstance(self.result, Mapping)
                or type(self.latency_ms) is not int or self.latency_ms < 0
                or type(self.replayable) is not bool):
            raise SecurityViolation("CALL_CONFLICT")
        object.__setattr__(self, "result", frozen_json(self.result))


class CallJournal(Protocol):
    """Adapter to T1 durable per-call records, NOT a second run store.

    begin is an atomic attempt-scoped claim: False means another worker owns it.
    lookup returns ONLY terminal succeeded/replayable read records. It may return
    a prior attempt, never another actor/run/agent/scope/config/schema/arguments.
    finish persists sanitized data, but cannot resurrect a rejected attempt.
    reject(invalidate=True) is sent ONLY by this executor's successful CAS winner:
    atomically tombstone any result of this claimed attempt (including a finish
    which raced cancel or completed just before a final permission rejection).
    A late finish must respect that tombstone. invalidate=False audits a denial
    or losing claim without invalidating another worker/earlier successful call.
    Failed/cancelled/denied claimed attempts are never replayable. T1 must enforce
    these monotone transitions in its real store; this package adds no new store.
    Authorization-denied events contain zero dispatches; post-dispatch failures
    report the actual submitted read and do not imply rollback of a remote read.
    """

    async def lookup(self, identity: CallIdentity) -> CommittedCall | None: ...
    async def begin(self, identity: CallIdentity, event: Mapping[str, Any]) -> bool: ...
    async def finish(self, call: CommittedCall, event: Mapping[str, Any]) -> None: ...
    async def reject(self, context: ExecutionContext, call_id: str, event: Mapping[str, Any],
                     *, invalidate: bool) -> None: ...

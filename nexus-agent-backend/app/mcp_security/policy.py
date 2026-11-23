"""Default-deny ownership and execution gates for the existing MCP layer."""
from __future__ import annotations

from typing import Any, Awaitable, Callable, Mapping, TypeVar

from .types import (
    AuthorityProvider, AuthorizedCall, ExecutionContext, LiveAuthority,
    Permissions, Principal, ProvenanceProvider, RunSnapshot, ScopeAuthorizer,
    SecurityViolation, ServiceIdentity, ToolIdentity, ToolKey, ScopeProof,
    ProvenanceProof, frozen_json, plain_json, require_id,
)
from .schema import clone_arguments
from .redaction import SecretRedactor

T = TypeVar("T")
_MANAGEMENT = frozenset({"get", "update", "delete", "test", "discover", "refresh", "status"})
_PROBES = frozenset({"test", "discover", "refresh"})


def _owner(principal: Principal, service: ServiceIdentity | None) -> ServiceIdentity:
    if (not isinstance(principal, Principal) or not isinstance(service, ServiceIdentity)
            or service.owner_user_id != principal.user_id):
        # Identical error for absent and another owner's service: no existence leak.
        raise SecurityViolation("FORBIDDEN")
    return service


def _allows(permissions: Permissions, key: ToolKey) -> bool:
    return (isinstance(permissions, Permissions)
            and key.service_id in permissions.services and key in permissions.tools)


class ManagementGuard:
    """Owner-check before CRUD callbacks or any MCP handshake/discovery.

    T4A must also scope UPDATE/DELETE SQL by owner (no ownership-changing DTO).
    A probe uses network.ProtocolRules(probe=True), so no tools/call can escape
    even if a probe callback accidentally invokes a tool.
    """

    def __init__(self, authority: AuthorityProvider | None, redactor: SecretRedactor | None = None) -> None:
        self._authority = authority
        self._redactor = redactor

    async def list_owned(self, principal: Principal) -> tuple[ServiceIdentity, ...]:
        if self._authority is None or not isinstance(principal, Principal):
            raise SecurityViolation("FORBIDDEN")
        try:
            items = await self._authority.owned_services(principal)
        except Exception:
            raise SecurityViolation("AUTHORITY_UNAVAILABLE") from None
        if not isinstance(items, (tuple, list)):
            raise SecurityViolation("AUTHORITY_UNAVAILABLE")
        return tuple(item for item in items if isinstance(item, ServiceIdentity)
                     and item.owner_user_id == principal.user_id)

    async def create(self, principal: Principal, payload: Mapping[str, Any],
                     persist: Callable[[int, Mapping[str, Any]], Awaitable[T]]) -> T:
        if self._authority is None or not isinstance(principal, Principal):
            raise SecurityViolation("FORBIDDEN")
        if type(payload) is not dict:
            raise SecurityViolation("FORBIDDEN")
        payload = plain_json(frozen_json(payload))

        def claims_owner(value):
            if isinstance(value, dict):
                for key, item in value.items():
                    normalized = "".join(c for c in key.lower() if c.isalnum())
                    if (normalized in {"owner", "owneruserid", "userid", "actor", "actoruserid",
                                       "internal", "external", "builtin", "isbuiltin", "internalendpoint",
                                       "allowedorigins", "trustenv", "serviceidentity", "transportfactory",
                                       "traceparent", "tracestate", "baggage", "cookie", "host", "proxyauthorization"}
                            or normalized.startswith(("xactor", "xinternal", "xmodel", "xtrace"))
                            or claims_owner(item)):
                        return True
            elif isinstance(value, list):
                return any(claims_owner(item) for item in value)
            return False

        if claims_owner(payload):
            raise SecurityViolation("FORBIDDEN")
        # The owner cannot be supplied in a browser body or a model argument.
        if not isinstance(self._redactor, SecretRedactor):
            raise SecurityViolation("AUTHORITY_UNAVAILABLE")
        try:
            return self._redactor.scrub(await persist(principal.user_id, payload))
        except SecurityViolation:
            raise
        except Exception:
            raise SecurityViolation("TRANSPORT_ERROR") from None

    async def authorize(self, action: str, principal: Principal,
                        service_id: str) -> ServiceIdentity:
        if action not in _MANAGEMENT or self._authority is None:
            raise SecurityViolation("FORBIDDEN")
        require_id(service_id)
        try:
            service = await self._authority.service(principal, service_id)
        except Exception:
            raise SecurityViolation("AUTHORITY_UNAVAILABLE") from None
        service = _owner(principal, service)
        if action in _PROBES and not service.enabled:
            raise SecurityViolation("FORBIDDEN")
        return service

    async def invoke(self, action: str, principal: Principal, service_id: str,
                     operation: Callable[[ServiceIdentity], Awaitable[T]]) -> T:
        if action in _PROBES:
            # Probes must use GuardedManagement with wire-level ProtocolRules.
            # An arbitrary callback could otherwise execute tools/call.
            raise SecurityViolation("PROTOCOL_DENIED")
        service = await self.authorize(action, principal, service_id)
        if not isinstance(self._redactor, SecretRedactor):
            raise SecurityViolation("AUTHORITY_UNAVAILABLE")
        try:
            return self._redactor.scrub(await operation(service))
        except SecurityViolation:
            raise
        except Exception:
            raise SecurityViolation("TRANSPORT_ERROR") from None


class ExecutionGate:
    """Load current Java/T4A permissions on every check; no grant cache.

    Check once before connecting, again immediately before dispatch, and before
    AND after reading a committed result. Revocation is checked across awaits.
    The T2 adapter is mandatory, including for resumed/retried runs.
    """

    def __init__(self, authority: AuthorityProvider | None,
                 scope: ScopeAuthorizer | None,
                 provenance: ProvenanceProvider | None) -> None:
        self.authority = authority
        self._scope = scope
        self._provenance = provenance

    async def _live(self, context: ExecutionContext, service_id: str) -> LiveAuthority:
        if self.authority is None or not isinstance(context, ExecutionContext):
            raise SecurityViolation("FORBIDDEN")
        try:
            live = await self.authority.live(context, service_id)
        except Exception:
            raise SecurityViolation("AUTHORITY_UNAVAILABLE") from None
        if not isinstance(live, LiveAuthority) or live.context != context:
            raise SecurityViolation("FORBIDDEN")
        if (live.agent_owner_user_id != context.principal.user_id
                or live.run_owner_user_id != context.principal.user_id):
            raise SecurityViolation("FORBIDDEN")
        if live.permission_version == "unverified":
            raise SecurityViolation("AUTHORITY_UNAVAILABLE")
        service = _owner(context.principal, live.service)
        if service.service_id != service_id or not service.enabled:
            raise SecurityViolation("FORBIDDEN")
        return live

    def _select(self, context: ExecutionContext, snapshot: RunSnapshot,
                live: LiveAuthority, key: ToolKey) -> AuthorizedCall:
        if (not isinstance(snapshot, RunSnapshot) or snapshot.context != context
                or not isinstance(snapshot.permissions, Permissions)):
            raise SecurityViolation("FORBIDDEN")
        service = _owner(context.principal, live.service)
        matches = [tool for tool in live.tools if isinstance(tool, ToolIdentity) and tool.key == key]
        if len(matches) != 1:
            raise SecurityViolation("FORBIDDEN")
        tool = matches[0]
        if (not tool.locally_read_only or tool.key.service_id != service.service_id
                or dict(snapshot.service_versions).get(service.service_id) != service.config_version
                or dict(snapshot.tool_versions).get(key) != tool.schema_digest):
            raise SecurityViolation("FORBIDDEN")
        layers = (snapshot.permissions, live.user, live.agent, live.run)
        if not all(_allows(layer, key) for layer in layers):
            raise SecurityViolation("FORBIDDEN")
        if service.external and not all(key in layer.egress_tools for layer in layers):
            raise SecurityViolation("EGRESS_DENIED")
        return AuthorizedCall(service, tool, live.permission_version,
                              "external_read" if service.external else "internal_read")

    async def authorize(self, context: ExecutionContext, snapshot: RunSnapshot,
                        key: ToolKey, arguments: Mapping[str, Any]) -> AuthorizedCall:
        if not isinstance(key, ToolKey) or self._scope is None:
            raise SecurityViolation("SCOPE_DENIED")
        # Freeze a JSON copy before the first await; scope and dispatch cannot
        # observe a caller mutating authorization parameters while we wait.
        arguments = clone_arguments(arguments)
        live = await self._live(context, key.service_id)
        decision = self._select(context, snapshot, live, key)
        try:
            proof = await self._scope.authorize(context, decision.service, decision.tool, arguments,
                                                permission_version=live.permission_version)
        except Exception:
            raise SecurityViolation("SCOPE_DENIED") from None
        if (not isinstance(proof, ScopeProof) or proof.context != context or not proof.allowed
                or proof.permission_version != live.permission_version):
            raise SecurityViolation("SCOPE_DENIED")
        classes = frozenset()
        if decision.service.external:
            if self._provenance is None:
                raise SecurityViolation("EGRESS_DENIED")
            try:
                provenance = await self._provenance.data_classes(
                    context, permission_version=live.permission_version)
            except Exception:
                raise SecurityViolation("EGRESS_DENIED") from None
            # Unlabelled/unknown content never becomes public by omission.
            if (not isinstance(provenance, ProvenanceProof) or provenance.context != context
                    or provenance.permission_version != live.permission_version):
                raise SecurityViolation("EGRESS_DENIED")
            classes = provenance.classes
            if not classes or "unknown" in classes:
                raise SecurityViolation("EGRESS_DENIED")
            layers = (snapshot.permissions, live.user, live.agent, live.run)
            if not all(classes <= layer.egress_data_classes for layer in layers):
                raise SecurityViolation("EGRESS_DENIED")
        # Scope/provenance are asynchronous: re-read current authority AFTER them.
        fresh = await self._live(context, key.service_id)
        checked = self._select(context, snapshot, fresh, key)
        if (checked.service != decision.service or checked.tool != decision.tool
                or fresh.permission_version != live.permission_version):
            # The epoch covers scope/membership/taint as well as grants. A
            # change across an await needs fresh authorization, never a retry
            # with an unversioned boolean. The caller may explicitly retry.
            raise SecurityViolation("FORBIDDEN")
        if decision.service.external and not all(classes <= layer.egress_data_classes
                for layer in (snapshot.permissions, fresh.user, fresh.agent, fresh.run)):
            raise SecurityViolation("EGRESS_DENIED")
        return checked

    async def schemas(self, context: ExecutionContext, snapshot: RunSnapshot) -> tuple[ToolIdentity, ...]:
        if self.authority is None or self._scope is None:
            raise SecurityViolation("FORBIDDEN")
        if not isinstance(snapshot, RunSnapshot) or snapshot.context != context:
            raise SecurityViolation("FORBIDDEN")
        try:
            offered = await self.authority.catalog(context)
        except Exception:
            raise SecurityViolation("AUTHORITY_UNAVAILABLE") from None
        allowed: list[ToolIdentity] = []
        for tool in offered:
            if not isinstance(tool, ToolIdentity):
                continue
            try:
                live = await self._live(context, tool.key.service_id)
                selected = self._select(context, snapshot, live, tool.key)
            except SecurityViolation:
                continue
            if selected.tool == tool:
                allowed.append(selected.tool)
        return tuple(allowed)

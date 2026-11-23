"""T4A integration adapter for the EXISTING MCPAdapter, not a new MCP manager.

Bindings come from T4A's real owner-scoped registry/credential store. They must
never be constructed from an inbound header dict or tool result. T2/T1 provide
the execution context/snapshot/journal separately to GuardedExecutor.
"""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from types import MappingProxyType
from typing import Any, Callable, Mapping, Protocol

from .execution import AuthorizeRequest, SessionResult
from .network import NetworkPolicy, ProtocolRules, secure_http_client
from .policy import ManagementGuard
from .redaction import SecretRedactor
from .types import ExecutionContext, Principal, SecurityViolation, ServiceIdentity
from .telemetry import require_safe_trace_sinks


@dataclass(frozen=True)
class MCPBinding:
    service: ServiceIdentity
    network: NetworkPolicy
    # Only this service's credentials; internal identity is never merged into
    # external headers. All three fields intentionally have no printable repr.
    headers: Mapping[str, str] = field(repr=False)
    redactor: SecretRedactor = field(repr=False)
    forbidden_external_secrets: SecretRedactor = field(repr=False)

    def __post_init__(self) -> None:
        if (not isinstance(self.service, ServiceIdentity) or not isinstance(self.network, NetworkPolicy)
                or self.network.external != self.service.external
                or self.network.endpoint != self.service.endpoint
                or not isinstance(self.redactor, SecretRedactor)
                or not isinstance(self.forbidden_external_secrets, SecretRedactor)
                or not self.forbidden_external_secrets.configured or not isinstance(self.headers, Mapping)):
            raise SecurityViolation("AUTHORITY_UNAVAILABLE")
        self.redactor.absorb(self.forbidden_external_secrets)
        for key, value in self.headers.items():
            if key.lower() in {"authorization", "x-api-key", "api-key", "x-auth-token"}:
                self.redactor.register_credential(value)
        self.redactor.deny_secret(self.service.endpoint)
        object.__setattr__(self, "headers", MappingProxyType(dict(self.headers)))


class BindingProvider(Protocol):
    """T4A returns a FRESH binding for a service already authorized by T4B.

    Internal bindings use only the trusted configured Java endpoint. External
    bindings use the operator endpoint allowlist, never self-allowlisting.
    Redactors contain the actual internal/provider/service/wire secrets.
    """

    async def binding(self, service: ServiceIdentity, principal: Principal,
                      context: ExecutionContext | None) -> MCPBinding: ...


class AdapterFactory(Protocol):
    def __call__(self, url: str, headers: dict[str, str], timeout: float,
                 *, httpx_client_factory: Callable[..., Any]) -> Any: ...


def principal_from_verified_actor(signed_actor: Mapping[str, Any], verified: Mapping[str, Any]) -> Principal:
    """Bind owner to BOTH the HMAC-signed body actor and verified actor header.

    Call ONLY after app.security.verify_internal_signature. A raw actor header
    is not itself HMAC-bound in baseline 8f28a5a; GET/management signatures must
    carry a body actor or T4A must cryptographically bind that header. Missing or
    mismatched signed identity is denied, not guessed from ownerId in a DTO.
    """
    if (not isinstance(signed_actor, Mapping) or not isinstance(verified, Mapping)
            or type(signed_actor.get("userId")) is not int
            or type(verified.get("actorUserId")) is not int
            or signed_actor.get("userId") != verified.get("actorUserId")):
        raise SecurityViolation("FORBIDDEN")
    return Principal(signed_actor["userId"])


def _client_factory(binding: MCPBinding, authorize: AuthorizeRequest,
                    timeout: float, *, probe: bool = False) -> Callable[..., Any]:
    def client(*, headers=None, timeout=None, auth=None, **kwargs):
        # SDK defaults are not identity. Only T4A's verified service binding is
        # used. Auth/cookies/hooks/proxies cannot bypass the guarded transport.
        if auth is not None or any(key in kwargs for key in (
                "cookies", "proxy", "proxies", "transport", "mounts", "event_hooks", "params")):
            raise SecurityViolation("HEADER_DENIED")
        return secure_http_client(binding.network, ProtocolRules(probe=probe, authorize=authorize),
                                  service_headers=binding.headers, redactor=binding.redactor,
                                  forbidden_external_secrets=binding.forbidden_external_secrets,
                                  timeout=deadline)
    deadline = timeout
    return client


class _AdapterSession:
    def __init__(self, adapter: Any, redactor: SecretRedactor) -> None:
        self._adapter = adapter
        self._redactor = redactor

    async def call(self, tool: str, arguments: dict[str, Any], call_id: str) -> SessionResult:
        # MCPAdapter's real BaseTools invoke through the client's protected
        # transport; no global catalog or legacy unguarded _tools_of is used.
        self._redactor.deny_secret(tool)
        self._redactor.deny_secret(arguments)
        tools = {entry.name: entry for entry in await self._adapter.list_tools()}
        selected = tools.get(tool)
        if selected is None:
            raise SecurityViolation("FORBIDDEN")
        message = await selected.ainvoke({
            "type": "tool_call", "id": call_id, "name": tool, "args": arguments,
        })
        artifact = message.artifact if isinstance(message.artifact, dict) else {}
        structured = artifact.get("structured_content")
        if structured is None:
            content = message.content
            structured = (content if isinstance(content, str) else
                          "".join(block.get("text", "") for block in content or []
                                  if isinstance(block, dict) and isinstance(block.get("text"), str)))
        return SessionResult(structured, is_error=message.status == "error")


class SecureSessionFactory:
    def __init__(self, bindings: BindingProvider, adapter_factory: AdapterFactory,
                 timeout_sec: float = 30) -> None:
        if bindings is None or adapter_factory is None or not 0 < timeout_sec <= 300:
            raise SecurityViolation("AUTHORITY_UNAVAILABLE")
        self._bindings, self._adapter, self._timeout = bindings, adapter_factory, timeout_sec

    @asynccontextmanager
    async def open(self, service: ServiceIdentity, context: ExecutionContext,
                   authorize_request: AuthorizeRequest):
        binding = await self._bindings.binding(service, context.principal, context)
        if (not isinstance(binding, MCPBinding) or binding.service != service
                or service.owner_user_id != context.principal.user_id):
            raise SecurityViolation("FORBIDDEN")
        if not service.external:
            actors = [value for key, value in binding.headers.items() if key.lower() == "x-actor-user-id"]
            if actors != [str(context.principal.user_id)]:
                raise SecurityViolation("FORBIDDEN")
        require_safe_trace_sinks(binding.redactor)
        adapter = self._adapter(service.endpoint, dict(binding.headers), self._timeout,
                                httpx_client_factory=_client_factory(binding, authorize_request, self._timeout))
        async with adapter:
            yield _AdapterSession(adapter, binding.redactor)


class GuardedManagement:
    """Only actual initialize/list through the guarded adapter may report success.

    T4A CRUD routes use ManagementGuard, validate NetworkPolicy before storing,
    and scope persistence WHERE clauses by the authenticated owner. Probes may
    not run an arbitrary callback which can choose an unguarded MCP client.
    """

    def __init__(self, guard: ManagementGuard, bindings: BindingProvider,
                 adapter_factory: AdapterFactory, timeout_sec: float = 30) -> None:
        if not isinstance(guard, ManagementGuard) or bindings is None or adapter_factory is None:
            raise SecurityViolation("AUTHORITY_UNAVAILABLE")
        self._guard, self._bindings, self._adapter, self._timeout = guard, bindings, adapter_factory, timeout_sec

    async def probe(self, action: str, principal: Principal, service_id: str) -> dict[str, Any]:
        if action not in {"test", "discover", "refresh"}:
            raise SecurityViolation("PROTOCOL_DENIED")
        service = await self._guard.authorize(action, principal, service_id)
        methods = set()

        async def authorize(payload):
            current = await self._guard.authorize(action, principal, service_id)
            if current != service:
                raise SecurityViolation("FORBIDDEN")
            if payload is not None:
                methods.add(payload.get("method"))

        failure = None
        result = None
        try:
            async with asyncio.timeout(self._timeout):
                binding = await self._bindings.binding(service, principal, None)
                if not isinstance(binding, MCPBinding) or binding.service != service:
                    raise SecurityViolation("FORBIDDEN")
                actors = [value for key, value in binding.headers.items() if key.lower() == "x-actor-user-id"]
                if actors and (service.external or actors != [str(principal.user_id)]):
                    raise SecurityViolation("FORBIDDEN")
                require_safe_trace_sinks(binding.redactor)
                adapter = self._adapter(service.endpoint, dict(binding.headers), self._timeout,
                                        httpx_client_factory=_client_factory(binding, authorize, self._timeout, probe=True))
                async with adapter:
                    if action == "test":
                        result = {"status": "connected"}
                    else:
                        from langchain_core.utils.function_calling import convert_to_openai_tool
                        result = {"status": "connected", "tools": [
                            binding.redactor.scrub(convert_to_openai_tool(tool))
                            for tool in await adapter.list_tools()]}
                current = await self._guard.authorize(action, principal, service_id)
                if current != service or "initialize" not in methods:
                    raise SecurityViolation("PROTOCOL_DENIED")
        except TimeoutError:
            failure = "TIMEOUT"
        except SecurityViolation as exc:
            failure = exc.code
        except Exception:
            failure = "TRANSPORT_ERROR"
        if failure is not None:
            raise SecurityViolation(failure)
        return result

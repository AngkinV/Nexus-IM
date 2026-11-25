"""T4A receiving-side adapters for the actual T4B security modules.

Java owns configuration/credentials; T4B owns permission/network/protocol gates;
T1 supplies the durable journal and T2 supplies versioned scope/provenance.
No fake authority, in-memory journal or normal-HTTP fallback lives here.
"""
from __future__ import annotations

import hashlib
import importlib
import json
import uuid
from urllib.parse import urlsplit

from .managed_mcp import JavaRegistryClient, ManagedMcpError, McpContext, Server, schema_check


def policy_tool_id(remote_name: str) -> str:
    """Public toolIds contain ':', while T4B IDs do not. Never truncate this key."""
    if not isinstance(remote_name, str) or not remote_name:
        raise ManagedMcpError("MCP_INVALID_SCHEMA")
    return "t_" + hashlib.sha256(remote_name.encode("utf-8")).hexdigest()


def _module(name):
    return importlib.import_module(__package__ + ".mcp_security." + name)


class JavaMcpAuthority:
    """Real signed Java configuration, with a mandatory real delegate for run grants.

    A service-level allowlist is NEVER copied into user/Agent/run permissions.
    authorizationVersion is NOT substituted for T4B's full permission_version.
    """
    def __init__(self, client=None, *, business_authority=None, redactor=None, forbidden_secrets=None):
        self.types = _module("types")
        self.client = client or JavaRegistryClient()
        self.business = business_authority
        self.redactor, self.forbidden = redactor, forbidden_secrets

    def _learn(self, server):
        if server.data.get("deleted") is True:
            return  # Disabled tombstones contain no credential and can never connect.
        values = server.data.get("forbiddenProviderSecrets")
        if (server.data.get("forbiddenProviderSecretsVerified") is not True or
                not isinstance(values, list) or len(values) > 256 or
                any(not isinstance(value, str) or not value or len(value) > 16384 for value in values) or
                self.redactor is None or self.forbidden is None):
            raise self.types.SecurityViolation("AUTHORITY_UNAVAILABLE")
        for value in values:
            self.redactor.register(value)
            self.forbidden.register(value)
        credential = server.data.get("credential")
        if isinstance(credential, dict):
            value = credential.get("secret")
            if self.forbidden.matches_registered(value):
                raise self.types.SecurityViolation("SECRET_EGRESS")
            self.redactor.register(value)

    def _identity(self, server):
        return self.types.ServiceIdentity(
            server.id, server.data["ownerUserId"], server.version, server.data["enabled"],
            True, server.data["url"], server.data["transport"])

    async def resolve(self, principal, service_id, trace=None):
        if not isinstance(principal, self.types.Principal):
            raise self.types.SecurityViolation("FORBIDDEN")
        server = await self.client.read(principal.user_id, trace or "tr_" + uuid.uuid4().hex, "get", service_id)
        self._learn(server)
        return server

    async def service(self, principal, service_id):
        return self._identity(await self.resolve(principal, service_id))

    async def owned_services(self, principal):
        if not isinstance(principal, self.types.Principal):
            raise self.types.SecurityViolation("FORBIDDEN")
        values = await self.client.read(principal.user_id, "tr_" + uuid.uuid4().hex, "list")
        for server in values:
            self._learn(server)
        return tuple(self._identity(server) for server in values)

    async def live(self, context, service_id):
        if self.business is None:
            raise self.types.SecurityViolation("AUTHORITY_UNAVAILABLE")
        live = await self.business.live(context, service_id)
        current = await self.service(context.principal, service_id)
        if not isinstance(live, self.types.LiveAuthority) or live.context != context or live.service != current:
            raise self.types.SecurityViolation("FORBIDDEN")
        return live

    async def catalog(self, context):
        if self.business is None:
            raise self.types.SecurityViolation("AUTHORITY_UNAVAILABLE")
        return await self.business.catalog(context)


class RegistryBindingProvider:
    """Fresh owner-scoped credentials + operator origins for T4B.MCPBinding.

    The endpoint is NOT self-allowlisted from a service/browser payload. The
    dedicated header is rebuilt from Java's encrypted-at-rest record. Internal
    and supplier secrets come from the trusted quarantine source, not arguments.
    """
    def __init__(self, authority, *, allowed_origins, allow_http=False, any_public_origin=False,
                 redactor=None, forbidden_secrets=None):
        self.authority = authority
        self.types = _module("types")
        self.bridge, self.network = _module("bridge"), _module("network")
        self.allowed_origins = frozenset(allowed_origins)
        self.allow_http = allow_http
        self.any_public_origin = any_public_origin
        self.redactor, self.forbidden = redactor, forbidden_secrets

    async def binding(self, service, principal, context):
        server = await self.authority.resolve(principal, service.service_id)
        if self.authority._identity(server) != service or server.data.get("deleted") is True:
            raise self.types.SecurityViolation("FORBIDDEN")
        self.forbidden.deny_secret(server.data["url"])
        headers = {}
        credential = server.data.get("credential")
        if credential is not None:
            if not isinstance(credential, dict) or not isinstance(credential.get("secret"), str):
                raise self.types.SecurityViolation("HEADER_DENIED")
            if credential.get("kind") == "bearer":
                headers["Authorization"] = "Bearer " + credential["secret"]
            elif credential.get("kind") == "header" and credential.get("headerName", "").lower() in {"x-api-key", "api-key"}:
                headers[credential["headerName"]] = credential["secret"]
            else:
                raise self.types.SecurityViolation("HEADER_DENIED")
        # Managed UUID services are always external; no user-controlled localhost exception.
        network = self.network.NetworkPolicy(service.endpoint, external=True,
                                             allowed_origins=self.allowed_origins, allow_http=self.allow_http,
                                             any_public_origin=self.any_public_origin)
        return self.bridge.MCPBinding(service, network, headers, self.redactor, self.forbidden)


class _CheckedAdapter:
    """Reuse MCPAdapter; compare real MCP schema before its BaseTool dispatch."""
    def __init__(self, adapter, expected):
        self.adapter, self.expected = adapter, expected

    async def __aenter__(self):
        await self.adapter.__aenter__()
        return self

    async def __aexit__(self, *exc):
        return await self.adapter.__aexit__(*exc)

    async def list_tools(self):
        actual = {tool.name: tool for tool in await self.adapter.client.list_tools()}
        descriptor = actual.get(self.expected["remoteName"])
        if descriptor is None:
            raise _module("types").SecurityViolation("FORBIDDEN")
        schema_check(descriptor.input_schema)
        if descriptor.input_schema != self.expected["inputSchema"]:
            raise _module("types").SecurityViolation("BAD_SCHEMA")
        return await self.adapter.list_tools()


class T4BPolicyAdapter:
    """Production calls delegate entirely to T4B.GuardedExecutor.

    T4B owns lookup-before-connect, atomic T1 claim, per-wire authorization,
    scope/provenance epochs, immutable schema, cancellation, untrusted envelope
    and sanitize-before-commit. T4A never runs a second dispatch engine beside it.
    """
    def __init__(self, *, authority=None, scope=None, provenance=None, bindings=None, journal=None, redactor=None):
        self.types, self.policies = _module("types"), _module("policy")
        self.bridge, self.execution = _module("bridge"), _module("execution")
        self.schema, self.network = _module("schema"), _module("network")
        self.management_guard = self.policies.ManagementGuard(authority)
        self.execution_gate = self.policies.ExecutionGate(authority, scope, provenance)
        self.bindings, self.journal, self.redactor = bindings, journal, redactor

    def _context(self, ctx):
        if not isinstance(ctx, McpContext) or not ctx.valid() or not isinstance(ctx.scope, str):
            raise self.types.SecurityViolation("FORBIDDEN")
        context = self.types.ExecutionContext(self.types.Principal(ctx.actorUserId), ctx.agentId, ctx.runId,
                                              ctx.attemptId, ctx.scope)
        snapshot = ctx.permissionSnapshot
        if not isinstance(snapshot, self.types.RunSnapshot) or snapshot.context != context:
            raise self.types.SecurityViolation("FORBIDDEN")
        # A flag restricts; it does not create any of the four egress grants.
        if ctx.allowExternalData is not True:
            raise self.types.SecurityViolation("EGRESS_DENIED")
        return context, snapshot

    @staticmethod
    def _service_matches(actual, value):
        return (actual.service_id == value.get("serviceId") and actual.owner_user_id == value.get("ownerUserId") and
                actual.config_version == value.get("configVersion") and actual.enabled is value.get("enabled") and
                actual.external is value.get("external") and actual.endpoint == value.get("endpoint") and
                actual.transport == value.get("transport"))

    def _key(self, service, tool):
        remote = tool.get("remoteName")
        if tool.get("serviceId") != service.get("serviceId") or tool.get("toolId") != service["serviceId"] + ":" + str(remote):
            raise self.types.SecurityViolation("FORBIDDEN")
        return self.types.ToolKey(service["serviceId"], policy_tool_id(remote))

    def _tool_matches(self, actual, offered):
        return (actual.locally_read_only is True and actual.remote_name == offered.get("remoteName") and
                actual.public_name == offered.get("name") and actual.schema_version == offered.get("schemaVersion") and
                self.types.plain_json(actual.input_schema) == offered.get("inputSchema"))

    async def management(self, actor, service, action):
        principal = self.types.Principal(actor)
        if action == "list":
            return any(self._service_matches(item, service) for item in await self.management_guard.list_owned(principal))
        mapped = {"register": "get", "tools": "get"}.get(action, action)
        return self._service_matches(await self.management_guard.authorize(mapped, principal, service["serviceId"]), service)

    async def http_client_factory(self, actor, ctx, server, action):
        # This hook is ONLY a probe. Real calls use SecureSessionFactory below.
        if ctx is not None or action not in {"test", "discover", "refresh"} or self.bindings is None:
            raise self.types.SecurityViolation("PROTOCOL_DENIED")
        principal = self.types.Principal(actor)
        if actor != server.data["ownerUserId"]:
            raise self.types.SecurityViolation("FORBIDDEN")
        service = await self.management_guard.authorize(action, principal, server.id)
        if not self._service_matches(service, server.identity()):
            raise self.types.SecurityViolation("FORBIDDEN")
        binding = await self.bindings.binding(service, principal, None)

        async def authorize_request(_payload):
            current = await self.management_guard.authorize(action, principal, server.id)
            if current != service:
                raise self.types.SecurityViolation("FORBIDDEN")
            if any(binding.forbidden_external_secrets.matches_registered(value) for value in binding.headers.values()):
                raise self.types.SecurityViolation("SECRET_EGRESS")

        # Actual T4B factory overrides SDK follow_redirects=True and prohibits
        # tools/call, sampling, prompts/resources and arbitrary auth during probes.
        return self.bridge._client_factory(binding, authorize_request, server.data["timeoutSec"], probe=True)

    async def catalog_visible(self, ctx, service, tool):
        context, snapshot = self._context(ctx)
        key = self._key(service, tool)
        return any(offered.key == key and self._tool_matches(offered, tool)
                   for offered in await self.execution_gate.schemas(context, snapshot))

    async def _authorized(self, ctx, service, tool, args):
        context, snapshot = self._context(ctx)
        self.schema.validate_arguments(tool["inputSchema"], args)
        decision = await self.execution_gate.authorize(context, snapshot, self._key(service, tool), args)
        if not self._service_matches(decision.service, service) or not self._tool_matches(decision.tool, tool):
            raise self.types.SecurityViolation("FORBIDDEN")
        return context, snapshot, decision

    async def authorize_call(self, ctx, snapshot, current, tool, args, call_id):
        self.types.require_id(call_id)
        if snapshot != current:
            raise self.types.SecurityViolation("FORBIDDEN")
        await self._authorized(ctx, current, tool, args)
        return True

    async def execute_managed(self, ctx, server, tool, args, call_id):
        try:
            if self.bindings is None or self.journal is None or self.redactor is None:
                raise self.types.SecurityViolation("AUTHORITY_UNAVAILABLE")
            context, snapshot, _decision = await self._authorized(ctx, server.identity(), tool, args)
            from .mcp_tools import _adapter

            def adapter_factory(url, headers, timeout, *, httpx_client_factory):
                adapter = _adapter(url, headers, timeout, httpx_client_factory=httpx_client_factory)
                return _CheckedAdapter(adapter, tool)

            sessions = self.bridge.SecureSessionFactory(self.bindings, adapter_factory, server.data["timeoutSec"])
            executor = self.execution.GuardedExecutor(self.execution_gate, context, snapshot, self.journal,
                    self.redactor, sessions, timeout_sec=server.data["timeoutSec"], max_result_chars=server.data["resultMaxChars"])
            return await executor.execute(self._key(server.identity(), tool), args, call_id)
        except self.types.SecurityViolation as error:
            raise ManagedMcpError("MCP_" + error.code) from None

    async def dispatch(self, *_args):
        # No alternate production dispatcher may bypass the per-wire T4B executor.
        raise self.types.SecurityViolation("PROTOCOL_DENIED")

    async def sanitize(self, server, value):
        if self.redactor is None:
            raise self.types.SecurityViolation("SECRET_EGRESS")
        credential = server.data.get("credential")
        if isinstance(credential, dict):
            self.redactor.register(credential.get("secret"))
        return self.redactor.scrub(value)


def _origins(settings):
    try:
        values = json.loads(settings.mcp_allowed_origins)
        if not isinstance(values, list) or len(values) > 256:
            raise ValueError()
        origins = set()
        for value in values:
            parts = urlsplit(value)
            if (not isinstance(value, str) or parts.scheme not in {"http", "https"} or not parts.hostname or
                    parts.username or parts.query or parts.fragment or parts.path not in {"", "/"}):
                raise ValueError()
            host = "[" + parts.hostname + "]" if ":" in parts.hostname else parts.hostname
            port = parts.port or (443 if parts.scheme == "https" else 80)
            origins.add(parts.scheme + "://" + host + (":" + str(port) if port != (443 if parts.scheme == "https" else 80) else ""))
        return frozenset(origins)
    except Exception:
        raise ManagedMcpError("MCP_INVALID_ALLOWLIST") from None


def management_policy():
    """After T4B merge, compose REAL owner + safe probe adapters, not a fake policy.

    Empty operator origin list denies all external connections. Without T1's
    journal / T2's versioned scope and fresh business authority, calls still deny.
    """
    try:
        redaction, network = _module("redaction"), _module("network")
        _module("bridge"); _module("execution")
    except ImportError:
        return None
    from .config import get_settings
    settings = get_settings()
    values = [settings.java_internal_token, settings.internal_signing_secret,
              settings.openai_api_key, settings.embedding_api_key]
    redactor, forbidden = redaction.SecretRedactor(values), redaction.SecretRedactor(values)
    network.install_log_redaction(redactor)  # Before even the signed Java registry read.
    authority = JavaMcpAuthority(redactor=redactor, forbidden_secrets=forbidden)
    bindings = RegistryBindingProvider(authority, allowed_origins=_origins(settings),
                  allow_http=settings.mcp_allow_http, any_public_origin=settings.mcp_allow_any_public_origin,
                  redactor=redactor, forbidden_secrets=forbidden)
    return T4BPolicyAdapter(authority=authority, bindings=bindings, redactor=redactor)

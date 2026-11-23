"""T4A↔T4B adapter regressions, to run only after T1 merges T4B modules.

No real authorization/Java/MCP chain is certified by the synthetic fixtures.
Missing T4B is an explicit collection skip, not a passing integration test.
"""
from __future__ import annotations

import pytest

from app.managed_mcp import ManagedMcpError, McpContext, Server
from app.mcp_policy_adapter import JavaMcpAuthority, T4BPolicyAdapter, policy_tool_id

T = pytest.importorskip("app.mcp_security.types", reason="T1 must integrate T4B before adapter tests")
R = pytest.importorskip("app.mcp_security.redaction")

SID = "714e3483-cf38-42e4-88cd-6cf56af62f71"
KEY = T.ToolKey(SID, policy_tool_id("read_docs"))
CONTEXT = T.ExecutionContext(T.Principal(41), "agent_fixture", "run_fixture", "attempt_fixture", "scope_fixture")
SERVICE = T.ServiceIdentity(SID, 41, 1, True, True, "https://mcp.example.invalid/mcp")
SCHEMA = {"type": "object", "properties": {"query": {"type": "string"}}}
TOOL = {"serviceId": SID, "toolId": SID + ":read_docs", "remoteName": "read_docs", "name": "read_docs_public", "schemaVersion": "schema_one", "inputSchema": SCHEMA}
PERMISSIONS = T.Permissions(frozenset({SID}), frozenset({KEY}), frozenset({KEY}), frozenset({"public"}))
# T4B pins a tool by the digest of its schema, not by the registry's version label.
DIGEST = T.ToolIdentity(KEY, "read_docs", TOOL["name"], "schema_one", SCHEMA, locally_read_only=True).schema_digest
SNAPSHOT = T.RunSnapshot(CONTEXT, PERMISSIONS, ((SID, 1),), ((KEY, DIGEST),))
CTX = McpContext(41, CONTEXT.agent_id, CONTEXT.run_id, CONTEXT.attempt_id,
                 scope=CONTEXT.scope_key, permissionSnapshot=SNAPSHOT, allowExternalData=True, traceId="tr_fixture")


class Authority:
    def __init__(self, readonly=True):
        self.tool = T.ToolIdentity(KEY, "read_docs", TOOL["name"], "schema_one", SCHEMA, locally_read_only=readonly)
        self.permitted = True

    async def service(self, principal, service_id):
        return SERVICE if principal.user_id == 41 and service_id == SID else None

    async def owned_services(self, principal):
        return (SERVICE,) if principal.user_id == 41 else ()

    async def catalog(self, _context):
        return (self.tool,)

    async def live(self, context, _service):
        grants = PERMISSIONS if self.permitted else T.Permissions()
        # T4B also requires the live record to name who owns the agent and the run.
        return T.LiveAuthority(context, SERVICE, (self.tool,), grants, grants, grants, "epoch_fixture",
                               agent_owner_user_id=41, run_owner_user_id=41)


class Scope:
    async def authorize(self, context, _service, _tool, _args, *, permission_version):
        return T.ScopeProof(context, permission_version, True)


class Provenance:
    async def data_classes(self, context, *, permission_version):
        return T.ProvenanceProof(context, permission_version, frozenset({"public"}))


def adapter(readonly=True):
    authority = Authority(readonly)
    policy = T4BPolicyAdapter(authority=authority, scope=Scope(), provenance=Provenance(),
                             redactor=R.SecretRedactor(("fixture-only-internal-token",)))
    return policy, authority


def identity():
    return {"serviceId": SID, "ownerUserId": 41, "configVersion": 1, "enabled": True,
            "transport": "streamable_http", "internal": False, "external": True, "endpoint": SERVICE.endpoint}


def test_permission_id_is_stable_full_digest_and_never_public_colon_id():
    assert policy_tool_id("read_docs") == KEY.tool_id
    assert ":" not in KEY.tool_id and len(KEY.tool_id) == 66
    assert policy_tool_id("read_other_docs") != KEY.tool_id


async def test_read_only_hint_never_grants_local_read_only_classification():
    policy, _authority = adapter(readonly=False)
    assert await policy.catalog_visible(CTX, identity(), {**TOOL, "readOnlyHint": True}) is False


async def test_real_typed_snapshot_and_live_intersection_are_required():
    policy, authority = adapter()
    assert await policy.catalog_visible(CTX, identity(), TOOL) is True
    authority.permitted = False
    assert await policy.catalog_visible(CTX, identity(), TOOL) is False
    malformed = McpContext(41, "agent_fixture", "run_fixture", "attempt_fixture", scope="scope_fixture",
                           permissionSnapshot={"allowAll": True}, allowExternalData=True, traceId="tr_fixture")
    with pytest.raises(T.SecurityViolation):
        await policy.catalog_visible(malformed, identity(), TOOL)


async def test_missing_durable_journal_or_binding_cannot_open_a_normal_http_fallback():
    policy, _authority = adapter()
    private = Server.parse({"serverId": SID, "ownerUserId": 41, "configVersion": 1, "authorizationVersion": 1,
            "displayName": "Synthetic service", "url": SERVICE.endpoint, "transport": "streamable_http", "enabled": True,
            "timeoutSec": 10, "resultMaxChars": 8000, "allowedTools": [SID + ":read_docs"]}, 41)
    with pytest.raises(ManagedMcpError) as denied:
        await policy.execute_managed(CTX, private, TOOL, {}, "call_fixture")
    assert denied.value.code == "MCP_AUTHORITY_UNAVAILABLE"


async def test_java_configuration_does_not_become_fake_three_layer_permissions():
    class Client:
        async def read(self, actor, _trace, operation, _service_id=None):
            server = Server.parse({"serverId": SID, "ownerUserId": 41, "configVersion": 1, "authorizationVersion": 1,
                "displayName": "Synthetic service", "url": SERVICE.endpoint, "transport": "streamable_http", "enabled": True,
                "timeoutSec": 10, "resultMaxChars": 8000, "allowedTools": [SID + ":read_docs"],
                "forbiddenProviderSecretsVerified": True, "forbiddenProviderSecrets": []}, actor)
            return [server] if operation == "list" else server
    redactor = R.SecretRedactor(("fixture-only-internal-token",))
    authority = JavaMcpAuthority(Client(), redactor=redactor, forbidden_secrets=redactor)
    assert (await authority.service(T.Principal(41), SID)).service_id == SID
    with pytest.raises(T.SecurityViolation):
        await authority.live(CONTEXT, SID)

"""T4A contract regressions; synthetic adapters are test-only, not real E2E evidence.

This file is intentionally not executed by the T4A window. T1 owns the serial
verification queue. Network/permissions/journal fixtures below do not implement
or certify T4B, T2 or T1 production adapters.
"""
from __future__ import annotations

import asyncio
import contextlib
import copy
import json
from types import SimpleNamespace

import pytest

from app import managed_mcp as mcp

SID = "714e3483-cf38-42e4-88cd-6cf56af62f71"
ACTOR = 41
TRACE = "tr_t4a_contract"
FIXTURE_SECRET = "fixture-only-dedicated-key"


def server_record(**changes):
    record = {
        "serverId": SID, "ownerUserId": ACTOR, "configVersion": 1,
        "authorizationVersion": 1, "namespace": "srv_" + SID.replace("-", ""),
        "displayName": "Synthetic documentation service", "transport": "streamable_http",
        "url": "https://mcp.example.invalid/mcp", "enabled": True,
        "timeoutSec": 10, "resultMaxChars": 8000, "allowedTools": [],
        "credentialConfigured": False, "credentialKind": "none", "credentialHeaderName": None,
        "catalogVersion": None, "tools": [],
    }
    record.update(changes)
    return record


def parsed(**changes):
    record = server_record(**changes)
    return mcp.Server.parse(record, record["ownerUserId"])


def descriptor(schema=None):
    # MCP 2 exposes Python snake_case fields, not the JSON wire aliases.
    return SimpleNamespace(name="read_docs", description="Synthetic read-only documentation",
                           input_schema=schema or {"type": "object", "properties": {"query": {"type": "string"}}},
                           annotations=SimpleNamespace(read_only_hint=True))


def discovered_record(**changes):
    record = server_record(**changes)
    version, tools = mcp.tool_views(mcp.Server.parse(record, record["ownerUserId"]), [descriptor()])
    record.update(catalogVersion=version, tools=tools, allowedTools=[tools[0]["toolId"]])
    return record


class MemoryAuthority:
    """Fixture-only signed Java authority replacement; not a production fallback."""
    def __init__(self, record):
        self.record = copy.deepcopy(record)
        self.reads = []

    async def read(self, actor, trace, operation, server_id=None):
        self.reads.append((actor, trace, operation, server_id))
        service = mcp.Server.parse(copy.deepcopy(self.record), actor)
        if operation == "list":
            return [service]
        if server_id != service.id:
            raise mcp.ManagedMcpError("MCP_REGISTRY_UNAVAILABLE")
        return service


class FixturePolicy:
    """Always-allow is confined to tests. Production has no installed fallback."""
    def __init__(self):
        self.denied_actions = set()
        self.factories = []
        self.committed = None
        self.authorizations = 0

    async def management(self, actor, service, action):
        return actor == service["ownerUserId"] and action not in self.denied_actions

    async def http_client_factory(self, actor, ctx, server, action):
        self.factories.append((actor, ctx, server.id, action))
        return lambda **_kwargs: None  # Only fake SDK clients use this test factory.

    async def catalog_visible(self, *_args):
        return True

    async def authorize_call(self, *_args):
        self.authorizations += 1
        return True

    async def dispatch(self, _ctx, _service, _tool, _args, _call_id, invoke):
        result = await invoke()
        self.committed = copy.deepcopy(result)
        return result

    async def sanitize(self, _server, value):
        if isinstance(value, str):
            return value.replace(FIXTURE_SECRET, "[REDACTED]")
        if isinstance(value, dict):
            return {key: await self.sanitize(_server, item) for key, item in value.items()}
        if isinstance(value, list):
            return [await self.sanitize(_server, item) for item in value]
        return value


class FixtureProvider:
    def __init__(self):
        self.offered = [descriptor()]
        self.opens = self.closes = self.invocations = 0
        self.block = None
        self.started = asyncio.Event()

    async def discover(self, server, _policy, _actor):
        return mcp.tool_views(server, self.offered)

    @contextlib.asynccontextmanager
    async def session(self, _server, _policy, _ctx, _action, *, actor):
        self.opens += 1
        provider = self

        class RawClient:
            async def list_tools(self):
                return provider.offered

        class BoundTool:
            name = "read_docs"

            async def ainvoke(self, request):
                provider.invocations += 1
                assert request["id"] == "call_t4a_fixture"
                provider.started.set()
                if provider.block is not None:
                    await provider.block.wait()
                return SimpleNamespace(status="success", content=[], artifact={
                    "structured_content": {"answer": FIXTURE_SECRET, "data": "x" * 1000}})

        class Adapter:
            async def list_tools(self):
                return [BoundTool()]

        try:
            assert actor == ACTOR
            yield RawClient(), Adapter()
        finally:
            self.closes += 1


def run_context():
    return mcp.McpContext(ACTOR, "agent_t4a_fixture", "run_t4a_fixture", "attempt_t4a_fixture",
                          scope="scope_fixture", permissionSnapshot={"fixtureOnly": True},
                          allowExternalData=True, traceId=TRACE)


def executor_fixture(**changes):
    authority = MemoryAuthority(discovered_record(**changes))
    provider, policy = FixtureProvider(), FixturePolicy()
    registry = mcp.ToolRegistry(authority, provider, policy)
    return mcp.ManagedMcpExecutor(registry, run_context()), authority, provider, policy


def test_sdk_fields_and_namespace_are_stable_not_read_only_authority():
    original = parsed()
    renamed = parsed(displayName="Renamed synthetic label", configVersion=2)
    version, tools = mcp.tool_views(original, [descriptor()])
    renamed_version, renamed_tools = mcp.tool_views(renamed, [descriptor()])
    assert version == renamed_version
    assert tools[0]["name"] == renamed_tools[0]["name"]
    assert len(tools[0]["name"]) == 64
    assert tools[0]["toolId"] == SID + ":read_docs"
    assert tools[0]["readOnlyHint"] is True
    assert tools[0]["risk"] == "unclassified" and tools[0]["allowed"] is False


@pytest.mark.parametrize("changes", [
    {"transport": "stdio"}, {"url": "https://user@example.invalid/mcp"},
    {"url": "https://example.invalid/mcp?token=fixture"}, {"url": "file:///etc/hosts"},
    {"timeoutSec": True}, {"resultMaxChars": False}, {"configVersion": True},
    {"authorizationVersion": 0}, {"namespace": "different"},
])
def test_invalid_configuration_is_controlled(changes):
    with pytest.raises(mcp.ManagedMcpError):
        parsed(**changes)


def test_cross_owner_parse_and_repr_never_reveal_secret():
    record = server_record(credential={"kind": "bearer", "secret": FIXTURE_SECRET}, credentialConfigured=True)
    with pytest.raises(mcp.ManagedMcpError) as denied:
        mcp.Server.parse(record, ACTOR + 1)
    assert denied.value.code == "MCP_OWNER_CONFLICT"
    service = mcp.Server.parse(record, ACTOR)
    assert FIXTURE_SECRET not in repr(service)
    assert FIXTURE_SECRET not in json.dumps(service.public())
    assert "credential" not in service.public()


@pytest.mark.parametrize("schema", [
    {"type": "array"}, {"type": "object", "properties": []},
    {"type": "object", "properties": {"query": {"type": "made-up-type"}}},
    {"type": "object", "properties": {"q": {"$ref": "https://example.invalid/schema"}}},
    {"type": "object", "required": [1]},
])
def test_invalid_or_external_schema_cannot_be_discovered(schema):
    with pytest.raises(mcp.ManagedMcpError):
        mcp.tool_views(parsed(), [descriptor(schema)])


def test_same_config_accepts_probe_metadata_but_rejects_secret_change():
    registry = mcp.ToolRegistry()
    original = parsed(credential={"kind": "bearer", "secret": FIXTURE_SECRET})
    registry.register(original)
    observed = copy.deepcopy(original.data)
    observed.update(status={"state": "connected", "configVersion": 1,
                            "checkedAt": "2026-11-23T00:00:00Z", "latencyMs": 4},
                    registrationState="registered", updatedAt="2026-11-23T00:00:00Z")
    registry.register(mcp.Server.parse(observed, ACTOR))
    assert registry.status(registry._servers[SID])["state"] == "connected"
    observed["credential"] = {"kind": "bearer", "secret": "another-fixture-key"}
    with pytest.raises(mcp.ManagedMcpError):
        registry.register(mcp.Server.parse(observed, ACTOR))


def test_invalid_catalog_does_not_mutate_registry_and_delete_blocks_replay():
    registry = mcp.ToolRegistry()
    bad = discovered_record(catalogVersion="tampered")
    bad["catalogVersion"] = "tampered"
    with pytest.raises(mcp.ManagedMcpError):
        registry.register(mcp.Server.parse(bad, ACTOR))
    assert registry._servers == {} and registry._catalogs == {}
    service = parsed()
    registry.register(service)
    registry.delete(ACTOR, SID, 1)
    with pytest.raises(mcp.ManagedMcpError):
        registry.register(service)
    assert registry._servers == {} and registry._catalogs == {}


def test_unknown_or_wrong_version_has_no_success_evidence():
    registry = mcp.ToolRegistry()
    service = parsed(status={"state": "connected", "configVersion": 0, "checkedAt": "2026-11-23T00:00:00Z"})
    registry.register(service)
    state = registry.status(service)
    assert state["state"] == "unknown" and state["checkedAt"] is None
    upgraded = parsed(configVersion=2)
    registry.register(upgraded)
    assert registry.status(upgraded)["state"] == "unknown"


async def test_owner_gate_precedes_cache_mutation():
    authority, policy = MemoryAuthority(server_record()), FixturePolicy()
    policy.denied_actions.add("get")
    registry = mcp.ToolRegistry(authority, policy=policy)
    with pytest.raises(mcp.ManagedMcpError):
        await registry.current(ACTOR, TRACE, SID)
    assert registry._servers == {} and registry._catalogs == {}


async def test_missing_policy_or_context_never_connects_or_reads_authority():
    authority, provider = MemoryAuthority(discovered_record()), FixtureProvider()
    registry = mcp.ToolRegistry(authority, provider, None)
    executor = mcp.ManagedMcpExecutor(registry, run_context())
    assert await executor.schemas() == []
    with pytest.raises(mcp.ManagedMcpError):
        await executor.execute("srv_not_authorized", {}, "call_t4a_fixture")
    assert authority.reads == [] and provider.opens == provider.invocations == 0


async def test_old_probe_cannot_overwrite_new_configuration():
    authority, policy = MemoryAuthority(server_record()), FixturePolicy()

    class ChangingProvider(FixtureProvider):
        async def discover(self, server, _policy, _actor):
            authority.record["configVersion"] = 2
            return mcp.tool_views(server, self.offered)

    registry = mcp.ToolRegistry(authority, ChangingProvider(), policy)
    with pytest.raises(mcp.ManagedMcpError) as stale:
        await registry.probe(ACTOR, TRACE, SID, 1)
    assert stale.value.code == "MCP_VERSION_CONFLICT"
    assert (SID, 1) not in registry._status
    assert registry.status(registry._servers[SID])["state"] == "unknown"


async def test_new_discovery_never_adds_permissions():
    authority, policy = MemoryAuthority(server_record()), FixturePolicy()
    registry = mcp.ToolRegistry(authority, FixtureProvider(), policy)
    response = await registry.probe(ACTOR, TRACE, SID, 1)
    assert response["ok"] and response["toolsCount"] == 1
    assert response["tools"][0]["allowed"] is False
    assert authority.record["allowedTools"] == []


async def test_execute_requires_real_call_id_and_live_permission_every_time():
    executor, authority, provider, _policy = executor_fixture()
    async with executor:
        [schema] = await executor.schemas()
        name = schema["function"]["name"]
        with pytest.raises(mcp.ManagedMcpError):
            await executor.execute(name, {})
        assert provider.invocations == provider.opens == 0
        authority.record.update(enabled=False, configVersion=2, authorizationVersion=2)
        with pytest.raises(mcp.ManagedMcpError):
            await executor.execute(name, {}, "call_t4a_fixture")
        assert provider.invocations == provider.opens == 0


async def test_schema_change_refuses_zero_dispatch_and_closes_session():
    executor, _authority, provider, _policy = executor_fixture()
    async with executor:
        [schema] = await executor.schemas()
        provider.offered = [descriptor({"type": "object", "properties": {"query": {"type": "integer"}}})]
        with pytest.raises(mcp.ManagedMcpError) as changed:
            await executor.execute(schema["function"]["name"], {}, "call_t4a_fixture")
        assert changed.value.code == "MCP_SCHEMA_CHANGED"
    assert provider.invocations == 0 and provider.opens == provider.closes == 1


async def test_result_is_sanitized_and_bounded_before_journal_commit():
    executor, _authority, provider, policy = executor_fixture(resultMaxChars=256)
    async with executor:
        [schema] = await executor.schemas()
        result, latency = await executor.execute(schema["function"]["name"], {}, "call_t4a_fixture")
    assert provider.invocations == 1 and provider.opens == provider.closes == 1
    assert result == policy.committed
    assert FIXTURE_SECRET not in json.dumps(policy.committed)
    assert result["untrusted"] is True and result["source"] == "external:" + SID
    assert result["truncated"] is True and len(result["content"]) <= 256 and latency >= 0


async def test_executor_close_cancels_active_session():
    executor, _authority, provider, _policy = executor_fixture()
    provider.block = asyncio.Event()
    [schema] = await executor.schemas()
    running = asyncio.create_task(executor.execute(schema["function"]["name"], {}, "call_t4a_fixture"))
    await provider.started.wait()
    await executor.__aexit__(None, None, None)
    with pytest.raises(asyncio.CancelledError):
        await running
    assert provider.opens == provider.closes == 1
    assert await executor.schemas() == []


async def test_sdk_adapter_clone_uses_one_handshake_and_never_calls_tools(monkeypatch):
    counts = {"initialize": 0, "tools/list": 0, "tools/call": 0, "close": 0}

    class Client:
        def __init__(self, *_args, **_kwargs):
            self.open = False

        async def list_tools(self):
            assert self.open, "the adapter's active clone must be used"
            counts["tools/list"] += 1
            return [descriptor()]

    class Adapter:
        def __init__(self, _original):
            self.client = Client()  # Mirrors the real SDK clone boundary.

        async def __aenter__(self):
            self.client.open = True
            counts["initialize"] += 1
            return self

        async def __aexit__(self, *_exc):
            self.client.open = False
            counts["close"] += 1

    monkeypatch.setattr(mcp, "Client", Client)
    monkeypatch.setattr(mcp, "MCPAdapter", Adapter)
    monkeypatch.setattr(mcp, "StreamableHttpTransport", lambda *_args, **_kwargs: object())
    policy = FixturePolicy()
    version, tools = await mcp.MCPProvider().discover(parsed(), policy, ACTOR)
    assert version and tools[0]["remoteName"] == "read_docs"
    assert counts == {"initialize": 1, "tools/list": 1, "tools/call": 0, "close": 1}
    assert policy.factories[0][0] == ACTOR


async def test_provider_owner_denial_opens_no_session():
    policy = FixturePolicy()
    with pytest.raises(mcp.ManagedMcpError):
        async with mcp.MCPProvider().session(parsed(), policy, None, "discover", actor=ACTOR + 1):
            pytest.fail("owner rejection must happen before connect")
    assert policy.factories == []

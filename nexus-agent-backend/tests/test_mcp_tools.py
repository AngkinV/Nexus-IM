"""The MCP tool layer against a wire-level fake server: discovery, identity, failures, external servers."""
from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

import httpx2
import pytest

sys.path.insert(0, str(Path(__file__).parent / "evals"))

from app import mcp_tools  # noqa: E402
from app.config import get_settings  # noqa: E402
from app.mcp_tools import ToolError, ToolExecutor, ToolTransportError  # noqa: E402
from eval_harness import JAVA_TOKEN, FakeJava  # noqa: E402

JAVA_HOST = "java.test"
EXTERNAL_HOST = "ext.test"


def scope_for(user_id: int = 7, session_id: str = "s_mcp", run_id: str = "run_mcp_0001"):
    """A synthetic, internally consistent grant for the tool layer.

    The executor now refuses to run without one; these tests exercise transport and
    error mapping, so the scope only has to be real and self-consistent.
    """
    from app.scope import AuthorizedScope, scope_hash
    data = dict(schemaVersion=1, grantId=f"g_{user_id}", actorUserId=user_id,
                sessionId=session_id, runId=run_id, bindingVersion=1, authorizationVersion=1,
                sessionGeneration=1, chatIds=[5], contactUserIds=[],
                timeRange=dict(fromInclusive=None, toExclusive=None, timeZone="Asia/Shanghai"))
    data["scopeKey"] = scope_hash(data)
    return AuthorizedScope.model_validate(data)


@pytest.fixture(autouse=True)
def _accept_scope(monkeypatch):
    async def _validate(self, scope, tool=None):
        from app.scope import ScopeDenied
        if scope is None:
            raise ScopeDenied()
        return scope
    monkeypatch.setattr(mcp_tools.ScopeClient, "validate", _validate)


def serve(monkeypatch, servers: dict[str, FakeJava]) -> None:
    """Route the tool layer's HTTP connections to fake servers by host name."""
    async def handler(request: httpx2.Request) -> httpx2.Response:
        return await servers[request.url.host].handler(request)

    def client(headers=None, timeout=None, auth=None, **_ignored) -> httpx2.AsyncClient:
        return httpx2.AsyncClient(transport=httpx2.MockTransport(handler), headers=headers, timeout=timeout, auth=auth)

    monkeypatch.setattr(mcp_tools, "_http_client", client)


@pytest.fixture
def java(monkeypatch) -> FakeJava:
    settings = get_settings()
    monkeypatch.setattr(settings, "java_internal_base_url", f"http://{JAVA_HOST}/internal/agent")
    monkeypatch.setattr(settings, "java_internal_token", JAVA_TOKEN)
    monkeypatch.setattr(settings, "mcp_external_servers", "")
    server = FakeJava({
        "get_chat_profile": {"json": {"chatId": 5, "chatName": "项目群"}},
        "whoami": {"json": {"ok": True}},
    })
    serve(monkeypatch, {JAVA_HOST: server})
    return server


def external(monkeypatch, java: FakeJava, server: FakeJava) -> None:
    monkeypatch.setattr(get_settings(), "mcp_external_servers", json.dumps({"docs": f"https://{EXTERNAL_HOST}/mcp"}))
    serve(monkeypatch, {JAVA_HOST: java, EXTERNAL_HOST: server})


# ----- discovery -----


async def test_tools_come_from_the_server_and_are_cached(java, monkeypatch):
    async with ToolExecutor(7, "tr_1", scope=scope_for(7)) as executor:
        first = await executor.schemas()
        # T2: a tool needing a capability the run lacks is withheld, not listed empty.
        expected = set(java.catalog) - {"get_user_profile"}
        assert {schema["function"]["name"] for schema in first} == expected
        assert all(schema["type"] == "function" for schema in first)
        assert await executor.schemas() == first
    assert java.rpc.count("tools/list") == 1

    monkeypatch.setattr(get_settings(), "tool_catalog_ttl_sec", 0)  # every read is now past its deadline
    mcp_tools.reset_catalog()
    await mcp_tools.catalog()
    await mcp_tools.catalog()
    assert java.rpc.count("tools/list") == 3


async def test_concurrent_first_runs_discover_once(java):
    catalogs = await asyncio.gather(*[mcp_tools.catalog() for _ in range(8)])
    assert java.rpc.count("tools/list") == 1
    assert all(catalog is catalogs[0] for catalog in catalogs)


async def test_discovery_carries_the_token_but_no_user(java):
    await mcp_tools.catalog()
    assert java.rpc_headers
    for headers in java.rpc_headers:
        assert headers["authorization"] == f"Bearer {JAVA_TOKEN}"
        assert "x-actor-user-id" not in headers


async def test_a_run_that_calls_no_tool_sends_nothing(java):
    await mcp_tools.catalog()
    java.rpc.clear()
    async with ToolExecutor(7, "tr_1", scope=scope_for(7)) as executor:
        await executor.schemas()
    assert java.rpc == []


# ----- calls -----


async def test_call_carries_identity_in_headers_and_returns_the_structured_result(java):
    async with ToolExecutor(7, "tr_call", scope=scope_for(7)) as executor:
        payload, latency_ms = await executor.execute("get_chat_profile", {"chat_id": 5})
    assert payload == {"chatId": 5, "chatName": "项目群"}
    assert latency_ms >= 0
    [call] = java.requests
    assert call["arguments"] == {"chat_id": 5}
    assert call["headers"]["authorization"] == f"Bearer {JAVA_TOKEN}"
    assert call["headers"]["x-actor-user-id"] == "7"
    assert call["headers"]["x-trace-id"] == "tr_call"


async def test_one_connection_serves_every_call_of_a_run(java):
    async with ToolExecutor(7, "tr_1", scope=scope_for(7)) as executor:
        await asyncio.gather(*[executor.execute("whoami", {}) for _ in range(5)])
    assert len(java.requests) == 5
    # One handshake for discovery, one for the run.
    assert java.rpc.count("initialize") == 2


async def test_concurrent_users_keep_their_own_identity(java):
    java.delay_sec = 0.02

    async def call(user_id: int) -> None:
        async with ToolExecutor(user_id, f"tr_{user_id}", scope=scope_for(user_id)) as executor:
            await executor.execute("whoami", {})

    await asyncio.gather(*[call(user_id) for user_id in (7, 8, 9, 7, 8, 9)])
    seen = sorted((r["headers"]["x-actor-user-id"], r["headers"]["x-trace-id"]) for r in java.requests)
    assert seen == sorted((str(u), f"tr_{u}") for u in (7, 8, 9, 7, 8, 9))


async def test_unknown_tool_is_refused_without_asking_the_server(java):
    async with ToolExecutor(7, "tr_1", scope=scope_for(7)) as executor:
        with pytest.raises(ToolError) as refused:
            await executor.execute("delete_all_messages", {"chat_id": 5})
    assert refused.value.code == "UNKNOWN_TOOL"
    assert java.requests == []
    assert java.rpc.count("initialize") == 1  # discovery only; no connection was opened for the run


@pytest.mark.parametrize("scripted, code, message", [
    ({"error": {"toolError": "FORBIDDEN", "message": "No permission for this chat"}}, "FORBIDDEN", "No permission for this chat"),
    ({"error": {"toolError": "NOT_FOUND", "message": "chat not found"}}, "NOT_FOUND", "chat not found"),
    ({"errorText": "Tool input validation failed: chat_id must be integer"}, "TOOL_ERROR", "Tool input validation failed"),
])
async def test_a_refusing_tool_is_reported_to_the_model_with_its_reason(java, scripted, code, message):
    java._responses["get_chat_profile"] = scripted
    async with ToolExecutor(7, "tr_1", scope=scope_for(7)) as executor:
        with pytest.raises(ToolError) as refused:
            await executor.execute("get_chat_profile", {"chat_id": 5})
    assert refused.value.code == code
    assert message in str(refused.value)


async def test_slow_tool_times_out_and_the_run_can_go_on(java, monkeypatch):
    monkeypatch.setattr(get_settings(), "tool_timeout_sec", 0.05)
    async with ToolExecutor(7, "tr_1", scope=scope_for(7)) as executor:
        java.delay_sec = 1
        with pytest.raises(ToolError) as slow:
            await executor.execute("whoami", {})
        assert slow.value.code == "TIMEOUT"
        java.delay_sec = 0
        payload, _ = await executor.execute("whoami", {})
        assert payload == {"ok": True}


async def test_unreachable_tool_server_is_not_dressed_up_as_a_tool_failure(java):
    java.fail_with = 503
    async with ToolExecutor(7, "tr_1", scope=scope_for(7)) as executor:
        with pytest.raises(ToolTransportError):
            await executor.schemas()

    java.fail_with = None
    await mcp_tools.catalog()
    java.fail_calls_with = 500
    async with ToolExecutor(7, "tr_1", scope=scope_for(7)) as executor:
        with pytest.raises(ToolTransportError):
            await executor.execute("whoami", {})


async def test_runs_waiting_on_a_failed_discovery_share_its_failure_and_the_next_run_tries_again(java):
    java.fail_with = 503
    with pytest.raises(ToolTransportError):
        await mcp_tools.catalog()
    one_attempt = java.http_requests
    assert one_attempt >= 1

    java.http_requests = 0
    outcomes = await asyncio.gather(*[mcp_tools.catalog() for _ in range(6)], return_exceptions=True)
    assert all(isinstance(outcome, ToolTransportError) for outcome in outcomes)
    assert java.http_requests == one_attempt  # six waiting runs, one attempt

    java.fail_with = None
    assert set(await mcp_tools.catalog()) == set(java.catalog)  # no stale failure is remembered


async def test_a_tool_server_that_never_answers_fails_within_the_deadline(java, monkeypatch):
    monkeypatch.setattr(get_settings(), "tool_timeout_sec", 0.1)
    java.hang = True
    started = asyncio.get_running_loop().time()
    with pytest.raises(ToolTransportError):
        await mcp_tools.catalog()
    assert asyncio.get_running_loop().time() - started < 3


async def test_rejected_token_is_a_transport_failure(java):
    java.fail_with = 401
    with pytest.raises(ToolTransportError) as rejected:
        await mcp_tools.catalog()
    assert JAVA_TOKEN not in str(rejected.value)


async def test_cancelling_a_call_releases_the_connection(java):
    java.delay_sec = 5
    async with ToolExecutor(7, "tr_1", scope=scope_for(7)) as executor:
        call = asyncio.create_task(executor.execute("whoami", {}))
        while not java.in_flight:
            await asyncio.sleep(0.01)
        call.cancel()
        with pytest.raises(asyncio.CancelledError):
            await call
    assert java.in_flight == 0


# ----- external servers -----


@pytest.mark.parametrize("configured", [
    '{"docs": "file:///etc/passwd"}',
    '{"docs": "/usr/local/bin/server.py"}',
    '{"docs": "stdio"}',
    '{"docs": "ftp://ext.test/mcp"}',
    '{"docs": "https://user:secret@ext.test/mcp"}',
    '{"Docs": "https://ext.test/mcp"}',
    '{"my__docs": "https://ext.test/mcp"}',
    '{"docs": 5}',
    '["https://ext.test/mcp"]',
    'https://ext.test/mcp',
])
def test_external_allow_list_accepts_only_named_http_urls(monkeypatch, configured):
    monkeypatch.setattr(get_settings(), "mcp_external_servers", configured)
    with pytest.raises(ValueError):
        mcp_tools.external_servers()


def test_external_allow_list_is_empty_by_default_and_reads_http_urls(monkeypatch):
    assert get_settings().mcp_external_servers == ""
    assert mcp_tools.external_servers() == {}
    monkeypatch.setattr(get_settings(), "mcp_external_servers", '{"docs": "https://ext.test/mcp", "kb2": "http://10.0.0.5:9000/mcp"}')
    assert set(mcp_tools.external_servers()) == {"docs", "kb2"}


async def test_legacy_external_config_has_no_owner_or_run_grant_and_sends_nothing(java, monkeypatch):
    docs = FakeJava({"search": {"json": {"hits": ["untrusted fixture"]}}})
    docs.catalog = {"search": {"type": "object", "properties": {"q": {"type": "string"}}}}
    external(monkeypatch, java, docs)
    async with ToolExecutor(7, "tr_ext", scope=scope_for(7)) as executor:
        names = {schema["function"]["name"] for schema in await executor.schemas()}
        # A server registered the old way is neither offered nor callable; the internal
        # tools this run is entitled to still are (the profile tool needs contacts).
        assert "docs__search" not in names and names <= set(java.catalog)
        assert set(java.catalog) - {"get_user_profile"} <= names
        with pytest.raises(ToolError) as denied:
            await executor.execute("docs__search", {"q": "fixture"})
        assert denied.value.code == "UNKNOWN_TOOL"
    assert docs.rpc == [] and docs.requests == [] and docs.rpc_headers == []
    assert java.requests == []


async def test_old_external_catalog_entry_cannot_bypass_default_deny(java, monkeypatch):
    docs = FakeJava({"search": {"json": {"text": "fixture"}}})
    external(monkeypatch, java, docs)
    entries = dict(await mcp_tools.catalog())
    entries["docs__search"] = mcp_tools._Entry("docs", "search", {"type": "function", "function": {"name": "docs__search", "parameters": {"type": "object"}}})
    monkeypatch.setattr(mcp_tools._state, "entries", entries)
    async with ToolExecutor(7, "tr_ext", scope=scope_for(7)) as executor:
        names = {schema["function"]["name"] for schema in await executor.schemas()}
        assert "docs__search" not in names
        with pytest.raises(ToolError) as denied:
            await executor.execute("docs__search", {})
        assert denied.value.code == "MCP_DENIED"
    assert docs.rpc == [] and docs.requests == []


async def test_a_broken_external_server_does_not_take_the_internal_tools_down(java, monkeypatch):
    docs = FakeJava({})
    docs.fail_with = 500
    external(monkeypatch, java, docs)
    async with ToolExecutor(7, "tr_ext", scope=scope_for(7)) as executor:
        names = {schema["function"]["name"] for schema in await executor.schemas()}
        assert names == set(java.catalog) - {"get_user_profile"}
        payload, _ = await executor.execute("whoami", {})
    assert payload == {"ok": True}

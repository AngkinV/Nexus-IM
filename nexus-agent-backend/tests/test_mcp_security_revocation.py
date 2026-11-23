"""Regressions for static-review findings. Authored only; T1 must execute them."""
from __future__ import annotations

import asyncio
from dataclasses import replace

import httpx2
import pytest

from app.mcp_security import (
    ExecutionGate, GuardedExecutor, ManagementGuard, NetworkPolicy, Principal,
    ProtocolRules, SecretRedactor, SecurityViolation, secure_http_client,
)
from mcp_security_fixtures import INTERNAL, KEY, CountingAuthority, setup


@pytest.mark.parametrize("association", ["agent_owner", "run_owner"])
@pytest.mark.parametrize("owner", [None, 8])
async def test_missing_or_foreign_agent_run_association_has_zero_actual_tool_calls(association, owner):
    authority, scope, provenance, journal, sessions, redactor, snapshot, executor = setup()
    setattr(authority, association, owner)
    with pytest.raises(SecurityViolation) as error:
        await executor.execute(KEY, {"chat_id": 5}, "call_0001")
    assert error.value.code == "FORBIDDEN"
    assert sessions.opens == sessions.dispatches == 0
    assert journal.events[-1]["dispatchCount"] == 0


async def test_forbidden_registry_covers_body_and_logs_even_if_output_redactor_started_empty():
    requests = []
    async def handler(request):
        requests.append(request)
        return httpx2.Response(200, json={"result": {}})
    async def resolver(host, port):
        return ("8.8.8.8",)
    async def authorize(payload):
        return None
    output = SecretRedactor()
    forbidden = SecretRedactor((INTERNAL,))
    policy = NetworkPolicy("https://docs.example/mcp", True, frozenset({"https://docs.example"}))
    async with secure_http_client(policy, ProtocolRules(authorize=authorize), service_headers={},
                                  redactor=output, forbidden_external_secrets=forbidden,
                                  resolver=resolver, inner=httpx2.MockTransport(handler)) as http:
        with pytest.raises(SecurityViolation) as error:
            await http.post(policy.endpoint, json={"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                                   "params": {"name": "read", "arguments": {"q": INTERNAL}}})
    assert error.value.code == "SECRET_EGRESS"
    assert requests == []
    assert INTERNAL not in output.text("echo " + INTERNAL)


async def test_revoke_after_finish_invalidates_only_this_claimed_attempt_and_never_replays_failure():
    authority, scope, provenance, journal, sessions, redactor, snapshot, executor = setup()
    original = authority.user
    async def revoke():
        authority.revoke()
    journal.before_finish = revoke
    with pytest.raises(SecurityViolation):
        await executor.execute(KEY, {"chat_id": 5}, "call_0001")
    assert sessions.dispatches == 1
    assert journal.records["call_0001"].replayable is False
    assert ("attempt_1", "call_0001") in journal.tombstones
    authority.user = original
    authority.epoch = "auth_v3"
    with pytest.raises(SecurityViolation):
        await executor.execute(KEY, {"chat_id": 5}, "call_0001")
    assert sessions.dispatches == 1  # not replayed as success or re-dispatched in the same attempt

    journal.before_finish = None
    authority.context = replace(authority.context, attempt_id="attempt_2")
    retry = GuardedExecutor(ExecutionGate(authority, scope, provenance), authority.context,
                            authority.snapshot(), journal, redactor, sessions)
    result, _ = await retry.execute(KEY, {"chat_id": 5}, "call_0001")
    assert result["untrusted"] is True
    assert sessions.dispatches == 2  # a genuinely authorized new attempt, not fake cached success


async def test_losing_claim_or_denied_replay_does_not_invalidate_earlier_success():
    authority, scope, provenance, journal, sessions, redactor, snapshot, executor = setup()
    await executor.execute(KEY, {"chat_id": 5}, "call_0001")
    authority.revoke()
    with pytest.raises(SecurityViolation):
        await executor.execute(KEY, {"chat_id": 5}, "call_0001")
    assert journal.records["call_0001"].replayable is True
    assert ("attempt_1", "call_0001") not in journal.tombstones
    assert sessions.dispatches == 1
    assert journal.events[-1]["dispatchCount"] == 0


async def test_close_cancels_every_active_session_and_rejects_late_work():
    authority, scope, provenance, journal, sessions, redactor, snapshot, executor = setup()
    sessions.wait = asyncio.Event()
    entered = asyncio.Event()
    count = 0
    async def started():
        nonlocal count
        count += 1
        if count == 2:
            entered.set()
    sessions.before_yield = started
    tasks = [asyncio.create_task(executor.execute(KEY, {"chat_id": 5}, f"call_000{index}")) for index in (1, 2)]
    await entered.wait()
    await executor.aclose()
    await asyncio.gather(*tasks, return_exceptions=True)
    assert all(task.cancelled() for task in tasks)
    assert sessions.opens == sessions.closed == 2
    assert sessions.dispatches == 0
    with pytest.raises(SecurityViolation):
        await executor.execute(KEY, {"chat_id": 5}, "call_late")
    assert sessions.opens == 2


async def test_nested_owner_claim_is_refused_before_persistence():
    guard = ManagementGuard(CountingAuthority(), SecretRedactor((INTERNAL,)))
    calls = []
    async def persist(owner, payload):
        calls.append(owner)
        return {}
    with pytest.raises(SecurityViolation):
        await guard.create(Principal(7), {"name": "docs", "metadata": {"owner_user_id": 8}}, persist)
    assert calls == []


@pytest.mark.parametrize("payload", [
    {"jsonrpc": "2.0", "id": 1, "result": {"sampling": "private data"}},
    {"jsonrpc": "2.0", "id": 1, "error": {"code": -32601, "message": ["private data"]}},
    {"jsonrpc": "2.0", "id": 1, "method": ["tools/call"]},
])
def test_nonempty_reverse_data_or_invalid_rpc_method_never_becomes_allowed_ack(payload):
    with pytest.raises(SecurityViolation):
        ProtocolRules(probe=True).parse("POST", __import__("json").dumps(payload).encode())
    empty = b'{"jsonrpc":"2.0","id":1,"result":{}}'
    assert ProtocolRules(probe=True).parse("POST", empty)["result"] == {}

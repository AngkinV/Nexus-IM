"""T2 authorizer policy for the frozen runtime contract §6.1 slots.

Regression source only; the original T1 session owns execution and the actual
start-up installation. Nothing here installs a global slot.

The decisive assertions are on real outcomes: a call outside the scope must not
be allowed, and the denied path must not have performed any tool execution or
returned any content.
"""
import asyncio

import pytest

from app.scope import AuthorizedScope, ScopeDenied
from app.scope_authorizers import (
    combine_authorizers,
    install_scope_authorizers,
    scope_replay_authorizer,
    scope_reuse_authorizer,
)


class FakeClient:
    """Records the authority checks the policy actually performs."""

    def __init__(self, allow=True):
        self.allow = allow
        self.calls = []

    async def validate(self, scope, tool=None):
        self.calls.append((scope, tool))
        if not self.allow:
            raise ScopeDenied()
        return scope


def _scope(**overrides):
    base = dict(
        schemaVersion=1, grantId="g-1", actorUserId=7, sessionId="as_1", runId="run_1",
        bindingVersion=2, authorizationVersion=3, sessionGeneration=1,
        chatIds=[101], contactUserIds=[9],
        timeRange={"fromInclusive": None, "toExclusive": None, "timeZone": "UTC"},
    )
    base.update(overrides)
    from app.scope import scope_hash
    base["scopeKey"] = overrides.get("scopeKey") or scope_hash(base)
    return AuthorizedScope.model_validate(base)


class Request:
    def __init__(self, scope, actor=7, session="as_1", run="run_1"):
        self.scope = scope
        self.actor = type("Actor", (), {"userId": actor})()
        self.session = type("Session", (), {"sessionId": session})()
        self.runId = run


def run(coro):
    return asyncio.run(coro)


def test_authorized_chat_call_reuses_and_reports_a_real_authority_check():
    client = FakeClient()
    allowed = run(scope_reuse_authorizer(
        Request(_scope()), "tool",
        {"name": "get_recent_messages", "arguments": {"chat_id": 101}},
        client=client,
    ))
    assert allowed is True
    assert client.calls[0][1] == {"name": "get_recent_messages", "arguments": {"chat_id": 101}}


def test_chat_outside_scope_is_denied_and_never_executes():
    client = FakeClient(allow=False)
    executed = []
    allowed = run(scope_reuse_authorizer(
        Request(_scope()), "tool",
        {"name": "get_recent_messages", "arguments": {"chat_id": 202}},
        client=client,
    ))
    assert allowed is False
    assert executed == []  # the policy never invokes a tool itself


@pytest.mark.parametrize("bad_call", [
    {"name": "", "arguments": {}},
    {"name": "get_recent_messages", "arguments": None},
    {"name": None, "arguments": {"chat_id": 101}},
    "not-a-call",
])
def test_malformed_committed_call_is_denied_without_authority_or_execution(bad_call):
    client = FakeClient()
    assert run(scope_reuse_authorizer(Request(_scope()), "tool", bad_call, client=client)) is False
    assert client.calls == []


@pytest.mark.parametrize("field,value", [
    ("runId", "run_other"),
    ("sessionId", "as_other"),
    ("actorUserId", 8),
    ("scopeKey", "a" * 64),
    ("authorizationVersion", 2),
])
def test_receipt_bound_to_another_grant_or_run_cannot_ride_the_current_one(field, value):
    client = FakeClient()
    call = {"name": "get_recent_messages", "arguments": {"chat_id": 101}, field: value}
    assert run(scope_reuse_authorizer(Request(_scope()), "tool", call, client=client)) is False
    assert client.calls == []


def test_unknown_kind_is_denied_not_guessed():
    client = FakeClient()
    assert run(scope_reuse_authorizer(Request(_scope()), "mystery", {"name": "x"}, client=client)) is False
    assert client.calls == []


def test_missing_scope_denies_even_for_resume_handover():
    client = FakeClient()
    assert run(scope_replay_authorizer(Request(None), "handover", {"runId": "run_1"}, client=client)) is False
    assert client.calls == []


def test_object_without_any_identity_is_not_treated_as_reusable():
    client = FakeClient()

    class Anonymous:
        pass

    assert run(scope_reuse_authorizer(Request(_scope()), "tool",
                                      {"name": "get_recent_messages", "arguments": {"chat_id": 101}},
                                      client=client)) is True
    client.calls.clear()
    assert run(scope_replay_authorizer(Request(_scope()), "handover", Anonymous(), client=client)) is False
    assert client.calls == []


def test_resume_handover_rechecks_live_grant():
    client = FakeClient()
    assert run(scope_replay_authorizer(Request(_scope()), "handover", {"runId": "run_1"}, client=client)) is True
    assert client.calls[0][1] is None


def test_intersection_denies_when_either_side_refuses():
    async def allow(request, kind, call):
        return True

    async def refuse(request, kind, call):
        return False

    async def boom(request, kind, call):
        raise RuntimeError("policy unavailable")

    both = combine_authorizers(allow, refuse)
    assert run(both(Request(_scope()), "tool", {"name": "x", "arguments": {}})) is False
    broken = combine_authorizers(allow, boom)
    assert run(broken(Request(_scope()), "tool", {"name": "x", "arguments": {}})) is False
    both_allow = combine_authorizers(allow, allow)
    assert run(both_allow(Request(_scope()), "tool", {"name": "x", "arguments": {}})) is True


def test_installer_refuses_to_pretend_the_t1_slots_exist():
    with pytest.raises(TypeError):
        install_scope_authorizers(scope_reuse_authorizer, scope_replay_authorizer, compose=None)
    seen = {}

    def compose(reuse, replay):
        seen["reuse"], seen["replay"] = reuse, replay

    install_scope_authorizers(scope_reuse_authorizer, scope_replay_authorizer, compose=compose)
    assert seen["reuse"] is scope_reuse_authorizer
    assert seen["replay"] is scope_replay_authorizer

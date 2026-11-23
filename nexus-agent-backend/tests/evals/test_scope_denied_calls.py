"""An out-of-scope tool call inside a real run: never made, nothing returned.

The run itself goes on when only the arguments were out of scope, and ends with
one authorization error when the grant itself is gone.
"""
import copy
import json

import pytest

from app.config import get_settings
from app.scope import ScopeClient, ScopeDenied
from eval_harness import install, load_cases

SECRET = "项目A推进群"


@pytest.fixture(autouse=True)
def isolated_checkpoints(tmp_path, monkeypatch):
    monkeypatch.setattr(get_settings(), "checkpoint_path", str(tmp_path / "runs.sqlite"))


def two_tool_case():
    return copy.deepcopy(next(c for c in load_cases() if c["id"] == "two_tools_in_one_turn_keep_order"))


async def test_out_of_scope_chat_is_never_called_and_the_run_goes_on(monkeypatch):
    case = two_tool_case()
    # The scope covers chat 5 only; the model asks for chat 6 instead.
    case["model"][0][0]["tool"]["arguments"] = {"chat_id": 6}
    rig = install(case, monkeypatch)
    events = await rig.run()

    # Actual calls: the out-of-scope one never reached Java, the in-scope one did.
    assert [r["tool"] for r in rig.java.requests] == ["get_user_profile"]
    results = {e.data["callId"]: e.data for e in events if e.name == "tool_result"}
    assert results["call_a"]["status"] == "FAILED"
    assert results["call_b"]["status"] == "SUCCESS"

    # Returned data: the model got a bare refusal for call_a, no chat content.
    told = {m["tool_call_id"]: m["content"] for m in rig.model.calls[1]["messages"] if m["role"] == "tool"}
    assert json.loads(told["call_a"]) == {"toolError": "SCOPE_DENIED", "message": "scope authorization denied"}
    assert SECRET not in told["call_a"]

    # A refused argument is a tool failure, not a failed or stuck run.
    assert not [e for e in events if e.name == "error"]
    final = events[-1].data["result"]
    assert final["finishReason"] == "stop" and final["resumable"] is False
    assert [c["status"] for c in final["toolCalls"]] == ["FAILED", "SUCCESS"]


async def test_unreadable_time_argument_is_refused_without_a_call(monkeypatch):
    case = two_tool_case()
    case["model"][0][0]["tool"]["arguments"] = {"chat_id": 5, "start_time": "yesterday"}
    rig = install(case, monkeypatch)
    events = await rig.run()
    assert [r["tool"] for r in rig.java.requests] == ["get_user_profile"]
    assert not [e for e in events if e.name == "error"]
    assert events[-1].data["result"]["finishReason"] == "stop"


async def test_grant_revoked_mid_run_ends_with_one_authorization_error(monkeypatch):
    case = two_tool_case()
    rig = install(case, monkeypatch)

    async def validate(self, scope, tool=None):
        # Live until the model has been asked once; revoked from then on.
        if scope is None or rig.model.calls:
            raise ScopeDenied()
        return scope
    monkeypatch.setattr(ScopeClient, "validate", validate)
    events = await rig.run()

    assert rig.java.requests == []  # no tool call was made after the revocation
    assert [e.data["code"] for e in events if e.name == "error"] == ["AGENT_AUTHZ_40301"]
    assert events[-1].name == "done"
    assert events[-1].data["finishReason"] == "error" and events[-1].data["resumable"] is False
    assert not [e for e in events if e.name == "__final__"]
    assert SECRET not in json.dumps([e.data for e in events], ensure_ascii=False)

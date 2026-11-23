"""Golden regression set for agent orchestration.

Every case in cases.jsonl runs against a scripted model and a fake Java tool
service. Cases marked `knownDefect` state the intended behaviour and are
expected to fail until the defect is fixed.
"""
from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from app.prompts import build_messages
from app.schemas import Actor, InputPayload, InvokeRequest, Options, Session
from eval_harness import ACTOR_ID, ACTOR_NAME, check_expectations, check_invariants, install, load_cases, run_case

CASES = load_cases()
SNAPSHOTS = Path(__file__).parent / "snapshots"
UPDATE_SNAPSHOTS = os.environ.get("UPDATE_EVAL_SNAPSHOTS") == "1"


def _param(case):
    marks = [pytest.mark.xfail(reason=case["knownDefect"], strict=True)] if case.get("knownDefect") else []
    return pytest.param(case, id=case["id"], marks=marks)


@pytest.mark.parametrize("case", [_param(c) for c in CASES])
async def test_golden_case(case, monkeypatch):
    run = await run_case(case, monkeypatch)
    check_invariants(run)
    check_expectations(case, run)


def _assert_snapshot(name: str, actual) -> None:
    path = SNAPSHOTS / name
    if UPDATE_SNAPSHOTS:
        path.parent.mkdir(exist_ok=True)
        path.write_text(json.dumps(actual, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return
    expected = json.loads(path.read_text(encoding="utf-8"))
    assert actual == expected, f"{name} changed; rerun with UPDATE_EVAL_SNAPSHOTS=1 if this is intended"


def _request(operation: str, **input_fields) -> InvokeRequest:
    return InvokeRequest(
        traceId="tr_snapshot",
        actor=Actor(userId=ACTOR_ID, username=ACTOR_NAME),
        session=Session(sessionId="s_snapshot", operationType=operation),
        input=InputPayload(**input_fields),
        options=Options(),
    )


def test_prompt_rendering_snapshot():
    """A prompt edit must show up as a reviewable diff."""
    rendered = {
        "assistant_chat": build_messages(_request("ASSISTANT_CHAT", text="你好"), [], []),
        "chat_summary": build_messages(
            _request("CHAT_SUMMARY", text="总结本会话最近 80 条消息", chatId=20001,
                     summaryRangeType="LAST_N", rangeValue=80), [], []),
        "todo_extract": build_messages(
            _request("TODO_EXTRACT", text="从最近的会话消息中提炼待办事项", chatId=5,
                     summaryRangeType="LAST_N", rangeValue=24), [], []),
        "reply_suggest": build_messages(
            _request("REPLY_SUGGEST", text="为目标消息生成回复建议", chatId=5, targetMessageId=99,
                     targetMessageContent="报价单今天能给吗？", tone="PROFESSIONAL", length="SHORT"), [], []),
        "with_all_context": build_messages(
            _request("ASSISTANT_CHAT", text="上周的报价是多少？"),
            [{"role": "user", "content": "我叫小王"}, {"role": "assistant", "content": "好的，小王。"}],
            [{"memoryType": "PREF", "content": "用户喜欢简洁回复"}],
            relevant_history=["User: 上周报价是多少\nAssistant: 是 50 万"],
            kb_context="[1] contract.pdf#3 (score=0.910):\n违约金不超过合同总额 20%",
        ),
    }
    _assert_snapshot("build_messages.json", rendered)


async def test_model_is_offered_exactly_what_the_tool_server_lists(monkeypatch):
    """Nothing about a tool is written down in this service: a tool the server starts
    listing reaches the model with its name, description and argument schema intact."""
    case = next(c for c in CASES if c["id"] == "chat_plain_answer")
    rig = install(case, monkeypatch)
    rig.java.catalog["archive_chat"] = {
        "type": "object",
        "properties": {"chat_id": {"type": "integer", "description": "Chat to archive"},
                       "mode": {"type": "string", "enum": ["soft", "hard"]}},
        "required": ["chat_id"],
        "additionalProperties": False,
    }
    await rig.run()
    offered = {tool["function"]["name"]: tool["function"] for tool in rig.model.calls[0]["tools"]}
    # T2: the model is offered exactly what the server lists AND the run's scope allows.
    # This case has no contact scope, so the profile tool is withheld, not advertised.
    from app.scope import AuthorizedScope
    scope = rig.request().scope
    allowed = {name for name in rig.java.catalog
               if name != "get_user_profile" or scope.contactUserIds}
    assert set(offered) == allowed
    # The server's schema is passed through, with chat_id narrowed to what this run may read.
    narrowed = dict(rig.java.catalog["archive_chat"])
    narrowed["properties"] = {**narrowed["properties"],
        "chat_id": {**narrowed["properties"]["chat_id"], "enum": list(scope.chatIds)}}
    assert offered["archive_chat"]["parameters"] == narrowed
    assert offered["archive_chat"]["description"] == "archive_chat (fake)"
    assert rig.java.requests == []  # listing tools is not calling them

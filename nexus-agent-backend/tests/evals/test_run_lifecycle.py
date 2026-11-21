from __future__ import annotations

import asyncio
import contextlib
import copy
import json
import os
import re
import sqlite3
import subprocess
import sys
from pathlib import Path

import pytest
from pydantic import ValidationError

from app import orchestrator, routes
from app.checkpoint import open_run_store
from app.config import get_settings
from app.schemas import Actor, Options, ResumeRequest, RunQuery
from app.sse import sse_encoder
from eval_harness import ACTOR_ID, EVAL_API_KEY, install, load_cases


@pytest.fixture(autouse=True)
def isolated_checkpoints(tmp_path, monkeypatch):
    monkeypatch.setattr(get_settings(), "checkpoint_path", str(tmp_path / "runs.sqlite"))


def chat_case(turns):
    return {
        "id": "lifecycle",
        "operation": "ASSISTANT_CHAT",
        "input": {"text": "查询本会话"},
        "model": turns,
    }


async def until(stream, name):
    while True:
        event = await asyncio.wait_for(anext(stream), 2)
        if event.name == name:
            return event


async def test_first_text_is_visible_while_model_is_still_running(monkeypatch):
    rig = install(chat_case([[{"text": "甲"}, {"wait": "finish"}, {"text": "乙"}, {"finish": "stop"}]]), monkeypatch)
    stream = orchestrator.run_agent(rig.request())
    try:
        meta = await anext(stream)
        delta = await until(stream, "delta")
        assert delta.data["text"] == "甲"
        assert rig.model.open_streams == 1
        assert orchestrator.is_run_active(ACTOR_ID, meta.data["runId"])
        rig.model.gate("finish").set()
        rest = [event async for event in stream]
    finally:
        await stream.aclose()
    assert rest[-1].data["result"]["answer"] == "甲乙"
    assert rig.model.open_streams == 0


async def test_closed_consumer_cancels_model_and_can_resume(monkeypatch):
    rig = install(chat_case([
        [{"text": "未提交的半句"}, {"wait": "never"}, {"finish": "stop"}],
        [{"text": "恢复后的正文"}, {"finish": "stop"}],
    ]), monkeypatch)
    stream = orchestrator.run_agent(rig.request())
    meta = await anext(stream)
    await until(stream, "delta")
    await asyncio.wait_for(stream.aclose(), 2)
    run_id = meta.data["runId"]
    assert not orchestrator.is_run_active(ACTOR_ID, run_id)
    assert rig.model.open_streams == 0
    assert rig.memory.turns == [["user", "查询本会话"]]
    resumed = await rig.resume(run_id)
    assert resumed[-1].data["result"]["answer"] == "恢复后的正文"
    assert rig.memory.turns == [["user", "查询本会话"], ["assistant", "恢复后的正文"]]


async def test_resume_replays_committed_text_without_repeating_finished_tool(monkeypatch):
    case = copy.deepcopy(next(c for c in load_cases() if c["id"] == "failure_after_tool_keeps_tool_result"))
    case["model"][0].insert(0, {"text": "已经检索。"})
    case["model"][1].insert(0, {"text": "供应商中断的半句"})
    case["model"].append([{"text": "周五前交付。"}, {"finish": "stop"}])
    rig = install(case, monkeypatch)
    first = await rig.run()
    run_id = first[-1].data["result"]["runId"]
    assert first[-1].data["result"]["resumable"]
    resumed = await rig.resume(run_id)
    final = resumed[-1].data["result"]
    assert final["answer"] == "已经检索。周五前交付。"
    assert "供应商中断的半句" not in final["answer"]
    assert next(e for e in resumed if e.name == "delta").data["replayed"] is True
    assert final["finishReason"] == "stop"
    assert len(rig.java.requests) == 1
    assert len(rig.memory.turns) == 2
    assert len(rig.rag_writes) == 1
    assert not final["resumable"]
    async with open_run_store() as store:
        assert await store.pending(ACTOR_ID, rig.request().session.sessionId) == []


async def test_checkpoint_does_not_store_provider_credentials(monkeypatch):
    rig = install(chat_case([[{"error": "limit"}, {"finish": "error"}]]), monkeypatch)
    events = await rig.run()
    assert events[-1].data["result"]["resumable"]
    path = Path(get_settings().checkpoint_path)
    blobs = [p.read_bytes() for p in path.parent.glob(path.name + "*")]
    assert blobs
    assert EVAL_API_KEY.encode() not in b"".join(blobs)


async def test_other_actor_or_session_cannot_resume_or_read_runs(monkeypatch):
    rig = install(chat_case([[{"error": "limit"}, {"finish": "error"}]]), monkeypatch)
    failed = await rig.run()
    run_id = failed[-1].data["result"]["runId"]
    for kwargs in ({"actor_id": 8}, {"session_id": "another-session"}):
        rejected = await rig.resume(run_id, **kwargs)
        assert [e.name for e in rejected] == ["error", "done"]
        assert rejected[0].data["code"] == "AGENT_RUN_40401"
    assert len(rig.model.calls) == 1
    async with open_run_store() as store:
        assert await store.pending(8, rig.request().session.sessionId) == []


async def test_duplicate_resume_is_busy_until_first_execution_is_closed(monkeypatch):
    rig = install(chat_case([[{"text": "半句"}, {"wait": "never"}, {"finish": "stop"}]]), monkeypatch)
    stream = orchestrator.run_agent(rig.request())
    try:
        meta = await anext(stream)
        await until(stream, "delta")
        busy = await rig.resume(meta.data["runId"])
        assert busy[0].data["code"] == "AGENT_RUN_40901"
        assert len(rig.model.calls) == 1
    finally:
        await stream.aclose()


async def test_parallel_tools_are_bounded_and_history_order_is_stable(monkeypatch):
    case = copy.deepcopy(next(c for c in load_cases() if c["id"] == "two_tools_in_one_turn_keep_order"))
    rig = install(case, monkeypatch)
    rig.java.delay_sec = 0.02
    monkeypatch.setattr(get_settings(), "tool_max_concurrency", 2)
    monkeypatch.setattr(get_settings(), "enable_parallel_tool_calls", True)
    events = await rig.run()
    assert rig.java.max_in_flight == 2
    assert events[-1].data["result"]["finishReason"] == "stop"
    tool_ids = [m["tool_call_id"] for m in rig.model.calls[1]["messages"] if m["role"] == "tool"]
    assert tool_ids == ["call_a", "call_b"]


async def test_parallel_switch_can_force_serial_execution(monkeypatch):
    case = copy.deepcopy(next(c for c in load_cases() if c["id"] == "two_tools_in_one_turn_keep_order"))
    rig = install(case, monkeypatch)
    rig.java.delay_sec = 0.02
    monkeypatch.setattr(get_settings(), "enable_parallel_tool_calls", False)
    await rig.run()
    assert rig.java.max_in_flight == 1


async def test_invalid_structured_output_gets_one_budgeted_repair(monkeypatch):
    case = chat_case([
        [{"text": "not json"}, {"finish": "stop"}],
        [{"text": '{"draft":"已确认","alternatives":[]}'}, {"finish": "stop"}],
    ])
    case["operation"] = "REPLY_SUGGEST"
    rig = install(case, monkeypatch)
    events = await rig.run()
    result = events[-1].data["result"]
    assert result["finishReason"] == "stop"
    assert result["draft"] == "已确认"
    assert len(rig.model.calls) == 2
    assert "未通过结构校验" in rig.model.calls[1]["messages"][-1]["content"]
    # The rejected first attempt is withdrawn everywhere, not glued in front of the repair.
    repaired = '{"draft":"已确认","alternatives":[]}'
    assert result["answer"] == repaired
    assert rig.memory.turns == [["user", "查询本会话"], ["assistant", repaired]]
    assert rig.rag_writes == [("查询本会话", repaired)]
    # Public events now carry the run envelope; compare the payload this test is about.
    deltas = [{key: value for key, value in e.data.items() if key in ("text", "replayed")}
              for e in events if e.name == "delta"]
    assert deltas == [{"text": "not json"}, {"text": "", "replayed": True}, {"text": repaired}]


async def test_repair_keeps_text_committed_before_the_rejected_output(monkeypatch):
    case = copy.deepcopy(next(c for c in load_cases() if c["id"] == "todo_extract_object"))
    good = case["model"][1]
    case["model"][0].insert(0, {"text": "先读消息。"})
    case["model"][1] = [{"text": "还不是 JSON"}, {"finish": "stop"}]
    case["model"].append(good)
    rig = install(case, monkeypatch)
    events = await rig.run()
    result = events[-1].data["result"]
    assert result["finishReason"] == "stop"
    assert result["todos"]
    assert result["answer"].startswith("先读消息。")
    assert "还不是 JSON" not in result["answer"]
    assert {"text": "先读消息。", "replayed": True} in [
        {key: value for key, value in e.data.items() if key in ("text", "replayed")}
        for e in events if e.name == "delta"]
    assert rig.memory.turns[-1] == ["assistant", result["answer"]]


async def test_invalid_output_exhausting_budget_never_enters_assistant_memory(monkeypatch):
    case = chat_case([[{"text": "not json"}, {"finish": "stop"}]])
    case["operation"] = "REPLY_SUGGEST"
    case["options"] = {"maxIterations": 1}
    rig = install(case, monkeypatch)
    events = await rig.run()
    result = events[-1].data["result"]
    assert result["finishReason"] == "invalid_output"
    assert result["outputError"]
    assert rig.memory.turns == [["user", "查询本会话"]]
    assert rig.rag_writes == []


async def test_pending_and_purge_require_matching_actor(monkeypatch):
    rig = install(chat_case([[{"error": "limit"}, {"finish": "error"}]]), monkeypatch)
    await rig.run()
    request = RunQuery(actor=Actor(userId=ACTOR_ID), sessionId=rig.request().session.sessionId)
    ctx = {"actorUserId": 8, "traceId": "t", "provider": None}
    assert (await routes.pending_runs(request, ctx=ctx)).status_code == 403
    assert (await routes.purge_runs(request, ctx=ctx)).status_code == 403
    ctx["actorUserId"] = ACTOR_ID
    result = await routes.pending_runs(request, ctx=ctx)
    assert len(result["runs"]) == 1 and result["finished"] == []
    assert await routes.purge_runs(request, ctx=ctx) == {"purged": 1}
    assert await routes.pending_runs(request, ctx=ctx) == {"runs": [], "finished": []}


def test_sse_ids_are_scoped_to_each_response():
    first, second = sse_encoder(), sse_encoder()
    assert "id: 1\n" in first("delta", {"text": "甲"})
    assert "id: 1\n" in second("delta", {"text": "乙"})
    assert "id: 2\n" in first("done", {})


def test_zero_iterations_is_rejected():
    with pytest.raises(ValidationError):
        Options(maxIterations=0)


async def test_stream_body_and_signed_trace_must_agree(monkeypatch):
    rig = install(chat_case([[{"text": "回答"}, {"finish": "stop"}]]), monkeypatch)
    request = rig.request()
    ctx = {"actorUserId": ACTOR_ID, "traceId": "different-trace", "provider": None}
    response = await routes.invoke_stream(request, ctx=ctx)
    assert response.status_code == 400
    assert rig.model.calls == []


async def test_resume_stream_body_and_signed_trace_must_agree(monkeypatch):
    def unexpected_resume(*args, **kwargs):
        pytest.fail("trace mismatch must be rejected before starting resume")

    monkeypatch.setattr(routes, "resume_agent", unexpected_resume)
    request = ResumeRequest(
        traceId="body-trace",
        actor=Actor(userId=ACTOR_ID),
        sessionId="lifecycle-session",
        runId="lifecycle-run",
    )
    ctx = {"actorUserId": ACTOR_ID, "traceId": "different-trace", "provider": None}
    response = await routes.resume_stream(request, ctx=ctx)
    assert response.status_code == 400
    assert json.loads(response.body)["code"] == "AGENT_PARAM_40001"


async def test_error_chunk_without_finish_is_not_treated_as_success(monkeypatch):
    rig = install(chat_case([[{"text": "部分"}, {"error": "stream failed"}]]), monkeypatch)
    events = await rig.run()
    assert events[-1].data["result"]["finishReason"] == "error"
    assert rig.memory.turns == [["user", "查询本会话"]]
    assert rig.rag_writes == []


@pytest.mark.parametrize("ending, expected", [
    ([{"error": "stream failed"}, {"finish": "stop"}], "error"),
    ([{"error": "stream failed"}, {"finish": "timeout"}, {"finish": "stop"}], "timeout"),
    ([{"finish": "timeout"}, {"error": "stream failed"}, {"finish": "stop"}], "timeout"),
    ([{"finish": "timeout"}, {"raise": "stream failed"}], "timeout"),
])
async def test_failed_turn_cannot_be_masked_by_later_chunks(monkeypatch, ending, expected):
    rig = install(chat_case([[{"text": "部分"}, *ending]]), monkeypatch)
    events = await rig.run()
    result = events[-1].data["result"]
    assert result["finishReason"] == expected
    assert result["resumable"] is True
    assert result["answer"] == "部分"
    assert next(e for e in events if e.name == "done").data["finishReason"] == expected
    assert rig.memory.turns == [["user", "查询本会话"]]
    assert rig.rag_writes == []


@pytest.mark.parametrize("tool_after_finish", [False, True])
async def test_tool_calls_after_length_are_never_executed(monkeypatch, tool_after_finish):
    case = copy.deepcopy(next(c for c in load_cases() if c["id"] == "last_iteration_tool_runs_then_stops"))
    case["model"][0][-1] = {"finish": "length"}
    if tool_after_finish:
        case["model"][0].insert(0, case["model"][0].pop())
    rig = install(case, monkeypatch)
    events = await rig.run()
    assert rig.java.requests == []
    assert events[-1].data["result"]["finishReason"] == "length"
    assert events[-1].data["result"]["toolCalls"] == []
    assert not any(e.name == "tool_call" for e in events)


def test_crashed_process_resumes_from_finished_tool(tmp_path):
    root = Path(__file__).resolve().parents[2]
    checkpoint = tmp_path / "crash.sqlite"
    case = copy.deepcopy(next(c for c in load_cases() if c["id"] == "failure_after_tool_keeps_tool_result"))
    case["model"][1] = [{"exit": 23}]
    case_file = tmp_path / "case.json"
    case_file.write_text(json.dumps(case), encoding="utf-8")
    script = """
import asyncio, json, os, sys
from pytest import MonkeyPatch
from eval_harness import install
from app.config import get_settings
case = json.loads(open(sys.argv[1], encoding='utf-8').read())
get_settings().checkpoint_path = sys.argv[2]
rig = install(case, MonkeyPatch())
async def run():
    if sys.argv[3] == 'start':
        await rig.run(rig.request(runId='crash_run_001'))
    else:
        rig.model.turns = [[{'text':'恢复完成'}, {'finish':'stop'}]]
        events = await rig.resume('crash_run_001')
        result = events[-1].data['result']
        print(json.dumps({'result': result, 'toolRequests': len(rig.java.requests)}, ensure_ascii=False))
asyncio.run(run())
"""
    env = {**os.environ, "PYTHONPATH": os.pathsep.join([str(root), str(root / "tests" / "evals")]),
           "ENGINE": "langgraph", "PYTHONDONTWRITEBYTECODE": "1"}
    first = subprocess.run([sys.executable, "-c", script, str(case_file), str(checkpoint), "start"],
                           cwd=tmp_path, env=env, capture_output=True, text=True, timeout=10)
    assert first.returncode == 23, first.stderr
    second = subprocess.run([sys.executable, "-c", script, str(case_file), str(checkpoint), "resume"],
                            cwd=tmp_path, env=env, capture_output=True, text=True, timeout=10)
    assert second.returncode == 0, second.stderr
    payload = json.loads(second.stdout.strip().splitlines()[-1])
    assert payload["toolRequests"] == 0
    assert payload["result"]["answer"] == "恢复完成"
    assert payload["result"]["finishReason"] == "stop"


# ----- a run that already ended can still hand over its outcome -----


async def test_finished_run_hands_over_its_outcome_without_another_model_call(monkeypatch):
    rig = install(chat_case([[{"text": "完整回答"}, {"usage": [11, 4]}, {"finish": "stop"}]]), monkeypatch)
    first = await rig.run()
    run_id = first[-1].data["result"]["runId"]
    again = await rig.resume(run_id)
    assert [e.name for e in again] == ["meta", "delta", "usage", "done", "__final__"]
    assert again[0].data["resumed"] is True
    assert again[1].data == {"text": "完整回答", "replayed": True}
    assert again[3].data == {"finishReason": "stop", "runId": run_id, "resumable": False}
    assert again[-1].data["result"] == first[-1].data["result"]
    assert len(rig.model.calls) == 1
    assert rig.memory.turns == [["user", "查询本会话"], ["assistant", "完整回答"]]
    assert len(rig.rag_writes) == 1


async def test_outcome_of_a_run_that_hit_the_iteration_cap_is_kept_too(monkeypatch):
    case = copy.deepcopy(next(c for c in load_cases() if c["id"] == "max_iterations_keeps_streamed_text"))
    rig = install(case, monkeypatch)
    first = await rig.run()
    final = first[-1].data["result"]
    assert final["finishReason"] == "max_iterations" and not final["resumable"]
    again = await rig.resume(final["runId"])
    assert again[-1].data["result"] == final
    assert len(rig.model.calls) == 2 and len(rig.java.requests) == 2


async def test_stored_outcome_is_bound_to_its_user_and_session_and_goes_with_the_session(monkeypatch):
    rig = install(chat_case([[{"text": "完整回答"}, {"finish": "stop"}]]), monkeypatch)
    run_id = (await rig.run())[-1].data["result"]["runId"]
    for kwargs in ({"actor_id": 8}, {"session_id": "another-session"}):
        rejected = await rig.resume(run_id, **kwargs)
        assert [e.name for e in rejected] == ["error", "done"]
        assert rejected[0].data["code"] == "AGENT_RUN_40401"
    request = RunQuery(actor=Actor(userId=ACTOR_ID), sessionId=rig.request().session.sessionId)
    ctx = {"actorUserId": ACTOR_ID, "traceId": "t", "provider": None}
    assert await routes.pending_runs(request, ctx=ctx) == {"runs": [], "finished": [run_id]}
    await routes.purge_runs(request, ctx=ctx)
    assert await routes.pending_runs(request, ctx=ctx) == {"runs": [], "finished": []}
    gone = await rig.resume(run_id)
    assert gone[0].data["code"] == "AGENT_RUN_40401"


async def test_non_streaming_turn_leaves_nothing_behind(monkeypatch):
    rig = install(chat_case([[{"text": "完整回答"}, {"finish": "stop"}]]), monkeypatch)
    run_id = (await rig.run(keep_failed=False))[-1].data["result"]["runId"]
    assert (await rig.resume(run_id))[0].data["code"] == "AGENT_RUN_40401"


# ----- the tool server is a dependency that can be down -----


async def test_chat_goes_on_without_tools_when_the_tool_server_is_down(monkeypatch):
    rig = install(chat_case([[{"text": "直接回答"}, {"finish": "stop"}]]), monkeypatch)
    rig.java.fail_with = 503
    events = await rig.run()
    stages = [e.data for e in events if e.name == "stage" and e.data.get("phase") == "tools"]
    assert len(stages) == 1 and stages[0]["status"] == "unavailable"
    assert events[-1].data["result"]["finishReason"] == "stop"
    assert rig.model.calls[0]["tools"] == []
    assert not any(e.name == "error" for e in events)


async def test_turn_that_needs_a_tool_stops_and_resumes_once_the_tool_server_is_back(monkeypatch):
    case = copy.deepcopy(next(c for c in load_cases() if c["id"] == "summary_forces_tool_on_first_call"))
    rig = install(case, monkeypatch)
    rig.java.fail_with = 503
    events = await rig.run()
    final = events[-1].data["result"]
    assert [e.data["code"] for e in events if e.name == "error"] == ["AGENT_TOOL_50301"]
    assert final["finishReason"] == "error" and final["resumable"]
    assert rig.model.calls == []  # never asked to call a tool it was not given
    rig.java.fail_with = None
    resumed = await rig.resume(final["runId"])
    assert resumed[-1].data["result"]["finishReason"] == "stop"
    assert resumed[-1].data["result"]["answer"] == case["expect"]["answer"]
    assert len(rig.java.requests) == 1


async def test_tool_server_failing_mid_call_ends_the_turn_and_the_call_is_made_on_resume(monkeypatch):
    case = copy.deepcopy(next(c for c in load_cases() if c["id"] == "summary_forces_tool_on_first_call"))
    rig = install(case, monkeypatch)
    rig.java.fail_calls_with = 502
    events = await rig.run()
    final = events[-1].data["result"]
    names = [e.name for e in events]
    assert names.count("done") == 1
    assert [e.data["code"] for e in events if e.name == "error"] == ["AGENT_TOOL_50301"]
    assert [e.data["status"] for e in events if e.name == "tool_result"] == ["FAILED"]
    assert final["finishReason"] == "error" and final["resumable"]
    assert len(rig.model.calls) == 1  # the model was not shown a transport fault as if the tool had answered
    assert rig.memory.turns == [["user", case["input"]["text"]]]
    rig.java.fail_calls_with = None
    resumed = await rig.resume(final["runId"])
    assert resumed[-1].data["result"]["finishReason"] == "stop"
    assert len(rig.java.requests) == 1 and len(rig.model.calls) == 2


# ----- the stream always ends with exactly one `done` -----


async def test_run_store_failure_still_ends_the_stream(monkeypatch):
    rig = install(chat_case([[{"text": "回答"}, {"finish": "stop"}]]), monkeypatch)

    @contextlib.asynccontextmanager
    async def broken():
        raise sqlite3.OperationalError(f"unable to open database file {EVAL_API_KEY}")
        yield

    monkeypatch.setattr(orchestrator, "open_run_store", broken)
    events = await rig.run()
    assert [e.name for e in events] == ["error", "done"]
    assert events[0].data["code"] == "AGENT_SYS_50001"
    assert EVAL_API_KEY not in events[0].data["message"]
    assert events[1].data == {"finishReason": "error", "runId": events[1].data["runId"], "resumable": False}
    assert not orchestrator.active_thread_ids()


async def test_actor_mismatch_stream_ends_with_done():
    response = routes._actor_mismatch_stream()
    body = "".join([chunk async for chunk in response.body_iterator])
    assert body.count("event: error") == 1 and body.count("event: done") == 1
    assert body.index("event: error") < body.index("event: done")


async def test_tool_calls_carry_the_run_trace_to_java(monkeypatch):
    from app.tracing import init_tracing

    init_tracing()  # default configuration: nothing is exported
    case = copy.deepcopy(next(c for c in load_cases() if c["id"] == "summary_forces_tool_on_first_call"))
    rig = install(case, monkeypatch)
    await rig.run()
    traceparent = rig.java.requests[0]["headers"]["traceparent"]
    assert re.fullmatch(r"00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}", traceparent)
    assert set(traceparent.split("-")[1]) != {"0"}

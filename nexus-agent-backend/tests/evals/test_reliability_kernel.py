"""Regressions for the run reliability kernel.

Each test pins one defect that was observed in the code before it was fixed:
event identity that collided across attempts, model calls that were not
counted, role failures reported as run failures, truncated collaboration
answers remembered as successes, an outcome that could be lost between the
checkpoint being cleared and the result being saved, a stop that was only a
dropped connection, turns of one session running into each other, and tool
calls that ran again on resume.
"""
from __future__ import annotations

import asyncio

import pytest
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from app import mcp_tools, orchestrator, routes
from app.checkpoint import RunStore, open_run_store
from app.config import get_settings
from app.schemas import Actor, ResumeRequest
from eval_harness import ACTOR_ID, check_invariants, install, run_case

IDENTITY = ("runId", "attemptId", "sequence", "at")


@pytest.fixture(autouse=True)
def isolated_checkpoints(tmp_path, monkeypatch):
    monkeypatch.setattr(get_settings(), "checkpoint_path", str(tmp_path / "runs.sqlite"))
    async def authorize_fixture_reuse(request, kind, call):
        return True
    monkeypatch.setattr(orchestrator, "reuse_authorizer", authorize_fixture_reuse)


def chat_case(turns, **extra):
    return {"id": "kernel", "operation": "ASSISTANT_CHAT", "input": {"text": "查询本会话"}, "model": turns, **extra}


def collab_case(turns, **extra):
    return chat_case(turns, options={"collaborationEnabled": True}, **extra)


def analyst(text):
    return [{"text": text}, {"usage": [10, 5]}, {"finish": "stop"}]


def public(events):
    return [e for e in events if e.name != "__final__"]


def final(events):
    return events[-1].data["result"]


def usage(events):
    return next(e.data for e in events if e.name == "usage")


async def consume_until(stream, events, reached):
    async for event in stream:
        events.append(event)
        if reached(events):
            return


def model_starts(events):
    return sum(e.name == "stage" and e.data.get("phase") == "model" and e.data.get("status") == "start"
               for e in events)


# ----- run / attempt event identity -----


async def test_every_public_event_names_its_run_attempt_and_position(monkeypatch):
    rig = install(chat_case([
        [{"text": "半句"}, {"error": "limit"}, {"finish": "error"}],
        [{"text": "完整回答"}, {"finish": "stop"}],
    ]), monkeypatch)
    first = public(await rig.run())
    run_id = first[0].data["runId"]
    second = public(await rig.resume(run_id))

    for attempt in (first, second):
        for event in attempt:
            assert all(key in event.data for key in IDENTITY), (event.name, event.data)
        assert [e.data["sequence"] for e in attempt] == list(range(1, len(attempt) + 1))
        assert {e.data["runId"] for e in attempt} == {run_id}
        assert len({e.data["attemptId"] for e in attempt}) == 1

    # Both attempts start at sequence 1 with a `meta` frame: only the attempt tells them apart.
    assert (first[0].name, first[0].data["sequence"]) == (second[0].name, second[0].data["sequence"]) == ("meta", 1)
    assert first[0].data["attemptId"] != second[0].data["attemptId"]


async def test_handing_back_a_finished_outcome_is_the_same_frames_every_time(monkeypatch):
    rig = install(chat_case([[{"text": "回答"}, {"usage": [3, 2]}, {"finish": "stop"}]]), monkeypatch)
    ran = public(await rig.run())
    run_id = ran[0].data["runId"]

    def identity(events):
        return [(e.name, e.data["attemptId"], e.data["sequence"]) for e in public(events)]

    once = await rig.resume(run_id)
    twice = await rig.resume(run_id)
    assert identity(once) == identity(twice)
    assert [name for name, _, _ in identity(once)] == ["meta", "delta", "usage", "done"]
    # A handover is not the attempt that produced the outcome, so its frames cannot shadow that attempt's.
    assert {attempt for _, attempt, _ in identity(once)}.isdisjoint({e.data["attemptId"] for e in ran})
    assert final(once)["answer"] == "回答" and final(once)["finishReason"] == "stop"
    assert len(rig.model.calls) == 1
    assert rig.memory.turns == [["user", "查询本会话"], ["assistant", "回答"]]


# ----- real call statistics and error scope -----


async def test_collaboration_counts_all_four_calls_and_a_role_failure_stays_with_the_role(monkeypatch):
    rig = install(collab_case([
        analyst("code finding"),
        [{"error": "analyst quota"}, {"finish": "error"}],
        analyst("arch finding"),
        [{"text": "joined"}, {"usage": [30, 7]}, {"finish": "stop"}],
    ]), monkeypatch)
    events = await rig.run()

    assert len(rig.model.calls) == 4
    assert usage(events)["modelCalls"] == 4 and final(events)["modelCalls"] == 4
    errors = [e.data for e in events if e.name == "error"]
    assert [(e["scope"], e["agentId"], e["nodeId"]) for e in errors] == [("agent", "doc", "collaboration-doc")]
    assert final(events)["finishReason"] == "stop" and final(events)["answer"] == "joined"
    assert final(events)["usage"] == {"inputTokens": 50, "outputTokens": 17, "totalTokens": 67}
    assert rig.memory.turns[-1] == ["assistant", "joined"]


async def test_failed_and_retried_model_calls_are_counted_across_attempts(monkeypatch):
    rig = install(chat_case([
        [{"error": "limit"}, {"finish": "error"}],
        [{"text": "回答"}, {"finish": "stop"}],
    ]), monkeypatch)
    failed = await rig.run()
    assert usage(failed)["modelCalls"] == 1
    assert [e.data["scope"] for e in failed if e.name == "error"] == ["run"]
    resumed = await rig.resume(final(failed)["runId"])
    assert usage(resumed)["modelCalls"] == 2 and final(resumed)["modelCalls"] == 2
    # No provider reported tokens: that is unknown, and must not be read as zero spent.
    assert usage(resumed)["usageReported"] is False


async def test_a_turn_without_a_model_reports_zero_calls(monkeypatch):
    rig = install(chat_case([], provider="mock"), monkeypatch)
    events = await rig.run()
    assert usage(events)["modelCalls"] == 0
    assert final(events)["finishReason"] == "stop"


@pytest.mark.parametrize("ending, reason", [("length", "length"), ("content_filter", "content_filter")])
async def test_cut_off_synthesis_is_not_a_success_and_is_not_remembered(monkeypatch, ending, reason):
    rig = install(collab_case([
        analyst("a"), analyst("b"), analyst("c"),
        [{"text": "被截断的汇总"}, {"finish": ending}],
    ]), monkeypatch)
    events = await rig.run()
    assert final(events)["finishReason"] == reason
    assert final(events)["answer"] == "被截断的汇总"  # what was shown stays visible
    synth = next(e.data for e in events if e.name == "stage" and e.data.get("agentId") == "synth"
                 and e.data["status"] != "start")
    assert synth["finishReason"] == reason
    assert rig.memory.turns == [["user", "查询本会话"]]
    assert rig.rag_writes == []
    assert not final(events)["resumable"]


async def test_synthesis_that_times_out_fails_the_run_and_can_be_resumed(monkeypatch):
    rig = install(collab_case([
        analyst("a"), analyst("b"), analyst("c"),
        [{"text": "半句"}, {"finish": "timeout"}],
        [{"text": "完整汇总"}, {"finish": "stop"}],
    ]), monkeypatch)
    events = await rig.run()
    assert final(events)["finishReason"] == "timeout" and final(events)["resumable"]
    assert [(e.data["scope"], e.data["agentId"]) for e in events if e.name == "error"] == [("run", "synth")]
    assert rig.memory.turns == [["user", "查询本会话"]]

    resumed = await rig.resume(final(events)["runId"])
    assert final(resumed)["finishReason"] == "stop" and final(resumed)["answer"] == "完整汇总"
    # The three analysts finished in the first attempt: their calls are not made again.
    assert len(rig.model.calls) == 5
    assert usage(resumed)["modelCalls"] == 5
    reused = [e.data for e in resumed if e.name == "stage" and e.data.get("reused")]
    assert sorted(e["agentId"] for e in reused) == ["arch", "code", "doc"]
    assert sorted(e.data["text"] for e in resumed if e.name == "agent_output") == ["a", "b", "c"]
    assert rig.memory.turns == [["user", "查询本会话"], ["assistant", "完整汇总"]]


async def test_synthesis_that_raises_closes_its_step_and_reports_one_run_error(monkeypatch):
    rig = install(collab_case([analyst("a"), analyst("b"), analyst("c"), [{"raise": "connection reset"}]]),
                  monkeypatch)
    events = await rig.run()
    assert final(events)["finishReason"] == "error"
    assert [(e.data["code"], e.data["scope"]) for e in events if e.name == "error"] == [("AGENT_MODEL_50202", "run")]
    synth = [e.data["status"] for e in events if e.name == "stage" and e.data.get("agentId") == "synth"]
    assert synth == ["start", "failed"]


# ----- the outcome is durable before the checkpoint is cleared -----


async def test_outcome_survives_a_stop_between_saving_it_and_clearing_the_checkpoint(monkeypatch):
    rig = install(chat_case([[{"text": "回答"}, {"finish": "stop"}]]), monkeypatch)
    order: list[str] = []
    save_result, forget = RunStore.save_result, RunStore.forget

    async def saving(self, *args, **kwargs):
        order.append("save_result")
        return await save_result(self, *args, **kwargs)

    async def dying(self, thread_id):
        order.append("forget")
        raise RuntimeError("stopped before the checkpoint was cleared")

    monkeypatch.setattr(RunStore, "save_result", saving)
    monkeypatch.setattr(RunStore, "forget", dying)
    events = await rig.run()
    assert order == ["save_result", "forget"]
    assert [e.name for e in events][-2:] == ["error", "done"]  # the client never saw the outcome
    run_id = events[0].data["runId"]

    monkeypatch.setattr(RunStore, "forget", forget)
    resumed = await rig.resume(run_id)
    assert final(resumed)["finishReason"] == "stop" and final(resumed)["answer"] == "回答"
    assert len(rig.model.calls) == 1
    assert rig.memory.turns == [["user", "查询本会话"], ["assistant", "回答"]]
    async with open_run_store() as store:
        # The interrupted cleanup was completed: no unfinished run is offered any more.
        assert await store.pending(ACTOR_ID, "s_kernel") == []
        assert await store.finished(ACTOR_ID, "s_kernel") == [run_id]


async def test_outcome_that_could_not_be_saved_is_rebuilt_from_the_checkpoint_without_a_model_call(monkeypatch):
    rig = install(chat_case([[{"text": "回答"}, {"usage": [4, 2]}, {"finish": "stop"}]]), monkeypatch)
    save_result = RunStore.save_result

    async def dying(self, *args, **kwargs):
        raise RuntimeError("stopped before the outcome was saved")

    monkeypatch.setattr(RunStore, "save_result", dying)
    events = await rig.run()
    assert [e.name for e in events][-2:] == ["error", "done"]
    run_id = events[0].data["runId"]
    async with open_run_store() as store:
        assert [r.run_id for r in await store.pending(ACTOR_ID, "s_kernel")] == [run_id]

    monkeypatch.setattr(RunStore, "save_result", save_result)
    resumed = await rig.resume(run_id)
    assert final(resumed)["finishReason"] == "stop" and final(resumed)["answer"] == "回答"
    assert final(resumed)["usage"]["totalTokens"] == 6
    assert len(rig.model.calls) == 1
    assert rig.memory.turns == [["user", "查询本会话"], ["assistant", "回答"]]
    assert rig.rag_writes == [("查询本会话", "回答")] * 2 or rig.rag_writes == [("查询本会话", "回答")]


# ----- explicit cancel -----


async def cancel(run_id, session_id="s_kernel", actor_id=ACTOR_ID):
    return await orchestrator.cancel_run(actor_id, session_id, run_id)


async def test_cancel_closes_every_analyst_stream_and_is_confirmed(monkeypatch):
    waiting = [[{"wait": "never"}, {"text": "late"}, {"finish": "stop"}] for _ in range(3)]
    rig = install(collab_case([*waiting, [{"text": "joined"}, {"finish": "stop"}]]), monkeypatch)
    stream = orchestrator.run_agent(rig.request())
    events: list = []
    try:
        await asyncio.wait_for(consume_until(stream, events, lambda seen: model_starts(seen) == 3), 2)
        assert rig.model.open_streams == 3
        run_id = events[0].data["runId"]

        async def rest():
            events.extend([event async for event in stream])

        reader = asyncio.create_task(rest())
        assert await asyncio.wait_for(cancel(run_id), 3) == "cancelled"
        # Confirmed means released: nothing is still talking to the provider or the tool server.
        assert rig.model.open_streams == 0
        await asyncio.wait_for(reader, 2)
    finally:
        await stream.aclose()

    done = next(e.data for e in events if e.name == "done")
    assert done["finishReason"] == "cancelled" and done["resumable"] is True
    assert final(events)["finishReason"] == "cancelled"
    assert not orchestrator.is_run_active(ACTOR_ID, run_id)
    assert len(rig.model.calls) == 3  # the synthesis was never started
    assert usage(events)["modelCalls"] == 3
    assert rig.memory.turns == [["user", "查询本会话"]] and rig.rag_writes == []
    assert not [e for e in events if e.name == "error"]
    assert await cancel(run_id) == "not_active"


async def test_cancel_releases_the_tool_call_in_flight_and_never_starts_the_queued_one(monkeypatch):
    calls = [{"tool": {"id": f"call_{n}", "name": "get_chat_profile", "arguments": {"chat_id": n}}} for n in (1, 2)]
    rig = install(chat_case([[*calls, {"finish": "tool_calls"}], [{"text": "不应出现"}, {"finish": "stop"}]],
                            # Both chats are ones this turn is authorized to read.
                            java={"get_chat_profile": [{"json": {"chatId": 1}}, {"json": {"chatId": 2}}]}), monkeypatch)
    monkeypatch.setattr(get_settings(), "enable_parallel_tool_calls", False)
    monkeypatch.setattr(get_settings(), "tool_timeout_sec", 30)
    rig.java.delay_sec = 30
    stream = orchestrator.run_agent(rig.request())
    events: list = []
    try:
        async def first_call_in_flight():
            async for event in stream:
                events.append(event)
                if event.name == "tool_call" and event.data["callId"] == "call_2":
                    break
            while rig.java.in_flight == 0:
                await asyncio.sleep(0.01)

        await asyncio.wait_for(first_call_in_flight(), 3)
        run_id = events[0].data["runId"]

        async def rest():
            events.extend([event async for event in stream])

        reader = asyncio.create_task(rest())
        assert await asyncio.wait_for(cancel(run_id), 3) == "cancelled"
        await asyncio.wait_for(reader, 2)
    finally:
        await stream.aclose()

    assert rig.java.in_flight == 0
    assert [r["arguments"] for r in rig.java.requests] == [{"chat_id": 1}]
    assert len(rig.model.calls) == 1
    assert final(events)["finishReason"] == "cancelled"


async def test_cancel_is_scoped_to_the_owner_and_session(monkeypatch):
    rig = install(chat_case([[{"text": "甲"}, {"wait": "finish"}, {"text": "乙"}, {"finish": "stop"}]]), monkeypatch)
    stream = orchestrator.run_agent(rig.request())
    events: list = []
    try:
        await asyncio.wait_for(consume_until(stream, events, lambda seen: seen[-1].name == "delta"), 2)
        run_id = events[0].data["runId"]
        assert await cancel(run_id, actor_id=8) == "not_active"
        assert await cancel(run_id, session_id="another-session") == "not_active"
        assert orchestrator.is_run_active(ACTOR_ID, run_id) and rig.model.open_streams == 1

        response = await routes.cancel_run_route(
            ResumeRequest(traceId="tr", actor=Actor(userId=8), sessionId="s_kernel", runId=run_id),
            {"actorUserId": ACTOR_ID, "traceId": "tr"})
        assert response.status_code == 403
        assert orchestrator.is_run_active(ACTOR_ID, run_id)

        rig.model.gate("finish").set()
        events.extend([event async for event in stream])
    finally:
        await stream.aclose()
    assert final(events)["finishReason"] == "stop" and final(events)["answer"] == "甲乙"


async def test_cancel_route_confirms_with_the_outcome(monkeypatch):
    rig = install(chat_case([[{"text": "甲"}, {"wait": "never"}, {"finish": "stop"}]]), monkeypatch)
    stream = orchestrator.run_agent(rig.request())
    events: list = []
    try:
        await asyncio.wait_for(consume_until(stream, events, lambda seen: seen[-1].name == "delta"), 2)
        run_id = events[0].data["runId"]

        async def rest():
            events.extend([event async for event in stream])

        reader = asyncio.create_task(rest())
        body = await asyncio.wait_for(routes.cancel_run_route(
            ResumeRequest(traceId="tr", actor=Actor(userId=ACTOR_ID), sessionId="s_kernel", runId=run_id),
            {"actorUserId": ACTOR_ID, "traceId": "tr"}), 3)
        await asyncio.wait_for(reader, 2)
    finally:
        await stream.aclose()
    assert body == {"runId": run_id, "status": "cancelled"}
    assert rig.model.open_streams == 0


# ----- one turn at a time per session -----


async def test_second_turn_in_a_session_waits_for_the_first_instead_of_interleaving(monkeypatch):
    rig = install(chat_case([
        [{"text": "第一轮"}, {"wait": "first"}, {"finish": "stop"}],
        [{"text": "第二轮"}, {"finish": "stop"}],
    ]), monkeypatch)
    first = orchestrator.run_agent(rig.request())
    first_events: list = []
    try:
        await asyncio.wait_for(consume_until(first, first_events, lambda seen: seen[-1].name == "delta"), 2)

        second = asyncio.create_task(rig.run(rig.request(input={"text": "第二个问题"})))
        await asyncio.sleep(0.1)
        # The second turn has not read history, written memory or called the model yet.
        assert not second.done()
        assert len(rig.model.calls) == 1
        assert rig.memory.turns == [["user", "查询本会话"]]

        rig.model.gate("first").set()
        first_events.extend([event async for event in first])
        second_events = await asyncio.wait_for(second, 3)
    finally:
        await first.aclose()

    assert final(first_events)["answer"] == "第一轮" and final(second_events)["answer"] == "第二轮"
    assert rig.memory.turns == [["user", "查询本会话"], ["assistant", "第一轮"],
                                ["user", "第二个问题"], ["assistant", "第二轮"]]


async def test_second_turn_is_refused_when_the_first_does_not_end_in_time(monkeypatch):
    monkeypatch.setattr(orchestrator, "_SESSION_WAIT_SEC", 0.05)
    rig = install(chat_case([[{"text": "第一轮"}, {"wait": "never"}, {"finish": "stop"}],
                             [{"text": "不应出现"}, {"finish": "stop"}]]), monkeypatch)
    first = orchestrator.run_agent(rig.request())
    try:
        await asyncio.wait_for(consume_until(first, [], lambda seen: seen[-1].name == "delta"), 2)
        refused = await rig.run(rig.request(input={"text": "第二个问题"}))
        assert [e.name for e in refused] == ["error", "done"]
        assert refused[0].data["code"] == "AGENT_RUN_40902" and refused[0].data["scope"] == "run"
        assert refused[1].data["finishReason"] == "error" and refused[1].data["resumable"] is False
        assert len(rig.model.calls) == 1
        assert rig.memory.turns == [["user", "查询本会话"]]
        # Another session of the same user is not held up.
        other = await rig.run(rig.request(session={"sessionId": "s_other", "operationType": "ASSISTANT_CHAT"}))
        assert final(other)["finishReason"] == "stop"
    finally:
        await first.aclose()


# ----- committed tool calls are not made again -----


async def test_resume_reuses_the_committed_tool_call_and_only_makes_the_one_that_failed(monkeypatch):
    calls = [
        {"tool": {"id": "call_a", "name": "get_recent_messages", "arguments": {"chat_id": 5, "limit": 20}}},
        {"tool": {"id": "call_b", "name": "get_chat_profile", "arguments": {"chat_id": 5}}},
    ]
    rig = install(chat_case(
        [[*calls, {"finish": "tool_calls"}], [{"text": "两个结果都已读到。"}, {"finish": "stop"}]],
        java={"get_recent_messages": {"json": {"messages": ["周五前交付"]}},
              "get_chat_profile": {"json": {"name": "项目群"}}}), monkeypatch)
    rig.java.fail_tools = {"get_chat_profile"}
    failed = await rig.run()
    assert final(failed)["finishReason"] == "error" and final(failed)["resumable"]
    assert sorted(r["tool"] for r in rig.java.requests) == ["get_recent_messages"]
    assert len(rig.model.calls) == 1

    rig.java.fail_tools = set()
    resumed = await rig.resume(final(failed)["runId"])
    assert final(resumed)["finishReason"] == "stop"
    # The read that had succeeded was not sent to Java a second time.
    assert sorted(r["tool"] for r in rig.java.requests) == ["get_chat_profile", "get_recent_messages"]
    results = {e.data["callId"]: e.data for e in resumed if e.name == "tool_result"}
    assert results["call_a"]["status"] == "SUCCESS" and results["call_a"]["reused"] is True
    assert results["call_b"]["status"] == "SUCCESS" and "reused" not in results["call_b"]
    shown = [m for m in rig.model.calls[1]["messages"] if m["role"] == "tool"]
    assert [m["tool_call_id"] for m in shown] == ["call_a", "call_b"]
    assert "周五前交付" in shown[0]["content"] and "项目群" in shown[1]["content"]
    assert [(c["toolName"], c["status"]) for c in final(resumed)["toolCalls"]] == [
        ("get_recent_messages", "SUCCESS"), ("get_chat_profile", "SUCCESS")]


async def test_a_committed_call_is_only_reused_for_the_same_call(monkeypatch):
    async with open_run_store() as store:
        await store.commit("u7:run_one", "tool", "call_a", "fp-1", {"payload": {"n": 1}, "latencyMs": 3})
        await store.commit("u7:run_one", "tool", "call_a", "fp-1", {"payload": {"n": 2}, "latencyMs": 9})
        assert await store.committed("u7:run_one", "tool", "call_a", "fp-1") == {"payload": {"n": 1}, "latencyMs": 3}
        assert await store.committed("u7:run_one", "tool", "call_a", "fp-other") is None
        assert await store.committed("u7:run_two", "tool", "call_a", "fp-1") is None
        await store.forget("u7:run_one")
        assert await store.committed("u7:run_one", "tool", "call_a", "fp-1") is None


# ----- the ordinary single-assistant turn is unchanged -----


async def test_single_assistant_tool_turn_keeps_its_contract(monkeypatch):
    case = chat_case(
        [[{"tool": {"id": "call_1", "name": "get_recent_messages", "arguments": {"chat_id": 5, "limit": 80}}},
          {"usage": [100, 10]}, {"finish": "tool_calls"}],
         [{"text": "周五前交付。"}, {"usage": [140, 6]}, {"finish": "stop"}]],
        java={"get_recent_messages": {"json": {"messages": ["周五前交付初稿"]}}})
    run = await run_case(case, monkeypatch)
    check_invariants(run)
    assert run.final["answer"] == "周五前交付。" and run.final["finishReason"] == "stop"
    assert run.final["usage"] == {"inputTokens": 240, "outputTokens": 16, "totalTokens": 256}
    assert run.final["modelCalls"] == 2
    assert [e.name for e in run.events if e.name in ("tool_call", "tool_result")] == ["tool_call", "tool_result"]
    result = next(e.data for e in run.events if e.name == "tool_result")
    assert result["status"] == "SUCCESS" and "reused" not in result
    assert not [e for e in run.events if e.name == "error"]
    assert usage(run.events)["usageReported"] is True


async def test_unreported_usage_is_unknown_not_zero(monkeypatch):
    rig = install(chat_case([[{"text": "回答"}, {"finish": "stop"}]]), monkeypatch)
    events = await rig.run()
    assert final(events)["usage"] == {"inputTokens": None, "outputTokens": None, "totalTokens": None}
    assert usage(events)["usageComplete"] is False


async def test_repeated_model_errors_only_report_one_run_error(monkeypatch):
    rig = install(chat_case([[{"error": "first"}, {"error": "second"}, {"raise": "third"}]]), monkeypatch)
    events = await rig.run()
    assert len([e for e in events if e.name == "error"]) == 1


async def test_cancel_without_a_reader_releases_stream(monkeypatch):
    rig = install(chat_case([[{"text": "半句"}, {"wait": "never"}, {"finish": "stop"}]]), monkeypatch)
    stream = orchestrator.run_agent(rig.request())
    seen = []
    try:
        await consume_until(stream, seen, lambda events: events[-1].name == "delta")
        assert await asyncio.wait_for(cancel(seen[0].data["runId"]), 2) == "cancelled"
        assert rig.model.open_streams == 0
        rest = [e async for e in stream]
        assert final(rest)["finishReason"] == "cancelled"
    finally:
        await stream.aclose()


async def test_cached_analysts_require_current_authorization(monkeypatch):
    rig = install(collab_case([analyst("a"), analyst("b"), analyst("c"), [{"finish": "timeout"}]]), monkeypatch)
    failed = await rig.run()
    monkeypatch.setattr(orchestrator, "reuse_authorizer", None)
    resumed = await rig.resume(final(failed)["runId"])
    assert final(resumed)["finishReason"] == "error"
    assert len(rig.model.calls) == 4
    assert not [e for e in resumed if e.name == "agent_output"]


async def test_tool_statistics_separate_execution_from_reuse(monkeypatch):
    calls = [{"tool": {"id": "call_a", "name": "get_recent_messages", "arguments": {"chat_id": 5}}},
             {"tool": {"id": "call_b", "name": "get_chat_profile", "arguments": {"chat_id": 5}}}]
    rig = install(chat_case([[*calls, {"finish": "tool_calls"}], [{"text": "回答"}, {"finish": "stop"}]],
        java={"get_recent_messages": {"json": {}}, "get_chat_profile": {"json": {}}}), monkeypatch)
    rig.java.fail_tools = {"get_chat_profile"}
    failed = await rig.run()
    assert final(failed)["toolCallCount"] == 2
    rig.java.fail_tools.clear()
    resumed = await rig.resume(final(failed)["runId"])
    assert final(resumed)["toolCallCount"] == 3
    assert final(resumed)["reusedToolCallCount"] == 1


async def test_finished_handover_never_resolves_a_model_configuration(monkeypatch):
    rig = install(chat_case([[{"text": "已保存回答"}, {"usage": [3, 2]}, {"finish": "stop"}]]), monkeypatch)
    first = await rig.run()
    run_id = final(first)["runId"]

    def unavailable(_provider):
        raise ValueError("model configuration was removed")

    monkeypatch.setattr(orchestrator, "_resolve_provider_config", unavailable)
    recovered = await rig.resume(run_id)
    assert not [event for event in recovered if event.name == "error"]
    assert final(recovered)["answer"] == "已保存回答"
    assert final(recovered)["finishReason"] == "stop"
    assert len(rig.model.calls) == 1
    assert rig.memory.turns == [["user", "查询本会话"], ["assistant", "已保存回答"]]


# ----- a refused or denied resume leaves what it could not continue exactly as it was -----


def refusal(events):
    assert [e.name for e in events] == ["error", "done"]
    return events[0].data, events[1].data


async def test_resume_refused_because_the_run_is_executing_does_not_end_that_run(monkeypatch):
    rig = install(chat_case([[{"text": "半句"}, {"wait": "release"}, {"text": "，完整"}, {"finish": "stop"}]]),
                  monkeypatch)
    stream = orchestrator.run_agent(rig.request())
    events: list = []
    try:
        await asyncio.wait_for(consume_until(stream, events, lambda seen: seen[-1].name == "delta"), 2)
        run_id = events[0].data["runId"]
        error, done = refusal(await rig.resume(run_id))
        assert error["code"] == "AGENT_RUN_40901"
        # The run is alive: whoever recorded this refusal must not seal it as failed for good.
        assert done["finishReason"] == "error" and done["resumable"] is True
        assert done["attemptId"] != events[0].data["attemptId"]
        rig.model.gate("release").set()
        events.extend([event async for event in stream])
    finally:
        await stream.aclose()
    assert final(events)["finishReason"] == "stop" and final(events)["answer"] == "半句，完整"
    assert len(rig.model.calls) == 1


async def test_resume_refused_while_the_session_is_busy_can_be_resumed_afterwards(monkeypatch):
    rig = install(chat_case([
        [{"error": "limit"}, {"finish": "error"}],
        [{"text": "另一轮"}, {"wait": "other"}, {"finish": "stop"}],
        [{"text": "恢复后的回答"}, {"finish": "stop"}],
    ]), monkeypatch)
    failed = await rig.run()
    run_id = final(failed)["runId"]
    monkeypatch.setattr(orchestrator, "_SESSION_WAIT_SEC", 0.05)
    other = orchestrator.run_agent(rig.request(input={"text": "另一个问题"}))
    other_events: list = []
    try:
        await asyncio.wait_for(consume_until(other, other_events, lambda seen: seen[-1].name == "delta"), 2)
        error, done = refusal(await rig.resume(run_id))
        assert error["code"] == "AGENT_RUN_40902"
        assert done["resumable"] is True
        assert len(rig.model.calls) == 2
        rig.model.gate("other").set()
        other_events.extend([event async for event in other])
    finally:
        await other.aclose()
    resumed = await rig.resume(run_id)
    assert final(resumed)["finishReason"] == "stop" and final(resumed)["answer"] == "恢复后的回答"


async def test_a_new_turn_refused_as_busy_has_nothing_to_resume(monkeypatch):
    monkeypatch.setattr(orchestrator, "_SESSION_WAIT_SEC", 0.05)
    rig = install(chat_case([[{"text": "第一轮"}, {"wait": "never"}, {"finish": "stop"}]]), monkeypatch)
    first = orchestrator.run_agent(rig.request())
    try:
        await asyncio.wait_for(consume_until(first, [], lambda seen: seen[-1].name == "delta"), 2)
        _, done = refusal(await rig.run(rig.request(input={"text": "第二个问题"})))
        assert done["resumable"] is False
    finally:
        await first.aclose()


def tool_case(turns):
    return chat_case(turns, java={"get_recent_messages": {"json": {"messages": ["周五前交付"]}}})


TOOL_CALL = {"tool": {"id": "call_a", "name": "get_recent_messages", "arguments": {"chat_id": 5, "limit": 20}}}


async def test_denied_handover_gives_nothing_away_and_keeps_the_outcome_for_an_authorized_fetch(monkeypatch):
    rig = install(tool_case([[TOOL_CALL, {"finish": "tool_calls"}], [{"text": "周五前交付。"}, {"finish": "stop"}]]),
                  monkeypatch)
    ran = await rig.run()
    run_id = final(ran)["runId"]
    allowed = orchestrator.reuse_authorizer

    monkeypatch.setattr(orchestrator, "reuse_authorizer", None)
    error, done = refusal(await rig.resume(run_id))
    assert (error["code"], error["scope"]) == ("AGENT_AUTHZ_40301", "run")
    assert "周五前交付" not in str(error)
    # Nothing was lost: the outcome can be fetched once authorization can be checked again.
    assert done["finishReason"] == "error" and done["resumable"] is True

    async def deny(request, kind, call):
        assert kind == "handover" and call == {"runId": run_id}
        return False

    monkeypatch.setattr(orchestrator, "reuse_authorizer", deny)
    error, done = refusal(await rig.resume(run_id))
    assert error["code"] == "AGENT_AUTHZ_40301" and done["resumable"] is True

    monkeypatch.setattr(orchestrator, "reuse_authorizer", allowed)
    handed = await rig.resume(run_id)
    assert final(handed)["answer"] == "周五前交付。" and final(handed)["finishReason"] == "stop"
    assert len(rig.model.calls) == 2 and len(rig.java.requests) == 1


async def test_denied_resume_runs_nothing_and_keeps_the_checkpoint(monkeypatch):
    rig = install(tool_case([
        [TOOL_CALL, {"finish": "tool_calls"}],
        [{"error": "limit"}, {"finish": "error"}],
        [{"text": "周五前交付。"}, {"finish": "stop"}],
    ]), monkeypatch)
    failed = await rig.run()
    run_id = final(failed)["runId"]
    assert final(failed)["resumable"] and len(rig.model.calls) == 2
    allowed = orchestrator.reuse_authorizer

    monkeypatch.setattr(orchestrator, "reuse_authorizer", None)
    error, done = refusal(await rig.resume(run_id))
    assert (error["code"], error["scope"]) == ("AGENT_AUTHZ_40301", "run")
    assert done["resumable"] is True
    assert len(rig.model.calls) == 2 and len(rig.java.requests) == 1
    async with open_run_store() as store:
        assert [r.run_id for r in await store.pending(ACTOR_ID, "s_kernel")] == [run_id]

    monkeypatch.setattr(orchestrator, "reuse_authorizer", allowed)
    resumed = await rig.resume(run_id)
    assert final(resumed)["finishReason"] == "stop" and final(resumed)["answer"] == "周五前交付。"
    assert len(rig.model.calls) == 3 and len(rig.java.requests) == 1
    assert final(resumed)["modelCalls"] == 3


# ----- a provider stream belongs to the step that opened it -----


async def test_a_model_stream_is_opened_and_read_by_one_task(monkeypatch):
    """Provider SDKs hold timeouts and connection scopes that belong to the task that
    opened the stream; a stream handed from one task to another loses them."""
    rig = install(collab_case([analyst("a"), analyst("b"), analyst("c"),
                               [{"text": "joined"}, {"usage": [1, 1]}, {"finish": "stop"}]]), monkeypatch)
    events = await rig.run()
    assert final(events)["finishReason"] == "stop"
    assert [len(tasks) for tasks in rig.model.readers] == [1, 1, 1, 1]
    single = install(chat_case([[{"text": "回答"}, {"finish": "stop"}]]), monkeypatch)
    await single.run()
    assert [len(tasks) for tasks in single.model.readers] == [1]


async def test_a_model_step_is_announced_before_its_first_output_and_counted_once(monkeypatch):
    rig = install(chat_case([[{"reasoning": "想"}, {"text": "回答"}, {"finish": "stop"}]]), monkeypatch)
    events = await rig.run()
    names = [(e.name, e.data.get("status")) for e in events
             if e.name in ("reasoning", "delta") or e.data.get("phase") == "model"]
    assert names == [("stage", "start"), ("reasoning", None), ("delta", None), ("stage", "done")]
    assert usage(events)["modelCalls"] == 1


# ----- every durable step can be the last one written -----


class ByTranscript:
    """Answers from what it is shown, so a step that runs again gets the same reply."""

    def __init__(self):
        self.calls = []
        self.open_streams = 0

    async def complete(self, messages, tools=None, **kwargs):
        self.calls.append(messages)
        if any(m["role"] == "tool" for m in messages):
            steps = [{"text": "周五前交付。"}, {"usage": [140, 6]}, {"finish": "stop"}]
        else:
            steps = [TOOL_CALL, {"usage": [100, 10]}, {"finish": "tool_calls"}]
        from eval_harness import _chunk
        for step in steps:
            yield _chunk(step)


async def durable_writes(monkeypatch):
    """How many checkpoint writes the reference turn makes when nothing goes wrong."""
    rig = install(tool_case([]), monkeypatch)
    monkeypatch.setattr(orchestrator, "build_client", lambda _cfg: ByTranscript())
    writes = 0
    aput = AsyncSqliteSaver.aput

    async def counting(self, *args, **kwargs):
        nonlocal writes
        writes += 1
        return await aput(self, *args, **kwargs)

    monkeypatch.setattr(AsyncSqliteSaver, "aput", counting)
    events = await rig.run(rig.request(session={"sessionId": "s_reference", "operationType": "ASSISTANT_CHAT"}))
    monkeypatch.setattr(AsyncSqliteSaver, "aput", aput)
    assert final(events)["finishReason"] == "stop"
    return writes


async def test_losing_any_one_checkpoint_write_still_ends_in_one_consistent_outcome(monkeypatch):
    total = await durable_writes(monkeypatch)
    assert total >= 6  # input, context, model, tools, model, validation, settle
    aput = AsyncSqliteSaver.aput
    for lost in range(1, total + 1):
        rig = install(tool_case([]), monkeypatch)
        model = ByTranscript()
        monkeypatch.setattr(orchestrator, "build_client", lambda _cfg, model=model: model)
        session = {"sessionId": f"s_lost_{lost}", "operationType": "ASSISTANT_CHAT"}
        written = 0

        async def failing(self, *args, **kwargs):
            nonlocal written
            written += 1
            if written == lost:
                raise RuntimeError("the checkpoint could not be written")
            return await aput(self, *args, **kwargs)

        monkeypatch.setattr(AsyncSqliteSaver, "aput", failing)
        broken = await rig.run(rig.request(session=session))
        monkeypatch.setattr(AsyncSqliteSaver, "aput", aput)
        where = f"checkpoint write {lost} of {total}"
        assert [e.name for e in broken].count("done") == 1, where
        assert final(broken)["finishReason"] == "error", where
        run_id = final(broken)["runId"]

        # Only losing the very first write leaves nothing to continue from.
        assert final(broken)["resumable"] is (lost > 1), where
        recovered = await rig.resume(run_id, session_id=session["sessionId"])
        if lost == 1:
            # Nothing of the turn was durable: it is reported failed, and stays failed.
            assert final(recovered)["finishReason"] == "error", where
            assert model.calls == [] and rig.memory.turns == [] and rig.java.requests == [], where
        else:
            assert final(recovered)["finishReason"] == "stop", where
            assert final(recovered)["answer"] == "周五前交付。", where
            assert not final(recovered)["resumable"], where
            assert rig.memory.turns == [["user", "查询本会话"], ["assistant", "周五前交付。"]], where
            # The step's result was already on disk, so neither model call nor the read is made again.
            assert len(model.calls) == 2 and final(recovered)["modelCalls"] == 2, where
            assert len(rig.java.requests) == 1, where
            assert final(recovered)["usage"] == {"inputTokens": 240, "outputTokens": 16, "totalTokens": 256}, where
        requests = len(rig.java.requests)
        handed = await rig.resume(run_id, session_id=session["sessionId"])
        assert final(handed) == final(recovered), where
        assert len(model.calls) == (0 if lost == 1 else 2) and len(rig.java.requests) == requests, where


async def test_cancel_between_a_steps_result_and_its_checkpoint_keeps_the_run_resumable(monkeypatch):
    """The stop lands when the model step's result is on disk and the checkpoint that follows it
    is not. The turn is unfinished: it stays resumable instead of being sealed as a cancelled
    outcome, and the finished model call is not made again."""
    rig = install(chat_case([[{"text": "回答"}, {"usage": [3, 2]}, {"finish": "stop"}]]), monkeypatch)
    aput = AsyncSqliteSaver.aput
    reached, written = asyncio.Event(), 0

    async def held(self, *args, **kwargs):
        nonlocal written
        written += 1
        if written == 4:  # input, start, context, then the model step's checkpoint
            reached.set()
            await asyncio.Event().wait()  # until the stop abandons this write
        return await aput(self, *args, **kwargs)

    monkeypatch.setattr(AsyncSqliteSaver, "aput", held)
    stream = orchestrator.run_agent(rig.request())
    events: list = []

    async def read():
        async for event in stream:
            events.append(event)

    reader = asyncio.create_task(read())
    try:
        await asyncio.wait_for(reached.wait(), 2)
        run_id = events[0].data["runId"]
        assert rig.model.open_streams == 0 and len(rig.model.calls) == 1  # the model step is over
        assert await asyncio.wait_for(cancel(run_id), 3) == "cancelled"
        await asyncio.wait_for(reader, 2)
    finally:
        reader.cancel()
        await asyncio.gather(reader, return_exceptions=True)
        await stream.aclose()
    monkeypatch.setattr(AsyncSqliteSaver, "aput", aput)

    assert final(events)["finishReason"] == "cancelled" and final(events)["resumable"] is True
    assert rig.memory.turns == [["user", "查询本会话"]]
    async with open_run_store() as store:
        assert [r.run_id for r in await store.pending(ACTOR_ID, "s_kernel")] == [run_id]
        assert await store.finished(ACTOR_ID, "s_kernel") == []
    resumed = await rig.resume(run_id)
    assert final(resumed)["finishReason"] == "stop" and final(resumed)["answer"] == "回答"
    assert len(rig.model.calls) == 1 and final(resumed)["modelCalls"] == 1
    assert final(resumed)["usage"]["totalTokens"] == 5
    assert rig.memory.turns == [["user", "查询本会话"], ["assistant", "回答"]]


# ----- a model call is counted when its request leaves, not while it is being prepared -----


class Dispatching:
    """Shaped like the real adapters: it says when its request is about to leave."""
    signals_dispatch = True

    def __init__(self, turns):
        self.turns, self.calls, self.sent = list(turns), 0, 0

    async def complete(self, messages, tools=None, *, on_dispatch=None, **kwargs):
        from eval_harness import _chunk
        turn = self.turns[min(self.calls, len(self.turns) - 1)]
        self.calls += 1
        if turn == "unavailable":
            raise RuntimeError("openai SDK not installed")
        await on_dispatch()
        self.sent += 1
        for step in turn:
            if "raise" in step:
                raise RuntimeError(step["raise"])
            yield _chunk(step)


def model_stages(events):
    return [e.data["status"] for e in events if e.name == "stage" and e.data.get("phase") == "model"]


async def test_a_model_call_that_never_left_the_process_is_not_counted(monkeypatch):
    rig = install(chat_case([]), monkeypatch)
    client = Dispatching(["unavailable", [{"text": "回答"}, {"usage": [3, 2]}, {"finish": "stop"}]])
    monkeypatch.setattr(orchestrator, "build_client", lambda _cfg: client)
    failed = await rig.run()
    assert final(failed)["finishReason"] == "error" and client.sent == 0
    assert final(failed)["modelCalls"] == 0
    # The step still opens and closes in the stream; it just has no call to its name.
    assert model_stages(failed) == ["start", "failed"]

    resumed = await rig.resume(final(failed)["runId"])
    assert final(resumed)["finishReason"] == "stop"
    assert final(resumed)["modelCalls"] == 1 and client.sent == 1


async def test_a_model_call_that_left_and_then_failed_is_counted(monkeypatch):
    rig = install(chat_case([]), monkeypatch)
    client = Dispatching([[{"raise": "connection reset"}]])
    monkeypatch.setattr(orchestrator, "build_client", lambda _cfg: client)
    failed = await rig.run()
    assert final(failed)["finishReason"] == "error"
    assert final(failed)["modelCalls"] == 1 and client.sent == 1
    assert model_stages(failed) == ["start", "failed"]


# ----- one record per tool call: who holds it and how it ended -----


async def test_one_call_has_one_holder_and_one_outcome():
    async with open_run_store() as store:
        run = "u7:run_ledger"
        assert (await store.call_state(run, "c1", "fp", "a1"))["status"] == "absent"
        assert (await store.claim_call(run, "c1", "fp", "a1"))["status"] == "claimed"
        assert (await store.claim_call(run, "c1", "fp", "a1"))["status"] == "inflight"
        # A later attempt cannot tell whether the request was sent, so it is not "never tried".
        assert (await store.claim_call(run, "c1", "fp", "a2"))["status"] == "unknown"
        assert (await store.claim_call(run, "c1", "fp-other", "a2"))["status"] == "conflict"
        # Only a caller allowed to repeat it takes it over; the attempt that lost it records nothing.
        assert (await store.claim_call(run, "c1", "fp", "a2", takeover=True))["status"] == "claimed"
        assert await store.finish_call(run, "c1", "fp", "a1", {"n": 1}) is False
        assert await store.finish_call(run, "c1", "fp", "a2", {"n": 2}) is True
        assert await store.finish_call(run, "c1", "fp", "a2", {"n": 3}) is False
        state = await store.call_state(run, "c1", "fp", "a3")
        assert state == {"status": "committed", "attemptId": "a2", "result": {"n": 2}}
        # A recorded result is never replaced, and never handed to a different call or run.
        assert (await store.claim_call(run, "c1", "fp", "a3", takeover=True))["result"] == {"n": 2}
        assert "result" not in await store.call_state(run, "c1", "fp-other", "a3")
        assert (await store.call_state("u8:run_ledger", "c1", "fp", "a3"))["status"] == "absent"

        assert (await store.claim_call(run, "c2", "fp", "a1"))["status"] == "claimed"
        assert await store.fail_call(run, "c2", "fp", "a1") is True
        assert (await store.call_state(run, "c2", "fp", "a2"))["status"] == "failed"

        await store.forget(run)
        assert (await store.call_state(run, "c1", "fp", "a3"))["status"] == "absent"


async def test_a_withdrawn_call_cannot_be_brought_back_by_a_late_result():
    async with open_run_store() as store:
        run = "u7:run_withdrawn"
        await store.claim_call(run, "c1", "fp", "a1")
        assert await store.reject_call(run, "c1", "a1") is True
        assert await store.finish_call(run, "c1", "fp", "a1", {"n": 1}) is False
        assert await store.call_state(run, "c1", "fp", "a2") == {"status": "rejected", "attemptId": "a1"}
        # The attempt that withdrew it does not get it back; a later, authorized one may run it afresh.
        assert (await store.claim_call(run, "c1", "fp", "a1", takeover=True))["status"] == "rejected"
        assert (await store.claim_call(run, "c1", "fp", "a2", takeover=True))["status"] == "claimed"

        # A result that landed just before the final refusal is withdrawn with it.
        await store.claim_call(run, "c2", "fp", "a1")
        assert await store.finish_call(run, "c2", "fp", "a1", {"n": 2}) is True
        assert await store.reject_call(run, "c2", "a1") is True
        assert "result" not in await store.call_state(run, "c2", "fp", "a2")

        # Another attempt's refusal does not undo this one's result.
        await store.claim_call(run, "c3", "fp", "a1")
        await store.finish_call(run, "c3", "fp", "a1", {"n": 3})
        assert await store.reject_call(run, "c3", "a2") is False
        assert (await store.call_state(run, "c3", "fp", "a2"))["result"] == {"n": 3}


async def test_two_workers_claiming_the_same_call_have_one_winner():
    async with open_run_store() as one, open_run_store() as two:
        answers = await asyncio.gather(one.claim_call("u7:run_race", "c1", "fp", "a1"),
                                       two.claim_call("u7:run_race", "c1", "fp", "a2"))
    assert sorted(answer["status"] for answer in answers) == ["claimed", "unknown"]


async def test_a_call_whose_outcome_was_never_recorded_is_not_sent_again_without_leave(monkeypatch):
    rig = install(tool_case([[TOOL_CALL, {"finish": "tool_calls"}], [{"text": "读到了。"}, {"finish": "stop"}]]),
                  monkeypatch)
    rig.java.fail_tools = {"get_recent_messages"}
    failed = await rig.run()
    assert final(failed)["finishReason"] == "error" and rig.java.requests == []
    rig.java.fail_tools = set()

    monkeypatch.setattr(orchestrator, "replay_authorizer", None)
    refused = await rig.resume(final(failed)["runId"])
    assert final(refused)["finishReason"] == "error"
    assert rig.java.requests == [] and len(rig.model.calls) == 1

    asked = []

    async def allow(request, kind, call):
        asked.append((kind, call["id"]))
        return True

    monkeypatch.setattr(orchestrator, "replay_authorizer", allow)
    resumed = await rig.resume(final(failed)["runId"])
    assert final(resumed)["finishReason"] == "stop"
    assert [r["tool"] for r in rig.java.requests] == ["get_recent_messages"]
    assert asked == [("tool", "call_a")]


async def test_a_result_read_under_one_authorization_is_not_reused_under_another(monkeypatch):
    calls = [{"tool": {"id": "call_a", "name": "get_recent_messages", "arguments": {"chat_id": 5}}},
             {"tool": {"id": "call_b", "name": "get_chat_profile", "arguments": {"chat_id": 5}}}]
    rig = install(chat_case([[*calls, {"finish": "tool_calls"}], [{"text": "回答"}, {"finish": "stop"}]],
        java={"get_recent_messages": {"json": {"messages": ["周五前交付"]}},
              "get_chat_profile": {"json": {"name": "项目群"}}}), monkeypatch)
    scope = {"key": "scope-1"}
    monkeypatch.setattr(orchestrator, "authorization_binding", lambda request: scope["key"])
    rig.java.fail_tools = {"get_chat_profile"}
    failed = await rig.run()
    assert [r["tool"] for r in rig.java.requests] == ["get_recent_messages"]
    rig.java.fail_tools = set()
    scope["key"] = "scope-2"

    # Without leave to repeat it, the earlier read is neither handed over nor sent again.
    monkeypatch.setattr(orchestrator, "replay_authorizer", None)
    refused = await rig.resume(final(failed)["runId"])
    assert final(refused)["finishReason"] == "error"
    assert [r["tool"] for r in rig.java.requests] == ["get_recent_messages"]

    async def allow(request, kind, call):
        return True

    monkeypatch.setattr(orchestrator, "replay_authorizer", allow)
    resumed = await rig.resume(final(failed)["runId"])
    assert final(resumed)["finishReason"] == "stop"
    assert sorted(r["tool"] for r in rig.java.requests) == [
        "get_chat_profile", "get_recent_messages", "get_recent_messages"]
    assert not [e for e in resumed if e.name == "tool_result" and e.data.get("reused")]
    assert final(resumed)["reusedToolCallCount"] == 0


async def test_analysts_that_answered_under_one_authorization_answer_again_under_another(monkeypatch):
    turns = [analyst("a"), analyst("b"), analyst("c"), [{"finish": "timeout"}],
             analyst("a2"), analyst("b2"), analyst("c2"), [{"text": "合并"}, {"finish": "stop"}]]
    rig = install(collab_case(turns), monkeypatch)
    scope = {"key": "scope-1"}
    monkeypatch.setattr(orchestrator, "authorization_binding", lambda request: scope["key"])
    failed = await rig.run()
    assert final(failed)["finishReason"] != "stop" and len(rig.model.calls) == 4
    scope["key"] = "scope-2"
    resumed = await rig.resume(final(failed)["runId"])
    assert final(resumed)["finishReason"] == "stop"
    assert len(rig.model.calls) == 8 and final(resumed)["modelCalls"] == 8
    assert not [e for e in resumed if e.name == "agent_output" and e.data.get("reused")]


def test_a_tool_result_belongs_to_the_node_that_asked_for_it(monkeypatch):
    monkeypatch.setattr(orchestrator, "authorization_binding", None)
    request = install(chat_case([]), monkeypatch).request()
    call = {"id": "call_a", "name": "get_recent_messages", "arguments": {"chat_id": 5}}
    first = orchestrator._tool_fingerprint(request, call, "model-1")
    assert first == orchestrator._tool_fingerprint(request, dict(call), "model-1")
    assert first != orchestrator._tool_fingerprint(request, call, "model-2")
    monkeypatch.setattr(orchestrator, "authorization_binding", lambda _request: "scope-1")
    assert first != orchestrator._tool_fingerprint(request, call, "model-1")


async def test_the_executor_is_given_the_runs_own_call_id(monkeypatch):
    rig = install(tool_case([[TOOL_CALL, {"finish": "tool_calls"}], [{"text": "读到了。"}, {"finish": "stop"}]]),
                  monkeypatch)
    seen = []
    execute = mcp_tools.ToolExecutor.execute

    async def keyed(self, tool_name, args, *, call_id=None):
        seen.append(call_id)
        return await execute(self, tool_name, args)

    monkeypatch.setattr(mcp_tools.ToolExecutor, "execute", keyed)
    events = await rig.run()
    assert final(events)["finishReason"] == "stop" and seen == ["call_a"]
    reused = [e.data for e in events if e.name == "tool_result"]
    assert reused[0]["callId"] == "call_a"


async def test_a_call_id_repeated_by_a_later_step_is_another_call(monkeypatch):
    """Providers that issue no ids are numbered per step, so a later step repeats an earlier id."""
    same = {"tool": {"id": "call_0", "name": "get_recent_messages", "arguments": {"chat_id": 5, "limit": 20}}}
    rig = install(tool_case([[same, {"finish": "tool_calls"}], [same, {"finish": "tool_calls"}],
                             [{"text": "读了两次。"}, {"finish": "stop"}]]), monkeypatch)
    given = []
    execute = mcp_tools.ToolExecutor.execute

    async def keyed(self, tool_name, args, *, call_id=None):
        given.append(call_id)
        return await execute(self, tool_name, args)

    monkeypatch.setattr(mcp_tools.ToolExecutor, "execute", keyed)
    events = await rig.run()
    assert final(events)["finishReason"] == "stop"
    # The second read is a second read: sent again, not answered from the first one's record.
    assert [r["tool"] for r in rig.java.requests] == ["get_recent_messages"] * 2
    assert not [e for e in events if e.name == "tool_result" and e.data.get("reused")]
    asked = [e.data for e in events if e.name == "tool_call"]
    answered = [e.data for e in events if e.name == "tool_result"]
    ids = [a["callId"] for a in asked]
    assert ids[0] == "call_0" and len(set(ids)) == 2
    assert given == ids == [a["callId"] for a in answered] == [c["callId"] for c in final(events)["toolCalls"]]
    assert [a["nodeId"] for a in answered] == [a["nodeId"] for a in asked] and len({a["nodeId"] for a in asked}) == 2
    assert [a["parentIds"] for a in asked] == [["model-1"], ["model-2"]]
    assert {a["agentId"] for a in asked + answered} == {"assistant"}
    # Each model step hangs off the calls it was given, not off the start of the turn.
    starts = {e.data["nodeId"]: e.data["parentIds"] for e in events
              if e.name == "stage" and e.data.get("phase") == "model" and e.data.get("status") == "start"}
    assert starts == {"model-1": ["context"], "model-2": [asked[0]["nodeId"]], "model-3": [asked[1]["nodeId"]]}
    # The model is shown each result under the id of the request it answers.
    shown = rig.model.calls[2]["messages"]
    assert [c["id"] for m in shown if m["role"] == "assistant" for c in m.get("tool_calls", [])] == ids
    assert [m["tool_call_id"] for m in shown if m["role"] == "tool"] == ids


def test_call_ids_are_made_unique_without_touching_the_ones_that_already_are():
    calls = [{"id": "call_0"}, {"id": "toolu_x"}, {"id": ""}, {"id": "call_0"}]
    orchestrator._own_call_ids(calls, {"call_0", "call_0_s2"}, 2)
    ids = [c["id"] for c in calls]
    assert ids[1] == "toolu_x" and len(set(ids) | {"call_0", "call_0_s2"}) == 6
    assert all(part.replace("_", "").isalnum() for part in ids)


# ----- several parties decide reuse together -----


async def test_every_installed_party_must_allow_and_none_replaces_another(monkeypatch):
    for slot in ("reuse_authorizer", "replay_authorizer", "authorization_binding"):
        monkeypatch.setattr(orchestrator, slot, None)

    def party(answer):
        async def authorize(request, kind, call):
            if isinstance(answer, Exception):
                raise answer
            return answer
        return authorize

    orchestrator.install_authorizers(party(True), party(True), binding=lambda request: "scope-1")
    assert await orchestrator.reuse_authorizer(None, "tool", {}) is True
    assert orchestrator.authorization_binding(None) == ["scope-1"]

    orchestrator.install_authorizers(party(False), binding=lambda request: "policy-3")
    assert await orchestrator.reuse_authorizer(None, "tool", {}) is False
    # Only the slot it was installed into is narrowed.
    assert await orchestrator.replay_authorizer(None, "tool", {}) is True
    assert orchestrator.authorization_binding(None) == ["scope-1", "policy-3"]

    # A policy that cannot answer denies, and "yes" is not a permission.
    orchestrator.install_authorizers(replay=party(RuntimeError("policy service down")))
    assert await orchestrator.replay_authorizer(None, "tool", {}) is False
    monkeypatch.setattr(orchestrator, "reuse_authorizer", None)
    orchestrator.install_authorizers(party("yes"))
    assert await orchestrator.reuse_authorizer(None, "tool", {}) is False


async def test_the_service_starts_with_the_scope_policy_installed_once(monkeypatch):
    import app
    from eval_harness import eval_scope
    for slot in ("reuse_authorizer", "replay_authorizer", "authorization_binding"):
        monkeypatch.setattr(orchestrator, slot, None)
    app.install_run_policies()
    app.install_run_policies()
    assert len(orchestrator.reuse_authorizer.parties) == len(orchestrator.replay_authorizer.parties) == 1
    rig = install(chat_case([]), monkeypatch)  # restores the rig's own fixtures afterwards
    for slot in ("reuse_authorizer", "replay_authorizer", "authorization_binding"):
        monkeypatch.setattr(orchestrator, slot, None)
    app.install_run_policies()
    request = rig.request()
    # With a live grant the stored result may be used; without any scope it may not.
    assert await orchestrator.reuse_authorizer(request, "resume", {"runId": request.runId}) is True
    assert await orchestrator.reuse_authorizer(request, "resume", {"runId": "another-run"}) is False
    assert await orchestrator.replay_authorizer(request.model_copy(update={"scope": None}), "tool",
                                                {"id": "c", "name": "get_recent_messages", "arguments": {}}) is False
    # The binding does not change when a resume is issued a new grant for the same range.
    again = eval_scope(ACTOR_ID, request.session.sessionId, request.runId)
    again = again.model_copy(update={"grantId": "another-grant", "authorizationVersion": 9})
    assert orchestrator.authorization_binding(request) == orchestrator.authorization_binding(
        request.model_copy(update={"scope": again})) == [[request.scope.scopeKey, 1]]


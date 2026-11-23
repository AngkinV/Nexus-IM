from __future__ import annotations

import asyncio

import pytest

from app import orchestrator
from app.checkpoint import open_run_store, thread_id_for
from app.config import get_settings
from app.schemas import Actor
from eval_harness import install


@pytest.fixture(autouse=True)
def isolated_checkpoints(tmp_path, monkeypatch):
    monkeypatch.setattr(get_settings(), "checkpoint_path", str(tmp_path / "runs.sqlite"))


async def test_expired_runs_remove_checkpoint_and_index():
    async with open_run_store() as store:
        thread_id = thread_id_for(7, "expired_run_001")
        await store.register(thread_id, "expired_run_001", 7, "s1", "ASSISTANT_CHAT")
        await store.saver.conn.execute("UPDATE agent_runs SET updated_at = 0 WHERE thread_id = ?", (thread_id,))
        await store.saver.conn.commit()
        assert await store.purge_expired(24 * 60 * 60) == 1
        assert await store.get(thread_id) is None
        assert await store.pending(7, "s1") == []


async def test_expired_outcomes_are_removed_with_the_same_deadline():
    async with open_run_store() as store:
        thread_id = thread_id_for(7, "finished_run_001")
        await store.save_result(thread_id, 7, "s1", "ASSISTANT_CHAT", {"answer": "旧回答"})
        assert (await store.result(thread_id, "s1"))["result"] == {"answer": "旧回答"}
        assert await store.result(thread_id, "s2") is None
        await store.saver.conn.execute("UPDATE agent_run_results SET finished_at = 0")
        await store.saver.conn.commit()
        await store.purge_expired(24 * 60 * 60)
        assert await store.result(thread_id, "s1") is None


async def test_active_session_cannot_be_purged_silently(monkeypatch):
    from app import routes
    from app.schemas import RunQuery

    case = {"id": "purge_active", "operation": "ASSISTANT_CHAT", "input": {"text": "hi"},
            "model": [[{"text": "part"}, {"wait": "never"}, {"finish": "stop"}]]}
    rig = install(case, monkeypatch)
    stream = orchestrator.run_agent(rig.request())
    try:
        await anext(stream)
        while (await anext(stream)).name != "delta":
            pass
        response = await routes.purge_runs(
            RunQuery(actor=Actor(userId=7), sessionId=rig.request().session.sessionId),
            ctx={"actorUserId": 7, "traceId": "tr", "provider": None},
        )
        assert response.status_code == 409
    finally:
        await stream.aclose()


async def test_same_run_id_in_different_users_never_suppresses_memory(monkeypatch):
    case = {"id": "owners", "operation": "ASSISTANT_CHAT", "input": {"text": "hi"},
            "model": [[{"text": "A"}, {"finish": "stop"}], [{"text": "B"}, {"finish": "stop"}]]}
    rig = install(case, monkeypatch)
    for actor in (7, 8):
        request = rig.request(actor=Actor(userId=actor), runId="same_run_001")
        await rig.run(request)
    assert [owner[0] for owner in rig.memory.owners] == [7, 7, 8, 8]


async def test_slow_consumer_buffer_is_bounded(monkeypatch):
    case = {"id": "slow_reader", "operation": "ASSISTANT_CHAT", "input": {"text": "hi"},
            "model": [[*[{"text": str(i)} for i in range(100)], {"finish": "stop"}]]}
    rig = install(case, monkeypatch)
    monkeypatch.setattr(get_settings(), "stream_buffer_events", 3)
    stream = orchestrator.run_agent(rig.request())
    try:
        await anext(stream)
        while (await anext(stream)).name != "delta":
            pass
        assert rig.model.open_streams == 1
    finally:
        await asyncio.wait_for(stream.aclose(), 2)
    assert rig.model.open_streams == 0
    assert not orchestrator.active_thread_ids()


async def test_leaving_the_store_waits_for_a_checkpoint_write_still_in_flight():
    """A cancelled run leaves while the graph is still saving that step's pending writes.

    The connection used to be closed under such a write: its commit then failed on a
    closed database, and its transaction stayed behind holding the file's write lock,
    so resuming the run failed with "database is locked".
    """
    thread_id = thread_id_for(7, "in_flight")
    written, finish = asyncio.Event(), asyncio.Event()
    async with open_run_store() as store:
        await store.register(thread_id, "in_flight", 7, "s1", "ASSISTANT_CHAT")
        saver = store.saver

        async def save_pending_writes():
            # The shape of the saver's own writes: statement, then commit, under its lock.
            async with saver.lock, saver.conn.cursor() as cursor:
                await cursor.executemany("INSERT OR REPLACE INTO agent_call_commits VALUES (?, ?, ?, ?, ?)",
                                         [(thread_id, "model", "c1", "f", "{}")])
                written.set()
                await finish.wait()
                await saver.conn.commit()

        writer = asyncio.create_task(save_pending_writes())
        await written.wait()
        asyncio.get_running_loop().call_later(0.05, finish.set)
        # Leaving now: the write is between its statement and its commit.

    await asyncio.wait_for(writer, 2)  # it was allowed to finish; nothing closed under it
    async with open_run_store() as store:
        await asyncio.wait_for(store.touch(thread_id), 2)  # far below the connection's busy timeout
        assert await store.committed(thread_id, "model", "c1", "f") == {}


async def test_a_write_abandoned_before_its_commit_does_not_block_the_next_run():
    """A write can be cancelled between its statement and its commit.

    Closing the connection does not end that transaction while one of its cursors is
    still open (referenced from a cancelled coroutine's frames, say): the handle
    lingers with the write lock, and the next run's first write waits on it until it
    times out.
    """
    thread_id = thread_id_for(7, "abandoned")
    async with open_run_store() as store:
        await store.register(thread_id, "abandoned", 7, "s1", "ASSISTANT_CHAT")
        await store.register(thread_id_for(7, "other"), "other", 7, "s1", "ASSISTANT_CHAT")
        # A cursor that is still mid-read when the connection closes.
        held = await store.saver.conn.execute("SELECT * FROM agent_runs")
        await held.fetchone()
        # The statement ran; the coroutine that would have committed it was cancelled.
        await store.saver.conn.execute(
            "INSERT OR REPLACE INTO agent_call_commits VALUES (?, 'model', 'c1', 'f', '{}')", (thread_id,))
        assert store.saver.conn.in_transaction

    async with open_run_store() as store:
        await asyncio.wait_for(store.touch(thread_id), 2)  # far below the connection's busy timeout
        assert (await store.get(thread_id)).session_id == "s1"
        # What was never committed is not there.
        assert await store.committed(thread_id, "model", "c1", "f") is None
    del held

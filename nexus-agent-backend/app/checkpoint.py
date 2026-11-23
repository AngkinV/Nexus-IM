"""Durable run checkpoints: lets an interrupted turn resume without redoing finished steps.

A thread is one run (one user turn), not a conversation. Conversation memory
stays in Redis / Chroma / Java; nothing here replaces it. Checkpoints are kept
only for unfinished runs; a finished run leaves just its outcome behind. Both
expire, because they hold chat content.

SQLite on the agent data volume: single agent instance only.
"""
from __future__ import annotations

import contextlib
import dataclasses
import json
import logging
import time
from pathlib import Path
from typing import AsyncIterator

from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from .config import get_settings

log = logging.getLogger(__name__)

_PURGE_INTERVAL_SEC = 600
_last_purge = 0.0


def thread_id_for(actor_user_id: int, run_id: str) -> str:
    return f"u{int(actor_user_id)}:{run_id}"


@dataclasses.dataclass(frozen=True)
class RunRecord:
    thread_id: str
    run_id: str
    actor_user_id: int
    session_id: str
    operation_type: str
    created_at: int
    updated_at: int


class RunStore:
    """The LangGraph saver plus an index of who owns each unfinished run."""

    def __init__(self, saver: AsyncSqliteSaver) -> None:
        self.saver = saver

    async def _setup(self) -> None:
        await self.saver.setup()
        async with self.saver.lock:
            await self.saver.conn.execute(
                """
                CREATE TABLE IF NOT EXISTS agent_runs (
                    thread_id TEXT PRIMARY KEY,
                    run_id TEXT NOT NULL,
                    actor_user_id INTEGER NOT NULL,
                    session_id TEXT NOT NULL,
                    operation_type TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """
            )
            await self.saver.conn.execute(
                "CREATE INDEX IF NOT EXISTS agent_runs_owner ON agent_runs (actor_user_id, session_id)"
            )
            # Outcome of runs that ended, kept so a client that missed `done` can fetch it.
            await self.saver.conn.execute(
                """
                CREATE TABLE IF NOT EXISTS agent_run_results (
                    thread_id TEXT PRIMARY KEY,
                    actor_user_id INTEGER NOT NULL,
                    session_id TEXT NOT NULL,
                    operation_type TEXT NOT NULL,
                    result_json TEXT NOT NULL,
                    finished_at INTEGER NOT NULL
                )
                """
            )
            await self.saver.conn.execute(
                "CREATE INDEX IF NOT EXISTS agent_run_results_owner"
                " ON agent_run_results (actor_user_id, session_id)"
            )
            await self.saver.conn.execute(
                "CREATE TABLE IF NOT EXISTS agent_call_commits ("
                "thread_id TEXT NOT NULL, kind TEXT NOT NULL, call_id TEXT NOT NULL, "
                "fingerprint TEXT NOT NULL, result_json TEXT NOT NULL, "
                "PRIMARY KEY(thread_id, kind, call_id))"
            )
            # One row per tool call: who holds it and how it ended.
            await self.saver.conn.execute(
                "CREATE TABLE IF NOT EXISTS agent_call_ledger ("
                "thread_id TEXT NOT NULL, call_id TEXT NOT NULL, fingerprint TEXT NOT NULL, "
                "attempt_id TEXT NOT NULL, status TEXT NOT NULL, result_json TEXT, "
                "PRIMARY KEY(thread_id, call_id))"
            )
            await self.saver.conn.commit()

    async def commit(self, thread_id: str, kind: str, call_id: str,
                     fingerprint: str, result: dict) -> None:
        """First durable result wins; a changed fingerprint never replaces it.

        Callers must reauthorize before retrieving or exposing a cached result.
        This is a local receipt, not a remote exactly-once guarantee.
        """
        async with self.saver.lock:
            await self.saver.conn.execute(
                "INSERT OR IGNORE INTO agent_call_commits VALUES (?, ?, ?, ?, ?)",
                (thread_id, kind, call_id, fingerprint, json.dumps(result, ensure_ascii=False)),
            )
            await self.saver.conn.commit()

    async def committed(self, thread_id: str, kind: str, call_id: str,
                        fingerprint: str) -> dict | None:
        async with self.saver.lock:
            cursor = await self.saver.conn.execute(
                "SELECT result_json FROM agent_call_commits "
                "WHERE thread_id = ? AND kind = ? AND call_id = ? AND fingerprint = ?",
                (thread_id, kind, call_id, fingerprint),
            )
            row = await cursor.fetchone()
        return json.loads(row[0]) if row else None

    @staticmethod
    def _call_view(row, fingerprint: str, attempt_id: str) -> dict:
        """What a caller may know about a call record. Its result is only for the same call."""
        if row is None:
            return {"status": "absent"}
        recorded, holder, status, result = row
        if recorded != fingerprint:
            return {"status": "conflict", "attemptId": holder}
        if status == "claimed":
            # Another attempt's claim says the call may have been sent; it does not say it was.
            return {"status": "inflight" if holder == attempt_id else "unknown", "attemptId": holder}
        view = {"status": status, "attemptId": holder}
        if status == "committed":
            view["result"] = json.loads(result)
        return view

    async def _call_row(self, thread_id: str, call_id: str):
        cursor = await self.saver.conn.execute(
            "SELECT fingerprint, attempt_id, status, result_json FROM agent_call_ledger "
            "WHERE thread_id = ? AND call_id = ?", (thread_id, call_id))
        return await cursor.fetchone()

    async def call_state(self, thread_id: str, call_id: str, fingerprint: str, attempt_id: str) -> dict:
        """absent | inflight | unknown | committed | failed | rejected | conflict.

        `absent` means no attempt ever claimed the call. `unknown` means an earlier
        attempt claimed it and never recorded how it ended.
        """
        async with self.saver.lock:
            row = await self._call_row(thread_id, call_id)
            await self.saver.conn.commit()
        return self._call_view(row, fingerprint, attempt_id)

    async def claim_call(self, thread_id: str, call_id: str, fingerprint: str, attempt_id: str,
                         *, takeover: bool = False) -> dict:
        """Take the call for this attempt: `claimed`, or the state that stands in the way.

        One attempt holds a call at a time. `takeover` is for a caller that has been
        allowed to repeat it: it replaces an earlier attempt's unfinished, failed,
        withdrawn or differently-authorized record. A recorded result is never
        replaced for the same call, and an attempt never retakes what it withdrew.
        """
        async with self.saver.lock:
            try:
                cursor = await self.saver.conn.execute(
                    "INSERT OR IGNORE INTO agent_call_ledger VALUES (?, ?, ?, ?, 'claimed', NULL)",
                    (thread_id, call_id, fingerprint, attempt_id))
                if cursor.rowcount == 1:
                    return {"status": "claimed"}
                row = await self._call_row(thread_id, call_id)
                view = self._call_view(row, fingerprint, attempt_id)
                if (not takeover or view["status"] in ("committed", "inflight")
                        or (view["status"] == "rejected" and view["attemptId"] == attempt_id)):
                    return view
                cursor = await self.saver.conn.execute(
                    "UPDATE agent_call_ledger SET fingerprint = ?, attempt_id = ?, status = 'claimed', "
                    "result_json = NULL WHERE thread_id = ? AND call_id = ? AND attempt_id = ? AND status = ?",
                    (fingerprint, attempt_id, thread_id, call_id, row[1], row[2]))
                return {"status": "claimed"} if cursor.rowcount == 1 else view
            finally:
                await self.saver.conn.commit()

    async def _close_call(self, thread_id: str, call_id: str, fingerprint: str, attempt_id: str,
                          status: str, result: dict | None) -> bool:
        async with self.saver.lock:
            cursor = await self.saver.conn.execute(
                "UPDATE agent_call_ledger SET status = ?, result_json = ? WHERE thread_id = ? AND call_id = ? "
                "AND fingerprint = ? AND attempt_id = ? AND status = 'claimed'",
                (status, None if result is None else json.dumps(result, ensure_ascii=False),
                 thread_id, call_id, fingerprint, attempt_id))
            await self.saver.conn.commit()
            return cursor.rowcount == 1

    async def finish_call(self, thread_id: str, call_id: str, fingerprint: str, attempt_id: str,
                          result: dict) -> bool:
        """Record the result. False when this attempt no longer holds the call."""
        return await self._close_call(thread_id, call_id, fingerprint, attempt_id, "committed", result)

    async def fail_call(self, thread_id: str, call_id: str, fingerprint: str, attempt_id: str) -> bool:
        """Record that the call was answered with a refusal, so it is known not to have succeeded."""
        return await self._close_call(thread_id, call_id, fingerprint, attempt_id, "failed", None)

    async def reject_call(self, thread_id: str, call_id: str, attempt_id: str) -> bool:
        """Withdraw this attempt's call, with any result it recorded. A late result cannot undo it."""
        async with self.saver.lock:
            cursor = await self.saver.conn.execute(
                "UPDATE agent_call_ledger SET status = 'rejected', result_json = NULL WHERE thread_id = ? "
                "AND call_id = ? AND attempt_id = ? AND status IN ('claimed', 'committed')",
                (thread_id, call_id, attempt_id))
            await self.saver.conn.commit()
            return cursor.rowcount == 1

    async def receipt_count(self, thread_id: str, kind: str) -> int:
        async with self.saver.lock:
            cursor = await self.saver.conn.execute(
                "SELECT COUNT(*) FROM agent_call_commits WHERE thread_id = ? AND kind = ?",
                (thread_id, kind),
            )
            return (await cursor.fetchone())[0]

    async def register(self, thread_id: str, run_id: str, actor_user_id: int,
                       session_id: str, operation_type: str) -> bool:
        """Claim a run id. False when it is already taken."""
        now = int(time.time())
        async with self.saver.lock:
            existing = await self.saver.conn.execute(
                "SELECT 1 FROM agent_run_results WHERE thread_id = ?", (thread_id,))
            if await existing.fetchone():
                return False
            cursor = await self.saver.conn.execute(
                "INSERT OR IGNORE INTO agent_runs VALUES (?, ?, ?, ?, ?, ?, ?)",
                (thread_id, run_id, int(actor_user_id), session_id, operation_type, now, now),
            )
            await self.saver.conn.commit()
            return cursor.rowcount == 1

    async def touch(self, thread_id: str) -> None:
        async with self.saver.lock:
            await self.saver.conn.execute(
                "UPDATE agent_runs SET updated_at = ? WHERE thread_id = ?", (int(time.time()), thread_id))
            await self.saver.conn.commit()

    async def get(self, thread_id: str) -> RunRecord | None:
        async with self.saver.lock:
            cursor = await self.saver.conn.execute(
                "SELECT * FROM agent_runs WHERE thread_id = ?", (thread_id,))
            row = await cursor.fetchone()
        return RunRecord(*row) if row else None

    async def pending(self, actor_user_id: int, session_id: str) -> list[RunRecord]:
        async with self.saver.lock:
            cursor = await self.saver.conn.execute(
                "SELECT * FROM agent_runs WHERE actor_user_id = ? AND session_id = ? ORDER BY updated_at DESC",
                (int(actor_user_id), session_id),
            )
            rows = await cursor.fetchall()
        return [RunRecord(*row) for row in rows]

    async def forget(self, thread_id: str) -> None:
        await self.saver.adelete_thread(thread_id)
        async with self.saver.lock:
            await self.saver.conn.execute("DELETE FROM agent_runs WHERE thread_id = ?", (thread_id,))
            await self.saver.conn.execute("DELETE FROM agent_call_commits WHERE thread_id = ?", (thread_id,))
            await self.saver.conn.execute("DELETE FROM agent_call_ledger WHERE thread_id = ?", (thread_id,))
            await self.saver.conn.commit()

    async def save_result(self, thread_id: str, actor_user_id: int, session_id: str,
                          operation_type: str, result: dict) -> None:
        async with self.saver.lock:
            await self.saver.conn.execute(
                "INSERT OR REPLACE INTO agent_run_results VALUES (?, ?, ?, ?, ?, ?)",
                (thread_id, int(actor_user_id), session_id, operation_type,
                 json.dumps(result, ensure_ascii=False), int(time.time())),
            )
            await self.saver.conn.commit()

    async def result(self, thread_id: str, session_id: str) -> dict | None:
        """The stored outcome of a finished run, or None. The thread id already binds the user."""
        async with self.saver.lock:
            cursor = await self.saver.conn.execute(
                "SELECT operation_type, result_json FROM agent_run_results WHERE thread_id = ? AND session_id = ?",
                (thread_id, session_id),
            )
            row = await cursor.fetchone()
        return {"operationType": row[0], "result": json.loads(row[1])} if row else None

    async def finished(self, actor_user_id: int, session_id: str) -> list[str]:
        """Run ids of the session's finished runs whose outcome is still kept."""
        prefix = thread_id_for(actor_user_id, "")
        async with self.saver.lock:
            cursor = await self.saver.conn.execute(
                "SELECT thread_id FROM agent_run_results WHERE actor_user_id = ? AND session_id = ?"
                " ORDER BY finished_at DESC",
                (int(actor_user_id), session_id),
            )
            rows = await cursor.fetchall()
        return [row[0][len(prefix):] for row in rows if row[0].startswith(prefix)]

    async def purge_session(self, actor_user_id: int, session_id: str, *, keep: set[str]) -> int:
        """Drop the session's unfinished runs (except thread ids in `keep`) and its stored outcomes."""
        records = [r for r in await self.pending(actor_user_id, session_id) if r.thread_id not in keep]
        for record in records:
            await self.forget(record.thread_id)
        async with self.saver.lock:
            await self.saver.conn.execute(
                "DELETE FROM agent_run_results WHERE actor_user_id = ? AND session_id = ?",
                (int(actor_user_id), session_id),
            )
            await self.saver.conn.commit()
        return len(records)

    async def purge_expired(self, ttl_sec: int) -> int:
        cutoff = int(time.time()) - ttl_sec
        async with self.saver.lock:
            cursor = await self.saver.conn.execute(
                "SELECT thread_id FROM agent_runs WHERE updated_at < ?", (cutoff,))
            expired = [row[0] for row in await cursor.fetchall()]
        for thread_id in expired:
            await self.forget(thread_id)
        async with self.saver.lock:
            await self.saver.conn.execute("DELETE FROM agent_run_results WHERE finished_at < ?", (cutoff,))
            await self.saver.conn.commit()
        return len(expired)


@contextlib.asynccontextmanager
async def open_run_store() -> AsyncIterator[RunStore]:
    """One connection per run: the saver is bound to the event loop that created it."""
    global _last_purge
    settings = get_settings()
    path = Path(settings.checkpoint_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    async with AsyncSqliteSaver.from_conn_string(str(path)) as saver:
        # Per-run connections share this file; without WAL + busy timeout a
        # cancel/resume overlapping another run's write hits "database is locked".
        await saver.conn.execute("PRAGMA journal_mode=WAL")
        await saver.conn.execute("PRAGMA busy_timeout=5000")
        store = RunStore(saver)
        await store._setup()
        if time.monotonic() - _last_purge > _PURGE_INTERVAL_SEC:
            _last_purge = time.monotonic()
            try:
                purged = await store.purge_expired(settings.checkpoint_ttl_sec)
                if purged:
                    log.info("purged %d expired run checkpoint(s)", purged)
            except Exception:
                log.exception("purging expired run checkpoints failed")
        yield store

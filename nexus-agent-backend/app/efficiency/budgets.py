"""Run-local budgets; T1 owns model-start accounting, cancellation and persistence.

No graph, provider, settings, authorization or usage dependency lives here.
AdmissionController must be shared by all runs in one Python worker. It is not
an inter-process/distributed provider quota. T5 must put the entire run inside
run_scope(), and pass the same RunBudget to every model/tool/fact node.
"""
from __future__ import annotations

import asyncio
import inspect
import math
import time
from collections import deque
from dataclasses import dataclass, fields, replace
from datetime import datetime, timedelta, timezone
from typing import Any, AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager

from .cleanup import CleanupIncomplete, consume_exception, settle
from .context import ContextBudget, PreparedContext
from .results import BudgetLimited, LimitedResult, json_bytes, limit_result


def _positive_int(value: Any, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise ValueError(f"{name} must be a positive integer")
    return value


@dataclass(frozen=True)
class BudgetLimits:
    context_total_tokens: int = 12_000
    output_reserve_tokens: int = 1_024
    max_model_calls: int = 6
    run_timeout_seconds: float = 120.0
    cleanup_timeout_seconds: float = 1.0
    run_concurrency: int = 2
    user_concurrency: int = 2
    provider_concurrency: int = 2
    max_queue_size: int = 8
    max_tool_result_bytes: int = 65_536
    max_tool_result_tokens: int = 65_536

    def __post_init__(self) -> None:
        for field in fields(self):
            if field.name not in {"run_timeout_seconds", "cleanup_timeout_seconds"}:
                _positive_int(getattr(self, field.name), field.name)
        for seconds in (self.run_timeout_seconds, self.cleanup_timeout_seconds):
            if (isinstance(seconds, bool) or not isinstance(seconds, (int, float))
                    or not math.isfinite(seconds) or seconds <= 0):
                raise ValueError("deadline and cleanup grace must be finite and positive")
        if self.output_reserve_tokens >= self.context_total_tokens:
            raise ValueError("output reserve must leave a positive input budget")

    def tighten(self, **requested: Any) -> BudgetLimits:
        """Only a server policy instance can create a tighter request policy."""
        names = {field.name for field in fields(self)}
        if set(requested) - names:
            raise ValueError("unknown budget field")
        candidate = replace(self, **requested)
        if any(getattr(candidate, name) > getattr(self, name) for name in requested):
            raise ValueError("request budgets cannot expand the server policy")
        return candidate


@dataclass(frozen=True)
class EfficiencySnapshot:
    deadline_at: str
    remaining_seconds: float
    model_calls_started: int
    model_call_limit: int
    pending_call_reservations: int
    run_active: int
    user_active: int
    provider_active: int
    run_queued: int
    closed: bool
    cleanup_pending: int = 0
    quarantined_slots: int = 0
    cleanup_failed: int = 0
    # No input/output/total usage fields: only T1/provider can supply them.


@dataclass(eq=False)
class _Waiter:
    actor_id: int
    provider_id: str
    run_id: str
    limits: BudgetLimits
    future: asyncio.Future
    lease: _Lease | None = None


class _Lease:
    def __init__(self, controller: AdmissionController, waiter: _Waiter) -> None:
        self.controller = controller
        self.actor_id = waiter.actor_id
        self.provider_id = waiter.provider_id
        self.run_id = waiter.run_id
        self.released = False

    async def release(self) -> None:
        await self.controller._release(self)


class AdmissionController:
    """Atomic run/actor/provider acquisition with finite, work-conserving queues.

    Global actor/provider caps are server configuration, not model arguments.
    An individual request can further restrict its own acquisition, but cannot
    increase these shared caps. One blocked provider does not stall other keys.
    """
    def __init__(self, *, user_limit: int = 2, provider_limit: int = 2,
                 max_queue_size: int = 64) -> None:
        self.user_limit = _positive_int(user_limit, "user_limit")
        self.provider_limit = _positive_int(provider_limit, "provider_limit")
        self.max_queue_size = _positive_int(max_queue_size, "max_queue_size")
        self._lock = asyncio.Lock()
        self._runs: dict[tuple[int, str], int] = {}
        self._users: dict[int, int] = {}
        self._providers: dict[str, int] = {}
        self._queue: deque[_Waiter] = deque()
        self._closed = False

    def _can_grant(self, waiter: _Waiter) -> bool:
        return (
            self._runs.get((waiter.actor_id, waiter.run_id), 0) < waiter.limits.run_concurrency
            and self._users.get(waiter.actor_id, 0)
            < min(self.user_limit, waiter.limits.user_concurrency)
            and self._providers.get(waiter.provider_id, 0)
            < min(self.provider_limit, waiter.limits.provider_concurrency)
        )

    def _grant(self, waiter: _Waiter) -> _Lease:
        key = (waiter.actor_id, waiter.run_id)
        self._runs[key] = self._runs.get(key, 0) + 1
        self._users[waiter.actor_id] = self._users.get(waiter.actor_id, 0) + 1
        self._providers[waiter.provider_id] = self._providers.get(waiter.provider_id, 0) + 1
        waiter.lease = _Lease(self, waiter)
        return waiter.lease

    @staticmethod
    def _decrement(mapping: dict, key: Any) -> None:
        value = mapping.get(key, 0) - 1
        if value > 0:
            mapping[key] = value
        else:
            mapping.pop(key, None)

    def _release_locked(self, lease: _Lease) -> None:
        if lease.released:
            return
        lease.released = True
        self._decrement(self._runs, (lease.actor_id, lease.run_id))
        self._decrement(self._users, lease.actor_id)
        self._decrement(self._providers, lease.provider_id)

    def _drain_locked(self) -> None:
        for waiter in tuple(self._queue):
            if waiter.future.cancelled():
                self._queue.remove(waiter)
            elif self._can_grant(waiter):
                self._queue.remove(waiter)
                waiter.future.set_result(self._grant(waiter))

    async def acquire(self, actor_id: int, provider_id: str, run_id: str,
                      limits: BudgetLimits) -> _Lease:
        _positive_int(actor_id, "actor_id")
        if not provider_id or not run_id:
            raise ValueError("provider_id and run_id are required")
        future = asyncio.get_running_loop().create_future()
        waiter = _Waiter(actor_id, provider_id, run_id, limits, future)
        async with self._lock:
            if self._closed:
                raise BudgetLimited("admission-closed")
            self._drain_locked()
            # Do not bypass a queued waiter that is already runnable.
            if self._can_grant(waiter):
                return self._grant(waiter)
            run_queued = sum(w.actor_id == actor_id and w.run_id == run_id for w in self._queue)
            if len(self._queue) >= self.max_queue_size or run_queued >= limits.max_queue_size:
                raise BudgetLimited("queue-backpressure")
            self._queue.append(waiter)
        try:
            return await future
        except BaseException:
            async with self._lock:
                if waiter in self._queue:
                    self._queue.remove(waiter)
                if waiter.lease is not None:
                    self._release_locked(waiter.lease)
                self._drain_locked()
            raise

    async def _release(self, lease: _Lease) -> None:
        async with self._lock:
            self._release_locked(lease)
            self._drain_locked()

    def counts(self, actor_id: int, provider_id: str, run_id: str) -> tuple[int, int, int, int]:
        # A synchronous event-loop snapshot, not a reservation or permission.
        return (self._runs.get((actor_id, run_id), 0), self._users.get(actor_id, 0),
                self._providers.get(provider_id, 0),
                sum(w.actor_id == actor_id and w.run_id == run_id for w in self._queue))

    async def aclose(self) -> None:
        """Only the worker-level owner calls this, not an individual run."""
        async with self._lock:
            self._closed = True
            for waiter in self._queue:
                waiter.future.cancel()
            self._queue.clear()


@dataclass(eq=False)
class _StreamHandle:
    stream: AsyncIterator
    lease: _Lease
    cleanup_task: asyncio.Task | None = None
    closed: bool = False



class RunBudget:
    """Required T1 hooks make dependency gaps explicit rather than permissive.

    on_model_start(call_id) must synchronously increment get_model_calls() by
    exactly one at its REAL provider dispatch hook. The factory receives a
    required mark_started(actual_messages, actual_tools, output_limit) gate;
    iterator creation/first iteration is NOT assumed to be dispatch. Retries get
    a new T1 invocation identity. No callback is made for queued/rejected work.
    The getter must include persisted/restored starts, not just completed calls.
    """
    def __init__(self, *, limits: BudgetLimits, actor_id: int, provider_id: str,
                 run_id: str, deadline_at: datetime, cancel_event: asyncio.Event,
                 get_model_calls: Callable[[], int], on_model_start: Callable[[str], None],
                 admission: AdmissionController, context: ContextBudget | None = None) -> None:
        _positive_int(actor_id, "actor_id")
        if not provider_id or not run_id:
            raise ValueError("provider_id and run_id are required")
        if deadline_at.tzinfo is None or deadline_at.utcoffset() is None:
            raise ValueError("deadline_at must be timezone-aware and persisted by T1")
        if not isinstance(cancel_event, asyncio.Event):
            raise ValueError("T1 cancellation event is required")
        if not callable(get_model_calls) or not callable(on_model_start):
            raise ValueError("T1 model accounting hooks are required")
        if inspect.iscoroutinefunction(on_model_start):
            raise ValueError("on_model_start must synchronously record the provider start")
        self.limits, self.actor_id, self.provider_id, self.run_id = limits, actor_id, provider_id, run_id
        self.cancel_event, self.get_model_calls, self.on_model_start = cancel_event, get_model_calls, on_model_start
        self.admission = admission
        now = datetime.now(timezone.utc)
        # Tightened policy can shorten a restored deadline, never extend it.
        self.deadline_at = min(deadline_at.astimezone(timezone.utc),
                               now + timedelta(seconds=limits.run_timeout_seconds))
        self._deadline_mono = time.monotonic() + max(0.0, (self.deadline_at - now).total_seconds())
        self.context = context or ContextBudget(limits.context_total_tokens, limits.output_reserve_tokens)
        if (self.context.total_tokens > limits.context_total_tokens
                or self.context.output_reserve > limits.output_reserve_tokens):
            raise ValueError("context implementation cannot expand the run policy")
        self._lock = asyncio.Lock()
        self._reserved = 0
        self._started_ids: set[str] = set()
        self._last_reported = 0
        self._reported_calls()
        self._closed = False
        self._work: set[asyncio.Future] = set()
        self._streams: set[_StreamHandle] = set()
        self._leases: set[_Lease] = set()
        self._cleanup_tasks: set[asyncio.Future] = set()
        self._quarantined: set[_Lease] = set()
        self._terminal_cleanup_deadline: float | None = None

    @classmethod
    def new(cls, *, limits: BudgetLimits, **kwargs: Any) -> RunBudget:
        """For a new run only; resume must use __init__ with its old deadline."""
        if "deadline_at" in kwargs:
            raise ValueError("new() cannot be used with a restored deadline")
        return cls(limits=limits,
                   deadline_at=datetime.now(timezone.utc) + timedelta(seconds=limits.run_timeout_seconds),
                   **kwargs)

    @property
    def remaining_seconds(self) -> float:
        return max(0.0, self._deadline_mono - time.monotonic())

    def _reported_calls(self) -> int:
        value = self.get_model_calls()
        if isinstance(value, bool) or not isinstance(value, int) or value < self._last_reported:
            raise ValueError("T1 model-start counter must be nonnegative and monotonic")
        self._last_reported = value
        return value

    def _check_live(self) -> None:
        if self._closed or self.cancel_event.is_set():
            raise asyncio.CancelledError("T1 run cancellation or closed budget")
        if self.remaining_seconds <= 0:
            raise BudgetLimited("run-deadline")

    def _cleanup_remaining(self) -> float:
        terminal = self._closed or self.cancel_event.is_set() or self.remaining_seconds <= 0
        if terminal:
            if self._terminal_cleanup_deadline is None:
                self._terminal_cleanup_deadline = time.monotonic() + self.limits.cleanup_timeout_seconds
            return max(0.0, self._terminal_cleanup_deadline - time.monotonic())
        return min(self.remaining_seconds, self.limits.cleanup_timeout_seconds)

    def _observe_work(self, work: asyncio.Future, on_abandoned: Callable | None = None) -> None:
        def finished(task: asyncio.Future) -> None:
            self._work.discard(task)
            consume_exception(task)
            if on_abandoned is not None and not task.cancelled() and task.exception() is None:
                result = on_abandoned(task.result())
                if inspect.isawaitable(result):
                    cleanup = asyncio.ensure_future(result)
                    self._cleanup_tasks.add(cleanup)
                    cleanup.add_done_callback(consume_exception)
        work.add_done_callback(finished)

    async def guard(self, awaitable: Awaitable, *, on_abandoned: Callable | None = None) -> Any:
        """Deadline/cancellation bound work; cleanup has ONE terminal grace.

        Swallowed cancellation is reported as CleanupIncomplete, never waited
        forever or labelled closed. T1 must not confirm cancellation in that case.
        """
        try:
            self._check_live()
        except BaseException:
            if inspect.iscoroutine(awaitable):
                awaitable.close()
            elif isinstance(awaitable, asyncio.Future):
                self._work.add(awaitable)
                pending = await settle((awaitable,), self._cleanup_remaining(), cancel=True)
                if pending:
                    self._observe_work(awaitable, on_abandoned)
                    raise CleanupIncomplete(pending)
                self._work.discard(awaitable)
            raise
        work = asyncio.ensure_future(awaitable)
        cancelled = asyncio.create_task(self.cancel_event.wait())
        self._work.add(work)
        delivered = False
        try:
            done, _ = await asyncio.wait((work, cancelled), timeout=self.remaining_seconds,
                                         return_when=asyncio.FIRST_COMPLETED)
            self._check_live()
            if work in done:
                result = work.result()
                delivered = True
                return result
            if cancelled in done:
                raise asyncio.CancelledError("T1 run cancellation")
            raise BudgetLimited("run-deadline")
        finally:
            pending = await settle((work, cancelled), self._cleanup_remaining(), cancel=True)
            if work in pending:
                self._observe_work(work, on_abandoned if not delivered else None)
            else:
                self._work.discard(work)
                if (not delivered and on_abandoned is not None and not work.cancelled()
                        and work.exception() is None):
                    cleanup = on_abandoned(work.result())
                    if inspect.isawaitable(cleanup):
                        task = asyncio.ensure_future(cleanup)
                        self._cleanup_tasks.add(task)
                        task.add_done_callback(consume_exception)
                        await settle((task,), self._cleanup_remaining())
            if pending:
                raise CleanupIncomplete(pending)

    def _start_stream_cleanup(self, handle: _StreamHandle,
                              prerequisites: tuple[asyncio.Future, ...] = ()) -> asyncio.Task:
        if handle.cleanup_task is not None:
            return handle.cleanup_task

        async def finish() -> None:
            # This observer may remain pending, but it does NOT block a run's
            # deadline or free a still-live provider slot. T1 sees quarantine.
            if prerequisites:
                await asyncio.gather(*prerequisites, return_exceptions=True)
            await handle.stream.aclose()
            handle.closed = True
            await handle.lease.release()
            self._quarantined.discard(handle.lease)
            self._leases.discard(handle.lease)
            self._streams.discard(handle)

        handle.cleanup_task = asyncio.create_task(finish())
        self._cleanup_tasks.add(handle.cleanup_task)
        handle.cleanup_task.add_done_callback(consume_exception)
        return handle.cleanup_task

    async def _close_stream(self, handle: _StreamHandle,
                            prerequisites: tuple[asyncio.Future, ...] = ()) -> None:
        task = self._start_stream_cleanup(handle, prerequisites)
        pending = await settle((task,), self._cleanup_remaining())
        if pending or task.cancelled() or task.exception() is not None:
            self._quarantined.add(handle.lease)
            raise CleanupIncomplete((task,), reason="model-cleanup-incomplete")

    @asynccontextmanager
    async def run_scope(self):
        """Wrap ALL run work, including backpressure and non-model node time."""
        self._check_live()
        owner = asyncio.current_task()

        async def observe_t1_cancel() -> None:
            await self.cancel_event.wait()
            if owner is not None:
                owner.cancel()

        watcher = asyncio.create_task(observe_t1_cancel())
        deadline_scope = asyncio.timeout(self.remaining_seconds)
        try:
            async with deadline_scope:
                yield self
        except TimeoutError as exc:
            if deadline_scope.expired():
                raise BudgetLimited("run-deadline") from exc
            raise  # A provider idle timeout is not the run's total deadline.
        finally:
            watcher.cancel()
            await settle((watcher,), self._cleanup_remaining())
            await self.aclose()

    async def _reserve(self, call_id: str) -> None:
        async with self._lock:
            self._check_live()
            if not isinstance(call_id, str) or not call_id or call_id in self._started_ids:
                raise BudgetLimited("duplicate-or-missing-model-call-id")
            if self._reported_calls() + self._reserved >= self.limits.max_model_calls:
                raise BudgetLimited("model-call-limit")
            self._reserved += 1

    async def stream_model(self, call_id: str, *, messages: list, tools: list | tuple = (),
                           factory: Callable[[PreparedContext, Callable], AsyncIterator],
                           max_output_tokens: int | None = None) -> AsyncIterator[Any]:
        """Factory receives prepared context AND a required real-dispatch gate.

        T1's provider adapter calls the gate AT dispatch, after local validation.
        It passes the actual common-frame messages/schema/output limit. No
        iterator creation or preflight failure is counted as a model request.
        Cached/completed-node replay belongs to T1, outside this new dispatch.
        """
        self._check_live()
        prepared = self.context.fit(messages, tools, max_output_tokens=max_output_tokens)
        await self._reserve(call_id)
        reserved, started = True, False
        lease: _Lease | None = None
        handle: _StreamHandle | None = None
        prerequisites: tuple[asyncio.Future, ...] = ()

        async def mark_started(actual_messages: list, actual_tools: list | tuple | None,
                               actual_output_limit: int) -> None:
            nonlocal reserved, started
            if (json_bytes(list(actual_messages), sort_keys=True) != json_bytes(prepared.messages, sort_keys=True)
                    or json_bytes(list(actual_tools or ()), sort_keys=True) != json_bytes(prepared.tools, sort_keys=True)):
                raise BudgetLimited("model-dispatch-context-mismatch")
            _positive_int(actual_output_limit, "actual_output_limit")
            if actual_output_limit > prepared.reserved_output_tokens:
                raise BudgetLimited("model-dispatch-output-limit")
            if self.context.estimate(list(actual_messages), list(actual_tools or ())) + actual_output_limit > self.limits.context_total_tokens:
                raise BudgetLimited("model-dispatch-context-limit")
            async with self._lock:
                self._check_live()
                before = self._reported_calls()
                if started or before >= self.limits.max_model_calls or call_id in self._started_ids:
                    raise BudgetLimited("model-call-limit-or-duplicate-start")
                reported = self.on_model_start(call_id)  # T1's real dispatch hook
                if inspect.isawaitable(reported):
                    if inspect.iscoroutine(reported):
                        reported.close()
                    raise ValueError("T1 model-start hook must be synchronous")
                if self._reported_calls() != before + 1:
                    raise ValueError("T1 hook must record exactly one actual start")
                self._started_ids.add(call_id)
                self._reserved -= 1
                reserved, started = False, True

        try:
            lease = await self.guard(
                self.admission.acquire(self.actor_id, self.provider_id, self.run_id, self.limits),
                on_abandoned=lambda result: result.release())
            self._leases.add(lease)
            self._check_live()
            stream = factory(prepared, mark_started)
            if not hasattr(stream, "__anext__") or not hasattr(stream, "aclose"):
                if inspect.iscoroutine(stream):
                    stream.close()
                raise ValueError("factory must return a closable lazy async iterator")
            handle = _StreamHandle(stream, lease)
            self._streams.add(handle)
            while True:
                try:
                    chunk = await self.guard(stream.__anext__())
                except StopAsyncIteration:
                    if not started:
                        raise BudgetLimited("model-dispatch-not-reported")
                    break
                except CleanupIncomplete as exc:
                    prerequisites = exc.pending_tasks
                    raise
                self._check_live()
                if not started:
                    raise BudgetLimited("model-dispatch-not-reported")
                yield chunk  # supplier usage is unmodified, never estimated here
        finally:
            try:
                if handle is not None:
                    await self._close_stream(handle, prerequisites)
                elif lease is not None:
                    await lease.release()  # no factory/source was ever dispatched
                    self._leases.discard(lease)
            finally:
                if reserved:
                    async with self._lock:
                        self._reserved -= 1

    def limit_tool_result(self, value: Any, *, body_path: tuple = ()) -> LimitedResult:
        return limit_result(value, max_bytes=self.limits.max_tool_result_bytes,
                            max_tokens=self.limits.max_tool_result_tokens, body_path=body_path)

    def snapshot(self) -> EfficiencySnapshot:
        active = self.admission.counts(self.actor_id, self.provider_id, self.run_id)
        pending = sum(not task.done() for task in self._cleanup_tasks | self._work)
        failed = sum(task.done() and (task.cancelled() or task.exception() is not None)
                     for task in self._cleanup_tasks)
        return EfficiencySnapshot(self.deadline_at.isoformat(), self.remaining_seconds,
                                  self._reported_calls(), self.limits.max_model_calls,
                                  self._reserved, *active, self._closed, pending,
                                  len(self._quarantined), failed)

    async def aclose(self) -> None:
        """Bounded close; hung/failed streams KEEP their slot until real closure.

        CleanupIncomplete is not cancelled/succeeded. T1 owns transport-level
        reconciliation; this module never frees a still-live provider slot.
        """
        self._closed = True
        owner = asyncio.current_task()
        work = tuple(task for task in self._work if task is not owner)
        pending_work = await settle(work, self._cleanup_remaining(), cancel=True)
        for task in pending_work:
            self._observe_work(task)
        for handle in tuple(self._streams):
            # Conservatively wait for remaining iterations before aclose; this
            # prevents "async generator already running" from false cleanup.
            self._start_stream_cleanup(handle, pending_work)
        remaining = await settle(self._cleanup_tasks, self._cleanup_remaining())
        for handle in tuple(self._streams):
            if not handle.closed:
                self._quarantined.add(handle.lease)
        failed = tuple(task for task in self._cleanup_tasks
                       if task.done() and (task.cancelled() or task.exception() is not None))
        if pending_work or remaining or failed or self._quarantined:
            raise CleanupIncomplete(tuple(set(pending_work + remaining + failed)), reason="run-cleanup-incomplete")

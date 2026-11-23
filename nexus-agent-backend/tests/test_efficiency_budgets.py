"""T3 budgets. Test hooks are fixtures, not a production T1 integration.

Written but not executed in this window. T1 must run CPU-intensive work serially.
"""
import asyncio
import time
import unittest
from dataclasses import replace
from datetime import datetime, timedelta, timezone

from app.efficiency.budgets import AdmissionController, BudgetLimits, RunBudget
from app.efficiency.cleanup import CleanupIncomplete
from app.efficiency.results import BudgetLimited


async def consume(stream):
    return [chunk async for chunk in stream]


async def until(predicate):
    for _ in range(200):
        if predicate():
            return
        await asyncio.sleep(0)
    raise AssertionError("unit scheduling condition was not reached")


class RunBudgetTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.pool = AdmissionController()
        self.budgets, self.tasks, self.leases = [], [], []
        self.cleanup_events = []

    async def asyncTearDown(self):
        for event in self.cleanup_events:
            event.set()
        for task in self.tasks:
            task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        for budget in self.budgets:
            try:
                await budget.aclose()
            except Exception:
                pass  # a cleanup-failure fixture was already asserted
        for lease in self.leases:
            await lease.release()
        await self.pool.aclose()

    def budget(self, *, limits=None, actor=1, provider="unit-provider", run="unit-run",
               initial_calls=0, deadline=None, pool=None):
        state = {"calls": initial_calls, "ids": []}
        event = asyncio.Event()

        def started(call_id):
            state["calls"] += 1
            state["ids"].append(call_id)

        policy = limits or BudgetLimits()
        budget = RunBudget(limits=policy, actor_id=actor, provider_id=provider, run_id=run,
                           deadline_at=deadline or datetime.now(timezone.utc) + timedelta(seconds=policy.run_timeout_seconds),
                           cancel_event=event, get_model_calls=lambda: state["calls"],
                           on_model_start=started, admission=pool or self.pool)
        self.budgets.append(budget)
        return budget, state, event

    def stream(self, budget, call_id, factory, messages=None):
        return budget.stream_model(call_id, messages=messages or [{"role": "user", "content": "synthetic request"}],
                                   factory=factory)

    async def test_failed_retry_and_budget_rejection_count_actual_starts(self):
        budget, state, _ = self.budget(limits=replace(BudgetLimits(), max_model_calls=2))
        factories = []

        def fail(context, dispatch):
            factories.append(context)

            async def iterator():
                await dispatch(context.messages, context.tools, context.reserved_output_tokens)
                raise RuntimeError("synthetic provider failure")
                yield None
            return iterator()

        chunk = {"text": "unit", "usage": None}

        def success(context, dispatch):
            factories.append(context)

            async def iterator():
                await dispatch(context.messages, context.tools, context.reserved_output_tokens)
                yield chunk
            return iterator()

        with self.assertRaisesRegex(RuntimeError, "provider failure"):
            await consume(self.stream(budget, "attempt-1", fail))
        self.assertEqual(await consume(self.stream(budget, "attempt-2", success)), [chunk])
        with self.assertRaisesRegex(BudgetLimited, "model-call-limit"):
            await consume(self.stream(budget, "attempt-3", success))
        self.assertEqual(state["calls"], 2)
        self.assertEqual(state["ids"], ["attempt-1", "attempt-2"])
        self.assertEqual(len(factories), 2)
        self.assertEqual(budget.snapshot().run_active, 0)

    async def test_context_refusal_never_invokes_factory_or_start_hook(self):
        budget, state, _ = self.budget(limits=replace(BudgetLimits(), context_total_tokens=1000, output_reserve_tokens=100))
        factories = []
        with self.assertRaises(BudgetLimited):
            await consume(self.stream(budget, "not-dispatched", lambda context, dispatch: factories.append(context),
                                      [{"role": "user", "content": "large" * 10000}]))
        self.assertEqual(state["calls"], 0)
        self.assertEqual(factories, [])
        self.assertEqual(budget.snapshot().pending_call_reservations, 0)

    async def test_queued_cancellation_has_zero_provider_dispatches(self):
        limits = replace(BudgetLimits(), run_concurrency=1, user_concurrency=1, provider_concurrency=1)
        lease = await self.pool.acquire(1, "unit-provider", "holder", limits)
        self.leases.append(lease)
        budget, state, event = self.budget(limits=limits, run="queued-run")
        factories = []
        task = asyncio.create_task(consume(self.stream(budget, "queued", lambda context, dispatch: factories.append(context))))
        self.tasks.append(task)
        await until(lambda: budget.snapshot().run_queued == 1)
        event.set()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(factories, [])
        self.assertEqual(state["calls"], 0)
        self.assertEqual(budget.snapshot().run_queued, 0)
        self.assertEqual(budget.snapshot().pending_call_reservations, 0)
        self.assertEqual(budget.snapshot().run_active, 0)

    async def test_four_roles_share_default_concurrency_two(self):
        budget, state, _ = self.budget()
        release, observed = asyncio.Event(), {"active": 0, "peak": 0}

        def factory(context, dispatch):
            async def iterator():
                await dispatch(context.messages, context.tools, context.reserved_output_tokens)
                observed["active"] += 1
                observed["peak"] = max(observed["peak"], observed["active"])
                try:
                    await release.wait()
                    yield {"text": "unit", "usage": None}
                finally:
                    observed["active"] -= 1
            return iterator()

        tasks = [asyncio.create_task(consume(self.stream(budget, f"role-{i}", factory))) for i in range(4)]
        self.tasks.extend(tasks)
        await until(lambda: state["calls"] == 2 and budget.snapshot().run_queued == 2)
        self.assertEqual(observed["peak"], 2)
        release.set()
        await asyncio.gather(*tasks)
        self.assertEqual(observed["peak"], 2)
        self.assertEqual(state["calls"], 4)
        self.assertEqual(budget.snapshot().run_active, 0)

    async def test_actor_and_provider_caps_are_shared_across_runs(self):
        first, a, _ = self.budget(run="run-a")
        second, b, _ = self.budget(run="run-b")
        release = asyncio.Event()

        def factory(context, dispatch):
            async def iterator():
                await dispatch(context.messages, context.tools, context.reserved_output_tokens)
                await release.wait()
                yield "unit"
            return iterator()

        tasks = [asyncio.create_task(consume(self.stream(budget, f"call-{i}", factory)))
                 for i, budget in enumerate((first, second, first, second))]
        self.tasks.extend(tasks)
        await until(lambda: a["calls"] + b["calls"] == 2 and first.snapshot().run_queued + second.snapshot().run_queued == 2)
        self.assertEqual(first.snapshot().user_active, 2)
        self.assertEqual(second.snapshot().provider_active, 2)
        release.set()
        await asyncio.gather(*tasks)
        self.assertEqual(a["calls"] + b["calls"], 4)
        self.assertEqual(first.snapshot().user_active, 0)

    async def test_finite_queue_applies_backpressure_before_provider_start(self):
        limits = replace(BudgetLimits(), run_concurrency=1, user_concurrency=1, provider_concurrency=1, max_queue_size=1)
        lease = await self.pool.acquire(1, "unit-provider", "holder", limits)
        self.leases.append(lease)
        budget, state, event = self.budget(limits=limits, run="queue")
        factories = []
        task = asyncio.create_task(consume(self.stream(budget, "waiting", lambda context, dispatch: factories.append(context))))
        self.tasks.append(task)
        await until(lambda: budget.snapshot().run_queued == 1)
        with self.assertRaisesRegex(BudgetLimited, "queue-backpressure"):
            await consume(self.stream(budget, "overflow", lambda context, dispatch: factories.append(context)))
        self.assertEqual(state["calls"], 0)
        self.assertEqual(factories, [])
        event.set()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(budget.snapshot().pending_call_reservations, 0)

    async def test_continuous_slow_stream_still_hits_total_deadline(self):
        budget, state, _ = self.budget(limits=replace(BudgetLimits(), run_timeout_seconds=0.05))
        closed = asyncio.Event()

        def factory(context, dispatch):
            async def iterator():
                await dispatch(context.messages, context.tools, context.reserved_output_tokens)
                try:
                    while True:
                        await asyncio.sleep(0.003)  # never an idle 30-second timeout
                        yield "unit-chunk"
                finally:
                    closed.set()
            return iterator()

        started = time.monotonic()
        with self.assertRaisesRegex(BudgetLimited, "run-deadline"):
            await consume(self.stream(budget, "slow", factory))
        self.assertLess(time.monotonic() - started, 2)
        self.assertTrue(closed.is_set())
        self.assertEqual(state["calls"], 1)
        self.assertEqual(budget.snapshot().run_active, 0)

    async def test_consumer_aclose_closes_source_and_releases_all_slots(self):
        budget, state, _ = self.budget()
        closed = asyncio.Event()

        def factory(context, dispatch):
            async def iterator():
                await dispatch(context.messages, context.tools, context.reserved_output_tokens)
                try:
                    yield "unit"
                    await asyncio.Event().wait()
                finally:
                    closed.set()
            return iterator()

        stream = self.stream(budget, "close", factory)
        self.assertEqual(await stream.__anext__(), "unit")
        self.assertEqual(budget.snapshot().run_active, 1)
        await stream.aclose()
        self.assertTrue(closed.is_set())
        self.assertEqual(budget.snapshot().run_active, 0)
        self.assertEqual(budget.snapshot().pending_call_reservations, 0)
        self.assertEqual(state["calls"], 1)

    async def test_failing_provider_close_is_quarantined_not_falsely_released(self):
        budget, state, _ = self.budget()

        class BrokenStream:
            def __init__(self, context, dispatch):
                self.context, self.dispatch = context, dispatch

            async def __anext__(self):
                await self.dispatch(self.context.messages, self.context.tools, self.context.reserved_output_tokens)
                raise RuntimeError("synthetic iteration failure")

            async def aclose(self):
                raise RuntimeError("synthetic close failure")

        with self.assertRaises(CleanupIncomplete):
            await consume(self.stream(budget, "broken-close", lambda context, dispatch: BrokenStream(context, dispatch)))
        self.assertEqual(budget.snapshot().quarantined_slots, 1)
        self.assertEqual(budget.snapshot().run_active, 1)  # unknown live slot is NOT freed
        self.assertEqual(budget.snapshot().pending_call_reservations, 0)
        self.assertEqual(state["calls"], 1)

    async def test_resume_keeps_old_deadline_and_t1_call_count(self):
        budget, state, _ = self.budget(initial_calls=2, deadline=datetime.now(timezone.utc) - timedelta(seconds=1))
        factories = []
        with self.assertRaisesRegex(BudgetLimited, "run-deadline"):
            await consume(self.stream(budget, "expired", lambda context, dispatch: factories.append(context)))
        self.assertEqual(state["calls"], 2)
        self.assertEqual(factories, [])
        self.assertEqual(budget.remaining_seconds, 0)

    async def test_run_scope_deadline_covers_non_model_work(self):
        budget, state, _ = self.budget(limits=replace(BudgetLimits(), run_timeout_seconds=0.02))
        with self.assertRaisesRegex(BudgetLimited, "run-deadline"):
            async with budget.run_scope():
                await asyncio.sleep(0.2)
        self.assertEqual(state["calls"], 0)
        self.assertTrue(budget.snapshot().closed)

    async def test_run_scope_does_not_relabel_provider_timeout(self):
        budget, _, _ = self.budget()
        with self.assertRaisesRegex(TimeoutError, "provider idle"):
            async with budget.run_scope():
                raise TimeoutError("synthetic provider idle")

    def test_requests_cannot_expand_server_policy(self):
        policy = BudgetLimits()
        self.assertEqual(policy.tighten(run_concurrency=1).run_concurrency, 1)
        for requested in ({"max_model_calls": 7}, {"run_timeout_seconds": 121}, {"unknown": 1}):
            with self.subTest(requested=requested), self.assertRaises(ValueError):
                policy.tighten(**requested)


    async def test_local_preflight_failure_is_not_a_model_dispatch(self):
        budget, state, _ = self.budget()

        def preflight(context, dispatch):
            async def iterator():
                raise RuntimeError("unit local preflight")
                yield None
            return iterator()

        with self.assertRaisesRegex(RuntimeError, "local preflight"):
            await consume(self.stream(budget, "preflight", preflight))
        self.assertEqual(state["calls"], 0)
        self.assertEqual(budget.snapshot().run_active, 0)

    async def test_unreported_dispatch_or_increased_request_budget_is_refused(self):
        budget, state, _ = self.budget()

        def unreported(context, dispatch):
            async def iterator():
                yield "not-backed-by-start"
            return iterator()

        with self.assertRaisesRegex(BudgetLimited, "dispatch-not-reported"):
            await consume(self.stream(budget, "unreported", unreported))
        for mode in ("input", "output"):
            def invalid(context, dispatch):
                async def iterator():
                    messages = list(context.messages)
                    if mode == "input":
                        messages.append({"role": "user", "content": "extra"})
                    reserve = context.reserved_output_tokens + (1 if mode == "output" else 0)
                    await dispatch(messages, context.tools, reserve)
                    yield "unit"
                return iterator()
            with self.subTest(mode=mode), self.assertRaises(BudgetLimited):
                await consume(self.stream(budget, mode, invalid))
        self.assertEqual(state["calls"], 0)

    async def test_hanging_aclose_has_bounded_grace_and_quarantines_until_real_finish(self):
        limits = replace(BudgetLimits(), cleanup_timeout_seconds=0.02)
        budget, state, _ = self.budget(limits=limits)
        finish = asyncio.Event()
        self.cleanup_events.append(finish)

        class Source:
            def __init__(self, context, dispatch):
                self.context, self.dispatch = context, dispatch

            async def __anext__(self):
                await self.dispatch(self.context.messages, self.context.tools, self.context.reserved_output_tokens)
                return "unit"

            async def aclose(self):
                await finish.wait()

        stream = self.stream(budget, "hang-close", lambda context, dispatch: Source(context, dispatch))
        await stream.__anext__()
        started = time.monotonic()
        with self.assertRaises(CleanupIncomplete):
            await stream.aclose()
        self.assertLess(time.monotonic() - started, 1)
        self.assertEqual(budget.snapshot().quarantined_slots, 1)
        self.assertEqual(budget.snapshot().run_active, 1)
        finish.set()
        await until(lambda: budget.snapshot().run_active == 0)
        self.assertEqual(budget.snapshot().quarantined_slots, 0)
        self.assertEqual(state["calls"], 1)

    async def test_swallowed_cancellation_does_not_make_total_deadline_wait_forever(self):
        limits = replace(BudgetLimits(), run_timeout_seconds=0.02, cleanup_timeout_seconds=0.02)
        budget, state, _ = self.budget(limits=limits)
        finish = asyncio.Event()
        self.cleanup_events.append(finish)

        def factory(context, dispatch):
            async def iterator():
                await dispatch(context.messages, context.tools, context.reserved_output_tokens)
                while not finish.is_set():
                    try:
                        await finish.wait()
                    except asyncio.CancelledError:
                        pass  # intentionally noncooperative UNIT fixture
                yield "late-unit"
            return iterator()

        started = time.monotonic()
        with self.assertRaises(CleanupIncomplete):
            await consume(self.stream(budget, "swallow", factory))
        self.assertLess(time.monotonic() - started, 1)
        self.assertEqual(state["calls"], 1)
        self.assertEqual(budget.snapshot().quarantined_slots, 1)
        finish.set()
        await until(lambda: budget.snapshot().run_active == 0)


    async def test_dispatch_identity_is_not_sensitive_to_json_key_order(self):
        budget, state, _ = self.budget()

        def factory(context, dispatch):
            async def iterator():
                reordered = [dict(reversed(list(message.items()))) for message in context.messages]
                await dispatch(reordered, context.tools, context.reserved_output_tokens)
                yield "unit"
            return iterator()

        self.assertEqual(await consume(self.stream(budget, "reordered", factory)), ["unit"])
        self.assertEqual(state["calls"], 1)


if __name__ == "__main__":
    unittest.main()

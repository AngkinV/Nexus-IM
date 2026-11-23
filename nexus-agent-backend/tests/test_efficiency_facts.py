"""T3 fact contracts. Synthetic unit fixtures only; NOT real-chain evidence.

Tests are written but intentionally not executed by this window (T1 owns runs).
"""
import asyncio
import json
import unittest
from dataclasses import replace
from datetime import datetime, timedelta, timezone

from app.efficiency.facts import (CachePartition, FactBundle, FactContractError,
                                  FactFetch, FactPage, FactQuery, FactScopeChanged,
                                  SharedFactReader, calendar_window)
from app.efficiency.cleanup import CleanupIncomplete
from app.efficiency.results import BudgetLimited, json_bytes


def query(**options):
    return FactQuery(10, "2026-11-22T00:00:00Z", "2026-11-23T00:00:00Z", limit=2, **options)


def message(message_id, *, timestamp="2026-11-22T18:00:00+08:00", content="unit", clipped=False, original=None, chat=10):
    return {"messageId": message_id, "chatId": chat, "timestamp": timestamp, "createdAt": timestamp,
            "senderId": 101, "senderName": "unit sender", "content": content,
            "contentTruncated": clipped,
            "originalContentBytes": len((content or "").encode("utf-8")) if original is None else original}


def sized(value):
    value["resultBytes"] = 0
    for _ in range(8):
        size = len(json_bytes(value))
        if value["resultBytes"] == size:
            return value
        value["resultBytes"] = size
    raise AssertionError("fixture size did not converge")


def payload(effective, *, messages=None, read=None, next_cursor="unit-next", watermark=None):
    if messages is None:
        top = min(3, effective.watermark) if effective.watermark is not None else 3
        messages = [message(i, chat=effective.chat_id) for i in range(top, max(0, top - min(2, effective.limit)), -1)]
    read = len(messages) if read is None else read
    more = read > len(messages)
    high = effective.watermark if effective.watermark is not None else max((m["messageId"] for m in messages), default=0)
    return sized({"chatId": effective.chat_id, "messages": messages,
                  "window": {"startAt": effective.start_at.isoformat() if effective.start_at else None,
                             "endAt": effective.end_at.isoformat() if effective.end_at else None,
                             "semantics": "[start,end)"},
                  "timezone": effective.timezone, "storageTimeZone": "UTC",
                  "watermark": high if watermark is None else watermark,
                  "nextCursor": next_cursor if more else None,
                  "readCount": read, "returnedCount": len(messages), "truncatedCount": read - len(messages),
                  "probeCount": int(read == effective.limit + 1),
                  "contentTruncatedCount": sum(m["contentTruncated"] for m in messages),
                  "hasMore": more})


def parsed(effective=None, **options):
    effective = effective or query()
    return FactPage.parse(FactFetch(payload(effective, **options), "unit-call", True), effective)


class FactContractTests(unittest.TestCase):
    def test_window_start_inclusive_end_exclusive_and_evidence(self):
        effective = query()
        page = parsed(effective, messages=[message(2, timestamp="2026-11-23T07:59:59.999999+08:00"),
                                            message(1, timestamp="2026-11-22T08:00:00+08:00")])
        self.assertEqual(page.returned_count, 2)
        self.assertEqual(page.messages[1].timestamp, effective.start_at)
        for timestamp in ("2026-11-23T08:00:00+08:00", "2026-11-22T07:59:59.999999+08:00"):
            with self.subTest(timestamp=timestamp), self.assertRaises(FactContractError):
                parsed(effective, messages=[message(1, timestamp=timestamp)])

    def test_probe_and_truncated_counts_are_not_chat_totals(self):
        page = parsed(read=3)
        self.assertEqual((page.read_count, page.returned_count, page.truncated_count, page.probe_count), (3, 2, 1, 1))
        self.assertTrue(page.has_more)
        self.assertEqual(page.next_cursor, "unit-next")
        empty = parsed(messages=[], read=0)
        self.assertEqual((empty.read_count, empty.returned_count, empty.truncated_count), (0, 0, 0))
        self.assertFalse(empty.has_more)
        self.assertEqual(empty.watermark, 0)

    def test_old_payload_is_rejected_without_inventing_counts(self):
        with self.assertRaisesRegex(FactContractError, "fields are missing"):
            FactPage.parse(FactFetch({"chatId": 10, "messages": []}, "unit", True), query())

    def test_quantity_forgery_is_rejected_in_actual_payload(self):
        for field, value in (("readCount", 10000), ("returnedCount", 1), ("truncatedCount", 1),
                             ("probeCount", 1), ("contentTruncatedCount", 1), ("hasMore", True)):
            forged = payload(query())
            forged[field] = value
            with self.subTest(field=field), self.assertRaises(FactContractError):
                FactPage.parse(FactFetch(sized(forged), "unit", True), query())

    def test_duplicate_wrong_chat_wrong_watermark_and_unstable_order_are_rejected(self):
        cases = [[message(3), message(3)], [message(3, chat=20)], [message(4), message(2)], [message(2), message(3)]]
        for messages in cases:
            with self.subTest(ids=[m["messageId"] for m in messages]), self.assertRaises(FactContractError):
                parsed(messages=messages, watermark=3)

    def test_has_more_requires_progress_and_advancing_cursor(self):
        effective = replace(query(), cursor="unit-next")
        with self.assertRaises(FactContractError):
            parsed(effective, read=3, next_cursor="unit-next")
        with self.assertRaises(FactContractError):
            parsed(messages=[], read=1, next_cursor="next")

    def test_reported_bytes_cannot_hide_an_oversized_or_forged_result(self):
        forged = payload(query())
        forged["resultBytes"] = 1
        with self.assertRaisesRegex(FactContractError, "resultBytes"):
            FactPage.parse(FactFetch(forged, "unit", True), query())
        small = replace(query(), max_result_bytes=128)
        with self.assertRaises(BudgetLimited):
            FactPage.parse(FactFetch(payload(small), "unit", True), small)

    def test_body_truncation_is_separate_from_unreturned_rows(self):
        page = parsed(messages=[message(1, content="中", clipped=True, original=100)])
        self.assertEqual((page.returned_count, page.truncated_count, page.content_truncated_count), (1, 0, 1))
        self.assertTrue(FactBundle((page,)).view(1000).partial)
        with self.assertRaises(FactContractError):
            parsed(messages=[message(1, content="unit", clipped=True, original=4)])

    def test_timezone_metadata_matches_emitted_offset(self):
        with self.assertRaisesRegex(FactContractError, "output timezone"):
            parsed(messages=[message(1, timestamp="2026-11-22T10:00:00Z")])

    def test_naive_unpaired_nanosecond_or_invalid_zone_windows_are_rejected(self):
        cases = [dict(start_at=datetime(2026, 11, 22), end_at="2026-11-23T00:00:00Z"),
                 dict(start_at="2026-11-22T00:00:00Z"),
                 dict(start_at="2026-11-22T00:00:00.0000001Z", end_at="2026-11-23T00:00:00Z"),
                 dict(timezone="Not/AZone")]
        for options in cases:
            with self.subTest(options=options), self.assertRaises(FactContractError):
                FactQuery(10, **options)

    def test_calendar_dates_use_local_midnights_and_dst_day_lengths(self):
        start, end = calendar_window("2026-11-22", "2026-11-23", "Asia/Shanghai")
        self.assertEqual(start, datetime(2026, 11, 21, 16, tzinfo=timezone.utc))
        self.assertEqual((end - start).total_seconds(), 86400)
        spring = calendar_window("2026-03-08", "2026-03-09", "America/New_York")
        fall = calendar_window("2026-11-01", "2026-11-02", "America/New_York")
        self.assertEqual((spring[1] - spring[0]).total_seconds(), 23 * 3600)
        self.assertEqual((fall[1] - fall[0]).total_seconds(), 25 * 3600)

    def test_budgeted_view_retains_citations_and_explicit_omissions(self):
        page = parsed(messages=[message(3, content="中😀" * 1000), message(2, content="unit")])
        view = FactBundle((page,)).view(400)
        data = json.loads(view.text)
        self.assertLessEqual(view.estimated_tokens, 400)
        self.assertEqual(data["messages"][0]["messageId"], 3)
        self.assertEqual(data["messages"][0]["chatId"], 10)
        self.assertIn("timestamp", data["messages"][0])
        self.assertEqual(view.included_count + view.omitted_count, 2)
        self.assertTrue(view.partial)
        self.assertTrue(view.untrusted)
        self.assertEqual(view.source_call_ids, ("unit-call",))


    def test_conflicting_page_variants_cannot_silently_claim_complete_range(self):
        first = parsed(read=3)
        small_query = replace(query(), limit=1)
        second = FactPage.parse(FactFetch(payload(small_query, messages=[message(3)], watermark=3), "other-call", True), small_query)
        with self.assertRaisesRegex(FactContractError, "conflicting page variants"):
            _ = FactBundle((first, second)).has_more

    def test_wire_timestamps_must_be_json_strings_not_python_datetime_objects(self):
        value = payload(query())
        value["messages"][0]["timestamp"] = datetime.now(timezone.utc)
        with self.assertRaisesRegex(FactContractError, "JSON-compatible"):
            FactPage.parse(FactFetch(value, "unit", True), query())


async def until(predicate):
    for _ in range(200):
        if predicate():
            return
        await asyncio.sleep(0)
    raise AssertionError("unit scheduling condition was not reached")


class SharedFactTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.scope = object()  # stand-in ONLY in unit tests, not an auth implementation
        self.partition = CachePartition(1, "unit-scope", "v1")
        self.cancel = asyncio.Event()
        self.denied = False
        self.calls = 0
        self.scopes = []
        self.fetch_contexts = []
        self.gate = None
        self.readers, self.tasks = [], []
        self.cleanup_events = []

    async def authorize(self, scope, effective):
        self.scopes.append(scope)
        if self.denied:
            raise PermissionError("unit T2 denial")
        return self.partition, effective

    async def fetch(self, effective, partition, scope):
        self.calls += 1
        call_id = f"unit-call-{self.calls}"
        self.fetch_contexts.append((partition, scope))
        if self.gate is not None:
            await self.gate.wait()
        return FactFetch(payload(effective), call_id, True)

    def reader(self, **options):
        reader = SharedFactReader(actor_id=1, scope=self.scope,
                                  authorize=options.pop("authorize", self.authorize),
                                  fetch=options.pop("fetch", self.fetch), cancel_event=self.cancel, **options)
        self.readers.append(reader)
        return reader

    async def asyncTearDown(self):
        for event in self.cleanup_events:
            event.set()
        for task in self.tasks:
            task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        for reader in self.readers:
            try:
                await reader.aclose()
            except CleanupIncomplete:
                pass  # reported by the fixture, never claimed cancelled

    async def test_roles_share_one_fetch_and_original_t2_scope(self):
        reader = self.reader()
        self.gate = asyncio.Event()
        tasks = [asyncio.create_task(reader.read_page(query())) for _ in range(2)]
        self.tasks.extend(tasks)
        await until(lambda: self.calls == 1)
        self.gate.set()
        first, second = await asyncio.gather(*tasks)
        third = await reader.read_page(query())
        self.assertIs(first, second)
        self.assertIs(first, third)
        self.assertEqual(self.calls, 1)
        self.assertTrue(all(scope is self.scope for scope in self.scopes))
        self.assertIs(self.fetch_contexts[0][1], self.scope)
        self.assertEqual(reader.snapshot().reported_read_count, 2)
        self.assertEqual(reader.snapshot().executed_calls, 1)
        first.to_payload()["messages"][0]["content"] = "mutated"
        self.assertEqual(third.messages[0].content, "unit")

    async def test_denial_returns_no_data_and_executes_no_fetch(self):
        reader = self.reader()
        self.denied = True
        received = []
        with self.assertRaises(PermissionError):
            received.append(await reader.read_page(query()))
        self.assertEqual(received, [])
        self.assertEqual(self.calls, 0)
        self.assertEqual(reader.snapshot().executed_calls, 0)
        self.assertEqual(reader.snapshot().reported_read_count, 0)

    async def test_wrong_actor_and_expanding_query_never_fetch(self):
        async def wrong(scope, effective):
            return CachePartition(2, "other-account", "v1"), effective

        async def expanding(scope, effective):
            return self.partition, replace(effective, start_at=effective.start_at - timedelta(days=1))

        for callback in (wrong, expanding):
            with self.subTest(callback=callback.__name__), self.assertRaises(FactContractError):
                await self.reader(authorize=callback).read_page(query())
        self.assertEqual(self.calls, 0)

    async def test_revocation_cannot_reuse_cached_facts_or_prepared_view(self):
        reader = self.reader()
        page = await reader.read_page(query())
        self.denied = True
        with self.assertRaises(PermissionError):
            await reader.read_page(query())
        with self.assertRaises(PermissionError):
            await reader.view(FactBundle((page,)), 1000)
        self.assertEqual(self.calls, 1)  # no second MCP access after denial

    async def test_version_change_during_fetch_discards_data_but_reports_real_read(self):
        async def changed(effective, partition, scope):
            receipt = await self.fetch(effective, partition, scope)
            self.partition = CachePartition(1, "unit-scope", "v2")
            return receipt

        reader = self.reader(fetch=changed)
        with self.assertRaises(FactScopeChanged):
            await reader.read_page(query())
        self.assertEqual(self.calls, 1)
        self.assertEqual(reader.snapshot().reported_read_count, 2)
        self.assertEqual(reader.snapshot().discarded_after_authorization, 1)
        self.assertEqual(reader.snapshot().cached_bytes, 0)

    async def test_authorization_version_range_and_watermark_partition_cache(self):
        reader = self.reader()
        first_query = query()
        await reader.read_page(first_query)
        self.partition = CachePartition(1, "unit-scope", "v2")
        await reader.read_page(first_query)
        await reader.read_page(replace(first_query, start_at=first_query.start_at + timedelta(hours=1)))
        with self.assertRaisesRegex(FactContractError, "watermark cannot drift"):
            await reader.read_page(replace(first_query, watermark=2))
        other_run = self.reader()
        await other_run.read_page(replace(first_query, watermark=2))
        await other_run.read_page(replace(first_query, watermark=2))
        self.assertEqual(self.calls, 4)
        self.assertEqual(reader.snapshot().executed_calls, 3)
        self.assertEqual(reader.snapshot().message_watermarks, ((10, 3),))

    async def test_failed_page_is_not_cached_and_unknown_counts_are_not_zero_usage(self):
        async def malformed(effective, partition, scope):
            self.calls += 1
            return FactFetch({"chatId": 10, "messages": []}, f"bad-{self.calls}", True)

        reader = self.reader(fetch=malformed)
        for _ in range(2):
            with self.assertRaises(FactContractError):
                await reader.read_page(query())
        self.assertEqual(self.calls, 2)
        self.assertEqual(reader.snapshot().unknown_read_calls, 2)
        self.assertEqual(reader.snapshot().cached_bytes, 0)

    async def test_replayed_t1_receipt_does_not_count_a_new_mcp_read(self):
        async def replay(effective, partition, scope):
            return FactFetch(payload(effective), "previous-real-call", False)

        reader = self.reader(fetch=replay)
        page = await reader.read_page(query())
        self.assertEqual(page.call_id, "previous-real-call")
        self.assertEqual(page.read_count, 2)
        self.assertEqual(reader.snapshot().executed_calls, 0)
        self.assertEqual(reader.snapshot().reported_read_count, 0)

    async def test_individual_follower_cancellation_does_not_cancel_shared_fetch(self):
        reader = self.reader()
        self.gate = asyncio.Event()
        first = asyncio.create_task(reader.read_page(query()))
        second = asyncio.create_task(reader.read_page(query()))
        self.tasks.extend((first, second))
        await until(lambda: self.calls == 1)
        first.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await first
        self.gate.set()
        page = await second
        self.assertEqual(page.returned_count, 2)
        self.assertEqual(self.calls, 1)

    async def test_run_cancellation_and_close_release_pending_pages(self):
        reader = self.reader()
        self.gate = asyncio.Event()
        task = asyncio.create_task(reader.read_page(query()))
        self.tasks.append(task)
        await until(lambda: self.calls == 1)
        self.cancel.set()
        with self.assertRaises(asyncio.CancelledError):
            await task
        await reader.aclose()
        self.assertEqual(reader.snapshot().pending_pages, 0)
        self.assertEqual(reader.snapshot().cached_bytes, 0)

    async def test_finite_cache_and_explicit_invalidation(self):
        cap = len(json_bytes(payload(query()))) + 10
        reader = self.reader(max_cache_bytes=cap)
        await reader.read_page(query())
        await reader.read_page(replace(query(), start_at=query().start_at + timedelta(hours=1)))
        self.assertLessEqual(reader.snapshot().cached_bytes, cap)
        await reader.invalidate(self.partition)
        self.assertEqual(reader.snapshot().cached_bytes, 0)

    async def test_next_page_is_on_demand_with_same_watermark_and_complete_chain(self):
        calls = []

        async def pages(effective, partition, scope):
            calls.append(effective)
            body = payload(effective, read=3) if effective.cursor is None else payload(effective, messages=[message(1)])
            return FactFetch(body, f"unit-page-{len(calls)}", True)

        reader = self.reader(fetch=pages)
        first = await reader.read_page(query())
        self.assertEqual(len(calls), 1)
        second = await reader.next_page(first)
        self.assertEqual(calls[1].watermark, first.watermark)
        self.assertEqual(calls[1].cursor, first.next_cursor)
        bundle = FactBundle((first, second))
        self.assertFalse(bundle.has_more)
        self.assertEqual(len(bundle.messages), 3)
        self.assertEqual(bundle.read_count, 4)  # repeated probe is an actual read, not distinct coverage
        self.assertIsNone(await reader.next_page(second))


    async def test_invalidating_a_partition_does_not_discard_b_inflight(self):
        child = object()
        part_b = CachePartition(1, "unit-B", "v1")
        gates = {self.partition: asyncio.Event(), part_b: asyncio.Event()}
        entered = set()

        async def auth(scope, effective):
            return (part_b if scope is child else self.partition), effective

        async def fetch(effective, partition, scope):
            entered.add(partition)
            await gates[partition].wait()
            return FactFetch(payload(effective), "unit-" + partition.scope_fingerprint, True)

        reader = self.reader(authorize=auth, fetch=fetch)
        a = asyncio.create_task(reader.read_page(query()))
        b = asyncio.create_task(reader.read_page(query(), scope=child))
        self.tasks.extend((a, b))
        await until(lambda: len(entered) == 2)
        await reader.invalidate(self.partition)
        with self.assertRaises(asyncio.CancelledError):
            await a
        gates[part_b].set()
        result = await b
        self.assertEqual(result.returned_count, 2)

    async def test_prepared_reference_survives_eviction_but_not_invalidation(self):
        cap = len(json_bytes(payload(query()))) + 10
        reader = self.reader(max_cache_bytes=cap)
        first = await reader.read_page(query())
        await reader.read_page(replace(query(), start_at=query().start_at + timedelta(hours=1)))
        view = await reader.view(FactBundle((first,)), 1000)
        self.assertEqual(view.included_count, 2)
        self.assertEqual(self.calls, 2)  # no third physical retrieval after LRU eviction
        await reader.invalidate(self.partition)
        await reader.view(FactBundle((first,)), 1000)
        self.assertEqual(self.calls, 3)  # explicitly retired proof is NOT reused

    async def test_duplicate_executed_t1_receipt_is_not_double_counted(self):
        async def wrong_receipt(effective, partition, scope):
            return FactFetch(payload(effective), "same-executed-id", True)

        reader = self.reader(fetch=wrong_receipt)
        await reader.read_page(query())
        await reader.invalidate(self.partition)
        with self.assertRaisesRegex(FactContractError, "duplicated an executed"):
            await reader.read_page(query())
        self.assertEqual(reader.snapshot().executed_calls, 1)
        self.assertEqual(reader.snapshot().unconfirmed_fetches, 1)

    async def test_noncooperative_fetch_close_is_bounded_and_not_claimed_cancelled(self):
        finish, entered = asyncio.Event(), asyncio.Event()
        self.cleanup_events.append(finish)

        async def stubborn(effective, partition, scope):
            entered.set()
            while not finish.is_set():
                try:
                    await finish.wait()
                except asyncio.CancelledError:
                    pass
            return FactFetch(payload(effective), "late-unit", True)

        reader = self.reader(fetch=stubborn, cleanup_timeout_seconds=0.02)
        task = asyncio.create_task(reader.read_page(query()))
        self.tasks.append(task)
        await entered.wait()
        with self.assertRaises(CleanupIncomplete):
            await reader.aclose()
        self.assertGreater(reader.snapshot().cleanup_pending, 0)
        finish.set()
        await until(lambda: reader.snapshot().cleanup_pending == 0)
        await asyncio.gather(task, return_exceptions=True)


    async def test_invalidation_during_last_delivery_authorization_cannot_return_stale_page(self):
        count = 0

        async def racy(scope, effective):
            nonlocal count
            count += 1
            if count == 3:  # before load, after fetch, then final delivery auth
                await reader.invalidate(self.partition)
            return self.partition, effective

        reader = self.reader(authorize=racy)
        received = []
        with self.assertRaises(FactScopeChanged):
            received.append(await reader.read_page(query()))
        self.assertEqual(received, [])

    async def test_multi_page_view_fences_earlier_pages_after_later_authorization(self):
        later = replace(query(), start_at=query().start_at + timedelta(hours=1))
        invalidate_later = False

        async def racy(scope, effective):
            nonlocal invalidate_later
            if invalidate_later and effective.start_at == later.start_at:
                invalidate_later = False
                await reader.invalidate(self.partition)
            return self.partition, effective

        reader = self.reader(authorize=racy)
        first = await reader.read_page(query())
        second = await reader.read_page(later)
        invalidate_later = True
        with self.assertRaises(FactScopeChanged):
            await reader.view(FactBundle((first, second)), 1000)


    async def test_restored_watermark_is_used_instead_of_taking_a_new_snapshot(self):
        captured = []

        async def resumed(effective, partition, scope):
            captured.append(effective.watermark)
            return FactFetch(payload(effective), "unit-resumed", True)

        reader = self.reader(fetch=resumed, message_watermarks={10: 2})
        page = await reader.read_page(query())
        self.assertEqual(captured, [2])
        self.assertEqual(page.watermark, 2)
        self.assertEqual(reader.snapshot().message_watermarks, ((10, 2),))


if __name__ == "__main__":
    unittest.main()

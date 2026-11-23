"""Run-local, single-flight facts, using T2 scope and T1 call receipts.

Authorization is deliberately injected, never inferred from a chat id. Cache
partitions are identities, NOT an alternative authorization model. No provider,
embedding, graph, settings, RPC or private history dependency lives here.
"""
from __future__ import annotations

import asyncio
import inspect
import json
import math
import re
from collections import OrderedDict
from dataclasses import dataclass, replace
from datetime import date, datetime, time, timezone
from typing import Any, Awaitable, Callable, Mapping
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from weakref import WeakValueDictionary

from .cleanup import CleanupIncomplete, consume_exception, settle
from .results import BudgetLimited, json_bytes


class FactContractError(RuntimeError):
    """Missing/inconsistent backend evidence is an error, not inferred data."""


class _Epoch:
    """Run-local cache invalidation stamp, not an authorization capability."""


class FactScopeChanged(RuntimeError):
    """T2 partition/effective query changed while facts were in flight."""


def _integer(value: Any, name: str, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise FactContractError(f"invalid {name}")
    return value


def _instant(value: Any, *, nullable: bool = False) -> datetime | None:
    if value is None and nullable:
        return None
    if isinstance(value, str):
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})", value):
            raise FactContractError("RFC3339 offset/seconds and at most microsecond precision required")
        try:
            value = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise FactContractError("invalid timestamp") from exc
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise FactContractError("aware timestamp required")
    try:
        return value.astimezone(timezone.utc)
    except (OverflowError, ValueError) as exc:
        raise FactContractError("timestamp is outside the UTC calendar range") from exc


def _iso(value: datetime | None) -> str | None:
    return None if value is None else value.isoformat().replace("+00:00", "Z")


def _zone(value: Any) -> str:
    if not isinstance(value, str) or not value:
        raise FactContractError("IANA timezone required")
    try:
        ZoneInfo(value)
    except (ZoneInfoNotFoundError, ValueError) as exc:
        raise FactContractError("IANA timezone required") from exc
    return value


def calendar_window(start_date: date | str, end_date_exclusive: date | str,
                    timezone_name: str) -> tuple[datetime, datetime]:
    """Explicit local calendar dates -> UTC [start,end), with no 'today' guess.

    A DST day can be 23/25 hours. Ambiguous/nonexistent local midnight is
    rejected rather than resolved using an undocumented fold assumption.
    """
    zone = ZoneInfo(_zone(timezone_name))
    values: list[datetime] = []
    for value in (start_date, end_date_exclusive):
        if isinstance(value, str):
            if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
                raise FactContractError("YYYY-MM-DD calendar date required")
            try:
                value = date.fromisoformat(value)
            except ValueError as exc:
                raise FactContractError("ISO calendar date required") from exc
        if type(value) is not date:
            raise FactContractError("calendar date, not an implicit timestamp, required")
        local = datetime.combine(value, time.min, tzinfo=zone)
        utc = local.astimezone(timezone.utc)
        if (utc.astimezone(zone).replace(tzinfo=None) != local.replace(tzinfo=None)
                or local.replace(fold=1).utcoffset() != local.utcoffset()):
            raise FactContractError("local midnight is nonexistent or ambiguous")
        values.append(utc)
    if values[0] >= values[1]:
        raise FactContractError("calendar window must be nonempty")
    return values[0], values[1]


@dataclass(frozen=True)
class FactQuery:
    chat_id: int
    start_at: datetime | str | None = None
    end_at: datetime | str | None = None
    timezone: str = "Asia/Shanghai"
    limit: int = 80
    cursor: str | None = None
    watermark: int | None = None
    max_result_bytes: int = 65_536

    def __post_init__(self) -> None:
        _integer(self.chat_id, "chat_id", 1)
        _integer(self.limit, "limit", 1)
        _integer(self.max_result_bytes, "max_result_bytes", 1)
        if self.limit > 200 or self.max_result_bytes > 65_536:
            raise FactContractError("query exceeds the default message-page policy")
        start, end = _instant(self.start_at, nullable=True), _instant(self.end_at, nullable=True)
        if (start is None) != (end is None) or (start is not None and start >= end):
            raise FactContractError("nonempty paired [start,end) window required")
        object.__setattr__(self, "start_at", start)
        object.__setattr__(self, "end_at", end)
        _zone(self.timezone)
        if self.watermark is not None:
            _integer(self.watermark, "watermark")
        if self.cursor is not None and (not isinstance(self.cursor, str) or not self.cursor
                                        or len(self.cursor) > 2048):
            raise FactContractError("invalid opaque cursor")

    def to_tool_args(self) -> dict[str, Any]:
        args = {"chat_id": self.chat_id, "limit": self.limit, "timezone": self.timezone,
                "max_result_bytes": self.max_result_bytes}
        for key, value in (("start_at", _iso(self.start_at)), ("end_at", _iso(self.end_at)),
                           ("cursor", self.cursor), ("watermark", self.watermark)):
            if value is not None:
                args[key] = value
        return args


@dataclass(frozen=True)
class CachePartition:
    actor_id: int
    scope_fingerprint: str
    authorization_version: str

    def __post_init__(self) -> None:
        _integer(self.actor_id, "cache actor", 1)
        if (not isinstance(self.scope_fingerprint, str) or not self.scope_fingerprint
                or not isinstance(self.authorization_version, str) or not self.authorization_version):
            raise FactContractError("T2 scope fingerprint and authorization version are required")


@dataclass(frozen=True)
class FactFetch:
    payload: Mapping[str, Any]
    call_id: str
    executed: bool

    def __post_init__(self) -> None:
        if not isinstance(self.call_id, str) or not self.call_id or type(self.executed) is not bool:
            raise FactContractError("T1 call receipt and executed/replayed flag are required")
        if not isinstance(self.payload, Mapping):
            raise FactContractError("structured Java fact page required")


@dataclass(frozen=True)
class FactMessage:
    message_id: int
    chat_id: int
    timestamp: datetime
    sender_id: int
    sender_name: str
    content: str | None
    content_truncated: bool
    original_content_bytes: int

    def evidence(self) -> dict[str, Any]:
        return {"messageId": self.message_id, "chatId": self.chat_id,
                "timestamp": _iso(self.timestamp)}


@dataclass(frozen=True)
class FactPage:
    query: FactQuery
    messages: tuple[FactMessage, ...]
    watermark: int
    next_cursor: str | None
    read_count: int
    returned_count: int
    truncated_count: int
    probe_count: int
    content_truncated_count: int
    has_more: bool
    result_bytes: int
    storage_timezone: str
    call_id: str
    executed: bool
    payload_json: bytes
    partition: CachePartition | None = None
    cache_epoch: _Epoch | None = None

    def to_payload(self) -> dict[str, Any]:
        return json.loads(self.payload_json)

    @classmethod
    def parse(cls, receipt: FactFetch, query: FactQuery) -> FactPage:
        try:
            encoded = json_bytes(dict(receipt.payload))
        except BudgetLimited as exc:
            raise FactContractError("fact payload must be JSON-compatible with string timestamps") from exc
        payload = json.loads(encoded)  # detached before any validation/cache
        required = {"chatId", "messages", "window", "timezone", "storageTimeZone", "watermark",
                    "nextCursor", "readCount", "returnedCount", "truncatedCount", "probeCount",
                    "contentTruncatedCount", "hasMore", "resultBytes"}
        if not required.issubset(payload):
            raise FactContractError("Java page contract fields are missing")
        if len(encoded) > query.max_result_bytes:
            raise BudgetLimited("fact-result-bytes", limit=query.max_result_bytes, observed=len(encoded))
        if _integer(payload["resultBytes"], "resultBytes") != len(encoded):
            raise FactContractError("resultBytes does not match compact JSON UTF-8 bytes")
        if _integer(payload["chatId"], "chatId", 1) != query.chat_id:
            raise FactContractError("chat evidence does not match the effective query")
        if payload["timezone"] != query.timezone:
            raise FactContractError("page timezone drift")
        storage = _zone(payload["storageTimeZone"])
        window = payload["window"]
        if (not isinstance(window, dict) or window.get("semantics") != "[start,end)"
                or "startAt" not in window or "endAt" not in window
                or _instant(window["startAt"], nullable=True) != query.start_at
                or _instant(window["endAt"], nullable=True) != query.end_at):
            raise FactContractError("page window drift")
        watermark = _integer(payload["watermark"], "watermark")
        if query.watermark is not None and watermark != query.watermark:
            raise FactContractError("page watermark drift")
        read = _integer(payload["readCount"], "readCount")
        returned = _integer(payload["returnedCount"], "returnedCount")
        truncated = _integer(payload["truncatedCount"], "truncatedCount")
        probe = _integer(payload["probeCount"], "probeCount")
        body_truncated = _integer(payload["contentTruncatedCount"], "contentTruncatedCount")
        has_more, cursor = payload["hasMore"], payload["nextCursor"]
        raw_messages = payload["messages"]
        if (not isinstance(raw_messages, list) or returned != len(raw_messages)
                or returned > query.limit or read > query.limit + 1 or read < returned
                or truncated != read - returned or probe != int(read == query.limit + 1)
                or type(has_more) is not bool or has_more != (read > returned)):
            raise FactContractError("inconsistent actual page quantities")
        if has_more:
            if not isinstance(cursor, str) or not cursor or len(cursor) > 2048 or not returned:
                raise FactContractError("hasMore requires progress and a bounded next cursor")
            if cursor == query.cursor:
                raise FactContractError("cursor did not advance")
        elif cursor is not None:
            raise FactContractError("terminal page must not have a next cursor")
        messages: list[FactMessage] = []
        ids: set[int] = set()
        previous: tuple[datetime, int] | None = None
        for message in raw_messages:
            if not isinstance(message, dict):
                raise FactContractError("invalid message evidence")
            needed = {"messageId", "chatId", "timestamp", "createdAt", "senderId", "senderName",
                      "content", "contentTruncated", "originalContentBytes"}
            if not needed.issubset(message):
                raise FactContractError("message evidence fields are missing")
            message_id = _integer(message["messageId"], "messageId", 1)
            chat_id = _integer(message["chatId"], "message chatId", 1)
            if not isinstance(message["timestamp"], str) or not isinstance(message["createdAt"], str):
                raise FactContractError("wire message timestamps must be strings")
            timestamp = _instant(message["timestamp"])
            emitted_offset = datetime.fromisoformat(message["timestamp"].replace("Z", "+00:00")).utcoffset()
            if emitted_offset != timestamp.astimezone(ZoneInfo(query.timezone)).utcoffset():
                raise FactContractError("message offset does not match the declared output timezone")
            if (message_id in ids or message_id > watermark or chat_id != query.chat_id
                    or _instant(message["createdAt"]) != timestamp
                    or (query.start_at is not None
                        and not (query.start_at <= timestamp < query.end_at))):
                raise FactContractError("message lies outside the requested evidence boundary")
            position = (timestamp, message_id)
            if previous is not None and position >= previous:
                raise FactContractError("messages must be strictly timestamp/id descending")
            previous = position
            ids.add(message_id)
            sender_id = _integer(message["senderId"], "senderId", 1)
            content, clipped = message["content"], message["contentTruncated"]
            if ((content is not None and not isinstance(content, str))
                    or type(clipped) is not bool or not isinstance(message["senderName"], str)):
                raise FactContractError("invalid message body metadata")
            original = _integer(message["originalContentBytes"], "originalContentBytes")
            emitted = len((content or "").encode("utf-8"))
            if (clipped and original <= emitted) or (not clipped and original != emitted):
                raise FactContractError("body truncation does not match actual UTF-8 sizes")
            messages.append(FactMessage(message_id, chat_id, timestamp, sender_id, message["senderName"],
                                        content, clipped, original))
        if body_truncated != sum(message.content_truncated for message in messages):
            raise FactContractError("contentTruncatedCount mismatch")
        return cls(query, tuple(messages), watermark, cursor, read, returned, truncated, probe,
                   body_truncated, has_more, len(encoded), storage, receipt.call_id, receipt.executed, encoded)


@dataclass(frozen=True)
class FactReadSnapshot:
    executed_calls: int
    reported_read_count: int
    unknown_read_calls: int
    discarded_after_authorization: int
    cache_hits: int
    cached_bytes: int
    pending_pages: int
    unconfirmed_fetches: int = 0
    cleanup_pending: int = 0
    message_watermarks: tuple[tuple[int, int], ...] = ()


@dataclass(frozen=True)
class FactView:
    text: str
    included_count: int
    omitted_count: int
    body_truncated_count: int
    estimated_tokens: int
    estimation_method: str
    source_call_ids: tuple[str, ...]
    partial: bool
    untrusted: bool = True


@dataclass(frozen=True)
class FactBundle:
    pages: tuple[FactPage, ...]

    @property
    def messages(self) -> tuple[FactMessage, ...]:
        unique: dict[tuple[int, int], FactMessage] = {}
        for page in self.pages:
            for message in page.messages:
                key = (message.chat_id, message.message_id)
                if key in unique and unique[key] != message:
                    raise FactContractError("shared evidence changed under the same message identity")
                unique[key] = message
        return tuple(sorted(unique.values(), key=lambda message: (message.timestamp, message.message_id),
                            reverse=True))

    @property
    def read_count(self) -> int:
        # Historical actual row materialization, deduplicated by T1 call id.
        return sum(page.read_count for page in {p.call_id: p for p in self.pages}.values())

    @property
    def has_more(self) -> bool:
        if not self.pages:
            return True  # no proof that any authorized range was actually read
        groups: dict[tuple, dict[str | None, FactPage]] = {}
        for page in self.pages:
            key = (page.query.chat_id, page.query.start_at, page.query.end_at,
                   page.query.timezone, page.watermark)
            pages = groups.setdefault(key, {})
            previous = pages.get(page.query.cursor)
            if previous is not None and (previous.query.limit != page.query.limit
                    or previous.query.max_result_bytes != page.query.max_result_bytes
                    or previous.payload_json != page.payload_json):
                raise FactContractError("conflicting page variants at the same cursor")
            pages[page.query.cursor] = page
        for pages in groups.values():
            current = pages.get(None)
            seen: set[str | None] = set()
            if current is None:
                return True  # an arbitrary continuation is not complete coverage
            while current.has_more:
                if current.query.cursor in seen:
                    raise FactContractError("cycle in fact-page continuation")
                seen.add(current.query.cursor)
                current = pages.get(current.next_cursor)
                if current is None:
                    return True
        return False

    def view(self, max_tokens: int, *, estimator: Callable[[str], int] | None = None,
             estimation_method: str | None = None,
             include: set[tuple[int, int]] | None = None) -> FactView:
        _integer(max_tokens, "view tokens", 1)
        if estimator is not None and not estimation_method:
            raise ValueError("custom fact-view estimator requires a method label")
        all_messages = self.messages
        selected = [message for message in all_messages
                    if include is None or (message.chat_id, message.message_id) in include]
        rows: list[dict[str, Any]] = []
        complete = not self.has_more

        def candidate(candidate_rows: list) -> tuple[str, int, int]:
            body_count = sum(row["contentTruncated"] for row in candidate_rows)
            value = {"untrusted": True, "messages": candidate_rows,
                     "coverage": {"includedCount": len(candidate_rows),
                                  "omittedCount": len(all_messages) - len(candidate_rows),
                                  "bodyTruncatedCount": body_count, "rangeComplete": complete}}
            text = json_bytes(value).decode("utf-8")
            estimate = estimator(text) if estimator else len(text.encode("utf-8"))
            _integer(estimate, "estimated view tokens")
            return text, estimate, body_count

        if candidate([])[1] > max_tokens:
            raise BudgetLimited("fact-view-minimum-evidence", limit=max_tokens)
        for message in selected:
            row = {**message.evidence(), "senderName": message.sender_name, "content": message.content,
                   "contentTruncated": message.content_truncated,
                   "originalContentBytes": message.original_content_bytes}
            if candidate(rows + [row])[1] <= max_tokens:
                rows.append(row)
                continue
            body = message.content or ""
            row["contentTruncated"] = True
            row["content"] = ""
            if not body or candidate(rows + [row])[1] > max_tokens:
                break
            lo, hi = 0, len(body)
            while lo < hi:
                mid = (lo + hi + 1) // 2
                row["content"] = body[:mid]
                if candidate(rows + [row])[1] <= max_tokens:
                    lo = mid
                else:
                    hi = mid - 1
            row["content"] = body[:lo]
            rows.append(row)
            break  # remaining evidence is explicitly omitted, never invented
        text, estimated, clipped = candidate(rows)
        return FactView(text, len(rows), len(all_messages) - len(rows), clipped, estimated,
                        estimation_method or "fact-view-utf8-bytes-not-actual-usage",
                        tuple(dict.fromkeys(page.call_id for page in self.pages)),
                        not complete or len(rows) < len(all_messages) or clipped > 0)


class SharedFactReader:
    """One instance per run; T5 injects budget.guard as await_guard.

    authorize receives T2's original opaque scope and returns its current cache
    identity/effective query. It must perform current Java/T2 authorization.
    A receipt replayed by T1 keeps its real call id and historical evidence, but
    executed=False prevents counting another physical read this run.
    """
    def __init__(self, *, actor_id: int, scope: Any,
                 authorize: Callable[[Any, FactQuery], Awaitable[tuple[CachePartition, FactQuery]]],
                 fetch: Callable[[FactQuery, CachePartition, Any], Awaitable[FactFetch]], cancel_event: asyncio.Event,
                 await_guard: Callable[[Awaitable], Awaitable] | None = None,
                 max_cache_bytes: int = 262_144, max_pending_pages: int = 8,
                 cleanup_timeout_seconds: float = 1.0,
                 message_watermarks: Mapping[int, int] | None = None) -> None:
        _integer(actor_id, "actor_id", 1)
        _integer(max_cache_bytes, "cache bytes", 1)
        _integer(max_pending_pages, "pending pages", 1)
        if scope is None or not callable(authorize) or not callable(fetch):
            raise ValueError("T2 scope and its live authorization adapter are required")
        if not isinstance(cancel_event, asyncio.Event):
            raise ValueError("T1 cancellation event is required")
        if (isinstance(cleanup_timeout_seconds, bool) or not isinstance(cleanup_timeout_seconds, (int, float))
                or not math.isfinite(cleanup_timeout_seconds) or cleanup_timeout_seconds <= 0):
            raise ValueError("cleanup grace must be finite and positive")
        self.cleanup_timeout_seconds = cleanup_timeout_seconds
        self.actor_id, self.scope, self.authorize, self.fetch = actor_id, scope, authorize, fetch
        self.cancel_event, self.await_guard = cancel_event, await_guard
        self.max_cache_bytes, self.max_pending_pages = max_cache_bytes, max_pending_pages
        self._lock = asyncio.Lock()
        self._cache: OrderedDict[tuple[CachePartition, FactQuery], FactPage] = OrderedDict()
        self._aliases: dict[tuple[CachePartition, FactQuery], tuple[CachePartition, FactQuery]] = {}
        self._pending: dict[tuple[CachePartition, FactQuery], asyncio.Task] = {}
        self._cache_bytes = 0
        self._epochs: WeakValueDictionary = WeakValueDictionary()
        self._orphaned_io: set[asyncio.Future] = set()
        self._executed_ids: set[str] = set()
        self._watermarks: dict[int, int] = {}
        for chat_id, watermark in (message_watermarks or {}).items():
            self._watermarks[_integer(chat_id, "restored chatId", 1)] = _integer(watermark, "restored watermark")
        self._closed = False
        self._executed = self._read = self._unknown = self._discarded = self._hits = 0
        self._unconfirmed = 0

    def _live(self) -> None:
        if self._closed or self.cancel_event.is_set():
            raise asyncio.CancelledError("T1 cancellation or closed fact reader")

    def _observe_io(self, task: asyncio.Future) -> None:
        self._orphaned_io.add(task)

        def done(future: asyncio.Future) -> None:
            self._orphaned_io.discard(future)
            consume_exception(future)
        task.add_done_callback(done)

    async def _owned(self, awaitable: Awaitable) -> Any:
        try:
            self._live()
        except BaseException:
            if inspect.iscoroutine(awaitable):
                awaitable.close()
            raise
        if self.await_guard is not None:
            return await self.await_guard(awaitable)
        work = asyncio.ensure_future(awaitable)
        cancelled = asyncio.create_task(self.cancel_event.wait())
        try:
            await asyncio.wait((work, cancelled), return_when=asyncio.FIRST_COMPLETED)
            self._live()
            return work.result()
        finally:
            pending = await settle((work, cancelled), self.cleanup_timeout_seconds, cancel=True)
            for task in pending:
                self._observe_io(task)
            if pending:
                raise CleanupIncomplete(pending, reason="fact-cleanup-incomplete")

    @staticmethod
    def _narrowed(request: FactQuery, effective: FactQuery) -> bool:
        return (effective.chat_id == request.chat_id and effective.timezone == request.timezone
                and effective.limit <= request.limit and effective.max_result_bytes <= request.max_result_bytes
                and effective.cursor == request.cursor
                and (request.watermark is None or (effective.watermark is not None
                                                    and effective.watermark <= request.watermark))
                and (request.start_at is None or (effective.start_at is not None
                     and request.start_at <= effective.start_at and effective.end_at <= request.end_at)))

    async def _authorized(self, query: FactQuery, scope: Any = None) -> tuple[CachePartition, FactQuery]:
        selected_scope = self.scope if scope is None else scope
        value = await self._owned(self.authorize(selected_scope, query))
        if not isinstance(value, tuple) or len(value) != 2:
            raise FactContractError("invalid T2 authorization adapter result")
        partition, effective = value
        if (not isinstance(partition, CachePartition) or partition.actor_id != self.actor_id
                or not isinstance(effective, FactQuery) or not self._narrowed(query, effective)):
            raise FactContractError("T2 cache identity/query does not match this actor or request")
        fixed = self._watermarks.get(effective.chat_id)
        if fixed is not None:
            if effective.watermark is not None and effective.watermark != fixed:
                raise FactContractError("run message watermark cannot drift")
            effective = replace(effective, watermark=fixed)
        return partition, effective

    def _forget(self, key: tuple, task: asyncio.Task) -> None:
        if self._pending.get(key) is task:
            self._pending.pop(key, None)
        if not task.cancelled():
            task.exception()  # consume errors even if all followers left

    def _remove_cache(self, key: tuple) -> None:
        page = self._cache.pop(key, None)
        if page is not None:
            self._cache_bytes -= page.result_bytes
        for alias, target in tuple(self._aliases.items()):
            if target == key:
                self._aliases.pop(alias, None)

    async def _load(self, key: tuple[CachePartition, FactQuery], epoch: _Epoch, scope: Any) -> FactPage:
        partition, effective = key
        try:
            receipt = await self._owned(self.fetch(effective, partition, scope))
        except BaseException:
            self._unconfirmed += 1  # no T1 receipt: actual execution/read count is UNKNOWN
            raise
        if not isinstance(receipt, FactFetch):
            self._unconfirmed += 1
            raise FactContractError("T1 FactFetch receipt is required")
        if receipt.executed:
            if receipt.call_id in self._executed_ids:
                self._unconfirmed += 1
                raise FactContractError("T1 duplicated an executed invocation identity")
            self._executed_ids.add(receipt.call_id)
            self._executed += 1
        try:
            page = FactPage.parse(receipt, effective)
        except BaseException:
            if receipt.executed:
                self._unknown += 1  # no trustworthy read count, not a fabricated 0
            raise
        if receipt.executed:
            self._read += page.read_count
        try:
            current = await self._authorized(effective, scope)
        except BaseException:
            if receipt.executed:
                self._discarded += 1
            raise
        if (current[0] != partition or current[1].watermark not in (None, page.watermark)
                or replace(current[1], watermark=page.watermark) != replace(effective, watermark=page.watermark)):
            if receipt.executed:
                self._discarded += 1
            raise FactScopeChanged("T2 authorization changed during a fact read")
        async with self._lock:
            self._live()
            if self._epochs.get(partition) is not epoch:
                raise FactScopeChanged("facts invalidated during a read")
            existing_watermark = self._watermarks.get(effective.chat_id)
            if existing_watermark is not None and existing_watermark != page.watermark:
                raise FactContractError("concurrent first reads returned conflicting watermarks")
            self._watermarks.setdefault(effective.chat_id, page.watermark)
            page = replace(page, partition=partition, cache_epoch=epoch)
            canonical = (partition, replace(effective, watermark=page.watermark))
            if page.result_bytes <= self.max_cache_bytes:
                self._remove_cache(canonical)
                while self._cache and self._cache_bytes + page.result_bytes > self.max_cache_bytes:
                    self._remove_cache(next(iter(self._cache)))
                self._cache[canonical] = page
                self._cache_bytes += page.result_bytes
                if canonical != key:
                    self._aliases[key] = canonical
        return page

    async def read_page(self, query: FactQuery, *, scope: Any = None) -> FactPage:
        selected_scope = self.scope if scope is None else scope
        partition, effective = await self._authorized(query, selected_scope)  # before fetch/cache
        key = (partition, effective)
        async with self._lock:
            self._live()
            cached_key = self._aliases.get(key, key)
            page = self._cache.get(cached_key)
            if page is not None:
                self._cache.move_to_end(cached_key)
                self._hits += 1
                task = None
            else:
                task = self._pending.get(key)
                if task is None:
                    if len(self._pending) + len(self._orphaned_io) >= self.max_pending_pages:
                        raise BudgetLimited("fact-queue-backpressure")
                    epoch = self._epochs.setdefault(partition, _Epoch())
                    task = asyncio.create_task(self._load(key, epoch, selected_scope))
                    self._pending[key] = task
                    task.add_done_callback(lambda done, identity=key: self._forget(identity, done))
        if task is not None:
            # A cancelled individual role must not cancel another role's read.
            page = await self._owned(asyncio.shield(task))
        current = await self._authorized(query, selected_scope)
        canonical_key = (partition, replace(effective, watermark=page.watermark))
        if current != canonical_key:
            await self.invalidate(partition)
            raise FactScopeChanged("T2 authorization changed before facts were delivered")
        async with self._lock:
            self._live()
            if page.partition != partition or page.cache_epoch is None or self._epochs.get(partition) is not page.cache_epoch:
                raise FactScopeChanged("facts invalidated during delivery authorization")
            return page

    async def next_page(self, page: FactPage, *, scope: Any = None) -> FactPage | None:
        if not page.has_more:
            return None
        return await self.read_page(replace(page.query, watermark=page.watermark, cursor=page.next_cursor), scope=scope)

    async def view(self, bundle: FactBundle, max_tokens: int, *, scope: Any = None, **options: Any) -> FactView:
        """T5 role entry point: reauthorize even already-prepared/cached facts.

        Pass the ORIGINAL T2 child scope; T2 verifies inheritance/narrowing.
        A changed/narrowed scope gets only freshly authorized effective pages.
        FactBundle.view itself is merely a formatter, never an authorization.
        """
        selected_scope = self.scope if scope is None else scope
        pages = []
        for page in bundle.pages:
            query = replace(page.query, watermark=page.watermark)
            partition, effective = await self._authorized(query, selected_scope)
            # A live, immutable prepared reference remains reusable even when
            # the LRU entry was evicted. It is not a new MCP access. Scope/version
            # changes OR explicit invalidation retire the proof before reuse.
            if (page.partition == partition and effective == query and page.cache_epoch is not None
                    and self._epochs.get(partition) is page.cache_epoch):
                self._live()
                pages.append(page)
            else:
                pages.append(await self.read_page(query, scope=selected_scope))
        # Later authorization awaits may invalidate an already accepted page.
        # Recheck ALL page permissions, then fence ALL epochs without an await
        # between the fence and formatting/return. This is run-local, not a
        # distributed transaction with Java authorization.
        for page in pages:
            query = replace(page.query, watermark=page.watermark)
            if await self._authorized(query, selected_scope) != (page.partition, query):
                raise FactScopeChanged("scope changed while preparing the shared view")
        async with self._lock:
            self._live()
            if any(page.cache_epoch is None or self._epochs.get(page.partition) is not page.cache_epoch for page in pages):
                raise FactScopeChanged("facts invalidated while preparing the shared view")
            return FactBundle(tuple(pages)).view(max_tokens, **options)

    def snapshot(self) -> FactReadSnapshot:
        return FactReadSnapshot(self._executed, self._read, self._unknown, self._discarded,
                                self._hits, self._cache_bytes, len(self._pending), self._unconfirmed,
                                len(self._orphaned_io), tuple(sorted(self._watermarks.items())))

    async def invalidate(self, partition: CachePartition | None = None) -> None:
        """Only that T2 identity is invalidated; unrelated in-flight reads survive."""
        async with self._lock:
            if partition is None:
                self._epochs.clear()
            else:
                self._epochs.pop(partition, None)
            for key in tuple(self._cache):
                if partition is None or key[0] == partition:
                    self._remove_cache(key)
            tasks = []
            for key, task in tuple(self._pending.items()):
                if partition is None or key[0] == partition:
                    self._pending.pop(key, None)
                    tasks.append(task)
        pending = await settle(tasks, self.cleanup_timeout_seconds, cancel=True)
        for task in pending:
            self._observe_io(task)
        if pending:
            raise CleanupIncomplete(pending, reason="fact-invalidation-cleanup-incomplete")

    async def aclose(self) -> None:
        self._closed = True
        await self.invalidate()
        pending = await settle(self._orphaned_io, self.cleanup_timeout_seconds, cancel=True)
        if pending:
            raise CleanupIncomplete(pending, reason="fact-cleanup-incomplete")

"""Byte/token result caps for BOTH internal and external MCP adapters."""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any


class BudgetLimited(RuntimeError):
    def __init__(self, reason: str, *, limit: int | float | None = None,
                 observed: int | float | None = None) -> None:
        self.reason, self.limit, self.observed = reason, limit, observed
        super().__init__(reason)


def json_bytes(value: Any, *, sort_keys: bool = False) -> bytes:
    """The compact, Unicode-preserving JSON encoding used by this contract."""
    try:
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"),
                          allow_nan=False, sort_keys=sort_keys).encode("utf-8")
    except (TypeError, ValueError, UnicodeError, RecursionError) as exc:
        # Do not put raw untrusted tool content or credentials in an error.
        raise BudgetLimited("tool-result-invalid-json") from exc


@dataclass(frozen=True)
class LimitedResult:
    value: Any
    serialized: bytes
    original_bytes: int
    estimated_tokens: int
    truncated: bool
    estimation_method: str = "compact-json-utf8-bytes-upper-bound-not-actual-usage"


def limit_result(value: Any, *, max_bytes: int = 65_536,
                 max_tokens: int = 65_536, body_path: tuple = ()) -> LimitedResult:
    """Trim ONLY an explicitly designated body string, retaining all evidence.

    The returned value is detached. For a fact page, clipping without updating
    pagination/counts would be dishonest: reject an oversized page even if a
    body_path was supplied. Java's page packer owns that operation.
    No arbitrary JSON slicing, row dropping or count synthesis happens here.
    """
    if any(isinstance(cap, bool) or not isinstance(cap, int) or cap < 1
           for cap in (max_bytes, max_tokens)):
        raise ValueError("result limits must be positive integers")
    raw = json_bytes(value)
    detached = json.loads(raw)
    cap = min(max_bytes, max_tokens)  # one conservative token per JSON byte
    if len(raw) <= cap:
        return LimitedResult(detached, raw, len(raw), len(raw), False)
    if isinstance(detached, dict) and "messages" in detached and "readCount" in detached:
        raise BudgetLimited("tool-result-fact-page-evidence", limit=cap, observed=len(raw))
    parent = detached
    try:
        for key in body_path[:-1]:
            parent = parent[key]
        body = parent[body_path[-1]] if body_path else detached
    except (KeyError, IndexError, TypeError):
        raise BudgetLimited("tool-result-invalid-body-path") from None
    if not isinstance(body, str):
        raise BudgetLimited("tool-result-minimum-evidence", limit=cap, observed=len(raw))

    def candidate(length: int) -> tuple[Any, bytes]:
        if body_path:
            parent[body_path[-1]] = body[:length]
            payload = detached
        else:
            payload = body[:length]
        wrapped = {"value": payload, "truncation": {
            "truncated": True, "originalJsonBytes": len(raw),
            "originalBodyBytes": len(body.encode("utf-8")),
            "notice": "Body shortened; retained evidence and counts are unchanged."}}
        return wrapped, json_bytes(wrapped)

    _, minimal_bytes = candidate(0)
    if len(minimal_bytes) > cap:
        raise BudgetLimited("tool-result-minimum-evidence", limit=cap, observed=len(minimal_bytes))
    lo, hi = 0, len(body)
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if len(candidate(mid)[1]) <= cap:
            lo = mid
        else:
            hi = mid - 1
    final, encoded = candidate(lo)
    return LimitedResult(final, encoded, len(raw), len(encoded), True)

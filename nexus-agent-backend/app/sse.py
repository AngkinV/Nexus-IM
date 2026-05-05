"""Helpers for constructing SSE-formatted lines."""
from __future__ import annotations

import itertools
import json
from typing import Any

_id_seq = itertools.count(1)


def reset_id_seq() -> None:
    global _id_seq
    _id_seq = itertools.count(1)


def event_to_sse(event: str, data: dict[str, Any]) -> str:
    """Format a single SSE frame. ID is monotonically increasing per process."""
    eid = next(_id_seq)
    return f"event: {event}\nid: {eid}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"

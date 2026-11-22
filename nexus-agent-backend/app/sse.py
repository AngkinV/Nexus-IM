"""Helpers for constructing SSE-formatted lines."""
from __future__ import annotations

import itertools
import json
from typing import Any, Callable


def sse_encoder() -> Callable[[str, dict[str, Any]], str]:
    """Frame encoder with its own id sequence, one per response."""
    ids = itertools.count(1)

    def encode(event: str, data: dict[str, Any]) -> str:
        return f"event: {event}\nid: {next(ids)}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"

    return encode

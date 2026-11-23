"""Whole-request conservative estimates, never provider usage.

T5 supplies the complete provider-bound transcript and tool schema before every
call. Unlabelled attachments/facts inside the current user input are protected:
if they alone exceed the cap the run is limited, not silently rewritten.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Callable

from .results import BudgetLimited, json_bytes


@dataclass(frozen=True)
class PreparedContext:
    serialized_messages: bytes
    serialized_tools: bytes
    estimated_input_tokens: int
    reserved_output_tokens: int
    dropped_history_turns: int
    estimation_method: str

    @property
    def messages(self) -> tuple[dict[str, Any], ...]:
        # Each factory gets detached data; mutation cannot change the receipt.
        return tuple(json.loads(self.serialized_messages))

    @property
    def tools(self) -> tuple[dict[str, Any], ...]:
        return tuple(json.loads(self.serialized_tools))


class ContextBudget:
    def __init__(self, total_tokens: int = 12_000, output_reserve: int = 1_024,
                 estimator: Callable | None = None,
                 estimation_method: str | None = None) -> None:
        for value in (total_tokens, output_reserve):
            if isinstance(value, bool) or not isinstance(value, int) or value < 1:
                raise ValueError("context limits must be positive integers")
        if output_reserve >= total_tokens:
            raise ValueError("output reserve must leave input capacity")
        if estimator is not None and not estimation_method:
            raise ValueError("custom estimator requires an explicit method label")
        self.total_tokens, self.output_reserve = total_tokens, output_reserve
        self.estimator = estimator
        self.method = estimation_method or "compact-json-utf8-plus-envelope-not-actual-usage"

    def estimate(self, messages: list, tools: list | tuple = ()) -> int:
        if self.estimator is not None:
            value = self.estimator(messages, tools)
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise ValueError("token estimator must return a nonnegative integer")
            return value
        for message in messages:
            content = message.get("content")
            if isinstance(content, list) and any(
                    not isinstance(part, dict) or part.get("type") not in {"text", "input_text", "output_text"}
                    for part in content):
                raise BudgetLimited("context-multimodal-tokenizer-required")
            if any(key in message for key in ("images", "audio", "video")):
                raise BudgetLimited("context-multimodal-tokenizer-required")
        # One token per UTF-8 byte is conservative for byte-based TEXT
        # tokenizers. Native image/audio costs require an injected provider-aware
        # estimator; URL byte length cannot be called an image token budget.
        return (len(json_bytes({"messages": messages, "tools": tools}))
                + 256 + 32 * len(messages) + 64 * len(tools))

    @staticmethod
    def _validate_transcript(messages: list) -> None:
        pending: set[str] = set()
        seen: set[str] = set()
        for message in messages:
            if not isinstance(message, dict):
                raise BudgetLimited("context-invalid-message")
            role = message.get("role")
            if role not in {"system", "developer", "user", "assistant", "tool"}:
                raise BudgetLimited("context-invalid-role")
            if role == "tool":
                call_id = message.get("tool_call_id")
                if not isinstance(call_id, str) or call_id not in pending:
                    raise BudgetLimited("context-orphan-tool-result")
                pending.remove(call_id)
            elif pending:
                # Model calls cannot be dispatched with unresolved tool calls.
                raise BudgetLimited("context-unresolved-tool-call")
            calls = message.get("tool_calls", [])
            if calls:
                if role != "assistant" or not isinstance(calls, list):
                    raise BudgetLimited("context-invalid-tool-call")
                for call in calls:
                    call_id = call.get("id") if isinstance(call, dict) else None
                    if not isinstance(call_id, str) or not call_id or call_id in seen:
                        raise BudgetLimited("context-invalid-tool-call-id")
                    pending.add(call_id)
                    seen.add(call_id)
        if pending:
            raise BudgetLimited("context-unresolved-tool-call")

    def fit(self, messages: list | tuple, tools: list | tuple = (), *,
            max_output_tokens: int | None = None) -> PreparedContext:
        detached_messages = json.loads(json_bytes(list(messages)))
        detached_tools = json.loads(json_bytes(list(tools)))
        reserve = self.output_reserve if max_output_tokens is None else max_output_tokens
        if (isinstance(reserve, bool) or not isinstance(reserve, int)
                or reserve < 1 or reserve > self.output_reserve):
            raise ValueError("output tokens may only tighten the positive reserve")
        self._validate_transcript(detached_messages)
        users = [i for i, message in enumerate(detached_messages) if message["role"] == "user"]
        latest = users[-1] if users else 0
        starts = [i for i in users if i < latest]
        turns: list[set[int]] = []
        for index, start in enumerate(starts):
            end = starts[index + 1] if index + 1 < len(starts) else latest
            turns.append({i for i in range(start, end)
                          if detached_messages[i]["role"] not in {"system", "developer"}})
        removed: set[int] = set()
        dropped = 0
        while True:
            kept = [message for i, message in enumerate(detached_messages) if i not in removed]
            self._validate_transcript(kept)
            estimate = self.estimate(kept, detached_tools)
            if estimate + reserve <= self.total_tokens:
                return PreparedContext(json_bytes(kept), json_bytes(detached_tools), estimate,
                                       reserve, dropped, self.method)
            if dropped == len(turns):
                raise BudgetLimited("context-required-input")
            removed.update(turns[dropped])
            dropped += 1

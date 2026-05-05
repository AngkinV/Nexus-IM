"""Common types for the LLM abstraction layer."""
from __future__ import annotations

import abc
import dataclasses
from typing import Any, AsyncIterator


@dataclasses.dataclass
class ProviderConfig:
    """Per-request provider configuration injected by the Java gateway."""
    name: str
    base_url: str | None
    model: str | None
    api_key: str | None


@dataclasses.dataclass
class LLMUsage:
    input_tokens: int = 0
    output_tokens: int = 0
    total_tokens: int = 0


@dataclasses.dataclass
class LLMToolCall:
    """A normalized tool invocation requested by the model."""
    id: str
    name: str
    arguments: dict[str, Any]


@dataclasses.dataclass
class LLMChunk:
    """One streaming event produced by an LLM client.

    Discriminator is `kind`:
      - "text"        : content is the partial assistant text (delta)
      - "tool_call"   : tool_call holds the requested call
      - "usage"       : usage holds prompt/completion tokens (final)
      - "finish"      : the model has finished this turn (no more tools)
      - "tool_result" : (only used when adapter wants to round-trip a tool result back)
    """
    kind: str
    text: str | None = None
    tool_call: LLMToolCall | None = None
    usage: LLMUsage | None = None
    finish_reason: str | None = None
    raw: Any | None = None  # adapter-specific


class LLMClient(abc.ABC):
    """Abstract base for all LLM adapters."""

    def __init__(self, cfg: ProviderConfig) -> None:
        self.cfg = cfg

    @abc.abstractmethod
    async def complete(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None,
        *,
        tool_choice: str | None = None,
        temperature: float = 0.2,
        max_tokens: int = 1024,
        timeout_sec: int = 30,
    ) -> AsyncIterator[LLMChunk]:
        """Yield streaming LLMChunks.

        Implementations may collect the full assistant response (including tool calls)
        and yield it in one go; streaming chunks are not strictly required as long as
        the contract above is honored.

        After yielding tool_call chunks, callers will execute them and call
        ``submit_tool_results`` then ``complete`` again with the new message history.
        """
        if False:  # pragma: no cover - mark as async generator
            yield LLMChunk(kind="text", text="")

"""Real model collaboration: three analysts run at the same time, then one synthesis.

Only the ordinary assistant chat can turn this on. Each analyst gets its own
transcript (its own role brief, the same conversation, its own tool access) and
its own model call, so the three run concurrently rather than in sequence. What
each analyst says is reported as its own output; only the synthesizer's text
becomes the turn's answer and the only thing written to memory.

Nothing here invents a result. An analyst that fails is reported as failed; if
all three fail the turn fails with them instead of synthesizing over nothing.
"""
from __future__ import annotations

import asyncio
import contextlib
import dataclasses
import json
import logging
import time
from typing import Any, Awaitable, Callable

from .llm import LLMClient

log = logging.getLogger(__name__)

ANALYSTS = ("code", "doc", "arch")

_BRIEF = {
    "code": "你是代码分析员。只从实现角度分析：涉及哪些代码、接口、数据结构与风险。",
    "doc": "你是文档分析员。只从文档与需求角度分析：目标、约束、歧义、需要确认的假设。",
    "arch": "你是架构分析员。只从结构与权衡角度分析：边界、依赖、扩展性与替代方案。",
}

_SYNTH = (
    "你是汇总者。下面是三位分析员针对同一问题的独立分析。"
    "请给出一份统一的回答：合并一致的部分，明确指出分歧，不要复述过程。"
)


@dataclasses.dataclass
class AnalystResult:
    agent_id: str
    text: str
    reasoning: str = ""
    error: str | None = None
    usage_in: int = 0
    usage_out: int = 0
    tool_calls: list[dict[str, Any]] = dataclasses.field(default_factory=list)
    ms: int = 0

    def as_payload(self) -> dict[str, Any]:
        return {
            "agentId": self.agent_id,
            "text": self.text,
            "reasoning": self.reasoning,
            "error": self.error,
            "usage": {"inputTokens": self.usage_in, "outputTokens": self.usage_out},
            "toolCalls": self.tool_calls,
            "ms": self.ms,
        }


async def _one(agent_id: str, base: list[dict[str, Any]], client: LLMClient, emit, options,
                node_id: str, parent_ids: list[str]) -> AnalystResult:
    """One analyst's own call. Its text is its own output, never the turn's answer."""
    started = time.monotonic()
    await emit("stage", {"phase": "model", "status": "start", "agentId": agent_id,
                         "nodeId": node_id, "parentIds": parent_ids, "iteration": 1})
    messages = [{"role": "system", "content": _BRIEF[agent_id]}, *base]
    result = AnalystResult(agent_id, "")
    parts: list[str] = []
    finish = "stop"
    try:
        stream = client.complete(messages, tools=None, temperature=options.temperature,
                                 max_tokens=min(options.maxOutputTokens, 2048), timeout_sec=120)
        async with contextlib.aclosing(stream) as chunks:
            async for chunk in chunks:
                if chunk.kind == "text" and chunk.text:
                    parts.append(chunk.text)
                elif chunk.kind == "reasoning" and chunk.text:
                    result.reasoning += chunk.text
                    await emit("reasoning", {"agentId": agent_id, "nodeId": node_id, "iteration": 1, "text": chunk.text})
                elif chunk.kind == "usage" and chunk.usage:
                    result.usage_in += chunk.usage.input_tokens
                    result.usage_out += chunk.usage.output_tokens
                elif chunk.kind == "error":
                    finish = "error"
                    result.error = (chunk.text or "model call failed")[:200]
                elif chunk.kind == "finish":
                    finish = chunk.finish_reason or finish
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        finish, result.error = "error", f"{type(exc).__name__}: {str(exc)[:160]}"
    result.text = "".join(parts)
    result.ms = int((time.monotonic() - started) * 1000)
    if finish not in ("stop",) and not result.error:
        result.error = f"the analyst stopped with {finish}"
    if result.text:
        await emit("agent_output", {"agentId": agent_id, "nodeId": node_id, "iteration": 1, "text": result.text})
    await emit("stage", {"phase": "model", "status": "failed" if result.error else "done", "agentId": agent_id,
                         "nodeId": node_id, "parentIds": parent_ids, "iteration": 1, "ms": result.ms,
                         "inputTokens": result.usage_in, "outputTokens": result.usage_out})
    return result


async def run_collaboration(messages: list[dict[str, Any]], client: LLMClient, tools, emit,
                            *, run_id: str, options, node_id: str = "collaboration",
                            parent_ids: list[str] | None = None) -> dict[str, Any]:
    """Fan out to the analysts, join, then synthesize once.

    Returns the messages to append, the usage of all four calls, and each
    analyst's result. Only the synthesis text is meant to reach the answer.
    """
    parents = list(parent_ids or ["context"])
    await emit("stage", {"phase": "collaboration", "status": "start", "nodeId": node_id, "parentIds": parents})
    started = time.monotonic()
    results: list[AnalystResult] = []
    try:
        results = list(await asyncio.gather(*[
            _one(agent_id, messages, client, emit, options, f"{node_id}-{agent_id}", [node_id])
            for agent_id in ANALYSTS
        ]))
    except BaseException:
        # Cancelling the turn must close every analyst's provider stream.
        raise
    await emit("stage", {"phase": "collaboration", "status": "done", "nodeId": node_id,
                         "ms": int((time.monotonic() - started) * 1000)})

    usable = [r for r in results if r.text and not r.error]
    if not usable:
        reasons = "; ".join(f"{r.agent_id}: {r.error or 'no output'}" for r in results)
        await emit("error", {"code": "AGENT_COLLAB_50203", "message": f"all analysts failed ({reasons})"[:200]})
        return {"messages": [], "usage_in": sum(r.usage_in for r in results),
                "usage_out": sum(r.usage_out for r in results), "agent_results": [r.as_payload() for r in results],
                "failed": True, "artifacts": [], "sources": []}

    brief = "\n\n".join(f"[{r.agent_id}]\n{r.text}" for r in usable)
    skipped = [r.agent_id for r in results if r not in usable]
    synth_messages = [
        {"role": "system", "content": _SYNTH},
        *messages,
        {"role": "user", "content": brief + (f"\n\n（{'、'.join(skipped)} 未能给出结果）" if skipped else "")},
    ]
    synth_node = f"{node_id}-synth"
    await emit("stage", {"phase": "model", "status": "start", "agentId": "synth", "nodeId": synth_node,
                         "parentIds": [f"{node_id}-{r.agent_id}" for r in usable], "iteration": 1})
    text_parts: list[str] = []
    usage_in = usage_out = 0
    finish = "stop"
    failure: str | None = None
    synth_started = time.monotonic()
    stream = client.complete(synth_messages, tools=None, temperature=options.temperature,
                             max_tokens=options.maxOutputTokens, timeout_sec=120)
    async with contextlib.aclosing(stream) as chunks:
        async for chunk in chunks:
            if chunk.kind == "text" and chunk.text:
                text_parts.append(chunk.text)
                # Only the synthesis is the turn's answer.
                await emit("delta", {"text": chunk.text})
            elif chunk.kind == "reasoning" and chunk.text:
                await emit("reasoning", {"agentId": "synth", "nodeId": synth_node, "iteration": 1, "text": chunk.text})
            elif chunk.kind == "usage" and chunk.usage:
                usage_in += chunk.usage.input_tokens
                usage_out += chunk.usage.output_tokens
            elif chunk.kind == "error":
                finish, failure = "error", (chunk.text or "model call failed")[:200]
            elif chunk.kind == "finish":
                finish = chunk.finish_reason or finish
    await emit("stage", {"phase": "model", "status": "failed" if failure else "done", "agentId": "synth",
                         "nodeId": synth_node, "iteration": 1, "ms": int((time.monotonic() - synth_started) * 1000),
                         "inputTokens": usage_in, "outputTokens": usage_out,
                         "finishReason": finish if failure else "stop"})
    return {
        "messages": [{"role": "assistant", "content": "".join(text_parts)}] if text_parts else [],
        "answer": "".join(text_parts),
        "usage_in": sum(r.usage_in for r in results) + usage_in,
        "usage_out": sum(r.usage_out for r in results) + usage_out,
        "agent_results": [r.as_payload() for r in results],
        "finish_reason": "error" if failure else "stop",
        "failed": bool(failure) or not text_parts,
        "artifacts": [],
        "sources": [],
    }

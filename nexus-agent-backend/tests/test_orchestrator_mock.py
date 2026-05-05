"""Smoke test for the orchestrator under the mock pathway.

We patch `ToolExecutor` so we don't need the real Java backend. Redis is also
absent — the memory module gracefully degrades to a no-op when it can't reach
Redis at construction time.
"""
from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

# Force mock pathway (no OpenAI key) regardless of host environment.
os.environ.pop("OPENAI_API_KEY", None)
os.environ["USE_REAL_MODEL"] = "false"
os.environ["REDIS_URL"] = "redis://127.0.0.1:1"  # unreachable -> graceful degrade


from app import orchestrator  # noqa: E402
from app.schemas import Actor, InputPayload, InvokeRequest, Options, Session  # noqa: E402


class _FakeToolExecutor:
    def __init__(self, *_args, **_kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc_info):
        return False

    async def execute(self, tool_name: str, args: dict):
        return {
            "chatId": args.get("chat_id"),
            "messages": [
                {"messageId": 1, "senderId": 10, "senderName": "张三", "content": "明天前确认报价", "createdAt": "2026-05-01T10:00:00+08:00"},
                {"messageId": 2, "senderId": 11, "senderName": "李四", "content": "周五前交付初稿", "createdAt": "2026-05-01T10:05:00+08:00"},
            ],
        }, 12


@pytest.fixture(autouse=True)
def _patch_tools(monkeypatch):
    monkeypatch.setattr(orchestrator, "ToolExecutor", _FakeToolExecutor)


@pytest.mark.asyncio
async def test_chat_summary_emits_meta_tool_delta_done():
    req = InvokeRequest(
        traceId="tr_test_1",
        actor=Actor(userId=1001, username="alice"),
        session=Session(sessionId="as_test_1", operationType="CHAT_SUMMARY"),
        input=InputPayload(text="总结一下", chatId=20001),
        options=Options(),
    )
    events = [e async for e in orchestrator.run_agent(req)]
    names = [e.name for e in events]
    assert names[0] == "meta"
    assert "tool_call" in names and "tool_result" in names
    assert "delta" in names
    assert "usage" in names
    assert names[-2] == "done"
    assert names[-1] == "__final__"

    final = events[-1].data["result"]
    assert final["answer"]
    assert any(tc["toolName"] == "get_recent_messages" and tc["status"] == "SUCCESS" for tc in final["toolCalls"])


@pytest.mark.asyncio
async def test_reply_suggest_returns_structured_draft_and_alternatives():
    req = InvokeRequest(
        traceId="tr_test_2",
        actor=Actor(userId=1001, username="alice"),
        session=Session(sessionId="as_test_2", operationType="REPLY_SUGGEST"),
        input=InputPayload(text="生成回复", chatId=20001, targetMessageId=99, tone="PROFESSIONAL", length="SHORT"),
        options=Options(),
    )
    events = [e async for e in orchestrator.run_agent(req)]
    final = events[-1].data["result"]
    assert final["draft"]
    assert isinstance(final["alternatives"], list) and len(final["alternatives"]) >= 1


@pytest.mark.asyncio
async def test_assistant_chat_no_chat_id_skips_tool_call():
    req = InvokeRequest(
        traceId="tr_test_3",
        actor=Actor(userId=1001, username="alice"),
        session=Session(sessionId="as_test_3", operationType="ASSISTANT_CHAT"),
        input=InputPayload(text="你好"),
        options=Options(),
    )
    events = [e async for e in orchestrator.run_agent(req)]
    assert all(e.name != "tool_call" for e in events), "no tool calls expected without chatId"
    final = events[-1].data["result"]
    assert "你说的是" in final["answer"]

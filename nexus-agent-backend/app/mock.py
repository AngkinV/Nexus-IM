"""Deterministic mock answers used when no real model is configured.

The mock still emits realistic tool calls, structured outputs and token usage so
the entire UX (Java gateway + frontend) can be exercised without an API key.
"""
from __future__ import annotations

import json
from typing import Any

from .schemas import InvokeRequest


def mock_answer(request: InvokeRequest, tool_results: list[dict[str, Any]]) -> dict[str, Any]:
    op = request.session.operationType
    fetched_messages: list[dict[str, Any]] = []
    for tr in tool_results:
        payload = tr.get("payload") or {}
        if isinstance(payload, dict) and isinstance(payload.get("messages"), list):
            fetched_messages = payload["messages"]
            break

    if op == "CHAT_SUMMARY":
        topics = _topics_from(fetched_messages)
        bullets = [
            "主题：" + ("、".join(topics) if topics else "围绕近期工作沟通"),
            "关键结论：双方已就主要议题达成初步一致",
            "风险点：仍有 1-2 项细节待对齐",
            "下一步：明确 Owner 与截止时间，并按节点回执",
        ]
        return {"answer": "\n".join("- " + b for b in bullets)}

    if op == "TODO_EXTRACT":
        todos = []
        if fetched_messages:
            sample = fetched_messages[0]
            todos.append({
                "owner": sample.get("senderName", "未知"),
                "task": "跟进上一条消息所提任务",
                "dueAt": None,
                "confidence": 0.72,
            })
        body = {"todos": todos}
        return {"answer": "```json\n" + json.dumps(body, ensure_ascii=False) + "\n```", "todos": todos}

    if op == "REPLY_SUGGEST":
        tone = request.input.tone or "PROFESSIONAL"
        length = request.input.length or "SHORT"
        primary = "收到，我会在今天下班前补齐并回传。" if tone == "PROFESSIONAL" else "好嘞，今天搞定！"
        alts = [
            "明白，我今天 17:30 前给你最终版本。",
            "好的，已记录，我会在今天内处理完成。",
        ]
        body = {"draft": primary, "alternatives": alts}
        return {
            "answer": "```json\n" + json.dumps(body, ensure_ascii=False) + "\n```",
            "draft": primary,
            "alternatives": alts,
            "_meta": {"tone": tone, "length": length},
        }

    text = request.input.text.strip()
    return {"answer": f"你说的是：「{text}」。我已基于现有上下文给出建议。"}


def _topics_from(messages: list[dict[str, Any]]) -> list[str]:
    keywords: list[str] = []
    for m in messages[:20]:
        content = (m.get("content") or "").strip()
        if not content:
            continue
        for kw in ["报价", "交付", "需求", "上线", "预算", "排期", "风险", "回复", "客户"]:
            if kw in content and kw not in keywords:
                keywords.append(kw)
    return keywords

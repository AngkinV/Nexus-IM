"""Day 4 unit tests for prompts.build_messages relevant_history wiring."""
from __future__ import annotations

import pytest

from app.prompts import build_messages
from app.schemas import Actor, InputPayload, InvokeRequest, Options, Session


def _req(text: str = "你好") -> InvokeRequest:
    return InvokeRequest(
        traceId="tr_test",
        actor=Actor(userId=1, username="alice"),
        session=Session(sessionId="s1", operationType="ASSISTANT_CHAT"),
        input=InputPayload(text=text),
        options=Options(),
    )


def test_relevant_history_omitted_by_default():
    """No relevant_history kw → no '相关历史' system block."""
    msgs = build_messages(_req(), short_term=[], long_term=[])
    contents = [m["content"] for m in msgs]
    assert not any("相关历史" in c for c in contents)


def test_relevant_history_none_is_treated_as_empty():
    msgs = build_messages(_req(), short_term=[], long_term=[], relevant_history=None)
    assert not any("相关历史" in m["content"] for m in msgs)


def test_relevant_history_empty_list_adds_no_block():
    msgs = build_messages(_req(), short_term=[], long_term=[], relevant_history=[])
    assert not any("相关历史" in m["content"] for m in msgs)


def test_relevant_history_renders_as_system_block():
    chunks = [
        "User: 上周报价是多少\nAssistant: 是 50 万",
        "User: 项目进度\nAssistant: 已完成 80%",
    ]
    msgs = build_messages(_req(), short_term=[], long_term=[], relevant_history=chunks)
    blocks = [m for m in msgs if m["role"] == "system" and "相关历史" in m["content"]]
    assert len(blocks) == 1

    body = blocks[0]["content"]
    for chunk in chunks:
        assert chunk in body
    # The label must explicitly tell the model this is auxiliary, not the user's
    # current turn — otherwise the model conflates retrieved content with input.
    assert "仅供参考" in body or "如与当前问题无关请忽略" in body


def test_relevant_history_does_not_replace_short_term():
    """Both blocks must coexist — RAG augments, not replaces, the sliding window."""
    short = [
        {"role": "user", "content": "刚才说的项目"},
        {"role": "assistant", "content": "好的，我记住了"},
    ]
    msgs = build_messages(
        _req(), short_term=short, long_term=[], relevant_history=["older context"],
    )
    user_msgs = [m for m in msgs if m["role"] == "user" and "刚才说的项目" in m["content"]]
    assert len(user_msgs) == 1
    assert any("older context" in m["content"] for m in msgs if m["role"] == "system")


def test_long_term_and_relevant_history_render_as_distinct_blocks():
    msgs = build_messages(
        _req(),
        short_term=[],
        long_term=[{"memoryType": "PREF", "content": "用户喜欢简洁回复"}],
        relevant_history=["older retrieved text"],
    )
    sys_blocks = [m for m in msgs if m["role"] == "system"]
    has_long_term = any("长期记忆" in m["content"] for m in sys_blocks)
    has_relevant = any("相关历史" in m["content"] for m in sys_blocks)
    assert has_long_term and has_relevant


# -------- Module B: kb_context --------


def test_kb_context_omitted_by_default():
    msgs = build_messages(_req(), short_term=[], long_term=[])
    assert not any("知识库参考片段" in m["content"] for m in msgs)


def test_kb_context_empty_string_adds_no_block():
    msgs = build_messages(_req(), short_term=[], long_term=[], kb_context="")
    assert not any("知识库参考片段" in m["content"] for m in msgs)


def test_kb_context_renders_as_system_block():
    body = "[1] contract.pdf#3 (score=0.910):\n违约金不超过合同总额 20%"
    msgs = build_messages(_req(), short_term=[], long_term=[], kb_context=body)
    blocks = [m for m in msgs if m["role"] == "system" and "知识库参考片段" in m["content"]]
    assert len(blocks) == 1
    assert "violation".lower() not in blocks[0]["content"]  # sanity: no leak from elsewhere
    assert body in blocks[0]["content"]
    # The framing must direct the model to cite — that's what makes RAG answers useful.
    assert "[n]" in blocks[0]["content"] and "文件名" in blocks[0]["content"]


def test_kb_context_coexists_with_relevant_history():
    msgs = build_messages(
        _req(),
        short_term=[],
        long_term=[],
        relevant_history=["older user/assistant exchange"],
        kb_context="[1] doc.txt#0:\nsome chunk",
    )
    sys_blocks = [m["content"] for m in msgs if m["role"] == "system"]
    assert any("相关历史" in c for c in sys_blocks)
    assert any("知识库参考片段" in c for c in sys_blocks)


def test_kb_context_appears_after_relevant_history():
    """Ordering: long_term → relevant_history → kb_context → short_term turns.
    The conversation context (relevant_history) frames the question; document
    context (kb_context) is supporting evidence the model should cite into."""
    msgs = build_messages(
        _req(),
        short_term=[],
        long_term=[],
        relevant_history=["semantic recall A"],
        kb_context="[1] x.txt#0:\nfact",
    )
    history_idx = next(i for i, m in enumerate(msgs) if "相关历史" in m["content"])
    kb_idx = next(i for i, m in enumerate(msgs) if "知识库参考片段" in m["content"])
    assert history_idx < kb_idx

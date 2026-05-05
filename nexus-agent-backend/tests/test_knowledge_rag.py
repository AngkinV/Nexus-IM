"""Day 11 tests for app.rag.knowledge_rag.

Module B retrieval mirrors Module A in shape — same deterministic local
embedder, same isolation pattern. The novelty here is filtering by kbId
(not userId), with optional userId as a second guardrail.
"""
from __future__ import annotations

from pathlib import Path

import pytest

from app.config import get_settings
from app.knowledge import ingestion as ing
from app.rag import embeddings as emb_mod
from app.rag import knowledge_rag, vectorstore as vs


class _DetEmbedding:
    """Same trivial deterministic embedder used elsewhere in tests."""

    def __init__(self, dim: int = 32):
        self.dim = dim

    def _vec(self, text: str) -> list[float]:
        v = [0.0] * self.dim
        for i, c in enumerate(text):
            v[i % self.dim] += float(ord(c)) / 1000.0
        v[0] += float(len(text)) / 1000.0
        return v

    def embed_documents(self, texts):
        return [self._vec(t) for t in texts]

    def embed_query(self, text):
        return self._vec(text)


@pytest.fixture(autouse=True)
def _isolate(tmp_path: Path, monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "chroma_persist_dir", str(tmp_path / "chroma"))
    monkeypatch.setattr(s, "knowledge_rag_enabled", True)
    monkeypatch.setattr(s, "knowledge_rag_top_k", 3)

    vs.reset_for_testing()
    emb_mod.reset_cache()

    fake = lambda **kw: _DetEmbedding()  # noqa: E731
    monkeypatch.setattr(ing, "get_embeddings", fake)
    monkeypatch.setattr(knowledge_rag, "get_embeddings", fake)

    yield

    vs.reset_for_testing()
    emb_mod.reset_cache()


async def _seed_doc(tmp_path: Path, kb_id: str, doc_id: str, text: str, *, user_id: int = 1):
    fp = tmp_path / f"{doc_id}.txt"
    fp.write_text(text, encoding="utf-8")
    await ing.ingest_document(
        kb_id=kb_id, doc_id=doc_id, file_path=str(fp), file_type="txt",
        file_name=f"{doc_id}.txt", user_id=user_id,
        chunk_size=128, chunk_overlap=16,
    )


async def test_retrieve_returns_chunks_for_seeded_kb(tmp_path: Path):
    await _seed_doc(tmp_path, "kb_alpha", "doc1", "项目报价单和合同条款 " * 30)

    pairs = await knowledge_rag.retrieve("kb_alpha", "报价单")
    assert len(pairs) >= 1
    doc, score = pairs[0]
    assert "报价单" in doc.page_content
    assert isinstance(score, float)
    assert doc.metadata["kbId"] == "kb_alpha"


async def test_retrieve_isolates_kbs(tmp_path: Path):
    await _seed_doc(tmp_path, "kb_a", "doc_a", "alice 私人合同 " * 30, user_id=1)
    await _seed_doc(tmp_path, "kb_b", "doc_b", "bob 私人合同 " * 30, user_id=2)

    a_pairs = await knowledge_rag.retrieve("kb_a", "私人合同")
    assert all(d.metadata["kbId"] == "kb_a" for d, _ in a_pairs)
    assert all("bob" not in d.page_content for d, _ in a_pairs)


async def test_retrieve_with_user_filter_blocks_other_user(tmp_path: Path):
    # Force the same kbId for two different users via the metadata; this
    # simulates a hostile request that forged a kbId belonging to another
    # tenant. The userId filter must hold.
    await _seed_doc(tmp_path, "kb_shared", "doc_x", "secret content " * 30, user_id=1)

    pairs = await knowledge_rag.retrieve("kb_shared", "secret", user_id=999)
    assert pairs == []


async def test_empty_query_returns_empty(tmp_path: Path):
    await _seed_doc(tmp_path, "kb_z", "doc_z", "anything goes here " * 20)
    assert await knowledge_rag.retrieve("kb_z", "") == []
    assert await knowledge_rag.retrieve("kb_z", "   ") == []


async def test_disabled_flag_returns_empty(tmp_path: Path, monkeypatch):
    await _seed_doc(tmp_path, "kb_e", "doc_e", "hello world " * 20)
    s = get_settings()
    monkeypatch.setattr(s, "knowledge_rag_enabled", False)

    assert await knowledge_rag.retrieve("kb_e", "hello") == []


async def test_top_k_caps_results(tmp_path: Path):
    for i in range(6):
        await _seed_doc(tmp_path, "kb_k", f"doc_{i}", f"content {i} " * 30)

    pairs = await knowledge_rag.retrieve("kb_k", "content", top_k=2)
    assert len(pairs) <= 2


async def test_no_embedder_returns_empty(tmp_path: Path, monkeypatch):
    await _seed_doc(tmp_path, "kb_x", "doc_x", "content " * 20)
    monkeypatch.setattr(knowledge_rag, "get_embeddings", lambda **kw: None)

    assert await knowledge_rag.retrieve("kb_x", "content") == []


# --------------- qa.build_kb_context ---------------


def test_build_kb_context_renders_chunks_with_citations():
    from langchain_core.documents import Document

    from app.knowledge.qa import build_kb_context

    pairs = [
        (Document(page_content="第一段内容", metadata={"fileName": "a.txt", "chunkIndex": 0}), 0.91),
        (Document(page_content="第二段内容", metadata={"fileName": "a.txt", "chunkIndex": 1}), 0.85),
    ]
    out = build_kb_context(pairs)
    assert "[1] a.txt#0" in out
    assert "[2] a.txt#1" in out
    assert "第一段内容" in out
    assert "score=0.910" in out


def test_build_kb_context_empty_input_returns_empty_string():
    from app.knowledge.qa import build_kb_context
    assert build_kb_context([]) == ""


def test_build_kb_context_truncates_long_chunks():
    from langchain_core.documents import Document

    from app.knowledge.qa import MAX_CHUNK_CHARS, build_kb_context

    long_text = "x" * (MAX_CHUNK_CHARS + 200)
    pairs = [(Document(page_content=long_text, metadata={}), 0.5)]
    out = build_kb_context(pairs)
    assert "…" in out
    # The header line plus a body line; the body must not exceed cap+1 ellipsis.
    body = out.split("\n", 1)[1]
    assert len(body) <= MAX_CHUNK_CHARS + 1

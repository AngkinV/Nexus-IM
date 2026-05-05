"""Day 3 unit tests for app.rag.memory_rag.

We patch `memory_rag.get_embeddings` with a deterministic local embedding so
tests don't hit OpenAI. Same input → same vector, which is enough to prove
write+retrieve roundtrips, user isolation, and degradation paths.
"""
from __future__ import annotations

from pathlib import Path

import pytest

from app.config import get_settings
from app.rag import embeddings as emb_mod
from app.rag import memory_rag, vectorstore as vs


class _DetEmbedding:
    """Deterministic embedding: same text → same vector. Stable across runs."""

    def __init__(self, dim: int = 32):
        self.dim = dim

    def _vec(self, text: str) -> list[float]:
        v = [0.0] * self.dim
        for i, c in enumerate(text):
            v[i % self.dim] += float(ord(c)) / 1000.0
        # add a length signal so different-length texts diverge
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
    monkeypatch.setattr(s, "memory_rag_enabled", True)
    monkeypatch.setattr(s, "memory_rag_min_chars", 5)
    monkeypatch.setattr(s, "memory_rag_top_k", 3)

    vs.reset_for_testing()
    emb_mod.reset_cache()

    # Patch the module-local reference that memory_rag actually calls.
    monkeypatch.setattr(memory_rag, "get_embeddings", lambda **kw: _DetEmbedding())

    yield

    vs.reset_for_testing()
    emb_mod.reset_cache()


async def test_write_returns_chunk_id_on_success():
    cid = await memory_rag.write(1, "s1", "你好", "你好我是 AI")
    assert cid is not None
    assert cid.startswith("mem_")


async def test_retrieve_finds_recently_written_content():
    await memory_rag.write(1, "s1", "讨论项目报价单", "已收到将整理")
    results = await memory_rag.retrieve(1, "讨论项目报价单")
    assert len(results) >= 1
    assert "讨论项目报价单" in results[0]


async def test_retrieve_isolates_users():
    await memory_rag.write(1, "s1", "alice 私人内容", "记下了 alice")
    await memory_rag.write(2, "s2", "bob 私人内容", "记下了 bob")

    user2_results = await memory_rag.retrieve(2, "私人内容")
    assert all("alice" not in r for r in user2_results)


async def test_skip_when_text_too_short():
    cid = await memory_rag.write(1, "s1", "a", "b")  # combined far below min_chars=5
    assert cid is None


async def test_skip_when_disabled(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "memory_rag_enabled", False)

    cid = await memory_rag.write(1, "s1", "long enough text here", "long answer here")
    assert cid is None
    results = await memory_rag.retrieve(1, "anything")
    assert results == []


async def test_top_k_caps_results():
    for i in range(5):
        await memory_rag.write(1, "s1", f"chunk number {i}", f"answer for {i}")
    results = await memory_rag.retrieve(1, "chunk number", top_k=2)
    assert len(results) <= 2


async def test_degrades_when_embeddings_unavailable(monkeypatch):
    monkeypatch.setattr(memory_rag, "get_embeddings", lambda **kw: None)

    cid = await memory_rag.write(1, "s1", "hello world", "hi there")
    assert cid is None

    results = await memory_rag.retrieve(1, "hello")
    assert results == []


async def test_empty_query_returns_empty():
    await memory_rag.write(1, "s1", "some content", "some reply")
    assert await memory_rag.retrieve(1, "") == []
    assert await memory_rag.retrieve(1, "   ") == []


async def test_metadata_includes_trace_id():
    """The trace_id, when supplied, is persisted on the chunk metadata so
    operators can correlate vector-store entries with request logs."""
    cid = await memory_rag.write(
        1, "s1", "hello world here", "hi there friend", trace_id="tr_abc123"
    )
    assert cid is not None

    # Inspect the underlying ChromaDB collection directly.
    col = vs.get_or_create_collection(vs.MEMORY_COLLECTION)
    rec = col.get(ids=[cid])
    assert rec["metadatas"][0]["traceId"] == "tr_abc123"
    assert rec["metadatas"][0]["userId"] == 1

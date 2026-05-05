"""Day 6 end-to-end acceptance tests for Sprint 1 Module A.

Acceptance criteria (per RAG扩展实施方案.md §6 Sprint 1):
  1. After 50+ rounds of conversation, asking about an early topic should
     surface the corresponding memory chunk via similarity_search.
  2. ChromaDB persistence directory contains data on disk.
  3. Disabling memory_rag_enabled returns the system to short-term-only
     behavior (already covered indirectly by test_orchestrator_rag.py;
     this file pins the contract for the memory_rag module specifically).

These tests use a real ChromaDB persistent client against a tmp directory and
a deterministic local fake embedding (no OpenAI calls).
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

os.environ.pop("OPENAI_API_KEY", None)
os.environ["USE_REAL_MODEL"] = "false"
os.environ["REDIS_URL"] = "redis://127.0.0.1:1"


from app.config import get_settings  # noqa: E402
from app.rag import embeddings as emb_mod  # noqa: E402
from app.rag import memory_rag, vectorstore as vs  # noqa: E402


class _DetEmbedding:
    """Wider deterministic fake embedding (dim=128) for stable distance ranking
    even when the corpus has many similar-shaped chunks. Same input → same vec.
    """

    def __init__(self, dim: int = 128):
        self.dim = dim

    def _vec(self, text: str) -> list[float]:
        v = [0.0] * self.dim
        for i, c in enumerate(text):
            v[i % self.dim] += float(ord(c)) / 1000.0
        # length signal in slot 0 helps distinguish very different lengths
        v[0] += float(len(text)) / 1000.0
        return v

    def embed_documents(self, texts):
        return [self._vec(t) for t in texts]

    def embed_query(self, text):
        return self._vec(text)


@pytest.fixture
def isolated_chroma(tmp_path: Path, monkeypatch):
    """Per-test ChromaDB directory + deterministic embedding."""
    s = get_settings()
    monkeypatch.setattr(s, "chroma_persist_dir", str(tmp_path / "chroma"))
    monkeypatch.setattr(s, "memory_rag_enabled", True)
    monkeypatch.setattr(s, "memory_rag_min_chars", 5)
    monkeypatch.setattr(s, "memory_rag_top_k", 3)

    vs.reset_for_testing()
    emb_mod.reset_cache()
    monkeypatch.setattr(memory_rag, "get_embeddings", lambda **kw: _DetEmbedding())

    yield tmp_path / "chroma"

    vs.reset_for_testing()
    emb_mod.reset_cache()


# ---------------------------------------------------------------------------
# Acceptance criterion #1: recall an early chunk after 50 rounds of noise.
# ---------------------------------------------------------------------------
async def test_recall_early_topic_after_50_rounds_of_noise(isolated_chroma):
    user_id = 12345
    session_id = "s_acceptance_1"

    # Round 1: a uniquely-identifiable topic the user mentions once.
    early_token = "PROJECT-ALPHA-Q3-BUDGET-OWNER"
    await memory_rag.write(
        user_id, session_id,
        f"我们的 {early_token} 是谁负责",
        f"我帮你查一下 {early_token} 的负责人",
    )

    # Rounds 2..51: 50 rounds of unrelated exchanges to push round 1 far out
    # of any short-term sliding window we could imagine.
    for i in range(2, 52):
        await memory_rag.write(
            user_id, session_id,
            f"第 {i} 轮闲聊：今天天气怎么样",
            f"第 {i} 轮回答：天气晴朗",
        )

    # Round 52: ask about the early topic using the same unique token.
    results = await memory_rag.retrieve(
        user_id, f"提一下 {early_token}", top_k=3,
    )

    assert results, "retrieve returned nothing — 50-round recall completely failed"
    assert any(early_token in r for r in results), (
        f"early topic was not in top-{len(results)} after 50 rounds; got: {results}"
    )


# ---------------------------------------------------------------------------
# Acceptance criterion #2: ChromaDB writes to disk so data survives restart.
# ---------------------------------------------------------------------------
async def test_chroma_persists_to_disk(isolated_chroma: Path):
    cid = await memory_rag.write(
        9999, "s_persist",
        "持久化测试用户消息一段较长内容",
        "持久化测试模型答复也较长",
    )
    assert cid is not None

    # Force a read so any in-memory buffer flushes; Chroma persists eagerly,
    # but explicitly checking is a stronger contract.
    col = vs.get_or_create_collection(vs.MEMORY_COLLECTION)
    rec = col.get(ids=[cid])
    assert rec["documents"], "chunk not retrievable by id immediately after write"

    chroma_files = [f for f in isolated_chroma.rglob("*") if f.is_file()]
    assert chroma_files, f"chroma_persist_dir is empty: {isolated_chroma}"
    # Chroma 0.5 writes its primary state to a sqlite file.
    assert any(f.suffix in {".sqlite3", ".bin", ".parquet", ".db"} or f.name.startswith("chroma")
               for f in chroma_files), \
        f"unexpected files-only-no-state in chroma_persist_dir: {[f.name for f in chroma_files]}"


# ---------------------------------------------------------------------------
# Stronger isolation: cross-user leakage cannot happen even at 50-round scale.
# ---------------------------------------------------------------------------
async def test_user_isolation_holds_across_50_rounds(isolated_chroma):
    secret = "USER1-PRIVATE-API-KEY-LEAK-CANARY"

    await memory_rag.write(1, "s1", f"我的密钥是 {secret}", "记下了")

    for i in range(50):
        await memory_rag.write(2, "s2", f"user 2 round {i} 闲聊问题", f"user 2 round {i} 答复")

    # User 2 actively tries to retrieve content that includes user 1's secret.
    results = await memory_rag.retrieve(2, "我的密钥是什么", top_k=10)
    assert not any(secret in r for r in results), (
        f"User isolation broken: user 2 retrieved user 1's secret. results={results}"
    )


# ---------------------------------------------------------------------------
# Acceptance criterion #3: disabling the flag must hard-degrade both paths.
# ---------------------------------------------------------------------------
async def test_disabled_flag_yields_no_writes_no_reads(monkeypatch, isolated_chroma):
    s = get_settings()
    monkeypatch.setattr(s, "memory_rag_enabled", False)

    cid = await memory_rag.write(
        1, "s_disabled", "this should not be persisted at all", "neither this",
    )
    assert cid is None

    results = await memory_rag.retrieve(1, "anything")
    assert results == []

    # And the underlying collection must be empty for this user.
    col = vs.get_or_create_collection(vs.MEMORY_COLLECTION)
    listing = col.get(where={"userId": 1})
    assert not listing["ids"], "disabled mode still wrote chunks"


# ---------------------------------------------------------------------------
# top_k must cap output even when corpus is large.
# ---------------------------------------------------------------------------
async def test_top_k_cap_holds_with_large_corpus(isolated_chroma):
    user_id = 777
    for i in range(40):
        await memory_rag.write(
            user_id, "s_top_k",
            f"topic-{i:03d} content",
            f"answer-{i:03d}",
        )

    results = await memory_rag.retrieve(user_id, "topic-007 content", top_k=5)
    assert 0 < len(results) <= 5

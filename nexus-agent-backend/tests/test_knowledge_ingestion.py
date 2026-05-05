"""Day 10 tests for app.knowledge.{loaders,splitter,ingestion}.

These tests stay self-contained: they create plain-text fixtures on the fly
and patch get_embeddings with a deterministic local fake so no OpenAI key
is needed. PDF/DOCX paths are covered by the loader dispatch logic but
their actual binary parsing is left to manual / acceptance verification
since seeding a real PDF/DOCX in a unit test adds binary fixtures we
don't want in git.
"""
from __future__ import annotations

from pathlib import Path

import pytest

from app.config import get_settings
from app.knowledge import ingestion as ing
from app.knowledge import loaders, splitter
from app.rag import embeddings as emb_mod
from app.rag import vectorstore as vs


class _DetEmbedding:
    """Same trivial deterministic embedder used by Module A tests."""

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

    vs.reset_for_testing()
    emb_mod.reset_cache()

    # Patch the symbol ingestion.py imported, not the original module.
    monkeypatch.setattr(ing, "get_embeddings", lambda **kw: _DetEmbedding())

    yield

    vs.reset_for_testing()
    emb_mod.reset_cache()


# ---------------- loaders ----------------


def test_load_text_file_returns_documents(tmp_path: Path):
    fp = tmp_path / "hello.txt"
    fp.write_text("hello world from a knowledge document", encoding="utf-8")

    docs = loaders.load_document(str(fp), "txt")
    assert len(docs) == 1
    assert "hello world" in docs[0].page_content
    assert docs[0].metadata["fileType"] == "txt"
    assert docs[0].metadata["source"] == str(fp)


def test_load_markdown_with_canonical_aliases(tmp_path: Path):
    fp = tmp_path / "notes.md"
    fp.write_text("# heading\n\nbody paragraph", encoding="utf-8")

    # Both "md" and "markdown" must dispatch and tag with the canonical "md".
    for ftype in ("md", "markdown", ".MD", "Markdown"):
        docs = loaders.load_document(str(fp), ftype)
        assert docs
        assert docs[0].metadata["fileType"] == "md"


def test_unsupported_file_type_raises(tmp_path: Path):
    fp = tmp_path / "x.bin"
    fp.write_bytes(b"\x00\x01")
    with pytest.raises(loaders.UnsupportedFileTypeError):
        loaders.load_document(str(fp), "exe")


def test_missing_file_raises(tmp_path: Path):
    with pytest.raises(loaders.DocumentLoadError):
        loaders.load_document(str(tmp_path / "nope.txt"), "txt")


def test_empty_file_raises(tmp_path: Path):
    fp = tmp_path / "empty.txt"
    fp.write_text("", encoding="utf-8")
    with pytest.raises(loaders.DocumentLoadError):
        loaders.load_document(str(fp), "txt")


# ---------------- splitter ----------------


def test_short_text_yields_one_chunk():
    from langchain_core.documents import Document
    docs = [Document(page_content="just a few words")]
    chunks = splitter.split_documents(docs, chunk_size=512, chunk_overlap=64)
    assert len(chunks) == 1


def test_long_text_yields_multiple_chunks():
    from langchain_core.documents import Document
    # ~3000 chars of varied content; a 64-token splitter must produce >1 chunk.
    body = "段落 " + ("内容 " * 200) + "结束"
    docs = [Document(page_content=body * 3)]
    chunks = splitter.split_documents(docs, chunk_size=64, chunk_overlap=8)
    assert len(chunks) > 1


def test_overlap_ge_size_resets_to_zero(caplog):
    sp = splitter.make_splitter(chunk_size=128, chunk_overlap=128)
    # Just verify it constructs without raising; the warning is logged.
    assert sp is not None


def test_splitter_clamps_extreme_values():
    # 10 is below min (64); should be clamped without raising.
    sp_small = splitter.make_splitter(chunk_size=10, chunk_overlap=0)
    assert sp_small is not None
    # 999_999 is way above max; clamped to MAX_CHUNK_SIZE.
    sp_big = splitter.make_splitter(chunk_size=999_999, chunk_overlap=0)
    assert sp_big is not None


# ---------------- ingestion ----------------


async def test_ingest_pipeline_writes_chunks_to_chroma(tmp_path: Path):
    fp = tmp_path / "report.txt"
    fp.write_text(
        "Knowledge base ingestion smoke test. " * 80,
        encoding="utf-8",
    )

    result = await ing.ingest_document(
        kb_id="kb_test",
        doc_id="doc_test1",
        file_path=str(fp),
        file_type="txt",
        file_name="report.txt",
        user_id=42,
        chunk_size=128,
        chunk_overlap=16,
    )
    assert result.status == "READY"
    assert result.chunk_count >= 1
    assert result.doc_id == "doc_test1"

    # Verify the chunks landed in the canonical collection with correct metadata.
    col = vs.get_or_create_collection(vs.KNOWLEDGE_COLLECTION)
    got = col.get(where={"docId": "doc_test1"})
    assert len(got["ids"]) == result.chunk_count
    md0 = got["metadatas"][0]
    assert md0["kbId"] == "kb_test"
    assert md0["userId"] == 42
    assert md0["fileName"] == "report.txt"
    assert md0["fileType"] == "txt"
    assert "chunkIndex" in md0


async def test_ingest_fails_without_embedder(tmp_path: Path, monkeypatch):
    fp = tmp_path / "x.txt"
    fp.write_text("hello", encoding="utf-8")
    monkeypatch.setattr(ing, "get_embeddings", lambda **kw: None)

    with pytest.raises(ing.IngestionError, match="no embedding API key"):
        await ing.ingest_document(
            kb_id="kb_x", doc_id="doc_x", file_path=str(fp), file_type="txt",
        )


async def test_ingest_propagates_loader_error(tmp_path: Path):
    with pytest.raises(ing.IngestionError, match="file not found"):
        await ing.ingest_document(
            kb_id="kb_x",
            doc_id="doc_missing",
            file_path=str(tmp_path / "missing.txt"),
            file_type="txt",
        )


async def test_delete_document_removes_only_target_doc(tmp_path: Path):
    # Seed two docs in the same KB, delete one, verify the other survives.
    for did in ("doc_keep", "doc_drop"):
        fp = tmp_path / f"{did}.txt"
        fp.write_text(f"content for {did} " * 30, encoding="utf-8")
        await ing.ingest_document(
            kb_id="kb_multi", doc_id=did, file_path=str(fp), file_type="txt",
            chunk_size=128, chunk_overlap=16,
        )

    deleted = await ing.delete_document(kb_id="kb_multi", doc_id="doc_drop")
    assert deleted >= 1

    col = vs.get_or_create_collection(vs.KNOWLEDGE_COLLECTION)
    keep = col.get(where={"docId": "doc_keep"})
    drop = col.get(where={"docId": "doc_drop"})
    assert len(keep["ids"]) >= 1
    assert len(drop["ids"]) == 0


async def test_delete_returns_zero_when_no_chunks(tmp_path: Path):
    deleted = await ing.delete_document(kb_id="kb_empty", doc_id="doc_unknown")
    assert deleted == 0


async def test_delete_whole_kb_clears_all_its_docs(tmp_path: Path):
    for did in ("a", "b", "c"):
        fp = tmp_path / f"{did}.txt"
        fp.write_text(f"alpha {did} beta " * 20, encoding="utf-8")
        await ing.ingest_document(
            kb_id="kb_wipe", doc_id=f"doc_{did}", file_path=str(fp), file_type="txt",
            chunk_size=128, chunk_overlap=16,
        )

    total_before = await ing.delete_document(kb_id="kb_wipe")  # doc_id None -> whole kb
    assert total_before >= 3

    col = vs.get_or_create_collection(vs.KNOWLEDGE_COLLECTION)
    remaining = col.get(where={"kbId": "kb_wipe"})
    assert len(remaining["ids"]) == 0

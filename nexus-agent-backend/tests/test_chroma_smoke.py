"""Day 1 smoke test: ChromaDB persistent client can boot, create a collection,
upsert + query a vector, and survive a process restart (PersistentClient).

This is intentionally minimal — it validates that the dependency stack is
healthy on this machine before we build the memory RAG layer on top of it.
"""
from __future__ import annotations

import shutil
from pathlib import Path

import pytest


def _tmp_chroma_dir(tmp_path: Path) -> str:
    p = tmp_path / "chroma"
    p.mkdir(parents=True, exist_ok=True)
    return str(p)


def test_chroma_persistent_client_boots(tmp_path: Path) -> None:
    import chromadb
    from chromadb.config import Settings as ChromaSettings

    persist_dir = _tmp_chroma_dir(tmp_path)
    client = chromadb.PersistentClient(
        path=persist_dir,
        settings=ChromaSettings(anonymized_telemetry=False),
    )
    assert client is not None
    # Persistence directory is created lazily on first write — but the client itself
    # must be instantiable without raising.


def test_chroma_collection_roundtrip_with_explicit_embeddings(tmp_path: Path) -> None:
    """Validate add → query without depending on OpenAI: we pass embeddings explicitly."""
    import chromadb
    from chromadb.config import Settings as ChromaSettings

    persist_dir = _tmp_chroma_dir(tmp_path)
    client = chromadb.PersistentClient(
        path=persist_dir,
        settings=ChromaSettings(anonymized_telemetry=False),
    )
    col = client.get_or_create_collection(name="smoke_test")

    col.add(
        ids=["a", "b"],
        embeddings=[[1.0, 0.0, 0.0], [0.0, 1.0, 0.0]],
        documents=["hello", "world"],
        metadatas=[{"k": "x"}, {"k": "y"}],
    )

    res = col.query(query_embeddings=[[1.0, 0.0, 0.0]], n_results=1)
    ids = res.get("ids", [[]])[0]
    docs = res.get("documents", [[]])[0]
    assert ids == ["a"]
    assert docs == ["hello"]


def test_chroma_persistence_survives_restart(tmp_path: Path) -> None:
    """A second PersistentClient on the same path must see the prior write."""
    import chromadb
    from chromadb.config import Settings as ChromaSettings

    persist_dir = _tmp_chroma_dir(tmp_path)

    client1 = chromadb.PersistentClient(
        path=persist_dir,
        settings=ChromaSettings(anonymized_telemetry=False),
    )
    col1 = client1.get_or_create_collection(name="persist_test")
    col1.add(ids=["only"], embeddings=[[0.5, 0.5, 0.0]], documents=["persisted"])
    del client1  # simulate process exit

    client2 = chromadb.PersistentClient(
        path=persist_dir,
        settings=ChromaSettings(anonymized_telemetry=False),
    )
    col2 = client2.get_collection(name="persist_test")
    res = col2.get(ids=["only"])
    assert res["documents"] == ["persisted"]


@pytest.fixture(autouse=True)
def _cleanup_default_chroma_dir():
    """Some tests may inadvertently create ./data/chroma — wipe between runs."""
    yield
    default = Path("./data/chroma")
    if default.exists():
        shutil.rmtree(default, ignore_errors=True)

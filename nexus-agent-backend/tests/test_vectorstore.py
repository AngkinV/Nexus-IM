"""Day 2 unit tests for `app.rag.vectorstore` (ChromaDB lifecycle helpers)."""
from __future__ import annotations

from pathlib import Path

import pytest

from app.config import get_settings
from app.rag import vectorstore as vs


@pytest.fixture(autouse=True)
def _reset_state():
    vs.reset_for_testing()
    yield
    vs.reset_for_testing()


def test_client_is_singleton(tmp_path: Path, monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "chroma_persist_dir", str(tmp_path / "chroma"))
    a = vs.get_chroma_client()
    b = vs.get_chroma_client()
    assert a is b


def test_persist_dir_created_lazily(tmp_path: Path, monkeypatch):
    s = get_settings()
    target = tmp_path / "nested" / "chroma"
    monkeypatch.setattr(s, "chroma_persist_dir", str(target))

    assert not target.exists()
    vs.get_chroma_client()
    assert target.exists() and target.is_dir()


def test_get_or_create_collection_idempotent(tmp_path: Path, monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "chroma_persist_dir", str(tmp_path / "chroma"))

    col1 = vs.get_or_create_collection("rag_test_col", metadata={"v": "1"})
    col2 = vs.get_or_create_collection("rag_test_col")
    assert col1.name == col2.name
    assert col1.id == col2.id


def test_canonical_collection_names_exposed():
    assert vs.MEMORY_COLLECTION == "memory_chunks"
    assert vs.KNOWLEDGE_COLLECTION == "kb_chunks"


def test_reset_for_testing_rebuilds_client(tmp_path: Path, monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "chroma_persist_dir", str(tmp_path / "chroma"))

    a = vs.get_chroma_client()
    vs.reset_for_testing()
    b = vs.get_chroma_client()
    assert a is not b

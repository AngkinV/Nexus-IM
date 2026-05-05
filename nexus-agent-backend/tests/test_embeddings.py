"""Day 2 unit tests for `app.rag.embeddings`."""
from __future__ import annotations

import pytest

from app.config import get_settings
from app.rag import embeddings as emb_mod


@pytest.fixture(autouse=True)
def _reset_cache():
    emb_mod.reset_cache()
    yield
    emb_mod.reset_cache()


def test_returns_none_when_no_key(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "openai_api_key", None)
    # Also clear the embedding-specific key — EMBEDDING_PROVIDER presets in
    # .env now populate this independently of openai_api_key, so the "no key
    # resolvable" path requires both to be empty.
    monkeypatch.setattr(s, "embedding_api_key", None)
    assert emb_mod.get_embeddings() is None


def test_explicit_args_override_settings(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "openai_api_key", "settings-key")
    monkeypatch.setattr(s, "embedding_model", "text-embedding-3-small")

    result = emb_mod.get_embeddings(
        api_key="explicit-key",
        model="text-embedding-3-large",
        base_url="https://example.com/v1",
    )
    assert result is not None
    assert result.model == "text-embedding-3-large"
    assert result.openai_api_key.get_secret_value() == "explicit-key"


def test_settings_used_when_args_absent(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "openai_api_key", "from-settings")
    # Clear embedding_api_key so the resolution chain falls through to
    # openai_api_key (preset in .env may have populated embedding_api_key).
    monkeypatch.setattr(s, "embedding_api_key", None)
    monkeypatch.setattr(s, "embedding_model", "text-embedding-3-small")

    result = emb_mod.get_embeddings()
    assert result is not None
    assert result.model == "text-embedding-3-small"
    assert result.openai_api_key.get_secret_value() == "from-settings"


def test_cache_returns_same_instance_for_same_tuple(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "openai_api_key", "k")
    monkeypatch.setattr(s, "embedding_model", "text-embedding-3-small")

    a = emb_mod.get_embeddings()
    b = emb_mod.get_embeddings()
    assert a is b


def test_cache_separates_by_model(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "openai_api_key", "k")

    a = emb_mod.get_embeddings(model="text-embedding-3-small")
    b = emb_mod.get_embeddings(model="text-embedding-3-large")
    assert a is not b


def test_reset_cache_drops_instances(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "openai_api_key", "k")

    a = emb_mod.get_embeddings()
    emb_mod.reset_cache()
    b = emb_mod.get_embeddings()
    assert a is not b

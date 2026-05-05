"""Embedding service wrapper.

Resolution priority for (api_key, base_url, model):
    1. explicit kwargs (caller has already decided which key to use — typically
       a per-KB override, NOT the BYOK chat key)
    2. dedicated embedding settings (`embedding_api_key`, `embedding_base_url`)
       — real-world deployments use these when chat is BYOK on a provider
       that doesn't expose `/embeddings` (DeepSeek/Moonshot/Groq/...).
    3. server-default OpenAI settings (`openai_api_key`, `openai_base_url`)

We deliberately do NOT consult the BYOK chat provider here. Callers who want
to try the BYOK chat key for embeddings must pass it explicitly via api_key=,
because most non-OpenAI BYOK providers will 404/400 on /embeddings or reject
"text-embedding-3-small" as an unknown model — silently using the chat key
masks that with "vectorstore write failed" instead of a clear root cause.

Returns None when no API key can be resolved — callers must handle the
degraded path (skip RAG write/retrieve, fall back to short-term memory).

Instances are cached by (model, api_key, base_url) to avoid recreating the
underlying OpenAI client on every request.
"""
from __future__ import annotations

import logging
from typing import Optional

from langchain_openai import OpenAIEmbeddings

from ..config import get_settings

log = logging.getLogger(__name__)

_cache: dict[tuple[str, str, str], OpenAIEmbeddings] = {}


def get_embeddings(
    *,
    api_key: Optional[str] = None,
    base_url: Optional[str] = None,
    model: Optional[str] = None,
) -> Optional[OpenAIEmbeddings]:
    """Return a cached OpenAI embedding client, or None if no key is available.

    See module docstring for the resolution chain. ``api_key=None`` (the
    common case) means "use server-side embedding/openai defaults" — that's
    the right call when the caller has a BYOK chat provider that isn't
    OpenAI-compatible for embeddings.
    """
    s = get_settings()
    resolved_key = api_key or s.embedding_api_key or s.openai_api_key
    resolved_base = base_url or s.embedding_base_url or s.openai_base_url
    resolved_model = model or s.embedding_model

    if not resolved_key:
        log.warning(
            "Embeddings disabled: no API key resolved (model=%s, base_url=%s). "
            "Set EMBEDDING_API_KEY or OPENAI_API_KEY on the Python service.",
            resolved_model, resolved_base,
        )
        return None

    cache_key = (resolved_model, resolved_key, resolved_base)
    cached = _cache.get(cache_key)
    if cached is not None:
        return cached

    emb = OpenAIEmbeddings(
        model=resolved_model,
        api_key=resolved_key,
        base_url=resolved_base,
    )
    _cache[cache_key] = emb
    return emb


def describe_resolution(
    *,
    api_key: Optional[str] = None,
    base_url: Optional[str] = None,
    model: Optional[str] = None,
) -> dict:
    """Return the resolved (model, base_url, key_source) tuple without
    constructing the client. Used in error messages so the failing
    ingestion's row knows exactly which endpoint was attempted.
    """
    s = get_settings()
    if api_key:
        source = "explicit"
    elif s.embedding_api_key:
        source = "embedding_api_key"
    elif s.openai_api_key:
        source = "openai_api_key"
    else:
        source = "none"
    return {
        "model": model or s.embedding_model,
        "baseUrl": base_url or s.embedding_base_url or s.openai_base_url,
        "keySource": source,
    }


def reset_cache() -> None:
    """Drop all cached embedding clients. Test-only hook."""
    _cache.clear()

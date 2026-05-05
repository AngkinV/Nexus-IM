"""ChromaDB persistent client lifecycle + canonical collection helpers.

The persistent client is a thread-safe process-wide singleton; its on-disk
location is `settings.chroma_persist_dir`. Collections used elsewhere in the
codebase MUST reference the helpers exported here so module A (memory) and
module B (knowledge base) cannot accidentally collide on a name.

Collection naming:
  * Memory RAG (Module A) — single shared collection {@code memory_chunks},
    written by every assistant turn and filtered by userId at read time.
  * Knowledge bases (Module B) — one collection PER kb (`kb_chunks_<kb_id>`).
    This isolation is required because a Chroma collection's vector dimension
    is locked at first write; if every KB shared one collection, the first
    user to ingest with text-embedding-3-small (1536) would prevent any other
    user from picking BGE (768), DashScope text-embedding-v3 (1024), etc.
"""
from __future__ import annotations

import logging
import re
import threading
from pathlib import Path
from typing import Any, Optional

import chromadb
from chromadb.config import Settings as ChromaSettings

from ..config import get_settings

log = logging.getLogger(__name__)

# Memory RAG (Module A) — name is fixed because all turns share one space.
MEMORY_COLLECTION = "memory_chunks"

# Knowledge bases (Module B) — prefix used by knowledge_collection_for(kb_id).
# The legacy name is kept only so existing test fixtures or pre-migration
# Chroma directories can be inspected; new code MUST go through
# knowledge_collection_for().
KNOWLEDGE_COLLECTION_LEGACY = "kb_chunks"
KNOWLEDGE_COLLECTION_PREFIX = "kb_chunks_"

# Chroma collection names must be 3-63 chars, [a-zA-Z0-9._-] only, start+end
# alphanumeric. kb_id is always "kb_<16 hex>" today, so the regex below is
# defensive — it survives a future kb_id format change without breaking
# silently.
_KB_ID_SANITIZE = re.compile(r"[^a-zA-Z0-9_]+")

_client: Optional[Any] = None
_lock = threading.Lock()


def get_chroma_client():
    """Return the process-wide ChromaDB persistent client."""
    global _client
    if _client is not None:
        return _client
    with _lock:
        if _client is None:
            s = get_settings()
            persist_dir = Path(s.chroma_persist_dir)
            persist_dir.mkdir(parents=True, exist_ok=True)
            _client = chromadb.PersistentClient(
                path=str(persist_dir),
                settings=ChromaSettings(anonymized_telemetry=False),
            )
            log.info("ChromaDB persistent client initialized at %s", persist_dir)
    return _client


def knowledge_collection_for(kb_id: str) -> str:
    """Per-KB collection name. Sanitises {@code kb_id} so it survives Chroma's
    name validator (alphanumeric + underscore). Empty kb_id is a programming
    error — callers must pass a real id."""
    if not kb_id:
        raise ValueError("kb_id is required for knowledge_collection_for")
    safe = _KB_ID_SANITIZE.sub("_", str(kb_id))
    if not safe or not safe[0].isalnum():
        # Defensive: pad with a stable prefix character so the result is valid
        # even if the input started with '_' / digits-only / etc.
        safe = "k" + safe
    name = f"{KNOWLEDGE_COLLECTION_PREFIX}{safe}"
    # Chroma rejects names > 63 chars; truncate while preserving a chunk of the
    # kb_id for greppability. Collisions across KBs of the same user are
    # impossible because kb_id itself is globally unique (16 random hex chars).
    return name[:63]


def get_or_create_collection(name: str, *, metadata: dict | None = None):
    """Idempotent get-or-create. We do not bind a default embedding function
    to the collection: memory_rag / knowledge_rag pass embeddings explicitly
    so that BYOK (per-request OpenAI key) can flow through.
    """
    client = get_chroma_client()
    # ChromaDB 0.5+ rejects empty-dict metadata; pass None when no metadata is given.
    md = metadata if metadata else None
    return client.get_or_create_collection(name=name, metadata=md)


def delete_collection_if_exists(name: str) -> bool:
    """Drop a collection by name. Returns True on success, False if missing.
    Used by the KB-deletion path so {@code POST /v1/knowledge/delete} with
    {@code docId=None} can drop the whole per-KB collection in one call."""
    client = get_chroma_client()
    try:
        client.delete_collection(name=name)
        return True
    except Exception as exc:
        log.info("delete_collection_if_exists(%s) noop: %s", name, exc)
        return False


def reset_for_testing() -> None:
    """Drop the cached client so the next call rebuilds it. Test-only hook —
    pair with `monkeypatch.setattr(settings, "chroma_persist_dir", tmp_dir)`.
    """
    global _client
    with _lock:
        _client = None


# ----- Backwards-compat shim -----
#
# Some test fixtures and legacy import paths still reference the singular
# {@code KNOWLEDGE_COLLECTION} symbol from before the per-KB split. We keep
# it as the legacy name so importing it (and only importing it) doesn't
# crash; production code paths route through knowledge_collection_for(kb_id)
# and never touch this constant.
KNOWLEDGE_COLLECTION = KNOWLEDGE_COLLECTION_LEGACY

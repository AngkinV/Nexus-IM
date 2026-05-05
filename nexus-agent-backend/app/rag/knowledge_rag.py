"""Module B: knowledge-base RAG retrieval.

Read-only mirror of memory_rag.retrieve, but scoped to the per-KB collection
({@code kb_chunks_<kb_id>}) so different KBs can pin different embedding
dimensions without colliding. The write path lives in
app/knowledge/ingestion.py — keeping reader and writer separate makes it
easy to reason about who is allowed to mutate the collection (the answer:
only the ingestion pipeline).

Cross-tenant safety: the kbId-only filter is sufficient because Java's
KnowledgeBaseService refuses to resolve a kbId not owned by the requesting
user (findByKbIdAndUserId). When userId is also supplied here, we add it
as a second guardrail — same pattern Module A's memory collection uses.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Optional, Tuple

from langchain_chroma import Chroma
from langchain_core.documents import Document

from ..config import get_settings
from .embeddings import get_embeddings
from .vectorstore import get_chroma_client, knowledge_collection_for

log = logging.getLogger(__name__)


def _build_store(
    kb_id: str,
    provider: Optional[dict] = None,
    embedding_model: Optional[str] = None,
) -> Optional[Chroma]:
    """Open a Chroma vectorstore handle bound to the per-KB collection,
    or None when no embedding client can be resolved.

    Unlike ingestion.py, the ``provider`` here is the BYOK *chat* credential
    forwarded from X-Model-* headers — NOT a purpose=embedding credential.
    Chat providers typically 404 on /embeddings or reject the configured
    embedding model, so we ignore it and use the server-side embedding
    settings instead. ``embedding_model`` is still honoured so each KB stays
    pinned to the dimension it was ingested with.
    """
    del provider  # deliberately ignored; see docstring
    embedder = get_embeddings(model=embedding_model)
    if embedder is None:
        return None
    return Chroma(
        client=get_chroma_client(),
        collection_name=knowledge_collection_for(kb_id),
        embedding_function=embedder,
    )


async def retrieve(
    kb_id: str,
    query: str,
    top_k: Optional[int] = None,
    *,
    user_id: Optional[int] = None,
    embedding_model: Optional[str] = None,
    provider: Optional[dict] = None,
) -> list[Tuple[Document, float]]:
    """Return up to top_k (Document, score) pairs ranked by similarity.

    Returns [] for any of:
      - knowledge_rag_enabled=False
      - empty query / kb_id
      - no embedding client resolvable
      - underlying vectorstore error (logged, not raised)
    """
    settings = get_settings()
    if not settings.knowledge_rag_enabled:
        return []
    if not query or not query.strip():
        return []
    if not kb_id:
        return []

    k = top_k if top_k is not None else settings.knowledge_rag_top_k

    store = _build_store(kb_id, provider, embedding_model)
    if store is None:
        return []

    where: dict
    if user_id is None:
        where = {"kbId": str(kb_id)}
    else:
        where = {"$and": [{"kbId": str(kb_id)}, {"userId": int(user_id)}]}

    try:
        return await asyncio.to_thread(
            store.similarity_search_with_score,
            query, k=k, filter=where,
        )
    except Exception:
        log.exception("knowledge_rag.retrieve failed for kb_id=%s", kb_id)
        return []

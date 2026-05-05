"""End-to-end document ingestion pipeline (load → split → embed → store).

This is the only writer of a KB's per-collection vector store. It is invoked
from FastAPI's BackgroundTasks (Day 11) so a slow PDF doesn't block the
upload response. The synchronous LangChain primitives are wrapped in
asyncio.to_thread so the FastAPI event loop stays free.

Provider handling: each {@code POST /v1/knowledge/ingest} call carries the
caller's chosen embedding credential in signed X-Model-* headers (see
{@code app/security.py}). The verifier hands those off as a {@code provider}
dict; this module pulls {@code apiKey + baseUrl} out of it and threads them
into {@code get_embeddings(...)}. That's the whole BYOK story for KB writes.

Failure modes (raised as IngestionError; controller maps each to a status
update on agent_knowledge_document):
  - UnsupportedFileTypeError / DocumentLoadError from loaders
  - "no embedding key" — provider has no API key resolvable
  - underlying LangChain / Chroma exceptions
"""
from __future__ import annotations

import asyncio
import logging
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

from langchain_chroma import Chroma
from langchain_core.documents import Document

from ..rag.embeddings import describe_resolution, get_embeddings
from ..rag.vectorstore import (
    delete_collection_if_exists,
    get_chroma_client,
    knowledge_collection_for,
)
from .loaders import DocumentLoadError, UnsupportedFileTypeError, load_document
from .splitter import DEFAULT_CHUNK_OVERLAP, DEFAULT_CHUNK_SIZE, split_documents

log = logging.getLogger(__name__)


class IngestionError(RuntimeError):
    """Raised when ingestion cannot complete; carries a short reason suitable
    for the agent_knowledge_document.error_message column (<=500 chars)."""


@dataclass
class IngestionResult:
    doc_id: str
    chunk_count: int
    status: str  # "READY" — failures raise IngestionError instead.
    embedding_dimension: Optional[int] = None


async def ingest_document(
    *,
    kb_id: str,
    doc_id: str,
    file_path: str,
    file_type: str,
    file_name: Optional[str] = None,
    user_id: Optional[int] = None,
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    chunk_overlap: int = DEFAULT_CHUNK_OVERLAP,
    embedding_model: Optional[str] = None,
    provider: Optional[dict] = None,
) -> IngestionResult:
    """Run the full pipeline for one document. Raises IngestionError on any
    failure; on success returns the produced chunk count + the vector dim
    Chroma observed (so Java can pin agent_knowledge_base.embedding_dimension
    on the first ingestion).

    Idempotency: callers re-running this for the same doc_id will simply add
    a fresh chunk batch with new chunk ids — `delete_document` removes the
    old chunks first, so the public path stays consistent.

    Provider handling: when ``provider`` is the embedding credential forwarded
    by Java's KnowledgeGatewayService, its ``apiKey``/``baseUrl`` win over
    server-side env defaults. When ``provider`` is None the standard
    embedding-settings fallback chain in get_embeddings() applies.
    """
    if not kb_id or not doc_id or not file_path or not file_type:
        raise IngestionError("kb_id / doc_id / file_path / file_type are all required")

    api_key = provider.get("apiKey") if provider else None
    base_url = provider.get("baseUrl") if provider else None
    # Caller-supplied model (KB row) wins; fall back to provider's default
    # model name when the KB hasn't pinned one and the credential row has.
    resolved_model = embedding_model or (provider.get("model") if provider else None)

    embedder = get_embeddings(api_key=api_key, base_url=base_url, model=resolved_model)
    if embedder is None:
        info = describe_resolution(api_key=api_key, base_url=base_url, model=resolved_model)
        raise IngestionError(
            f"no embedding API key resolvable (model={info['model']}, "
            f"base_url={info['baseUrl']}). Bind a purpose=embedding credential "
            f"to the knowledge base, or set EMBEDDING_API_KEY on the Python service."
        )

    try:
        raw_docs = await asyncio.to_thread(load_document, file_path, file_type)
    except UnsupportedFileTypeError as exc:
        raise IngestionError(str(exc)) from exc
    except DocumentLoadError as exc:
        raise IngestionError(str(exc)) from exc

    if not raw_docs:
        raise IngestionError(f"loader produced 0 documents for {file_path}")

    chunks = await asyncio.to_thread(
        split_documents, raw_docs, chunk_size=chunk_size, chunk_overlap=chunk_overlap
    )
    if not chunks:
        raise IngestionError("splitter produced 0 chunks (empty document?)")

    now_iso = datetime.now(timezone.utc).isoformat()
    enriched: list[Document] = []
    ids: list[str] = []
    for index, chunk in enumerate(chunks):
        chunk_id = f"kbc_{uuid.uuid4().hex[:16]}"
        meta = dict(chunk.metadata or {})
        meta.update({
            "kbId": kb_id,
            "docId": doc_id,
            "chunkId": chunk_id,
            "chunkIndex": index,
            "createdAt": now_iso,
        })
        if user_id is not None:
            meta["userId"] = int(user_id)
        if file_name:
            meta["fileName"] = file_name
        enriched.append(Document(page_content=chunk.page_content, metadata=meta))
        ids.append(chunk_id)

    collection_name = knowledge_collection_for(kb_id)
    store = Chroma(
        client=get_chroma_client(),
        collection_name=collection_name,
        embedding_function=embedder,
    )

    # Probe the embedding dimension on the first chunk so we can return it to
    # Java. Done before the bulk write so a failure here is still an
    # IngestionError (rather than partial state in the collection).
    embedding_dimension: Optional[int] = None
    try:
        sample_vec = await asyncio.to_thread(
            embedder.embed_query, enriched[0].page_content[:512]
        )
        embedding_dimension = len(sample_vec) if sample_vec else None
    except Exception as exc:
        info = describe_resolution(api_key=api_key, base_url=base_url, model=resolved_model)
        log.warning(
            "embedding probe failed before bulk write for doc_id=%s "
            "(model=%s base_url=%s): %s",
            doc_id, info["model"], info["baseUrl"], exc,
        )
        raise IngestionError(
            f"embedding probe failed (model={info['model']}, "
            f"base_url={info['baseUrl']}): {exc}"
        ) from exc

    try:
        await asyncio.to_thread(store.add_documents, enriched, ids=ids)
    except Exception as exc:
        # Wrap so the controller gets a clean message; the original is logged.
        info = describe_resolution(api_key=api_key, base_url=base_url, model=resolved_model)
        log.exception(
            "vectorstore add_documents failed for doc_id=%s using "
            "embedding model=%s base_url=%s key_source=%s",
            doc_id, info["model"], info["baseUrl"], info["keySource"],
        )
        raise IngestionError(
            f"embedding/vectorstore write failed (model={info['model']}, "
            f"base_url={info['baseUrl']}, keySource={info['keySource']}): {exc}"
        ) from exc

    return IngestionResult(
        doc_id=doc_id,
        chunk_count=len(enriched),
        status="READY",
        embedding_dimension=embedding_dimension,
    )


async def delete_document(
    *,
    kb_id: str,
    doc_id: Optional[str] = None,
) -> int:
    """Remove vectors for a doc (or the whole KB if doc_id is None) from
    its per-KB Chroma collection. Returns the number of chunks deleted; 0 is
    a non-error result when the doc had not been ingested yet (still PENDING).
    """
    if not kb_id:
        raise IngestionError("kb_id required")

    collection_name = knowledge_collection_for(kb_id)

    # Whole-KB drop: nuke the entire collection. This avoids enumerating every
    # chunk first and matches the user-facing semantics ("delete this KB"
    # should leave no orphaned vectors).
    if doc_id is None:
        client = get_chroma_client()
        try:
            existing = await asyncio.to_thread(
                client.get_or_create_collection, collection_name
            )
            count = await asyncio.to_thread(existing.count)
        except Exception as exc:
            raise IngestionError(f"cannot open kb collection: {exc}") from exc
        # Delete the collection itself so a future ingestion can pick a
        # different embedding dimension without dim-mismatch errors.
        delete_collection_if_exists(collection_name)
        return count or 0

    # Per-doc delete: enumerate chunks for the doc and remove them.
    try:
        collection = await asyncio.to_thread(
            get_chroma_client().get_or_create_collection, collection_name
        )
    except Exception as exc:
        raise IngestionError(f"cannot open kb collection: {exc}") from exc

    where: dict = {"$and": [{"kbId": kb_id}, {"docId": doc_id}]}

    try:
        existing = await asyncio.to_thread(collection.get, where=where)
    except Exception as exc:
        raise IngestionError(f"cannot enumerate kb chunks: {exc}") from exc

    ids = (existing or {}).get("ids") or []
    if not ids:
        return 0

    try:
        await asyncio.to_thread(collection.delete, ids=ids)
    except Exception as exc:
        raise IngestionError(f"cannot delete kb chunks: {exc}") from exc
    return len(ids)

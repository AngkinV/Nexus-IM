"""Module A: conversation memory RAG.

After every assistant turn we embed (user_text, assistant_text) into a single
chunk and store it in the `memory_chunks` collection. Before each new turn we
retrieve the top-K semantically similar chunks for the same user and surface
them to the prompt builder as "relevant history".

Both write and retrieve are no-ops when:
  - `settings.memory_rag_enabled` is False, OR
  - no embedding client can be resolved (no API key)

The orchestrator falls back to the short-term Redis sliding window in those
cases. LangChain's synchronous Chroma calls are wrapped with asyncio.to_thread
so we don't block the event loop on disk/network IO.
"""
from __future__ import annotations

import asyncio
import hashlib
import logging
import uuid
from datetime import datetime, timezone
from typing import Optional

from langchain_chroma import Chroma
from langchain_core.documents import Document

from ..config import get_settings
from ..scope import AuthorizedScope, ScopeClient, ScopeDenied
from .embeddings import get_embeddings
from .vectorstore import MEMORY_COLLECTION, get_chroma_client

log = logging.getLogger(__name__)


def _build_store(provider: Optional[dict] = None) -> Optional[Chroma]:
    """Return a Chroma vectorstore bound to MEMORY_COLLECTION,
    or None if no embedding client is available.

    The ``provider`` arg is intentionally NOT forwarded to embeddings: it
    carries the BYOK *chat* credential decoded from X-Model-* headers, and
    most chat providers either lack /embeddings or refuse the requested
    embedding model (see app/rag/embeddings.py module docstring). Memory RAG
    always uses the server-side EMBEDDING_PROVIDER / EMBEDDING_API_KEY config.
    The arg is kept for signature stability with existing callers.
    """
    del provider  # deliberately ignored; see docstring
    embedder = get_embeddings()
    if embedder is None:
        return None
    return Chroma(
        client=get_chroma_client(),
        collection_name=MEMORY_COLLECTION,
        embedding_function=embedder,
    )


async def write(
    user_id: int,
    session_id: str,
    user_text: str,
    assistant_text: str,
    *,
    trace_id: Optional[str] = None,
    run_id: Optional[str] = None,
    provider: Optional[dict] = None,
    scope: AuthorizedScope | None = None,
) -> Optional[str]:
    """Persist one (user, assistant) exchange. Returns chunk_id on success,
    None on degraded paths (disabled / too short / no key / underlying error)."""
    scope = await ScopeClient().validate(scope)
    scope.bind(user_id, session_id, scope.runId)
    settings = get_settings()
    if not settings.memory_rag_enabled:
        return None

    text = f"User: {user_text}\nAssistant: {assistant_text}".strip()
    # min_chars guards against trivial exchanges; measure real content only,
    # not the "User: / Assistant: " framing which would otherwise always pass.
    content_len = len(user_text or "") + len(assistant_text or "")
    if content_len < settings.memory_rag_min_chars:
        return None

    store = await asyncio.to_thread(_build_store, provider)
    if store is None:
        return None

    if run_id:
        identity = f"{user_id}:{session_id}:{scope.scopeKey}:{run_id}".encode()
        chunk_id = "mem_" + hashlib.sha256(identity).hexdigest()[:32]
    else:
        chunk_id = f"mem_{uuid.uuid4().hex[:16]}"
    metadata: dict = {
        "userId": int(user_id),
        "sessionId": str(session_id),
        "scopeKey": scope.scopeKey,
        "sessionGeneration": scope.sessionGeneration,
        "authorizationVersion": scope.authorizationVersion,
        "chunkId": chunk_id,
        "createdAt": datetime.now(timezone.utc).isoformat(),
    }
    if trace_id:
        metadata["traceId"] = str(trace_id)

    doc = Document(page_content=text, metadata=metadata)

    await ScopeClient().validate(scope)
    try:
        await asyncio.to_thread(store.add_documents, [doc], ids=[chunk_id])
        return chunk_id
    except Exception:
        log.exception("memory_rag.write failed for user_id=%s", user_id)
        return None


async def retrieve(
    user_id: int,
    query: str,
    top_k: Optional[int] = None,
    *,
    provider: Optional[dict] = None,
    scope: AuthorizedScope | None = None,
) -> list[str]:
    """Return up to top_k semantically similar memory chunks for this user.

    Filtered by userId — cross-user retrieval is impossible by construction.
    Returns [] when disabled, query empty, no key, no matches, or on error.
    """
    scope = await ScopeClient().validate(scope)
    if scope.actorUserId != user_id:
        raise ScopeDenied()
    settings = get_settings()
    if not settings.memory_rag_enabled:
        return []

    if not query or not query.strip():
        return []

    k = top_k if top_k is not None else settings.memory_rag_top_k

    store = await asyncio.to_thread(_build_store, provider)
    if store is None:
        return []

    # An empty collection cannot match anything, and asking costs an embedding
    # round trip (~2s) on every single turn. Once memory has been written this
    # is a no-op; before then it saves the wait.
    try:
        if await asyncio.to_thread(store._collection.count) == 0:
            return []
    except Exception:
        # Counting is an optimisation, never a reason to skip retrieval.
        pass

    await ScopeClient().validate(scope)
    try:
        results = await asyncio.to_thread(
            store.similarity_search,
            query,
            k=k,
            filter={"$and": [{"userId": int(user_id)},
                             {"sessionId": scope.sessionId},
                             {"scopeKey": scope.scopeKey},
                             {"sessionGeneration": scope.sessionGeneration}]},
        )
        await ScopeClient().validate(scope)
        return [d.page_content for d in results
                if d.metadata.get("userId") == user_id
                and d.metadata.get("sessionId") == scope.sessionId
                and d.metadata.get("scopeKey") == scope.scopeKey
                and d.metadata.get("sessionGeneration") == scope.sessionGeneration]
    except ScopeDenied:
        raise
    except Exception:
        log.exception("memory_rag.retrieve failed for user_id=%s", user_id)
        return []


async def purge_session(user_id: int, session_id: str) -> None:
    """Java generation/tombstone must precede best effort physical deletion."""
    collection = await asyncio.to_thread(
        get_chroma_client().get_or_create_collection, MEMORY_COLLECTION)
    await asyncio.to_thread(collection.delete, where={"$and": [
        {"userId": int(user_id)}, {"sessionId": str(session_id)}]})

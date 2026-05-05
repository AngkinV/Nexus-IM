"""FastAPI routes for the Python Agent backend."""
from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any, AsyncIterator, Callable

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse, StreamingResponse

from .config import get_settings
from .knowledge.ingestion import IngestionError, delete_document, ingest_document
from .orchestrator import Event
from .orchestrator import run_agent as run_handcrafted
from .orchestrator_langgraph import run_agent as run_langgraph
from .rag import knowledge_rag
from .schemas import (
    InvokeRequest,
    InvokeResponse,
    InvokeResult,
    KnowledgeChunk,
    KnowledgeDeleteRequest,
    KnowledgeDeleteResponse,
    KnowledgeIngestRequest,
    KnowledgeIngestResponse,
    KnowledgeQueryRequest,
    KnowledgeQueryResponse,
    TokenUsage,
)
from .security import verify_internal_signature
from .sse import event_to_sse, reset_id_seq

log = logging.getLogger(__name__)
router = APIRouter()


# Both engines must expose the same generator signature so this dispatcher
# stays a one-liner. Day 15/16 verified the LangGraph engine emits the
# canonical event sequence so the SSE wire format is engine-agnostic.
EngineRunner = Callable[..., AsyncIterator[Event]]


def _resolve_engine() -> EngineRunner:
    """Pick the engine for this request based on `settings.engine`.

    Recognised values:
      - "handcrafted" (default): orchestrator.run_agent — production path
      - "langgraph"            : orchestrator_langgraph.run_agent — Module C
    Unknown values silently fall back to the handcrafted engine; the
    config field is operator-controlled so we don't need to surface a
    400 to the user.
    """
    engine = (get_settings().engine or "handcrafted").strip().lower()
    if engine == "langgraph":
        return run_langgraph
    return run_handcrafted


@router.get("/v1/agent/health")
def health():
    s = get_settings()
    return {
        "status": "UP",
        "modelProvider": s.model_provider if s.use_real_model else "mock",
        "model": s.model_name,
        "engine": s.engine,
        "time": datetime.now(timezone.utc).astimezone().isoformat(),
    }


@router.post("/v1/agent/invoke", response_model=InvokeResponse)
async def invoke(request: InvokeRequest, ctx: dict = Depends(verify_internal_signature)):
    if request.actor.userId != ctx["actorUserId"]:
        return JSONResponse(status_code=403, content={"code": "AGENT_AUTHZ_40301", "message": "actor mismatch"})
    if request.traceId != ctx["traceId"]:
        return JSONResponse(status_code=400, content={"code": "AGENT_PARAM_40001", "message": "trace mismatch"})

    runner = _resolve_engine()
    final: dict | None = None
    async for event in runner(request, provider=ctx.get("provider")):
        if event.name == "__final__":
            final = event.data["result"]
    if final is None:
        final = InvokeResult(answer="", usage=TokenUsage()).model_dump()
    return InvokeResponse(traceId=request.traceId, result=InvokeResult(**final))


@router.post("/v1/agent/invoke/stream")
async def invoke_stream(request: InvokeRequest, ctx: dict = Depends(verify_internal_signature)):
    if request.actor.userId != ctx["actorUserId"]:
        async def err():
            yield event_to_sse("error", {"code": "AGENT_AUTHZ_40301", "message": "actor mismatch"})
        return StreamingResponse(err(), media_type="text/event-stream")

    runner = _resolve_engine()

    async def stream():
        reset_id_seq()
        async for event in runner(request, provider=ctx.get("provider")):
            if event.name == "__final__":
                continue
            yield event_to_sse(event.name, event.data)

    return StreamingResponse(stream(), media_type="text/event-stream")


# ---------- Module B: knowledge base ----------
#
# All three routes share the same HMAC-verified internal contract used by
# /v1/agent/invoke. The kbId/docId in the body must already exist on the
# Java side (refused there for cross-user requests); Python is a dumb
# worker that trusts the HMAC and processes the file path it is given.


@router.post("/v1/knowledge/ingest", response_model=KnowledgeIngestResponse)
async def knowledge_ingest(
    request: KnowledgeIngestRequest,
    ctx: dict = Depends(verify_internal_signature),
):
    """Synchronous ingestion: load → split → embed → write to ChromaDB.

    On success returns chunkCount + status="READY".
    On any failure returns 502 with a short reason that fits the
    agent_knowledge_document.error_message column on the Java side.
    """
    try:
        result = await ingest_document(
            kb_id=request.kbId,
            doc_id=request.docId,
            file_path=request.filePath,
            file_type=request.fileType,
            file_name=request.fileName,
            user_id=request.userId,
            chunk_size=request.chunkSize,
            chunk_overlap=request.chunkOverlap,
            embedding_model=request.embeddingModel,
            provider=ctx.get("provider"),
        )
        return KnowledgeIngestResponse(
            kbId=request.kbId,
            docId=result.doc_id,
            chunkCount=result.chunk_count,
            status=result.status,
            embeddingDimension=result.embedding_dimension,
        )
    except IngestionError as exc:
        log.warning("knowledge_ingest failed: kb=%s doc=%s err=%s",
                    request.kbId, request.docId, exc)
        return JSONResponse(
            status_code=502,
            content={
                "code": "AGENT_KB_INGEST_50203",
                "message": str(exc)[:480],
            },
        )


@router.post("/v1/knowledge/delete", response_model=KnowledgeDeleteResponse)
async def knowledge_delete(
    request: KnowledgeDeleteRequest,
    ctx: dict = Depends(verify_internal_signature),
):
    """Delete vectors from ChromaDB.

    docId=None deletes the whole KB (used when Java drops a KnowledgeBase
    row); docId=<value> deletes a single document. Returning 0 deletedCount
    is a non-error result (the doc may have been PENDING and never ingested).
    """
    try:
        deleted = await delete_document(kb_id=request.kbId, doc_id=request.docId)
        return KnowledgeDeleteResponse(
            kbId=request.kbId, docId=request.docId, deletedCount=deleted,
        )
    except IngestionError as exc:
        log.warning("knowledge_delete failed: kb=%s doc=%s err=%s",
                    request.kbId, request.docId, exc)
        return JSONResponse(
            status_code=502,
            content={
                "code": "AGENT_KB_INGEST_50203",
                "message": str(exc)[:480],
            },
        )


@router.post("/v1/knowledge/query", response_model=KnowledgeQueryResponse)
async def knowledge_query(
    request: KnowledgeQueryRequest,
    ctx: dict = Depends(verify_internal_signature),
):
    """Top-K similarity search over kb_chunks.

    Always returns 200 — empty chunks list signals "no matches" or
    "retrieval disabled / no embedding key", and the Java caller is expected
    to render either as "I don't know" rather than as a hard error.
    """
    pairs = await knowledge_rag.retrieve(
        kb_id=request.kbId,
        query=request.query,
        top_k=request.topK,
        user_id=request.userId,
        embedding_model=request.embeddingModel,
        provider=ctx.get("provider"),
    )
    chunks: list[KnowledgeChunk] = []
    for doc, score in pairs:
        meta = dict(doc.metadata or {})
        chunks.append(KnowledgeChunk(
            chunkId=str(meta.get("chunkId", "")),
            text=doc.page_content,
            score=float(score) if score is not None else None,
            metadata=meta,
        ))
    return KnowledgeQueryResponse(
        kbId=request.kbId, query=request.query, chunks=chunks,
    )

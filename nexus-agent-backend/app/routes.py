"""FastAPI routes for the Python Agent backend."""
from __future__ import annotations

import asyncio
import contextlib
import logging
import uuid
from datetime import datetime, timezone
from typing import AsyncIterator

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse

from .checkpoint import open_run_store, thread_id_for
from .scope import AuthorizedScope, ScopeClient, ScopeDenied
from .config import get_settings
from .knowledge.ingestion import IngestionError, delete_document, ingest_document
from .orchestrator import Event, active_thread_ids, cancel_run, is_run_active, resume_agent, run_agent
from .rag import knowledge_rag, memory_rag
from .memory import get_memory
from .artifacts import ArtifactError, apply_unified_patch
from .attachments import extract_attachment
from .schemas import (
    AttachmentExtractRequest,
    AttachmentExtractResponse,
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
    PatchPreviewRequest,
    PatchPreviewResponse,
    ResumeRequest,
    RunQuery,
    TokenUsage,
)
from .security import verify_internal_signature
from .sse import sse_encoder

log = logging.getLogger(__name__)
router = APIRouter()

_SSE_HEADERS = {"Cache-Control": "no-cache, no-transform", "X-Accel-Buffering": "no"}


def _actor_mismatch_stream(run_id: str | None = None) -> StreamingResponse:
    async def err():
        encode = sse_encoder()
        identity = {"runId": run_id or uuid.uuid4().hex, "attemptId": uuid.uuid4().hex,
                    "at": 0, "runtimeContractVersion": 1}
        yield encode("error", {**identity, "sequence": 1, "scope": "run",
                               "code": "AGENT_AUTHZ_40301", "message": "actor mismatch"})
        yield encode("done", {**identity, "sequence": 2, "finishReason": "error", "resumable": False})
    return StreamingResponse(err(), media_type="text/event-stream")


def _event_stream(events: AsyncIterator[Event]) -> StreamingResponse:
    async def stream():
        encode = sse_encoder()
        # Closing the run when the client goes away is what cancels the model call.
        async with contextlib.aclosing(events) as source:
            async for event in source:
                if event.name != "__final__":
                    yield encode(event.name, event.data)

    return StreamingResponse(stream(), media_type="text/event-stream", headers=_SSE_HEADERS)


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

    final: dict | None = None
    error_message: str | None = None
    # Non-streaming callers have no way to continue a run, so a failed one is not kept.
    async for event in run_agent(request, provider=ctx.get("provider"), keep_failed=False):
        if event.name == "__final__":
            final = event.data["result"]
        elif event.name == "error":
            error_message = event.data.get("message") or "model call failed"
    if final is None:
        final = InvokeResult(answer="", usage=TokenUsage(), finishReason="error").model_dump()
    if error_message and not final.get("answer"):
        # Display-only note for the non-streaming caller; the engine never
        # persists error text to memory. Partial streamed text is kept as-is.
        final["answer"] = f"（模型调用失败：{error_message}）"
    return InvokeResponse(traceId=request.traceId, result=InvokeResult(**final))


@router.post("/v1/agent/invoke/stream")
async def invoke_stream(request: InvokeRequest, ctx: dict = Depends(verify_internal_signature)):
    if request.actor.userId != ctx["actorUserId"]:
        return _actor_mismatch_stream(request.runId)
    if request.traceId != ctx["traceId"]:
        return JSONResponse(status_code=400, content={"code": "AGENT_PARAM_40001", "message": "trace mismatch"})
    return _event_stream(run_agent(request, provider=ctx.get("provider")))


@router.post("/v1/agent/runs/resume/stream")
async def resume_stream(request: ResumeRequest, ctx: dict = Depends(verify_internal_signature)):
    """Continue an unfinished run. Java re-checks session ownership and re-sends
    the provider credentials; the run itself stored neither."""
    if request.actor.userId != ctx["actorUserId"]:
        return _actor_mismatch_stream(request.runId)
    if request.traceId != ctx["traceId"]:
        return JSONResponse(status_code=400, content={"code": "AGENT_PARAM_40001", "message": "trace mismatch"})
    return _event_stream(resume_agent(request, provider=ctx.get("provider")))


@router.post("/v1/agent/runs/cancel")
async def cancel_run_route(request: ResumeRequest, ctx: dict = Depends(verify_internal_signature)):
    if request.actor.userId != ctx["actorUserId"]:
        return JSONResponse(status_code=403, content={"code": "AGENT_AUTHZ_40301", "message": "actor mismatch"})
    if request.traceId != ctx["traceId"]:
        return JSONResponse(status_code=400, content={"code": "AGENT_PARAM_40001", "message": "trace mismatch"})
    status = await cancel_run(request.actor.userId, request.sessionId, request.runId)
    return {"runId": request.runId, "status": status}


@router.post("/v1/agent/runs/pending")
async def pending_runs(request: RunQuery, ctx: dict = Depends(verify_internal_signature)):
    if request.actor.userId != ctx["actorUserId"]:
        return JSONResponse(status_code=403, content={"code": "AGENT_AUTHZ_40301", "message": "actor mismatch"})
    runs, finished, finished_scopes = [], [], {}
    async with open_run_store() as store:
        records = await store.pending(request.actor.userId, request.sessionId)
        graph = None
        for record in records:
            try:
                if graph is None:
                    from .orchestrator import get_graph_builder
                    graph = get_graph_builder().compile(checkpointer=store.saver)
                snapshot = await graph.aget_state({"configurable": {"thread_id": record.thread_id}})
                scope = AuthorizedScope.model_validate(snapshot.values['request']['scope'])
                scope.bind(request.actor.userId, request.sessionId, record.run_id)
                scope = await ScopeClient().validate(scope)
            except (ScopeDenied, ValueError, TypeError, KeyError):
                continue
            runs.append({
                "runId": record.run_id, "operationType": record.operation_type,
                "updatedAt": record.updated_at,
                "active": is_run_active(record.actor_user_id, record.run_id),
                "scope": scope.model_dump(),
            })
        # Keep the original list[str] shape. Scope metadata is separate; never
        # expose terminal bodies during discovery, even for authorized runs.
        for run_id in await store.finished(request.actor.userId, request.sessionId):
            try:
                stored = await store.result(thread_id_for(request.actor.userId, run_id), request.sessionId)
                scope = AuthorizedScope.model_validate(stored['result']['scope'])
                scope.bind(request.actor.userId, request.sessionId, run_id)
                scope = await ScopeClient().validate(scope)
            except (ScopeDenied, ValueError, TypeError, KeyError):
                continue
            finished.append(run_id)
            finished_scopes[run_id] = scope.model_dump()
    return {"runs": runs, "finished": finished, "finishedScopes": finished_scopes}


@router.post("/v1/agent/runs/purge")
async def purge_runs(request: RunQuery, ctx: dict = Depends(verify_internal_signature)):
    """Drop a session's unfinished runs (called when the session is deleted)."""
    if request.actor.userId != ctx["actorUserId"]:
        return JSONResponse(status_code=403, content={"code": "AGENT_AUTHZ_40301", "message": "actor mismatch"})
    async with open_run_store() as store:
        records = await store.pending(request.actor.userId, request.sessionId)
        if any(record.thread_id in active_thread_ids() for record in records):
            return JSONResponse(status_code=409, content={"code": "AGENT_RUN_40901", "message": "session has an active run"})
        purged = await store.purge_session(
            request.actor.userId, request.sessionId, keep=set())
    await get_memory().clear(request.actor.userId, request.sessionId)
    try:
        await memory_rag.purge_session(request.actor.userId, request.sessionId)
    except Exception:
        # No bodies/credentials in the diagnostic. Java tombstone remains authority.
        log.warning("session memory physical cleanup incomplete")
    return {"purged": purged}


# ---------- Workbench documents ----------
#
# Java owns the uploaded bytes and the ownership checks; these two endpoints only
# read a file and dry-run a patch. Neither writes anything.


@router.post("/v1/agent/attachments/extract", response_model=AttachmentExtractResponse)
async def extract_upload(
    request: AttachmentExtractRequest,
    ctx: dict = Depends(verify_internal_signature),
):
    import base64
    import binascii

    try:
        content = base64.b64decode(request.contentBase64, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise HTTPException(status_code=400, detail="The upload is not valid base64") from exc
    try:
        extracted = await asyncio.to_thread(extract_attachment, request.fileName, content)
    except ValueError as exc:
        # The reason is meant for the person who uploaded the file.
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return extracted


@router.post("/v1/agent/artifacts/patch-preview", response_model=PatchPreviewResponse)
async def preview_patch(
    request: PatchPreviewRequest,
    ctx: dict = Depends(verify_internal_signature),
):
    import hashlib

    try:
        after = await asyncio.to_thread(apply_unified_patch, request.original, request.patch, request.fileName)
    except ArtifactError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return {
        "content": after,
        "baseHash": hashlib.sha256(request.original.encode("utf-8")).hexdigest(),
        "patchHash": hashlib.sha256(request.patch.encode("utf-8")).hexdigest(),
    }


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

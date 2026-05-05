"""Pydantic schemas matching the Java <-> Python contract.

See `agent开发文档/Java 网关与 Python Agent 接口契约.md` §6.
"""
from __future__ import annotations

from typing import Any
from pydantic import BaseModel, Field


class Actor(BaseModel):
    userId: int
    username: str = "unknown"


class Session(BaseModel):
    sessionId: str
    operationType: str  # ASSISTANT_CHAT | CHAT_SUMMARY | TODO_EXTRACT | REPLY_SUGGEST
    # Module B: optional knowledge base bound to this turn. When set, the
    # orchestrator runs the user's input through knowledge_rag.retrieve()
    # and injects a kb_context block into the system prompt.
    linkedKbId: str | None = None


class InputPayload(BaseModel):
    text: str
    chatId: int | None = None

    # Mode B specific extras (forwarded as-is from Java; tolerated as optional)
    summaryRangeType: str | None = None
    rangeValue: int | None = None
    outputStyle: str | None = None
    targetMessageId: int | None = None
    targetMessageContent: str | None = None
    tone: str | None = None
    length: str | None = None


class Options(BaseModel):
    maxIterations: int = 6
    maxOutputTokens: int = 1024
    temperature: float = 0.2


class InvokeRequest(BaseModel):
    traceId: str
    actor: Actor
    session: Session
    input: InputPayload
    options: Options = Field(default_factory=Options)


class ToolCallSummary(BaseModel):
    toolName: str
    status: str  # SUCCESS | FAILED | TIMEOUT
    latencyMs: int
    error: str | None = None


class TokenUsage(BaseModel):
    inputTokens: int = 0
    outputTokens: int = 0
    totalTokens: int = 0


class InvokeResult(BaseModel):
    answer: str
    toolCalls: list[ToolCallSummary] = Field(default_factory=list)
    usage: TokenUsage = Field(default_factory=TokenUsage)
    finishReason: str = "stop"
    # Mode B structured outputs
    todos: list[dict[str, Any]] | None = None
    draft: str | None = None
    alternatives: list[str] | None = None


class InvokeResponse(BaseModel):
    traceId: str
    result: InvokeResult


# ---------- Module B: knowledge base ----------
#
# Contracts mirror RAG扩展实施方案.md §4.2.
# Java is the only caller; HMAC headers are validated by the existing
# `verify_internal_signature` dependency, so these schemas are bare bodies.


class KnowledgeIngestRequest(BaseModel):
    kbId: str
    docId: str
    filePath: str
    fileType: str
    fileName: str | None = None
    userId: int | None = None
    chunkSize: int = 512
    chunkOverlap: int = 64
    embeddingModel: str | None = None


class KnowledgeIngestResponse(BaseModel):
    kbId: str
    docId: str
    chunkCount: int
    status: str  # READY | FAILED
    # Vector dim observed during this ingestion. Java pins it on the KB row
    # the first time it sees a non-null value so the UI can disable embedding
    # configuration changes from then on.
    embeddingDimension: int | None = None


class KnowledgeDeleteRequest(BaseModel):
    kbId: str
    # When None, the whole KB is wiped from the vector store.
    docId: str | None = None


class KnowledgeDeleteResponse(BaseModel):
    kbId: str
    docId: str | None = None
    deletedCount: int


class KnowledgeChunk(BaseModel):
    chunkId: str
    text: str
    score: float | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class KnowledgeQueryRequest(BaseModel):
    kbId: str
    query: str
    topK: int = 4
    # Optional second-line guardrail: when supplied, retrieval also filters
    # by userId so a forged kbId from another user cannot leak chunks.
    userId: int | None = None
    embeddingModel: str | None = None


class KnowledgeQueryResponse(BaseModel):
    kbId: str
    query: str
    chunks: list[KnowledgeChunk] = Field(default_factory=list)

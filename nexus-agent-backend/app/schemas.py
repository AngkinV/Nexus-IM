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


class AttachmentRef(BaseModel):
    """One uploaded file, resolved by Java. Content is text Java extracted or the raw text."""
    id: str = Field(min_length=1, max_length=64)
    fileName: str = Field(min_length=1, max_length=200)
    content: str = ""
    sha256: str | None = None


class InputPayload(BaseModel):
    text: str
    chatId: int | None = None
    # Resolved by Java; the model sees the text, and the source list cites it as A1, A2…
    attachments: list[AttachmentRef] = Field(default_factory=list, max_length=4)

    # Mode B specific extras (forwarded as-is from Java; tolerated as optional)
    summaryRangeType: str | None = None
    rangeValue: int | None = None
    outputStyle: str | None = None
    targetMessageId: int | None = None
    targetMessageContent: str | None = None
    tone: str | None = None
    length: str | None = None


class Options(BaseModel):
    maxIterations: int = Field(default=6, ge=1)
    maxOutputTokens: int = 1024
    temperature: float = 0.2
    # Both are opt-in: an ordinary turn runs one assistant and no search.
    collaborationEnabled: bool = False
    webSearchEnabled: bool = False


RUN_ID_PATTERN = r"^[A-Za-z0-9_-]{8,64}$"


class InvokeRequest(BaseModel):
    traceId: str
    actor: Actor
    session: Session
    input: InputPayload
    options: Options = Field(default_factory=Options)
    # Identifies this turn for checkpoint resume; generated here when Java omits it.
    runId: str | None = Field(default=None, pattern=RUN_ID_PATTERN)
    # Set on a retry: the run whose answer this one supersedes.
    retryOfRunId: str | None = Field(default=None, pattern=RUN_ID_PATTERN)


class ResumeRequest(BaseModel):
    traceId: str
    actor: Actor
    sessionId: str
    runId: str = Field(pattern=RUN_ID_PATTERN)


class RunQuery(BaseModel):
    actor: Actor
    sessionId: str


class ToolCallSummary(BaseModel):
    toolName: str
    status: str  # SUCCESS | FAILED | TIMEOUT
    latencyMs: int
    error: str | None = None


class TokenUsage(BaseModel):
    inputTokens: int = 0
    outputTokens: int = 0
    totalTokens: int = 0


class TodoItem(BaseModel):
    owner: str | None = None
    task: str = Field(min_length=1)
    dueAt: str | None = None
    confidence: float | None = Field(default=None, ge=0, le=1)


class TodoExtractOutput(BaseModel):
    todos: list[TodoItem]


class ReplySuggestOutput(BaseModel):
    draft: str = Field(min_length=1)
    alternatives: list[str] = Field(default_factory=list)


class InvokeResult(BaseModel):
    answer: str
    toolCalls: list[ToolCallSummary] = Field(default_factory=list)
    usage: TokenUsage = Field(default_factory=TokenUsage)
    finishReason: str = "stop"
    # Mode B structured outputs
    todos: list[dict[str, Any]] | None = None
    draft: str | None = None
    alternatives: list[str] | None = None
    runId: str | None = None
    # True when the turn stopped early and its checkpoint can be resumed.
    resumable: bool = False
    # Set when finishReason is "invalid_output".
    outputError: str | None = None


class InvokeResponse(BaseModel):
    traceId: str
    result: InvokeResult


# ---------- Module B: knowledge base ----------
#
# Contracts mirror RAG扩展实施方案.md §4.2.
# Java is the only caller; HMAC headers are validated by the existing
# `verify_internal_signature` dependency, so these schemas are bare bodies.


class AttachmentExtractRequest(BaseModel):
    """Bytes Java uploaded. The name is a display name only; the type comes from it."""
    fileName: str = Field(min_length=1, max_length=200)
    contentBase64: str


class AttachmentExtractResponse(BaseModel):
    fileName: str
    content: str
    truncated: bool
    sizeBytes: int


class PatchPreviewRequest(BaseModel):
    fileName: str = Field(min_length=1, max_length=200)
    original: str
    patch: str


class PatchPreviewResponse(BaseModel):
    content: str
    baseHash: str
    patchHash: str


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

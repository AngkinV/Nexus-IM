"""Format retrieved knowledge-base chunks for prompt injection.

The orchestrator (Day 14) will call this with the result of
`knowledge_rag.retrieve(...)` to produce a deterministic block that goes
into the system prompt's "knowledge base" section. Keeping this purely
formatting (no LLM call) means the existing ReAct loop owns generation
end-to-end — qa.py is the lightweight bridge between retrieval and prompt.
"""
from __future__ import annotations

from typing import Iterable, Tuple

from langchain_core.documents import Document


# Per-chunk character cap. Long PDF pages can produce 1500+ char chunks
# even after splitting; trimming protects the prompt budget. A single
# truncated chunk is preferable to dropping it entirely.
MAX_CHUNK_CHARS = 800


def build_kb_context(chunks: Iterable[Tuple[Document, float]]) -> str:
    """Render chunks as a numbered reference block.

    Format (one chunk per line, blank line between):
        [1] {fileName}#{chunkIndex} (score=0.83):
        {text}

    Empty input → "" so callers can unconditionally concatenate.
    """
    parts: list[str] = []
    for i, (doc, score) in enumerate(chunks, start=1):
        meta = doc.metadata or {}
        file_name = meta.get("fileName") or meta.get("source") or "unknown"
        chunk_idx = meta.get("chunkIndex", "?")
        text = (doc.page_content or "").strip()
        if len(text) > MAX_CHUNK_CHARS:
            text = text[:MAX_CHUNK_CHARS] + "…"
        score_part = f" (score={score:.3f})" if score is not None else ""
        parts.append(f"[{i}] {file_name}#{chunk_idx}{score_part}:\n{text}")
    return "\n\n".join(parts)

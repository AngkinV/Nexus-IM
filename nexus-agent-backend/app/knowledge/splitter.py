"""RecursiveCharacterTextSplitter pre-configured per knowledge base.

We split on token boundaries (cl100k_base, the encoding used by GPT-4 /
text-embedding-3-small) so chunk_size / chunk_overlap have the same meaning
the embedding model uses internally — i.e. counts that match the cost
column on the OpenAI bill.

If tiktoken is unavailable for some reason (stripped runtime) we fall back
to a character-based splitter; semantics drift slightly but ingestion does
not break.
"""
from __future__ import annotations

import logging
from typing import List

from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter

log = logging.getLogger(__name__)


# Same defaults the schema's DEFAULT clause documents.
DEFAULT_CHUNK_SIZE = 512
DEFAULT_CHUNK_OVERLAP = 64

# Sensible safety bounds; mirror the Java-side @Min/@Max on CreateKbRequest.
_MIN_CHUNK_SIZE = 64
_MAX_CHUNK_SIZE = 2048
_MIN_OVERLAP = 0
_MAX_OVERLAP = 512


def make_splitter(
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    chunk_overlap: int = DEFAULT_CHUNK_OVERLAP,
) -> RecursiveCharacterTextSplitter:
    """Build a token-aware RecursiveCharacterTextSplitter.

    Inputs are clamped to safe ranges; values outside the bounds are silently
    pinned because by the time we get here the request has already been
    validated by Java's @Min/@Max — but we never want a runtime exception
    here to break ingestion of a legitimate document.
    """
    size = _clamp(chunk_size, _MIN_CHUNK_SIZE, _MAX_CHUNK_SIZE, DEFAULT_CHUNK_SIZE)
    overlap = _clamp(chunk_overlap, _MIN_OVERLAP, _MAX_OVERLAP, DEFAULT_CHUNK_OVERLAP)
    if overlap >= size:
        # Overlap >= size produces an infinite loop in any sliding splitter;
        # collapse to "no overlap" which is the conservative choice.
        log.warning("chunk_overlap (%d) >= chunk_size (%d); resetting overlap to 0", overlap, size)
        overlap = 0

    try:
        return RecursiveCharacterTextSplitter.from_tiktoken_encoder(
            encoding_name="cl100k_base",
            chunk_size=size,
            chunk_overlap=overlap,
        )
    except Exception as exc:
        log.warning("tiktoken splitter unavailable, using character-based splitter: %s", exc)
        return RecursiveCharacterTextSplitter(
            chunk_size=size,
            chunk_overlap=overlap,
            length_function=len,
        )


def split_documents(
    documents: List[Document],
    *,
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    chunk_overlap: int = DEFAULT_CHUNK_OVERLAP,
) -> List[Document]:
    """Convenience wrapper: build a splitter once, split a list of Documents,
    return the flattened chunk list.
    """
    if not documents:
        return []
    return make_splitter(chunk_size, chunk_overlap).split_documents(documents)


def _clamp(value: int, lo: int, hi: int, default: int) -> int:
    if value is None:
        return default
    if value < lo:
        return lo
    if value > hi:
        return hi
    return value

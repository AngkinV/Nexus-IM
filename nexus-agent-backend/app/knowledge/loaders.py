"""Extension-dispatched LangChain document loaders.

Given a (file_path, file_type) pair, return a list of LangChain `Document`
objects. The caller (ingestion.py) does not care whether a Document came
from a single text file or a multi-page PDF — splitting happens uniformly
downstream. This module's responsibility is purely "bytes on disk → list of
in-memory Documents with provenance metadata".

Supported types (mirrors the Java-side allowlist in KnowledgeBaseService):
    pdf  → PyPDFLoader        (one Document per page, page metadata kept)
    md   → UnstructuredMarkdownLoader  (single Document, structure-aware)
    txt  → TextLoader         (single Document, raw text)
    docx → Docx2txtLoader     (single Document; falls back gracefully if
                               python-docx can't open the file)
    doc  → UnstructuredWordDocumentLoader (legacy Office 97-2003 binary;
                               REQUIRES libreoffice or antiword on PATH —
                               otherwise we surface a clear actionable error
                               instead of unstructured's default stack trace)

Errors:
    UnsupportedFileTypeError — unrecognised file_type
    DocumentLoadError        — loader raised; reason captured for the row's
                               error_message column.
"""
from __future__ import annotations

import logging
import os
import shutil
from pathlib import Path
from typing import List

from langchain_core.documents import Document

log = logging.getLogger(__name__)


SUPPORTED_FILE_TYPES = {"pdf", "md", "markdown", "txt", "docx", "doc"}


class UnsupportedFileTypeError(ValueError):
    """Raised when file_type is not in SUPPORTED_FILE_TYPES."""


class DocumentLoadError(RuntimeError):
    """Raised when an underlying loader fails (corrupt PDF, broken docx, ...)."""


def load_document(file_path: str, file_type: str) -> List[Document]:
    """Dispatch to the appropriate LangChain loader and return its Documents.

    Each returned Document has at least:
        page_content : str
        metadata     : dict — augmented with {"source": file_path,
                              "fileType": canonical_type}; per-page docs
                              from PDF also carry "page".
    """
    canonical = _canonicalise_type(file_type)
    if canonical not in SUPPORTED_FILE_TYPES:
        raise UnsupportedFileTypeError(
            f"unsupported file type: {file_type!r}; allowed={sorted(SUPPORTED_FILE_TYPES)}"
        )

    path = Path(file_path)
    if not path.exists() or not path.is_file():
        raise DocumentLoadError(f"file not found or not a regular file: {file_path}")
    if os.path.getsize(file_path) == 0:
        raise DocumentLoadError(f"file is empty: {file_path}")

    try:
        if canonical == "pdf":
            docs = _load_pdf(file_path)
        elif canonical in {"md", "markdown"}:
            docs = _load_markdown(file_path)
        elif canonical == "txt":
            docs = _load_text(file_path)
        elif canonical == "docx":
            docs = _load_docx(file_path)
        elif canonical == "doc":
            docs = _load_doc(file_path)
        else:  # pragma: no cover — guarded above
            raise UnsupportedFileTypeError(canonical)
    except UnsupportedFileTypeError:
        raise
    except Exception as exc:
        raise DocumentLoadError(f"loader failed for {canonical} {file_path}: {exc}") from exc

    # Annotate with our canonical type so downstream metadata is uniform
    # regardless of which loader produced the doc.
    annotated_type = "md" if canonical in {"md", "markdown"} else canonical
    for d in docs:
        d.metadata = dict(d.metadata or {})
        d.metadata.setdefault("source", str(file_path))
        d.metadata["fileType"] = annotated_type

    return docs


def _canonicalise_type(file_type: str) -> str:
    if not file_type:
        return ""
    return file_type.strip().lower().lstrip(".")


def _load_pdf(file_path: str) -> List[Document]:
    from langchain_community.document_loaders import PyPDFLoader
    return PyPDFLoader(file_path).load()


def _load_markdown(file_path: str) -> List[Document]:
    # Unstructured pulls in heavy native deps; if it fails to import (e.g. on
    # a stripped runtime), fall back to TextLoader so .md files still ingest
    # — losing structural awareness but not the content.
    try:
        from langchain_community.document_loaders import UnstructuredMarkdownLoader
        return UnstructuredMarkdownLoader(file_path).load()
    except Exception as exc:
        log.warning("markdown structured loader unavailable, using plaintext fallback: %s", exc)
        return _load_text(file_path)


def _load_text(file_path: str) -> List[Document]:
    from langchain_community.document_loaders import TextLoader
    return TextLoader(file_path, encoding="utf-8", autodetect_encoding=True).load()


def _load_docx(file_path: str) -> List[Document]:
    from langchain_community.document_loaders import Docx2txtLoader
    return Docx2txtLoader(file_path).load()


def _load_doc(file_path: str) -> List[Document]:
    """Load legacy Office 97-2003 .doc binaries.

    `python-docx` and `Docx2txtLoader` are .docx-only — they cannot read .doc.
    UnstructuredWordDocumentLoader can, but at runtime it shells out to either
    ``libreoffice`` (preferred) or ``antiword``; if neither is on PATH the
    underlying call fails with a stack trace that's hard to map back to "you
    need to install libreoffice". We pre-flight that check here so the row's
    error_message column carries an actionable message.

    Production deployments should install one of:
      - libreoffice (covers both .doc and old binary .ppt/.xls if needed)
      - antiword    (lightweight, .doc only)
    """
    if shutil.which("libreoffice") is None and shutil.which("antiword") is None:
        raise DocumentLoadError(
            ".doc parsing requires libreoffice or antiword installed on the "
            "server (none found on PATH). Install one, or convert the file to "
            ".docx and re-upload."
        )
    from langchain_community.document_loaders import UnstructuredWordDocumentLoader
    return UnstructuredWordDocumentLoader(file_path).load()

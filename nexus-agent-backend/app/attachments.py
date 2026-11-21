"""Attachment extraction: the text of an uploaded file, without guessing.

Java uploads bytes and a display name; this module returns text. The name is
only ever a display name — the type is decided from it and the content is
checked against that type, so nothing here opens a path.

A file that cannot be read as text is refused rather than approximated: a
scanned PDF has no text, a password-protected PDF has none to give, and a
"docx" that is not a ZIP is not a document. Refusals raise `ValueError` with a
reason the caller can show.
"""
from __future__ import annotations

import io
import re
import zipfile
from xml.etree import ElementTree

# The model gets at most this many characters per attachment.
TEXT_BUDGET = 32_000
# The upload itself may be larger; beyond this it is refused outright.
MAX_UPLOAD_BYTES = 5 * 1024 * 1024
# A document part that unpacks larger than this is a compression bomb.
MAX_PART_BYTES = 8 * 1024 * 1024

TEXT_SUFFIXES = {"txt", "md", "markdown", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "json", "patch", "diff",
                 "xml", "csv", "html", "css", "scss", "yaml", "yml", "toml", "ini", "cfg", "sh", "sql", "java",
                 "kt", "go", "rs", "rb", "php", "c", "h", "cpp", "hpp", "log"}
_WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
_BAD_NAME = re.compile(r"[\\/\x00]|^[A-Za-z]:")


def _display_name(file_name: str) -> tuple[str, str]:
    """(name, lower-case suffix). A name that looks like a path is refused."""
    name = (file_name or "").strip()
    if not name or len(name) > 200 or name in (".", "..") or name.startswith(".") or _BAD_NAME.search(name):
        raise ValueError("the attachment name must be a plain file name, not a path")
    if "://" in name:
        raise ValueError("the attachment name must be a plain file name, not a path")
    suffix = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    if not suffix:
        raise ValueError("the attachment has no file type")
    return name, suffix


def _truncate(text: str) -> tuple[str, bool]:
    if len(text) <= TEXT_BUDGET:
        return text, False
    return text[:TEXT_BUDGET], True


def _check_size(content: bytes) -> None:
    if len(content) > MAX_UPLOAD_BYTES:
        raise ValueError(f"the upload exceeds the 5 MB size limit ({len(content)} bytes)")


def _text(name: str, content: bytes) -> str:
    if b"\x00" in content:
        raise ValueError("the file looks binary (it contains a NUL byte), so it has no text to read")
    try:
        return content.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError("the file is not valid UTF-8 text") from exc


def _docx(content: bytes) -> str:
    try:
        archive = zipfile.ZipFile(io.BytesIO(content))
    except zipfile.BadZipFile as exc:
        raise ValueError("the DOCX file is not a valid ZIP archive") from exc
    with archive:
        try:
            info = archive.getinfo("word/document.xml")
        except KeyError as exc:
            raise ValueError("the DOCX file has no Word document body") from exc
        if info.file_size > MAX_PART_BYTES:
            raise ValueError("the DOCX document part is too large to unpack")
        raw = archive.read(info)
    if raw.lstrip().startswith(b"<!DOCTYPE") or b"<!DOCTYPE" in raw[:4096]:
        raise ValueError("the DOCX document part carries a DTD, which is not accepted")
    try:
        root = ElementTree.fromstring(raw)
    except ElementTree.ParseError as exc:
        raise ValueError("the DOCX document XML is malformed") from exc
    if root.tag != f"{{{_WORD}}}document":
        raise ValueError("the DOCX file has no Word document body")
    parts: list[str] = []
    for paragraph in root.iter(f"{{{_WORD}}}p"):
        text = ""
        for node in paragraph.iter():
            if node.tag == f"{{{_WORD}}}t":
                text += node.text or ""
            elif node.tag == f"{{{_WORD}}}tab":
                text += "\t"
            elif node.tag in (f"{{{_WORD}}}br", f"{{{_WORD}}}cr"):
                text += "\n"
        parts.append(text)
    return "\n".join(parts).strip("\n")


def _pdf(content: bytes) -> str:
    if not content.lstrip()[:5].startswith(b"%PDF"):
        raise ValueError("the file is not a PDF")
    try:
        from pypdf import PdfReader
    except ImportError as exc:  # pragma: no cover - the dependency is pinned
        raise ValueError("PDF support is not installed") from exc
    try:
        reader = PdfReader(io.BytesIO(content))
        if reader.is_encrypted:
            raise ValueError("the PDF is password protected, so its text cannot be read")
        text = "\n".join(page.extract_text() or "" for page in reader.pages)
    except ValueError:
        raise
    except Exception as exc:
        raise ValueError("the PDF could not be read") from exc
    if not text.strip():
        raise ValueError("the PDF has no embedded text to read; OCR is not performed")
    return text


def extract_attachment(file_name: str, content: bytes) -> dict:
    """The text of `content`, with the display name and the real byte size.

    `truncated` says the text was cut to the model's budget, not that the upload
    was refused: an oversized upload raises.
    """
    name, suffix = _display_name(file_name)
    _check_size(content)
    if suffix == "docx":
        text = _docx(content)
    elif suffix == "pdf":
        text = _pdf(content)
    elif suffix in TEXT_SUFFIXES:
        text = _text(name, content)
    else:
        raise ValueError(f"the file type .{suffix} is not supported")
    body, truncated = _truncate(text)
    return {"fileName": name, "content": body, "truncated": truncated, "sizeBytes": len(content)}

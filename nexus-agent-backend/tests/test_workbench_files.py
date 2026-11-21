import hashlib
from io import BytesIO
from zipfile import ZIP_DEFLATED, ZipFile

import pytest


HEADERS = "--- a/notes.txt\n+++ b/notes.txt\n"
NO_LF = "\\ No newline at end of file\n"


def test_patch_applies_multiple_hunks_at_both_declared_offsets():
    from app.artifacts import apply_unified_patch

    patch = (
        "diff --git a/notes.txt b/notes.txt\nindex 0123456..abcdef0 100644\n" + HEADERS
        + "@@ -1,2 +1,3 @@ heading\n first\n-old\n+雪\n+extra\n"
        + "@@ -4 +5 @@\n-last\n" + NO_LF + "+final\n" + NO_LF
    )
    assert apply_unified_patch("first\nold\ngap\nlast", patch, "notes.txt") == "first\n雪\nextra\ngap\nfinal"


@pytest.mark.parametrize(("original", "hunk", "expected"), [
    ("", "@@ -0,0 +1 @@\n+first\n", "first\n"),
    ("first\n", "@@ -1 +0,0 @@\n-first\n", ""),
    ("start\nend", "@@ -1,0 +2 @@\n+middle\n", "start\nmiddle\nend"),
    ("head\n", "@@ -1,0 +2 @@\n+\n", "head\n\n"),
    ("old\n", "@@ -1 +1 @@\n-old\n+new\n" + NO_LF, "new"),
    ("old", "@@ -1 +1 @@\n-old\n" + NO_LF + "+new\n", "new\n"),
    ("a\nend", "@@ -1,2 +1,2 @@\n-a\n+b\n end\n" + NO_LF, "b\nend"),
    ("a\r\nb\u2028c\n", "@@ -1,2 +1,2 @@\n a\r\n-b\u2028c\n+d\n", "a\r\nd\n"),
    ("-- title\n", "@@ -1 +1 @@\n--- title\n+++ title\n", "++ title\n"),
])
def test_patch_line_endings_and_zero_length_ranges(original, hunk, expected):
    from app.artifacts import apply_unified_patch

    assert apply_unified_patch(original, HEADERS + hunk, "notes.txt") == expected


@pytest.mark.parametrize(("original", "hunk"), [
    ("other\nold\n", "@@ -1 +1 @@\n-old\n+new\n"),  # no fuzzy relocation
    ("a\nb\n", "@@ -1,2 +1,2 @@\n wrong\n-b\n+c\n"),
    ("a\n", "@@ -1,2 +1 @@\n-a\n+b\n"),
    ("a\n", "@@ -1 +1,2 @@\n-a\n+b\n"),
    ("a\n", "@@ -1 +1 @@\n-a\n+b\n+extra\n"),
    ("a\n", "@@ -1 +2 @@\n-a\n+b\n"),
    ("a\n", "@@ -2,0 +2 @@\n+b\n"),
    ("a\n", "@@ -0 +1 @@\n-a\n+b\n"),
    ("a\n", "@@ -1 +1 @@\n-a\n+b\n@@ -1 +1 @@\n-a\n+c\n"),
    ("a\nb\n", "@@ -1 +1 @@\n-a\n+b\n" + NO_LF),  # old suffix would follow new EOF
    ("a", "@@ -1,0 +2 @@\n+b\n"),
    ("a", "@@ -1 +1 @@\n-a\n+b\n"),
    ("a\n", "@@ -1 +1 @@\n-a\n" + NO_LF + "+b\n"),
    ("a\n", "@@ -1 +1 @@\n" + NO_LF + "-a\n+b\n"),
    ("a\x00\n", "@@ -1 +1 @@\n-a\x00\n+b\n"),
    ("a\n", "@@ -1 +1 @@\n-a\n+\ud800\n"),
])
def test_patch_rejects_bad_baselines_counts_ranges_and_eof(original, hunk):
    from app.artifacts import ArtifactError, apply_unified_patch

    with pytest.raises(ArtifactError):
        apply_unified_patch(original, HEADERS + hunk, "notes.txt")


@pytest.mark.parametrize(("name", "headers", "extra"), [
    ("../notes.txt", "--- ../notes.txt\n+++ ../notes.txt\n", ""),
    ("sub\\notes.txt", "--- sub\\notes.txt\n+++ sub\\notes.txt\n", ""),
    ("notes.txt", "--- a/nested/notes.txt\n+++ b/nested/notes.txt\n", ""),
    ("notes.txt", "--- /dev/null\n+++ b/notes.txt\n", ""),
    ("notes.txt", "--- a/notes.txt\n+++ /dev/null\n", ""),
    ("notes.txt", "--- a/notes.txt\n+++ b/renamed.txt\n", ""),
    ("notes.txt", "diff --git a/other b/other\n" + HEADERS, ""),
    ("notes.txt", "rename from other.txt\nrename to notes.txt\n" + HEADERS, ""),
    ("notes.txt", "new file mode 100644\n" + HEADERS, ""),
    ("notes.txt", "git apply input.patch\n" + HEADERS, ""),
    ("notes.txt", HEADERS, "git status\n"),
    ("notes.txt", HEADERS, "--- a/other\n+++ b/other\n@@ -1 +1 @@\n-x\n+y\n"),
])
def test_patch_accepts_only_one_named_upload(name, headers, extra):
    from app.artifacts import ArtifactError, apply_unified_patch

    with pytest.raises(ArtifactError):
        apply_unified_patch("a\n", headers + "@@ -1 +1 @@\n-a\n+b\n" + extra, name)


LINEAGE = dict(run_id="r1", agent_id="a2", node_id="n3", source_ids=["s4"], call_ids=["c5"])


def test_artifacts_include_only_closed_nonempty_fences_and_exact_payload_metadata():
    from app.artifacts import extract_artifacts

    payload = '\n  print("雪")  \r\n'
    text = ('Ignore inline ```x``` and this prose.\n```python filename="my report.py"\n'
            + payload + '```\n~~~json\n \n~~~\n```js\nnot closed')
    [artifact] = extract_artifacts(text, **LINEAGE)
    assert artifact["id"]
    assert artifact == {
        "id": artifact["id"], "name": "my report.py", "language": "python", "kind": "code",
        "content": payload, "sizeBytes": len(payload.encode()), "sha256": hashlib.sha256(payload.encode()).hexdigest(),
        "runId": "r1", "agentId": "a2", "nodeId": "n3", "sourceIds": ["s4"], "callIds": ["c5"],
    }
    assert extract_artifacts('prose and an inline `{"x":1}`', **LINEAGE) == []


def test_artifact_kinds_names_and_nonstandard_json():
    from app.artifacts import extract_artifacts

    text = (
        '```\n{"x": [1, true]}\n```\n```json bad.json\n{"x": NaN}\n```\n'
        '```diff\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-a\n+b\n```\n'
        '~~~~markdown name=guide.md\n# Notes\n```py\ncode\n```\n~~~~\n'
        '```mermaid\ngraph TD\n  A --> B\n```\n```js filename=../wrong.js\nlet x = 1;\n```\n'
        '```settings.json\n[1, 2]\n```\n```python\nx = 2\n```'
    )
    arts = extract_artifacts(text, **LINEAGE)
    assert [a["kind"] for a in arts] == ["json", "code", "patch", "markdown", "graph", "code", "json", "code"]
    assert [arts[i]["name"] for i in (1, 3, 6)] == ["bad.json", "guide.md", "settings.json"]
    assert [arts[i]["name"].rsplit(".", 1)[-1] for i in (0, 2, 4, 5, 7)] == ["json", "patch", "mmd", "js", "py"]
    assert "```py\ncode\n```\n" in arts[3]["content"]
    assert len({a["id"] for a in arts}) == len(arts)
    assert all("/" not in a["name"] and "sample" not in a["name"].lower() for a in arts)


@pytest.mark.parametrize("ext", ["txt", "md", "py", "js", "json", "patch", "TS", "xml", "csv"])
def test_text_attachments_preserve_uploaded_text_and_byte_size(ext):
    from app.attachments import extract_attachment

    name, text = f"my file.{ext}", "雪\r\n  final line"
    assert extract_attachment(name, text.encode()) == {
        "fileName": name, "content": text, "truncated": False, "sizeBytes": len(text.encode()),
    }


def test_text_budget_and_upload_limit_are_different_units():
    from app.attachments import extract_attachment

    prefix = "界" * 32_000
    assert not extract_attachment("text.txt", prefix.encode())["truncated"]
    data = (prefix + "more").encode()
    assert extract_attachment("text.txt", data) == {
        "fileName": "text.txt", "content": prefix, "truncated": True, "sizeBytes": len(data),
    }
    assert extract_attachment("text.txt", b"a" * (5 * 1024 * 1024))["truncated"]
    with pytest.raises(ValueError, match=r"(?i)5\s*M|size|limit"):
        extract_attachment("text.txt", b"a" * (5 * 1024 * 1024 + 1))
    with pytest.raises(ValueError, match="(?i)UTF-?8"):
        extract_attachment("text.txt", data + b"\xff")


@pytest.mark.parametrize(("name", "data", "reason"), [
    ("../note.py", b"pass", "name|path"), ("/tmp/x.py", b"pass", "name|path"),
    (r"C:\\tmp\\x.py", b"pass", "name|path"), ("file:///tmp/x.py", b"pass", "name|path"),
    ("data.bin", b"abc", "unsupported|type"), ("binary.txt", b"a\0b", "binary|NUL"),
    ("bad.docx", b"not zip", "DOCX|ZIP"), ("bad.pdf", b"not pdf", "PDF"),
])
def test_bad_uploads_report_the_problem(name, data, reason):
    from app.attachments import extract_attachment

    with pytest.raises(ValueError, match=f"(?i){reason}"):
        extract_attachment(name, data)


def _docx(document_xml):
    buf = BytesIO()
    with ZipFile(buf, "w", ZIP_DEFLATED) as archive:
        archive.writestr("word/document.xml", document_xml)
    return buf.getvalue()


WORD = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'


def test_docx_extracts_word_text_breaks_and_paragraphs_not_xml_markup():
    from app.attachments import extract_attachment

    xml = (f'<w:document xmlns:w="{WORD}"><w:body>'
           '<w:p><w:r><w:t>A &amp; B</w:t><w:tab/><w:t>雪</w:t></w:r></w:p>'
           '<w:tbl><w:tr><w:tc><w:p><w:r><w:t>next</w:t><w:br/><w:t>line</w:t></w:r>'
           '</w:p></w:tc></w:tr></w:tbl></w:body></w:document>')
    data = _docx(xml)
    assert extract_attachment("report.docx", data) == {
        "fileName": "report.docx", "content": "A & B\t雪\nnext\nline", "truncated": False, "sizeBytes": len(data),
    }
    data = _docx(xml.replace("next", "X" * 32_000))
    result = extract_attachment("report.docx", data)
    assert result["truncated"] and len(result["content"]) == 32_000


@pytest.mark.parametrize(("xml", "reason"), [
    ("<broken>", "XML|DOCX"), ("<other>not Word</other>", "body|Word|DOCX"),
    ('<!DOCTYPE doc><doc/>', 'DTD|DOCTYPE'),
    pytest.param(" " * (8 * 1024 * 1024 + 1), "limit|large|compression|size", id="oversized-part"),
])
def test_docx_rejects_malformed_or_excessive_document_parts(xml, reason):
    from app.attachments import extract_attachment

    with pytest.raises(ValueError, match=f"(?i){reason}"):
        extract_attachment("report.docx", _docx(xml))


def _pdf(text=None, encrypted=False):
    from pypdf import PdfWriter
    from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject

    writer = PdfWriter()
    page = writer.add_blank_page(200, 200)
    if text:
        font = DictionaryObject({NameObject("/Type"): NameObject("/Font"), NameObject("/Subtype"): NameObject("/Type1"),
                                 NameObject("/BaseFont"): NameObject("/Helvetica")})
        page[NameObject("/Resources")] = DictionaryObject({NameObject("/Font"): DictionaryObject({NameObject("/F1"): font})})
        stream = DecodedStreamObject()
        stream.set_data(b"BT /F1 12 Tf 10 100 Td (" + text + b") Tj ET")
        page[NameObject("/Contents")] = stream
    if encrypted:
        writer.encrypt("test-password")
    buf = BytesIO()
    writer.write(buf)
    return buf.getvalue()


def test_pdf_extracts_embedded_text_with_no_ocr_or_password_guessing():
    from app.attachments import extract_attachment

    data = _pdf(b"Read this page")
    result = extract_attachment("page.PDF", data)
    assert result["content"].strip() == "Read this page"
    assert result["sizeBytes"] == len(data) and not result["truncated"]
    with pytest.raises(ValueError, match="(?i)no.*text|OCR"):
        extract_attachment("blank.pdf", _pdf())
    with pytest.raises(ValueError, match="(?i)encrypt|password"):
        extract_attachment("locked.pdf", _pdf(b"private", encrypted=True))

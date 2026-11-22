"""The two document endpoints Java calls: a real file, a real patch, nothing written."""
import base64
import hashlib

import pytest
from fastapi.testclient import TestClient

from app import app


@pytest.fixture
def client():
    return TestClient(app)


def _signed():
    """The routes are internal; the signature check is exercised elsewhere, so bypass it here."""
    from app import routes

    async def accept():
        return {"actorUserId": 7, "traceId": "tr_doc", "provider": None}

    app.dependency_overrides[routes.verify_internal_signature] = accept
    yield
    app.dependency_overrides.clear()


def test_a_text_upload_is_extracted_with_its_real_size():
    for _ in _signed():
        text = "雪\r\n final line"
        response = TestClient(app).post("/v1/agent/attachments/extract", json={
            "fileName": "notes.txt", "contentBase64": base64.b64encode(text.encode()).decode()})
        assert response.status_code == 200, response.text
        assert response.json() == {"fileName": "notes.txt", "content": text, "truncated": False,
                                   "sizeBytes": len(text.encode())}


def test_a_document_that_cannot_be_read_is_refused_with_a_reason():
    for _ in _signed():
        response = TestClient(app).post("/v1/agent/attachments/extract", json={
            "fileName": "photo.png", "contentBase64": base64.b64encode(b"\x89PNG").decode()})
        assert response.status_code == 422
        assert "not supported" in response.json()["detail"]
        broken = TestClient(app).post("/v1/agent/attachments/extract", json={
            "fileName": "notes.txt", "contentBase64": "not base64!"})
        assert broken.status_code == 400


def test_a_patch_is_dry_run_and_reports_the_hashes_it_used():
    for _ in _signed():
        original, patch = "a\nb\n", "--- a/demo.txt\n+++ b/demo.txt\n@@ -1,2 +1,2 @@\n a\n-b\n+c\n"
        response = TestClient(app).post("/v1/agent/artifacts/patch-preview", json={
            "fileName": "demo.txt", "original": original, "patch": patch})
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["content"] == "a\nc\n"
        assert body["baseHash"] == hashlib.sha256(original.encode()).hexdigest()
        assert body["patchHash"] == hashlib.sha256(patch.encode()).hexdigest()


def test_a_patch_that_does_not_match_is_refused():
    for _ in _signed():
        response = TestClient(app).post("/v1/agent/artifacts/patch-preview", json={
            "fileName": "demo.txt", "original": "other\n",
            "patch": "--- a/demo.txt\n+++ b/demo.txt\n@@ -1 +1 @@\n-a\n+b\n"})
        assert response.status_code == 422
        assert "match" in response.json()["detail"]

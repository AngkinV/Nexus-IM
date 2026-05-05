"""Day 11 route tests for /v1/knowledge/{ingest,delete,query}.

Uses FastAPI's TestClient + a hand-rolled HMAC header builder that mirrors
the Java side. The deterministic local embedder is patched into ingestion
and knowledge_rag so no OpenAI key is required.
"""
from __future__ import annotations

import hashlib
import hmac
import time
import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import app
from app.config import get_settings
from app.knowledge import ingestion as ing
from app.rag import embeddings as emb_mod
from app.rag import knowledge_rag, vectorstore as vs


SIGNING_SECRET = "test-secret-day11"


class _DetEmbedding:
    def __init__(self, dim: int = 32):
        self.dim = dim

    def _vec(self, text: str) -> list[float]:
        v = [0.0] * self.dim
        for i, c in enumerate(text):
            v[i % self.dim] += float(ord(c)) / 1000.0
        v[0] += float(len(text)) / 1000.0
        return v

    def embed_documents(self, texts):
        return [self._vec(t) for t in texts]

    def embed_query(self, text):
        return self._vec(text)


@pytest.fixture(autouse=True)
def _isolate(tmp_path: Path, monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "chroma_persist_dir", str(tmp_path / "chroma"))
    monkeypatch.setattr(s, "internal_signing_secret", SIGNING_SECRET)
    monkeypatch.setattr(s, "expected_caller", "nexus-chat-backend")
    monkeypatch.setattr(s, "knowledge_rag_enabled", True)

    vs.reset_for_testing()
    emb_mod.reset_cache()

    fake = lambda **kw: _DetEmbedding()  # noqa: E731
    monkeypatch.setattr(ing, "get_embeddings", fake)
    monkeypatch.setattr(knowledge_rag, "get_embeddings", fake)

    yield

    vs.reset_for_testing()
    emb_mod.reset_cache()


def _signed_headers(body: bytes, *, actor_user_id: int = 1, trace_id: str = "tr_route_test") -> dict:
    timestamp = str(int(time.time() * 1000))
    nonce = uuid.uuid4().hex
    body_hash = hashlib.sha256(body).hexdigest()
    sig_input = f"{timestamp}.{nonce}.{body_hash}"
    signature = hmac.new(
        SIGNING_SECRET.encode("utf-8"),
        sig_input.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return {
        "X-Internal-Service": "nexus-chat-backend",
        "X-Internal-Timestamp": timestamp,
        "X-Internal-Nonce": nonce,
        "X-Internal-Signature": signature,
        "X-Trace-Id": trace_id,
        "X-Actor-User-Id": str(actor_user_id),
        "Content-Type": "application/json",
    }


def _post(client: TestClient, url: str, json_body: dict, **header_kwargs):
    import json as _json
    body = _json.dumps(json_body).encode("utf-8")
    return client.post(url, content=body, headers=_signed_headers(body, **header_kwargs))


# -------------- /v1/knowledge/ingest --------------


def test_ingest_route_succeeds_for_txt_file(tmp_path: Path):
    fp = tmp_path / "doc.txt"
    fp.write_text("hello world from a knowledge document " * 30, encoding="utf-8")

    with TestClient(app) as client:
        resp = _post(client, "/v1/knowledge/ingest", {
            "kbId": "kb_route_1",
            "docId": "doc_route_1",
            "filePath": str(fp),
            "fileType": "txt",
            "fileName": "doc.txt",
            "userId": 1,
            "chunkSize": 128,
            "chunkOverlap": 16,
        })
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["status"] == "READY"
    assert body["docId"] == "doc_route_1"
    assert body["chunkCount"] >= 1


def test_ingest_route_returns_502_on_missing_file(tmp_path: Path):
    with TestClient(app) as client:
        resp = _post(client, "/v1/knowledge/ingest", {
            "kbId": "kb_x",
            "docId": "doc_missing",
            "filePath": str(tmp_path / "nope.txt"),
            "fileType": "txt",
        })
    assert resp.status_code == 502
    assert resp.json()["code"] == "AGENT_KB_INGEST_50203"


def test_ingest_route_rejects_unsigned_request(tmp_path: Path):
    fp = tmp_path / "x.txt"
    fp.write_text("hello", encoding="utf-8")
    with TestClient(app) as client:
        # No HMAC headers — must be rejected by the dependency.
        resp = client.post("/v1/knowledge/ingest", json={
            "kbId": "kb_x", "docId": "doc_x",
            "filePath": str(fp), "fileType": "txt",
        })
    assert resp.status_code in (401, 403, 422)


# -------------- /v1/knowledge/query --------------


def test_query_route_returns_chunks_for_seeded_kb(tmp_path: Path):
    fp = tmp_path / "seed.txt"
    fp.write_text("项目报价单和合同条款 " * 30, encoding="utf-8")

    with TestClient(app) as client:
        # First, ingest via the route so the on-disk Chroma collection is
        # populated by the same code path the query will hit.
        ing_resp = _post(client, "/v1/knowledge/ingest", {
            "kbId": "kb_q1", "docId": "doc_q1",
            "filePath": str(fp), "fileType": "txt",
            "fileName": "seed.txt", "userId": 1,
            "chunkSize": 128, "chunkOverlap": 16,
        })
        assert ing_resp.status_code == 200

        q_resp = _post(client, "/v1/knowledge/query", {
            "kbId": "kb_q1", "query": "报价单", "topK": 3,
        })
    assert q_resp.status_code == 200
    body = q_resp.json()
    assert body["kbId"] == "kb_q1"
    assert len(body["chunks"]) >= 1
    first = body["chunks"][0]
    assert "报价单" in first["text"]
    assert first["metadata"]["kbId"] == "kb_q1"


def test_query_route_empty_query_returns_empty_chunks():
    with TestClient(app) as client:
        resp = _post(client, "/v1/knowledge/query", {
            "kbId": "kb_any", "query": "", "topK": 3,
        })
    assert resp.status_code == 200
    assert resp.json()["chunks"] == []


def test_query_route_user_id_filter_blocks_other_tenant(tmp_path: Path):
    fp = tmp_path / "seed.txt"
    fp.write_text("alice 私人合同 " * 30, encoding="utf-8")

    with TestClient(app) as client:
        _post(client, "/v1/knowledge/ingest", {
            "kbId": "kb_priv", "docId": "doc_priv",
            "filePath": str(fp), "fileType": "txt",
            "fileName": "seed.txt", "userId": 1,
        })

        # User 999 forges the kbId — userId filter must return zero hits.
        resp = _post(client, "/v1/knowledge/query", {
            "kbId": "kb_priv", "query": "私人合同", "userId": 999,
        })
    assert resp.status_code == 200
    assert resp.json()["chunks"] == []


# -------------- /v1/knowledge/delete --------------


def test_delete_route_removes_single_doc(tmp_path: Path):
    fp1 = tmp_path / "keep.txt"
    fp1.write_text("keep me " * 30, encoding="utf-8")
    fp2 = tmp_path / "drop.txt"
    fp2.write_text("drop me " * 30, encoding="utf-8")

    with TestClient(app) as client:
        for did, fp in [("doc_keep", fp1), ("doc_drop", fp2)]:
            _post(client, "/v1/knowledge/ingest", {
                "kbId": "kb_del1", "docId": did,
                "filePath": str(fp), "fileType": "txt",
                "fileName": fp.name,
            })

        del_resp = _post(client, "/v1/knowledge/delete", {
            "kbId": "kb_del1", "docId": "doc_drop",
        })
        assert del_resp.status_code == 200
        assert del_resp.json()["deletedCount"] >= 1

        # The "keep" doc must still be queryable.
        q = _post(client, "/v1/knowledge/query", {
            "kbId": "kb_del1", "query": "keep me", "topK": 5,
        })
        assert any(c["metadata"]["docId"] == "doc_keep" for c in q.json()["chunks"])


def test_delete_route_whole_kb_clears_all(tmp_path: Path):
    with TestClient(app) as client:
        for i in range(3):
            fp = tmp_path / f"d{i}.txt"
            fp.write_text(f"alpha {i} " * 20, encoding="utf-8")
            _post(client, "/v1/knowledge/ingest", {
                "kbId": "kb_wipe", "docId": f"doc_{i}",
                "filePath": str(fp), "fileType": "txt",
                "fileName": fp.name,
            })

        resp = _post(client, "/v1/knowledge/delete", {"kbId": "kb_wipe"})
        assert resp.status_code == 200
        assert resp.json()["deletedCount"] >= 3

        q = _post(client, "/v1/knowledge/query", {"kbId": "kb_wipe", "query": "alpha"})
        assert q.json()["chunks"] == []


def test_delete_route_returns_zero_when_kb_empty():
    with TestClient(app) as client:
        resp = _post(client, "/v1/knowledge/delete", {
            "kbId": "kb_never_existed", "docId": "doc_z",
        })
    assert resp.status_code == 200
    assert resp.json()["deletedCount"] == 0

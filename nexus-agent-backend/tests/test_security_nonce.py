"""Nonce replay protection for the Java -> Python internal HMAC channel."""
from __future__ import annotations

import hashlib
import hmac
import time
import uuid

import pytest
from fastapi import HTTPException
from starlette.requests import Request

from app.config import get_settings
from app.security import _seen_nonces, verify_internal_signature


@pytest.fixture(autouse=True)
def _clean_nonces():
    _seen_nonces.clear()
    yield
    _seen_nonces.clear()


def _request(body: bytes) -> Request:
    async def receive():
        return {"type": "http.request", "body": body, "more_body": False}

    return Request({"type": "http", "headers": []}, receive)


def _headers(body: bytes, nonce: str | None = None, ts_ms: int | None = None) -> dict:
    settings = get_settings()
    ts = str(ts_ms if ts_ms is not None else int(time.time() * 1000))
    nonce = nonce or uuid.uuid4().hex
    body_hash = hashlib.sha256(body).hexdigest()
    sig_input = f"{ts}.{nonce}.{body_hash}"
    signature = hmac.new(
        settings.internal_signing_secret.encode("utf-8"),
        sig_input.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return {
        "x_internal_service": settings.expected_caller,
        "x_internal_timestamp": ts,
        "x_internal_nonce": nonce,
        "x_internal_signature": signature,
        "x_trace_id": "tr_test",
        "x_actor_user_id": "1",
        # Called directly (not through FastAPI DI), so the optional Header
        # defaults must be supplied as plain values.
        "x_model_provider": None,
        "x_model_base_url": None,
        "x_model_name": None,
        "x_model_api_key": None,
    }


async def test_valid_request_passes():
    body = b'{"hello": "world"}'
    ctx = await verify_internal_signature(_request(body), **_headers(body))
    assert ctx["actorUserId"] == 1
    assert ctx["provider"] is None


async def test_replayed_nonce_rejected():
    body = b'{"hello": "world"}'
    headers = _headers(body)
    await verify_internal_signature(_request(body), **headers)
    with pytest.raises(HTTPException) as exc:
        await verify_internal_signature(_request(body), **headers)
    assert exc.value.status_code == 403
    assert exc.value.detail["message"] == "replayed nonce"


async def test_fresh_nonce_still_passes_after_replay():
    body = b'{"hello": "world"}'
    headers = _headers(body)
    await verify_internal_signature(_request(body), **headers)
    with pytest.raises(HTTPException):
        await verify_internal_signature(_request(body), **headers)
    ctx = await verify_internal_signature(_request(body), **_headers(body))
    assert ctx["actorUserId"] == 1


async def test_bad_signature_does_not_consume_nonce():
    body = b'{"hello": "world"}'
    headers = _headers(body)
    bad = dict(headers, x_internal_signature="0" * 64)
    with pytest.raises(HTTPException) as exc:
        await verify_internal_signature(_request(body), **bad)
    assert exc.value.detail["message"] == "bad signature"
    # The nonce was never accepted, so the legitimate request still works.
    ctx = await verify_internal_signature(_request(body), **headers)
    assert ctx["actorUserId"] == 1


async def test_stale_timestamp_rejected():
    body = b"{}"
    settings = get_settings()
    old_ms = int(time.time() * 1000) - settings.nonce_skew_ms - 1000
    with pytest.raises(HTTPException) as exc:
        await verify_internal_signature(_request(body), **_headers(body, ts_ms=old_ms))
    assert exc.value.detail["message"] == "stale timestamp"


async def test_expired_nonce_entry_is_reusable():
    body = b"{}"
    headers = _headers(body)
    # Simulate a nonce whose acceptance window has already elapsed.
    _seen_nonces[headers["x_internal_nonce"]] = int(time.time() * 1000) - 1
    ctx = await verify_internal_signature(_request(body), **headers)
    assert ctx["actorUserId"] == 1
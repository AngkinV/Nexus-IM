"""HMAC signature verification for Java -> Python internal calls.

Header contract (see Java 网关与 Python Agent 接口契约.md §3.2):
  X-Internal-Service:   nexus-chat-backend
  X-Internal-Timestamp: <ms>
  X-Internal-Nonce:     <uuid>
  X-Internal-Signature: HMAC-SHA256(secret, timestamp + "." + nonce + "." + sha256(body)
                                            + (if provider headers present)
                                              "." + sha256(provider|baseUrl|model|apiKeyB64))
  X-Trace-Id:           <trace>
  X-Actor-User-Id:      <userId>
  X-Model-Provider:     <optional, e.g. "openai" / "deepseek-1">
  X-Model-Base-URL:     <optional, OpenAI-compatible base URL>
  X-Model-Name:         <optional, model id>
  X-Model-Api-Key:      <optional, base64-encoded plaintext key>
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import time

from fastapi import Header, HTTPException, Request, status

from .config import get_settings


def _sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _hmac_sha256_hex(secret: str, content: str) -> str:
    return hmac.new(secret.encode("utf-8"), content.encode("utf-8"), hashlib.sha256).hexdigest()


async def verify_internal_signature(
    request: Request,
    x_internal_service: str = Header(..., alias="X-Internal-Service"),
    x_internal_timestamp: str = Header(..., alias="X-Internal-Timestamp"),
    x_internal_nonce: str = Header(..., alias="X-Internal-Nonce"),
    x_internal_signature: str = Header(..., alias="X-Internal-Signature"),
    x_trace_id: str = Header(..., alias="X-Trace-Id"),
    x_actor_user_id: str = Header(..., alias="X-Actor-User-Id"),
    x_model_provider: str | None = Header(None, alias="X-Model-Provider"),
    x_model_base_url: str | None = Header(None, alias="X-Model-Base-URL"),
    x_model_name: str | None = Header(None, alias="X-Model-Name"),
    x_model_api_key: str | None = Header(None, alias="X-Model-Api-Key"),
) -> dict:
    settings = get_settings()

    if x_internal_service != settings.expected_caller:
        raise HTTPException(status.HTTP_403_FORBIDDEN, detail={"code": "AGENT_AUTHZ_40301", "message": "invalid caller"})

    try:
        ts_ms = int(x_internal_timestamp)
    except ValueError as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail={"code": "AGENT_PARAM_40001", "message": "bad timestamp"}) from exc

    now_ms = int(time.time() * 1000)
    if abs(now_ms - ts_ms) > settings.nonce_skew_ms:
        raise HTTPException(status.HTTP_403_FORBIDDEN, detail={"code": "AGENT_AUTHZ_40301", "message": "stale timestamp"})

    body = await request.body()
    body_hash = _sha256_hex(body)
    sig_input = f"{x_internal_timestamp}.{x_internal_nonce}.{body_hash}"

    provider_present = x_model_provider is not None
    if provider_present:
        provider_blob = "|".join([
            x_model_provider or "",
            x_model_base_url or "",
            x_model_name or "",
            x_model_api_key or "",
        ])
        sig_input = sig_input + "." + _sha256_hex(provider_blob.encode("utf-8"))

    expected = _hmac_sha256_hex(settings.internal_signing_secret, sig_input)
    if not hmac.compare_digest(expected, x_internal_signature):
        raise HTTPException(status.HTTP_403_FORBIDDEN, detail={"code": "AGENT_AUTHZ_40301", "message": "bad signature"})

    api_key_plain: str | None = None
    if x_model_api_key:
        try:
            api_key_plain = base64.b64decode(x_model_api_key).decode("utf-8")
        except Exception:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, detail={"code": "AGENT_PARAM_40001", "message": "bad X-Model-Api-Key encoding"})

    provider_ctx = None
    if provider_present:
        provider_ctx = {
            "name": x_model_provider,
            "baseUrl": x_model_base_url or None,
            "model": x_model_name or None,
            "apiKey": api_key_plain,
        }

    return {
        "traceId": x_trace_id,
        "actorUserId": int(x_actor_user_id),
        "provider": provider_ctx,
    }


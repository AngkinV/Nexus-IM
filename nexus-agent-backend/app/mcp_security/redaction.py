"""Secret quarantine and untrusted data envelopes for tools/files/chat/recall."""
from __future__ import annotations

import base64
import json
import logging
import os
import re
from collections.abc import Mapping
from urllib.parse import quote, quote_plus, unquote

from .types import SecurityViolation, require_id

_REDACTED = "[REDACTED]"
_SENSITIVE = frozenset({
    "authorization", "proxyauthorization", "cookie", "setcookie", "password",
    "passwd", "secret", "token", "accesstoken", "refreshtoken", "apikey",
    "credential", "credentials", "internalToken", "internalSigningSecret",
    "javainternaltoken", "xmodelapikey", "xinternalSignature",
})
_BEARER = re.compile(r"(?i)\b(?:bearer|basic)\s+[A-Za-z0-9+/_=.\-]+")
_KEY_VALUE = re.compile(
    r"(?i)(\b(?:api[_-]?key|password|secret|access[_-]?token|refresh[_-]?token|token)"
    r"\s*[:=]\s*)(?:\"[^\"]*\"|'[^']*'|[^\s,;}]+)"
)


def normalized_key(key: str) -> str:
    return "".join(c for c in key.lower() if c.isalnum())


def sensitive_key(key: str) -> bool:
    normalized = normalized_key(key)
    return (normalized in {normalized_key(item) for item in _SENSITIVE}
            or normalized.endswith(("apikey", "password", "credential", "secret", "token")))


class SecretRedactor:
    """Registered values are never returned, rendered by repr, or logged.

    T4A registers ALL internal/provider/service secrets before opening a client.
    Encodings cover wire JSON, URL escaping, bearer/basic, base64 and hex.
    This is defense in depth, not permission to send sensitive context externally.
    """

    def __init__(self, values=()) -> None:
        self._variants: set[str] = set()
        for value in values:
            self.register(value)

    def __repr__(self) -> str:
        return "SecretRedactor(configured)"

    @property
    def configured(self) -> bool:
        return bool(self._variants)

    def absorb(self, other: "SecretRedactor") -> None:
        if not isinstance(other, SecretRedactor):
            raise SecurityViolation("SECRET_EGRESS")
        self._variants.update(other._variants)

    def register_credential(self, value: str) -> None:
        """Register the full wire value AND raw Bearer/Basic credential pieces."""
        self.register(value)
        parts = value.split(None, 1)
        if len(parts) != 2:
            return
        if parts[0].lower() in {"bearer", "basic"}:
            self.register(parts[1])
        if parts[0].lower() == "basic":
            try:
                decoded = base64.b64decode(parts[1], validate=True).decode("utf-8")
            except (ValueError, UnicodeError):
                return
            self.register(decoded)
            if ":" in decoded:
                self.register(decoded.split(":", 1)[1])

    def register(self, value: str | None) -> None:
        if value is None or value == "":
            return
        if not isinstance(value, str):
            raise SecurityViolation("SECRET_EGRESS")
        raw = value.encode("utf-8")
        variants = {
            value, quote(value, safe=""), quote_plus(value, safe=""),
            base64.b64encode(raw).decode("ascii"),
            base64.urlsafe_b64encode(raw).decode("ascii"),
            base64.urlsafe_b64encode(raw).decode("ascii").rstrip("="),
            raw.hex(), raw.hex().upper(), json.dumps(value, ensure_ascii=True)[1:-1],
            json.dumps(value, ensure_ascii=False)[1:-1],
        }
        for variant in tuple(variants):
            variants.add(quote(variant, safe=""))
            if "%" in variant:
                variants.add(re.sub(r"%[0-9A-Fa-f]{2}", lambda m: m[0].lower(), variant))
        self._variants.update(variant for variant in variants if variant)

    def matches_registered(self, text: str) -> bool:
        """For header quarantine: a service may use its OWN credential only.

        Decode whole Basic/wire base64 values too; encoding username:secret is
        not the same as encoding secret alone. No decoded value is emitted.
        """
        if not isinstance(text, str) or len(text) > 1048576:
            return True
        decoded = text
        for _ in range(5):
            if any(variant in decoded for variant in self._variants):
                return True
            for candidate in re.findall(r"[A-Za-z0-9+/_=-]{8,8192}", decoded):
                try:
                    raw = base64.b64decode(candidate + "=" * (-len(candidate) % 4),
                                           altchars=b"-_", validate=True).decode("utf-8")
                except (ValueError, UnicodeError):
                    continue
                if any(variant in raw for variant in self._variants):
                    return True
            next_decoded = unquote(decoded)
            if next_decoded == decoded:
                break
            decoded = next_decoded
        return False

    def contains_secret(self, text: str) -> bool:
        return (not isinstance(text, str) or self.matches_registered(text)
                or bool(_BEARER.search(text) or _KEY_VALUE.search(text)))

    def deny_secret(self, value) -> None:
        # Serialized JSON also joins split/escaped wire representations.
        try:
            text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, allow_nan=False)
        except (TypeError, ValueError, RecursionError):
            raise SecurityViolation("BAD_ARGUMENTS") from None
        if self.contains_secret(text):
            raise SecurityViolation("SECRET_EGRESS")
        if isinstance(value, Mapping):
            for key, item in value.items():
                if isinstance(key, str) and sensitive_key(key) and item not in (None, ""):
                    raise SecurityViolation("SECRET_EGRESS")
                self.deny_secret(item)
        elif isinstance(value, (list, tuple)):
            for item in value:
                self.deny_secret(item)

    def text(self, value: str) -> str:
        if not isinstance(value, str):
            return _REDACTED
        for variant in sorted(self._variants, key=len, reverse=True):
            value = value.replace(variant, _REDACTED)
        # Detection and output quarantine share the same decoding rule. When
        # an encoded occurrence cannot be replaced safely, redact the FIELD.
        if self.matches_registered(value):
            return _REDACTED
        value = _BEARER.sub(_REDACTED, value)
        return _KEY_VALUE.sub(lambda m: m[1] + _REDACTED, value)

    def scrub(self, value, *, _depth: int = 0):
        if _depth > 32:
            return _REDACTED
        if isinstance(value, str):
            return self.text(value)
        if value is None or type(value) in (bool, int, float):
            return value
        if isinstance(value, Mapping):
            clean = {}
            for key, item in value.items():
                if not isinstance(key, str):
                    continue
                clean_key = self.text(key)
                clean[clean_key] = (_REDACTED if sensitive_key(key)
                                    else self.scrub(item, _depth=_depth + 1))
            return clean
        if isinstance(value, (list, tuple)):
            return [self.scrub(item, _depth=_depth + 1) for item in value]
        # No str()/repr() of SDK objects or exceptions: those can carry headers.
        return _REDACTED

    def log_filter(self) -> "SecretLogFilter":
        return SecretLogFilter(self)


def _failure_origin(exc_info, *, frames: int = 6, causes: int = 3) -> str:
    """Exception class names and code locations only.

    Never the exception's message or arguments (those can carry headers, URLs or
    provider replies), never local variables. File names are reduced to their
    base name, so no deployment path is written either.
    """
    if not exc_info or exc_info is True or not isinstance(exc_info, tuple) or len(exc_info) < 2:
        return ""
    error = exc_info[1]
    parts: list[str] = []
    seen: set[int] = set()
    while isinstance(error, BaseException) and id(error) not in seen and len(parts) < causes:
        seen.add(id(error))
        trail: list[str] = []
        tb = error.__traceback__
        while tb is not None:
            code = tb.tb_frame.f_code
            trail.append(f"{os.path.basename(code.co_filename)}:{tb.tb_lineno} {code.co_name}")
            tb = tb.tb_next
        where = " < ".join(reversed(trail[-frames:]))
        parts.append(f"{type(error).__name__}" + (f" at {where}" if where else ""))
        error = error.__cause__ or (None if error.__suppress_context__ else error.__context__)
    return "; caused by ".join(parts)


class SecretLogFilter(logging.Filter):
    """Attach to output handlers, not just parent loggers (child propagation).

    T4A's runtime attaches this to all configured logging/trace export sinks.
    New handlers must use the same filter. Arbitrary third-party sinks are not
    made safe by a parent logger filter, so that is an explicit integration check.
    """

    def __init__(self, redactor: SecretRedactor) -> None:
        super().__init__()
        self._redactor = redactor

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            rendered = record.getMessage()
            clean = self._redactor.text(rendered)
        except Exception:
            rendered, clean = None, "redacted log entry"
        if clean != rendered:
            # Something was removed: only the cleaned text goes on, never the original parts.
            record.msg, record.args = clean, ()
        # Otherwise the record keeps its template and arguments: formatters such as the
        # server's access log read the arguments themselves.
        origin = _failure_origin(record.exc_info)
        if origin:
            # The exception itself is dropped below; where it came from is not a secret,
            # and without it a failure in production cannot be located at all.
            try:
                record.msg, record.args = f"{record.getMessage()} [{origin}]", ()
            except Exception:
                record.msg, record.args = f"redacted log entry [{origin}]", ()
        record.exc_info = None
        record.exc_text = None
        record.stack_info = None
        # JSON formatters serialize extras, not just msg. Drop all nonstandard
        # attributes except controlled audit identifiers; never retain headers,
        # URLs, args, raw results or arbitrary SDK objects under another name.
        standard = set(logging.makeLogRecord({}).__dict__) | {"message", "asctime"}
        audit = {"runId", "attemptId", "agentId", "callId", "serviceId", "toolId",
                 "status", "code", "classification", "dispatchCount", "latencyMs"}
        for key in tuple(record.__dict__):
            if key not in standard and key not in audit:
                del record.__dict__[key]
            elif isinstance(record.__dict__[key], str):
                record.__dict__[key] = self._redactor.text(record.__dict__[key])
            elif key in audit:
                record.__dict__[key] = self._redactor.scrub(record.__dict__[key])
        return True


def untrusted_envelope(value, *, source: str, data_class: str,
                       redactor: SecretRedactor, max_chars: int = 8000) -> dict:
    """Keep remote permission/system fields inside data, never control-plane.

    Also used by T2/T5 for file/chat/recall facts. Authority, provenance and
    source are supplied by trusted code, never copied out of remote payloads.
    """
    require_id(data_class)
    if redactor.contains_secret(data_class):
        raise SecurityViolation("BAD_ARGUMENTS")
    if (type(source) is not str or len(source) > 192
            or any(ord(char) < 32 for char in source)
            or type(max_chars) is not int or not 1 <= max_chars <= 64000):
        raise SecurityViolation("BAD_ARGUMENTS")
    clean = redactor.scrub(value)
    try:
        text = clean if isinstance(clean, str) else json.dumps(clean, ensure_ascii=False, allow_nan=False)
    except (TypeError, ValueError, RecursionError):
        raise SecurityViolation("REMOTE_ERROR") from None
    # Redact AFTER serialization too (secrets split across JSON text blocks).
    text = redactor.text(text)
    return {
        "source": redactor.text(source),
        "dataClass": data_class,
        "untrusted": True,
        "content": text[:max_chars],
        "truncated": len(text) > max_chars,
    }

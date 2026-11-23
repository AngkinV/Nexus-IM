"""Digest format regressions only; T1 runs them, not the T4A window."""
import hashlib

from app.mcp_catalog import _encode, catalog_version


def test_exact_empty_encoding_and_numeric_literal_types():
    assert catalog_version([]) == hashlib.sha256(b"mcp-catalog-v1:A0[]").hexdigest()
    assert _encode(1) == b"I1;"
    assert _encode(1.0) == b"R3ff0000000000000;"
    assert _encode(True) == b"T;"
    assert _encode(1) != _encode(1.0) != _encode(True)


def test_order_whitespace_and_runtime_limits_are_not_catalog_identity():
    first = [{"toolId": "s:t", "inputSchema": {"type": "object", "default": 1.0}, "allowed": False, "timeoutSec": 10}]
    second = [{"timeoutSec": 20, "allowed": True, "inputSchema": {"default": 1.0, "type": "object"}, "toolId": "s:t"}]
    assert catalog_version(first) == catalog_version(second)


def test_unicode_uses_utf8_byte_order_and_lengths():
    assert _encode("中") == b"S3:" + "中".encode()
    assert _encode({"b": True, "a": 1}) == b"O2{S1:aI1;S1:bT;}"

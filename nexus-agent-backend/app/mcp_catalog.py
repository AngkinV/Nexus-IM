"""Cross-language catalog digest v1; independent of JSON whitespace/float spelling.

The field set excludes approvals and runtime limits. JSON scalar TYPES remain
significant; floating values use IEEE-754 binary64 bytes, strings use UTF-8.
Java McpCatalogDigest implements the same encoding. No network/schema evaluator.
"""
from __future__ import annotations

import hashlib
import math
import struct

FIELDS = ("toolId", "serverId", "name", "remoteName", "description", "inputSchema", "readOnlyHint", "risk")


def _encode(value, depth=0):
    if depth > 32:
        raise ValueError("MCP catalog depth")
    if value is None:
        return b"N;"
    if type(value) is bool:
        return b"T;" if value else b"F;"
    if type(value) is int:
        return b"I" + str(value).encode("ascii") + b";"
    if type(value) is float:
        if not math.isfinite(value):
            raise ValueError("MCP catalog number")
        return b"R" + struct.pack(">d", value).hex().encode("ascii") + b";"
    if type(value) is str:
        raw = value.encode("utf-8")
        return b"S" + str(len(raw)).encode("ascii") + b":" + raw
    if type(value) is list:
        return b"A" + str(len(value)).encode("ascii") + b"[" + b"".join(_encode(item, depth + 1) for item in value) + b"]"
    if type(value) is dict and all(type(key) is str for key in value):
        keys = sorted(value, key=lambda key: key.encode("utf-8"))
        return b"O" + str(len(keys)).encode("ascii") + b"{" + b"".join(
            _encode(key, depth + 1) + _encode(value[key], depth + 1) for key in keys) + b"}"
    raise ValueError("MCP catalog JSON")


def catalog_version(tools):
    if type(tools) is not list or len(tools) > 128:
        raise ValueError("MCP catalog size")
    ordered = sorted(tools, key=lambda tool: tool["toolId"].encode("utf-8"))
    observed = [{key: tool.get(key) for key in FIELDS} for tool in ordered]
    encoded = b"mcp-catalog-v1:" + _encode(observed)
    if len(encoded) > 2097152:
        raise ValueError("MCP catalog size")
    return hashlib.sha256(encoded).hexdigest()

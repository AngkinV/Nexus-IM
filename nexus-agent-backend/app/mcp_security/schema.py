"""Bounded strict JSON and an intentionally fail-closed JSON Schema subset.

No remote references, regex evaluation, coercion or ignored validation keyword.
The registry may discover other schemas, but they are not executable until the
adapter supports them explicitly. Unknown properties default to denied.
"""
from __future__ import annotations

import json
import math
from collections.abc import Mapping
from typing import Any

from .types import SecurityViolation

_MAX_BYTES = 65536
_TYPES = frozenset({"object", "array", "string", "integer", "number", "boolean", "null"})
_ANNOTATIONS = frozenset({"title", "description", "default", "examples", "$schema"})
_KEYWORDS = _ANNOTATIONS | frozenset({
    "type", "properties", "required", "additionalProperties", "items", "enum", "const",
    "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength",
    "minItems", "maxItems", "minProperties", "maxProperties", "anyOf", "oneOf", "allOf",
})
_CONTROL = frozenset({
    "actor", "actoruserid", "principal", "owner", "owneruserid", "agentid", "runid", "attemptid",
    "scope", "scopekey", "scopeversion", "authorizationversion", "permissionversion",
    "permissions", "grants", "grant", "snapshot", "context", "headers", "authorization",
    "internalcredentials", "internalidentity", "providerauth", "readonlyhint", "egress",
    "egressallowed", "dataclass", "dataclasses", "provenance",
})


def _normalized(key: str) -> str:
    return "".join(char for char in key.lower() if char.isalnum())


def clone_arguments(arguments: Mapping[str, Any]) -> dict[str, Any]:
    """Fix arguments before any await; reject SDK objects, NaN and authority keys."""
    if type(arguments) is not dict:
        raise SecurityViolation("BAD_ARGUMENTS")
    count = 0

    def clone(value: Any, depth: int = 0) -> Any:
        nonlocal count
        count += 1
        if depth > 32 or count > 4096:
            raise SecurityViolation("BAD_ARGUMENTS")
        if value is None or type(value) in (str, bool, int, float):
            if type(value) is float and not math.isfinite(value):
                raise SecurityViolation("BAD_ARGUMENTS")
            if type(value) is int and value.bit_length() > 256:
                raise SecurityViolation("BAD_ARGUMENTS")
            if type(value) is str and len(value) > _MAX_BYTES:
                raise SecurityViolation("BAD_ARGUMENTS")
            return value
        if type(value) is dict:
            if len(value) > 1024:
                raise SecurityViolation("BAD_ARGUMENTS")
            clean = {}
            for key, item in value.items():
                if (type(key) is not str or len(key) > 256 or _normalized(key) in _CONTROL
                        or _normalized(key).startswith(("xinternal", "xactor", "xmodel"))):
                    raise SecurityViolation("BAD_ARGUMENTS")
                clean[key] = clone(item, depth + 1)
            return clean
        if type(value) is list:
            if len(value) > 4096:
                raise SecurityViolation("BAD_ARGUMENTS")
            return [clone(item, depth + 1) for item in value]
        raise SecurityViolation("BAD_ARGUMENTS")

    clean = clone(arguments)
    try:
        if len(json.dumps(clean, ensure_ascii=False, allow_nan=False).encode("utf-8")) > _MAX_BYTES:
            raise SecurityViolation("BAD_ARGUMENTS")
    except (TypeError, ValueError, UnicodeError, RecursionError):
        raise SecurityViolation("BAD_ARGUMENTS") from None
    return clean


def _schema_check(schema: Any, depth: int = 0, budget: list[int] | None = None) -> None:
    budget = [0] if budget is None else budget
    budget[0] += 1
    if (depth > 24 or budget[0] > 512 or not isinstance(schema, Mapping)
            or any(type(key) is not str for key in schema) or set(schema) - _KEYWORDS):
        raise SecurityViolation("BAD_SCHEMA")
    types = schema.get("type")
    if types is not None:
        types = (types,) if isinstance(types, str) else types
        if (not isinstance(types, (list, tuple)) or not types or len(types) > 7
                or any(type(item) is not str or item not in _TYPES for item in types)):
            raise SecurityViolation("BAD_SCHEMA")
    alternatives = False
    for name in ("anyOf", "oneOf", "allOf"):
        if name in schema:
            alternatives = True
            branches = schema[name]
            if not isinstance(branches, (tuple, list)) or not 1 <= len(branches) <= 16:
                raise SecurityViolation("BAD_SCHEMA")
            for branch in branches:
                _schema_check(branch, depth + 1, budget)
    if types is None and not alternatives and "enum" not in schema and "const" not in schema:
        raise SecurityViolation("BAD_SCHEMA")
    properties = schema.get("properties", {})
    if not isinstance(properties, Mapping) or len(properties) > 1024:
        raise SecurityViolation("BAD_SCHEMA")
    for name, child in properties.items():
        if type(name) is not str or len(name) > 256:
            raise SecurityViolation("BAD_SCHEMA")
        _schema_check(child, depth + 1, budget)
    required = schema.get("required", ())
    if (not isinstance(required, (list, tuple)) or any(type(name) is not str for name in required)
            or len(set(required)) != len(required) or not set(required) <= set(properties)):
        raise SecurityViolation("BAD_SCHEMA")
    additional = schema.get("additionalProperties", False)
    if isinstance(additional, Mapping):
        _schema_check(additional, depth + 1, budget)
    elif type(additional) is not bool:
        raise SecurityViolation("BAD_SCHEMA")
    if "items" in schema:
        _schema_check(schema["items"], depth + 1, budget)
    if types is not None and "array" in types and "items" not in schema:
        raise SecurityViolation("BAD_SCHEMA")
    for name in ("minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties"):
        if name in schema and (type(schema[name]) is not int or not 0 <= schema[name] <= _MAX_BYTES):
            raise SecurityViolation("BAD_SCHEMA")
    for name in ("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum"):
        if name in schema and (type(schema[name]) not in (int, float) or not math.isfinite(schema[name])):
            raise SecurityViolation("BAD_SCHEMA")
    if "enum" in schema and (not isinstance(schema["enum"], (tuple, list)) or not schema["enum"]
                              or len(schema["enum"]) > 256):
        raise SecurityViolation("BAD_SCHEMA")


def _json_equal(left: Any, right: Any) -> bool:
    # bool is not an integer; mutable/frozen arrays/maps compare by JSON shape.
    if isinstance(left, Mapping) and isinstance(right, Mapping):
        return set(left) == set(right) and all(_json_equal(left[key], right[key]) for key in left)
    if isinstance(left, (tuple, list)) and isinstance(right, (tuple, list)):
        return len(left) == len(right) and all(_json_equal(a, b) for a, b in zip(left, right))
    return type(left) is type(right) and left == right


def _matches_type(value: Any, name: str) -> bool:
    return {
        "object": type(value) is dict, "array": type(value) is list,
        "string": type(value) is str, "integer": type(value) is int,
        "number": type(value) in (int, float), "boolean": type(value) is bool,
        "null": value is None,
    }[name]


def _valid(schema: Mapping[str, Any], value: Any) -> bool:
    names = schema.get("type")
    if names is not None:
        names = (names,) if isinstance(names, str) else names
        if not any(_matches_type(value, name) for name in names):
            return False
    if "enum" in schema and not any(_json_equal(value, item) for item in schema["enum"]):
        return False
    if "const" in schema and not _json_equal(value, schema["const"]):
        return False
    for name in ("anyOf", "oneOf", "allOf"):
        if name in schema:
            matched = sum(_valid(branch, value) for branch in schema[name])
            if ((name == "anyOf" and matched < 1) or (name == "oneOf" and matched != 1)
                    or (name == "allOf" and matched != len(schema[name]))):
                return False
    if type(value) is dict and ((names is not None and "object" in names)
            or any(key in schema for key in ("properties", "required", "additionalProperties",
                                           "minProperties", "maxProperties"))):
        properties = schema.get("properties", {})
        if (not set(schema.get("required", ())) <= set(value)
                or len(value) < schema.get("minProperties", 0)
                or len(value) > schema.get("maxProperties", 1024)):
            return False
        additional = schema.get("additionalProperties", False)
        for key, item in value.items():
            if key in properties:
                if not _valid(properties[key], item):
                    return False
            elif additional is False or (isinstance(additional, Mapping) and not _valid(additional, item)):
                return False
    if type(value) is list:
        if len(value) < schema.get("minItems", 0) or len(value) > schema.get("maxItems", 4096):
            return False
        if "items" in schema and not all(_valid(schema["items"], item) for item in value):
            return False
    if type(value) is str:
        if len(value) < schema.get("minLength", 0) or len(value) > schema.get("maxLength", _MAX_BYTES):
            return False
    if type(value) in (int, float):
        for name, compare in (("minimum", lambda bound: value >= bound),
                              ("maximum", lambda bound: value <= bound),
                              ("exclusiveMinimum", lambda bound: value > bound),
                              ("exclusiveMaximum", lambda bound: value < bound)):
            if name in schema and not compare(schema[name]):
                return False
    return True


def validate_arguments(schema: Mapping[str, Any], arguments: Mapping[str, Any]) -> dict[str, Any]:
    clean = clone_arguments(arguments)
    _schema_check(schema)
    if not _valid(schema, clean):
        raise SecurityViolation("BAD_ARGUMENTS")
    return clean

"""Artifacts: strict unified-diff application and extraction of fenced blocks.

Both are deliberately strict. A patch is applied only to exactly the file it
names, at exactly the offsets it declares; anything else raises `ArtifactError`
so the caller can report it instead of writing a wrong file. An artifact comes
only from a complete, closed fence, and its size and digest are the real bytes
of its content.
"""
from __future__ import annotations

import hashlib
import json
import re
from typing import Any

_MAX_NAME = 200
_LANG = re.compile(r"[A-Za-z0-9_+.#-]{1,32}")
_NO_LF = "\\ No newline at end of file"
_HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")
_FENCE = re.compile(r"^(?P<fence>`{3,}|~{3,})(?P<info>.*)$")
_EXTENSION = re.compile(r"[A-Za-z0-9_+-]{1,16}")
_GRAPH_EXTENSION = {"mermaid": "mmd", "dot": "dot", "graphviz": "dot"}


class ArtifactError(ValueError):
    """A patch that cannot be applied safely."""


def _reject_constant(value: str):
    raise ValueError(f"{value} is not JSON")


# ----- unified diff -----


def _decode(raw: str) -> str:
    """Refuse text that could not be written back out as UTF-8."""
    try:
        raw.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise ArtifactError("the patch contains text that is not valid UTF-8") from exc
    if "\x00" in raw:
        raise ArtifactError("the patch contains a NUL byte")
    return raw


def _plain_name(name: str) -> None:
    if not name or len(name) > _MAX_NAME or name in (".", "..") or name.startswith("."):
        raise ArtifactError("the target must be a plain file name")
    if "/" in name or "\\" in name or "://" in name or re.match(r"^[A-Za-z]:", name):
        raise ArtifactError("the target must be a plain file name, not a path")


def _check_target(patch: str, file_name: str) -> None:
    """Only a content change to exactly `file_name`, nothing else."""
    _plain_name(file_name)
    minus = plus = 0
    # Only the preamble carries file headers; inside a hunk a line may start with "--- " legitimately.
    preamble = patch.split("\n@@", 1)[0].split("\n")
    for line in preamble:
        if line.startswith(("--- ", "+++ ")):
            marker, target = line[:3], line[4:].split("\t")[0].strip()
            if target == "/dev/null":
                raise ArtifactError("creating or deleting a file is not supported")
            if target.rsplit("/", 1)[-1] != file_name or target[: -len(file_name)] not in ("", "a/", "b/"):
                raise ArtifactError(f"the patch does not modify exactly {file_name}")
            if marker == "---":
                minus += 1
            else:
                plus += 1
        elif line.startswith("diff --git "):
            parts = line.split()
            if len(parts) != 4 or any(part.rsplit("/", 1)[-1] != file_name for part in parts[2:]):
                raise ArtifactError(f"the patch does not modify exactly {file_name}")
        elif line.startswith(("rename from ", "rename to ", "copy from ", "copy to ")):
            raise ArtifactError("renaming or copying files is not supported")
        elif line.startswith(("new file mode", "deleted file mode", "old mode", "new mode", "similarity index")):
            raise ArtifactError("only a content change is supported")
        elif line.startswith(("git apply", "$ ")):
            raise ArtifactError("the patch contains commands, not a diff")
    if minus != 1 or plus != 1:
        raise ArtifactError("the patch must carry exactly one file header")


def _hunk_bodies(patch: str) -> list[list[str]]:
    bodies: list[list[str]] = []
    current: list[str] | None = None
    for line in patch.split("\n"):
        if _HUNK.match(line):
            current = [line]
            bodies.append(current)
        elif current is not None:
            # Inside a hunk a line beginning with "--- " is a removed line, not a header.
            if line.startswith(("diff --git ", "@@")):
                current = None
            else:
                current.append(line)
    if not bodies:
        raise ArtifactError("the patch contains no hunk")
    return bodies


def _tokens(body: list[str]) -> list[tuple[str, str, bool]]:
    """(marker, text, ends_without_newline) for each line of a hunk.

    The marker line that follows a content line belongs to that line, so it is
    folded into the previous entry rather than read as a line of its own.
    """
    parsed: list[tuple[str, str, bool]] = []
    for line in body[1:]:
        if line == "":
            continue  # the newline that ended the patch, not a line of the hunk
        if line.startswith(_NO_LF):
            if not parsed:
                raise ArtifactError("an end-of-file marker must follow a line")
            marker, text, _ = parsed[-1]
            parsed[-1] = (marker, text, True)
            continue
        marker = line[:1]
        if marker not in (" ", "-", "+"):
            raise ArtifactError("a hunk contains something that is not a diff line")
        parsed.append((marker, _decode(line[1:]), False))
    return parsed


def apply_unified_patch(original: str, patch: str, file_name: str) -> str:
    """Apply one unified diff to `original` and return the new text.

    Offsets are honoured exactly: a hunk that does not match at its declared
    position is refused rather than searched for somewhere else.
    """
    _check_target(patch, file_name)
    source = original.split("\n")
    trailing_newline = original.endswith("\n")
    if trailing_newline:
        source.pop()
    result: list[str] = []
    cursor = 0
    for body in _hunk_bodies(patch):
        header = _HUNK.match(body[0])
        old_start, old_count = int(header.group(1)), int(header.group(2) or 1)
        new_start, new_count = int(header.group(3)), int(header.group(4) or 1)
        start = old_start - 1 if old_count else old_start
        if start < cursor or start > len(source):
            raise ArtifactError("a hunk starts outside the file or overlaps the previous one")
        if start == len(source) and source and not original.endswith("\n") and old_count == 0:
            raise ArtifactError("a hunk appends after a last line that has no newline")
        result.extend(source[cursor:start])
        cursor = start
        if new_count and new_start != len(result) + 1:
            raise ArtifactError("a hunk's new offset does not match where it lands")
        if not new_count and new_start not in (len(result), len(result) + 1):
            raise ArtifactError("a hunk's new offset does not match where it lands")

        seen_old = seen_new = 0
        new_ends_without_newline = None
        for marker, text, no_newline in _tokens(body):
            last_source_line = cursor == len(source) - 1
            if marker in (" ", "-"):
                if seen_old >= old_count:
                    raise ArtifactError("a hunk carries more old lines than it declares")
                if cursor >= len(source) or source[cursor] != text:
                    raise ArtifactError("a hunk does not match the file at its declared position")
                if last_source_line and not trailing_newline and not no_newline:
                    raise ArtifactError("a hunk treats the last line as if it ended with a newline")
                if no_newline and (not last_source_line or trailing_newline):
                    raise ArtifactError("a hunk claims the end of the file where the file has a newline")
                if no_newline:
                    trailing_newline = False
                cursor += 1
                seen_old += 1
                if marker == " ":
                    result.append(text)
                    seen_new += 1
                if marker == "-" and no_newline:
                    continue
            if marker in (" ", "+"):
                if marker == "+":
                    if seen_new >= new_count:
                        raise ArtifactError("a hunk carries more new lines than it declares")
                    result.append(text)
                    seen_new += 1
                new_ends_without_newline = no_newline
                if no_newline and cursor != len(source):
                    raise ArtifactError("a hunk claims the end of the file before it ends")
        if seen_old != old_count or seen_new != new_count:
            raise ArtifactError("a hunk does not carry the number of lines it declares")
        if new_ends_without_newline is not None and cursor == len(source):
            trailing_newline = not new_ends_without_newline
    result.extend(source[cursor:])
    return "\n".join(result) + ("\n" if trailing_newline and result else "")


# ----- fenced artifacts -----


def _split_info(info: str) -> tuple[str, str | None]:
    """The language and an explicit display name from a fence info string."""
    tokens = info.split()
    if not tokens:
        return "", None
    language, rest = tokens[0], info[len(tokens[0]):]
    name: str | None = None
    if "." in language:
        # A bare file name such as "settings.json": its extension is the language.
        name, language = language, language.rsplit(".", 1)[-1]
    # A quoted value may hold spaces, so it is matched against the whole remainder.
    quoted = re.search(r'[A-Za-z]+\s*=\s*(?:"([^"]+)"|\'([^\']+)\')', rest)
    if quoted:
        name = quoted.group(1) or quoted.group(2)
    else:
        for token in rest.split():
            value = token.split("=", 1)[1] if "=" in token else token
            if "." in value:
                name = value
                break
    return language.lower(), name


_EXTENSION_ALIASES = {"python": "py", "javascript": "js", "typescript": "ts", "ruby": "rb", "shell": "sh",
                      "bash": "sh", "markdown": "md", "mermaid": "mmd", "text": "txt", "plaintext": "txt"}


def _display_name(explicit: str | None, kind: str, language: str) -> str:
    if explicit:
        cleaned = explicit.strip().strip("\"'").replace("\\", "/").rsplit("/", 1)[-1].strip()
        if cleaned and ".." not in cleaned and len(cleaned) <= 120 and not cleaned.startswith("."):
            return cleaned
    extension = _GRAPH_EXTENSION.get(language) or {"json": "json", "patch": "patch", "markdown": "md"}.get(kind) or (
        _EXTENSION_ALIASES.get(language) or language if _EXTENSION.fullmatch(language or "") else "txt")
    return f"artifact.{extension}"


def _kind(language: str, content: str) -> str:
    if not language:
        # An unlabelled fence is judged by what it holds.
        stripped = content.strip()
        if stripped.startswith(("{", "[")):
            try:
                json.loads(stripped, parse_constant=_reject_constant)
                return "json"
            except ValueError:
                return "code"
        return "code"
    if language in ("diff", "patch"):
        return "patch"
    if language in ("markdown", "md"):
        return "markdown"
    if language in _GRAPH_EXTENSION:
        return "graph"
    if language == "json":
        try:
            # Strict JSON: NaN and Infinity are not JSON, so such a block is not a JSON artifact.
            json.loads(content, parse_constant=_reject_constant)
        except ValueError:
            return "code"
        return "json"
    return "code"


def extract_artifacts(text: str, *, run_id: str, agent_id: str, node_id: str,
                      source_ids: list[str], call_ids: list[str]) -> list[dict[str, Any]]:
    """Complete fenced blocks in `text`, with their real byte size and digest.

    An unterminated fence ends extraction: nothing after it was finished either.
    """
    lines = (text or "").split("\n")
    found: list[dict[str, Any]] = []
    seen: dict[str, int] = {}
    index = 0
    while index < len(lines):
        opening = _FENCE.match(lines[index])
        if not opening:
            index += 1
            continue
        fence, info = opening.group("fence"), opening.group("info").strip()
        closing = index + 1
        while closing < len(lines):
            candidate = lines[closing].strip()
            if candidate and set(candidate) == {fence[0]} and len(candidate) >= len(fence):
                break
            closing += 1
        if closing >= len(lines):
            break
        body = lines[index + 1:closing]
        content = "\n".join(body) + ("\n" if body else "")
        if content.strip():
            language, explicit = _split_info(info)
            if language and not _LANG.fullmatch(language):
                language, explicit = "", explicit
            kind = _kind(language, content)
            digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
            base = f"{node_id or 'node'}-{digest[:12]}"
            seen[base] = seen.get(base, 0) + 1
            found.append({
                "id": base if seen[base] == 1 else f"{base}-{seen[base]}",
                "name": _display_name(explicit, kind, language),
                "language": language or "text",
                "kind": kind,
                "content": content,
                "sizeBytes": len(content.encode("utf-8")),
                "sha256": digest,
                "runId": run_id,
                "agentId": agent_id,
                "nodeId": node_id,
                "sourceIds": list(source_ids),
                "callIds": list(call_ids),
            })
        index = closing + 1
    return found

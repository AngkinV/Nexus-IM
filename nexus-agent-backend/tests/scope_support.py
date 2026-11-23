"""Test-only scope helpers.

Every agent turn runs under an :class:`AuthorizedScope`. Unit tests that exercise the
graph, the tool layer or the RAG modules have no live Java authority, so they build a
synthetic grant here and stub the validator. The grant is always internally consistent
(its ``scopeKey`` is derived from its own fields), so nothing under test can tell the
difference between it and one Java issued.
"""
from __future__ import annotations

from typing import Any, Iterable

from app.scope import AuthorizedScope, ScopeClient, ScopeDenied, scope_hash


_SEQ = 0


def next_run_id(prefix: str = "run") -> str:
    """A fresh run id per request: a checkpoint store refuses a reused one."""
    global _SEQ
    _SEQ += 1
    return f"{prefix}_{_SEQ:08d}"


def make_scope(
    actor: int = 7,
    *,
    session_id: str = "s_test",
    run_id: str = "run_test_0001",
    chats: Iterable[int] = (),
    contacts: Iterable[int] = (),
    generation: int = 1,
    authorization_version: int = 1,
    grant_id: str | None = None,
) -> AuthorizedScope:
    """A synthetic grant for ``actor`` over exactly the given chats/contacts."""
    data: dict[str, Any] = dict(
        schemaVersion=1,
        grantId=grant_id or f"g_{actor}_{session_id}",
        actorUserId=actor,
        sessionId=session_id,
        runId=run_id,
        bindingVersion=1,
        authorizationVersion=authorization_version,
        sessionGeneration=generation,
        chatIds=sorted(set(chats)),
        contactUserIds=sorted(set(contacts)),
        timeRange=dict(fromInclusive=None, toExclusive=None, timeZone="Asia/Shanghai"),
    )
    data["scopeKey"] = scope_hash(data)
    return AuthorizedScope.model_validate(data)


async def accept_any_scope(self, scope, tool=None):
    """Stand-in for :meth:`ScopeClient.validate` that accepts a present scope."""
    if scope is None:
        raise ScopeDenied()
    return scope


def install_scope_authority(monkeypatch, targets: Iterable[Any]) -> None:
    """Point every named module's ``ScopeClient.validate`` at the local stub."""
    for module in targets:
        monkeypatch.setattr(module.ScopeClient, "validate", accept_any_scope)

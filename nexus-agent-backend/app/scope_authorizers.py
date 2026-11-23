"""T2's half of the frozen runtime contract §6.1 authorizer slots.

T1 owns `orchestrator.reuse_authorizer` / `replay_authorizer` and installs them
once at application start-up. This module only supplies T2's decision function
and a composition helper, so T4B's policy is intersected rather than replaced:

    install_scope_authorizers(scope_reuse_authorizer, scope_replay_authorizer,
                              compose=orchestrator.install_authorizers)

Nothing here installs itself at import time, and nothing here fakes a T1 slot
that does not exist yet.

Authorization is decided live by Java: the scope must still be an active grant
for this actor/session/run, and a committed tool call's resource arguments must
still fall inside it. Cached receipts are never a grant, and a cached
`message_id` is resolved by Java's non-body projection, not by reading a body.
"""
from __future__ import annotations

from typing import Any, Awaitable, Callable, Optional

from .scope import AuthorizedScope, ScopeClient, ScopeDenied

Authorizer = Callable[[Any, str, Any], Awaitable[bool]]

#: Kinds the frozen contract defines. Anything else is refused, not guessed.
CALL_KINDS = frozenset({"tool", "agent", "resume", "handover"})
TOOL_KINDS = frozenset({"tool"})

#: Identity fields a cached call may carry; all must agree with the live run.
_IDENTITY_FIELDS = ("actorUserId", "sessionId", "runId", "scopeKey",
                    "sessionGeneration", "authorizationVersion", "grantId")


def _current_scope(request: Any) -> AuthorizedScope:
    scope = getattr(request, "scope", None)
    if scope is None:
        raise ScopeDenied()
    actor = getattr(request, "actor", None)
    session = getattr(request, "session", None)
    actor_id = getattr(actor, "userId", None)
    session_id = getattr(session, "sessionId", None) or getattr(request, "sessionId", None)
    run_id = getattr(request, "runId", None)
    if not isinstance(actor_id, int) or not session_id or not run_id:
        raise ScopeDenied()
    return scope.bind(actor_id, session_id, run_id)


def _tool_identity(call: Any) -> tuple[str, dict[str, Any]]:
    if isinstance(call, dict):
        name, arguments = call.get("name"), call.get("arguments")
    else:
        name, arguments = getattr(call, "name", None), getattr(call, "arguments", None)
    if not isinstance(name, str) or not name or not isinstance(arguments, dict):
        raise ScopeDenied()
    return name, dict(arguments)


def _check_reference(scope: AuthorizedScope, call: Any) -> None:
    """A cached call bound to another grant/run cannot ride the current one.

    An object with none of the identity fields is not a committed-call record we
    can check, so it is refused rather than treated as universally reusable.
    """
    if isinstance(call, dict):
        reference = call
    else:
        reference = {field: getattr(call, field, None) for field in _IDENTITY_FIELDS}
        if all(value is None for value in reference.values()):
            raise ScopeDenied()
    for field in _IDENTITY_FIELDS:
        expected = reference.get(field)
        if expected is None:
            continue
        actual = getattr(scope, field, None)
        if actual is None or str(actual) != str(expected):
            raise ScopeDenied()


async def _authorize(request: Any, kind: str, call: Any, *,
                     client: Optional[ScopeClient] = None) -> bool:
    try:
        if kind not in CALL_KINDS:
            return False
        scope = _current_scope(request)
        if kind in TOOL_KINDS:
            name, arguments = _tool_identity(call)
            _check_reference(scope, call)
            await (client or ScopeClient()).validate(scope, tool={"name": name, "arguments": arguments})
            return True
        # agent / resume / handover reuse only re-checks the live grant; the
        # downstream role, input and attempt identity stay T1/T5's decision.
        _check_reference(scope, call)
        await (client or ScopeClient()).validate(scope)
        return True
    except (ScopeDenied, ValueError, TypeError, KeyError):
        return False


async def scope_reuse_authorizer(request: Any, kind: str, call: Any, *,
                                 client: Optional[ScopeClient] = None) -> bool:
    """T2 policy for reusing a committed call's stored result."""
    return await _authorize(request, kind, call, client=client)


async def scope_replay_authorizer(request: Any, kind: str, call: Any, *,
                                  client: Optional[ScopeClient] = None) -> bool:
    """T2 policy for replaying a call that was attempted but not confirmed.

    Identical resource checks to reuse; side-effect and retryability policy is
    T4B's half of the intersection, never assumed here.
    """
    return await _authorize(request, kind, call, client=client)


def combine_authorizers(*authorizers: Authorizer) -> Authorizer:
    """Intersect policies: every installed party must allow, and any error denies.

    Order is irrelevant to the outcome, but every authorizer is consulted so a
    later one cannot silently override an earlier refusal.
    """
    if not authorizers or any(not callable(a) for a in authorizers):
        raise ValueError("authorizers must be callable")

    async def combined(request: Any, kind: str, call: Any) -> bool:
        allowed = True
        for authorizer in authorizers:
            try:
                result = await authorizer(request, kind, call)
            except Exception:  # noqa: BLE001 - a broken policy must deny, not crash the run
                return False
            if result is not True:
                allowed = False
        return allowed

    return combined


def install_scope_authorizers(reuse: Authorizer, replay: Authorizer, *,
                              compose: Callable[..., None]) -> None:
    """Hand T2's policy to T1's single start-up installer.

    `compose` is T1's own installer; it is responsible for intersecting with an
    already-installed T4B policy. Refusing to proceed when it is missing keeps
    this module from silently pretending the slots exist.
    """
    if not callable(compose):
        raise TypeError("compose must be T1's start-up installer")
    if not callable(reuse) or not callable(replay):
        raise TypeError("authorizers must be callable")
    compose(reuse, replay)

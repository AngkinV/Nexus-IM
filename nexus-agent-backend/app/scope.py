"""AuthorizedScope v1. A hash is a partition identity, never a grant proof."""
from __future__ import annotations
import base64
import hashlib
import json
import re
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
from typing import Any
import httpx
from pydantic import BaseModel, ConfigDict, StrictInt, Field, model_validator
from .config import get_settings


class ScopeDenied(ValueError):
    def __init__(self):
        super().__init__('scope authorization denied')


def epoch(value: str | None) -> int | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ScopeDenied()
    # datetime truncates fractional digits past microseconds. Reject non-zero
    # digits beyond milliseconds BEFORE parsing, so v1's key cannot alias two
    # distinct windows. Zero tails describe the same exact millisecond.
    # fromisoformat also accepts a comma separator and the basic (colon-less)
    # form, so look at every fraction rather than one spelling of it.
    for fraction in re.findall(r'[.,](\d+)', value):
        if any(digit != '0' for digit in fraction[3:]):
            raise ScopeDenied()
    try:
        dt = datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        # An unreadable boundary proves nothing about the window: deny, not crash.
        raise ScopeDenied() from None
    if dt.tzinfo is None:
        raise ScopeDenied()
    utc = dt.astimezone(timezone.utc)
    if utc.microsecond % 1000:
        raise ScopeDenied()
    delta = utc - datetime(1970, 1, 1, tzinfo=timezone.utc)
    return (delta.days * 86400 + delta.seconds) * 1000 + delta.microseconds // 1000


def scope_hash(d: dict) -> str:
    t = d['timeRange']
    parts = ['v1', str(d['actorUserId']), d['sessionId'], str(d['sessionGeneration']),
             ','.join(map(str, sorted(d['chatIds']))),
             ','.join(map(str, sorted(d['contactUserIds'])))]
    parts += [str(epoch(t[k])) if t[k] is not None else '*'
              for k in ('fromInclusive', 'toExclusive')]
    return hashlib.sha256('|'.join(parts).encode('utf-8')).hexdigest()


class TimeRange(BaseModel):
    model_config = ConfigDict(extra='forbid', frozen=True)
    fromInclusive: str | None
    toExclusive: str | None
    timeZone: str

    @model_validator(mode='after')
    def valid(self):
        ZoneInfo(self.timeZone)
        lo, hi = epoch(self.fromInclusive), epoch(self.toExclusive)
        if lo is not None and hi is not None and lo >= hi:
            raise ScopeDenied()
        return self


class AuthorizedScope(BaseModel):
    model_config = ConfigDict(extra='forbid', frozen=True)
    schemaVersion: StrictInt
    grantId: str = Field(min_length=1, max_length=256)
    actorUserId: StrictInt = Field(gt=0)
    sessionId: str = Field(min_length=1, max_length=256)
    runId: str = Field(min_length=1, max_length=64)
    bindingVersion: StrictInt = Field(ge=1)
    authorizationVersion: StrictInt = Field(ge=1)
    sessionGeneration: StrictInt = Field(ge=1)
    chatIds: list[StrictInt]
    contactUserIds: list[StrictInt]
    timeRange: TimeRange
    scopeKey: str = Field(pattern=r'^[0-9a-f]{64}$')

    @model_validator(mode='after')
    def valid(self):
        if self.schemaVersion != 1 or '|' in self.sessionId:
            raise ScopeDenied()
        for ids in (self.chatIds, self.contactUserIds):
            if any(i <= 0 for i in ids) or len(ids) != len(set(ids)):
                raise ScopeDenied()
        if scope_hash(self.model_dump()) != self.scopeKey:
            raise ScopeDenied()
        return self

    def bind(self, actor: int, session: str, run: str):
        if (self.actorUserId, self.sessionId, self.runId) != (actor, session, run):
            raise ScopeDenied()
        return self

    def header(self) -> str:
        return base64.urlsafe_b64encode(self.model_dump_json().encode()).decode().rstrip('=')


def narrow_scope(parent: AuthorizedScope, *, chat_ids=None, contact_user_ids=None,
                 time_range=None) -> AuthorizedScope:
    d = parent.model_dump()
    if chat_ids is not None: d['chatIds'] = list(chat_ids)
    if contact_user_ids is not None: d['contactUserIds'] = list(contact_user_ids)
    if time_range is not None: d['timeRange'] = dict(time_range)
    for key in ('chatIds', 'contactUserIds'):
        if not set(d[key]) <= set(getattr(parent, key)):
            raise ScopeDenied()
    for key, lower in (('fromInclusive', True), ('toExclusive', False)):
        old, new = epoch(getattr(parent.timeRange, key)), epoch(d['timeRange'][key])
        if old is not None and (new is None or (new < old if lower else new > old)):
            raise ScopeDenied()
    d['scopeKey'] = scope_hash(d)
    return AuthorizedScope.model_validate(d)


def resume_scope(saved: dict, current: AuthorizedScope) -> AuthorizedScope:
    try:
        old = AuthorizedScope.model_validate(saved['scope'])
        old.bind(current.actorUserId, current.sessionId, current.runId)
        if old.scopeKey != current.scopeKey or old.sessionGeneration != current.sessionGeneration:
            raise ScopeDenied()
        return current
    except (KeyError, ValueError, TypeError):
        raise ScopeDenied() from None


class ScopeClient:
    def __init__(self, client=None):
        self.client = client

    async def validate(self, scope: AuthorizedScope | None,
                       tool: dict[str, Any] | None = None) -> AuthorizedScope:
        """Confirm a live grant. ``tool`` re-checks one committed call's resources."""
        if scope is None:
            raise ScopeDenied()
        s = get_settings()
        body = dict(actorUserId=scope.actorUserId, sessionId=scope.sessionId,
                    runId=scope.runId, scope=scope.model_dump())
        if tool is not None:
            name = tool.get('name')
            arguments = tool.get('arguments')
            if not isinstance(name, str) or not name or not isinstance(arguments, dict):
                raise ScopeDenied()
            body['tool'] = {'name': name, 'arguments': arguments}
        headers = {'Authorization': f'Bearer {s.java_internal_token}',
                   'X-Actor-User-Id': str(scope.actorUserId)}
        try:
            url = s.java_internal_base_url.rstrip('/') + '/scope/validate'
            if self.client is not None:
                response = await self.client.post(url, json=body, headers=headers)
            else:
                async with httpx.AsyncClient(timeout=5, follow_redirects=False, trust_env=False) as client:
                    response = await client.post(url, json=body, headers=headers)
            response.raise_for_status()
            data = response.json()
            if (not isinstance(data, dict) or set(data) != {'valid', 'scope'}
                    or data.get('valid') is not True):
                raise ScopeDenied()
            current = AuthorizedScope.model_validate(data['scope'])
            resume_scope({'scope': scope.model_dump()}, current)
            if current.grantId != scope.grantId or current.authorizationVersion != scope.authorizationVersion:
                raise ScopeDenied()
            return current
        except Exception:
            # Never put response bodies, headers or secrets in logs/errors.
            raise ScopeDenied() from None


#: Arguments that name a protected resource. A call carrying none of these (a
#: discovery call such as find_direct_chat_with_user) is not itself a resource read.
_CHAT_KEYS = ('chat_id', 'chatId')
_USER_KEYS = ('user_id', 'userId', 'contact_user_id')
_MESSAGE_KEYS = ('message_id', 'messageId')


def guard_args(scope: AuthorizedScope, args: dict[str, Any]):
    forbidden = {'scope', 'scopeKey', 'actorUserId', 'actor_user_id', 'grantId',
                 'authorizationVersion', 'sessionGeneration', 'sessionId', 'runId'}
    if forbidden.intersection(args):
        raise ScopeDenied()
    for key in _CHAT_KEYS:
        if key in args and (type(args[key]) is not int or args[key] not in scope.chatIds):
            raise ScopeDenied()
    for key in _USER_KEYS:
        if key in args and (type(args[key]) is not int or args[key] not in scope.contactUserIds):
            raise ScopeDenied()
    # A resource read needs the matching capability; Java re-checks membership too.
    touches_chat = any(key in args for key in _CHAT_KEYS + _MESSAGE_KEYS)
    touches_user = any(key in args for key in _USER_KEYS)
    if touches_chat and not scope.chatIds:
        raise ScopeDenied()
    if touches_user and not scope.contactUserIds:
        raise ScopeDenied()
    for key in ('fromInclusive', 'from_inclusive', 'start_time'):
        if key in args:
            old, new = epoch(scope.timeRange.fromInclusive), epoch(args[key])
            if old is not None and (new is None or new < old):
                raise ScopeDenied()
    for key in ('toExclusive', 'to_exclusive', 'end_time'):
        if key in args:
            old, new = epoch(scope.timeRange.toExclusive), epoch(args[key])
            if old is not None and (new is None or new > old):
                raise ScopeDenied()
    # message_id is not locally resolved: Java must authorize its non-body
    # chatId/timestamp projection before any message body repository call.


class ScopedModel:
    """Guard every actual provider call, including collaboration subcalls."""
    def __init__(self, client, scope):
        self.client, self.scope = client, scope

    async def complete(self, *args, **kwargs):
        import contextlib
        await ScopeClient().validate(self.scope)
        stream = self.client.complete(*args, **kwargs)
        async with contextlib.aclosing(stream):
            async for chunk in stream:
                # Public streaming output is gated by the orchestrator. Do not
                # duplicate that network check for every token here.
                yield chunk
        # Complete provider result must be authorized before downstream reuse.
        await ScopeClient().validate(self.scope)

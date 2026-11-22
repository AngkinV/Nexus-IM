"""Short-term memory backed by Redis.

Long-term memory writes are intentionally delegated back to Java so that all
durable user data lives behind the Java security perimeter.

See `agent开发文档/Agent 记忆设计（短期:长期:压缩:治理）.md` §4.
"""
from __future__ import annotations

import json
import logging
import time
from typing import Any

import redis.asyncio as redis

from .config import get_settings

log = logging.getLogger(__name__)

_OP_TIMEOUT_SEC = 2
# After a failure, skip Redis for this long instead of paying the timeout on every turn.
_RETRY_AFTER_SEC = 30

_APPEND_ONCE = """
if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
redis.call('RPUSH', KEYS[1], ARGV[1])
redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1)
redis.call('EXPIRE', KEYS[1], ARGV[3])
redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
return 1
"""


class ShortTermMemory:
    def __init__(self, client: redis.Redis | None = None) -> None:
        s = get_settings()
        self.ttl = s.memory_short_ttl_sec
        self.max_turns = s.memory_short_max_turns
        self._client = client if client is not None else redis.Redis.from_url(
            s.redis_url,
            decode_responses=True,
            socket_timeout=_OP_TIMEOUT_SEC,
            socket_connect_timeout=_OP_TIMEOUT_SEC,
        )
        self._skip_until = 0.0

    @staticmethod
    def _messages_key(user_id: int, session_id: str) -> str:
        return f"agent:ctx:{user_id}:{session_id}:messages"

    @staticmethod
    def _summary_key(user_id: int, session_id: str) -> str:
        return f"agent:ctx:{user_id}:{session_id}"

    def _available(self) -> bool:
        return time.monotonic() >= self._skip_until

    def _failed(self, action: str, exc: Exception) -> None:
        self._skip_until = time.monotonic() + _RETRY_AFTER_SEC
        log.warning("%s short-term memory failed, skipping redis for %ss: %s", action, _RETRY_AFTER_SEC, exc)

    async def append(self, user_id: int, session_id: str, role: str, content: str,
                     *, run_id: str | None = None) -> bool | None:
        if not self._available():
            return None
        key = self._messages_key(user_id, session_id)
        payload = json.dumps({"role": role, "content": content}, ensure_ascii=False)
        try:
            if run_id is not None:
                marker = f"{key}:run:{run_id}:{role}"
                return bool(await self._client.eval(_APPEND_ONCE, 2, key, marker,
                                                   payload, self.max_turns * 2, self.ttl))
            async with self._client.pipeline(transaction=True) as pipe:
                pipe.rpush(key, payload)
                pipe.ltrim(key, -self.max_turns * 2, -1)
                pipe.expire(key, self.ttl)
                await pipe.execute()
            return True
        except Exception as exc:
            self._failed("append", exc)
            return None

    async def recent(self, user_id: int, session_id: str) -> list[dict[str, Any]]:
        if not self._available():
            return []
        try:
            raw = await self._client.lrange(self._messages_key(user_id, session_id), 0, -1) or []
        except Exception as exc:
            self._failed("read", exc)
            return []
        out = []
        for item in raw:
            try:
                out.append(json.loads(item))
            except Exception:
                continue
        return out

    async def clear(self, user_id: int, session_id: str) -> None:
        if not self._available():
            return
        try:
            await self._client.delete(
                self._messages_key(user_id, session_id),
                self._summary_key(user_id, session_id),
            )
        except Exception as exc:
            self._failed("clear", exc)

    async def aclose(self) -> None:
        try:
            await self._client.aclose()
        except Exception as exc:
            log.warning("closing short-term memory client failed: %s", exc)


_singleton: ShortTermMemory | None = None


def get_memory() -> ShortTermMemory:
    global _singleton
    if _singleton is None:
        _singleton = ShortTermMemory()
    return _singleton


async def close_memory() -> None:
    global _singleton
    if _singleton is not None:
        await _singleton.aclose()
        _singleton = None

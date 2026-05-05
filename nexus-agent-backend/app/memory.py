"""Short-term memory backed by Redis.

Long-term memory writes are intentionally delegated back to Java so that all
durable user data lives behind the Java security perimeter.

See `agent开发文档/Agent 记忆设计（短期:长期:压缩:治理）.md` §4.
"""
from __future__ import annotations

import json
import logging
from typing import Any

import redis

from .config import get_settings

log = logging.getLogger(__name__)


class ShortTermMemory:
    def __init__(self, client: redis.Redis | None = None) -> None:
        s = get_settings()
        self.ttl = s.memory_short_ttl_sec
        self.max_turns = s.memory_short_max_turns
        if client is not None:
            self._client = client
        else:
            try:
                self._client = redis.Redis.from_url(s.redis_url, decode_responses=True)
                self._client.ping()
            except Exception as exc:
                log.warning("redis unavailable, short-term memory disabled: %s", exc)
                self._client = None

    @staticmethod
    def _messages_key(user_id: int, session_id: str) -> str:
        return f"agent:ctx:{user_id}:{session_id}:messages"

    @staticmethod
    def _summary_key(user_id: int, session_id: str) -> str:
        return f"agent:ctx:{user_id}:{session_id}"

    def append(self, user_id: int, session_id: str, role: str, content: str) -> None:
        if not self._client:
            return
        key = self._messages_key(user_id, session_id)
        try:
            self._client.rpush(key, json.dumps({"role": role, "content": content}, ensure_ascii=False))
            self._client.ltrim(key, -self.max_turns * 2, -1)  # keep last N user+assistant pairs
            self._client.expire(key, self.ttl)
        except Exception as exc:
            log.warning("append short-term memory failed: %s", exc)

    def recent(self, user_id: int, session_id: str) -> list[dict[str, Any]]:
        if not self._client:
            return []
        try:
            raw = self._client.lrange(self._messages_key(user_id, session_id), 0, -1) or []
        except Exception as exc:
            log.warning("read short-term memory failed: %s", exc)
            return []
        out = []
        for item in raw:
            try:
                out.append(json.loads(item))
            except Exception:
                continue
        return out

    def clear(self, user_id: int, session_id: str) -> None:
        if not self._client:
            return
        try:
            self._client.delete(self._messages_key(user_id, session_id))
            self._client.delete(self._summary_key(user_id, session_id))
        except Exception as exc:
            log.warning("clear short-term memory failed: %s", exc)


_singleton: ShortTermMemory | None = None


def get_memory() -> ShortTermMemory:
    global _singleton
    if _singleton is None:
        _singleton = ShortTermMemory()
    return _singleton

"""Tool registry + executor.

Tools are thin wrappers over the Java Internal Agent API. All calls carry the
`X-Actor-User-Id` and `Authorization: Bearer <JAVA_INTERNAL_TOKEN>` headers so
the Java side can re-authorize each access.

See `agent开发文档/Java 网关与 Python Agent 接口契约.md` §7
and `agent开发文档/Agent 设计说明（推理、工具、记忆、安全）.md` §7.
"""
from __future__ import annotations

import logging
import time
from typing import Any, Callable, Awaitable

import httpx

from .config import get_settings

log = logging.getLogger(__name__)


# JSON Schemas advertised to the model. Keep argument names aligned with
# downstream Java path/query params.
TOOL_SCHEMAS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "get_recent_messages",
            "description": "Fetch the most recent messages of the given chat. Use this to ground summaries / todos / replies in real chat content.",
            "parameters": {
                "type": "object",
                "properties": {
                    "chat_id": {"type": "integer", "description": "Chat ID to read"},
                    "limit": {"type": "integer", "description": "How many messages, max 200", "default": 80},
                },
                "required": ["chat_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_chat_profile",
            "description": "Fetch chat metadata: name, type (direct/group), member ids.",
            "parameters": {
                "type": "object",
                "properties": {"chat_id": {"type": "integer"}},
                "required": ["chat_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_user_profile",
            "description": "Fetch a user's display profile. Useful for mentioning correct nicknames.",
            "parameters": {
                "type": "object",
                "properties": {"user_id": {"type": "integer"}},
                "required": ["user_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_message_by_id",
            "description": "Fetch a single message by id (used for reply suggestion to inspect target message).",
            "parameters": {
                "type": "object",
                "properties": {"message_id": {"type": "integer"}},
                "required": ["message_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "find_user_by_username",
            "description": (
                "Resolve a user's profile from their @username handle. "
                "Use this WHENEVER the user mentions someone by an @handle (e.g. '@test00001', "
                "'帮我看一下 @bob 的资料'). The returned payload includes the numeric userId, "
                "which can then be passed to other tools like get_user_profile or used in answers."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "username": {
                        "type": "string",
                        "description": "The username handle, with or without a leading '@'. e.g. 'test00001' or '@test00001'.",
                    }
                },
                "required": ["username"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_my_chats",
            "description": (
                "List the actor's own chats (1-on-1 and group), most-recent first. "
                "Use this when the user refers to a chat by name — e.g. '总结一下「项目A推进群」' "
                "or '我和谁在聊' — and you don't yet know the numeric chatId. The optional "
                "`query` does case-insensitive substring matching on the chat name (for direct "
                "chats the name is the other person's @handle)."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "Optional fuzzy match on chat name. Empty = list all.",
                    },
                    "type": {
                        "type": "string",
                        "enum": ["direct", "group"],
                        "description": "Optional filter; omit to include both.",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Max rows to return, 1..50. Default 20.",
                    },
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "find_direct_chat_with_user",
            "description": (
                "Find the 1-on-1 (direct) chat between the actor and a named user. "
                "Use this when the user says things like '我和 @test00001 的聊天', '总结我和 bob 的对话'. "
                "Returns the chatId, then call get_recent_messages with that chatId. "
                "If no direct chat exists, this returns an error — tell the user there isn't one."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "username": {
                        "type": "string",
                        "description": "Other party's @handle, with or without leading '@'.",
                    }
                },
                "required": ["username"],
            },
        },
    },
]


class ToolError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class ToolExecutor:
    def __init__(self, actor_user_id: int, trace_id: str) -> None:
        s = get_settings()
        self._base_url = s.java_internal_base_url.rstrip("/")
        self._token = s.java_internal_token
        self._actor_user_id = actor_user_id
        self._trace_id = trace_id
        self._timeout = s.tool_timeout_sec
        self._client: httpx.AsyncClient | None = None

    async def __aenter__(self) -> "ToolExecutor":
        self._client = httpx.AsyncClient(timeout=self._timeout)
        return self

    async def __aexit__(self, *exc_info) -> None:
        if self._client:
            await self._client.aclose()

    def _headers(self, tool_name: str) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self._token}",
            "X-Actor-User-Id": str(self._actor_user_id),
            "X-Trace-Id": self._trace_id,
            "X-Agent-Tool": tool_name,
        }

    async def execute(self, tool_name: str, args: dict[str, Any]) -> tuple[dict[str, Any], int]:
        """Returns (result_payload, latency_ms). Raises ToolError on failure."""
        started = time.perf_counter()
        handler = self._dispatch.get(tool_name)
        if handler is None:
            raise ToolError("UNKNOWN_TOOL", f"unregistered tool: {tool_name}")
        try:
            payload = await handler(self, args)
            latency_ms = int((time.perf_counter() - started) * 1000)
            return payload, latency_ms
        except httpx.TimeoutException as exc:
            raise ToolError("TIMEOUT", f"{tool_name} timeout") from exc
        except httpx.HTTPStatusError as exc:
            raise ToolError("HTTP_ERROR", f"{tool_name} {exc.response.status_code}") from exc
        except ToolError:
            raise
        except Exception as exc:
            raise ToolError("INTERNAL", f"{tool_name} failed: {exc}") from exc

    # ---- handlers ----
    async def _get_recent_messages(self, args: dict[str, Any]) -> dict[str, Any]:
        chat_id = int(args["chat_id"])
        limit = int(args.get("limit", 80))
        url = f"{self._base_url}/chats/{chat_id}/recent-messages"
        r = await self._client.get(url, params={"limit": limit}, headers=self._headers("get_recent_messages"))
        r.raise_for_status()
        return r.json()

    async def _get_chat_profile(self, args: dict[str, Any]) -> dict[str, Any]:
        chat_id = int(args["chat_id"])
        url = f"{self._base_url}/chats/{chat_id}/profile"
        r = await self._client.get(url, headers=self._headers("get_chat_profile"))
        r.raise_for_status()
        return r.json()

    async def _get_user_profile(self, args: dict[str, Any]) -> dict[str, Any]:
        user_id = int(args["user_id"])
        url = f"{self._base_url}/users/{user_id}/profile"
        r = await self._client.get(url, headers=self._headers("get_user_profile"))
        r.raise_for_status()
        return r.json()

    async def _get_message_by_id(self, args: dict[str, Any]) -> dict[str, Any]:
        message_id = int(args["message_id"])
        url = f"{self._base_url}/messages/{message_id}"
        r = await self._client.get(url, headers=self._headers("get_message_by_id"))
        r.raise_for_status()
        return r.json()

    async def _find_user_by_username(self, args: dict[str, Any]) -> dict[str, Any]:
        raw = str(args.get("username", "")).strip()
        if raw.startswith("@"):
            raw = raw[1:]
        if not raw:
            raise ToolError("BAD_ARG", "username is empty")
        # The handle goes in a path segment, so any unusual characters are URL-quoted.
        from urllib.parse import quote
        url = f"{self._base_url}/users/by-username/{quote(raw, safe='')}/profile"
        r = await self._client.get(url, headers=self._headers("find_user_by_username"))
        r.raise_for_status()
        return r.json()

    async def _list_my_chats(self, args: dict[str, Any]) -> dict[str, Any]:
        params: dict[str, Any] = {}
        q = (args.get("query") or "").strip()
        t = (args.get("type") or "").strip().lower()
        limit = int(args.get("limit") or 20)
        if q:
            params["query"] = q
        if t in {"direct", "group"}:
            params["type"] = t
        params["limit"] = max(1, min(limit, 50))
        url = f"{self._base_url}/me/chats"
        r = await self._client.get(url, params=params, headers=self._headers("list_my_chats"))
        r.raise_for_status()
        return r.json()

    async def _find_direct_chat_with_user(self, args: dict[str, Any]) -> dict[str, Any]:
        raw = str(args.get("username", "")).strip()
        if raw.startswith("@"):
            raw = raw[1:]
        if not raw:
            raise ToolError("BAD_ARG", "username is empty")
        from urllib.parse import quote
        url = f"{self._base_url}/me/chats/with-user/{quote(raw, safe='')}"
        r = await self._client.get(url, headers=self._headers("find_direct_chat_with_user"))
        r.raise_for_status()
        return r.json()

    _dispatch: dict[str, Callable[["ToolExecutor", dict[str, Any]], Awaitable[dict[str, Any]]]] = {
        "get_recent_messages": _get_recent_messages,
        "get_chat_profile": _get_chat_profile,
        "get_user_profile": _get_user_profile,
        "get_message_by_id": _get_message_by_id,
        "find_user_by_username": _find_user_by_username,
        "list_my_chats": _list_my_chats,
        "find_direct_chat_with_user": _find_direct_chat_with_user,
    }

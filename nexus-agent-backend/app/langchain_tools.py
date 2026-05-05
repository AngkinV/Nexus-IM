"""LangChain Tool wrappers for the existing 7 internal-API tools.

The handcrafted orchestrator (orchestrator.py) keeps using the raw
``TOOL_SCHEMAS`` from ``tools.py`` — it predates this module and changing
the production path adds risk for no behavior win.

The LangGraph engine (orchestrator_langgraph.py), as of the Day 17
follow-up, derives its tool schemas from the ``@tool``-decorated
wrappers in this module via ``convert_langchain_tools_to_openai(...)``.
That way the LangChain ``BaseTool`` layer is the *live* source of truth
on the LangGraph path — not just a parallel demonstration — and the
résumé claim "wrap 7 tools with @tool + bind_tools to demonstrate
industry-standard usage" matches the code that actually runs.

``ToolExecutor`` still does the wire-level dispatch (HMAC + 3 s tool
timeout + ``X-Actor-User-Id`` reauthorization), so both engines share
the same Java contract.

Per-request context (actor_user_id, trace_id) is closure-captured by
``make_langchain_tools(...)`` because ``@tool`` callables don't easily
accept hidden kwargs. Each request builds its own tool list — cheap
since LangChain ``BaseTool`` construction is lightweight.
"""
from __future__ import annotations

import logging
from typing import Any, Optional

from langchain_core.tools import BaseTool, tool
from langchain_core.utils.function_calling import convert_to_openai_tool

from .llm.base import ProviderConfig
from .tools import ToolError, ToolExecutor

log = logging.getLogger(__name__)


def make_langchain_tools(actor_user_id: int, trace_id: str) -> list[BaseTool]:
    """Return the 7 internal-API tools as LangChain ``BaseTool``s.

    Each tool delegates to ``ToolExecutor`` so the wire-level Java API
    contract (HMAC + actor binding + 3 s tool timeout) is shared with
    the handcrafted engine.
    """

    async def _run(name: str, args: dict) -> dict:
        async with ToolExecutor(actor_user_id, trace_id) as ex:
            try:
                payload, _latency = await ex.execute(name, args)
                return payload
            except ToolError as exc:
                # LangChain's reduce/loop relies on stringifiable returns;
                # surface tool errors as a structured payload the model can
                # reason about, same shape orchestrator.py emits.
                return {"toolError": exc.code, "message": str(exc)}

    @tool("get_recent_messages")
    async def get_recent_messages(chat_id: int, limit: int = 80) -> dict:
        """Fetch the most recent messages of the given chat. Use to ground
        summaries / todos / replies in real chat content."""
        return await _run("get_recent_messages", {"chat_id": chat_id, "limit": limit})

    @tool("get_chat_profile")
    async def get_chat_profile(chat_id: int) -> dict:
        """Fetch chat metadata: name, type (direct/group), member ids."""
        return await _run("get_chat_profile", {"chat_id": chat_id})

    @tool("get_user_profile")
    async def get_user_profile(user_id: int) -> dict:
        """Fetch a user's display profile. Useful for mentioning correct
        nicknames."""
        return await _run("get_user_profile", {"user_id": user_id})

    @tool("get_message_by_id")
    async def get_message_by_id(message_id: int) -> dict:
        """Fetch a single message by id (for reply suggestion to inspect
        the target message)."""
        return await _run("get_message_by_id", {"message_id": message_id})

    @tool("find_user_by_username")
    async def find_user_by_username(username: str) -> dict:
        """Resolve a user's profile from their @username handle. Use this
        WHENEVER the user mentions someone by an @handle. Returns the
        numeric userId."""
        return await _run("find_user_by_username", {"username": username})

    @tool("list_my_chats")
    async def list_my_chats(
        query: Optional[str] = None,
        type: Optional[str] = None,
        limit: int = 20,
    ) -> dict:
        """List the actor's chats, most-recent first. ``query`` is a
        case-insensitive substring match on the chat name; ``type`` is
        ``direct`` or ``group``."""
        args: dict = {"limit": limit}
        if query:
            args["query"] = query
        if type:
            args["type"] = type
        return await _run("list_my_chats", args)

    @tool("find_direct_chat_with_user")
    async def find_direct_chat_with_user(username: str) -> dict:
        """Find the 1-on-1 chat between the actor and a named user. Returns
        chatId, then call ``get_recent_messages`` with that chatId."""
        return await _run("find_direct_chat_with_user", {"username": username})

    return [
        get_recent_messages,
        get_chat_profile,
        get_user_profile,
        get_message_by_id,
        find_user_by_username,
        list_my_chats,
        find_direct_chat_with_user,
    ]


def convert_langchain_tools_to_openai(
    actor_user_id: int, trace_id: str
) -> list[dict[str, Any]]:
    """Build per-request ``@tool`` wrappers and convert them into the
    OpenAI function-calling JSON shape that ``LLMClient.complete`` expects.

    This is the bridge that lets the LangGraph engine treat
    ``langchain_tools.py`` as the live source of truth for tool schemas
    while still feeding our multi-provider ``LLMClient`` (which speaks
    Anthropic / Gemini SDKs as well as OpenAI-compatible). The output
    list is name-equivalent to ``tools.TOOL_SCHEMAS`` — both engines
    produce calls under the same names so ``ToolExecutor`` dispatch is
    engine-agnostic.
    """
    return [convert_to_openai_tool(t) for t in make_langchain_tools(actor_user_id, trace_id)]


# ----------------------------------------------------------------------
# ChatModel helper
# ----------------------------------------------------------------------
#
# Demonstrates the canonical LangGraph pattern:
#     model = build_chat_openai(cfg)
#     model_with_tools = model.bind_tools(make_langchain_tools(...))
#
# We intentionally keep this lightweight — the production orchestrator uses
# our own LLMClient abstraction (which supports BYOK across OpenAI /
# Anthropic / Gemini). This helper only handles the OpenAI-compatible
# subset; non-OpenAI providers still go through LLMClient.


def build_chat_openai(cfg: ProviderConfig, *, temperature: float = 0.2,
                      max_tokens: int = 1024, timeout_sec: int = 30):
    """Build a ``ChatOpenAI`` from our request-scoped ``ProviderConfig``.

    Raises ``ValueError`` when called for a non-OpenAI-compatible provider —
    callers should fall back to the multi-provider ``LLMClient`` in that
    case.
    """
    if not cfg.api_key:
        raise ValueError("ProviderConfig.api_key is required to build a ChatOpenAI")
    # Lazy import so module import stays cheap when the LangChain ChatModel
    # path isn't exercised.
    from langchain_openai import ChatOpenAI
    return ChatOpenAI(
        model=cfg.model or "gpt-4.1-mini",
        api_key=cfg.api_key,
        base_url=cfg.base_url,
        temperature=temperature,
        max_tokens=max_tokens,
        timeout=timeout_sec,
    )

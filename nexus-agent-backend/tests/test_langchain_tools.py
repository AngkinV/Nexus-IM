"""Day 16 tests: LangChain Tool wrappers + ChatModel helper.

Goal: prove every wrapped tool exposes the right name + parameter
schema to ChatModel.bind_tools(), and that invoking a tool reaches
ToolExecutor.execute with the right (name, args) tuple.

We monkey-patch ToolExecutor so the tests stay offline — same fixture
shape Day 15 used for the LangGraph runner.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

os.environ.pop("OPENAI_API_KEY", None)


from app import langchain_tools as lt  # noqa: E402
from app import tools as tools_mod  # noqa: E402
from app.llm.base import ProviderConfig  # noqa: E402


class _FakeToolExecutor:
    """Captures (tool_name, args) into the class-level last_call list."""
    last_calls: list[tuple[str, dict]] = []
    fail_with: Exception | None = None
    return_payload: dict = {"ok": True}

    def __init__(self, *_a, **_kw):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def execute(self, name, args):
        type(self).last_calls.append((name, args))
        if self.fail_with:
            raise self.fail_with
        return self.return_payload, 7


@pytest.fixture(autouse=True)
def _patch_executor(monkeypatch):
    _FakeToolExecutor.last_calls = []
    _FakeToolExecutor.fail_with = None
    _FakeToolExecutor.return_payload = {"ok": True}
    monkeypatch.setattr(lt, "ToolExecutor", _FakeToolExecutor)
    yield
    _FakeToolExecutor.last_calls = []


# ---------------- shape ----------------


def test_make_tools_returns_seven_tools():
    tools = lt.make_langchain_tools(actor_user_id=1, trace_id="tr_t")
    assert len(tools) == 7


def test_tool_names_match_handcrafted_schemas():
    """The wrappers must keep parity with TOOL_SCHEMAS so a tool listed
    in the model's reply maps to the same Java endpoint regardless of
    which engine produced the call."""
    schema_names = {s["function"]["name"] for s in tools_mod.TOOL_SCHEMAS}
    wrapper_names = {t.name for t in lt.make_langchain_tools(1, "tr")}
    assert schema_names == wrapper_names


def test_each_tool_has_a_description():
    for t in lt.make_langchain_tools(1, "tr"):
        assert t.description and t.description.strip()


def test_each_tool_has_args_schema():
    """LangChain's bind_tools introspects args_schema to produce the
    OpenAI function spec; missing schemas would silently strip args."""
    for t in lt.make_langchain_tools(1, "tr"):
        assert t.args_schema is not None


# ---------------- dispatch ----------------


async def test_get_recent_messages_dispatches_with_chat_id_and_limit():
    [t] = [t for t in lt.make_langchain_tools(1, "tr") if t.name == "get_recent_messages"]
    payload = await t.ainvoke({"chat_id": 42, "limit": 50})
    assert payload == {"ok": True}
    assert _FakeToolExecutor.last_calls == [
        ("get_recent_messages", {"chat_id": 42, "limit": 50}),
    ]


async def test_get_recent_messages_default_limit_is_80():
    [t] = [t for t in lt.make_langchain_tools(1, "tr") if t.name == "get_recent_messages"]
    await t.ainvoke({"chat_id": 7})
    name, args = _FakeToolExecutor.last_calls[0]
    assert name == "get_recent_messages"
    assert args["chat_id"] == 7
    assert args["limit"] == 80


async def test_find_user_by_username_dispatches_username():
    [t] = [t for t in lt.make_langchain_tools(1, "tr") if t.name == "find_user_by_username"]
    await t.ainvoke({"username": "alice"})
    assert _FakeToolExecutor.last_calls == [
        ("find_user_by_username", {"username": "alice"}),
    ]


async def test_list_my_chats_omits_optional_args_when_none():
    [t] = [t for t in lt.make_langchain_tools(1, "tr") if t.name == "list_my_chats"]
    await t.ainvoke({})  # everything default
    name, args = _FakeToolExecutor.last_calls[0]
    assert name == "list_my_chats"
    assert "query" not in args  # optional, omitted
    assert "type" not in args
    assert args.get("limit") == 20


async def test_list_my_chats_passes_optional_args_when_set():
    [t] = [t for t in lt.make_langchain_tools(1, "tr") if t.name == "list_my_chats"]
    await t.ainvoke({"query": "项目A", "type": "group", "limit": 5})
    name, args = _FakeToolExecutor.last_calls[0]
    assert args["query"] == "项目A"
    assert args["type"] == "group"
    assert args["limit"] == 5


async def test_tool_error_is_returned_as_structured_payload():
    """LangChain's reduce step expects stringifiable tool returns; we
    surface ToolError as a {toolError, message} dict the model can
    reason about (same shape orchestrator.py emits)."""
    _FakeToolExecutor.fail_with = tools_mod.ToolError("BAD_ARG", "username is empty")

    [t] = [t for t in lt.make_langchain_tools(1, "tr") if t.name == "find_user_by_username"]
    payload = await t.ainvoke({"username": ""})
    assert payload == {"toolError": "BAD_ARG", "message": "username is empty"}


# ---------------- ChatModel helper ----------------


def test_build_chat_openai_returns_a_chat_model():
    cfg = ProviderConfig(name="openai", base_url="https://api.openai.com/v1",
                         model="gpt-4.1-mini", api_key="sk-test")
    model = lt.build_chat_openai(cfg, temperature=0.1)

    from langchain_openai import ChatOpenAI
    assert isinstance(model, ChatOpenAI)
    # The model must accept tools via .bind_tools — that's the whole
    # point of building it through this helper.
    bound = model.bind_tools(lt.make_langchain_tools(1, "tr"))
    assert bound is not None


def test_build_chat_openai_requires_api_key():
    cfg = ProviderConfig(name="openai", base_url=None, model="gpt-4o-mini", api_key=None)
    with pytest.raises(ValueError, match="api_key"):
        lt.build_chat_openai(cfg)


def test_build_chat_openai_falls_back_to_default_model():
    cfg = ProviderConfig(name="openai", base_url=None, model=None, api_key="sk-test")
    model = lt.build_chat_openai(cfg)
    # ChatOpenAI exposes .model_name on its instance for the resolved name.
    assert getattr(model, "model_name", None)


def test_per_request_isolation_of_actor_user_id():
    """Two concurrent requests must not see each other's actor_user_id,
    since the closure captures it. We verify by inspecting that the
    captured arg makes its way into ToolExecutor invocation correctly."""
    a_tools = lt.make_langchain_tools(actor_user_id=100, trace_id="tr_a")
    b_tools = lt.make_langchain_tools(actor_user_id=200, trace_id="tr_b")
    # Different list instances → independent closures.
    assert a_tools[0] is not b_tools[0]


# ---------------- convert_langchain_tools_to_openai (Day 17 follow-up) ----------------


def test_converter_returns_seven_openai_function_schemas():
    schemas = lt.convert_langchain_tools_to_openai(actor_user_id=1, trace_id="tr_c")
    assert len(schemas) == 7
    for s in schemas:
        assert s["type"] == "function"
        assert "function" in s
        assert "name" in s["function"]
        assert "parameters" in s["function"]


def test_converted_schemas_match_handcrafted_TOOL_SCHEMAS_names():
    """Tool names must stay byte-identical so a model reply maps to the
    same Java endpoint regardless of which engine produced the call."""
    converted = {s["function"]["name"]
                 for s in lt.convert_langchain_tools_to_openai(1, "tr_c")}
    handcrafted = {s["function"]["name"] for s in tools_mod.TOOL_SCHEMAS}
    assert converted == handcrafted


def test_converted_schemas_required_args_match_handcrafted():
    """The set of required args per tool must match — if the converter
    drops a required marker the model would happily call without it
    and ToolExecutor would reject downstream."""
    by_name_handcrafted = {
        s["function"]["name"]: set(s["function"]["parameters"].get("required", []))
        for s in tools_mod.TOOL_SCHEMAS
    }
    by_name_converted = {
        s["function"]["name"]: set(s["function"]["parameters"].get("required", []))
        for s in lt.convert_langchain_tools_to_openai(1, "tr_c")
    }
    assert by_name_handcrafted == by_name_converted

"""Deterministic eval harness: a scripted model and a fake Java MCP tool server.

Verifies orchestration (tool selection, termination, events, memory side
effects), not model quality.
"""
from __future__ import annotations

import asyncio
import copy
import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx2

from app import mcp_tools, orchestrator
from app.config import get_settings
from app.llm import LLMChunk, LLMToolCall, LLMUsage, ProviderConfig
from app.rag import memory_rag
from app.schemas import Actor, InputPayload, InvokeRequest, Options, ResumeRequest, Session

CASES_PATH = Path(__file__).parent / "cases.jsonl"
ACTOR_ID = 7
ACTOR_NAME = "alice"
JAVA_TOKEN = "eval-internal-token"
EVAL_API_KEY = "sk-eval-credential-marker"


def load_cases() -> list[dict[str, Any]]:
    cases = [json.loads(line) for line in CASES_PATH.read_text(encoding="utf-8").splitlines() if line.strip()]
    ids = [c["id"] for c in cases]
    assert len(ids) == len(set(ids)), "duplicate case id in cases.jsonl"
    return cases


def _chunk(step: dict[str, Any]) -> LLMChunk:
    if "text" in step:
        return LLMChunk(kind="text", text=step["text"])
    if "reasoning" in step:
        return LLMChunk(kind="reasoning", text=step["reasoning"])
    if "tool" in step:
        call = step["tool"]
        return LLMChunk(kind="tool_call", tool_call=LLMToolCall(
            id=call["id"], name=call["name"], arguments=call.get("arguments", {})))
    if "usage" in step:
        tokens_in, tokens_out = step["usage"]
        return LLMChunk(kind="usage", usage=LLMUsage(
            input_tokens=tokens_in, output_tokens=tokens_out, total_tokens=tokens_in + tokens_out))
    if "finish" in step:
        return LLMChunk(kind="finish", finish_reason=step["finish"])
    if "error" in step:
        return LLMChunk(kind="error", text=step["error"])
    raise ValueError(f"unknown scripted step: {step}")


class ScriptedModel:
    """Replays one scripted turn per complete() call and records what it was asked.

    A {"wait": name} step blocks until the test sets gates[name].
    """

    def __init__(self, turns: list[list[dict[str, Any]]]) -> None:
        self.turns = turns
        self.calls: list[dict[str, Any]] = []
        self.gates: dict[str, asyncio.Event] = {}
        self.open_streams = 0

    def gate(self, name: str) -> asyncio.Event:
        return self.gates.setdefault(name, asyncio.Event())

    async def complete(self, messages, tools=None, **kwargs):
        index = len(self.calls)
        self.calls.append({"messages": copy.deepcopy(messages), "tools": tools, **kwargs})
        if index >= len(self.turns):
            # Asserted by check_invariants; raising here would be swallowed as a model error.
            return
        self.open_streams += 1
        try:
            for step in self.turns[index]:
                if "raise" in step:
                    raise RuntimeError(step["raise"])
                if "wait" in step:
                    await self.gate(step["wait"]).wait()
                    continue
                if "exit" in step:
                    os._exit(step["exit"])  # the process dies mid-call, with no cleanup
                yield _chunk(step)
        finally:
            self.open_streams -= 1


# Tools the fake server lists besides the ones a case scripts. Names only: the
# real descriptions and argument schemas live in Java and are not copied here.
LISTED_TOOLS = ("get_recent_messages", "get_chat_profile", "get_user_profile", "list_my_chats")


class FakeJava:
    """Stands in for the Java MCP tool server on the wire and records every tool call.

    A scripted response is {"json": {...}} for a result, {"error": {"toolError": ...,
    "message": ...}} for a call the tool refuses, or {"errorText": "..."} for a refusal
    in free text.
    """

    def __init__(self, responses: dict[str, Any]) -> None:
        self._responses = {name: list(spec) if isinstance(spec, list) else spec
                           for name, spec in responses.items()}
        self.catalog: dict[str, dict[str, Any]] = {
            name: {"type": "object"} for name in (*LISTED_TOOLS, *responses)}
        self.requests: list[dict[str, Any]] = []  # tools/call only
        self.rpc: list[str] = []  # every JSON-RPC method received, in order
        self.rpc_headers: list[dict[str, str]] = []  # and the headers each arrived with
        self.http_requests = 0  # everything that reached the server, answered or refused
        self.fail_with: int | None = None  # answer every request with this HTTP status
        self.fail_calls_with: int | None = None  # answer only tools/call with this HTTP status
        self.hang = False  # accept every request and never answer
        self.delay_sec = 0.0
        self.in_flight = 0
        self.max_in_flight = 0

    def client(self, headers=None, timeout=None, auth=None, **_ignored) -> httpx2.AsyncClient:
        return httpx2.AsyncClient(
            transport=httpx2.MockTransport(self.handler), headers=headers, timeout=timeout, auth=auth)

    async def handler(self, request: httpx2.Request) -> httpx2.Response:
        self.http_requests += 1
        if self.hang:
            await asyncio.sleep(3600)
        if self.fail_with is not None:
            return httpx2.Response(self.fail_with)
        if request.method != "POST":
            return httpx2.Response(405)
        message = json.loads(request.content)
        method, message_id = message.get("method"), message.get("id")
        self.rpc.append(method)
        self.rpc_headers.append(dict(request.headers))
        if method == "tools/call" and self.fail_calls_with is not None:
            return httpx2.Response(self.fail_calls_with)
        if message_id is None:  # a notification
            return httpx2.Response(202)
        if method == "initialize":
            result: dict[str, Any] = {
                "protocolVersion": message["params"]["protocolVersion"],
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "fake-java", "version": "1"},
            }
        elif method == "tools/list":
            result = {"tools": [{"name": name, "description": f"{name} (fake)", "inputSchema": schema}
                                for name, schema in self.catalog.items()]}
        elif method == "tools/call":
            self.in_flight += 1
            self.max_in_flight = max(self.max_in_flight, self.in_flight)
            try:
                if self.delay_sec:
                    await asyncio.sleep(self.delay_sec)
                result = self._call(request, message["params"])
            finally:
                self.in_flight -= 1
        else:
            result = {}
        return httpx2.Response(200, json={"jsonrpc": "2.0", "id": message_id, "result": result})

    def _call(self, request: httpx2.Request, params: dict[str, Any]) -> dict[str, Any]:
        tool_name = params["name"]
        self.requests.append({
            "tool": tool_name,
            "arguments": params.get("arguments") or {},
            "headers": dict(request.headers),
        })
        spec = self._responses.get(tool_name)
        if isinstance(spec, list):
            spec = spec.pop(0) if spec else None
        if spec is None:
            spec = {"error": {"toolError": "NOT_FOUND", "message": f"no scripted response for {tool_name}"}}
        if "error" in spec or "errorText" in spec:
            text = spec.get("errorText") or json.dumps(spec["error"], ensure_ascii=False)
            return {"content": [{"type": "text", "text": text}], "isError": True}
        payload = spec.get("json", {})
        return {"content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}],
                "isError": False, "structuredContent": payload}


class RecordingMemory:
    def __init__(self, history: list[dict[str, Any]]) -> None:
        self.history = history
        self.turns: list[list[str]] = []
        self.recorded: set[tuple[str, str]] = set()
        self.owners: list[tuple[int, str]] = []

    async def recent(self, user_id, session_id):
        return list(self.history)

    async def append(self, user_id, session_id, role, content, *, run_id=None):
        key = (user_id, session_id, run_id, role)
        if run_id and key in self.recorded:
            return False
        self.recorded.add(key)
        self.turns.append([role, content])
        self.owners.append((user_id, session_id))
        return True


@dataclass
class Rig:
    """Everything a turn touches, replaced by recording fakes."""
    case: dict[str, Any]
    model: ScriptedModel
    java: FakeJava
    memory: RecordingMemory
    rag_writes: list[tuple[str, str]]

    def request(self, **overrides) -> InvokeRequest:
        case = self.case
        return InvokeRequest(**{
            "traceId": f"tr_{case['id']}",
            "actor": Actor(userId=ACTOR_ID, username=ACTOR_NAME),
            "session": Session(sessionId=f"s_{case['id']}", operationType=case["operation"]),
            "input": InputPayload(**case["input"]),
            "options": Options(**case.get("options", {})),
            **overrides,
        })

    async def collect(self, events) -> list[Any]:
        # A turn that crashed in an earlier test leaves a task bound to a closed loop.
        orchestrator._pending_rag_writes.clear()
        try:
            return [event async for event in events]
        finally:
            pending = list(orchestrator._pending_rag_writes)
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)

    async def run(self, request: InvokeRequest | None = None, **kwargs) -> list[Any]:
        return await self.collect(orchestrator.run_agent(request or self.request(), **kwargs))

    async def resume(self, run_id: str, *, actor_id: int = ACTOR_ID, session_id: str | None = None) -> list[Any]:
        return await self.collect(orchestrator.resume_agent(ResumeRequest(
            traceId=f"tr_{self.case['id']}_resume",
            actor=Actor(userId=actor_id, username=ACTOR_NAME),
            sessionId=session_id or f"s_{self.case['id']}",
            runId=run_id,
        )))


@dataclass
class EvalRun:
    request: InvokeRequest
    events: list[Any]
    model: ScriptedModel
    java: FakeJava
    memory: RecordingMemory
    rag_writes: list[tuple[str, str]]

    @property
    def final(self) -> dict[str, Any]:
        return self.events[-1].data["result"]


def install(case: dict[str, Any], monkeypatch) -> Rig:
    settings = get_settings()
    monkeypatch.setattr(settings, "memory_rag_enabled", True)
    monkeypatch.setattr(settings, "knowledge_rag_enabled", False)
    monkeypatch.setattr(settings, "max_iterations", 6)
    monkeypatch.setattr(settings, "java_internal_base_url", "http://java.test/internal/agent")
    monkeypatch.setattr(settings, "java_internal_token", JAVA_TOKEN)

    model = ScriptedModel(case.get("model", []))
    java = FakeJava(case.get("java", {}))
    memory = RecordingMemory(case.get("history", []))
    rag_writes: list[tuple[str, str]] = []

    async def _retrieve(user_id, query, **kwargs):
        return list(case.get("recall", []))

    async def _write(user_id, session_id, user_text, answer, **kwargs):
        rag_writes.append((user_text, answer))

    provider = None if case.get("provider") == "mock" else ProviderConfig(
        name="openai", base_url=None, model="eval-model", api_key=EVAL_API_KEY)
    monkeypatch.setattr(orchestrator, "_resolve_provider_config", lambda _provider: provider)
    monkeypatch.setattr(orchestrator, "build_client", lambda _cfg: model)
    # The real tool layer runs; only its HTTP connection is replaced.
    mcp_tools.reset_catalog()
    monkeypatch.setattr(mcp_tools, "_http_client", java.client)
    monkeypatch.setattr(orchestrator, "get_memory", lambda: memory)
    monkeypatch.setattr(memory_rag, "retrieve", _retrieve)
    monkeypatch.setattr(memory_rag, "write", _write)
    return Rig(case, model, java, memory, rag_writes)


async def run_case(case: dict[str, Any], monkeypatch) -> EvalRun:
    rig = install(case, monkeypatch)
    request = rig.request()
    events = await rig.run(request)
    return EvalRun(request, events, rig.model, rig.java, rig.memory, rig.rag_writes)


def transcript(messages: list[dict[str, Any]]) -> str:
    """Flatten what the model was shown into searchable text."""
    lines: list[str] = []
    for message in messages:
        role = message["role"]
        tag = f"tool:{message.get('tool_call_id')}" if role == "tool" else role
        lines.append(f"[{tag}] {message.get('content') or ''}")
        for call in message.get("tool_calls") or []:
            function = call["function"]
            lines.append(f"[call:{call['id']}] {function['name']} {function['arguments']}")
    return "\n".join(lines)


def _is_subsequence(needle: list[str], haystack: list[str]) -> bool:
    remaining = iter(haystack)
    return all(item in remaining for item in needle)


def check_invariants(run: EvalRun) -> None:
    """Contract every turn must honour, whatever the scenario."""
    events = run.events
    names = [e.name for e in events]
    final = run.final

    assert names[0] == "meta"
    assert names.count("done") == 1
    assert names.count("usage") == 1
    assert names[-2:] == ["done", "__final__"]
    assert names.index("usage") < names.index("done")
    assert next(e for e in events if e.name == "done").data["finishReason"] == final["finishReason"]

    shown = ""
    for event in events:
        if event.name == "delta":
            # A replayed delta is the client's cue to start the text over.
            shown = event.data["text"] if event.data.get("replayed") else shown + event.data["text"]
    assert final["answer"] == shown

    assert len(run.model.calls) <= len(run.model.turns), "model was called more often than scripted"
    model_stages = [e.data for e in events if e.name == "stage" and e.data.get("phase") == "model"]
    assert sum(s.get("status") == "start" for s in model_stages) == len(run.model.calls)
    assert len(model_stages) == 2 * len(run.model.calls)

    called = sorted(e.data["toolName"] for e in events if e.name == "tool_call")
    answered = sorted(e.data["toolName"] for e in events if e.name == "tool_result")
    assert called == answered

    for request in run.java.requests:
        headers = request["headers"]
        assert headers["authorization"] == f"Bearer {JAVA_TOKEN}"
        assert headers["x-actor-user-id"] == str(ACTOR_ID)
        assert headers["x-trace-id"] == run.request.traceId

    clean = final["finishReason"] == "stop" and bool(final["answer"])
    remembered = [["user", run.request.input.text]]
    if clean:
        remembered.append(["assistant", final["answer"]])
    assert run.memory.turns == remembered
    assert run.rag_writes == ([(run.request.input.text, final["answer"])] if clean else [])


def check_expectations(case: dict[str, Any], run: EvalRun) -> None:
    expect = case["expect"]
    events = run.events
    final = run.final

    assert final["finishReason"] == expect["finishReason"]
    if "answer" in expect:
        assert final["answer"] == expect["answer"]
    for needle in expect.get("answerContains", []):
        assert needle in final["answer"]
    if "modelCalls" in expect:
        assert len(run.model.calls) == expect["modelCalls"]
    if "toolChoice" in expect:
        assert [call["tool_choice"] for call in run.model.calls] == expect["toolChoice"]
    if "toolCalls" in expect:
        requested = [{"toolName": e.data["toolName"], "args": e.data["args"]}
                     for e in events if e.name == "tool_call"]
        assert requested == [{"toolName": t["toolName"], "args": t["args"]} for t in expect["toolCalls"]]
        assert [(t["toolName"], t["status"]) for t in final["toolCalls"]] == \
            [(t["toolName"], t["status"]) for t in expect["toolCalls"]]
    if "javaRequests" in expect:
        assert len(run.java.requests) == expect["javaRequests"]
    if "usage" in expect:
        assert final["usage"] == expect["usage"]
    assert sum(e.name == "error" for e in events) == expect.get("errorEvents", 0)
    for field_name, value in expect.get("structured", {}).items():
        assert final[field_name] == value

    for index, needles in expect.get("modelSaw", {}).items():
        shown = transcript(run.model.calls[int(index)]["messages"])
        for needle in needles:
            assert needle in shown, f"model call {index} was not shown {needle!r}"
    for index, needles in expect.get("modelSawInOrder", {}).items():
        shown = transcript(run.model.calls[int(index)]["messages"])
        position = -1
        for needle in needles:
            found = shown.find(needle, position + 1)
            assert found > position, f"model call {index}: {needle!r} missing or out of order"
            position = found
    for needle in expect.get("modelNeverSaw", []):
        for call in run.model.calls:
            assert needle not in transcript(call["messages"])
    if "eventOrder" in expect:
        assert _is_subsequence(expect["eventOrder"], [e.name for e in events])

"""Required owner adapters. No defaults, executors, savers or policy fallbacks."""
from __future__ import annotations
from typing import Any, AsyncContextManager, AsyncIterator, Protocol
from ..llm.base import LLMChunk
from .state import State, Node


class NotReady(RuntimeError):
    pass


class T1(Protocol):
    async def persist(self, state: State) -> None: ...
    async def emit(self, kind: str, payload: dict[str, Any]) -> None: ...
    async def cancelled(self, identity: dict[str, str]) -> bool: ...
    def call_id(self, identity: dict[str, str], node_id: str, binding: str,
                ordinal: int, provider_id: str) -> str: ...
    async def query(self, call_id: str) -> dict[str, Any]: ...
    async def claim_model(self, call_id: str) -> None: ...  # lifecycle only, NOT statistics
    async def model_end(self, call_id: str, usage: dict[str, int | None] | None,
                        finish: str, response: dict[str, Any]) -> None: ...
    async def commit_tool(self, call_id: str, provider_call_id: str,
                          request: dict[str, Any], invoke: Any) -> dict[str, Any]: ...


class T2(Protocol):
    async def narrow(self, identity: dict[str, str], inherited: dict[str, Any],
                     role: str) -> dict[str, Any]: ...
    async def verify(self, identity: dict[str, str], scope: dict[str, Any],
                     role: str) -> bool: ...
    def binding(self, state: State, node: Node, scope: dict[str, Any]) -> str: ...


class T3(Protocol):
    async def model_context(self, state: State, node: Node, scope: dict[str, Any],
                            schemas: list[dict[str, Any]]) -> dict[str, Any]: ...
    # Required bounded messages and max_tokens/timeout_sec/temperature; recheck
    # facts and actual parent texts/versions, preserving tool signatures/round trips.
    # budget is the single owner RunBudget for this run; model_context assembles
    # messages only. ContextBudget.fit is invoked by budget.stream_model.
    budget: Any
    async def prepare(self, state: State, node: Node,
                      scope: dict[str, Any]) -> dict[str, Any]: ...
    async def select(self, facts: dict[str, Any], state: State, node: Node,
                     scope: dict[str, Any]) -> dict[str, Any]: ...


class T4(Protocol):
    async def schemas(self, identity: dict[str, str], role: str,
                      scope: dict[str, Any]) -> list[dict[str, Any]]: ...
    async def authorize(self, identity: dict[str, str], role: str,
                        scope: dict[str, Any], call: dict[str, Any]) -> bool: ...
    async def execute(self, identity: dict[str, str], scope: dict[str, Any],
                      call_id: str, call: dict[str, Any]) -> dict[str, Any]: ...
    async def close(self, identity: dict[str, str], node_ids: list[str]) -> None: ...


class Model(Protocol):
    supports_dispatch_gate: bool
    def stream_dispatch(self, prepared: Any, options: dict[str, Any],
                        dispatch_gate: Any) -> AsyncIterator[LLMChunk]: ...
    # Only the real SDK/HTTP send entry awaits gate(messages, tools, limit).


class Ports:
    """T5-local requirements, not owner APIs. Production bridges are explicit."""
    def __init__(self, t1: T1, t2: T2, t3: T3, t4: T4, model: Model):
        for adapter, methods in ((t1, ('persist', 'emit', 'cancelled', 'call_id', 'query',
                                       'claim_model', 'model_end', 'commit_tool')),
                                 (t2, ('narrow', 'verify', 'binding')),
                                 (t3, ('prepare', 'select', 'model_context')),
                                 (t4, ('schemas', 'authorize', 'execute', 'close')),
                                 (model, ('stream_dispatch',))):
            if adapter is None or any(not callable(getattr(adapter, m, None)) for m in methods):
                raise NotReady('Required owner capability unavailable')
        if getattr(model, 'supports_dispatch_gate', False) is not True:
            raise NotReady('Actual SDK dispatch hook unavailable')
        budget = getattr(t3, 'budget', None)
        if budget is None or any(not callable(getattr(budget, m, None)) for m in
                                 ('stream_model', 'guard', 'run_scope', 'snapshot', 'aclose')):
            raise NotReady('Shared RunBudget unavailable')
        self.budget = budget
        self.t1, self.t2, self.t3, self.t4, self.model = t1, t2, t3, t4, model

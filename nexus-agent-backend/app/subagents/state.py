"""Serializable checkpoint data only; runtime capabilities never enter state."""
from __future__ import annotations
from typing import Any, TypedDict


class Node(TypedDict):
    nodeId: str
    agentId: str
    role: str
    parentIds: list[str]
    status: str
    phase: str
    version: int
    parentVersions: dict[str, int]
    transcript: list[dict[str, Any]]
    pending: list[dict[str, Any]]
    callIds: list[str]
    factIds: list[str]
    sourceIds: list[str]
    text: str
    finishReason: str | None
    errorCode: str | None
    binding: str | None
    context: dict[str, Any]
    modelOrdinal: int
    modelPending: dict[str, Any] | None
    missingNodeIds: list[str]
    missingReasons: dict[str, str]
    parentPolicy: str
    retryContext: dict[str, Any] | None
    factCoverage: dict[str, Any]
    factsPartial: bool


class State(TypedDict):
    identity: dict[str, str]
    input: str
    inputVersion: str
    scope: dict[str, Any]
    runScope: dict[str, Any]
    nodes: dict[str, Node]
    facts: dict[str, Any]
    factsPrepared: bool
    active: str | None
    cancelled: bool
    cancelRequested: bool
    cancelError: str | None
    output: dict[str, Any]
    sealed: bool  # Only T1 may inject verified durable sealing evidence.


def ensure_unsealed(state: State) -> None:
    if state.get('sealed', False):
        from .contracts import NotReady
        raise NotReady('Sealed run belongs to the T1 authorized handover path')


def new_node(role: str, parents: list[str]) -> Node:
    return Node(nodeId=role, agentId=role, role=role, parentIds=parents,
                status="queued", phase="prepare", version=0, parentVersions={},
                transcript=[], pending=[], callIds=[], factIds=[], sourceIds=[],
                text="", finishReason=None, errorCode=None, binding=None, context={}, modelOrdinal=0,
                modelPending=None, missingNodeIds=[], missingReasons={}, retryContext=None,
                factCoverage={}, factsPartial=False,
                parentPolicy='available_results' if role in ('review', 'synth') else 'all_success')

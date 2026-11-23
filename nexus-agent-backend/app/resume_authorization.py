"""Scope-only refresh for the existing SQLite checkpoint (no graph input/step).

Pinned AsyncSqliteSaver 3.1.3 stores the complete checkpoint with aput; replacing
its existing id retains pending writes. Do not generalize this to other savers.
Continuation behaviour must be integration-tested by T1 before release.
"""
from copy import deepcopy

from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from .scope import ScopeClient, ScopeDenied, resume_scope


async def refresh_checkpoint_scope(graph, saver, config, scope):
    # In-place replacement is specific to the existing SQLite saver. A new
    # storage adapter must not inherit this continuation-sensitive operation.
    if not isinstance(saver, AsyncSqliteSaver):
        raise ScopeDenied()
    saved = await saver.aget_tuple(config)
    if saved is None:
        raise ScopeDenied()
    checkpoint = deepcopy(saved.checkpoint)
    request = checkpoint.get("channel_values", {}).get("request")
    if not isinstance(request, dict):
        raise ScopeDenied()
    resume_scope(request, scope)
    scope = await ScopeClient().validate(scope)
    before = await graph.aget_state(saved.config)
    # No reducer, node writers, new step, trigger version or input submission.
    # Keep the checkpoint id: pending writes/interrupts are keyed by that id.
    request["scope"] = scope.model_dump()
    parent = deepcopy(saved.parent_config or saved.config)
    if saved.parent_config is None:
        parent["configurable"].pop("checkpoint_id", None)
    await saver.aput(parent, checkpoint, deepcopy(saved.metadata), {})
    after = await graph.aget_state(saved.config)
    if after.next != before.next or after.tasks != before.tasks:
        # Fail closed; never continue a graph whose continuation changed.
        raise RuntimeError("scope refresh changed checkpoint continuation")
    return after.values["request"], scope

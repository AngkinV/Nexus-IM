"""A future checkpoint adapter must not inherit SQLite's in-place scope refresh.

Regression source only; execution is owned by the original T1 session. The
positive real-SQLite continuation path is covered in test_scope_resume_persistence.
"""
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.resume_authorization import refresh_checkpoint_scope
from app.scope import ScopeDenied


@pytest.mark.asyncio
async def test_non_sqlite_adapter_is_rejected_before_any_read_write_or_authorization(monkeypatch):
    from app import resume_authorization

    saver = SimpleNamespace(aget_tuple=AsyncMock(), aput=AsyncMock())
    graph = SimpleNamespace(aget_state=AsyncMock())
    authority = AsyncMock()
    monkeypatch.setattr(resume_authorization.ScopeClient, "validate", authority)
    returned = []

    with pytest.raises(ScopeDenied):
        returned.append(await refresh_checkpoint_scope(
            graph, saver, {"configurable": {"thread_id": "u1:run_scope_adapter"}}, None,
        ))

    saver.aget_tuple.assert_not_awaited()
    saver.aput.assert_not_awaited()
    graph.aget_state.assert_not_awaited()
    authority.assert_not_awaited()
    assert returned == []

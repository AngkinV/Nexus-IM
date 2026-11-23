"""T2 synthetic regressions. Deliberately not executed in the implementation window."""
import copy
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
import pytest
from app.scope import AuthorizedScope, ScopeClient, ScopeDenied, scope_hash, narrow_scope, resume_scope
from app.mcp_tools import ToolExecutor, _Entry
from app.memory import ShortTermMemory


def grant(chats=(101,)):
    d = dict(schemaVersion=1, grantId='synthetic-grant', actorUserId=1,
             sessionId='session-a', runId='run-0001', bindingVersion=1,
             authorizationVersion=1, sessionGeneration=1, chatIds=list(chats),
             contactUserIds=[], timeRange=dict(fromInclusive=None, toExclusive=None,
                                             timeZone='Asia/Shanghai'))
    d['scopeKey'] = scope_hash(d)
    return AuthorizedScope.model_validate(d)


@pytest.mark.asyncio
async def test_actual_executor_ab_remote_counter():
    a = grant()
    remote = AsyncMock(return_value=SimpleNamespace(content='{"rows":["A"]}',
                                                    status='success', artifact=None))
    executor = ToolExecutor(1, 'fixture', scope=a)
    with patch.object(ScopeClient, 'validate', AsyncMock(return_value=a)), \
         patch('app.mcp_tools.catalog', AsyncMock(return_value={
             'get_chat_history': _Entry(None, 'get_chat_history', {})})), \
         patch.object(executor, '_tools_of', AsyncMock(return_value={
             'get_chat_history': SimpleNamespace(ainvoke=remote)})):
        returned_b = []
        with pytest.raises(ScopeDenied):
            returned_b.append(await executor.execute('get_chat_history', {'chat_id': 202}))
        assert remote.await_count == 0 and returned_b == []
        result, _ = await executor.execute('get_chat_history', {'chat_id': 101})
        assert remote.await_count > 0 and result['rows'] == ['A']


def test_hash_subset_and_checkpoint_sentinel():
    a, b = grant(), grant((202,))
    with pytest.raises(ScopeDenied):
        narrow_scope(a, chat_ids=[202])
    with pytest.raises(ScopeDenied):
        resume_scope({'scope': b.model_dump(), 'messages': ['B-PRIVATE']}, a)
    with pytest.raises(ScopeDenied):
        resume_scope({'messages': ['B-PRIVATE']}, a)
    fresh = a.model_copy(update={'authorizationVersion': 2, 'grantId': 'replacement'})
    assert resume_scope({'scope': a.model_dump()}, fresh) == fresh
    d = a.model_dump(); d['actorUserId'] = True
    with pytest.raises(ValueError):
        AuthorizedScope.model_validate(d)


@pytest.mark.asyncio
async def test_memory_partition_and_late_deleted_write():
    a, b = grant(), grant((202,))
    redis = SimpleNamespace(lrange=AsyncMock(return_value=[]), eval=AsyncMock(return_value=1))
    memory = ShortTermMemory(redis)
    with patch.object(ScopeClient, 'validate', AsyncMock(side_effect=lambda s: s)):
        await memory.append(1, 'session-a', 'user', 'A', scope=a, run_id='run-0001')
        await memory.recent(1, 'session-a', scope=b)
        assert a.scopeKey in redis.eval.call_args.args[2]
        assert b.scopeKey in redis.lrange.call_args.args[0]
    with patch.object(ScopeClient, 'validate', AsyncMock(side_effect=ScopeDenied())):
        before = redis.eval.await_count
        with pytest.raises(ScopeDenied):
            await memory.append(1, 'session-a', 'assistant', 'late-A', scope=a)
        assert redis.eval.await_count == before


@pytest.mark.asyncio
async def test_scope_client_fail_closed_actual_http_contract():
    a = grant()
    response = SimpleNamespace(raise_for_status=lambda: None,
                              json=lambda: {'valid': False, 'scope': a.model_dump()})
    http = AsyncMock(); http.post.return_value = response
    with pytest.raises(ScopeDenied):
        await ScopeClient(http).validate(a)
    call = http.post.call_args
    assert call.kwargs['json']['scope'] == a.model_dump()
    assert call.args[0].endswith('/internal/agent/scope/validate')

"""Static-authored T2 follow-up regressions; execution reserved for T1."""
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
import pytest
from app.scope import ScopeClient, ScopeDenied, ScopedModel
from app.memory import ShortTermMemory
from app.routes import pending_runs
from app.schemas import RunQuery, InvokeRequest
from app.prompts import build_messages
from app.mcp_tools import ToolExecutor, _Entry
from test_scope_v1 import grant
from test_scope_authority_paths import Authority


@pytest.mark.asyncio
@pytest.mark.parametrize('prefix', ['', '/api'])
async def test_success_http_full_url_nonempty_a(prefix):
    a = grant()
    http = AsyncMock()
    http.post.return_value = SimpleNamespace(raise_for_status=lambda: None,
        json=lambda: {'valid': True, 'scope': a.model_dump()})
    settings = SimpleNamespace(java_internal_base_url=f'http://java{prefix}/internal/agent/',
                               java_internal_token='synthetic')
    with patch('app.scope.get_settings', return_value=settings):
        result = await ScopeClient(http).validate(a)
    assert result.chatIds == [101]
    call = http.post.call_args
    assert call.args == (f'http://java{prefix}/internal/agent/scope/validate',)
    assert call.kwargs['headers']['X-Actor-User-Id'] == '1'
    assert call.kwargs['json']['scope'] == a.model_dump()


@pytest.mark.asyncio
@pytest.mark.parametrize('response', [
    {'code': 'ERROR', 'data': {'valid': True}},
    {'code': 'OK', 'data': {'valid': True}},
])
async def test_envelope_not_current_java_contract(response):
    http = AsyncMock()
    http.post.return_value = SimpleNamespace(raise_for_status=lambda: None, json=lambda: response)
    with pytest.raises(ScopeDenied):
        await ScopeClient(http).validate(grant())


@pytest.mark.asyncio
async def test_finished_and_resumable_a_visible_b_filtered():
    a = grant()
    b = grant((202,)).model_copy(update={'runId': 'run-0002'})
    store = SimpleNamespace(saver=None,
        pending=AsyncMock(return_value=[SimpleNamespace(thread_id='fixture-a', run_id=a.runId,
            actor_user_id=1, operation_type='REPLY_SUGGEST', updated_at=1),
            SimpleNamespace(thread_id='fixture-b', run_id=b.runId, actor_user_id=1,
                            operation_type='REPLY_SUGGEST', updated_at=2)]),
        finished=AsyncMock(return_value=[a.runId, b.runId, 'legacy00']),
        result=AsyncMock(side_effect=[{'result': {'scope': a.model_dump(), 'answer': 'A-PRIVATE'}},
            {'result': {'scope': b.model_dump(), 'answer': 'B-PRIVATE'}},
            {'result': {'answer': 'LEGACY-PRIVATE'}}]))
    graph = SimpleNamespace(aget_state=AsyncMock(side_effect=[SimpleNamespace(values={
        'request': {'scope': a.model_dump()}, 'answer': 'A-PRIVATE'}),
        SimpleNamespace(values={'request': {'scope': b.model_dump()}, 'answer': 'B-PRIVATE'})]))
    @asynccontextmanager
    async def opened():
        yield store
    with patch('app.routes.open_run_store', opened), \
         patch('app.routes.ScopeClient', return_value=ScopeClient(Authority(a))), \
         patch('app.orchestrator.get_graph_builder', return_value=SimpleNamespace(compile=lambda **kw: graph)):
        result = await pending_runs(RunQuery(actor={'userId': 1}, sessionId=a.sessionId), {'actorUserId': 1})
    assert result['finished'] == [a.runId]
    assert result['finishedScopes'] == {a.runId: a.model_dump()}
    assert len(result['runs']) == 1
    assert result['runs'][0]['scope'] == a.model_dump()
    assert 'PRIVATE' not in str(result)
    assert b.runId not in result['finished']  # invalid B returns zero discoveries
    store.finished.assert_awaited_once_with(1, a.sessionId)


@pytest.mark.asyncio
async def test_provider_checks_boundaries_not_chunks_and_fresh_generation_works():
    a = grant()
    fresh_data = a.model_dump()
    fresh_data.update(sessionGeneration=2, authorizationVersion=2, grantId='new-generation')
    from app.scope import scope_hash, AuthorizedScope
    fresh_data['scopeKey'] = scope_hash(fresh_data)
    fresh = AuthorizedScope.model_validate(fresh_data)
    authority = Authority(fresh)
    client = ScopeClient(authority)
    redis = SimpleNamespace(eval=AsyncMock(return_value=1), lrange=AsyncMock(return_value=['{"role":"user","content":"NEW-A"}']))
    async def complete(*args, **kwargs):
        for token in ['NEW', '-', 'A']:
            yield token
    model = SimpleNamespace(complete=complete)
    with patch('app.scope.ScopeClient', return_value=client), patch('app.memory.ScopeClient', return_value=client):
        memory = ShortTermMemory(redis)
        with pytest.raises(ScopeDenied):
            await memory.append(1, a.sessionId, 'user', 'late-old', scope=a, run_id=a.runId)
        assert redis.eval.await_count == 0
        assert await memory.append(1, fresh.sessionId, 'user', 'NEW-A', scope=fresh, run_id=fresh.runId)
        assert (await memory.recent(1, fresh.sessionId, scope=fresh))[0]['content'] == 'NEW-A'
        before = authority.calls
        assert [x async for x in ScopedModel(model, fresh).complete([])] == ['NEW', '-', 'A']
        assert authority.calls - before == 2


@pytest.mark.asyncio
async def test_reply_target_id_authorized_tool_positive_no_raw():
    a = grant()
    request = InvokeRequest(traceId='fixture', actor={'userId': 1}, scope=a, runId=a.runId,
        session={'sessionId': a.sessionId, 'operationType': 'REPLY_SUGGEST'},
        input={'text': 'reply', 'chatId': 101, 'targetMessageId': 42})
    messages = build_messages(request, [], [])
    assert 'get_message_by_id' in messages[0]['content']
    assert 'targetMessageId=42' in messages[-1]['content']
    assert 'targetMessageContent=' not in str(messages)
    remote = AsyncMock(return_value=SimpleNamespace(content='{"content":"A-REPLY-TARGET"}', status='success', artifact=None))
    executor = ToolExecutor(1, 'fixture', scope=a)
    executor._scope_client = ScopeClient(Authority(a))
    with patch('app.mcp_tools.catalog', AsyncMock(return_value={'get_message_by_id': _Entry(None, 'get_message_by_id', {})})), \
         patch.object(executor, '_tools_of', AsyncMock(return_value={'get_message_by_id': SimpleNamespace(ainvoke=remote)})):
        result, _ = await executor.execute('get_message_by_id', {'message_id': 42})
    assert result['content'] == 'A-REPLY-TARGET'
    assert remote.await_count == 1


@pytest.mark.asyncio
async def test_purge_all_scope_partitions_not_other_sessions_or_users():
    a, b = grant(), grant((202,))
    memory = ShortTermMemory(SimpleNamespace(delete=AsyncMock()))
    keys = [memory._messages_key(1, a.sessionId, s.scopeKey) for s in (a, b)]
    keys += [memory._summary_key(1, a.sessionId, a.scopeKey), keys[0] + ':run:run-0001:user']
    unrelated = [memory._messages_key(2, a.sessionId, a.scopeKey),
                 memory._messages_key(1, a.sessionId + ':other', a.scopeKey),
                 'agent:ctx:1:session-a:messages:run:ambiguous:user']
    async def scan(**kwargs):
        for key in keys + unrelated:
            yield key
    memory._client.scan_iter = scan
    await memory.clear(1, a.sessionId)
    deleted = [k for call in memory._client.delete.call_args_list for k in call.args]
    assert all(k in deleted for k in keys)
    assert not any(k in deleted for k in unrelated)

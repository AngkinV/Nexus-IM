"""T2 source-only regressions. T1 runs serially; real graph and SQLite saver.

No fakegraph update-branch assertion substitutes for continuation checks.
"""
from contextlib import asynccontextmanager
from copy import deepcopy
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from langgraph.constants import INTERRUPT
from langgraph.types import Interrupt

from app.checkpoint import RunStore, thread_id_for
from app.orchestrator import get_graph_builder, resume_agent, Event
from app.resume_authorization import refresh_checkpoint_scope
from app.routes import pending_runs, invoke, _event_stream
from app.schemas import InvokeRequest, InvokeResponse, ResumeRequest, RunQuery
from app.scope import ScopeClient, ScopeDenied
from test_scope_v1 import grant
from test_scope_authority_paths import Authority


def request(scope):
    return InvokeRequest(traceId='original-trace', actor={'userId': 1}, scope=scope,
        runId=scope.runId, session={'sessionId': scope.sessionId,
        'operationType': 'ASSISTANT_CHAT'}, input={'text': 'original-input'})


@asynccontextmanager
async def opened_store(path):
    async with AsyncSqliteSaver.from_conn_string(str(path)) as saver:
        store = RunStore(saver)
        await store._setup()
        yield store


@pytest.mark.asyncio
@pytest.mark.parametrize('node', ['reason', 'tools'])
@pytest.mark.parametrize('interrupted', [False, True])
async def test_real_checkpoint_scope_only_preserves_next_tasks_pending_and_counts(tmp_path, node, interrupted):
    old = grant(); current = old.model_copy(update={'authorizationVersion': 2, 'grantId': 'renewed'})
    authority = ScopeClient(Authority(current))
    tid = thread_id_for(1, old.runId)
    config = {'configurable': {'thread_id': tid}}
    async with opened_store(tmp_path / 'continuation.sqlite') as store:
        graph = get_graph_builder().compile(checkpointer=store.saver)
        await store.register(tid, old.runId, 1, old.sessionId, 'ASSISTANT_CHAT')
        # Official writer API creates a synthetic checkpoint on the ACTUAL graph;
        # no node/model/tool executes while seeding a completed reason/tools step.
        await graph.aupdate_state(config, {'request': request(old).model_dump(),
            'pending_tool_calls': [{'id': 'tool-1', 'name': 'get_recent_messages', 'args': {'chat_id': 101}}],
            'answer': 'committed', 'model_calls': 1, 'usage_in': 7, 'usage_out': 3,
            'messages': [{'role': 'assistant', 'content': 'committed'}],
            'tool_calls': [], 'finish_reason': ''}, as_node=node)
        initial = await graph.aget_state(config)
        assert initial.next == (('tools',) if node == 'reason' else ('reason',))
        if interrupted:
            await store.saver.aput_writes(initial.config,
                [(INTERRUPT, (Interrupt(value={'approval': 'existing'}, id='existing-interrupt'),))],
                initial.tasks[0].id)
        before = await graph.aget_state(config)
        raw = await store.saver.aget_tuple(config)
        original_checkpoint = deepcopy(raw.checkpoint)
        original_pending = deepcopy(raw.pending_writes)
        model = AsyncMock(); tools = AsyncMock(); memory = AsyncMock()
        @asynccontextmanager
        async def opened():
            yield store
        with patch('app.resume_authorization.ScopeClient', return_value=authority), \
             patch('app.routes.ScopeClient', return_value=authority), \
             patch('app.routes.open_run_store', opened), \
             patch('app.orchestrator.build_client', model), \
             patch('app.mcp_tools.ToolExecutor.execute', tools), \
             patch('app.memory.ShortTermMemory.append', memory):
            saved, validated = await refresh_checkpoint_scope(graph, store.saver, config, current)
            listed = await pending_runs(RunQuery(actor={'userId': 1}, sessionId=old.sessionId),
                                        {'actorUserId': 1})
        after = await graph.aget_state(config)
        persisted = await store.saver.aget_tuple(config)
        expected = deepcopy(original_checkpoint)
        expected['channel_values']['request']['scope'] = current.model_dump()
        assert persisted.checkpoint == expected
        assert persisted.metadata == raw.metadata
        assert persisted.parent_config == raw.parent_config
        assert persisted.pending_writes == original_pending
        assert after.next == before.next and after.tasks == before.tasks
        assert after.config == before.config
        assert saved == {**request(old).model_dump(), 'scope': current.model_dump()}
        assert validated == current
        assert listed['runs'][0]['scope'] == current.model_dump()
        assert model.call_count == tools.call_count == memory.call_count == 0
        # Reopen from disk: not merely the runtime request or an update branch.
    async with opened_store(tmp_path / 'continuation.sqlite') as store:
        after = await get_graph_builder().compile(checkpointer=store.saver).aget_state(config)
        assert after.next == before.next and after.tasks == before.tasks
        assert after.values['request']['scope'] == current.model_dump()
        assert after.values['request']['traceId'] == 'original-trace'


@pytest.mark.asyncio
@pytest.mark.parametrize('deny', ['key', 'generation', 'revoked'])
async def test_checkpoint_denied_no_write(tmp_path, deny):
    old = grant()
    current = grant((202,)) if deny == 'key' else old.model_copy(update={
        'sessionGeneration': 2} if deny == 'generation' else {'authorizationVersion': 2})
    authority = Authority(current); authority.revoked = deny == 'revoked'
    async with opened_store(tmp_path / 'reject.sqlite') as store:
        graph = get_graph_builder().compile(checkpointer=store.saver)
        config = {'configurable': {'thread_id': thread_id_for(1, old.runId)}}
        await graph.aupdate_state(config, {'request': request(old).model_dump()}, as_node='tools')
        before = await store.saver.aget_tuple(config)
        with patch.object(store.saver, 'aput', wraps=store.saver.aput) as save, \
             patch.object(graph, 'aupdate_state', wraps=graph.aupdate_state) as update, \
             patch('app.resume_authorization.ScopeClient', return_value=ScopeClient(authority)):
            with pytest.raises(ScopeDenied):
                await refresh_checkpoint_scope(graph, store.saver, config, current)
            assert save.call_count == update.call_count == 0
        assert (await store.saver.aget_tuple(config)).checkpoint == before.checkpoint


@pytest.mark.asyncio
@pytest.mark.parametrize('deny', [None, 'key', 'generation', 'revoked'])
async def test_finished_refresh_persisted_before_any_success_frame(tmp_path, deny):
    old = grant(); current = old.model_copy(update={'authorizationVersion': 2, 'grantId': 'renewed'})
    if deny == 'key':
        current = grant((202,))
    if deny == 'generation':
        # A newer generation is a different, internally consistent grant: its key must
        # match its own generation so the model accepts it and only the authority rejects.
        from app.scope import scope_hash
        bumped = {**current.model_dump(), 'sessionGeneration': 2}
        bumped['scopeKey'] = scope_hash(bumped)
        current = type(current).model_validate(bumped)
    authority = Authority(current); authority.revoked = deny == 'revoked'
    client = ScopeClient(authority)
    tid = thread_id_for(1, old.runId)
    original = dict(answer='committed answer', usage={'inputTokens': 7, 'outputTokens': 3,
        'totalTokens': 10}, finishReason='stop', toolCalls=[], scope=old.model_dump(), runId=old.runId)
    async with opened_store(tmp_path / 'finished.sqlite') as store:
        await store.save_result(tid, 1, old.sessionId, 'ASSISTANT_CHAT', original)
        @asynccontextmanager
        async def opened():
            yield store
        model = AsyncMock(); tools = AsyncMock(); memory = AsyncMock()
        with patch('app.orchestrator.open_run_store', opened), \
             patch('app.routes.open_run_store', opened), \
             patch('app.orchestrator.ScopeClient', return_value=client), \
             patch('app.routes.ScopeClient', return_value=client), \
             patch('app.orchestrator._resolve_provider_config', return_value=None), \
             patch('app.orchestrator.build_client', model), \
             patch('app.mcp_tools.ToolExecutor.execute', tools), \
             patch('app.memory.ShortTermMemory.append', memory), \
             patch.object(store, 'save_result', wraps=store.save_result) as save, \
             patch('app.orchestrator.refresh_checkpoint_scope', AsyncMock()) as update:
            events = []
            async for event in resume_agent(ResumeRequest(traceId='resume-trace', actor={'userId': 1},
                                            sessionId=old.sessionId, runId=old.runId, scope=current)):
                events.append(event)
                if event.name in ('meta', 'delta', 'usage', '__final__'):
                    assert deny is None
                    assert (await store.result(tid, old.sessionId))['result']['scope'] == current.model_dump()
            listed = await pending_runs(RunQuery(actor={'userId': 1}, sessionId=old.sessionId),
                                        {'actorUserId': 1})
            assert model.call_count == tools.call_count == memory.call_count == update.call_count == 0
            if deny:
                assert save.call_count == 0
                assert not any(e.name in ('meta', 'delta', '__final__') for e in events)
                assert (await store.result(tid, old.sessionId))['result'] == original
            else:
                save.assert_awaited_once_with(tid, 1, old.sessionId, 'ASSISTANT_CHAT',
                                             {**original, 'scope': current.model_dump()})
                result = next(e.data['result'] for e in events if e.name == '__final__')
                assert result == {**original, 'scope': current.model_dump()}
                assert next(e.data['scope'] for e in events if e.name == 'meta') == current.model_dump()
                assert listed['finishedScopes'][old.runId] == current.model_dump()
                assert InvokeResponse(traceId='resume-trace', result=result).model_dump()['result']['scope'] == current.model_dump()


@pytest.mark.asyncio
async def test_nonstream_response_model_and_stream_meta_keep_scope():
    current = grant().model_copy(update={'authorizationVersion': 2, 'grantId': 'renewed'})
    result = {'answer': 'answer', 'scope': current.model_dump()}
    async def source(*args, **kwargs):
        yield Event('meta', {'scope': current.model_dump()})
        yield Event('__final__', {'result': result})
    with patch('app.routes.run_agent', source):
        response = await invoke(request(current), {'actorUserId': 1, 'traceId': 'original-trace'})
    # FastAPI uses the declared InvokeResponse schema, not arbitrary extra keys.
    assert InvokeResponse.model_validate(response.model_dump()).model_dump()['result']['scope'] == current.model_dump()
    from fastapi import FastAPI
    from httpx import ASGITransport, AsyncClient
    from app.routes import router
    from app.security import verify_internal_signature
    api = FastAPI()
    api.include_router(router)
    api.dependency_overrides[verify_internal_signature] = lambda: {
        'actorUserId': 1, 'traceId': 'original-trace'}
    with patch('app.routes.run_agent', source):
        async with AsyncClient(transport=ASGITransport(app=api), base_url='http://fixture') as http:
            wire = await http.post('/v1/agent/invoke', json=request(current).model_dump())
            assert wire.status_code == 200
            assert wire.json()['result']['scope'] == current.model_dump()
            wire_stream = await http.post('/v1/agent/invoke/stream', json=request(current).model_dump())
            assert wire_stream.status_code == 200
            assert 'authorizationVersion' in wire_stream.text and 'renewed' in wire_stream.text
    streamed = b''
    async for chunk in _event_stream(source()).body_iterator:
        streamed += chunk.encode() if isinstance(chunk, str) else chunk
    assert b'authorizationVersion' in streamed and b'renewed' in streamed
    assert b'__final__' not in streamed

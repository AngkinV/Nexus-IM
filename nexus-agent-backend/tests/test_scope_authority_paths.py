"""Authority-backed synthetic paths; no embeddings, services or tests executed here."""
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
import pytest
from app.scope import ScopeClient, ScopeDenied, ScopedModel
from app.mcp_tools import ToolExecutor, _Entry
from app.rag import memory_rag
from app.schemas import ResumeRequest
from app.orchestrator import resume_agent
from test_scope_v1 import grant


class Authority:
    """Synthetic Java validate endpoint, exercises actual ScopeClient parsing."""
    def __init__(self, scope):
        self.scope = scope
        self.revoked = False
        self.calls = 0

    async def post(self, url, *, json, headers):
        self.calls += 1
        valid = (not self.revoked and json['scope'] == self.scope.model_dump()
                 and json['actorUserId'] == self.scope.actorUserId
                 and headers['X-Actor-User-Id'] == str(self.scope.actorUserId))
        return SimpleNamespace(raise_for_status=lambda: None, json=lambda: {
            'valid': valid, 'scope': self.scope.model_dump()})


@pytest.mark.asyncio
async def test_cross_account_empty_missing_forged_and_exit_actual_ainvoke_zero():
    a = grant(); authority = Authority(a)
    client = ScopeClient(authority)
    remote = AsyncMock(return_value=SimpleNamespace(content='{"rows":["A"]}',
                                                    status='success', artifact=None))
    catalog = {'get_recent_messages': _Entry(None, 'get_recent_messages', {})}
    for s in [None, grant(()), a.model_copy(update={'grantId': 'forged'}),
              a.model_copy(update={'authorizationVersion': 999})]:
        executor = ToolExecutor(1, 'synthetic', scope=s)
        executor._scope_client = client
        returned = []
        with patch('app.mcp_tools.catalog', AsyncMock(return_value=catalog)), \
             patch.object(executor, '_tools_of', AsyncMock(return_value={
                 'get_recent_messages': SimpleNamespace(ainvoke=remote)})):
            with pytest.raises(ScopeDenied):
                returned.append(await executor.execute('get_recent_messages', {'chat_id': 101}))
        assert returned == [] and remote.await_count == 0
    with pytest.raises(ScopeDenied):
        ToolExecutor(2, 'synthetic', scope=a)
    executor = ToolExecutor(1, 'synthetic', scope=a); executor._scope_client = client
    with patch('app.mcp_tools.catalog', AsyncMock(return_value=catalog)), \
         patch.object(executor, '_tools_of', AsyncMock(return_value={
             'get_recent_messages': SimpleNamespace(ainvoke=remote)})):
        assert (await executor.execute('get_recent_messages', {'chat_id': 101}))[0]['rows'] == ['A']
        before = remote.await_count
        authority.revoked = True
        with pytest.raises(ScopeDenied):
            await executor.execute('get_recent_messages', {'chat_id': 101})
        assert remote.await_count == before and authority.calls > 0


@pytest.mark.asyncio
async def test_checkpoint_b_private_never_replayed_or_modeled():
    a, b = grant(), grant((202,))
    authority = ScopeClient(Authority(a))
    graph = SimpleNamespace(aget_state=AsyncMock(return_value=SimpleNamespace(values={
        'request': {'scope': b.model_dump()}, 'answer': 'B-PRIVATE-SENTINEL'})),
        astream=AsyncMock())
    store = SimpleNamespace(saver=None, get=AsyncMock(return_value=SimpleNamespace(session_id='session-a')))
    @asynccontextmanager
    async def opened():
        yield store
    remote = AsyncMock(); model = AsyncMock()
    with patch('app.orchestrator.ScopeClient', return_value=authority), \
         patch('app.orchestrator.open_run_store', opened), \
         patch('app.orchestrator.get_graph_builder', return_value=SimpleNamespace(compile=lambda **kw: graph)), \
         patch('app.orchestrator._resolve_provider_config', return_value=None), \
         patch('app.orchestrator.build_client', model), \
         patch('app.mcp_tools.ToolExecutor.execute', remote):
        events = [e async for e in resume_agent(ResumeRequest(traceId='fixture', actor={'userId': 1},
                    sessionId='session-a', runId='run-0001', scope=a))]
    assert not any('B-PRIVATE' in str(e.data) for e in events)
    assert not any(e.name in ('delta', '__final__') for e in events)
    assert model.call_count == 0 and remote.await_count == 0 and graph.astream.call_count == 0


@pytest.mark.asyncio
async def test_rag_exact_partition_and_delete_late_write_without_embeddings():
    a, b = grant(), grant((202,))
    authority = Authority(b); client = ScopeClient(authority)
    fake_doc = SimpleNamespace(page_content='A-PRIVATE', metadata={
        'userId': 1, 'sessionId': a.sessionId, 'scopeKey': a.scopeKey, 'sessionGeneration': 1})
    store = SimpleNamespace(_collection=SimpleNamespace(count=lambda: 1),
                            similarity_search=lambda *args, **kwargs: [fake_doc],
                            add_documents=lambda *args, **kwargs: pytest.fail('late write'))
    settings = SimpleNamespace(memory_rag_enabled=True, memory_rag_min_chars=1, memory_rag_top_k=4)
    with patch('app.rag.memory_rag.ScopeClient', return_value=client), \
         patch('app.rag.memory_rag.get_settings', return_value=settings), \
         patch('app.rag.memory_rag._build_store', return_value=store):
        assert await memory_rag.retrieve(1, 'synthetic', scope=b) == []
        authority.revoked = True  # synthetic Java deletion tombstone
        with pytest.raises(ScopeDenied):
            await memory_rag.write(1, a.sessionId, 'late', 'A-PRIVATE', scope=a)
        with pytest.raises(ScopeDenied):
            await memory_rag.retrieve(1, 'synthetic', scope=b)


@pytest.mark.asyncio
async def test_every_provider_call_guarded_before_model():
    a = grant(); authority = Authority(a); authority.revoked = True
    model = SimpleNamespace(complete=AsyncMock())
    with patch('app.scope.ScopeClient', return_value=ScopeClient(authority)):
        with pytest.raises(ScopeDenied):
            _ = [c async for c in ScopedModel(model, a).complete([{'role': 'user', 'content': 'A'}])]
    assert model.complete.call_count == 0

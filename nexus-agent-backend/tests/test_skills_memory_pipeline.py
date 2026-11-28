"""Account embedding and durable-context contracts; all providers and memories are isolated fixtures."""
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock
import pytest
from fake_provider import FakeProvider
from test_model_routes import post, response
from app import orchestrator
from app.artifacts import extract_artifacts
from app.prompts import build_messages, SKILL_WRITER_GUIDE
from app.schemas import Actor, Session, InputPayload, InvokeRequest, ContextMemory
from app.scope import ScopeDenied
from scope_support import make_scope, next_run_id


async def test_embedding_batch_reorders_by_upstream_index_and_uses_account_channel():
    async with FakeProvider([response({'data':[{'index':1,'embedding':[0,1]},{'index':0,'embedding':[1,0]}]})]) as provider:
        r=await post('embed',provider.base_url,purpose='embedding',inputs=['first','second'],key='fixture-credential',model='fixture-embedding')
        assert r.status_code==200,r.text
        result=r.json();assert result['ok'] and result['vectors']==[[1,0],[0,1]]
        request=provider.requests[0]
        assert request['target']=='/v1/embeddings'
        assert request['json']['input']==['first','second'] and request['json']['model']=='fixture-embedding'
        assert request['headers']['authorization']=='Bearer fixture-credential'


@pytest.mark.parametrize('rows',[
    [{'index':0,'embedding':[1,0]},{'index':0,'embedding':[0,1]}],
    [{'index':0,'embedding':[1]},{'index':1,'embedding':[0,1]}],
    [{'index':0,'embedding':[float('nan')]},{'index':1,'embedding':[1]}],
    [{'index':True,'embedding':[1]},{'index':1,'embedding':[1]}],
])
async def test_invalid_vectors_are_rejected_not_saved_or_returned(rows):
    async with FakeProvider([response({'data':rows})]) as provider:
        result=(await post('embed',provider.base_url,purpose='embedding',inputs=['a','b'])).json()
        assert not result['ok'] and result['vectors']==[] and result['error']['code']=='INVALID_RESPONSE'


async def test_embedding_never_follows_redirects_or_falls_back_to_chat_key():
    async with FakeProvider([]) as target,FakeProvider([response({},307,location=target.base_url)]) as source:
        result=(await post('embed',source.base_url,purpose='embedding',inputs=['a'])).json()
        assert not result['ok'] and not target.requests
    result=(await post('embed',channel=None,purpose='embedding',inputs=['a'])).json()
    assert not result['ok'] and result['error']['code']=='CONFIG' and result['vectors']==[]


async def test_embedding_actor_purpose_and_input_bounds_fail_closed():
    assert (await post('embed',purpose='chat',inputs=['a'])).status_code==400
    assert (await post('embed',purpose='embedding',inputs=['a']*65)).status_code==422
    assert (await post('embed',purpose='embedding',inputs=[''])).status_code==422
    assert (await post('embed',purpose='embedding',inputs=['a'],headers={'X-Actor-User-Id':'999'})).status_code in (400,401,403)


async def test_gemini_batch_embedding_uses_declared_model():
    async with FakeProvider([response({'embeddings':[{'values':[1,0]},{'values':[0,1]}]})]) as provider:
        result=(await post('embed',provider.base_url,purpose='embedding',channel='gemini',model='embedding-model',inputs=['a','b'])).json()
        assert result['ok'],result
        req=provider.requests[0];assert req['target'].endswith('/models/embedding-model:batchEmbedContents')
        assert [x['content']['parts'][0]['text'] for x in req['json']['requests']]==['a','b']


def request_with_memory():
    run=next_run_id('run_durable');scope=make_scope(1,session_id='session-memory',run_id=run)
    durable=ContextMemory(scopeKey=scope.scopeKey,sessionGeneration=scope.sessionGeneration,messages=[{'role':'user','content':'prior durable input'}],
        summary='actual older source excerpts',summaryThrough=8,source='mysql',memoryStatus='unconfigured',recalled=['unconfirmed user preference'],memoryIds=[9])
    return InvokeRequest(traceId='trace-memory',actor=Actor(userId=1,username='fixture'),session=Session(sessionId='session-memory',operationType='ASSISTANT_CHAT'),
        input=InputPayload(text='continue',contextMemory=durable),scope=scope,runId=run)


async def test_durable_memory_survives_absent_redis_and_reports_actual_sources():
    request=request_with_memory();events=[]
    async def emit(name,data):events.append((name,data))
    memory=SimpleNamespace(recent=AsyncMock(side_effect=AssertionError('Must not read Redis when durable context is available')),append=AsyncMock(return_value=True))
    ctx=SimpleNamespace(request=request,memory=memory,run_id=request.runId,outbox=SimpleNamespace(emit=emit),provider=None)
    result=await orchestrator._prepare_context({},ctx)
    contents=[m['content'] for m in result['messages']]
    assert any('prior durable input' in c for c in contents)
    assert any('actual older source excerpts' in c for c in contents)
    assert any('unconfirmed user preference' in c for c in contents)
    receipt=next(data for name,data in events if name=='stage' and data.get('phase')=='context')
    assert receipt['shortSource']=='mysql' and receipt['summaryUsed'] is True and receipt['summaryThrough']==8
    assert receipt['memoryStatus']=='unconfigured' and receipt['memoryIds']==[9]
    memory.recent.assert_not_called()


async def test_durable_memory_rejects_another_scope_before_reading_any_history():
    request=request_with_memory();request.input.contextMemory=request.input.contextMemory.model_copy(update={'scopeKey':'f'*64})
    memory=SimpleNamespace(recent=AsyncMock(),append=AsyncMock())
    ctx=SimpleNamespace(request=request,memory=memory,run_id=request.runId,outbox=SimpleNamespace(emit=AsyncMock()),provider=None)
    with pytest.raises(ScopeDenied):await orchestrator._prepare_context({},ctx)
    memory.recent.assert_not_called();memory.append.assert_not_called()


def test_skill_writer_guide_and_generated_artifact_are_interoperable():
    request=request_with_memory();request.input.text='请制作酒馆 skills 人格包'
    messages=build_messages(request,[],[])
    assert any(SKILL_WRITER_GUIDE==m['content'] for m in messages)
    md='---\nname: fixture-listener\ndescription: concise listening\nnexus:\n  type: personality\n  version: "1"\n---\nListen before speaking.\n'
    artifacts=extract_artifacts('```nexus-skill\n'+json.dumps({'files':{'SKILL.md':md}})+'\n```',run_id='run-fixture',agent_id='assistant',node_id='model-1',source_ids=[],call_ids=[])
    assert len(artifacts)==1 and artifacts[0]['kind']=='skill'
    assert artifacts[0]['name'].endswith('.json') and json.loads(artifacts[0]['content'])['files']['SKILL.md']==md

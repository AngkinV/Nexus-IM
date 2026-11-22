import asyncio
import base64
import hashlib
import hmac
import json
import time
import uuid

import httpx
import pytest

from app import app, orchestrator
from app.config import get_settings
from fake_provider import FakeProvider


def response(data, status=200, **headers):
    return {'status': status, 'headers': {'content-type': 'application/json', **headers}, 'body': [json.dumps(data).encode()]}


def completion(text=None, reason=None):
    delta = {'content': text} if text is not None else {'reasoning_content': 'private thought'}
    data = {'id': 'chat-1', 'model': 'chosen', 'object': 'chat.completion.chunk', 'created': 1,
            'choices': [{'index': 0, 'delta': delta, 'finish_reason': reason}]}
    return {'body': [('data: ' + json.dumps(data) + '\n\ndata: [DONE]\n\n').encode()]}


async def post(op, base='', *, channel='custom', key='dummy', model='chosen', path=None, actor=7, trace='trace', headers=None, **extra):
    data = {'operation': op, 'actorUserId': actor, 'traceId': trace, 'purpose': 'chat', **extra}
    if op.startswith('complete'):
        data.setdefault('messages', [{'role': 'user', 'content': 'hello'}])
    body = json.dumps(data, separators=(',', ':')).encode()
    h = lambda value: hashlib.sha256(value).hexdigest()
    ts, nonce = str(int(time.time()*1000)), uuid.uuid4().hex
    auth = {'X-Internal-Service': get_settings().expected_caller, 'X-Internal-Timestamp': ts,
            'X-Internal-Nonce': nonce, 'X-Actor-User-Id': str(actor), 'X-Trace-Id': trace, 'Content-Type': 'application/json'}
    signed = f'{ts}.{nonce}.{h(body)}'
    if channel is not None:
        encoded = base64.b64encode(key.encode()).decode()
        auth.update({'X-Model-Provider': channel, 'X-Model-Base-URL': base, 'X-Model-Name': model, 'X-Model-Api-Key': encoded})
        signed += '.' + h('|'.join([channel, base, model, encoded]).encode())
    auth['X-Internal-Signature'] = hmac.new(get_settings().internal_signing_secret.encode(), signed.encode(), 'sha256').hexdigest()
    auth.update(headers or {})
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
        return await client.post('/v1/models/' + (path or op.replace('_', '/')), content=body, headers=auth)


@pytest.mark.parametrize('channel,data,expected', [
    ('custom', {'data':[{'id':'vendor/alpha'}]}, '/v1/models'),
    ('anthropic', {'data':[{'id':'claude-a'}],'has_more':False}, '/v1/models'),
    ('gemini', {'models':[{'name':'models/a','supportedGenerationMethods':['generateContent']}]}, '/v1/models'),
])
async def test_catalog_without_saved_model_does_not_probe(channel,data,expected):
    async with FakeProvider([response(data)]) as server:
        r = await post('catalog',server.base_url,channel=channel,model='')
        assert r.status_code == 200, r.text
        result = r.json()
        assert result['status'] == 'ready' and len(result['models']) == 1
        assert result['models'][0]['chat'] == (True if channel=='gemini' else None)
        assert server.requests[0]['method'] == 'GET' and server.requests[0]['target'].split('?')[0] == expected
        assert len(server.requests) == 1


async def test_pagination_deduplicates_ids_and_keeps_unknown_capabilities():
    async with FakeProvider([response({'data':[{'id':'alpha'}], 'has_more':True, 'last_id':'alpha'}),
                             response({'data':[{'id':'alpha'},{'id':'beta'}], 'has_more':False})]) as server:
        result = (await post('catalog',server.base_url,channel='anthropic')).json()
        assert [m['id'] for m in result['models']] == ['alpha','beta'] and not result['truncated']
        assert 'after_id=alpha' in server.requests[1]['target']


@pytest.mark.parametrize('data,status,expected', [({'data':[]},200,'empty'), ({},404,'unsupported'),
                                                 ({'error':{'code':'quota'}},429,'error'), ({},200,'error')])
async def test_empty_catalog_distinct_from_error(data,status,expected):
    async with FakeProvider([response(data,status)]) as server:
        result = (await post('catalog',server.base_url)).json()
        assert result['status'] == expected and not result['models']


async def test_test_and_completion_share_the_provider_request_without_graph_side_effects(monkeypatch):
    monkeypatch.setattr(orchestrator,'run_agent',lambda *a,**kw: pytest.fail('pure model API entered graph'))
    async with FakeProvider([completion('hello','stop') for _ in range(3)]) as server:
        opts = {'messages':[{'role':'user','content':'hello'}], 'maxOutputTokens':121, 'temperature':.3}
        test = (await post('test',server.base_url,**opts)).json()
        plain = (await post('complete',server.base_url,**opts)).json()
        stream = await post('complete_stream',server.base_url,**opts)
        assert test['ok'] and plain['text']=='hello' and plain['error'] is None and not plain['partial']
        assert stream.text.count('event: done')==1 and 'event: delta' in stream.text
        bodies = [r['json'] for r in server.requests]
        assert len(bodies)==3 and bodies[0]==bodies[1]==bodies[2]
        assert bodies[0]['model']=='chosen' and 'tools' not in bodies[0] and bodies[0]['max_tokens']==121


async def test_429_and_retry_after_are_not_lost_or_retried():
    async with FakeProvider([response({'error':{'code':'RateLimitExceeded.EndpointTPMExceeded','message':'quota exceeded'}},429, **{'retry-after':'9'})]) as server:
        result = (await post('complete',server.base_url)).json()
        assert result['error']['code']=='RATE_LIMIT' and result['error']['httpStatus']==429
        assert result['error']['retryAfterSeconds']==9 and result['error']['upstreamCode']=='RateLimitExceeded.EndpointTPMExceeded'
        assert len(server.requests)==1


@pytest.mark.parametrize('text,finish,error',[(None,'stop','EMPTY_RESPONSE'),('', 'stop','EMPTY_RESPONSE'),('半句',None,'INCOMPLETE')])
async def test_only_visible_completed_text_passes(text,finish,error):
    async with FakeProvider([completion(text,finish)]) as server:
        result = (await post('test',server.base_url)).json()
        assert not result['ok'] and result['error']['code']==error


@pytest.mark.parametrize('path,headers,status',[('test',{},400),('catalog',{'X-Trace-Id':'changed'},400),('catalog',{'X-Actor-User-Id':'8'},403),('catalog',{'X-Model-Name':'changed'},403)])
async def test_operation_identity_and_signed_provider_must_match(path,headers,status):
    async with FakeProvider([]) as server:
        r=await post('catalog',server.base_url,path=path,headers=headers)
        assert r.status_code==status and not server.requests


async def test_environment_key_is_never_borrowed(monkeypatch):
    monkeypatch.setattr(get_settings(),'openai_api_key','environment-only')
    async with FakeProvider([]) as server:
        for channel in (None,'custom'):
            result=(await post('test',server.base_url,channel=channel,key='')).json()
            assert not result['ok'] and result['error']['code']=='CONFIG'
        assert not server.requests


async def test_separate_users_keep_distinct_keys_and_models():
    async with FakeProvider([completion('one','stop')]) as a, FakeProvider([completion('two','stop')]) as b:
        await asyncio.gather(post('complete',a.base_url,actor=7,key='key-one',model='one'),post('complete',b.base_url,actor=8,key='key-two',model='two'))
        assert [(s.requests[0]['headers']['authorization'],s.requests[0]['json']['model']) for s in [a,b]]==[('Bearer key-one','one'),('Bearer key-two','two')]


async def test_embedding_probe_checks_vector_without_storage():
    async with FakeProvider([response({'data':[{'embedding':[.1,.2,.3]}]})]) as server:
        result=(await post('test',server.base_url,purpose='embedding')).json()
        assert result['ok'] and result['embeddingDimension']==3
        assert [r['target'] for r in server.requests]==['/v1/embeddings']


@pytest.mark.parametrize('channel',['custom','anthropic','gemini'])
async def test_inference_does_not_follow_an_upstream_redirect(channel):
    async with FakeProvider([]) as other, FakeProvider([response({},307,location=other.base_url)]) as source:
        result=(await post('test',source.base_url,channel=channel)).json()
        assert not result['ok'] and not other.requests


@pytest.mark.parametrize('body,code', [
    ({'error': {'message': "This model's maximum context length is 8192 tokens", 'code': 'context_length_exceeded'}}, 'CONTEXT_LENGTH'),
    ({'error': {'message': 'input must be a string', 'type': 'invalid_request_error'}}, 'BAD_REQUEST'),
])
async def test_rejected_request_is_classified_like_the_inference_path(body, code):
    async with FakeProvider([response(body, 400)]) as server:
        result = (await post('test', server.base_url, purpose='embedding')).json()
        assert not result['ok']
        assert result['error']['code'] == code and result['error']['httpStatus'] == 400

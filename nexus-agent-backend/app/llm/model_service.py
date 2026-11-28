from __future__ import annotations

import asyncio
import json
import math
import re
from contextlib import aclosing
from datetime import datetime, timezone
from urllib.parse import quote

import httpx

from .base import (
    CONTEXT_HINTS,
    LLMError,
    ModelTransport,
    ProviderConfig,
    UnsafeEndpoint,
    classify_error,
    retry_after,
)
from .factory import build_client, is_anthropic, is_gemini, model_endpoint

_MAX_BYTES = 2 * 1024 * 1024
_MAX_PAGES = 10
_MAX_MODELS = 1000


def explicit_channel(provider: dict | None, *, needs_model: bool) -> ProviderConfig:
    provider = provider or {}
    name = (provider.get('name') or '').strip().lower()
    model = (provider.get('model') or '').strip()
    key = provider.get('apiKey') or ('ollama' if name == 'ollama' else None)
    if not name or not key or (needs_model and not model):
        raise ValueError('The saved channel needs a provider, credential and selected model')
    if len(model) > 512 or any(ord(c) < 32 for c in name + model):
        raise ValueError('Invalid provider or model identifier')
    return ProviderConfig(name, model_endpoint(name, provider.get('baseUrl')), model or None, key)


def diagnostic(error: LLMError, provider: dict | None, stage: str) -> dict:
    cfg = provider or {}
    key = cfg.get('apiKey')
    summary = error.summary.replace(key, '***') if key else error.summary
    upstream = error.upstream_code
    if upstream and (not re.fullmatch(r'[a-zA-Z0-9._:-]{1,160}', upstream) or key and key in upstream):
        upstream = None
    return {'code': error.code, 'summary': summary[:300], 'httpStatus': error.http_status,
            'upstreamCode': upstream, 'retryAfterSeconds': error.retry_after_seconds,
            'provider': cfg.get('name'), 'model': cfg.get('model'), 'stage': stage}


class _RemoteFailure(Exception):
    def __init__(self, error: LLMError):
        self.error = error
        super().__init__(error.code)


async def _json(client: httpx.AsyncClient, method: str, url: str, **kwargs) -> dict:
    async with client.stream(method, url, **kwargs) as r:
        received = bytearray()
        async for piece in r.aiter_bytes(16_384):
            if len(received) + len(piece) > _MAX_BYTES:
                raise _RemoteFailure(LLMError('INVALID_RESPONSE', 'Provider response exceeds size limit', r.status_code))
            received.extend(piece)
        try:
            data = json.loads(received)
        except (ValueError, UnicodeError):
            data = None
        if not r.is_success:
            detail = data.get('error', {}) if isinstance(data, dict) else {}
            code = detail.get('status') or detail.get('code') or detail.get('type') if isinstance(detail, dict) else None
            reason = {401:'AUTH', 403:'PERMISSION', 404:'NOT_FOUND', 429:'RATE_LIMIT', 408:'TIMEOUT', 504:'TIMEOUT'}.get(r.status_code, 'UPSTREAM')
            if r.status_code in (400, 413, 422):  # same split as classify_error makes for the SDK paths
                said = json.dumps(detail, ensure_ascii=False).lower()
                reason = 'CONTEXT_LENGTH' if any(hint in said for hint in CONTEXT_HINTS) else 'BAD_REQUEST'
            raise _RemoteFailure(LLMError(reason, f'Provider returned HTTP {r.status_code}', r.status_code,
                                          str(code) if code else None, retry_after(r.headers.get('retry-after'))))
        if not isinstance(data, dict):
            raise _RemoteFailure(LLMError('INVALID_RESPONSE', 'Provider did not return a JSON object'))
        return data


def _headers(cfg: ProviderConfig) -> dict:
    if is_gemini(cfg.name):
        return {'x-goog-api-key': cfg.api_key}
    headers = {'Authorization': 'Bearer ' + cfg.api_key}
    if is_anthropic(cfg.name):
        headers['anthropic-version'] = '2023-06-01'
    return headers


async def catalog(provider: dict | None) -> dict:
    result = {'status':'error', 'models':[], 'truncated':False, 'fetchedAt':datetime.now(timezone.utc).isoformat(), 'error':None}
    try:
        cfg = explicit_channel(provider, needs_model=False)
        gemini, anthropic = is_gemini(cfg.name), is_anthropic(cfg.name)
        url = cfg.base_url + ('/v1/models' if gemini or anthropic else '/models')
        ids, cursors = set(), set()
        params = {'pageSize': 100} if gemini else {'limit':100} if anthropic else {}
        async with asyncio.timeout(30), httpx.AsyncClient(transport=ModelTransport(), follow_redirects=False, trust_env=False) as client:
            for page in range(_MAX_PAGES):
                data = await _json(client, 'GET', url, headers=_headers(cfg), params=params)
                rows = data.get('models' if gemini else 'data')
                if not isinstance(rows, list):
                    raise _RemoteFailure(LLMError('INVALID_RESPONSE', 'Provider model list is missing'))
                for row in rows:
                    field = 'name' if gemini else 'id'
                    if not isinstance(row, dict) or not isinstance(row.get(field), str) or not row[field].strip():
                        raise _RemoteFailure(LLMError('INVALID_RESPONSE', 'Provider returned a malformed model entry'))
                    model = row[field]
                    if model in ids:
                        continue
                    if len(ids) >= _MAX_MODELS:
                        result['truncated'] = True
                        break
                    ids.add(model)
                    methods = row.get('supportedGenerationMethods') if gemini else None
                    chat = 'generateContent' in methods if isinstance(methods, list) else None
                    embed = 'embedContent' in methods if isinstance(methods, list) else None
                    result['models'].append({'id':model,'chat':chat,'embedding':embed})
                cursor = data.get('nextPageToken') if gemini else data.get('last_id') if data.get('has_more') else None
                if data.get('has_more') and not gemini and not cursor and rows:
                    cursor = rows[-1]['id']
                if not cursor or result['truncated']:
                    break
                if not isinstance(cursor,str) or cursor in cursors or len(cursor)>2048 or page+1 == _MAX_PAGES or len(ids)>=_MAX_MODELS:
                    result['truncated'] = True
                    break
                cursors.add(cursor)
                params['pageToken' if gemini else 'after_id' if anthropic else 'after'] = cursor
        result['status'] = 'ready' if ids else 'empty'
        return result
    except _RemoteFailure as exc:
        error = exc.error
        if error.http_status in (404,405,501):
            result['status'] = 'unsupported'
    except (UnsafeEndpoint, ValueError) as exc:
        error = LLMError('CONFIG', str(exc))
    except (httpx.TimeoutException, TimeoutError):
        error = LLMError('TIMEOUT','Provider model directory timed out')
    except (httpx.RequestError, OSError):
        error = LLMError('NETWORK','Could not reach the provider model directory')
    result.update(models=[], error=diagnostic(error, provider, 'catalog'))
    return result


async def events(provider: dict | None, messages: list[dict], temperature: float, max_tokens: int, timeout: int, stage: str):
    visible = False
    error = None
    finish = None
    usage = {'inputTokens':0, 'outputTokens':0, 'totalTokens':0}
    try:
        cfg = explicit_channel(provider, needs_model=True)
        stream = build_client(cfg).complete(messages, tools=None, temperature=temperature, max_tokens=max_tokens, timeout_sec=timeout)
        async with aclosing(stream):
            async for chunk in stream:
                if chunk.kind == 'text' and chunk.text:
                    visible = visible or bool(chunk.text.strip())
                    yield 'delta', {'text':chunk.text}
                elif chunk.kind == 'usage' and chunk.usage:
                    usage = {'inputTokens':chunk.usage.input_tokens,'outputTokens':chunk.usage.output_tokens,'totalTokens':chunk.usage.total_tokens}
                elif chunk.kind == 'error':
                    error = chunk.error or LLMError('UPSTREAM','Model call failed')
                elif chunk.kind == 'finish':
                    finish = chunk.finish_reason
    except (UnsafeEndpoint, ValueError) as exc:
        error = LLMError('CONFIG', str(exc))
    except Exception as exc:
        error = classify_error(exc, cfg) if 'cfg' in locals() else LLMError('CONFIG','Invalid model channel')
    if not error and finish != 'stop':
        error = LLMError({'length':'OUTPUT_LIMIT','timeout':'TIMEOUT','content_filter':'CONTENT_FILTER'}.get(finish,'INCOMPLETE'),
                         'Model did not complete a visible reply')
    if not error and not visible:
        error = LLMError('EMPTY_RESPONSE','The model returned no visible reply')
    yield 'usage', usage
    if error:
        detail = diagnostic(error, provider, stage)
        yield 'error', {**detail, 'message':detail['summary']}
    yield 'done', {'finishReason':('timeout' if error and error.code=='TIMEOUT' else (finish if finish in ('length','content_filter') else 'error')) if error else 'stop',
                   'partial':bool(error and visible)}


async def collect(provider, messages, temperature, max_tokens, timeout, stage):
    text = []
    result = {'error':None}
    async with aclosing(events(provider, messages, temperature, max_tokens, timeout, stage)) as stream:
        async for name, payload in stream:
            if name=='delta':
                text.append(payload['text'])
            elif name=='done':
                result.update(payload)
            else:
                result[name] = payload
    return {**result, 'text':''.join(text)}


async def test_embedding(provider: dict | None, timeout: int) -> dict:
    try:
        cfg = explicit_channel(provider, needs_model=True)
        if is_anthropic(cfg.name):
            raise _RemoteFailure(LLMError('UNSUPPORTED', 'This channel does not expose embedding inference'))
        gemini = is_gemini(cfg.name)
        if gemini:
            model = cfg.model.removeprefix('models/')
            url = cfg.base_url + '/v1/models/' + quote(model, safe='') + ':embedContent'
            payload = {'content': {'parts':[{'text':'embedding check'}]}}
        else:
            url, payload = cfg.base_url + '/embeddings', {'model':cfg.model,'input':'embedding check'}
        async with asyncio.timeout(timeout), httpx.AsyncClient(transport=ModelTransport(), trust_env=False) as client:
            data = await _json(client, 'POST', url, headers=_headers(cfg), json=payload)
        rows = data.get('data')
        row = data.get('embedding') if gemini else rows[0] if isinstance(rows,list) and rows else None
        vector = row.get('values' if gemini else 'embedding') if isinstance(row,dict) else None
        if not isinstance(vector,list) or not vector or not all(type(n) in (int,float) and math.isfinite(n) for n in vector):
            raise _RemoteFailure(LLMError('INVALID_RESPONSE','Provider did not return a finite embedding vector'))
        return {'ok':True,'embeddingDimension':len(vector),'message':'ok','error':None}
    except _RemoteFailure as exc:
        error = exc.error
    except (UnsafeEndpoint, ValueError) as exc:
        error = LLMError('CONFIG', str(exc))
    except (httpx.TimeoutException, TimeoutError):
        error = LLMError('TIMEOUT','Embedding sample timed out')
    except (httpx.RequestError, OSError):
        error = LLMError('NETWORK','Could not reach the embedding endpoint')
    return {'ok':False, 'message':error.summary,'error':diagnostic(error,provider,'test')}


async def embed(provider: dict | None, inputs: list[str], timeout: int = 15) -> dict:
    """Account-scoped inference. Never falls back to a server/chat credential."""
    try:
        if (not isinstance(inputs, list) or not 1 <= len(inputs) <= 64
                or any(not isinstance(s, str) or not s.strip() or len(s) > 8_000 for s in inputs)
                or sum(len(s.encode('utf-8')) for s in inputs) > 262_144):
            raise ValueError('Embedding input exceeds its bounds')
        cfg = explicit_channel(provider, needs_model=True)
        if is_anthropic(cfg.name):
            raise _RemoteFailure(LLMError('UNSUPPORTED', 'This channel does not expose embedding inference'))
        gemini = is_gemini(cfg.name)
        if gemini:
            model = 'models/' + cfg.model.removeprefix('models/')
            url = cfg.base_url + '/v1/' + quote(model, safe='/') + ':batchEmbedContents'
            payload = {'requests': [{'model': model, 'content': {'parts': [{'text': s}]}} for s in inputs]}
        else:
            url = cfg.base_url + '/embeddings'
            payload = {'model': cfg.model, 'input': inputs, 'encoding_format': 'float'}
        async with asyncio.timeout(max(1, min(60, timeout))), httpx.AsyncClient(
                transport=ModelTransport(), follow_redirects=False, trust_env=False) as client:
            data = await _json(client, 'POST', url, headers=_headers(cfg), json=payload)
        rows = data.get('embeddings' if gemini else 'data')
        if not isinstance(rows, list) or len(rows) != len(inputs):
            raise _RemoteFailure(LLMError('INVALID_RESPONSE', 'Embedding result count does not match the input'))
        vectors = [None] * len(inputs)
        dimension = None
        for position, row in enumerate(rows):
            if not isinstance(row, dict):
                raise _RemoteFailure(LLMError('INVALID_RESPONSE', 'Malformed embedding entry'))
            index = position if gemini else row.get('index', 0 if len(inputs) == 1 else None)
            vector = row.get('values' if gemini else 'embedding')
            if (type(index) is not int or not 0 <= index < len(inputs) or vectors[index] is not None
                    or not isinstance(vector, list) or not 1 <= len(vector) <= 8192
                    or not all(type(n) in (int, float) and math.isfinite(n) for n in vector)
                    or dimension is not None and len(vector) != dimension):
                raise _RemoteFailure(LLMError('INVALID_RESPONSE', 'Provider returned an invalid embedding vector'))
            dimension = len(vector)
            vectors[index] = vector
        return {'ok': True, 'status': 'ready', 'vectors': vectors, 'dimension': dimension, 'error': None}
    except _RemoteFailure as exc:
        error = exc.error
    except (UnsafeEndpoint, ValueError):
        error = LLMError('CONFIG', 'Embedding channel or input is invalid')
    except (httpx.TimeoutException, TimeoutError):
        error = LLMError('TIMEOUT', 'Embedding request timed out')
    except (httpx.RequestError, OSError):
        error = LLMError('NETWORK', 'Could not reach the embedding endpoint')
    except Exception:
        error = LLMError('INVALID_RESPONSE', 'Embedding inference failed')
    return {'ok': False, 'status': 'failed', 'vectors': [], 'error': diagnostic(error, provider, 'embed')}

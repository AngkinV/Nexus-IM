from __future__ import annotations

import time
from contextlib import aclosing
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .llm import model_service
from .security import verify_internal_signature
from .sse import sse_encoder

router = APIRouter(prefix='/v1/models', dependencies=[])


class ModelOperation(BaseModel):
    model_config = ConfigDict(extra='forbid')
    operation: str = Field(min_length=1,max_length=24)
    actorUserId: int = Field(gt=0)
    traceId: str = Field(min_length=1,max_length=128)
    purpose: Literal['chat','embedding']


class Message(BaseModel):
    model_config = ConfigDict(extra='forbid')
    role: Literal['system','user','assistant']
    content: str = Field(max_length=128_000)


class Completion(ModelOperation):
    messages: list[Message] = Field(default_factory=list,max_length=256)
    temperature: float = Field(default=0.2,ge=0,le=2)
    maxOutputTokens: int = Field(default=1024,ge=1,le=8192)
    timeoutSec: int = Field(default=120,ge=1,le=300)

    @model_validator(mode='after')
    def bound_context(self):
        if sum(len(m.content.encode('utf-8')) for m in self.messages)>1_048_576:
            raise ValueError('Model input exceeds 1 MiB')
        return self


def channel(request: ModelOperation, ctx: dict, operation: str):
    if request.actorUserId != ctx['actorUserId']:
        raise HTTPException(403,'Actor mismatch')
    if request.traceId != ctx['traceId'] or request.operation != operation:
        raise HTTPException(400,'Trace or operation mismatch')
    return ctx.get('provider')


def arguments(request: Completion, *, sample: bool = False):
    if request.purpose != 'chat':
        raise HTTPException(400, 'Chat credentials required')
    messages = [m.model_dump() for m in request.messages]
    if not messages and sample:
        messages = [{'role':'user','content':'Reply with one short greeting.'}]
    if not messages:
        raise HTTPException(422,'At least one message is required')
    return messages, request.temperature, request.maxOutputTokens, request.timeoutSec


@router.post('/catalog')
async def catalog(request: ModelOperation, ctx: dict = Depends(verify_internal_signature)):
    return await model_service.catalog(channel(request,ctx,'catalog'))


@router.post('/test')
async def test_channel(request: Completion, ctx: dict = Depends(verify_internal_signature)):
    cfg = channel(request,ctx,'test')
    start = time.monotonic()
    if request.purpose=='embedding':
        result = await model_service.test_embedding(cfg, request.timeoutSec)
    else:
        reply = await model_service.collect(cfg, *arguments(request,sample=True), 'test')
        error = reply['error']
        result = {**reply, 'ok':error is None, 'message':error['summary'] if error else 'ok'}
    return {**result,'latencyMs':round((time.monotonic()-start)*1000)}


@router.post('/complete')
async def complete(request: Completion, ctx: dict = Depends(verify_internal_signature)):
    cfg = channel(request,ctx,'complete')
    return await model_service.collect(cfg, *arguments(request), 'complete')


@router.post('/complete/stream')
async def stream(request: Completion, ctx: dict = Depends(verify_internal_signature)):
    cfg = channel(request,ctx,'complete_stream')
    args = arguments(request)

    async def frames():
        encode = sse_encoder()
        async with aclosing(model_service.events(cfg,*args,'complete_stream')) as source:
            async for name, payload in source:
                yield encode(name,payload)

    return StreamingResponse(frames(), media_type='text/event-stream', headers={'Cache-Control':'no-store','X-Accel-Buffering':'no'})


class Embedding(ModelOperation):
    inputs: list[str] = Field(min_length=1, max_length=64)
    timeoutSec: int = Field(default=15, ge=1, le=60)

    @model_validator(mode='after')
    def bound_embedding(self):
        if (any(not s.strip() or len(s) > 8_000 for s in self.inputs)
                or sum(len(s.encode('utf-8')) for s in self.inputs) > 262_144):
            raise ValueError('Embedding input exceeds its bounds')
        return self


async def embed(request: Embedding, ctx: dict = Depends(verify_internal_signature)):
    cfg = channel(request, ctx, 'embed')
    if request.purpose != 'embedding':
        raise HTTPException(400, 'Embedding credentials required')
    return await model_service.embed(cfg, request.inputs, request.timeoutSec)


router.add_api_route('/embed', embed, methods=['POST'])

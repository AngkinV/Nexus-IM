"""Final review counterexamples; authored only, never run in this window."""
import copy
import pytest
from app.llm.base import LLMChunk, LLMToolCall
from app.subagents.contracts import NotReady
from app.subagents.graph import build_graph, dispatch
from app.subagents.recovery import invalidate, outcome, retry_failed
from app.subagents.worker import step
from .test_contracts import Owners, state, ports, drive


@pytest.mark.asyncio
@pytest.mark.parametrize('entry', ['dispatch', 'step', 'invalidate', 'retry', 'graph'])
async def test_sealed_results_reject_mutating_entries_without_changes(entry):
    o, s = Owners(), state()
    await drive(s, o)
    s['output'] = outcome(s)
    s['sealed'] = True  # Simulates T1 injecting a verified durable result receipt.
    s['active'] = 'reply'
    before = copy.deepcopy(s)
    events, snapshots, requests = len(o.events), len(o.snapshots), len(o.requests)
    o.allowed = False  # Reject this delivery; do not rewrite its producer result.
    with pytest.raises(NotReady):
        if entry == 'dispatch':
            await dispatch(s, ports(o))
        elif entry == 'step':
            await step(s, ports(o), 'model')
        elif entry == 'invalidate':
            invalidate(s, ['reply'], context_changed=True)
        elif entry == 'retry':
            await retry_failed(s, ['reply'], ports(o))
        else:
            await build_graph(ports(o)).compile().ainvoke(s)
    assert s == before
    assert len(o.events) == events and len(o.snapshots) == snapshots
    assert len(o.requests) == requests


@pytest.mark.asyncio
@pytest.mark.parametrize('finish', ['stop', 'tool_calls'])
@pytest.mark.parametrize('local_transcript', [False, True])
async def test_retry_checks_original_response_sources_before_apply(finish, local_transcript):
    class Current(Owners):
        async def select(self, facts, s, n, scope):
            return dict(factIds=[], sourceIds=[], content='authorized-empty-view')
    o, s = Current(), state()
    n = s['nodes']['reply']
    n.update(status='interrupted', phase='model',
             context=dict(input=s['input'], inputVersion=s['inputVersion'], scope=s['scope']))
    n['binding'] = o.binding(s, n, s['scope'])
    n['transcript'] = [{'role': 'user', 'content': 'current input'}] if local_transcript else []
    n['modelPending'] = dict(callId='committed-response', binding=n['binding'], ordinal=0,
                             end=False, consume=False, streamClosed=False,
                             factIds=[], sourceIds=[])
    s['factsPrepared'] = True
    o.ledger['committed-response'] = dict(status='committed', response=dict(
        text='WITHDRAWN-PRIVATE-ANSWER' if finish == 'stop' else '',
        calls=[] if finish == 'stop' else [dict(id='tool', name='read', arguments={}, signature='sig')],
        finish=finish, error=False, streamClosed=True,
        factIds=['withdrawn-fact'], sourceIds=['withdrawn-source']))
    before = copy.deepcopy(s)
    with pytest.raises(PermissionError):
        await retry_failed(s, ['reply'], ports(o))
    assert s == before
    assert o.events == [] and o.snapshots == [] and o.requests == [] and o.executions == 0
    assert n['modelPending']['consume'] is False and n['version'] == 0


@pytest.mark.asyncio
@pytest.mark.parametrize('with_sources', [False, True])
async def test_claim_loser_reuse_requires_original_source_receipt(with_sources):
    class Winner(Owners):
        async def commit_tool(self, cid, provider_id, request, invoke):
            # A competing winner already submitted the readonly call. No invoke.
            result = dict(modelContent='WINNER-RESULT', status='SUCCESS', reused=True)
            if with_sources:
                result.update(sourceAttemptId='producer-attempt', sourceNodeId='producer-node')
            self.ledger[cid] = dict(status='committed', result=copy.deepcopy(result))
            return result
    o = Winner([[LLMChunk(kind='tool_call', tool_call=LLMToolCall('p', 'read', {})),
                 LLMChunk(kind='finish', finish_reason='tool_calls')]])
    s = state()
    await dispatch(s, ports(o))
    await step(s, ports(o), 'prepare')
    await step(s, ports(o), 'model')
    await step(s, ports(o), 'tool')
    assert o.executions == 0
    frames = [e for kind, e in o.events if kind == 'tool_result']
    if with_sources:
        assert len(frames) == 1 and frames[0]['reused'] is True
        assert frames[0]['status'] == 'SUCCESS'
        assert frames[0]['sourceAttemptId'] == 'producer-attempt'
        assert frames[0]['sourceNodeId'] == 'producer-node'
    else:
        assert frames == [] and s['nodes']['reply']['status'] == 'limited'
        assert not any(m.get('role') == 'tool' for m in s['nodes']['reply']['transcript'])

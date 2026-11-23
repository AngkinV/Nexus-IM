"""Additional unexecuted failure/replay regressions using test-only owners."""
import copy
import pytest
from app.llm.base import LLMChunk
from app.subagents.worker import step
from app.subagents.graph import dispatch
from app.subagents.recovery import invalidate
from .test_contracts import Owners, ports, state, drive


@pytest.mark.asyncio
async def test_committed_model_replay_no_second_request():
    o, s = Owners(), state()
    await dispatch(s, ports(o)); await step(s, ports(o), 'prepare')
    before = copy.deepcopy(s)
    await step(s, ports(o), 'model')
    await step(before, ports(o), 'model')
    assert len(o.requests) == o.permits == 1
    assert before['nodes']['reply']['status'] == 'succeeded'
    assert before['nodes']['reply']['version'] == s['nodes']['reply']['version']


@pytest.mark.asyncio
async def test_review_synth_are_real_budgeted_nodes():
    o = Owners([[LLMChunk(kind='text', text='fixture-result'), LLMChunk(kind='finish', finish_reason='stop')] for _ in range(3)])
    s = state(['reply', 'review', 'synth'], {'review': ['reply'], 'synth': ['review']})
    assert (await drive(s, o))['status'] == 'succeeded'
    assert len(o.requests) == o.permits == 3 and o.prepares == 1
    assert s['nodes']['review']['parentVersions'] == {'reply': 1}
    assert s['nodes']['synth']['parentVersions'] == {'review': 1}
    assert [e['nodeId'] for k, e in o.events if k == 'agent_output'] == ['reply', 'review', 'synth']


@pytest.mark.asyncio
async def test_failed_request_is_ledgered_usage_unknown():
    class Broken(Owners):
        async def stream(self, transcript, schemas, options):
            try:
                raise RuntimeError('private provider secret')
                yield
            finally:
                self.stream_closed += 1
    o, s = Broken(), state()
    result = await drive(s, o)
    assert result['status'] == 'failed' and o.permits == 1
    assert o.usage == [None] and o.stream_closed == 1
    assert 'private provider secret' not in repr(o.events)


@pytest.mark.asyncio
async def test_obsolete_unknown_blocks_but_absent_can_be_abandoned():
    o, s = Owners(), state()
    n = s['nodes']['reply']
    n['pending'] = [{'callId': 'old', 'providerCallId': 'provider', 'name': 'read',
                     'status': 'pending', 'obsolete': False}]
    invalidate(s, ['reply'])
    s['active'] = 'reply'
    o.ledger['old'] = {'status': 'unknown'}
    await step(s, ports(o), 'prepare')
    assert n['status'] == 'interrupted' and o.executions == 0
    invalidate(s, ['reply'])
    s['active'] = 'reply'
    del o.ledger['old']
    await step(s, ports(o), 'prepare')
    assert n['pending'][0]['status'] == 'abandoned' and o.executions == 0
    await step(s, ports(o), 'prepare')
    assert n['phase'] == 'model' and len(n['transcript']) == 1


@pytest.mark.asyncio
async def test_authorization_change_rechecks_success_no_stale_output():
    o, s = Owners(), state()
    await drive(s, o)
    o.allowed = False
    result = await drive(s, o)
    assert result['status'] == 'failed' and result['results'] == {}
    assert s['facts'] == {} and s['nodes']['reply']['transcript'] == []


@pytest.mark.asyncio
async def test_upstream_version_change_keeps_unrelated_success():
    o = Owners([[LLMChunk(kind='text', text='fixture-result'), LLMChunk(kind='finish', finish_reason='stop')] for _ in range(3)])
    s = state(['summary', 'todo', 'review'], {'review': ['summary']})
    await drive(s, o)
    unrelated = copy.deepcopy(s['nodes']['todo'])
    s['nodes']['summary']['version'] += 1
    await dispatch(s, ports(o))
    assert s['nodes']['review']['status'] == 'queued'
    assert s['nodes']['review']['transcript'] == []
    assert s['nodes']['todo'] == unrelated
    assert s['facts'] == {'refs': ['f1'], 'sources': ['s1']}

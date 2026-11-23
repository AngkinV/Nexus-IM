"""Review counterexamples. Test-only data; none collected or executed here."""
import asyncio
import copy
from contextlib import asynccontextmanager
import pytest
from app.llm.base import LLMChunk, LLMToolCall
from app.subagents.worker import cancel, step
from app.subagents.graph import dispatch
from app.subagents.recovery import outcome, retry_failed
from .test_contracts import Owners, state, ports, drive


def answer(text):
    return [LLMChunk(kind='text', text=text), LLMChunk(kind='finish', finish_reason='stop')]


def tools(*ids):
    return [LLMChunk(kind='tool_call', tool_call=LLMToolCall(cid, 'read', {}, 'sig-' + cid))
            for cid in ids] + [LLMChunk(kind='finish', finish_reason='tool_calls')]


@pytest.mark.asyncio
@pytest.mark.parametrize('finished', [False, True])
async def test_cancel_storage_failure_never_publishes_completion(finished):
    class Broken(Owners):
        async def persist(self, s):
            raise RuntimeError('storage failure')
    o, s = Broken(), state()
    if finished:
        s['nodes']['reply'].update(status='succeeded', text='existing-result', version=1)
    await cancel(s, ports(o))
    if finished:
        assert o.closed == 0 and s['cancelled'] is False
        assert outcome(s)['status'] == 'succeeded'
        assert s['nodes']['reply']['text'] == 'existing-result'
        assert not any(e.get('status') == 'cancelled' for _, e in o.events)
        return
    assert o.closed == 1 and s['cancelled'] is False
    assert outcome(s)['status'] == 'stop_requested'
    assert s['cancelError'] == 'CANCEL_PERSIST_FAILED'
    assert not any(e.get('status') == 'cancelled' for _, e in o.events)


@pytest.mark.asyncio
@pytest.mark.parametrize('ledger_status', ['unknown', 'inflight', 'committed_unclosed', 'committed_missing'])
async def test_old_optimistic_snapshot_is_not_close_confirmation(ledger_status):
    o, s = Owners(), state()
    n = s['nodes']['reply']
    n['modelPending'] = dict(callId='old-model', binding='old-binding', ordinal=0,
                             end=ledger_status == 'committed_unclosed', consume=False,
                             streamClosed=True)
    if ledger_status.startswith('committed'):
        response = dict(text='old-result', calls=[], finish='stop', error=False)
        if ledger_status == 'committed_unclosed':
            response['streamClosed'] = False
        o.ledger['old-model'] = dict(status='committed', response=response)
    else:
        o.ledger['old-model'] = dict(status=ledger_status)
    await cancel(s, ports(o))
    assert o.closed == 1 and n['status'] == 'stop_requested'
    assert not s['cancelled'] and not o.requests
    assert not any(e.get('status') == 'cancelled' for _, e in o.events)
    assert o.snapshots[-1]['cancelled'] is False


@pytest.mark.asyncio
async def test_pending_close_is_persisted_unconfirmed_before_model_begin():
    class Inspect(Owners):
        async def model_begin(self, cid):
            pending = self.snapshots[-1]['nodes']['reply']['modelPending']
            assert pending['callId'] == cid
            assert pending['streamClosed'] is False and pending['end'] is False
            await super().model_begin(cid)
    o, s = Inspect(), state()
    assert (await drive(s, o))['status'] == 'succeeded'
    assert len(o.requests) == 1
    pending = s['nodes']['reply']['modelPending']
    assert o.ledger[pending['callId']]['response']['streamClosed'] is True


@pytest.mark.asyncio
@pytest.mark.parametrize('finish', ['tool_calls', 'stop'])
async def test_valid_committed_response_is_replayed_not_discarded_on_retry(finish):
    o, s = Owners([answer('AFTER-RESTORED-TOOL')]), state()
    await dispatch(s, ports(o))
    await step(s, ports(o), 'prepare')
    n = s['nodes']['reply']
    cid = o.call_id(s['identity'], n['nodeId'], n['binding'], 0, 'model')
    n['callIds'].append(cid)
    n.update(status='interrupted', phase='model', modelPending=dict(
        callId=cid, binding=n['binding'], ordinal=0, end=False, consume=False, streamClosed=False))
    restored_calls = [dict(id='restored-call', name='read', arguments={}, signature='restored-signature')]
    o.ledger[cid] = dict(status='committed', response=dict(
        text='RESTORED-ANSWER' if finish == 'stop' else '',
        calls=[] if finish == 'stop' else restored_calls, finish=finish,
        error=False, streamClosed=True))
    await retry_failed(s, ['reply'], ports(o))
    if finish == 'tool_calls':
        assert n['pending'][0]['signature'] == 'restored-signature'
        assert n['modelPending']['consume'] is True
    assert (await drive(s, o))['status'] == 'succeeded'
    if finish == 'stop':
        assert o.requests == [] and o.executions == 0
        assert n['text'] == 'RESTORED-ANSWER'
    else:
        assert len(o.requests) == 1 and o.executions == 1
        assert any(m.get('role') == 'tool' for m in o.requests[0])
    version = n['version']
    requests = len(o.requests)
    await drive(s, o)
    assert n['version'] == version and len(o.requests) == requests
    assert n['callIds'].count(cid) == 1


@pytest.mark.asyncio
async def test_related_retry_preserves_committed_targets_and_rebases_child():
    o = Owners([tools('reply-read'), [LLMChunk(kind='finish', finish_reason='error')],
                answer('UNRELATED-SUMMARY'), tools('review-read'),
                [LLMChunk(kind='finish', finish_reason='error')]])
    s = state(['reply', 'summary', 'review'], {'review': ['reply', 'summary']})
    await drive(s, o)
    assert s['nodes']['reply']['status'] == s['nodes']['review']['status'] == 'failed'
    assert o.executions == 2
    transcripts = {key: copy.deepcopy(s['nodes'][key]['transcript']) for key in ('reply', 'review')}
    unrelated = copy.deepcopy(s['nodes']['summary'])
    o.turns = [answer('NEW-REPLY'), answer('NEW-REVIEW')]
    assert await retry_failed(s, ['reply', 'review'], ports(o)) == {'reply', 'review'}
    assert all(s['nodes'][key]['transcript'] == transcripts[key] for key in transcripts)
    assert (await drive(s, o))['status'] == 'succeeded'
    assert o.executions == 2 and len(o.requests) == 7
    assert s['nodes']['summary'] == unrelated
    assert 'NEW-REPLY' in repr(o.requests[-1])
    assert any(m.get('role') == 'tool' and m['content'] == 'safe' for m in o.requests[-1])
    assert 'missing:reply' not in repr(o.requests[-1])


@pytest.mark.asyncio
@pytest.mark.parametrize('change', [
    {'messages': 'not-a-list'}, {'messages': [{}]},
    {'max_tokens': None}, {'max_tokens': True}, {'max_tokens': 0}, {'max_tokens': -1},
    {'timeout_sec': None}, {'timeout_sec': 0}, {'timeout_sec': float('nan')},
    {'temperature': None}, {'temperature': float('inf')}, {'temperature': 3},
])
async def test_invalid_prepared_contract_never_begins_model(change):
    class Invalid(Owners):
        async def model_context(self, s, n, scope, schemas):
            prepared = await super().model_context(s, n, scope, schemas)
            prepared.update(change)
            return prepared
    o, s = Invalid(), state()
    assert (await drive(s, o))['status'] == 'failed'
    assert o.requests == [] and o.permits == 0 and o.ledger == {}
    assert s['nodes']['reply']['errorCode'] == 'CAPABILITY_NOT_READY'


@pytest.mark.asyncio
async def test_blank_checkpoint_parent_is_missing_from_partial_review():
    o, s = Owners([answer('PARTIAL-REVIEW')]), state(
        ['summary', 'todo', 'review'], {'review': ['summary', 'todo']})
    s['facts'] = {'refs': ['f1'], 'sources': ['s1']}
    for key, text in (('summary', '  '), ('todo', 'VALID-TODO')):
        n = s['nodes'][key]
        n.update(status='succeeded', text=text, version=1,
                 binding=o.binding(s, n, s['scope']),
                 context=dict(input=s['input'], inputVersion=s['inputVersion'], scope=s['scope']))
    assert (await drive(s, o))['status'] == 'partial'
    n = s['nodes']['review']
    assert n['status'] == 'partial' and 'summary' in n['missingNodeIds']
    assert n['missingReasons']['summary'] == 'EMPTY_RESPONSE'
    assert len(o.requests) == 1 and 'VALID-TODO' in repr(o.requests[0])


@pytest.mark.asyncio
async def test_model_namespace_does_not_collide_with_provider_tool_id():
    class Inspect(Owners):
        async def execute(self, identity, scope, cid, call):
            assert call['role'] == call['agentId'] == call['nodeId'] == 'reply'
            return await super().execute(identity, scope, cid, call)
    o, s = Inspect([tools('model'), answer('FINAL')]), state()
    assert (await drive(s, o))['status'] == 'succeeded'
    n = s['nodes']['reply']
    assert o.executions == 1 and len(o.requests) == 2
    assert n['pending'][0]['providerCallId'] == 'model'
    assert n['pending'][0]['callId'] != n['callIds'][0]


@pytest.mark.asyncio
async def test_multiple_tools_mid_batch_restore_reuses_committed_call():
    o, s = Owners([tools('p1', 'p2'), answer('FINAL')]), state()
    await dispatch(s, ports(o))
    await step(s, ports(o), 'prepare')
    await step(s, ports(o), 'model')
    before_commit = copy.deepcopy(s)
    await step(s, ports(o), 'tool')
    assert o.executions == 1
    await step(before_commit, ports(o), 'tool')
    assert o.executions == 1
    await step(before_commit, ports(o), 'tool')
    assert o.executions == 2
    assert (await drive(before_commit, o))['status'] == 'succeeded'
    assert o.executions == 2 and len(o.requests) == 2
    assert [m['tool_call_id'] for m in o.requests[-1] if m['role'] == 'tool'] == ['p1', 'p2']
    assert [c['signature'] for m in o.requests[-1] for c in m.get('tool_calls', [])] == ['sig-p1', 'sig-p2']


@pytest.mark.asyncio
async def test_cancellation_during_permit_does_not_begin_model():
    class Queued(Owners):
        @asynccontextmanager
        async def permit(self, identity, node_id, cid):
            self.permits += 1
            self.stop = True
            yield
    o, s = Queued(), state()
    await dispatch(s, ports(o))
    await step(s, ports(o), 'prepare')
    with pytest.raises(asyncio.CancelledError):
        await step(s, ports(o), 'model')
    assert o.requests == [] and o.ledger == {} and o.usage == []
    assert s['cancelled'] is True and s['nodes']['reply']['modelOrdinal'] == 0


@pytest.mark.asyncio
async def test_invalid_second_retry_target_does_not_mutate_first():
    o, s = Owners(), state(['reply', 'summary'])
    n = s['nodes']['reply']
    n.update(status='failed', binding=o.binding(s, n, s['scope']))
    s['nodes']['summary'].update(status='succeeded', text='COMPLETE', version=1)
    before = copy.deepcopy(s)
    with pytest.raises(ValueError):
        await retry_failed(s, ['reply', 'summary'], ports(o))
    assert s == before and not o.requests


@pytest.mark.asyncio
async def test_confirmed_cancellation_replay_does_not_close_again():
    o, s = Owners(), state()
    await cancel(s, ports(o))
    events, snapshots = len(o.events), len(o.snapshots)
    await cancel(s, ports(o))
    assert o.closed == 1 and len(o.events) == events and len(o.snapshots) == snapshots
    assert outcome(s)['status'] == 'cancelled'


@pytest.mark.asyncio
async def test_committed_model_replay_rechecks_withdrawn_facts():
    class Revoked(Owners):
        withdrawn = False
        async def select(self, facts, s, n, scope):
            if self.withdrawn:
                return dict(factIds=[], sourceIds=[], content='withdrawn')
            return await super().select(facts, s, n, scope)
    o, s = Revoked([answer('PRIVATE-RESULT')]), state()
    await drive(s, o)
    saved = next(copy.deepcopy(snapshot) for snapshot in reversed(o.snapshots)
                 if snapshot['nodes']['reply'].get('modelPending') and
                 snapshot['nodes']['reply']['modelPending']['end'] and
                 not snapshot['nodes']['reply']['modelPending']['consume'])
    o.withdrawn = True
    before_events = len(o.events)
    await step(saved, ports(o), 'model')
    assert len(o.requests) == 1 and saved['nodes']['reply']['status'] == 'limited'
    assert not any(e.get('status') == 'succeeded' for _, e in o.events[before_events:])
    assert 'PRIVATE-RESULT' not in repr(o.events[before_events:])


@pytest.mark.asyncio
async def test_committed_model_replay_rechecks_cached_tool_permission():
    class Revoked(Owners):
        tool_enabled = True
        async def authorize(self, identity, role, scope, call):
            return self.tool_enabled and await super().authorize(identity, role, scope, call)
    o, s = Revoked([tools('p'), answer('PRIVATE-TOOL-RESULT')]), state()
    await drive(s, o)
    saved = next(copy.deepcopy(snapshot) for snapshot in reversed(o.snapshots)
                 if snapshot['nodes']['reply'].get('modelPending') and
                 snapshot['nodes']['reply']['modelPending']['ordinal'] == 1 and
                 snapshot['nodes']['reply']['modelPending']['end'] and
                 not snapshot['nodes']['reply']['modelPending']['consume'])
    o.tool_enabled = False
    before_events = len(o.events)
    await step(saved, ports(o), 'model')
    assert len(o.requests) == 2 and o.executions == 1
    assert saved['nodes']['reply']['status'] == 'limited'
    assert 'PRIVATE-TOOL-RESULT' not in repr(o.events[before_events:])


@pytest.mark.asyncio
@pytest.mark.parametrize(('reported', 'expected'), [('FAILED', 'failed'), (None, 'unknown')])
async def test_tool_business_status_is_not_inferred_from_commit(reported, expected):
    class Reported(Owners):
        async def execute(self, identity, scope, cid, call):
            result = await super().execute(identity, scope, cid, call)
            if reported is not None:
                result['status'] = reported
            return result
    o, s = Reported([tools('p'), answer('FINAL')]), state()
    await drive(s, o)
    frame = next(e for kind, e in o.events if kind == 'tool_result')
    assert frame['commitStatus'] == 'committed' and frame['status'] == expected


@pytest.mark.asyncio
async def test_empty_shared_facts_are_prepared_once():
    class Empty(Owners):
        async def prepare(self, s, n, scope):
            self.prepares += 1
            return {}
        async def select(self, facts, s, n, scope):
            return dict(factIds=[], sourceIds=[], content=s['input'] + n['role'] + ' empty')
    o, s = Empty([answer('NO-FACTS-REPLY'), answer('NO-TODOS')]), state(['reply', 'todo'])
    assert (await drive(s, o))['status'] == 'succeeded'
    assert o.prepares == 1 and s['facts'] == {} and s['factsPrepared'] is True


@pytest.mark.asyncio
async def test_empty_provider_deltas_do_not_create_reasoning_or_tokens():
    o, s = Owners([[LLMChunk(kind='reasoning', text=None), LLMChunk(kind='text', text=''),
                    *answer('ACTUAL-ANSWER')]]), state()
    assert (await drive(s, o))['status'] == 'succeeded'
    assert not any(kind == 'reasoning' for kind, _ in o.events)
    assert all(frame['text'] for kind, frame in o.events if kind == 'token')

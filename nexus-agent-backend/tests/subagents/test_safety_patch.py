"""Static-authored regressions; NOT executed or collected."""
import asyncio
import copy
import pytest
from app.llm.base import LLMChunk, LLMToolCall
from app.subagents.worker import cancel, step
from app.subagents.recovery import outcome, retry_failed, invalidate
from app.subagents.graph import dispatch
from .test_contracts import Owners, state, ports, drive

@pytest.mark.asyncio
async def test_multiple_tools_prepared_roundtrips_and_terminal_fields():
    class Bounded(Owners):
        async def model_context(self, s, n, scope, schemas):
            messages = copy.deepcopy(n['transcript'])
            messages[0]['content'] = 'bounded-user'
            self.last_prepared = messages
            return dict(messages=messages, max_tokens=64, timeout_sec=10, temperature=0)
    o = Bounded([[LLMChunk(kind='tool_call', tool_call=LLMToolCall('p1', 'read', {}, 'sig1')),
                  LLMChunk(kind='tool_call', tool_call=LLMToolCall('p2', 'read', {}, 'sig2')),
                  LLMChunk(kind='finish', finish_reason='tool_calls')], answer('FINAL')])
    s = state()
    s['input'] = 'oversize' * 10000
    await drive(s, o)
    assert o.executions == 2 and o.requests[-1] == o.last_prepared
    assert o.requests[-1][0]['content'] == 'bounded-user'
    assert [m['tool_call_id'] for m in o.requests[-1] if m['role'] == 'tool'] == ['p1', 'p2']
    assert [c['signature'] for m in o.requests[-1] for c in m.get('tool_calls', [])] == ['sig1', 'sig2']
    output = [e for k, e in o.events if k == 'agent_output'][-1]
    assert {'status', 'finishReason', 'resultVersion', 'factIds', 'sourceIds', 'callIds', 'missingNodeIds'} <= output.keys()



def answer(text):
    return [LLMChunk(kind='text', text=text), LLMChunk(kind='finish', finish_reason='stop')]


@pytest.mark.asyncio
@pytest.mark.parametrize('failure', ['close', 'timeout', 'emit_first', 'emit_middle', 'persist'])
async def test_cancel_release_independent_of_delivery(failure):
    class Broken(Owners):
        async def emit(self, kind, payload):
            if failure == 'emit_first' or (failure == 'emit_middle' and self.events):
                raise RuntimeError('transport')
            await super().emit(kind, payload)
        async def persist(self, s):
            if failure == 'persist':
                raise RuntimeError('storage')
            await super().persist(s)
        async def close(self, identity, ids):
            await super().close(identity, ids)
            if failure == 'close':
                raise RuntimeError('private')
            if failure == 'timeout':
                raise TimeoutError()
    o, s = Broken(), state(['reply', 'todo'])
    await cancel(s, ports(o))
    assert o.closed == 1 and set(o.closed_ids) == {'reply', 'todo'}
    expected = 'stop_requested' if failure in ('close', 'timeout', 'persist') else 'cancelled'
    assert all(n['status'] == expected for n in s['nodes'].values())
    assert outcome(s)['status'] == expected
    if failure == 'persist':
        assert s['cancelled'] is False
        assert not any(e.get('status') == 'cancelled' for _, e in o.events)


@pytest.mark.asyncio
async def test_event_failure_does_not_fail_model():
    class Broken(Owners):
        async def emit(self, *args):
            raise RuntimeError('transport')
    o, s = Broken(), state()
    assert (await drive(s, o))['status'] == 'succeeded'


@pytest.mark.asyncio
async def test_cancel_committed_explicit_retry_one_new_request():
    class Cancelling(Owners):
        async def stream(self, messages, schemas, options):
            if not self.requests:
                self.requests.append(copy.deepcopy(messages))
                self.stop = True
                try:
                    yield LLMChunk(kind='text', text='cancelled fragment')
                finally:
                    self.stream_closed += 1
            else:
                async for chunk in super().stream(messages, schemas, options):
                    yield chunk
    o, s = Cancelling([answer('retry-result')]), state()
    await dispatch(s, ports(o)); await step(s, ports(o), 'prepare')
    with pytest.raises(asyncio.CancelledError):
        await step(s, ports(o), 'model')
    assert s['nodes']['reply']['modelOrdinal'] == 1
    o.stop = False
    await retry_failed(s, ['reply'], ports(o))
    assert (await drive(s, o))['status'] == 'succeeded'
    assert len(o.requests) == 2 and s['nodes']['reply']['modelOrdinal'] == 2


@pytest.mark.asyncio
@pytest.mark.parametrize('ledger_status', ['unknown', 'inflight'])
async def test_old_model_blocks_changed_input(ledger_status):
    o, s = Owners(), state()
    await dispatch(s, ports(o)); await step(s, ports(o), 'prepare')
    n = s['nodes']['reply']
    n['modelPending'] = dict(callId='old', binding=n['binding'], ordinal=0,
                             end=False, consume=False, streamClosed=True)
    o.ledger['old'] = {'status': ledger_status}
    s['inputVersion'] = 'v2'
    invalidate(s, ['reply'], context_changed=True)
    s['active'] = 'reply'
    await step(s, ports(o), 'prepare')
    assert n['status'] == 'interrupted' and not o.requests


@pytest.mark.asyncio
async def test_tool_commit_then_model_failure_local_retry_preserves_result():
    o = Owners([[LLMChunk(kind='tool_call', tool_call=LLMToolCall('p', 'read', {}, 'sig')),
                 LLMChunk(kind='finish', finish_reason='tool_calls')],
                [LLMChunk(kind='finish', finish_reason='error')], answer('unrelated')])
    s = state(['reply', 'todo'])
    await drive(s, o)
    unrelated = copy.deepcopy(s['nodes']['todo'])
    assert o.executions == 1
    o.turns = [answer('retried')]
    await retry_failed(s, ['reply'], ports(o))
    await drive(s, o)
    assert o.executions == 1 and s['nodes']['todo'] == unrelated
    assert any(m.get('role') == 'tool' and m['content'] == 'safe' for m in o.requests[-1])


@pytest.mark.asyncio
async def test_actual_request_uses_prepared_bounded_messages():
    class Bounded(Owners):
        async def model_context(self, s, n, scope, schemas):
            self.prepared = [dict(role='user', content='bounded')]
            return dict(messages=self.prepared, max_tokens=32, timeout_sec=5, temperature=0)
        async def stream(self, messages, schemas, options):
            assert options == dict(max_tokens=32, timeout_sec=5, temperature=0)
            async for chunk in super().stream(messages, schemas, options):
                yield chunk
    o, s = Bounded(), state()
    s['input'] = 'long' * 10000
    await drive(s, o)
    assert o.requests == [o.prepared]
    assert s['nodes']['reply']['transcript'][0]['content'] != 'bounded'


@pytest.mark.asyncio
async def test_preparation_denial_zero_model_requests():
    class Denied(Owners):
        async def model_context(self, *args):
            return {}
    o, s = Denied(), state()
    assert (await drive(s, o))['status'] == 'failed'
    assert not o.requests and o.permits == 0


@pytest.mark.asyncio
async def test_empty_stop_is_not_success():
    o, s = Owners([[LLMChunk(kind='finish', finish_reason='stop')]]), state()
    assert (await drive(s, o))['status'] == 'failed'
    assert s['nodes']['reply']['errorCode'] == 'EMPTY_RESPONSE' and o.usage == [None]
    assert not any(k == 'agent_output' and e['status'] == 'succeeded' for k, e in o.events)


@pytest.mark.asyncio
async def test_review_synth_consume_feature_parent_text():
    o = Owners([answer('REPLY-FEATURE'), answer('REVIEW-FEATURE'), answer('SYNTH-FEATURE')])
    s = state(['reply', 'review', 'synth'], {'review': ['reply'], 'synth': ['review']})
    await drive(s, o)
    assert 'REPLY-FEATURE' in repr(o.requests[1])
    assert 'REVIEW-FEATURE' in repr(o.requests[2])
    invalidate(s, ['reply'])
    o.turns = [answer('NEW-REPLY'), answer('NEW-REVIEW'), answer('NEW-SYNTH')]
    await drive(s, o)
    assert 'REPLY-FEATURE' not in repr(o.requests[4])
    assert 'REVIEW-FEATURE' not in repr(o.requests[5])


@pytest.mark.asyncio
@pytest.mark.parametrize('usable', [True, False])
async def test_available_parent_policy_partial_or_skip(usable):
    failure = [LLMChunk(kind='finish', finish_reason='error')]
    o = Owners([answer('SUMMARY-FEATURE') if usable else failure, failure,
                answer('REVIEW-PARTIAL'), answer('SYNTH-PARTIAL')])
    s = state(['summary', 'todo', 'review', 'synth'],
              {'review': ['summary', 'todo'], 'synth': ['review']})
    result = await drive(s, o)
    if usable:
        assert result['status'] == 'partial'
        assert s['nodes']['review']['status'] == s['nodes']['synth']['status'] == 'partial'
        assert 'SUMMARY-FEATURE' in repr(o.requests[2]) and 'todo' in repr(o.requests[2])
        assert 'REVIEW-PARTIAL' in repr(o.requests[3]) and 'todo' in repr(o.requests[3])
    else:
        assert len(o.requests) == 2
        assert s['nodes']['review']['status'] == s['nodes']['synth']['status'] == 'skipped'

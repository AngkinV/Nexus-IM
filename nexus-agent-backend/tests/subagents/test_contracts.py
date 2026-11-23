"""Unexecuted contract regressions. All substitutes live exclusively in tests."""
import asyncio
import copy
import json
from contextlib import asynccontextmanager
import pytest
from app.llm.base import LLMChunk, LLMToolCall
from app.subagents.contracts import Ports, NotReady
from app.subagents.planning import plan
from app.subagents.recovery import invalidate, outcome
from app.subagents.graph import build_graph, dispatch
from app.subagents.worker import step


from types import SimpleNamespace
from app.subagents.worker import scope_identity


class TestBudget:
    """TEST ONLY legacy regression harness; not RunBudget/production evidence."""
    def __init__(self, owner):
        self.owner = owner
        self.starts = 0
    async def guard(self, work):
        return await work
    @asynccontextmanager
    async def run_scope(self):
        yield self
    async def aclose(self):
        pass
    def snapshot(self):
        return SimpleNamespace(cleanup_pending=0, quarantined_slots=0, cleanup_failed=0)
    async def stream_model(self, call_id, *, messages, tools, factory, max_output_tokens):
        async with self.owner.permit({}, '', call_id):
            if self.owner.stop:
                raise asyncio.CancelledError
            started = False
            async def mark_started(actual_messages, actual_tools, actual_limit):
                nonlocal started
                assert not started
                assert actual_messages == messages and actual_tools == tools
                assert actual_limit <= max_output_tokens
                started = True
                self.starts += 1
            prepared = SimpleNamespace(messages=messages, tools=tools,
                                       reserved_output_tokens=max_output_tokens)
            stream = factory(prepared, mark_started)
            try:
                async for chunk in stream:
                    assert started
                    yield chunk
            finally:
                await stream.aclose()


class Owners:
    supports_dispatch_gate = True
    def __init__(self, turns=None):
        self.budget = TestBudget(self)
        self.turns = turns or [[LLMChunk(kind='text', text='ok'), LLMChunk(kind='finish', finish_reason='stop')]]
        self.ledger, self.events, self.snapshots, self.requests = {}, [], [], []
        self.allowed, self.stop = True, False
        self.executions = self.permits = self.prepares = self.closed = self.stream_closed = 0
        self.usage = []
    async def persist(self, state):
        json.dumps(state)
        self.snapshots.append(copy.deepcopy(state))
    async def emit(self, kind, payload):
        self.events.append((kind, copy.deepcopy(payload)))
    async def cancelled(self, identity):
        return self.stop
    def call_id(self, identity, node_id, binding, ordinal, provider_id):
        return json.dumps([identity['runId'], node_id, binding, ordinal, provider_id])
    async def query(self, cid):
        return copy.deepcopy(self.ledger.get(cid, {'status': 'absent'}))
    async def claim_model(self, cid):
        await self.model_begin(cid)  # old inspection fixture; lifecycle, not statistics
    async def model_begin(self, cid):
        assert cid not in self.ledger
        self.ledger[cid] = {'status': 'inflight'}
    async def model_end(self, cid, usage, finish, response):
        self.usage.append(usage)
        self.ledger[cid] = {'status': 'committed', 'response': copy.deepcopy(response)}
    async def commit_tool(self, cid, provider_id, request, invoke):
        if cid in self.ledger:
            assert self.ledger[cid]['status'] == 'committed'
            return copy.deepcopy(self.ledger[cid]['result'])
        assert any(any(c['callId'] == cid for c in n['pending'])
                   for s in self.snapshots for n in s['nodes'].values())
        self.ledger[cid] = {'status': 'inflight'}
        result = await invoke()
        self.ledger[cid] = {'status': 'committed', 'result': result}
        return result
    async def narrow(self, identity, inherited, role):
        return dict(inherited)
    async def verify(self, identity, scope, role):
        return self.allowed
    def binding(self, state, node, scope):
        return json.dumps([state['inputVersion'], state['input'], scope_identity(scope),
                           {k: state['nodes'][k]['version'] for k in node['parentIds']}], sort_keys=True)
    @asynccontextmanager
    async def permit(self, identity, node_id, cid):
        self.permits += 1
        yield
    async def prepare(self, state, node, scope):
        self.prepares += 1
        return {'refs': ['f1'], 'sources': ['s1']}
    async def select(self, facts, state, node, scope):
        assert self.allowed
        return {'factIds': facts['refs'], 'sourceIds': facts['sources'],
                'content': state['input'] + node['role'] + ''.join(
                    state['nodes'][k]['text'] for k in node['parentIds']
                    if state['nodes'][k]['status'] in ('succeeded', 'partial')) +
                    ' missing:' + ','.join(node['missingNodeIds'])}
    async def model_context(self, state, node, scope, schemas):
        await self.select(state['facts'], state, node, scope)
        return dict(messages=copy.deepcopy(node['transcript']), max_tokens=256,
                    timeout_sec=20, temperature=0)
    async def schemas(self, identity, role, scope):
        return [{'name': 'read'}] if self.allowed else []
    async def authorize(self, identity, role, scope, call):
        return self.allowed and call['name'] == 'read'
    async def execute(self, identity, scope, cid, call):
        self.executions += 1
        return {'modelContent': 'safe'}
    async def close(self, identity, node_ids):
        self.closed += 1
        self.closed_ids = node_ids
    async def stream_dispatch(self, prepared, options, dispatch_gate):
        # Synthetic test dispatch boundary ONLY, never an SDK adapter.
        await dispatch_gate(prepared.messages, prepared.tools, prepared.reserved_output_tokens)
        from contextlib import aclosing
        async with aclosing(self.stream(prepared.messages, prepared.tools, options)) as stream:
            async for chunk in stream:
                yield chunk
    async def stream(self, transcript, schemas, options):
        self.requests.append(copy.deepcopy(transcript))
        try:
            for chunk in self.turns.pop(0):
                yield chunk
        finally:
            self.stream_closed += 1


def state(goals=None, deps=None):
    return plan({'runId': 'run', 'actorId': 'actor', 'sessionId': 's', 'attemptId': 'owner'},
                'task', 'v1', {'version': 'auth1'}, goals or ['reply'], True, deps)


def ports(o):
    return Ports(o, o, o, o, o)


async def drive(s, o):
    for _ in range(40):
        await dispatch(s, ports(o))
        if s['active'] is None:
            return outcome(s)
        await step(s, ports(o), s['nodes'][s['active']]['phase'])
    raise AssertionError('Graph did not settle')


@pytest.mark.asyncio
async def test_simple_one_model_and_real_graph():
    o, s = Owners(), state()
    graph = build_graph(ports(o)).compile()
    result = await graph.ainvoke(s)
    assert result['output']['status'] == 'succeeded'
    assert len(o.requests) == o.permits == 1
    assert o.usage == [None]
    assert o.prepares == 1


@pytest.mark.asyncio
async def test_tool_signature_checkpoint_and_committed_replay():
    o = Owners([[LLMChunk(kind='tool_call', tool_call=LLMToolCall('provider', 'read', {}, 'opaque')),
                 LLMChunk(kind='finish', finish_reason='tool_calls')],
                [LLMChunk(kind='text', text='fixture-result'), LLMChunk(kind='finish', finish_reason='stop')]])
    s = state()
    await dispatch(s, ports(o)); await step(s, ports(o), 'prepare')
    await step(s, ports(o), 'model')
    saved = copy.deepcopy(s)
    cid = s['nodes']['reply']['pending'][0]['callId']
    await step(s, ports(o), 'tool')
    assert o.executions == 1
    # Restore checkpoint from before commit: ledger result is replayed, not executed.
    await step(saved, ports(o), 'tool')
    assert o.executions == 1
    assert saved['nodes']['reply']['pending'][0]['signature'] == 'opaque'
    assert saved['nodes']['reply']['transcript'][-1]['tool_call_id'] == 'provider'
    assert cid in saved['nodes']['reply']['callIds']
    assert (await drive(saved, o))['status'] == 'succeeded'
    assert o.permits == 2
    events = [e for k, e in o.events if k.startswith('tool_')]
    assert all(e['callId'] == cid and e['providerCallId'] == 'provider' for e in events)
    assert all(not {'args', 'arguments', 'attemptId', 'sequence', 'at'} & e.keys() for e in events)


@pytest.mark.asyncio
@pytest.mark.parametrize('ledger_status', ['unknown', 'inflight'])
async def test_unknown_tool_blocks_without_execution(ledger_status):
    o, s = Owners(), state()
    n = s['nodes']['reply']
    n['pending'] = [{'callId': 'c', 'providerCallId': 'p', 'name': 'read',
                     'status': 'pending', 'obsolete': False}]
    n.update(phase='tool', binding=o.binding(s, n, s['scope']))
    s['active'] = 'reply'
    s['facts'] = {'refs': ['f1'], 'sources': ['s1']}
    o.ledger['c'] = {'status': ledger_status}
    await step(s, ports(o), 'tool')
    assert o.executions == 0 and n['status'] == 'interrupted'


@pytest.mark.asyncio
async def test_denied_scope_and_role_execute_zero():
    for scope_denied in (True, False):
        o, s = Owners(), state()
        o.allowed = not scope_denied
        n = s['nodes']['reply']
        n.update(phase='tool', binding=o.binding(s, n, s['scope']))
        n['pending'] = [{'callId': 'c', 'providerCallId': 'p', 'name': 'forbidden',
                         'status': 'pending', 'obsolete': False}]
        s['active'] = 'reply'
        s['facts'] = {'refs': ['f1'], 'sources': ['s1']}
        await step(s, ports(o), 'tool')
        assert o.executions == 0 and n['status'] == 'limited'


@pytest.mark.asyncio
async def test_partial_nonstop_shared_facts_and_local_retry():
    o = Owners([[LLMChunk(kind='text', text='fixture-result'), LLMChunk(kind='finish', finish_reason='stop')],
                [LLMChunk(kind='text', text='not success'), LLMChunk(kind='finish', finish_reason='length')]])
    s = state(['summary', 'todo', 'synth'], {'synth': ['todo']})
    result = await drive(s, o)
    assert result['status'] == 'partial' and result['memoryEligible'] is False
    assert set(result['missingNodeIds']) == {'todo', 'synth'}
    assert o.prepares == 1
    assert s['nodes']['summary']['transcript'] is not s['nodes']['todo']['transcript']
    saved = copy.deepcopy(s['nodes']['summary'])
    assert invalidate(s, ['todo']) == {'todo', 'synth'}
    assert s['nodes']['summary'] == saved
    assert s['nodes']['todo']['transcript'] == []
    o.turns = [[LLMChunk(kind='text', text='fixture-result'), LLMChunk(kind='finish', finish_reason='stop')] for _ in range(2)]
    assert (await drive(s, o))['status'] == 'succeeded'
    assert s['nodes']['summary'] == saved and len(o.requests) == 4


@pytest.mark.asyncio
async def test_cancel_closes_stream_and_all_queued_nodes():
    class Cancelling(Owners):
        async def stream(self, transcript, schemas, options):
            try:
                self.stop = True
                yield LLMChunk(kind='text', text='secret')
            finally:
                self.stream_closed += 1
    o, s = Cancelling(), state(['reply', 'todo'])
    await dispatch(s, ports(o)); await step(s, ports(o), 'prepare')
    with pytest.raises(asyncio.CancelledError):
        await step(s, ports(o), 'model')
    assert o.stream_closed == o.closed == 1
    assert set(o.closed_ids) == {'reply', 'todo'}
    assert all(n['status'] == 'cancelled' for n in s['nodes'].values())


@pytest.mark.asyncio
async def test_changed_input_invalidates_success_and_facts():
    o, s = Owners(), state()
    await drive(s, o)
    s['inputVersion'] = 'v2'
    await dispatch(s, ports(o))
    assert s['nodes']['reply']['transcript'] == [] and s['facts'] == {}
    assert s['nodes']['reply']['status'] == 'queued'


def test_no_defaults_and_invalid_dags():
    with pytest.raises(NotReady):
        Ports(None, None, None, None, None)
    with pytest.raises(ValueError):
        state(['reply', 'synth'], {'reply': ['synth'], 'synth': ['reply']})
    with pytest.raises(ValueError):
        plan({}, '', '', {}, ['reply', 'todo'], False)

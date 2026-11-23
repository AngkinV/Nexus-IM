"""Owner-API regressions authored only; require integrated T2/T3 modules. NOT RUN.
Synthetic send boundaries below are test fixtures, never production SDK hooks.
"""
import asyncio
import copy
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
import pytest
from app.llm.base import LLMChunk
from app.subagents.contracts import NotReady, Ports
from app.subagents.worker import cancel, step
from app.subagents.graph import dispatch
from app.subagents.owner_adapters import ScopeAdapter, CommittedResults, ExecutorAdapter
from .test_contracts import Owners, state, ports, drive
from .test_review_fixes import tools, answer


@pytest.mark.asyncio
async def test_same_scope_reauthorization_keeps_committed_transcript():
    o, s = Owners([tools('p'), answer('DONE')]), state()
    s['scope'].update(scopeKey='same', sessionGeneration=1, grantId='old', authorizationVersion=1)
    s['runScope'] = copy.deepcopy(s['scope'])
    await dispatch(s, ports(o)); await step(s, ports(o), 'prepare')
    await step(s, ports(o), 'model'); await step(s, ports(o), 'tool')
    saved = copy.deepcopy(s['nodes']['reply']['transcript'])
    binding = s['nodes']['reply']['binding']
    s['scope'].update(grantId='new', authorizationVersion=2)
    await step(s, ports(o), 'model')
    assert s['nodes']['reply']['binding'] == binding
    assert o.requests[-1] == saved and o.executions == 1
    assert s['nodes']['reply']['pending'][0]['status'] == 'committed'


@pytest.mark.asyncio
@pytest.mark.parametrize('field', ['scopeKey', 'sessionGeneration'])
async def test_scope_change_cannot_continue_same_run(field):
    o, s = Owners(), state()
    s['scope'][field] = 'changed'
    s['active'] = 'reply'
    await step(s, ports(o), 'prepare')
    assert s['nodes']['reply']['errorCode'] == 'CAPABILITY_NOT_READY'
    assert not o.requests and o.executions == 0 and s['facts'] == {}


@pytest.mark.asyncio
async def test_child_and_new_grant_delegate_independent_executor_identity():
    bindings, sent = {}, []
    async def factory(identity, role, node, scope):
        key = (node, scope['scopeKey'], scope['grantId'])
        class Bound:
            async def schemas(self):
                return []
            async def execute(self, name, args, cid):
                sent.append(key)
                return {'untrusted': True, 'content': 'authorized'}, 7
        return bindings.setdefault(key, Bound())
    async def allow(*args): return True
    async def close(*args): pass
    async def mapped(identity, scope, call, value):
        assert identity['nodeId'] == 'child' and identity['agentId'] == 'agent-child'
        envelope, latency = value
        assert latency == 7 and envelope['untrusted']
        return {'modelContent': envelope['content']}  # status intentionally unknown
    adapter = ExecutorAdapter(factory, allow, close, mapped, message_query=lambda *_: None)
    parent = await factory({}, 'parent', 'parent', dict(scopeKey='AB', grantId='g1'))
    call = dict(role='child', nodeId='child', agentId='agent-child', name='namespaced_read', arguments={})
    for grant in ('g1', 'g2'):
        await adapter.execute({}, dict(scopeKey='A', grantId=grant), 'call', call)
    assert sent == [('child', 'A', 'g1'), ('child', 'A', 'g2')]
    assert all(value is not parent for key, value in bindings.items() if key[0] == 'child')
    assert bindings[('child', 'A', 'g1')] is not bindings[('child', 'A', 'g2')]


@pytest.mark.asyncio
async def test_quarantined_budget_never_confirms_cancelled():
    o, s = Owners(), state()
    o.budget.snapshot = lambda: SimpleNamespace(cleanup_pending=1, quarantined_slots=1, cleanup_failed=0)
    await cancel(s, ports(o))
    assert not s['cancelled'] and s['cancelError'] == 'BUDGET_CLEANUP_UNCONFIRMED'
    assert not any(frame.get('status') == 'cancelled' for _, frame in o.events)


@pytest.mark.asyncio
async def test_fact_coverage_partial_not_complete():
    class Partial(Owners):
        async def select(self, *args):
            selected = await super().select(*args)
            selected.update(partial=True, coverage={'includedCount': 1, 'omittedCount': 2,
                                                   'bodyTruncatedCount': 1, 'rangeComplete': False})
            return selected
    o, s = Partial(), state()
    assert (await drive(s, o))['status'] == 'partial'
    frame = next(frame for kind, frame in o.events if kind == 'agent_output')
    assert frame['factsPartial'] is True and frame['factCoverage']['omittedCount'] == 2


@pytest.mark.asyncio
async def test_success_replay_status_reused_original_source():
    class Success(Owners):
        async def execute(self, *args):
            result = await super().execute(*args)
            result.update(status='SUCCESS', sourceAttemptId='producer', sourceNodeId='reply')
            return result
    o, s = Success([tools('p')]), state()
    await dispatch(s, ports(o)); await step(s, ports(o), 'prepare'); await step(s, ports(o), 'model')
    before = copy.deepcopy(s)
    await step(s, ports(o), 'tool'); await step(before, ports(o), 'tool')
    frame = [frame for kind, frame in o.events if kind == 'tool_result'][-1]
    assert o.executions == 1 and frame['status'] == 'SUCCESS' and frame['reused'] is True
    assert (frame['sourceAttemptId'], frame['sourceNodeId']) == ('producer', 'reply')


def actual_budget():
    # Delayed imports intentionally require real owner modules, not fixtures/skips.
    from app.efficiency.budgets import RunBudget, BudgetLimits, AdmissionController
    count = [0]
    def record(cid): count[0] += 1
    deadline = datetime.now(timezone.utc) + timedelta(seconds=30)
    budget = RunBudget(limits=BudgetLimits(), actor_id=1, provider_id='test-provider', run_id='r',
                       deadline_at=deadline, cancel_event=asyncio.Event(), get_model_calls=lambda: count[0],
                       on_model_start=record, admission=AdmissionController())
    return budget, count, deadline


@pytest.mark.asyncio
async def test_real_budget_local_preflight_zero_and_dispatch_once():
    budget, count, deadline = actual_budget()
    messages = [{'role': 'user', 'content': 'test'}]
    def reject(prepared, gate):
        async def source():
            raise ValueError('local preflight rejection')
            yield
        return source()
    with pytest.raises(ValueError):
        async for _ in budget.stream_model('local', messages=messages, tools=[], factory=reject, max_output_tokens=16): pass
    assert count == [0]
    gates = []
    def dispatch(prepared, gate):
        async def source():
            await gate(prepared.messages, prepared.tools, prepared.reserved_output_tokens)
            gates.append('sent')
            yield 'actual test send'
            from app.efficiency.results import BudgetLimited
            with pytest.raises(BudgetLimited):
                await gate(prepared.messages, prepared.tools, prepared.reserved_output_tokens)
        return source()
    assert [x async for x in budget.stream_model('sent', messages=messages, tools=[], factory=dispatch,
                                               max_output_tokens=16)] == ['actual test send']
    assert count == [1] and gates == ['sent']
    assert budget.deadline_at == deadline
    await budget.aclose()


@pytest.mark.asyncio
async def test_nodes_share_real_budget_resume_inherits_deadline_and_getter():
    budget, count, deadline = actual_budget()
    o = Owners(); o.budget = budget
    first, second = ports(o), ports(o)
    assert first.budget is second.budget is budget
    await first.budget.guard(asyncio.sleep(0))  # facts/non-model work also guarded
    count[0] = 2
    from app.efficiency.budgets import RunBudget
    resumed = RunBudget(limits=budget.limits, actor_id=1, provider_id=budget.provider_id, run_id=budget.run_id,
                        deadline_at=deadline, cancel_event=budget.cancel_event, get_model_calls=budget.get_model_calls,
                        on_model_start=budget.on_model_start, admission=budget.admission)
    assert resumed.deadline_at == deadline and resumed.snapshot().model_calls_started == 2
    await budget.aclose(); await resumed.aclose()


@pytest.mark.asyncio
async def test_real_cleanup_incomplete_keeps_quarantine():
    budget, count, _ = actual_budget()
    release = asyncio.Event()
    class Stream:
        sent = False
        def __aiter__(self): return self
        async def __anext__(self):
            if self.sent: raise StopAsyncIteration
            self.sent = True
            await self.gate(self.prepared.messages, self.prepared.tools, self.prepared.reserved_output_tokens)
            return 'chunk'
        async def aclose(self): await release.wait()
    def factory(prepared, gate):
        stream = Stream(); stream.prepared, stream.gate = prepared, gate
        return stream
    from app.efficiency.cleanup import CleanupIncomplete
    stream = budget.stream_model('hung', messages=[{'role': 'user', 'content': 'test'}], tools=[],
                                 factory=factory, max_output_tokens=16)
    await stream.__anext__()
    budget.cancel_event.set()
    try:
        with pytest.raises(CleanupIncomplete): await stream.aclose()
        assert budget.snapshot().quarantined_slots == 1 and count == [1]
    finally:
        release.set()
        await asyncio.sleep(0)
        await budget.aclose()


def test_legacy_model_fails_closed():
    o = Owners(); o.supports_dispatch_gate = False
    with pytest.raises(NotReady): ports(o)


@pytest.mark.asyncio
async def test_committed_bridge_miss_never_claims_absence():
    class Store:
        async def committed(self, *args): return None
    bridge = CommittedResults(Store(), 'u1:r')
    assert await bridge.get('tool', 'c', 'fp') is None
    with pytest.raises(NotReady): await bridge.query('c')


@pytest.mark.asyncio
async def test_actual_t2_resume_scope_reauthorization_and_range_refusal():
    from app import scope as module
    payload = dict(schemaVersion=1, grantId='g1', actorUserId=1, sessionId='s', runId='r',
                   bindingVersion=1, authorizationVersion=1, sessionGeneration=1,
                   chatIds=[101, 202], contactUserIds=[],
                   timeRange=dict(fromInclusive=None, toExclusive=None, timeZone='Asia/Shanghai'))
    payload['scopeKey'] = module.scope_hash(payload)  # owner formula, never duplicated
    current = module.AuthorizedScope.model_validate(payload)
    class LiveClient:
        async def validate(self, scope):
            assert scope.grantId == 'g2' and scope.authorizationVersion == 2
            return scope  # synthetic authority boundary, real schema/resume rules
    adapter = ScopeAdapter(module, LiveClient(), lambda *_: {}, lambda *_: {})
    s = state(); s['identity'].update(actorId='1', runId='r', sessionId='s')
    s['scope'] = current.model_dump(); s['runScope'] = current.model_dump()
    n = s['nodes']['reply']; n['pending'] = [dict(callId='original', status='committed')]
    original = copy.deepcopy(n['pending'])
    fresh = current.model_copy(update=dict(grantId='g2', authorizationVersion=2))
    await adapter.resume(s, fresh.model_dump())
    assert n['pending'] == original and s['runScope']['grantId'] == 'g1'
    narrower = module.narrow_scope(fresh, chat_ids=[101])
    with pytest.raises(module.ScopeDenied): await adapter.resume(s, narrower.model_dump())
    assert s['scope']['scopeKey'] == current.scopeKey and n['pending'] == original


@pytest.mark.asyncio
async def test_the_real_tool_executor_is_called_with_its_keyword_call_id():
    """The run's ToolExecutor takes `call_id` by keyword; T4B's guarded executor takes it by position."""
    from app.mcp_tools import ToolExecutor
    seen = []

    class Real(ToolExecutor):
        def __init__(self): pass
        async def execute(self, tool_name, args, *, call_id=None):
            seen.append(('keyword', tool_name, call_id))
            return {'content': 'ok'}, 3

    class Guarded:
        async def execute_named(self, public_name, args, call_id):
            seen.append(('positional', public_name, call_id))
            return {'content': 'ok'}, 3

    async def allow(*args): return True
    async def close(*args): pass
    async def mapped(identity, scope, call, value): return {'modelContent': value[0]['content']}
    call = dict(role='child', nodeId='child', agentId='agent-child', name='read', arguments={})
    for executor in (Real(), Guarded()):
        async def factory(identity, role, node_id, scope, executor=executor): return executor
        adapter = ExecutorAdapter(factory, allow, close, mapped, message_query=lambda *_: None)
        assert await adapter.execute({}, dict(scopeKey='A'), 'call-7', call) == {'modelContent': 'ok'}
    assert seen == [('keyword', 'read', 'call-7'), ('positional', 'read', 'call-7')]

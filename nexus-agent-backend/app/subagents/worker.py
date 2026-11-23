"""One model request or one tool commit per checkpointable graph step."""
from __future__ import annotations
import asyncio
import math
from contextlib import asynccontextmanager
from dataclasses import asdict
from .contracts import Ports, NotReady
from .state import State, Node, ensure_unsealed


@asynccontextmanager
async def confirmed_stream(stream, pending):
    pending['streamClosed'] = False
    original_cancel = False
    try:
        yield stream
    except asyncio.CancelledError:
        original_cancel = True
        raise
    finally:
        try:
            await stream.aclose()
            pending['streamClosed'] = True
        except BaseException:
            if not original_cancel:
                raise


async def event(p: Ports, n: Node, kind: str, **fields) -> None:
    try:
        await p.t1.emit(kind, dict(nodeId=n['nodeId'], agentId=n['agentId'],
                                  parentIds=n['parentIds'], role=n['role'], **fields))
    except Exception:
        # Transport only: T1 reconstructs public events from persisted state.
        pass


def model_closed(pending: dict, record: dict) -> bool:
    """Use owner evidence, never an optimistic pre-start snapshot alone."""
    if record['status'] == 'absent':
        return True  # The authoritative ledger proves this request never began.
    if record['status'] != 'committed':
        return False
    response = record.get('response', {})
    if 'streamClosed' in response:
        return response['streamClosed'] is True
    return pending.get('end') is True and pending.get('streamClosed') is True


async def cancel(state: State, p: Ports) -> None:
    if state.get('sealed') or (state['nodes'] and all(
            n['status'] in ('succeeded', 'partial') for n in state['nodes'].values())):
        return  # Finished handover/not_active is not cancellation confirmation.
    if state['cancelled'] and state.get('cancelRequested') and not state.get('cancelError'):
        return  # Confirmed cancellation is replayed by T1, not executed again.
    state.update(cancelRequested=True, cancelled=False, cancelError=None, active=None)
    ids = [k for k, n in state['nodes'].items() if n['status'] not in ('succeeded', 'partial')]
    for key in ids:
        state['nodes'][key].update(status='stop_requested', errorCode='CANCEL_REQUESTED')
    # Request persistence is attempted, but can never gate resource release.
    reason, interrupted = None, None
    try:
        await p.t1.persist(state)
    except asyncio.CancelledError as exc:
        interrupted = exc
    except Exception:
        pass
    try:
        # Completed roles can still own cached tool sessions: close all roles.
        await p.t4.close(state['identity'], list(state['nodes']))
    except asyncio.CancelledError as exc:
        reason, interrupted = 'CLOSE_INTERRUPTED', exc
    except Exception as exc:
        reason = 'CLOSE_TIMEOUT' if isinstance(exc, TimeoutError) else 'CLOSE_FAILED'
    try:
        await p.budget.aclose()  # cleanup is deliberately outside expired guard
        snapshot = p.budget.snapshot()
        if any(getattr(snapshot, field, None) != 0 for field in
               ('cleanup_pending', 'quarantined_slots', 'cleanup_failed')):
            reason = 'BUDGET_CLEANUP_UNCONFIRMED'
    except asyncio.CancelledError as exc:
        reason, interrupted = 'BUDGET_CLEANUP_UNCONFIRMED', exc
    except Exception:
        reason = 'BUDGET_CLEANUP_UNCONFIRMED'
    if reason is None:
        for n in state['nodes'].values():
            pending = n.get('modelPending')
            if not pending:
                continue
            try:
                record = await p.t1.query(pending['callId'])
                if not model_closed(pending, record):
                    reason = 'MODEL_RECONCILIATION_REQUIRED' if record['status'] in ('unknown', 'inflight') else 'MODEL_CLOSE_UNCONFIRMED'
            except asyncio.CancelledError as exc:
                reason, interrupted = 'MODEL_CLOSE_UNCONFIRMED', exc
            except Exception:
                reason = 'MODEL_CLOSE_UNCONFIRMED'
            if reason:
                break
    if reason is None:
        for key in ids:
            state['nodes'][key].update(status='cancelled', finishReason='cancelled', errorCode='CANCELLED')
        state['cancelled'] = True
    else:
        for key in ids:
            state['nodes'][key]['errorCode'] = reason
        state['cancelError'] = reason
    # Only this successful checkpoint can authorize a public completion frame.
    try:
        await p.t1.persist(state)
    except asyncio.CancelledError as exc:
        interrupted = interrupted or exc
        state.update(cancelled=False, cancelError='CANCEL_PERSIST_FAILED')
        for key in ids:
            state['nodes'][key].update(status='stop_requested', finishReason=None,
                                      errorCode='CANCEL_PERSIST_FAILED')
    except Exception:
        state.update(cancelled=False, cancelError='CANCEL_PERSIST_FAILED')
        for key in ids:
            state['nodes'][key].update(status='stop_requested', finishReason=None,
                                      errorCode='CANCEL_PERSIST_FAILED')
    for key in ids:
        n = state['nodes'][key]
        await event(p, n, 'stage', status=n['status'], phase=n['phase'])
        if state['cancelled']:
            await terminal_event(p, n)
    if interrupted is not None:
        raise interrupted


def stable_call_id(p: Ports, state: State, n: Node, ordinal: int, provider: str) -> str:
    if not isinstance(n['binding'], str) or not n['binding']:
        raise NotReady('Missing stable context binding')
    cid = p.t1.call_id(state['identity'], n['nodeId'], n['binding'], ordinal, provider)
    if not isinstance(cid, str) or not cid:
        raise NotReady('Missing stable call identity')
    return cid


def checked_context(prepared: dict) -> tuple[list[dict], dict]:
    """Validate the T3 contract shape; no token estimator or private budget."""
    if not isinstance(prepared, dict):
        raise NotReady('Model context unavailable')
    messages = prepared.get('messages')
    if not isinstance(messages, list) or not messages:
        raise NotReady('Model messages unavailable')
    for message in messages:
        if not isinstance(message, dict) or message.get('role') not in ('system', 'user', 'assistant', 'tool'):
            raise NotReady('Invalid model message')
        content = message.get('content')
        if not isinstance(content, (str, list)) and not (
                message['role'] == 'assistant' and content is None and
                isinstance(message.get('tool_calls'), list) and message['tool_calls']):
            raise NotReady('Invalid model message content')
    tokens = prepared.get('max_tokens')
    timeout = prepared.get('timeout_sec')
    temperature = prepared.get('temperature')
    if isinstance(tokens, bool) or not isinstance(tokens, int) or tokens <= 0:
        raise NotReady('Invalid output allowance')
    if (isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or
            not math.isfinite(timeout) or timeout <= 0):
        raise NotReady('Invalid model deadline')
    if (isinstance(temperature, bool) or not isinstance(temperature, (int, float)) or
            not math.isfinite(temperature) or not 0 <= temperature <= 2):
        raise NotReady('Invalid model temperature')
    return messages, dict(max_tokens=tokens, timeout_sec=timeout, temperature=temperature)


async def validate_references(state: State, n: Node, p: Ports, scope: dict,
                              response_pending: dict | None = None) -> dict:
    """Recheck cached data before reuse; unchanged scope alone is not proof."""
    selected = await p.t3.select(state['facts'], state, n, scope)
    for field in ('factIds', 'sourceIds'):
        required = set(n[field])
        if response_pending:
            required.update(response_pending.get(field, []))
        if not required.issubset(set(selected[field])):
            raise PermissionError('Cached references are no longer approved')
    for call in n['pending']:
        if call['status'] == 'committed' and (not call['obsolete'] or
                n.get('retryContext') == context(state, scope)):
            if not await p.t4.authorize(state['identity'], n['role'], scope, call):
                raise PermissionError('Cached tool result is no longer approved')
    n['factCoverage'] = selected.get('coverage', {})
    n['factsPartial'] = selected.get('partial', False) is True
    return selected


async def refresh_retry(state: State, n: Node, p: Ports, scope: dict) -> bool:
    """Rebase changed parents without losing a retry target's committed tools.

    T3 still decides which prior facts are valid/relevant and what is actually
    sent. Unsubmitted old-context tool requests are only reconciled/abandoned.
    """
    if n.get('retryContext') != context(state, scope):
        return False
    n['parentVersions'] = {key: state['nodes'][key]['version'] for key in n['parentIds']}
    n['binding'] = p.t2.binding(state, n, scope)
    selected = await p.t3.select(state['facts'], state, n, scope)
    n['factIds'], n['sourceIds'] = selected['factIds'], selected['sourceIds']
    if n['transcript']:
        n['transcript'][0] = {'role': 'user', 'content': selected['content']}
    else:
        n['transcript'] = [{'role': 'user', 'content': selected['content']}]
    for call in n['pending']:
        if call['status'] not in ('committed', 'abandoned'):
            call['obsolete'] = True
    n.update(context=context(state, scope), phase='tool' if any(
        c['status'] not in ('committed', 'abandoned') for c in n['pending']) else 'model')
    return True


async def terminal_event(p: Ports, n: Node) -> None:
    await event(p, n, 'agent_output', text=n['text'] if n['status'] in ('succeeded', 'partial') else '',
                status=n['status'], finishReason=n['finishReason'], resultVersion=n['version'],
                factIds=n['factIds'], sourceIds=n['sourceIds'], callIds=n['callIds'],
                missingNodeIds=n.get('missingNodeIds', []),
                missingReasons=n.get('missingReasons', {}), errorCode=n['errorCode'],
                factCoverage=n.get('factCoverage', {}), factsPartial=n.get('factsPartial', False))


def scope_identity(scope: dict) -> dict:
    # T2 already validates schema/hash. These are transport grants, not data identity.
    if 'scopeKey' in scope:
        return {k: scope.get(k) for k in ('schemaVersion', 'actorUserId', 'sessionId',
                'runId', 'scopeKey', 'sessionGeneration')}
    return {k: v for k, v in scope.items() if k not in
            ('grantId', 'authorizationVersion', 'bindingVersion')}


async def scope_for(state: State, n: Node, p: Ports) -> dict:
    original = state.get('runScope', state['scope'])
    if scope_identity(original) != scope_identity(state['scope']):
        raise NotReady('Scope change requires a new run')
    scope = await p.t2.narrow(state['identity'], state['scope'], n['role'])
    if not await p.t2.verify(state['identity'], scope, n['role']):
        raise PermissionError('Scope denied')
    if n['context'].get('scope') and scope_identity(n['context']['scope']) != scope_identity(scope):
        raise NotReady('Child scope change requires a new run')
    return scope


def context(state: State, scope: dict) -> dict:
    return dict(input=state['input'], inputVersion=state['inputVersion'], scope=scope_identity(scope))


async def prepare(state: State, n: Node, p: Ports, scope: dict) -> None:
    from .recovery import invalidate
    if n['binding'] is not None and n['context'] != context(state, scope):
        invalidate(state, [n['nodeId']], context_changed=True)
    if not state.get('factsPrepared', False):
        if not state['facts']:
            facts = await p.t3.prepare(state, n, scope)
            if not isinstance(facts, dict):
                raise NotReady('Invalid shared facts manifest')
            state['facts'] = facts
        state['factsPrepared'] = True  # Empty is an actual completed preparation.
    binding = p.t2.binding(state, n, scope)
    if n['binding'] not in (None, binding):
        invalidate(state, [n['nodeId']])
    n['binding'] = binding
    n['context'] = context(state, scope)
    n['parentVersions'] = {key: state['nodes'][key]['version'] for key in n['parentIds']}
    selected = await p.t3.select(state['facts'], state, n, scope)
    n['factIds'] = selected['factIds']
    n['sourceIds'] = selected['sourceIds']
    n['transcript'] = [{'role': 'user', 'content': selected['content']}]
    n['phase'] = 'model'
    n['status'] = 'running'
    await p.t1.persist(state)


async def model_step(state: State, n: Node, p: Ports, scope: dict) -> None:
    schemas = await p.t4.schemas(state['identity'], n['role'], scope)
    if not isinstance(schemas, list) or any(not isinstance(schema, dict) for schema in schemas):
        raise NotReady('Invalid authorized tool catalogue')
    prepared = await p.t3.model_context(state, n, scope, schemas)
    messages, options = checked_context(prepared)
    cid = stable_call_id(p, state, n, n['modelOrdinal'], 'model')
    n['modelPending'] = dict(callId=cid, binding=n['binding'], ordinal=n['modelOrdinal'],
                             end=False, consume=False, streamClosed=False,
                             factIds=list(n['factIds']), sourceIds=list(n['sourceIds']))
    if cid not in n['callIds']:
        n['callIds'].append(cid)
    await p.t1.persist(state)
    record = await p.t1.query(cid)
    if record['status'] == 'committed':
        if not model_closed(n['modelPending'], record):
            n.update(status='interrupted', errorCode='MODEL_CLOSE_UNCONFIRMED')
            return
        response = record['response']
        await validate_references(state, n, p, scope, response)
        await apply_response(state, n, p, response['text'], response['calls'],
                             response['finish'], response['error'], stream_closed=True)
        return
    if record['status'] != 'absent':
        n.update(status='interrupted', errorCode='MODEL_RECONCILIATION_REQUIRED')
        return
    claimed = False
    def factory(fitted, mark_started):
        async def dispatch_gate(actual_messages, actual_tools, actual_output_limit):
            nonlocal claimed
            current = await scope_for(state, n, p)
            if p.t2.binding(state, n, current) != n['binding']:
                raise PermissionError('Context changed before dispatch')
            await validate_references(state, n, p, current, n['modelPending'])
            await p.t1.claim_model(cid)
            claimed = True
            await mark_started(actual_messages, actual_tools, actual_output_limit)
        fitted_options = dict(options, max_tokens=fitted.reserved_output_tokens)
        return p.model.stream_dispatch(fitted, fitted_options, dispatch_gate)

    text, calls, usage, finish, error = '', [], None, 'interrupted', False
    finalized = False
    try:
        if await p.t1.cancelled(state['identity']):
            raise asyncio.CancelledError
        try:
            n['modelPending']['streamClosed'] = False
            async with confirmed_stream(p.budget.stream_model(cid, messages=messages, tools=schemas,
                    factory=factory, max_output_tokens=options['max_tokens']), n['modelPending']) as stream:
                async for chunk in stream:
                    if await p.t1.cancelled(state['identity']):
                        raise asyncio.CancelledError
                    if chunk.kind in ('text', 'reasoning'):
                        if not chunk.text:
                            continue
                        if chunk.kind == 'text':
                            text += chunk.text or ''
                        # T1 must sanitize public deltas before constructing its envelope.
                        await event(p, n, 'token' if chunk.kind == 'text' else 'reasoning',
                                    text=chunk.text or '', callId=cid)
                    elif chunk.kind == 'tool_call' and chunk.tool_call:
                        calls.append(asdict(chunk.tool_call))
                    elif chunk.kind == 'usage' and chunk.usage:
                        usage = asdict(chunk.usage)
                    elif chunk.kind == 'error':
                        error = True
                    elif chunk.kind == 'finish':
                        finish = chunk.finish_reason or 'interrupted'
        except asyncio.CancelledError:
            finish = 'cancelled'
            raise
        except Exception:
            error, finish = True, 'error'
            raise
        finally:
            cleanup = p.budget.snapshot()
            if any(getattr(cleanup, field, None) != 0 for field in
                   ('quarantined_slots', 'cleanup_failed')):
                n['modelPending']['streamClosed'] = False
            if claimed:
                try:
                    await p.t1.model_end(cid, usage, 'error' if error else finish,
                                          dict(text=text, calls=calls, finish=finish, error=error,
                                               streamClosed=n['modelPending']['streamClosed'],
                                               factIds=n['modelPending']['factIds'],
                                               sourceIds=n['modelPending']['sourceIds']))
                    n['modelPending']['end'] = True
                    n['modelOrdinal'] = max(n['modelOrdinal'], n['modelPending']['ordinal'] + 1)
                    finalized = True
                    await p.t1.persist(state)
                except Exception:
                    if finish != 'cancelled':
                        raise
    except asyncio.CancelledError:
        raise
    except Exception:
        cleanup = p.budget.snapshot()
        if any(getattr(cleanup, field, None) != 0 for field in
               ('cleanup_pending', 'quarantined_slots', 'cleanup_failed')):
            n.update(status='limited', errorCode='CLEANUP_UNCONFIRMED', finishReason='interrupted')
        else:
            n.update(status='failed', errorCode='MODEL_FAILED', finishReason='error')
        return
    current_scope = await scope_for(state, n, p)
    if p.t2.binding(state, n, current_scope) != n['binding']:
        raise PermissionError('Scope changed during model response')
    await validate_references(state, n, p, current_scope, n['modelPending'])
    await apply_response(state, n, p, text, calls, finish, error)


async def apply_response(state: State, n: Node, p: Ports, text: str,
                         calls: list[dict], finish: str, error: bool,
                         stream_closed: bool | None = None) -> None:
    pending = n.get('modelPending')
    if pending:
        if stream_closed is not None:
            pending['streamClosed'] = stream_closed
        if pending['consume']:
            return
        pending.update(end=True, consume=True)
        n['modelOrdinal'] = max(n['modelOrdinal'], pending['ordinal'] + 1)
    else:
        raise RuntimeError('Missing model reference')
    n['text'] = text
    n['finishReason'] = finish
    if error or finish not in ('stop', 'tool_calls') or (finish == 'stop' and calls):
        n.update(status='failed', errorCode='NON_STOP')
        return
    if finish == 'stop' and not text.strip():
        n.update(status='failed', errorCode='EMPTY_RESPONSE')
        return
    if finish == 'tool_calls':
        if not calls or len({c['id'] for c in calls}) != len(calls):
            n.update(status='failed', errorCode='INVALID_TOOL_CALLS')
            return
        n['transcript'].append({'role': 'assistant', 'content': text, 'tool_calls': calls})
        for call in calls:
            call.update(providerCallId=call['id'],
                        callId=stable_call_id(p, state, n, pending['ordinal'], 'tool:' + call['id']),
                        agentId=n['agentId'], nodeId=n['nodeId'], role=n['role'],
                        parentIds=list(n['parentIds']),
                        binding=n['binding'], status='pending', obsolete=False)
        n['pending'].extend(calls)
        n.update(phase='tool', status='waiting_tool')
        # Persist all provider signatures and pending IDs BEFORE any execute.
        await p.t1.persist(state)
        for call in calls:
            await event(p, n, 'tool_call', callId=call['callId'],
                        providerCallId=call['providerCallId'], toolName=call['name'],
                        status='pending')
    else:
        n['transcript'].append({'role': 'assistant', 'content': text})
        n['version'] += 1
        n['status'] = 'partial' if n['missingNodeIds'] or n.get('factsPartial') else 'succeeded'
        n.update(retryContext=None, errorCode=None)
        await p.t1.persist(state)
        await terminal_event(p, n)


async def tool_step(state: State, n: Node, p: Ports, scope: dict) -> None:
    call = next((c for c in n['pending'] if c['status'] not in ('committed', 'abandoned')), None)
    if call is None:
        n.update(phase='model', status='running')
        return
    record = await p.t1.query(call['callId'])
    if record['status'] in ('unknown', 'inflight'):
        n.update(status='interrupted', errorCode='TOOL_RECONCILIATION_REQUIRED')
        return
    if record['status'] not in ('absent', 'committed'):
        n.update(status='interrupted', errorCode='INVALID_LEDGER_STATE')
        return
    if call['obsolete'] and record['status'] == 'absent':
        call['status'] = 'abandoned'
        # A changed-parent retry must not leave an unexecuted tool request in
        # its provider transcript after the old request is abandoned.
        for message in n['transcript']:
            if message.get('role') == 'assistant' and isinstance(message.get('tool_calls'), list):
                message['tool_calls'] = [c for c in message['tool_calls'] if c.get('callId') != call['callId']]
        await p.t1.persist(state)
        return
    if not await p.t4.authorize(state['identity'], n['role'], scope, call):
        raise PermissionError('Tool denied')
    replayed = record['status'] == 'committed'
    if replayed:
        result = record['result']
    elif call['obsolete']:
        n.update(status='interrupted', errorCode='OBSOLETE_PENDING_CALL')
        return
    else:
        async def invoke():
            # Owner atomic commit invokes this only after winning claim.
            current = await scope_for(state, n, p)
            if p.t2.binding(state, n, current) != call['binding'] or not await p.t4.authorize(
                    state['identity'], n['role'], current, call):
                raise PermissionError('Tool denied')
            return await p.t4.execute(state['identity'], current, call['callId'], call)
        result = await p.t1.commit_tool(call['callId'], call['providerCallId'], call, invoke)
    reused = replayed or result.get('reused') is True
    if reused and isinstance(result.get('status'), str) and result['status'].lower() in ('success', 'succeeded'):
        if not all(isinstance(result.get(field), str) and result[field] for field in
                   ('sourceAttemptId', 'sourceNodeId')):
            raise NotReady('Committed success source receipt unavailable')
    # Cache release is authorized with the latest grant, never the old source grant.
    latest = await scope_for(state, n, p)
    if not await p.t4.authorize(state['identity'], n['role'], latest, call):
        raise PermissionError('Tool result release denied')
    call['status'] = 'committed'
    if call['callId'] not in n['callIds']:
        n['callIds'].append(call['callId'])
    reusable_retry_fact = n.get('retryContext') == context(state, scope)
    if not call['obsolete'] or reusable_retry_fact:
        for field in ('factIds', 'sourceIds'):
            n[field] = list(dict.fromkeys(n[field] + result.get(field, [])))
        if not any(m.get('callId') == call['callId'] and m.get('role') == 'tool' for m in n['transcript']):
            n['transcript'].append({'role': 'tool', 'tool_call_id': call['providerCallId'],
                                    'callId': call['callId'], 'name': call['name'],
                                    'content': result['modelContent']})
    await p.t1.persist(state)  # every call committed separately
    reported = result.get('status')
    status = {'success': 'succeeded', 'succeeded': 'succeeded', 'failed': 'failed',
              'timeout': 'limited', 'denied': 'limited', 'cancelled': 'cancelled'}.get(
                  reported.lower() if isinstance(reported, str) else '', 'unknown')
    call['resultStatus'] = status
    await p.t1.persist(state)
    await event(p, n, 'tool_result', callId=call['callId'],
                providerCallId=call['providerCallId'], toolName=call['name'],
                status='SUCCESS' if reused and status == 'succeeded' else status,
                commitStatus='committed', reused=reused,
                sourceAttemptId=result.get('sourceAttemptId'), sourceNodeId=result.get('sourceNodeId'))


async def step(state: State, p: Ports, phase: str) -> State:
    ensure_unsealed(state)  # Outside the error-to-node-state mutation handler.
    n = state['nodes'][state['active']]
    previous = (n['status'], n['phase'])
    try:
        if await p.t1.cancelled(state['identity']):
            await cancel(state, p)
            return state
        scope = await scope_for(state, n, p)
        pending = n.get('modelPending')
        if pending and not pending['consume'] and not pending.get('discarded'):
            record = await p.t1.query(pending['callId'])
            if record['status'] in ('unknown', 'inflight'):
                n.update(status='interrupted', errorCode='MODEL_RECONCILIATION_REQUIRED')
                await p.t1.persist(state)
                await event(p, n, 'stage', status=n['status'], phase=n['phase'])
                await terminal_event(p, n)
                return state
            if record['status'] == 'committed':
                if not model_closed(pending, record):
                    n.update(status='interrupted', errorCode='MODEL_CLOSE_UNCONFIRMED')
                    await p.t1.persist(state)
                    await terminal_event(p, n)
                    return state
                if p.t2.binding(state, n, scope) != pending['binding']:
                    pending.update(end=True, discarded=True, discardReason='changed_context')
                    n['modelOrdinal'] = max(n['modelOrdinal'], pending['ordinal'] + 1)
                    if not await refresh_retry(state, n, p, scope):
                        from .recovery import invalidate
                        invalidate(state, [n['nodeId']], context_changed=n['context'] != context(state, scope))
                    await p.t1.persist(state)
                    return state
                response = record['response']
                await validate_references(state, n, p, scope, response)
                await apply_response(state, n, p, response['text'], response['calls'],
                                     response['finish'], response['error'], stream_closed=True)
                await p.t1.persist(state)
                return state
        if phase != 'prepare' and p.t2.binding(state, n, scope) != n['binding']:
            if not await refresh_retry(state, n, p, scope):
                from .recovery import invalidate
                invalidate(state, [n['nodeId']], context_changed=n['context'] != context(state, scope))
        elif phase == 'prepare':
            # Reconcile immutable pending references before constructing fresh context.
            if any(c.get('obsolete') and c['status'] not in ('committed', 'abandoned') for c in n['pending']):
                await tool_step(state, n, p, scope)
            else:
                await prepare(state, n, p, scope)
        elif phase == 'model':
            await validate_references(state, n, p, scope)
            await model_step(state, n, p, scope)
        else:
            await validate_references(state, n, p, scope)
            await tool_step(state, n, p, scope)
    except asyncio.CancelledError:
        await cancel(state, p)
        raise
    except NotReady:
        n.update(status='limited', errorCode='CAPABILITY_NOT_READY')
    except PermissionError:
        n.update(status='limited', errorCode='SCOPE_DENIED')
    except Exception:
        n.update(status='failed', errorCode='CAPABILITY_FAILED')
    await p.t1.persist(state)
    if previous != (n['status'], n['phase']):
        await event(p, n, 'stage', status=n['status'], phase=n['phase'])
        if n['status'] in ('failed', 'limited', 'interrupted'):
            await terminal_event(p, n)
    return state

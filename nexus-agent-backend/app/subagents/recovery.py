"""Local recovery over checkpoint data; the owner remains the call ledger."""
from .state import State, ensure_unsealed


def descendants(state: State, targets: list[str]) -> set[str]:
    affected = set(targets)
    if affected - state['nodes'].keys():
        raise ValueError('Unknown retry target')
    while True:
        expanded = affected | {key for key, node in state['nodes'].items()
                               if affected.intersection(node['parentIds'])}
        if expanded == affected:
            return affected
        affected = expanded


def _invalidate_nodes(state: State, keys: set[str]) -> None:
    ensure_unsealed(state)
    for key in keys:
        node = state['nodes'][key]
        node.update(status='queued', phase='prepare', transcript=[], text='',
                    finishReason=None, errorCode=None, binding=None,
                    parentVersions={}, factIds=[], sourceIds=[], missingNodeIds=[], missingReasons={},
                    retryContext=None, factCoverage={}, factsPartial=False)
        # Immutable old IDs remain available for owner reconciliation.
        for call in node['pending']:
            call['obsolete'] = True
    state['output'] = {}
    state['active'] = None


def invalidate(state: State, targets: list[str], context_changed: bool = False) -> set[str]:
    ensure_unsealed(state)
    affected = descendants(state, targets)
    _invalidate_nodes(state, affected)
    if context_changed:
        state['facts'] = {}
        state['factsPrepared'] = False
    return affected


async def retry_failed(state: State, targets: list[str], p) -> set[str]:
    """Unfinished local continuation, NOT T1's public new-run retry.

    Preserve committed tool context and replay unapplied responses.

    All target eligibility and old model records are checked before resetting any
    target. Related targets wait for their actual parents; changed parent output
    is rebased by the worker, not by deleting the target's committed tool facts.
    """
    ensure_unsealed(state)
    from .worker import apply_response, scope_for, model_closed, event, terminal_event, validate_references

    targets = list(dict.fromkeys(targets))
    if not targets or set(targets) - state['nodes'].keys():
        raise ValueError('Known nonempty retry targets required')
    if await p.t1.cancelled(state['identity']):
        raise ValueError('T1 must clear the run cancellation before retry')
    if state.get('cancelRequested') and not state['cancelled']:
        raise ValueError('Cancellation release or persistence is not confirmed')
    records, blocked = {}, {}
    for key in targets:
        n = state['nodes'][key]
        if n['status'] not in ('failed', 'cancelled', 'interrupted', 'limited'):
            raise ValueError('Not a failed retry target')
        scope = await scope_for(state, n, p)
        if p.t2.binding(state, n, scope) != n['binding']:
            raise ValueError('Context changed: use invalidate, then reconcile')
        pending = n.get('modelPending')
        if n['transcript']:
            await validate_references(state, n, p, scope, pending)
        if not pending:
            continue
        record = await p.t1.query(pending['callId'])
        records[key] = record
        if record['status'] in ('unknown', 'inflight'):
            blocked[key] = 'MODEL_RECONCILIATION_REQUIRED'
        elif record['status'] not in ('absent', 'committed'):
            blocked[key] = 'INVALID_LEDGER_STATE'
        elif not model_closed(pending, record):
            blocked[key] = 'MODEL_CLOSE_UNCONFIRMED'
        elif record['status'] == 'committed':
            # The durable response may be newer than local pending/transcript.
            # Validate its ORIGINAL source footprint before touching any target.
            await validate_references(state, n, p, scope, record['response'])
    if blocked:
        state['output'] = {}
        for key, reason in blocked.items():
            n = state['nodes'][key]
            n.update(status='interrupted', errorCode=reason)
        await p.t1.persist(state)
        for key in blocked:
            n = state['nodes'][key]
            await event(p, n, 'stage', status=n['status'], phase=n['phase'])
            await terminal_event(p, n)
        return set()

    affected = descendants(state, targets)
    # Do not traverse again from intermediate descendants: that would wipe an
    # explicitly selected target further downstream.
    _invalidate_nodes(state, affected - set(targets))
    restored = set()
    for key in targets:
        n = state['nodes'][key]
        n['retryContext'] = dict(n['context'])
        pending, record = n.get('modelPending'), records.get(key)
        has_retried_ancestor = any(key in descendants(state, [other])
                                  for other in targets if other != key)
        if pending and record and record['status'] == 'committed':
            response = record['response']
            if not has_retried_ancestor and not pending['consume'] and not pending.get('discarded'):
                # A committed tool_calls/stop response is not a cancelled
                # response: apply its real calls/signatures instead of losing it.
                await apply_response(state, n, p, response['text'], response['calls'],
                                     response['finish'], response['error'], stream_closed=True)
            elif (not has_retried_ancestor and pending['consume'] and
                  response['finish'] == 'stop' and not response['error'] and
                  n['text'].strip() and n['version'] > 0):
                # Already applied, but its final persistence failed. No new LLM.
                n.update(status='partial' if n['missingNodeIds'] or n.get('factsPartial') else 'succeeded',
                         finishReason='stop', errorCode=None, retryContext=None)
            if n['status'] in ('succeeded', 'partial'):
                restored.add(key)
                continue
            if response['error'] or response['finish'] not in ('stop', 'tool_calls'):
                pending.update(discarded=True, discardReason='explicit_failed_retry')
                n['modelOrdinal'] = max(n['modelOrdinal'], pending['ordinal'] + 1)
        elif pending and record and record['status'] == 'absent':
            pending.update(discarded=True, discardReason='never_started')
        phase = 'tool' if any(c['status'] not in ('committed', 'abandoned') for c in n['pending']) else 'model'
        n.update(status='queued', phase=phase if n['transcript'] else 'prepare',
                 text='', errorCode=None, finishReason=None)
    state.update(cancelled=False, cancelRequested=False, cancelError=None, output={}, active=None)
    await p.t1.persist(state)
    for key in restored:
        await terminal_event(p, state['nodes'][key])
    return affected


def outcome(state: State) -> dict:
    missing = [key for key, node in state['nodes'].items()
               if node['status'] != 'succeeded' or not node['text'].strip()]
    results = {key: node['text'] for key, node in state['nodes'].items()
               if node['status'] in ('succeeded', 'partial') and node['text'].strip()}
    unconfirmed = (state.get('cancelRequested') and not state['cancelled']) or any(
        n['status'] == 'stop_requested' for n in state['nodes'].values())
    return {'status': 'stop_requested' if unconfirmed else 'cancelled' if state['cancelled'] else
            ('partial' if missing and results else 'failed' if missing else 'succeeded'),
            'missingNodeIds': missing,
            'missingReasons': {key: state['nodes'][key]['errorCode'] or
                               ('EMPTY_RESPONSE' if not state['nodes'][key]['text'].strip() and
                                state['nodes'][key]['status'] == 'succeeded' else state['nodes'][key]['status'])
                               for key in missing},
            'results': results, 'memoryEligible': False,
            'cancelError': state.get('cancelError')}

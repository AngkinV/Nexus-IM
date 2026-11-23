"""Attach the compiled graph as a T1 parent node; inherit its checkpointer.

Serial ready-node scheduling deliberately uses no private concurrency pool.
Model turns and individual tool commits are separate LangGraph nodes.
"""
import asyncio
from langgraph.graph import StateGraph, START, END
from .contracts import Ports
from .state import State, ensure_unsealed
from .recovery import outcome
from .worker import cancel, step, event, terminal_event

TERMINAL = frozenset(('succeeded', 'partial', 'failed', 'limited', 'cancelled', 'interrupted', 'skipped', 'stop_requested'))


async def dispatch(state: State, p: Ports) -> State:
    ensure_unsealed(state)
    if state['cancelled'] or state.get('cancelRequested') or await p.t1.cancelled(state['identity']):
        await cancel(state, p)
        state['active'] = None
        return state
    state['active'] = None
    publications = {}
    # Completed checkpoint results still require current authorization and binding.
    from .recovery import invalidate
    from .worker import scope_for, context, validate_references
    for key, n in state['nodes'].items():
        if n['status'] not in ('succeeded', 'partial'):
            continue
        if not n['text'].strip():
            invalidate(state, [key])
            n.update(status='failed', errorCode='EMPTY_RESPONSE')
            publications[key] = True
            continue
        try:
            scope = await scope_for(state, n, p)
            if p.t2.binding(state, n, scope) != n['binding']:
                invalidate(state, [key], context_changed=n['context'] != context(state, scope))
            else:
                await validate_references(state, n, p, scope)
        except Exception:
            invalidate(state, [key], context_changed=True)
            n.update(status='limited', errorCode='REFERENCE_RECHECK_DENIED')
            publications[key] = True
    for key, n in state['nodes'].items():
        if n['status'] in TERMINAL:
            continue
        parents = [state['nodes'][parent] for parent in n['parentIds']]
        available = [parent for parent in parents if parent['status'] in ('succeeded', 'partial') and parent['text'].strip()]
        usable_ids = {parent['nodeId'] for parent in available}
        ready = all(parent['status'] == 'succeeded' and parent['text'].strip() for parent in parents)
        if n['parentPolicy'] == 'available_results' and parents:
            if not all(parent['status'] in TERMINAL for parent in parents):
                continue
            ready = bool(available)
            n['missingNodeIds'] = list(dict.fromkeys(
                [parent['nodeId'] for parent in parents
                 if parent['status'] != 'succeeded' or parent['nodeId'] not in usable_ids] +
                [missing for parent in available for missing in parent['missingNodeIds']]))
            reasons = {missing: reason for parent in available
                       for missing, reason in parent.get('missingReasons', {}).items()}
            for parent in parents:
                if parent['nodeId'] in n['missingNodeIds']:
                    reasons[parent['nodeId']] = parent['errorCode'] or (
                        'EMPTY_RESPONSE' if not parent['text'].strip() else parent['status'])
            n['missingReasons'] = reasons
        if not ready and all(parent['status'] in TERMINAL for parent in parents):
            n.update(status='skipped', errorCode='MISSING_DEPENDENCY')
            publications[key] = True
            continue
        if ready:
            state['active'] = key
            if n['status'] == 'queued':
                publications[key] = False
            break
    # Final state is durable before any terminal/dependency frame is published.
    await p.t1.persist(state)
    for key, terminal in publications.items():
        n = state['nodes'][key]
        await event(p, n, 'stage', status=n['status'], phase=n['phase'])
        if terminal and n['status'] in TERMINAL:
            await terminal_event(p, n)
    return state


def route(state: State) -> str:
    return state['nodes'][state['active']]['phase'] if state['active'] else 'finish'


def build_graph(ports: Ports):
    """Return uncompiled StateGraph[State]; T1 owns saver/compile/run config."""
    graph = StateGraph(State)
    async def schedule(state):
        try:
            return await ports.budget.guard(dispatch(state, ports))
        except asyncio.CancelledError:
            await cancel(state, ports)
            raise
    graph.add_node('dispatch', schedule)
    for phase in ('prepare', 'model', 'tool'):
        async def work(state, selected=phase):
            return await ports.budget.guard(step(state, ports, selected))
        graph.add_node(phase, work)
        graph.add_edge(phase, 'dispatch')
    async def finish(state):
        ensure_unsealed(state)
        state['output'] = outcome(state)
        await ports.t1.persist(state)
        return state
    graph.add_node('finish', finish)
    graph.add_edge(START, 'dispatch')
    graph.add_conditional_edges('dispatch', route,
                                {p: p for p in ('prepare', 'model', 'tool', 'finish')})
    graph.add_edge('finish', END)
    return graph

"""Explicit goals only: no inferred three-role fan-out or coordinator call."""
from .state import State, new_node

ROLES = frozenset(('fact', 'summary', 'todo', 'reply', 'review', 'synth'))


def plan(identity: dict[str, str], text: str, input_version: str, scope: dict,
         goals: list[str], collaboration: bool,
         dependencies: dict[str, list[str]] | None = None) -> State:
    roles = list(dict.fromkeys(goals))
    if not roles or any(r not in ROLES for r in roles):
        raise ValueError('Explicit supported goals required')
    if not collaboration and len(roles) != 1:
        raise ValueError('Choose one goal when collaboration is disabled')
    deps = dependencies or {}
    if set(deps) - set(roles):
        raise ValueError('Unknown dependency target')
    nodes = {r: new_node(r, list(dict.fromkeys(deps.get(r, [])))) for r in roles}
    visited, active = set(), set()
    def visit(r):
        if r not in nodes or r in active:
            raise ValueError('Invalid DAG')
        if r in visited:
            return
        active.add(r)
        for parent in nodes[r]['parentIds']:
            visit(parent)
        active.remove(r)
        visited.add(r)
    for r in roles:
        visit(r)
    return State(identity=dict(identity), input=text, inputVersion=input_version,
                 scope=dict(scope), runScope=dict(scope), nodes=nodes, facts={}, factsPrepared=False, active=None,
                 cancelled=False, cancelRequested=False, cancelError=None, output={}, sealed=False)

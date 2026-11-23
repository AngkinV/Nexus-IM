"""Thin DI consumers of owner modules; no permission, hash, pool or journal implementation.

Pass the actual app.scope/app.efficiency classes after owner integration. Missing
SDK dispatch, snapshot hydration and atomic journal hooks remain NotReady.
This module never imports a sibling worktree or creates a transport/session.
"""
from __future__ import annotations
import json
import inspect
from .contracts import NotReady
from .worker import scope_identity


class ScopeAdapter:
    def __init__(self, scope_module, client, child_selection, binding_parts):
        # child_selection supplies explicit trusted subsets, not model parameters.
        self.module, self.client = scope_module, client
        self.child_selection, self.binding_parts = child_selection, binding_parts

    def parsed(self, identity, scope):
        return self.module.AuthorizedScope.model_validate(scope).bind(
            int(identity['actorId']), identity['sessionId'], identity['runId'])

    async def narrow(self, identity, inherited, role):
        parent = self.parsed(identity, inherited)
        child = self.module.narrow_scope(parent, **self.child_selection(identity, role))
        return child.model_dump(mode='json')

    async def verify(self, identity, scope, role):
        await self.client.validate(self.parsed(identity, scope))
        return True

    def binding(self, state, node, scope):
        # Stable content identity, not the replaceable grant/authVersion. Owner
        # parts must include snapshot/schema/provenance/watermark identity.
        parts = self.binding_parts(state, node, scope)
        if parts is None:
            raise NotReady('Per-node snapshot/provenance binding unavailable')
        return json.dumps([state['identity']['runId'], state['inputVersion'],
                           state['input'], node['role'], scope_identity(scope), parts,
                           node['parentIds'], node['parentVersions'] if not node['parentIds'] else
                           {k: state['nodes'][k]['version'] for k in node['parentIds']}],
                          sort_keys=True, separators=(',', ':'))

    async def resume(self, state, current):
        parsed = self.parsed(state['identity'], current)
        # Delegates the frozen v1 rule. No scopehash or permission reimplementation.
        self.module.resume_scope({'scope': state.get('runScope', state['scope'])}, parsed)
        await self.client.validate(parsed)
        state['scope'] = parsed.model_dump(mode='json')  # keeps original runScope and commits


class CommittedResults:
    """Only the real T1 durable-result API; deliberately NOT a full T1 port.

    A cache miss proves no durable result, NOT absent/unattempted. It cannot be
    used as query/claim or as T4B CallJournal.begin/finish/reject.
    """
    def __init__(self, store, thread_id):
        self.store, self.thread_id = store, thread_id

    async def get(self, kind, call_id, fingerprint):
        return await self.store.committed(self.thread_id, kind, call_id, fingerprint)

    async def put(self, kind, call_id, fingerprint, result):
        await self.store.commit(self.thread_id, kind, call_id, fingerprint, result)
        winner = await self.get(kind, call_id, fingerprint)
        if winner is None:
            raise NotReady('Commit identity/fingerprint conflict')
        return winner  # first durable wins, never the losing local result

    async def query(self, call_id):
        raise NotReady('T1 authoritative pending/absent reconciliation unavailable')


class EfficiencyAdapter:
    """One RunBudget + one SharedFactReader, injected for the entire run.

    resolve_bundle must restore live authorized FactPages, not format raw private
    checkpoint content. owner factory constructs reader with await_guard=budget.guard
    and restored message_watermarks. No new() here, including on resume.
    """
    def __init__(self, budget, reader, facts_module, queries, save_manifest,
                 resolve_bundle, assemble_messages, view_tokens, parse_scope):
        self.budget, self.reader, self.facts_module = budget, reader, facts_module
        self.queries, self.save_manifest, self.resolve_bundle = queries, save_manifest, resolve_bundle
        self.assemble_messages, self.view_tokens, self.parse_scope = assemble_messages, view_tokens, parse_scope

    async def prepare(self, state, node, scope):
        if self.save_manifest is None:
            raise NotReady('T1 durable fact reference snapshot hook unavailable')
        pages = []
        for query in self.queries(state, node, scope):
            pages.append(await self.reader.read_page(query, scope=self.parse_scope(scope)))
        bundle = self.facts_module.FactBundle(tuple(pages))
        return await self.save_manifest(state, bundle)

    async def select(self, manifest, state, node, scope):
        if self.resolve_bundle is None:
            raise NotReady('T1/T3 authorized fact reference hydration unavailable')
        bundle = await self.resolve_bundle(state, manifest, self.parse_scope(scope))
        view = await self.reader.view(bundle, self.view_tokens, scope=self.parse_scope(scope))
        # coverage is the actual owner structure serialized in view.text, not new read/usage counts.
        rendered = json.loads(view.text)
        coverage = rendered['coverage']
        return dict(factIds=[f"{m['chatId']}:{m['messageId']}" for m in rendered['messages']],
                    sourceIds=list(view.source_call_ids), content=view.text,
                    partial=view.partial, coverage=coverage)

    async def model_context(self, state, node, scope, schemas):
        selected = await self.select(state['facts'], state, node, scope)
        return await self.assemble_messages(state, node, scope, schemas, selected)
        # stream_model performs the real ContextBudget.fit on this complete frame.


class ExecutorAdapter:
    """Owner supplies a separately bound executor for every node/child/new grant.

    factory(identity, role, node_id, scope) must use actual ToolExecutor/McpContext
    or GuardedExecutor/ExecutionContext + RunSnapshot. Never return a bound parent
    executor for a child. No connection cache/manager is implemented here.
    authorize is the current T2 ∩ T4B cache/dispatch/release permission hook.
    result_mapper consumes actual envelope + latency + trusted journal receipt;
    it MUST NOT infer SUCCESS/executed from merely returning a dict.
    """
    def __init__(self, factory, authorize, close_nodes, result_mapper,
                 message_query=None, reader=None, parse_scope=None):
        self.factory, self.authorizer, self.close_nodes = factory, authorize, close_nodes
        self.result_mapper = result_mapper
        self.message_query, self.reader, self.parse_scope = message_query, reader, parse_scope

    async def schemas(self, identity, role, scope):
        # T5 currently has one node per role; no inferred ID for multi-node roles.
        executor = await self.factory(identity, role, role, scope)
        if callable(getattr(executor, 'public_schemas', None)):
            return await executor.public_schemas()  # T4B, not its ToolIdentity tuple
        return await executor.schemas()  # T4A/ToolExecutor function schemas

    async def authorize(self, identity, role, scope, call):
        return await self.authorizer(identity, role, scope, call)

    async def execute(self, identity, scope, call_id, call):
        role, node_id = call['role'], call['nodeId']
        actual = dict(identity, agentId=call['agentId'], nodeId=node_id)
        if not await self.authorizer(actual, role, scope, call):
            raise PermissionError('Tool authorization denied')
        # Trusted catalog resolver must identify internal message tools, including
        # namespaced names. Missing resolver fails closed for ALL tools.
        if self.message_query is None:
            raise NotReady('T4 trusted message-tool routing hook unavailable')
        query = self.message_query(call, scope)
        if query is not None:
            if self.reader is None or self.parse_scope is None:
                raise NotReady('SharedFactReader message route unavailable')
            page = await self.reader.read_page(query, scope=self.parse_scope(scope))
            return await self.result_mapper(actual, scope, call, page)
        executor = await self.factory(actual, role, node_id, scope)
        execute = getattr(executor, 'execute_named', None)  # T4B
        if not callable(execute):
            execute = getattr(executor, 'execute', None)  # T4A/ToolExecutor
        if not callable(execute) or self.result_mapper is None:
            raise NotReady('Actual executor/result receipt adapter unavailable')
        # T4B's guarded executor takes the call id by position; the run's own
        # ToolExecutor (T1 / T4A) takes it by keyword. Either way it is always sent.
        for extra in ((call_id,), None):
            args, kwargs = (call['name'], call['arguments'], *(extra or ())), ({} if extra else {'call_id': call_id})
            try:
                inspect.signature(execute).bind(*args, **kwargs)
            except (TypeError, ValueError):
                continue
            value = await execute(*args, **kwargs)
            break
        else:
            raise NotReady('ToolExecutor stable call_id signature unavailable')
        return await self.result_mapper(actual, scope, call, value)

    async def close(self, identity, node_ids):
        await self.close_nodes(identity, node_ids)  # actual owner lifecycle, no new sessions


async def run_owned(ports, invoke):
    """Wrap owner-provided compiled-subgraph invocation, not a new orchestrator."""
    async with ports.budget.run_scope():
        return await invoke()

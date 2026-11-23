import test from 'node:test'
import assert from 'node:assert/strict'
import { buildRunPresentation, dependencyLayers, displayState, evidenceText, reportedUsage } from './runPresentation.js'

// Synthetic fixtures are restricted to regression tests. They are not model,
// MCP, replay, browser, or production-chain evidence. Execution belongs to T1.
const frame = (name, data, attempt = 'a1', sequence = 1) => ({ name, data: { runId: 'r1', attemptId: attempt, sequence, at: sequence * 10, ...data } })
const model = (nodeId, extra = {}, attempt = 'a1', sequence = 2) => frame('stage', { phase: 'model', nodeId, status: 'running', ...extra }, attempt, sequence)
const meta = (attempt = 'a1', sequence = 1, extra = {}) => frame('meta', extra, attempt, sequence)
const live = { live: true, status: 'streaming', runId: 'r1' }

function freeze(value) { if (value && typeof value === 'object' && !Object.isFrozen(value)) { Object.freeze(value); Object.values(value).forEach(freeze) } return value }

test('empty projection has no invented work, roles, elapsed, usage, or artifacts', () => {
  const view = buildRunPresentation([], live)
  assert.deepEqual([view.nodes, view.roles, view.edges, view.artifacts, view.sources], [[], [], [], [], []])
  assert.equal(view.summary.elapsed.ms, null)
  assert.equal(view.summary.usage, null)
  assert.equal(view.summary.reportedModelCalls, null)
  assert.equal(view.task, null)
  assert.equal(view.scope, null)
  assert.equal(view.summary.recordedNodes, 0)
  assert.equal(view.summary.state, 'unknown') // Streaming alone is not a backend running node.
})

test('all reported node states retain their meaning rather than collapsing partial or waits', () => {
  const states = ['queued', 'running', 'waiting_tool', 'waiting_approval', 'stop_requested', 'succeeded', 'partial', 'failed', 'cancelled', 'unknown', 'interrupted', 'limited', 'skipped']
  const view = buildRunPresentation([meta(), ...states.map((status, i) => model(`n${i}`, { status, agentId: `real-${i}` }, 'a1', i + 2))], live)
  assert.deepEqual(view.nodes.map(node => node.state), states)
  assert.equal(displayState('partial'), 'partial')
  assert.equal(displayState('SUCCESS'), 'succeeded')
  assert.equal(displayState('FAILED'), 'failed')
})

test('reasoning alone and legacy inferred assistant IDs do not schedule characters', () => {
  const view = buildRunPresentation([meta(), model('n1'), frame('reasoning', { agentId: 'analyst', text: 'unattributed original' }, 'a1', 3)], live)
  assert.equal(view.nodes.length, 1)
  assert.equal(view.nodes[0].agentId, null)
  assert.equal(view.roles.length, 0)
  assert.equal(view.unattributed[0].text, 'unattributed original')
  const reported = buildRunPresentation([meta(), model('n1', { agentId: 'stable-id', role: 'Fact extraction' }), model('n2', { agentId: 'stable-id' }, 'a1', 3)], live)
  assert.equal(reported.roles.length, 1)
  assert.equal(reported.roles[0].id, 'stable-id')
  assert.equal(reported.roles[0].nodes.length, 2)
})

test('layers follow explicit parents; parallel siblings occupy the same dependency depth', () => {
  const trace = [meta(), frame('stage', { phase: 'context', nodeId: 'context', status: 'done' }, 'a1', 2),
    model('facts', { parentIds: ['context'], agentId: 'facts' }, 'a1', 3), model('review', { parentIds: ['context'], agentId: 'review' }, 'a1', 4),
    model('summary', { parentIds: ['facts', 'review'], agentId: 'summary' }, 'a1', 5)]
  const view = buildRunPresentation(trace, live)
  assert.deepEqual(view.layers.map(layer => layer.nodes.map(node => node.nodeId)), [['context'], ['facts', 'review'], ['summary']])
  assert.equal(view.edges.length, 4)
  assert.ok(view.edges.every(edge => edge.evidence === 'parentIds'))
})

test('unresolved parents never create dummy context, handoff, or artifact nodes', () => {
  const view = buildRunPresentation([meta(), model('n1', { parentIds: ['not-reported'] })], live)
  assert.equal(view.nodes.length, 1)
  assert.deepEqual(view.nodes[0].reportedParentIds, ['not-reported'])
  assert.deepEqual(view.nodes[0].unresolvedParentIds, ['not-reported'])
  assert.deepEqual(view.edges, [])
})

test('cyclic reports are retained as unresolved rather than presented as a valid DAG', () => {
  const result = dependencyLayers([{ id: 'a' }, { id: 'b' }], [{ from: 'a', to: 'b' }, { from: 'b', to: 'a' }])
  assert.deepEqual(result.cyclicNodeIds, ['a', 'b'])
  assert.equal(result.layers[0].unresolved, true)
  assert.equal(result.layers[0].level, null)
})

test('work keys use run plus actual attempt plus raw node identity and survive earlier insertions', () => {
  const trace = [meta(), model('same', { agentId: 'real' })]
  const baseline = buildRunPresentation(trace, live).nodes[0]
  const withEarlier = buildRunPresentation([meta('a0'), model('same', {}, 'a0', 2), ...trace], live)
  assert.equal(withEarlier.nodes.find(node => node.attemptId === 'a1').id, baseline.id)
  assert.notEqual(withEarlier.nodes[0].id, baseline.id)
  assert.equal(baseline.identityComplete, true)
})

test('same sequence in two attempts is retained, exact replay is deduplicated, late frames stay scoped', () => {
  const start2 = model('same', { agentId: 'real' }, 'a2', 2)
  const trace = [meta(), model('same', { agentId: 'real' }), frame('done', { finishReason: 'interrupted' }, 'a1', 3),
    meta('a2', 1, { resumed: true }), start2, start2, meta('a1'), frame('reasoning', { nodeId: 'same', agentId: 'real', text: 'late first' }, 'a1', 4)]
  const view = buildRunPresentation(trace, live)
  assert.deepEqual(view.nodes.map(node => [node.attemptId, node.reasoning]), [['a1', 'late first'], ['a2', '']])
  assert.equal(view.meta.attemptId, 'a2')
  assert.equal(view.nodes.length, 2)
  assert.notEqual(view.nodes[0].id, view.nodes[1].id)
})

test('traceId is a legacy display scope, not a reported attempt ID or recovery guarantee', () => {
  const view = buildRunPresentation([{ kind: 'meta', runId: 'r1', traceId: 'legacy-trace' }, { kind: 'model', nodeId: 'n1', agentId: 'real', status: 'running' }], live)
  assert.equal(view.nodes[0].attemptId, null)
  assert.equal(view.nodes[0].traceId, 'legacy-trace')
  assert.equal(view.nodes[0].identityComplete, false)
  assert.equal(view.summary.attemptId, null)
})

test('usage retains explicitly reported fields but does not invent a missing total', () => {
  const view = buildRunPresentation([meta(), model('n1', { inputTokens: 11, outputTokens: 7, usageReported: true }), frame('usage', { inputTokens: 20, outputTokens: 3, usageReported: true }, 'a1', 3)], live)
  assert.deepEqual(view.nodes[0].usage, { inputTokens: 11, outputTokens: 7, totalTokens: null })
  assert.deepEqual(view.summary.usage, { inputTokens: 20, outputTokens: 3, totalTokens: null })
  assert.deepEqual(reportedUsage({ inputTokens: 0, outputTokens: 0, totalTokens: 0 }), { inputTokens: 0, outputTokens: 0, totalTokens: 0 })
  assert.equal(reportedUsage({ inputTokens: 0, totalTokens: 0, usageReported: false }), null)
  assert.deepEqual(reportedUsage({ totalTokens: 0, usageReported: true }), { inputTokens: null, outputTokens: null, totalTokens: 0 })
})

test('partial usage reports preserve reported zero but never borrow another attempt', () => {
  const trace = [meta(), frame('usage', { inputTokens: 12, totalTokens: 20 }, 'a1', 2), meta('a2', 1, { resumed: true }), frame('usage', { outputTokens: 5 }, 'a2', 2)]
  const view = buildRunPresentation(trace, { ...live, usage: { inputTokens: 12, totalTokens: 20 } })
  assert.deepEqual(view.summary.usage, { inputTokens: null, outputTokens: 5, totalTokens: null })
  const missing = buildRunPresentation(trace.slice(0, -1), { ...live, usage: { totalTokens: 999 } })
  assert.equal(missing.summary.usage, null)
  assert.deepEqual(reportedUsage({ outputTokens: 3 }, reportedUsage({ inputTokens: 0, usageReported: true })), { inputTokens: 0, outputTokens: 3, totalTokens: null })
})

test('flat browser fallback offsets and local elapsedMs cannot become backend elapsed evidence', () => {
  const view = buildRunPresentation([{ kind: 'meta', runId: 'r1', attemptId: 'a1', sequence: 1, at: 30 },
    { kind: 'model', nodeId: 'n1', status: 'running', runId: 'r1', attemptId: 'a1', sequence: 2, at: 90 }], { ...live, elapsedMs: 8888 })
  assert.equal(view.summary.elapsed.ms, null)
  assert.equal(view.summary.elapsed.basis, null)
})

test('elapsed uses original backend offsets and ignores transport/local times and epoch timestamps', () => {
  const trace = [meta(), model('n1', { ms: 85 }), frame('done', { finishReason: 'stop', at: 96 }, 'a1', 3), { kind: 'error', transport: true, at: 9000 }]
  const view = buildRunPresentation(trace, { ...live, elapsedMs: 9000 })
  assert.deepEqual(view.summary.elapsed, { ms: 96, basis: 'event' })
  assert.equal(view.nodes[0].ms, 85)
  const epoch = buildRunPresentation([model('n1', { at: Date.UTC(2026, 0, 1) })], live)
  assert.equal(epoch.summary.elapsed.ms, null)
  assert.deepEqual(buildRunPresentation([], { elapsedSource: 'java_wall', elapsedMs: 1200 }).summary.elapsed, { ms: 1200, basis: 'wall' })
})

test('invalid numeric durations and string or negative usage stay unreported', () => {
  const view = buildRunPresentation([model('n1', { ms: -1, inputTokens: '10', outputTokens: -2, totalTokens: Infinity })], live)
  assert.equal(view.nodes[0].ms, null)
  assert.equal(view.nodes[0].usage, null)
})

test('local stop closure is not backend cancellation confirmation, including store-mutated open nodes', () => {
  const trace = [{ kind: 'meta', runId: 'r1', attemptId: 'a1' }, { kind: 'model', nodeId: 'n1', agentId: 'real', status: 'cancelled' },
    { kind: 'done', finishReason: 'cancelled', runId: 'r1', local: true }]
  const view = buildRunPresentation(trace, { status: 'cancelled', runId: 'r1' })
  assert.equal(view.summary.state, 'stop_requested')
  assert.equal(view.summary.cancel.unconfirmed, true)
  assert.equal(view.nodes[0].state, 'stop_requested')
  assert.equal(view.nodes[0].cancellationConfirmed, false)
})

test('transport interruption cannot confirm cancellation from a browser summary', () => {
  const trace = [meta(), model('n1'), { kind: 'error', transport: true, code: 'STREAM_INCOMPLETE', message: 'EOF' }]
  const view = buildRunPresentation(trace, { status: 'cancelled', runId: 'r1' })
  assert.equal(view.summary.state, 'interrupted')
  assert.equal(view.summary.cancel.confirmed, false)
  assert.notEqual(view.nodes[0].state, 'succeeded')
})

test('real cancellation acknowledgement preserves cancellation rather than pretending success', () => {
  const view = buildRunPresentation([meta(), model('n1'), frame('done', { finishReason: 'cancelled' }, 'a1', 3)], { status: 'cancelled' })
  assert.equal(view.summary.state, 'cancelled')
  assert.equal(view.summary.cancel.confirmed, true)
  assert.equal(view.summary.cancel.unconfirmed, false)
  assert.equal(view.nodes[0].state, 'cancelled')
})

test('a later real backend completion resolves a local stream closure', () => {
  const view = buildRunPresentation([meta(), model('n1', { status: 'done' }), { kind: 'done', local: true, finishReason: 'cancelled' }, frame('done', { finishReason: 'stop' }, 'a1', 3)], { status: 'cancelled' })
  assert.equal(view.summary.state, 'succeeded')
  assert.equal(view.summary.cancel.unconfirmed, false)
})

test('sources and artifacts attach only through actual node or call evidence, never role or timing', () => {
  const trace = [meta(), model('n1', { agentId: 'real', sourceIds: ['S1'] }), model('n2', { agentId: 'other' }, 'a1', 3),
    frame('sources', { items: [{ id: 'S1', title: 'Actual attachment' }, { id: 'S2', title: 'Unlinked attachment' }] }, 'a1', 4),
    frame('artifact', { artifact: { id: 'A1', kind: 'text', nodeId: 'n1', name: 'actual.txt' } }, 'a1', 5),
    frame('artifact', { artifact: { id: 'A2', agentId: 'other', name: 'unlinked.txt' } }, 'a1', 6)]
  const view = buildRunPresentation(trace, live)
  assert.deepEqual(view.nodes[0].sources.map(item => item.id), ['S1'])
  assert.deepEqual(view.nodes[0].artifacts.map(item => item.id), ['A1'])
  assert.deepEqual(view.nodes[1].sources, [])
  assert.deepEqual(view.nodes[1].artifacts, [])
  assert.equal(view.artifacts.length, 2)
  assert.equal(view.nodes.length, 2)
  assert.equal(view.edges.length, 0)
})

test('reused evidence IDs from two attempts do not assign one producer to the other', () => {
  const trace = [meta(), model('n1', { sourceIds: ['S'] }), frame('sources', { items: [{ id: 'S', title: 'first' }] }, 'a1', 3),
    frame('artifact', { artifact: { id: 'A', nodeId: 'n1', name: 'first.txt' } }, 'a1', 4), meta('a2', 1, { resumed: true }), model('n1', { sourceIds: ['S'] }, 'a2', 2),
    frame('sources', { items: [{ id: 'S', title: 'second' }] }, 'a2', 3), frame('artifact', { artifact: { id: 'A', nodeId: 'n1', name: 'second.txt' } }, 'a2', 4)]
  const view = buildRunPresentation(trace, live)
  assert.deepEqual(view.nodes.map(node => node.sources[0].title), ['first', 'second'])
  assert.deepEqual(view.nodes.map(node => node.artifacts[0].name), ['first.txt', 'second.txt'])
  assert.notEqual(view.artifacts[0].evidenceKey, view.artifacts[1].evidenceKey)
})

test('task and scope are reported values, and explicit unknown scope cannot borrow old options', () => {
  const scope = { chatIds: ['chat-a'], range: { from: '2026-11-22', to: '2026-11-23' } }
  const view = buildRunPresentation([meta('a1', 1, { task: 'Reported task', scope, modelCalls: 3 }), model('n1')], live)
  assert.equal(view.task, 'Reported task')
  assert.deepEqual(view.scope, scope)
  assert.equal(view.summary.reportedModelCalls, 3)
  const unknown = buildRunPresentation([meta('a1', 1, { scope: null })], { scope: { chatIds: ['old'] } })
  assert.equal(unknown.scope, null)
})

test('generic reported work nodes do not count as model calls without an explicit model kind', () => {
  const view = buildRunPresentation([meta(), frame('agent_node', { nodeId: 'task', agentId: 'actual', status: 'queued' }, 'a1', 2)], live)
  assert.equal(view.nodes.length, 1)
  assert.equal(view.nodes[0].kind, 'task')
  assert.equal(view.summary.recordedModelCalls, 0)
})

test('the display projection does not mutate frozen original trace or options', () => {
  const trace = freeze([meta(), model('n1', { agentId: 'real', args: { query: 'synthetic' }, parentIds: ['missing'] }),
    frame('artifact', { artifact: { id: 'A', nodeId: 'n1' } }, 'a1', 3)])
  const options = freeze({ ...live, usage: { totalTokens: 9 } })
  const before = JSON.stringify([trace, options])
  assert.doesNotThrow(() => buildRunPresentation(trace, options))
  assert.equal(JSON.stringify([trace, options]), before)
})

test('evidence formatting redacts credential-shaped keys and handles missing/circular data', () => {
  const args = { query: 'synthetic', apiKey: ['fixture', 'only', 'value'].join('-'), [['pass', 'word'].join('')]: 'fixture-only-value' }
  assert.doesNotMatch(evidenceText(args), /fixture-only-value/)
  assert.match(evidenceText(args), /redacted/)
  assert.equal(evidenceText(null), null)
  const circular = {}; circular.self = circular
  assert.equal(evidenceText(circular), null)
})


test('confirmed backend success cannot be overwritten by later local or transport terminals', () => {
  for (const end of [{ kind: 'done', local: true, finishReason: 'cancelled' }, { kind: 'done', transport: true }]) {
    const view = buildRunPresentation([meta(), model('n1', { status: 'done' }), frame('done', { finishReason: 'stop' }, 'a1', 3), end], { ...live, status: 'cancelled' })
    assert.equal(view.summary.state, 'succeeded')
    assert.equal(view.summary.cancel.unconfirmed, false)
  }
})

test('actual attempt and legacy trace namespaces cannot collide on an identical ID string', () => {
  const view = buildRunPresentation([{ kind: 'meta', runId: 'r1', traceId: 'x', sequence: 1 },
    { kind: 'model', runId: 'r1', nodeId: 'legacy', sequence: 2, status: 'running' },
    { kind: 'meta', runId: 'r1', attemptId: 'x', sequence: 1 },
    { kind: 'model', runId: 'r1', attemptId: 'x', nodeId: 'actual', sequence: 2, status: 'running' }], live)
  assert.deepEqual(view.nodes.map(node => [node.nodeId, node.attemptId]), [['legacy', null], ['actual', 'x']])
})

test('a same-frame explicit identity association can migrate legacy records without duplicating work', () => {
  const view = buildRunPresentation([{ kind: 'meta', runId: 'r1', traceId: 'legacy', sequence: 1 },
    { kind: 'model', runId: 'r1', nodeId: 'n1', sequence: 2, status: 'running' },
    { kind: 'meta', runId: 'r1', traceId: 'legacy', attemptId: 'actual', sequence: 1 }], live)
  assert.equal(view.nodes.length, 1)
  assert.equal(view.nodes[0].attemptId, 'actual')
  assert.equal(view.meta.attemptId, 'actual')
})

test('two tool calls under one reported node retain call identity, results and artifact linkage', () => {
  const trace = [meta(), frame('tool_call', { nodeId: 'tools', callId: 'c1', toolName: 'readA' }, 'a1', 2),
    frame('tool_call', { nodeId: 'tools', callId: 'c2', toolName: 'readB' }, 'a1', 3),
    frame('tool_result', { nodeId: 'tools', callId: 'c1', status: 'SUCCESS', latencyMs: 7 }, 'a1', 4),
    frame('artifact', { artifact: { id: 'A', callIds: ['c1'], name: 'readA.txt' } }, 'a1', 5)]
  const view = buildRunPresentation(trace, live)
  assert.equal(view.summary.recordedToolCalls, 2)
  assert.deepEqual(view.nodes.map(node => [node.callId, node.toolName]), [['c1', 'readA'], ['c2', 'readB']])
  assert.notEqual(view.nodes[0].id, view.nodes[1].id)
  assert.deepEqual(view.nodes.map(node => node.artifacts.map(item => item.id)), [['A'], []])
})

test('explicit model call IDs sharing a node cannot fold reasoning or usage into their sibling', () => {
  const trace = [meta(), model('shared', { callId: 'm1', agentId: 'real' }), model('shared', { callId: 'm2', agentId: 'real' }, 'a1', 3),
    frame('reasoning', { nodeId: 'shared', callId: 'm1', agentId: 'real', text: 'first only' }, 'a1', 4)]
  const view = buildRunPresentation(trace, live)
  assert.equal(view.summary.recordedModelCalls, 2)
  assert.deepEqual(view.nodes.map(node => node.reasoning), ['first only', ''])
})

test('reported zero replaces an old positive value while known legacy accumulator zeros stay unknown', () => {
  assert.deepEqual(reportedUsage({ totalTokens: 0 }, { inputTokens: null, outputTokens: null, totalTokens: 12 }), { inputTokens: null, outputTokens: null, totalTokens: 0 })
  const legacy = buildRunPresentation([{ kind: 'model', nodeId: 'n1', status: 'done', inputTokens: 0, outputTokens: 0, usageSource: 'legacy_accumulator' }])
  assert.equal(legacy.nodes[0].usage, null)
})

test('explicit local timing markers are rejected even if a frame is wrapped', () => {
  const view = buildRunPresentation([frame('stage', { phase: 'model', nodeId: 'n1', at: 500, fallbackAt: true })], live)
  assert.equal(view.summary.elapsed.ms, null)
})

test('source envelope identity is inherited and conflicting source/producer identities are not linked', () => {
  const trace = [meta(), model('n1', { sourceIds: ['good', 'bad'] }),
    frame('sources', { items: [{ id: 'good', title: 'reported' }, { id: 'bad', attemptId: 'other', title: 'wrong attempt' }] }, 'a1', 3),
    frame('artifact', { artifact: { id: 'bad-artifact', runId: 'other-run', nodeId: 'n1' } }, 'a1', 4)]
  const view = buildRunPresentation(trace, live)
  assert.deepEqual(view.nodes[0].sources.map(item => [item.id, item.runId, item.attemptId]), [['good', 'r1', 'a1']])
  assert.equal(view.artifacts.length, 0)
})


test('generic zero usage survives the complete model projection without identity or report flags', () => {
  for (const fields of [{ totalTokens: 0 }, { inputTokens: 0, outputTokens: 0 }, { usage: { totalTokens: 0 } }]) {
    const view = buildRunPresentation([{ kind: 'model', nodeId: 'generic', status: 'done', ...fields }])
    assert.ok(view.nodes[0].usage)
    if (Object.hasOwn(fields, 'inputTokens')) assert.equal(view.nodes[0].usage.inputTokens, 0)
    else assert.equal(view.nodes[0].usage.totalTokens, 0)
  }
})

test('artifact node and related call evidence are distinct associations, not two producers', () => {
  const trace = [meta(), model('shared', {}, 'a1', 2), frame('tool_call', { nodeId: 'shared', callId: 'tool', toolName: 'lookup' }, 'a1', 3),
    frame('artifact', { artifact: { id: 'A', nodeId: 'shared', callIds: ['tool'], name: 'result.txt' } }, 'a1', 4)]
  const view = buildRunPresentation(trace, live)
  assert.deepEqual(view.nodes.map(node => node.artifacts[0]?.relation), ['node_association', 'call_association'])
  assert.ok(view.nodes.every(node => !node.artifacts.some(item => item.relation === 'producer')))
  assert.deepEqual(view.edges, []) // Related artifact evidence does not invent a handoff.
})

test('unmatched explicit artifact constraints cannot be overridden by an artifactIds-only link', () => {
  const trace = [meta(), model('n1', { artifactIds: ['A'] }), frame('artifact', { artifact: { id: 'A', nodeId: 'other', callIds: ['other-call'] } }, 'a1', 3)]
  const view = buildRunPresentation(trace, live)
  assert.deepEqual(view.nodes[0].artifacts, [])
  assert.equal(view.artifacts.length, 1)
})

test('resume without a fresh scope report cannot borrow authorization from the old attempt', () => {
  const view = buildRunPresentation([meta(), meta('a2', 1, { resumed: true })], { scope: { chatIds: ['old'] } })
  assert.equal(view.scope, null)
})

test('a static report without lifecycle evidence does not fabricate an EOF or run termination', () => {
  const view = buildRunPresentation([{ kind: 'model', nodeId: 'n1', status: 'queued' }])
  assert.equal(view.summary.state, 'unknown')
  assert.equal(view.nodes[0].state, 'queued')
})

test('an unknown node role does not contradict an explicit artifact node association or invent a role', () => {
  const trace = [meta(), model('n1'), frame('artifact', { artifact: { id: 'A', nodeId: 'n1', agentId: 'assistant' } }, 'a1', 3)]
  const view = buildRunPresentation(trace, live)
  assert.equal(view.nodes[0].artifacts[0].relation, 'node_association')
  assert.equal(view.nodes[0].agentId, null)
  assert.equal(view.roles.length, 0)
})

test('missing node status stays unreported even when context or retrieval counts are present', () => {
  const view = buildRunPresentation([{ kind: 'context', shortTerm: 2, recalled: 0, ms: 4 }, { kind: 'knowledge', chunks: 0 }, { kind: 'knowledge', chunks: null }])
  assert.deepEqual(view.nodes.map(node => node.state), ['unknown', 'unknown', 'unknown'])
})

test('local scope selections cannot masquerade as a backend authorization snapshot', () => {
  const scope = { chatIds: ['selected-but-unconfirmed'] }
  assert.equal(buildRunPresentation([], { scope }).scope, null)
  assert.deepEqual(buildRunPresentation([], { scope, scopeSource: 'backend' }).scope, scope)
})


test('explicit partial and cancelled verdicts are not overwritten by generic provider finish or error detail', () => {
  const partial = buildRunPresentation([meta(), model('n1', { status: 'partial', finishReason: 'stop' }), frame('done', { status: 'partial', finishReason: 'stop' }, 'a1', 3)])
  assert.equal(partial.summary.state, 'partial')
  assert.equal(partial.nodes[0].state, 'partial')
  const cancelled = buildRunPresentation([meta(), model('n1', { status: 'cancelled', error: 'request closed' }), frame('done', { finishReason: 'cancelled' }, 'a1', 3)])
  assert.equal(cancelled.nodes[0].state, 'cancelled')
})

test('explicit run error with node and call attribution still overrides a falsely clean finish', () => {
  const trace = [meta(), model('n1', { callId: 'm1' }), frame('error', { nodeId: 'n1', callId: 'm1', errorScope: 'run', message: 'run failed' }, 'a1', 3), frame('done', { finishReason: 'stop' }, 'a1', 4)]
  assert.equal(buildRunPresentation(trace).summary.state, 'failed')
})

test('explicit agent-local errors do not become global failures just because the role is named assistant', () => {
  const trace = [meta(), model('n1', { agentId: 'assistant' }), frame('error', { nodeId: 'n1', agentId: 'assistant', errorScope: 'agent', message: 'local failure' }, 'a1', 3), frame('done', { finishReason: 'stop' }, 'a1', 4)]
  const view = buildRunPresentation(trace)
  assert.equal(view.nodes[0].state, 'failed')
  assert.equal(view.summary.state, 'succeeded')
})

test('transport-scoped errors are connection interruptions, not confirmed model or run failures', () => {
  const trace = [meta(), model('n1'), frame('error', { nodeId: 'n1', errorScope: 'transport', message: 'connection lost' }, 'a1', 3)]
  const view = buildRunPresentation(trace, { ...live, status: 'error' })
  assert.equal(view.summary.state, 'interrupted')
  assert.notEqual(view.nodes[0].state, 'failed')
})

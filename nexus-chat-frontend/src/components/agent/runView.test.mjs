import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildRunView, formatTraceText } from './runView.js'

const model = (extra = {}) => ({ kind: 'model', nodeId: 'model-1', agentId: 'assistant', iteration: 1, model: 'actual-model', status: 'running', at: 40, ...extra })

test('counts actual model nodes and tools instead of pretending tools are agents', () => {
  const trace = [
    { kind: 'context', nodeId: 'context', status: 'done', shortTerm: 2, recalled: 0, at: 20, ms: 20 },
    model({ parentIds: ['context'] }),
    { kind: 'tool', nodeId: 'tool-c1', callId: 'c1', toolName: 'get_recent_messages', parentIds: ['model-1'], status: 'SUCCESS', at: 60, latencyMs: 12 },
    { kind: 'tool', nodeId: 'tool-c2', callId: 'c2', toolName: 'get_chat_profile', parentIds: ['model-1'], status: 'SUCCESS', at: 62, latencyMs: 8 },
    model({ status: 'done', at: 90, ms: 50 })
  ]
  const view = buildRunView(trace, { live: false, elapsedMs: 95 })
  assert.equal(view.summary.calls, 1)
  assert.equal(view.summary.tools, 2)
  assert.equal(view.summary.agents, 1)
  assert.equal(view.summary.steps, 4)
  assert.equal(view.nodes.find(n => n.id === 'model-1').state, 'done')
  assert.deepEqual(view.edges, [
    { from: 'context', to: 'model-1' },
    { from: 'model-1', to: 'tool-c1' },
    { from: 'model-1', to: 'tool-c2' }
  ])
})

test('joins native reasoning deltas under one real call without counting chunks as steps', () => {
  const trace = [model(),
    { kind: 'reasoning', nodeId: 'model-1', agentId: 'assistant', iteration: 1, text: '先核对', at: 45 },
    { kind: 'reasoning', nodeId: 'model-1', agentId: 'assistant', iteration: 1, text: '用户输入。', at: 49 },
    { kind: 'answer', chars: 20, at: 52 }
  ]
  const view = buildRunView(trace, { live: true })
  assert.equal(view.summary.steps, 1)
  assert.equal(view.nodes[0].reasoning, '先核对用户输入。')
  assert.equal(view.reasoning, '先核对用户输入。')
  assert.equal(view.summary.runningAgents, 1)
})

test('matches repeated tool names by call identity and preserves independent status', () => {
  const trace = [
    { kind: 'tool', callId: 'a', toolName: 'get_recent_messages', status: 'running', at: 5 },
    { kind: 'tool', callId: 'b', toolName: 'get_recent_messages', status: 'running', at: 6 },
    { kind: 'tool', callId: 'a', toolName: 'get_recent_messages', status: 'FAILED', error: 'forbidden', at: 8 },
    { kind: 'tool', callId: 'b', toolName: 'get_recent_messages', status: 'SUCCESS', latencyMs: 4, at: 10 }
  ]
  const view = buildRunView(trace, { live: true })
  assert.equal(view.nodes.length, 2)
  assert.equal(view.nodes[0].state, 'error')
  assert.equal(view.nodes[1].state, 'done')
  assert.equal(view.nodes[0].detail, 'forbidden')
})

test('shows real collaboration identities and does not invent a role that never started', () => {
  const trace = [
    model({ nodeId: 'code-1', agentId: 'code' }),
    model({ nodeId: 'doc-1', agentId: 'doc' }),
    { kind: 'agent_output', nodeId: 'code-1', agentId: 'code', text: '实际分析输出', at: 50 }
  ]
  const view = buildRunView(trace, { live: true })
  assert.deepEqual(view.agents.map(a => a.id), ['code', 'doc'])
  assert.equal(view.summary.runningAgents, 2)
  assert.equal(view.nodes[0].output, '实际分析输出')
  assert.equal(view.reasoning, '')
  assert.equal(view.summary.progress, undefined)
})

test('cancellation and incomplete EOF never become successful completion', () => {
  const cancelled = buildRunView([model(), { kind: 'done', finishReason: 'cancelled', at: 80 }], { live: false, elapsedMs: 80 })
  assert.equal(cancelled.summary.status, 'cancelled')
  assert.equal(cancelled.nodes[0].state, 'cancelled')
  const interrupted = buildRunView([model()], { live: false, elapsedMs: 100 })
  assert.equal(interrupted.summary.status, 'interrupted')
  assert.equal(interrupted.nodes[0].state, 'interrupted')
  const failed = buildRunView([model(), { kind: 'error', code: 'NETWORK', message: '连接中断', at: 80 }], { live: false })
  assert.equal(failed.summary.status, 'error')
})

test('token display uses provider usage and otherwise remains unknown', () => {
  assert.equal(buildRunView([model({ status: 'done', ms: 5 })], { live: false }).summary.totalTokens, null)
  const view = buildRunView([model({ status: 'done', inputTokens: 10, outputTokens: 5, usageReported: true }),
    { kind: 'usage', inputTokens: 10, outputTokens: 5, totalTokens: 15, usageReported: true, at: 90 }
  ], { live: false })
  assert.equal(view.summary.totalTokens, 15)
  assert.equal(view.nodes[0].usage.totalTokens, 15)
  assert.equal(buildRunView([{ kind: 'usage', inputTokens: 0, outputTokens: 0, totalTokens: 0, usageReported: false }], {}).summary.totalTokens, null)
})

test('sources and artifacts are actual deduplicated events, not seeded sample files', () => {
  const artifact = { id: 'artifact-1', name: 'actual.json', kind: 'json', content: '{"value":1}', sizeBytes: 11 }
  const trace = [
    { kind: 'sources', items: [{ id: 'A1', kind: 'attachment', title: 'input.txt' }], at: 1 },
    { kind: 'sources', items: [{ id: 'A1', kind: 'attachment', title: 'input.txt' }, { id: 'W1', kind: 'web', title: '实际来源' }], at: 2 },
    { kind: 'artifact', artifact, at: 3 }, { kind: 'artifact', artifact, at: 4 }
  ]
  const view = buildRunView(trace, {})
  assert.deepEqual(view.artifacts.map(a => a.name), ['actual.json'])
  assert.deepEqual(view.sources.map(s => s.id), ['A1', 'W1'])
  assert.equal(buildRunView([], { live: true }).nodes.length, 0)
  assert.equal(buildRunView([], {}).artifacts.length, 0)
})

test('unknown phases remain readable without crashing or leaking phantom dependencies', () => {
  const view = buildRunView([{ kind: 'custom-phase', text: 'actual event', at: 20 }, model({ parentIds: ['missing-node'] })], { live: true })
  assert.equal(view.edges.length, 0)
  assert.equal(view.nodes.length, 1)
  assert.equal(view.summary.steps, 1)
})

test('resumed attempts retain separate nodes without replaying a duplicate meta or text', () => {
  const first = { kind: 'meta', traceId: 'first', sequence: 1 }
  const second = { kind: 'meta', traceId: 'second', resumed: true, sequence: 1 }
  const v = buildRunView([first, model({ sequence: 2 }), { kind: 'reasoning', iteration: 1, sequence: 3, text: 'original' }, { kind: 'error', message: 'lost' }, second, model({ sequence: 2 }), { kind: 'reasoning', iteration: 1, sequence: 3, text: 'resumed' }, { kind: 'done', finishReason: 'stop', sequence: 4 }, second], { live: true })
  assert.deepEqual(v.nodes.map(n => n.reasoning), ['original', 'resumed'])
  assert.notEqual(v.nodes[0].id, v.nodes[1].id)
  assert.equal(v.summary.status, 'done')
})

test('wrapped frames retain identity/timing and terminal errors outrank connection liveness', () => {
  const event = { name: 'stage', sequence: 1, at: 50, data: { phase: 'model', status: 'start', nodeId: 'm', model: 'real' } }
  const v = buildRunView([event, { name: 'reasoning', at: 60, sequence: 2, data: { nodeId: 'm', text: 'once' } }, { name: 'reasoning', at: 60, sequence: 2, data: { nodeId: 'm', text: 'once' } }, { kind: 'error', message: 'timeout' }], { live: true })
  assert.equal(v.nodes[0].at, 50)
  assert.equal(v.reasoning, 'once')
  assert.equal(v.summary.elapsedMs, 60)
  assert.equal(v.summary.status, 'error')
})

test('a tool error is failed even without status and an unknown tool outcome is never success', () => {
  const v = buildRunView([{ kind: 'tool', callId: 'bad', toolName: 'lookup', error: 'denied' }, { kind: 'tool_result', callId: 'unknown', toolName: 'read' }], { live: true })
  assert.deepEqual(v.nodes.map(n => n.state), ['error', 'unknown'])
})

test('missing usage does not erase reported fields or a previously reported total', () => {
  const v = buildRunView([model({ status: 'done', outputTokens: 9 }), { kind: 'usage', totalTokens: 17 }, { kind: 'usage', usageReported: false, totalTokens: 0 }])
  assert.equal(v.summary.totalTokens, 17)
  assert.equal(v.nodes[0].usage.outputTokens, 9)
  assert.equal(v.nodes[0].usage.totalTokens, null)
})

test('copying the execution flow includes native text and real tool details', () => {
  const text = formatTraceText([model(), { kind: 'reasoning', iteration: 1, nodeId: 'model-1', text: '只输出上游给出的思考。', at: 45 },
    { kind: 'tool', callId: 'c1', toolName: 'get_recent_messages', args: { chat_id: 3 }, status: 'FAILED', error: 'forbidden', at: 50 }
  ])
  assert.ok(text.includes('actual-model'))
  assert.ok(text.includes('只输出上游给出的思考。'))
  assert.ok(text.includes('get_recent_messages'))
  assert.ok(text.includes('"chat_id":3'))
  assert.ok(text.includes('forbidden'))
})

test('a replayed meta cannot redirect the next frame; sequence numbers are local to run and attempt', () => {
  const meta = runId => ({ kind: 'meta', runId, attemptId: 'same', sequence: 1 })
  const v = buildRunView([meta('r1'), model({ sequence: 2 }), meta('r2'), model({ sequence: 2 }),
    meta('r1'), { kind: 'reasoning', nodeId: 'model-1', text: 'second run', sequence: 3 },
    { kind: 'agent_output', runId: 'r1', attemptId: 'same', nodeId: 'model-1', text: 'late first output', sequence: 3 }
  ], { live: true })
  assert.equal(v.meta.runId, 'r2')
  assert.deepEqual(v.nodes.map(n => [n.reasoning, n.output, n.state]), [
    ['', 'late first output', 'interrupted'], ['second run', '', 'running']
  ])
  assert.notEqual(v.nodes[0].id, v.nodes[1].id)
})

test('unattributed native thoughts survive copy without creating agents, steps, or model calls', () => {
  const thought = { kind: 'reasoning', agentId: 'code', iteration: 2, text: 'unattributed' }
  const orphan = buildRunView([thought])
  assert.deepEqual([orphan.summary.calls, orphan.summary.agents, orphan.summary.steps], [0, 0, 0])
  assert.equal(orphan.reasoning, 'unattributed')
  const trace = [model({ agentId: 'code', iteration: 2, nodeId: 'c' }), model({ agentId: 'code', iteration: 2, nodeId: 'd' }),
    model({ agentId: 'doc', iteration: 2, nodeId: 'e' }), thought, { ...thought, agentId: 'doc', text: 'document' }]
  const v = buildRunView(trace)
  assert.deepEqual(v.nodes.filter(n => n.kind === 'model').map(n => n.reasoning), ['', '', 'document'])
  assert.equal(v.summary.calls, 3)
  assert.match(formatTraceText(trace), /unattributed/)
})

test('a fresh legacy start is not another delta for the previous call', () => {
  const start = model({ nodeId: undefined })
  const v = buildRunView([start, { ...start, status: 'done' }, start,
    { kind: 'reasoning', agentId: 'assistant', iteration: 1, text: 'not uniquely identified' }], { live: true })
  assert.equal(v.summary.calls, 2)
  assert.deepEqual(v.nodes.filter(n => n.kind === 'model').map(n => [n.state, n.reasoning]), [['done', ''], ['running', '']])
  assert.match(v.reasoning, /not uniquely identified/)
})

test('epoch milliseconds become elapsed times, without rebasing ordinary elapsed offsets', () => {
  const epoch = Date.UTC(2026, 0, 1)
  const trace = [model({ at: epoch + 15 }), { kind: 'done', finishReason: 'stop', at: epoch + 95 }]
  const anchored = buildRunView(trace, { startedAt: new Date(epoch).toISOString() })
  assert.deepEqual([anchored.nodes[0].at, anchored.summary.elapsedMs], [15, 95])
  const unanchored = buildRunView(trace)
  assert.deepEqual([unanchored.nodes[0].at, unanchored.summary.elapsedMs], [0, 80])
  assert.equal(buildRunView([model({ at: 40 })], { startedAt: epoch }).nodes[0].at, 40)
})

test('errors update the identified role or tool, not its siblings', () => {
  const trace = [model({ agentId: 'code' }), model({ nodeId: 'd', agentId: 'doc' }),
    { kind: 'tool_call', callId: 't', nodeId: 'tool-node', toolName: 'lookup' },
    { kind: 'tool_result', callId: 't', error: 'tool failure' },
    { kind: 'error', agentId: 'code', iteration: 1, message: 'role failure' }]
  const v = buildRunView(trace, { live: true })
  assert.equal(v.summary.status, 'running')
  assert.deepEqual(v.nodes.map(n => [n.state, n.detail]), [['error', 'role failure'], ['running', ''], ['error', 'tool failure']])
  const ended = buildRunView([...trace, { kind: 'error', message: 'disconnected' }], { live: true })
  assert.equal(ended.summary.status, 'error')
  assert.equal(ended.nodes[1].state, 'interrupted')
})

test('a limited attempt stays limited after resume, not running or successfully finished', () => {
  const v = buildRunView([{ kind: 'meta', traceId: 'one' }, model(), { kind: 'done', finishReason: 'length' },
    { kind: 'meta', traceId: 'two', resumed: true }, model(), { kind: 'done', finishReason: 'cancelled' }], { live: true })
  assert.equal(v.summary.status, 'cancelled')
  assert.deepEqual(v.nodes.map(n => n.state), ['limited', 'cancelled'])
  assert.equal(v.summary.finishedSteps, 0)
  assert.equal(buildRunView([model()], { status: 'truncated', live: true }).summary.status, 'limited')
})

test('partial usage updates preserve previously reported components, including zero', () => {
  const v = buildRunView([model({ inputTokens: 0, usageReported: true }), model({ outputTokens: 3, status: 'done' }),
    { kind: 'usage', outputTokens: 4, usageReported: true }, { kind: 'usage', usageReported: false, inputTokens: 0 }])
  assert.deepEqual(v.nodes[0].usage, { inputTokens: 0, outputTokens: 3, totalTokens: 3 })
  assert.deepEqual(v.usage, { inputTokens: null, outputTokens: 4, totalTokens: null })
})

test('legacy completion facts remain readable without treating unknown stage outcomes as success', () => {
  const v = buildRunView([{ kind: 'context', shortTerm: 2, recalled: 0 }, { kind: 'knowledge', chunks: null },
    { name: 'stage', data: { phase: 'web_search', nodeId: 'w', status: 'unreported' } }, { kind: 'mock' }])
  assert.deepEqual(v.nodes.map(n => n.kind), ['context', 'knowledge', 'web_search', 'mock'])
  assert.equal(v.summary.finishedSteps, 1)
  assert.equal(v.summary.calls, 0)
})

test('a new partial usage report cannot borrow missing counts from a previous attempt', () => {
  const v = buildRunView([{ kind: 'meta', traceId: 'first' }, { kind: 'usage', inputTokens: 10 },
    { kind: 'meta', traceId: 'second', resumed: true }, { kind: 'usage', outputTokens: 5 }])
  assert.deepEqual(v.usage, { inputTokens: null, outputTokens: 5, totalTokens: null })
  assert.equal(v.summary.totalTokens, null)
})

test('adjacent unattributed reasoning chunks remain verbatim and separate across attempts', () => {
  const v = buildRunView([{ kind: 'meta', traceId: 'first' }, { kind: 'reasoning', iteration: 2, text: 'un' },
    { kind: 'reasoning', iteration: 2, text: 'linked' }, { kind: 'meta', traceId: 'second', resumed: true },
    { kind: 'reasoning', iteration: 2, text: 'retry' }])
  assert.equal(v.reasoning, 'unlinked\n\nretry')
  assert.equal(v.summary.calls, 0)
})

test('a turn that went on without tools shows that as a skipped step, not as a failure or a model role', () => {
  const view = buildRunView([
    { kind: 'meta', runId: 'run_1', traceId: 'tr_1' },
    { kind: 'tools', status: 'unavailable', message: 'tool service unavailable (timeout)' },
    { kind: 'model', status: 'done', iteration: 1, model: 'actual-model' },
    { kind: 'done', finishReason: 'stop' }
  ])
  const step = view.nodes.find(n => n.kind === 'tools')
  assert.equal(step.state, 'skipped')
  assert.equal(step.detail, 'tool service unavailable (timeout)')
  assert.equal(view.summary.status, 'done')
  assert.equal(view.agents.length, 1)
})

test('an early end of the stream leaves the status the turn actually ended with', () => {
  const trace = [{ kind: 'error', transport: true, code: 'STREAM_INCOMPLETE', message: 'early EOF' }]
  assert.equal(buildRunView(trace, { status: 'interrupted', live: false }).summary.status, 'interrupted')
  assert.equal(buildRunView(trace, { status: 'cancelled', live: false }).summary.status, 'cancelled')
  // A real failure still wins over a summary that claims success.
  const failed = [{ kind: 'error', code: 'AGENT_MODEL_50202', message: 'upstream refused' }]
  assert.equal(buildRunView(failed, { status: 'stop', live: false }).summary.status, 'error')
})

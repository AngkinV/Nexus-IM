import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildRunPresentation } from './runPresentation.js'
import { agentColor, buildPixelScene, poseOf } from './pixelScene.js'

// Frames shaped like the ones the Python orchestrator sends.
const frame = (name, data) => ({ name, data: { runId: 'r1', attemptId: 'a1', ...data } })
const stage = data => frame('stage', data)
const scene = (trace, options = { live: true }, limits) => buildPixelScene(buildRunPresentation(trace, options), limits)

const toolTurn = [
  frame('meta', { traceId: 't1' }),
  stage({ phase: 'context', status: 'done', shortTerm: 2, recalled: 0, ms: 4 }),
  stage({ phase: 'model', status: 'start', agentId: 'assistant', nodeId: 'model-1', iteration: 1, parentIds: ['context'], model: 'm' }),
  frame('reasoning', { agentId: 'assistant', nodeId: 'model-1', iteration: 1, text: '先读最近的消息' }),
  stage({ phase: 'model', status: 'done', agentId: 'assistant', nodeId: 'model-1', iteration: 1, finishReason: 'tool_calls' }),
  frame('tool_call', { toolName: 'get_recent_messages', callId: 'call_a', nodeId: 'tool-call_a', agentId: 'assistant', parentIds: ['model-1'], args: { chat_id: 5 } }),
  frame('tool_result', { toolName: 'get_recent_messages', callId: 'call_a', nodeId: 'tool-call_a', agentId: 'assistant', status: 'SUCCESS', latencyMs: 12 }),
  stage({ phase: 'model', status: 'start', agentId: 'assistant', nodeId: 'model-2', iteration: 2, parentIds: ['tool-call_a'], model: 'm' }),
  frame('reasoning', { agentId: 'assistant', nodeId: 'model-2', iteration: 2, text: '消息里提到周五前交付，\n整理成结论' })
]

test('a turn with no reported step has an empty room', () => {
  assert.deepEqual(scene([], { live: false }), { cells: [], lines: [], earlier: 0, working: false })
})

test('one reported agent is one figure however many calls it makes, and its tools stand beside it', () => {
  const room = scene(toolTurn)
  assert.deepEqual(room.cells.map(cell => [cell.type, cell.type === 'actor' ? cell.agentId : cell.kind]),
    [['prop', 'context'], ['actor', 'assistant'], ['prop', 'tool']])
  const [shelf, figure, terminal] = room.cells
  assert.deepEqual([figure.calls, figure.state, figure.pose, figure.handoffs], [2, 'running', 'working', []])
  assert.deepEqual([terminal.label, terminal.icon, terminal.state, terminal.agentId], ['get_recent_messages', 'terminal', 'succeeded', 'assistant'])
  assert.deepEqual(terminal.color, agentColor('assistant'))
  assert.equal(shelf.color, null)
  assert.equal(room.working, true)
})

test('the dialog box carries the running step\'s own latest words and nothing else', () => {
  const room = scene(toolTurn)
  assert.deepEqual(room.lines.map(line => [line.agentId, line.text]), [['assistant', '消息里提到周五前交付， 整理成结论']])
  // The step that already ended said something too; it is not repeated as if it were being said now.
  assert.doesNotMatch(JSON.stringify(room.lines), /先读最近的消息/)
  const finished = scene([...toolTurn, stage({ phase: 'model', status: 'done', agentId: 'assistant', nodeId: 'model-2', iteration: 2, finishReason: 'stop' }),
    frame('done', { finishReason: 'stop' })], { live: false })
  assert.deepEqual([finished.lines, finished.working], [[], false])
  assert.equal(finished.cells[1].pose, 'done')
  const long = scene([stage({ phase: 'model', status: 'start', agentId: 'assistant', nodeId: 'm' }),
    frame('reasoning', { agentId: 'assistant', nodeId: 'm', text: '字'.repeat(300) + '结尾' })])
  assert.equal([...long.lines[0].text].length, 97)
  assert.match(long.lines[0].text, /^…字+结尾$/)
})

test('a running step that reported no reasoning is shown working without any words put in its mouth', () => {
  const room = scene([stage({ phase: 'model', status: 'start', agentId: 'assistant', nodeId: 'model-1' })])
  assert.deepEqual([room.cells[0].pose, room.lines], ['working', []])
})

test('collaboration: each analyst is its own figure and the synthesis holds a paper from each of them', () => {
  const analysts = ['code', 'doc', 'arch']
  const trace = [stage({ phase: 'collaboration', status: 'start', nodeId: 'collaboration', parentIds: ['context'] }),
    ...analysts.map(id => stage({ phase: 'model', status: 'done', agentId: id, nodeId: 'collaboration-' + id, parentIds: ['collaboration'] })),
    stage({ phase: 'model', status: 'start', agentId: 'synth', nodeId: 'collaboration-synth', parentIds: analysts.map(id => 'collaboration-' + id) })]
  const room = scene(trace)
  const figures = room.cells.filter(cell => cell.type === 'actor')
  assert.deepEqual(figures.map(cell => cell.agentId), [...analysts, 'synth'])
  assert.equal(new Set(figures.map(cell => cell.color.main)).size, 4)
  assert.deepEqual(figures.at(-1).handoffs.map(paper => paper.agentId), analysts)
  assert.deepEqual(figures.at(-1).handoffs.map(paper => paper.color), analysts.map(agentColor))
  for (const analyst of figures.slice(0, 3)) assert.deepEqual(analyst.handoffs, [])
})

test('a step without a reported agent is a prop, never a made-up figure', () => {
  const room = scene([stage({ phase: 'model', status: 'done', nodeId: 'model-1', model: 'actual-model' }), stage({ phase: 'mock' })], { live: false })
  assert.deepEqual(room.cells.map(cell => [cell.type, cell.icon, cell.label]), [['prop', 'chip', 'actual-model'], ['prop', 'flask', null]])
})

test('every reported state has its own stance, and an unknown one is not drawn as finished', () => {
  const stances = Object.fromEntries(['running', 'waiting_tool', 'queued', 'succeeded', 'partial', 'limited', 'failed', 'cancelled',
    'stop_requested', 'interrupted', 'skipped', 'unknown', 'not-a-state'].map(state => [state, poseOf(state)]))
  assert.deepEqual(stances, { running: 'working', waiting_tool: 'waiting', queued: 'idle', succeeded: 'done', partial: 'limited',
    limited: 'limited', failed: 'failed', cancelled: 'stopped', stop_requested: 'stopped', interrupted: 'stopped', skipped: 'skipped',
    unknown: 'unknown', 'not-a-state': 'unknown' })
})

test('a reused tool result is marked as reused', () => {
  const room = scene([frame('tool_call', { toolName: 'lookup', callId: 'c', nodeId: 'tool-c', agentId: 'assistant' }),
    frame('tool_result', { toolName: 'lookup', callId: 'c', nodeId: 'tool-c', agentId: 'assistant', status: 'SUCCESS', reused: true, producerAttemptId: 'a0' })])
  assert.deepEqual([room.cells[0].reused, room.cells[0].state], [true, 'succeeded'])
})

test('a long turn folds its oldest finished props into one counter and keeps every figure and unfinished step', () => {
  const tools = Array.from({ length: 14 }, (_, i) => [
    frame('tool_call', { toolName: 'tool_' + i, callId: 'c' + i, nodeId: 'tool-c' + i, agentId: 'assistant', parentIds: ['model-1'] }),
    ...(i < 13 ? [frame('tool_result', { toolName: 'tool_' + i, callId: 'c' + i, nodeId: 'tool-c' + i, status: 'SUCCESS' })] : [])]).flat()
  const room = scene([stage({ phase: 'model', status: 'done', agentId: 'assistant', nodeId: 'model-1' }), ...tools], { live: true }, { max: 8 })
  assert.equal(room.cells.length, 8)
  const more = room.cells.find(cell => cell.type === 'more')
  assert.equal(more.count, 8)
  assert.equal(room.cells.filter(cell => cell.type === 'actor').length, 1)
  assert.equal(room.cells.at(-1).label, 'tool_13') // still running, so still in the room
  assert.equal(more.count + room.cells.length - 1, 15)
})

test('the room shows the latest attempt and says how many earlier nodes are in the details', () => {
  const first = [frame('meta', {}), stage({ phase: 'model', status: 'failed', agentId: 'assistant', nodeId: 'model-1' }), frame('done', { finishReason: 'error' })]
  const second = [frame('meta', { attemptId: 'a2', resumed: true }), stage({ phase: 'model', status: 'start', agentId: 'assistant', nodeId: 'model-1', attemptId: 'a2' })]
  const room = scene([...first, ...second])
  assert.deepEqual([room.cells.length, room.cells[0].state, room.earlier], [1, 'running', 1])
})

test('a colour belongs to an agent id: stable, shared by nothing it was not given to, absent without an id', () => {
  assert.deepEqual(agentColor('researcher-7'), agentColor('researcher-7'))
  assert.equal(new Set(['assistant', 'code', 'doc', 'arch', 'synth'].map(id => agentColor(id).main)).size, 5)
  assert.equal(agentColor(null), null)
  assert.equal(agentColor(''), null)
})

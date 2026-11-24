import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildRunPresentation } from './runPresentation.js'
import { agentColor, buildPixelScene, layoutMap, MAP, pathLength, pointAt, poseOf } from './pixelScene.js'

// Frames shaped like the ones the Python orchestrator sends.
const frame = (name, data) => ({ name, data: { runId: 'r1', attemptId: 'a1', ...data } })
const stage = data => frame('stage', data)
const scene = (trace, options = { live: true }, limits) => buildPixelScene(buildRunPresentation(trace, options), limits)

const toolTurn = [
  frame('meta', { traceId: 't1' }),
  stage({ phase: 'context', nodeId: 'context', status: 'done', shortTerm: 2, recalled: 0, ms: 4 }),
  stage({ phase: 'model', status: 'start', agentId: 'assistant', nodeId: 'model-1', iteration: 1, parentIds: ['context'], model: 'm' }),
  frame('reasoning', { agentId: 'assistant', nodeId: 'model-1', iteration: 1, text: '先读最近的消息' }),
  stage({ phase: 'model', status: 'done', agentId: 'assistant', nodeId: 'model-1', iteration: 1, finishReason: 'tool_calls' }),
  frame('tool_call', { toolName: 'get_recent_messages', callId: 'call_a', nodeId: 'tool-call_a', agentId: 'assistant', parentIds: ['model-1'], args: { chat_id: 5 } }),
  frame('tool_result', { toolName: 'get_recent_messages', callId: 'call_a', nodeId: 'tool-call_a', agentId: 'assistant', status: 'SUCCESS', latencyMs: 12 }),
  stage({ phase: 'model', status: 'start', agentId: 'assistant', nodeId: 'model-2', iteration: 2, parentIds: ['tool-call_a'], model: 'm' }),
  frame('reasoning', { agentId: 'assistant', nodeId: 'model-2', iteration: 2, text: '消息里提到周五前交付，\n整理成结论' })
]

test('a turn with no reported step has an empty room', () => {
  assert.deepEqual(scene([], { live: false }), { cells: [], links: [], lines: [], earlier: 0, working: false })
  assert.deepEqual(layoutMap(scene([], { live: false }), 600).spots, {})
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

// The same turn at the moment the tool has been called and has not answered yet.
const toolPending = toolTurn.slice(0, 6)
const actorOf = room => room.cells.find(cell => cell.type === 'actor')
const key = (room, kind) => room.cells.find(cell => cell.kind === kind && cell.type === 'prop').key

test('a figure leaves its desk only for a step of its own that is still under way, and is back once the result is reported', () => {
  const waiting = scene(toolPending)
  const figure = actorOf(waiting), tool = waiting.cells.find(cell => cell.kind === 'tool')
  // Its model call has ended; it is drawn standing at the tool, not as finished.
  assert.deepEqual([figure.state, figure.pose, figure.stance], ['succeeded', 'done', 'away'])
  assert.deepEqual(figure.at, { key: tool.key, nodeId: tool.nodeId, kind: 'tool', label: 'get_recent_messages' })
  assert.equal(waiting.working, true)
  const answered = scene(toolTurn.slice(0, 7))
  assert.deepEqual([actorOf(answered).at, actorOf(answered).stance], [null, 'done'])
  // Back at its desk and thinking again: a working figure is never parked at a pending step.
  const thinking = scene(toolTurn)
  assert.deepEqual([actorOf(thinking).at, actorOf(thinking).stance], [null, 'working'])
  // Somebody else's pending step moves nobody, and a step with no agent moves nobody.
  const other = scene([stage({ phase: 'model', status: 'done', agentId: 'code', nodeId: 'm-code' }),
    frame('tool_call', { toolName: 'lookup', callId: 'c', nodeId: 'tool-c', agentId: 'doc' }), stage({ phase: 'knowledge', status: 'start' })])
  assert.equal(actorOf(other).at, null)
})

test('a figure is startled by its own failed steps only, counted from what was reported', () => {
  const failing = [stage({ phase: 'model', status: 'done', agentId: 'assistant', nodeId: 'model-1' }),
    frame('tool_call', { toolName: 'lookup', callId: 'c1', nodeId: 'tool-c1', agentId: 'assistant' }),
    frame('tool_result', { toolName: 'lookup', callId: 'c1', nodeId: 'tool-c1', agentId: 'assistant', status: 'ERROR', error: 'boom' }),
    frame('tool_call', { toolName: 'lookup', callId: 'c2', nodeId: 'tool-c2', agentId: 'doc' }),
    frame('tool_result', { toolName: 'lookup', callId: 'c2', nodeId: 'tool-c2', agentId: 'doc', status: 'ERROR', error: 'boom' })]
  assert.equal(scene(failing.slice(0, 2)).cells[0].trouble, 0)
  assert.equal(scene(failing).cells[0].trouble, 1)
})

test('the board gets what each desk shows from the report: the model it named and its reported duration, or nothing', () => {
  const room = scene([stage({ phase: 'model', status: 'done', agentId: 'assistant', nodeId: 'model-1', model: 'actual-model', ms: 85 }),
    stage({ phase: 'model', status: 'start', agentId: 'code', nodeId: 'model-2' })])
  assert.deepEqual(room.cells.map(cell => [cell.kind, cell.title, cell.ms]), [['model', 'actual-model', 85], ['model', null, null]])
})

const analysts = ['code', 'doc', 'arch']
const collaboration = (synth = 'start') => scene([stage({ phase: 'context', nodeId: 'context', status: 'done' }),
  stage({ phase: 'collaboration', status: 'start', nodeId: 'collaboration', parentIds: ['context'] }),
  ...analysts.map(id => stage({ phase: 'model', status: 'done', agentId: id, nodeId: 'collaboration-' + id, parentIds: ['collaboration'] })),
  stage({ phase: 'model', status: synth, agentId: 'synth', nodeId: 'collaboration-synth', parentIds: analysts.map(id => 'collaboration-' + id) })])

test('roads are the reported dependencies between places, one per pair, live while the receiving step is under way', () => {
  const room = scene(toolTurn), me = 'actor:assistant'
  // The second call is under way and draws on the tool, so that road is live, flowing back to the desk.
  // The road from the context step fed the first call, which is over, so it is quiet.
  assert.deepEqual(room.links.map(link => [link.from, link.to, link.live, link.back]),
    [[key(room, 'context'), me, false, false], [me, key(room, 'tool'), true, true]])
  // While the tool itself is the step under way, the same road is live flowing out to it.
  assert.deepEqual(scene(toolPending).links.map(link => [link.live, link.back]), [[false, false], [true, false]])
  // During the first call it is the context road that is live.
  assert.deepEqual(scene(toolTurn.slice(0, 3)).links.map(link => [link.from, link.live, link.back]), [[key(room, 'context'), true, false]])
  // A finished turn has every road and none of them live.
  const done = scene([...toolTurn, stage({ phase: 'model', status: 'done', agentId: 'assistant', nodeId: 'model-2', finishReason: 'stop' }), frame('done', { finishReason: 'stop' })], { live: false })
  assert.deepEqual(done.links.map(link => link.live), [false, false])
  // Nothing is joined that was not reported: a context step nobody named as a parent has no road.
  const loose = scene([stage({ phase: 'context', status: 'done' }), stage({ phase: 'model', status: 'start', agentId: 'assistant', nodeId: 'model-1', parentIds: ['context'] })])
  assert.deepEqual(loose.links, [])
  const together = collaboration()
  assert.deepEqual(together.links.map(link => link.from + '>' + link.to), [
    key(together, 'context') + '>' + key(together, 'collaboration'),
    ...analysts.map(id => key(together, 'collaboration') + '>actor:' + id), ...analysts.map(id => 'actor:' + id + '>actor:synth')])
  // The collaboration step itself is still reported as under way, and so is the synthesis; the analysts are done.
  assert.deepEqual(together.links.filter(link => link.live).map(link => link.to), [key(together, 'collaboration'), 'actor:synth', 'actor:synth', 'actor:synth'])
  // A step that names its agent belongs to that agent even when it reported no parent.
  const owned = scene([stage({ phase: 'model', status: 'done', agentId: 'assistant', nodeId: 'model-1' }), frame('tool_call', { toolName: 'lookup', callId: 'c', nodeId: 'tool-c', agentId: 'assistant' })])
  assert.deepEqual(owned.links.map(link => [link.from, link.live]), [['actor:assistant', true]])
})

test('the road from the context step is there for the lines the store keeps, which carry the id the backend now reports', () => {
  // The store flattens each frame into a line: { kind, status: 'running' | 'done', nodeId, parentIds, ... }.
  const lines = [{ kind: 'context', nodeId: 'context', status: 'done', runId: 'r1', attemptId: 'a1', ms: 4 },
    { kind: 'model', nodeId: 'model-1', agentId: 'assistant', iteration: 1, parentIds: ['context'], status: 'running', runId: 'r1', attemptId: 'a1' }]
  const room = scene(lines)
  assert.deepEqual(room.links.map(link => [link.to, link.live]), [['actor:assistant', true]])
  assert.equal(layoutMap(room, 900).plans['actor:assistant'].mode, 'loop')
  // A record made before the backend reported that id keeps its places and has no road between them.
  const older = scene([{ ...lines[0], nodeId: undefined }, lines[1]])
  assert.deepEqual([older.cells.length, older.links.length, layoutMap(older, 900).plans['actor:assistant'].mode], [2, 0, 'home'])
})

test('the map is never one straight line: places step along their dependencies and neighbouring depths are pushed apart', () => {
  const room = scene(toolTurn), map = layoutMap(room, 900)
  const [source, desk, tool] = [key(room, 'context'), 'actor:assistant', key(room, 'tool')].map(place => map.spots[place])
  assert.equal(map.sideways, true)
  assert.ok(source.x < desk.x && desk.x < tool.x)
  // Three places in a chain do not share a height: the middle one is set off from both ends.
  assert.ok(Math.abs(source.y - desk.y) >= MAP.zig * 2 && Math.abs(tool.y - desk.y) >= MAP.zig * 2)
  const together = collaboration(), wide = layoutMap(together, 900)
  const desks = analysts.map(id => wide.spots['actor:' + id])
  assert.equal(new Set(desks.map(spot => spot.x)).size, 1)
  assert.equal(new Set(desks.map(spot => spot.y)).size, 3)
  assert.ok(wide.spots['actor:synth'].x > desks[0].x && desks[0].x > wide.spots[key(together, 'collaboration')].x)
  // A desk that reported no tie to anything still stands after the sources, without a road.
  const loose = scene([stage({ phase: 'context', status: 'done' }), stage({ phase: 'model', status: 'start', agentId: 'assistant', nodeId: 'model-1' })])
  const plain = layoutMap(loose, 900)
  assert.ok(plain.spots['actor:assistant'].x > plain.spots[key(loose, 'context')].x)
  assert.deepEqual(plain.roads, [])
})

test('the same run draws the same map, nothing overlaps or leaves it, and a narrow map turns to run downward', () => {
  const together = collaboration()
  assert.deepEqual(layoutMap(together, 600), layoutMap(together, 600))
  for (const room of [together, scene(toolTurn), scene(toolPending)]) for (const width of [260, 275, 320, 476, 605, 938, 1100]) {
    const map = layoutMap(room, width), all = Object.values(map.spots)
    assert.equal(all.length, room.cells.length)
    for (const a of all) {
      assert.ok(a.x - MAP.site / 2 >= 0 && a.x + MAP.site / 2 <= width, `${width}: x ${a.x}`)
      assert.ok(a.y - MAP.art - MAP.head >= 0 && a.y + MAP.tag <= map.height, `${width}: y ${a.y} of ${map.height}`)
      for (const b of all) if (a !== b) assert.ok(Math.abs(a.x - b.x) >= MAP.site || Math.abs(a.y - b.y) >= MAP.art + MAP.tag, `${width}: ${a.x},${a.y} over ${b.x},${b.y}`)
    }
    for (const road of map.roads) for (const point of road.points) assert.ok(point[0] >= 0 && point[0] <= width && point[1] >= 0 && point[1] <= map.height)
  }
  const narrow = layoutMap(together, 275)
  assert.equal(narrow.sideways, false)
  assert.ok(narrow.spots['actor:synth'].y > narrow.spots['actor:code'].y && narrow.spots['actor:code'].y > narrow.spots[key(together, 'context')].y)
})

test('a road is drawn between the two places it joins, in straight stretches', () => {
  for (const width of [275, 900]) {
    const room = collaboration(), map = layoutMap(room, width)
    assert.equal(map.roads.length, room.links.length)
    for (const road of map.roads) {
      assert.deepEqual(road.points[0], [map.spots[road.from].x, map.spots[road.from].y])
      assert.deepEqual(road.points.at(-1), [map.spots[road.to].x, map.spots[road.to].y])
      for (let i = 1; i < road.points.length; i++) assert.ok(road.points[i][0] === road.points[i - 1][0] || road.points[i][1] === road.points[i - 1][1])
      assert.match(road.d, /^M\d+ \d+(L\d+ \d+)+$/)
    }
  }
})

test('a figure walks only where the report sends it: to and fro between its desk and what its running step draws on', () => {
  const near = (a, b, by = MAP.beside) => Math.abs(a[0] - b[0]) <= by && Math.abs(a[1] - b[1]) <= 1
  const at = (map, place) => [map.spots[place].x, map.spots[place].y]
  // Thinking about the tool's answer: it starts beside the tool and carries the answer to its desk.
  const thinking = scene(toolTurn), map = layoutMap(thinking, 900), plan = map.plans['actor:assistant']
  assert.equal(plan.mode, 'loop')
  assert.ok(near(plan.points[0], at(map, key(thinking, 'tool'))))
  assert.deepEqual(plan.points.at(-1), at(map, 'actor:assistant'))
  assert.equal(plan.rest, 1)
  assert.deepEqual(actorOf(thinking).inputs, [key(thinking, 'tool')])
  // Its first call draws on the context step, so that is where it walks.
  const first = scene(toolTurn.slice(0, 3)), firstMap = layoutMap(first, 900)
  assert.ok(near(firstMap.plans['actor:assistant'].points[0], at(firstMap, key(first, 'context'))))
  // Waiting on the tool, it stands beside the tool and nowhere else.
  const waiting = scene(toolPending), waitMap = layoutMap(waiting, 900), stand = waitMap.plans['actor:assistant']
  assert.equal(stand.mode, 'at')
  assert.deepEqual(stand.points[0], at(waitMap, 'actor:assistant'))
  assert.ok(near(stand.points.at(-1), at(waitMap, key(waiting, 'tool'))))
  // The synthesis goes round every desk that handed it a result; the finished analysts stay put.
  const together = collaboration(), all = layoutMap(together, 900), round = all.plans['actor:synth']
  assert.equal(round.mode, 'loop')
  for (const id of analysts) assert.ok(round.points.some(point => near(point, at(all, 'actor:' + id))), id)
  assert.deepEqual(pointAt(round.points, round.rest), at(all, 'actor:synth'))
  for (const id of analysts) assert.deepEqual(all.plans['actor:' + id], { mode: 'home', points: [at(all, 'actor:' + id)], rest: 1 })
  // Once its step is over it goes nowhere, and a running step that reported no parent stays at its desk.
  assert.equal(layoutMap(collaboration('done'), 900).plans['actor:synth'].mode, 'home')
  const alone = scene([stage({ phase: 'model', status: 'start', agentId: 'assistant', nodeId: 'model-1' })])
  assert.equal(layoutMap(alone, 900).plans['actor:assistant'].mode, 'home')
  // Every stretch of a walk is straight, on a wide map and on a narrow one.
  for (const width of [275, 900]) for (const walk of Object.values(layoutMap(collaboration(), width).plans)) {
    for (let i = 1; i < walk.points.length; i++) assert.ok(walk.points[i][0] === walk.points[i - 1][0] || walk.points[i][1] === walk.points[i - 1][1])
  }
})

test('a point along a path is found by distance walked', () => {
  const path = [[0, 0], [100, 0], [100, 50]]
  assert.equal(pathLength(path), 150)
  assert.deepEqual([pointAt(path, 0), pointAt(path, 1 / 3), pointAt(path, 2 / 3), pointAt(path, 1), pointAt(path, 7)], [[0, 0], [50, 0], [100, 0], [100, 50], [100, 50]])
  assert.deepEqual(pointAt([[4, 9]], 0.5), [4, 9])
})

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { artifactTabs, artifactText, artifactFileName } from './artifactView.js'

const run = {
  runId: 'run-actual',
  trace: [{ kind: 'model', nodeId: 'model-1', agentId: 'synth', model: 'actual-model', status: 'done', at: 5, ms: 5 }],
  sources: [{ id: 'A1', kind: 'attachment', title: 'input.py' }]
}

test('Raw and Diff share the actual patch bytes and expose recorded log and graph', () => {
  const artifact = { id: 'patch-1', name: 'change.patch', kind: 'patch', nodeId: 'model-1', sourceIds: ['A1'], content: '--- a/input.py\n+++ b/input.py\n@@ -1 +1 @@\n-old\n+new\n' }
  assert.deepEqual(artifactTabs(artifact, run).map(t => t.id), ['diff', 'raw', 'log', 'graph'])
  assert.equal(artifactText(artifact, 'raw', run), artifact.content)
  assert.equal(artifactText(artifact, 'diff', run), artifact.content)
  assert.ok(artifactText(artifact, 'log', run).includes('actual-model'))
  const graph = JSON.parse(artifactText(artifact, 'graph', run))
  assert.equal(graph.runId, 'run-actual')
  assert.equal(graph.artifactId, 'patch-1')
  assert.equal(graph.nodes.length, 1)
  assert.equal(graph.nodes[0].id, 'model-1')
  assert.deepEqual(graph.sources.map(s => s.id), ['A1'])
})

test('a JSON or code artifact never pretends there is a file diff', () => {
  const artifact = { id: 'json-1', name: 'result.json', kind: 'json', nodeId: 'model-1', content: '{"items":[]}' }
  assert.deepEqual(artifactTabs(artifact, run).map(t => t.id), ['raw', 'log', 'graph'])
  assert.equal(artifactText(artifact, 'diff', run), '')
  assert.equal(artifactText(artifact, 'raw', run), '{"items":[]}')
})

test('empty context does not fabricate execution logs or a topology', () => {
  const artifact = { id: 'code-1', name: 'a.py', kind: 'code', content: 'print(1)' }
  assert.deepEqual(artifactTabs(artifact, { trace: [] }).map(t => t.id), ['raw'])
  assert.equal(artifactText(artifact, 'log', { trace: [] }), '')
})

test('artifact provenance excludes unrelated work and is empty when no producer is known', () => {
  const privateRun = { ...run, trace: [...run.trace, { kind: 'tool', nodeId: 'other', toolName: 'unrelated', args: { unrelated: 'private' }, status: 'SUCCESS' }] }
  const a = { id: 'a', kind: 'code', nodeId: 'model-1', sourceIds: [] }
  assert.doesNotMatch(artifactText(a, 'log', privateRun), /private|unrelated/)
  assert.equal(JSON.parse(artifactText(a, 'graph', privateRun)).sources.length, 0)
  assert.deepEqual(artifactTabs({ id: 'unlinked', kind: 'code' }, privateRun).map(tab => tab.id), ['raw'])
})

test('download filenames are display-only safe basenames, not filesystem targets', () => {
  assert.equal(artifactFileName({ name: '../../private/source.py' }), 'source.py')
  assert.equal(artifactFileName({ name: 'a\\b\\result.json' }), 'result.json')
  assert.equal(artifactFileName({ name: 'report\u0000.json' }), 'report.json')
  assert.equal(artifactFileName({ id: 'artifact-1', kind: 'graph' }), 'execution-graph.json')
})

test('log and graph use the producer, named calls, and recorded parents, not downstream siblings or global diagnostics', () => {
  const trace = [{ kind: 'context', nodeId: 'ctx', title: 'prepare' },
    { kind: 'model', nodeId: 'producer', model: 'writer', parentIds: ['ctx'] },
    { kind: 'tool_call', callId: 'one', nodeId: 't1', toolName: 'lookup', args: { query: 'included' }, parentIds: ['ctx'] },
    { kind: 'tool_call', callId: 'two', nodeId: 't2', toolName: 'lookup', args: { query: 'private' }, parentIds: ['producer'] },
    { kind: 'knowledge', nodeId: 'kb', title: 'references', chunks: 1 }, { kind: 'error', message: 'private diagnostics' }]
  const r = { trace, sources: [{ id: 'A' }, { id: 'B', title: 'private source' }] }
  const a = { id: 'file', nodeId: 'producer', parentIds: ['kb'], callIds: ['one'], sourceIds: ['A'] }
  const graph = JSON.parse(artifactText(a, 'graph', r))
  assert.deepEqual(graph.nodes.map(n => n.id), ['ctx', 'producer', 't1', 'kb'])
  assert.deepEqual(graph.edges, [{ from: 'ctx', to: 'producer' }, { from: 'ctx', to: 't1' }])
  assert.deepEqual(graph.sources.map(s => s.id), ['A'])
  const log = artifactText(a, 'log', r)
  for (const text of ['prepare', 'writer', 'included', 'references']) assert.ok(log.includes(text))
  assert.doesNotMatch(log, /private/)
  assert.deepEqual(JSON.parse(artifactText({ nodeId: 'absent', callIds: ['one'] }, 'graph', r)).nodes.map(n => n.id), ['ctx', 't1'])
  assert.deepEqual(JSON.parse(artifactText({ nodeId: 'producer' }, 'graph', r)).sources, [])
  assert.equal(artifactText({ nodeId: 'absent' }, 'graph', r), '')
})

test('artifact events identify their producing attempt even when resumed nodes, calls and source IDs are reused', () => {
  const a = { id: 'old-file', kind: 'code', nodeId: 'm', callIds: ['c'], sourceIds: ['A'] }
  const attempt = (id, resumed) => [{ kind: 'meta', runId: 'r', traceId: id, resumed, sequence: 1 },
    { kind: 'tool_call', callId: 'c', nodeId: 't', toolName: `${id}-lookup` },
    { kind: 'model', nodeId: 'm', model: `${id}-writer`, parentIds: ['t'] },
    { kind: 'sources', items: [{ id: 'A', title: `${id}-source` }] }]
  const r = { runId: 'r', trace: [...attempt('old', false), { kind: 'artifact', artifact: a }, ...attempt('new', true)] }
  const old = JSON.parse(artifactText({ ...a }, 'graph', r))
  assert.deepEqual(old.nodes.map(n => n.title), ['old-lookup', 'old-writer'])
  assert.deepEqual(old.sources.map(s => s.title), ['old-source'])
  assert.doesNotMatch(artifactText(a, 'log', r), /new-/)
  const explicit = JSON.parse(artifactText({ ...a, id: 'new-file', traceId: 'new' }, 'graph', r))
  assert.deepEqual(explicit.nodes.map(n => n.title), ['new-lookup', 'new-writer'])
  assert.deepEqual(explicit.edges, [{ from: explicit.nodes[0].id, to: explicit.nodes[1].id }])
  assert.deepEqual(explicit.sources.map(s => s.title), ['new-source'])
  assert.equal(artifactText({ id: 'ambiguous', nodeId: 'm' }, 'log', r), '')
  assert.equal(artifactText({ ...a, runId: 'different-run' }, 'graph', r), '')
})

test('graph kind alone grants no whole-run log: only graph members confirmed by the trace are shown', () => {
  const r = { ...run, trace: [...run.trace, { kind: 'context', nodeId: 'ctx', title: 'other step' }] }
  const graph = ids => ({ kind: 'graph', content: JSON.stringify({ runId: r.runId, nodes: ids.map(id => ({ id })), edges: [] }) })
  assert.deepEqual(artifactTabs(graph(['absent']), r).map(t => t.id), ['raw'])
  assert.equal(artifactText({ kind: 'graph', content: '{}' }, 'graph', r), '')
  assert.doesNotMatch(artifactText(graph(['model-1']), 'log', r), /other step/)
  const full = JSON.parse(artifactText(graph(['model-1', 'ctx']), 'graph', r))
  assert.deepEqual(full.nodes.map(n => n.id), ['model-1', 'ctx'])
  assert.deepEqual(full.sources, [])
})

test('an explicitly linked source can be shown without inventing a producer or a log', () => {
  const a = { id: 'source-only', sourceIds: ['A1'] }
  assert.deepEqual(artifactTabs(a, run).map(t => t.id), ['raw', 'graph'])
  assert.equal(artifactText(a, 'log', run), '')
  const graph = JSON.parse(artifactText(a, 'graph', run))
  assert.deepEqual(graph.nodes, [])
  assert.deepEqual(graph.edges, [])
  assert.deepEqual(graph.sources.map(s => s.id), ['A1'])
})

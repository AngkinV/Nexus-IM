import { after, before, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'

let server, useAgentStore, api, store, requests, useAgentWorkspaceStore, messageBox, originalConfirm, confirmations
const storage = new Map()
const originalStorage = globalThis.localStorage
const originalFetch = globalThis.fetch
const frames = (...events) => events.map(([name, data]) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`).join('')
let responses

before(async () => {
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key)
  }
  server = await createServer({
    configFile: false,
    optimizeDeps: { noDiscovery: true }, // SSR-only: never write to the shared node_modules cache.
    root: fileURLToPath(new URL('../../', import.meta.url)),
    resolve: { alias: { '@': fileURLToPath(new URL('../', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, watch: null },
    logLevel: 'error'
  })
  ;({ useAgentStore } = await server.ssrLoadModule('/src/stores/agent.js'))
  ;({ agentAPI: api } = await server.ssrLoadModule('/src/services/agentApi.js'))
  ;({ useAgentWorkspaceStore } = await server.ssrLoadModule('/src/stores/agentWorkspace.js'))
  // agent.js resolves element-plus through Node's own module registry, which is a
  // different instance from Vite's ssrLoadModule copy. Patch the one it actually uses.
  ;({ ElMessageBox: messageBox } = await import('element-plus'))
  originalConfirm = messageBox.confirm
})

after(async () => {
  if (messageBox) messageBox.confirm = originalConfirm
  await server?.close()
  globalThis.localStorage = originalStorage
  globalThis.fetch = originalFetch
})

beforeEach(() => {
  storage.clear()
  storage.set('user', JSON.stringify({ id: 7, username: 'alice' }))
  storage.set('token', 'test-token')
  storage.set('agent.sessionId:7', 'session_001')
  confirmations = []
  messageBox.confirm = async (...args) => { confirmations.push(args); return 'confirm' }
  api.scopeResources = async () => ok({ contacts: [], groups: [] })
  api.getScope = async () => ok({ bindingVersion: 1, sessionGeneration: 1, selection: { targets: [], timeRange: { fromInclusive: null, toExclusive: null, timeZone: 'UTC' } } })
  requests = []
  responses = []
  globalThis.fetch = async (url, options) => {
    requests.push({ url, body: JSON.parse(options.body), signal: options.signal })
    assert.ok(responses.length, 'unexpected extra request')
    return new Response(responses.shift(), { headers: { 'Content-Type': 'text/event-stream' } })
  }
  setActivePinia(createPinia())
  store = useAgentStore()
  store.sessionId = 'session_001'
  api.createSession = async () => ok({ sessionId: 'session_001' })
  api.listSessions = async () => ok([])
  api.listRuns = async () => ok([])
  api.sessionMessages = async () => ok({ messages: [] })
  api.pendingRuns = async () => ok({ runs: [] })
  api.cancelRun = async (sid, runId) => ok({ runId, status: 'cancelled' })
})

test('failed turns keep the partial answer and separate diagnostic', async () => {
  responses.push(frames(
    ['meta', { runId: 'run_test_001', traceId: 'tr_001' }],
    ['delta', { text: '半句' }],
    ['error', { code: 'AGENT_MODEL_50202', reason: 'RATE_LIMIT', message: 'quota exceeded' }],
    ['done', { finishReason: 'error', resumable: true, runId: 'run_test_001' }]
  ))
  await store.sendMessage('查询')
  const answer = store.messages.at(-1)
  assert.equal(answer.content, '半句')
  assert.equal(answer.status, 'error')
  assert.equal(answer.error.reason, 'RATE_LIMIT')
  assert.equal(answer.runId, 'run_test_001')
  assert.equal(answer.resumable, true)
  assert.equal(store.streaming, false)
})

test('resume replaces unfinished text and never repeats the user message', async () => {
  responses.push(frames(
    ['meta', { runId: 'run_test_001', traceId: 'tr_001' }],
    ['delta', { text: '未完成的半句' }],
    ['error', { message: 'timeout' }],
    ['done', { finishReason: 'timeout', resumable: true, runId: 'run_test_001' }]
  ))
  await store.sendMessage('查询')
  responses.push(frames(
    ['meta', { runId: 'run_test_001', traceId: 'tr_002', resumed: true }],
    ['delta', { text: '已提交的前文', replayed: true }],
    ['delta', { text: '完整答案' }],
    ['done', { finishReason: 'stop', resumable: false, runId: 'run_test_001' }]
  ))
  await store.resumeMessage(store.messages.at(-1).id)
  assert.equal(store.messages.length, 2)
  assert.equal(store.messages.at(-1).content, '已提交的前文完整答案')
  assert.equal(store.messages.at(-1).status, 'done')
  assert.equal(store.messages.at(-1).resumable, false)
  assert.match(requests[1].url, /sessions\/session_001\/runs\/run_test_001\/resume\/stream$/)
  assert.equal(requests[1].body.input, undefined)
})

test('same-name tools use call id rather than completion order', async () => {
  responses.push(frames(
    ['meta', { runId: 'run_test_001' }],
    ['tool_call', { callId: 'a', toolName: 'get_user_profile', args: { user_id: 7 } }],
    ['tool_call', { callId: 'b', toolName: 'get_user_profile', args: { user_id: 8 } }],
    ['tool_result', { callId: 'a', toolName: 'get_user_profile', status: 'FAILED' }],
    ['tool_result', { callId: 'b', toolName: 'get_user_profile', status: 'SUCCESS' }],
    ['delta', { text: '已查询' }],
    ['done', { finishReason: 'stop' }]
  ))
  await store.sendMessage('查询')
  const calls = store.messages.at(-1).toolCalls
  assert.equal(calls.find(call => call.args.user_id === 7).status, 'FAILED')
  assert.equal(calls.find(call => call.args.user_id === 8).status, 'SUCCESS')
})

test('cached interrupted turns retain run metadata for explicit resume', async () => {
  responses.push(frames(
    ['meta', { runId: 'run_test_001' }],
    ['error', { message: 'limit' }],
    ['done', { finishReason: 'error', resumable: true, runId: 'run_test_001' }]
  ))
  await store.sendMessage('查询')
  const cache = JSON.parse(storage.get('agent.sessionMessages:7'))
  assert.equal(cache.session_001.at(-1).runId, 'run_test_001')
  assert.equal(cache.session_001.at(-1).resumable, true)
  assert.equal(cache.session_001.at(-1).status, 'error')
})

test('stream ending without done is incomplete, not successful', async () => {
  responses.push(frames(['meta', { runId: 'run_test_001' }], ['delta', { text: '半句' }]))
  await store.sendMessage('查询')
  assert.equal(store.messages.at(-1).status, 'interrupted')
  assert.equal(store.messages.at(-1).content, '半句')
})

const ok = data => ({ data: { code: 'OK', data } })
const stop = ['done', { finishReason: 'stop' }]
const nextTick = () => new Promise(resolve => setImmediate(resolve))
const freshStore = () => { setActivePinia(createPinia()); return useAgentStore() }
const events = entries => entries.map(([name, data]) => ({ name, data }))

test('stream preserves event identity and folds native reasoning, analysis and final text separately', async () => {
  const ids = { runId: 'run_scoped_1', nodeId: 'code-1', agentId: 'code', parentIds: ['ctx'], callId: 'call_1' }
  const source = { id: 'A1', title: 'File', kind: 'attachment' }
  const artifact = { id: 'art_1', kind: 'code', content: 'real bytes' }
  responses.push(frames(
    ['meta', { runId: ids.runId, traceId: 't', sequence: 1, at: 0 }],
    ['stage', { ...ids, phase: 'model', status: 'start', sequence: 2, at: 10 }],
    ...Array.from({ length: 75 }, (_, i) => ['reasoning', { ...ids, text: '思', sequence: 3 + i, at: 11 + i }]),
    ['reasoning', { ...ids, text: 'duplicate', sequence: 77, at: 85 }],
    ['agent_output', { ...ids, text: '分析', sequence: 78, at: 86 }],
    ['agent_output', { ...ids, text: '结果', sequence: 79, at: 87 }],
    ['error', { ...ids, message: 'role failed', sequence: 80, at: 88 }],
    ['delta', { runId: ids.runId, agentId: 'synth', nodeId: 'synth-1', text: '答', sequence: 81, at: 90 }],
    ['delta', { runId: ids.runId, agentId: 'synth', nodeId: 'synth-1', text: '案', sequence: 82, at: 91 }],
    ['sources', { items: [source] }], ['artifact', { artifact }],
    ['usage', { totalTokens: 11, usageReported: true }], ['done', { finishReason: 'stop', at: 100 }]
  ))
  await store.sendMessage('analyse')
  const live = store.messages.at(-1)
  assert.equal(live.content, '答案')
  assert.equal(live.status, 'done', 'an analyst error does not cancel the synthesis')
  assert.equal(live.error, null)
  const thoughts = live.trace.filter(s => s.kind === 'reasoning')
  assert.equal(thoughts.length, 1)
  assert.equal(thoughts[0].text, '思'.repeat(75))
  for (const [key, value] of Object.entries(ids)) assert.deepEqual(thoughts[0][key], value)
  assert.equal(thoughts[0].sequence, 3)
  assert.equal(thoughts[0].at, 11)
  assert.deepEqual(live.trace.filter(s => s.kind === 'agent_output').map(s => s.text), ['分析结果'])
  assert.equal(live.trace.filter(s => s.kind === 'answer').length, 1)
  assert.equal(live.trace.find(s => s.kind === 'error').agentId, 'code')
  assert.deepEqual(live.sources, [source]); assert.deepEqual(live.artifacts, [artifact])
  assert.equal(live.usage.totalTokens, 11); assert.equal(live.finishReason, 'stop')
  assert.equal(live.elapsedMs, 100)
})

test('selected options survive retry as a distinct run of the original question, not a new question', async () => {
  store.setActiveProviderId(41)
  const selected = { temperature: 0, maxIterations: 4, maxOutputTokens: 2048, collaborationEnabled: true,
    webSearchEnabled: false, linkedKbId: 'kb_original', attachmentIds: ['att_first', 'att_second'] }
  responses.push(frames(['delta', { text: 'original' }], stop), frames(['delta', { text: 'different' }], stop))
  await store.sendMessage('question', selected)
  const original = store.messages.at(-1)
  selected.attachmentIds.pop(); store.setActiveProviderId(99)
  await store.retryMessage(original.id)
  assert.deepEqual(store.messages.map(m => m.content), ['question', 'original', 'different'])
  const [first, retry] = requests.map(r => r.body)
  assert.match(first.runId, /^[\w-]{8,64}$/); assert.match(retry.runId, /^[\w-]{8,64}$/)
  assert.notEqual(retry.runId, first.runId); assert.equal(retry.retryOfRunId, first.runId)
  for (const request of [first, retry]) {
    assert.equal(request.input, 'question'); assert.equal(request.providerId, 41); assert.equal(request.linkedKbId, 'kb_original')
    assert.deepEqual(request.attachmentIds, ['att_first', 'att_second'])
    assert.deepEqual(request.options, { visibility: 'PRIVATE_ONLY', temperature: 0, maxIterations: 4, maxOutputTokens: 2048, collaborationEnabled: true, webSearchEnabled: false })
  }
  assert.deepEqual(original.options, first.options)
  assert.deepEqual(original.attachmentIds, first.attachmentIds)
  assert.equal(store.messages.at(-1).retryOfRunId, original.runId)
})

test('invalid options and a failed session create do not send the draft or append sent bubbles', async () => {
  for (const options of [{ temperature: -0.1 }, { maxIterations: 1.5 }, { maxOutputTokens: -1 },
    { attachmentIds: ['a', 'b', 'c', 'd', 'e'] }, { webSearchEnabled: 'false' }]) {
    await assert.rejects(store.sendMessage('draft', options))
  }
  store.sessionId = null
  api.createSession = async () => { throw new Error('session unavailable') }
  await assert.rejects(store.sendMessage('draft'), /session unavailable/)
  assert.deepEqual(store.messages, []); assert.equal(store.streaming, false)
  assert.equal(store.lastError.code, 'SESSION'); assert.deepEqual(requests, [])
})

for (const [reason, status] of [['length', 'truncated'], ['max_iterations', 'truncated'], ['cancelled', 'cancelled'], ['timeout', 'interrupted'], ['content_filter', 'truncated']]) {
  test(`a ${reason} termination preserves partial text and reports ${status}`, async () => {
    responses.push(frames(['delta', { text: 'partial' }], ['done', { finishReason: reason }]))
    await store.sendMessage('question')
    assert.equal(store.messages.at(-1).content, 'partial')
    assert.equal(store.messages.at(-1).status, status)
    assert.equal(store.messages.at(-1).finishReason, reason)
  })
}

test('clear awaits cancellation, propagates a rejected clear and does not erase the transcript or cache', async () => {
  const finishCancel = Promise.withResolvers(), clear = Promise.withResolvers()
  const bytes = new TextEncoder().encode(frames(['meta', { runId: 'run_clear' }], ['delta', { text: 'before' }]))
  responses.push(new ReadableStream({ start(c) { c.enqueue(bytes) }, cancel() { return finishCancel.promise } }))
  const sending = store.sendMessage('keep me')
  await nextTick()
  let clears = 0
  api.clearSessionMemory = sid => { assert.equal(sid, 'session_001'); clears++; return clear.promise }
  const clearing = store.clearConversation()
  await nextTick(); assert.equal(clears, 0); assert.equal(store.messages.at(-1).content, 'before')
  finishCancel.resolve(); await sending; await nextTick(); assert.equal(clears, 1)
  clear.reject(new Error('not cleared')); await assert.rejects(clearing, /not cleared/)
  assert.equal(store.lastError.code, 'CLEAR_FAILED')
  assert.equal(store.messages.at(-1).status, 'cancelled')
  assert.equal(JSON.parse(storage.get('agent.sessionMessages:7')).session_001.at(-1).content, 'before')
  api.clearSessionMemory = async () => ok({ cleared: true })
  await store.clearConversation()
  assert.deepEqual(store.messages, []); assert.equal(store.messagesBySessionId.session_001, undefined)
})

test('a slow cancelled stream cannot stop a new stream or write into the next session', async () => {
  const oldClose = Promise.withResolvers(); let controller
  const fetchNext = globalThis.fetch
  globalThis.fetch = (...args) => { globalThis.fetch = fetchNext; return oldClose.promise }
  const first = store.sendMessage('one'); await nextTick()
  await store.switchSession('two')
  responses.push(new ReadableStream({ start(c) { controller = c } }))
  const next = store.sendMessage('two'); await nextTick()
  oldClose.resolve(new Response(frames(['delta', { text: 'late' }], stop), { headers: { 'Content-Type': 'text/event-stream' } })); await first
  assert.equal(store.streaming, true); assert.deepEqual(store.messages.map(m => m.content), ['two', ''])
  controller.enqueue(new TextEncoder().encode(frames(['delta', { text: 'second answer' }], stop))); controller.close()
  await next
  assert.equal(store.messages.at(-1).content, 'second answer')
  assert.equal(store.messagesBySessionId.session_001.at(-1).status, 'interrupted') // detaching is not cancellation proof
})

test('obsolete session creation never redirects the new selection or submits the old draft', async () => {
  const creation = Promise.withResolvers()
  store.sessionId = null; api.createSession = () => creation.promise
  const sending = store.sendMessage('old draft')
  await store.switchSession('newer')
  creation.resolve(ok({ sessionId: 'obsolete' }))
  await Promise.allSettled([sending])
  assert.equal(store.sessionId, 'newer'); assert.deepEqual(store.messages, []); assert.deepEqual(requests, [])
})

test('durable history and retries are reconstructed by run id; identical inputs are not turn identity', async () => {
  responses.push(frames(['meta', { runId: 'run_first' }], ['reasoning', { nodeId: 'm', text: 'cached trace' }], ['delta', { text: 'cached answer' }], stop))
  await store.sendMessage('same input')
  const originalId = store.messages.at(-1).id
  const calls = []
  api.listRuns = async sid => { calls.push(sid); return ok([
    { runId: 'run_first', input: 'same input', answer: 'confirmed answer', status: 'done', trace: [] },
    { runId: 'run_retry', retryOfRunId: 'run_first', input: 'same input', status: 'truncated', finishReason: 'length',
      trace: events([['reasoning', { nodeId: 'n2', text: 'real' }], ['delta', { text: 'short' }], ['done', { finishReason: 'length' }]]),
      options: { maxIterations: 2 }, providerRef: { providerId: 42 }, attachmentRefs: [{ id: 'att_base' }], elapsedMs: 19,
      usage: { totalTokens: 10 }, sources: [{ id: 'A1' }], artifacts: [{ id: 'art_1' }] },
    { runId: 'run_other', input: 'same input', answer: 'unrelated', status: 'interrupted', trace: [] }
  ]) }
  api.sessionMessages = async () => ok({ messages: [{ role: 'user', content: 'short-term shadow' }] })
  await store.refreshActiveSession()
  assert.deepEqual(store.messages.map(m => m.content), ['same input', 'confirmed answer', 'short', 'same input', 'unrelated'])
  const [, first, retry, , other] = store.messages
  assert.equal(first.id, originalId); assert.equal(first.trace.find(e => e.kind === 'reasoning').text, 'cached trace')
  assert.equal(retry.status, 'truncated'); assert.equal(retry.trace.find(e => e.kind === 'reasoning').text, 'real')
  assert.deepEqual(retry.attachmentIds, ['att_base']); assert.equal(retry.providerId, 42); assert.equal(retry.options.maxIterations, 2)
  assert.equal(retry.usage.totalTokens, 10); assert.equal(retry.elapsedMs, 19)
  assert.deepEqual(retry.sources, [{ id: 'A1' }]); assert.deepEqual(retry.artifacts, [{ id: 'art_1' }])
  assert.equal(other.status, 'interrupted'); assert.deepEqual(other.trace, [])
  const boot = freshStore(); await boot.hydrateActiveSession()
  assert.deepEqual(calls, ['session_001', 'session_001']); assert.deepEqual(boot.messages, store.messages)
})

test('legacy transcript is retained when durable history first becomes available, even for repeated text', async () => {
  api.sessionMessages = async () => ok({ messages: [{ role: 'user', content: 'same' }, { role: 'assistant', content: 'old answer' }] })
  await store.switchSession('legacy')
  const legacyIds = store.messages.map(m => m.id)
  api.listRuns = async () => ok([{ runId: 'run_modern', input: 'same', answer: 'new answer', status: 'done' }])
  await store.refreshActiveSession(); await store.refreshActiveSession()
  assert.deepEqual(store.messages.map(m => m.content), ['same', 'old answer', 'same', 'new answer'])
  assert.deepEqual(store.messages.slice(0, 2).map(m => m.id), legacyIds)
  assert.equal(store.messages[1].runId ?? null, null)
})

test('stale history reads never overwrite newer selections or turns', async () => {
  const slow = Promise.withResolvers()
  api.listRuns = sid => sid === 'slow' ? slow.promise : Promise.resolve(ok([]))
  const first = store.switchSession('slow')
  await store.switchSession('latest')
  slow.resolve(ok([{ runId: 'run_slow', input: 'wrong', answer: 'wrong' }]))
  await first
  assert.equal(store.sessionId, 'latest'); assert.deepEqual(store.messages, [])
  const pending = Promise.withResolvers(); api.listRuns = () => pending.promise
  const refresh = store.refreshActiveSession()
  responses.push(frames(['delta', { text: 'right' }], stop)); await store.sendMessage('latest turn')
  pending.resolve(ok([{ runId: 'run_stale', input: 'wrong', answer: 'wrong' }]))
  await refresh
  assert.deepEqual(store.messages.map(m => m.content), ['latest turn', 'right'])
})

test('authenticated caches never adopt unowned global transcripts or expose a previous account on hydration', async () => {
  storage.delete('agent.sessionId:7')
  storage.set('agent.sessionId', 'global'); storage.set('agent.sessionMessages', JSON.stringify({ global: [{ role: 'assistant', content: 'unknown owner' }] }))
  const alice = freshStore()
  assert.equal(alice.sessionId, null); assert.deepEqual(alice.messagesBySessionId, {})
  responses.push(frames(['delta', { text: 'private answer' }], stop)); await alice.sendMessage('private input')
  const pending = Promise.withResolvers(); api.listRuns = () => pending.promise
  const reading = alice.refreshActiveSession()
  storage.set('user', '{"id":8}'); storage.set('token', 'token-eight')
  await alice.hydrateActiveSession()
  pending.resolve(ok([{ runId: 'run_private', input: 'private', answer: 'old account' }]))
  await reading
  assert.deepEqual(alice.messages, []); assert.deepEqual(alice.messagesBySessionId, {}); assert.equal(alice.sessionId, null)
  assert.deepEqual(freshStore().messagesBySessionId, {})
  storage.set('user', '{"id":7}'); storage.set('token', 'test-token')
  assert.deepEqual(freshStore().messages.map(m => m.content), ['private input', 'private answer'])
})

test('a done frame for a different run cannot terminate the current stream', async () => {
  responses.push(frames(['meta', { runId: 'run_owned' }], ['done', { runId: 'run_foreign', finishReason: 'stop' }],
    ['delta', { runId: 'run_owned', text: 'right result' }], ['done', { runId: 'run_owned', finishReason: 'stop' }]))
  await store.sendMessage('question')
  const answer = store.messages.at(-1)
  assert.equal(answer.status, 'done'); assert.equal(answer.content, 'right result')
  assert.ok(answer.trace.every(e => e.runId === 'run_owned'))
})

test('an explicit synthesizer failure cannot be promoted to done by a clean stop', async () => {
  responses.push(frames(['delta', { agentId: 'synth', text: 'partial' }],
    ['error', { agentId: 'synth', nodeId: 'synth-1', code: 'UPSTREAM', message: 'model failed' }], stop))
  await store.sendMessage('question')
  const answer = store.messages.at(-1)
  assert.equal(answer.status, 'error'); assert.equal(answer.content, 'partial'); assert.equal(answer.error.code, 'UPSTREAM')
})

test('multiple attempts with the same tool call id remain separate on resume', async () => {
  const started = ['tool_call', { toolName: 'lookup', callId: 'same' }]
  responses.push(frames(['meta', { runId: 'run_resume', traceId: 'first' }], started,
    ['tool_result', { toolName: 'lookup', callId: 'same', status: 'FAILED', error: 'busy' }],
    ['done', { finishReason: 'error', resumable: true }]))
  await store.sendMessage('retry tool')
  responses.push(frames(['meta', { runId: 'run_resume', traceId: 'second', resumed: true }], started,
    ['tool_result', { toolName: 'lookup', callId: 'same', status: 'SUCCESS', latencyMs: 8 }], stop))
  await store.resumeMessage(store.messages.at(-1).id)
  const answer = store.messages.at(-1)
  assert.deepEqual(answer.trace.filter(e => e.kind === 'tool').map(e => e.status), ['FAILED', 'SUCCESS'])
  assert.deepEqual(answer.toolCalls.map(c => c.status), ['FAILED', 'SUCCESS'])
})

test('pending checkpoints cannot duplicate a completed run or advertise an active run as resumable', async () => {
  responses.push(frames(['meta', { runId: 'run_finished' }], stop)); await store.sendMessage('question')
  api.pendingRuns = async () => ok({ runs: [{ runId: 'run_finished', active: false }, { runId: 'run_busy', active: true }, { runId: 'run_pending', active: false }] })
  await store.loadPendingRuns()
  assert.deepEqual(store.messages.filter(m => m.role === 'assistant').map(m => [m.runId, m.resumable]), [
    ['run_finished', false], ['run_pending', true]
  ])
})

test('a pending-only run cannot retry an unrelated preceding user question', async () => {
  responses.push(frames(['delta', { text: 'previous answer' }], stop)); await store.sendMessage('unrelated question')
  api.pendingRuns = async () => ok({ runs: [{ runId: 'run_pending', active: false }] }); await store.loadPendingRuns()
  await assert.rejects(store.retryMessage(store.messages.at(-1).id), /question/i)
  assert.equal(requests.length, 1)
})

test('model stage start and finish retain the original times and parent identities', async () => {
  responses.push(frames(['stage', { phase: 'model', status: 'start', nodeId: 'm', agentId: 'code', parentIds: ['ctx'], sequence: 1, at: 4 }],
    ['stage', { phase: 'model', status: 'done', nodeId: 'm', agentId: 'code', parentIds: [], sequence: 4, at: 10, ms: 6 }], stop))
  await store.sendMessage('question')
  const call = store.messages.at(-1).trace.find(s => s.kind === 'model')
  assert.equal(call.status, 'done'); assert.equal(call.at, 4); assert.equal(call.sequence, 1)
  assert.equal(call.lastAt, 10); assert.equal(call.lastSequence, 4); assert.deepEqual(call.parentIds, ['ctx'])
})

test('server order on fresh history remains intact when the browser cached only its suffix', async () => {
  store.messages = [{ id: 'q-last', role: 'user', content: 'last', runId: 'r_last' },
    { id: 'a-last', role: 'assistant', content: 'cached', runId: 'r_last', userMessageId: 'q-last', trace: [], status: 'done' }]
  api.listRuns = async () => ok([{ runId: 'r_first', input: 'first', answer: 'A', status: 'done' },
    { runId: 'r_last', input: 'last', answer: 'B', status: 'done' }])
  await store.refreshActiveSession()
  assert.deepEqual(store.messages.map(m => m.content), ['first', 'A', 'last', 'B'])
  assert.equal(store.messages.at(-1).id, 'a-last')
})

test('a failed or cancelled persisted turn is not upgraded by a contradictory stop summary', async () => {
  responses.push(frames(['meta', { runId: 'run_failed' }], ['error', { code: 'FAIL', message: 'failed' }], ['done', { finishReason: 'error' }]))
  await store.sendMessage('question')
  api.listRuns = async () => ok([{ runId: 'run_failed', input: 'question', status: 'done', finishReason: 'stop', trace: [
    { name: 'error', data: { code: 'TIMEOUT', message: 'timed out' } }, { name: 'done', data: { finishReason: 'timeout' } }
  ] }])
  await store.refreshActiveSession()
  assert.equal(store.messages.at(-1).status, 'interrupted'); assert.equal(store.messages.at(-1).finishReason, 'timeout')
})

test('previous session clear finishing after switching back cannot resurrect the cleared bubbles', async () => {
  responses.push(frames(['delta', { text: 'before' }], stop)); await store.sendMessage('question')
  const slow = Promise.withResolvers(); api.clearSessionMemory = () => slow.promise
  const clear = store.clearConversation(); await nextTick()
  await store.switchSession('other'); await store.switchSession('session_001')
  slow.resolve(ok({ cleared: true })); await clear
  assert.deepEqual(store.messages, []); assert.equal(store.messagesBySessionId.session_001, undefined)
})

test('restores an authenticated legacy transcript when a pre-workbench server has no runs route', async () => {
  api.listRuns = async () => { throw Object.assign(new Error('not found'), { response: { status: 404 } }) }
  api.sessionMessages = async () => ok({ messages: [{ role: 'user', content: 'historical' }] })
  await store.switchSession('legacy-only')
  assert.deepEqual(store.messages.map(m => m.content), ['historical'])
  api.listRuns = async () => { throw Object.assign(new Error('forbidden'), { response: { status: 403 } }) }
  api.sessionMessages = async () => { assert.fail('must not fall back on authorization failure') }
  await assert.rejects(store.refreshActiveSession(), /forbidden/)
})

test('an incomplete server trace does not erase unmatched cached reasoning, tool or artifact events for that run', async () => {
  const meta = ['meta', { runId: 'run_mix', traceId: 'attempt', sequence: 1, at: 0 }]
  const terminal = ['done', { runId: 'run_mix', sequence: 7, at: 12, finishReason: 'stop' }]
  responses.push(frames(meta, ['reasoning', { text: 'observed', nodeId: 'm', sequence: 2, at: 1 }],
    ['reasoning', { text: ' thought', nodeId: 'm', sequence: 3, at: 2 }],
    ['tool_call', { callId: 'c', toolName: 'lookup', sequence: 4, at: 4 }],
    ['tool_result', { callId: 'c', status: 'SUCCESS', sequence: 5, at: 5 }],
    ['artifact', { artifact: { id: 'art_retained', content: 'file' }, sequence: 6, at: 10 }], terminal))
  await store.sendMessage('question')
  api.listRuns = async () => ok([{ runId: 'run_mix', input: 'question', answer: 'server answer', status: 'done', trace: events([meta, terminal]) }])
  await store.refreshActiveSession(); await store.refreshActiveSession()
  const result = store.messages.at(-1)
  assert.deepEqual(result.trace.map(e => e.kind), ['meta', 'reasoning', 'tool', 'artifact', 'done'])
  assert.equal(result.trace[1].text, 'observed thought'); assert.equal(result.toolCalls[0].status, 'SUCCESS')
  assert.equal(result.artifacts[0].content, 'file'); assert.equal(result.content, 'server answer')
})

test('a legacy-only refresh can recover additional Redis turns without matching repeated text or destroying local traces', async () => {
  const turns = [{ role: 'user', content: 'again' }, { role: 'assistant', content: 'first' }]
  api.sessionMessages = async () => ok({ messages: turns })
  await store.switchSession('legacy')
  turns.push({ role: 'user', content: 'again' }, { role: 'assistant', content: 'second' })
  await store.refreshActiveSession()
  assert.deepEqual(store.messages.map(m => m.content), ['again', 'first', 'again', 'second'])
  responses.push(frames(['reasoning', { text: 'keep me' }], stop)); await store.sendMessage('local turn')
  turns.splice(0, turns.length, { role: 'user', content: 'again' }, { role: 'assistant', content: 'different archive' })
  await store.refreshActiveSession()
  assert.equal(store.messages.length, 6); assert.equal(store.messages.at(-1).trace[0].text, 'keep me')
})

test('overlapping clears on different sessions keep each pending clear locked independently', async () => {
  const first = Promise.withResolvers(), second = Promise.withResolvers()
  api.clearSessionMemory = sid => sid === 'session_001' ? first.promise : second.promise
  const one = store.clearConversation(); await store.switchSession('two')
  const two = store.clearConversation(); await store.switchSession('session_001')
  responses.push(frames(stop)); await store.sendMessage('must not be sent while clearing')
  first.resolve(ok({ cleared: true })); second.resolve(ok({ cleared: true })); await Promise.all([one, two])
  assert.equal(requests.length, 0)
})

test('terminal details survive a stale success summary following a cancelled or interrupted generation', async () => {
  responses.push(frames(['meta', { runId: 'run_lost' }], ['delta', { text: 'partial' }]))
  await store.sendMessage('question')
  api.listRuns = async () => ok([{ runId: 'run_lost', input: 'question', status: 'done', answer: 'partial', trace: [] }])
  await store.refreshActiveSession()
  assert.equal(store.messages.at(-1).status, 'interrupted')
})

test('cancel during session creation invalidates that generation without selecting its late result', async () => {
  store.sessionId = null
  const creating = Promise.withResolvers(); api.createSession = () => creating.promise
  const sending = store.sendMessage('unsent draft')
  store.cancelStream(); creating.resolve(ok({ sessionId: 'too-late' }))
  await assert.rejects(sending)
  assert.equal(store.sessionId, null); assert.deepEqual(store.messages, []); assert.deepEqual(requests, [])
})

test('the early partial text is checkpointed for a page reload without waiting for another event', async () => {
  let c
  responses.push(new ReadableStream({ start(controller) {
    c = controller; c.enqueue(new TextEncoder().encode(frames(['meta', { runId: 'run_reload' }], ['delta', { text: 'before stall' }])))
  } }))
  const sending = store.sendMessage('recover me'); await nextTick()
  const before = freshStore().messages.at(-1)
  c.enqueue(new TextEncoder().encode(frames(stop))); c.close(); await sending
  assert.equal(before.status, 'interrupted'); assert.equal(before.content, 'before stall')
})

test('a tools-unavailable stage is kept as its own trace step', async () => {
  responses.push(frames(
    ['meta', { runId: 'run_tools' }],
    ['stage', { phase: 'tools', status: 'unavailable', message: 'catalog timeout' }],
    ['delta', { text: '好' }],
    ['done', { finishReason: 'stop', runId: 'run_tools' }]
  ))
  await store.sendMessage('查询')
  const line = store.messages.at(-1).trace.find(step => step.kind === 'tools')
  assert.equal(line.status, 'unavailable')
  assert.equal(line.message, 'catalog timeout')
  assert.equal(store.messages.at(-1).status, 'done')
})

test('a rate-limited turn records when the backend allows another attempt, and resuming clears it', async () => {
  responses.push(frames(
    ['meta', { runId: 'run_limited' }],
    ['error', { code: 'AGENT_MODEL_50202', reason: 'RATE_LIMIT', message: 'quota', retryAfterSeconds: 20 }],
    ['done', { finishReason: 'error', resumable: true, runId: 'run_limited' }]
  ))
  await store.sendMessage('查询')
  const answer = store.messages.at(-1)
  assert.ok(answer.retryAt > Date.now())
  responses.push(frames(['meta', { runId: 'run_limited', resumed: true }], ['delta', { text: '答案' }],
    ['done', { finishReason: 'stop', resumable: false, runId: 'run_limited' }]))
  await store.resumeMessage(answer.id)
  assert.equal(answer.retryAt, null)
  assert.equal(answer.status, 'done')
})

test('an error without a wait from the backend sets no countdown', async () => {
  responses.push(frames(['meta', { runId: 'run_plain' }], ['error', { code: 'AGENT_MODEL_50202', message: 'boom' }],
    ['done', { finishReason: 'error', resumable: true, runId: 'run_plain' }]))
  await store.sendMessage('查询')
  assert.equal(store.messages.at(-1).retryAt, null)
})

test('a turn that never saw done can fetch the answer of a run that ended meanwhile', async () => {
  responses.push(frames(['meta', { runId: 'run_lost_001' }], ['delta', { text: '半句' }]))
  await store.sendMessage('查询')
  const answer = store.messages.at(-1)
  assert.equal(answer.status, 'interrupted')

  // The run is no longer pending, but the server still keeps its outcome.
  api.pendingRuns = async () => ok({ runs: [], finished: ['run_lost_001'] })
  await store.loadPendingRuns()
  assert.equal(answer.resumable, true)

  responses.push(frames(
    ['meta', { runId: 'run_lost_001', resumed: true }],
    ['delta', { text: '完整答案', replayed: true }],
    ['done', { finishReason: 'stop', resumable: false, runId: 'run_lost_001' }]
  ))
  await store.resumeMessage(answer.id)
  assert.equal(answer.content, '完整答案')
  assert.equal(answer.status, 'done')

  // Once its end was seen here, a kept outcome is not offered again.
  await store.loadPendingRuns()
  assert.equal(answer.resumable, false)
})

test('a run whose outcome expired is no longer offered', async () => {
  responses.push(frames(['meta', { runId: 'run_gone_001' }], ['delta', { text: '半句' }]))
  await store.sendMessage('查询')
  api.pendingRuns = async () => ok({ runs: [] })
  api.cancelRun = async (sid, runId) => ok({ runId, status: 'cancelled' })
  await store.loadPendingRuns()
  assert.equal(store.messages.at(-1).resumable, false)
})

test('T1 explicit local errors do not fail successful synth; filter and timeout stay truthful', async () => {
  responses.push(frames(
    ['meta', { runId: 'run_t1_001', attemptId: 'a', sequence: 1 }],
    ['error', { scope: 'agent', agentId: 'assistant', message: 'optional', sequence: 2, attemptId: 'a' }],
    ['delta', { text: 'answer', sequence: 3, attemptId: 'a' }],
    ['done', { finishReason: 'stop', sequence: 4, attemptId: 'a' }]
  ))
  const msg = await store.sendMessage('fixture')
  assert.equal(msg.status, 'done')
  assert.equal(msg.error, null)
})

test('T1 late old attempt cannot overwrite resumed answer or close stream', async () => {
  responses.push(frames(
    ['meta', { runId: 'run_t1_002', attemptId: 'old', sequence: 1 }],
    ['delta', { text: 'old', attemptId: 'old', sequence: 2 }],
    ['meta', { runId: 'run_t1_002', attemptId: 'new', resumed: true, sequence: 1 }],
    ['delta', { text: 'new', attemptId: 'new', sequence: 2 }],
    ['done', { finishReason: 'cancelled', attemptId: 'old', sequence: 3 }],
    ['delta', { text: ' answer', attemptId: 'new', sequence: 3 }],
    ['done', { finishReason: 'stop', attemptId: 'new', sequence: 4 }]
  ))
  const msg = await store.sendMessage('fixture')
  assert.equal(msg.content, 'new answer')
  assert.equal(msg.status, 'done')
})

test('T1 stop waits for release confirmation and drains authoritative usage footer', async () => {
  let controller
  responses.push(new ReadableStream({ start(c) { controller = c } }))
  const release = Promise.withResolvers()
  let cancels = 0
  api.cancelRun = (sid, runId) => { cancels++; assert.equal(sid, 'session_001'); return release.promise }
  const sending = store.sendMessage('fixture'); await nextTick()
  const runId = store.messages.at(-1).runId
  controller.enqueue(new TextEncoder().encode(frames(['meta', { runId, attemptId: 'a', sequence: 1 }])))
  await nextTick()
  const stopping = store.cancelStream()
  assert.equal(store.messages.at(-1).status, 'stop_requested')
  assert.equal(store.streaming, true)
  assert.equal(cancels, 1)
  controller.enqueue(new TextEncoder().encode(frames(
    ['usage', { runId, attemptId: 'a', sequence: 2, modelCalls: 4, toolCallCount: 1,
      inputTokens: null, outputTokens: null, totalTokens: null, usageReported: false, usageComplete: false }],
    ['done', { runId, attemptId: 'a', sequence: 3, finishReason: 'cancelled', resumable: true }]
  )))
  controller.close()
  release.resolve(ok({ runId, status: 'cancelled' }))
  await Promise.all([sending, stopping])
  assert.equal(store.messages.at(-1).status, 'cancelled')
  assert.equal(store.messages.at(-1).modelCalls, 4)
  assert.equal(store.messages.at(-1).usage.totalTokens, null)
  assert.equal(store.messages.at(-1).resumable, true)
})

test('T1 not_active keeps completed result and cancellation failure cannot erase history', async () => {
  let controller
  responses.push(new ReadableStream({ start(c) { controller = c } }))
  const sending = store.sendMessage('fixture'); await nextTick()
  const runId = store.messages.at(-1).runId
  api.cancelRun = async () => { throw new Error('release unavailable') }
  await assert.rejects(store.clearConversation(), /release unavailable/)
  assert.equal(store.messages.length, 2)
  assert.equal(store.messages.at(-1).status, 'streaming')
  api.cancelRun = async () => ok({ runId, status: 'not_active' })
  const stopping = store.cancelStream()
  controller.enqueue(new TextEncoder().encode(frames(['delta', { text: 'finished' }], ['done', { finishReason: 'stop' }])))
  controller.close(); await Promise.all([sending, stopping])
  assert.equal(store.messages.at(-1).status, 'done')
  assert.equal(store.messages.at(-1).finishReason, 'stop')
})

test('T1 reload keeps same-numbered same-named frames of both attempts and one copy of a replayed frame', async () => {
  const frame = (name, attemptId, sequence, data = {}) => [name, { runId: 'run_t1_reload', attemptId, sequence, at: sequence * 10, runtimeContractVersion: 1, ...data }]
  const tool = (attemptId, sequence, status) => frame('tool_result', attemptId, sequence, { toolName: 'lookup', callId: 'same', status })
  api.listRuns = async () => ok([{ runId: 'run_t1_reload', input: 'fixture', status: 'done', finishReason: 'stop', trace: events([
    frame('meta', 'attempt_a', 1), frame('tool_call', 'attempt_a', 2, { toolName: 'lookup', callId: 'same' }), tool('attempt_a', 3, 'FAILED'),
    frame('delta', 'attempt_a', 4, { text: 'old' }),
    frame('error', 'attempt_a', 5, { scope: 'run', code: 'AGENT_MODEL_50202', message: 'first attempt failed' }),
    frame('done', 'attempt_a', 6, { finishReason: 'error', resumable: true }),
    frame('meta', 'attempt_b', 1, { resumed: true }), frame('tool_call', 'attempt_b', 2, { toolName: 'lookup', callId: 'same' }),
    tool('attempt_b', 3, 'SUCCESS'), tool('attempt_b', 3, 'SUCCESS'),
    frame('delta', 'attempt_b', 4, { text: 'new' }), frame('delta', 'attempt_b', 4, { text: 'new' }),
    frame('done', 'attempt_b', 5, { finishReason: 'stop' })
  ]) }])
  await store.refreshActiveSession()
  const answer = store.messages.at(-1)
  const identity = kind => answer.trace.filter(e => e.kind === kind).map(e => [e.attemptId, e.sequence])
  assert.deepEqual(identity('meta'), [['attempt_a', 1], ['attempt_b', 1]])
  assert.deepEqual(answer.trace.filter(e => e.kind === 'tool').map(e => [e.attemptId, e.status]), [['attempt_a', 'FAILED'], ['attempt_b', 'SUCCESS']])
  assert.equal(answer.content, 'new')
  // The resumed attempt succeeded: the first attempt's run error stays in its trace, not on the run.
  assert.equal(answer.status, 'done'); assert.equal(answer.error, null)
  assert.equal(answer.trace.filter(e => e.kind === 'error').length, 1)
})

test('T1 reload after an analyst failed and the synthesis succeeded is still a success', async () => {
  const frame = (name, sequence, data = {}) => [name, { runId: 'run_t1_role', attemptId: 'attempt_a', sequence, at: sequence, runtimeContractVersion: 1, ...data }]
  api.listRuns = async () => ok([{ runId: 'run_t1_role', input: 'fixture', answer: 'joined', status: 'done', finishReason: 'stop', error: null, modelCalls: 4, trace: events([
    frame('meta', 1), frame('stage', 2, { phase: 'model', status: 'start', agentId: 'doc', nodeId: 'collaboration-doc' }),
    frame('error', 3, { scope: 'agent', agentId: 'doc', nodeId: 'collaboration-doc', code: 'AGENT_MODEL_50202', message: 'analyst quota' }),
    frame('stage', 4, { phase: 'model', status: 'failed', agentId: 'doc', nodeId: 'collaboration-doc' }),
    frame('delta', 5, { text: 'joined' }), frame('usage', 6, { modelCalls: 4, usageReported: false }), frame('done', 7, { finishReason: 'stop' })
  ]) }])
  await store.refreshActiveSession()
  const answer = store.messages.at(-1)
  assert.equal(answer.status, 'done'); assert.equal(answer.error, null); assert.equal(answer.content, 'joined')
  assert.equal(answer.modelCalls, 4)
  assert.equal(answer.trace.find(e => e.kind === 'error').agentId, 'doc')
  const boot = freshStore(); await boot.hydrateActiveSession()
  assert.equal(boot.messages.at(-1).status, 'done'); assert.equal(boot.messages.at(-1).error, null)
})

// T2 contract regressions: fixture dialog is an explicit action, never production auto-consent.
test('T2 unconfirmed nonempty scope executes zero requests; edits clear consent', async () => {
  const workspace = useAgentWorkspaceStore()
  await workspace.loadScope(store.sessionId)
  const scope = { targets: [{ type: 'group', id: 8 }], timeRange: { fromInclusive: null, toExclusive: null, timeZone: 'UTC' } }
  workspace.setScope(scope)
  await assert.rejects(store.sendMessage('read'), /confirm/i)
  assert.equal(requests.length, 0)
  workspace.confirmScope(); workspace.setScope({ ...scope, targets: [{ type: 'contact', id: 8 }] })
  await assert.rejects(store.sendMessage('read'), /confirm/i)
  assert.equal(requests.length, 0)
})
test('T2 explicit empty scope ignores legacy chatId; retry and resume ask anew', async () => {
  responses.push(frames(['meta', { runId: 'run_scope' }], ['delta', { text: 'partial' }]))
  await store.sendMessage('ordinary task', { chatId: 999 })
  assert.deepEqual(requests[0].body.scopeSelection.targets, [])
  assert.equal(Object.hasOwn(requests[0].body, 'chatContext'), false)
  const live = store.messages.at(-1)
  responses.push(frames(['meta', { runId: 'run_scope', resumed: true }], stop))
  await store.resumeMessage(live.id)
  assert.equal(confirmations.length, 1)
  assert.equal(requests[1].body.scopeConfirmed, true)
  assert.equal(requests[1].body.expectedBindingVersion, 1)
  responses.push(frames(stop)); await store.retryMessage(live.id)
  assert.equal(confirmations.length, 2)
  assert.equal(requests[2].body.scopeConfirmed, true)
  assert.deepEqual(requests[2].body.scopeSelection, requests[0].body.scopeSelection)
})

test('first send preserves explicitly confirmed A, never adds B, and loads version 1', async () => {
  store.sessionId = null
  const workspace = useAgentWorkspaceStore()
  workspace.select(null)
  workspace.setScope({ targets: [{ type: 'contact', id: 'A' }], timeRange: { fromInclusive: null, toExclusive: null, timeZone: 'UTC' } })
  workspace.confirmScope()
  responses.push(frames(['done', { finishReason: 'stop' }]))
  await store.sendMessage('first')
  assert.deepEqual(requests[0].body.scopeSelection.targets, [{ type: 'contact', id: 'A' }])
  assert.equal(requests[0].body.scopeConfirmed, true)
  assert.equal(requests[0].body.expectedBindingVersion, 1)
})
test('unconfirmed first send has no stream or session creation request', async () => {
  store.sessionId = null
  const workspace = useAgentWorkspaceStore(); workspace.select(null)
  workspace.setScope({ targets: [{ type: 'contact', id: 'A' }], timeRange: { fromInclusive: null, toExclusive: null, timeZone: 'UTC' } })
  let creations = 0; api.createSession = async () => { creations++; return ok({ sessionId: 'new' }) }
  await assert.rejects(store.sendMessage('first'), /confirm/i)
  assert.equal(creations, 0); assert.equal(requests.length, 0)
})
test('empty first send explicitly confirms empty scope at actual version 1', async () => {
  store.sessionId = null
  useAgentWorkspaceStore().select(null)
  responses.push(frames(['done', { finishReason: 'stop' }]))
  await store.sendMessage('ordinary task')
  assert.deepEqual(requests[0].body.scopeSelection.targets, [])
  assert.equal(requests[0].body.scopeConfirmed, true)
  assert.equal(requests[0].body.expectedBindingVersion, 1)
})

const receiptFixture = (runId, version = 2, extra = {}) => ({ schemaVersion: 1, grantId: 'grant_test', actorUserId: 7,
  sessionId: 'session_001', runId, bindingVersion: 1, authorizationVersion: version, sessionGeneration: 1,
  chatIds: [31], contactUserIds: [12], timeRange: { fromInclusive: null, toExclusive: null, timeZone: 'UTC' }, scopeKey: 'a'.repeat(64), ...extra })

test('actual reducer and Java history/pending preserve v2 receipts, never local confirmation', async () => {
  const workspace = useAgentWorkspaceStore()
  await workspace.loadScope('session_001')
  workspace.confirmScope()
  assert.equal(store.messages.some(m => m.actualScope), false)
  responses.push(frames(['meta', { runId: 'run_receipt', scope: receiptFixture('run_receipt') }],
    ['meta', { runId: 'run_receipt', scope: receiptFixture('run_receipt', 1) }],
    ['meta', { runId: 'run_receipt', scope: receiptFixture('run_receipt', 3, { actorUserId: 8 }) }],
    ['meta', { runId: 'run_receipt', scope: receiptFixture('run_receipt', 3, { sessionId: 'other_session' }) }],
    ['meta', { runId: 'run_receipt', scope: receiptFixture('other_run', 3) }],
    ['done', { finishReason: 'stop' }]))
  await store.sendMessage('receipt')
  const message = store.messages.find(m => m.role === 'assistant')
  assert.equal(message.actualScope.authorizationVersion, 2)
  assert.deepEqual(message.actualScope.chatIds, [31])
  api.listRuns = async () => ok([{ runId: 'run_receipt', status: 'done', scope: receiptFixture('run_receipt') }])
  api.pendingRuns = async () => ok({ runs: [], finished: ['run_receipt'], finishedScopes: { run_receipt: receiptFixture('run_receipt') } })
  await store.refreshActiveSession()
  assert.deepEqual(store.messages.find(m => m.runId === 'run_receipt' && m.role === 'assistant').actualScope, message.actualScope)
  api.pendingRuns = async () => ok({ runs: [{ runId: 'run_receipt', scope: receiptFixture('run_receipt', 1) }] })
  await store.loadPendingRuns()
  assert.equal(store.messages.find(m => m.role === 'assistant').actualScope.authorizationVersion, 2)
  api.pendingRuns = async () => ok({ runs: [{ runId: 'run_receipt', scope: receiptFixture('run_receipt', 3, { scopeKey: 'invalid' }) }] })
  await store.loadPendingRuns()
  assert.equal(store.messages.find(m => m.role === 'assistant').actualScope.authorizationVersion, 2)
  assert.equal(store.messages.find(m => m.role === 'assistant').scopeState, 'unknown')
})

const scopeSnapshot = () => ({ targets: [{ type: 'contact', id: 12 }],
  timeRange: { fromInclusive: null, toExclusive: null, timeZone: 'UTC' } })
async function cachePrivateSentinel() {
  responses.push(frames(['meta', { runId: 'run_redacted', scope: receiptFixture('run_redacted') }],
    ['delta', { text: 'B_PRIVATE_SENTINEL' }], ['reasoning', { text: 'B_PRIVATE_SENTINEL' }],
    ['tool_call', { callId: 'b', toolName: 'lookup', args: { value: 'B_PRIVATE_SENTINEL' } }],
    ['tool_result', { callId: 'b', toolName: 'lookup', status: 'SUCCESS', result: 'B_PRIVATE_SENTINEL' }],
    ['sources', { items: [{ id: 'b', excerpt: 'B_PRIVATE_SENTINEL' }] }],
    ['artifact', { artifact: { id: 'b', name: 'B_PRIVATE_SENTINEL' } }], ['done', { finishReason: 'stop' }]))
  await store.sendMessage('original user question')
  // Exercise top-level old-cache reasoning too, in addition to real reducer trace.
  store.messages.at(-1).reasoning = 'B_PRIVATE_SENTINEL'
  api.listRuns = async () => ok([{ runId: 'run_redacted', status: 'done', scope: receiptFixture('run_redacted'), scopeSelection: scopeSnapshot() }])
  await store.refreshActiveSession()
  assert.ok(storage.get('agent.sessionMessages:7').includes('B_PRIVATE_SENTINEL'))
  const before = store.messages.at(-1)
  assert.ok(before.trace.length); assert.ok(before.toolCalls.length)
  assert.ok(before.sources.length); assert.ok(before.artifacts.length)
}
const unavailableRow = extra => ({ runId: 'run_redacted', sessionId: 'session_001', status: 'done',
  scopeState: 'unavailable', scope: null, input: 'original user question',
  scopeSelection: scopeSnapshot(), expectedBindingVersion: 3, ...extra })

test('Java unavailable read view purges cached private B, persists redaction, and preserves real done/input/selection', async () => {
  await cachePrivateSentinel()
  api.listRuns = async () => ok([unavailableRow()])
  // A historical checkpoint must not resurrect a revoked receipt.
  api.pendingRuns = async () => ok({ runs: [], finished: ['run_redacted'], finishedScopes: { run_redacted: receiptFixture('run_redacted') } })
  await store.refreshActiveSession()
  const msg = store.messages.at(-1)
  assert.equal(msg.status, 'done'); assert.equal(msg.error, null)
  assert.equal(msg.scopeState, 'unavailable'); assert.equal(msg.receiptState, 'unknown'); assert.equal(msg.actualScope, null)
  assert.equal(msg.content, ''); assert.equal(msg.reasoning, '')
  for (const key of ['trace', 'toolCalls', 'sources', 'artifacts']) assert.deepEqual(msg[key], [])
  assert.equal(msg.input, 'original user question'); assert.deepEqual(msg.scopeSelection, scopeSnapshot())
  assert.equal(msg.expectedBindingVersion, 3)
  assert.equal(store.messages[0].content, 'original user question')
  assert.equal((JSON.stringify(store.messages).match(/B_PRIVATE_SENTINEL/g) || []).length, 0)
  assert.equal((storage.get('agent.sessionMessages:7').match(/B_PRIVATE_SENTINEL/g) || []).length, 0)
  const boot = freshStore()
  assert.equal(boot.messages.at(-1).scopeState, 'unavailable')
  assert.equal(boot.messages.at(-1).status, 'done')
  responses.push(frames(['delta', { text: 'A_RETRY_RESULT' }], ['done', { finishReason: 'stop' }]))
  await boot.retryMessage(boot.messages.at(-1).id)
  assert.equal(confirmations.length, 1)
  assert.deepEqual(requests.at(-1).body.scopeSelection, scopeSnapshot())
})

test('available nonempty A restores normally after B redaction', async () => {
  await cachePrivateSentinel()
  api.listRuns = async () => ok([unavailableRow()]); await store.refreshActiveSession()
  api.listRuns = async () => ok([{ ...unavailableRow(), scopeState: 'available', scope: receiptFixture('run_redacted', 3),
    answer: 'A_ALLOWED_RESULT', trace: [{ name: 'reasoning', data: { text: 'A_ALLOWED_REASONING' } }],
    sources: [{ id: 'a', excerpt: 'A_ALLOWED_SOURCE' }], artifacts: [{ id: 'a', name: 'A_ALLOWED_ARTIFACT' }] }])
  await store.refreshActiveSession()
  const msg = store.messages.at(-1)
  assert.equal(msg.content, 'A_ALLOWED_RESULT'); assert.equal(msg.status, 'done'); assert.equal(msg.error, null)
  assert.equal(msg.scopeState, 'observed'); assert.ok(msg.trace.length)
  assert.equal(msg.sources[0].excerpt, 'A_ALLOWED_SOURCE'); assert.equal(msg.artifacts[0].name, 'A_ALLOWED_ARTIFACT')
  assert.equal(storage.get('agent.sessionMessages:7').includes('B_PRIVATE_SENTINEL'), false)
})

test('misbound unavailable rows cannot redact the current matching run ID', async () => {
  await cachePrivateSentinel()
  api.listRuns = async () => ok([unavailableRow({ actorUserId: 8 }), unavailableRow({ sessionId: 'other_session' })])
  await store.refreshActiveSession()
  assert.equal(store.messages.at(-1).content, 'B_PRIVATE_SENTINEL')
  assert.ok(storage.get('agent.sessionMessages:7').includes('B_PRIVATE_SENTINEL'))
})

test('session and owner late unavailable replies cannot clear current private cache', async () => {
  await cachePrivateSentinel()
  const slow = Promise.withResolvers(); api.listRuns = () => slow.promise
  const reading = store.refreshActiveSession()
  api.listRuns = async () => ok([])
  await store.switchSession('other_session')
  responses.push(frames(['meta', { runId: 'run_redacted' }], ['delta', { text: 'CURRENT_SESSION_RESULT' }], ['done', { finishReason: 'stop' }]))
  await store.sendMessage('current session input')
  slow.resolve(ok([unavailableRow()])); await reading
  assert.equal(store.messages.at(-1).content, 'CURRENT_SESSION_RESULT')
  assert.ok(storage.get('agent.sessionMessages:7').includes('CURRENT_SESSION_RESULT'))
  const ownerSlow = Promise.withResolvers(); api.listRuns = () => ownerSlow.promise
  const oldRead = store.refreshActiveSession()
  storage.set('user', '{"id":8}'); storage.set('token', 'token-eight')
  storage.set('agent.sessionId:8', 'session_001')
  const nextOwner = freshStore()
  responses.push(frames(['meta', { runId: 'run_redacted' }], ['delta', { text: 'CURRENT_OWNER_RESULT' }], ['done', { finishReason: 'stop' }]))
  await nextOwner.sendMessage('current owner input')
  ownerSlow.resolve(ok([unavailableRow()])); await oldRead
  assert.equal(nextOwner.messages.at(-1).content, 'CURRENT_OWNER_RESULT')
  assert.ok(storage.get('agent.sessionMessages:8').includes('CURRENT_OWNER_RESULT'))
})

test('unavailable legacy run with no original selection cannot infer contact from an old receipt', async () => {
  api.listRuns = async () => ok([{ ...unavailableRow(), scopeSelection: null }])
  await store.refreshActiveSession()
  const count = requests.length
  await assert.rejects(store.retryMessage(store.messages.at(-1).id))
  assert.equal(confirmations.length, 0); assert.equal(requests.length, count)
  assert.equal(store.messages.at(-1).status, 'done')
  assert.equal(store.messages.at(-1).error, null)
})

test('a selection confirmed after the binding was invalidated is sent as confirmed, not swapped for the saved binding', async () => {
  const workspace = useAgentWorkspaceStore()
  await workspace.loadScope('session_001')
  workspace.invalidateScope() // what a cleared conversation or a refused turn leaves behind
  const chosen = { targets: [{ type: 'group', id: 8 }], timeRange: { fromInclusive: null, toExclusive: null, timeZone: 'UTC' } }
  workspace.setScope(chosen); workspace.confirmScope()
  responses.push(frames(stop))
  await store.sendMessage('read the group')
  assert.deepEqual(requests[0].body.scopeSelection.targets, [{ type: 'group', id: 8 }])
  assert.equal(requests[0].body.scopeConfirmed, true)
  assert.equal(requests[0].body.expectedBindingVersion, 1)
})

test('an unconfirmed selection is not sent as the empty saved binding after an invalidation', async () => {
  const workspace = useAgentWorkspaceStore()
  await workspace.loadScope('session_001')
  workspace.invalidateScope()
  workspace.setScope({ targets: [{ type: 'group', id: 8 }], timeRange: { fromInclusive: null, toExclusive: null, timeZone: 'UTC' } })
  await assert.rejects(store.sendMessage('read the group'), /confirm/i)
  assert.equal(requests.length, 0)
  assert.deepEqual(workspace.scopeSelection.targets, [{ type: 'group', id: 8 }])
})

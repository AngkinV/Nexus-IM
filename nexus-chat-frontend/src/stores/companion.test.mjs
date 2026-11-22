import { after, before, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'
import { watch } from 'vue'

let server, useCompanionStore, api, store
const originalStorage = globalThis.localStorage
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

before(async () => {
  globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} }
  server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('../../', import.meta.url)),
    resolve: { alias: { '@': fileURLToPath(new URL('../', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, watch: null },
    logLevel: 'error'
  })
  ;({ useCompanionStore } = await server.ssrLoadModule('/src/stores/companion.js'))
  ;({ companionAPI: api } = await server.ssrLoadModule('/src/services/api.js'))
})

after(async () => {
  await server?.close()
  globalThis.localStorage = originalStorage
})

beforeEach(() => {
  setActivePinia(createPinia())
  store = useCompanionStore()
  store.roles = [{ id: 1, lastMessageAt: '2026-11-20', presence: 'old state' }, { id: 2 }]
  store.activeRoleId = 1
  store.messages = [{ id: 11, roleId: 1, content: 'stored message' }]
  store.activeSession = { id: 21, roleId: 1 }
  store.sessionHistory = [{ id: 20 }]
  store.memories = [{ id: 31, content: 'independent memory' }]
  store.growth = { intimacy: 42 }
  store.status = { summary: 'old state' }
  api.clearConversation = async () => ({ data: { deletedMessages: 1, deletedSessions: 2 } })
  api.getConversation = async () => ({ data: [{ id: 11, roleId: 1, content: 'stored message' }] })
  api.getMemories = async () => ({ data: [{ id: 31, content: 'independent memory' }] })
  api.getGrowth = async () => ({ data: { intimacy: 42 } })
  api.getStatus = async () => ({ data: { summary: 'old state' } })
  api.activeSession = async () => ({ data: { id: 21, roleId: 1 } })
  api.sessionHistory = async () => ({ data: [{ id: 20 }] })
  api.getCredentialStatus = async () => ({ data: null })
  api.getRoles = async () => ({ data: store.roles.map(role => ({ ...role })) })
  api.initRoles = api.getRoles
  api.restoreConversation = async () => ({ status: 204 })
})

test('only removes the conversation after server success and keeps the character', async () => {
  const response = deferred()
  api.clearConversation = (roleId, userId) => {
    assert.equal(roleId, 1)
    assert.equal(userId, 7)
    return response.promise
  }
  const clearing = store.clearConversation(7, 1)
  assert.equal(store.messages.length, 1)
  assert.equal(store.isRoleBusy(1), true)
  response.resolve({ data: { deletedMessages: 1, deletedSessions: 2 } })
  await clearing
  assert.deepEqual(store.messages, [])
  assert.deepEqual(store.sessionHistory, [])
  assert.equal(store.activeSession, null)
  assert.equal(store.status, null)
  assert.equal(store.roles[0].lastMessageAt, null)
  assert.equal(store.roles[0].presence, null)
  assert.equal(store.roles.length, 2)
  assert.equal(store.roles[0].conversationHidden, true)
  assert.deepEqual(store.conversationRoles.map(role => role.id), [2])
  assert.equal(store.activeRoleId, null)
  assert.equal(store.activeRole, null)
  assert.deepEqual(store.memories, [])
  assert.equal(store.growth, null)
  assert.equal(store.isRoleBusy(1), false)
})

test('late history, session, status and role responses cannot restore deleted data', async () => {
  const response = deferred()
  api.getConversation = api.activeSession = api.sessionHistory = api.getStatus = api.getRoles = () => response.promise
  const reads = [store.fetchConversation(7, 1), store.fetchActiveSession(7, 1),
    store.fetchSessionHistory(7, 1), store.fetchStatus(7, 1), store.fetchRoles(7)]
  await store.clearConversation(7, 1)
  response.resolve({ data: [{ id: 99, roleId: 1, content: 'stale', lastMessageAt: 'old' }] })
  await Promise.all(reads)
  assert.deepEqual(store.messages, [])
  assert.deepEqual(store.sessionHistory, [])
  assert.equal(store.activeSession, null)
  assert.equal(store.status, null)
  assert.equal(store.roles[0].id, 1)
  assert.equal(store.roles[0].lastMessageAt, null)
})

test('a read started during deletion cannot restore data after deletion completes', async () => {
  const deletion = deferred(), read = deferred()
  api.clearConversation = () => deletion.promise
  api.getConversation = () => read.promise
  const clearing = store.clearConversation(7, 1)
  const reading = store.fetchConversation(7, 1)
  deletion.resolve({ data: {} })
  await clearing
  read.resolve({ data: [{ id: 11, content: 'stale' }] })
  await reading
  assert.deepEqual(store.messages, [])
})

test('switching roles while clearing never clears the newly selected role', async () => {
  const response = deferred()
  api.clearConversation = () => response.promise
  const clearing = store.clearConversation(7, 1)
  store.activeRoleId = 2
  store.messages = [{ id: 22, roleId: 2 }]
  store.activeSession = { id: 44, roleId: 2 }
  response.resolve({ data: {} })
  await clearing
  assert.equal(store.messages[0].id, 22)
  assert.equal(store.activeSession.id, 44)
  assert.equal(store.roles[0].lastMessageAt, null)
})

test('failed deletion rejects and reloads persisted history rather than claiming success', async () => {
  const failure = new Error('database unavailable')
  api.clearConversation = async () => { throw failure }
  await assert.rejects(store.clearConversation(7, 1), failure)
  assert.equal(store.messages[0].id, 11)
  assert.equal(store.activeSession.id, 21)
  assert.equal(store.roles[0].lastMessageAt, '2026-11-20')
  assert.equal(store.isRoleBusy(1), false)
})

test('clear and send are mutually exclusive and double deletion is rejected', async () => {
  const response = deferred()
  api.clearConversation = () => response.promise
  const clearing = store.clearConversation(7, 1)
  await assert.rejects(store.clearConversation(7, 1), error => error.response.status === 409)
  assert.equal(await store.sendMessage(7, 1, 'too soon'), null)
  assert.equal(await store.sendMessageStream(7, 1, 'too soon'), null)
  response.resolve({ data: {} })
  await clearing
})

test('reload never selects a removed conversation and keeps unused characters visible', async () => {
  const persisted = [{ id: 1, conversationHidden: true }, { id: 2, conversationHidden: false }]
  api.initRoles = api.getRoles = async () => ({ data: persisted.map(role => ({ ...role })) })
  setActivePinia(createPinia())
  store = useCompanionStore()
  await store.ensureInitialized(7)
  assert.deepEqual(store.conversationRoles.map(role => role.id), [2])
  assert.equal(store.activeRoleId, 2)
  assert.equal(store.activeRole.id, 2)
  persisted[1].conversationHidden = true
  await store.ensureInitialized(7)
  assert.deepEqual(store.conversationRoles, [])
  assert.equal(store.roles.length, 2)
  assert.equal(store.activeRoleId, null)
  assert.equal(store.activeRole, null)
  await store.setActiveRole(7, 1)
  assert.equal(store.activeRoleId, null)
})

test('reopening waits for persistence and cannot restore deleted history', async () => {
  await store.clearConversation(7, 1)
  const response = deferred(), stale = deferred()
  api.getRoles = () => stale.promise
  const reading = store.fetchRoles(7)
  api.restoreConversation = (roleId, userId) => {
    assert.equal(roleId, 1)
    assert.equal(userId, 7)
    return response.promise
  }
  api.getConversation = api.sessionHistory = async () => ({ data: [] })
  api.activeSession = async () => ({ status: 204 })
  const restoring = store.restoreConversation(7, 1)
  assert.equal(store.roles[0].conversationHidden, true)
  assert.equal(store.activeRole, null)
  await assert.rejects(store.clearConversation(7, 1), error => error.response.status === 409)
  response.resolve({ status: 204 })
  await restoring
  stale.resolve({ data: [{ id: 1, conversationHidden: true }] })
  await reading
  assert.equal(store.roles[0].conversationHidden, false)
  assert.equal(store.conversationRoles.length, 2)
  assert.equal(store.activeRoleId, 1)
  assert.deepEqual(store.messages, [])
  assert.deepEqual(store.sessionHistory, [])
  assert.equal(store.activeSession, null)
  assert.equal(store.memories[0].id, 31)
  assert.equal(store.growth.intimacy, 42)
  assert.equal(store.isRoleBusy(1), false)
})

test('failed reopening keeps the conversation removed', async () => {
  await store.clearConversation(7, 1)
  const failure = new Error('database unavailable')
  api.restoreConversation = async () => { throw failure }
  await assert.rejects(store.restoreConversation(7, 1), failure)
  assert.equal(store.roles[0].conversationHidden, true)
  assert.deepEqual(store.conversationRoles.map(role => role.id), [2])
  assert.equal(store.activeRole, null)
  assert.equal(store.isRoleBusy(1), false)
})

const limited = { code: 'RATE_LIMITED', summary: 'Channel quota exhausted', retryAfterSeconds: 35 }
const frame = (name, data) => `event: ${name}\r\ndata: ${JSON.stringify(data)}\r\n\r\n`

test('typed terminal error retains streamed text and client id without accepting a fallback or resending', async t => {
  const requests = []
  t.mock.method(globalThis, 'fetch', async (_, options) => {
    requests.push(JSON.parse(options.body))
    const body = requests.length === 1
      ? frame('delta', 'half a line') + frame('done', { userMessage: { id: 42, senderType: 'user', content: 'hello' }, modelError: limited, partial: true })
      : frame('done', { userMessage: { id: 42, senderType: 'user', content: 'hello', clientMsgId: 'msg-1' }, roleMessage: { id: 43, senderType: 'role', content: 'finished' } })
    return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
  })
  api.sendMessage = () => assert.fail('no plain resend')
  const failure = await store.sendMessageStream(7, 1, 'hello', null, 'msg-1')
  assert.deepEqual(failure.modelError, limited)
  assert.equal(failure.clientMsgId, 'msg-1')
  assert.equal(failure.partial, true)
  assert.equal(requests.length, 1)
  const row = store.messages.find(m => m.senderType === 'user')
  assert.equal(row.clientMsgId, 'msg-1')
  assert.deepEqual(row.modelError, limited)
  assert.equal(row.failed, true)
  assert.equal(store.messages.at(-1).content, 'half a line')
  assert.equal(store.messages.at(-1).live, false)
  assert.equal(store.isSending, false)
  await store.sendMessageStream(7, 1, row.content, null, row.clientMsgId)
  assert.deepEqual(requests.map(r => r.clientMsgId), ['msg-1', 'msg-1'])
  assert.deepEqual(store.messages.slice(1).map(m => m.content), ['hello', 'finished'])
})

test('a typed error outranks a roleMessage in both plain and stream exchanges', async t => {
  const result = { modelError: limited, partial: true, roleMessage: { id: 24, senderType: 'role', content: 'saved partial' } }
  api.sendMessage = async () => ({ data: result })
  const plain = await store.sendMessage(7, 1, 'a', null, 'plain')
  assert.deepEqual(plain.modelError, limited)
  assert.ok(store.messages.find(m => m.clientMsgId === 'plain' && m.failed))
  assert.equal(store.messages.at(-1).content, 'saved partial')
  t.mock.method(globalThis, 'fetch', async () => new Response(frame('error', { modelError: limited })))
  const streamed = await store.sendMessageStream(7, 1, 'b', null, 'stream')
  assert.deepEqual(streamed.modelError, limited)
  assert.equal(streamed.clientMsgId, 'stream')
})

test('404/405 model failures and ambiguous errors never authorize a plain resend; a missing internal route does', async t => {
  const path = '/api/companion/messages/stream'
  let response
  t.mock.method(globalThis, 'fetch', async () => response)
  for (const status of [404, 405]) {
    response = Response.json({ modelError: { code: 'MODEL_NOT_FOUND', summary: 'Unknown upstream alias' } }, { status, headers: { 'Retry-After': '12' } })
    const result = await store.sendMessageStream(7, 1, 'hello')
    assert.equal(result.modelError.code, 'MODEL_NOT_FOUND')
    assert.equal(result.modelError.retryAfterSeconds, 12)
    assert.ok(!result.unsupported)
    response = Response.json({ status, message: 'model unavailable' }, { status })
    assert.ok(!(await store.sendMessageStream(7, 1, 'hello'))?.unsupported)
    const routeError = { path, status, error: status === 404 ? 'Not Found' : 'Method Not Allowed' }
    response = Response.json(routeError, { status })
    assert.ok(!(await store.sendMessageStream(7, 1, 'hello'))?.unsupported)
    response = Response.json({ ...routeError, message: status === 404 ? `No static resource ${path.slice(1)}.` : "Request method 'POST' is not supported" }, { status })
    const missing = await store.sendMessageStream(7, 1, 'hello', null, `missing-${status}`)
    assert.deepEqual(missing, { unsupported: true, clientMsgId: `missing-${status}` })
  }
})

test('cancelling a live turn is not a model failure and retains its partial text', async t => {
  let signal
  const started = deferred()
  t.mock.method(globalThis, 'fetch', async (_, options) => {
    signal = options.signal
    return new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(frame('delta', 'already spoken')))
        signal.addEventListener('abort', () => controller.error(signal.reason))
      }
    }))
  })
  const unwatch = watch(() => store.speaking, speaking => { if (speaking) started.resolve() }, { flush: 'sync' })
  const request = store.sendMessageStream(7, 1, 'hello', null, 'stop-1')
  await started.promise
  unwatch()
  store.stopStreaming()
  assert.deepEqual(await request, { stopped: true, clientMsgId: 'stop-1' })
  assert.equal(store.messages.at(-1).content, 'already spoken')
  assert.equal(store.messages.at(-1).stopped, true)
  assert.ok(!store.messages.at(-1).modelError)
  assert.equal(store.isSending, false)
})

test('opening and closing sessions block clear and release their busy state', async () => {
  const opening = deferred()
  api.openSession = () => opening.promise
  const open = store.openSession(7, 1, 'rain-fireplace')
  await assert.rejects(store.clearConversation(7, 1), error => error.response.status === 409)
  opening.resolve({ data: { id: 21, roleId: 1 } })
  await open
  assert.equal(store.isRoleBusy(1), false)
  const closing = deferred()
  api.closeSession = () => closing.promise
  const close = store.closeSession(7, 21)
  await assert.rejects(store.clearConversation(7, 1), error => error.response.status === 409)
  store.activeRoleId = 2
  store.activeSession = { id: 42, roleId: 2 }
  closing.resolve({ data: { id: 21, status: 'closed' } })
  await close
  assert.equal(store.activeSession.id, 42)
  assert.equal(store.isRoleBusy(1), false)
})

test('a stored half reply is shown as partial and its user line can be sent again', async t => {
  const asked = { id: 42, senderType: 'user', content: 'hello', clientMsgId: 'msg-1' }
  api.getConversation = async () => ({ data: [asked, { id: 43, senderType: 'role', content: 'half a', partial: true }] })
  await store.fetchConversation(7, 1)
  assert.equal(store.messages[0].partialId, 43)
  assert.equal(store.messages[1].partial, true)
  t.mock.method(globalThis, 'fetch', async () => new Response(
    frame('done', { userMessage: asked, roleMessage: { id: 44, senderType: 'role', content: 'finished' } }),
    { headers: { 'Content-Type': 'text/event-stream' } }))
  await store.sendMessageStream(7, 1, 'hello', null, 'msg-1')
  assert.deepEqual(store.messages.map(m => m.id), [42, 44])

  // Without the field nothing is marked, whatever the order of the rows.
  const plain = [asked, { id: 43, senderType: 'role', content: 'whole' }]
  api.getConversation = async () => ({ data: plain })
  await store.fetchConversation(7, 1)
  assert.deepEqual(store.messages, plain)
  api.getConversation = async () => ({ data: [{ id: 43, senderType: 'role', content: 'half a', partial: true }, asked] })
  await store.fetchConversation(7, 1)
  assert.equal(store.messages.some(m => 'partialId' in m), false)
})

test('a rate-limited line carries the wait the backend gave, and none otherwise', async t => {
  let error = limited
  t.mock.method(globalThis, 'fetch', async () => new Response(frame('error', { modelError: error })))
  await store.sendMessageStream(7, 1, 'a', null, 'wait')
  const waiting = store.messages.find(m => m.clientMsgId === 'wait').retryAt - Date.now()
  assert.ok(waiting > 34000 && waiting <= 35000)
  error = { code: 'RATE_LIMIT', summary: 'slow down' }
  await store.sendMessageStream(7, 1, 'b', null, 'free')
  assert.equal(store.messages.find(m => m.clientMsgId === 'free').retryAt, null)
})

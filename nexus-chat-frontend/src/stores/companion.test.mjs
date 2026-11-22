import { after, before, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'
import { watch } from 'vue'

let server, useCompanionStore, useUserStore, api, store, user
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
  ;({ useUserStore } = await server.ssrLoadModule('/src/stores/user.js'))
  ;({ companionAPI: api } = await server.ssrLoadModule('/src/services/api.js'))
})

after(async () => {
  await server?.close()
  globalThis.localStorage = originalStorage
})

beforeEach(() => {
  setActivePinia(createPinia())
  user = useUserStore()
  user.currentUser = { id: 7 }
  store = useCompanionStore()
  store.roles = [{ id: 1, lastMessageAt: '2026-11-20', lastMessage: 'old preview', presence: 'old state' }, { id: 2 }]
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
  assert.equal(store.roles[0].lastMessage, null)
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
  user = useUserStore()
  user.currentUser = { id: 7 }
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

const filed = (id, senderType, content, createdAt = '2026-11-21T19:08:00') => ({ id, roleId: 1, senderType, content, createdAt })
const reply = (id, text = 'new reply') => ({
  userMessage: filed(id, 'user', 'question'), roleMessage: filed(id + 1, 'role', text)
})

test('successful replies update preview and timestamp without a role-list refresh', async () => {
  api.sendMessage = async () => ({ data: reply(20) })
  await store.sendMessage(7, 1, 'question')
  assert.equal(store.roles[0].lastMessage, 'new reply')
  assert.equal(store.roles[0].lastMessageAt, '2026-11-21T19:08:00')
})

test('an older replay cannot rewind the preview or move an old reply to the end', async () => {
  api.sendMessage = async () => ({ data: reply(30, 'latest') })
  await store.sendMessage(7, 1, 'question')
  api.sendMessage = async () => ({ data: reply(20, 'earlier') })
  await store.sendMessage(7, 1, 'question')
  assert.equal(store.roles[0].lastMessage, 'latest')
  assert.equal(store.messages.at(-1).content, 'latest')
})

test('a no-text model failure previews its persisted user line, not an optimistic row', async () => {
  const asked = filed(30, 'user', 'persisted question')
  api.sendMessage = async () => ({ data: { userMessage: asked, modelError: limited } })
  api.getConversation = async () => ({ data: [asked] })
  await store.sendMessage(7, 1, 'persisted question')
  assert.equal(store.roles[0].lastMessage, 'persisted question')
  assert.equal(store.roles[0].lastMessageAt, asked.createdAt)
  assert.equal(store.messages.at(-1).failed, true)
})

test('network failure performs only a read-only reconciliation, never another POST', async () => {
  let posts = 0, reads = 0
  api.sendMessage = async () => { posts += 1; throw new Error('connection lost') }
  api.getConversation = async () => { reads += 1; return { data: [filed(30, 'user', 'server filed this')] } }
  assert.equal(await store.sendMessage(7, 1, 'server filed this'), null)
  assert.equal(posts, 1)
  assert.equal(reads, 1)
  assert.equal(store.roles[0].lastMessage, 'server filed this')
})

test('failed reconciliation keeps confirmed preview instead of inventing a browser timestamp', async () => {
  api.sendMessage = async () => { throw new Error('offline') }
  api.getConversation = async () => { throw new Error('still offline') }
  await store.sendMessage(7, 1, 'not confirmed')
  assert.equal(store.roles[0].lastMessage, 'old preview')
  assert.equal(store.roles[0].lastMessageAt, '2026-11-20')
})

test('a slow role-list read cannot replace a newer completed exchange', async () => {
  const reading = deferred()
  api.getRoles = () => reading.promise
  const pending = store.fetchRoles(7)
  api.sendMessage = async () => ({ data: reply(30, 'completed while reading') })
  await store.sendMessage(7, 1, 'question')
  reading.resolve({ data: [{ id: 1, lastMessage: 'obsolete' }] })
  await pending
  assert.equal(store.roles[0].lastMessage, 'completed while reading')
})

test('an A-B-A account switch rejects the original A response despite the same user ID', async () => {
  const reading = deferred()
  api.getConversation = () => reading.promise
  const pending = store.fetchConversation(7, 1)
  user.currentUser = { id: 8 }
  user.currentUser = { id: 7 }
  store.roles = [{ id: 1, lastMessage: 'current account view' }]
  store.activeRoleId = 1
  store.messages = [filed(90, 'user', 'current')]
  reading.resolve({ data: [filed(30, 'role', 'must not leak')] })
  await pending
  assert.equal(store.messages[0].id, 90)
  assert.equal(store.roles[0].lastMessage, 'current account view')
})

test('a late clear cannot hide a role or clear messages in the new account', async () => {
  const clearing = deferred()
  api.clearConversation = () => clearing.promise
  const pending = store.clearConversation(7, 1)
  user.currentUser = { id: 8 }
  store.roles = [{ id: 1, conversationHidden: false, lastMessage: 'another account' }]
  store.activeRoleId = 1
  store.messages = [filed(90, 'role', 'keep')]
  clearing.resolve({ data: {} })
  await assert.rejects(pending, error => error.code === 'STALE_USER_CONTEXT')
  assert.equal(store.roles[0].conversationHidden, false)
  assert.equal(store.messages[0].id, 90)
})

test('the old send finally cannot unlock a new-account send for the same role ID', async () => {
  const old = deferred(), current = deferred()
  let calls = 0
  api.sendMessage = () => (++calls === 1 ? old.promise : current.promise)
  const first = store.sendMessage(7, 1, 'old account')
  user.currentUser = { id: 8 }
  store.roles = [{ id: 1 }]
  store.activeRoleId = 1
  const second = store.sendMessage(8, 1, 'new account')
  old.resolve({ data: reply(30, 'old reply') })
  assert.equal(await first, null)
  assert.equal(store.isSending, true)
  assert.ok(!store.messages.some(message => message.content === 'old reply'))
  current.resolve({ data: reply(40, 'current reply') })
  await second
  assert.equal(store.isSending, false)
  assert.equal(store.roles[0].lastMessage, 'current reply')
})

test('an A-B-A character switch rejects the first A growth response', async () => {
  const reading = deferred()
  api.getGrowth = () => reading.promise
  const pending = store.fetchGrowth(7, 1)
  store.activeRoleId = 2
  store.activeRoleId = 1
  store.growth = { intimacy: 99 }
  reading.resolve({ data: { intimacy: 1 } })
  await pending
  assert.equal(store.growth.intimacy, 99)
})

test('settings shape updates preserve server preview and do not rewind a concurrent reply', async () => {
  const saving = deferred()
  api.updateRole = () => saving.promise
  const pending = store.updateRole(7, 1, { modelUrl: '/models/a.vrm', modelType: 'vrm' })
  api.sendMessage = async () => ({ data: reply(30, 'newest preview') })
  await store.sendMessage(7, 1, 'question')
  saving.resolve({ data: { id: 1, modelUrl: '/models/a.vrm', modelType: 'vrm', lastMessage: 'old preview' } })
  await pending
  assert.equal(store.roles[0].modelUrl, '/models/a.vrm')
  assert.equal(store.roles[0].lastMessage, 'newest preview')
})

test('delete and reopen clear both preview fields without restoring old history', async () => {
  await store.clearConversation(7, 1)
  api.getConversation = api.sessionHistory = async () => ({ data: [] })
  api.activeSession = async () => ({ status: 204 })
  await store.restoreConversation(7, 1)
  assert.equal(store.roles[0].lastMessage, null)
  assert.equal(store.roles[0].lastMessageAt, null)
  assert.equal(store.roles[0].conversationHidden, false)
  assert.deepEqual(store.messages, [])
})

test('stream completion updates only the originating role after switching away', async t => {
  const response = deferred()
  t.mock.method(globalThis, 'fetch', () => response.promise)
  const pending = store.sendMessageStream(7, 1, 'question')
  store.activeRoleId = 2
  store.messages = [{ id: 90, roleId: 2, content: 'other role' }]
  response.resolve(new Response(frame('done', reply(30, 'role one answer'))))
  await pending
  assert.equal(store.roles[0].lastMessage, 'role one answer')
  assert.equal(store.messages[0].content, 'other role')
  assert.equal(store.activeRoleId, 2)
})

test('a stopped stream previews persisted partial history, not merely delivered words', async t => {
  const started = deferred()
  let reads = 0
  api.getConversation = async () => {
    reads += 1
    return { data: [filed(30, 'user', 'question'), { ...filed(31, 'role', 'persisted partial'), partial: true }] }
  }
  t.mock.method(globalThis, 'fetch', async (_, options) => new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(frame('delta', 'delivered text')))
      options.signal.addEventListener('abort', () => controller.error(options.signal.reason))
    }
  })))
  const unwatch = watch(() => store.speaking, value => { if (value) started.resolve() }, { flush: 'sync' })
  const pending = store.sendMessageStream(7, 1, 'question')
  await started.promise
  unwatch()
  store.stopStreaming()
  await pending
  assert.equal(reads, 1)
  assert.equal(store.roles[0].lastMessage, 'persisted partial')
  assert.equal(store.messages.at(-1).content, 'delivered text')
})

test('late status, sessions, memories and credential reads cannot populate a new account', async () => {
  const reading = deferred()
  api.getStatus = api.activeSession = api.sessionHistory = api.getMemories = api.getGrowth = api.getCredentialStatus = () => reading.promise
  const requests = [store.fetchStatus(7, 1), store.fetchActiveSession(7, 1), store.fetchSessionHistory(7, 1),
    store.fetchMemories(7, 1), store.fetchGrowth(7, 1), store.fetchModelStatus(7)]
  user.currentUser = { id: 8 }
  store.roles = [{ id: 1 }]
  store.activeRoleId = 1
  reading.resolve({ data: [{ id: 99, content: 'old account' }] })
  await Promise.all(requests)
  assert.deepEqual(store.memories, [])
  assert.equal(store.growth, null)
  assert.equal(store.status, null)
  assert.equal(store.modelStatus, null)
  assert.equal(store.activeSession, null)
})

test('model list failure is not converted to a successful empty library', async () => {
  const failure = new Error('cannot read directory')
  api.inviteModels = async () => { throw failure }
  await assert.rejects(store.fetchInviteModels(7), failure)
  api.inviteModels = async () => ({ data: [] })
  assert.deepEqual(await store.fetchInviteModels(7), [])
})

test('an invitation uses its POST response directly and blocks duplicate clicks', async () => {
  const invitation = deferred()
  let calls = 0
  api.inviteGuest = () => { calls += 1; return invitation.promise }
  const pending = store.inviteGuest(7, 'rain-fireplace', '/models/a.vrm')
  await assert.rejects(store.inviteGuest(7, 'rain-fireplace'), error => error.response.status === 409)
  invitation.resolve({ data: { id: 99, kind: 'guest', name: 'new', modelUrl: '/models/a.vrm' } })
  const created = await pending
  assert.equal(created.id, 99)
  assert.equal(calls, 1)
  assert.equal(store.roles.at(-1).id, 99)
  assert.equal(store.isInviting, false)
})

test('a late invitation cannot put an old-account character in the new account', async () => {
  const invitation = deferred()
  api.inviteGuest = () => invitation.promise
  const pending = store.inviteGuest(7, 'rain-fireplace')
  user.currentUser = { id: 8 }
  store.roles = [{ id: 55, kind: 'regular' }]
  invitation.resolve({ data: { id: 99, kind: 'guest' } })
  await assert.rejects(pending, error => error.code === 'STALE_USER_CONTEXT')
  assert.deepEqual(store.roles.map(role => role.id), [55])
  assert.equal(store.isInviting, false)
})

test('renaming an asset updates cached references but leaves all conversation data alone', () => {
  store.roles = [
    { id: 1, modelUrl: '/models/a.vrm', lastMessage: 'keep preview', lastMessageAt: '2026-11-20' },
    { id: 2, modelUrl: 'a.vrm', conversationHidden: true },
    { id: 3, modelUrl: 'https://example.invalid/a.vrm' }
  ]
  store.syncRenamedModel(7, 'a.vrm', '/models/b.vrm')
  assert.equal(store.roles[0].modelUrl, '/models/b.vrm')
  assert.equal(store.roles[1].modelUrl, '/models/b.vrm')
  assert.equal(store.roles[1].conversationHidden, true)
  assert.equal(store.roles[0].lastMessage, 'keep preview')
  assert.equal(store.roles[0].lastMessageAt, '2026-11-20')
  assert.equal(store.roles[2].modelUrl, 'https://example.invalid/a.vrm')
})

test('a role-list read started before rename cannot reintroduce an obsolete URL', async () => {
  const reading = deferred()
  store.roles[0].modelUrl = '/models/a.vrm'
  api.getRoles = () => reading.promise
  const pending = store.fetchRoles(7)
  store.syncRenamedModel(7, 'a.vrm', '/models/b.vrm')
  reading.resolve({ data: [{ id: 1, modelUrl: '/models/a.vrm' }] })
  await pending
  assert.equal(store.roles[0].modelUrl, '/models/b.vrm')
  assert.equal(store.isLoading, false)
})

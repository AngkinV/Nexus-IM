import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createPinia, setActivePinia } from 'pinia'
import { isKnownExpiredJwt } from '../utils/sessionGuard.js'

// Explicit unsigned fixtures, never credentials; negative evidence only.
const jwt = exp => `e30.${Buffer.from(JSON.stringify({ exp })).toString('base64url')}.fixture`
const expired = jwt(1), future = jwt(4102444800)
const key = '__nexusSessionUnitBoundary'
const originalStorage = globalThis.localStorage, originalWindow = globalThis.window
after(() => {
    globalThis.localStorage = originalStorage; globalThis.window = originalWindow
    delete globalThis[key]
})
async function load(file, f, bindings = {}) {
    let source = await readFile(new URL(file, import.meta.url), 'utf8')
    source = source.replace(/^import (.*?) from '([^']+)'$/gm, (line, names, path) => {
        if (bindings[path]) return bindings[path]
        const url = path.startsWith('@/utils/') ? new URL(`../utils/${path.slice(8)}`, import.meta.url).href
            : path.startsWith('../utils/') ? new URL(path, new URL(file, import.meta.url)).href
            : ['vue', 'pinia', 'axios'].includes(path) ? import.meta.resolve(path) : null
        return url ? `import ${names} from '${url}'` : line
    })
    globalThis[key] = f
    return import(`data:text/javascript;base64,${Buffer.from(`const f = globalThis.${key};\n${source}`).toString('base64')}#${Math.random()}`)
}
async function setup() {
    setActivePinia(createPinia())
    const memory = new Map([['draft:1', 'draft'], ['outbox:1', 'pending'], ['settings.pending:1', 'pending']])
    globalThis.localStorage = { getItem: k => memory.get(k) ?? null, setItem: (k, v) => memory.set(k, String(v)), removeItem: k => memory.delete(k) }
    const f = { memory, clearCalls: 0, resetCalls: 0, cacheWrites: 0, next: future, owner: 1, navigations: 0, transports: 0 }
    f.authAPI = { login: async () => ({ data: { token: f.next, userId: f.owner, username: 'fixture' } }) }
    f.offlineStore = {
        clearAll: async () => { f.clearCalls++ },
        saveChats: async () => { f.cacheWrites++ }
    }
    f.settings = { switchUser: async () => {} }
    f.chat = { resetStore: () => { f.resetCalls++ }, invalidatePresence: () => {} }
    const module = await load('./user.js', f, {
        '@/services/api': 'const { authAPI } = f; const userAPI = {}; const resolveFileUrl = x => x',
        '@/stores/chat': 'const useChatStore = () => f.chat',
        '@/stores/settings': 'const useSettingsStore = () => f.settings',
        '@/services/offlineStore': 'const offlineStore = f.offlineStore'
    })
    f.user = module.useUserStore()
    f.persist = (token, id = 1) => { localStorage.setItem('token', token); localStorage.setItem('user', JSON.stringify({ id })) }
    globalThis.window = { location: { hash: '', pathname: '/main', replace: () => { f.navigations++ } } }
    return f
}
async function api(f) {
    return (await load('../services/api.js', f, {
        '@/stores/user': 'const useUserStore = () => f.user',
        './runtimeConfig': "const API_BASE_URL = '/api'; const SERVER_BASE_URL = 'https://fixture.invalid'"
    })).default
}
async function ws(f) {
    return (await load('../services/websocket.js', f, {
        '@stomp/stompjs': 'const Client = class { constructor(options) { f.wsOptions = options } activate() { f.transports++ } deactivate() { f.deactivations = (f.deactivations || 0) + 1 } }',
        'sockjs-client': 'const SockJS = class { constructor() { f.transports++ } }',
        '@/stores/user': 'const useUserStore = () => f.user',
        '@/stores/chat': 'const useChatStore = () => f.chat',
        '@/stores/contact': 'const useContactStore = () => ({ invalidatePresence() {} })',
        '@/stores/message': 'const useMessageStore = () => ({})',
        '@/stores/connection': 'const useConnectionStore = () => ({ markDisconnected() {}, markConnecting() {} })',
        '@/stores/settings': 'const useSettingsStore = () => f.settings',
        '@/utils/uiSound': 'const playMessageSound = () => {}; const playSendSound = () => {}; const playErrorSound = () => {}',
        '@/services/api': 'const resolveFileUrl = x => x',
        '@/services/runtimeConfig': "const WS_URL = 'https://fixture.invalid/ws'",
        '@/locales/i18n': 'const i18n = {}'
    })).default
}

test('expired startup executes real UserStore without deleting business data', async () => {
    const f = await setup(); f.persist(expired)
    f.user.loadUserFromStorage()
    assert.equal(f.user.isLoggedIn, false)
    assert.equal(f.user.currentUser, null); assert.equal(f.user.token, null)
    assert.equal(f.memory.has('user'), false); assert.equal(f.memory.has('token'), false)
    assert.equal(f.clearCalls, 0); assert.equal(f.resetCalls, 0)
    assert.equal(f.memory.size, 3)
})
test('future, opaque, missing exp and malformed tokens are not proof of authorization', async () => {
    const f = await setup()
    for (const value of [future, 'opaque-fixture', jwt(undefined), 'e30.invalid.fixture', jwt('1')]) {
        assert.equal(isKnownExpiredJwt(value), false)
        f.persist(value); f.user.loadUserFromStorage()
        // This is only legacy local state, not signature verification or backend authorization.
        assert.equal(f.user.isLoggedIn, true)
        assert.equal(f.user.currentUser.id, 1)
    }
    assert.equal(isKnownExpiredJwt(expired), true)
    assert.equal(isKnownExpiredJwt(jwt(10), 10000), true)
    const browserDecoder = globalThis.atob
    try {
        globalThis.atob = undefined
        assert.equal(isKnownExpiredJwt(expired), true)
        assert.equal(isKnownExpiredJwt(future), false)
    } finally { globalThis.atob = browserDecoder }
})
test('current expiry only deletes auth; late callback cannot clear A → B → A', async () => {
    const f = await setup(); f.persist(future); f.user.loadUserFromStorage()
    const old = f.user.captureSession()
    f.next = 'other-fixture'; f.owner = 2; await f.user.login('fixture', 'unit-only')
    f.next = future; f.owner = 1; await f.user.login('fixture', 'unit-only')
    assert.equal(f.user.expireSession(future, old), false)
    assert.equal(f.user.isLoggedIn, true)
    f.memory.set('token', 'different-storage-fixture')
    assert.equal(f.user.expireSession(future, f.user.captureSession()), false)
    f.memory.set('token', future)
    assert.equal(f.user.expireSession(future, f.user.captureSession()), true)
    assert.equal(f.user.isLoggedIn, false)
    assert.equal(f.memory.size, 3); assert.equal(f.clearCalls, 0); assert.equal(f.resetCalls, 0)
})
test('request expiry blocks adapter, but public login remains available', async () => {
    const f = await setup(); f.persist(expired)
    const client = await api(f); let calls = 0
    client.defaults.adapter = async config => { calls++; return { data: {}, status: 200, headers: {}, config } }
    await assert.rejects(client.get('/users/1'), error => error.code === 'ERR_CANCELED')
    assert.equal(calls, 0); assert.equal(f.navigations, 1)
    f.persist(expired)
    client.defaults.adapter = async config => {
        calls++; assert.equal(config.headers.get('Authorization'), undefined)
        return { data: {}, status: 200, headers: {}, config }
    }
    await client.post('/auth/login', { username: 'fixture' })
    assert.equal(calls, 1)
})
test('late HTTP 401, including A → B → A, and explicit 403 cannot revoke new login', async () => {
    const f = await setup(); f.persist(future); f.user.loadUserFromStorage()
    const client = await api(f); let sent, reject
    client.defaults.adapter = config => { sent = config; return new Promise((_, r) => { reject = r }) }
    const pending = client.get('/users/1'); const caught = pending.catch(() => {})
    // Drain interceptor microtasks only; no HTTP or timer/service execution.
    while (!sent) await Promise.resolve()
    f.next = 'other-fixture'; await f.user.login('fixture', 'unit-only')
    f.next = future; await f.user.login('fixture', 'unit-only')
    reject({ config: sent, response: { status: 401 } }); await caught
    assert.equal(f.user.isLoggedIn, true); assert.equal(f.navigations, 0)
    client.defaults.adapter = config => Promise.reject({ config, response: { status: 403 } })
    await assert.rejects(client.get('/users/1'))
    assert.equal(f.user.isLoggedIn, true)
    client.defaults.adapter = config => Promise.reject({ config, response: { status: 401 } })
    await assert.rejects(client.get('/users/1'))
    assert.equal(f.user.isLoggedIn, false); assert.equal(f.navigations, 1)
})
test('expiry invalidates real contact/chat identity revisions without touching caches', async () => {
    const f = await setup(); f.persist(future); f.user.loadUserFromStorage()
    const bindings = {
        '@/stores/user': 'const useUserStore = () => f.user',
        '@/stores/chat': 'const useChatStore = () => f.chat',
        '@/stores/contact': 'const useContactStore = () => f.contact',
        '@/services/api': 'const contactAPI = {}; const userAPI = {}; const chatAPI = {}; const resolveFileUrl = x => x',
        '@/services/offlineStore': 'const offlineStore = f.offlineStore',
        '@/stores/agent': "const AI_ASSISTANT_CHAT_ID = 'ai'; const buildAiAssistantChatItem = () => ({ id: 'ai' })",
        '@/locales/i18n': "const i18n = { global: { t: x => x, locale: { value: 'en' } } }"
    }
    f.contact = (await load('./contact.js', f, bindings)).useContactStore()
    f.chat = (await load('./chat.js', f, bindings)).useChatStore()
    f.contact.addContact({ id: 7, nickname: 'fixture', isOnline: true })
    f.chat.mergeChats([{ id: 10, type: 'group', members: [{ id: 7, isOnline: true }] }], 1)
    const revision = f.contact.captureIdentityRevision()
    const cacheWritesBeforeExpiry = f.cacheWrites
    assert.equal(f.user.expireSession(future, f.user.captureSession()), true)
    assert.equal(f.cacheWrites, cacheWritesBeforeExpiry, 'expiry does not rewrite business caches')
    assert.equal(f.contact.contacts.length, 0); assert.equal(f.contact.onlineUsers.size, 0)
    assert.equal(f.contact.getUserIdentity(7), null); assert.equal(f.chat.chats.length, 0)
    assert.equal(f.contact.isIdentityRevisionCurrent(revision), false)
    assert.equal(f.contact.ingestUserSnapshots([{ id: 7, isOnline: true }], revision), false)
    assert.equal(f.memory.size, 3); assert.equal(f.clearCalls, 0)
})
test('old public login failure cannot clear a successful newer login', async () => {
    const f = await setup(); f.persist(expired)
    const client = await api(f); let sent, reject
    client.defaults.adapter = config => { sent = config; return new Promise((_, r) => { reject = r }) }
    const pending = client.post('/auth/login', { username: 'fixture' }).catch(() => {})
    while (!sent) await Promise.resolve()
    await f.user.login('fixture', 'unit-only')
    reject({ config: sent, response: { status: 401 } }); await pending
    assert.equal(f.user.isLoggedIn, true); assert.equal(f.navigations, 0)
})

test('known-expired WS connect/reconnect never activates transport', async () => {
    const f = await setup(); f.persist(expired)
    // Execute real load first with a future fixture, then expire that same session.
    f.persist(future); f.user.loadUserFromStorage()
    f.user.token = expired; f.persist(expired)
    const service = await ws(f)
    service.connect(1)
    assert.equal(f.transports, 0); assert.equal(service.connected, false)
    assert.equal(f.user.isLoggedIn, false); assert.equal(service.reconnectTimer, null)
    f.user.token = expired; f.user.currentUser = { id: 1 }; f.persist(expired)
    service.attemptReconnect(1)
    assert.equal(f.transports, 0); assert.equal(service.reconnectTimer, null)
    assert.equal(f.clearCalls, 0)
})

test('a factor challenge leaves the existing account untouched and a completed factor reaches the real API', async () => {
    const f = await setup(); f.persist(future); f.user.loadUserFromStorage()
    const before = new Map(f.memory)
    f.authAPI.login = async () => { throw { response: { data: { message: 'error.auth.2fa.required' } } } }
    await assert.rejects(f.user.login('fixture', 'unit-only'), message => message === 'error.auth.2fa.required')
    assert.equal(f.user.token, future); assert.equal(f.user.currentUser.id, 1)
    assert.deepEqual(f.memory, before)
    let args
    f.authAPI.login = async (...values) => { args = values; return { data: { token: 'factor-complete-fixture', userId: 1, username: 'fixture' } } }
    await f.user.login('fixture', 'unit-only', '123456')
    assert.deepEqual(args, ['fixture', 'unit-only', '123456'])
    assert.equal(f.memory.get('token'), 'factor-complete-fixture')
})

test('a server disconnect expires this session immediately without reconnecting or clearing drafts', async () => {
    const f = await setup(); f.persist(future); f.user.loadUserFromStorage()
    const service = await ws(f); service.connect(1)
    assert.equal(f.transports, 2)
    f.wsOptions.onWebSocketClose({ code: 4001, reason: 'new_login' })
    assert.equal(f.user.isLoggedIn, false)
    assert.equal(service.client, null); assert.equal(service.reconnectTimer, null)
    assert.equal(f.navigations, 1)
    assert.equal(f.memory.size, 3); assert.equal(f.clearCalls, 0); assert.equal(f.resetCalls, 0)
})

test('an old socket close does not expire a newer token for the same account', async () => {
    const f = await setup(); f.persist(future); f.user.loadUserFromStorage()
    const service = await ws(f); service.connect(1)
    const old = f.wsOptions
    f.next = 'newer-session-fixture'; await f.user.login('fixture', 'unit-only')
    old.onWebSocketClose({ code: 4001, reason: 'password_changed' })
    assert.equal(f.user.token, 'newer-session-fixture')
    assert.equal(f.user.isLoggedIn, true); assert.equal(f.navigations, 0)
    service.disconnect()
})

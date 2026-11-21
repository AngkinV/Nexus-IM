import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import {
  useSettingsStore,
  sanitizeSettings,
  to12Hour,
  DEFAULT_SETTINGS,
  SETTINGS_TIMING
} from './settings.js'
import { __setKeyStoreForTests, encryptValue, LEGACY_KEY_ID } from '../utils/secureStorage.js'

/* ── harness: in-memory localStorage + key store, short timers, a fake backend ── */

Object.assign(SETTINGS_TIMING, { localWriteMs: 5, accountPushMs: 15, retryMs: [40, 40] })
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const settle = () => wait(60)

let disk
function memoryKeyStore() {
  const keys = new Map()
  return {
    keys,
    failWrites: false,
    async get(id) { return keys.get(id) ?? null },
    async getOrPut(id, candidate) {
      if (this.failWrites) throw new Error('key store is read-only')
      if (!keys.has(id)) keys.set(id, candidate)
      return keys.get(id)
    },
    async delete(id) { keys.delete(id) }
  }
}
let keyStore

test.beforeEach(() => {
  disk = new Map()
  globalThis.localStorage = {
    getItem: (k) => (disk.has(k) ? disk.get(k) : null),
    setItem: (k, v) => { disk.set(k, String(v)) },
    removeItem: (k) => { disk.delete(k) }
  }
  keyStore = memoryKeyStore()
  __setKeyStoreForTests(keyStore)
})

/** A backend that stores per-user preferences with merge semantics, like the real one. */
function fakeBackend() {
  const rows = new Map()
  const backend = {
    rows,
    calls: [],
    failing: false,
    hold: null, // set to a promise to keep requests in flight
    async get(id) {
      backend.calls.push({ op: 'get', id })
      if (backend.failing) throw new Error('network down')
      return { data: { hourCycle: '24', glassIntensity: 100, soundEnabled: true, ...rows.get(id) } }
    },
    async update(id, patch) {
      backend.calls.push({ op: 'update', id, patch: { ...patch } })
      if (backend.hold) await backend.hold
      if (backend.failing) throw new Error('network down')
      rows.set(id, { ...rows.get(id), ...patch })
      return { data: { hourCycle: '24', glassIntensity: 100, soundEnabled: true, ...rows.get(id) } }
    },
    updates: () => backend.calls.filter((c) => c.op === 'update')
  }
  return backend
}

const live = []
function newStore(backend) {
  setActivePinia(createPinia())
  const store = useSettingsStore()
  if (backend) store.bindAccountApi(backend)
  live.push(store)
  return store
}
// Sign every store out when its test ends: that cancels its timers, so a store
// from one test cannot write into the next test's storage.
test.afterEach(async () => {
  for (const store of live.splice(0)) {
    store.bindAccountApi(null)
    await store.switchUser(null)
  }
})
const bucket = (id) => JSON.parse(disk.get(`nexus.settings.${id}`) ?? 'null')

/* ── pure helpers ── */

test('to12Hour converts time tokens and leaves dates alone', () => {
  assert.equal(to12Hour('HH:mm'), 'h:mm A')
  assert.equal(to12Hour('HH:mm:ss'), 'h:mm:ss A')
  assert.equal(to12Hour('MM-DD HH:mm'), 'MM-DD h:mm A')
  assert.equal(to12Hour('YYYY-MM-DD'), 'YYYY-MM-DD')
})

test('sanitizeSettings turns anything into valid settings', () => {
  const s = sanitizeSettings({
    zoomPercent: 9999, glassIntensity: -5, fpsCap: 144, renderScale: '8k', hourCycle: 13,
    soundEnabled: 'yes', encryptedStorage: 'true', petVisible: 1, petMotion: 42, unknown: 'dropped'
  })
  assert.equal(s.zoomPercent, 200)
  assert.equal(s.glassIntensity, 0)
  assert.equal(s.fpsCap, 60)
  assert.equal(s.renderScale, 'native')
  assert.equal(s.hourCycle, '24')
  assert.equal(s.encryptedStorage, false, 'only a real true enables encryption')
  assert.equal(s.petVisible, false)
  assert.equal(s.petMotion, '')
  assert.equal('unknown' in s, false)
  assert.deepEqual(sanitizeSettings(null), { ...DEFAULT_SETTINGS })
  assert.equal(sanitizeSettings({ glassIntensity: null }).glassIntensity, 100, 'null is "missing", not 0')
  assert.equal(sanitizeSettings({ zoomPercent: 'abc' }).zoomPercent, 100)
})

/* ── accounts ── */

test('switching accounts without a restart loads the other account, plain blobs included', async () => {
  disk.set('nexus.settings.1', JSON.stringify({ zoomPercent: 140, fpsCap: 30, notificationsEnabled: false }))
  disk.set('nexus.settings.2', JSON.stringify({ zoomPercent: 80, fpsCap: 60 }))
  const store = newStore()

  await store.switchUser(1)
  assert.deepEqual([store.zoomPercent, store.fpsCap, store.notificationsEnabled], [140, 30, false])

  await store.switchUser(2)
  assert.deepEqual([store.zoomPercent, store.fpsCap, store.notificationsEnabled], [80, 60, true])
})

test('an account with nothing stored gets defaults, not the previous account\'s values', async () => {
  disk.set('nexus.settings.1', JSON.stringify({ zoomPercent: 140, fpsCap: 30, petVisible: true }))
  const store = newStore()
  await store.switchUser(1)
  await store.switchUser(2)
  assert.deepEqual([store.zoomPercent, store.fpsCap, store.petVisible], [100, 60, false])
  await settle()
  assert.equal(disk.has('nexus.settings.2'), false, 'nothing is written for an account that changed nothing')
})

test('a change made just before switching lands in the right bucket', async () => {
  disk.set('nexus.settings.2', JSON.stringify({ zoomPercent: 80 }))
  const store = newStore()
  await store.switchUser(1)
  store.zoomPercent = 150 // its write is still waiting on the coalescing timer
  await store.switchUser(2)
  await settle()
  assert.equal(bucket(1).zoomPercent, 150, 'the outgoing account keeps its last change')
  assert.equal(bucket(2).zoomPercent, 80, 'the incoming account is not touched by it')
  assert.equal(store.zoomPercent, 80)
})

test('signed out: defaults, and nothing is persisted', async () => {
  disk.set('nexus.settings.1', JSON.stringify({ zoomPercent: 140 }))
  const store = newStore()
  await store.switchUser(1)
  await store.switchUser(null)
  assert.equal(store.zoomPercent, 100)
  store.zoomPercent = 170
  await settle()
  assert.deepEqual([...disk.keys()], ['nexus.settings.1'])
  assert.equal(bucket(1).zoomPercent, 140)
})

test('the old machine-wide blob is not handed to whoever signs in first', async () => {
  disk.set('nexus.settings', JSON.stringify({ zoomPercent: 175, hourCycle: '12' }))
  const store = newStore()
  await store.switchUser(7)
  assert.equal(store.zoomPercent, 100)
  assert.equal(store.hourCycle, '24')
  assert.equal(disk.has('nexus.settings'), true, 'and it is left alone, not deleted')
})

test('unowned legacy pet visibility is not inherited by new accounts', async () => {
  disk.set('companion.petVisible', 'true')
  const store = newStore()
  await store.switchUser(1)
  assert.equal(store.petVisible, false)
  await store.switchUser(2)
  assert.equal(store.petVisible, false)
  disk.set('nexus.settings.3', JSON.stringify({ petVisible: true }))
  await store.switchUser(3)
  assert.equal(store.petVisible, true, 'known per-account values are preserved')
})

/* ── backend sync ── */

test('three quick edits go out as one request carrying all three fields', async () => {
  const backend = fakeBackend()
  const store = newStore(backend)
  await store.switchUser(1)
  await settle()

  store.hourCycle = '12'
  store.glassIntensity = 40
  store.soundEnabled = false
  assert.equal(store.pendingSync, 3)
  await settle()

  assert.deepEqual(backend.updates().map((c) => c.patch), [{ hourCycle: '12', glassIntensity: 40, soundEnabled: false }])
  assert.deepEqual(backend.rows.get(1), { hourCycle: '12', glassIntensity: 40, soundEnabled: false })
  assert.equal(store.pendingSync, 0)
  assert.equal(store.syncStatus, 'synced')
})

test('device-only settings never reach the backend', async () => {
  const backend = fakeBackend()
  const store = newStore(backend)
  await store.switchUser(1)
  store.zoomPercent = 130
  store.fpsCap = 30
  store.renderScale = '720p'
  store.petVisible = true
  await settle()
  assert.equal(backend.updates().length, 0)
  assert.equal(bucket(1).zoomPercent, 130)
})

test('reset sends every changed account field together and keeps encryption', async () => {
  const backend = fakeBackend()
  backend.rows.set(1, { hourCycle: '12', glassIntensity: 30, soundEnabled: false })
  const store = newStore(backend)
  await store.switchUser(1)
  await settle()
  assert.equal(store.hourCycle, '12')
  store.petVisible = true
  await store.setEncryption(true)

  const result = store.resetToDefaults()
  await settle()

  assert.deepEqual(result, { encryptionPreserved: true })
  assert.deepEqual(backend.updates().at(-1).patch, { hourCycle: '24', glassIntensity: 100, soundEnabled: true })
  assert.equal(store.petVisible, false, 'reset covers the pet switch too')
  assert.equal(store.encryptedStorage, true)
  assert.equal(bucket(1).__encrypted, true)
})

test('an edit made while a request is in flight is not lost', async () => {
  const backend = fakeBackend()
  const store = newStore(backend)
  await store.switchUser(1)
  await settle()

  let release
  backend.hold = new Promise((resolve) => { release = resolve })
  store.glassIntensity = 40
  await wait(30) // first request is now in flight with 40
  store.glassIntensity = 70
  backend.hold = null
  release()
  await settle()

  assert.deepEqual(backend.updates().map((c) => c.patch.glassIntensity), [40, 70])
  assert.equal(backend.rows.get(1).glassIntensity, 70)
  assert.equal(store.pendingSync, 0)
})

test('a failed save is reported, kept on disk, retried, and survives a restart', async () => {
  const backend = fakeBackend()
  let store = newStore(backend)
  await store.switchUser(1)
  await settle()

  backend.failing = true
  store.hourCycle = '12'
  await settle()
  assert.equal(store.syncStatus, 'failed')
  assert.equal(store.pendingSync, 1)
  assert.deepEqual(bucket(1).__pendingSync, ['hourCycle'], 'the unsent change is remembered on disk')

  // Restart while still offline, with the server holding older values.
  backend.rows.set(1, { hourCycle: '24', glassIntensity: 55 })
  store = newStore(backend)
  await store.switchUser(1)
  await settle()
  assert.equal(store.hourCycle, '12', 'local values still work while offline')
  assert.equal(store.syncStatus, 'failed')

  backend.failing = false
  await wait(120) // the scheduled retry runs: pull, then push what was pending
  assert.equal(store.hourCycle, '12', 'the pending local change wins over the stale server value')
  assert.equal(store.glassIntensity, 55, 'fields without a pending change take the server value')
  assert.equal(backend.rows.get(1).hourCycle, '12')
  assert.equal(store.pendingSync, 0)
  assert.equal(store.syncStatus, 'synced')
  await settle()
  assert.deepEqual(bucket(1).__pendingSync, [])
})

test('a client error is not retried forever', async () => {
  const backend = fakeBackend()
  const store = newStore(backend)
  await store.switchUser(1)
  await settle()
  backend.update = async (id, patch) => {
    backend.calls.push({ op: 'update', id, patch })
    throw Object.assign(new Error('bad request'), { response: { status: 400 } })
  }
  store.hourCycle = '12'
  await wait(200)
  assert.equal(backend.updates().length, 1)
  assert.equal(store.syncStatus, 'failed')
  assert.equal(store.pendingSync, 1)
})

test('a response for the previous account is not applied to the new one', async () => {
  const backend = fakeBackend()
  backend.rows.set(1, { hourCycle: '12', glassIntensity: 20 })
  const store = newStore(backend)

  let release
  const realGet = backend.get
  backend.get = async (id) => {
    if (id === 1) await new Promise((resolve) => { release = resolve })
    return realGet(id)
  }
  await store.switchUser(1) // user 1's pull is now stuck in flight
  await store.switchUser(2)
  await settle()
  release()
  await settle()

  assert.equal(store.hourCycle, '24')
  assert.equal(store.glassIntensity, 100)
  assert.equal(disk.has('nexus.settings.2'), false)
})

test('a request for the previous account never carries the new account\'s values', async () => {
  const backend = fakeBackend()
  const store = newStore(backend)
  await store.switchUser(1)
  await settle()
  store.hourCycle = '12' // push is scheduled but has not fired
  await store.switchUser(2)
  await settle()
  assert.equal(backend.updates().length, 0, 'the scheduled push was cancelled with the account')
  assert.deepEqual(bucket(1).__pendingSync, ['hourCycle'], 'and is kept for account 1\'s next sign-in')

  await store.switchUser(1)
  await settle()
  assert.deepEqual(backend.updates().map((c) => [c.id, c.patch]), [[1, { hourCycle: '12' }]])
})

test('signing out flushes pending account changes first', async () => {
  const backend = fakeBackend()
  const store = newStore(backend)
  await store.switchUser(1)
  await settle()
  store.soundEnabled = false
  await store.flushAccount()
  assert.deepEqual(backend.rows.get(1), { soundEnabled: false })
  assert.equal(store.pendingSync, 0)
})

/* ── encryption ── */

test('encryption round trip: ciphertext on disk, values back after a restart', async () => {
  let store = newStore()
  await store.switchUser(1)
  store.zoomPercent = 135
  store.hourCycle = '12'
  await store.setEncryption(true)

  const raw = disk.get('nexus.settings.1')
  assert.equal(JSON.parse(raw).__encrypted, true)
  assert.equal(/zoomPercent|hourCycle|135/.test(raw), false, 'no plaintext settings in the blob')
  assert.equal(store.encryptedStorage, true)

  store.zoomPercent = 160 // later changes are re-encrypted, not written in the clear
  await store.flushLocal()
  assert.equal(JSON.parse(disk.get('nexus.settings.1')).__encrypted, true)

  store = newStore()
  await store.switchUser(1)
  assert.deepEqual([store.zoomPercent, store.hourCycle, store.encryptedStorage], [160, '12', true])

  await store.setEncryption(false)
  assert.equal(bucket(1).zoomPercent, 160)
  assert.equal(bucket(1).__encrypted, undefined)
  assert.equal(store.encryptedStorage, false)
})

test('one account cannot read another account\'s encrypted settings', async () => {
  const store = newStore()
  await store.switchUser(1)
  store.zoomPercent = 135
  await store.setEncryption(true)
  const stolen = disk.get('nexus.settings.1')

  disk.set('nexus.settings.2', stolen)
  await store.switchUser(2)
  assert.equal(store.storageIssue, 'unreadable')
  assert.equal(store.zoomPercent, 100, 'nothing of account 1 leaks into account 2')
})

test('an unreadable blob is never overwritten until the user discards it', async () => {
  const store = newStore()
  await store.switchUser(1)
  await store.setEncryption(true)
  const envelope = disk.get('nexus.settings.1')
  keyStore.keys.clear() // the key is gone (browser data cleared, profile copied, …)
  __setKeyStoreForTests(keyStore)

  const fresh = newStore()
  await fresh.switchUser(1)
  assert.equal(fresh.storageIssue, 'unreadable')
  fresh.zoomPercent = 150
  await settle()
  assert.equal(disk.get('nexus.settings.1'), envelope, 'the ciphertext is untouched')
  await assert.rejects(() => fresh.setEncryption(false))
  assert.equal(disk.get('nexus.settings.1'), envelope)

  fresh.discardUnreadableStorage()
  assert.equal(fresh.storageIssue, null)
  assert.equal(fresh.encryptedStorage, false)
  await settle()
  assert.equal(bucket(1).zoomPercent, 150, 'after discarding, settings save normally again')
})

test('old shared-key ciphertext is preserved, not silently assigned to an account', async () => {
  const sharedKey = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  keyStore.keys.set(LEGACY_KEY_ID, sharedKey)
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const cipher = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, sharedKey,
    new TextEncoder().encode(JSON.stringify({ zoomPercent: 125, encryptedStorage: true })))
  const base64 = (bytes) => Buffer.from(bytes).toString('base64')
  const raw = JSON.stringify({ __encrypted: true, iv: base64(iv), data: base64(cipher) })
  disk.set('nexus.settings.1', raw)
  const store = newStore()
  await store.switchUser(1)
  assert.equal(store.storageIssue, 'unreadable')
  assert.equal(store.zoomPercent, 100)
  assert.equal(disk.get('nexus.settings.1'), raw)
})

 test('legacy per-account encryption upgrades without trying shared keys', async () => {
  const ownKey = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  keyStore.keys.set('local-settings.1', ownKey)
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const cipher = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, ownKey,
    new TextEncoder().encode(JSON.stringify({ zoomPercent: 125, encryptedStorage: true })))
  const base64 = (bytes) => Buffer.from(bytes).toString('base64')
  disk.set('nexus.settings.1', JSON.stringify({ __encrypted: true, iv: base64(iv), data: base64(cipher) }))
  const store = newStore()
  await store.switchUser(1)
  assert.equal(store.storageIssue, null)
  assert.equal(store.zoomPercent, 125)
  store.zoomPercent = 126
  await store.flushLocal()
  assert.equal(bucket(1).version, 2)
  const again = newStore()
  await again.switchUser(1)
  assert.equal(again.zoomPercent, 126)
})

test('if encryption cannot be switched on, the plain blob stays and the switch stays off', async () => {
  const store = newStore()
  await store.switchUser(1)
  store.zoomPercent = 140
  await store.flushLocal()
  keyStore.failWrites = true

  const realError = console.error
  console.error = () => {}
  try {
    await assert.rejects(() => store.setEncryption(true))
  } finally {
    console.error = realError
  }
  assert.equal(store.encryptedStorage, false)
  assert.equal(bucket(1).zoomPercent, 140)
  assert.equal(bucket(1).__encrypted, undefined)

  keyStore.failWrites = false
  await store.setEncryption(true) // and it works once the cause is gone
  assert.equal(bucket(1).__encrypted, true)
})

test('unparseable data is set aside, not destroyed, and the app starts clean', async () => {
  disk.set('nexus.settings.1', '{"zoomPercent": 14')
  const store = newStore()
  await store.switchUser(1)
  assert.equal(store.storageIssue, 'corrupt')
  assert.equal(store.zoomPercent, 100)
  assert.equal(disk.get('nexus.settings.1.corrupt'), '{"zoomPercent": 14')
  store.zoomPercent = 110
  await settle()
  assert.equal(bucket(1).zoomPercent, 110)
})

test('out-of-range values on disk are clamped on load', async () => {
  disk.set('nexus.settings.1', JSON.stringify({ zoomPercent: 5000, glassIntensity: 'x', fpsCap: 0 }))
  const store = newStore()
  await store.switchUser(1)
  assert.deepEqual([store.zoomPercent, store.glassIntensity, store.fpsCap], [200, 100, 60])
})

test('format honours the hour cycle and rejects bad input', async () => {
  const store = newStore()
  await store.switchUser(1)
  const at = new Date(2026, 10, 21, 15, 4, 0)
  assert.equal(store.format(at), '15:04')
  store.hourCycle = '12'
  assert.equal(store.format(at), '3:04 PM')
  assert.equal(store.format(at, 'MM-DD HH:mm'), '11-21 3:04 PM')
  assert.equal(store.format(null), '')
  assert.equal(store.format('not a date'), '')
})

/* Precise concurrency / response-contract regressions, not just happy paths. */
const deferred = () => {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}
const validData = (patch = {}) => ({ hourCycle: '24', glassIntensity: 100, soundEnabled: true, ...patch })

test('a late GET cannot overwrite an edit whose PUT already succeeded', async () => {
  const backend = fakeBackend()
  const delayed = deferred()
  backend.get = async () => { await delayed.promise; return { data: validData() } }
  const store = newStore(backend)
  await store.switchUser(1)
  store.hourCycle = '12'
  await settle()
  assert.equal(store.pendingSync, 0, 'the PUT was confirmed')
  assert.notEqual(store.syncStatus, 'synced', 'the full account has not been loaded yet')
  delayed.resolve()
  await settle()
  assert.equal(store.hourCycle, '12')
  assert.equal(store.syncStatus, 'synced')
})

test('two GETs returning backwards apply only the newest result', async () => {
  const backend = fakeBackend()
  const store = newStore(backend)
  await store.switchUser(1)
  await settle()
  const first = deferred(), second = deferred()
  let count = 0
  backend.get = async () => (++count === 1 ? first.promise : second.promise)
  const older = store.syncFromAccount()
  const newer = store.syncFromAccount()
  second.resolve({ data: validData({ hourCycle: '12', glassIntensity: 35 }) })
  await newer
  first.resolve({ data: validData() })
  await older
  assert.equal(store.hourCycle, '12')
  assert.equal(store.glassIntensity, 35)
})

test('empty or malformed GET data is not reported as synced', async () => {
  for (const data of [null, {}, { hourCycle: '12' }, validData({ glassIntensity: 100.5 }), validData({ soundEnabled: 'yes' })]) {
    const backend = fakeBackend()
    backend.get = async () => ({ data })
    const store = newStore(backend)
    await store.switchUser(1)
    await wait(10)
    assert.equal(store.syncStatus, 'failed')
    assert.equal(store.hourCycle, '24')
    await store.switchUser(null)
  }
})

test('a PUT without a valid or matching acknowledgement keeps changes pending', async () => {
  for (const data of [null, {}, validData()]) {
    disk.delete('nexus.settings.1') // each malformed-response case starts with a clean account
    const backend = fakeBackend()
    const store = newStore(backend)
    await store.switchUser(1)
    await settle()
    backend.update = async () => ({ data })
    store.hourCycle = '12'
    await settle()
    assert.equal(store.pendingSync, 1)
    assert.equal(store.syncStatus, 'failed')
    assert.deepEqual(bucket(1).__pendingSync, ['hourCycle'])
    await store.switchUser(null)
  }
})

test('an old account hanging PUT does not block the new account or change its status', async () => {
  const backend = fakeBackend()
  const held = deferred()
  const update = backend.update
  backend.update = async (id, patch) => { if (id === 1) await held.promise; return update(id, patch) }
  const store = newStore(backend)
  await store.switchUser(1)
  await settle()
  store.hourCycle = '12'
  await wait(30)
  await store.switchUser(2)
  await settle()
  store.glassIntensity = 40
  await settle()
  assert.equal(backend.rows.get(2).glassIntensity, 40)
  assert.equal(store.pendingSync, 0)
  held.resolve()
  await settle()
  assert.equal(store.glassIntensity, 40)
  assert.equal(store.hourCycle, '24')
  assert.equal(store.syncStatus, 'synced')
})

test('flushAccount waits for a PUT that was already in flight', async () => {
  const backend = fakeBackend()
  const held = deferred()
  const update = backend.update
  backend.update = async (id, patch) => { await held.promise; return update(id, patch) }
  const store = newStore(backend)
  await store.switchUser(1)
  await settle()
  store.hourCycle = '12'
  await wait(30)
  let finished = false
  const flush = store.flushAccount(500).then(() => { finished = true })
  await wait(20)
  assert.equal(finished, false)
  held.resolve()
  await flush
  assert.equal(store.pendingSync, 0)
})

test('encryption and account switch share a barrier; edits cannot enter during the handoff', async () => {
  const store = newStore()
  await store.switchUser(1)
  store.zoomPercent = 130
  await store.flushLocal()
  const held = deferred()
  const getOrPut = keyStore.getOrPut.bind(keyStore)
  keyStore.getOrPut = async (id, key) => { await held.promise; return getOrPut(id, key) }
  const encrypt = store.setEncryption(true).catch((error) => error)
  await wait(10)
  assert.equal(store.busy, true)
  store.zoomPercent = 199
  assert.equal(store.zoomPercent, 130)
  const switchAccount = store.switchUser(2)
  held.resolve()
  await encrypt
  await switchAccount
  assert.equal(store.zoomPercent, 100)
  assert.equal(store.encryptedStorage, false)
  assert.equal(bucket(1).__encrypted, true)
  assert.equal(bucket(1).owner, '1')
  assert.equal(disk.has('nexus.settings.2'), false)
})

test('encryption readback failure restores the exact previous bytes', async () => {
  const store = newStore()
  await store.switchUser(1)
  store.zoomPercent = 130
  await store.flushLocal()
  const before = disk.get('nexus.settings.1')
  const set = localStorage.setItem
  let tamperOnce = true
  localStorage.setItem = (key, raw) => {
    const value = JSON.parse(raw)
    if (tamperOnce && value.__encrypted) {
      tamperOnce = false
      raw = JSON.stringify({ ...value, data: 'broken' })
    }
    set(key, raw)
  }
  await assert.rejects(() => store.setEncryption(true))
  assert.equal(disk.get('nexus.settings.1'), before)
  assert.equal(store.encryptedStorage, false)
})

test('a storage quota failure is surfaced, not reported as a successful local save', async () => {
  const store = newStore()
  await store.switchUser(1)
  localStorage.setItem = () => { throw new Error('quota exceeded') }
  store.zoomPercent = 140
  await settle()
  assert.equal(store.localSaveFailed, true)
  await assert.rejects(() => store.flushLocal())
  assert.equal(store.zoomPercent, 140, 'current session still applies the user choice')
})

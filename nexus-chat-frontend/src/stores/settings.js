import { ref, computed, watch, onScopeDispose } from 'vue'
import { defineStore } from 'pinia'
import dayjs from 'dayjs'
import { configureRenderGovernor } from '../utils/renderGovernor.js'
import {
  encryptValue,
  decryptValue,
  isEnvelope,
  isEncryptionSupported
} from '../utils/secureStorage.js'

/**
 * The signed-in user's settings.
 *
 * Every control on the settings screen is backed by a value here, and every
 * value here is read by the feature that owns it. Three rules hold throughout:
 *
 * 1. Settings belong to an account. The in-memory values, the localStorage
 *    bucket, the encryption key and any request in flight are all tied to one
 *    explicit user id (`ownerId`). Nothing is derived from "whoever happens to
 *    be signed in when the timer fires" — that is how one account's values end
 *    up written into another's bucket. A signed-out session has defaults and
 *    persists nothing.
 * 2. Three settings follow the account across machines (hour cycle, glass,
 *    sounds) and are stored by the backend. The rest describe this machine and
 *    stay local. A local change the server has not confirmed is remembered as
 *    pending — on disk, so it survives a restart — and retried; on sign-in the
 *    server wins for every field that has no pending local change.
 * 3. The on-disk format is the truth about encryption: an envelope means on,
 *    plain JSON means off. A blob that cannot be decrypted is never overwritten
 *    behind the user's back.
 */

const STORAGE_PREFIX = 'nexus.settings'
const bucketKey = (userId) => `${STORAGE_PREFIX}.${userId}`

export const DEFAULT_SETTINGS = Object.freeze({
  notificationsEnabled: true,
  locale: 'zh', // UI language belongs to this account on this device
  hourCycle: '24', // '24' | '12'
  glassIntensity: 100, // 0-100, drives --glass-blur-scale
  badgeEnabled: true, // dock / taskbar unread badge
  zoomPercent: 100, // 50-200, whole-window zoom
  suspendOnBlur: true, // pause continuous 3D rendering while the window is unfocused
  fpsCap: 60, // 30 | 60
  renderScale: 'native', // 'native' | '1080p' | '720p'
  soundEnabled: true, // UI sounds (Web Audio, synthesised)
  encryptedStorage: false, // mirrors the on-disk format; change it with setEncryption()
  petVisible: false, // desktop pet on/off
  petMotion: '' // motion the desktop pet was last switched to ('' = its default)
})

/** Fields the backend stores; everything else never leaves this machine. */
export const ACCOUNT_FIELDS = Object.freeze(['hourCycle', 'glassIntensity', 'soundEnabled'])

/** Timings, exported so tests can shorten them. */
export const SETTINGS_TIMING = {
  localWriteMs: 120, // coalesce slider drags into one write
  accountPushMs: 600, // coalesce quick edits into one request
  retryMs: [5000, 15000, 60000] // backoff for a failed sync; the last value repeats
}

const bounded = (value, min, max, fallback) => {
  if (value === null || value === undefined || value === '') return fallback
  const n = Number(value)
  return Number.isFinite(n) ? Math.round(Math.min(max, Math.max(min, n))) : fallback
}

/** One normaliser per field: whatever comes in (disk, server, UI), a valid value comes out. */
const NORMALIZE = {
  notificationsEnabled: (v) => v !== false,
  locale: (v) => v === 'en' ? 'en' : 'zh',
  hourCycle: (v) => (String(v) === '12' ? '12' : '24'),
  glassIntensity: (v) => bounded(v, 0, 100, 100),
  badgeEnabled: (v) => v !== false,
  zoomPercent: (v) => bounded(v, 50, 200, 100),
  suspendOnBlur: (v) => v !== false,
  fpsCap: (v) => (Number(v) === 30 ? 30 : 60),
  renderScale: (v) => (v === '1080p' || v === '720p' ? v : 'native'),
  soundEnabled: (v) => v !== false,
  encryptedStorage: (v) => v === true,
  petVisible: (v) => v === true,
  petMotion: (v) => (typeof v === 'string' ? v.slice(0, 120) : '')
}
const FIELDS = Object.keys(DEFAULT_SETTINGS)

export function sanitizeSettings(raw) {
  const out = { ...DEFAULT_SETTINGS }
  if (!raw || typeof raw !== 'object') return out
  for (const key of FIELDS) {
    if (key in raw) out[key] = NORMALIZE[key](raw[key])
  }
  return out
}

/** Swap 24-hour tokens in a dayjs format string for 12-hour ones. */
export function to12Hour(fmt) {
  return fmt.replace(/HH:mm(:ss)?/, (match) => (match.endsWith(':ss') ? 'h:mm:ss A' : 'h:mm A'))
}

const storage = () => (typeof localStorage === 'undefined' ? null : localStorage)

/**
 * Read one user's bucket.
 * `issue` is null, 'corrupt' (unparseable; set aside and replaced by defaults) or
 * 'unreadable' (encrypted and the key cannot open it; left exactly as it is).
 */
async function loadBucket(userId) {
  const result = { values: { ...DEFAULT_SETTINGS }, pending: [], issue: null }
  const store = storage()
  if (!store || userId == null) return result

  const key = bucketKey(userId)
  let raw = null
  try {
    raw = store.getItem(key)
  } catch {
    return result // storage blocked (private mode): run on defaults for this session
  }

  let parsed = null
  if (raw) {
    try {
      parsed = JSON.parse(raw)
    } catch {
      // Not JSON at all. Keep the bytes under another name rather than destroying
      // them, then start clean so the app keeps working.
      try {
        store.setItem(`${key}.corrupt`, raw)
        store.removeItem(key)
      } catch {
        /* nothing more can be done without storage */
      }
      result.issue = 'corrupt'
    }
  }

  let encrypted = false
  if (parsed && typeof parsed === 'object' && parsed.__encrypted) {
    encrypted = true
    // Only this account's key. The old shared key has no trustworthy ownership
    // binding, so silently trying it here would let another account inherit the
    // ciphertext. Keep unreadable legacy data intact for explicit recovery/reset.
    const plain = isEnvelope(parsed) ? await decryptValue(parsed, userId) : null
    if (!plain || typeof plain !== 'object') {
      result.issue = 'unreadable'
      result.values.encryptedStorage = true
      return result
    }
    parsed = plain
  }

  if (parsed && typeof parsed === 'object') {
    result.values = sanitizeSettings(parsed)
    if (Array.isArray(parsed.__pendingSync)) {
      result.pending = parsed.__pendingSync.filter((field) => ACCOUNT_FIELDS.includes(field))
    }
  }
  result.values.encryptedStorage = encrypted

  // An unowned machine-wide legacy pet flag is deliberately not inherited.
  // New accounts get defaults; known per-account values above are preserved.
  return result
}

async function writeBucket(userId, snapshot) {
  const store = storage()
  if (userId == null) return
  if (!store) throw new Error('local storage unavailable')
  const payload = snapshot.encryptedStorage ? await encryptValue(snapshot, userId) : snapshot
  store.setItem(bucketKey(userId), JSON.stringify(payload))
}

export const useSettingsStore = defineStore('settings', () => {
  const state = ref({ ...DEFAULT_SETTINGS })
  const ready = ref(false)
  const busy = ref(false)
  const storageIssue = ref(null)
  const localSaveFailed = ref(false)
  const syncStatus = ref('idle')
  const pendingSync = ref(0)

  let active = null
  let api = null
  let disposed = false
  let writeChain = Promise.resolve()
  // Account handoff and encryption-format changes share one ordered barrier.
  let operationChain = Promise.resolve()
  let pendingOperations = 0
  let requestedOwner = null

  const sameOwner = (a, b) => a == null || b == null ? a === b : String(a) === String(b)
  const isCurrent = (ctx) => !!ctx && !disposed && ctx === active && !ctx.retired
  const canApply = (ctx) => isCurrent(ctx) && ready.value && !busy.value
  const refreshPending = () => { pendingSync.value = active?.dirty.size || 0 }

  function newContext(owner) {
    return {
      owner, retired: false, dirty: new Set(), revision: {}, pulled: false,
      localTimer: null, pushTimer: null, retryTimer: null, retryAttempt: 0,
      pullSequence: 0, pullPromise: null, pushPromise: null, pushAgain: false,
      pullFailed: false, pushFailed: false, saveSequence: 0
    }
  }

  function clearSyncTimers(ctx) {
    if (!ctx) return
    if (ctx.pushTimer !== null) clearTimeout(ctx.pushTimer)
    if (ctx.retryTimer !== null) clearTimeout(ctx.retryTimer)
    ctx.pushTimer = ctx.retryTimer = null
  }

  function snapshotNow(ctx = active) {
    return { ...state.value, __pendingSync: [...(ctx?.dirty || [])] }
  }

  // Every write captures owner + immutable values now, never when an async
  // timer/crypto operation finishes. Serialisation also prevents stale writes
  // from overtaking a newer encrypted payload.
  function enqueueWrite(ctx, snapshot) {
    if (!ctx || ctx.owner == null) return Promise.resolve()
    const ticket = ++ctx.saveSequence
    const run = writeChain.then(() => writeBucket(ctx.owner, snapshot))
    writeChain = run.then(
      () => {
        if (isCurrent(ctx) && ticket === ctx.saveSequence) localSaveFailed.value = false
      },
      () => {
        if (isCurrent(ctx) && ticket === ctx.saveSequence) localSaveFailed.value = true
      }
    )
    return run
  }

  function scheduleLocalWrite(ctx = active) {
    if (!canApply(ctx) || ctx.owner == null || storageIssue.value === 'unreadable') return
    if (ctx.localTimer !== null) clearTimeout(ctx.localTimer)
    ctx.localTimer = setTimeout(() => {
      ctx.localTimer = null
      if (canApply(ctx) && storageIssue.value !== 'unreadable') {
        enqueueWrite(ctx, snapshotNow(ctx)).catch(() => {})
      }
    }, SETTINGS_TIMING.localWriteMs)
  }

  async function flushLocal() {
    const ctx = active
    if (ctx?.localTimer !== null && ctx?.localTimer !== undefined) {
      clearTimeout(ctx.localTimer)
      ctx.localTimer = null
      if (ctx.owner != null && storageIssue.value !== 'unreadable') {
        enqueueWrite(ctx, snapshotNow(ctx)).catch(() => {})
      }
    }
    await writeChain
    if (localSaveFailed.value) throw new Error('settings could not be saved locally')
  }

  function markChanges(keys) {
    const ctx = active
    if (!ctx || ctx.owner == null) return
    for (const key of keys) {
      ctx.revision[key] = (ctx.revision[key] || 0) + 1
      if (ACCOUNT_FIELDS.includes(key)) ctx.dirty.add(key)
    }
    refreshPending()
    scheduleLocalWrite(ctx)
    if (keys.some((key) => ACCOUNT_FIELDS.includes(key))) scheduleAccountPush(ctx)
  }

  function setField(key, value) {
    // Inputs are disabled while busy, and programmatic callers cannot sneak an
    // edit into the old account during an async handoff or encryption change.
    if (!ready.value || busy.value || !isCurrent(active)) return
    const next = NORMALIZE[key](value)
    if (state.value[key] === next) return
    state.value = { ...state.value, [key]: next }
    markChanges([key])
  }

  const field = (key) => computed({ get: () => state.value[key], set: (v) => setField(key, v) })
  const notificationsEnabled = field('notificationsEnabled')
  const locale = field('locale')
  const hourCycle = field('hourCycle')
  const glassIntensity = field('glassIntensity')
  const badgeEnabled = field('badgeEnabled')
  const zoomPercent = field('zoomPercent')
  const suspendOnBlur = field('suspendOnBlur')
  const fpsCap = field('fpsCap')
  const renderScale = field('renderScale')
  const soundEnabled = field('soundEnabled')
  const petVisible = field('petVisible')
  const petMotion = field('petMotion')
  const encryptedStorage = computed(() => state.value.encryptedStorage === true)

  function resetToDefaults() {
    if (!ready.value || busy.value || !isCurrent(active)) throw new Error('settings are busy')
    const keep = state.value.encryptedStorage === true
    const next = { ...DEFAULT_SETTINGS, encryptedStorage: keep }
    const changed = FIELDS.filter((key) => state.value[key] !== next[key])
    state.value = next
    markChanges(changed)
    return { encryptionPreserved: keep }
  }

  watch(glassIntensity, (value) => {
    if (typeof document !== 'undefined' && document.documentElement) {
      document.documentElement.style.setProperty('--glass-blur-scale', String(value / 100))
    }
  }, { immediate: true })

  watch(zoomPercent, (value) => {
    if (typeof window !== 'undefined' && window.electronAPI?.setZoom) {
      Promise.resolve(window.electronAPI.setZoom(value)).catch(() => {})
    }
  }, { immediate: true })

  watch([fpsCap, renderScale, suspendOnBlur], ([fps, scale, suspend]) => {
    configureRenderGovernor({ fpsCap: fps, renderScale: scale, suspendOnBlur: suspend })
  }, { immediate: true, flush: 'sync' })

  function bindAccountApi(next) { api = next }

  // Server responses are a contract, not input to be guessed/clamped. Missing,
  // malformed, or different accepted values must never clear pending changes.
  function validAccountData(data) {
    return !!data && !Array.isArray(data) && typeof data === 'object' &&
      (data.hourCycle === '12' || data.hourCycle === '24') &&
      Number.isInteger(data.glassIntensity) && data.glassIntensity >= 0 && data.glassIntensity <= 100 &&
      typeof data.soundEnabled === 'boolean'
  }

  const contractError = () => Object.assign(new Error('Invalid settings response'), { noRetry: true })
  const shouldRetry = (error) => {
    const status = error?.response?.status
    return !error?.noRetry && (!(status >= 400 && status < 500) || status === 429)
  }

  function updateStatus(ctx) {
    if (!isCurrent(ctx)) return
    refreshPending()
    if (ctx.owner == null) syncStatus.value = 'idle'
    else if (ctx.pullFailed || ctx.pushFailed) syncStatus.value = 'failed'
    else if (ctx.pulled && ctx.dirty.size === 0 && !ctx.pullPromise && !ctx.pushPromise) syncStatus.value = 'synced'
    else syncStatus.value = 'syncing'
  }

  function scheduleRetry(ctx, error) {
    if (!canApply(ctx) || !shouldRetry(error)) return
    if (ctx.retryTimer !== null) clearTimeout(ctx.retryTimer)
    const delays = SETTINGS_TIMING.retryMs
    const delay = delays[Math.min(ctx.retryAttempt++, delays.length - 1)]
    ctx.retryTimer = setTimeout(() => {
      ctx.retryTimer = null
      if (canApply(ctx)) void retrySync()
    }, delay)
  }

  function scheduleAccountPush(ctx = active, delay = SETTINGS_TIMING.accountPushMs) {
    if (!canApply(ctx) || ctx.owner == null || !api || ctx.dirty.size === 0) return
    if (ctx.pushTimer !== null) clearTimeout(ctx.pushTimer)
    ctx.pushTimer = setTimeout(() => {
      ctx.pushTimer = null
      if (canApply(ctx)) void pushAccount(ctx)
    }, delay)
    updateStatus(ctx)
  }

  function pushAccount(ctx = active) {
    if (!canApply(ctx) || ctx.owner == null || !api) return Promise.resolve({ synced: false })
    if (ctx.pushPromise) {
      ctx.pushAgain = true
      return ctx.pushPromise // flush must await the actual existing request
    }
    if (!ctx.dirty.size) return Promise.resolve({ synced: true })
    const sent = {}, revisions = {}
    for (const key of ctx.dirty) {
      sent[key] = state.value[key]
      revisions[key] = ctx.revision[key] || 0
    }
    const adapter = api
    const promise = (async () => {
      try {
        const response = await adapter.update(ctx.owner, { ...sent })
        if (!canApply(ctx)) return { synced: false, stale: true }
        const data = response?.data
        if (!validAccountData(data)) throw contractError()
        let mismatch = false
        for (const [key, value] of Object.entries(sent)) {
          if (data[key] !== value) { mismatch = true; continue }
          // Even A -> B -> A edits within one request have different revisions.
          if ((ctx.revision[key] || 0) === revisions[key] && state.value[key] === value) ctx.dirty.delete(key)
        }
        refreshPending()
        scheduleLocalWrite(ctx)
        if (mismatch) throw contractError()
        ctx.pushFailed = false
        ctx.retryAttempt = 0
        return { synced: true }
      } catch (error) {
        if (!canApply(ctx)) return { synced: false, stale: true }
        ctx.pushFailed = true
        scheduleRetry(ctx, error)
        return { synced: false }
      }
    })()
    ctx.pushPromise = promise
    updateStatus(ctx)
    void promise.then((result) => {
      if (ctx.pushPromise === promise) ctx.pushPromise = null
      if (!canApply(ctx)) return
      const again = ctx.pushAgain
      ctx.pushAgain = false
      updateStatus(ctx)
      if (result.synced && ctx.dirty.size && (again || !ctx.pushFailed)) scheduleAccountPush(ctx, 0)
    })
    return promise
  }

  function syncFromAccount() {
    const ctx = active
    if (!canApply(ctx) || ctx.owner == null || !api) return Promise.resolve({ synced: false, reason: 'anonymous' })
    const sequence = ++ctx.pullSequence
    const revisions = { ...ctx.revision }
    const adapter = api
    const promise = (async () => {
      try {
        const response = await adapter.get(ctx.owner)
        if (!canApply(ctx) || sequence !== ctx.pullSequence) return { synced: false, reason: 'stale' }
        const data = response?.data
        if (!validAccountData(data)) throw contractError()
        const next = { ...state.value }
        let changed = false
        for (const key of ACCOUNT_FIELDS) {
          // A late GET cannot undo a local edit made after it started, even when
          // that edit's PUT has already succeeded and cleared its dirty flag.
          if (ctx.dirty.has(key) || (ctx.revision[key] || 0) !== (revisions[key] || 0)) continue
          if (next[key] !== data[key]) { next[key] = data[key]; changed = true }
        }
        if (changed) { state.value = next; scheduleLocalWrite(ctx) }
        ctx.pulled = true
        ctx.pullFailed = false
        ctx.retryAttempt = 0
        if (ctx.dirty.size) void pushAccount(ctx)
        return { synced: true }
      } catch (error) {
        if (!canApply(ctx) || sequence !== ctx.pullSequence) return { synced: false, reason: 'stale' }
        ctx.pullFailed = true
        scheduleRetry(ctx, error)
        return { synced: false, reason: 'unreachable' }
      }
    })()
    ctx.pullPromise = promise
    updateStatus(ctx)
    void promise.then(() => {
      if (ctx.pullPromise === promise) ctx.pullPromise = null
      updateStatus(ctx)
    })
    return promise
  }

  async function retrySync() {
    const ctx = active
    if (!canApply(ctx) || ctx.owner == null || !api) return
    if (ctx.retryTimer !== null) { clearTimeout(ctx.retryTimer); ctx.retryTimer = null }
    // A successful partial PUT does not prove the other fields were loaded.
    if (!ctx.pulled || ctx.pullFailed) await syncFromAccount()
    if (canApply(ctx) && ctx.dirty.size) await pushAccount(ctx)
  }

  async function flushAccount(timeoutMs = 3000) {
    const ctx = active
    if (!canApply(ctx) || ctx.owner == null || !api) return
    if (ctx.pushTimer !== null) { clearTimeout(ctx.pushTimer); ctx.pushTimer = null }
    let timer
    const work = (async () => {
      while (canApply(ctx) && (ctx.pushPromise || ctx.dirty.size)) {
        const result = await (ctx.pushPromise || pushAccount(ctx))
        if (!result?.synced) break
      }
    })()
    try {
      await Promise.race([work, new Promise((resolve) => { timer = setTimeout(resolve, Math.max(0, timeoutMs)) })])
    } finally {
      clearTimeout(timer)
      // Unconfirmed changes survive sign-out even if a request timed out.
      if (ctx === active) await flushLocal()
    }
  }

  function queueOperation(work) {
    pendingOperations += 1
    busy.value = true
    const run = operationChain.then(work)
    operationChain = run.catch(() => {})
    return run.finally(() => {
      pendingOperations -= 1
      busy.value = pendingOperations > 0
      if (!busy.value && isCurrent(active) && ready.value && active.owner != null && api) void retrySync()
    })
  }

  function switchUser(userId) {
    const next = userId ?? null
    // Coalesce duplicate boot/login watchers while the same handoff is pending.
    if (sameOwner(next, requestedOwner) && (busy.value || (ready.value && sameOwner(next, active?.owner)))) {
      return operationChain
    }
    requestedOwner = next
    if (active) {
      // Invalidate network work synchronously, BEFORE awaiting crypto/IO.
      active.retired = true
      clearSyncTimers(active)
    }
    ready.value = false
    return queueOperation(async () => {
      const outgoing = active
      if (outgoing) {
        if (outgoing.localTimer !== null) clearTimeout(outgoing.localTimer)
        outgoing.localTimer = null
        if (outgoing.owner != null && storageIssue.value !== 'unreadable') {
          // Keep the outgoing account's final snapshot even if its timer had
          // not fired. Never write this snapshot to the incoming account.
          if (outgoing.saveSequence || outgoing.dirty.size || FIELDS.some((k) => state.value[k] !== DEFAULT_SETTINGS[k])) {
            enqueueWrite(outgoing, snapshotNow(outgoing)).catch(() => {})
          }
        }
      }
      await writeChain
      const ctx = newContext(next)
      active = ctx
      state.value = { ...DEFAULT_SETTINGS }
      localSaveFailed.value = false
      syncStatus.value = 'idle'
      storageIssue.value = null
      refreshPending()
      const loaded = await loadBucket(next)
      state.value = loaded.values
      ctx.dirty = new Set(loaded.pending)
      storageIssue.value = loaded.issue
      refreshPending()
      ready.value = true
      // If another switch is queued, this intermediate account must not send
      // requests with the final account's token.
      if (!sameOwner(next, requestedOwner)) ctx.retired = true
    })
  }

  function setEncryption(enabled) {
    const ctx = active
    const want = enabled === true
    if (!canApply(ctx) || ctx.owner == null) return Promise.reject(new Error('settings are busy or signed out'))
    if (storageIssue.value === 'unreadable') return Promise.reject(new Error('stored settings are unreadable'))
    if (want && !isEncryptionSupported()) return Promise.reject(new Error('encryption is unavailable'))
    if (want === state.value.encryptedStorage) return Promise.resolve({ ownerId: ctx.owner })
    return queueOperation(async () => {
      if (ctx !== active) throw new Error('account changed')
      if (ctx.localTimer !== null) { clearTimeout(ctx.localTimer); ctx.localTimer = null }
      clearSyncTimers(ctx)
      // Disallow an older GET from applying after the format operation finishes.
      ctx.pullSequence += 1
      await enqueueWrite(ctx, snapshotNow(ctx))
      await writeChain
      const store = storage()
      if (!store) throw new Error('local storage unavailable')
      const key = bucketKey(ctx.owner)
      const before = store.getItem(key)
      const payload = { ...snapshotNow(ctx), encryptedStorage: want }
      try {
        await enqueueWrite(ctx, payload)
        const raw = store.getItem(key)
        if (!raw) throw new Error('settings write was not retained')
        const stored = JSON.parse(raw)
        const verified = want ? await decryptValue(stored, ctx.owner) : stored
        if (!verified || JSON.stringify(verified) !== JSON.stringify(payload) || isEnvelope(stored) !== want) {
          throw new Error('settings write verification failed')
        }
        // Report the new format only after the exact payload is read back.
        state.value = { ...state.value, encryptedStorage: want }
        if (!isCurrent(ctx)) throw Object.assign(new Error('account changed'), { committed: true })
        return { ownerId: ctx.owner }
      } catch (error) {
        if (!error.committed) {
          // Restore original bytes, not a newly encrypted guess that could
          // destroy a still-readable older envelope after a key failure.
          try {
            if (before === null) store.removeItem(key)
            else store.setItem(key, before)
          } catch {
            if (isCurrent(ctx)) localSaveFailed.value = true
          }
        }
        throw error
      }
    })
  }

  function discardUnreadableStorage() {
    if (!canApply(active) || storageIssue.value !== 'unreadable' || active.owner == null) return
    const store = storage()
    if (!store) return
    try { store.removeItem(bucketKey(active.owner)) } catch { localSaveFailed.value = true; return }
    storageIssue.value = null
    state.value = { ...state.value, encryptedStorage: false }
    scheduleLocalWrite()
  }

  const onOnline = () => { if (syncStatus.value === 'failed') void retrySync() }
  const onPageHide = () => {
    // localStorage is synchronous only for plain blobs. Flush it directly on
    // close; encrypted writes are continuously coalesced during the session.
    const ctx = active
    if (canApply(ctx) && ctx.owner != null && !state.value.encryptedStorage && storageIssue.value !== 'unreadable') {
      try { storage()?.setItem(bucketKey(ctx.owner), JSON.stringify(snapshotNow(ctx))) } catch { localSaveFailed.value = true }
    }
  }
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    window.addEventListener('online', onOnline)
    window.addEventListener('pagehide', onPageHide)
  }
  onScopeDispose(() => {
    disposed = true
    if (active) {
      active.retired = true
      clearSyncTimers(active)
      if (active.localTimer !== null) clearTimeout(active.localTimer)
    }
    if (typeof window !== 'undefined') {
      window.removeEventListener?.('online', onOnline)
      window.removeEventListener?.('pagehide', onPageHide)
    }
  })

  function shouldNotify() { return ready.value && active?.owner != null && notificationsEnabled.value }
  function format(time, fmt = 'HH:mm') {
    if (time === null || time === undefined || time === '') return ''
    const d = dayjs(time)
    return d.isValid() ? d.format(hourCycle.value === '12' ? to12Hour(fmt) : fmt) : ''
  }

  return {
    ready, busy, storageIssue, localSaveFailed, syncStatus, pendingSync,
    notificationsEnabled, locale, hourCycle, glassIntensity, badgeEnabled, zoomPercent,
    suspendOnBlur, fpsCap, renderScale, soundEnabled, petVisible, petMotion,
    encryptedStorage, setEncryption, discardUnreadableStorage, resetToDefaults,
    switchUser, flushLocal, bindAccountApi, syncFromAccount, retrySync,
    flushAccount, shouldNotify, format
  }
})

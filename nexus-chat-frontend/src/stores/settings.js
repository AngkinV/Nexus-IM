import { ref, computed, watch } from 'vue'
import { defineStore } from 'pinia'
import dayjs from 'dayjs'
import { setFrameCap } from '../utils/frameGate.js'

/**
 * User preferences that live on this machine only.
 *
 * Everything here is a real switch: a value written to localStorage and read
 * back by the feature that owns it. Nothing is stored "for show" — if a control
 * is on the settings screen it must change behaviour somewhere.
 *
 * The notification switch is the reason this store exists: before, the settings
 * screen flipped a local ref nobody read, while websocket.js and call.js called
 * electronAPI.showNotification unconditionally.
 */

const STORAGE_KEY_BASE = 'nexus.settings'

/**
 * Per-user storage key.
 *
 * Settings used to live under one global key, so signing in as a second account
 * on the same machine silently inherited — and then overwrote — the first
 * account's preferences. The key is now scoped to the signed-in user id; an
 * anonymous session gets its own bucket rather than borrowing someone's.
 */
function storageKey() {
  try {
    const raw = localStorage.getItem('user')
    if (!raw) return `${STORAGE_KEY_BASE}.anon`
    const user = JSON.parse(raw)
    const id = user?.id
    return id != null ? `${STORAGE_KEY_BASE}.${id}` : `${STORAGE_KEY_BASE}.anon`
  } catch {
    return `${STORAGE_KEY_BASE}.anon`
  }
}

/**
 * Move a blob written under the old global key into the current user's bucket.
 *
 * Before per-user keys existed every account shared `nexus.settings`. Leaving
 * that key in place would let it leak into whichever account happens to sign in
 * next, so it is claimed once by the first user we see and then removed.
 */
function migrateLegacyBlob() {
  try {
    const legacy = localStorage.getItem(STORAGE_KEY_BASE)
    if (!legacy) return
    const scoped = storageKey()
    if (scoped === `${STORAGE_KEY_BASE}.anon`) return // no user yet: leave it
    if (!localStorage.getItem(scoped)) localStorage.setItem(scoped, legacy)
    localStorage.removeItem(STORAGE_KEY_BASE)
  } catch {
    /* a failed migration must not block startup */
  }
}

/** The signed-in user's id, or null when nobody is signed in. */
function currentUserId() {
  try {
    const raw = localStorage.getItem('user')
    if (!raw) return null
    const user = JSON.parse(raw)
    return user?.id ?? null
  } catch {
    return null
  }
}

export const DEFAULT_SETTINGS = Object.freeze({
  notificationsEnabled: true,
  hourCycle: '24', // '24' | '12'
  glassIntensity: 100, // 0-100, drives --glass-blur-scale
  badgeEnabled: true, // dock / taskbar unread badge
  zoomPercent: 100, // 50-200, whole-window zoom
  suspendOnBlur: true, // pause 3D rendering while the window is unfocused
  fpsCap: 60, // 30 | 60
  renderScale: 'native', // 'native' | '1080p' | '720p'
  soundEnabled: true, // UI sounds (Web Audio, synthesised)
  encryptedStorage: false // encrypt the settings blob at rest
})

function readStored() {
  if (typeof localStorage === 'undefined') return { ...DEFAULT_SETTINGS }
  try {
    const raw = localStorage.getItem(storageKey())
    if (!raw) return { ...DEFAULT_SETTINGS }
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return { ...DEFAULT_SETTINGS }
    const merged = { ...DEFAULT_SETTINGS }
    for (const key of Object.keys(DEFAULT_SETTINGS)) {
      if (key in parsed) merged[key] = parsed[key]
    }
    return merged
  } catch {
    return { ...DEFAULT_SETTINGS }
  }
}

/** Swap 24-hour tokens in a dayjs format string for 12-hour ones. */
export function to12Hour(fmt) {
  return fmt.replace(/HH:mm(:ss)?/, (match) => (match.endsWith(':ss') ? 'h:mm:ss A' : 'h:mm A'))
}

export const useSettingsStore = defineStore('settings', () => {
  const state = ref(readStored())
  const ready = ref(false)

  // Persist on any change so a reload keeps the user's choice.
  //
  // When encryption is on the blob is re-encrypted instead of written as plain
  // JSON. Writes are coalesced: dragging a slider fires a change per pixel, and
  // AES-GCM on every one of those would be wasteful.
  let persistTimer = null
  let persistPending = false

  const writeNow = async (value) => {
    try {
      if (value.encryptedStorage) {
        const { encryptValue } = await import('@/utils/secureStorage')
        const envelope = await encryptValue({ ...value })
        localStorage.setItem(storageKey(), JSON.stringify(envelope))
      } else {
        localStorage.setItem(storageKey(), JSON.stringify(value))
      }
    } catch {
      /* quota, private mode, or crypto unavailable — the in-memory value
         still applies for this session */
    }
  }

  /**
   * Held while an encryption toggle is rewriting the blob. Without this the
   * 120ms coalescing timer can fire mid-toggle and re-encrypt a state that has
   * not finished flipping, leaving the on-disk flag disagreeing with the data.
   */
  let persistHeld = false

  function holdPersist() {
    persistHeld = true
    if (persistTimer) {
      clearTimeout(persistTimer)
      persistTimer = null
    }
    persistPending = false
  }

  function releasePersist(flush = true) {
    persistHeld = false
    if (flush) writeNow({ ...state.value })
  }

  watch(
    state,
    (value) => {
      if (typeof localStorage === 'undefined') return
      if (persistHeld) return
      if (persistPending) return
      persistPending = true
      if (persistTimer) clearTimeout(persistTimer)
      persistTimer = setTimeout(() => {
        persistPending = false
        persistTimer = null
        writeNow(value)
      }, 120)
    },
    { deep: true }
  )

  /** Which user the in-memory state belongs to, so a switch can be detected. */
  let loadedForUserId = null

  /**
   * Load the stored blob. Called at boot and again whenever the signed-in user
   * changes: settings are stored per user id, so signing out and into another
   * account must not carry the previous account's preferences across.
   */
  async function hydrate() {
    if (typeof localStorage === 'undefined') {
      ready.value = true
      return
    }
    loadedForUserId = currentUserId()
    migrateLegacyBlob()
    try {
      const raw = localStorage.getItem(storageKey())
      if (!raw) {
        ready.value = true
        return
      }
      const parsed = JSON.parse(raw)
      if (parsed && parsed.__encrypted === true) {
        // Imported lazily: secureStorage pulls in IndexedDB/Web Crypto, which a
        // node test run has no business loading just to read a plain blob.
        const { decryptValue } = await import('@/utils/secureStorage')
        const plain = await decryptValue(parsed)
        if (plain && typeof plain === 'object') {
          const merged = { ...DEFAULT_SETTINGS }
          for (const key of Object.keys(DEFAULT_SETTINGS)) {
            if (key in plain) merged[key] = plain[key]
          }
          state.value = merged
        }
      }
    } catch {
      /* corrupt blob: keep defaults */
    } finally {
      ready.value = true
    }
  }

  const notificationsEnabled = computed({
    get: () => state.value.notificationsEnabled !== false,
    set: (v) => {
      state.value.notificationsEnabled = !!v
    }
  })

  const hourCycle = computed({
    get: () => (state.value.hourCycle === '12' ? '12' : '24'),
    set: (v) => {
      state.value.hourCycle = v === '12' ? '12' : '24'
    }
  })

  const glassIntensity = computed({
    get: () => {
      const v = Number(state.value.glassIntensity)
      return Number.isFinite(v) ? Math.min(100, Math.max(0, v)) : 100
    },
    set: (v) => {
      const n = Number(v)
      state.value.glassIntensity = Number.isFinite(n) ? Math.min(100, Math.max(0, n)) : 100
    }
  })

  /**
   * Push the glass intensity into CSS. Every backdrop-filter in the app
   * multiplies its radius by --glass-blur-scale, so this one write retunes
   * the whole interface.
   */
  function applyGlassIntensity() {
    if (typeof document === 'undefined' || !document.documentElement) return
    document.documentElement.style.setProperty(
      '--glass-blur-scale',
      String(glassIntensity.value / 100)
    )
  }

  // Keep CSS in sync whenever the value changes, including on first load.
  watch(glassIntensity, applyGlassIntensity, { immediate: true })

  const badgeEnabled = computed({
    get: () => state.value.badgeEnabled !== false,
    set: (v) => {
      state.value.badgeEnabled = !!v
    }
  })

  const zoomPercent = computed({
    get: () => {
      const v = Number(state.value.zoomPercent)
      return Number.isFinite(v) ? Math.min(200, Math.max(50, v)) : 100
    },
    set: (v) => {
      const n = Number(v)
      state.value.zoomPercent = Number.isFinite(n) ? Math.min(200, Math.max(50, n)) : 100
    }
  })

  const suspendOnBlur = computed({
    get: () => state.value.suspendOnBlur !== false,
    set: (v) => {
      state.value.suspendOnBlur = !!v
    }
  })

  /**
   * Apply the zoom factor to the window. Electron's zoom is per-origin and
   * survives a reload only when re-applied, so this runs on boot too.
   */
  async function applyZoom() {
    if (typeof window === 'undefined' || !window.electronAPI?.setZoom) return
    try {
      await window.electronAPI.setZoom(zoomPercent.value)
    } catch {
      /* the window may not exist yet during boot */
    }
  }

  const fpsCap = computed({
    get: () => (Number(state.value.fpsCap) === 30 ? 30 : 60),
    set: (v) => {
      state.value.fpsCap = Number(v) === 30 ? 30 : 60
    }
  })

  const renderScale = computed({
    get: () => {
      const v = state.value.renderScale
      return v === '1080p' || v === '720p' ? v : 'native'
    },
    set: (v) => {
      state.value.renderScale = v === '1080p' || v === '720p' ? v : 'native'
    }
  })

  const soundEnabled = computed({
    get: () => state.value.soundEnabled !== false,
    set: (v) => {
      state.value.soundEnabled = !!v
    }
  })

  const encryptedStorage = computed({
    get: () => state.value.encryptedStorage === true,
    set: (v) => {
      state.value.encryptedStorage = v === true
    }
  })

  watch(zoomPercent, applyZoom, { immediate: true })

  // Plain-JS renderers (tavernStage, abyssStage, ComputerEye, the entrance
  // scene) cannot read Pinia, so mirror the cap into their shared gate.
  watch(fpsCap, setFrameCap, { immediate: true })

  /**
   * Whether the OS-level notification should actually be shown.
   * Callers must still check for the Electron bridge themselves.
   */
  function shouldNotify() {
    return notificationsEnabled.value
  }

  /**
   * Format a timestamp honouring the user's hour cycle.
   * Returns '' for empty or invalid input.
   *
   * @param {string|number|Date} time
   * @param {string} fmt dayjs format string, may contain HH:mm
   */
  function format(time, fmt = 'HH:mm') {
    if (!time) return ''
    const d = dayjs(time)
    if (!d.isValid()) return ''
    return d.format(hourCycle.value === '12' ? to12Hour(fmt) : fmt)
  }

  /**
   * Restore factory defaults.
   *
   * Encryption is a security posture, not a preference: resetting it silently
   * would write plaintext back to disk while the user believes their data is
   * still encrypted. It therefore survives a reset, and the caller is told
   * whether it was preserved so the UI can say so.
   */
  /* ── Account sync ─────────────────────────────────────────
     Three settings follow the account (hour cycle, glass, sounds). The rest
     describe this machine and never leave it. The server is the source of truth
     on sign-in; local values are the fallback when it is unreachable. */

  const syncing = ref(false)
  let pushTimer = null

  /** Fields the backend owns. */
  const ACCOUNT_FIELDS = ['hourCycle', 'glassIntensity', 'soundEnabled']

  /**
   * Pull account preferences after sign-in. Failures are silent: the local
   * values already work, and a network blip should not reset the interface.
   */
  async function syncFromAccount() {
    const id = currentUserId()
    if (!id) return { synced: false, reason: 'anonymous' }
    // A different user signed in while the app was open: drop the previous
    // account's in-memory values and reload from this account's bucket first.
    if (loadedForUserId !== null && loadedForUserId !== id) {
      state.value = { ...DEFAULT_SETTINGS }
      await hydrate()
    }
    syncing.value = true
    try {
      const { userAPI } = await import('@/services/api')
      const { data } = await userAPI.getPreferences(id)
      if (data) {
        if (data.hourCycle === '12' || data.hourCycle === '24') {
          state.value.hourCycle = data.hourCycle
        }
        if (Number.isFinite(Number(data.glassIntensity))) {
          state.value.glassIntensity = Number(data.glassIntensity)
        }
        if (typeof data.soundEnabled === 'boolean') {
          state.value.soundEnabled = data.soundEnabled
        }
      }
      return { synced: true }
    } catch {
      return { synced: false, reason: 'unreachable' }
    } finally {
      syncing.value = false
    }
  }

  /** Push one changed account field, coalescing rapid edits. */
  function scheduleAccountPush(field) {
    if (!ACCOUNT_FIELDS.includes(field)) return
    const id = currentUserId()
    if (!id) return
    if (pushTimer) clearTimeout(pushTimer)
    pushTimer = setTimeout(async () => {
      pushTimer = null
      try {
        const { userAPI } = await import('@/services/api')
        await userAPI.updatePreferences(id, {
          [field]: state.value[field]
        })
      } catch {
        /* offline: the local value stands, next sign-in re-syncs */
      }
    }, 600)
  }

  // Watch only the account-scoped fields, so dragging the zoom slider never
  // hits the network.
  watch(
    () => [state.value.hourCycle, state.value.glassIntensity, state.value.soundEnabled],
    ([hour, glass, sound], old) => {
      if (!old) return
      if (hour !== old[0]) scheduleAccountPush('hourCycle')
      if (glass !== old[1]) scheduleAccountPush('glassIntensity')
      if (sound !== old[2]) scheduleAccountPush('soundEnabled')
    }
  )

  function resetToDefaults() {
    const keepEncrypted = state.value.encryptedStorage === true
    state.value = { ...DEFAULT_SETTINGS, encryptedStorage: keepEncrypted }
    return { encryptionPreserved: keepEncrypted }
  }

  return {
    state,
    ready,
    hydrate,
    notificationsEnabled,
    hourCycle,
    glassIntensity,
    badgeEnabled,
    zoomPercent,
    suspendOnBlur,
    fpsCap,
    renderScale,
    soundEnabled,
    encryptedStorage,
    applyZoom,
    shouldNotify,
    applyGlassIntensity,
    format,
    resetToDefaults,
    syncing,
    syncFromAccount,
    holdPersist,
    releasePersist
  }
})

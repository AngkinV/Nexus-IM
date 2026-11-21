import { ref, computed, watch } from 'vue'
import { defineStore } from 'pinia'
import dayjs from 'dayjs'

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

const STORAGE_KEY = 'nexus.settings'

export const DEFAULT_SETTINGS = Object.freeze({
  notificationsEnabled: true,
  hourCycle: '24' // '24' | '12'
})

function readStored() {
  if (typeof localStorage === 'undefined') return { ...DEFAULT_SETTINGS }
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
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

  // Persist on any change so a reload keeps the user's choice.
  watch(
    state,
    (value) => {
      if (typeof localStorage === 'undefined') return
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(value))
      } catch {
        /* quota or private mode — the in-memory value still applies */
      }
    },
    { deep: true }
  )

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

  function resetToDefaults() {
    state.value = { ...DEFAULT_SETTINGS }
  }

  return {
    state,
    notificationsEnabled,
    hourCycle,
    shouldNotify,
    format,
    resetToDefaults
  }
})

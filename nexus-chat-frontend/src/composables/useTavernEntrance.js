import { reactive, readonly } from 'vue'

/**
 * Tavern entrance — the transition played when the user walks into the tavern.
 *
 * One entrance exists per window, so the state lives at module level and every caller
 * shares it. Callers ask for it with `enter({ onCommit })`. There is no confirmation
 * step: `onCommit` runs by itself once the picture covers the app, and the picture
 * then clears to show the tavern.
 *
 * The first entrance for an account plays in full and cannot be interrupted — that is
 * the one time the picture is actually seen. Every later entrance can be skipped with a
 * click, Enter, Space, Esc, or the Skip button the stage offers.
 *
 *   idle ──enter──▶ playing ──(stage: commit)──▶ playing ──(stage: finish)──▶ idle
 *                      │
 *                      └──skip / cancel──▶ leaving ──(stage: finish)──▶ idle
 */
export const ENTRANCE_PHASE = Object.freeze({
  IDLE: 'idle',
  PLAYING: 'playing',
  LEAVING: 'leaving'
})

const SEEN_KEY = 'tavernEntranceSeen'

/**
 * The key is per account, so every account gets its own one full viewing and no more.
 * Falls back to a plain key when the signed-in user is not readable yet.
 */
const seenKey = () => {
  try {
    const id = JSON.parse(localStorage.getItem('user') || 'null')?.id
    return id ? `${SEEN_KEY}:${id}` : SEEN_KEY
  } catch {
    return SEEN_KEY
  }
}

// Stands in for localStorage where it is missing (unit tests, locked-down webviews).
// Keyed like the real thing, so accounts stay separate there too.
const seenInMemory = new Set()

const hasSeen = (key = seenKey()) => {
  try {
    const stored = localStorage.getItem(key)
    if (stored !== null) return stored === '1'
  } catch {
    /* no storage: fall through to memory */
  }
  return seenInMemory.has(key)
}

/** Record that the full picture has been seen; later entrances may be skipped. */
const markSeen = () => {
  if (state.phase !== ENTRANCE_PHASE.PLAYING || !runSeenKey) return
  const key = runSeenKey
  seenInMemory.add(key)
  try {
    localStorage.setItem(key, '1')
  } catch {
    /* no storage: memory alone is enough for this session */
  }
}

/** Test/debug only: forget that the picture was seen, so the next entrance is the first one again. */
export const clearEntranceSeen = () => {
  seenInMemory.clear()
  try {
    localStorage.removeItem(seenKey())
    localStorage.removeItem(SEEN_KEY)
  } catch {
    /* nothing to clear */
  }
}

const state = reactive({
  phase: ENTRANCE_PHASE.IDLE,
  /** Bumped on every entrance. */
  runId: 0,
  /** True once the destination has been committed during this run. */
  committed: false,
  /** Repeat entrances in the same window play at double speed. */
  fast: false,
  /** False on the first entrance for this account: it must play out in full. */
  canSkip: false
})

let runSeenKey = null
let callbacks = null
let hostCount = 0
let hasEntered = false

const prefersReducedMotion = () =>
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

/**
 * Start the entrance.
 * @param {{ onCommit?: () => void, onCancel?: () => void }} [options]
 * @returns {boolean} true when the transition started; false when `onCommit` ran
 *   straight away (no stage mounted, or reduced motion) or one is already playing
 */
function enter({ onCommit, onCancel } = {}) {
  if (state.phase !== ENTRANCE_PHASE.IDLE) return false

  // No stage mounted, or the user asked for less motion: go straight in.
  if (hostCount === 0 || prefersReducedMotion()) {
    hasEntered = true
    onCommit?.()
    return false
  }

  callbacks = { onCommit, onCancel }
  state.committed = false
  runSeenKey = seenKey()
  state.canSkip = hasSeen(runSeenKey)
  // The first viewing is always the long one; repeats are the short version.
  state.fast = false
  state.runId += 1
  state.phase = ENTRANCE_PHASE.PLAYING
  return true
}

/** Switch to the destination. Called by the stage once the picture covers the app. */
function commit() {
  if (state.phase === ENTRANCE_PHASE.IDLE || state.committed) return
  state.committed = true
  hasEntered = true
  callbacks?.onCommit?.()
}

/**
 * Stop watching and go in now: commits if that has not happened yet, then clears the stage.
 * @param {boolean} [force] Ignore the first-viewing lock (a resize makes the picture unusable).
 */
function skip(force = false) {
  if (state.phase !== ENTRANCE_PHASE.PLAYING) return
  if (!state.canSkip && !force) return
  commit()
  state.phase = ENTRANCE_PHASE.LEAVING
}

/**
 * Get the stage out of the way. Before the commit this backs out and the app stays
 * where it was; after it, the destination is already on screen, so this only clears
 * the stage.
 * @param {boolean} [force] Ignore the first-viewing lock (an incoming call outranks the picture).
 */
function cancel(force = false) {
  if (state.phase !== ENTRANCE_PHASE.PLAYING) return
  if (!state.canSkip && !force) return
  const onCancel = state.committed ? null : callbacks?.onCancel
  if (!state.committed) callbacks = null
  state.phase = ENTRANCE_PHASE.LEAVING
  onCancel?.()
}

/** Called by the stage when it is clear again. */
function finish() {
  runSeenKey = null
  callbacks = null
  state.phase = ENTRANCE_PHASE.IDLE
}

/** Called by the stage component so `enter` knows a stage exists. */
function registerHost() {
  hostCount += 1
}

function unregisterHost() {
  hostCount = Math.max(0, hostCount - 1)
  if (hostCount > 0) return
  // The stage is gone mid-entrance (route change, logout): reset without firing callbacks.
  callbacks = null
  state.phase = ENTRANCE_PHASE.IDLE
}

export function useTavernEntrance() {
  return {
    state: readonly(state),
    enter,
    commit,
    skip,
    cancel,
    finish,
    markSeen,
    registerHost,
    unregisterHost
  }
}

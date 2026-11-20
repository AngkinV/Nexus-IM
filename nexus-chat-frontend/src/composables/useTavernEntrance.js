import { reactive, readonly } from 'vue'

/**
 * Tavern entrance — the transition played when the user walks into the tavern.
 *
 * One entrance exists per window, so the state lives at module level and every caller
 * shares it. Callers ask for it with `enter({ onCommit })`. There is no confirmation
 * step: `onCommit` runs by itself once the picture covers the app, and the picture
 * then clears to show the tavern. The only way to stay outside is to back out with
 * ESC in the first moments, before `onCommit` has run.
 *
 *   idle ──enter──▶ playing ──(stage: commit at 0.62s)──▶ playing ──(stage: finish)──▶ idle
 *                      │
 *                      └──skip / cancel──▶ leaving ──(stage: finish)──▶ idle
 */
export const ENTRANCE_PHASE = Object.freeze({
  IDLE: 'idle',
  PLAYING: 'playing',
  LEAVING: 'leaving'
})

const state = reactive({
  phase: ENTRANCE_PHASE.IDLE,
  /** Bumped on every entrance. */
  runId: 0,
  /** True once the destination has been committed during this run. */
  committed: false,
  /** Repeat entrances in the same window play at double speed. */
  fast: false
})

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
  state.fast = hasEntered
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

/** Stop watching and go in now: commits if that has not happened yet, then clears the stage. */
function skip() {
  if (state.phase !== ENTRANCE_PHASE.PLAYING) return
  commit()
  state.phase = ENTRANCE_PHASE.LEAVING
}

/**
 * Get the stage out of the way. Before the commit this backs out and the app stays
 * where it was; after it, the destination is already on screen, so this only clears
 * the stage.
 */
function cancel() {
  if (state.phase !== ENTRANCE_PHASE.PLAYING) return
  const onCancel = state.committed ? null : callbacks?.onCancel
  if (!state.committed) callbacks = null
  state.phase = ENTRANCE_PHASE.LEAVING
  onCancel?.()
}

/** Called by the stage when it is clear again. */
function finish() {
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
    registerHost,
    unregisterHost
  }
}

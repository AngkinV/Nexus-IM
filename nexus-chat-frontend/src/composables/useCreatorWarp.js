import { reactive, readonly } from 'vue'

/**
 * Creator's Eye warp — the transition played when the user enters the AI dimension.
 *
 * One warp exists per window, so the state lives at module level and every caller
 * shares it. Callers ask for the warp with `trigger({ onEnter })`; the destination
 * is only committed (onEnter) once the user confirms on the arrival stage, so
 * backing out with ESC leaves the app exactly where it was.
 *
 *   idle ──trigger──▶ gaze ──820ms──▶ collapse ──980ms──▶ arrived ──confirm/cancel──▶ leaving ──▶ idle
 */
export const WARP_PHASE = Object.freeze({
  IDLE: 'idle',
  GAZE: 'gaze',
  COLLAPSE: 'collapse',
  ARRIVED: 'arrived',
  LEAVING: 'leaving'
})

/** Milestones from the design prototype, in ms from the moment the warp opens. */
export const WARP_TIMING = Object.freeze({
  collapseAt: 820,
  arriveAt: 1800,
  leaveMs: 350
})

const state = reactive({
  phase: WARP_PHASE.IDLE,
  /** Bumped on every trigger; lets the view restart one-shot animations. */
  runId: 0,
  /** Optional destination name supplied by the caller; empty means the default. */
  target: '',
  reducedMotion: false
})

let timers = []
let leaveTimer = null
let callbacks = null
let hostCount = 0

const schedule = (fn, delay) => {
  timers.push(setTimeout(fn, delay))
}

const clearTimers = () => {
  timers.forEach(clearTimeout)
  timers = []
}

const prefersReducedMotion = () =>
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

const arrive = () => {
  state.phase = WARP_PHASE.ARRIVED
}

const collapse = () => {
  state.phase = WARP_PHASE.COLLAPSE
  schedule(arrive, WARP_TIMING.arriveAt - WARP_TIMING.collapseAt)
}

const leave = () => {
  clearTimers()
  callbacks = null
  state.phase = WARP_PHASE.LEAVING
  clearTimeout(leaveTimer)
  leaveTimer = setTimeout(() => {
    leaveTimer = null
    state.phase = WARP_PHASE.IDLE
  }, WARP_TIMING.leaveMs)
}

/**
 * Start the warp.
 * @param {{ onEnter?: () => void, onCancel?: () => void, target?: string }} [options]
 * @returns {boolean} true when the transition started
 */
function trigger({ onEnter, onCancel, target = '' } = {}) {
  if (state.phase !== WARP_PHASE.IDLE) return false

  // No overlay mounted (e.g. a screen that does not host it): never strand the caller.
  if (hostCount === 0) {
    onEnter?.()
    return false
  }

  callbacks = { onEnter, onCancel }
  state.target = target
  state.runId += 1
  state.reducedMotion = prefersReducedMotion()

  if (state.reducedMotion) {
    // Skip the gaze, the blink and the shredding; go straight to the arrival card.
    arrive()
  } else {
    state.phase = WARP_PHASE.GAZE
    schedule(collapse, WARP_TIMING.collapseAt)
  }
  return true
}

/** "Abyssal leap": cut the opening gaze short and blink right now. */
function skip() {
  if (state.phase !== WARP_PHASE.GAZE) return
  clearTimers()
  collapse()
}

/** Commit the destination and close. Only meaningful once arrived. */
function confirm() {
  if (state.phase !== WARP_PHASE.ARRIVED) return
  const onEnter = callbacks?.onEnter
  leave()
  onEnter?.()
}

/** Abort at any point; the app stays where it was. */
function cancel() {
  if (state.phase === WARP_PHASE.IDLE || state.phase === WARP_PHASE.LEAVING) return
  const onCancel = callbacks?.onCancel
  leave()
  onCancel?.()
}

/** Called by the overlay component so `trigger` knows a stage exists. */
function registerHost() {
  hostCount += 1
}

function unregisterHost() {
  hostCount = Math.max(0, hostCount - 1)
  if (hostCount > 0) return
  // The stage is gone mid-warp (route change, logout): reset without firing callbacks.
  clearTimers()
  clearTimeout(leaveTimer)
  leaveTimer = null
  callbacks = null
  state.phase = WARP_PHASE.IDLE
}

export function useCreatorWarp() {
  return {
    state: readonly(state),
    trigger,
    skip,
    confirm,
    cancel,
    registerHost,
    unregisterHost
  }
}

/**
 * One place that decides how often, how sharp and whether the app draws.
 *
 * Settings → 桌面宠物与 3D 渲染 has three switches (frame-rate cap, render
 * resolution, suspend when unfocused). They only mean something if every
 * renderer obeys the same rules, so the rules live here and the renderers ask.
 * The module is plain JS on purpose: the canvas renderers are built from plain
 * functions and must not need Pinia, and it has to run under `node --test`.
 *
 * Contract
 * - Frame cap: 30 or 60 draws per second, per loop.
 * - Render resolution: a cap on the *window's* physical pixel height. "1080p"
 *   means "draw as if the window were at most 1080 physical pixels tall", so
 *   every renderer multiplies its own pixel ratio by min(1, 1080 / windowPx).
 *   A window that is already smaller than the target is left at native — the
 *   setting never upscales.
 * - Suspend: after the window has been unfocused for 30 s, continuous renderers
 *   stop drawing until it is focused again. Hidden/minimised windows pause
 *   immediately regardless of the switch. Transitions use the quality at start
 *   and keep their simulation time frozen while suspended.
 */

export const SUSPEND_DELAY_MS = 30_000
/** While suspended, loops wake this often to check for a resume instead of every frame. */
export const SUSPEND_POLL_MS = 500
const MIN_PIXEL_RATIO = 0.5
const TARGET_HEIGHT = { '720p': 720, '1080p': 1080 }

// Vite can import the same module with different cache-busting URLs during HMR.
// Keep one scheduler per renderer context, not one disconnected state per URL.
const runtimeKey = Symbol.for('nexus.renderGovernor')
const runtime = globalThis[runtimeKey] ||= {
  state: { fpsCap: 60, scaleMode: 'native', suspendOnBlur: true, focused: true,
    hidden: false, blurSuspended: false, suspended: false },
  qualityListeners: new Set(), suspendListeners: new Set(), suspendTimer: null,
  stopTracking: null
}
const state = runtime.state
const qualityListeners = runtime.qualityListeners
const suspendListeners = runtime.suspendListeners

const notify = (listeners, value) => {
  for (const listener of [...listeners]) {
    try {
      listener(value)
    } catch (error) {
      // One renderer failing to resize must not stop the others from hearing about it.
      console.error('[renderGovernor] listener failed', error)
    }
  }
}

const clearSuspendTimer = () => {
  if (runtime.suspendTimer !== null) {
    clearTimeout(runtime.suspendTimer)
    runtime.suspendTimer = null
  }
}

const setSuspended = (next) => {
  if (state.suspended === next) return
  state.suspended = next
  notify(suspendListeners, next)
}

const setBlurSuspended = (next) => {
  state.blurSuspended = next
  setSuspended(state.hidden || next)
}

/** Re-derive the suspend state from focus + the switch. Safe to call at any time. */
const reconcileSuspend = () => {
  if (state.focused || !state.suspendOnBlur) {
    clearSuspendTimer()
    setBlurSuspended(false)
    return
  }
  if (state.blurSuspended || runtime.suspendTimer !== null) return
  runtime.suspendTimer = setTimeout(() => {
    runtime.suspendTimer = null
    if (!state.focused && state.suspendOnBlur) setBlurSuspended(true)
  }, SUSPEND_DELAY_MS)
}

/* ── Configuration (pushed by the settings store) ───────────────── */

export function configureRenderGovernor({ fpsCap, renderScale, suspendOnBlur } = {}) {
  if (fpsCap !== undefined) state.fpsCap = Number(fpsCap) === 30 ? 30 : 60

  if (renderScale !== undefined) {
    const mode = renderScale === '720p' || renderScale === '1080p' ? renderScale : 'native'
    if (mode !== state.scaleMode) {
      state.scaleMode = mode
      notify(qualityListeners, mode)
    }
  }

  if (suspendOnBlur !== undefined) {
    const enabled = suspendOnBlur !== false
    if (enabled !== state.suspendOnBlur) {
      state.suspendOnBlur = enabled
      // Turning it off must wake a suspended app immediately; turning it on while
      // already unfocused must start the 30 s countdown now, not on the next blur.
      reconcileSuspend()
    }
  }
}

/* ── Frame rate ─────────────────────────────────────────────────── */

export function getFrameCap() {
  return state.fpsCap
}

/**
 * Per-loop gate. Each loop keeps its own clock so two renderers do not steal
 * frames from each other.
 */
export function createFrameGate(capFn = getFrameCap) {
  let last = -Infinity
  return {
    shouldDraw(now) {
      const interval = 1000 / Math.max(1, Number(capFn()) || 60)
      const elapsed = now - last
      // 1 ms of slack: display timestamps jitter, and a frame that is a hair
      // early must not be pushed to the next refresh.
      if (elapsed < interval - 1) return false
      // Advance along the ideal schedule by whole intervals (rounded, so float
      // error cannot leave `last` a full interval behind and let two frames
      // through). After the first frame or a long stall, restart from "now"
      // instead of bursting to catch up.
      const steps = Math.round(elapsed / interval)
      last = steps > 4 ? now : last + Math.max(1, steps) * interval
      return true
    },
    reset() {
      last = -Infinity
    }
  }
}

/* ── Render resolution ──────────────────────────────────────────── */

const currentEnv = () => ({
  innerHeight: typeof window !== 'undefined' ? window.innerHeight : 0,
  devicePixelRatio: typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1
})

/** 0 < factor ≤ 1. The multiplier every renderer applies to its own pixel ratio. */
export function renderScaleFactor(env = currentEnv()) {
  const target = TARGET_HEIGHT[state.scaleMode]
  if (!target) return 1
  const physical = Number(env.innerHeight) * (Number(env.devicePixelRatio) || 1)
  if (!(physical > 0)) return 1
  return Math.min(1, target / physical)
}

/**
 * Pixel ratio for a renderer whose own ceiling is `cap` (most use 1.5 or 2).
 * Never above the renderer's native ratio and never below 0.5, where a canvas
 * stops being legible.
 */
export function scaledPixelRatio(cap = 2, env = currentEnv()) {
  const native = Math.min(Number(env.devicePixelRatio) || 1, cap)
  return Math.min(native, Math.max(MIN_PIXEL_RATIO, native * renderScaleFactor(env)))
}

/** What the current choice actually does on this window — shown in Settings. */
export function describeRenderScale(env = currentEnv()) {
  const physicalHeight = Math.round(Number(env.innerHeight) * (Number(env.devicePixelRatio) || 1))
  const factor = renderScaleFactor(env)
  return {
    mode: state.scaleMode,
    factor,
    physicalHeight,
    renderedHeight: Math.round(physicalHeight * factor),
    // True when the chosen tier changes nothing on this window (already smaller).
    sameAsNative: factor === 1
  }
}

/** Fires when the mode changes or the window is resized. Returns an unsubscribe. */
export function onRenderQualityChange(listener) {
  qualityListeners.add(listener)
  return () => qualityListeners.delete(listener)
}

/* ── Suspend ────────────────────────────────────────────────────── */

export function isRenderSuspended() {
  return state.suspended
}

/** Read-only runtime status for diagnostics; no mutation or synthetic frame counts. */
export function getRenderStatus() {
  return { ...state }
}

export function onRenderSuspendChange(listener) {
  suspendListeners.add(listener)
  return () => suspendListeners.delete(listener)
}

export function setWindowFocused(focused) {
  const next = !!focused
  if (state.focused === next) return
  state.focused = next
  reconcileSuspend()
}

/** Visibility is separate from focus: minimised windows always stop drawing. */
export function setDocumentHidden(hidden) {
  state.hidden = !!hidden
  setSuspended(state.hidden || state.blurSuspended)
}

/* ── Scheduling ─────────────────────────────────────────────────── */

/**
 * requestAnimationFrame that idles while suspended.
 *
 * A suspended loop that keeps calling rAF still wakes the renderer 60 times a
 * second to do nothing. Here it falls back to a 500 ms timer, so the "suspend"
 * switch saves the work it claims to save, and resuming takes at most one poll.
 */
export function requestFrame(callback) {
  if (state.suspended) {
    return { timer: setTimeout(() => callback(performance.now()), SUSPEND_POLL_MS) }
  }
  return { raf: requestAnimationFrame(callback) }
}

export function cancelFrame(token) {
  if (!token) return
  if ('timer' in token) clearTimeout(token.timer)
  else cancelAnimationFrame(token.raf)
}

/**
 * A capped, suspendable loop for renderers that just need "call me per frame".
 * `onResume` runs once before the first draw after a suspension so the caller
 * can discard the time that passed in the background instead of simulating it.
 */
export function createRenderLoop(callback, { onResume } = {}) {
  const gate = createFrameGate()
  let token = null
  let running = false
  let wasSuspended = false

  const tick = (timestamp) => {
    if (!running) return
    // Schedule first: a draw that throws must not kill the loop.
    token = requestFrame(tick)
    if (state.suspended) {
      wasSuspended = true
      return
    }
    if (wasSuspended) {
      wasSuspended = false
      gate.reset()
      onResume?.()
    }
    if (gate.shouldDraw(timestamp)) callback(timestamp)
  }

  return {
    start() {
      if (running) return
      running = true
      wasSuspended = false
      gate.reset()
      token = requestFrame(tick)
    },
    stop() {
      running = false
      cancelFrame(token)
      token = null
    },
    get isRunning() {
      return running
    }
  }
}

/* ── Wiring to the real window ──────────────────────────────────── */


/**
 * Start following the real window. Idempotent; returns a stop function.
 *
 * Electron reports focus from the main process, which is the authority there.
 * In a plain browser the window's own focus/blur events are used. Either way
 * the starting state is read once, so a renderer mounted while the window is
 * already in the background does not assume it is focused.
 */
export function startRenderGovernor() {
  if (runtime.stopTracking || typeof window === 'undefined') return runtime.stopTracking || (() => {})

  const disposers = []
  setWindowFocused(typeof document !== 'undefined' && typeof document.hasFocus === 'function' ? document.hasFocus() : true)
  setDocumentHidden(typeof document !== 'undefined' && document.hidden)

  if (window.electronAPI?.onWindowFocus) {
    let focusEvents = 0
    disposers.push(window.electronAPI.onWindowFocus((focused) => { focusEvents++; setWindowFocused(focused) }))
    // Subscribe before querying, and never overwrite a newer event with a stale reply.
    if (window.electronAPI.getWindowState) {
      Promise.resolve(window.electronAPI.getWindowState()).then((info) => {
        if (runtime.stopTracking && focusEvents === 0 && typeof info?.focused === 'boolean') setWindowFocused(info.focused)
      }).catch(() => {})
    }
  } else {
    const onFocus = () => setWindowFocused(true)
    const onBlur = () => setWindowFocused(false)
    window.addEventListener('focus', onFocus)
    window.addEventListener('blur', onBlur)
    disposers.push(() => {
      window.removeEventListener('focus', onFocus)
      window.removeEventListener('blur', onBlur)
    })
  }

  // The scale factor depends on the window's pixel height, so a resize (or a
  // move to a display with another density) changes every renderer's ratio.
  let resizeTimer = null
  const onResize = () => {
    if (resizeTimer !== null) clearTimeout(resizeTimer)
    resizeTimer = setTimeout(() => {
      resizeTimer = null
      notify(qualityListeners, state.scaleMode)
    }, 150)
  }
  const onVisibility = () => setDocumentHidden(document.hidden)
  document.addEventListener('visibilitychange', onVisibility)
  disposers.push(() => document.removeEventListener('visibilitychange', onVisibility))
  window.addEventListener('resize', onResize)
  disposers.push(() => {
    window.removeEventListener('resize', onResize)
    if (resizeTimer !== null) clearTimeout(resizeTimer)
  })

  runtime.stopTracking = () => {
    for (const dispose of disposers) {
      try {
        dispose?.()
      } catch {
        /* a listener that is already gone is not an error */
      }
    }
    clearSuspendTimer()
    runtime.stopTracking = null
  }
  return runtime.stopTracking
}

/** Test hook: back to a pristine module. */
export function __resetRenderGovernorForTests() {
  runtime.stopTracking?.()
  clearSuspendTimer()
  qualityListeners.clear()
  suspendListeners.clear()
  Object.assign(state, { fpsCap: 60, scaleMode: 'native', suspendOnBlur: true, focused: true, hidden: false, blurSuspended: false, suspended: false })
}

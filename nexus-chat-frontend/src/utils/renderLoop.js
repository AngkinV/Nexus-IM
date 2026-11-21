/**
 * A requestAnimationFrame loop with a frame-rate cap and a suspend flag.
 *
 * The 3D components used to call requestAnimationFrame directly, which always
 * runs at the display's refresh rate. Settings → 渲染帧率上限 needs to hold the
 * loop at 30 FPS without rewriting each component's draw logic, so this wraps
 * the same pattern once.
 *
 *   const loop = createRenderLoop(draw, {
 *     fpsCap: () => settings.fpsCap,
 *     suspended: () => isSuspended.value
 *   })
 *   loop.start()
 *
 * While suspended the loop keeps a rAF pending but skips the callback, so
 * resuming is instant and does not rebuild any WebGL state.
 */
export function createRenderLoop(callback, options = {}) {
  const {
    fpsCap = () => 60,
    suspended = () => false,
    now = () => performance.now()
  } = options

  let handle = null
  let lastRun = 0
  let running = false

  const tick = (timestamp) => {
    if (!running) return
    const cap = Math.max(1, Number(fpsCap()) || 60)
    const interval = 1000 / cap
    const elapsed = timestamp - lastRun

    if (elapsed >= interval) {
      // Advance by whole intervals so a 30 FPS cap does not drift to 29.9.
      lastRun = timestamp - (elapsed % interval)
      if (!suspended()) callback(timestamp)
    }

    handle = requestAnimationFrame(tick)
  }

  return {
    start() {
      if (running) return
      running = true
      lastRun = 0
      handle = requestAnimationFrame(tick)
    },
    stop() {
      running = false
      if (handle !== null) {
        cancelAnimationFrame(handle)
        handle = null
      }
    },
    get isRunning() {
      return running
    }
  }
}

/** Choose a pixel ratio for the canvas, honouring the render-scale setting. */
export function resolvePixelRatio(mode, canvasHeight, devicePixelRatio = window.devicePixelRatio || 1) {
  const native = Math.min(devicePixelRatio, 2)
  if (mode !== '1080p' && mode !== '720p') return native
  const target = mode === '1080p' ? 1080 : 720
  if (!canvasHeight || canvasHeight <= 0) return native
  // Never upscale past the display's own density: a 720p target on a small
  // canvas would otherwise blur into mush.
  return Math.min(native, Math.max(0.5, target / canvasHeight))
}

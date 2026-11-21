/**
 * A shared frame-rate gate for the plain-JS renderers.
 *
 * The Vue components can read the settings store directly, but these modules are
 * instantiated from plain functions and should not depend on Pinia. They read a
 * module-level mirror instead, which the settings store keeps in sync.
 *
 *   const gate = createFrameGate()
 *   const animate = (now) => {
 *     raf = requestAnimationFrame(animate)
 *     if (!gate.shouldDraw(now)) return
 *     draw()
 *   }
 */

let fpsCap = 60

/** Called by the settings store whenever the cap changes. */
export function setFrameCap(value) {
  const n = Number(value)
  fpsCap = n === 30 ? 30 : 60
}

export function getFrameCap() {
  return fpsCap
}

/**
 * Creates a per-loop gate. Each loop keeps its own clock so two independent
 * renderers do not steal frames from each other.
 */
export function createFrameGate() {
  let last = 0

  return {
    /**
     * True when this frame should be drawn. Advances by whole intervals so a
     * 30 FPS cap does not drift into 29.9.
     */
    shouldDraw(now) {
      const interval = 1000 / fpsCap
      const elapsed = now - last
      if (elapsed < interval - 1) return false
      last = now - (elapsed % interval)
      return true
    },
    reset() {
      last = 0
    }
  }
}

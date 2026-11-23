export const ENTRANCE_TIMING = Object.freeze({ paper: .25, impact: .72, veins: 1.35, mountains: 2.75, gray: 4.4, color: 5.1, colored: 6.3, crumple: 6.8, end: 8.7 })
export const smooth = (a, b, v) => { const t = Math.max(0, Math.min(1, (v - a) / (b - a))); return t * t * (3 - 2 * t) }
export function entrancePhase(t) {
  const b = [.25, .72, 1.35, 2.75, 4.4, 5.1, 6.3, 6.8, 8.7]
  return ['paper', 'drop', 'wet', 'spread', 'mountains', 'gray', 'color', 'rest', 'crumple', 'done'][b.findIndex(v => t < v) < 0 ? 9 : b.findIndex(v => t < v)]
}
/** One transport: waiting and background time never reach either animation or audio. */
export function createEntranceClock() {
  let last = null, seconds = 0
  return { reset(t = 0) { seconds = t; last = null }, tick(now, active) {
    if (!active) { last = null; return seconds }
    if (last !== null) seconds += Math.max(0, (now - last) / 1000)
    last = now
    return seconds
  }, get seconds() { return seconds } }
}
export function inkOrigin(width, height, pointerX = width * .57) {
  return { x: width * .57 + Math.max(-40, Math.min(40, pointerX - width * .57)), y: height * .64 }
}


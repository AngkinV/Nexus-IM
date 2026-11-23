/**
 * Small reactions of the head and torso: a nod, a shake, a glance at the window.
 * They never move an arm, so they cannot push a hand through anything, and they
 * add to whatever posture the mood and the current gesture already hold.
 *
 * Angles are radians. pitch: + looks down. yaw: + turns to her left.
 */
const smooth = value => { const x = Math.max(0, Math.min(1, value)); return x * x * (3 - 2 * x) }
const bell = value => Math.sin(Math.PI * Math.max(0, Math.min(1, value)))
/** Rise until `up`, hold, fall from `down` to the end. */
const plateau = (t, up, down) => smooth(t / up) * (1 - smooth((t - down) / (1 - down)))

export const BEATS = {
  nod: { duration: 1.05, gap: 1.6, run(t, side, out) { out.pitch = 0.15 * Math.sin(t * Math.PI * 4) * (1 - t) ** 1.2 } },
  ack: { duration: 0.6, gap: 1.2, run(t, side, out) { out.pitch = 0.1 * bell(t) ** 2 } },
  shake: { duration: 1.15, gap: 2.4, run(t, side, out) { out.yaw = 0.16 * Math.sin(t * Math.PI * 4) * (1 - t * 0.6); out.pitch = 0.03 * bell(t) } },
  tilt: { duration: 1.8, gap: 4, run(t, side, out) { const hold = plateau(t, 0.22, 0.7); out.roll = side * 0.13 * hold; out.yaw = side * 0.04 * hold } },
  laugh: { duration: 1.25, gap: 3, run(t, side, out) {
    const fade = 1 - t
    // Eased in, so the first shake of the shoulders is not a jerk.
    out.shoulders = 0.55 * Math.abs(Math.sin(t * Math.PI * 5)) * fade * smooth(t / 0.15)
    out.pitch = -0.06 * bell(t) + 0.03 * Math.sin(t * Math.PI * 10) * fade
    out.lean = -0.02 * bell(t)
    const hold = plateau(t, 0.15, 0.75)
    out.accent.eyeJoy = 0.34 * hold; out.accent.mouthJoy = 0.28 * hold; out.accent.browJoy = 0.2 * hold
  } },
  smile: { duration: 2, gap: 4, run(t, side, out) {
    const hold = plateau(t, 0.2, 0.7)
    out.roll = side * 0.04 * hold
    out.accent.eyeJoy = 0.22 * hold; out.accent.mouthJoy = 0.22 * hold
  } },
  sigh: { duration: 2.2, gap: 6, run(t, side, out) {
    const inhale = bell(t / 0.36) * (t < 0.36 ? 1 : 0)
    const exhale = t < 0.36 ? 0 : plateau((t - 0.36) / 0.64, 0.3, 0.55)
    out.lean = -0.03 * inhale + 0.04 * exhale
    out.pitch = -0.03 * inhale + 0.09 * exhale
    out.shoulders = 0.35 * inhale - 0.4 * exhale
    out.blink = 0.55 * exhale
  } },
  lookDown: { duration: 1.9, gap: 4, run(t, side, out) { const hold = plateau(t, 0.25, 0.7); out.pitch = 0.16 * hold; out.gaze = 'down'; out.gazeWeight = hold } },
  lookAway: { duration: 2.6, gap: 6, run(t, side, out) { const hold = plateau(t, 0.2, 0.72); out.yaw = side * 0.26 * hold; out.gaze = 'scene'; out.gazeWeight = 0.9 * hold } },
  glanceCup: { duration: 1.4, gap: 5, run(t, side, out) { const hold = plateau(t, 0.2, 0.65); out.pitch = 0.06 * hold; out.gaze = 'cup'; out.gazeWeight = 0.85 * hold } },
  leanIn: { duration: 2.4, gap: 4, run(t, side, out) { const hold = plateau(t, 0.3, 0.75); out.lean = 0.1 * hold; out.pitch = -0.02 * hold } },
  leanBack: { duration: 2.2, gap: 4, run(t, side, out) { const hold = plateau(t, 0.3, 0.72); out.lean = -0.08 * hold; out.pitch = -0.03 * hold } },
  perk: { duration: 1, gap: 3, run(t, side, out) {
    out.pitch = -0.07 * plateau(t, 0.12, 0.5)
    out.blink = Math.max(bell((t - 0.14) / 0.14), bell((t - 0.4) / 0.14))
    out.accent.browSurprised = 0.25 * plateau(t, 0.1, 0.55)
  } },
  settle: { duration: 2.6, gap: 8, run(t, side, out) { const hold = plateau(t, 0.35, 0.6); out.sway = side * 0.03 * hold; out.roll = -side * 0.025 * hold } }
}
export const BEAT_NAMES = Object.keys(BEATS)

const CHANNELS = ['pitch', 'yaw', 'roll', 'lean', 'sway', 'shoulders', 'blink']
const blank = () => ({ pitch: 0, yaw: 0, roll: 0, lean: 0, sway: 0, shoulders: 0, blink: 0, gaze: null, gazeWeight: 0, accent: {} })

export function createTavernBeats({ random = Math.random } = {}) {
  let clock = 0
  let current = null
  let fading = null
  const lastAt = new Map()
  const stats = { played: 0, skipped: 0 }
  const out = blank()
  const scratch = blank()

  const reset = () => { current = null; fading = null }
  const clear = target => {
    for (const name of CHANNELS) target[name] = 0
    target.gaze = null; target.gazeWeight = 0
    for (const name of Object.keys(target.accent)) target.accent[name] = 0
  }

  return {
    /** side: +1 her left, -1 her right. Omitted: picked at random. */
    play(name, { strength = 1, side, force = false } = {}) {
      const beat = BEATS[name]
      if (!beat) { stats.skipped++; return false }
      const last = lastAt.get(name)
      if (!force && last != null && clock - last < beat.gap) { stats.skipped++; return false }
      // A beat that has barely begun is not cut off by another.
      if (!force && current && current.age < current.beat.duration * 0.35) { stats.skipped++; return false }
      if (current) fading = { ...current, fade: 1 }
      current = { name, beat, age: 0, strength: Math.max(0, Math.min(1.5, strength)), side: side === 1 || side === -1 ? side : random() < 0.5 ? -1 : 1 }
      lastAt.set(name, clock)
      stats.played++
      return true
    },
    update(delta = 0, reducedMotion = false) {
      delta = Number.isFinite(delta) ? Math.max(0, Math.min(0.5, delta)) : 0
      clock += delta
      clear(out)
      if (reducedMotion) { reset(); return out }
      for (const layer of [fading, current]) {
        if (!layer) continue
        layer.age += delta
        const t = layer.age / layer.beat.duration
        if (layer === fading) layer.fade = Math.max(0, layer.fade - delta / 0.25)
        if (t >= 1 || (layer === fading && layer.fade <= 0)) {
          if (layer === fading) fading = null
          else current = null
          continue
        }
        clear(scratch)
        layer.beat.run(t, layer.side, scratch)
        const weight = layer.strength * (layer === fading ? layer.fade : 1)
        for (const name of CHANNELS) out[name] += scratch[name] * weight
        for (const [name, value] of Object.entries(scratch.accent)) out.accent[name] = Math.max(out.accent[name] || 0, value * weight)
        if (scratch.gaze && scratch.gazeWeight * weight > out.gazeWeight) { out.gaze = scratch.gaze; out.gazeWeight = Math.min(1, scratch.gazeWeight * weight) }
      }
      out.blink = Math.max(0, Math.min(1, out.blink))
      return out
    },
    reset,
    active() { return current?.name || null },
    snapshot() { return { active: current?.name || null, age: current?.age ?? 0, fading: fading?.name || null, stats: { ...stats } } }
  }
}

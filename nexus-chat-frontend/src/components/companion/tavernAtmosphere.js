const BASE = {
  'rain-fireplace': { key: 0.86, ambient: 0.7, rim: 0.36, line: 0.32, window: 0.2, hearth: 0.6, dust: 0.28, lampSwing: 0, vignette: 0.12 },
  'late-rain': { key: 0.64, ambient: 0.58, rim: 0.31, line: 0.25, window: 0.15, hearth: 0, dust: 0.16, lampSwing: 0, vignette: 0.18 },
  'empty-bar': { key: 0.92, ambient: 0.76, rim: 0.25, line: 0.35, window: 0, hearth: 0, dust: 0.22, lampSwing: 0, vignette: 0.08 },
  'closing-time': { key: 0.66, ambient: 0.55, rim: 0.24, line: 0.25, window: 0, hearth: 0, dust: 0.17, lampSwing: 0.025, vignette: 0.16 }
}
const BARE = { key: 0.8, ambient: 0.7, rim: 0.28, line: 0.31, window: 0, hearth: 0, dust: 0.22, lampSwing: 0, vignette: 0.1 }
// Visual bias only. It never changes weather, scene, cup temperature or time of day.
const MOOD_BIAS = {
  calm: 0, warm: 0.04, happy: 0.06, shy: -0.02, sad: -0.06,
  worried: -0.03, angry: 0.025, surprised: 0.035, tired: -0.05, thoughtful: -0.015
}
export const ATMOSPHERE_LIMITS = { key: [0.48, 1.05], ambient: [0.45, 0.85], rim: [0.18, 0.45],
  line: [0.18, 0.42], window: [0, 0.27], hearth: [0, 0.76], dust: [0.12, 0.32],
  lampSwing: [0, 0.035], vignette: [0.04, 0.23] }
const limit = (name, value) => Math.max(ATMOSPHERE_LIMITS[name][0], Math.min(ATMOSPHERE_LIMITS[name][1], value))

export function createTavernAtmosphere() {
  let sceneKey = null, mood = null, elapsed = 0
  let base = BARE, parameters = { ...BARE }
  return {
    setScene(key) {
      if (sceneKey === key) return false
      sceneKey = key || null; base = BASE[key] || BARE
      parameters = { ...base }; elapsed = 0
      return true
    },
    setMood(value) { if (Object.hasOwn(MOOD_BIAS, value)) mood = value },
    clearMood() { mood = null },
    update(delta = 0, { reducedMotion = false, settled = false } = {}) {
      delta = Number.isFinite(delta) ? Math.max(0, Math.min(0.1, delta)) : 0
      if (!reducedMotion) elapsed += delta
      const bias = MOOD_BIAS[mood] || 0
      const goals = { ...base, key: base.key * (1 + bias), ambient: base.ambient * (1 + bias * 0.4),
        line: base.line * (1 + bias * 0.5), vignette: base.vignette - bias * 0.35 }
      const ease = reducedMotion || settled ? 1 : 1 - Math.exp(-delta / 1.15)
      for (const name of Object.keys(parameters)) parameters[name] += (limit(name, goals[name]) - parameters[name]) * ease
      const flicker = reducedMotion || !base.hearth ? 0
        : (Math.sin(elapsed * 7.3) * 0.45 + Math.sin(elapsed * 12.1) * 0.2 + Math.sin(elapsed * 2.9) * 0.35) * 0.055
      return { ...parameters, elapsed, flicker,
        hearthLight: base.hearth ? Math.max(0, parameters.hearth + flicker) : 0,
        lampRotation: reducedMotion ? 0 : Math.sin(elapsed * 0.6) * parameters.lampSwing }
    },
    snapshot() { return { scene: sceneKey, mood, elapsed, parameters: { ...parameters }, limits: ATMOSPHERE_LIMITS } }
  }
}

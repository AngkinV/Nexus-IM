import { createVrmExpressions } from './vrmExpressions.js'
import { registerTavernProfile } from './tavernModelProfile.js'

export const VOWELS = ['aa', 'ih', 'ou', 'ee', 'oh']
const EMOTIONS = ['happy', 'relaxed', 'sad', 'angry', 'surprised']
const PRESETS = {
  calm: { relaxed: 0.12 }, warm: { relaxed: 0.16, happy: 0.25 }, happy: { happy: 0.52 },
  shy: { happy: 0.16, relaxed: 0.12 }, sad: { sad: 0.48 }, worried: { sad: 0.24, surprised: 0.09 },
  angry: { angry: 0.46 }, surprised: { surprised: 0.5 }, tired: { relaxed: 0.14, sad: 0.12 },
  thoughtful: { relaxed: 0.1 }
}
// Candidate weights are deliberately conservative; no fabricated left/right smile.
export const REGIONAL_MOODS = {
  calm: { browFun: 0.08, eyeFun: 0.04, mouthFun: 0.06 },
  warm: { browFun: 0.14, eyeJoy: 0.12, mouthJoy: 0.17 },
  happy: { browJoy: 0.4, eyeJoy: 0.26, mouthJoy: 0.3 },
  shy: { browSad: 0.13, eyeJoy: 0.09, mouthSmall: 0.12, mouthJoy: 0.08 },
  sad: { browSad: 0.48, eyeSad: 0.3, mouthSad: 0.2 },
  worried: { browSad: 0.33, browSurprised: 0.09, eyeSad: 0.13, eyeSurprised: 0.08, mouthSmall: 0.12 },
  angry: { browAngry: 0.47, eyeAngry: 0.26, mouthAngry: 0.22 },
  surprised: { browSurprised: 0.45, eyeSurprised: 0.32, mouthSurprised: 0.24 },
  tired: { browSad: 0.15, eyeFun: 0.18, eyeSad: 0.08, mouthSmall: 0.1 },
  thoughtful: { browAngry: 0.1, browSad: 0.06, eyeFun: 0.08, mouthSmall: 0.1 }
}
export const REGION_BUDGETS = { brow: 0.55, eye: 0.42, mouth: 0.32, vowels: 0.48 }
const clamp = value => Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0
const blend = (from, to, delta, seconds, settled) => settled ? to : from + (to - from) * (1 - Math.exp(-delta / seconds))

export function limitRegion(values, prefix, budget) {
  const keys = Object.keys(values).filter(name => name.startsWith(prefix))
  const sum = keys.reduce((total, name) => total + clamp(values[name]), 0)
  const factor = sum > budget ? budget / sum : 1
  for (const name of keys) values[name] = clamp(values[name]) * factor
  return values
}

export function createTavernExpression(vrm, json) {
  const profile = registerTavernProfile(vrm, json)
  const adapter = createVrmExpressions(vrm, json)
  const manager = vrm.expressionManager
  const owned = new Set([...Object.values(profile.channels), ...Object.values(adapter.snapshot().bindings).flat().map(([name]) => name)])
  const regional = Object.fromEntries(Object.keys(profile.channels).map(name => [name, 0]))
  const preset = Object.fromEntries(EMOTIONS.map(name => [name, 0]))
  const vowels = Object.fromEntries(VOWELS.map(name => [name, 0]))
  let weights = {}, lastMood = null, blinkWeight = 0

  function clearManager() {
    for (const name of owned) manager?.setValue(name, 0)
  }
  function reset() {
    for (const state of [regional, preset, vowels]) for (const name of Object.keys(state)) state[name] = 0
    for (const name of [...EMOTIONS, ...VOWELS, 'blink']) adapter.setValue(name, 0)
    adapter.update()
    clearManager()
    weights = {}; lastMood = null; blinkWeight = 0
  }
  return {
    profile,
    update({ mood, delta = 0, articulation = {}, blink = 0, reducedMotion = false, settled = false, blockMouth = false } = {}) {
      delta = Math.max(0, Math.min(0.1, Number.isFinite(delta) ? delta : 0))
      settled ||= reducedMotion
      lastMood = Object.hasOwn(REGIONAL_MOODS, mood) ? mood : null
      const recipe = { ...REGIONAL_MOODS[lastMood || 'calm'] }
      if (lastMood === 'shy' && profile.channels.eyeJoyLeft && profile.channels.eyeJoyRight) {
        recipe.eyeJoy = 0; recipe.eyeJoyLeft = 0.1; recipe.eyeJoyRight = 0.08
      }
      blinkWeight = reducedMotion ? 0 : clamp(blink)
      const open = reducedMotion || blockMouth ? 0 : Math.min(REGION_BUDGETS.vowels, clamp(articulation.open))
      for (const name of VOWELS) {
        const goal = articulation.mouth === name ? open : 0
        vowels[name] = blend(vowels[name], goal, delta, 0.032, settled || blockMouth)
      }
      limitRegion(vowels, '', REGION_BUDGETS.vowels)
      const vowelSum = Object.values(vowels).reduce((sum, value) => sum + value, 0)
      // Suppress emotion lips while talking; keep independent brow/eye expression.
      const emotionMouth = blockMouth ? 0 : Math.max(0, 1 - vowelSum / REGION_BUDGETS.vowels) ** 2
      clearManager()
      for (const name of EMOTIONS) adapter.setValue(name, 0)
      if (profile.fine) {
        for (const name of Object.keys(regional)) {
          const region = name.startsWith('brow') ? 'brow' : name.startsWith('eye') ? 'eye' : 'mouth'
          const seconds = region === 'brow' ? 0.22 : region === 'eye' ? 0.12 : 0.45
          const rapid = lastMood === 'surprised' || lastMood === 'angry' ? 0.65 : 1
          regional[name] = blend(regional[name], recipe[name] || 0, delta, seconds * rapid, settled)
        }
        const mixed = { ...regional }
        for (const [region, budget] of Object.entries(REGION_BUDGETS)) {
          if (region !== 'vowels') limitRegion(mixed, region, budget)
        }
        weights = {}
        for (const [name, value] of Object.entries(mixed)) {
          const weight = name.startsWith('eye') ? value * (1 - blinkWeight)
            : name.startsWith('mouth') ? value * emotionMouth : value
          weights[profile.channels[name]] = weight
        }
      } else {
        const recipe = PRESETS[lastMood || 'calm']
        for (const name of EMOTIONS) {
          preset[name] = blend(preset[name], recipe[name] || 0, delta, 0.28, settled)
          adapter.setValue(name, preset[name] * (1 - vowelSum * 1.5) * (1 - blinkWeight * 0.7))
        }
        weights = {}
      }
      for (const name of VOWELS) adapter.setValue(name, vowels[name])
      adapter.setValue('blink', blinkWeight)
      adapter.update()
      for (const [name, value] of Object.entries(weights)) manager?.setValue(name, clamp(value))
      // Snapshot the actual manager values, not an invented facial score.
      weights = Object.fromEntries([...owned].map(name => [name, manager?.getValue?.(name) ?? weights[name] ?? 0]))
      return { mouthOpen: vowelSum, mode: profile.fine ? 'regional' : 'preset' }
    },
    reset,
    stopMouth() {
      for (const name of VOWELS) { vowels[name] = 0; adapter.setValue(name, 0) }
      adapter.update()
    },
    snapshot() {
      return { mode: profile.fine ? 'regional' : 'preset', mood: lastMood,
        capabilities: profile.capabilities, morphTargets: profile.readMorphs(), regional: { ...regional }, vowels: { ...vowels },
        blink: blinkWeight, weights: Object.fromEntries([...owned].map(name => [name, manager?.getValue?.(name) ?? weights[name] ?? 0])), budgets: { ...REGION_BUDGETS } }
    }
  }
}

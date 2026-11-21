/**
 * UI sounds synthesised with the Web Audio API.
 *
 * No audio assets ship with the app, and none are needed: every cue here is two
 * oscillators with a short gain envelope. That keeps the bundle small and lets
 * the timbre stay monochrome, matching the interface.
 *
 * Browsers refuse to start an AudioContext before a user gesture, so the
 * context is created lazily on the first cue and resumed if suspended.
 */

let ctx = null

function getContext() {
  if (typeof window === 'undefined') return null
  const Ctor = window.AudioContext || window.webkitAudioContext
  if (!Ctor) return null
  if (!ctx) ctx = new Ctor()
  if (ctx.state === 'suspended') ctx.resume().catch(() => {})
  return ctx
}

/**
 * Play one short tone.
 *
 * @param {object} opts
 * @param {number} opts.from   start frequency in Hz
 * @param {number} opts.to     end frequency in Hz
 * @param {number} opts.duration seconds
 * @param {number} opts.gain   peak gain (0-1)
 * @param {number} opts.delay  seconds to wait before starting
 */
function tone({ from, to, duration = 0.12, gain = 0.05, delay = 0, type = 'sine' }) {
  const audio = getContext()
  if (!audio) return false

  const start = audio.currentTime + delay
  const osc = audio.createOscillator()
  const env = audio.createGain()

  osc.type = type
  osc.frequency.setValueAtTime(from, start)
  if (to !== from) osc.frequency.exponentialRampToValueAtTime(to, start + duration)

  env.gain.setValueAtTime(0, start)
  env.gain.linearRampToValueAtTime(gain, start + 0.01)
  env.gain.exponentialRampToValueAtTime(0.0001, start + duration)

  osc.connect(env)
  env.connect(audio.destination)
  osc.start(start)
  osc.stop(start + duration + 0.02)
  return true
}

/** Two rising notes — an incoming message. */
export function playMessageSound() {
  tone({ from: 880, to: 1046, duration: 0.1 })
  tone({ from: 1318, to: 1318, duration: 0.12, delay: 0.09, gain: 0.035 })
}

/** One short blip — the user sent something. */
export function playSendSound() {
  tone({ from: 660, to: 880, duration: 0.08, gain: 0.04 })
}

/** Two falling notes — something failed. */
export function playErrorSound() {
  tone({ from: 440, to: 330, duration: 0.12, gain: 0.05 })
  tone({ from: 330, to: 220, duration: 0.16, delay: 0.1, gain: 0.04 })
}

/** Whether the platform can play synthesised audio at all. */
export function isSoundSupported() {
  return typeof window !== 'undefined' && !!(window.AudioContext || window.webkitAudioContext)
}

/** Release the context (used by tests and on teardown). */
export function disposeAudio() {
  if (ctx) {
    ctx.close().catch(() => {})
    ctx = null
  }
}

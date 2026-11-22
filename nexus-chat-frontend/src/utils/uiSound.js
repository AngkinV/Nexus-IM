/**
 * UI sounds synthesised with the Web Audio API.
 *
 * Cues are driven by actual incoming, acknowledged-send and failed-send events,
 * plus the explicit preview button. No downloaded audio resource is needed.
 *
 * Browsers refuse to start an AudioContext before a user gesture, so the
 * context is created lazily on the first cue and resumed if suspended.
 */

let ctx = null

function getContext() {
  if (typeof window === 'undefined') return null
  const Ctor = window.AudioContext || window.webkitAudioContext
  if (!Ctor) return null
  try {
    if (!ctx) ctx = new Ctor()
    if (ctx.state === 'suspended') ctx.resume().catch(() => {})
    return ctx
  } catch { return null }
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
  if (!audio || audio.state === 'closed') return false

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
  osc.onended = () => { osc.disconnect(); env.disconnect() }
  return true
}

/** Two rising notes — an incoming message. */
export function playMessageSound() {
  tone({ from: 880, to: 1046, duration: 0.1 })
  tone({ from: 1318, to: 1318, duration: 0.12, delay: 0.09, gain: 0.035 })
}

/** Played only after the server acknowledges a sent message. */
export function playSendSound() { return tone({ from: 660, to: 880, duration: 0.08, gain: 0.025 }) }
/** Played for an actual failed send, never for a scripted/demo response. */
export function playErrorSound() { return tone({ from: 440, to: 220, duration: 0.16, gain: 0.035 }) }

/** Whether the platform can play synthesised audio at all. */
export function isSoundSupported() {
  return typeof window !== 'undefined' && !!(window.AudioContext || window.webkitAudioContext)
}

export function disposeAudio() {
  if (ctx) { ctx.close().catch(() => {}); ctx = null }
}

/** Call during a user gesture so later notification sounds are not blocked by autoplay. */
export function unlockAudio() { const audio = getContext(); return !!audio && audio.state !== 'closed' }

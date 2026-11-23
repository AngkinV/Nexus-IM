/**
 * When she reacts. Everything here is driven by what is really happening in the
 * conversation: the user typing, a message going out, the wait for the model,
 * the reply's own words, and the mood the model reported. Nothing invents
 * dialogue or state; quiet stretches only get the small idle life any seated
 * person has, picked to suit that mood.
 */
const IDLE = {
  calm: [['settle', 3], ['lookAway', 2], ['glanceCup', 2], ['tilt', 1]],
  warm: [['settle', 2], ['glanceCup', 2], ['smile', 2], ['tilt', 1], ['lookAway', 1]],
  happy: [['smile', 3], ['tilt', 2], ['settle', 2], ['glanceCup', 1]],
  shy: [['lookDown', 3], ['lookAway', 2], ['settle', 1], ['glanceCup', 1]],
  sad: [['lookDown', 3], ['sigh', 2], ['lookAway', 2]],
  worried: [['lookAway', 2], ['lookDown', 2], ['settle', 2]],
  angry: [['lookAway', 2], ['settle', 2]],
  surprised: [['tilt', 2], ['settle', 1], ['perk', 1]],
  tired: [['lookDown', 2], ['sigh', 2], ['settle', 2]],
  thoughtful: [['lookAway', 3], ['tilt', 2], ['glanceCup', 2]]
}
const WAITING = [['lookAway', 3], ['tilt', 2], ['lookDown', 2]]
// Moods in which absently tucking her hair would not contradict what she feels.
const HAIR_MOODS = new Set(['calm', 'warm', 'happy', 'shy', 'thoughtful'])
export const DIRECTOR_TIMING = { idleMin: 7, idleSpan: 8, spokenGap: 2.2, armGap: 40, armChance: 0.22, thoughtMin: 1.4, thoughtSpan: 1.2, thoughtRepeat: 5.5 }

function pick(pool, random) {
  const total = pool.reduce((sum, [, weight]) => sum + weight, 0)
  let at = random() * total
  for (const [name, weight] of pool) {
    at -= weight
    if (at < 0) return name
  }
  return pool[pool.length - 1][0]
}

export function createTavernDirector({ random = Math.random } = {}) {
  const T = DIRECTOR_TIMING
  let clock = 0
  let listening = false, waiting = false, speaking = false
  let mood = null
  let quiet = 0
  let nextIdle = T.idleMin + random() * T.idleSpan
  let waited = 0
  let nextThought = 0
  let lastSpoken = -Infinity
  let lastArm = -Infinity
  let lastIdle = null
  let tends = false
  const pending = []
  const stats = { cues: 0, idle: 0, spoken: 0, dropped: 0 }
  const posture = { lean: 0, attention: 0, thinking: 0 }
  const emit = cue => { pending.push(cue); stats.cues++ }
  const rest = () => { quiet = 0; nextIdle = T.idleMin + random() * T.idleSpan }

  return {
    /** The user is writing: she pays attention and stops fidgeting. */
    setListening(value) {
      value = !!value
      if (value === listening) return
      listening = value
      rest()
    },
    /** A message is on its way and the model has not answered yet. */
    setWaiting(value) {
      value = !!value
      if (value === waiting) return
      waiting = value
      rest()
      if (!waiting) return
      waited = 0
      nextThought = T.thoughtMin + random() * T.thoughtSpan
      if (random() < 0.6) emit({ kind: 'beat', name: 'ack' })
    },
    setSpeaking(value) {
      value = !!value
      if (value === speaking) return
      speaking = value
      rest()
    },
    setMood(value) { mood = Object.hasOwn(IDLE, value) ? value : null },
    /** Whether she is working the bar in this room, so an idle moment may go to the glass. */
    setTends(value) { tends = !!value },
    /** A cue found in the reply's spoken words. Rate-limited so she does not twitch. */
    spoken(name) {
      if (clock - lastSpoken < T.spokenGap) { stats.dropped++; return false }
      lastSpoken = clock
      stats.spoken++
      emit({ kind: 'beat', name })
      return true
    },
    /** Something visible just happened (a gesture, a narrated action): hold the idle life back. */
    touched() { rest() },
    update(delta = 0, { busy = false, reducedMotion = false } = {}) {
      delta = Number.isFinite(delta) ? Math.max(0, Math.min(0.5, delta)) : 0
      clock += delta
      const ease = 1 - Math.exp(-delta / 0.6)
      posture.attention += ((listening ? 1 : 0) - posture.attention) * ease
      posture.thinking += ((waiting && waited > 0.8 ? 1 : 0) - posture.thinking) * ease
      posture.lean = 0.05 * posture.attention
      if (reducedMotion) { pending.length = 0; return { cues: pending.splice(0), posture } }
      if (waiting) {
        waited += delta
        if (waited >= nextThought && !busy) {
          emit({ kind: 'beat', name: pick(WAITING, random) })
          nextThought = waited + T.thoughtRepeat + random() * 3
        }
      } else if (!listening && !speaking) {
        if (busy) quiet = Math.min(quiet, nextIdle - 2)
        else quiet += delta
        if (quiet >= nextIdle) {
          rest()
          const arm = [['sip', 2]]
          if (HAIR_MOODS.has(mood || 'calm')) arm.push(['tuck', 2])
          if (tends) arm.push(['wipe', 2])
          if (clock - lastArm > T.armGap && random() < T.armChance) {
            lastArm = clock
            emit({ kind: 'gesture', name: pick(arm, random) })
          } else {
            const pool = (IDLE[mood] || IDLE.calm).filter(([name]) => name !== lastIdle)
            lastIdle = pick(pool, random)
            emit({ kind: 'beat', name: lastIdle })
          }
          stats.idle++
        }
      }
      return { cues: pending.splice(0), posture }
    },
    reset() { pending.length = 0; listening = false; waiting = false; speaking = false; waited = 0; posture.attention = 0; posture.thinking = 0; posture.lean = 0; rest() },
    snapshot() { return { clock, listening, waiting, speaking, mood, tends, quiet, nextIdle, waited, posture: { ...posture }, stats: { ...stats } } }
  }
}

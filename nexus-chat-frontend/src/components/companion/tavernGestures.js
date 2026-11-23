export const GESTURE_NAMES = ['rest', 'chin', 'sip', 'wipe', 'wave', 'shrug', 'cover', 'cross', 'tuck']

// How far each shoulder rises (radians, [left, right]) at the top of a gesture.
// The arms themselves are placed by IK against the model's measured body.
const LIFT = {
  rest: [0, 0], chin: [0, 0.04], sip: [0, 0.06], wipe: [0.03, 0.03], wave: [0, 0.08],
  shrug: [0.2, 0.2], cover: [0, 0.06], cross: [0.03, 0.03], tuck: [0, 0.1]
}

const SEATS = [
  { name: 'angled', yaw: 0.22, lean: 0.05, x: -0.3, hipY: -1.78, thigh: -1.45, left: 'across', right: 'table' },
  { name: 'attentive', yaw: -0.16, lean: 0.1, x: 0.16, hipY: -1.72, thigh: -1.55, left: 'table', right: 'table' },
  { name: 'relaxed', yaw: 0.08, lean: -0.02, x: -0.08, hipY: -1.82, thigh: -1.38, left: 'across', right: 'table' }
]

/** Both hands rest on the counter: the right by the glass, the left flat or lying across in front of her. */
export function seatedPose(seed = 0) {
  return { ...SEATS[(seed >>> 0) % SEATS.length] }
}

const smoother = (x) => { const t = Math.max(0, Math.min(1, x)); return Math.max(0, Math.min(1, t * t * t * (t * (t * 6 - 15) + 10))) }

// [reach, hold, return] in seconds. Reaches are unhurried; a shrug is quick.
export const GESTURE_TIMING = {
  chin: [0.75, 2.6, 0.9], sip: [1.2, 0.95, 1.1], wipe: [1.4, 2.6, 1.3],
  wave: [0.5, 1.5, 0.65], shrug: [0.34, 0.7, 0.6], cover: [0.42, 1.3, 0.7],
  cross: [0.8, 3.2, 0.95], tuck: [0.6, 0.95, 0.7]
}
// The model's explicit state outranks her narration, which outranks idle life.
const PRIORITY = { idle: -1, motion: 0, doing: 0, narration: 1, state: 2 }
const TWO_HANDED = ['shrug', 'cross', 'wipe']

export function createTavernGestures({ now = () => performance.now() } = {}) {
  let active = 'rest', age = 0, weight = 0, phase = 'rest', source = null, turnId = null
  let returning = false, releaseAt = 0, releaseWeight = 0, reason = null
  const seen = new Set()
  const stats = { accepted: 0, deduplicated: 0, rejected: 0, expired: 0, cancelled: 0, completed: 0 }
  const lift = { left: 0, right: 0 }
  let roll = 0
  const reset = (why = 'reset') => {
    if (active !== 'rest') stats.cancelled++
    active = 'rest'; age = 0; weight = 0; phase = 'rest'; returning = false; source = null; reason = why
    lift.left = 0; lift.right = 0; roll = 0
  }
  const beginTurn = id => {
    if (id == null || String(id) === turnId) return
    // A gesture she is in the middle of belongs to her, not to the turn: the next
    // reply arriving lets it come back down instead of snapping away.
    if (active !== 'rest' && !returning) { stats.cancelled++; reason = 'turn-change'; returning = true; phase = 'returning'; releaseAt = age; releaseWeight = weight }
    turnId = String(id)
  }
  const cancel = (why = 'cancelled', { immediate = false } = {}) => {
    if (active === 'rest') return
    if (immediate || weight < 1e-5) { reset(why); return }
    if (returning) return
    stats.cancelled++; reason = why; returning = true; phase = 'returning'; releaseAt = age; releaseWeight = weight
  }
  return {
    beginTurn, cancel, reset,
    play(name, cue = {}) {
      if (!GESTURE_NAMES.includes(name)) { stats.rejected++; return false }
      if (name === 'rest') { cancel('rest', { immediate: cue.turnId == null && turnId == null }); return true }
      if (Number.isFinite(cue.receivedAt) && now() - cue.receivedAt > 3000) { stats.expired++; return false }
      if (cue.turnId != null) beginTurn(cue.turnId)
      const nextSource = cue.source || 'state'
      const scoped = cue.turnId ?? turnId ?? cue.cueId
      // Idle life is not part of a reply, so it is not deduplicated against one.
      const key = nextSource !== 'idle' && scoped != null ? String(scoped) + '|' + name : null
      if ((key && seen.has(key)) || (!key && active === name && age < 0.9)) { stats.deduplicated++; return false }
      if (active !== 'rest' && phase !== 'returning' && (PRIORITY[nextSource] ?? 0) < (PRIORITY[source] ?? 0)) {
        stats.rejected++; return false
      }
      // Idle life never interrupts anything she is already doing.
      if (nextSource === 'idle' && active !== 'rest') { stats.rejected++; return false }
      if (key) {
        seen.add(key)
        if (seen.size > 64) seen.delete(seen.values().next().value)
      }
      active = name; age = 0; weight = 0; phase = 'preparing'; source = nextSource
      returning = false; reason = null; stats.accepted++
      return true
    },
    update(delta = 0, motion = 'idle', reducedMotion = false) {
      delta = Number.isFinite(delta) ? Math.max(0, Math.min(0.5, delta)) : 0
      if (reducedMotion) reset('reduced-motion')
      age += reducedMotion ? 0 : delta
      let hold = 0
      if (active !== 'rest') {
        const [prepare, held, release] = GESTURE_TIMING[active]
        if (returning) {
          phase = 'returning'
          weight = releaseWeight * (1 - smoother((age - releaseAt) / release))
          if (age - releaseAt >= release) { active = 'rest'; weight = 0; phase = 'rest'; returning = false }
        } else if (age < prepare) { phase = 'preparing'; weight = smoother(age / prepare) }
        else if (age < prepare + held) { phase = 'holding'; weight = 1; hold = (age - prepare) / held }
        else if (age < prepare + held + release) { phase = 'returning'; weight = 1 - smoother((age - prepare - held) / release); hold = 1 }
        else { active = 'rest'; weight = 0; phase = 'rest'; stats.completed++ }
      }
      const [left, right] = LIFT[active] || LIFT.rest
      const ease = reducedMotion ? 1 : 1 - Math.exp(-delta / 0.1)
      lift.left += (left * weight - lift.left) * ease
      lift.right += (right * weight - lift.right) * ease
      // The wave itself: the wrist rolls side to side while the hand is up.
      roll += ((active === 'wave' ? Math.sin(age * 13) * 0.3 * weight : 0) - roll) * ease
      const owners = active === 'rest' ? [] : TWO_HANDED.includes(active) ? ['leftHand', 'rightHand'] : ['rightHand']
      return { active, weight, age, hold, phase, source, turnId, owners, lift, roll,
        blocksMouth: active === 'sip' && weight > 0.5 }
    },
    snapshot() {
      return { active, age, weight, phase, source, turnId, reason, lift: { ...lift }, roll,
        stats: { ...stats }, cachedCues: seen.size }
    }
  }
}

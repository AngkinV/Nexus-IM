export const GESTURE_NAMES = ['rest', 'chin', 'sip', 'wipe', 'wave', 'shrug', 'cover', 'cross', 'tuck']

const REST = {
  leftShoulder: [0, 0, 0.02], rightShoulder: [0, 0, -0.02],
  leftUpperArm: [-0.16, 0, 1.12], rightUpperArm: [-0.16, 0, -1.12],
  leftLowerArm: [-0.55, -0.12, 0.08], rightLowerArm: [-0.55, 0.12, -0.08],
  leftHand: [0, 0, 0.06], rightHand: [0, 0, -0.06]
}
const POSES = {
  rest: {},
  chin: { rightUpperArm: [-0.4, -0.45, -0.95], rightLowerArm: [-1.65, 0.4, -0.18], rightHand: [0.25, 0.2, -0.15] },
  sip: { rightUpperArm: [-0.5, -0.3, -0.95], rightLowerArm: [-1.85, 0.2, -0.2], rightHand: [-0.12, 0.1, -0.12] },
  wipe: { leftLowerArm: [-0.9, -0.2, 0.18], rightUpperArm: [-0.3, -0.35, -1.1], rightLowerArm: [-0.9, 0.45, -0.3] },
  wave: { rightUpperArm: [-0.3, 0, -0.35], rightLowerArm: [-2, 0.15, -0.3], rightHand: [0, 0.2, 0] },
  shrug: { leftShoulder: [0, 0, -0.12], rightShoulder: [0, 0, 0.12], leftUpperArm: [-0.25, 0, 0.9], rightUpperArm: [-0.25, 0, -0.9], leftLowerArm: [-1.1, -0.65, 0.25], rightLowerArm: [-1.1, 0.65, -0.25] },
  cover: { rightUpperArm: [-0.45, -0.45, -0.85], rightLowerArm: [-1.9, 0.5, -0.3], rightHand: [0.15, 0, -0.25] },
  cross: { leftUpperArm: [-0.25, 0.4, 1.05], rightUpperArm: [-0.3, -0.4, -1.05], leftLowerArm: [-1.25, -0.6, 0.45], rightLowerArm: [-1.25, 0.6, -0.45] },
  tuck: { rightUpperArm: [-0.3, 0.25, -0.25], rightLowerArm: [-2.2, 0.1, -0.3], rightHand: [0.2, 0, -0.2] }
}
const MOTION_POSES = {
  idle: {}, listen: { leftLowerArm: [-0.75, -0.15, 0.1], rightLowerArm: [-0.75, 0.15, -0.1] },
  think: POSES.chin, nod: {},
  lean_in: { leftUpperArm: [-0.32, 0, 1.05], rightUpperArm: [-0.32, 0, -1.05], leftLowerArm: [-0.9, -0.12, 0.1], rightLowerArm: [-0.9, 0.12, -0.1] },
  look_away: { rightLowerArm: [-0.35, 0.15, -0.06] }
}

const SEATS = [
  { name: 'angled', yaw: 0.26, lean: 0.06, x: -0.34, hipY: -1.78, thigh: -1.45, left: 'lap', right: 'table' },
  { name: 'attentive', yaw: -0.18, lean: 0.13, x: 0.2, hipY: -1.68, thigh: -1.55, left: 'table', right: 'lap' },
  { name: 'relaxed', yaw: 0.12, lean: -0.06, x: -0.08, hipY: -1.86, thigh: -1.38, left: 'lap', right: 'lap' }
]

export function seatedPose(seed = 0) {
  return { ...SEATS[(seed >>> 0) % SEATS.length] }
}

const smooth = (x) => x * x * (3 - 2 * x)

const TIMING = {
  chin: [0.55, 1.85, 0.75], sip: [0.9, 0.8, 0.85], wipe: [0.5, 1.75, 0.75],
  wave: [0.4, 1.25, 0.65], shrug: [0.4, 1, 0.65], cover: [0.42, 1, 0.7],
  cross: [0.55, 1.85, 0.75], tuck: [0.5, 0.9, 0.7]
}
const PRIORITY = { doing: 0, narration: 1, state: 2 }

export function createTavernGestures({ now = () => performance.now() } = {}) {
  let active = 'rest', age = 0, weight = 0, phase = 'rest', source = null, turnId = null
  let returning = false, releaseAt = 0, releaseWeight = 0, reason = null
  const seen = new Set()
  const stats = { accepted: 0, deduplicated: 0, rejected: 0, expired: 0, cancelled: 0 }
  const pose = Object.fromEntries(Object.entries(REST).map(([name, value]) => [name, [...value]]))
  const reset = (why = 'reset') => {
    if (active !== 'rest') stats.cancelled++
    active = 'rest'; age = 0; weight = 0; phase = 'rest'; returning = false; source = null; reason = why
    for (const [name, rest] of Object.entries(REST)) pose[name] = [...rest]
  }
  const beginTurn = id => {
    if (id == null || String(id) === turnId) return
    reset('turn-change')
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
      const scoped = cue.turnId ?? turnId ?? cue.cueId
      const key = scoped != null ? String(scoped) + '|' + name : null
      if ((key && seen.has(key)) || (!key && active === name && age < 0.9)) { stats.deduplicated++; return false }
      const nextSource = cue.source || 'state'
      if (active !== 'rest' && phase !== 'returning' && (PRIORITY[nextSource] ?? 0) < (PRIORITY[source] ?? 0)) {
        stats.rejected++; return false
      }
      if (key) {
        seen.add(key)
        if (seen.size > 64) seen.delete(seen.values().next().value)
      }
      active = name; age = 0; weight = 0; phase = 'preparing'; source = nextSource
      returning = false; reason = null; stats.accepted++
      return true
    },
    update(delta = 0, motion = 'idle', reducedMotion = false, { speaking = false } = {}) {
      delta = Number.isFinite(delta) ? Math.max(0, Math.min(0.5, delta)) : 0
      if (reducedMotion) reset('reduced-motion')
      age += reducedMotion ? 0 : delta
      if (active !== 'rest') {
        const [prepare, hold, release] = TIMING[active]
        if (returning) {
          phase = 'returning'
          weight = releaseWeight * (1 - smooth(Math.min(1, (age - releaseAt) / release)))
          if (age - releaseAt >= release) { active = 'rest'; weight = 0; phase = 'rest'; returning = false }
        } else if (age < prepare) { phase = 'preparing'; weight = smooth(age / prepare) }
        else if (age < prepare + hold) { phase = 'holding'; weight = 1 }
        else if (age < prepare + hold + release) { phase = 'returning'; weight = 1 - smooth((age - prepare - hold) / release) }
        else { active = 'rest'; weight = 0; phase = 'rest' }
        if (active === 'sip' && speaking && phase === 'holding' && age >= prepare + 0.15) cancel('speaking')
      }
      const base = reducedMotion ? {} : MOTION_POSES[motion] || MOTION_POSES.idle
      const gesture = POSES[active] || {}
      const ease = reducedMotion ? 1 : 1 - Math.exp(-delta / 0.1)
      for (const [bone, rest] of Object.entries(REST)) {
        const from = base[bone] || rest
        const to = gesture[bone] || from
        for (let axis = 0; axis < 3; axis++) {
          let target = from[axis] + (to[axis] - from[axis]) * weight
          if (active === 'wave' && bone === 'rightHand' && axis === 2) target += Math.sin(age * 11) * 0.22 * weight
          if (active === 'wipe' && bone === 'rightLowerArm' && axis === 1) target += Math.sin(age * 5) * 0.12 * weight
          pose[bone][axis] += (target - pose[bone][axis]) * ease
        }
      }
      const owners = active === 'rest' ? [] : ['shrug', 'cross', 'wipe'].includes(active) ? ['leftHand', 'rightHand'] : ['rightHand']
      return { pose, active, weight, age, phase, source, turnId, owners,
        blocksMouth: active === 'sip' && weight > 0.18 }
    },
    snapshot() {
      return { active, age, weight, phase, source, turnId, reason, pose: structuredClone(pose),
        stats: { ...stats }, cachedCues: seen.size }
    }
  }
}

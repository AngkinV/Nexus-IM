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

export function createTavernGestures() {
  let active = 'rest'
  let age = 0
  const pose = Object.fromEntries(Object.entries(REST).map(([name, value]) => [name, [...value]]))
  return {
    play(name) {
      if (!GESTURE_NAMES.includes(name) || (name !== 'rest' && name === active && age < 0.9)) return
      active = name
      age = 0
    },
    update(delta, motion = 'idle', reducedMotion = false) {
      if (reducedMotion) active = 'rest'
      age += delta
      const duration = ['chin', 'cross', 'wipe'].includes(active) ? 4 : 2.6
      const weight = active === 'rest' ? 0 : smooth(Math.min(1, age / 0.55)) * smooth(Math.max(0, Math.min(1, (duration - age) / 0.7)))
      if (age >= duration) active = 'rest'
      const base = MOTION_POSES[motion] || MOTION_POSES.idle
      const gesture = POSES[active]
      const ease = reducedMotion ? 1 : 1 - Math.exp(-delta * 10)
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
      return { pose, active, weight, age }
    },
    snapshot() { return { active, age, pose: structuredClone(pose) } }
  }
}

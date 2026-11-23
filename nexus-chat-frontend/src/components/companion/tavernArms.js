import * as THREE from 'three'
import { createLimbSolver, calibrateHand } from './tavernRig.js'
import { sipPath, GRIP } from './tavernCup.js'
import { COUNTER, counterFloor } from './tavernLayout.js'

/**
 * Both arms: where each wrist goes, how the hand is turned, and a constraint
 * pass that keeps every part of the hand and forearm out of her body and above
 * the counter. Contact is anatomical: a named point of the hand is laid on a
 * named point of her face or torso, measured from the model.
 */

/**
 * Hand shapes, written for the RIGHT hand in her own frame: +x toward her left
 * (inward for the right hand), +y up, +z toward the guest. Mirrored for the
 * left. finger: wrist to knuckles. palm: the way the palm faces.
 */
export const HAND_POSES = {
  table: { finger: [0.36, 0.12, 1], palm: [0, -1, 0.06], curl: 0.3 },
  lap: { finger: [0.8, -0.15, 0.3], palm: [0, -1, 0.1], curl: 0.3 },
  // Lying across in front of her, relaxed, instead of mirroring the other hand.
  across: { finger: [0.62, 0.06, 0.78], palm: [0, -1, 0.05], curl: 0.42 },
  // Her collar leaves no room for a fist under the chin, so the hand comes from
  // in front: fingers tilted back, their backs resting against her chin.
  chin: { finger: [0.28, 0.8, -0.55], palm: [0.15, -0.55, -0.8], curl: 0.62 },
  cover: { finger: [0.85, 0.5, 0.05], palm: [0.1, -0.1, -1], curl: 0.18 },
  tuck: { finger: [0.3, 0.62, -0.72], palm: [1, 0.1, 0.25], curl: 0.72 },
  wave: { finger: [-0.05, 1, 0.1], palm: [0, 0, 1], curl: 0.05 },
  shrug: { finger: [-0.3, 0.12, 1], palm: [0.25, 1, 0], curl: 0.3 },
  cross: { finger: [0.92, 0.1, -0.38], palm: [-0.38, 0, -0.92], curl: 0.5 },
  // Holding the glass: palm against its side, thumb side up.
  grip: { finger: [0.15, 0, 1], palm: [1, 0, 0], curl: 0.75 },
  // Raised to her lips: the hand stays at the side of the glass, so the glass is seen.
  drink: { finger: [0.5, 0.08, 0.86], palm: [0.86, 0, -0.5], curl: 0.75 },
  // The glass tipped toward the other hand while it is wiped.
  work: { finger: [0.13, -0.07, 1], palm: [0.88, -0.48, 0], curl: 0.75 },
  polish: { finger: [0.2, 0.45, 0.85], palm: [1, -0.3, 0], curl: 0.55 }
}

// contact: the point of the hand that touches, in palm lengths (along the
// fingers, toward the little finger, out of the palm).
// pole: where the elbow points. arc: a bump at mid-path, in torso lengths.
const RECIPES = {
  // The backs of her curled fingers come up under her chin.
  chin: { pose: 'chin', contact: [1.35, 0.1, 0.31], pole: [-0.3, -1, 0.3], arc: [0, 0, 0.14] },
  cover: { pose: 'cover', contact: [1.45, 0, 0.14], pole: [-0.3, -1, 0.3], arc: [0, 0, 0.1] },
  // Up in front of her shoulder first, then back to the hair.
  tuck: { pose: 'tuck', contact: [1.5, 0, 0.3], pole: [-0.55, -0.2, 0.8], arc: [0, 0, 0.32] },
  wave: { pose: 'wave', contact: [0, 0, 0], pole: [-0.35, -1, -0.1], arc: [0, 0, 0.08] },
  shrug: { pose: 'shrug', contact: [0, 0, 0], pole: [-0.45, -1, -0.35], arc: [0, 0, 0] },
  cross: { pose: 'cross', contact: [0, 0, 0], pole: [-0.7, -1, 0.15], arc: [0, 0, 0.2] }
}
// Elbows back beside her ribs, not flared out to the sides.
const REST_POLE = [-0.16, -0.35, -1]
const HOLD_POLE = [-0.35, -1, 0.1]
const USES = { chin: ['right'], cover: ['right'], tuck: ['right'], wave: ['right'], sip: ['right'],
  shrug: ['left', 'right'], cross: ['left', 'right'], wipe: ['left', 'right'] }
const SIDES = ['left', 'right']
const TORSO_ONLY = { head: false }, HEAD_ONLY = { torso: false }
const FINGER_SCALE = { Index: 0.9, Middle: 1, Ring: 1.06, Little: 1.12, Thumb: 1 }
const clamp = (value, min, max) => Math.max(min, Math.min(max, value))
const smooth = value => { const x = clamp(value, 0, 1); return x * x * (3 - 2 * x) }
/** The mid-path bump: zero at both ends, held back until the hand has risen, same peak as a plain sine. */
export const arcShape = (weight, rise = 1 - (1 - weight) * (1 - weight)) => Math.min(1, Math.sin(Math.PI * clamp(weight, 0, 1)) * rise / 0.8)
// Constraint passes per arm per frame. Most frames settle in two.
const PASSES = 6

/** Keeps, per axis, whichever correction is largest. */
export function extreme(total, delta, share = 1) {
  for (const axis of ['x', 'y', 'z']) {
    const value = delta[axis] * share
    if (Math.abs(value) > Math.abs(total[axis])) total[axis] = value
  }
  return total
}

/**
 * @param root   the scaled, placed actor root
 * @param bones  name -> { bone, rest, frame, inverseFrame } (normalized humanoid)
 * @param body   collision volumes from tavernBody.js
 * @param unit   hips-to-head distance in model units
 * @param scale  root scale
 * @param seat   { left: 'table'|'lap', right: 'table'|'lap' }
 * @param cup    { radius, height } in model units
 */
export function createTavernArms({ root, bones, body, unit, scale, seat, cup }) {
  const mirror = { left: -1, right: 1 }
  const outward = { left: 1, right: -1 }
  const k = unit * scale
  const rootRotation = new THREE.Quaternion()
  const a = new THREE.Vector3(), b = new THREE.Vector3(), c = new THREE.Vector3(), d = new THREE.Vector3()
  const anchor = new THREE.Vector3(), target = new THREE.Vector3(), pole = new THREE.Vector3()
  const wristPush = new THREE.Vector3(), elbowPush = new THREE.Vector3()
  const solveTarget = new THREE.Vector3(), solvePole = new THREE.Vector3(), basePole = new THREE.Vector3()
  const basis = new THREE.Matrix4()
  const qGoal = new THREE.Quaternion(), qStage = new THREE.Quaternion(), qWorld = new THREE.Quaternion()
  const qParent = new THREE.Quaternion(), qTurn = new THREE.Quaternion()
  const euler = new THREE.Euler()

  const hands = {}
  for (const side of SIDES) {
    const hand = bones.get(`${side}Hand`), upper = bones.get(`${side}UpperArm`), lower = bones.get(`${side}LowerArm`)
    if (!hand || !upper || !lower) continue
    const frame = calibrateHand(hand.bone, bones.get(`${side}IndexProximal`)?.bone,
      bones.get(`${side}MiddleProximal`)?.bone, bones.get(`${side}LittleProximal`)?.bone, side)
    if (!frame || !(frame.palmLength > 0)) continue
    const palm = frame.palmLength
    const local = frame.rotation.clone().invert()
    const axes = [new THREE.Vector3(1, 0, 0), new THREE.Vector3(0, 1, 0), new THREE.Vector3(0, 0, 1)].map(axis => axis.applyQuaternion(local))
    const fingers = [], probes = []
    for (const [name, entry] of bones) {
      const match = name.startsWith(side) && /(Index|Middle|Ring|Little|Thumb)(Metacarpal|Proximal|Intermediate|Distal)$/.exec(name)
      if (!match) continue
      const [, digit, joint] = match
      const axis = frame.curlAxis.clone().applyQuaternion(hand.frame).applyQuaternion(entry.inverseFrame)
      const factor = (digit === 'Thumb' ? 0.3 : joint === 'Proximal' ? 0.7 : joint === 'Distal' ? 0.6 : 1) * FINGER_SCALE[digit]
      fingers.push({ bone: entry.bone, rest: entry.rest, axis, factor })
      if (joint === 'Metacarpal') continue
      probes.push({ bone: entry.bone, radius: palm * (joint === 'Proximal' ? 0.2 : 0.17), thin: palm * 0.1 })
      // The fingertip lies beyond the last joint by about its own length.
      if (joint === 'Distal') {
        const before = bones.get(`${side}${digit}${digit === 'Thumb' ? 'Proximal' : 'Intermediate'}`)
        if (before) probes.push({ bone: entry.bone, from: before.bone, radius: palm * 0.15, thin: palm * 0.09 })
      }
    }
    // A hand is wide but flat: against her face only its thickness counts.
    probes.push({ bone: hand.bone, radius: palm * 0.34, thin: palm * 0.2 })
    probes.push({ bone: hand.bone, palm: 0.55, radius: palm * 0.36, thin: palm * 0.14 })
    for (const along of [0.25, 0.5, 0.78]) probes.push({ bone: lower.bone, to: hand.bone, along, radius: palm * (0.46 - along * 0.1) })
    probes.push({ bone: lower.bone, to: hand.bone, along: 0, radius: palm * 0.5 })
    const orientations = {}
    for (const [name, pose] of Object.entries(HAND_POSES)) {
      a.fromArray(pose.finger).normalize()
      b.fromArray(pose.palm)
      a.x *= mirror[side]; b.x *= mirror[side]
      b.addScaledVector(a, -b.dot(a)).normalize()
      c.crossVectors(b, a).normalize()
      basis.makeBasis(a, c, b)
      orientations[name] = new THREE.Quaternion().setFromRotationMatrix(basis).multiply(frame.rotation)
    }
    hands[side] = { side, frame, palm, axes, fingers, probes, orientations, bone: hand.bone, upper: upper.bone, lower: lower.bone,
      solver: createLimbSolver(upper.bone, lower.bone, hand.bone), target: new THREE.Vector3(), quat: new THREE.Quaternion(),
      pole: new THREE.Vector3(), offset: new THREE.Vector3(), swing: new THREE.Vector3(), curl: 0.3, ready: false, gate: 1, residual: 0, passes: 0, contact: null, passing: false, restTable: new THREE.Vector3() }
  }

  const cupTable = new THREE.Vector3(), clothTable = new THREE.Vector3(), workPoint = new THREE.Vector3()
  const palmLength = hands.right?.palm || hands.left?.palm || unit * 0.13
  const gripContact = [GRIP.along, 0, cup.radius / palmLength + GRIP.out]
  // The near edge of the rim, which is what meets her lips.
  const rimContact = [GRIP.along - cup.radius / palmLength, -(cup.height / 2) / palmLength, cup.radius / palmLength + GRIP.out]
  const palmContact = [0.9, 0, 0.24]
  let placed = false
  let worst = 0

  const shoulder = (side, out) => out.setFromMatrixPosition(hands[side].upper.matrixWorld)
  const rooted = (side, array, out) => out.set(array[0] * mirror[side], array[1], array[2]).applyQuaternion(rootRotation)

  /** Where the wrist must be for `contact` (palm lengths) of a hand turned to `orientation` to sit on `point`. */
  function contactWrist(side, orientation, point, contact, out) {
    const hand = hands[side]
    const size = hand.palm * scale
    qTurn.copy(rootRotation).multiply(orientation)
    out.copy(point)
    out.addScaledVector(a.copy(hand.axes[0]).applyQuaternion(qTurn), -contact[0] * size)
    out.addScaledVector(a.copy(hand.axes[1]).applyQuaternion(qTurn), -contact[1] * size * mirror[side])
    out.addScaledVector(a.copy(hand.axes[2]).applyQuaternion(qTurn), -contact[2] * size)
    return out
  }

  function lapWrist(side, out) {
    const leg = bones.get(`${side}UpperLeg`) || bones.get('hips')
    out.set(outward[side] * unit * 0.02, -unit * 0.12, unit * 0.2).applyQuaternion(leg.inverseFrame)
    leg.bone.localToWorld(out)
    out.z = Math.min(out.z, COUNTER.back - 0.34)
    return out
  }
  const restWrist = (side, out) => seat[side] === 'lap' ? lapWrist(side, out) : out.copy(hands[side].restTable)

  /** Fixes where her hands rest and where the glass and cloth stand, once she is seated. */
  function place() {
    placed = true
    for (const side of SIDES) {
      const hand = hands[side]
      if (!hand) continue
      shoulder(side, hand.restTable).add(rooted(side, seat[side] === 'across' ? [-0.06, 0, 0.3] : [0, 0, 0.4], a).multiplyScalar(k))
      hand.restTable.z = clamp(hand.restTable.z, COUNTER.back + 0.24, COUNTER.front - 0.74)
      hand.restTable.y = COUNTER.top + hand.palm * scale * 0.42
    }
    const radius = cup.radius * scale
    if (hands.right) cupTable.copy(hands.right.restTable).add(a.set(0.23 * k, 0, 0.12 * k).applyQuaternion(rootRotation))
    else cupTable.set(root.position.x, 0, COUNTER.back + 0.5)
    cupTable.z = clamp(cupTable.z, COUNTER.back + radius + 0.14, COUNTER.front - radius - 0.22)
    cupTable.y = COUNTER.top + cup.height * scale / 2 + 0.004
    if (hands.left) shoulder('left', clothTable).add(a.set(0.2 * k, 0, 0).applyQuaternion(rootRotation))
    else clothTable.set(root.position.x + 1, 0, 0)
    clothTable.z = COUNTER.back + 0.4
    clothTable.y = COUNTER.top + 0.012
    workPoint.set(cupTable.x + 0.02 * k, COUNTER.top + 0.27 * k, cupTable.z - 0.1 * k)
  }

  /** Full-weight wrist position for a one-stage gesture. */
  function gestureWrist(side, name, gesture, orientation, out) {
    const hand = hands[side]
    const marks = body.landmarks
    const recipe = RECIPES[name]
    const palm = hand.palm
    if (name === 'chin') {
      // Her chin settles on the backs of her curled fingers.
      body.headPoint(a.set(marks.chin.x - palm * 0.1, marks.chin.y + palm * 0.08, marks.chin.z + palm * 0.12), anchor)
    } else if (name === 'cover') {
      body.headPoint(a.set(marks.mouth.x, marks.mouth.y, marks.mouth.z + palm * 0.22), anchor)
    } else if (name === 'tuck') {
      // The fingertips draw the hair back past the ear while the gesture holds.
      const slide = smooth(gesture.hold)
      body.headPoint(a.set(marks.headCenter.x - marks.hairSide * 0.9,
        marks.earY + palm * (0.15 - 0.3 * slide), marks.headCenter.z + marks.headRadii.z * (0.4 - 0.5 * slide)), anchor)
    } else if (name === 'wave') {
      shoulder(side, anchor).add(rooted(side, [-0.36, 0.28, 0.46], a).multiplyScalar(k))
      anchor.add(rooted(side, [1, 0, 0], a).multiplyScalar(Math.sin(gesture.age * 6.5) * 0.045 * k))
    } else if (name === 'shrug') {
      // Elbows stay by her ribs; only the forearms turn out, palms up.
      shoulder(side, anchor).add(rooted(side, [-0.26, -0.44, 0.36], a).multiplyScalar(k))
    } else if (name === 'cross') {
      // The right forearm lies against her; the left lies over it.
      const inner = side === 'right'
      body.torsoPoint(-outward[side] * 0.5, inner ? 0.49 : 0.53, 1, anchor)
      anchor.add(a.set(0, 0, 1).applyQuaternion(rootRotation).multiplyScalar(palm * scale * (inner ? 0.62 : 1.45)))
    }
    return contactWrist(side, orientation, anchor, recipe.contact, out)
  }

  /** Reach, settle, then lift: the glass (and the cloth) are picked up from the counter. */
  function staged(side, name, weight, gesture, cupWorld, state) {
    const hand = hands[side]
    const { reach, lift } = sipPath(weight)
    let first, second, firstPose, secondPose
    if (side === 'right') {
      first = hand.orientations.grip; firstPose = HAND_POSES.grip
      contactWrist(side, first, cupTable, gripContact, b)
      if (name === 'sip') {
        // She tips the glass only once it is at her lips.
        const tilt = 0.45 * smooth((lift - 0.7) / 0.3)
        second = qStage.setFromEuler(euler.set(-tilt, 0, 0)).multiply(hand.orientations.drink); secondPose = HAND_POSES.drink
        const marks = body.landmarks
        body.headPoint(a.set(marks.mouth.x, marks.mouth.y - hand.palm * 0.1, marks.mouth.z + hand.palm * 0.12), anchor)
        contactWrist(side, second, anchor, rimContact, c)
      } else {
        second = hand.orientations.work; secondPose = HAND_POSES.work
        contactWrist(side, second, workPoint, gripContact, c)
      }
    } else {
      first = hand.orientations.table; firstPose = HAND_POSES.table
      contactWrist(side, first, a.copy(clothTable).setY(clothTable.y + 0.02), palmContact, b)
      second = hand.orientations.polish; secondPose = HAND_POSES.polish
      // Her palm, through the cloth, works around the upper side of the glass.
      const turn = gesture.age * 6
      anchor.copy(cupWorld || workPoint)
      anchor.add(rooted('right', [1, 0, 0], a).multiplyScalar(cup.radius * scale * 1.05))
      anchor.y += cup.height * scale * (0.12 + 0.2 * Math.sin(turn))
      anchor.add(rooted('right', [0, 0, 1], a).multiplyScalar(cup.radius * scale * 0.45 * Math.cos(turn)))
      contactWrist(side, second, anchor, palmContact, c)
    }
    target.lerp(b, reach)
    qGoal.slerp(first, reach)
    state.curl += (firstPose.curl - state.curl) * reach
    pole.lerp(a.fromArray(HOLD_POLE).setX(HOLD_POLE[0] * mirror[side]), reach)
    if (lift > 0) {
      target.lerp(c, lift)
      target.add(a.set(0, 0, 1).applyQuaternion(rootRotation).multiplyScalar(Math.sin(Math.PI * lift) * 0.05 * k))
      qGoal.slerp(second, lift)
      state.curl += (secondPose.curl - state.curl) * lift
    }
  }

  function poseFingers(hand) {
    for (const finger of hand.fingers) {
      qTurn.setFromAxisAngle(finger.axis, hand.curl * finger.factor)
      finger.bone.quaternion.copy(finger.rest).multiply(qTurn)
    }
  }

  function probePosition(probe, hand, out) {
    out.setFromMatrixPosition(probe.bone.matrixWorld)
    if (probe.from) out.addScaledVector(a.setFromMatrixPosition(probe.from.matrixWorld).sub(out), -0.9)
    else if (probe.to) out.lerp(a.setFromMatrixPosition(probe.to.matrixWorld), probe.along)
    else if (probe.palm) {
      hand.bone.getWorldQuaternion(qTurn)
      out.addScaledVector(a.copy(hand.axes[0]).applyQuaternion(qTurn), probe.palm * hand.palm * scale)
    }
    return out
  }

  /** How far the arm is inside the counter or her body, and which way out. */
  function measure(hand) {
    wristPush.set(0, 0, 0); elbowPush.set(0, 0, 0)
    let deepest = 0
    hand.contact = null
    for (const probe of hand.probes) {
      probePosition(probe, hand, c)
      d.copy(c)
      let touched = null
      const floor = counterFloor(d.z, probe.radius * scale * 0.5)
      if (d.y < floor) { d.y = floor; touched = 'counter' }
      // The glass standing on the counter is solid to a hand that is not reaching for it.
      if (hand.avoidCup) {
        const reach = (cup.radius + probe.radius * 0.5) * scale
        const dx = d.x - cupTable.x, dz = d.z - cupTable.z, flat = Math.hypot(dx, dz)
        const over = cupTable.y + cup.height * scale / 2 + probe.radius * scale * 0.5
        if (flat < reach && d.y < over) {
          touched = 'glass'
          // A hand on its way somewhere passes over the glass: whatever part is
          // above the middle of it is lifted clear. Pushed sideways instead, the
          // fingers only slid along the rim they were crossing and never got free.
          // A hand lying on the counter is beside the glass and is moved around it.
          if (d.y > cupTable.y && (hand.passing || over - d.y < reach - flat)) d.y = over
          else if (flat < 1e-5) d.x += reach * outward[hand.side]
          else { d.x = cupTable.x + dx / flat * reach; d.z = cupTable.z + dz / flat * reach }
        }
      }
      if (body.resolve(d, probe.radius, TORSO_ONLY) > 0) touched = 'torso'
      if (body.resolve(d, probe.thin ?? probe.radius, HEAD_ONLY) > 0) touched = 'head'
      d.sub(c)
      const depth = d.length()
      if (depth < 1e-6) continue
      // What the deepest part is against, for the stage's diagnostics.
      if (depth > deepest) hand.contact = `${touched}:${probe.along != null ? 'forearm' : probe.palm ? 'palm' : probe.bone === hand.bone ? 'wrist' : 'finger'}`
      deepest = Math.max(deepest, depth)
      if (probe.along == null) extreme(wristPush, d)
      else { extreme(wristPush, d, probe.along); extreme(elbowPush, d, 1 - probe.along) }
    }
    return deepest
  }

  function settle(hand) {
    // Start from most of last frame's correction, so contact is held steadily
    // instead of being rediscovered (and briefly missed) every frame.
    solveTarget.copy(hand.target).addScaledVector(hand.offset, 0.85)
    solvePole.copy(hand.pole).applyQuaternion(rootRotation).normalize()
    basePole.copy(solvePole)
    // The elbow's correction is kept the same way. Without it an elbow resting
    // against her ribs was found inside them again at the start of every frame.
    if (hand.swing.lengthSq() > 0) solvePole.addScaledVector(hand.swing, 0.85).normalize()
    poseFingers(hand)
    const tolerance = 0.004 * k
    let depth = 0, pass = 0
    for (; pass < PASSES; pass++) {
      hand.solver.solve(solveTarget, solvePole)
      hand.bone.parent.getWorldQuaternion(qParent)
      hand.bone.quaternion.copy(qParent.invert().multiply(hand.quat))
      hand.bone.updateWorldMatrix(false, true)
      depth = measure(hand)
      if (depth < tolerance || pass === PASSES - 1) break
      solveTarget.add(wristPush)
      if (elbowPush.lengthSq() > 0) {
        // Swing the elbow around the shoulder-wrist axis, away from what it touched.
        a.setFromMatrixPosition(hand.upper.matrixWorld)
        b.setFromMatrixPosition(hand.lower.matrixWorld).sub(a)
        c.setFromMatrixPosition(hand.bone.matrixWorld).sub(a).normalize()
        b.addScaledVector(c, -b.dot(c)).addScaledVector(elbowPush, 1.5)
        if (b.lengthSq() > 1e-8) solvePole.copy(b).normalize()
      }
    }
    hand.offset.copy(solveTarget).sub(hand.target)
    hand.swing.copy(solvePole).sub(basePole)
    hand.residual = depth
    hand.passes = pass + 1
    worst = Math.max(worst, depth)
  }

  return {
    hands, cupTable, clothTable, palmLength, palmContact,
    sides(name) { return USES[name] || [] },
    update({ gesture, delta = 0, reducedMotion = false, settled = false, cupAttached = false, clothAttached = false, cupWorld = null }) {
      root.getWorldQuaternion(rootRotation)
      if (!placed) place()
      worst = 0
      const state = { curl: 0 }
      for (const side of SIDES) {
        const hand = hands[side]
        if (!hand) continue
        // A prop still in her hand is put down before that hand does anything else.
        const forced = side === 'right' ? cupAttached && !['sip', 'wipe'].includes(gesture.active) : clothAttached && gesture.active !== 'wipe'
        let name = 'rest', weight = 0
        if (forced) { name = side === 'right' ? 'sip' : 'wipe'; weight = 0.5; hand.gate = 0 }
        else {
          hand.gate = Math.min(1, hand.gate + delta / 0.45)
          if (gesture.active !== 'rest' && (USES[gesture.active] || []).includes(side)) { name = gesture.active; weight = gesture.weight * smooth(hand.gate) }
        }
        hand.avoidCup = !cupAttached && !(side === 'right' && (name === 'sip' || name === 'wipe'))
        hand.passing = weight > 0.02
        restWrist(side, target)
        qGoal.copy(hand.orientations[seat[side]])
        state.curl = HAND_POSES[seat[side]].curl
        pole.fromArray(REST_POLE).setX(REST_POLE[0] * mirror[side])
        if (weight > 0 && (name === 'sip' || name === 'wipe')) staged(side, name, weight, gesture, cupAttached ? cupWorld : null, state)
        else if (weight > 0) {
          const recipe = RECIPES[name], orientation = hand.orientations[recipe.pose]
          // A hand leaves the counter upward before it travels, and comes back down
          // onto it last, so it never slides through the glass standing beside it.
          gestureWrist(side, name, gesture, orientation, b)
          const across = Math.pow(weight, 1.6), rise = 1 - (1 - weight) * (1 - weight)
          target.set(target.x + (b.x - target.x) * across, target.y + (b.y - target.y) * (b.y > target.y ? rise : weight), target.z + (b.z - target.z) * across)
          // The bump in the path waits for the lift as well: a hand that swung
          // forward at once did so at the height of the glass, fingers first.
          target.add(rooted(side, recipe.arc, a).multiplyScalar(arcShape(weight, rise) * k))
          qGoal.slerp(orientation, weight)
          state.curl += (HAND_POSES[recipe.pose].curl - state.curl) * weight
          pole.lerp(a.fromArray(recipe.pole).setX(recipe.pole[0] * mirror[side]), weight)
        }
        qWorld.copy(rootRotation).multiply(qGoal)
        if (name === 'wave' && gesture.roll) qWorld.multiply(qTurn.setFromAxisAngle(hand.axes[2], gesture.roll * weight * mirror[side]))
        const tau = forced ? 0.2 : name === 'sip' || name === 'wipe' ? 0.06 : 0.09
        const ease = reducedMotion || settled || !hand.ready ? 1 : 1 - Math.exp(-delta / tau)
        hand.target.lerp(target, ease)
        hand.quat.slerp(qWorld, ease)
        hand.pole.lerp(pole, ease)
        hand.curl += (state.curl - hand.curl) * ease
        hand.ready = true
        settle(hand)
      }
      return worst
    },
    /** Forget smoothed targets, e.g. after a scene change, so nothing sweeps across the frame. */
    rebase() { for (const hand of Object.values(hands)) { hand.ready = false; hand.gate = 1; hand.offset.set(0, 0, 0); hand.swing.set(0, 0, 0) } },
    snapshot() {
      return Object.fromEntries(Object.entries(hands).map(([side, hand]) => [side, { ...hand.solver.snapshot(),
        residual: hand.residual, passes: hand.passes, contact: hand.residual > 0 ? hand.contact : null, curl: hand.curl, goal: hand.target.toArray(),
        wrist: new THREE.Vector3().setFromMatrixPosition(hand.bone.matrixWorld).toArray() }]))
    }
  }
}

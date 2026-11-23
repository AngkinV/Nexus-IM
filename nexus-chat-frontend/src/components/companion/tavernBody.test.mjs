import test from 'node:test'
import assert from 'node:assert/strict'
import * as THREE from 'three'
import { fitBands, bandAt, pushOutEllipse, pushOutEllipsoid, approximateTavernBody } from './tavernBody.js'
import { createTavernArms, extreme, HAND_POSES } from './tavernArms.js'
import { createTavernGestures, GESTURE_NAMES, GESTURE_TIMING, seatedPose } from './tavernGestures.js'
import { COUNTER, STAGE_CAMERA, counterFloor } from './tavernLayout.js'

test('bands measure width and depth per slice and borrow from a neighbour where there are no vertices', () => {
  const points = []
  for (let index = 0; index < 200; index++) {
    const y = index < 100 ? 0.05 : 0.95, angle = index * 0.7
    points.push(new THREE.Vector3(Math.cos(angle) * (index < 100 ? 0.2 : 0.1), y, 0.02 + Math.sin(angle) * 0.1))
  }
  const bands = fitBands(points, 0, 1, 10)
  assert.equal(bands.length, 10)
  assert.ok(Math.abs(bands[0].rx - 0.2) < 0.01 && Math.abs(bands[9].rx - 0.1) < 0.01)
  assert.ok(Math.abs(bands[0].cz - 0.02) < 0.01 && Math.abs(bands[0].rz - 0.1) < 0.01)
  assert.equal(bands[3].rx, bands[0].rx)
  assert.equal(bands[7].rx, bands[9].rx)
  const middle = bandAt(bands, 0.05 + 0.1 * 4.5)
  assert.ok(middle.rx > 0.1 && middle.rx <= 0.2)
  assert.deepEqual(fitBands([], 0, 1, 4), [])
  assert.deepEqual(fitBands(points, 1, 1, 4), [])
  assert.equal(bandAt([], 0.5), null)
})

test('points inside an ellipse or ellipsoid are moved to its surface; points outside are left alone', () => {
  for (const [x, z] of [[0.05, 0.02], [-0.1, 0.05], [0, 0], [0.19, 0], [0, -0.09]]) {
    const point = new THREE.Vector3(x, 3, z)
    const depth = pushOutEllipse(point, 0, 0, 0.2, 0.1, 0.02)
    assert.ok(depth > 0)
    assert.ok(Math.abs((point.x / 0.22) ** 2 + (point.z / 0.12) ** 2 - 1) < 1e-9)
    assert.equal(point.y, 3)
  }
  const outside = new THREE.Vector3(0.3, 0, 0)
  assert.equal(pushOutEllipse(outside, 0, 0, 0.2, 0.1, 0.02), 0)
  assert.deepEqual(outside.toArray(), [0.3, 0, 0])
  const center = new THREE.Vector3(1, 2, 3), radii = new THREE.Vector3(0.1, 0.12, 0.1)
  for (const offset of [[0.02, 0.03, 0.01], [0, 0, 0], [-0.09, 0, 0]]) {
    const point = center.clone().add(new THREE.Vector3(...offset))
    assert.ok(pushOutEllipsoid(point, center, radii, 0.01) > 0)
    const d = point.clone().sub(center)
    assert.ok(Math.abs((d.x / 0.11) ** 2 + (d.y / 0.13) ** 2 + (d.z / 0.11) ** 2 - 1) < 1e-6)
    assert.ok(point.toArray().every(Number.isFinite))
  }
  assert.equal(pushOutEllipsoid(new THREE.Vector3(2, 2, 3), center, radii, 0.01), 0)
})

test('the counter is solid from above, open behind its back edge, and the camera frames it', () => {
  const mid = (COUNTER.back + COUNTER.front) / 2
  assert.equal(counterFloor(mid), COUNTER.top)
  assert.equal(counterFloor(mid, 0.03), COUNTER.top + 0.03)
  assert.equal(counterFloor(COUNTER.front + 0.2), -Infinity)
  // A hand in her lap, a hand's length behind the edge, is not lifted by the counter.
  assert.ok(counterFloor(COUNTER.back - 0.3) < COUNTER.top - 1)
  assert.ok(counterFloor(COUNTER.back - 0.01) > COUNTER.top - 0.1)
  assert.ok(STAGE_CAMERA.position[2] > COUNTER.front && STAGE_CAMERA.position[1] > COUNTER.top)
  const total = new THREE.Vector3()
  extreme(total, new THREE.Vector3(0.1, -0.2, 0))
  extreme(total, new THREE.Vector3(-0.3, 0.1, 0.05), 0.5)
  assert.deepEqual(total.toArray(), [-0.15, -0.2, 0.025])
})

// A plain humanoid in a T-pose, facing +z, in model units. Her left is +x.
function humanoid() {
  const root = new THREE.Group()
  const bones = new Map()
  const add = (name, parent, x, y, z) => {
    const bone = new THREE.Bone()
    const holder = parent ? bones.get(parent).bone : root
    holder.add(bone)
    holder.updateWorldMatrix(true, false)
    bone.position.copy(holder.worldToLocal(new THREE.Vector3(x, y, z)))
    bone.updateWorldMatrix(true, false)
    bones.set(name, { bone })
  }
  add('hips', null, 0, 1, 0); add('spine', 'hips', 0, 1.08, 0); add('chest', 'spine', 0, 1.2, 0)
  add('upperChest', 'chest', 0, 1.3, 0); add('neck', 'upperChest', 0, 1.4, 0); add('head', 'neck', 0, 1.45, 0)
  for (const [side, sign] of [['left', 1], ['right', -1]]) {
    add(`${side}Shoulder`, 'upperChest', sign * 0.03, 1.38, 0)
    add(`${side}UpperArm`, `${side}Shoulder`, sign * 0.1, 1.38, 0)
    add(`${side}LowerArm`, `${side}UpperArm`, sign * 0.33, 1.38, 0)
    add(`${side}Hand`, `${side}LowerArm`, sign * 0.55, 1.38, 0)
    add(`${side}UpperLeg`, 'hips', sign * 0.08, 0.95, 0)
    for (const [finger, z] of [['Index', 0.02], ['Middle', 0.007], ['Ring', -0.007], ['Little', -0.02]]) {
      add(`${side}${finger}Proximal`, `${side}Hand`, sign * 0.62, 1.38, z)
      add(`${side}${finger}Intermediate`, `${side}${finger}Proximal`, sign * 0.65, 1.38, z)
      add(`${side}${finger}Distal`, `${side}${finger}Intermediate`, sign * 0.67, 1.38, z)
    }
    add(`${side}ThumbMetacarpal`, `${side}Hand`, sign * 0.565, 1.38, 0.02)
    add(`${side}ThumbProximal`, `${side}ThumbMetacarpal`, sign * 0.59, 1.38, 0.035)
    add(`${side}ThumbDistal`, `${side}ThumbProximal`, sign * 0.61, 1.38, 0.045)
  }
  root.updateMatrixWorld(true)
  for (const entry of bones.values()) {
    entry.rest = entry.bone.quaternion.clone()
    entry.position = entry.bone.getWorldPosition(new THREE.Vector3())
    entry.frame = entry.bone.getWorldQuaternion(new THREE.Quaternion())
    entry.inverseFrame = entry.frame.clone().invert()
  }
  return { root, bones, unit: 0.45 }
}

function seated(seed = 0) {
  const rig = humanoid()
  const body = approximateTavernBody(rig.bones)
  const scale = 2.3 / rig.unit
  rig.root.scale.setScalar(scale)
  rig.root.rotation.y = seatedPose(seed).yaw
  // Head at eye level behind the counter, torso clear of its back edge.
  rig.root.position.set(0, 0.52 - 1.45 * scale, COUNTER.back - 0.07 - rig.unit * 0.2 * scale - 0.1)
  rig.root.updateMatrixWorld(true)
  const cup = { radius: rig.unit * 0.064, height: rig.unit * 0.155 }
  const arms = createTavernArms({ root: rig.root, bones: rig.bones, body, unit: rig.unit, scale, seat: seatedPose(seed), cup })
  const frame = (gesture, delta) => {
    for (const [side, sign] of [['left', 1], ['right', -1]]) {
      rig.bones.get(`${side}UpperArm`).bone.quaternion.setFromEuler(new THREE.Euler(0, 0, sign * -0.85))
      rig.bones.get(`${side}LowerArm`).bone.quaternion.identity()
    }
    rig.root.updateMatrixWorld(true)
    body.prepare()
    return arms.update({ gesture, delta })
  }
  return { ...rig, body, arms, scale, cup, frame }
}
const REST = { active: 'rest', weight: 0, age: 0, hold: 0, roll: 0, lift: { left: 0, right: 0 }, phase: 'rest' }
const world = bone => bone.getWorldPosition(new THREE.Vector3())

test('a body built from proportions has a torso, a head and landmarks in sensible places', () => {
  const { body, unit } = seated()
  assert.equal(body.source, 'proportions')
  assert.equal(body.bands.length, 10)
  const marks = body.landmarks
  assert.ok(marks.chin.y < marks.mouth.y && marks.mouth.y < marks.headCenter.y)
  assert.ok(marks.mouth.z > marks.headCenter.z && marks.hairSide > marks.headRadii.x)
  assert.ok(body.bands.every(band => band.rx > 0 && band.rz > 0 && band.rx < unit))
  assert.equal(approximateTavernBody(new Map()), null)
})

test('the body volumes follow the posed skeleton: a point inside her chest or head is pushed out, wherever she sits', () => {
  for (const seed of [0, 1, 2]) {
    const { body, bones, frame } = seated(seed)
    frame(REST, 0)
    const chest = world(bones.get('chest').bone), head = world(bones.get('head').bone).add(new THREE.Vector3(0, 0.4, 0))
    for (const inside of [chest, head]) {
      assert.ok(body.depth(inside, 0.01) > 0.05)
      const moved = inside.clone()
      body.resolve(moved, 0.01)
      assert.ok(body.depth(moved, 0.01) < 1e-4)
      assert.ok(moved.distanceTo(inside) > 0.05)
    }
    assert.equal(body.depth(chest.clone().add(new THREE.Vector3(0, 0, 3)), 0.01), 0)
    assert.equal(body.depth(head, 0.01, { head: false }), 0)
    const front = body.torsoPoint(0, 0.5, 1, new THREE.Vector3())
    assert.ok(front.z > chest.z && body.depth(front, 0) < 1e-6 && body.depth(front.clone().add(new THREE.Vector3(0, 0, -0.05)), 0) > 0)
  }
})

test('at rest both hands lie on the counter, clear of the glass, with nothing inside her body', () => {
  for (const seed of [0, 1, 2]) {
    const { arms, frame, scale, cup, bones } = seated(seed)
    let depth = 0
    for (let index = 0; index < 20; index++) depth = frame(REST, 1 / 30)
    assert.ok(depth < 0.012, `seat ${seed} depth ${depth}`)
    for (const side of ['left', 'right']) {
      const wrist = world(bones.get(`${side}Hand`).bone)
      assert.ok(wrist.y > COUNTER.top && wrist.y < COUNTER.top + 0.2, `${side} wrist height ${wrist.y}`)
      assert.ok(wrist.z > COUNTER.back && wrist.z < COUNTER.front)
      const tip = world(bones.get(`${side}MiddleDistal`).bone)
      assert.ok(tip.z < COUNTER.front, 'fingertips stay on the counter')
      assert.ok(Math.hypot(wrist.x - arms.cupTable.x, wrist.z - arms.cupTable.z) > cup.radius * scale)
    }
    assert.ok(arms.cupTable.z - cup.radius * scale > COUNTER.back && arms.cupTable.z + cup.radius * scale < COUNTER.front)
    assert.ok(arms.clothTable.x > arms.cupTable.x)
  }
})

test('through every gesture, on every frame, the arms stay out of her body and above the counter', () => {
  for (const seed of [0, 1, 2]) {
    for (const name of GESTURE_NAMES.filter(name => name !== 'rest')) {
      const { frame, bones, arms } = seated(seed)
      for (let index = 0; index < 10; index++) frame(REST, 1 / 30)
      const track = createTavernGestures()
      track.play(name)
      let deepest = 0, lowest = Infinity, peak = null
      const total = GESTURE_TIMING[name].reduce((sum, value) => sum + value, 0)
      for (let index = 0; index < (total + 1) * 30; index++) {
        const gesture = track.update(1 / 30)
        deepest = Math.max(deepest, frame(gesture, 1 / 30))
        for (const side of arms.sides(name)) {
          const wrist = world(bones.get(`${side}Hand`).bone)
          if (wrist.z > COUNTER.back + 0.05) lowest = Math.min(lowest, wrist.y)
          assert.ok(wrist.toArray().every(Number.isFinite))
        }
        if (gesture.phase === 'holding' && !peak) peak = world(bones.get('rightHand').bone)
      }
      // Under a hundredth of her torso height; a solver that stops converging shows up as 0.03 or more.
      assert.ok(deepest < 0.02, `${name} seat ${seed}: deepest ${deepest}`)
      assert.ok(lowest > COUNTER.top - 0.01, `${name} seat ${seed}: wrist at ${lowest}`)
      assert.ok(peak, name)
      const snapshot = arms.snapshot()
      assert.ok(snapshot.right.error < 0.02 && snapshot.left.error < 0.02, 'the solver reaches the constrained target')
    }
  }
})

test('gestures arrive where they are meant to: chin and mouth at her face, a wave above the shoulder, crossed arms in front of her chest', () => {
  const hold = name => {
    const rig = seated(0)
    for (let index = 0; index < 10; index++) rig.frame(REST, 1 / 30)
    const track = createTavernGestures()
    track.play(name)
    const [prepare, held] = GESTURE_TIMING[name]
    for (let index = 0; index < (prepare + held * 0.6) * 30; index++) rig.frame(track.update(1 / 30), 1 / 30)
    return rig
  }
  const k = 2.3
  const chin = hold('chin'), marks = chin.body.landmarks
  const chinPoint = chin.body.headPoint(marks.chin, new THREE.Vector3())
  assert.ok(world(chin.bones.get('rightMiddleProximal').bone).distanceTo(chinPoint) < 0.25 * k, 'knuckles by the chin')
  const cover = hold('cover')
  const mouth = cover.body.headPoint(cover.body.landmarks.mouth, new THREE.Vector3())
  const fingers = world(cover.bones.get('rightMiddleIntermediate').bone)
  assert.ok(fingers.distanceTo(mouth) < 0.2 * k && fingers.z > mouth.z, 'fingers in front of the mouth')
  const wave = hold('wave')
  assert.ok(world(wave.bones.get('rightHand').bone).y > world(wave.bones.get('rightUpperArm').bone).y + 0.3)
  assert.ok(world(wave.bones.get('rightLowerArm').bone).y < world(wave.bones.get('rightUpperArm').bone).y, 'elbow below the shoulder')
  const cross = hold('cross')
  const chest = world(cross.bones.get('chest').bone)
  for (const side of ['left', 'right']) {
    const wrist = world(cross.bones.get(`${side}Hand`).bone)
    assert.ok(wrist.z > chest.z && wrist.z - chest.z < 0.5 * k && Math.abs(wrist.y - chest.y) < 0.35 * k)
    // Each wrist has crossed to the other side of her.
    assert.ok(side === 'left' ? wrist.x < world(cross.bones.get('leftUpperArm').bone).x : wrist.x > world(cross.bones.get('rightUpperArm').bone).x)
  }
  const tuck = hold('tuck')
  assert.ok(world(tuck.bones.get('rightHand').bone).y > world(tuck.bones.get('neck').bone).y)
  assert.ok(Object.values(HAND_POSES).every(pose => pose.curl >= 0 && pose.curl <= 1.2))
})

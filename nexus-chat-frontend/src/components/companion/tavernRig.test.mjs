import test from 'node:test'
import assert from 'node:assert/strict'
import * as THREE from 'three'
import { calibrateHand, createLimbSolver } from './tavernRig.js'

const radians = degrees => degrees * Math.PI / 180
const vector = (x, y, z) => new THREE.Vector3(x, y, z)
const worldPosition = bone => bone.getWorldPosition(new THREE.Vector3())

function near(actual, expected, tolerance = 1e-8) {
  assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} != ${expected}`)
}

function nearVector(actual, expected, tolerance = 1e-8) {
  assert.ok(actual.distanceTo(expected) <= tolerance, `${actual.toArray()} != ${expected.toArray()}`)
}

function validQuaternion(quaternion) {
  assert.ok(quaternion.toArray().every(Number.isFinite))
  near(quaternion.length(), 1)
}

function distanceAtFlex(a, b, degrees) {
  return Math.sqrt(a * a + b * b + 2 * a * b * Math.cos(radians(degrees)))
}

function createChain(side = 'left', transformed = false, a = 0.7, b = 0.55) {
  const parent = new THREE.Object3D()
  parent.position.set(2.3, -1.7, 0.8)
  if (transformed) {
    parent.rotation.set(0.47, -0.81, 0.32)
    parent.scale.setScalar(1.8)
  }
  const upper = new THREE.Bone()
  const lower = new THREE.Bone()
  const end = new THREE.Bone()
  parent.add(upper)
  upper.add(lower)
  lower.add(end)
  upper.position.set(0.13, 0.27, -0.18)
  const sign = side === 'right' ? -1 : 1
  lower.position.set(sign * a, 0, 0)
  end.position.set(sign * b, 0, 0)
  upper.rotation.set(0.21, -0.38, 0.13)
  lower.rotation.set(-0.17, 0.24, -0.49)
  end.rotation.set(0.15, -0.12, 0.34)
  parent.updateMatrixWorld(true)
  return { parent, upper, lower, end }
}

function measurements({ upper, lower, end }) {
  const shoulder = worldPosition(upper)
  const joint = worldPosition(lower)
  const wrist = worldPosition(end)
  const first = joint.clone().sub(shoulder)
  const second = wrist.clone().sub(joint)
  const a = first.length()
  const b = second.length()
  const bend = Math.acos(THREE.MathUtils.clamp(first.dot(second) / (a * b), -1, 1))
  return { shoulder, joint, wrist, a, b, bend }
}

function assertSolve(chain, solver, target, pole, zeroDirection) {
  const before = measurements(chain)
  const targetCopy = target.clone()
  const poleCopy = pole.clone()
  const nodes = [chain.upper, chain.lower, chain.end]
  const transforms = nodes.map(bone => ({ parent: bone.parent, position: bone.position.clone(), scale: bone.scale.clone() }))
  const endRotation = chain.end.quaternion.clone()
  const direction = target.clone().sub(before.shoulder)
  const requestedDistance = direction.length()
  if (requestedDistance === 0) direction.copy(zeroDirection || before.wrist.clone().sub(before.shoulder)).normalize()
  else direction.normalize()
  const distance = THREE.MathUtils.clamp(requestedDistance, distanceAtFlex(before.a, before.b, 145), distanceAtFlex(before.a, before.b, 5))
  const expectedWrist = before.shoulder.clone().addScaledVector(direction, distance)

  solver.solve(target, pole)

  const after = measurements(chain)
  nearVector(after.shoulder, before.shoulder)
  nearVector(after.wrist, expectedWrist)
  near(after.a, before.a)
  near(after.b, before.b)
  assert.ok(after.bend >= radians(5) - 1e-8 && after.bend <= radians(145) + 1e-8, `Flex ${after.bend}`)
  const expectedFlex = Math.acos(THREE.MathUtils.clamp((distance * distance - before.a * before.a - before.b * before.b) / (2 * before.a * before.b), -1, 1))
  near(after.bend, expectedFlex)
  const snapshot = solver.snapshot()
  near(snapshot.error, after.wrist.distanceTo(targetCopy))
  near(snapshot.error, Math.abs(requestedDistance - distance))
  near(snapshot.bend, after.bend)
  assert.deepEqual(target, targetCopy)
  assert.deepEqual(pole, poleCopy)
  for (let i = 0; i < nodes.length; i++) {
    assert.equal(nodes[i].parent, transforms[i].parent)
    assert.deepEqual(nodes[i].position, transforms[i].position)
    assert.deepEqual(nodes[i].scale, transforms[i].scale)
    validQuaternion(nodes[i].quaternion)
  }
  assert.deepEqual(chain.end.quaternion.toArray(), endRotation.toArray())
  return after
}

for (const side of ['left', 'right']) {
  for (const transformed of [false, true]) {
    for (const reach of ['reachable', 'far', 'fully extended', 'too close', 'shoulder']) {
      test(`${side} IK ${reach}, ${transformed ? 'rotated and scaled' : 'translated'} parent`, () => {
        const chain = createChain(side, transformed)
        const solver = createLimbSolver(chain.upper, chain.lower, chain.end)
        const before = measurements(chain)
        const distances = {
          reachable: distanceAtFlex(before.a, before.b, 83),
          far: 10 * (before.a + before.b),
          'fully extended': before.a + before.b,
          'too close': 0.001,
          shoulder: 0
        }
        const direction = vector(side === 'left' ? 0.6 : -0.6, -0.2, 0.75).normalize()
        const target = before.shoulder.clone().addScaledVector(direction, distances[reach])
        const pole = vector(0.21, -2, 0.44)
        const after = assertSolve(chain, solver, target, pole)
        const targetDirection = reach === 'shoulder' ? before.wrist.clone().sub(before.shoulder).normalize() : direction
        const expectedPole = pole.clone().addScaledVector(targetDirection, -pole.dot(targetDirection)).normalize()
        const elbowDirection = after.joint.clone().sub(after.shoulder)
        elbowDirection.addScaledVector(targetDirection, -elbowDirection.dot(targetDirection)).normalize()
        nearVector(elbowDirection, expectedPole)
      })
    }
  }
}

for (const [a, b] of [[0.4, 0.4], [0.15, 0.8], [0.8, 0.15]]) {
  test(`IK clamps both aims for segment lengths ${a} and ${b}`, () => {
    const chain = createChain('right', true, a, b)
    const solver = createLimbSolver(chain.upper, chain.lower, chain.end)
    const shoulder = worldPosition(chain.upper)
    const direction = vector(-0.3, 0.8, -0.4).normalize()
    for (const distance of [10, 0.0001, 0]) {
      assertSolve(chain, solver, shoulder.clone().addScaledVector(direction, distance), vector(0, -1, 0), direction)
    }
  })
}

test('parallel, antiparallel, almost parallel and zero poles have stable perpendicular fallbacks', () => {
  for (const direction of [vector(1, 0, 0), vector(0, 1, 0), vector(0, 0, -1), vector(0.3, 0.5, -0.8).normalize()]) {
    const chain = createChain('left', true)
    const solver = createLimbSolver(chain.upper, chain.lower, chain.end)
    const { shoulder, a, b } = measurements(chain)
    const target = shoulder.addScaledVector(direction, distanceAtFlex(a, b, 90))
    let previous
    for (const pole of [direction.clone(), direction.clone().multiplyScalar(-10), vector(0, 0, 0), direction.clone().add(vector(1e-10, -1e-10, 1e-10))]) {
      for (let i = 0; i < 3; i++) {
        const after = assertSolve(chain, solver, target, pole)
        if (previous) nearVector(after.joint, previous)
        previous = after.joint
      }
    }
  }
})

test('pole is a world direction, independent of translation, magnitude and axial component', () => {
  const chain = createChain('right', true)
  const solver = createLimbSolver(chain.upper, chain.lower, chain.end)
  const { shoulder, a, b } = measurements(chain)
  const direction = vector(0.2, -0.8, 0.5).normalize()
  const target = shoulder.clone().addScaledVector(direction, distanceAtFlex(a, b, 70))
  const pole = vector(-0.7, 0.1, -0.4)
  const initial = assertSolve(chain, solver, target, pole)
  const scaledPole = pole.clone().multiplyScalar(1e-9).addScaledVector(direction, 2e-9)
  const scaled = assertSolve(chain, solver, target, scaledPole)
  nearVector(scaled.joint, initial.joint)
  const translation = vector(-12, 5, 8)
  chain.parent.position.add(translation)
  const translated = assertSolve(chain, solver, target.clone().add(translation), pole)
  nearVector(translated.joint, initial.joint.clone().add(translation))
})

test('segment lengths are remeasured after changing root scale and rotation', () => {
  const chain = createChain('left', true)
  const solver = createLimbSolver(chain.upper, chain.lower, chain.end)
  const direction = vector(0.7, -0.1, 0.4).normalize()
  const pole = vector(0, -1, 0)
  for (const scale of [0.35, 2.7, 1.1]) {
    chain.parent.scale.setScalar(scale)
    chain.parent.rotation.y += 0.31
    const { shoulder, a, b } = measurements(chain)
    near(a, 0.7 * scale)
    near(b, 0.55 * scale)
    const target = shoulder.addScaledVector(direction, distanceAtFlex(a, b, 64))
    chain.parent.position.z += 0.2
    target.z += 0.2
    solver.solve(target, pole)
    near(solver.snapshot().error, 0)
    assertSolve(chain, solver, target, pole)
  }
})

test('non-X bone axes and a parentless shoulder can be aimed without changing local offsets', () => {
  const chain = createChain('left')
  chain.parent.remove(chain.upper)
  chain.lower.position.set(0, 0.7, 0)
  chain.end.position.set(0, 0, -0.55)
  const solver = createLimbSolver(chain.upper, chain.lower, chain.end)
  const target = worldPosition(chain.upper).add(vector(-0.4, 0.5, 0.4))
  assertSolve(chain, solver, target, vector(0.2, -0.3, 1))
})

test('repeated solves and snapshots preserve length and do not alias caller vectors or other solvers', () => {
  const left = createChain('left', true)
  const right = createChain('right', true)
  const leftSolver = createLimbSolver(left.upper, left.lower, left.end)
  const rightSolver = createLimbSolver(right.upper, right.lower, right.end)
  const original = measurements(left)
  const target = new THREE.Vector3()
  const pole = vector(0.2, -1, 0.3)
  const rightTarget = worldPosition(right.upper).add(vector(-0.6, 0.2, 0.8))
  rightSolver.solve(rightTarget, pole)
  const rightSnapshot = rightSolver.snapshot()
  for (let i = 0; i < 100; i++) {
    target.set(Math.sin(i * 0.17), -0.5, Math.cos(i * 0.21)).multiplyScalar(0.2 + i * 0.04).add(original.shoulder)
    const after = assertSolve(left, leftSolver, target, pole)
    near(after.a, original.a)
    near(after.b, original.b)
    const snapshot = leftSolver.snapshot()
    target.set(100, 200, 300)
    assert.deepEqual(leftSolver.snapshot(), snapshot)
    assert.deepEqual(rightSolver.snapshot(), rightSnapshot)
  }
})

test('zero-length segments and collapsed root scale remain finite', () => {
  for (const [a, b, scale] of [[0, 0.5, 1], [0.5, 0, 1], [0, 0, 1], [0.5, 0.5, 0]]) {
    const chain = createChain('left', false, a, b)
    chain.parent.scale.setScalar(scale)
    const solver = createLimbSolver(chain.upper, chain.lower, chain.end)
    near(solver.snapshot().error, 0)
    solver.solve(worldPosition(chain.upper), vector(0, 0, 0))
    const snapshot = solver.snapshot()
    assert.ok(Number.isFinite(snapshot.error))
    assert.ok(Number.isFinite(snapshot.bend))
    for (const bone of [chain.upper, chain.lower, chain.end]) validQuaternion(bone.quaternion)
  }
})

function createHand(side, handRotation = new THREE.Quaternion(), transformed = false) {
  const parent = new THREE.Object3D()
  parent.position.set(-1.7, 0.8, 2.2)
  if (transformed) {
    parent.rotation.set(-0.32, 0.61, 0.28)
    parent.scale.setScalar(2.3)
  }
  const hand = new THREE.Bone()
  parent.add(hand)
  hand.position.set(0.2, -0.3, 0.1)
  hand.quaternion.copy(handRotation)
  const inverse = handRotation.clone().invert()
  const sign = side === 'right' ? -1 : 1
  const finger = vector(sign, 0, 0).applyQuaternion(inverse)
  const palm = vector(0, -1, 0).applyQuaternion(inverse)
  const spacer = new THREE.Bone()
  hand.add(spacer)
  spacer.position.set(0.007, -0.009, 0.006)
  spacer.rotation.set(0.2, 0.13, -0.31)
  const spacerInverse = spacer.quaternion.clone().invert()
  const makeFinger = (length, span) => {
    const bone = new THREE.Bone()
    const local = vector(sign * length, 0, span).applyQuaternion(inverse)
    bone.position.copy(local).sub(spacer.position).applyQuaternion(spacerInverse)
    spacer.add(bone)
    return bone
  }
  const middle = makeFinger(0.11, 0)
  const index = makeFinger(0.105, 0.025)
  const little = makeFinger(0.08, -0.03)
  parent.updateMatrixWorld(true)
  return { parent, hand, index, middle, little, finger, palm }
}

function assertCalibration(calibration, finger, palm, palmLength) {
  assert.ok(calibration)
  assert.ok(calibration.rotation instanceof THREE.Quaternion)
  assert.ok(calibration.curlAxis instanceof THREE.Vector3)
  validQuaternion(calibration.rotation)
  near(calibration.palmLength, palmLength)
  nearVector(finger.clone().applyQuaternion(calibration.rotation), vector(1, 0, 0))
  nearVector(palm.clone().applyQuaternion(calibration.rotation), vector(0, 0, 1))
  const tangent = new THREE.Vector3().crossVectors(palm, finger).normalize()
  nearVector(tangent.applyQuaternion(calibration.rotation), vector(0, 1, 0))
  nearVector(calibration.curlAxis, new THREE.Vector3().crossVectors(finger, palm).normalize())
  near(calibration.curlAxis.length(), 1)
  const curled = finger.clone().applyAxisAngle(calibration.curlAxis, 0.2)
  assert.ok(curled.dot(palm) > 0)
}

const handFrames = [
  ['X', new THREE.Quaternion()],
  ['Y', new THREE.Quaternion().setFromAxisAngle(vector(0, 0, 1), Math.PI / 2)],
  ['Z', new THREE.Quaternion().setFromAxisAngle(vector(0, 1, 0), Math.PI / 2)],
  ['oblique', new THREE.Quaternion().setFromEuler(new THREE.Euler(0.43, -0.79, 0.52))]
]

for (const side of ['left', 'right']) {
  for (const [axis, rotation] of handFrames) {
    for (const transformed of [false, true]) {
      test(`${side} hand calibrates local ${axis} fingers under ${transformed ? 'transformed' : 'unrotated'} parent`, () => {
        const { parent, hand, index, middle, little, finger, palm } = createHand(side, rotation, transformed)
        const bones = [hand, index, middle, little]
        const before = bones.map(bone => bone.matrixWorld.clone())
        const result = calibrateHand(hand, index, middle, little, side)
        assertCalibration(result, finger, palm, 0.11)
        const palmWorld = palm.clone().applyQuaternion(hand.getWorldQuaternion(new THREE.Quaternion()))
        const expectedDown = vector(0, -1, 0).applyQuaternion(parent.quaternion)
        nearVector(palmWorld, expectedDown)
        for (let i = 0; i < bones.length; i++) assert.deepEqual(bones[i].matrixWorld, before[i])
      })
    }
  }
}

test('missing hand or middle returns null', () => {
  const { hand, index, middle, little } = createHand('left')
  assert.equal(calibrateHand(null, index, middle, little, 'left'), null)
  assert.equal(calibrateHand(hand, index, undefined, little, 'left'), null)
})

test('missing index or little uses world down converted to the rotated hand local frame', () => {
  for (const side of ['left', 'right']) {
    for (const missing of ['index', 'little', 'both']) {
      const { hand, index, middle, little, finger } = createHand(side, handFrames[3][1], true)
      const palm = vector(0, -1, 0).applyQuaternion(hand.getWorldQuaternion(new THREE.Quaternion()).invert())
      palm.addScaledVector(finger, -palm.dot(finger)).normalize()
      const result = calibrateHand(hand, missing === 'little' ? index : null, middle, missing === 'index' ? little : null, side)
      assertCalibration(result, finger, palm, 0.11)
    }
  }
})

test('degenerate finger spans use a finite orthogonal downward fallback', () => {
  for (const parallel of [false, true]) {
    const { hand, index, middle, little, finger, palm } = createHand('right', handFrames[2][1])
    index.position.copy(little.position)
    if (parallel) index.position.add(finger.clone().applyQuaternion(index.parent.quaternion.clone().invert()).multiplyScalar(0.03))
    assertCalibration(calibrateHand(hand, index, middle, little, 'right'), finger, palm, 0.11)
  }
})

test('vertical fingers and zero palm length retain finite reference frames', () => {
  const hand = new THREE.Bone()
  const middle = new THREE.Bone()
  hand.add(middle)
  hand.rotation.set(Math.PI / 2, 0, 0)
  middle.position.set(0, 0, 0.1)
  const first = calibrateHand(hand, null, middle, null, 'left')
  const second = calibrateHand(hand, null, middle, null, 'left')
  assert.deepEqual(first, second)
  validQuaternion(first.rotation)
  near(first.curlAxis.length(), 1)
  near(first.curlAxis.dot(middle.position.clone().normalize()), 0)
  nearVector(middle.position.clone().normalize().applyQuaternion(first.rotation), vector(1, 0, 0))
  middle.position.set(0, 0, 0)
  const zero = calibrateHand(hand, null, middle, null, 'right')
  validQuaternion(zero.rotation)
  near(zero.curlAxis.length(), 1)
  near(zero.palmLength, 0)
})

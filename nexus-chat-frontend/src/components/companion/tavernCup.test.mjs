import test from 'node:test'
import assert from 'node:assert/strict'
import * as THREE from 'three'
import { sipPath, calibrateCupGrip, createCupAttachment } from './tavernCup.js'
import { calibrateHand } from './tavernRig.js'

test('sip reaches and settles the grip before lifting, with a reversible bounded path', () => {
  assert.deepEqual(sipPath(0), { reach: 0, lift: 0 })
  assert.deepEqual(sipPath(0.5), { reach: 1, lift: 0 })
  assert.deepEqual(sipPath(1), { reach: 1, lift: 1 })
  for (const value of [-1, 0.1, 0.35, 0.6, 0.9, 2, NaN]) {
    const state = sipPath(value)
    assert.ok(state.reach >= 0 && state.reach <= 1)
    assert.ok(state.lift >= 0 && state.lift <= 1)
  }
})

test('normalized and raw grip coordinates agree under different hand scales and later rotations', () => {
  const root = new THREE.Group(); root.position.set(1, 2, 3); root.scale.setScalar(3)
  const hand = new THREE.Bone(), raw = new THREE.Bone()
  hand.position.set(0.2, 0.3, 0.4); raw.position.copy(hand.position); raw.scale.setScalar(2)
  root.add(hand, raw)
  const middle = new THREE.Bone(), index = new THREE.Bone(), little = new THREE.Bone()
  middle.position.set(0.05, 0, 0); index.position.set(0.05, 0, 0.02); little.position.set(0.05, 0, -0.02)
  hand.add(middle, index, little); root.updateMatrixWorld(true)
  const frame = calibrateHand(hand, index, middle, little, 'right')
  const grip = calibrateCupGrip(hand, raw, frame, 0.065)
  assert.ok(hand.localToWorld(grip.normalizedLocal.clone()).distanceTo(raw.localToWorld(grip.rawLocal.clone())) < 1e-10)
  hand.position.set(0.1, 0.7, 0.2); raw.position.copy(hand.position)
  hand.rotation.set(0.4, 0.2, -0.1); raw.quaternion.copy(hand.quaternion); root.updateMatrixWorld(true)
  assert.ok(hand.localToWorld(grip.normalizedLocal.clone()).distanceTo(raw.localToWorld(grip.rawLocal.clone())) < 1e-10)
  assert.ok(Math.abs(grip.rawFinger.dot(grip.rawPalm)) < 1e-10)
})

test('a picked-up cup is rigidly attached, without a second lagging position interpolation', () => {
  const table = new THREE.Vector3(0, 0, 0), owner = createCupAttachment(table)
  owner.update({ active: true, progress: 0.5, handGrip: table.clone() })
  assert.equal(owner.snapshot().attachment, 'rightHand')
  for (let index = 1; index <= 20; index++) {
    const handGrip = new THREE.Vector3(index * 0.01, index * 0.02, index * 0.03)
    owner.update({ active: true, progress: 1, handGrip })
    assert.deepEqual(owner.snapshot().position, handGrip.toArray())
    assert.equal(owner.snapshot().gripError, 0)
  }
})

test('cup returns to the table before detaching and reset removes held state', () => {
  const table = new THREE.Vector3(1, 2, 3), owner = createCupAttachment(table)
  owner.update({ active: true, progress: 0.5, handGrip: table.clone() })
  owner.update({ active: false, progress: 0.5, handGrip: new THREE.Vector3(1, 3, 3) })
  assert.equal(owner.snapshot().returning, true)
  owner.update({ active: false, handGrip: table.clone() })
  assert.equal(owner.snapshot().attachment, 'table')
  owner.update({ active: true, progress: 0.5, handGrip: table.clone() })
  owner.reset()
  assert.equal(owner.snapshot().attachment, 'table')
  assert.deepEqual(owner.snapshot().position, table.toArray())
})

test('unreachable, invalid and release-only grips do not fabricate a pickup', () => {
  const table = new THREE.Vector3(), owner = createCupAttachment(table)
  owner.update({ active: true, progress: 0.5, handGrip: new THREE.Vector3(2, 2, 2) })
  assert.equal(owner.snapshot().attachment, 'table')
  owner.update({ active: true, progress: 0.5, handGrip: table.clone(), releasing: true })
  assert.equal(owner.snapshot().attachment, 'table')
  owner.update({ active: true, progress: 0.5, handGrip: new THREE.Vector3(NaN, 0, 0) })
  assert.deepEqual(owner.snapshot().position, table.toArray())
})

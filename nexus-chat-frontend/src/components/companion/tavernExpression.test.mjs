import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import * as THREE from 'three'
import { VRMExpression, VRMExpressionManager, VRMExpressionMorphTargetBind } from '@pixiv/three-vrm'
import { createTavernExpression, REGION_BUDGETS } from './tavernExpression.js'
import { registerTavernProfile } from './tavernModelProfile.js'

const bytes = readFileSync(new URL('../../../public/models/SpringSnow.vrm', import.meta.url))
let json, binOffset, cursor = 12
while (cursor < bytes.length) {
  const size = bytes.readUInt32LE(cursor), kind = bytes.readUInt32LE(cursor + 4)
  if (kind === 0x4e4f534a) json = JSON.parse(bytes.subarray(cursor + 8, cursor + 8 + size).toString())
  if (kind === 0x004e4942) binOffset = cursor + 8
  cursor += size + 8
}
const v0 = { neutral: 'neutral', a: 'aa', i: 'ih', u: 'ou', e: 'ee', o: 'oh',
  blink: 'blink', blink_l: 'blinkLeft', blink_r: 'blinkRight', angry: 'angry', fun: 'relaxed', joy: 'happy', sorrow: 'sad' }
function attribute(index) {
  const accessor = json.accessors[index], view = json.bufferViews[accessor.bufferView]
  assert.equal(accessor.componentType, 5126)
  const array = new Float32Array(accessor.count * 3)
  const start = binOffset + (view.byteOffset || 0) + (accessor.byteOffset || 0)
  for (let vertex = 0; vertex < accessor.count; vertex++) for (let axis = 0; axis < 3; axis++) {
    array[vertex * 3 + axis] = bytes.readFloatLE(start + vertex * (view.byteStride || 12) + axis * 4)
  }
  return new THREE.BufferAttribute(array, 3)
}
function fixture(t, fine = true) {
  const primitive = json.meshes[0].primitives[0], names = json.meshes[0].extras.targetNames
  const geometry = new THREE.BufferGeometry()
  geometry.setAttribute('position', attribute(primitive.attributes.POSITION))
  geometry.morphTargetsRelative = true
  geometry.morphAttributes.position = primitive.targets.map((target, index) => {
    const value = attribute(target.POSITION); value.name = names[index]; return value
  })
  const material = new THREE.MeshBasicMaterial()
  const meshes = [new THREE.Mesh(geometry, material), new THREE.Mesh(geometry, material)]
  const scene = new THREE.Group()
  if (fine) scene.add(...meshes)
  const manager = new VRMExpressionManager()
  for (const group of json.extensions.VRM.blendShapeMaster.blendShapeGroups) {
    const expression = new VRMExpression(v0[group.presetName] || group.name)
    for (const bind of group.binds) expression.addBind(new VRMExpressionMorphTargetBind({ primitives: meshes, index: bind.index, weight: bind.weight / 100 }))
    manager.registerExpression(expression)
  }
  const vrm = { scene, expressionManager: manager, humanoid: { getNormalizedBoneNode: () => null } }
  t.after(() => { geometry.dispose(); material.dispose() })
  return { vrm, manager, meshes }
}

test('real SpringSnow deltas register regional expressions with the real 2.1 API', t => {
  const { vrm, manager, meshes } = fixture(t)
  const expression = createTavernExpression(vrm, json)
  assert.equal(expression.profile.fine, true)
  assert.equal(expression.profile.capabilities.registeredExpressions.length, 14)
  assert.equal(expression.profile.capabilities.asymmetricSmile, false)
  assert.ok(Object.keys(expression.profile.channels).every(name => !/HA_|SkinFung|Natural/.test(name)))
  expression.update({ mood: 'happy', settled: true })
  manager.update()
  for (const mesh of meshes) {
    assert.equal(mesh.morphTargetInfluences[3], 0, 'whole-face Joy must remain zero')
    assert.ok(mesh.morphTargetInfluences[8] > 0, 'brow Joy is actually applied')
    assert.ok(mesh.morphTargetInfluences[17] > 0, 'eye Joy is actually applied')
    assert.ok(mesh.morphTargetInfluences[33] > 0, 'mouth Joy is actually applied')
  }
  const count = manager.expressions.length
  registerTavernProfile(vrm, json)
  assert.equal(manager.expressions.length, count, 'registration is idempotent')
})

test('blink and full-open speech suppress conflicting regional eye and mouth shapes', t => {
  const { vrm, manager, meshes } = fixture(t)
  const expression = createTavernExpression(vrm, json)
  expression.update({ mood: 'happy', blink: 1, articulation: { mouth: 'aa', open: 0.48 }, settled: true })
  manager.update()
  assert.equal(meshes[0].morphTargetInfluences[17], 0)
  assert.equal(meshes[0].morphTargetInfluences[33], 0)
  assert.equal(meshes[0].morphTargetInfluences[13], 1)
  assert.equal(meshes[0].morphTargetInfluences[39], 0.48)
  assert.ok(meshes[0].morphTargetInfluences[8] > 0, 'speech must preserve independent brows')
})

test('all moods and rapid vowel changes remain within regional budgets', t => {
  const { vrm } = fixture(t)
  const expression = createTavernExpression(vrm, json)
  const moods = ['calm', 'warm', 'happy', 'shy', 'sad', 'worried', 'angry', 'surprised', 'tired', 'thoughtful']
  for (let frame = 0; frame < 900; frame++) {
    expression.update({ mood: moods[Math.floor(frame / 90)], delta: 1 / 60,
      articulation: { mouth: ['aa', 'ih', 'ou', 'ee', 'oh'][frame % 5], open: 1 } })
    vrm.expressionManager.update()
    const state = expression.snapshot()
    for (const region of ['brow', 'eye', 'mouth']) {
      const sum = Object.entries(expression.profile.channels).filter(([name]) => name.startsWith(region))
        .reduce((total, [, target]) => total + state.weights[target], 0)
      assert.ok(sum <= REGION_BUDGETS[region] + 1e-9, region + ' actual manager budget')
    }
    assert.ok(Object.values(state.vowels).reduce((sum, value) => sum + value, 0) <= REGION_BUDGETS.vowels + 1e-9)
    assert.ok(Object.values(state.weights).every(value => Number.isFinite(value) && value >= 0 && value <= 1))
  }
})

test('unsupported fine families fall back to existing bound expressions without losing emotion', t => {
  const { vrm, manager, meshes } = fixture(t, false)
  const expression = createTavernExpression(vrm, json)
  assert.equal(expression.profile.fine, false)
  expression.update({ mood: 'sad', settled: true })
  manager.update()
  assert.ok(meshes[0].morphTargetInfluences[4] > 0)
  assert.equal(expression.snapshot().mode, 'preset')
})

test('supported left and right eye expressions are independent and still yield to blink', t => {
  const { vrm, manager, meshes } = fixture(t)
  const expression = createTavernExpression(vrm, json)
  expression.update({ mood: 'shy', settled: true }); manager.update()
  assert.equal(meshes[0].morphTargetInfluences[19], 0.1)
  assert.equal(meshes[0].morphTargetInfluences[18], 0.08)
  assert.equal(meshes[0].morphTargetInfluences[17], 0)
  expression.update({ mood: 'shy', blink: 1, settled: true }); manager.update()
  assert.equal(meshes[0].morphTargetInfluences[19], 0)
  assert.equal(meshes[0].morphTargetInfluences[18], 0)
})

test('reduced motion and sip block immediately stop mouth and blink', t => {
  const { vrm, manager, meshes } = fixture(t)
  const expression = createTavernExpression(vrm, json)
  expression.update({ mood: 'angry', blink: 1, articulation: { mouth: 'aa', open: 0.48 }, reducedMotion: true })
  manager.update()
  assert.equal(meshes[0].morphTargetInfluences[39], 0)
  assert.equal(meshes[0].morphTargetInfluences[13], 0)
  expression.update({ mood: 'angry', articulation: { mouth: 'aa', open: 0.48 }, blockMouth: true })
  manager.update()
  assert.equal(meshes[0].morphTargetInfluences[39], 0)
  expression.reset(); manager.update()
  assert.ok(meshes[0].morphTargetInfluences.every(value => value === 0))
})

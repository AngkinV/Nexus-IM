import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { expressionBindings, createVrmExpressions } from './vrmExpressions.js'
import { characterModelSource } from './tavernStage.js'

function modelJson(name) {
  const buffer = readFileSync(new URL(`../../../public/models/${name}.vrm`, import.meta.url))
  return JSON.parse(buffer.subarray(20, 20 + buffer.readUInt32LE(12)).toString())
}

test('Yuuka uses working custom expressions instead of empty standard presets', () => {
  const bindings = expressionBindings(modelJson('YuukaPajamas6th103'))
  assert.deepEqual(bindings.blink, [['Blink', 1]])
  assert.ok(bindings.happy.some(([name]) => name === 'mouthSmileLeft'))
  assert.ok(bindings.sad.some(([name]) => name === 'browInnerUp'))
  assert.ok(bindings.angry.some(([name]) => name === 'browDownLeft'))
})

test('SpringSnow keeps its working standard expressions and custom surprise', () => {
  const bindings = expressionBindings(modelJson('SpringSnow'))
  assert.deepEqual(bindings.happy, [['happy', 1]])
  assert.deepEqual(bindings.blink, [['blink', 1]])
  assert.deepEqual(bindings.surprised, [['Surprised', 1]])
})

test('all bundled characters expose five usable vowel bindings', () => {
  for (const model of ['SpringSnow', 'default', 'YuukaPajamas6th103']) {
    const bindings = expressionBindings(modelJson(model))
    for (const mouth of ['aa', 'ih', 'ou', 'ee', 'oh']) assert.ok(bindings[mouth].length, `${model}: ${mouth}`)
  }
})

test('shared morphs compose without overriding each other and return to neutral', () => {
  const weights = new Map()
  const adapter = createVrmExpressions({ expressionManager: { setValue: (name, value) => weights.set(name, value) } }, modelJson('YuukaPajamas6th103'))
  adapter.setValue('happy', 1)
  adapter.setValue('relaxed', 0.5)
  adapter.update()
  assert.equal(weights.get('mouthSmileLeft'), 0.8)
  adapter.setValue('happy', 0)
  adapter.update()
  assert.equal(weights.get('mouthSmileLeft'), 0.15)
  adapter.setValue('relaxed', 0)
  adapter.update()
  assert.ok([...weights.values()].every(value => value === 0))
})

test('VRM 1 expressions are supported without inventing missing morphs', () => {
  const bindings = expressionBindings({ extensions: { VRMC_vrm: { expressions: { preset: {
    happy: { morphTargetBinds: [{}] }, blink: {}, sad: { materialColorBinds: [{}] }
  } } } } })
  assert.deepEqual(bindings.happy, [['happy', 1]])
  assert.deepEqual(bindings.blink, [])
  assert.deepEqual(bindings.sad, [['sad', 1]])
})

test('unbound guests remain silhouettes and existing VRM assignments take precedence', () => {
  assert.equal(characterModelSource(null), null)
  assert.equal(characterModelSource({ kind: 'guest' }), null)
  assert.equal(characterModelSource({ kind: 'resident' }), '/models/SpringSnow.vrm')
  assert.equal(characterModelSource({ kind: 'guest', modelUrl: '/models/custom.vrm' }), '/models/custom.vrm')
  assert.equal(characterModelSource({ modelUrl: '/files/123', modelType: 'vrm' }), '/files/123')
  assert.equal(characterModelSource({ modelUrl: '/models/actor.glb', modelType: 'gltf' }), null)
})

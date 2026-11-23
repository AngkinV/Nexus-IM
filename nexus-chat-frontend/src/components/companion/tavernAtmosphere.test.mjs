import test from 'node:test'
import assert from 'node:assert/strict'
import { createTavernAtmosphere, ATMOSPHERE_LIMITS } from './tavernAtmosphere.js'

const scenes = ['rain-fireplace', 'late-rain', 'empty-bar', 'closing-time']
const moods = ['calm', 'warm', 'happy', 'shy', 'sad', 'worried', 'angry', 'surprised', 'tired', 'thoughtful']

test('mood only changes bounded visual bias and does not change scene or its props', () => {
  for (const scene of scenes) {
    const air = createTavernAtmosphere(); air.setScene(scene)
    const original = air.snapshot().parameters
    for (const mood of moods) {
      air.setMood(mood)
      let state
      for (let index = 0; index < 180; index++) state = air.update(1 / 60)
      assert.equal(air.snapshot().scene, scene)
      for (const [name, bounds] of Object.entries(ATMOSPHERE_LIMITS)) assert.ok(state[name] >= bounds[0] && state[name] <= bounds[1], name)
      for (const name of ['window', 'hearth', 'lampSwing']) assert.equal(state[name], original[name], 'mood must not invent ' + name)
      assert.ok(Math.abs(state.key / original.key - 1) < 0.065)
    }
  }
})

test('mood transitions are slow, frame-rate-independent and repeated scene assignment is inert', () => {
  const build = fps => {
    const air = createTavernAtmosphere(); air.setScene('rain-fireplace'); air.setMood('happy')
    const first = air.update(1 / fps)
    assert.ok(first.key < 0.86 * 1.06)
    for (let frame = 1; frame < fps * 3; frame++) air.update(1 / fps)
    const state = air.snapshot()
    assert.equal(air.setScene('rain-fireplace'), false)
    assert.equal(air.snapshot().elapsed, state.elapsed)
    return state.parameters
  }
  const thirty = build(30), sixty = build(60)
  for (const name of Object.keys(thirty)) assert.ok(Math.abs(thirty[name] - sixty[name]) < 1e-9)
})

test('reduced motion freezes flicker and lamp, missing rooms use a bare neutral scene', () => {
  const air = createTavernAtmosphere(); air.setScene('closing-time')
  assert.equal(air.update(0.1, { reducedMotion: true }).lampRotation, 0)
  assert.equal(air.snapshot().elapsed, 0)
  air.setScene('rain-fireplace')
  assert.equal(air.update(0.1, { reducedMotion: true }).flicker, 0)
  air.setScene('missing-room')
  const bare = air.update(0.1)
  assert.equal(bare.hearthLight, 0)
  assert.equal(bare.window, 0)
  assert.equal(bare.lampRotation, 0)
})

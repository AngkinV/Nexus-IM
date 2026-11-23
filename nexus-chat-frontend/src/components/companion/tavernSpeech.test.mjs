import test from 'node:test'
import assert from 'node:assert/strict'
import { createTavernSpeech, narrationGesture } from './tavernSpeech.js'
import { createTavernGestures, GESTURE_NAMES, seatedPose } from './tavernGestures.js'
import { createTavernLife } from './tavernLife.js'
import { styleTavernMaterial } from './tavernMaterial.js'
import { MToonMaterial } from '@pixiv/three-vrm'

const run = (speech, seconds = 2) => Array.from({ length: seconds * 100 }, () => speech.update(0.01))

test('articulation follows text and rests when no words arrive', () => {
  const speech = createTavernSpeech()
  assert.ok(run(speech).every(frame => frame.open === 0))
  speech.push('aiueo')
  const frames = run(speech)
  assert.deepEqual(new Set(frames.map(frame => frame.mouth).filter(Boolean)), new Set(['aa', 'ih', 'ou', 'ee', 'oh']))
  assert.equal(frames.at(-1).open, 0)
})

test('all stream chunk boundaries preserve narration silence and cues', () => {
  const text = '（她挥了挥手。）*耸肩* (sips tea)'
  for (let size = 1; size <= text.length; size++) {
    const speech = createTavernSpeech()
    for (let i = 0; i < text.length; i += size) speech.push(text.slice(i, i + size))
    const frames = run(speech)
    assert.ok(frames.every(frame => frame.open === 0))
    assert.deepEqual(frames.map(frame => frame.gesture).filter(Boolean), ['wave', 'shrug', 'sip'])
  }
})

test('pausing drops playback but preserves narration across hidden chunks', () => {
  const speech = createTavernSpeech()
  speech.push('你好（她抬起')
  speech.pause()
  assert.equal(speech.snapshot().queued, 0)
  speech.push('头望向', true)
  speech.push('窗外。）')
  assert.ok(run(speech).every(frame => frame.open === 0))
  speech.push('现在可以聊聊了。')
  assert.ok(run(speech).some(frame => frame.open > 0))
  speech.pause()
  speech.push('（挥手）再见', true)
  assert.ok(run(speech).every(frame => frame.open === 0 && !frame.gesture))
})

test('duplicate state and narration cues do not restart the same gesture', () => {
  const gestures = createTavernGestures()
  gestures.play('wave')
  gestures.update(0.3)
  gestures.play('wave')
  assert.equal(gestures.snapshot().age, 0.3)
  gestures.play('rest')
  assert.equal(gestures.snapshot().active, 'rest')
})

test('punctuation closes the mouth and does not invent an emotion', () => {
  const speech = createTavernSpeech()
  speech.push('，。！？…')
  const frames = run(speech)
  assert.ok(frames.every(frame => frame.open === 0 && !frame.gesture))
  assert.ok(frames.some(frame => frame.emphasis > 0))
})

test('unfinished narration is never spoken; stop clears parser and queue', () => {
  const speech = createTavernSpeech()
  speech.push('（未完成的旁白')
  assert.ok(run(speech).every(frame => frame.open === 0))
  speech.reset()
  speech.push('你好')
  assert.ok(run(speech).some(frame => frame.open > 0))
  speech.reset()
  assert.equal(speech.snapshot().queued, 0)
})

test('fast streams have bounded backlog and finish within 1.2 seconds', () => {
  const speech = createTavernSpeech()
  speech.push('好'.repeat(10000))
  assert.ok(speech.snapshot().queued <= 96)
  speech.finish()
  const frames = run(speech, 2)
  assert.ok(frames.slice(120).every(frame => frame.open === 0))
  assert.equal(speech.snapshot().queued, 0)
})

test('only explicit affirmative narration produces gestures', () => {
  assert.equal(narrationGesture('她没有挥手'), null)
  assert.equal(narrationGesture('天色很好'), null)
  assert.equal(narrationGesture('她托腮望着窗外'), 'chin')
})

test('gestures remain bounded, return to rest and respect reduced motion', () => {
  for (const name of GESTURE_NAMES) {
    const track = createTavernGestures()
    track.play(name)
    for (let i = 0; i < 500; i++) {
      const frame = track.update(0.02)
      assert.ok(frame.weight >= 0 && frame.weight <= 1)
      assert.ok([frame.lift.left, frame.lift.right, frame.roll].every(value => Number.isFinite(value) && Math.abs(value) < Math.PI))
    }
    assert.equal(track.snapshot().active, 'rest')
    track.play(name)
    assert.equal(track.update(0.1, 'idle', true).active, 'rest')
  }
})

test('physiological motion is subtle, variable and disabled by reduced motion', () => {
  const life = createTavernLife(() => 0.5)
  const frames = Array.from({ length: 1800 }, () => life.update(1 / 30))
  assert.ok(frames.some(frame => frame.blink > 0.8))
  assert.ok(frames.every(frame => frame.blink >= 0 && frame.blink <= 1 && Math.abs(frame.sway) <= 0.018))
  assert.deepEqual(life.update(1, true), { blink: 0, breath: 0, sway: 0, drift: 0, eyeX: 0, eyeY: 0 })
})

test('seated profiles are stable, varied and independently mutable', () => {
  const seats = [0, 1, 2].map(seatedPose)
  assert.equal(new Set(seats.map(seat => seat.name)).size, 3)
  assert.equal(new Set(seats.map(seat => seat.yaw)).size, 3)
  // The right hand always rests by the glass; the left varies.
  assert.ok(seats.every(seat => seat.right === 'table' && ['table', 'across'].includes(seat.left)))
  assert.equal(new Set(seats.map(seat => seat.left)).size, 2)
  for (const seed of [0, 1, 2, -1, 4294967295]) {
    assert.deepEqual(seatedPose(seed), seatedPose(seed))
    assert.ok(Number.isFinite(seatedPose(seed).hipY))
  }
  seats[0].yaw = 99
  assert.notEqual(seatedPose(0).yaw, 99)
})

test('lighting preserves model colors, MToon outline variants and compile hooks', () => {
  const surface = new MToonMaterial()
  const outline = new MToonMaterial({ isOutline: true })
  const light = { value: 1 }
  for (const material of [surface, outline]) styleTavernMaterial(material, light)
  assert.notEqual(surface.customProgramCacheKey(), outline.customProgramCacheKey())
  const shader = { uniforms: {}, vertexShader: '', fragmentShader: 'void main() { gl_FragColor=vec4(1.0); }' }
  outline.onBeforeCompile(shader)
  assert.match(shader.fragmentShader, /#define OUTLINE true/)
  assert.doesNotMatch(shader.fragmentShader, /float ink|dot\(gl_FragColor\.rgb/)
  assert.match(shader.fragmentShader, /gl_FragColor\.rgb \*= tavernLight/)
  assert.equal(surface.clippingPlanes, null)
  assert.equal(shader.uniforms.tavernLight, light)
  surface.dispose()
  outline.dispose()
})

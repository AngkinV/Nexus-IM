import test from 'node:test'
import assert from 'node:assert/strict'
import * as THREE from 'three'
import { createTavernGestures, GESTURE_NAMES } from './tavernGestures.js'
import { createTavernSpeech, narrationGesture } from './tavernSpeech.js'
import { createTavernGaze } from './tavernGaze.js'
import { stageReplyMeta, interruptedStageReply } from './tavernEvents.js'
const advance = (track, seconds, options = {}) => {
  let frame
  for (let index = 0; index < Math.ceil(seconds * 60); index++) frame = track.update(1 / 60, 'idle', false, options)
  return frame
}

test('state and narration share bounded turn-scoped deduplication after an action ends', () => {
  const track = createTavernGestures()
  assert.equal(track.play('wave', { turnId: 'one', source: 'state' }), true)
  advance(track, 0.6)
  const age = track.snapshot().age
  assert.equal(track.play('wave', { turnId: 'one', source: 'narration' }), false)
  assert.equal(track.snapshot().age, age)
  advance(track, 4)
  assert.equal(track.play('wave', { turnId: 'one', source: 'narration' }), false)
  assert.equal(track.play('wave', { turnId: 'two', source: 'state' }), true)
  for (let index = 0; index < 200; index++) track.play('wave', { turnId: 'turn-' + index })
  assert.ok(track.snapshot().cachedCues <= 64)
})

test('explicit state owns preparing even before the first frame', () => {
  const track = createTavernGestures()
  track.play('wave', { turnId: 'one', source: 'state' })
  assert.equal(track.play('sip', { turnId: 'one', source: 'narration' }), false)
  assert.equal(track.snapshot().active, 'wave')
  track.update(0.01)
  assert.equal(track.play('chin', { turnId: 'one', source: 'doing' }), false)
  assert.equal(track.snapshot().active, 'wave')
})

test('every supported gesture has preparing, holding, returning and rest phases', () => {
  for (const name of GESTURE_NAMES.filter(name => name !== 'rest')) {
    const track = createTavernGestures()
    track.play(name, { turnId: name })
    const phases = new Set()
    for (let index = 0; index < 300; index++) {
      const frame = track.update(1 / 60)
      phases.add(frame.phase)
      assert.ok(frame.weight >= 0 && frame.weight <= 1)
      assert.ok(Object.values(frame.pose).flat().every(Number.isFinite))
    }
    assert.deepEqual(phases, new Set(['preparing', 'holding', 'returning', 'rest']))
  }
})

test('wave exposes changing wrist pose and two-handed actions declare both owners', () => {
  const wave = createTavernGestures(); wave.play('wave')
  const wrist = []
  for (let index = 0; index < 100; index++) wrist.push(wave.update(1 / 60).pose.rightHand[2])
  assert.ok(Math.max(...wrist) - Math.min(...wrist) > 0.2)
  for (const name of ['cross', 'shrug', 'wipe']) {
    const track = createTavernGestures(); track.play(name)
    assert.deepEqual(advance(track, 0.8).owners, ['leftHand', 'rightHand'])
  }
})

test('cancellation releases smoothly while pause and reduced motion clear transients', () => {
  const track = createTavernGestures(); track.play('chin', { turnId: 'one' })
  advance(track, 0.8)
  track.cancel('abort')
  assert.equal(track.snapshot().phase, 'returning')
  assert.ok(track.snapshot().weight > 0)
  assert.equal(advance(track, 1).active, 'rest')
  track.play('sip', { turnId: 'two' })
  advance(track, 0.8)
  assert.equal(track.update(0.1, 'think', true).active, 'rest')
  assert.equal(track.play('sip', { turnId: 'two', source: 'narration' }), false)
  assert.equal(track.play('sip', { turnId: 'three' }), true)
})

test('old and malformed cues do not start actions; sip releases when speech starts', () => {
  const track = createTavernGestures({ now: () => 5000 })
  assert.equal(track.play('sip', { receivedAt: 1 }), false)
  assert.equal(track.play('dance'), false)
  track.play('sip', { turnId: 'fresh' })
  const held = advance(track, 1.1)
  assert.equal(held.blocksMouth, true)
  advance(track, 0.15, { speaking: true })
  assert.equal(track.snapshot().reason, 'speaking')
  assert.equal(advance(track, 1).active, 'rest')
})

test('negation, recollection, hypothetical and other-subject narration are not instructions', () => {
  for (const text of ['她没有抿茶', '昨天她挥手', '如果她喝一口', '他喝了一口，我看着', '客人挥手', 'she did not sip tea', 'he waves']) {
    assert.equal(narrationGesture(text), null, text)
  }
  assert.equal(narrationGesture('她托腮望着窗外'), 'chin')
  assert.equal(narrationGesture('sips tea'), 'sip')
})

test('pause discards unfinished narration, and a new turn can speak immediately after finish', () => {
  const speech = createTavernSpeech()
  speech.push('（托着下巴')
  speech.pause()
  speech.pause()
  speech.push('看向窗外', true)
  speech.push('）现在说话')
  const frames = Array.from({ length: 150 }, () => speech.update(0.01))
  assert.ok(frames.every(frame => !frame.gesture))
  assert.ok(frames.some(frame => frame.open > 0))
  speech.finish(); speech.pause(); speech.push('新的回复')
  assert.ok(Array.from({ length: 100 }, () => speech.update(0.01)).some(frame => frame.open > 0))
  speech.cancel()
  assert.equal(speech.snapshot().queued, 0)
})

test('official lookAt owns eyes, action targets outrank pointer and reduced motion disables auto-update', () => {
  const lookAt = { autoUpdate: true, reset() { this.yaw = 0; this.pitch = 0 } }
  const gaze = createTavernGaze({ lookAt })
  const head = new THREE.Vector3(0, 0.5, 0), partner = new THREE.Vector3(0, 0.1, 6.4)
  gaze.update({ head, partner, action: 'sip', actionWeight: 1, actionTarget: new THREE.Vector3(0.3, -1, 1), pointerX: 1, delta: 0.1 })
  assert.equal(gaze.snapshot().source, 'action')
  gaze.update({ head, partner, reducedMotion: true })
  assert.equal(lookAt.autoUpdate, false)
  assert.equal(gaze.snapshot().source, 'static')
  assert.deepEqual(gaze.snapshot().headFollow, { yaw: 0, pitch: 0 })
  gaze.update({ head, partner, delta: 0.1 })
  assert.equal(lookAt.autoUpdate, true)
  assert.ok(gaze.snapshot().target.every(Number.isFinite))
})

test('live routing IDs never come from stored or another role reply; all terminal failures stop the stage', () => {
  assert.deepEqual(stageReplyMeta(7, { id: 'live-abc', roleId: 7, live: true }, { mood: 'warm' }, 9),
    { roleId: 7, sessionId: 9, turnId: 'live-abc', source: 'state' })
  assert.equal(stageReplyMeta(7, { id: 42, roleId: 7 }, null).source, 'stored')
  assert.equal(stageReplyMeta(7, { id: 'live-abc', roleId: 8, live: true }, {}).turnId, null)
  for (const flag of ['failed', 'stopped', 'partial', 'modelError']) assert.equal(interruptedStageReply({ [flag]: true }), true)
  assert.equal(interruptedStageReply({ content: '正常回复' }), false)
})

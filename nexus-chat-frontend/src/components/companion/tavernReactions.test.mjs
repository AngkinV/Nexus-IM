import test from 'node:test'
import assert from 'node:assert/strict'
import { createTavernBeats, BEATS, BEAT_NAMES } from './tavernBeats.js'
import { createTavernDirector, DIRECTOR_TIMING } from './tavernDirector.js'
import { createTavernSpeech, narrationBeat, narrationGesture, spokenBeat } from './tavernSpeech.js'

const seeded = (seed = 1) => () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 4294967296 }
const play = (beats, seconds) => Array.from({ length: Math.ceil(seconds * 60) }, () => ({ ...beats.update(1 / 60), accent: { ...beats.update(0).accent } }))

test('every beat starts and ends at zero, stays small, and never names an arm', () => {
  for (const name of BEAT_NAMES) {
    const beats = createTavernBeats({ random: seeded(7) })
    assert.equal(beats.play(name), true, name)
    const frames = []
    for (let index = 0; index < BEATS[name].duration * 60 + 30; index++) {
      const frame = beats.update(1 / 60)
      frames.push({ pitch: frame.pitch, yaw: frame.yaw, roll: frame.roll, lean: frame.lean, sway: frame.sway, shoulders: frame.shoulders, blink: frame.blink, gazeWeight: frame.gazeWeight })
      assert.deepEqual(Object.keys(frame).sort(), ['accent', 'blink', 'gaze', 'gazeWeight', 'lean', 'pitch', 'roll', 'shoulders', 'sway', 'yaw'])
      for (const value of Object.values(frame.accent)) assert.ok(value >= 0 && value <= 0.5)
    }
    for (const frame of frames) {
      assert.ok(Math.abs(frame.pitch) <= 0.2 && Math.abs(frame.yaw) <= 0.3 && Math.abs(frame.roll) <= 0.16, name)
      assert.ok(Math.abs(frame.lean) <= 0.12 && Math.abs(frame.shoulders) <= 0.6 && frame.blink >= 0 && frame.blink <= 1, name)
    }
    const first = frames[0], last = frames.at(-1)
    for (const key of ['pitch', 'yaw', 'roll', 'lean', 'sway', 'shoulders']) {
      assert.ok(Math.abs(first[key]) < 0.03, `${name} ${key} starts at ${first[key]}`)
      assert.equal(last[key], 0, `${name} ${key} ends`)
    }
    assert.ok(frames.some(frame => Object.values(frame).some(value => Math.abs(value) > 0.02)), name + ' is visible')
    assert.equal(beats.active(), null)
  }
})

test('a nod dips the head twice; a shake turns it both ways', () => {
  const nod = createTavernBeats(); nod.play('nod')
  const pitch = Array.from({ length: 70 }, () => nod.update(1 / 60).pitch)
  let dips = 0
  for (let index = 1; index < pitch.length - 1; index++) if (pitch[index] > 0.04 && pitch[index] >= pitch[index - 1] && pitch[index] > pitch[index + 1]) dips++
  assert.equal(dips, 2)
  const shake = createTavernBeats(); shake.play('shake')
  const yaw = Array.from({ length: 70 }, () => shake.update(1 / 60).yaw)
  assert.ok(Math.max(...yaw) > 0.08 && Math.min(...yaw) < -0.08)
})

test('beats have a repeat gap, do not cut off one that just began, cross-fade, and stop for reduced motion', () => {
  const beats = createTavernBeats({ random: seeded(3) })
  assert.equal(beats.play('tilt'), true)
  beats.update(0.1)
  assert.equal(beats.play('nod'), false)
  assert.equal(beats.play('unknown'), false)
  for (let index = 0; index < 60; index++) beats.update(1 / 60)
  assert.equal(beats.play('nod'), true)
  assert.equal(beats.snapshot().fading, 'tilt')
  const blended = beats.update(1 / 60)
  assert.ok(Math.abs(blended.roll) > 0.02 && Number.isFinite(blended.pitch))
  for (let index = 0; index < 80; index++) beats.update(1 / 60)
  assert.equal(beats.play('nod'), false)
  assert.equal(beats.play('nod', { force: true }), true)
  const still = beats.update(0.1, true)
  assert.deepEqual([still.pitch, still.yaw, still.roll, still.lean, still.blink, still.gaze], [0, 0, 0, 0, 0, null])
  assert.equal(beats.active(), null)
  assert.equal(beats.snapshot().stats.skipped, 3)
})

test('lookAway honours the requested side and strength scales the motion', () => {
  const left = createTavernBeats(); left.play('lookAway', { side: 1 })
  const right = createTavernBeats(); right.play('lookAway', { side: -1 })
  for (let index = 0; index < 60; index++) { left.update(1 / 60); right.update(1 / 60) }
  assert.ok(left.update(0).yaw > 0.1 && right.update(0).yaw < -0.1)
  assert.equal(left.update(0).gaze, 'scene')
  const soft = createTavernBeats(); soft.play('leanIn', { strength: 0.5 })
  const full = createTavernBeats(); full.play('leanIn')
  for (let index = 0; index < 70; index++) { soft.update(1 / 60); full.update(1 / 60) }
  assert.ok(Math.abs(soft.update(0).lean * 2 - full.update(0).lean) < 1e-9)
})

test('narrated reactions are recognised; negation, other subjects and plain talk are not', () => {
  const cases = { '点了点头': 'nod', '轻轻摇头': 'shake', '歪了歪头': 'tilt', '叹了口气': 'sigh', '轻笑了一声': 'laugh', '微笑着': 'smile',
    '望向窗外的雨': 'lookAway', '垂下眼': 'lookDown', '身子微微前倾': 'leanIn', '往后靠了靠': 'leanBack', '愣了一下': 'perk',
    'nods slowly': 'nod', 'shakes her head': 'shake', 'tilts her head': 'tilt', 'sighs': 'sigh', 'chuckles': 'laugh', 'looks away': 'lookAway', 'leans in': 'leanIn' }
  for (const [text, beat] of Object.entries(cases)) assert.equal(narrationBeat(text), beat, text)
  for (const text of ['没有点头', '他点了点头', '如果她笑了', '昨天她叹气', '天色很好', 'he nods', 'she did not smile']) assert.equal(narrationBeat(text), null, text)
  // An arm gesture and a head beat can share one narration.
  assert.equal(narrationGesture('托着下巴，轻轻点头'), 'chin')
  assert.equal(narrationBeat('托着下巴，轻轻点头'), 'nod')
})

test('only words that are themselves a reaction count as spoken cues', () => {
  for (const [text, beat] of [['嗯', 'nod'], ['嗯嗯', 'nod'], ['好的', 'nod'], ['当然', 'nod'], ['yes', 'nod'], ['不是', 'shake'], ['no', 'shake'], ['唉', 'sigh']]) assert.equal(spokenBeat(text), beat, text)
  for (const text of ['', '嗯这个问题很复杂', '不知道怎么说', '好像是这样', '对面的窗', 'nothing']) assert.equal(spokenBeat(text), null, text)
})

test('the speech stream delivers narration beats, opener beats, laughter and questions in order', () => {
  const speech = createTavernSpeech()
  speech.push('嗯，我在听。（轻轻点了点头）哈哈，真的吗？')
  const seen = []
  for (let index = 0; index < 600; index++) {
    const frame = speech.update(0.01)
    if (frame.beat) seen.push('narration:' + frame.beat)
    if (frame.spoken) seen.push('spoken:' + frame.spoken)
  }
  assert.deepEqual(seen, ['spoken:nod', 'narration:nod', 'spoken:laugh', 'spoken:tilt'])
  // Chunk boundaries do not change what is found.
  const chunked = createTavernSpeech()
  for (const ch of '嗯，我在听。（轻轻点了点头）哈哈，真的吗？') chunked.push(ch)
  const again = []
  for (let index = 0; index < 600; index++) {
    const frame = chunked.update(0.01)
    if (frame.beat) again.push('narration:' + frame.beat)
    if (frame.spoken) again.push('spoken:' + frame.spoken)
  }
  assert.deepEqual(again, seen)
  const silent = createTavernSpeech()
  silent.push('嗯，（点了点头）哈哈？', true)
  assert.ok(Array.from({ length: 200 }, () => silent.update(0.01)).every(frame => !frame.beat && !frame.spoken))
})

const runDirector = (director, seconds, options = {}) => {
  const cues = []
  for (let index = 0; index < seconds * 30; index++) cues.push(...director.update(1 / 30, options).cues)
  return cues
}

test('quiet stretches get sparse idle life that suits the mood and never repeats back to back', () => {
  const director = createTavernDirector({ random: seeded(11) })
  director.setMood('sad')
  const cues = runDirector(director, 300)
  assert.ok(cues.length >= 15 && cues.length <= 300 / DIRECTOR_TIMING.idleMin, String(cues.length))
  const beats = cues.filter(cue => cue.kind === 'beat').map(cue => cue.name)
  assert.ok(beats.every(name => ['lookDown', 'sigh', 'lookAway'].includes(name)), beats.join())
  assert.ok(beats.every((name, index) => index === 0 || name !== beats[index - 1]))
  // A sad mood does not absently tuck her hair, and she is not tending the bar here.
  assert.ok(cues.filter(cue => cue.kind === 'gesture').every(cue => cue.name === 'sip'))
})

test('arm gestures in idle are rare and wiping only happens where she tends the bar', () => {
  const quiet = createTavernDirector({ random: seeded(5) })
  quiet.setMood('calm')
  const plain = runDirector(quiet, 1200).filter(cue => cue.kind === 'gesture')
  assert.ok(plain.length >= 1 && plain.length <= 1200 / DIRECTOR_TIMING.armGap, String(plain.length))
  assert.ok(plain.every(cue => ['sip', 'tuck'].includes(cue.name)))
  const tending = createTavernDirector({ random: seeded(5) })
  tending.setTends(true)
  assert.ok(runDirector(tending, 2400).some(cue => cue.kind === 'gesture' && cue.name === 'wipe'))
})

test('she holds still while the user writes, while she talks, and while something else is in progress', () => {
  for (const arrange of [d => d.setListening(true), d => d.setSpeaking(true)]) {
    const director = createTavernDirector({ random: seeded(2) })
    arrange(director)
    assert.deepEqual(runDirector(director, 120), [])
  }
  const busy = createTavernDirector({ random: seeded(2) })
  assert.deepEqual(runDirector(busy, 120, { busy: true }), [])
  const still = createTavernDirector({ random: seeded(2) })
  assert.deepEqual(runDirector(still, 120, { reducedMotion: true }), [])
  const listening = createTavernDirector()
  listening.setListening(true)
  runDirector(listening, 4)
  assert.ok(listening.snapshot().posture.attention > 0.95 && listening.snapshot().posture.lean > 0.04)
  listening.setListening(false)
  runDirector(listening, 4)
  assert.ok(listening.snapshot().posture.lean < 0.002)
})

test('waiting for the model: at most an acknowledgement, then an occasional thinking glance', () => {
  const director = createTavernDirector({ random: seeded(9) })
  director.setWaiting(true)
  const cues = runDirector(director, 20)
  assert.ok(cues.length >= 2 && cues.length <= 5, String(cues.length))
  assert.ok(cues.every(cue => cue.kind === 'beat' && ['ack', 'lookAway', 'tilt', 'lookDown'].includes(cue.name)))
  assert.ok(director.snapshot().posture.thinking > 0.9)
  director.setWaiting(false)
  assert.deepEqual(runDirector(director, 3), [])
})

test('spoken cues are rate limited and every cue is delivered exactly once', () => {
  const director = createTavernDirector({ random: seeded(4) })
  director.setSpeaking(true)
  assert.equal(director.spoken('nod'), true)
  assert.equal(director.spoken('laugh'), false)
  assert.deepEqual(director.update(1 / 30).cues, [{ kind: 'beat', name: 'nod' }])
  assert.deepEqual(director.update(1 / 30).cues, [])
  runDirector(director, 3)
  assert.equal(director.spoken('laugh'), true)
  assert.equal(director.snapshot().stats.dropped, 1)
  director.reset()
  assert.deepEqual(director.update(1 / 30).cues, [])
})

import test from 'node:test'
import assert from 'node:assert/strict'
import { createTavernEntranceMusic, MUSIC_SCORE, renderTavernNote, renderTavernScore } from './tavernEntranceMusic.js'

const SR = 48000
function metrics(data) {
  let peak = 0, jump = 0, sum = 0, energy = 0
  for (let i = 0; i < data.length; i++) {
    assert.ok(Number.isFinite(data[i]))
    peak = Math.max(peak, Math.abs(data[i]))
    if (i) jump = Math.max(jump, Math.abs(data[i] - data[i - 1]))
    sum += data[i]; energy += data[i] ** 2
  }
  return { peak, jump, mean: sum / data.length, rms: Math.sqrt(energy / data.length) }
}
function spectralEnergy(data, f, from = 0.04, to = 0.55) {
  let re = 0, im = 0
  const a = Math.floor(from * SR), b = Math.min(data.length, Math.floor(to * SR))
  for (let i = a; i < b; i++) {
    const w = 0.5 - 0.5 * Math.cos(2 * Math.PI * (i - a) / (b - a - 1))
    re += data[i] * w * Math.cos(2 * Math.PI * f * i / SR)
    im += data[i] * w * Math.sin(2 * Math.PI * f * i / SR)
  }
  return re * re + im * im
}
test('actual score PCM: silent lead-in and ink blank, finite, bounded, smooth', () => {
  const data = renderTavernScore(SR)
  assert.equal(metrics(data.subarray(0, Math.round(0.72 * SR))).peak, 0)
  assert.equal(metrics(data.subarray(Math.ceil(4.4 * SR) + 2, Math.floor(5.1 * SR))).peak, 0)
  const m = metrics(data)
  assert.ok(m.peak > 0.03 && m.peak < 0.5, JSON.stringify(m))
  // Whole-score slopes include the normal oscillation of the D4/A4 notes, not just
  // boundary clicks. Compare that slope against the measured old noisy landing;
  // genuine attack/release discontinuities are checked separately below.
  assert.ok(m.jump < 0.022408044897019863 * 0.5, JSON.stringify(m))
  assert.equal(data[0], 0)
  assert.equal(data.at(-1), 0)
})
test('actual notes: DC-free tapered boundaries, fundamental plus decaying upper modes', () => {
  for (const cue of MUSIC_SCORE) {
    const data = renderTavernNote(cue.frequency, cue.duration, SR)
    const m = metrics(data)
    assert.ok(Math.abs(m.mean) < 1e-7, JSON.stringify(m))
    assert.ok(m.peak < 0.25 && m.jump < 0.02)
    assert.equal(data[0], 0); assert.equal(data.at(-1), 0)
    assert.ok(metrics(data.subarray(0, 48)).peak < 0.002)
    assert.ok(metrics(data.subarray(-48)).peak < 0.0001)
    assert.ok(metrics(data.subarray(0, 48)).jump < 0.00005, 'soft attack has no click')
    assert.ok(metrics(data.subarray(-48)).jump < 0.0000002, 'soft release has no hard cutoff')
    const fundamental = spectralEnergy(data, cue.frequency)
    assert.ok(fundamental > spectralEnergy(data, cue.frequency * 1.13) * 50)
    assert.ok(spectralEnergy(data, cue.frequency * 2) > fundamental * 0.008)
    const early = spectralEnergy(data, cue.frequency * 3, 0.04, 0.24) / spectralEnergy(data, cue.frequency, 0.04, 0.24)
    const late = spectralEnergy(data, cue.frequency * 3, 0.65, 0.85) / spectralEnergy(data, cue.frequency, 0.65, 0.85)
    assert.ok(late < early)
    assert.deepEqual(data, renderTavernNote(cue.frequency, cue.duration, SR))
  }
})

// Mock only Web Audio plumbing. Buffers contain actual synthesized PCM, and
// scheduling assertions inspect source calls rather than score constants alone.
class Param {
  value = 1
  events = []
  cancelScheduledValues(t) { this.events.push(['cancel', t]) }
  setValueAtTime(v, t) { this.events.push(['set', v, t]); this.value = v }
  linearRampToValueAtTime(v, t) { this.events.push(['ramp', v, t]) }
}
class Node {
  gain = new Param()
  pan = new Param()
  playbackRate = new Param()
  disconnected = false
  connect() {}
  disconnect() { this.disconnected = true }
}
class Audio {
  static instances = []
  sampleRate = SR
  currentTime = 0
  state = 'suspended'
  destination = new Node()
  sources = []
  nodes = []
  constructor() { Audio.instances.push(this) }
  createGain() { const n = new Node(); this.nodes.push(n); return n }
  createStereoPanner() { return this.createGain() }
  createBuffer(channels, n, rate) {
    const data = new Float32Array(n)
    return { duration: n / rate, copyToChannel: d => data.set(d), getChannelData: () => data }
  }
  createBufferSource() {
    const n = new Node()
    n.starts = []; n.stops = []
    n.start = t => n.starts.push(t)
    n.stop = t => n.stops.push(t)
    this.sources.push(n); this.nodes.push(n)
    return n
  }
  resume() { this.state = 'running'; return Promise.resolve() }
  close() { this.state = 'closed'; return Promise.resolve() }
  advance(t) {
    this.currentTime = t
    for (const s of this.sources) {
      if (!s.ended && s.starts.length && Math.min(s.starts[0] + s.buffer.duration, ...s.stops) <= t) {
        s.ended = true; s.onended?.()
      }
    }
  }
}
async function withAudio(fn) {
  const previous = globalThis.window
  globalThis.window = { AudioContext: Audio }
  try { await fn() } finally {
    if (previous === undefined) delete globalThis.window
    else globalThis.window = previous
  }
}
test('prepare/unlock/start/tick: actual buffers, no pre-roll or independent scheduling, speed keeps pitch', async () => withAudio(async () => {
  const music = createTavernEntranceMusic()
  const pending = music.prepare()
  const audio = Audio.instances.at(-1)
  music.start(2)
  assert.equal(audio.sources.length, 0)
  assert.equal(await pending, true)
  assert.equal(await music.ready, true)
  assert.equal(audio.state, 'suspended')
  music.tick(0.7)
  assert.equal(audio.sources.length, 0)
  assert.equal(await music.unlock(), true)
  audio.advance(0.36)
  music.tick(0.72)
  assert.equal(audio.sources.length, 1)
  const first = audio.sources[0]
  assert.deepEqual(first.starts, [0.36])
  assert.equal(first.playbackRate.value, 1)
  assert.deepEqual(first.buffer.getChannelData(0), renderTavernNote(146.83, 1.65, SR))
  music.tick(0.72); music.tick(0.8)
  audio.advance(0.975); music.tick(1.95)
  assert.equal(audio.sources.length, 2)
  audio.advance(20) // waiting: wall clock alone cannot schedule later cues
  assert.equal(audio.sources.length, 2)
  music.tick(6.35) // stale A4 and D4-rise skipped, only current resolving D4
  assert.equal(audio.sources.length, 3)
  assert.equal(music.diagnostics.fired, 5)
  music.tick(8.7)
  assert.equal(music.playing, false)
  await music.dispose()
}))
test('pause/resume drops backlog; repeat/stop release every live voice and dispose disconnects', async () => withAudio(async () => {
  const music = createTavernEntranceMusic()
  await music.prepare(); await music.unlock(); music.start()
  const audio = Audio.instances.at(-1)
  audio.advance(0.72); music.tick(0.72)
  music.pause()
  assert.equal(music.playing, false)
  assert.ok(audio.sources[0].stops[0] > audio.currentTime)
  music.tick(3.3)
  assert.equal(audio.sources.length, 1)
  music.resume(); music.tick(3.3)
  assert.equal(audio.sources.length, 1)
  audio.advance(5.25); music.tick(5.25)
  assert.equal(audio.sources.length, 2)
  music.start(); music.tick(0)
  assert.equal(audio.sources.length, 2)
  assert.ok(audio.sources[1].stops.length)
  audio.advance(5.30) // release tail gone before repeated entrance's first cue
  assert.equal(music.diagnostics.voices, 0)
  music.tick(0.72)
  assert.equal(audio.sources.length, 3)
  music.stop(); music.stop()
  assert.equal(audio.sources[2].stops.length, 1)
  const gains = audio.nodes.filter(n => n.gain.events.some(e => e[0] === 'ramp'))
  assert.equal(gains.length, 3)
  for (const n of gains) assert.ok(n.gain.events.some(e => e[0] === 'ramp' && e[1] === 0))
  await music.dispose(); await music.dispose()
  assert.equal(audio.state, 'closed')
  assert.ok(audio.nodes.every(n => n.disconnected))
  assert.equal(music.diagnostics.voices, 0)
  const count = audio.sources.length
  music.start(); music.tick(0.72); await music.unlock()
  assert.equal(audio.sources.length, count)
}))
test('disabled/noWindow/noAudio and disposed preparation are safe', async () => {
  for (const enabled of [true, false]) {
    const music = createTavernEntranceMusic({ enabled })
    assert.equal(await music.prepare(), false)
    assert.equal(await music.unlock(), false)
    music.start(); music.tick(0.72); music.pause(); music.resume(); music.stop()
    assert.equal(music.playing, false)
    await music.dispose()
  }
  await withAudio(async () => {
    const music = createTavernEntranceMusic()
    const pending = music.prepare()
    await music.dispose()
    assert.equal(await pending, false)
    assert.equal(music.diagnostics.ready, false)
  })
})

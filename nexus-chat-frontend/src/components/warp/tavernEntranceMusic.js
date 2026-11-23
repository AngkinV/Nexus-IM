/** Sparse, synthetic warm strings, not a physical guqin/xiao imitation.
 * No noise, drone, independent score timer, downloads or pitch-changing playback.
 */
export const MUSIC_CUES = Object.freeze({ land: 0.72, wash: 1.95, rise: 3.3, colour: 5.25, settle: 6.35, end: 8.7 })
export const MUSIC_SCORE = Object.freeze([
  { time: 0.72, frequency: 146.83, duration: 1.65, gain: 0.85, pan: -0.08 },
  { time: 1.95, frequency: 220, duration: 1.55, gain: 0.70, pan: 0.08 },
  { time: 3.3, frequency: 293.66, duration: 1.1, gain: 0.65, pan: -0.05 },
  { time: 5.25, frequency: 440, duration: 1, gain: 0.36, pan: 0.06 },
  { time: 6.35, frequency: 293.66, duration: 2, gain: 0.65, pan: 0 }
].map(Object.freeze))

const ATTACK = 0.022
const RELEASE = 0.12
const FADE = 0.035
const PARTIALS = Object.freeze([1, 0.32, 0.16, 0.085, 0.045, 0.025])
const smooth = (x) => { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x) }
function envelope(t, duration) {
  return smooth(t / ATTACK) * smooth((duration - t) / RELEASE)
}
function sample(t, frequency, duration, sampleRate) {
  let v = 0
  for (let k = 0; k < PARTIALS.length; k++) {
    const harmonic = k + 1
    // Higher modes lose energy faster; tiny upper-mode stretch adds string body.
    const f = frequency * harmonic * (1 + (k > 1 ? k * k * 0.00012 : 0))
    if (f >= sampleRate * 0.45) continue
    v += PARTIALS[k] * Math.sin(2 * Math.PI * f * t) * Math.exp(-t * (1.9 + k * 1.15))
  }
  return 0.13 * v * envelope(t, duration)
}
function removeDC(data, sampleRate, duration) {
  let sum = 0, weight = 0
  for (let i = 0; i < data.length; i++) {
    sum += data[i]
    weight += envelope(i / sampleRate, duration)
  }
  const correction = weight ? sum / weight : 0
  for (let i = 0; i < data.length; i++) data[i] -= correction * envelope(i / sampleRate, duration)
  data[0] = data[data.length - 1] = 0
  return data
}
/** Pure deterministic PCM for spectral/boundary acceptance; no browser required. */
export function renderTavernNote(frequency, duration, sampleRate = 48000) {
  if (![frequency, duration, sampleRate].every(Number.isFinite) || frequency <= 0 || duration < ATTACK + RELEASE || sampleRate < 8000) {
    throw new RangeError('Invalid note parameters')
  }
  const data = new Float32Array(Math.ceil(duration * sampleRate) + 1)
  for (let i = 0; i < data.length; i++) data[i] = sample(i / sampleRate, frequency, duration, sampleRate)
  return removeDC(data, sampleRate, duration)
}
/** Reference mix at normal speed (runtime scheduling is tested separately). */
export function renderTavernScore(sampleRate = 48000) {
  const mix = new Float32Array(Math.ceil(MUSIC_CUES.end * sampleRate) + 1)
  for (const cue of MUSIC_SCORE) {
    const data = renderTavernNote(cue.frequency, cue.duration, sampleRate)
    const offset = Math.round(cue.time * sampleRate)
    for (let i = 0; i < data.length && offset + i < mix.length; i++) mix[offset + i] += data[i] * cue.gain
  }
  return mix
}

export function createTavernEntranceMusic({ enabled = true } = {}) {
  let ctx = null, master = null, preparation = null, disposed = false, ready = false
  let active = false, paused = false, speed = 1, scene = 0, skipOnResume = false
  let generation = 0
  const buffers = new Map(), voices = new Set(), fired = new Set()
  const Ctor = enabled && typeof window !== 'undefined' && (window.AudioContext || window.webkitAudioContext)
  function ensure() {
    if (disposed || !Ctor) return null
    if (ctx) return ctx
    try {
      ctx = new Ctor()
      master = ctx.createGain()
      master.gain.value = 1
      master.connect(ctx.destination)
      return ctx
    } catch {
      try { ctx?.close()?.catch?.(() => {}) } catch { /* unavailable */ }
      ctx = null
      master = null
      return null
    }
  }
  function clean(voice) {
    for (const node of [voice.source, voice.gain, voice.panner]) {
      try { node?.disconnect() } catch { /* already disconnected */ }
    }
    voices.delete(voice)
  }
  function releaseAll() {
    if (!ctx) return
    const now = ctx.currentTime
    for (const voice of voices) {
      if (voice.releasing) continue
      voice.releasing = true
      try {
        voice.gain.gain.cancelScheduledValues(now)
        voice.gain.gain.setValueAtTime(voice.level, now)
        voice.gain.gain.linearRampToValueAtTime(0, now + FADE)
        voice.source.stop(now + FADE + 0.002)
      } catch { clean(voice) }
    }
  }
  /** Yield every 4096 samples. Does not resume the context or connect any source. */
  function prepare() {
    if (preparation) return preparation
    const audio = ensure()
    if (!audio) return Promise.resolve(false)
    const token = generation
    preparation = (async () => {
      try {
        for (const cue of MUSIC_SCORE) {
          const n = Math.ceil(cue.duration * audio.sampleRate) + 1
          const data = new Float32Array(n)
          for (let start = 0; start < n; start += 4096) {
            await new Promise(resolve => setTimeout(resolve, 0))
            if (disposed || token !== generation) return false
            for (let i = start; i < Math.min(start + 4096, n); i++) data[i] = sample(i / audio.sampleRate, cue.frequency, cue.duration, audio.sampleRate)
          }
          removeDC(data, audio.sampleRate, cue.duration)
          const buffer = audio.createBuffer(1, n, audio.sampleRate)
          buffer.copyToChannel(data, 0)
          buffers.set(cue, buffer)
        }
        ready = true
        return true
      } catch { buffers.clear(); return false }
    })()
    return preparation
  }
  /** Call directly inside pointer/key handler, before any await, for autoplay permission. */
  function unlock() {
    const audio = ensure()
    if (!audio) return Promise.resolve(false)
    try {
      return Promise.resolve(audio.resume()).then(() => !disposed && audio.state === 'running', () => false)
    } catch { return Promise.resolve(false) }
  }
  function start(nextSpeed = 1) {
    if (disposed || !enabled || !Ctor) return
    releaseAll()
    speed = Number.isFinite(nextSpeed) && nextSpeed > 0 ? nextSpeed : 1
    fired.clear()
    scene = 0
    active = true
    paused = false
    skipOnResume = false
  }
  function play(cue) {
    let voice
    try {
      const source = ctx.createBufferSource(), gain = ctx.createGain()
      voice = { source, gain, panner: null, level: cue.gain, releasing: false }
      voices.add(voice)
      source.onended = () => clean(voice)
      source.buffer = buffers.get(cue)
      source.playbackRate.value = 1 // speed changes cue timing only, never tuning.
      gain.gain.value = cue.gain
      source.connect(gain)
      if (ctx.createStereoPanner) {
        voice.panner = ctx.createStereoPanner()
        voice.panner.pan.value = cue.pan
        gain.connect(voice.panner)
        voice.panner.connect(master)
      } else gain.connect(master)
      source.start(ctx.currentTime)
    } catch {
      if (voice) { try { voice.source.stop() } catch { /* not started */ }; clean(voice) }
    }
  }
  /** No lookahead: nothing can begin before the visual clock reaches its cue.
   * Missed cues older than 120 real ms are discarded, never replayed in a burst.
   */
  function tick(sceneSeconds) {
    if (!active || paused || disposed || !Number.isFinite(sceneSeconds) || sceneSeconds < scene) return
    scene = sceneSeconds
    if (scene >= MUSIC_CUES.end) { stop(); return }
    const canPlay = ready && ctx?.state === 'running' && !skipOnResume
    for (const cue of MUSIC_SCORE) {
      if (fired.has(cue) || cue.time > scene) continue
      fired.add(cue)
      if (canPlay && (scene - cue.time) / speed <= 0.12) play(cue)
    }
    skipOnResume = false
  }
  function pause() { if (active && !paused) { paused = true; releaseAll() } }
  function resume() { if (active && paused) { paused = false; skipOnResume = true } }
  function stop() { active = false; paused = false; skipOnResume = false; releaseAll() }
  /** Soft fade first, close our own context after its sources end. Promise is awaitable. */
  function dispose() {
    if (disposed) return disposal
    disposed = true
    generation++
    stop()
    buffers.clear()
    ready = false
    const audio = ctx
    disposal = (async () => {
      if (!audio) return
      // Wall timer is only teardown, never a musical/score clock.
      if (audio.state === 'running' && voices.size) await new Promise(resolve => setTimeout(resolve, 45))
      for (const voice of [...voices]) {
        try { voice.source.stop() } catch { /* ended */ }
        clean(voice)
      }
      try { master?.disconnect() } catch { /* closed */ }
      try { await audio.close() } catch { /* already closed */ }
      ctx = null
      master = null
    })()
    return disposal
  }
  let disposal = Promise.resolve()
  return {
    prepare, unlock, start, tick, pause, resume, stop, dispose,
    get ready() { return prepare() },
    get playing() { return active && !paused && ready && ctx?.state === 'running' && !disposed },
    get diagnostics() { return { ready, active, paused, scene, speed, fired: fired.size, voices: voices.size, disposed } }
  }
}

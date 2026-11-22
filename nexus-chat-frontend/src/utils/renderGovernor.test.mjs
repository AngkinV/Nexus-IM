import test from 'node:test'
import assert from 'node:assert/strict'
import {
  configureRenderGovernor,
  createFrameGate,
  createRenderLoop,
  describeRenderScale,
  isRenderSuspended,
  onRenderQualityChange,
  onRenderSuspendChange,
  renderScaleFactor,
  scaledPixelRatio,
  setWindowFocused,
  SUSPEND_DELAY_MS,
  __resetRenderGovernorForTests
} from './renderGovernor.js'

test.beforeEach(() => __resetRenderGovernorForTests())

/* ── frame cap ── */

const countDraws = (cap, ticks, stepMs) => {
  const gate = createFrameGate(() => cap)
  let draws = 0
  for (let i = 1; i <= ticks; i++) if (gate.shouldDraw(i * stepMs)) draws++
  return draws
}

test('frame gate: a 60 Hz display under a 30 cap draws every other frame', () => {
  assert.equal(countDraws(30, 120, 1000 / 60), 60)
})

test('frame gate: a 60 cap does not drop frames on a 60 Hz display', () => {
  assert.equal(countDraws(60, 120, 1000 / 60), 120)
})

test('frame gate: a 120 Hz display is held to the cap', () => {
  assert.equal(countDraws(60, 240, 1000 / 120), 120)
  assert.equal(countDraws(30, 240, 1000 / 120), 60)
})

test('frame gate: after a long stall it draws once and does not burst to catch up', () => {
  const gate = createFrameGate(() => 30)
  assert.equal(gate.shouldDraw(0), true)
  assert.equal(gate.shouldDraw(60_000), true)
  assert.equal(gate.shouldDraw(60_010), false)
  assert.equal(gate.shouldDraw(60_034), true)
})

test('frame gate: follows the configured cap when it changes', () => {
  const gate = createFrameGate()
  configureRenderGovernor({ fpsCap: 30 })
  let draws = 0
  for (let i = 1; i <= 60; i++) if (gate.shouldDraw(i * (1000 / 60))) draws++
  assert.equal(draws, 30)
  configureRenderGovernor({ fpsCap: 'nonsense' })
  gate.reset()
  draws = 0
  for (let i = 61; i <= 120; i++) if (gate.shouldDraw(i * (1000 / 60))) draws++
  assert.equal(draws, 60, 'an invalid cap falls back to 60')
})

/* ── render resolution ── */

const retina = { innerHeight: 900, devicePixelRatio: 2 } // 1800 physical px tall
const laptop = { innerHeight: 800, devicePixelRatio: 1 } // 800 physical px tall

test('render scale: the three tiers give three different buffers on a tall window', () => {
  const height = (mode) => {
    configureRenderGovernor({ renderScale: mode })
    // the desktop pet: a 240 px tall canvas with a ratio ceiling of 2
    return Math.round(240 * scaledPixelRatio(2, retina))
  }
  assert.equal(height('native'), 480)
  assert.equal(height('1080p'), 288)
  assert.equal(height('720p'), 192)
})

test('render scale: every renderer scales by the same factor, whatever its own ceiling', () => {
  configureRenderGovernor({ renderScale: '720p' })
  assert.equal(renderScaleFactor(retina), 0.4)
  assert.equal(scaledPixelRatio(2, retina), 0.8)
  assert.ok(Math.abs(scaledPixelRatio(1.5, retina) - 0.6) < 1e-9)
})

test('render scale: never upscales a window that is already below the target', () => {
  configureRenderGovernor({ renderScale: '1080p' })
  assert.equal(renderScaleFactor(laptop), 1)
  assert.equal(scaledPixelRatio(2, laptop), 1)
  assert.equal(describeRenderScale(laptop).sameAsNative, true)
})

test('render scale: never drops below a legible ratio, and survives bad input', () => {
  configureRenderGovernor({ renderScale: '720p' })
  assert.equal(scaledPixelRatio(2, { innerHeight: 4000, devicePixelRatio: 2 }), 0.5)
  assert.equal(renderScaleFactor({ innerHeight: 0, devicePixelRatio: 2 }), 1)
  assert.equal(renderScaleFactor({ innerHeight: NaN, devicePixelRatio: NaN }), 1)
  configureRenderGovernor({ renderScale: '4k' })
  assert.equal(describeRenderScale(retina).mode, 'native')
})

test('render scale: describes what the tier does on this window', () => {
  configureRenderGovernor({ renderScale: '1080p' })
  assert.deepEqual(describeRenderScale(retina), {
    mode: '1080p', factor: 0.6, physicalHeight: 1800, renderedHeight: 1080, sameAsNative: false
  })
})

test('render scale: listeners hear a change once, and a broken listener does not silence the rest', () => {
  const heard = []
  const stop = onRenderQualityChange(() => { throw new Error('renderer exploded') })
  onRenderQualityChange((mode) => heard.push(mode))
  const realError = console.error
  console.error = () => {}
  try {
    configureRenderGovernor({ renderScale: '720p' })
    configureRenderGovernor({ renderScale: '720p' }) // unchanged: no second call
  } finally {
    console.error = realError
  }
  assert.deepEqual(heard, ['720p'])
  stop()
})

/* ── suspend ── */

test('suspend: only after 30 s unfocused, and focus resumes immediately', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const events = []
  onRenderSuspendChange((value) => events.push(value))

  setWindowFocused(false)
  t.mock.timers.tick(SUSPEND_DELAY_MS - 1)
  assert.equal(isRenderSuspended(), false, 'a short trip away must not suspend')
  t.mock.timers.tick(1)
  assert.equal(isRenderSuspended(), true)

  setWindowFocused(true)
  assert.equal(isRenderSuspended(), false)
  assert.deepEqual(events, [true, false])
})

test('suspend: coming back before 30 s cancels the countdown', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  setWindowFocused(false)
  t.mock.timers.tick(20_000)
  setWindowFocused(true)
  setWindowFocused(false)
  t.mock.timers.tick(20_000)
  assert.equal(isRenderSuspended(), false, 'the countdown restarts from the latest blur')
  t.mock.timers.tick(10_000)
  assert.equal(isRenderSuspended(), true)
})

test('suspend: switching the setting off wakes a suspended app at once', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  setWindowFocused(false)
  t.mock.timers.tick(SUSPEND_DELAY_MS)
  assert.equal(isRenderSuspended(), true)
  configureRenderGovernor({ suspendOnBlur: false })
  assert.equal(isRenderSuspended(), false)
})

test('suspend: switching the setting on while unfocused starts the countdown then', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  configureRenderGovernor({ suspendOnBlur: false })
  setWindowFocused(false)
  t.mock.timers.tick(SUSPEND_DELAY_MS * 3)
  assert.equal(isRenderSuspended(), false, 'off means never')
  configureRenderGovernor({ suspendOnBlur: true })
  t.mock.timers.tick(SUSPEND_DELAY_MS - 1)
  assert.equal(isRenderSuspended(), false)
  t.mock.timers.tick(1)
  assert.equal(isRenderSuspended(), true)
})

/* ── loop ── */

function fakeFrames() {
  const queue = []
  globalThis.requestAnimationFrame = (cb) => { queue.push(cb); return queue.length }
  globalThis.cancelAnimationFrame = () => {}
  return { run: (ts) => { const cb = queue.shift(); if (cb) cb(ts); return !!cb }, pending: () => queue.length }
}

test('render loop: capped, and a draw that throws does not stop the loop', () => {
  const frames = fakeFrames()
  configureRenderGovernor({ fpsCap: 30 })
  let draws = 0
  const loop = createRenderLoop(() => { draws++; if (draws === 3) throw new Error('bad frame') })
  loop.start()
  let thrown = 0
  for (let i = 1; i <= 60; i++) {
    try { frames.run(i * (1000 / 60)) } catch { thrown++ }
  }
  assert.equal(thrown, 1)
  assert.equal(draws, 30, 'the loop kept going after the failing frame')
  loop.stop()
  assert.equal(loop.isRunning, false)
})

test('render loop: while suspended it leaves rAF, draws nothing, and reports the resume once', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const frames = fakeFrames()
  let draws = 0
  let resumes = 0
  const loop = createRenderLoop(() => draws++, { onResume: () => resumes++ })
  loop.start()
  frames.run(16)
  assert.equal(draws, 1)

  setWindowFocused(false)
  t.mock.timers.tick(SUSPEND_DELAY_MS)
  frames.run(32) // this frame notices the suspension and re-arms on a timer, not on rAF
  assert.equal(frames.pending(), 0, 'no animation frame is pending while suspended')
  t.mock.timers.tick(5_000)
  assert.equal(draws, 1, 'nothing is drawn while suspended')

  setWindowFocused(true)
  t.mock.timers.tick(500) // the poll notices the resume
  assert.equal(resumes, 1)
  assert.equal(draws, 2)
  frames.run(40_000)
  assert.equal(resumes, 1, 'onResume fires once per suspension')
  loop.stop()
})

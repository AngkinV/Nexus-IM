import test from 'node:test'
import assert from 'node:assert/strict'
import { to12Hour } from './settings.js'
import { resolvePixelRatio, createRenderLoop } from '../utils/renderLoop.js'

test('to12Hour converts 24-hour tokens', () => {
  assert.equal(to12Hour('HH:mm'), 'h:mm A')
  assert.equal(to12Hour('HH:mm:ss'), 'h:mm:ss A')
})

test('to12Hour leaves date tokens alone', () => {
  assert.equal(to12Hour('MM-DD HH:mm'), 'MM-DD h:mm A')
  assert.equal(to12Hour('YYYY-MM-DD'), 'YYYY-MM-DD')
})

test('to12Hour keeps the format unchanged when there is no time', () => {
  assert.equal(to12Hour('ddd'), 'ddd')
  assert.equal(to12Hour('MMM D'), 'MMM D')
})

test('resolvePixelRatio returns the display density for native', () => {
  assert.equal(resolvePixelRatio('native', 240, 2), 2)
  assert.equal(resolvePixelRatio('native', 240, 1), 1)
  // Capped at 2 even on a 3x display, to keep the drawing buffer sane.
  assert.equal(resolvePixelRatio('native', 240, 3), 2)
})

test('resolvePixelRatio scales down for a lower render target', () => {
  const ratio = resolvePixelRatio('720p', 1440, 2)
  assert.ok(ratio > 0 && ratio <= 2, `expected 0 < ratio <= 2, got ${ratio}`)
  assert.ok(Math.abs(ratio - 0.5) < 1e-6, `720/1440 should be 0.5, got ${ratio}`)
})

test('resolvePixelRatio never upscales past the display density', () => {
  // A tiny canvas asking for 1080p must not ask for a 4x buffer.
  const ratio = resolvePixelRatio('1080p', 100, 2)
  assert.equal(ratio, 2)
})

test('resolvePixelRatio falls back to native when the height is unknown', () => {
  assert.equal(resolvePixelRatio('720p', 0, 2), 2)
  assert.equal(resolvePixelRatio('720p', null, 1), 1)
})

test('render loop caps the frame rate', async () => {
  // Drive the loop manually: no real rAF, no timers.
  let frames = 0
  const scheduled = []
  globalThis.requestAnimationFrame = (cb) => {
    scheduled.push(cb)
    return scheduled.length
  }
  globalThis.cancelAnimationFrame = () => {}

  const loop = createRenderLoop(() => frames++, { fpsCap: () => 30 })
  loop.start()

  // Feed timestamps: 60 ticks at 16.7ms should yield ~30 frames at a 30 FPS cap.
  let t = 0
  for (let i = 0; i < 60; i++) {
    t += 1000 / 60
    const next = scheduled.shift()
    if (!next) break
    next(t)
  }

  assert.ok(frames >= 28 && frames <= 32, `expected ~30 frames, got ${frames}`)
  loop.stop()
})

test('render loop skips the callback while suspended', () => {
  let frames = 0
  const scheduled = []
  globalThis.requestAnimationFrame = (cb) => {
    scheduled.push(cb)
    return scheduled.length
  }
  globalThis.cancelAnimationFrame = () => {}

  const loop = createRenderLoop(() => frames++, {
    fpsCap: () => 60,
    suspended: () => true
  })
  loop.start()

  // Suspended: the first tick schedules a poll instead of drawing.
  const first = scheduled.shift()
  if (first) first(16)
  assert.equal(frames, 0, 'no frame should render while suspended')

  loop.stop()
})

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createEntranceClock, entrancePhase, inkOrigin } from './tavernEntranceGrowth.js'

test('transport excludes waiting/background but does not cap long foreground frames', () => {
  const clock = createEntranceClock()
  clock.tick(100, true)
  assert.equal(clock.tick(350, true), .25)
  clock.tick(800, false)
  assert.equal(clock.tick(9000, true), .25)
  assert.equal(clock.tick(9300, true), .55)
  clock.reset(.2)
  assert.equal(clock.tick(20000, false), .2)
})

test('phase boundaries preserve a complete gray rest before original colour', () => {
  for (const [time, phase] of [[.2, 'paper'], [.65, 'drop'], [.72, 'wet'], [1.95, 'spread'], [3.3, 'mountains'], [4.7, 'gray'], [5.25, 'color'], [6.5, 'rest'], [7.4, 'crumple'], [8.7, 'done']]) assert.equal(entrancePhase(time), phase)
})

test('navigation pointer cannot become the ink origin', () => {
  const a = inkOrigin(1377, 967, 50)
  const b = inkOrigin(1377, 967, 1377)
  assert.equal(a.x, 1377 * .57 - 40)
  assert.equal(b.x, 1377 * .57 + 40)
  assert.equal(a.y, 967 * .64)
})


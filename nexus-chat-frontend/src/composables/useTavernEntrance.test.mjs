import { beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { useTavernEntrance, ENTRANCE_PHASE, clearEntranceSeen } from './useTavernEntrance.js'

// A minimal localStorage, so the "seen" flag has somewhere to live.
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k)
}

const entrance = useTavernEntrance()
const { state } = entrance

// Stand in for a mounted stage; each test starts idle with one host.
entrance.registerHost()
beforeEach(() => {
  entrance.finish()
  store.clear()
  clearEntranceSeen()
})

const spies = () => {
  const calls = { commit: 0, cancel: 0 }
  return { calls, onCommit: () => { calls.commit += 1 }, onCancel: () => { calls.cancel += 1 } }
}

test('the destination is committed by the stage, with no confirmation step', () => {
  const { calls, onCommit, onCancel } = spies()
  assert.equal(entrance.enter({ onCommit, onCancel }), true)
  assert.equal(state.phase, ENTRANCE_PHASE.PLAYING)
  assert.equal(calls.commit, 0)

  entrance.commit()
  assert.equal(calls.commit, 1)
  assert.equal(state.committed, true)
  assert.equal(state.phase, ENTRANCE_PHASE.PLAYING)

  entrance.commit()
  assert.equal(calls.commit, 1, 'commit runs once per entrance')

  entrance.finish()
  assert.equal(state.phase, ENTRANCE_PHASE.IDLE)
  assert.equal(calls.cancel, 0)
})

test('the first entrance cannot be skipped or cancelled', () => {
  const { calls, onCommit, onCancel } = spies()
  entrance.enter({ onCommit, onCancel })
  assert.equal(state.canSkip, false)

  entrance.skip()
  assert.equal(state.phase, ENTRANCE_PHASE.PLAYING, 'a skip does nothing on the first viewing')
  assert.deepEqual(calls, { commit: 0, cancel: 0 })

  entrance.cancel()
  assert.equal(state.phase, ENTRANCE_PHASE.PLAYING, 'a cancel does nothing on the first viewing')
  assert.deepEqual(calls, { commit: 0, cancel: 0 })

  entrance.commit()
  assert.equal(calls.commit, 1, 'the stage still commits by itself')
})

test('a force-skip gets past the first-viewing lock (a resize makes the picture unusable)', () => {
  const { calls, onCommit } = spies()
  entrance.enter({ onCommit })
  entrance.skip(true)
  assert.equal(state.phase, ENTRANCE_PHASE.LEAVING)
  assert.equal(calls.commit, 1)
})

test('a force-cancel gets past the lock too (an incoming call outranks the picture)', () => {
  const { calls, onCommit, onCancel } = spies()
  entrance.enter({ onCommit, onCancel })
  entrance.cancel(true)
  assert.equal(state.phase, ENTRANCE_PHASE.LEAVING)
  assert.deepEqual(calls, { commit: 0, cancel: 1 })
})

test('after the picture has been seen once, later entrances can be skipped', () => {
  // First viewing, played to the end.
  entrance.enter({})
  entrance.commit()
  entrance.markSeen()
  entrance.finish()

  const { calls, onCommit } = spies()
  entrance.enter({ onCommit })
  assert.equal(state.canSkip, true)
  entrance.skip()
  assert.equal(state.phase, ENTRANCE_PHASE.LEAVING)
  assert.equal(calls.commit, 1)
})

test('being cut short on the first viewing does not count as seen', () => {
  // A resize / a call stops the first entrance before it finishes. The stage only
  // calls markSeen when the picture actually played out, so this must stay unskippable.
  entrance.enter({})
  entrance.skip(true)
  entrance.finish()

  entrance.enter({})
  assert.equal(state.canSkip, false, 'the first viewing was not actually watched')
})

test('the seen flag is per account', () => {
  localStorage.setItem('user', JSON.stringify({ id: 'alice' }))
  entrance.enter({})
  entrance.commit()
  entrance.markSeen()
  entrance.finish()

  entrance.enter({})
  assert.equal(state.canSkip, true, 'alice has seen it')
  entrance.finish()

  localStorage.setItem('user', JSON.stringify({ id: 'bob' }))
  entrance.enter({})
  assert.equal(state.canSkip, false, 'bob has not')
})

test('cancel before the commit backs out; the app stays where it was', () => {
  entrance.enter({})
  entrance.markSeen()
  entrance.finish()

  const { calls, onCommit, onCancel } = spies()
  entrance.enter({ onCommit, onCancel })
  entrance.cancel()
  assert.equal(state.phase, ENTRANCE_PHASE.LEAVING)
  assert.deepEqual(calls, { commit: 0, cancel: 1 })

  // A late commit from the stage must not switch the view after backing out.
  entrance.commit()
  entrance.finish()
  assert.deepEqual(calls, { commit: 0, cancel: 1 })
})

test('cancel after the commit only clears the stage', () => {
  entrance.enter({})
  entrance.markSeen()
  entrance.finish()

  const { calls, onCommit, onCancel } = spies()
  entrance.enter({ onCommit, onCancel })
  entrance.commit()
  entrance.cancel()
  assert.equal(state.phase, ENTRANCE_PHASE.LEAVING)
  assert.deepEqual(calls, { commit: 1, cancel: 0 })
})

test('a second entrance cannot start while one is playing', () => {
  const first = spies()
  const second = spies()
  entrance.enter(first)
  assert.equal(entrance.enter(second), false)
  entrance.commit()
  assert.equal(first.calls.commit, 1)
  assert.equal(second.calls.commit, 0)
})

test('repeat entrances retain normal speed and can be skipped', () => {
  entrance.enter({})
  assert.equal(state.fast, false, 'the first viewing is always the long one')
  entrance.commit()
  entrance.markSeen()
  entrance.finish()

  entrance.enter({})
  assert.equal(state.fast, false)
})

test('without a stage the caller is never stranded', () => {
  entrance.unregisterHost()
  const { calls, onCommit } = spies()
  assert.equal(entrance.enter({ onCommit }), false)
  assert.equal(calls.commit, 1)
  assert.equal(state.phase, ENTRANCE_PHASE.IDLE)
  entrance.registerHost()
})

test('losing the stage mid-entrance resets without firing callbacks', () => {
  const { calls, onCommit, onCancel } = spies()
  entrance.enter({ onCommit, onCancel })
  entrance.unregisterHost()
  assert.equal(state.phase, ENTRANCE_PHASE.IDLE)
  assert.deepEqual(calls, { commit: 0, cancel: 0 })
  entrance.registerHost()
})


test('natural completion uses the account frozen at run start', () => {
  localStorage.setItem('user', JSON.stringify({ id: 'original' }))
  entrance.enter({})
  localStorage.setItem('user', JSON.stringify({ id: 'replacement' }))
  entrance.commit()
  entrance.markSeen()
  assert.equal(localStorage.getItem('tavernEntranceSeen:original'), '1')
  assert.equal(localStorage.getItem('tavernEntranceSeen:replacement'), null)
})

test('leaving and idle cannot record a completed first viewing', () => {
  entrance.enter({})
  entrance.cancel(true)
  entrance.markSeen()
  assert.equal(localStorage.getItem('tavernEntranceSeen'), null)
  entrance.finish()
  entrance.markSeen()
  assert.equal(localStorage.getItem('tavernEntranceSeen'), null)
})

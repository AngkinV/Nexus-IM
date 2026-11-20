import { beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { useTavernEntrance, ENTRANCE_PHASE } from './useTavernEntrance.js'

const entrance = useTavernEntrance()
const { state } = entrance

// Stand in for a mounted stage; each test starts idle with one host.
entrance.registerHost()
beforeEach(() => {
  entrance.finish()
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

test('cancel before the commit backs out; the app stays where it was', () => {
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
  const { calls, onCommit, onCancel } = spies()
  entrance.enter({ onCommit, onCancel })
  entrance.commit()
  entrance.cancel()
  assert.equal(state.phase, ENTRANCE_PHASE.LEAVING)
  assert.deepEqual(calls, { commit: 1, cancel: 0 })
})

test('skip before the commit goes in straight away', () => {
  const { calls, onCommit, onCancel } = spies()
  entrance.enter({ onCommit, onCancel })
  entrance.skip()
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

test('entrances after the first one play fast', () => {
  entrance.enter({})
  entrance.commit()
  entrance.finish()
  entrance.enter({})
  assert.equal(state.fast, true)
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

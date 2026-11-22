import test from 'node:test'
import assert from 'node:assert/strict'
import { failureBelongsToSession } from './sessionGuard.js'

test('late 401 for the previous account cannot sign out the current account', () => {
  const error = { response: { status: 401 }, config: { headers: { Authorization: 'Bearer old-test-session' } } }
  assert.equal(failureBelongsToSession(error, 'new-test-session'), false)
  assert.equal(failureBelongsToSession(error, 'old-test-session'), true)
  assert.equal(failureBelongsToSession(error, null), false)
})
test('AxiosHeaders, missing headers and non-auth failures are handled without guesses', () => {
  const error = { response: { status: 401 }, config: { headers: { get: () => 'Bearer current-test-session' } } }
  assert.equal(failureBelongsToSession(error, 'current-test-session'), true)
  assert.equal(failureBelongsToSession({ response: { status: 401 } }, 'current-test-session'), false)
  assert.equal(failureBelongsToSession({ ...error, response: { status: 503 } }, 'current-test-session'), false)
})

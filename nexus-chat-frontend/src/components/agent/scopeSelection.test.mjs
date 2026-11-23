import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { emptyScope, normalizeScope, scopeRequest, createScopeState, scopeFailure } from './scopeSelection.mjs'
const scope = { targets: [{ type: 'group', id: 2 }], timeRange: { fromInclusive: null, toExclusive: null, timeZone: 'UTC' } }
test('nonempty selection requires confirmation; edits invalidate it; IDs are not names', () => {
  const state = createScopeState(); state.select(scope)
  assert.throws(() => state.request(1), /confirm/i)
  state.confirm(); assert.equal(state.request(1).scopeSelection.targets[0].id, 2)
  state.select({ ...scope, targets: [{ type: 'contact', id: 2 }] })
  assert.throws(() => state.request(1), /confirm/i)
  assert.throws(() => normalizeScope({ ...scope, targets: [{ type: 'group', label: 'same' }] }))
})
test('empty scope is explicit and never supplies chatContext or grant', () => {
  assert.deepEqual(scopeRequest(emptyScope(), false, 1), { scopeSelection: emptyScope(), scopeConfirmed: true, expectedBindingVersion: 1 })
  assert.throws(() => scopeRequest(emptyScope(), true, 0), /binding version/i)
})
test('owner/session generation fences late resource, binding and save responses', () => {
  const state = createScopeState(); state.reset('U', 'A'); const ticket = state.ticket()
  state.reset('V', 'A'); assert.equal(state.current(ticket), false)
  const second = state.ticket(); state.reset('V', 'B'); assert.equal(state.current(second), false)
})
test('resume/retry require a new explicit confirmation, including empty scopes', () => {
  const state = createScopeState(); state.select(scope); state.confirm()
  state.request(3, true); assert.throws(() => state.request(3, true), /confirm/i)
  state.select(emptyScope()); assert.throws(() => state.request(3, true), /confirm/i)
})
test('UTC half-open boundaries reject invalid or reversed windows', () => {
  assert.throws(() => normalizeScope({ ...scope, timeRange: { fromInclusive: '2026-01-02T00:00:00Z', toExclusive: '2026-01-01T00:00:00Z', timeZone: 'UTC' } }))
})
test('Java URLs and wire fields are retained; chatId fallback is removed', () => {
  const api = readFileSync(new URL('../../services/agentApi.js', import.meta.url), 'utf8')
  const store = readFileSync(new URL('../../stores/agent.js', import.meta.url), 'utf8')
  assert.ok(api.includes("'/agent/scope/resources'")); assert.ok(api.includes('/scope`'))
  assert.ok(store.includes('scopeSelection')); assert.ok(store.includes('expectedBindingVersion'))
  assert.ok(!store.includes('chatContext: settings.chatId'))
  assert.ok(store.includes('confirmRunScope')); assert.ok(store.includes('providerId: live.providerId'))
})
test('only an authorization refusal is a scope failure; a busy run or a model error is not', () => {
  assert.equal(scopeFailure({ code: 'AGENT_AUTHZ_40301' }), true)
  assert.equal(scopeFailure({ code: 'ERR_BAD_REQUEST', response: { status: 403, data: { code: 'AGENT_AUTHZ_40301' } } }), true)
  assert.equal(scopeFailure({ status: 403, code: 'HTTP_403' }), true)
  assert.equal(scopeFailure({ code: 'ERR_BAD_REQUEST', response: { status: 409, data: {} } }), true) // binding version conflict
  assert.equal(scopeFailure({ status: 409, code: 'AGENT_RUN_40901' }), false) // a run is still active
  assert.equal(scopeFailure({ code: 'ERR_BAD_REQUEST', response: { status: 409, data: { code: 'AGENT_RUN_40901' } } }), false)
  assert.equal(scopeFailure({ code: 'AGENT_SYS_50001' }), false)
  assert.equal(scopeFailure({ code: 'AGENT_RUN_40401' }), false)
  assert.equal(scopeFailure(null), false)
})

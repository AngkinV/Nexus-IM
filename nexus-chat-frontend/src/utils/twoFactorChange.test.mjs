import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { disableTwoFactorWithProof } from './twoFactorChange.js'
import { loginErrorKey } from './loginSecurity.js'

const required = () => ({ response: { status: 400, data: { messageKey: 'error.auth.2fa.required', message: 'Translated challenge' } } })
const disabled = { data: { twoFactorEnabled: false } }

test('local/default success never asks for a second proof', async () => {
  let calls = 0
  const result = await disableTwoFactorWithProof({ isCurrent: () => true,
    disable: async code => { assert.equal(code, undefined); calls++; return disabled },
    askCode: () => assert.fail('no server challenge, no extra dialog') })
  assert.equal(result, disabled); assert.equal(calls, 1)
})

test('translated server challenge is recognized by messageKey and retries exactly once', async () => {
  const calls = []; let asked = 0
  const result = await disableTwoFactorWithProof({ isCurrent: () => true,
    disable: async code => { calls.push(code); if (!code) throw required(); return disabled },
    askCode: async () => { asked++; return ' ABCD-2345 ' } })
  assert.deepEqual(calls, [undefined, 'ABCD-2345']); assert.equal(asked, 1); assert.equal(result, disabled)
})

test('cancel or close after the challenge never resubmits or changes the enabled state', async () => {
  for (const action of ['cancel', 'close']) {
    let calls = 0
    const result = await disableTwoFactorWithProof({ isCurrent: () => true,
      disable: async () => { calls++; throw required() }, askCode: async () => { throw action } })
    assert.equal(result, null); assert.equal(calls, 1)
  }
})

test('an ordinary failure or wrong factor does not create a retry loop', async () => {
  const failure = { response: { data: { messageKey: 'error.auth.2fa.invalid.code' } } }
  await assert.rejects(disableTwoFactorWithProof({ isCurrent: () => true,
    disable: async () => { throw failure }, askCode: () => assert.fail('not a missing-factor challenge') }), error => error === failure)
  let calls = 0
  await assert.rejects(disableTwoFactorWithProof({ isCurrent: () => true,
    disable: async () => { if (++calls === 1) throw required(); throw failure }, askCode: async () => 'incorrect' }), error => error === failure)
  assert.equal(calls, 2)
})

test('an authentication change while the prompt is open prevents the second request', async () => {
  let current = true, calls = 0
  const result = await disableTwoFactorWithProof({ isCurrent: () => current,
    disable: async () => { calls++; throw required() }, askCode: async () => { current = false; return 'ABCD-2345' } })
  assert.equal(result, null); assert.equal(calls, 1)
})

test('a late successful response is not applied to another authentication context', async () => {
  let current = true
  const result = await disableTwoFactorWithProof({ isCurrent: () => current,
    disable: async () => { current = false; return disabled }, askCode: () => assert.fail('no prompt') })
  assert.equal(result, null)
})

test('missing or still-enabled backend state never becomes a fake successful disable', async () => {
  for (const response of [{ data: {} }, { data: { twoFactorEnabled: true } }]) {
    await assert.rejects(disableTwoFactorWithProof({ isCurrent: () => true, disable: async () => response,
      askCode: () => assert.fail('no prompt') }), /not confirmed/)
  }
})

async function realHandler(promptAction) {
  const source = await readFile(new URL('../components/profile/AccountSecurityModule.vue', import.meta.url), 'utf8')
  const body = source.match(/const disableTwoFactor = (async \(\) => \{[\s\S]*?)(?=\nconst copySecret)/)?.[1]
  assert.ok(body)
  const enabled = { value: true }, busy = { value: false }, calls = [], successes = [], errors = []
  const snapshot = { revision: 1, token: 'fixture-session', storedToken: 'fixture-session', owner: 7, storedUser: '{"id":7}' }
  const bindings = { userId: { value: 7 }, twoFactorBusy: busy, twoFactorEnabled: enabled,
    userStore: { captureSession: () => ({ ...snapshot }) }, t: key => key, disableTwoFactorWithProof, loginErrorKey,
    ElMessageBox: { confirm: async () => {}, prompt: async (...args) => { assert.equal(enabled.value, true); return promptAction(...args) } },
    ElMessage: { success: value => successes.push(value), error: value => errors.push(value) },
    securityAPI: { disableTwoFactor: async (owner, code, ctx) => {
      assert.equal(enabled.value, true); assert.equal(owner, 7); assert.deepEqual(ctx, { owner: 7, session: 'fixture-session' })
      calls.push(code); if (!code) throw required(); return disabled
    } } }
  const handle = Function(...Object.keys(bindings), 'return ' + body)(...Object.values(bindings))
  await handle()
  return { enabled, busy, calls, successes, errors }
}

test('the real profile handler uses the server challenge and updates only after confirmation', async () => {
  const result = await realHandler(async (message, title, options) => {
    assert.equal(message, 'profile.twoFactorDisableProof'); assert.equal(options.inputType, 'text')
    assert.equal(options.inputValidator(''), 'profile.twoFactorDisableProof')
    return { value: ' ABCD-2345 ' }
  })
  assert.equal(result.enabled.value, false); assert.equal(result.busy.value, false)
  assert.deepEqual(result.calls, [undefined, 'ABCD-2345']); assert.deepEqual(result.successes, ['profile.twoFactorDisabledMsg'])
  assert.deepEqual(result.errors, [])
})

test('the real profile handler keeps enabled state and clears its busy flag after cancel', async () => {
  const result = await realHandler(async () => { throw 'cancel' })
  assert.equal(result.enabled.value, true); assert.equal(result.busy.value, false)
  assert.deepEqual(result.calls, [undefined]); assert.deepEqual(result.successes, []); assert.deepEqual(result.errors, [])
})

import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { loginErrorKey, needsSecondFactor, rememberSignOut, takeSignOutMessage, signOutMessageKey, SIGN_OUT_STORAGE_KEY } from './loginSecurity.js'

const originalStorage = globalThis.sessionStorage
const storage = new Map()
globalThis.sessionStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
after(() => { globalThis.sessionStorage = originalStorage })

test('only stable backend factor challenges disclose the second factor input', () => {
  for (const message of ['error.auth.2fa.required', 'error.auth.2fa.invalid.code', 'error.auth.2fa.code.used']) {
    assert.equal(needsSecondFactor(message), true)
    assert.equal(needsSecondFactor({ response: { data: { message } } }), true)
    assert.match(loginErrorKey(message), /^auth\.twoFactor/)
  }
  for (const message of ['error.auth.invalid.credentials', 'error.auth.2fa.not.setup', 'arbitrary server text', null]) {
    assert.equal(needsSecondFactor(message), false)
  }
  for (const value of ['arbitrary server text', '__proto__', 'constructor', 'toString']) {
    assert.equal(loginErrorKey(value), 'auth.loginFailed')
    assert.equal(signOutMessageKey(value), null)
  }
})

test('trusted sign-out reasons are one-shot and never hold tokens or arbitrary server text', () => {
  for (const reason of ['new_login', 'password_changed', 'logout', 'session_revoked']) {
    rememberSignOut(reason)
    assert.equal(storage.get(SIGN_OUT_STORAGE_KEY), reason)
    assert.equal(takeSignOutMessage(), signOutMessageKey(reason))
    assert.equal(takeSignOutMessage(), null)
  }
  rememberSignOut('arbitrary-server-text')
  assert.equal(storage.size, 0)
  storage.set(SIGN_OUT_STORAGE_KEY, 'arbitrary-server-text')
  assert.equal(takeSignOutMessage(), null)
  assert.equal(storage.size, 0)
})

test('login form waits for the real challenge and submits the factor without replacing credentials locally', async () => {
  const source = await readFile(new URL('../views/Login.vue', import.meta.url), 'utf8')
  assert.match(source, /v-if="secondFactorRequired"/)
  assert.match(source, /v-model="loginForm\.twoFactorCode"/)
  assert.match(source, /autocomplete="one-time-code"/)
  assert.match(source, /id="login-second-factor"/)
  assert.match(source, /role="alert"/)
  assert.match(source, /if \(needsSecondFactor\(error\)\)/)
  assert.match(source, /await userStore\.login\(loginForm\.usernameOrEmail, loginForm\.password, loginForm\.twoFactorCode\.trim\(\)\)/)
  assert.match(source, /if \(!isLoginValid\.value \|\| loading\.value\) return/)
  assert.match(source, /factorInput\.value\?\.focus\(\)/)
  assert.doesNotMatch(source, /@paste\.prevent|onpaste=/)
})

test('the real challenge handler focuses only after the input is enabled and does not create a fake login', async () => {
  const source = await readFile(new URL('../views/Login.vue', import.meta.url), 'utf8')
  const body = source.match(/const handleLogin = (async \(\) => \{[\s\S]*?)(?=\nconst handleRegister)/)?.[1]
  assert.ok(body)
  const loading = { value: false }, factor = { value: false }, error = { value: '' }
  let focused = 0
  const bindings = {
    isLoginValid: { value: true }, loading, loginError: error,
    loginForm: { usernameOrEmail: 'fixture', password: 'unit-only', twoFactorCode: '' },
    userStore: { login: async () => { throw 'error.auth.2fa.required' } },
    route: { name: 'Login' }, router: { replace: () => assert.fail('challenge must not enter the application') },
    loginDestination: () => '/main', loginErrorKey, needsSecondFactor, secondFactorRequired: factor,
    ElMessage: { error: () => assert.fail('the factor error should be shown inline') }, t: value => value,
    nextTick: async () => {}, factorInput: { value: { focus() { assert.equal(loading.value, false); focused++ } } }
  }
  const handle = Function(...Object.keys(bindings), 'return ' + body)(...Object.values(bindings))
  await handle()
  assert.equal(focused, 1); assert.equal(factor.value, true); assert.equal(error.value, 'auth.twoFactorRequired')
  assert.equal(loading.value, false)
})

test('login and credential-address errors have aligned nonempty Chinese and English messages', async () => {
  const zh = JSON.parse(await readFile(new URL('../locales/zh.json', import.meta.url), 'utf8'))
  const en = JSON.parse(await readFile(new URL('../locales/en.json', import.meta.url), 'utf8'))
  const flatten = (value, prefix = '') => Object.entries(value).flatMap(([key, item]) =>
    item && typeof item === 'object' ? flatten(item, `${prefix}${key}.`) : [`${prefix}${key}`])
  assert.deepEqual(flatten(zh).sort(), flatten(en).sort())
  for (const key of ['twoFactorCode', 'twoFactorRequired', 'twoFactorInvalid', 'twoFactorUsed', 'twoFactorNotReady',
    'signedInElsewhere', 'passwordChangedElsewhere', 'sessionRevoked']) {
    assert.match(zh.auth[key], /[\u4e00-\u9fff]/)
    assert.ok(en.auth[key].length > 5)
    assert.doesNotMatch(en.auth[key], /[\u4e00-\u9fff]/)
  }
  assert.match(zh.agent.providers.keyRequiredForNewAddress, /重新填写/)
  assert.match(en.agent.providers.keyRequiredForNewAddress, /Enter the API key again/)
})

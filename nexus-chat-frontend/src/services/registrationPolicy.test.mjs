import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  DEFAULT_REGISTRATION_POLICY, normalizeRegistrationPolicy, isRegisterFormComplete, registerPayload, inviteForEmailCode
} from './registrationPolicy.js'

const form = (extra = {}) => ({
  username: 'synthetic', email: 'synthetic@example.test', nickname: 'Synthetic', password: 'pw', confirmPassword: 'pw',
  phone: '', verificationCode: '', inviteCode: '', ...extra
})
const INVITE_ONLY = { inviteRequired: true, emailCodeRequired: false }

test('a missing or malformed policy keeps the form as it has always been', () => {
  for (const answer of [undefined, null, '', {}, { inviteRequired: 'true', emailCodeRequired: true }, { inviteRequired: true }]) {
    assert.equal(normalizeRegistrationPolicy(answer), DEFAULT_REGISTRATION_POLICY)
  }
  assert.deepEqual(DEFAULT_REGISTRATION_POLICY, { inviteRequired: false, emailCodeRequired: true })
  assert.deepEqual(normalizeRegistrationPolicy({ inviteRequired: true, emailCodeRequired: false, extra: 1 }), INVITE_ONLY)
})

test('by default the request is exactly what it was before invites existed', () => {
  const filled = form({ verificationCode: '123456', inviteCode: 'IGNORED' })
  assert.equal(isRegisterFormComplete(form(), DEFAULT_REGISTRATION_POLICY), false)
  assert.equal(isRegisterFormComplete(filled, DEFAULT_REGISTRATION_POLICY), true)
  assert.deepEqual(registerPayload(filled, DEFAULT_REGISTRATION_POLICY), {
    username: 'synthetic', email: 'synthetic@example.test', nickname: 'Synthetic', password: 'pw', phone: null,
    verificationCode: '123456'
  })
  assert.equal(inviteForEmailCode(filled, DEFAULT_REGISTRATION_POLICY), undefined)
})

test('with invites required the form needs the invite and sends no email code', () => {
  assert.equal(isRegisterFormComplete(form({ verificationCode: '123456' }), INVITE_ONLY), false)
  assert.equal(isRegisterFormComplete(form({ inviteCode: '   ' }), INVITE_ONLY), false)
  const filled = form({ inviteCode: ' ABCD-EFGH ', phone: '13800000000' })
  assert.equal(isRegisterFormComplete(filled, INVITE_ONLY), true)
  assert.deepEqual(registerPayload(filled, INVITE_ONLY), {
    username: 'synthetic', email: 'synthetic@example.test', nickname: 'Synthetic', password: 'pw', phone: '13800000000',
    inviteCode: 'ABCD-EFGH'
  })
})

test('with both required the invite goes along with the email-code request', () => {
  const both = { inviteRequired: true, emailCodeRequired: true }
  const filled = form({ inviteCode: 'ABCD-EFGH', verificationCode: '123456' })
  assert.equal(inviteForEmailCode(filled, both), 'ABCD-EFGH')
  assert.equal(isRegisterFormComplete(form({ inviteCode: 'ABCD-EFGH' }), both), false)
  assert.deepEqual(Object.keys(registerPayload(filled, both)).sort(),
    ['email', 'inviteCode', 'nickname', 'password', 'phone', 'username', 'verificationCode'])
})

test('the register form and the API client are wired to the policy', () => {
  const view = readFileSync(new URL('../views/Login.vue', import.meta.url), 'utf8')
  assert.match(view, /v-if="registrationPolicy\.inviteRequired"[\s\S]{0,200}v-model="registerForm\.inviteCode"/)
  assert.equal((view.match(/v-if="registrationPolicy\.emailCodeRequired"/g) || []).length, 2)
  assert.match(view, /authAPI\.getRegistrationPolicy\(\)/)
  assert.match(view, /userStore\.register\(registerPayload\(registerForm, registrationPolicy\.value\)\)/)
  const api = readFileSync(new URL('./api.js', import.meta.url), 'utf8')
  assert.match(api, /apiClient\.get\('\/auth\/registration-policy'\)/)
  assert.match(api, /'\/auth\/register', \{[^}]*inviteCode[^}]*\}/)
  assert.match(api, /'\/auth\/send-code', \{ email, type, inviteCode \}/)
  for (const locale of ['zh', 'en']) {
    const auth = JSON.parse(readFileSync(new URL(`../locales/${locale}.json`, import.meta.url), 'utf8')).auth
    assert.ok(auth.inviteCode, locale)
  }
})

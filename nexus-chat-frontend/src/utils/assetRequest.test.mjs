import test from 'node:test'
import assert from 'node:assert/strict'
import { assetRequest } from './assetRequest.js'
const id = '00000000-0000-0000-0000-000000000000'
const backend = 'http://127.0.0.1:8080'
test('only same-backend private asset routes receive authentication', () => {
  const path = `/api/companion/user-assets/file/model/${id}`
  const request = assetRequest(path, backend, 'test-only-credential')
  assert.equal(request.url, backend + path)
  assert.equal(Object.keys(request.headers).length, 1)
  assert.deepEqual(assetRequest(`https://example.invalid${path}`, backend, 'test-only-credential').headers, {})
  assert.deepEqual(assetRequest('/models/default.vrm', backend, 'test-only-credential').headers, {})
  assert.deepEqual(assetRequest('http://127.0.0.1:8080/other', backend, 'test-only-credential').headers, {})
})
test('private routes require login and unsupported schemes are rejected', () => {
  assert.throws(() => assetRequest(`/api/companion/user-assets/file/motion/${id}`, backend, null))
  assert.throws(() => assetRequest('javascript:void(0)', backend, 'test-only-credential'))
  assert.throws(() => assetRequest('file:///etc/passwd', backend, 'test-only-credential'))
})

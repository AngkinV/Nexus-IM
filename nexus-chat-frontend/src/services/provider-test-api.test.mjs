import { before, after, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import axios from 'axios'

const originalStorage = globalThis.localStorage
const originalAdapter = axios.defaults.adapter
let server, api, token, requests
before(async () => {
  globalThis.localStorage = { getItem: key => key === 'token' ? token : null }
  axios.defaults.adapter = async config => {
    requests.push(config)
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 'OK', data: { ok: true } } }
  }
  server = await createServer({
    configFile: false, root: fileURLToPath(new URL('../../', import.meta.url)),
    resolve: { alias: { '@': fileURLToPath(new URL('../', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, watch: null }, logLevel: 'error'
  })
  ;({ agentAPI: api } = await server.ssrLoadModule('/src/services/agentApi.js'))
})
after(async () => {
  await server?.close()
  globalThis.localStorage = originalStorage
  axios.defaults.adapter = originalAdapter
})
beforeEach(() => { token = 'first-unit-test-session'; requests = [] })

test('probe transport captures Authorization before async interceptors can see another login', async () => {
  const request = api.testProvider('channel/with spaces')
  token = 'another-unit-test-session'
  await request
  assert.equal(requests.length, 1)
  assert.equal(requests[0].headers.get('Authorization'), 'Bearer first-unit-test-session')
  assert.equal(requests[0].url, '/agent/providers/channel%2Fwith%20spaces/test')
  assert.equal(requests[0].timeout, 135000, '120s Python budget plus BFF overhead, bounded in the browser')
})

test('an explicitly captured old session cannot be replaced by the currently stored token', async () => {
  await api.testProvider(4, { token: 'captured-unit-test-session' })
  assert.equal(requests[0].headers.get('Authorization'), 'Bearer captured-unit-test-session')
})

test('no-token probes fail locally rather than retargeting a new account', () => {
  assert.throws(() => api.testProvider(4, { token: null }), { code: 'AUTH' })
  token = null
  assert.throws(() => api.testProvider(4), { code: 'AUTH' })
  assert.equal(requests.length, 0)
})

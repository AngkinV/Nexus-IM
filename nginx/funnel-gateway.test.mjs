import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import http from 'node:http'
import { createHash } from 'node:crypto'
import { allowedRequest, assertBackendReady, bodyLimit, clientAddress, createGateway, createLimiter, DEFAULT_LIMITS, limitClass, listenOptions, PUBLIC_ORIGIN } from './funnel-gateway.mjs'

let backend, gateway, base
let readiness = { ready: true, policyVersion: 'nexus-funnel-v2', publicOrigin: PUBLIC_ORIGIN,
  origins: [PUBLIC_ORIGIN, 'http://localhost:*', 'http://127.0.0.1:*', 'app://*'] }
const received = []
const backendSockets = new Set()

function rawRequest(target, path, { method = 'GET', headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const request = http.request({ ...{ hostname: '127.0.0.1', port: new URL(target).port }, path, method, headers }, response => {
      let text = ''
      response.setEncoding('utf8')
      response.on('data', chunk => { text += chunk })
      response.on('error', reject)
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, text }))
    })
    request.on('error', reject)
    request.on('upgrade', (_response, socket) => { socket.destroy(); reject(new Error('Unexpected WebSocket upgrade')) })
    if (body !== undefined) request.write(body)
    request.end()
  })
}

function upgradeHeaders(origin = PUBLIC_ORIGIN) {
  return { Connection: 'Upgrade', Upgrade: 'websocket', Origin: origin, 'Sec-WebSocket-Version': '13', 'Sec-WebSocket-Key': Buffer.alloc(16).toString('base64') }
}

before(async () => {
  backend = http.createServer((request, response) => {
    received.push({ path: request.url, headers: request.headers })
    if (request.url === '/internal/funnel/readiness') {
      response.writeHead(readiness.ready ? 200 : 503, { 'content-type': 'application/json' })
      response.end(JSON.stringify(readiness))
      return
    }
    if (request.url === '/api/agent/stream') {
      response.writeHead(200, { 'content-type': 'text/event-stream' })
      response.write('data: first\n\n')
      setTimeout(() => response.end('data: last\n\n'), 150)
      return
    }
    if (request.url === '/api/users/refuse') { request.socket.destroy(); return }
    let body = ''
    request.on('data', chunk => { body += chunk })
    request.on('end', () => {
      response.setHeader('content-type', 'application/json')
      response.end(JSON.stringify({ path: request.url, method: request.method, body }))
    })
  })
  backend.on('connection', socket => {
    backendSockets.add(socket)
    socket.on('close', () => backendSockets.delete(socket))
  })
  backend.on('upgrade', (request, socket) => {
    const accept = createHash('sha1').update(request.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')
    socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`)
    socket.on('data', frame => {
      if ((frame[0] & 15) === 8) { socket.end(Buffer.from([0x88, 0])); return }
      const length = frame[1] & 127
      const mask = frame.subarray(2, 6)
      const body = Buffer.from(frame.subarray(6, 6 + length))
      for (let i = 0; i < body.length; i++) body[i] ^= mask[i % 4]
      socket.write(Buffer.concat([Buffer.from([0x81, body.length]), body]))
    })
  })
  await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve))
  gateway = createGateway({ upstreamPort: backend.address().port })
  await new Promise(resolve => gateway.listen(0, '127.0.0.1', resolve))
  base = `http://127.0.0.1:${gateway.address().port}`
})

after(async () => {
  gateway.stop()
  for (const socket of backendSockets) socket.destroy()
  await new Promise(resolve => backend.close(resolve))
})

test('only declared business paths and read-only resources are exposed', () => {
  for (const path of ['/api/users/7', '/api/files/upload', '/ws/info', '/ws-native', '/uploads/avatars/a.png', '/models/a.vrm', '/motions/a.fbx']) {
    assert.equal(allowedRequest('GET', path), true, path)
  }
  for (const path of ['/', '/login', '/internal/agent/mcp', '/internal/funnel/readiness', '/actuator', '/api/app', '/api/internal/agent', '/apk/a.apk', '/.env', '/models/.env', '/uploads/chunks/.hidden', '/uploads/../.env', '/api/auth/%2e%2e/%2e%2e/internal', '/api/auth/..;/internal', '/api/auth/%252e%252e/internal', '/api//auth', 'http://elsewhere.invalid/api/users']) {
    assert.equal(allowedRequest('GET', path), false, path)
  }
  assert.equal(allowedRequest('POST', '/models/a.vrm'), false)
  assert.equal(allowedRequest('POST', '/ws-native'), false)
  assert.equal(allowedRequest('TRACE', '/api/users'), false)
  assert.equal(allowedRequest('GET', '/api/users', true), false)
})

test('blocked paths do not reach the backend or expose frontend files', async () => {
  const count = received.length
  for (const path of ['/internal/agent/mcp', '/internal/funnel/readiness', '/actuator/health', '/api/app', '/login', '/.env']) {
    const response = await fetch(base + path)
    assert.equal(response.status, 404)
    assert.equal(response.headers.get('cache-control'), 'no-store')
  }
  assert.equal(received.length, count)
})

test('raw encoded traversal and hidden-resource requests are blocked before any upstream request', async () => {
  const count = received.length
  for (const path of ['/api/auth/%2e%2e/%2e%2e/internal/agent', '/api/auth/%252e%252e/internal', '/api/auth/..;/internal', '/api/auth/%5c..%5cinternal', '/models/%2eenv', '/api//auth', '//internal/agent']) {
    assert.equal((await rawRequest(base, path)).status, 404, path)
  }
  assert.equal(received.length, count)
})

test('gateway health is explicitly not backend health', async () => {
  const response = await fetch(base + '/health')
  assert.deepEqual(await response.json(), { gateway: 'ok' })
})

test('authorization, Origin and body are preserved without trusting client forwarding headers', async () => {
  const response = await fetch(base + '/api/users/7', {
    method: 'POST', body: JSON.stringify({ nickname: 'fixture' }),
    headers: { Authorization: 'Bearer fixture-not-a-real-token', Origin: PUBLIC_ORIGIN, 'Content-Type': 'application/json', 'X-Forwarded-For': 'spoof', 'X-Forwarded-Proto': 'http' }
  })
  assert.equal(response.status, 200)
  const value = await response.json()
  assert.equal(value.body, '{"nickname":"fixture"}')
  const headers = received.at(-1).headers
  assert.equal(headers.authorization, 'Bearer fixture-not-a-real-token')
  assert.equal(headers.origin, PUBLIC_ORIGIN)
  assert.equal(headers['x-forwarded-for'], undefined)
  assert.equal(headers['x-forwarded-proto'], 'https')
  assert.equal(headers['accept-encoding'], 'identity')
})

test('spoofed forwarding prefix, identity and connection-nominated headers are removed', async () => {
  const response = await rawRequest(base, '/api/users/7', { headers: {
    Forwarded: 'for=spoof;proto=http', 'X-Forwarded-Prefix': '/internal', 'X-Forwarded-Host': 'spoof.invalid',
    'X-Real-IP': 'spoof', 'Tailscale-User-Login': 'spoof', Connection: 'keep-alive, x-test-hop', 'X-Test-Hop': 'spoof'
  } })
  assert.equal(response.status, 200)
  const headers = received.at(-1).headers
  for (const name of ['forwarded', 'x-forwarded-prefix', 'x-forwarded-host', 'x-real-ip', 'tailscale-user-login', 'x-test-hop']) assert.equal(headers[name], undefined, name)
  assert.equal(headers['x-forwarded-proto'], 'https')
})

test('unexpected Origins are refused for HTTP and WebSocket without reaching the backend', async () => {
  const count = received.length
  for (const origin of ['https://evil.example', PUBLIC_ORIGIN + '.evil.example', 'null', 'http://localhost:5173', 'app://nexus-chat']) {
    assert.equal((await rawRequest(base, '/api/users/7', { headers: { Origin: origin } })).status, 403)
    assert.equal((await rawRequest(base, '/ws-native', { headers: upgradeHeaders(origin) })).status, 403)
  }
  assert.equal(received.length, count)
})

test('SockJS info, XHR send and CORS preflight retain their paths and methods', async () => {
  for (const [path, method, body] of [['/ws/info?t=1', 'GET', undefined], ['/ws/000/session/xhr_send', 'POST', '["CONNECT"]'], ['/api/files/upload', 'OPTIONS', undefined]]) {
    const response = await fetch(base + path, { method, body })
    assert.equal(response.status, 200)
    const value = await response.json()
    assert.equal(value.path, path)
    assert.equal(value.method, method)
    assert.equal(value.body, body || '')
  }
})

test('SSE arrives before completion and is not buffered', async () => {
  const response = await fetch(base + '/api/agent/stream')
  assert.equal(response.headers.get('x-accel-buffering'), 'no')
  const reader = response.body.getReader()
  const first = await reader.read()
  assert.equal(new TextDecoder().decode(first.value), 'data: first\n\n')
  const last = await reader.read()
  assert.equal(new TextDecoder().decode(last.value), 'data: last\n\n')
  assert.equal((await reader.read()).done, true)
})

test('WebSocket upgrade and bidirectional payload pass through the SockJS and native paths', async () => {
  for (const path of ['/ws/000/session/websocket', '/ws-native']) {
    const socket = new WebSocket(base.replace('http:', 'ws:') + path)
    await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }) })
    const message = new Promise(resolve => socket.addEventListener('message', event => resolve(event.data), { once: true }))
    socket.send('fixture-stomp-transport')
    assert.equal(await message, 'fixture-stomp-transport')
    const closed = new Promise(resolve => socket.addEventListener('close', resolve, { once: true }))
    socket.close()
    await closed
  }
})

test('upstream connection failures return a safe error', async () => {
  const response = await fetch(base + '/api/users/refuse')
  assert.equal(response.status, 502)
  assert.deepEqual(await response.json(), { error: 'Backend unavailable' })
})

test('readiness requires the new backend policy and the exact published Origin', async () => {
  const original = readiness
  try {
    await assertBackendReady({ upstreamPort: backend.address().port })
    for (const value of [{ ...original, policyVersion: 'old-policy' }, { ...original, origins: ['https://evil.example'] }, { ...original, origins: [...original.origins, 'http://localhost:5173'] }]) {
      readiness = value
      await assert.rejects(assertBackendReady({ upstreamPort: backend.address().port }), /Updated Java backend/)
    }
  } finally { readiness = original }
})

test('a backend restarted without its safety profile cannot receive business HTTP or WebSocket traffic', async () => {
  const original = readiness
  const count = received.length
  try {
    readiness = { ...original, ready: false }
    assert.equal((await rawRequest(base, '/api/users/7')).status, 503)
    assert.equal((await rawRequest(base, '/ws-native', { headers: upgradeHeaders() })).status, 503)
    assert.equal(received.slice(count).filter(entry => entry.path !== '/internal/funnel/readiness').length, 0)
  } finally { readiness = original }
})

test('declared and chunked oversized request bodies are refused with a small test limit', async () => {
  const limited = createGateway({ upstreamPort: backend.address().port, maxBodyBytes: 64 })
  await new Promise(resolve => limited.listen(0, '127.0.0.1', resolve))
  const target = `http://127.0.0.1:${limited.address().port}`
  try {
    const count = received.length
    assert.equal((await fetch(target + '/api/users/7', { method: 'POST', body: 'x'.repeat(65) })).status, 413)
    assert.equal(received.length, count)
    assert.equal((await rawRequest(target, '/api/users/7', { method: 'POST', headers: { 'Transfer-Encoding': 'chunked' }, body: 'x'.repeat(65) })).status, 413)
  } finally { limited.stop() }
})

async function withGateway(options, run) {
  const limited = createGateway({ upstreamPort: backend.address().port, ...options })
  await new Promise(resolve => limited.listen(0, '127.0.0.1', resolve))
  try { await run(`http://127.0.0.1:${limited.address().port}`) } finally { limited.stop() }
}

const TIGHT = { windowMs: 60_000, auth: { client: 2, global: 5 }, upgrade: { client: 1, global: 50 }, general: { client: 3, global: 50 } }

test('the limiter counts per key and window and never grows past its key bound', () => {
  let time = 0
  const take = createLimiter({ windowMs: 1000, maxKeys: 2, now: () => time })
  assert.deepEqual([take('a', 2), take('a', 2), take('a', 2)], [true, true, false])
  assert.equal(take('b', 1), true)
  assert.equal(take('c', 1), false, 'table full of live windows refuses unknown keys')
  time = 999
  assert.equal(take('a', 2), false)
  time = 1000
  assert.equal(take('c', 1), true, 'expired windows are evicted to make room')
  assert.deepEqual([take('a', 2), take('a', 2), take('a', 2)], [true, true, false])
})

test('requests are classified and sized by what they are', () => {
  assert.equal(limitClass('POST', '/api/auth/login'), 'auth')
  assert.equal(limitClass('POST', '/api/%61uth/register?x=1'), 'auth')
  assert.equal(limitClass('GET', '/api/auth/registration-policy'), 'general')
  assert.equal(limitClass('POST', '/api/messages'), 'general')
  assert.equal(limitClass('GET', '/ws/1/2/websocket', true), 'upgrade')
  for (const path of ['/api/files/upload', '/api/files/upload/chunk', '/api/companion/assets/model', '/api/companion/user-assets/background',
    '/api/users/7/avatar', '/api/agent/sessions/as_1/attachments', '/api/agent/knowledge/3/documents']) {
    assert.equal(bodyLimit('POST', path, 1000, 10), 1000, path)
  }
  for (const [method, path] of [['POST', '/api/messages'], ['PUT', '/api/files/upload'], ['POST', '/api/files/upload/chunk/extra'],
    ['POST', '/api/users/7/avatar/x'], ['POST', '/api/auth/login']]) {
    assert.equal(bodyLimit(method, path, 1000, 10), 10, `${method} ${path}`)
  }
  assert.equal(bodyLimit('POST', '/api/messages', 5, 10), 5)
})

test('the visitor address is the last entry the local proxy wrote, or nothing', () => {
  const request = value => ({ headers: value === undefined ? {} : { 'x-forwarded-for': value } })
  assert.equal(clientAddress(request('203.0.113.9'), 'x-forwarded-for'), '203.0.113.9')
  assert.equal(clientAddress(request('198.51.100.1, 203.0.113.9'), 'x-forwarded-for'), '203.0.113.9')
  assert.equal(clientAddress(request('2001:db8::7'), 'x-forwarded-for'), '2001:db8::7')
  for (const bad of [undefined, '', 'unknown', '203.0.113.9, spoof', '203.0.113.9:443']) {
    assert.equal(clientAddress(request(bad), 'x-forwarded-for'), null, String(bad))
  }
  assert.equal(clientAddress(request('203.0.113.9'), null), null)
})

test('sign-in attempts are limited per visitor without reaching the backend, and other visitors are unaffected', async () => {
  await withGateway({ limits: TIGHT, clientAddressHeader: 'x-forwarded-for' }, async target => {
    const login = address => fetch(target + '/api/auth/login', { method: 'POST', headers: { 'X-Forwarded-For': address }, body: '{}' })
    assert.deepEqual([(await login('203.0.113.1')).status, (await login('203.0.113.1')).status], [200, 200])
    const count = received.length
    const refused = await login('198.51.100.200, 203.0.113.1')
    assert.equal(refused.status, 429)
    assert.equal(refused.headers.get('retry-after'), '60')
    assert.deepEqual(await refused.json(), { error: 'Too many requests' })
    assert.equal(received.length, count, 'a refused request costs the backend nothing, not even a readiness probe')
    assert.equal((await login('203.0.113.2')).status, 200)
    assert.equal((await fetch(target + '/api/users/7', { headers: { 'X-Forwarded-For': '203.0.113.1' } })).status, 200)
    assert.equal((await fetch(target + '/health')).status, 200)
  })
})

test('the shared limit holds when visitors cannot be told apart or keep changing address', async () => {
  await withGateway({ limits: TIGHT }, async target => {
    const statuses = []
    for (let i = 0; i < 7; i++) {
      statuses.push((await fetch(target + '/api/auth/login', { method: 'POST', headers: { 'X-Forwarded-For': `203.0.113.${i}` }, body: '{}' })).status)
    }
    assert.deepEqual(statuses, [200, 200, 200, 200, 200, 429, 429])
  })
})

test('limits reopen when the window passes', async () => {
  let time = 0
  await withGateway({ limits: TIGHT, clientAddressHeader: 'x-forwarded-for', now: () => time }, async target => {
    const get = () => fetch(target + '/api/users/7', { headers: { 'X-Forwarded-For': '203.0.113.5' } }).then(response => response.status)
    assert.deepEqual([await get(), await get(), await get(), await get()], [200, 200, 200, 429])
    time = 59_999
    assert.equal(await get(), 429)
    time = 60_000
    assert.equal(await get(), 200)
  })
})

test('WebSocket upgrades are limited before the backend is contacted', async () => {
  await withGateway({ limits: TIGHT, clientAddressHeader: 'x-forwarded-for' }, async target => {
    const upgrade = () => new Promise((resolve, reject) => {
      const request = http.request({ hostname: '127.0.0.1', port: new URL(target).port, path: '/ws-native',
        headers: { ...upgradeHeaders(), 'X-Forwarded-For': '203.0.113.8' } })
      request.on('upgrade', (response, socket) => { socket.destroy(); resolve(response.statusCode) })
      request.on('response', response => { response.resume(); resolve(response.statusCode) })
      request.on('error', reject)
      request.end()
    })
    assert.equal(await upgrade(), 101)
    const count = received.length
    assert.equal(await upgrade(), 429)
    assert.equal(received.length, count)
  })
})

test('only upload routes may carry large bodies', async () => {
  await withGateway({ maxBodyBytes: 4096, maxJsonBytes: 64 }, async target => {
    const post = path => fetch(target + path, { method: 'POST', body: 'x'.repeat(65) }).then(response => response.status)
    assert.equal(await post('/api/files/upload'), 200)
    assert.equal(await post('/api/messages'), 413)
    assert.equal((await rawRequest(target, '/api/messages', { method: 'POST', headers: { 'Transfer-Encoding': 'chunked' }, body: 'x'.repeat(65) })).status, 413)
    assert.equal((await fetch(target + '/api/files/upload', { method: 'POST', body: 'x'.repeat(4097) })).status, 413)
  })
})

test('the default limits leave ordinary use alone', () => {
  assert.ok(DEFAULT_LIMITS.general.client >= 600 && DEFAULT_LIMITS.auth.client <= 20 && DEFAULT_LIMITS.windowMs === 60_000)
  for (const kind of ['auth', 'upgrade', 'general']) assert.ok(DEFAULT_LIMITS[kind].global > DEFAULT_LIMITS[kind].client)
})

test('the gateway listens on loopback with no visitor header unless told otherwise', () => {
  assert.deepEqual(listenOptions(), { host: '127.0.0.1', clientAddressHeader: null })
  assert.deepEqual(listenOptions({ GATEWAY_LISTEN_HOST: '', GATEWAY_CLIENT_ADDRESS_HEADER: '' }), { host: '127.0.0.1', clientAddressHeader: null })
  assert.deepEqual(listenOptions({ GATEWAY_LISTEN_HOST: '0.0.0.0', GATEWAY_CLIENT_ADDRESS_HEADER: 'X-Forwarded-For' }),
    { host: '0.0.0.0', clientAddressHeader: 'x-forwarded-for' })
  for (const bad of [{ GATEWAY_LISTEN_HOST: 'localhost' }, { GATEWAY_LISTEN_HOST: '0.0.0.0:80' }, { GATEWAY_CLIENT_ADDRESS_HEADER: 'x forwarded' },
    { GATEWAY_CLIENT_ADDRESS_HEADER: 'x-forwarded-for\r\nx: y' }]) {
    assert.throws(() => listenOptions(bad), TypeError)
  }
})

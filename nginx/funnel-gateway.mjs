import http from 'node:http'
import { pathToFileURL } from 'node:url'

export const PUBLIC_ORIGIN = 'https://nexus-chat-2026-fwohlcnoevd.qoder.zone'
const POLICY_VERSION = 'nexus-funnel-v2'
const BACKEND_ORIGINS = new Set([PUBLIC_ORIGIN, 'http://localhost:*', 'http://127.0.0.1:*', 'app://*'])
const API_GROUPS = new Set(['auth', 'users', 'contacts', 'groups', 'chats', 'messages', 'files', 'posts', 'sync', 'companion', 'agent', 'tavern'])
const METHODS = new Set(['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'])
const HOP_HEADERS = new Set(['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade'])
const MAX_BODY_BYTES = 101 * 1024 * 1024

export function allowedRequest(method, target, upgrade = false) {
  if (!METHODS.has(method) || !target?.startsWith('/') || target.startsWith('//') || target.includes('#')) return false
  let path
  try { path = decodeURIComponent(target.split('?')[0]) } catch { return false }
  if (/[\\%;\u0000-\u001f\u007f]/.test(path) || path.includes('//') || path.split('/').some(part => part.startsWith('.'))) return false
  const sockjs = path === '/ws' || path.startsWith('/ws/')
  const websocket = sockjs || path === '/ws-native'
  if (upgrade) return method === 'GET' && websocket
  if (websocket) return (sockjs ? ['GET', 'POST', 'OPTIONS'] : ['GET', 'OPTIONS']).includes(method)
  if (path === '/health') return method === 'GET' || method === 'HEAD'
  if (path.startsWith('/api/')) return API_GROUPS.has(path.split('/')[2])
  return ['GET', 'HEAD', 'OPTIONS'].includes(method) && ['/uploads/', '/models/', '/motions/'].some(prefix => path.startsWith(prefix))
}

function endResponse(response, status, message) {
  if (response.writableEnded || response.destroyed) return
  if (response.headersSent) { response.destroy(); return }
  response.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' })
  response.end(JSON.stringify({ error: message }))
}

function filteredHeaders(headers) {
  const blocked = new Set(HOP_HEADERS)
  for (const name of String(headers.connection || '').split(',')) blocked.add(name.trim().toLowerCase())
  return Object.fromEntries(Object.entries(headers).filter(([name]) => !blocked.has(name.toLowerCase())))
}

function requestHeaders(request, port) {
  const headers = filteredHeaders(request.headers)
  for (const name of Object.keys(headers)) {
    if (name === 'forwarded' || name.startsWith('x-forwarded-') || name.startsWith('tailscale-') || name === 'x-real-ip') delete headers[name]
  }
  headers.host = `127.0.0.1:${port}`
  headers['x-forwarded-proto'] = 'https'
  headers['accept-encoding'] = 'identity'
  return headers
}

// Fail closed against an old backend, an inactive Funnel profile, or a backend
// restarted without the safety policy. No credentials or business data are read.
export function assertBackendReady({ upstreamPort = 8080, timeoutMs = 5000 } = {}) {
  return new Promise((resolve, reject) => {
    const fail = () => reject(new Error('Updated Java backend with the local,funnel profiles is required'))
    const probe = http.get({ hostname: '127.0.0.1', port: upstreamPort, path: '/internal/funnel/readiness', agent: false }, response => {
      if (response.statusCode !== 200) { response.resume(); fail(); return }
      let body = ''
      response.setEncoding('utf8')
      response.on('data', chunk => {
        body += chunk
        if (body.length > 4096) { response.destroy(); fail() }
      })
      response.on('error', fail)
      response.on('aborted', fail)
      response.on('end', () => {
        try {
          const value = JSON.parse(body)
          if (value.ready === true && value.policyVersion === POLICY_VERSION && value.publicOrigin === PUBLIC_ORIGIN
              && Array.isArray(value.origins) && value.origins.includes(PUBLIC_ORIGIN)
              && value.origins.every(origin => BACKEND_ORIGINS.has(origin))) resolve()
          else fail()
        } catch { fail() }
      })
    })
    probe.setTimeout(timeoutMs, () => { probe.destroy(); fail() })
    probe.on('error', fail)
  })
}

export function createGateway({ upstreamPort = 8080, maxBodyBytes = MAX_BODY_BYTES } = {}) {
  if (!Number.isSafeInteger(maxBodyBytes) || maxBodyBytes < 1) throw new TypeError('Invalid body limit')
  const agent = new http.Agent({ keepAlive: true, maxSockets: 64 })
  const sockets = new Set()
  const server = http.createServer({ maxHeaderSize: 16 * 1024 }, async (request, response) => {
    if (!allowedRequest(request.method, request.url)) {
      endResponse(response, 404, 'Route not exposed')
      request.resume()
      return
    }
    if (request.headers.origin && request.headers.origin !== PUBLIC_ORIGIN) {
      endResponse(response, 403, 'Origin not allowed')
      request.resume()
      return
    }
    if (request.url.split('?')[0] === '/health') {
      response.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' })
      response.end(JSON.stringify({ gateway: 'ok' }))
      return
    }
    if (Number(request.headers['content-length']) > maxBodyBytes) {
      endResponse(response, 413, 'Request too large')
      request.resume()
      return
    }
    try { await assertBackendReady({ upstreamPort }) } catch {
      endResponse(response, 503, 'Backend safety profile not ready')
      request.resume()
      return
    }
    if (request.destroyed || response.destroyed) return
    const upstream = http.request({ hostname: '127.0.0.1', port: upstreamPort, path: request.url, method: request.method, headers: requestHeaders(request, upstreamPort), agent }, upstreamResponse => {
      if (response.destroyed || response.writableEnded) { upstreamResponse.destroy(); return }
      const headers = filteredHeaders(upstreamResponse.headers)
      if (String(headers['content-type']).startsWith('text/event-stream')) {
        headers['cache-control'] = 'no-cache, no-transform'
        headers['x-accel-buffering'] = 'no'
      }
      response.writeHead(upstreamResponse.statusCode, headers)
      response.flushHeaders()
      upstreamResponse.on('error', () => response.destroy())
      upstreamResponse.pipe(response)
    })
    upstream.setTimeout(240_000, () => upstream.destroy(new Error('Upstream idle timeout')))
    upstream.on('error', () => endResponse(response, 502, 'Backend unavailable'))
    request.on('aborted', () => upstream.destroy())
    request.on('error', () => upstream.destroy())
    response.on('close', () => upstream.destroy())
    let bytes = 0
    let tooLarge = false
    request.on('data', chunk => {
      if (tooLarge) return
      bytes += chunk.length
      if (bytes > maxBodyBytes) {
        tooLarge = true
        request.unpipe(upstream)
        upstream.destroy()
        response.once('finish', () => request.destroy())
        endResponse(response, 413, 'Request too large')
        request.resume()
      }
    })
    request.pipe(upstream)
  })

  server.on('connection', socket => {
    sockets.add(socket)
    socket.setNoDelay(true)
    socket.on('close', () => sockets.delete(socket))
  })
  server.on('upgrade', async (request, socket, head) => {
    if (!allowedRequest(request.method, request.url, true) || request.headers.upgrade?.toLowerCase() !== 'websocket') {
      socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
      return
    }
    if (request.headers.origin && request.headers.origin !== PUBLIC_ORIGIN) {
      socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
      return
    }
    socket.on('error', () => {})
    try { await assertBackendReady({ upstreamPort }) } catch {
      if (!socket.destroyed) socket.end('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
      return
    }
    if (socket.destroyed) return
    const headers = requestHeaders(request, upstreamPort)
    headers.connection = 'Upgrade'
    headers.upgrade = 'websocket'
    const upstream = http.request({ hostname: '127.0.0.1', port: upstreamPort, path: request.url, method: 'GET', headers, agent: false })
    upstream.setTimeout(15_000, () => upstream.destroy(new Error('Upgrade timeout')))
    upstream.on('upgrade', (upstreamResponse, upstreamSocket, upstreamHead) => {
      if (socket.destroyed) { upstreamSocket.destroy(); return }
      const responseHeaders = filteredHeaders(upstreamResponse.headers)
      responseHeaders.connection = 'Upgrade'
      responseHeaders.upgrade = 'websocket'
      const headerLines = Object.entries(responseHeaders).flatMap(([name, value]) => (Array.isArray(value) ? value : [value]).map(item => `${name}: ${item}`))
      socket.write('HTTP/1.1 101 Switching Protocols\r\n' + headerLines.join('\r\n') + '\r\n\r\n')
      upstreamSocket.setTimeout(0)
      upstreamSocket.setNoDelay(true)
      if (upstreamHead.length) socket.write(upstreamHead)
      if (head.length) upstreamSocket.write(head)
      socket.on('error', () => upstreamSocket.destroy())
      upstreamSocket.on('error', () => socket.destroy())
      socket.on('close', () => upstreamSocket.destroy())
      upstreamSocket.on('close', () => socket.destroy())
      socket.pipe(upstreamSocket).pipe(socket)
    })
    upstream.on('response', response => {
      response.resume()
      socket.end(`HTTP/1.1 ${response.statusCode} Backend Rejected\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`)
    })
    upstream.on('error', () => socket.destroy())
    socket.on('error', () => upstream.destroy())
    socket.on('close', () => upstream.destroy())
    upstream.end()
  })
  server.maxConnections = 128
  server.headersTimeout = 15_000
  server.requestTimeout = 120_000
  server.setTimeout(300_000, socket => socket.destroy())
  server.on('close', () => agent.destroy())
  server.stop = () => {
    for (const socket of sockets) socket.destroy()
    server.close()
  }
  return server
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  try {
    await assertBackendReady()
    const server = createGateway()
    server.on('error', error => { console.error(`Gateway could not listen: ${error.code}`); process.exitCode = 1 })
    server.listen(18080, '127.0.0.1', () => console.log('Nexus backend gateway listening at http://127.0.0.1:18080'))
    process.once('SIGINT', () => server.stop())
    process.once('SIGTERM', () => server.stop())
  } catch {
    console.error('Gateway not started: restart the updated Java backend with the local,funnel profiles first')
    process.exitCode = 1
  }
}

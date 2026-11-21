import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

let server, streamAgentChat
const oldStorage = globalThis.localStorage
const oldFetch = globalThis.fetch
const sse = { headers: { 'Content-Type': 'text/event-stream; charset=utf-8' } }
before(async () => {
  globalThis.localStorage = { getItem: key => key === 'token' ? 'test-token' : null }
  server = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true },
    root: fileURLToPath(new URL('../../', import.meta.url)),
    resolve: { alias: { '@': fileURLToPath(new URL('../', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, watch: null }, logLevel: 'error' })
  ;({ streamAgentChat } = await server.ssrLoadModule('/src/services/agentApi.js'))
})
after(async () => { await server?.close(); globalThis.localStorage = oldStorage; globalThis.fetch = oldFetch })

function listen() {
  const events = [], deltas = [], errors = [], finishes = [], closes = []
  const stream = streamAgentChat('session/1', { input: 'question' }, {
    onEvent: event => events.push(event), onDelta: data => deltas.push(data),
    onError: error => errors.push(error), onDone: data => finishes.push(data), onClose: close => closes.push(close)
  })
  return { ...stream, events, deltas, errors, finishes, closes }
}

test('one event per frame across CRLF, UTF-8 and JSON data line boundaries', async () => {
  const bytes = new TextEncoder().encode(': keepalive\r\n\r\nevent: delta\r\nid: 7\r\ndata: {"text":\r\ndata: "你好"}\r\n\r\nevent: extra\ndata: {"value":1}\n\nevent: done\ndata:{"finishReason":"stop"}\n\n')
  globalThis.fetch = async (url, options) => {
    assert.match(url, /sessions\/session%2F1\/chat\/stream$/)
    assert.equal(options.headers.Authorization, 'Bearer test-token')
    assert.equal(JSON.parse(options.body).input, 'question')
    return new Response(new ReadableStream({ start(controller) {
      for (const byte of bytes) controller.enqueue(Uint8Array.of(byte))
      controller.close()
    } }), sse)
  }
  const stream = listen()
  await stream.done
  assert.deepEqual(stream.events.map(e => e.name), ['delta', 'extra', 'done'])
  assert.deepEqual(stream.events[0], { name: 'delta', data: { text: '你好' }, id: '7' })
  assert.deepEqual(stream.deltas, [{ text: '你好' }])
  assert.deepEqual(stream.finishes, [{ finishReason: 'stop' }])
  assert.equal(stream.closes[0].completed, true)
  assert.deepEqual(stream.errors, [])
})

for (const [label, response, code] of [
  ['non-SSE success', () => new Response('event: done\ndata: {"finishReason":"stop"}\n\n', { headers: { 'Content-Type': 'text/plain' } }), 'STREAM_CONTENT_TYPE'],
  ['invalid JSON', () => new Response('event: delta\ndata: broken\n\nevent: done\ndata: {"finishReason":"stop"}\n\n', sse), 'STREAM_INVALID_FRAME'],
  ['non-object payload', () => new Response('event: done\ndata: null\n\n', sse), 'STREAM_INVALID_FRAME'],
  ['EOF before done', () => new Response('event: delta\ndata: {"text":"part"}\n\n', sse), 'STREAM_INCOMPLETE'],
  ['unterminated done frame', () => new Response('event: done\ndata: {"finishReason":"stop"}\n', sse), 'STREAM_INCOMPLETE'],
  ['HTTP rejection', () => Response.json({ code: 'DENIED', message: 'not owner' }, { status: 403 }), 'DENIED']
]) {
  test(`${label} is not a completed stream`, async () => {
    globalThis.fetch = async () => response()
    const stream = listen()
    await stream.done
    assert.deepEqual(stream.finishes, [])
    assert.deepEqual(stream.errors.map(e => e.code), [code])
    assert.equal(stream.closes.length, 1)
    assert.equal(stream.closes[0].completed, false)
  })
}

test('cancels the pending POST without emitting a spurious transport error or done', async () => {
  let signal
  globalThis.fetch = (_url, options) => new Promise((_resolve, reject) => {
    signal = options.signal
    signal.addEventListener('abort', () => reject(signal.reason), { once: true })
  })
  const stream = listen()
  stream.cancel()
  await stream.done
  assert.equal(signal.aborted, true)
  assert.deepEqual(stream.events, [])
  assert.deepEqual(stream.errors, [])
  assert.equal(stream.closes.length, 1)
  assert.equal(stream.closes[0].cancelled, true)
})

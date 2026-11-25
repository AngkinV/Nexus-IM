import assert from 'node:assert/strict'
import { test } from 'node:test'
import { resolvePublicBackendUrls } from './publicBackendUrls.mjs'

const HOST = 'https://lvmacbook-air.tail007e54.ts.net'

test('Funnel API, SockJS and resource host resolve to the same HTTPS origin', () => {
  assert.deepEqual(resolvePublicBackendUrls(`${HOST}/api/`, `${HOST}/ws/`), {
    apiBaseUrl: `${HOST}/api`, wsUrl: `${HOST}/ws`, serverBaseUrl: HOST
  })
})

test('missing and relative URLs never silently fall back to localhost', () => {
  for (const value of [undefined, '', '/api', '//localhost/api']) {
    assert.throws(() => resolvePublicBackendUrls(value, `${HOST}/ws`), /explicitly configured/)
  }
})

test('loopback, private IPv4, mapped IPv6 and local names cannot be public endpoints', () => {
  for (const host of ['localhost', 'localhost.', 'x.localhost', 'device.local', '127.0.0.1', '2130706433', '0.0.0.0',
    '10.0.0.1', '192.168.0.1', '172.16.0.1', '169.254.1.1', '100.64.0.1', '[::1]', '[::]', '[::ffff:127.0.0.1]', '[fd00::1]', '[fe80::1]']) {
    assert.throws(() => resolvePublicBackendUrls(`https://${host}/api`, `https://${host}/ws`), /public HTTPS/, host)
  }
})

test('wrong protocols, path prefixes, fragments, queries and URL credentials are rejected', () => {
  for (const url of ['http://public.example.com/api', `${HOST}/api/v1`, `${HOST}/api?debug=1`, `${HOST}/api#fragment`, `${HOST}/api//`]) {
    assert.throws(() => resolvePublicBackendUrls(url, `${HOST}/ws`), /public HTTPS/)
  }
  const credentialed = new URL(`${HOST}/api`)
  credentialed.username = 'fixture'
  assert.throws(() => resolvePublicBackendUrls(credentialed.href, `${HOST}/ws`), /public HTTPS/)
  assert.throws(() => resolvePublicBackendUrls(`${HOST}/api`, HOST.replace('https:', 'wss:') + '/ws'), /public HTTPS/)
})

test('API and SockJS cannot point to different public gateways', () => {
  assert.throws(() => resolvePublicBackendUrls(`${HOST}/api`, 'https://other.example.com/ws'), /same origin/)
})

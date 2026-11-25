import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'

const source = (await readFile(new URL('./runtimeConfig.js', import.meta.url), 'utf8'))
  .replace("'./publicBackendUrls.mjs'", JSON.stringify(new URL('./publicBackendUrls.mjs', import.meta.url).href))
async function load(env, electron = false) {
  const code = `const __IS_ELECTRON__ = ${electron};\n` + source.replaceAll('import.meta.env', `(${JSON.stringify(env)})`)
  return import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'))
}

test('development keeps the existing loopback backend', async () => {
  const config = await load({ DEV: true, VITE_API_BASE_URL: 'https://unused.invalid/api', VITE_WS_URL: 'https://unused.invalid/ws' })
  assert.equal(config.API_BASE_URL, 'http://127.0.0.1:8080/api')
  assert.equal(config.WS_URL, 'http://127.0.0.1:8080/ws')
})

test('web production requires configured backend URLs instead of localhost fallback', async () => {
  await assert.rejects(load({ DEV: false }), /explicitly configured/)
  await assert.rejects(load({ DEV: false, VITE_API_BASE_URL: '/api', VITE_WS_URL: '/ws' }), /explicitly configured/)
})

test('configured production URLs and server resource root stay aligned', async () => {
  const config = await load({ DEV: false, VITE_API_BASE_URL: 'https://backend.example.invalid/api', VITE_WS_URL: 'https://backend.example.invalid/ws' })
  assert.equal(config.API_BASE_URL, 'https://backend.example.invalid/api')
  assert.equal(config.WS_URL, 'https://backend.example.invalid/ws')
  assert.equal(config.SERVER_BASE_URL, 'https://backend.example.invalid')
})

test('Electron local-backend behavior is not changed by web deployment', async () => {
  const config = await load({ DEV: false }, true)
  assert.equal(config.API_BASE_URL, 'http://localhost:8080/api')
  assert.equal(config.WS_URL, 'http://localhost:8080/ws')
})

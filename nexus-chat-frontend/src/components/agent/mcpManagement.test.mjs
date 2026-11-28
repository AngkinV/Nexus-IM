// T4A regressions. T1 executes serially; these fixtures are NOT production/E2E data.
import { after, before, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { readFile } from 'node:fs/promises'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'
import axios from 'axios'

const SID = '714e3483-cf38-42e4-88cd-6cf56af62f71'
const SECRET = 'fixture-only-mcp-secret'
const oldStorage = globalThis.localStorage, oldAdapter = axios.defaults.adapter
let vite, api, useStore, helpers, messages, store, requests, replies, owner = 41, token = 'fixture-token-41'
const ok = data => ({ data: { code: 'OK', data }, status: 200, headers: {} })
const record = (version = 1, changes = {}) => ({ serverId: SID, ownerUserId: owner, namespace: 'srv_' + SID.replaceAll('-', ''),
  displayName: 'Synthetic service', transport: 'streamable_http', url: 'https://mcp.example.invalid/mcp', enabled: true,
  configVersion: version, authorizationVersion: version, timeoutSec: 10, resultMaxChars: 8000, allowedTools: [],
  credentialConfigured: false, credentialKind: 'none', credentialHeaderName: null, registrationState: 'registered', catalogVersion: null,
  status: { state: 'unknown', configVersion: version, checkedAt: null, latencyMs: null }, ...changes })
const draft = (changes = {}) => ({ serverId: null, displayName: 'Synthetic service', url: 'https://mcp.example.invalid/mcp', enabled: false,
  timeoutSec: 10, resultMaxChars: 8000, credentialKind: 'bearer', headerName: 'X-API-Key', secret: '', clearCredential: false, allowedTools: [], ...changes })
const reply = data => replies.push(() => ok(data))
const defer = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
const turn = () => new Promise(resolve => setImmediate(resolve))

before(async () => {
  globalThis.localStorage = { getItem: key => key === 'token' ? token : key === 'user' ? JSON.stringify({ id: owner }) : null }
  axios.defaults.adapter = async config => {
    requests.push({ method: config.method, path: config.url, auth: config.headers.Authorization,
      body: typeof config.data === 'string' ? JSON.parse(config.data) : config.data })
    assert.ok(replies.length, `unexpected ${config.method} ${config.url}`)
    return replies.shift()()
  }
  vite = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true },
    root: fileURLToPath(new URL('../../../', import.meta.url)),
    resolve: { alias: { '@': fileURLToPath(new URL('../../', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, watch: null }, logLevel: 'error' })
  ;({ agentAPI: api } = await vite.ssrLoadModule('/src/services/agentApi.js'))
  ;({ useAgentWorkspaceStore: useStore } = await vite.ssrLoadModule('/src/stores/agentWorkspace.js'))
  helpers = await vite.ssrLoadModule('/src/components/agent/mcpManagement.js')
  ;({ mcpMessages: messages } = await vite.ssrLoadModule('/src/components/agent/mcpMessages.js'))
})
after(async () => { await vite?.close(); axios.defaults.adapter = oldAdapter; globalThis.localStorage = oldStorage })
beforeEach(() => { owner = 41; token = 'fixture-token-41'; requests = []; replies = []; setActivePinia(createPinia()); store = useStore() })

test('MCP list and writes need no session and use only the authenticated Java BFF', async () => {
  reply([]); await store.loadMcpServers()
  assert.equal(store.sessionId, null)
  reply(record()); await store.createMcpServer(helpers.mcpPayload(draft(), true))
  reply(record(2, { enabled: false })); await store.patchMcpServer(SID, 1, { enabled: false })
  reply({ serverId: SID, configVersion: 2, catalogVersion: null, tools: [] }); await store.loadMcpTools(SID, 2)
  reply({ serverId: SID, configVersion: 2, registrationState: 'registered', status: record(2).status }); await store.loadMcpStatus(SID, 2)
  reply({ deleted: true, serverId: SID, configVersion: 3 }); await store.deleteMcpServer(SID, 2)
  assert.deepEqual(requests.map(request => [request.method, request.path]), [
    ['get', '/agent/mcp/servers'], ['post', '/agent/mcp/servers'], ['patch', `/agent/mcp/servers/${SID}`],
    ['get', `/agent/mcp/servers/${SID}/tools`], ['get', `/agent/mcp/servers/${SID}/status`], ['delete', `/agent/mcp/servers/${SID}`]
  ])
  assert.ok(requests.every(request => request.auth === 'Bearer fixture-token-41'))
  assert.equal(requests[2].body.expectedConfigVersion, 1)
  assert.deepEqual(requests[5].body, { expectedConfigVersion: 2 })
  assert.equal(store.mcp.servers.length, 0)
})

test('test and refresh send explicit versions, not tool arguments or direct MCP requests', async () => {
  reply([record()]); await store.loadMcpServers()
  const result = { serverId: SID, configVersion: 1, ok: true, catalogVersion: 'observed-version', tools: [],
    status: { state: 'connected', configVersion: 1, checkedAt: '2026-11-23T00:00:00Z', latencyMs: 0 } }
  reply(result); await store.probeMcpServer(SID, 1)
  reply(result); await store.probeMcpServer(SID, 1, true)
  assert.equal(requests[1].path, `/agent/mcp/servers/${SID}/test`)
  assert.equal(requests[2].path, `/agent/mcp/servers/${SID}/tools/refresh`)
  assert.deepEqual(requests[1].body, { expectedConfigVersion: 1 })
  assert.deepEqual(requests[2].body, { expectedConfigVersion: 1 })
  assert.equal(store.mcp.servers[0].status.latencyMs, 0)
})

test('registration or malformed probe evidence is never connected', async () => {
  reply([record(1, { status: { state: 'connected', configVersion: 1, checkedAt: null } })]); await store.loadMcpServers()
  assert.equal(helpers.mcpStateKey(store.mcp.servers[0]), 'unknown')
  reply({ serverId: SID, configVersion: 1, ok: true, tools: [], catalogVersion: null,
    status: { state: 'connected', configVersion: 1, checkedAt: 'not-a-date' } })
  await assert.rejects(store.probeMcpServer(SID, 1), error => error.code === 'MCP_PROBE_FAILED')
  assert.equal(store.mcp.servers[0].status.state, 'unknown')
})

test('a failed real probe remains error while raw credentials never enter state', async () => {
  reply([record(1, { credential: { secret: SECRET }, credentialCiphertext: SECRET })]); await store.loadMcpServers()
  reply({ serverId: SID, configVersion: 1, ok: false, tools: [], catalogVersion: null, credential: SECRET,
    status: { state: 'error', configVersion: 1, checkedAt: '2026-11-23T00:00:00Z', latencyMs: 5, error: { summary: SECRET } } })
  await assert.rejects(store.probeMcpServer(SID, 1), error => error.code === 'MCP_PROBE_FAILED')
  assert.equal(store.mcp.servers[0].status.state, 'error')
  assert.ok(!JSON.stringify(store.mcp).includes(SECRET))
})

test('changed-server credential rejection is preserved and never stores raw error text or a fake success', async () => {
  reply([record(1, { credentialConfigured: true, credentialKind: 'bearer' })]); await store.loadMcpServers()
  const original = JSON.stringify(store.mcp.servers[0])
  replies.push(() => { throw { response: { status: 400, data: { code: 'MCP_CREDENTIAL_REQUIRED', message: SECRET } } } })
  await assert.rejects(store.patchMcpServer(SID, 1, { url: 'https://other.example.invalid/mcp' }),
    error => error.code === 'MCP_CREDENTIAL_REQUIRED')
  assert.equal(store.mcp.error, 'MCP_CREDENTIAL_REQUIRED')
  assert.equal(JSON.stringify(store.mcp.servers[0]), original)
  assert.ok(!JSON.stringify(store.mcp).includes(SECRET))
  assert.match(messages.zh.mcp.credentialRequired, /重新填写/)
  assert.match(messages.en.mcp.credentialRequired, /Enter a new credential/)
})

test('old probe after patch cannot overwrite the new configuration', async () => {
  reply([record()]); await store.loadMcpServers()
  const pending = defer(); replies.push(() => pending.promise)
  const probing = store.probeMcpServer(SID, 1); await turn()
  reply(record(2)); await store.patchMcpServer(SID, 1, { displayName: 'Changed' })
  pending.resolve(ok({ serverId: SID, configVersion: 1, ok: true, tools: [], catalogVersion: null,
    status: { state: 'connected', configVersion: 1, checkedAt: '2026-11-23T00:00:00Z' } }))
  await assert.rejects(probing, error => error.code === 'MCP_STALE')
  assert.equal(store.mcp.servers[0].configVersion, 2)
  assert.equal(store.mcp.servers[0].status.state, 'unknown')
})

test('a late old-account response retains zero data in the new account', async () => {
  const pending = defer(); replies.push(() => pending.promise)
  const loading = store.loadMcpServers(); await turn()
  owner = 42; token = 'fixture-token-42'; store.syncMcpOwner()
  pending.resolve(ok([record(1, { ownerUserId: 41, credential: SECRET })])); await loading
  assert.equal(store.mcp.owner, '42')
  assert.equal(store.mcp.servers.length, 0)
  assert.ok(!JSON.stringify(store.mcp).includes(SECRET))
})

test('Axios captures the original account token before an async interceptor can retarget a write', async () => {
  const payload = helpers.mcpPayload(draft({ secret: SECRET }), true)
  reply(record(1, { ownerUserId: 41 }))
  const writing = api.createMcpServer(payload)
  owner = 42; token = 'fixture-token-42'
  await writing
  assert.equal(requests[0].auth, 'Bearer fixture-token-41')
  payload.credential.secret = ''
})

test('invalid headers, URI credentials/query, limits and secret line breaks are rejected', () => {
  for (const changes of [{ url: 'https://user:fixture@mcp.example.invalid/mcp' }, { url: 'https://mcp.example.invalid/mcp?token=fixture' },
    { url: 'file:///etc/hosts' }, { displayName: 'x'.repeat(81) }, { timeoutSec: 0 }, { resultMaxChars: 255 },
    { secret: 'fixture\r\nheader' }, { secret: 'x'.repeat(4001) }, { secret: SECRET, credentialKind: 'header', headerName: 'X-Actor-User-Id' }]) {
    assert.throws(() => helpers.mcpPayload(draft(changes), true), /MCP_FORM_INVALID/)
  }
})

test('write-only drafts can keep or explicitly clear credentials and are wiped after use', () => {
  assert.ok(!Object.hasOwn(helpers.mcpPayload(draft(), true), 'credential'))
  const form = draft({ serverId: SID, secret: SECRET, allowedTools: [`${SID}:read_docs`] })
  assert.equal(helpers.mcpPayload(form).credential.secret, SECRET)
  helpers.clearMcpCredential(form)
  assert.equal(form.secret, '')
  assert.ok(!Object.hasOwn(helpers.mcpPayload(form), 'credential'))
  form.clearCredential = true
  assert.equal(helpers.mcpPayload(form).clearCredential, true)
})

test('domain-local English/Chinese keys match and missing run data is not invented', () => {
  assert.deepEqual(Object.keys(messages.en.mcp).sort(), Object.keys(messages.zh.mcp).sort())
  assert.equal(helpers.publicMcpRunAuthorization(null), null)
  const projected = helpers.publicMcpRunAuthorization({ runId: 'run_one', attemptId: 'attempt_one', agentId: 'agent_one', secret: SECRET,
    services: [{ serverId: SID, configVersion: 1, toolIds: [`${SID}:read_docs`], credential: SECRET }] })
  assert.ok(!JSON.stringify(projected).includes(SECRET))
  assert.equal(projected.services[0].toolIds[0], `${SID}:read_docs`)
})

test('the existing Composer has a real Element Plus MCP drawer entrance and no direct Python access', async () => {
  const composer = await readFile(new URL('./AgentComposer.vue', import.meta.url), 'utf8')
  const manager = await readFile(new URL('./AgentMcpManager.vue', import.meta.url), 'utf8')
  assert.match(composer, /<AgentMcpManager v-model="mcpOpen"/)
  assert.match(composer, /class="tool mcp-entry"/)
  assert.match(manager, /<ElDrawer/)
  assert.match(manager, /restoreFocus/)
  assert.match(manager, /prefers-reduced-motion/)
  assert.match(manager, /clearMcpCredential/)
  assert.ok(!manager.includes('/v1/agent/mcp') && !manager.includes('8100'))
})

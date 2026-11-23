import { after, before, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { readFileSync } from 'node:fs'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'

let server, describeModelError, retryAtOf, modelCatalogNote, api, user, store, useUserStore, useAgentProvidersStore, rows, calls
let channels
const originalStorage = globalThis.localStorage
const wrap = data => ({ data: { data } })
const model = { id: 'catalog-model', chat: null, embedding: false }
const catalog = (fields = {}) => wrap({ providerId: 4, purpose: 'chat', revision: 'r1', status: 'ready', models: [model], fetchedAt: '2026-06-01T12:00:00Z', truncated: false, ...fields })

before(async () => {
  globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} }
  server = await createServer({
    configFile: false, root: fileURLToPath(new URL('../../', import.meta.url)),
    resolve: { alias: { '@': fileURLToPath(new URL('../', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, watch: null }, logLevel: 'error'
  })
  ;({ agentAPI: api } = await server.ssrLoadModule('/src/services/agentApi.js'))
  ;({ useUserStore } = await server.ssrLoadModule('/src/stores/user.js'))
  ;({ useAgentProvidersStore, describeModelError, retryAtOf, modelCatalogNote } = await server.ssrLoadModule('/src/stores/agentProviders.js'))
  channels = await server.ssrLoadModule('/src/stores/agentProviders.js')
})
after(async () => { await server?.close(); globalThis.localStorage = originalStorage })
beforeEach(async () => {
  setActivePinia(createPinia())
  user = useUserStore()
  user.currentUser = { id: 7 }
  store = useAgentProvidersStore()
  rows = [{ id: 4, purpose: 'chat', provider: 'custom', revision: 'r1' }, { id: 5, purpose: 'embedding', revision: 'r2' }]
  calls = []
  api.listProviders = async () => wrap(rows)
  api.listProviderModels = async id => { calls.push(id); return catalog() }
  api.testProvider = () => assert.fail('catalog reads must never probe a model')
  await store.fetchAll()
})

test('only saved providers are read; concurrent requests share a two-minute catalog', async t => {
  let now = Date.now()
  t.mock.method(Date, 'now', () => now)
  await store.fetchModels(null)
  await store.fetchModels(88)
  await Promise.all([store.fetchModels(4), store.fetchModels(4)])
  assert.deepEqual(store.getModelsForProvider(4), [model])
  assert.deepEqual(store.chatProviders.map(p => p.id), [4])
  now += 119999
  await store.fetchModels(4)
  assert.deepEqual(calls, [4])
  now++
  await store.fetchModels(4)
  await store.fetchModels(4, { force: true })
  assert.deepEqual(calls, [4, 4, 4])
})

test('empty, failed and mismatched responses cannot leave old choices visible', async () => {
  for (const response of [catalog({ status: 'empty', models: [] }), catalog({ status: 'error', error: { code: 'DENIED' } }), catalog({ revision: 'older' }), catalog({ purpose: 'embedding' }), catalog({ providerId: 5 }), null]) {
    api.listProviderModels = async () => catalog()
    await store.fetchModels(4, { force: true })
    api.listProviderModels = async () => { if (!response) throw new Error('offline'); return response }
    await store.fetchModels(4, { force: true })
    assert.deepEqual(store.getModelsForProvider(4), [])
  }
})

test('saving invalidates even an unchanged revision; old requests cannot overwrite the saved catalog', async () => {
  const slow = Promise.withResolvers()
  api.listProviderModels = () => slow.promise
  const first = store.fetchModels(4)
  api.upsertProvider = async () => wrap(rows[0])
  api.listProviderModels = async () => catalog({ models: [{ ...model, id: 'new-alias' }] })
  await store.upsert({ provider: 'custom', purpose: 'chat', defaultModel: null })
  await store.fetchModels(4)
  slow.resolve(catalog())
  await first
  assert.deepEqual(store.getModelsForProvider(4).map(m => m.id), ['new-alias'])
})

test('revision changes, deletion and logout discard late catalogs, including after signing back in', async () => {
  const oldRevision = Promise.withResolvers(), deleted = Promise.withResolvers(), loggedOut = Promise.withResolvers()
  api.listProviderModels = () => oldRevision.promise
  const first = store.fetchModels(4)
  rows = [{ ...rows[0], revision: 'r2' }]
  await store.fetchAll()
  api.listProviderModels = () => deleted.promise
  const second = store.fetchModels(4)
  oldRevision.resolve(catalog()); await first
  assert.deepEqual(store.getModelsForProvider(4), [])
  api.deleteProvider = async () => { rows = []; return wrap({ deleted: true }) }
  await store.remove(4)
  deleted.resolve(catalog({ revision: 'r2' })); await second
  assert.deepEqual(store.getModelsForProvider(4), [])
  rows = [{ id: 4, purpose: 'chat', revision: 'r1' }]
  await store.fetchAll()
  api.listProviderModels = () => loggedOut.promise
  const third = store.fetchModels(4)
  user.currentUser = null
  assert.deepEqual(store.providers, [])
  user.currentUser = { id: 8 }
  await store.fetchAll()
  api.listProviderModels = async () => catalog({ models: [{ ...model, id: 'other-account' }] })
  await store.fetchModels(4)
  loggedOut.resolve(catalog()); await third
  assert.deepEqual(store.getModelsForProvider(4).map(m => m.id), ['other-account'])
  user.currentUser = { id: 7 }
  assert.deepEqual(store.getModelsForProvider(4), [])
})

test('a paid test never supplies catalog choices', async () => {
  api.testProvider = async () => wrap({ ok: true, availableModels: ['not-a-catalog'] })
  await store.test(4)
  assert.deepEqual(store.getModelsForProvider(4), [])
  assert.deepEqual(calls, [])
})

test('a listed model is only flagged when its capability is explicitly false', () => {
  const models = [{ id: 'a', chat: null, embedding: false }, { id: 'b', chat: true, embedding: null }]
  assert.equal(modelCatalogNote(models, ' a ', 'chat'), null)
  assert.equal(modelCatalogNote(models, 'b', 'chat'), null)
  assert.equal(modelCatalogNote(models, 'b', 'embedding'), null)
  assert.equal(modelCatalogNote(models, 'a', 'embedding'), 'incompatible')
  assert.equal(modelCatalogNote(models, 'typed-by-hand', 'chat'), 'unconfirmed')
  // No catalog yet (loading, error, unsupported, empty): a saved value is only ever unconfirmed.
  assert.equal(modelCatalogNote([], 'a', 'embedding'), 'unconfirmed')
  assert.equal(modelCatalogNote([], '  ', 'chat'), null)
})

test('model errors map to one key per code, with reason outranking the wrapper code', () => {
  const zh = JSON.parse(readFileSync(new URL('../locales/zh.json', import.meta.url))).modelError
  const en = JSON.parse(readFileSync(new URL('../locales/en.json', import.meta.url))).modelError
  const codes = [
    'PYTHON_UNAVAILABLE', 'INTERNAL_AUTH', 'TIMEOUT', 'AUTH', 'PERMISSION', 'MODEL_NOT_FOUND', 'NOT_FOUND', 'RATE_LIMIT',
    'CONTEXT_LENGTH', 'BAD_REQUEST', 'NETWORK', 'UPSTREAM', 'CONTENT_FILTER', 'OUTPUT_LIMIT', 'EMPTY_RESPONSE', 'INCOMPLETE',
    'INVALID_RESPONSE', 'CONFIG', 'CONFIG_CHANGED', 'no_credential', 'no_provider', 'endpoint_mismatch', 'unreadable_api_key',
    'no_model', 'AGENT_RUN_40901', 'AGENT_RUN_40401', 'AGENT_TOOL_50301', 'AGENT_STREAM_50003', 'AGENT_SYS_50001', 'AGENT_MODEL_50202'
  ]
  for (const code of codes) {
    assert.equal(describeModelError({ code }).key, `modelError.${code}`)
    assert.ok(zh[code] && en[code], code)
  }
  const settings = codes.filter(code => describeModelError({ code }).settings)
  assert.deepEqual(settings, ['AUTH', 'PERMISSION', 'MODEL_NOT_FOUND', 'NOT_FOUND', 'CONFIG', 'CONFIG_CHANGED',
    'no_credential', 'no_provider', 'endpoint_mismatch', 'unreadable_api_key', 'no_model'])
  assert.equal(new Set([zh.PYTHON_UNAVAILABLE, zh.INTERNAL_AUTH, zh.AUTH, zh.RATE_LIMIT]).size, 4)

  const wrapped = describeModelError({ code: 'AGENT_MODEL_50202', reason: 'AUTH', message: 'bad key', httpStatus: 401 })
  assert.deepEqual(wrapped, { key: 'modelError.AUTH', fallback: 'bad key', detail: 'AGENT_MODEL_50202 · AUTH · HTTP 401', settings: true })
  assert.equal(describeModelError({ code: 'RATE_LIMITED', httpStatus: 429 }).key, 'modelError.RATE_LIMIT')
  assert.equal(describeModelError({ code: 'AGENT_RUN_40901', message: 'run is already executing' }).key, 'modelError.AGENT_RUN_40901')
  assert.deepEqual(describeModelError({ code: 'SOMETHING_NEW', summary: 'Upstream said so' }),
    { key: null, fallback: 'Upstream said so', detail: 'SOMETHING_NEW', settings: false })
})

test('a retry wait exists only when the backend gave one', t => {
  t.mock.timers.enable({ apis: ['Date'], now: 1000 })
  assert.equal(retryAtOf({ code: 'RATE_LIMIT', retryAfterSeconds: 35 }), 36000)
  assert.equal(retryAtOf({ code: 'RATE_LIMIT' }), null)
  assert.equal(retryAtOf({ code: 'RATE_LIMIT', retryAfterSeconds: 0 }), null)
})

test('a channel that cannot be used as configured points at the settings, whichever side reports it', async () => {
  // Java refuses before calling Python; Python refuses a channel it was handed. Same reading.
  for (const message of ['Selected channel has no configured chat model or credential', 'Selected channel is missing a provider, model or credential']) {
    const described = describeModelError({ code: 'AGENT_PARAM_40001', reason: 'CONFIG', message })
    assert.equal(described.key, 'modelError.CONFIG')
    assert.equal(described.settings, true)
    assert.match(described.detail, /AGENT_PARAM_40001/)
  }
})

// ---- many channels per account -------------------------------------------------

test('the catalogue offers official, aggregator, local and custom providers with a usable template each', () => {
  for (const presets of [channels.PROVIDER_PRESETS, channels.EMBEDDING_PRESETS]) {
    assert.equal(new Set(presets.map(p => p.key)).size, presets.length, 'vendor keys are unique')
    assert.deepEqual([...new Set(presets.map(p => p.group))].sort(), [...channels.VENDOR_GROUPS].sort())
    for (const p of presets) {
      assert.ok(Object.values(channels.API_FORMAT).includes(p.format), `${p.key} names a supported protocol`)
      if (p.key === 'custom') assert.ok(p.formats.length > 1 && p.baseUrl === '', 'a custom endpoint chooses its own')
      else assert.match(p.baseUrl, /^https?:\/\/[^\s?#]+[^/]$/, `${p.key} has an address to start from`)
      assert.ok(p.key.length <= 40 && p.key === p.key.toLowerCase())
    }
    assert.equal(presets.filter(p => p.key === 'custom').length, 1)
  }
  assert.ok(channels.PROVIDER_PRESETS.length >= 20)
  assert.ok(!channels.EMBEDDING_PRESETS.find(p => p.key === 'custom').formats.includes('anthropic'), 'Anthropic has no embeddings')
})

test('a new channel never takes the id of a saved one, however many share a vendor', () => {
  const saved = []
  for (const expected of ['custom', 'custom-2', 'custom-3', 'custom-4']) {
    const id = channels.freeChannelId('custom', saved)
    assert.equal(id, expected)
    saved.push({ provider: id })
  }
  assert.equal(channels.freeChannelId('deepseek', saved), 'deepseek')
  assert.equal(channels.freeChannelId('OpenAI', [{ provider: 'openai' }, { provider: 'OPENAI-2' }]), 'openai-3')
  assert.equal(channels.freeChannelId('', []), 'custom')
})

test('a channel is shown under the vendor the backend recorded, or the one its old id implied', () => {
  const key = (row, purpose) => channels.vendorOf(row, purpose).key
  assert.equal(key({ provider: 'deepseek-2', vendor: 'deepseek' }), 'deepseek')
  assert.equal(key({ provider: 'openai', vendor: 'custom' }), 'custom', 'the recorded vendor wins over the id')
  assert.equal(key({ provider: 'deepseek-personal', vendor: 'deepseek-personal' }), 'deepseek', 'older row: the backend echoes its id')
  assert.equal(key({ provider: 'moonshot' }), 'moonshot', 'backend that reports no vendor yet')
  assert.equal(key({ provider: 'my-relay', vendor: 'my-relay' }), 'custom')
  assert.equal(key({ provider: 'x', vendor: 'voyage' }, 'embedding'), 'voyage')
  assert.equal(key({ provider: 'x', vendor: 'voyage' }, 'chat'), 'custom', 'not a chat vendor')
  assert.equal(channels.formatOf({ provider: 'claude', vendor: 'claude', apiFormat: 'anthropic' }), 'anthropic')
  assert.equal(channels.formatOf({ provider: 'gemini' }), 'gemini')
  assert.equal(channels.formatOf({ provider: 'my-relay' }), 'openai-compatible')
})

test('a channel that cannot answer says why; only a local server may lack a key', () => {
  assert.equal(channels.channelIssue({ defaultModel: 'm', hasApiKey: true }), null)
  assert.equal(channels.channelIssue({ defaultModel: '  ', hasApiKey: true }), 'model')
  assert.equal(channels.channelIssue({ defaultModel: 'm', hasApiKey: false }), 'key')
  assert.equal(channels.channelIssue({ defaultModel: 'm', hasApiKey: false, keyOptional: true }), null)
  assert.equal(channels.channelIssue({ defaultModel: 'm', hasApiKey: false, keyOptional: false, provider: 'ollama' }), 'key', 'the backend decides when it says')
  assert.equal(channels.channelIssue({ defaultModel: 'm', hasApiKey: false, provider: 'Ollama' }), null, 'older backend: the id is all there is')
  assert.equal(channels.channelIssue(null), 'model')
})

test('dozens of channels stay findable: search spans name, vendor, model and address; groups keep the default first', () => {
  const saved = [
    { id: 1, provider: 'custom', vendor: 'custom', displayName: 'Relay B', defaultModel: 'glm-flash', baseUrl: 'https://b.invalid/v1', hasApiKey: true },
    { id: 2, provider: 'custom-2', vendor: 'custom', displayName: 'Relay A', defaultModel: 'step-flash', baseUrl: 'https://a.invalid/v1', hasApiKey: true, isDefault: true },
    { id: 3, provider: 'deepseek', vendor: 'deepseek', displayName: 'DeepSeek', defaultModel: '', hasApiKey: true },
    { id: 4, provider: 'ollama', vendor: 'ollama', displayName: 'Laptop', defaultModel: 'qwen', hasApiKey: false, keyOptional: true },
    { id: 5, provider: 'openrouter', vendor: 'openrouter', displayName: 'Router', defaultModel: 'any/model', hasApiKey: false, keyOptional: false },
    ...Array.from({ length: 30 }, (_, i) => ({ id: 100 + i, provider: `custom-${i + 3}`, vendor: 'custom', displayName: `Extra ${String(i).padStart(2, '0')}`, defaultModel: 'm', hasApiKey: true }))
  ]
  const ids = q => channels.filterChannels(saved, q).map(c => c.id)
  assert.equal(ids('').length, 35)
  assert.deepEqual(ids('relay'), [1, 2])
  assert.deepEqual(ids('STEP'), [2], 'model, case-insensitive')
  assert.deepEqual(ids('b.invalid'), [1], 'address')
  assert.deepEqual(ids('deepseek'), [3], 'vendor')
  assert.deepEqual(ids('relay glm'), [1], 'every word must match')
  assert.deepEqual(ids('nothing-like-this'), [])

  const groups = channels.groupChannels(saved)
  assert.deepEqual(groups.map(g => g.key), ['official', 'aggregator', 'local', 'custom'])
  const custom = groups.at(-1).items
  assert.equal(custom.length, 32)
  assert.deepEqual(custom.slice(0, 3).map(c => c.id), [2, 100, 101], 'default first, then by name')
  assert.equal(groups[0].items[0].issue, 'model')
  assert.equal(groups[1].items[0].issue, 'key')
  assert.equal(groups[2].items[0].issue, null)
  assert.deepEqual(channels.groupChannels(saved, 'laptop').map(g => [g.key, g.items.length]), [['local', 1]], 'empty groups are left out')
  assert.deepEqual(channels.groupChannels([], ''), [])
})

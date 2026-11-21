import { after, before, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'
import axios from 'axios'

let server, useAgentWorkspaceStore, store, api, requests, responses
const oldStorage = globalThis.localStorage, oldAdapter = axios.defaults.adapter
const ok = data => ({ data: { code: 'OK', data }, status: 200, headers: {} })
const base = { id: 'att_base', fileName: 'demo.txt', content: 'before' }
const saved = { id: 'att_vers', baseAttachmentId: base.id, content: 'after' }
const task = { id: 'task_001', title: 'Check the result', status: 'pending' }
const preview = { baseAttachmentId: base.id, before: 'before', after: 'after', diff: 'actual diff', baseHash: 'base-hash', patchHash: 'patch-hash' }

before(async () => {
  globalThis.localStorage = { getItem: key => ({ token: 'test-token', user: '{"id":7}' })[key] ?? null }
  axios.defaults.adapter = async config => {
    requests.push({ method: config.method, path: config.url, auth: config.headers.Authorization,
      body: typeof config.data === 'string' ? JSON.parse(config.data) : config.data })
    assert.ok(responses.length, `unexpected ${config.method} ${config.url}`)
    return responses.shift()()
  }
  server = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true },
    root: fileURLToPath(new URL('../../', import.meta.url)),
    resolve: { alias: { '@': fileURLToPath(new URL('../', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, watch: null }, logLevel: 'error' })
  ;({ agentAPI: api } = await server.ssrLoadModule('/src/services/agentApi.js'))
})
after(async () => { await server?.close(); axios.defaults.adapter = oldAdapter; globalThis.localStorage = oldStorage })
beforeEach(() => { requests = []; responses = []; setActivePinia(createPinia()) })
async function workspace() {
  ;({ useAgentWorkspaceStore } = await server.ssrLoadModule('/src/stores/agentWorkspace.js'))
  store = useAgentWorkspaceStore()
  return store
}
const reply = (...items) => responses.push(...items.map(item => () => ok(item)))

test('run history uses the authenticated session runs endpoint', async () => {
  reply([{ runId: 'run_history' }])
  const response = await api.listRuns('as/test')
  assert.equal(response.data.data[0].runId, 'run_history')
  assert.deepEqual(requests[0], { method: 'get', path: '/agent/sessions/as%2Ftest/runs', auth: 'Bearer test-token', body: undefined })
})

test('workspace operations use server records, multipart bytes, and only the confirmed patch hashes', async () => {
  await workspace()
  const sid = 'as/test', prefix = '/agent/sessions/as%2Ftest'
  reply({ attachments: [], tasks: [] }, base, preview, saved, [task], { ...task, status: 'done' }, { id: 'saved_graph', content: 'graph' }, { deleted: true })
  await store.load(sid)
  const file = new File(['before'], 'demo.txt', { type: 'text/plain' })
  assert.deepEqual(await store.upload(sid, file), base)
  const dryRun = await store.previewPatch(sid, 'art/patch', base.id)
  assert.deepEqual(dryRun, preview)
  assert.deepEqual(store.attachments, [base])
  assert.deepEqual(await store.applyPatch(sid, 'art/patch', dryRun), saved)
  assert.deepEqual(store.attachments, [base, saved])
  assert.deepEqual(await store.createTasks(sid, 'art_tasks'), [task])
  assert.equal((await store.updateTask(sid, task.id, 'done')).status, 'done')
  assert.equal(store.tasks[0].status, 'done')
  assert.equal((await store.saveArtifact(sid, 'art_graph')).id, 'saved_graph')
  await store.removeAttachment(sid, saved.id)
  assert.deepEqual(store.attachments.map(a => a.id), [base.id, 'saved_graph'])
  assert.deepEqual(requests.map(r => [r.method, r.path.slice(prefix.length)]), [
    ['get', '/attachments'], ['post', '/attachments'], ['post', '/artifacts/art%2Fpatch/patch-preview'],
    ['post', '/artifacts/art%2Fpatch/apply'], ['post', '/artifacts/art_tasks/tasks'], ['patch', '/tasks/task_001'],
    ['post', '/artifacts/art_graph/save'], ['delete', '/attachments/att_vers']
  ])
  assert.ok(requests.every(r => r.path.startsWith(prefix) && r.auth === 'Bearer test-token'))
  assert.equal(await requests[1].body.get('file').text(), 'before')
  assert.deepEqual(requests[2].body, { baseAttachmentId: 'att_base' })
  assert.deepEqual(requests[3].body, { baseAttachmentId: 'att_base', baseHash: 'base-hash', patchHash: 'patch-hash', confirmed: true })
  assert.deepEqual(requests[5].body, { status: 'done' })
})

test('HTTP failures and rejected envelopes preserve saved attachments and tasks', async () => {
  await workspace()
  reply({ attachments: [base], tasks: [task] })
  await store.load('as_test')
  responses.push(() => { throw new Error('not owner') }, () => ({ data: { code: 'DENIED', message: 'not saved', data: null } }))
  await assert.rejects(store.removeAttachment('as_test', base.id), /not owner/)
  await assert.rejects(store.updateTask('as_test', task.id, 'done'), /not saved/)
  assert.deepEqual(store.attachments, [base])
  assert.deepEqual(store.tasks, [task])
})

test('a late read and a late upload cannot replace the newly selected workspace', async () => {
  await workspace()
  const obsolete = Promise.withResolvers(), upload = Promise.withResolvers()
  reply({ attachments: [base], tasks: [] })
  await store.load('one')
  responses.push(() => obsolete.promise, () => upload.promise)
  const first = store.load('one'), writing = store.upload('one', new File(['b'], 'b.txt'))
  reply({ attachments: [{ id: 'other' }], tasks: [] })
  await store.load('two')
  obsolete.resolve(ok({ attachments: [base], tasks: [task] }))
  upload.resolve(ok({ id: 'stale-upload' }))
  await Promise.allSettled([first, writing])
  assert.deepEqual(store.attachments, [{ id: 'other' }])
  assert.deepEqual(store.tasks, [])
})

test('a fresh upload cannot suppress the initial attachment/task list or lose its own response', async () => {
  await workspace()
  const initial = Promise.withResolvers()
  responses.push(() => initial.promise); reply(saved)
  const loading = store.load('one'); await store.upload('one', new File(['new'], 'new.txt'))
  // The initial GET predates the write. An authoritative re-read includes both versions.
  reply({ attachments: [base, saved], tasks: [task] })
  initial.resolve(ok({ attachments: [base], tasks: [task] })); await loading
  assert.deepEqual(store.attachments, [base, saved]); assert.deepEqual(store.tasks, [task])
})

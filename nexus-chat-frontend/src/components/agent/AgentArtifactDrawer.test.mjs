import { after, afterEach, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { resolveObjectURL } from 'node:buffer'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { compileScript, parse } from 'vue/compiler-sfc'
import { createRenderer, defineComponent, h, nextTick, shallowReactive, watch } from 'vue'
import { createI18n } from 'vue-i18n'
import { createPinia, setActivePinia } from 'pinia'
import axios from 'axios'

const file = fileURLToPath(new URL('./AgentArtifactDrawer.vue', import.meta.url))
let server, Drawer, app, store, useWorkspace, appCleanup
const oldAdapter = axios.defaults.adapter, oldStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
before(async () => {
  assert.ok(existsSync(file), 'The artifact drawer component has not been implemented')
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => null } })
  axios.defaults.adapter = () => { throw new Error('Unexpected transport: tests must not access a server') }
  // Evaluate the SFC with Vite SSR, compiling a client render function so we can operate
  // the actual controls. No HTTP listener, optimized dependency cache, or browser profile.
  server = await createServer({
    root: fileURLToPath(new URL('../../../', import.meta.url)), configFile: false, appType: 'custom',
    resolve: { alias: { '@': fileURLToPath(new URL('../../', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, watch: null, ws: false },
    optimizeDeps: { noDiscovery: true, include: [] }, logLevel: 'silent',
    plugins: [{ name: 'drawer-test-host', transform(source, id) {
      if (id.endsWith('.vue')) return compileScript(parse(source, { filename: id }).descriptor, { id: 'drawer', inlineTemplate: true }).content
    } }]
  })
  Drawer = (await server.ssrLoadModule('/src/components/agent/AgentArtifactDrawer.vue')).default
  useWorkspace = (await server.ssrLoadModule('/src/stores/agentWorkspace.js')).useAgentWorkspaceStore
})
after(async () => {
  await server?.close(); axios.defaults.adapter = oldAdapter
  if (oldStorage) Object.defineProperty(globalThis, 'localStorage', oldStorage); else delete globalThis.localStorage
})

// A small in-memory Vue renderer: only Element Plus's modal shell and external I/O
// are substituted. Queries and interactions below go through the component template.
function node(type, text = '') {
  return { type, text, props: {}, children: [], focus() { document.activeElement = this }, isConnected: true }
}
function remove(el) {
  if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1)
  el.parent = null
}
function insert(el, parent, anchor) {
  remove(el)
  parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el)
  el.parent = parent
}
const renderer = createRenderer({
  createElement: type => node(type), createText: text => node('text', text), createComment: () => node('comment'),
  setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] },
  parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1],
  patchProp: (el, key, prev, value) => { el.props[key] = value }, insert, remove,
  insertStaticContent(html, parent, anchor) { const el = node('static', html); insert(el, parent, anchor); return [el, el] }
})
const ModalShell = defineComponent({
  inheritAttrs: false, props: { modelValue: Boolean },
  setup(props, { slots, emit }) {
    watch(() => props.modelValue, open => nextTick(() => emit(open ? 'openAutoFocus' : 'closed')), { immediate: true })
    return () => props.modelValue ? h('dialog', [slots.header?.({ titleId: 'title' }), slots.default?.(), slots.footer?.()]) : null
  }
})
const all = el => [el, ...el.children.flatMap(all)]
const text = el => [el.text, ...el.children.map(text)].join('')
const tick = async () => { await nextTick(); await new Promise(resolve => setImmediate(resolve)); await nextTick() }
const deferred = () => Promise.withResolvers()
const patch = { id: 'patch-one', kind: 'patch', name: 'sub/change.patch', agentId: 'code', nodeId: 'model-z', sourceIds: ['A7'],
  content: '--- a/base.txt\r\n+++ b/base.txt\r\n@@ -9 +9 @@\r\n-旧\r\n+<b>new</b>\r\n' }
const issues = { id: 'issue-list', kind: 'json', name: 'issues.json', content: '{"items":[{"title":"Check input"}]}' }
const topology = { id: 'graph-one', kind: 'graph', name: 'trace.json', content: '{"nodes":[{"id":"actual-node"}],"edges":[]}' }
const run = { runId: 'run-z', trace: [
  { kind: 'tool', nodeId: 'tool-y', toolName: 'lookup', status: 'done' },
  { kind: 'model', nodeId: 'model-z', parentIds: ['tool-y'], model: 'recorded-model', agentId: 'code', status: 'done' }
], sources: [{ id: 'A7', kind: 'attachment', title: 'base.txt' }] }
const base = { id: 'base-one', fileName: 'base.txt', content: '旧\r\n', sha256: 'a'.repeat(64) }
const proof = { baseAttachmentId: base.id, baseHash: base.sha256, patchHash: 'b'.repeat(64), before: base.content, after: '<b>new</b>\r\n', diff: patch.content }
const originals = new Map(['window', 'document'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
afterEach(() => {
  app?.unmount(); app = null; appCleanup?.(); appCleanup = null
  for (const [key, descriptor] of originals) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor)
    else delete globalThis[key]
  }
})
async function mount(options = {}, overrides = {}, locale = 'en', transport) {
  const root = node('root'), opener = node('button'), downloads = [], selected = [], applied = []
  globalThis.window = new EventTarget()
  globalThis.document = { activeElement: opener, body: { appendChild() {} }, createElement() {
    return { remove() {}, click() { downloads.push({ name: this.download, blob: resolveObjectURL(this.href) }) } }
  } }
  const pinia = createPinia(); setActivePinia(pinia); store = useWorkspace(pinia)
  if (transport) {
    const { agentAPI } = await server.ssrLoadModule('/src/services/agentApi.js')
    // Specify server replies at the authentic API boundary; leave Pinia's actions intact.
    const original = {}
    for (const [name, handler] of Object.entries(transport)) {
      original[name] = agentAPI[name]
      agentAPI[name] = async (...args) => ({ data: { code: 'OK', data: await handler(...args) } })
    }
    appCleanup = () => Object.assign(agentAPI, original)
  } else Object.assign(store, {
    attachments: [base, { id: 'base-two', fileName: 'second.txt' }], tasks: [],
    async load(sid) { this.sessionId = sid; return { attachments: this.attachments, tasks: this.tasks } },
    async previewPatch() { return proof },
    async applyPatch(...args) { applied.push(args); return { id: 'version-two', fileName: 'base.v2.txt', baseAttachmentId: base.id } },
    ...overrides
  })
  const props = shallowReactive({ visible: true, artifact: patch, artifacts: [patch, issues, topology], run, sessionId: 'session-one', ...options })
  const i18n = createI18n({ legacy: false, locale, messages: { en: {}, zh: {} } })
  app = renderer.createApp({ render: () => h(Drawer, { ...props,
    'onUpdate:visible': value => { props.visible = value }, onSelect: artifact => { selected.push(artifact); props.artifact = artifact }
  }) }); app.component('ElDrawer', ModalShell); app.use(pinia); app.use(i18n); app.mount(root)
  await tick()
  const find = (type, match = () => true) => all(root).find(el => el.type === type && match(el))
  const button = name => find('button', el => name.test(el.props['aria-label'] || text(el)))
  const click = async el => { assert.ok(el, 'control must exist'); assert.ok(!el.props.disabled, 'control must be enabled'); el.props.onClick?.({}); await tick() }
  const change = async (el, value) => { assert.ok(el, 'field must exist'); el.props.onChange({ target: { value, checked: value } }); await tick() }
  function key(key, modifiers = {}) {
    const e = Object.assign(new Event('keydown', { cancelable: true, bubbles: true }), { key, ...modifiers }); window.dispatchEvent(e); return e
  }
  return { props, selected, applied, downloads, opener, find, button, click, change, key, contents: () => text(root) }
}
async function chooseBase(ui) {
  await ui.click(ui.button(/apply patch/i))
  const field = ui.find('select', el => /base file/i.test(el.props['aria-label'] || ''))
  assert.ok(ui.button(/preview patch/i).props.disabled, 'an upload must be explicitly selected')
  await ui.change(field, base.id)
  return field
}

test('renders canonical diff, raw, recorded log and graph IDs through keyboard-operable tabs', async () => {
  const ui = await mount()
  const tab = name => ui.find('button', el => el.props.role === 'tab' && text(el).toLowerCase().includes(name))
  assert.equal(String(tab('diff').props['aria-selected']), 'true')
  assert.ok(ui.find('code', el => text(el) === '+<b>new</b>'))
  assert.ok(!ui.find('b'), 'artifact markup must remain text')
  assert.match(ui.contents(), new RegExp(`${Buffer.byteLength(patch.content)}\\s*(B|bytes)`))
  assert.match(ui.contents(), /code/)
  const right = { key: 'ArrowRight', preventDefault() {}, stopPropagation() {} }
  const tabs = ui.find('div', el => el.props.role === 'tablist')
  ;(tab('diff').props.onKeydown || tabs.props.onKeydown)(right); await tick()
  assert.equal(document.activeElement, tab('raw')); assert.equal(String(tab('raw').props['aria-selected']), 'true')
  await ui.click(tab('log')); assert.match(ui.contents(), /recorded-model/)
  await ui.click(tab('graph'))
  for (const id of ['run-z', 'patch-one', 'tool-y', 'model-z', 'A7']) assert.ok(ui.contents().includes(id), id)
  assert.match(ui.contents(), /tool-y\s*→\s*model-z/)
})

test('visible selector and drawer-only shortcuts select real artifact objects; Escape restores focus', async () => {
  const ui = await mount()
  assert.notEqual(document.activeElement, ui.opener)
  let escaped = 0
  window.addEventListener('keydown', () => { escaped++ })
  assert.ok(ui.key('2', { metaKey: true }).defaultPrevented); await tick()
  assert.equal(ui.selected.at(-1), issues); assert.equal(escaped, 0)
  const picker = ui.find('select', el => /artifact/i.test(el.props['aria-label'] || ''))
  await ui.change(picker, topology.id); assert.equal(ui.selected.at(-1), topology)
  assert.ok(ui.key('1', { ctrlKey: true }).defaultPrevented); await tick(); assert.equal(ui.selected.at(-1), patch)
  ui.key('Escape'); await tick(); assert.equal(ui.props.visible, false); assert.equal(document.activeElement, ui.opener)
  assert.ok(!ui.key('3', { ctrlKey: true }).defaultPrevented); assert.equal(ui.selected.at(-1), patch); assert.equal(escaped, 1)
})

test('waits for clipboard permission, exposes rejection and downloads original UTF-8 bytes even on Graph', async t => {
  const ui = await mount(), writes = [], pending = deferred()
  const clipboard = { writeText(value) { writes.push(value); return pending.promise } }
  const previous = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: clipboard })
  t.after(() => { if (previous) Object.defineProperty(navigator, 'clipboard', previous); else delete navigator.clipboard })
  await ui.click(ui.button(/copy/i)); assert.deepEqual(writes, [patch.content]); assert.doesNotMatch(ui.contents(), /copied/i)
  pending.reject(new Error('clipboard denied')); await tick(); assert.match(ui.contents(), /clipboard denied/i)
  clipboard.writeText = async value => { writes.push(value) }
  await ui.click(ui.button(/graph/i)); await ui.click(ui.button(/copy/i))
  assert.equal(JSON.parse(writes.at(-1)).artifactId, patch.id); assert.match(ui.contents(), /copied/i)
  await ui.click(ui.button(/download artifact/i))
  assert.equal(ui.downloads[0].name, 'change.patch'); assert.equal(await ui.downloads[0].blob.text(), patch.content)
})

test('only explicit confirmation submits the reviewed hashes and reports a distinct saved file', async () => {
  let request
  const ui = await mount({}, { async previewPatch(...args) { request = args; return proof } })
  await chooseBase(ui); await ui.click(ui.button(/preview patch/i))
  assert.deepEqual(request, ['session-one', patch.id, base.id]); assert.equal(ui.applied.length, 0)
  assert.match(ui.contents(), /source stays unchanged/i)
  for (const value of [proof.baseHash, proof.patchHash, proof.before.trim(), proof.after.trim()]) assert.ok(ui.contents().includes(value))
  await ui.click(ui.button(/cancel/i)); assert.equal(ui.applied.length, 0); assert.ok(!ui.button(/confirm/i))
  await chooseBase(ui); await ui.click(ui.button(/preview patch/i)); await ui.click(ui.button(/confirm/i))
  assert.deepEqual(ui.applied, [['session-one', patch.id, proof]]); assert.match(ui.contents(), /base\.v2\.txt/)
  assert.equal(store.attachments[0].content, base.content); assert.ok(!ui.button(/confirm/i))
})

for (const transition of ['base', 'artifact', 'session', 'close and reopen', 'cancel']) {
  test(`late preview cannot re-arm confirmation after ${transition} changes`, async () => {
    const reply = deferred(), ui = await mount({}, { previewPatch: () => reply.promise })
    const field = await chooseBase(ui); await ui.click(ui.button(/preview patch/i))
    if (transition === 'base') { await ui.change(field, 'base-two'); await ui.change(field, base.id) }
    if (transition === 'artifact') { ui.key('2', { ctrlKey: true }); await tick(); ui.key('1', { ctrlKey: true }); await tick() }
    if (transition === 'session') { ui.props.sessionId = 'session-two'; await tick(); ui.props.sessionId = 'session-one'; await tick() }
    if (transition === 'close and reopen') { ui.key('Escape'); await tick(); ui.props.visible = true; await tick() }
    if (transition === 'cancel') await ui.click(ui.button(/cancel/i))
    reply.resolve(proof); await tick()
    assert.ok(!ui.button(/confirm/i)); assert.ok(!ui.contents().includes(proof.patchHash)); assert.equal(ui.applied.length, 0)
  })
}

test('rejects mismatched preview identity and discards a reviewed base removed from the workspace', async () => {
  let result = { ...proof, baseAttachmentId: 'base-two' }
  const ui = await mount({}, { async previewPatch() { return result } })
  await chooseBase(ui); await ui.click(ui.button(/preview patch/i)); assert.ok(!ui.button(/confirm/i))
  assert.ok(ui.find('p', el => el.props.role === 'alert'))
  result = proof; await ui.click(ui.button(/preview patch/i)); assert.ok(ui.button(/confirm/i))
  store.attachments = []; await tick(); assert.ok(!ui.button(/confirm/i)); assert.match(ui.contents(), /no uploaded/i)
  assert.equal(ui.applied.length, 0)
})

test('checklist and JSON export reflect persisted rows, not the source JSON or an optimistic checkbox', async () => {
  const row = { id: 'task-real', title: 'Persisted title', sourceArtifactId: issues.id, status: 'pending', createdAt: 71 }
  let fail = true, createArgs, updateArgs, persisted = []
  const ui = await mount({ artifact: issues }, {}, 'en', {
    listAttachments() { return { attachments: [], tasks: structuredClone(persisted) } },
    createTasks(...args) { createArgs = args; persisted = [row]; return { tasks: structuredClone(persisted) } },
    updateTask(...args) { updateArgs = args; if (fail) throw new Error('write rejected'); persisted = [{ ...row, status: 'done' }]; return { ...persisted[0] } }
  })
  await ui.click(ui.button(/create.*tasks/i)); assert.deepEqual(createArgs, ['session-one', issues.id]); assert.match(ui.contents(), /Persisted title/)
  assert.ok(!ui.find('p', el => el.props.role === 'alert'), 'server task envelope must not be reported as a failed write')
  const checkbox = () => ui.find('input', el => el.props.type === 'checkbox')
  await ui.change(checkbox(), true); assert.match(ui.contents(), /write rejected/); assert.ok(!checkbox().props.checked)
  fail = false; await ui.change(checkbox(), true); assert.deepEqual(updateArgs, ['session-one', row.id, 'done']); assert.equal(checkbox().props.checked, true)
  ui.props.visible = false; await tick(); ui.props.visible = true; await tick(); assert.equal(checkbox().props.checked, true)
  await ui.click(ui.button(/export.*tasks/i)); assert.deepEqual(JSON.parse(await ui.downloads[0].blob.text()), [{ ...row, status: 'done' }])
})

test('non-task JSON does not offer task creation; saving a topology uses its artifact ID and actual result', async () => {
  let saved
  const ui = await mount({ artifact: { ...issues, content: '{"items":[{"value":3}]}' } }, {
    async saveArtifact(...args) { saved = args; return { id: 'attachment-new', fileName: 'stored-trace.json' } }
  })
  assert.ok(!ui.button(/create.*tasks/i)); ui.props.artifact = topology; await tick()
  await ui.click(ui.button(/save.*session/i)); assert.deepEqual(saved, ['session-one', topology.id]); assert.match(ui.contents(), /stored-trace\.json/)
})

test('an unavailable artifact payload opens a fallback view without claiming a byte count or offering file writes', async () => {
  const ui = await mount({ artifact: { id: 'metadata-only', name: 'partial.json', kind: 'json', content: {} } })
  assert.match(ui.contents(), /no content recorded/i); assert.doesNotMatch(ui.contents(), /\d+ bytes/)
  assert.ok(ui.button(/copy/i).props.disabled); assert.ok(ui.button(/download artifact/i).props.disabled); assert.ok(ui.button(/save.*session/i).props.disabled)
  ui.props.artifact = { ...ui.props.artifact, content: '' }; await tick()
  assert.match(ui.contents(), /0 bytes/); assert.ok(!ui.button(/download artifact/i).props.disabled)
})

test('a failed or incomplete file save never announces success or reuses the consumed preview', async () => {
  const ui = await mount({}, { async applyPatch() { throw new Error('base hash changed') }, async saveArtifact() { return {} } })
  await chooseBase(ui); await ui.click(ui.button(/preview patch/i)); await ui.click(ui.button(/confirm/i))
  assert.match(ui.contents(), /base hash changed/); assert.ok(!ui.button(/confirm/i)); assert.ok(ui.button(/preview patch/i))
  ui.props.artifact = topology; await tick(); await ui.click(ui.button(/save.*session/i))
  assert.ok(ui.find('p', el => el.props.role === 'alert')); assert.doesNotMatch(ui.contents(), /saved session attachment/i)
})

test('empty artifact, missing attachments and a Chinese locale never produce sample content', async () => {
  const ui = await mount({ artifact: null, artifacts: [], run: null }, { attachments: null, tasks: {} }, 'zh')
  assert.match(ui.contents(), /暂无产物|未选择产物/); assert.ok(!ui.find('button', el => el.props.role === 'tab'))
  ui.props.artifact = patch; await tick(); await ui.click(ui.button(/应用补丁/)); assert.match(ui.contents(), /没有.*附件|暂无.*附件/)
  assert.ok(!ui.button(/确认/)); assert.ok(ui.button(/预览补丁/).props.disabled)
})

import { after, before, mock, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { build } from 'esbuild'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick, reactive, ref } from 'vue'
import { createI18n } from 'vue-i18n'
import en from '../../locales/en.json' with { type: 'json' }
import zh from '../../locales/zh.json' with { type: 'json' }
import { normalizeScope } from './scopeSelection.mjs'

// Mount real compiled Vue components using a small in-memory renderer. This needs
// neither a DOM package nor a live backend. Invoke the rendered controls and keep
// Vue's real event, lifecycle, child-prop, watcher and scheduling behavior.
const here = dirname(fileURLToPath(import.meta.url)), require = createRequire(import.meta.url)
const owned = ['AgentChatView', 'AgentComposer', 'AgentScopePanel', 'AgentWorkbenchToolbar']
let View, stores
before(async () => {
  const bundle = await build({
    entryPoints: [resolve(here, 'AgentChatView.vue')], bundle: true, write: false,
    format: 'cjs', platform: 'node', packages: 'external', logLevel: 'silent',
    plugins: [{ name: 'workbench-sfc', setup(b) {
      b.onResolve({ filter: /\.vue$/ }, args => {
        const name = args.path.split('/').at(-1).slice(0, -4)
        return owned.includes(name) ? { path: resolve(here, `${name}.vue`), namespace: 'sfc' } : { path: name, namespace: 'widget' }
      })
      b.onLoad({ filter: /.*/, namespace: 'sfc' }, async ({ path }) => {
        const { descriptor } = parse(await readFile(path, 'utf8'), { filename: path })
        return { contents: compileScript(descriptor, { id: 'view-contract', inlineTemplate: true }).content, resolveDir: here }
      })
      b.onResolve({ filter: /(?:@\/stores\/|abyssStage|^element-plus$)/ }, ({ path }) => ({ path, namespace: 'boundary' }))
      b.onLoad({ filter: /.*/, namespace: 'boundary' }, ({ path }) => {
        if (path.endsWith('abyssStage')) return { contents: 'export function mountAbyssStage() {}' }
        // ElButton is the composer's MCP entry: a plain button is all the view contract needs of it.
        if (path === 'element-plus') return { contents: 'import { h } from "vue"; export const ElMessage = {success(){},error(){}}, ElMessageBox = {confirm: () => globalThis.workbenchTestStores.confirm()}, ElButton = (props, { slots, attrs }) => h("button", { type: "button", ...attrs }, slots.default?.())', resolveDir: here }
        const name = path.split('/').at(-1)
        // The provider store also exports the reading of a backend model error; each test supplies it.
        const extra = name === 'agentProviders' ? ';export const describeModelError = error => globalThis.workbenchTestStores.describeModelError(error)' : ''
        return { contents: `export function use${name[0].toUpperCase() + name.slice(1)}Store(){return globalThis.workbenchTestStores.${name}}${extra}` }
      })
      b.onLoad({ filter: /.*/, namespace: 'widget' }, ({ path }) => ({ contents: `export default {name:'${path}', inheritAttrs:false, render:()=>null}` }))
    } }]
  })
  const mod = { exports: {} }
  Function('module', 'exports', 'require', bundle.outputFiles[0].text)(mod, mod.exports, require)
  View = mod.exports.default
})
const windows = { window: globalThis.window, document: globalThis.document, getComputedStyle: globalThis.getComputedStyle, ResizeObserver: globalThis.ResizeObserver }
const apps = []
class Host extends EventTarget {
  constructor(tag = '') { super(); Object.assign(this, { tagName: tag.toUpperCase(), style: {}, children: [], scrollHeight: 100, clientHeight: 100, scrollTop: 0 }) }
  focus() { document.activeElement = this }
  // The layout the scope panel measures: its button, and the column that clips the panel.
  static button = { left: 200, top: 600 }
  static column = { left: 0, top: 0, right: 1280, width: 1280 }
  get parentElement() { return this.parent }
  getBoundingClientRect() { return this.class === 'scope-anchor' ? Host.button : Host.column }
  contains(node) { return this === node || this.children.some(c => c.contains(node)) }
  scrollTo({ top }) { this.scrollTop = top }
}
// The browser's size observer, reduced to what a test needs: which boxes are watched,
// and a way to report that one of them changed.
class Sizes {
  static all = []
  boxes = []
  constructor(changed) { this.changed = changed; Sizes.all.push(this) }
  observe(box) { this.boxes.push(box) }
  disconnect() { this.boxes = [] }
}
before(() => {
  globalThis.ResizeObserver = Sizes
  globalThis.window = Object.assign(new Host(), { performance: globalThis.performance, innerWidth: 1280, matchMedia: () => ({ matches: false }) })
  globalThis.getComputedStyle = el => ({ overflowY: el.class === 'scope-context-tools' ? 'visible' : 'hidden' }); globalThis.document = new Host(); document.body = new Host('body'); document.documentElement = new Host('html'); document.documentElement.children.push(document.body)
})
after(() => { apps.forEach(app => app.unmount()); Object.assign(globalThis, windows); delete globalThis.workbenchTestStores })
const remove = el => { if (el.parent) el.parent.children = el.parent.children.filter(c => c !== el) }
const renderer = createRenderer({
  querySelector: selector => selector === 'body' ? document.body : null,
  createElement: tag => new Host(tag), createComment: () => new Host(), createText: text => Object.assign(new Host(), { text }),
  setText: (n, text) => { n.text = text }, setElementText: (n, text) => { n.text = text }, patchProp: (n, k, _, v) => { if (!k.startsWith('on')) n[k] = v },
  insert(el, parent, anchor) { remove(el); const pos = parent.children.indexOf(anchor); parent.children.splice(pos === -1 ? parent.children.length : pos, 0, el); el.parent = parent },
  remove, parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1],
  insertStaticContent(text, parent, anchor) { const el = Object.assign(new Host(), { text }); parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el); el.parent = parent; return [el, el] }
})

const undescribed = error => ({ key: null, fallback: error.message || error.code || '', detail: error.code || '', settings: false })
async function mount(messages = [], sessionId = 'session-one', describeModelError = undescribed, locale = 'en') {
  apps.splice(0).forEach(app => app.unmount())
  const calls = [], widgets = {}, selected = { id: 5, purpose: 'chat', displayName: 'Personal', defaultModel: 'next-model', hasApiKey: true }
  stores = globalThis.workbenchTestStores = {
    describeModelError,
    agent: reactive({ sessionId, activeSession: { title: 'Review notes' }, activeProviderId: 5,
      messages, get hasMessages() { return this.messages.length > 0 }, streaming: false, switching: false,
      async ensureSession() { calls.push(['ensure']); return this.sessionId ||= 'created' },
      async sendMessage(...args) { calls.push(['send', ...args]) }, cancelStream() { calls.push(['stop']) },
      async refreshActiveSession() { calls.push(['refresh']) }, async resumeMessage(id) { calls.push(['resume', id]) },
      async retryMessage(id) { calls.push(['retry', id]) }, setActiveProviderId(id) { this.activeProviderId = id },
      async hydrateActiveSession() {}, async clearConversation() { calls.push(['clear']) }
    }),
    async confirm() {},
    agentWorkspace: reactive({ attachments: [], tasks: [], async load(sid) { calls.push(['load', sid]) },
      scopeSelection: { targets: [], timeRange: { fromInclusive: null, toExclusive: null, timeZone: 'UTC' } },
      scopeConfirmed: false, bindingVersion: 1, sessionGeneration: 1,
      scopeResources: { contacts: [], groups: [] }, scopeError: '', scopeLoading: false,
      async loadScope(sid) { calls.push(['loadScope', sid]) },
      // The store's own rules, with its real validation: an edit ends the confirmation, a save spends it.
      setScope(value) { calls.push(['setScope', value]); this.scopeSelection = normalizeScope(value); this.scopeConfirmed = false },
      confirmScope() { calls.push(['confirm']); this.scopeSelection = normalizeScope(this.scopeSelection); this.scopeConfirmed = true },
      takeScope() { return { scopeSelection: this.scopeSelection, scopeConfirmed: true, expectedBindingVersion: 1 } },
      invalidateScope() {}, async saveScope(sid) { calls.push(['saveScope', sid]); this.scopeConfirmed = false },
      async upload(sid, file) { calls.push(['upload', sid, file.name]); const a = { id: 'att-1', fileName: file.name }; this.attachments.push(a); return a },
      async removeAttachment(sid, id) { calls.push(['delete', sid, id]) }, async updateTask(sid, id, status) { calls.push(['task', sid, id, status]) },
      // Switching the workspace session is not a data load; only load() records one.
      select(sid) { this.sessionId = sid }
    }),
    agentProviders: reactive({ providers: [selected, { id: 9, purpose: 'embedding', defaultModel: 'vector-only' }],
      chatProviders: [selected], defaultProvider: selected, async fetchAll() {} }),
    knowledge: reactive({ activeKbId: 'kb-42' }), user: reactive({ currentUser: { id: 3, nickname: 'Quinn' }, token: 'token-one' }),
    // The workspace can open a group conversation it already lists; only the
    // groups the chat store holds are openable, so the stub starts empty.
    chat: reactive({ groupChats: [], clearActiveChat() { calls.push(['back']) }, setActiveChat(chat) { calls.push(['open', chat.id]) },
      async fetchChats() { calls.push(['chats']); this.groupChats = [{ id: 77, type: 'GROUP', name: 'Design review' }] } })
  }
  const root = h(View), app = renderer.createApp({ render: () => root })
  app.provide('isMobile', ref(false)).provide('activeView', ref('ai')).provide('toggleList', () => calls.push(['list']))
  app.provide('setActiveView', view => calls.push(['view', view]))
  const i18n = createI18n({ legacy: false, locale, messages: { en, zh }, missingWarn: false, fallbackWarn: false })
  app.use(i18n)
  for (const name of ['ElDropdown', 'ElDropdownMenu', 'ElDropdownItem', 'ElDialog']) {
    app.component(name, { name, inheritAttrs: false, setup: (_, { slots, attrs }) => () => name !== 'ElDialog' || attrs.modelValue ? h('div', [slots.default?.(), slots.dropdown?.(), slots.footer?.()]) : null })
  }
  app.mount(new Host('main')); apps.push(app); await paint()
  function walk(node, result = []) {
    if (!node || typeof node !== 'object') return result
    result.push(node)
    if (node.component) {
      widgets[node.type.name] = node
      walk(node.component.subTree, result)
    } else if (Array.isArray(node.children)) node.children.flat(Infinity).forEach(n => walk(n, result))
    return result
  }
  async function paint() { await nextTick(); await new Promise(resolve => setImmediate(resolve)); await nextTick() }
  function nodes(type) { return walk(root).filter(n => n.type === type) }
  function text(n) { return typeof n?.children === 'string' ? n.children : (Array.isArray(n?.children) ? n.children.map(text).join(' ') : '') }
  function button(label) {
    const button = nodes('button').find(n => [n.props?.['aria-label'], n.props?.title, text(n).trim()].some(s => s && label.test(s)))
    assert.ok(button, `expected a button for ${label}`); return button
  }
  async function click(label) { const b = button(label); assert.ok(!b.props?.disabled); await b.props.onClick({ currentTarget: { focus() {} }, stopPropagation() {}, preventDefault() {} }); await paint() }
  async function type(value) {
    const n = nodes('textarea')[0]
    if (n.props?.onInput) n.props.onInput({ target: { value, style: {} } })
    else n.props['onUpdate:modelValue'](value)
    await paint()
  }
  async function enter(extra = {}) {
    let prevented = false
    nodes('textarea')[0].props.onKeydown({ key: 'Enter', preventDefault() { prevented = true }, ...extra }); await paint(); return prevented
  }
  // The header has one dropdown menu, "more". The model picker is its own component.
  const menu = which => { assert.equal(which, 'more'); return walk(root).filter(n => n.type?.name === 'ElDropdown')[0] }
  return { i18n, calls, paint, nodes, button, click, type, enter, widgets, menu, textOf: text, text: () => walk(root).filter(n => typeof n.children === 'string').map(n => n.children).join(' ') }
}

test('IME candidate confirmation (including keyCode 229) and Shift+Enter do not submit', async () => {
  const v = await mount(); await v.type('草稿')
  for (const modifier of [{ isComposing: true }, { keyCode: 229 }, { shiftKey: true }]) assert.equal(await v.enter(modifier), false)
  assert.deepEqual(v.calls.filter(c => c[0] === 'send'), [])
  assert.equal(await v.enter(), true)
  assert.equal(v.calls.find(c => c[0] === 'send')[1], '草稿')
})

test('ordinary sends carry the KB and explicit off-by-default workbench options', async () => {
  const v = await mount(); await v.type('  请总结  '); await v.enter()
  assert.deepEqual(v.calls.find(c => c[0] === 'send'), ['send', '请总结', { attachmentIds: [], linkedKbId: 'kb-42',
    webSearchEnabled: false, collaborationEnabled: false, maxIterations: 6, maxOutputTokens: 1024, temperature: 0.2 }])
})

test('tuning and opt-ins change the next request; Unicode input is limited without cutting a character', async () => {
  const v = await mount(); await v.click(/web search/i); await v.click(/collaboration/i); await v.click(/tune request/i)
  for (const [name, val] of [['temperature', '0.7'], ['maxOutputTokens', '512'], ['maxIterations', '3']]) {
    const n = v.nodes('input').find(n => n.props?.name === name)
    assert.ok(n, name); n.props['onUpdate:modelValue'](Number(val))
  }
  const form = v.nodes('form').find(n => n.props?.onSubmit); assert.ok(form)
  await form.props.onSubmit({ preventDefault() {} }); await v.paint()
  const text = '你'.repeat(3999) + String.fromCodePoint(0x20000)
  await v.type(text + 'x'); await v.enter()
  assert.deepEqual(v.calls.find(c => c[0] === 'send'), ['send', text, { attachmentIds: [], linkedKbId: 'kb-42',
    webSearchEnabled: true, collaborationEnabled: true, maxIterations: 3, maxOutputTokens: 512, temperature: 0.7 }])
})

test('an upload on the first turn obtains a session and sends only the returned attachment ID', async () => {
  const v = await mount([], null); await v.type('Use my notes')
  const picker = v.nodes('input').find(n => n.props?.type === 'file'); assert.ok(picker, 'context file picker')
  await picker.props.onChange({ target: { value: 'notes', files: [new File(['abc'], 'notes.txt')] } }); await v.paint()
  await v.enter()
  assert.ok(v.calls.findIndex(c => c[0] === 'ensure') < v.calls.findIndex(c => c[0] === 'upload'))
  assert.deepEqual(v.calls.find(c => c[0] === 'upload'), ['upload', 'created', 'notes.txt'])
  assert.deepEqual(v.calls.find(c => c[0] === 'send')[2].attachmentIds, ['att-1'])
})

const message = { role: 'assistant', id: 'msg-two', runId: 'run-two', status: 'interrupted', resumable: true, content: '**partial**',
  trace: [{ kind: 'model', model: 'actual-model' }], elapsedMs: 91, usage: { totalTokens: 18 }, error: { message: 'Model timed out', code: 'TIMEOUT' },
  sources: [{ id: 'W1', title: 'Report', excerpt: 'What was actually retrieved', kind: 'web' }], artifacts: [{ id: 'latest', name: 'two.md' }] }

test('reload, retry and checkpoint resume remain distinct; stop cancels the running stream', async () => {
  const v = await mount([{ role: 'user', id: 'q', content: 'Question' }, message]); await v.click(/reload conversation/i)
  await v.click(/retry answer/i); await v.click(/continue this task/i)
  assert.deepEqual(v.calls.filter(c => ['refresh','retry','resume'].includes(c[0])), [['refresh'], ['retry','msg-two'], ['resume','msg-two']])
  stores.agent.streaming = true; await v.paint(); await v.click(/^stop$/i); assert.ok(v.calls.some(c => c[0] === 'stop'))
})

test('board selection binds the drawer to the originating run, not the newest run', async () => {
  const v = await mount([{ ...message, id: 'msg-one', runId: 'run-one', artifacts: [{ id: 'old', name: 'one.md' }] }, message]); v.nodes('section')
  const board = v.widgets.AgentExecutionBoard
  assert.equal(board?.props?.run?.id, 'msg-two')
  // It is docked above the conversation: not inside the feed, which is the only part that scrolls.
  const inside = (node, found = []) => { if (node && typeof node === 'object') { found.push(node); if (node.component) inside(node.component.subTree, found); else if (Array.isArray(node.children)) node.children.flat(Infinity).forEach(child => inside(child, found)) } return found }
  const feed = v.nodes('div').find(n => n.props?.class === 'feed'), dock = v.nodes('div').find(n => n.props?.class === 'board-dock')
  assert.ok(feed && dock)
  assert.ok(inside(dock).includes(board) && !inside(feed).includes(board))
  const shell = v.nodes('section').find(n => /agent-chat-view/.test(n.props?.class || '')).children.flat(Infinity)
  assert.ok(shell.indexOf(dock) > -1 && shell.indexOf(dock) < shell.indexOf(feed))
  board.props.onPreview(stores.agent.messages[0].artifacts[0]); await v.paint(); v.nodes('section')
  assert.equal(v.widgets.AgentArtifactDrawer.props.run.runId, 'run-one')
  assert.equal(v.widgets.AgentArtifactDrawer.props.artifact.name, 'one.md')
})

test('answer citations open the recorded excerpt; a run without sources never borrows a citation', async () => {
  const v = await mount([message]); v.nodes('section')
  const markdown = v.widgets.AgentMarkdown
  assert.equal(markdown?.props?.content, '**partial**')
  markdown.props.onSource(stores.agent.messages[0].sources[0]); await v.paint()
  assert.match(v.text(), /What was actually retrieved/)
  const empty = await mount([{ ...message, sources: [] }]); await empty.click(/sources/i)
  assert.match(empty.text(), /no sources/i); assert.doesNotMatch(empty.text(), /actually retrieved/)
})

test('a model error is shown as described; a backend wait holds Continue and only a settings error offers the settings', async () => {
  const limited = { ...message, error: { code: 'AGENT_MODEL_50202', reason: 'RATE_LIMIT', message: 'quota' }, retryAt: Date.now() + 20_000 }
  const v = await mount([limited], 'session-one', error => ({ key: 'modelError.RATE_LIMIT', fallback: error.message, detail: 'AGENT_MODEL_50202 · RATE_LIMIT', settings: false }))
  assert.match(v.text(), /rate limited/i); assert.match(v.text(), /AGENT_MODEL_50202 · RATE_LIMIT/)
  assert.equal(v.button(/retry in \d+s/i).props.disabled, true)
  assert.throws(() => v.button(/open model settings/i))
  assert.deepEqual(v.calls.filter(c => c[0] === 'resume'), [], 'the wait never resumes by itself')

  const unusable = { ...message, error: { code: 'AGENT_PARAM_40001', reason: 'CONFIG', message: 'no model' }, retryAt: null }
  const c = await mount([unusable], 'session-one', error => ({ key: 'modelError.CONFIG', fallback: error.message, detail: 'AGENT_PARAM_40001 · CONFIG', settings: true }))
  assert.equal(c.button(/continue this task/i).props.disabled, false)
  await c.click(/open model settings/i); c.nodes('section')
  assert.equal(c.widgets.AgentProviderSettings.props.visible, true)

  const unknown = await mount([{ ...message, error: { code: 'SOMETHING_NEW', message: 'as the backend said' } }])
  assert.match(unknown.text(), /as the backend said/)
})

test('the model switcher lists every saved chat channel; choosing one selects it for the next turn', async () => {
  const v = await mount([message])
  const other = { id: 6, purpose: 'chat', displayName: 'Relay', defaultModel: 'relay-model', hasApiKey: true }
  stores.agentProviders.chatProviders = [stores.agentProviders.chatProviders[0], other]; await v.paint(); v.nodes('section')
  const switcher = () => v.widgets.ModelChannelSwitcher.props
  assert.deepEqual(switcher().channels.map(c => c.id), [5, 6], 'chat channels only, never the embedding one')
  assert.equal(switcher()['active-id'] ?? switcher().activeId, 5)
  assert.equal(switcher().label, 'Personal · next-model')
  assert.equal(switcher().disabled, false)

  switcher().onSelect(6); await v.paint(); v.nodes('section')
  assert.equal(stores.agent.activeProviderId, 6)
  assert.equal(switcher().label, 'Relay · relay-model')
  switcher().onSelect(9); await v.paint()
  assert.equal(stores.agent.activeProviderId, 6, 'an embedding channel is never selected for chat')

  assert.ok(!v.widgets.AgentProviderSettings.props.visible)
  switcher().onManage(); await v.paint(); v.nodes('section')
  assert.equal(v.widgets.AgentProviderSettings.props.visible, true)

  stores.agent.streaming = true; await v.paint(); v.nodes('section')
  assert.equal(switcher().disabled, true, 'the channel cannot change under a running turn')
})

test('a clear confirmed after switching sessions clears nothing; the workspace follows every session change', async () => {
  const v = await mount([message]), answered = Promise.withResolvers()
  stores.confirm = () => answered.promise
  const pending = v.menu('more').props.onCommand('clear'); await v.paint()
  v.calls.length = 0
  stores.agent.sessionId = 'session-two'; await v.paint()
  answered.resolve(); await pending; await v.paint()
  assert.deepEqual(v.calls.filter(c => c[0] === 'clear'), [], 'the session that was switched to is never cleared')
  assert.deepEqual(v.calls.filter(c => c[0] === 'load'), [['load', 'session-two']])
  stores.agent.sessionId = null; await v.paint()
  assert.deepEqual(v.calls.filter(c => c[0] === 'load').at(-1), ['load', null])

  const same = await mount([message])
  await same.menu('more').props.onCommand('clear'); await same.paint()
  assert.deepEqual(same.calls.filter(c => c[0] === 'clear'), [['clear']])
})


test('trace receives its own actual run metadata and artifact preview uses the existing drawer', async () => {
  const current = { ...message, input: 'Synthetic task request', scope: { chatIds: ['synthetic-chat'] } }
  const v = await mount([current]); v.nodes('section')
  const trace = v.widgets.AgentTrace
  assert.equal(trace.props.run.runId, current.runId)
  assert.equal(trace.props.run.input, current.input)
  assert.deepEqual(trace.props.run.scope.chatIds, current.scope.chatIds)
  trace.props.onPreview(current.artifacts[0]); await v.paint(); v.nodes('section')
  assert.equal(v.widgets.AgentArtifactDrawer.props.artifact.id, current.artifacts[0].id)
})

test('workbench header uses the real trace verdict including partial rather than an old summary column', async () => {
  const current = { ...message, status: 'done', error: null, trace: [{ kind: 'done', runId: 'run-two', attemptId: 'actual', finishReason: 'partial', sequence: 1 }] }
  const v = await mount([current])
  assert.match(v.text(), /Partially completed/)
})

test('local stop cannot advertise confirmed cancellation in the header or answer diagnostic', async () => {
  const current = { ...message, status: 'cancelled', finishReason: 'cancelled', error: null, trace: [{ kind: 'done', local: true, runId: 'run-two', finishReason: 'cancelled' }] }
  const v = await mount([current])
  assert.match(v.text(), /Stop unconfirmed/)
  assert.doesNotMatch(v.text(), /Cancelled/)
})

// ── Chat reading scope: one toolbar button and the panel it opens ──
// Everything below drives the compiled panel through the controls a user has.
function scope(v) {
  const panel = () => v.nodes('section').find(n => n.props?.id === 'agent-scope-panel')
  const all = (type, node = panel()) => !node || typeof node !== 'object' ? []
    : [...(node.type === type ? [node] : []), ...(Array.isArray(node.children) ? node.children.flat(Infinity).flatMap(child => all(type, child)) : [])]
  const labelled = label => all('button').find(n => [n.props?.['aria-label'], n.props?.title, v.textOf(n).trim()].some(s => s && label.test(s)))
  const entry = (node, value, more = {}) => ({ target: { value, validity: { badInput: false }, ...more } })
  return {
    panel, all, labelled, text: () => v.textOf(panel()),
    alerts: () => all('p').filter(n => n.props?.role === 'alert').map(n => v.textOf(n).trim()),
    trigger: () => v.nodes('button').find(n => n.props?.['aria-controls'] === 'agent-scope-panel'),
    anchor: () => v.nodes('div').find(n => n.props?.class === 'scope-anchor'),
    open: () => v.click(/^chat reading scope ·/i),
    async press(label) { const b = labelled(label); assert.ok(b, `expected a panel button for ${label}`); assert.ok(!b.props.disabled, `${label} is enabled`); await b.props.onClick({}); await v.paint() },
    boxes: () => all('input').filter(n => n.props.type === 'checkbox'),
    walls: () => all('input').filter(n => n.props.type === 'datetime-local'),
    zone: () => all('input').find(n => n.props.list === 'agent-scope-zones'),
    chip: label => all('button').find(n => /scope-chip/.test(n.props.class) && label.test(v.textOf(n))),
    async search(value) { all('input').find(n => n.props.type === 'search').props.onInput(entry(null, value)); await v.paint() },
    async wall(side, value, more) { const n = all('input').filter(n => n.props.type === 'datetime-local')[side === 'from' ? 0 : 1]; n.props.onInput(entry(n, value, more)); await v.paint() },
    async zoneText(value, settled = true) { const n = all('input').find(n => n.props.list === 'agent-scope-zones'); n.props.onInput(entry(n, value)); if (settled) n.props.onChange(entry(n, value)); await v.paint() }
  }
}
const people = { contacts: [{ type: 'contact', id: 5, chatId: 9, label: 'Ada' }, { type: 'contact', id: 6, chatId: null, label: 'Ada' }], groups: [{ type: 'group', id: 77, chatId: 77, label: 'Design review' }] }
const chosen = (targets, timeRange = { fromInclusive: null, toExclusive: null, timeZone: 'UTC' }) => ({ targets, timeRange })
const scopeCalls = v => v.calls.filter(c => ['setScope', 'confirm', 'saveScope', 'loadScope'].includes(c[0])).map(c => c[0])

test('the scope button sits immediately left of the knowledge base selector and reports the selection, never a grant', async () => {
  const v = await mount(), s = scope(v)
  const tools = v.nodes('div').find(n => n.props?.class === 'tools').children.flat(Infinity).filter(n => n && typeof n === 'object')
  const context = tools.find(n => n.props?.class === 'scope-context-tools')
  assert.ok(context, 'the pair shares a non-wrapping toolbar group on narrow screens')
  const names = context.children.flat(Infinity).filter(n => n && typeof n === 'object').map(n => n.type?.__name || n.type?.name || n.type)
  assert.deepEqual(names, ['AgentScopePanel', 'KnowledgeBaseSelector'], 'the scope remains immediately left of the knowledge base')
  assert.equal(v.nodes('div').some(n => /scope-controls/.test(n.props?.class || '')), false, 'the block above the input is gone')
  const trigger = () => s.trigger().props
  assert.equal(trigger()['aria-haspopup'], 'dialog'); assert.equal(trigger()['aria-expanded'], false)
  assert.match(v.textOf(s.trigger()), /Chat reading scope/); assert.match(v.textOf(s.trigger()), /None selected/)
  assert.equal(s.panel(), undefined, 'closed until asked for')

  stores.agentWorkspace.scopeSelection = chosen([{ type: 'group', id: 77 }, { type: 'contact', id: 5 }]); await v.paint()
  assert.match(v.textOf(s.trigger()), /2 selected · unconfirmed/); assert.match(trigger().class, /pending/)
  const summary = v.nodes('span').find(n => n.props?.class === 'scope-state')
  assert.equal(v.textOf(summary), 'Confirm · 2', 'the visible summary stays compact without losing the object count')
  assert.equal(summary.props['aria-hidden'], 'true', 'assistive technology uses the full per-request state instead of the abbreviation')
  assert.match(v.textOf(s.trigger()), /2 selected · unconfirmed/, 'the full state remains in the accessible text')
  stores.agentWorkspace.scopeConfirmed = true; await v.paint()
  assert.match(v.textOf(s.trigger()), /2 selected · confirmed once/)
  stores.agentWorkspace.scopeLoading = true; await v.paint()
  assert.match(v.textOf(s.trigger()), /Loading/)
  stores.agentWorkspace.scopeLoading = false; stores.agent.streaming = true; await v.paint()
  assert.match(v.textOf(s.trigger()), /Running/)
  await s.open()
  assert.doesNotMatch(v.textOf(s.trigger()), /authori[sz]ed|granted/i, 'the local trigger never claims a server grant')
  assert.match(s.text(), /Candidates are not grants\. Authorization is checked by Java for every run\./, 'the panel explains the distinction explicitly')
})

test('the panel opens above the button inside the column that clips it; close, Escape and an outside press only close it', async () => {
  const v = await mount(), s = scope(v)
  stores.agentWorkspace.scopeResources = people
  stores.agentWorkspace.scopeSelection = chosen([{ type: 'group', id: 77 }]); stores.agentWorkspace.scopeConfirmed = true
  await s.open()
  const panel = s.panel()
  assert.equal(panel.props.role, 'dialog'); assert.equal(panel.props['aria-labelledby'], 'agent-scope-title')
  assert.equal(s.trigger().props['aria-expanded'], true)
  const title = s.all('h3')[0]
  assert.equal(title.props.id, 'agent-scope-title'); assert.equal(document.activeElement, title.el, 'focus moves into the panel')
  // 420px wide, left-aligned with the button (x=200) and no taller than the 600px above it.
  assert.deepEqual(panel.props.style, { '--scope-width': '420px', '--scope-shift': '0px', '--scope-max': '584px' })
  assert.equal(panel.props['aria-modal'], undefined, 'a popover on a desktop, not a modal')

  await s.press(/^close$/i)
  assert.equal(s.panel(), undefined); assert.equal(document.activeElement, s.trigger().el, 'focus returns to the button')
  await s.open(); s.anchor().props.onKeydown({ key: 'Escape', stopPropagation() {} }); await v.paint()
  assert.equal(s.panel(), undefined); assert.equal(document.activeElement, s.trigger().el)

  await s.open()
  const press = target => { const event = new Event('pointerdown'); Object.defineProperty(event, 'target', { value: target }); document.dispatchEvent(event) }
  press(s.panel().el); await v.paint()
  assert.ok(s.panel(), 'a press inside the panel keeps it open')
  press(document.body); await v.paint()
  assert.equal(s.panel(), undefined, 'a press outside closes it')

  assert.deepEqual(scopeCalls(v), [], 'closing never confirms, saves, reloads or edits')
  assert.equal(stores.agentWorkspace.scopeConfirmed, true, 'an unspent confirmation survives closing')
  assert.deepEqual(stores.agentWorkspace.scopeSelection.targets, [{ type: 'group', id: 77 }])
  await s.open()
  assert.equal(s.boxes().filter(n => n.props.checked).length, 1, 'reopening shows the same selection')
})

test('the panel stays inside the column that clips it; a narrow screen gets a modal bottom sheet', async t => {
  const v = await mount(), s = scope(v), layout = { button: Host.button, column: Host.column }
  t.after(() => { Object.assign(Host, layout); window.innerWidth = 1280 })
  const placed = async (button, column) => { Object.assign(Host, { button, column }); await s.open(); const style = s.panel().props.style; await s.press(/^close$/i); return style }
  assert.deepEqual(await placed({ left: 1000, top: 300 }, { left: 0, top: 0, right: 1280, width: 1280 }),
    { '--scope-width': '420px', '--scope-shift': '-148px', '--scope-max': '284px' }, 'pulled back from the right edge')
  assert.deepEqual(await placed({ left: 320, top: 648 }, { left: 300, top: 48, right: 1000, width: 700 }),
    { '--scope-width': '420px', '--scope-shift': '0px', '--scope-max': '584px' }, 'measured against the column, not the window')
  assert.deepEqual(await placed({ left: 420, top: 648 }, { left: 300, top: 48, right: 680, width: 380 }),
    { '--scope-width': '364px', '--scope-shift': '-112px', '--scope-max': '584px' }, 'a column under 420px gets a narrower panel that still fits')
  window.innerWidth = 390; Object.assign(Host, { button: { left: 150, top: 500 }, column: { left: 0, top: 0, right: 390, width: 390 } })
  await s.open()
  assert.equal(s.panel().props['aria-modal'], 'true')
  assert.ok(s.all('input').filter(n => /nx-field/.test(n.props.class)).every(n => /nx-field-lg/.test(n.props.class)), '44px fields on a narrow screen')
  assert.ok(v.nodes('div').some(n => n.props?.class === 'scope-backdrop'))
  assert.ok(document.body.contains(s.panel().el), 'the modal sheet is mounted at the viewport root, outside clipping ancestors')
  assert.equal(s.anchor().el.contains(s.panel().el), false, 'it is not trapped inside the toolbar')
  const inside = new Event('pointerdown')
  Object.defineProperty(inside, 'target', { value: s.panel().el })
  document.dispatchEvent(inside); await v.paint()
  assert.ok(s.panel(), 'an inside press on a teleported panel does not dismiss it')
  s.panel().props.onKeydown({ key: 'Escape', stopPropagation() {} }); await v.paint()
  assert.equal(s.panel(), undefined, 'Escape works even though the panel is outside the anchor DOM')
  assert.equal(document.activeElement, s.trigger().el, 'closing the sheet restores focus')
})

test('targets come only from what was loaded: three distinct empty states, search by label or ID, same names told apart', async () => {
  const v = await mount(), s = scope(v)
  stores.agentWorkspace.bindingVersion = null
  await s.open()
  assert.match(s.text(), /not loaded yet/i); assert.equal(s.boxes().length, 0)
  await s.press(/load scope and available chats/i)
  assert.deepEqual(v.calls.filter(c => ['ensure', 'loadScope'].includes(c[0])), [['ensure'], ['loadScope', 'session-one']])
  stores.agentWorkspace.bindingVersion = 3; await v.paint()
  assert.match(s.text(), /No contacts or groups to choose from/); assert.doesNotMatch(s.text(), /not loaded yet/i)
  assert.ok(s.labelled(/\bReload$/), 'once loaded the button reloads')

  stores.agentWorkspace.scopeResources = people; await v.paint()
  assert.equal(s.boxes().length, 3)
  assert.match(s.text(), /Ada\s+#5/); assert.match(s.text(), /Ada\s+#6/); assert.match(s.text(), /Design review\s+#77/)
  assert.doesNotMatch(s.text(), /members|department/i, 'only the type, label and ID the server listed')
  await s.search('design'); assert.equal(s.boxes().length, 1); assert.match(s.text(), /Design review/)
  await s.search('6'); assert.equal(s.boxes().length, 1); assert.match(s.text(), /#6/)
  await s.search('zzz'); assert.equal(s.boxes().length, 0); assert.match(s.text(), /Nothing in the loaded list matches/)
  assert.doesNotMatch(s.text(), /No contacts or groups to choose from|not loaded yet/i)
  await s.search('')

  s.boxes()[0].props.onChange({}); await v.paint()
  assert.deepEqual(v.calls.findLast(c => c[0] === 'setScope')[1].targets, [{ type: 'contact', id: 5 }], 'only the type and ID are selected, never a label')
  s.boxes()[2].props.onChange({}); await v.paint()
  assert.deepEqual(stores.agentWorkspace.scopeSelection.targets, [{ type: 'contact', id: 5 }, { type: 'group', id: 77 }])
  assert.match(s.text(), /2 selected/)
  s.boxes()[0].props.onChange({}); await v.paint()
  assert.deepEqual(stores.agentWorkspace.scopeSelection.targets, [{ type: 'group', id: 77 }])

  // A saved target the list no longer holds stays visible and removable; it is not silently dropped.
  stores.agentWorkspace.scopeSelection = chosen([{ type: 'group', id: 99 }, { type: 'contact', id: 5 }]); await v.paint()
  assert.equal(s.boxes().length, 4); assert.match(s.text(), /Group\s+#99\s+Not in the available list/)
  assert.equal(s.all('button').filter(n => /open group chat/i.test(n.props.title || '')).length, 1, 'no conversation entry for a group that was not listed')
  await s.press(/clear selection/i)
  assert.deepEqual(stores.agentWorkspace.scopeSelection.targets, [])
  assert.equal(s.labelled(/clear selection/i).props.disabled, true)
})

test('a readable group in the scope panel opens that group conversation instead of only being selectable', async () => {
  const v = await mount(), s = scope(v)
  stores.agentWorkspace.scopeResources = { contacts: [], groups: [{ type: 'group', id: 77, chatId: 77, label: 'Design review' }] }
  await s.open()
  const row = s.all('li').find(n => /Design review/.test(v.textOf(n)))
  assert.equal(s.all('input', row).length, 1); assert.equal(s.all('button', row).length, 1, 'the row offers both: select it, or open it')
  // The entry does not wait for the chat snapshot; opening it fetches the chat list once.
  await s.press(/open group chat · Design review/i)
  assert.deepEqual(v.calls.filter(c => ['chats', 'open', 'view'].includes(c[0])), [['chats'], ['open', 77], ['view', 'chats']])
  assert.deepEqual(scopeCalls(v), [], 'opening the conversation neither selects nor confirms')
  assert.deepEqual(stores.agentWorkspace.scopeSelection.targets, [])
  assert.equal(s.panel(), undefined, 'the panel gets out of the way of the conversation')
})

test('a group the chat list does not contain reports why inside the panel instead of navigating anywhere', async () => {
  const v = await mount(), s = scope(v)
  stores.chat.fetchChats = async () => { v.calls.push(['chats']) }
  stores.agentWorkspace.scopeResources = { contacts: [], groups: [{ type: 'group', id: 88, chatId: 88, label: 'Gone' }] }
  await s.open()
  await s.press(/open group chat · Gone/i)
  assert.deepEqual(v.calls.filter(c => ['chats', 'open', 'view'].includes(c[0])), [['chats']])
  assert.match(v.text(), /not available yet/i)
  assert.deepEqual(s.alerts().filter(a => /not available yet/i.test(a)).length, 1, 'reported in the panel, where the entry is')
})

test('chosen chats wait for a confirmation per request: send is held, the hint opens the panel, confirming is explicit', async () => {
  const v = await mount(), s = scope(v)
  await v.type('Summarize today'); assert.doesNotMatch(v.text(), /Confirm the chat reading scope first/, 'an empty scope needs no confirmation')
  stores.agentWorkspace.scopeResources = people
  stores.agentWorkspace.scopeSelection = chosen([{ type: 'group', id: 77 }]); await v.paint()
  assert.equal(v.button(/^send$/i).props.disabled, true)
  assert.match(v.text(), /Confirm the chat reading scope first/)
  await v.enter(); assert.deepEqual(v.calls.filter(c => c[0] === 'send'), [], 'Enter does not get around it')

  await v.click(/open chat reading scope/i)
  assert.ok(s.panel(), 'the hint opens the panel')
  assert.match(s.text(), /Scope not confirmed yet/)
  await s.press(/confirm for the next request/i)
  assert.deepEqual(scopeCalls(v), ['confirm'])
  assert.ok(s.panel(), 'the panel stays open after confirming')
  assert.match(s.text(), /Confirmed once\. After a request is sent or the default scope is saved, confirm again/)
  assert.equal(s.labelled(/confirm for the next request/i).props.disabled, true, 'nothing to confirm twice')
  assert.equal(v.button(/^send$/i).props.disabled, false); assert.doesNotMatch(v.text(), /Confirm the chat reading scope first/)

  // Any edit ends it again, including one made after confirming.
  s.boxes()[0].props.onChange({}); await v.paint()
  assert.equal(stores.agentWorkspace.scopeConfirmed, false); assert.equal(v.button(/^send$/i).props.disabled, true)
  await s.press(/confirm for the next request/i); await v.enter()
  assert.equal(v.calls.filter(c => c[0] === 'send').length, 1)
})

test('saving the default is its own action: it needs a confirmed scope and a loaded binding, and it spends the confirmation', async () => {
  const v = await mount(), s = scope(v), save = () => s.labelled(/save default scope|saving/i)
  stores.agentWorkspace.scopeResources = people
  stores.agentWorkspace.scopeSelection = chosen([{ type: 'group', id: 77 }])
  await s.open()
  assert.match(s.text(), /Saved to this session\. Saving uses up this confirmation; the next request must be confirmed again/)
  assert.equal(save().props.disabled, true, 'not before the scope is confirmed')
  await s.press(/confirm for the next request/i); assert.equal(save().props.disabled, false)
  stores.agentWorkspace.bindingVersion = null; await v.paint()
  assert.equal(save().props.disabled, true); assert.match(s.text(), /binding needs to be reloaded/i)
  stores.agentWorkspace.bindingVersion = 1; stores.agent.sessionId = null; await v.paint()
  assert.equal(save().props.disabled, true, 'a binding is saved to a session')
  stores.agent.sessionId = 'session-one'; await v.paint()
  stores.agentWorkspace.scopeConfirmed = true; await v.paint()

  await s.press(/save default scope/i)
  assert.deepEqual(v.calls.filter(c => c[0] === 'saveScope'), [['saveScope', 'session-one']])
  assert.equal(stores.agentWorkspace.scopeConfirmed, false, 'saving does not confirm again by itself')
  assert.match(s.text(), /Default scope saved\. Confirm again before sending/)
  assert.equal(s.labelled(/confirm for the next request/i).props.disabled, false); assert.equal(save().props.disabled, true)
  s.boxes()[0].props.onChange({}); await v.paint()
  assert.doesNotMatch(s.text(), /Default scope saved/, 'an edited selection is no longer the saved one')

  // A refused save keeps the selection, reports once beside its button and does not hand the confirmation back.
  await s.press(/confirm for the next request/i)
  stores.agentWorkspace.saveScope = async function () { this.scopeConfirmed = false; this.bindingVersion = null; this.scopeError = 'Binding changed'; throw new Error('Binding changed') }
  await s.press(/save default scope/i)
  assert.deepEqual(s.alerts(), ['The default scope was not saved, and this confirmation has been used up. Binding changed'])
  assert.equal(stores.agentWorkspace.scopeConfirmed, false); assert.equal(save().props.disabled, true)
  assert.match(s.text(), /binding needs to be reloaded/i)
  assert.equal(stores.agentWorkspace.scopeSelection.targets.length, 2, 'the selection is kept')
  assert.equal(v.nodes('p').filter(n => n.props?.class === 'feedback' && /Binding changed/.test(v.textOf(n))).length, 0, 'not pushed out under the input')
})

test('while loading, saving or running the scope cannot be changed, but can still be read and closed', async () => {
  const v = await mount(), s = scope(v)
  stores.agentWorkspace.scopeResources = people
  stores.agentWorkspace.scopeSelection = chosen([{ type: 'group', id: 77 }], { fromInclusive: '2026-11-24T16:00:00.000Z', toExclusive: null, timeZone: 'Asia/Shanghai' })
  await s.open()
  const editing = () => [...s.boxes(), ...s.walls(), s.zone(), ...['day', 'week', 'all', 'custom'].map((_, i) => s.all('button').filter(n => /scope-chip/.test(n.props.class))[i]),
    s.labelled(/\bReload$/), s.labelled(/clear selection/i), s.labelled(/confirm for the next request/i), s.labelled(/save default scope|saving/i)]
  assert.equal(s.walls().length, 2, 'a stored range opens the custom editors')
  assert.deepEqual(editing().slice(0, -1).map(n => !!n.props.disabled), editing().slice(0, -1).map(() => false), 'everything is editable when idle')
  const frozen = async why => {
    await v.paint()
    assert.equal(editing().length, 14)
    assert.deepEqual(editing().map(n => n.props.disabled), editing().map(() => true), why)
    assert.equal(s.labelled(/^close$/i).props.disabled, undefined, 'the panel can always be closed')
    assert.equal(s.all('input').find(n => n.props.type === 'search').props.disabled, undefined, 'searching the loaded list changes nothing')
    assert.ok(!s.labelled(/open group chat/i).props.disabled)
  }
  stores.agentWorkspace.scopeLoading = true; await frozen('loading'); assert.match(s.text(), /Loading scope and available chats/)
  stores.agentWorkspace.scopeLoading = false; stores.agent.streaming = true; await frozen('a running request'); assert.match(s.text(), /A request is running; the scope cannot be changed/)
  stores.agent.streaming = false; stores.agent.switching = true; await frozen('switching sessions')
  stores.agent.switching = false; stores.agentWorkspace.scopeConfirmed = true; await v.paint()

  const answer = Promise.withResolvers()
  stores.agentWorkspace.saveScope = function () { this.scopeConfirmed = false; return answer.promise }
  const saving = s.labelled(/save default scope/i).props.onClick({})
  await frozen('a save in flight'); assert.ok(s.labelled(/^saving/i))
  answer.resolve(); await saving; await v.paint()
  assert.equal(s.boxes()[0].props.disabled, false, 'editable again once the save has answered')

  // Sending is a run too: from the click until the stream is over.
  const sent = Promise.withResolvers()
  stores.agent.sendMessage = () => sent.promise
  await s.press(/confirm for the next request/i); await v.type('Go'); await v.enter()
  await frozen('a request being submitted'); assert.match(v.textOf(s.trigger()), /Running/)
  sent.resolve(); await v.paint()
})

test('a superseded session lookup never starts a scope load for the old workspace', async () => {
  for (const boundary of ['session', 'account']) {
    const v = await mount(), s = scope(v), answer = Promise.withResolvers()
    stores.agent.ensureSession = () => answer.promise
    await s.open()
    const pending = s.labelled(/\bReload$/).props.onClick({})
    await v.paint()
    assert.equal(s.boxes().every(n => n.props.disabled), true)
    if (boundary === 'session') stores.agent.sessionId = 'session-two'
    else stores.user.currentUser.id = 19
    await v.paint()
    answer.resolve('session-one')
    await pending; await v.paint()
    assert.deepEqual(v.calls.filter(c => c[0] === 'loadScope'), [], `${boundary}: do not select or load the old workspace after its lookup finishes`)
    assert.deepEqual(s.alerts(), [], `${boundary}: stale lookup is not an error in the new workspace`)
  }
})

test('network failures are localized and follow the language without another request', async () => {
  for (const locale of ['en', 'zh']) {
    const v = await mount([], 'session-one', undescribed, locale), s = scope(v)
    stores.agentWorkspace.loadScope = async () => { throw Object.assign(new Error('Network Error'), { code: 'ERR_NETWORK' }) }
    await v.click(locale === 'zh' ? /^聊天读取范围 ·/ : /^chat reading scope ·/i)
    await s.press(locale === 'zh' ? /重新加载/ : /\bReload$/i)
    const messages = { en, zh }
    assert.deepEqual(s.alerts(), [messages[locale].agentScope.serviceUnavailable])
    assert.doesNotMatch(s.alerts().join(' '), /Network Error/)
    const other = locale === 'zh' ? 'en' : 'zh'
    v.i18n.global.locale.value = other; await v.paint()
    assert.deepEqual(s.alerts(), [messages[other].agentScope.serviceUnavailable], 'the existing failure changes language without being fetched again')
  }
})

test('a failed load and a refused turn are reported inside the panel, beside what they concern', async () => {
  const v = await mount(), s = scope(v)
  stores.agentWorkspace.loadScope = async () => { throw Object.assign(new Error('Request failed'), { response: { data: { message: 'Scope service offline' } } }) }
  await s.open(); await s.press(/\bReload$/)
  assert.deepEqual(s.alerts(), ['Scope service offline'])
  assert.equal(v.nodes('p').filter(n => n.props?.class === 'feedback').length, 0, 'nothing is pushed out under the input')
  stores.agentWorkspace.loadScope = async () => {}
  await s.press(/\bReload$/); assert.deepEqual(s.alerts(), [], 'a later success clears it')
  // What the agent store records when Java refuses a turn's scope.
  stores.agentWorkspace.scopeError = 'Scope authorization failed'; stores.agentWorkspace.bindingVersion = null
  stores.agentWorkspace.scopeResources = people; await v.paint()
  assert.deepEqual(s.alerts(), ['Scope authorization failed']); assert.match(s.text(), /binding needs to be reloaded/i)
})

test('the open panel is measured again when the input area or the column changes size, and stops watching once it is closed or gone', async t => {
  const v = await mount(), s = scope(v), layout = { button: Host.button, column: Host.column }
  t.after(() => Object.assign(Host, layout))
  // Other parts of the view listen on the window too; only the resize handlers are the panel's.
  const listeners = { add: [], remove: [] }
  t.mock.method(window, 'addEventListener', (type, handler) => { if (type === 'resize') listeners.add.push(handler) })
  t.mock.method(window, 'removeEventListener', (type, handler) => { if (type === 'resize') listeners.remove.push(handler) })
  await s.open()
  const button = s.anchor().el, sizes = Sizes.all.findLast(observer => observer.boxes.includes(button))
  assert.ok(sizes, 'the panel watches its own button')
  // Every box from the button up to the one that clips the panel: here the toolbar is that box.
  const watched = () => sizes.boxes.map(box => box.class)
  assert.deepEqual(watched(), ['scope-anchor', 'scope-context-tools', 'tools']); assert.ok(sizes.boxes[1] === button.parent)
  assert.deepEqual(s.panel().props.style, { '--scope-width': '420px', '--scope-shift': '0px', '--scope-max': '584px' })

  // A hint appeared under the toolbar and the input grew: the button moved up. No window resize was raised.
  Host.button = { left: 200, top: 520 }; sizes.changed(); await v.paint()
  assert.deepEqual(s.panel().props.style, { '--scope-width': '420px', '--scope-shift': '0px', '--scope-max': '504px' }, 'no taller than the room that is left')
  // The column lost width to a side panel: the panel narrows and is pulled back inside it.
  Host.column = { left: 300, top: 48, right: 680, width: 380 }; Host.button = { left: 420, top: 520 }; sizes.changed(); await v.paint()
  assert.deepEqual(s.panel().props.style, { '--scope-width': '364px', '--scope-shift': '-112px', '--scope-max': '456px' })

  await s.press(/^close$/i)
  assert.deepEqual(watched(), [], 'nothing is watched while the panel is closed')
  assert.equal(listeners.add.length, 1); assert.deepEqual(listeners.remove, listeners.add, 'the resize listener it added is the one it removed')
  await s.open()
  assert.deepEqual(watched(), ['scope-anchor', 'scope-context-tools', 'tools'], 'the same observer, watching again')
  assert.equal(Sizes.all.filter(observer => observer.changed === sizes.changed).length, 1, 'one observer for the life of the panel')
  apps.pop().unmount()
  assert.deepEqual(watched(), [], 'unmounting with the panel open leaves nothing watched')
  assert.equal(listeners.add.length, 2); assert.deepEqual(listeners.remove, listeners.add)
})

test('a load is loading from the click: creating the session is part of it, and nothing can be changed or loaded twice meanwhile', async () => {
  const v = await mount([], null), s = scope(v)
  stores.agentWorkspace.bindingVersion = null
  stores.agentWorkspace.scopeSelection = chosen([{ type: 'group', id: 77 }])
  await s.open()
  const session = Promise.withResolvers(), binding = Promise.withResolvers()
  stores.agent.ensureSession = () => { v.calls.push(['ensure']); return session.promise }
  stores.agentWorkspace.loadScope = sid => { v.calls.push(['loadScope', sid]); return binding.promise }
  const load = () => s.labelled(/load scope and available chats|\bReload$/i)
  const requests = () => v.calls.filter(c => ['ensure', 'loadScope'].includes(c[0]))
  const loading = load().props.onClick({}); await v.paint()
  // The store has not been asked yet and reports no load of its own.
  assert.deepEqual(requests(), [['ensure']]); assert.equal(stores.agentWorkspace.scopeLoading, false)
  assert.match(v.textOf(s.trigger()), /Loading/); assert.match(s.text(), /Loading scope and available chats…/)
  assert.equal(load().props.disabled, true, 'a second press cannot start a second session')
  assert.equal(s.boxes()[0].props.disabled, true); assert.equal(s.labelled(/confirm for the next request/i).props.disabled, true)
  assert.doesNotMatch(s.text(), /binding needs to be reloaded/i, 'not while it is being loaded')

  // The session coming into being is the same conversation: the load it belongs to goes on.
  stores.agent.sessionId = 'created'; session.resolve('created'); await v.paint()
  assert.deepEqual(requests(), [['ensure'], ['loadScope', 'created']])
  assert.match(v.textOf(s.trigger()), /Loading/); assert.equal(load().props.disabled, true)
  stores.agentWorkspace.bindingVersion = 1; binding.resolve(); await loading; await v.paint()
  assert.doesNotMatch(v.textOf(s.trigger()), /Loading/); assert.equal(load().props.disabled, false); assert.equal(s.boxes()[0].props.disabled, false)

  // A session that cannot be created is a failed load, reported where the button is.
  stores.agent.ensureSession = async () => { throw new Error('Session service offline') }
  await s.press(/\bReload$/)
  assert.deepEqual(s.alerts(), ['Session service offline']); assert.equal(load().props.disabled, false, 'and it can be tried again')
})

test('an answer that arrives after the session or the account changed is not reported in the one now on screen', async () => {
  const v = await mount(), s = scope(v), save = () => s.labelled(/save default scope|saving/i)
  stores.agentWorkspace.scopeResources = people
  stores.agentWorkspace.scopeSelection = chosen([{ type: 'group', id: 77 }])
  await s.open()
  const idle = async why => {
    await v.paint()
    assert.equal(s.boxes()[0].props.disabled, false, `${why}: the scope can be edited`)
    assert.match(v.textOf(save()), /Save default scope/, `${why}: nothing is being saved here`)
    assert.doesNotMatch(v.textOf(s.trigger()), /Loading/, `${why}: nothing is being loaded here`)
    assert.deepEqual(s.alerts(), [], `${why}: nothing is reported`); assert.doesNotMatch(s.text(), /Default scope saved/, why)
  }
  // A save accepted for the session that was left. The mock keeps the selection, as a
  // session with the same saved scope would, so only the isolation keeps "saved" out.
  let answer = Promise.withResolvers()
  stores.agentWorkspace.saveScope = function () { this.scopeConfirmed = false; return answer.promise }
  await s.press(/confirm for the next request/i)
  let pending = save().props.onClick({}); await v.paint()
  assert.match(v.textOf(save()), /Saving/)
  stores.agent.sessionId = 'session-two'; await idle('after switching session')
  answer.resolve(); await pending; await idle('a late accepted save')

  // A save refused for the session that was left.
  answer = Promise.withResolvers()
  await s.press(/confirm for the next request/i)
  pending = save().props.onClick({}); await v.paint()
  stores.agent.sessionId = 'session-three'; await v.paint()
  answer.reject(new Error('Binding changed')); await pending; await idle('a late refused save')

  // A load that fails after another account signed in.
  answer = Promise.withResolvers()
  stores.agentWorkspace.loadScope = () => answer.promise
  pending = s.labelled(/\bReload$/).props.onClick({}); await v.paint()
  assert.match(v.textOf(s.trigger()), /Loading/)
  stores.user.currentUser = { id: 4, nickname: 'Rae' }; await idle('after switching account')
  answer.reject(new Error('Scope service offline')); await pending; await idle('a late failed load')

  // A group conversation asked for before the token changed is not opened afterwards.
  answer = Promise.withResolvers(); v.calls.length = 0
  stores.chat.fetchChats = function () { return answer.promise.then(() => { this.groupChats = [{ id: 77, type: 'GROUP', name: 'Design review' }] }) }
  pending = s.labelled(/open group chat · Design review/i).props.onClick({}); await v.paint()
  stores.user.token = 'token-two'; await v.paint()
  answer.resolve(); await pending; await idle('a late chat list')
  assert.deepEqual(v.calls.filter(c => ['open', 'view'].includes(c[0])), [], 'no navigation on behalf of the earlier sign-in')
  assert.ok(s.panel(), 'and the panel is left as it was')

  // The same requests, answered while nothing changed, still report.
  stores.agentWorkspace.loadScope = async () => { throw new Error('Scope service offline') }
  await s.press(/\bReload$/); assert.deepEqual(s.alerts(), ['Scope service offline'])
})

test('"this week" never ends after the click: the first instant is refused with a reason, the first second ends at the click itself', async t => {
  mock.timers.enable({ apis: ['Date'], now: Date.parse('2026-11-30T00:00:00.000Z') }); t.after(() => mock.timers.reset())
  const v = await mount(), s = scope(v), range = () => ({ ...stores.agentWorkspace.scopeSelection.timeRange })
  const pressed = () => s.all('button').filter(n => /scope-chip/.test(n.props.class) && n.props['aria-pressed']).map(n => v.textOf(n).trim())
  stores.agentWorkspace.scopeSelection = chosen([{ type: 'group', id: 77 }])
  await s.open(); await s.press(/confirm for the next request/i); v.calls.length = 0

  // Monday 00:00:00.000 exactly: the week holds nothing yet.
  await s.press(/this week/i)
  assert.deepEqual(s.alerts(), ['This week has only just begun, so there is nothing to read yet. Press it again in a moment, or choose another range.'])
  assert.deepEqual(range(), { fromInclusive: null, toExclusive: null, timeZone: 'UTC' }, 'nothing is stored, least of all a window into the future')
  assert.deepEqual(scopeCalls(v), []); assert.deepEqual(pressed(), ['All time'], 'the preset that is stored stays the one shown')
  assert.equal(stores.agentWorkspace.scopeConfirmed, false); assert.equal(s.labelled(/confirm for the next request/i).props.disabled, true)

  // 400 ms later the week holds those 400 ms, and no more.
  mock.timers.setTime(Date.parse('2026-11-30T00:00:00.400Z'))
  await s.press(/this week/i)
  assert.deepEqual(s.alerts(), [])
  assert.deepEqual(range(), { fromInclusive: '2026-11-30T00:00:00.000Z', toExclusive: '2026-11-30T00:00:00.400Z', timeZone: 'UTC' })
  assert.ok(Date.parse(range().toExclusive) <= Date.now(), 'the end is not after the click')
  assert.deepEqual(pressed(), ['This week']); assert.match(s.text(), /End \(exclusive\)\s+2026-11-30 00:00:00\.400/, 'shown to the millisecond it is stored with')
  assert.equal(s.labelled(/confirm for the next request/i).props.disabled, false)

  // Taken again in a zone where that click is the first instant of the week: refused there too.
  mock.timers.setTime(Date.parse('2026-11-29T16:00:00.000Z')); await s.press(/all time/i); await s.zoneText('Asia/Shanghai', false)
  await s.press(/this week/i)
  assert.equal(s.alerts().length, 1); assert.match(s.alerts()[0], /only just begun/)
  assert.deepEqual(range(), { fromInclusive: null, toExclusive: null, timeZone: 'Asia/Shanghai' })
  await s.press(/last 24 hours/i)
  assert.deepEqual(s.alerts(), [], 'another range clears it'); assert.equal(range().toExclusive, '2026-11-29T16:00:00.000Z')
})

test('presets become fixed UTC boundaries taken at the click; custom entries are read in the chosen zone', async t => {
  mock.timers.enable({ apis: ['Date'], now: Date.parse('2026-11-25T08:15:30.640Z') }); t.after(() => mock.timers.reset())
  const v = await mount(), s = scope(v), range = () => ({ ...stores.agentWorkspace.scopeSelection.timeRange })
  const pressed = () => s.all('button').filter(n => /scope-chip/.test(n.props.class) && n.props['aria-pressed']).map(n => v.textOf(n).trim())
  await s.open()
  assert.deepEqual(pressed(), ['All time']); assert.equal(s.walls().length, 0)
  assert.match(s.text(), /Start \(inclusive\)\s+No start limit/); assert.match(s.text(), /End \(exclusive\)\s+No end limit/)
  assert.equal(s.zone().props.value, 'UTC', 'the default stays what the code has: all time, UTC')

  await s.press(/last 24 hours/i)
  assert.deepEqual(range(), { fromInclusive: '2026-11-24T08:15:30.000Z', toExclusive: '2026-11-25T08:15:30.000Z', timeZone: 'UTC' })
  assert.deepEqual(pressed(), ['Last 24 hours'])
  assert.match(s.text(), /2026-11-24 08:15:30/); assert.match(s.text(), /2026-11-25 08:15:30/)
  // The zone only changes how the same 24 hours read.
  await s.zoneText('Asia/Shanghai', false)
  assert.deepEqual(range(), { fromInclusive: '2026-11-24T08:15:30.000Z', toExclusive: '2026-11-25T08:15:30.000Z', timeZone: 'Asia/Shanghai' })
  assert.match(s.text(), /2026-11-24 16:15:30/); assert.deepEqual(pressed(), ['Last 24 hours'])

  await s.press(/this week/i)
  assert.deepEqual(range(), { fromInclusive: '2026-11-22T16:00:00.000Z', toExclusive: '2026-11-25T08:15:30.000Z', timeZone: 'Asia/Shanghai' })
  assert.match(s.text(), /2026-11-23 00:00:00/, 'the summary shows the full boundary, not just "this week"')
  // Monday midnight belongs to a zone, so the week is taken again there, at the original click.
  mock.timers.setTime(Date.parse('2026-11-25T09:00:00Z'))
  await s.zoneText('UTC', false)
  assert.deepEqual(range(), { fromInclusive: '2026-11-23T00:00:00.000Z', toExclusive: '2026-11-25T08:15:30.000Z', timeZone: 'UTC' })
  assert.deepEqual(pressed(), ['This week'])

  // "Custom" opens the editors on the range as it stands. It is not an edit.
  await s.press(/confirm for the next request/i); v.calls.length = 0
  await s.press(/^custom$/i)
  assert.deepEqual(scopeCalls(v), []); assert.equal(stores.agentWorkspace.scopeConfirmed, true); assert.deepEqual(pressed(), ['Custom'])
  assert.deepEqual(s.walls().map(n => n.props.value), ['2026-11-23T00:00:00', '2026-11-25T08:15:30'])
  assert.deepEqual(s.walls().map(n => [n.props.type, n.props.step]), [['datetime-local', '1'], ['datetime-local', '1']], 'a date-time picker with seconds, not a hand-written ISO string')

  // Typed digits are read in the zone: the same digits in another zone are another instant.
  await s.zoneText('Asia/Shanghai', false)
  assert.equal(stores.agentWorkspace.scopeConfirmed, false)
  assert.deepEqual(range(), { fromInclusive: '2026-11-22T16:00:00.000Z', toExclusive: '2026-11-25T00:15:30.000Z', timeZone: 'Asia/Shanghai' })
  await s.wall('from', '2026-11-25T00:00'); await s.wall('to', '2026-11-26T00:00')
  assert.deepEqual(range(), { fromInclusive: '2026-11-24T16:00:00.000Z', toExclusive: '2026-11-25T16:00:00.000Z', timeZone: 'Asia/Shanghai' })
  await s.wall('to', '')
  assert.deepEqual(range(), { fromInclusive: '2026-11-24T16:00:00.000Z', toExclusive: null, timeZone: 'Asia/Shanghai' })
  assert.match(s.text(), /End \(exclusive\)\s+No end limit/)
  await s.press(/all time/i)
  assert.deepEqual(range(), { fromInclusive: null, toExclusive: null, timeZone: 'Asia/Shanghai' }); assert.equal(s.walls().length, 0)
})

test('a time the zone cannot place is refused in its own field: nothing stored changes and the confirmation ends', async () => {
  const v = await mount(), s = scope(v), range = () => ({ ...stores.agentWorkspace.scopeSelection.timeRange })
  const stored = { fromInclusive: '2026-03-01T05:00:00.000Z', toExclusive: '2026-12-01T05:00:00.000Z', timeZone: 'America/New_York' }
  stores.agentWorkspace.scopeSelection = chosen([{ type: 'group', id: 77 }], stored)
  await s.open()
  const confirm = () => s.labelled(/confirm for the next request/i)
  const refused = async (side, value, message, more) => {
    await s.press(/confirm for the next request/i); v.calls.length = 0
    await s.wall(side, value, more)
    assert.equal(s.alerts().length, 1); assert.match(s.alerts()[0], message)
    assert.deepEqual(range(), stored, 'the stored range is untouched'); assert.deepEqual(scopeCalls(v), [])
    assert.equal(stores.agentWorkspace.scopeConfirmed, false, 'the earlier confirmation does not survive the edit')
    assert.equal(confirm().props.disabled, true, 'and what is on screen cannot be confirmed')
    assert.equal(s.walls()[side === 'from' ? 0 : 1].props['aria-invalid'], true)
    assert.equal(v.button(/^send$/i).props.disabled, true)
    // Put the field back; the stored range was never changed.
    await s.wall(side, side === 'from' ? '2026-03-01T00:00:00' : '2026-12-01T00:00:00'); assert.deepEqual(s.alerts(), [])
  }
  await v.type('Summarize')
  await refused('from', '2026-03-08T02:30:00', /does not exist in the selected zone/)        // skipped at spring-forward
  await refused('to', '2026-11-01T01:30:00', /occurs twice in the selected zone/)             // repeated at fall-back
  await refused('from', '', /complete date and time/, { validity: { badInput: true } })      // half-typed
  await s.press(/confirm for the next request/i); await s.wall('from', '2026-12-01T00:00:00')
  assert.match(s.alerts()[0], /start must be earlier than the end/i, 'an empty window holds nothing'); assert.deepEqual(range(), stored)
  await s.wall('from', '2026-03-08T03:00:00')
  assert.deepEqual(s.alerts(), []); assert.equal(range().fromInclusive, '2026-03-08T07:00:00.000Z', 'the first second after the gap is accepted')

  // The zone: text still being typed is not an error yet, but it is not the stored zone either.
  await s.press(/confirm for the next request/i)
  await s.zoneText('Asia/Sh', false)
  assert.deepEqual(s.alerts(), []); assert.equal(stores.agentWorkspace.scopeConfirmed, false); assert.equal(confirm().props.disabled, true)
  assert.equal(range().timeZone, 'America/New_York')
  await s.zoneText('Asia/Sh')
  assert.match(s.alerts()[0], /valid time zone name/); assert.equal(range().timeZone, 'America/New_York'); assert.equal(s.zone().props['aria-invalid'], true)
  await s.zoneText('+08:00'); assert.match(s.alerts()[0], /valid time zone name/, 'an offset is not a zone the backend reads by')
  await s.zoneText('asia/shanghai')
  assert.deepEqual(s.alerts(), []); assert.equal(range().timeZone, 'Asia/Shanghai'); assert.equal(s.zone().props.value, 'Asia/Shanghai')
  assert.equal(confirm().props.disabled, false)
})

test('the receipt is what the server reported for a run, kept apart from the local selection', async () => {
  const receipt = { chatIds: [77], contactUserIds: [5, 6], authorizationVersion: 4, timeRange: { fromInclusive: '2026-11-24T16:00:00.000Z', toExclusive: null, timeZone: 'Asia/Shanghai' } }
  const v = await mount([{ ...message, status: 'done', scopeState: 'observed', actualScope: receipt }]), s = scope(v)
  stores.agentWorkspace.bindingVersion = 3; stores.agentWorkspace.sessionGeneration = 2
  await s.open()
  assert.deepEqual(s.all('details').map(n => n.props?.open), [undefined, undefined], 'both collapsed by default')
  const [reported, advanced] = s.all('details').map(n => v.textOf(n))
  assert.match(reported, /Receipt for the most recent request \(not current permission\)/)
  assert.match(reported, /Chat IDs\s+77/); assert.match(reported, /Contact user IDs\s+5, 6/)
  assert.match(reported, /2026-11-25 00:00:00/); assert.match(reported, /No end limit/); assert.match(reported, /Asia\/Shanghai/)
  assert.match(reported, /does not mean every chat record was read/)
  assert.match(advanced, /Binding version\s+3\s+from this session's binding/); assert.match(advanced, /Session generation\s+2/)
  assert.match(advanced, /Authorization version\s+4\s+from the latest receipt/)
  assert.match(v.textOf(s.trigger()), /None selected/, 'a past receipt never decides the local state')

  stores.agent.streaming = true; stores.agent.messages[0].status = 'streaming'; await v.paint()
  assert.match(v.textOf(s.all('details')[0]), /Receipt for the current request/)
  stores.agent.streaming = false; stores.agent.messages[0].scopeState = 'unavailable'; await v.paint()
  assert.match(v.textOf(s.all('details')[0]), /Permission unavailable: this run’s results cannot be read/)
  stores.agent.messages[0].scopeState = 'unknown'; stores.agentWorkspace.bindingVersion = null; await v.paint()
  assert.match(v.textOf(s.all('details')[0]), /No usable receipt yet/)
  assert.match(v.textOf(s.all('details')[1]), /Binding version\s+—/); assert.match(v.textOf(s.all('details')[1]), /Authorization version\s+—/, 'a version that was not reported is not made up')
})

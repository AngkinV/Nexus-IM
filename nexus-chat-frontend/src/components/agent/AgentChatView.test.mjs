import { after, before, test } from 'node:test'
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

// Mount real compiled Vue components using a small in-memory renderer. This needs
// neither a DOM package nor a live backend. Invoke the rendered controls and keep
// Vue's real event, lifecycle, child-prop, watcher and scheduling behavior.
const here = dirname(fileURLToPath(import.meta.url)), require = createRequire(import.meta.url)
const owned = ['AgentChatView', 'AgentComposer', 'AgentWorkbenchToolbar']
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
const windows = { window: globalThis.window, document: globalThis.document }
const apps = []
class Host extends EventTarget {
  constructor(tag = '') { super(); Object.assign(this, { tagName: tag.toUpperCase(), style: {}, children: [], scrollHeight: 100, clientHeight: 100, scrollTop: 0 }) }
  focus() { document.activeElement = this }
  contains(node) { return this === node || this.children.some(c => c.contains(node)) }
  scrollTo({ top }) { this.scrollTop = top }
}
before(() => {
  globalThis.window = Object.assign(new Host(), { performance: globalThis.performance }); globalThis.document = new Host(); document.body = new Host('body'); document.documentElement = new Host('html'); document.documentElement.children.push(document.body)
})
after(() => { apps.forEach(app => app.unmount()); Object.assign(globalThis, windows); delete globalThis.workbenchTestStores })
const remove = el => { if (el.parent) el.parent.children = el.parent.children.filter(c => c !== el) }
const renderer = createRenderer({
  createElement: tag => new Host(tag), createComment: () => new Host(), createText: text => Object.assign(new Host(), { text }),
  setText: (n, text) => { n.text = text }, setElementText: (n, text) => { n.text = text }, patchProp: (n, k, _, v) => { if (!k.startsWith('on')) n[k] = v },
  insert(el, parent, anchor) { remove(el); const pos = parent.children.indexOf(anchor); parent.children.splice(pos === -1 ? parent.children.length : pos, 0, el); el.parent = parent },
  remove, parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1],
  insertStaticContent(text, parent, anchor) { const el = Object.assign(new Host(), { text }); parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el); el.parent = parent; return [el, el] }
})

const undescribed = error => ({ key: null, fallback: error.message || error.code || '', detail: error.code || '', settings: false })
async function mount(messages = [], sessionId = 'session-one', describeModelError = undescribed) {
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
      async loadScope(sid) { calls.push(['loadScope', sid]) }, setScope() {}, confirmScope() {},
      takeScope() { return { scopeSelection: this.scopeSelection, scopeConfirmed: true, expectedBindingVersion: 1 } },
      invalidateScope() {}, async saveScope() {},
      async upload(sid, file) { calls.push(['upload', sid, file.name]); const a = { id: 'att-1', fileName: file.name }; this.attachments.push(a); return a },
      async removeAttachment(sid, id) { calls.push(['delete', sid, id]) }, async updateTask(sid, id, status) { calls.push(['task', sid, id, status]) },
      // Switching the workspace session is not a data load; only load() records one.
      select(sid) { this.sessionId = sid }
    }),
    agentProviders: reactive({ providers: [selected, { id: 9, purpose: 'embedding', defaultModel: 'vector-only' }],
      chatProviders: [selected], defaultProvider: selected, async fetchAll() {} }),
    knowledge: reactive({ activeKbId: 'kb-42' }), user: { currentUser: { nickname: 'Quinn' } }, chat: { clearActiveChat() { calls.push(['back']) } }
  }
  const root = h(View), app = renderer.createApp({ render: () => root })
  app.provide('isMobile', ref(false)).provide('activeView', ref('ai')).provide('toggleList', () => calls.push(['list']))
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en }, missingWarn: false, fallbackWarn: false }))
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
  return { calls, paint, nodes, button, click, type, enter, widgets, menu, text: () => walk(root).filter(n => typeof n.children === 'string').map(n => n.children).join(' ') }
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

import assert from 'node:assert/strict'
import { after, afterEach, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { compileScript, parse } from '@vue/compiler-sfc'
import en from '../../locales/en.json' with { type: 'json' }
import { createPinia } from 'pinia'
import { createServer } from 'vite'
import { createRenderer, nextTick } from 'vue'

// Mount the real shell, history and palette without starting WebGL, a browser or a backend.
const root = fileURLToPath(new URL('../../../', import.meta.url))
const components = /\/(Main|LeftPanel|AgentSessionList|CommandPalette)\.vue$/
const storage = new Map()
let vite, Main, i18n, agentAPI, useAgentStore, useChatStore, useKnowledgeStore, dialogs, app, body
const load = path => vite.ssrLoadModule(path)
before(async () => {
  globalThis.localStorage = { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) }
  vite = await createServer({
    root, configFile: false, logLevel: 'error', optimizeDeps: { noDiscovery: true }, ssr: { noExternal: ['element-plus'] },
    server: { middlewareMode: true, hmr: false, watch: null }, resolve: { alias: { '@': `${root}src` } },
    plugins: [{
      name: 'session-navigation-test', enforce: 'pre',
      resolveId(id) { if (id === 'element-plus') return '\0navigation-dialogs' },
      load(id) {
        if (id === '\0navigation-dialogs') return 'export const ElMessage = {success() {}, error() {}}; export const ElMessageBox = {}'
      },
      transform(source, id) {
        if (!id.endsWith('.vue')) return
        if (!components.test(id)) return 'export default {render: () => null}'
        return compileScript(parse(source).descriptor, { id, inlineTemplate: true }).content
      }
    }]
  })
  ;({ default: Main } = await load('/src/views/Main.vue'))
  ;({ default: i18n } = await load('/src/locales/i18n.js'))
  ;({ agentAPI } = await load('/src/services/agentApi.js'))
  ;({ useAgentStore } = await load('/src/stores/agent.js'))
  ;({ useChatStore } = await load('/src/stores/chat.js'))
  ;({ useKnowledgeStore } = await load('/src/stores/knowledge.js'))
  ;({ ElMessageBox: dialogs } = await load('element-plus'))
})
after(() => vite?.close())
afterEach(() => app?.unmount())

// Minimal Vue host: native click/input listeners, template-ref focus and Teleport targets.
class Host extends EventTarget {
  constructor(tag, text = '') { super(); Object.assign(this, { tag, tagName: tag.toUpperCase(), text, children: [], props: {}, style: {} }) }
  focus() { document.activeElement = this }
}
const remove = el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1) }
const renderer = createRenderer({
  createElement: tag => new Host(tag), createText: text => new Host('#text', text), createComment: () => new Host('#comment'),
  setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] },
  insert(el, parent, before) { remove(el); el.parent = parent; parent.children.splice(before ? parent.children.indexOf(before) : parent.children.length, 0, el) },
  remove, parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1],
  insertStaticContent(html, parent, anchor) {
    const el = new Host('#static', html.replace(/<[^>]+>/g, ''))
    el.parent = parent; parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el)
    return [el, el]
  },
  patchProp(el, key, prev, next) {
    el.props[key] = next
    if (key === 'value') el.value = next
    if (/^on[A-Z]/.test(key)) {
      if (prev) el.removeEventListener(key.slice(2).toLowerCase(), prev)
      if (next) el.addEventListener(key.slice(2).toLowerCase(), next)
    }
  },
  querySelector: () => body
})
const all = (el, predicate) => el ? [...(predicate(el) ? [el] : []), ...el.children.flatMap(child => all(child, predicate))] : []
const text = el => [el.text, ...el.children.map(text)].join(' ')
const click = async el => { assert.ok(el, 'control exists'); el.dispatchEvent(new Event('click')); await settle() }
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }
async function key(key, modifiers = { ctrlKey: true }, claimed = false) {
  const event = Object.assign(new Event('keydown', { cancelable: true }), { key, ...modifiers })
  if (claimed) event.preventDefault() // e.g. the artifact drawer at document capture
  window.dispatchEvent(event)
  await settle()
  return event
}
const reply = data => ({ data: { code: 'OK', data } })
async function mount(width = 1440) {
  storage.clear()
  globalThis.window = Object.assign(new EventTarget(), { innerWidth: width })
  body = new Host('body')
  globalThis.document = { activeElement: null, body }
  const pinia = createPinia()
  app = renderer.createApp(Main).use(pinia).use(i18n)
  for (const name of ['el-dropdown', 'el-dropdown-menu', 'el-dropdown-item']) app.component(name, { render: () => null })
  const agent = useAgentStore(pinia), chat = useChatStore(pinia)
  const sessions = [
    { sessionId: 'a', title: 'Early task', updatedAt: '2026-01-02' },
    { sessionId: 'b', title: 'Later task', updatedAt: '2026-04-02' }
  ]
  agentAPI.listSessions = async () => reply(sessions)
  agentAPI.sessionMessages = async () => reply({ messages: [] })
  agentAPI.listRuns = agentAPI.pendingRuns = async () => reply({ runs: [] })
  agentAPI.createSession = async () => reply({ sessionId: 'new' })
  agent.sessionId = 'a'
  agent.messagesBySessionId = { b: [{ role: 'assistant', content: 'Release\n checklist' }] }
  app.mount(body)
  await key('4')
  return { agent, chat, knowledge: useKnowledgeStore(pinia) }
}
// The AI history box only. The chats pane has its own "messages and conversations"
// box, and a loose match found that one instead: the assertion then compared the
// wrong control and the failure surfaced as a hang rather than a diff.
const search = () => all(body, el => el.tag === 'input' && el.props.placeholder === en.agent.history.search)[0]
const rows = () => all(body, el => el.tag === 'li')
const rowTitles = () => rows().map(el => text(el).match(/Early task|Later task/)?.[0])

test('a cached transcript preview is searchable; clearing and selecting retain history order', async () => {
  const { agent, chat } = await mount(390)
  const input = search()
  input.value = ' cHeCkLiSt '; input.dispatchEvent(new Event('input')); await nextTick()
  assert.deepEqual(rowTitles(), ['Later task'])
  input.value = ''; input.dispatchEvent(new Event('input')); await nextTick()
  assert.deepEqual(rowTitles(), ['Later task', 'Early task'])
  for (const [index, id] of [[1, 'a'], [0, 'b']]) {
    chat.clearActiveChat()
    const button = all(rows()[index], el => el.tag === 'button' && text(el).includes('task'))[0]
    await click(button) // native buttons support Enter and Space without duplicate key handlers
    assert.equal(chat.activeChat?.id, 'ai-assistant')
    assert.equal(agent.sessionId, id)
    assert.deepEqual(rowTitles(), ['Later task', 'Early task'])
    assert.equal(all(button, el => el !== button && el.tag === 'button').length, 0)
  }
})

test('AI search reveals folded history and the mobile list; palette and claimed digits stay scoped', async () => {
  const { chat } = await mount(820)
  assert.ok(all(body, el => el.props.class?.includes('blade-shell') && el.props.class.includes('is-collapsed')).length, 'tablet starts compact')
  await key('k', { metaKey: true })
  assert.ok(search() && document.activeElement === search(), 'focus moved to history search')
  assert.ok(!all(body, el => el.props.class?.includes('is-collapsed') && el.props.class.includes('blade-shell')).length)
  await key('2', { ctrlKey: true }, true)
  assert.ok(search(), 'the drawer-owned digit did not switch sections')
  window.innerWidth = 390; window.dispatchEvent(new Event('resize')); await nextTick()
  await key('k'); assert.equal(chat.activeChat, null); assert.ok(document.activeElement === search())
  await key('2'); await key('k')
  assert.ok(all(body, el => el.props.role === 'dialog').length, 'contacts retains its command palette')
})

test('returning to Chats keeps its palette even if the previous virtual chat is still selected', async () => {
  await mount()
  await key('1'); await key('k')
  assert.ok(all(body, el => el.props.role === 'dialog').length)
  assert.equal(search(), undefined)
})

test('an ordinary visit after searching does not steal focus again', async () => {
  await mount()
  await key('k'); await key('2')
  const elsewhere = document.activeElement = new Host('button')
  await key('4')
  assert.ok(document.activeElement === elsewhere)
})

test('a mobile selection opens the cached chat immediately and a late read cannot undo Back', async () => {
  const { chat } = await mount(390)
  const read = Promise.withResolvers()
  agentAPI.sessionMessages = () => read.promise
  await click(all(rows()[0], el => el.tag === 'button' && text(el).includes('Later task'))[0])
  assert.equal(chat.activeChat?.id, 'ai-assistant')
  chat.clearActiveChat(); read.resolve(reply({ messages: [] })); await settle()
  assert.equal(chat.activeChat, null)
})

test('new-session shortcut preserves busy sessions and opens the virtual chat only after success', async () => {
  const { agent, chat } = await mount(390)
  for (const flag of ['streaming', 'switching']) {
    agent[flag] = true; await key('n'); assert.equal(agent.sessionId, 'a'); agent[flag] = false
  }
  const create = agentAPI.createSession
  agentAPI.createSession = async () => { throw new Error('offline') }
  await key('n'); assert.equal(agent.sessionId, 'a'); assert.equal(chat.activeChat, null)
  agentAPI.createSession = create
  await key('n', { metaKey: true }); assert.equal(agent.sessionId, 'new'); assert.equal(chat.activeChat?.id, 'ai-assistant')
})

test('the active summary shows measured model data, not tool/run time or invented usage', async () => {
  const { agent, knowledge } = await mount()
  agent.messages = [{ role: 'assistant', status: 'error', content: 'Partial answer', elapsedMs: 999,
    trace: [{ kind: 'model', model: 'actual-local-model', ms: 37, status: 'done' },
      { kind: 'tool', toolName: 'fetch', latencyMs: 92, status: 'FAILED' }, { kind: 'done', finishReason: 'interrupted' }],
    sources: [{ id: 'K1', kind: 'knowledge', title: 'Release notes' }],
    usage: { inputTokens: 123, outputTokens: 8, totalTokens: 131, usageReported: true } }]
  knowledge.kbs = [{ kbId: 'kb1', name: 'Support docs' }]; knowledge.setActiveKb('kb1')
  await nextTick()
  for (const literal of [/actual-local-model/, /interrupted/i, /37\s*ms/, /123/, /Support docs/, /1 KB source/i]) assert.match(text(body), literal)
  assert.doesNotMatch(text(body), /999\s*ms|92\s*ms/)
  agent.messages = [{ role: 'assistant', status: 'done', content: 'An older transcript', usage: { inputTokens: 0, outputTokens: 0, usageReported: false } }]
  await nextTick(); assert.doesNotMatch(text(body), /actual-local-model|37\s*ms|0 tokens|mock demo/i)
})

test('reported truncation, partial input usage and a restored KB belong to this run, not the next provider', async () => {
  const { agent, knowledge } = await mount()
  const { useAgentProvidersStore } = await load('/src/stores/agentProviders.js')
  useAgentProvidersStore().providers = [{ id: 8, isDefault: true, defaultModel: 'next-model' }]
  knowledge.kbs = [{ kbId: 'build-kb', name: 'Build handbook' }]
  agent.messages = [{ role: 'assistant', runId: 'last', status: 'truncated', finishReason: 'max_output_tokens',
    linkedKbId: 'build-kb', usage: { inputTokens: 57, usageReported: true }, trace: [] }]
  await nextTick()
  const summary = all(body, el => el.tag === 'section' && el.props['aria-label'] === 'Active task')[0]
  assert.match(text(summary), /limit reached/i)
  assert.doesNotMatch(text(summary), /next-model/)
  assert.match(text(body), /Build handbook/); assert.match(text(body), /57/)
  agent.messages[0].status = 'interrupted'; agent.messages[0].finishReason = null
  agent.messages[0].trace = [{ kind: 'error', transport: true, code: 'STREAM_INCOMPLETE', message: 'early EOF' }]
  await nextTick(); assert.match(text(summary), /interrupted/i)
})

test('rename and delete are independent native actions and cancellation or failure retains the row', async () => {
  await mount()
  const target = () => rows().find(el => text(el).includes('Later task'))
  const action = label => all(target(), el => el.tag === 'button' && (el.props.title || '').startsWith(label))[0]
  let renamed, deleted
  agentAPI.renameSession = async (id, title) => { renamed = [id, title]; return reply({}) }
  agentAPI.deleteSession = async id => { deleted = id; throw new Error('offline') }
  dialogs.prompt = async () => ({ value: '  Revised name  ' })
  await click(action('Rename')); assert.deepEqual(renamed, ['b', 'Revised name'])
  dialogs.confirm = async () => { throw 'cancel' }
  await click(action('Delete')); assert.equal(deleted, undefined)
  dialogs.confirm = async () => 'confirm'
  await click(action('Delete')); assert.equal(deleted, 'b'); assert.ok(target())
})

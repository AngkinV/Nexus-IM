import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { build } from 'esbuild'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick, reactive } from 'vue'
import { createI18n } from 'vue-i18n'
import en from '../../locales/en.json' with { type: 'json' }
import zh from '../../locales/zh.json' with { type: 'json' }

// Mounts the real compiled RoleList (and the real delete button inside each row)
// with a small in-memory renderer, the same way AgentChatView.test.mjs does. The
// stores are plain reactive objects holding what the backend returns for a role;
// the preview text, search and row structure under test are the component's own.
const here = dirname(fileURLToPath(import.meta.url)), src = resolve(here, '../..'), require = createRequire(import.meta.url)
const owned = ['RoleList', 'ClearConversationButton']
let View, stores
before(async () => {
  const bundle = await build({
    entryPoints: [resolve(here, 'RoleList.vue')], bundle: true, write: false,
    format: 'cjs', platform: 'node', packages: 'external', logLevel: 'silent',
    plugins: [{ name: 'role-list-sfc', setup(b) {
      b.onResolve({ filter: /\.vue$/ }, args => {
        const name = args.path.split('/').at(-1).slice(0, -4)
        return owned.includes(name) ? { path: resolve(here, `${name}.vue`), namespace: 'sfc' } : { path: name, namespace: 'widget' }
      })
      b.onLoad({ filter: /.*/, namespace: 'sfc' }, async ({ path }) => {
        const { descriptor } = parse(await readFile(path, 'utf8'), { filename: path })
        return { contents: compileScript(descriptor, { id: 'role-list', inlineTemplate: true }).content, resolveDir: here }
      })
      b.onResolve({ filter: /^@\/stores\/|^element-plus$/ }, ({ path }) => ({ path, namespace: 'boundary' }))
      // Plain modules behind the alias (roleText, tavernScenes, useTavernInvite) are the real files.
      b.onResolve({ filter: /^@\/(components|composables)\// }, ({ path }) => ({ path: resolve(src, `${path.slice(2)}.js`) }))
      b.onLoad({ filter: /.*/, namespace: 'boundary' }, ({ path }) => {
        if (path === 'element-plus') {
          return { contents: `const quiet = { name: 'ElStub', inheritAttrs: false, render: () => null }
            export const ElButton = quiet, ElDialog = quiet
            export const ElMessage = { success() {}, error() {}, warning() {} }, ElMessageBox = { confirm: async () => {} }` }
        }
        const name = path.split('/').at(-1)
        return { contents: `export function use${name[0].toUpperCase() + name.slice(1)}Store(){return globalThis.roleListTestStores.${name}}` }
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
  constructor(tag = '') { super(); Object.assign(this, { tagName: tag.toUpperCase(), style: {}, children: [] }) }
  focus() { document.activeElement = this }
}
before(() => {
  globalThis.window = Object.assign(new Host(), { performance: globalThis.performance }); globalThis.document = new Host(); document.body = new Host('body')
})
after(() => { apps.forEach(app => app.unmount()); Object.assign(globalThis, windows); delete globalThis.roleListTestStores })
const remove = el => { if (el.parent) el.parent.children = el.parent.children.filter(c => c !== el) }
const renderer = createRenderer({
  createElement: tag => new Host(tag), createComment: () => new Host(), createText: text => Object.assign(new Host(), { text }),
  setText: (n, text) => { n.text = text }, setElementText: (n, text) => { n.text = text }, patchProp: (n, k, _, v) => { if (!k.startsWith('on')) n[k] = v },
  insert(el, parent, anchor) { remove(el); const pos = parent.children.indexOf(anchor); parent.children.splice(pos === -1 ? parent.children.length : pos, 0, el); el.parent = parent },
  remove, parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1],
  insertStaticContent(text, parent, anchor) { const el = Object.assign(new Host(), { text }); parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el); el.parent = parent; return [el, el] }
})

// What the backend returns for a user's roles. Recency: Noor, Pax, Mira; Odile and
// Quill have never been spoken to. "Mira the hidden" is a removed conversation.
const seats = () => [
  { id: 1, name: 'Mira', kind: 'regular', lastMessageAt: '2026-01-03T10:00:00', lastMessage: '（擦着杯子）今晚想喝点什么？', lastMessageSender: 'role' },
  { id: 2, name: 'Noor', kind: 'regular', lastMessageAt: '2026-01-05T10:00:00', lastMessage: 'f(x) = (a+b)*c*d', lastMessageSender: 'user' },
  { id: 3, name: 'Odile', kind: 'regular', greeting: '*waves* Sit anywhere.', presence: 'reading', tone: 'dry' },
  { id: 4, name: 'Pax', kind: 'guest', lastMessageAt: '2026-01-04T10:00:00', lastMessage: '(unknown) *line*' },
  { id: 5, name: 'Mira the hidden', kind: 'regular', conversationHidden: true, lastMessageAt: '2026-01-06T10:00:00', lastMessage: '今晚见', lastMessageSender: 'role' },
  { id: 6, name: 'Quill', kind: 'guest', presence: 'polishing glasses', tone: 'warm' }
]
const everyone = ['Noor', 'Mira', 'Odile', 'Pax', 'Quill']

async function mount({ roles = seats(), locale = 'en' } = {}) {
  apps.splice(0).forEach(app => app.unmount())
  document.activeElement = null
  const calls = []
  stores = globalThis.roleListTestStores = {
    companion: reactive({
      roles, activeRoleId: 2, isLoading: false, growth: null, clearingRoleIds: [],
      ownerEpoch: 0, isInviting: false, isUserCurrent: () => true,
      get conversationRoles() { return this.roles.filter(r => !r.conversationHidden) },
      get activeRole() { return this.conversationRoles.find(r => r.id === this.activeRoleId) || null },
      isRoleBusy: () => false,
      async ensureInitialized(userId) { calls.push(['init', userId]) },
      async setActiveRole(userId, roleId) { calls.push(['select', userId, roleId]); this.activeRoleId = roleId },
      async adoptRole(userId, roleId) { calls.push(['adopt', userId, roleId]) },
      async fetchRoles(userId) { calls.push(['fetch', userId]) },
      async clearConversation(userId, roleId) { calls.push(['clear', userId, roleId]) }
    }),
    user: reactive({ currentUser: { id: 7 } }),
    settings: { format: () => '' }
  }
  const root = h(View), app = renderer.createApp({ render: () => root }), host = new Host('main')
  app.provide('toggleList', () => calls.push(['list']))
  app.use(createI18n({ legacy: false, locale, messages: { en, zh }, missingWarn: false, fallbackWarn: false }))
  app.mount(host); apps.push(app); await paint()

  function walk(node, result = []) {
    if (!node || typeof node !== 'object') return result
    result.push(node)
    if (node.component) walk(node.component.subTree, result)
    else if (Array.isArray(node.children)) node.children.flat(Infinity).forEach(n => walk(n, result))
    return result
  }
  async function paint() { await nextTick(); await new Promise(done => setImmediate(done)); await nextTick() }
  const nodes = type => walk(root).filter(n => n.type === type)
  const text = n => typeof n?.children === 'string' ? n.children : (Array.isArray(n?.children) ? n.children.map(text).join(' ') : '')
  const cls = n => typeof n.props?.class === 'string' ? n.props.class : ''
  const press = async node => { await node.props.onClick({ currentTarget: { focus() {} }, stopPropagation() {}, preventDefault() {} }); await paint() }
  const input = () => nodes('input').find(n => n.props?.type === 'search')
  // The rows on screen, top to bottom, exactly as rendered.
  const rows = () => nodes('button').filter(n => cls(n).includes('role-row-main')).map(node => {
    const inside = walk(node), preview = inside.find(n => cls(n).includes('font-body-md'))
    return { node, name: inside.find(n => cls(n).includes('font-label-md')).children, text: preview.children,
      title: preview.props.title ?? null, current: node.props['aria-current'] === 'true' }
  })
  return {
    calls, paint, nodes, press, input, rows, host,
    names: () => rows().map(row => row.name),
    shown: () => rows().map(row => [row.name, row.text, row.title]),
    async type(value) { input().props['onUpdate:modelValue'](value); await paint() },
    cross: () => nodes('button').find(n => n.props?.['aria-label'] && /clear search|清除搜索/i.test(n.props['aria-label'])),
    reset: () => nodes('button').find(n => !n.props?.['aria-label'] && /^(clear search|清除搜索)$/i.test(text(n).trim())),
    text: () => walk(root).filter(n => typeof n.children === 'string').map(n => n.children).join(' ')
  }
}

test('each row shows the last line actually said, read by who said it, with the original as tooltip', async () => {
  const v = await mount({ locale: 'zh' })
  assert.deepEqual(v.shown(), [
    ['Noor', '你：f(x) = (a+b)*c*d', '你：f(x) = (a+b)*c*d'],
    ['Mira', '今晚想喝点什么？', '（擦着杯子）今晚想喝点什么？'],
    ['Odile', '开场白：Sit anywhere.', '开场白：*waves* Sit anywhere.'],
    ['Pax', '(unknown) *line*', '(unknown) *line*'],
    ['Quill', '暂无消息', null]
  ])
  // What a role is doing or sounds like is never passed off as something said.
  assert.doesNotMatch(v.text(), /reading|dry|polishing glasses|warm/)
  assert.deepEqual(v.rows().map(row => row.current), [true, false, false, false, false])
})

test('both languages carry every new label', async () => {
  const english = await mount({ locale: 'en' })
  assert.deepEqual(english.shown().map(row => row[1]),
    ['You: f(x) = (a+b)*c*d', '今晚想喝点什么？', 'Opening line: Sit anywhere.', '(unknown) *line*', 'No messages yet'])
  assert.equal(english.input().props['aria-label'], 'Search characters or messages')
  assert.equal(english.input().props.placeholder, 'Search characters or messages')
  await english.type('zzz')
  assert.equal(english.cross().props['aria-label'], 'Clear search')
  assert.match(english.text(), /No conversations match “zzz”/)
  assert.ok(english.reset(), 'a labelled way out of an empty result')

  const chinese = await mount({ locale: 'zh' })
  assert.equal(chinese.input().props['aria-label'], '搜索角色或消息')
  assert.equal(chinese.input().props.placeholder, '搜索角色或消息')
  await chinese.type('zzz')
  assert.equal(chinese.cross().props['aria-label'], '清除搜索')
  assert.match(chinese.text(), /没有匹配“zzz”的会话/)
  assert.ok(chinese.reset())
  // A key missing from either language would be rendered as its own name.
  for (const view of [english, chinese]) assert.doesNotMatch(view.text(), /noLineYet|noMatch|clearSearch/)
})

test('search narrows by name, narration and the shown line without reordering, selecting or requesting', async () => {
  const v = await mount()
  assert.deepEqual(v.names(), everyone)
  assert.equal(v.cross(), undefined, 'nothing to clear yet')
  const expectations = [
    ['MIRA', ['Mira']], // a name, any case — and not the removed conversation of the same name
    ['擦着杯子', ['Mira']], // words that only exist inside narration
    ['今晚', ['Mira']], // the removed conversation also says 今晚, and stays out
    ['sit anywhere', ['Odile']], // a greeting shown as the row's line
    ['(a+b)', ['Noor']], // the user's own brackets
    ['*line*', ['Pax']], // a line of unknown sender, as written
    ['i', ['Mira', 'Odile', 'Pax', 'Quill']] // several matches keep their order and groups
  ]
  for (const [query, names] of expectations) {
    await v.type(query)
    assert.deepEqual(v.names(), names, query)
  }
  assert.equal(stores.companion.activeRoleId, 2, 'the selected character is untouched, shown or not')
  assert.deepEqual(v.calls, [['init', 7]], 'typing asks the server for nothing and selects nothing')

  // A selected row that still matches keeps its marker.
  await v.type('noor')
  assert.deepEqual(v.rows().map(row => [row.name, row.current]), [['Noor', true]])

  await v.press(v.cross())
  assert.deepEqual(v.names(), everyone, 'the original order comes back')
  assert.equal(v.input().el.value, '')
  assert.equal(document.activeElement, v.input().el, 'clearing returns focus to the field')
  assert.equal(v.cross(), undefined)
})

test('no match has its own message and way back, and a group with no match takes no space', async () => {
  const v = await mount()
  assert.match(v.text(), /In the seats/); assert.match(v.text(), /Past encounters/)

  await v.type('pax')
  assert.deepEqual(v.names(), ['Pax'])
  assert.doesNotMatch(v.text(), /In the seats/); assert.match(v.text(), /Past encounters/)
  await v.type('noor')
  assert.match(v.text(), /In the seats/); assert.doesNotMatch(v.text(), /Past encounters/)
  assert.equal(v.reset(), undefined)

  await v.type('  no such thing  ')
  assert.deepEqual(v.names(), [])
  assert.doesNotMatch(v.text(), /In the seats|Past encounters/)
  assert.match(v.text(), /No conversations match “no such thing”/)
  assert.doesNotMatch(v.text(), /No conversations yet/, 'an empty result is not an empty list')
  await v.press(v.reset())
  assert.deepEqual(v.names(), everyone)
  assert.equal(document.activeElement, v.input().el)
  assert.doesNotMatch(v.text(), /No conversations match/)

  // With no conversation at all, the list's own empty state stands, whatever is typed.
  const none = await mount({ roles: seats().filter(role => role.conversationHidden) })
  await none.type('mira')
  assert.deepEqual(none.names(), [])
  assert.match(none.text(), /No conversations yet/)
  assert.doesNotMatch(none.text(), /No conversations match/)
})

test('a row is one real button beside its actions, never a button inside a button', async () => {
  const v = await mount()
  const buttons = []
  const visit = el => { if (el.tagName === 'BUTTON') buttons.push(el); el.children.forEach(visit) }
  visit(v.host)
  // Five rows, each with its delete button; the two guests also have Adopt.
  assert.ok(buttons.length >= 12)
  for (const button of buttons) {
    for (let el = button.parent; el; el = el.parent) {
      assert.notEqual(el.tagName, 'BUTTON', 'a button nested in a button')
      assert.notEqual(el.role, 'button', 'a button nested in a role=button')
    }
  }

  await v.press(v.rows().find(row => row.name === 'Mira').node)
  assert.deepEqual(v.calls.filter(call => call[0] === 'select'), [['select', 7, 1]])
  assert.deepEqual(v.rows().filter(row => row.current).map(row => row.name), ['Mira'])
  assert.deepEqual(v.names(), everyone, 'looking at a row does not move it')

  // Adopt acts on its own row and does not select it.
  const adopt = v.nodes('button').filter(n => /role-adopt-btn/.test(n.props?.class || ''))
  assert.equal(adopt.length, 2)
  await v.press(adopt[0])
  assert.deepEqual(v.calls.filter(call => call[0] === 'adopt'), [['adopt', 7, 4]])
  assert.deepEqual(v.calls.filter(call => call[0] === 'select'), [['select', 7, 1]])
})

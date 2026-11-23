import { fallbackColor, fallbackColors, membershipSignature, currentGroupRecord } from './displayFacts.mjs'
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import * as Vue from 'vue'
import { renderToString } from '@vue/server-renderer'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { compile } from '@vue/compiler-dom'
import { isGroup, presence, chatPresence, userId, memberRows, requestGate, initialOf, imageVisible } from './presentation.mjs'

const source = name => readFileSync(new URL(name, import.meta.url), 'utf8')
// Compile and execute the real shared SFC setup and template, not duplicated branches.
function component(name) {
  const { descriptor, errors } = parse(source(name), { filename: name })
  assert.deepEqual(errors, [], name)
  // Inline compilation keeps imports, refs and computed bindings in setup's
  // lexical scope, exactly as the production script-setup compiler expects.
  let script = compileScript(descriptor, { id: name, genDefaultAs: '__component', inlineTemplate: true }).content
  const bindings = []
  script = script.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g, (_, names) => {
    bindings.push(...names.split(',').map(n => n.trim().replace(/\s+as\s+/, ': ')))
    return ''
  }).replace(/import[^\n]+\n/g, '')
  const factory = new Function('Vue', 'resolveFileUrl', 'fallbackColor', 'initialOf', 'imageVisible', 'isGroup', 'presence', 'useI18n',
    `const { ${bindings.join(',')} } = Vue;\n${script}\nreturn __component`)
  return factory(Vue, url => url.startsWith('/') ? `https://backend.invalid${url}` : url, fallbackColor, initialOf, imageVisible, isGroup, presence, () => ({ t: key => key }))
}
const Dot = component('PresenceDot.vue')
const Avatar = component('IdentityAvatar.vue')

test('actual presence output: strict booleans, accessible labels, groups absent', async () => {
  for (const type of ['GROUP', 'group', 'Group']) {
    for (const value of [true, false, null]) {
      const html = await renderToString(Vue.h(Dot, { type, value }))
      assert.doesNotMatch(html, /identity-presence|chat.online|chat.offline/)
    }
  }
  for (const value of [null, undefined, 'true', 1, 'offline']) {
    const html = await renderToString(Vue.h(Dot, { value, type: 'direct' }))
    assert.doesNotMatch(html, /identity-presence|chat.offline/)
  }
  const online = await renderToString(Vue.h(Dot, { value: true, type: 'DIRECT' }))
  assert.match(online, /identity-presence online/)
  assert.match(online, /aria-label="chat.online"/)
  const offline = await renderToString(Vue.h(Dot, { value: false }))
  assert.match(offline, /aria-label="chat.offline"/)
  assert.doesNotMatch(offline, /identity-presence online/)
  assert.match(source('PresenceDot.vue'), /background: #22c55e/)
  assert.equal(chatPresence({ online: true }), true)
  assert.equal(chatPresence({ isOnline: null, online: true }), null)
})

test('avatar actual output, relative URL resolution and honest group fallback', async () => {
  const html = await renderToString(Vue.h(Avatar, { src: '/uploads/color.png', label: 'Alice', size: 32 }))
  assert.match(html, /https:\/\/backend.invalid\/uploads\/color.png/)
  assert.match(html, /alt="Alice"/)
  assert.match(html, /32px/)
  const missing = await renderToString(Vue.h(Avatar, { label: 'Team', kind: 'group' }))
  assert.match(missing, /▦/)
  assert.doesNotMatch(missing, /<img/)
})

test('actual avatar error handler retries when URL changes for same identity', async () => {
  const renderer = Vue.createRenderer({
    createElement: type => ({ type, children: [], props: {} }),
    createText: text => ({ text }), createComment: text => ({ comment: text }),
    setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text },
    parentNode: node => node.parent, nextSibling: () => null,
    insert: (node, parent) => { node.parent = parent; parent.children.push(node) },
    remove: node => { node.parent.children = node.parent.children.filter(n => n !== node) },
    patchProp: (node, key, old, value) => { node.props[key] = value }
  })
  const src = Vue.ref('/old.png')
  const root = { children: [] }
  const app = renderer.createApp({ setup: () => () => Vue.h(Avatar, { src: src.value, entityId: 7, label: 'Alice' }) })
  app.mount(root)
  let image = root.children[0].children.find(n => n.type === 'img')
  assert.ok(image)
  image.props.onError({ currentTarget: { getAttribute: key => image.props[key] } })
  await Vue.nextTick()
  assert.equal(root.children[0].children.some(n => n.type === 'img'), false)
  const oldImage = image
  src.value = '/new.png'
  await Vue.nextTick()
  image = root.children[0].children.find(n => n.type === 'img')
  assert.equal(image.props.src, 'https://backend.invalid/new.png')
  oldImage.props.onError({ currentTarget: { getAttribute: key => oldImage.props[key] } })
  await Vue.nextTick()
  assert.equal(root.children[0].children.find(n => n.type === 'img'), image)
  image.props.onError({ currentTarget: { getAttribute: key => image.props[key] } })
  await Vue.nextTick()
  assert.equal(root.children[0].children.some(n => n.type === 'img'), false)
  src.value = '/old.png'
  await Vue.nextTick()
  assert.equal(root.children[0].children.find(n => n.type === 'img').props.src, 'https://backend.invalid/old.png')
  app.unmount()
})

test('members preserve server role/UserID, latest identity and explicit unknown', () => {
  const rows = memberRows([{ id: 999, userId: 7, role: 'admin', isOnline: true, avatar: 'old.png' }], id => {
    assert.equal(id, 7)
    return { id: 7, role: 'member', nickname: 'New name', avatarUrl: '/new.png', isOnline: null }
  })
  assert.equal(rows[0].id, 7)
  assert.equal(rows[0].role, 'admin')
  assert.equal(rows[0].isOnline, null)
  assert.equal(rows[0].avatar, '/new.png')
})

test('rapid group switching and unmount discard late responses', async () => {
  const gate = requestGate()
  let selected = 'A', committed = []
  let resolveA
  const a = new Promise(resolve => { resolveA = resolve })
  const tokenA = gate.begin()
  const pending = a.then(rows => { if (gate.current(tokenA) && selected === 'A') committed = rows })
  selected = 'B'
  const tokenB = gate.begin()
  if (gate.current(tokenB)) committed = ['B member']
  resolveA(['A member'])
  await pending
  assert.deepEqual(committed, ['B member'])
  gate.dispose()
  assert.equal(gate.current(tokenB), false)
})

// Inspect complete declaration values: a negative lookahead after \s* can
// backtrack into whitespace and falsely reject `mix-blend-mode: normal`.
function assertAvatarColorPolicy(css, context) {
  for (const [, property, rawValue] of css.matchAll(/(?:^|;)\s*(filter|mix-blend-mode)\s*:\s*([^;}]*)/gi)) {
    const value = rawValue.replace(/\s*!important\s*$/i, '').trim().toLowerCase()
    if (property.toLowerCase() === 'filter') {
      assert.doesNotMatch(value, /\b(?:grayscale|contrast)\s*\(/i, context)
    } else {
      assert.equal(value, 'normal', context)
    }
  }
}
const displayFiles = ['../contact/ContactList.vue', '../chat/ChatList.vue', '../chat/GroupList.vue', '../layout/MiddlePanel.vue', '../contact/ContactDetail.vue', '../chat/GroupDetail.vue', '../layout/RightPanel.vue', '../contact/AddContactModal.vue', '../chat/AddGroupMemberModal.vue', '../layout/LeftPanel.vue', '../chat/CreateGroupModal.vue', '../chat/MessageList.vue']
test('display SFC compiler checks, avatar-only color policy and group head integration', () => {
  assert.doesNotThrow(() => assertAvatarColorPolicy('filter:none;mix-blend-mode:normal', 'neutral'))
  assert.doesNotThrow(() => assertAvatarColorPolicy('filter: none; mix-blend-mode: normal !important;', 'neutral spaced'))
  for (const css of ['filter:grayscale(100%)', 'filter: contrast(1.05)', 'filter: blur(1px) grayscale(1)', 'mix-blend-mode: luminosity']) {
    assert.throws(() => assertAvatarColorPolicy(css, 'forbidden'), assert.AssertionError)
  }
  for (const file of [...displayFiles, 'IdentityAvatar.vue', 'PresenceDot.vue']) {
    const text = source(file)
    const { descriptor, errors } = parse(text)
    assert.deepEqual(errors, [], file)
    const script = compileScript(descriptor, { id: file })
    assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename: file, id: file, compilerOptions: { bindingMetadata: script.bindings } }).errors, [], file)
    for (const style of descriptor.styles) {
      const css = style.content.replace(/\/\*[\s\S]*?\*\//g, '')
      for (const block of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
        if (/avatar/i.test(block[1])) assertAvatarColorPolicy(block[2], file)
      }
    }
  }
  const head = source('../layout/MiddlePanel.vue')
  assert.match(head, /PresenceDot class="status-dot"[^>]*:type="chatStore.activeChat.type"/)
  assert.match(head, /PresenceDot class="status-pip"[^>]*:type="chatStore.activeChat.type"/)
  assert.match(source('../chat/MessageList.vue'), /filter: grayscale\(100%\) contrast\(1.05\)/) // media untouched
})

test('creation consumes returned DTO, never selected count or fabricated presence', () => {
  const text = source('../chat/CreateGroupModal.vue')
  const created = text.slice(text.indexOf('const newGroup = response.data'), text.indexOf("ElMessage.success(t('group.createSuccess'))"))
  assert.match(created, /chatStore.mergeChats\(\[newGroup\], userId\)/)
  assert.doesNotMatch(created, /online:\s*true|selectedUsers|memberCount:\s*.*\+\s*1|members\[0\]/)
})


test('actual chat head dot markup renders neither group avatar nor title dot', async () => {
  const text = source('../layout/MiddlePanel.vue')
  const tags = [...text.matchAll(/<PresenceDot class="status-(?:dot|pip)"[^>]*\/>/g)].map(m => m[0]).join('')
  assert.equal((tags.match(/PresenceDot/g) || []).length, 2)
  const render = new Function('Vue', compile(`<div>${tags}</div>`, { mode: 'function' }).code)(Vue)
  const Head = { components: { PresenceDot: Dot }, setup: props => ({ chatStore: { activeChat: props.chat }, chatPresence }), props: ['chat'], render }
  for (const type of ['GROUP', 'group']) {
    const html = await renderToString(Vue.h(Head, { chat: { type, online: true } }))
    assert.doesNotMatch(html, /identity-presence|chat.online|chat.offline/)
  }
  const html = await renderToString(Vue.h(Head, { chat: { type: 'direct', online: true } }))
  assert.equal((html.match(/aria-label="chat.online"/g) || []).length, 2)
})

test('creation output retains server memberCount despite UI selection size', () => {
  const text = source('../chat/CreateGroupModal.vue')
  const body = text.slice(text.indexOf('const newGroup = response.data'), text.indexOf("ElMessage.success(t('group.createSuccess'))"))
  const dto = { id: 4, type: 'GROUP', memberCount: 2, members: [{ userId: 1 }, { userId: 2 }], avatar: '/team.png' }
  let emitted
  const chatStore = { chats: [], mergeChats: rows => { assert.deepEqual(rows, [dto]); chatStore.chats.push({ ...rows[0] }) } }
  new Function('response', 'chatStore', 'userId', 'emit', body)({ data: dto }, chatStore, 1, (event, group) => { emitted = group })
  assert.equal(emitted.memberCount, 2)
  assert.equal(emitted.avatar, '/team.png')
  assert.equal(Object.hasOwn(emitted, 'online'), false)
})

test('partial identities preserve server fields; explicit clears and group permissions are authoritative', () => {
  const member = { userId: 7, nickname: 'Server', avatar: '/real.png', avatarUrl: '/real.png', isOnline: true, role: 'member', isAdmin: false }
  const partial = memberRows([member], () => ({ nickname: undefined, avatar: undefined, avatarUrl: undefined, isOnline: false, role: 'owner', isAdmin: true }))[0]
  assert.equal(partial.nickname, 'Server')
  assert.equal(partial.avatarUrl, '/real.png')
  assert.equal(partial.avatar, '/real.png')
  assert.equal(partial.isOnline, false)
  assert.equal(partial.role, 'member')
  assert.equal(partial.isAdmin, false)
  for (const clear of ['', null]) {
    const row = memberRows([member], () => ({ nickname: clear, avatarUrl: clear, isOnline: null }))[0]
    assert.equal(row.nickname, clear)
    assert.equal(row.avatarUrl, clear)
    assert.equal(row.avatar, clear)
    assert.equal(row.isOnline, null)
    const legacy = memberRows([member], () => ({ avatar: clear }))[0]
    assert.equal(legacy.avatarUrl, clear)
  }
  assert.equal(memberRows([member], () => ({ nickname: 'Only name' }))[0].isOnline, true)
})

// Execute the real parent SFCs with inline templates and a memory renderer.
// Only external stores/endpoints are replaced; load/watch/render stay production code.
function parentComponent(file, env) {
  const { descriptor } = parse(source(file), { filename: file })
  let script = compileScript(descriptor, { id: file, genDefaultAs: '__component', inlineTemplate: true }).content
  const bindings = []
  script = script.replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g, (_, names) => {
    bindings.push(...names.split(',').map(n => n.trim().replace(/\s+as\s+/, ': ')))
    return ''
  }).replace(/import[^\n]+\n/g, '')
  return new Function('Vue', 'env', `const { ${bindings.join(',')} } = Vue; const { ${Object.keys(env).join(',')} } = env;\n${script}\nreturn __component`)(Vue, env)
}
function memoryRenderer() {
  return Vue.createRenderer({
    createElement: type => ({ type, children: [], props: {} }),
    createText: text => ({ text }), createComment: comment => ({ comment }),
    setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text; node.children = [] },
    parentNode: node => node.parent, nextSibling: node => {
      const children = node.parent?.children || []
      return children[children.indexOf(node) + 1] || null
    },
    insert: (node, parent, anchor) => {
      if (node.parent) node.parent.children = node.parent.children.filter(n => n !== node)
      node.parent = parent
      const index = anchor ? parent.children.indexOf(anchor) : -1
      if (index < 0) parent.children.push(node); else parent.children.splice(index, 0, node)
    },
    remove: node => { node.parent.children = node.parent.children.filter(n => n !== node) },
    patchProp: (node, key, old, value) => { node.props[key] = value }
  })
}
const renderedText = node => [node.text || '', ...(node.children || []).map(renderedText)].join(' ')
const settleUI = async () => { await Promise.resolve(); await Promise.resolve(); await Vue.nextTick() }

for (const file of ['../chat/GroupDetail.vue', '../layout/RightPanel.vue']) {
  test(`${file}: A→B→A same group rejects late rows/errors and unmounted responses in final display`, async () => {
    const userStore = Vue.reactive({ currentUser: { id: 'A' } })
    let epoch = 0
    Vue.watch(() => userStore.currentUser?.id, () => { epoch++ }, { flush: 'sync' })
    const errors = [], requests = [], infos = []
    const deferred = queue => new Promise((resolve, reject) => queue.push({ resolve, reject }))
    const contactStore = {
      captureIdentityRevision: () => ({ epoch }),
      isIdentityRevisionCurrent: token => token.epoch === epoch,
      ingestUserSnapshots: (rows, token) => token.epoch === epoch,
      getUserIdentity: () => null
    }
    const chatStore = Vue.reactive({ activeChat: { id: 9, type: 'GROUP', name: 'Group' },
      isChatPinned: () => false, isChatMuted: () => false })
    const empty = { render: () => null }
    const Parent = parentComponent(file, {
      useContactStore: () => contactStore, useUserStore: () => userStore, useChatStore: () => chatStore,
      useRouter: () => ({ push() {} }), useI18n: () => ({ t: key => key }),
      groupAPI: { getGroupMembers: () => deferred(requests), getGroupById: () => deferred(infos) },
      memberRows, requestGate, isGroup, presence, chatPresence, membershipSignature, currentGroupRecord,
      sameId: (a, b) => a != null && b != null && String(a) === String(b),
      dayjs: () => ({ format: () => '' }), ElMessage: { error: value => errors.push(value) },
      IdentityAvatar: Avatar, PresenceDot: Dot, AddGroupMemberModal: empty, SearchMessagesModal: empty
    })
    const root = { children: [] }
    const app = memoryRenderer().createApp({ setup: () => () => Vue.h(Parent, { group: chatStore.activeChat }) })
    app.provide('toggleRightPanel', () => {})
    app.config.globalProperties.$t = key => key
    app.config.warnHandler = () => {}
    app.mount(root)
    const resolve = (index, name) => {
      requests[index].resolve({ data: [{ userId: 7, nickname: name, role: 'member' }] })
      infos[index]?.resolve({ data: { description: name } })
    }
    assert.equal(requests.length, 1)
    userStore.currentUser = { id: 'B' }
    await settleUI()
    assert.equal(requests.length, 2)
    resolve(1, 'Bravo')
    await settleUI()
    assert.match(renderedText(root), /Bravo/)
    userStore.currentUser = { id: 'A' }
    await settleUI()
    assert.equal(requests.length, 3)
    assert.doesNotMatch(renderedText(root), /Bravo/)
    resolve(0, 'StaleA')
    await settleUI()
    assert.doesNotMatch(renderedText(root), /StaleA|Bravo/)
    resolve(2, 'FreshA')
    await settleUI()
    assert.match(renderedText(root), /FreshA/)
    userStore.currentUser = { id: 'B' }
    await settleUI()
    userStore.currentUser = { id: 'A' }
    await settleUI()
    requests[3].reject(new Error('late B'))
    infos[3]?.reject(new Error('late B info'))
    await settleUI()
    assert.deepEqual(errors, [])
    assert.doesNotMatch(renderedText(root), /group.loadMembersFailed/)
    if (file.includes('GroupDetail')) assert.match(renderedText(root), /common.loading/)
    resolve(4, 'FinalA')
    await settleUI()
    assert.match(renderedText(root), /FinalA/)
    assert.doesNotMatch(renderedText(root), /StaleA|Bravo|FreshA/)
    userStore.currentUser = { id: 'B' }
    await settleUI()
    app.unmount()
    resolve(5, 'Gone')
    await settleUI()
    assert.doesNotMatch(renderedText(root), /Gone/)
  })
}

test('actual fallback SFC is colored, stable across label and numeric/string IDs; photographs untouched', async () => {
  const html = label => renderToString(Vue.h(Avatar, { entityId: 7, label }))
  const first = await html('Alice'), renamed = await html('Renamed')
  const stringId = await renderToString(Vue.h(Avatar, { entityId: '7', label: 'Alice' }))
  const color = fallbackColor(7, 'user')
  for (const output of [first, renamed, stringId]) {
    assert.match(output, /data-avatar-fallback="true"/)
    assert.ok(output.includes(`background-color:${color}`))
    assert.doesNotMatch(output, /<img/)
  }
  for (const color of fallbackColors) {
    const rgb = color.slice(1).match(/../g).map(v => parseInt(v, 16) / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
    assert.ok(1.05 / (.2126 * rgb[0] + .7152 * rgb[1] + .0722 * rgb[2] + .05) >= 4.5)
  }
  const photo = await renderToString(Vue.h(Avatar, { entityId: 7, src: '/real.png', label: 'Alice' }))
  assert.doesNotMatch(photo, /background-color|data-avatar-fallback/)
})

test('actual GroupDetail refreshes GET members, roles and total after membership events, not identity ticks', async () => {
  const userStore = Vue.reactive({ currentUser: { id: 1 } })
  const record = Vue.reactive({ id: 9, type: 'GROUP', name: 'Team', memberCount: 1, members: [{ userId: 7, role: 'member' }] })
  const chatStore = Vue.reactive({ chats: [record], activeChat: record, isChatPinned: () => false, isChatMuted: () => false })
  const identities = Vue.reactive({})
  let calls = 0, infoCalls = 0
  let backend = [{ userId: 7, nickname: 'Alice', role: 'member' }]
  const contactStore = {
    captureIdentityRevision: () => 1, isIdentityRevisionCurrent: token => token === 1,
    ingestUserSnapshots: rows => { for (const row of rows) identities[row.userId] = { nickname: row.nickname }; return true },
    getUserIdentity: id => identities[id]
  }
  const Parent = parentComponent('../chat/GroupDetail.vue', {
    useContactStore: () => contactStore, useUserStore: () => userStore, useChatStore: () => chatStore,
    useRouter: () => ({ push() {} }), useI18n: () => ({ t: key => key }),
    groupAPI: {
      getGroupMembers: async () => { calls++; return { data: backend.map(row => ({ ...row })) } },
      getGroupById: async () => { infoCalls++; return { data: { memberCount: backend.length } } }
    },
    memberRows, requestGate, membershipSignature, currentGroupRecord,
    dayjs: () => ({ format: () => '' }), IdentityAvatar: Avatar, PresenceDot: Dot
  })
  const root = { children: [] }
  const app = memoryRenderer().createApp({ setup: () => () => Vue.h(Parent, { group: record }) })
  app.config.globalProperties.$t = (key, values) => values?.n != null ? `${key}:${values.n}` : key
  app.mount(root)
  await settleUI()
  assert.equal(calls, 1)
  backend = [{ userId: 7, nickname: 'Alice', role: 'admin' }, { userId: 8, nickname: 'Bob', role: 'member' }]
  record.members = backend.map(row => ({ ...row }))
  record.memberCount = 2
  await settleUI(); await settleUI()
  assert.equal(calls, 2)
  assert.match(renderedText(root), /Bob/)
  assert.match(renderedText(root), /group.admin/)
  assert.match(renderedText(root), /group.memberTotal:2/)
  backend[0].role = 'member'
  record.members[0].role = 'member' // same count: role alone must refresh
  await settleUI(); await settleUI()
  assert.equal(calls, 3)
  assert.doesNotMatch(renderedText(root), /group.admin/)
  record.members[0].isAdmin = false
  await settleUI(); await settleUI()
  assert.equal(calls, 4)
  for (let i = 0; i < 3; i++) {
    identities[7] = { nickname: `Profile${i}`, isOnline: !!(i % 2), role: 'owner' }
    record.members[0].isOnline = !!(i % 2)
    record.members[0].avatarUrl = `/profile${i}.png`
    record.members = [...record.members].reverse()
    await settleUI()
  }
  assert.equal(calls, 4)
  assert.equal(infoCalls, 4)
  assert.doesNotMatch(renderedText(root), /group.owner:/)
  backend = [backend[0]]
  record.members = [{ userId: 7, role: 'member', isAdmin: false }]
  record.memberCount = 1
  await settleUI(); await settleUI()
  assert.equal(calls, 5)
  assert.doesNotMatch(renderedText(root), /Bob/)
  assert.match(renderedText(root), /group.memberTotal:1/)
  app.unmount()
})

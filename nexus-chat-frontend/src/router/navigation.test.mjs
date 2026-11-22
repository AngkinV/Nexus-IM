import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@babel/parser'
import { createRouter, createMemoryHistory } from 'vue-router'
import { installNavigation, mainLocation, readMainState, safeAppPath, loginDestination,
  createAuthNavigationGuard, canGoBackOnEscape, hasBlockingOverlay, createNavigationIntent } from './navigation.js'
import { useTavernEntrance } from '../composables/useTavernEntrance.js'

function configuredRoutes() {
  const source = readFileSync(new URL('./index.js', import.meta.url), 'utf8')
  const ast = parse(source, { sourceType: 'module' })
  let records
  const visit = node => {
    if (!node || typeof node !== 'object') return
    if (node.type === 'CallExpression' && node.callee?.name === 'createRouter') {
      records = node.arguments[0].properties.find(property => property.key.name === 'routes').value.elements
    }
    for (const value of Object.values(node)) {
      if (Array.isArray(value)) value.forEach(visit)
      else if (value && typeof value === 'object') visit(value)
    }
  }
  visit(ast)
  assert.ok(records, 'Read actual production route declarations, not a hand-written mirror')
  return records.map(record => {
    const result = { component: {} }
    for (const property of record.properties) {
      const key = property.key.name || property.key.value
      if (['path', 'name', 'redirect'].includes(key)) result[key] = property.value.value
      if (key === 'meta') result.meta = Object.fromEntries(property.value.properties.map(item => [item.key.name, item.value.value]))
    }
    return result
  })
}

function settingsKeyHandler(document) {
  const source = readFileSync(new URL('../views/Settings.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup[^>]*>([\s\S]*?)<\/script>/)[1]
  const ast = parse(script, { sourceType: 'module' })
  const declaration = ast.program.body.flatMap(node => node.declarations || []).find(node => node.id?.name === 'onKeydown')
  assert.ok(declaration, 'Execute the actual settings keyboard handler')
  const calls = { back: 0, blur: 0, focus: 0 }
  const input = { tagName: 'INPUT', blur: () => { calls.blur += 1; document.activeElement = null },
    focus: () => { calls.focus += 1; document.activeElement = input } }
  const searchQuery = { value: '' }, searchInput = { value: input }
  const handler = new Function('goBack', 'searchInput', 'searchQuery', 'document', 'hasBlockingOverlay', 'canGoBackOnEscape',
    `return (${script.slice(declaration.init.start, declaration.init.end)})`)(
      () => { calls.back += 1 }, searchInput, searchQuery, document,
      () => hasBlockingOverlay(document), event => canGoBackOnEscape(event, document))
  return { handler, calls, input, searchQuery }
}

function nativeMetadataHistory() {
  const history = createMemoryHistory()
  let position = 0
  const pages = ['']
  const push = history.push.bind(history), replace = history.replace.bind(history)
  history.push = (to, state) => { push(to, state); position += 1; pages.splice(position); pages[position] = to }
  history.replace = (to, state) => { replace(to, state); pages[position] = to }
  history.listen((_to, _from, info) => { position += info.delta })
  Object.defineProperty(history, 'state', { get: () => ({ position, current: pages[position], back: pages[position - 1] || null }) })
  return history
}

function fixture(t, { loggedIn = true, native = false, delayedInstall = false } = {}) {
  const values = new Map(loggedIn ? [['token', 'test-session']] : [])
  const storage = { getItem: key => values.get(key) || null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
  const history = native ? nativeMetadataHistory() : createMemoryHistory()
  const router = createRouter({ history, routes: configuredRoutes() })
  const options = { authenticated: () => !!storage.getItem('token') && !storage.getItem('token').startsWith('mock-token-') }
  let navigation = delayedInstall ? null : installNavigation(router, options)
  router.beforeEach(createAuthNavigationGuard(router, storage))
  const install = () => navigation = installNavigation(router, options)
  t.after(() => { navigation?.dispose(); history.destroy() })
  return { router, storage, install, get navigation() { return navigation } }
}

async function move(router, delta) {
  const changed = new Promise(resolve => { const stop = router.afterEach(() => { stop(); resolve() }) })
  router.go(delta)
  await changed
}

for (const view of ['chats', 'contacts', 'groups', 'ai', 'companion']) {
  test(`${view} survives settings and a remount snapshot`, async t => {
    const { router, navigation } = fixture(t)
    await router.push(mainLocation(view, { contactId: '3', groupId: '4' }, { view: 'ai', chatId: 12 }))
    const original = router.currentRoute.value.fullPath
    await router.push('/settings')
    assert.equal(await navigation.back(), true)
    assert.equal(router.currentRoute.value.fullPath, original)
    assert.equal(readMainState(router.currentRoute.value.query).view, view)
    assert.equal(readMainState(router.currentRoute.value.query).contactId, 3)
  })
}

for (const path of ['/settings', '/profile', '/user/30']) {
  test(`direct-open ${path} returns safely without history`, async t => {
    const { router, navigation } = fixture(t)
    await router.push(path)
    await navigation.back()
    assert.equal(router.currentRoute.value.name, 'Main')
    assert.equal(readMainState(router.currentRoute.value.query).view, 'chats')
    const current = router.currentRoute.value.fullPath
    await navigation.back()
    assert.equal(router.currentRoute.value.fullPath, current)
  })
}

test('nested user profile returns to the original profile tab then source module', async t => {
  const { router, navigation } = fixture(t)
  await router.push(mainLocation('contacts', { contactId: '8' }))
  await router.push('/profile?tab=social')
  await router.push('/user/30')
  await navigation.back()
  assert.equal(router.currentRoute.value.name, 'Profile')
  assert.equal(router.currentRoute.value.query.tab, 'social')
  await navigation.back()
  assert.equal(readMainState(router.currentRoute.value.query).view, 'contacts')
  assert.equal(readMainState(router.currentRoute.value.query).contactId, 8)
})

test('duplicate same-page entries are skipped rather than looping through profile tabs', async t => {
  const { router, navigation } = fixture(t)
  await router.push(mainLocation('ai'))
  await router.push('/profile?tab=overview')
  await router.push('/profile?tab=security')
  await navigation.back()
  assert.equal(router.currentRoute.value.name, 'Main')
  assert.equal(readMainState(router.currentRoute.value.query).view, 'ai')
})

test('replace preserves profile origin and does not add a return level', async t => {
  const { router, navigation } = fixture(t)
  await router.push(mainLocation('groups'))
  await router.push('/profile?tab=overview')
  await router.replace('/profile?tab=social')
  await router.push('/settings')
  await navigation.back()
  assert.equal(router.currentRoute.value.query.tab, 'social')
  await navigation.back()
  assert.equal(readMainState(router.currentRoute.value.query).view, 'groups')
})

test('Main production code derives the module from the URL rather than resetting local state', () => {
  const source = readFileSync(new URL('../views/Main.vue', import.meta.url), 'utf8')
  assert.match(source, /const activeView\s*=\s*computed\(\(\)\s*=>\s*readMainState\(route\.query\)\.view\)/)
  assert.doesNotMatch(source, /activeView\.value\s*=[^=]/)
})

test('Setup production code has no synthetic user or token writes', () => {
  const source = readFileSync(new URL('../views/Setup.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /localStorage\.setItem\(\s*['"](?:token|user)['"]/)
  assert.match(source, /userAPI\.updateProfile\(id,\s*\{\s*nickname:\s*name\s*\}\)/)
  assert.equal(configuredRoutes().find(route => route.name === 'Setup').meta.requiresAuth, true)
})

for (const page of ['Settings', 'Profile', 'UserProfile']) {
  test(`${page} production back button uses the tested safe navigation helper`, () => {
    const source = readFileSync(new URL(`../views/${page}.vue`, import.meta.url), 'utf8')
    assert.match(source, /const goBack\s*=\s*\(\)\s*=>\s*returnToSource\(router\)/)
    assert.doesNotMatch(source, /router\.back\(\)/)
  })
}

test('module return consumes a tavern entry instead of adding a back-and-forth loop', async t => {
  const { router, navigation } = fixture(t)
  await router.push(mainLocation('chats'))
  await router.push(mainLocation('ai'))
  await router.push(mainLocation('companion', {}, { view: 'ai' }))
  await navigation.back('ai')
  assert.equal(readMainState(router.currentRoute.value.query).view, 'ai')
  await move(router, -1)
  assert.equal(readMainState(router.currentRoute.value.query).view, 'chats')
})

test('browser history back and forward restore module query state', async t => {
  const { router } = fixture(t)
  await router.push(mainLocation('chats'))
  await router.push(mainLocation('ai'))
  await router.push(mainLocation('companion', {}, { view: 'ai', chatId: 5 }))
  await move(router, -1)
  assert.equal(readMainState(router.currentRoute.value.query).view, 'ai')
  await move(router, 1)
  assert.equal(readMainState(router.currentRoute.value.query).view, 'companion')
  assert.equal(readMainState(router.currentRoute.value.query).returnChat, '5')
})

test('native history state can restore origin after in-memory provenance is lost', async t => {
  const f = fixture(t, { native: true, delayedInstall: true })
  await f.router.push(mainLocation('companion', {}, { view: 'ai' }))
  await f.router.push('/settings')
  f.install()
  await f.navigation.back()
  assert.equal(readMainState(f.router.currentRoute.value.query).view, 'companion')
})

test('rapid return clicks share one navigation and do not skip another page', async t => {
  const { router, navigation } = fixture(t)
  await router.push(mainLocation('chats'))
  await router.push('/profile')
  await router.push('/settings')
  const first = navigation.back(), second = navigation.back()
  assert.equal(first, second)
  await first
  assert.equal(router.currentRoute.value.name, 'Profile')
})

test('login replacement does not leave an authenticated login page as a return target', async t => {
  const { router, storage, navigation } = fixture(t, { loggedIn: false })
  await router.push('/login')
  storage.setItem('token', 'test-session')
  await router.replace(loginDestination(router, {}))
  await router.push('/profile')
  await navigation.back()
  assert.equal(router.currentRoute.value.name, 'Main')
  await navigation.back()
  assert.equal(router.currentRoute.value.name, 'Main')
})

test('unauthenticated deep links retain a safe requested destination', async t => {
  const { router, storage } = fixture(t, { loggedIn: false })
  await router.push('/user/30')
  assert.equal(router.currentRoute.value.name, 'Login')
  assert.equal(router.currentRoute.value.query.redirect, '/user/30')
  storage.setItem('token', 'test-session')
  await router.replace(loginDestination(router, router.currentRoute.value.query))
  assert.equal(router.currentRoute.value.name, 'UserProfile')
})

test('setup requires real authentication and does not act as a login bypass', async t => {
  const { router, storage } = fixture(t, { loggedIn: false })
  storage.setItem('token', 'mock-token-old-prototype')
  await router.push('/setup')
  assert.equal(storage.getItem('token'), null)
  assert.equal(router.currentRoute.value.name, 'Login')
  assert.equal(router.currentRoute.value.query.redirect, '/setup')
  storage.setItem('token', 'test-session')
  await router.replace(loginDestination(router, router.currentRoute.value.query))
  assert.equal(router.currentRoute.value.name, 'Setup')
})

test('logout removes private return targets and back remains on login', async t => {
  const { router, storage, navigation } = fixture(t)
  await router.push(mainLocation('companion', {}, { view: 'ai' }))
  await router.push('/settings')
  storage.removeItem('token')
  await router.replace('/login')
  await navigation.back()
  assert.equal(router.currentRoute.value.name, 'Login')
})

for (const target of ['https://example.invalid/main', '//example.invalid/main', 'javascript:alert(1)', '/unknown', '/login', '/setup', ['/main']]) {
  test(`unsafe or ineligible return target ${JSON.stringify(target)} is refused`, t => {
    const { router } = fixture(t)
    assert.equal(safeAppPath(router, target), null)
    if (target !== '/setup') assert.equal(router.resolve(loginDestination(router, { redirect: target })).name, 'Main')
  })
}

test('hash route spelling and malformed module state are handled explicitly', t => {
  const { router } = fixture(t)
  assert.equal(safeAppPath(router, '#/main?view=ai'), '/main?view=ai')
  assert.equal(readMainState({ view: ['ai'], returnView: 'unknown', contactId: ['3'], groupId: '-1' }).view, 'chats')
  assert.equal(readMainState({ contactId: ['3'], groupId: '-1' }).contactId, null)
  assert.equal(readMainState({ groupId: '-1' }).groupId, null)
})

const doc = (elements = [], activeElement = null) => ({ querySelectorAll: () => elements, activeElement,
  defaultView: { getComputedStyle: element => element.style || {} } })
const overlay = (visible = true) => ({ getBoundingClientRect: () => ({ width: visible ? 100 : 0, height: visible ? 100 : 0 }),
  style: { display: visible ? 'block' : 'none', visibility: 'visible' } })

for (const type of ['INPUT', 'TEXTAREA', 'SELECT']) {
  test(`Escape does not leave a page while editing ${type}`, () => {
    assert.equal(canGoBackOnEscape({ key: 'Escape', target: { tagName: type } }, doc()), false)
  })
}

test('Escape respects composition, prevention and semantic controls', () => {
  assert.equal(canGoBackOnEscape({ key: 'Escape', isComposing: true }, doc()), false)
  assert.equal(canGoBackOnEscape({ key: 'Escape', defaultPrevented: true }, doc()), false)
  assert.equal(canGoBackOnEscape({ key: 'Escape', target: { isContentEditable: true } }, doc()), false)
  assert.equal(canGoBackOnEscape({ key: 'Escape', target: { getAttribute: () => 'slider' } }, doc()), false)
})

test('visible overlays own Escape but retained hidden dialog DOM does not block it', () => {
  assert.equal(hasBlockingOverlay(doc([overlay(true)])), true)
  assert.equal(canGoBackOnEscape({ key: 'Escape' }, doc([overlay(true)])), false)
  assert.equal(hasBlockingOverlay(doc([overlay(false)])), false)
  assert.equal(canGoBackOnEscape({ key: 'Escape' }, doc([overlay(false)])), true)
})

test('new navigation intent invalidates old callbacks without reviving on A-B-A', () => {
  const intent = createNavigationIntent()
  const first = intent.capture()
  intent.invalidate()
  const next = intent.capture()
  assert.equal(intent.current(first), false)
  assert.equal(intent.current(next), true)
})

test('a delayed tavern commit cannot override a newer main-module destination', async t => {
  const { router } = fixture(t)
  const intent = createNavigationIntent(), ticket = intent.capture()
  const entrance = useTavernEntrance()
  entrance.finish()
  entrance.registerHost()
  t.after(() => { entrance.finish(); entrance.unregisterHost() })
  await router.push(mainLocation('ai'))
  entrance.enter({ onCommit: () => { if (intent.current(ticket)) router.push(mainLocation('companion', {}, { view: 'ai' })) } })
  intent.invalidate()
  await router.push(mainLocation('groups'))
  entrance.commit()
  assert.equal(readMainState(router.currentRoute.value.query).view, 'groups')
})

const escapeEvent = () => ({ key: 'Escape', preventDefault() { this.defaultPrevented = true }, stopPropagation() {} })

test('actual settings Escape clears search, then blurs it, before navigating back', () => {
  const document = doc(), { handler, calls, input, searchQuery } = settingsKeyHandler(document)
  document.activeElement = input
  searchQuery.value = 'something'
  handler(escapeEvent())
  assert.equal(searchQuery.value, '')
  assert.equal(calls.back, 0)
  handler(escapeEvent())
  assert.equal(calls.blur, 1)
  assert.equal(calls.back, 0)
  handler(escapeEvent())
  assert.equal(calls.back, 1)
})

test('actual settings handler neither returns nor clears search through a visible modal', () => {
  const document = doc([overlay(true)]), { handler, calls, input, searchQuery } = settingsKeyHandler(document)
  document.activeElement = input
  searchQuery.value = 'keep'
  handler(escapeEvent())
  assert.equal(searchQuery.value, 'keep')
  assert.equal(calls.back, 0)
})

test('actual settings handler does not leave other inputs or trigger back on ordinary keys', () => {
  const document = doc(), { handler, calls } = settingsKeyHandler(document)
  document.activeElement = { tagName: 'TEXTAREA' }
  handler(escapeEvent())
  assert.equal(calls.back, 0)
  document.activeElement = null
  handler({ key: 'x' })
  assert.equal(calls.back, 0)
})

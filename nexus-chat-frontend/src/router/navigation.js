const VIEWS = new Set(['chats', 'contacts', 'groups', 'ai', 'companion'])
const controllers = new WeakMap()
const scalar = value => typeof value === 'string' ? value : null
const selectionId = value => {
  if (typeof value !== 'string' && typeof value !== 'number') return null
  if (!/^[1-9]\d*$/.test(String(value))) return null
  const id = Number(value)
  return Number.isSafeInteger(id) ? id : null
}

export function createNavigationIntent() {
  let version = 0
  return { capture: () => ++version, current: request => request === version, invalidate: () => { version += 1 } }
}

export function readMainState(query = {}) {
  const view = scalar(query.view)
  const previous = scalar(query.returnView)
  return {
    view: VIEWS.has(view) ? view : 'chats',
    returnView: VIEWS.has(previous) && previous !== 'companion' ? previous : null,
    returnChat: scalar(query.returnChat),
    contactId: selectionId(query.contactId),
    groupId: selectionId(query.groupId)
  }
}

export function mainLocation(view = 'chats', query = {}, origin = {}) {
  const next = { ...query, view: VIEWS.has(view) ? view : 'chats' }
  if (next.view === 'companion') {
    const previous = origin.view ?? scalar(query.returnView)
    if (VIEWS.has(previous) && previous !== 'companion') next.returnView = previous
    else delete next.returnView
    const chat = origin.chatId ?? scalar(query.returnChat)
    if (chat != null && String(chat)) next.returnChat = String(chat)
    else delete next.returnChat
  } else {
    delete next.returnView
    delete next.returnChat
  }
  return { name: 'Main', query: next }
}

export function safeAppPath(router, value, { allowSetup = false } = {}) {
  let path = scalar(value)
  if (path?.startsWith('#/')) path = path.slice(1)
  if (!path?.startsWith('/') || path.startsWith('//') || /[\\\x00-\x1f\x7f]/.test(path)) return null
  try {
    const resolved = router.resolve(path)
    if (!resolved.matched.some(record => record.meta.requiresAuth)) return null
    if (resolved.name === 'Login' || (!allowSetup && resolved.name === 'Setup')) return null
    return resolved.fullPath
  } catch { return null }
}

const sameScreen = (router, a, b) => {
  const left = router.resolve(a), right = router.resolve(b)
  return left.path === right.path && (left.name !== 'Main' ||
    readMainState(left.query).view === readMainState(right.query).view)
}

/** Track route entries, not URL folder parents. Works with Web, Hash and memory history. */
export function installNavigation(router, { authenticated = () =>
  typeof localStorage !== 'undefined' && !!localStorage.getItem('token') } = {}) {
  if (controllers.has(router)) return controllers.get(router)
  const history = router.options.history
  const entries = new Map()
  let position = Number.isFinite(history.state?.position) ? history.state.position : 0
  let pending = null
  const push = history.push.bind(history), replace = history.replace.bind(history)
  history.push = (to, state) => {
    push(to, state)
    position = Number.isFinite(history.state?.position) ? history.state.position : position + 1
    for (const key of entries.keys()) if (key >= position) entries.delete(key)
    entries.set(position, to)
  }
  history.replace = (to, state) => {
    replace(to, state)
    if (Number.isFinite(history.state?.position)) position = history.state.position
    entries.set(position, to)
  }
  const stopListen = history.listen((to, _from, info) => {
    position = Number.isFinite(history.state?.position) ? history.state.position : position + info.delta
    entries.set(position, to)
  })
  const stopAfter = router.afterEach((to, _from, failure) => {
    if (failure) return
    if (['Login', 'Setup'].includes(to.name)) entries.clear()
    entries.set(position, to.fullPath)
  })

  async function performBack(mainView = null, fallbackQuery = {}) {
    const accepts = path => !mainView || (router.resolve(path).name === 'Main' && readMainState(router.resolve(path).query).view === mainView)
    const from = router.currentRoute.value.fullPath
    if (!authenticated()) {
      if (router.currentRoute.value.name !== 'Login') await router.replace({ name: 'Login' })
      return true
    }
    const candidates = [...entries.entries()].filter(([index, path]) => index < position &&
      safeAppPath(router, path) && accepts(path) && !sameScreen(router, path, from)).sort((a, b) => b[0] - a[0])
    let target = candidates[0]?.[1], delta = candidates[0] ? candidates[0][0] - position : null
    if (!target) {
      const nativeBack = safeAppPath(router, history.state?.back)
      if (nativeBack && accepts(nativeBack) && history.state?.position > 0 && !sameScreen(router, nativeBack, from)) {
        target = nativeBack
        delta = -1
      }
    }
    if (!target) {
      const fallback = mainLocation(mainView || 'chats', fallbackQuery)
      if (router.resolve(fallback).fullPath !== from) await router.replace(fallback)
      return true
    }
    return new Promise(resolve => {
      let settled = false, timer
      const finish = value => { if (settled) return; settled = true; clearTimeout(timer); remove(); resolve(value) }
      const remove = router.afterEach((to, _old, failure) => finish(!failure && to.fullPath === target))
      // An unavailable history entry must not leave a direct-open page stuck.
      timer = setTimeout(async () => {
        if (router.currentRoute.value.fullPath !== from) { finish(false); return }
        try { await router.replace(target); finish(true) } catch { finish(false) }
      }, 1200)
      history.go(delta)
    })
  }
  const back = (view = null, query = {}) => {
    if (pending) return pending
    const request = performBack(view, query)
    pending = request
    request.finally(() => { if (pending === request) pending = null }).catch(() => {})
    return request
  }
  const dispose = () => { stopListen(); stopAfter(); history.push = push; history.replace = replace; controllers.delete(router) }
  const controller = { back, dispose }
  controllers.set(router, controller)
  return controller
}

export const goBack = router => installNavigation(router).back()
export const goBackToMain = (router, view, query = {}) => installNavigation(router).back(VIEWS.has(view) ? view : 'chats', query)
export const loginDestination = (router, query) => safeAppPath(router, query?.redirect, { allowSetup: true }) || mainLocation('chats')

export function createAuthNavigationGuard(router, storage = typeof localStorage === 'undefined' ? null : localStorage) {
  return (to, _from, next) => {
    let token = storage?.getItem('token')
    if (token?.startsWith('mock-token-')) { storage.removeItem('token'); token = null }
    if (to.meta.requiresAuth && !token) {
      next({ name: 'Login', query: { redirect: to.fullPath }, replace: true })
    } else if (to.name === 'Login' && token) {
      const destination = loginDestination(router, to.query)
      next(typeof destination === 'string' ? { path: destination, replace: true } : { ...destination, replace: true })
    } else next()
  }
}

/** Hidden Element Plus DOM is not a visible keyboard owner. */
export function hasBlockingOverlay(doc = typeof document === 'undefined' ? null : document) {
  const overlays = doc?.querySelectorAll?.('[role="dialog"],[role="listbox"],.el-overlay,.modal-overlay,.model-panel-scrim,.busy-scrim') || []
  return [...overlays].some(element => {
    const box = element.getBoundingClientRect?.()
    if (!box || box.width <= 0 || box.height <= 0) return false
    const style = doc.defaultView?.getComputedStyle?.(element) || element.style || {}
    return style.display !== 'none' && style.visibility !== 'hidden'
  })
}

/** Escape closes editing/overlays before it can become page navigation. */
export function canGoBackOnEscape(event, doc = typeof document === 'undefined' ? null : document) {
  if (event.key !== 'Escape' || event.defaultPrevented || event.isComposing || event.metaKey || event.ctrlKey || event.altKey) return false
  const editingRoles = ['textbox', 'combobox', 'spinbutton', 'slider']
  const typing = element => element && (['INPUT', 'TEXTAREA', 'SELECT'].includes(element.tagName) ||
    element.isContentEditable || editingRoles.includes(element.getAttribute?.('role')))
  return !typing(event.target) && !typing(doc?.activeElement) && !hasBlockingOverlay(doc)
}

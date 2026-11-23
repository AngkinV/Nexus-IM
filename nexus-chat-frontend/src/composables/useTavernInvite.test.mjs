import { after, afterEach, before, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { reactive, ref } from 'vue'

let server, useTavernInvite, useRoleModelTarget, modelAssetType, userId, store, flow, selected, notices
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const model = name => ({ fileName: `${name}.vrm`, fileUrl: `/models/${name}.vrm`, size: 30, type: 'vrm' })

before(async () => {
  server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('../../', import.meta.url)),
    resolve: { alias: { '@': fileURLToPath(new URL('../', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, watch: null }, logLevel: 'error'
  })
  ;({ useTavernInvite } = await server.ssrLoadModule('/src/composables/useTavernInvite.js'))
  ;({ modelAssetType } = await server.ssrLoadModule('/src/components/companion/modelAssetType.js'))
  ;({ useRoleModelTarget } = await server.ssrLoadModule('/src/composables/useRoleModelTarget.js'))
})
after(async () => { await server?.close() })
afterEach(() => flow?.dispose())
beforeEach(() => {
  userId = ref(7)
  selected = []
  notices = { success: [], error: [], unknown: [] }
  store = reactive({
    ownerEpoch: 1, isInviting: false,
    isUserCurrent: id => String(id) === String(userId.value),
    fetchInviteModels: async () => [model('a')],
    inviteGuest: async () => ({ id: 99, kind: 'guest' }),
    setActiveRole: async (user, role) => { selected.push([user, role]) }
  })
  flow = useTavernInvite({ store, getUserId: () => userId.value, getSceneKey: () => 'rain-fireplace',
    onSuccess: value => notices.success.push(value), onError: value => notices.error.push(value),
    onUnknown: value => notices.unknown.push(value) })
})

test('refresh keeps old rows but blocks confirming stale options', async () => {
  await flow.open()
  const response = deferred()
  store.fetchInviteModels = () => response.promise
  const reading = flow.open()
  assert.equal(flow.models.value.length, 1)
  assert.equal(flow.loading.value, true)
  assert.equal(flow.canConfirm.value, false)
  response.resolve([model('b')])
  await reading
  assert.equal(flow.models.value[0].fileName, 'b.vrm')
  assert.equal(flow.canConfirm.value, true)
})

test('an old picker response cannot replace a newer list', async () => {
  const first = deferred(), second = deferred()
  let calls = 0
  store.fetchInviteModels = () => (++calls === 1 ? first.promise : second.promise)
  const old = flow.open(), current = flow.open()
  second.resolve([model('new')])
  await current
  first.resolve([model('old')])
  await old
  assert.equal(flow.models.value[0].fileName, 'new.vrm')
})

test('read failure is shown and prevents confirming; retry clears it', async () => {
  store.fetchInviteModels = async () => { throw new Error('I/O failed') }
  await flow.open()
  assert.ok(flow.error.value)
  assert.equal(flow.canConfirm.value, false)
  assert.equal(await flow.confirm(), null)
  assert.deepEqual(selected, [])
  store.fetchInviteModels = async () => [model('ok')]
  await flow.open()
  assert.equal(flow.error.value, null)
  assert.equal(flow.canConfirm.value, true)
})

test('a real empty library still permits text-only automatic invitation', async () => {
  store.fetchInviteModels = async () => []
  await flow.open()
  assert.equal(flow.error.value, null)
  assert.equal(flow.canConfirm.value, true)
  await flow.confirm()
  assert.deepEqual(selected, [[7, 99]])
})

test('closing during a read discards its late result', async () => {
  const response = deferred()
  store.fetchInviteModels = () => response.promise
  const reading = flow.open()
  flow.visible.value = false
  response.resolve([model('closed')])
  await reading
  assert.deepEqual(flow.models.value, [])
  assert.equal(flow.loading.value, false)
})

test('account switch and component disposal discard unfinished reads', async () => {
  const response = deferred()
  store.fetchInviteModels = () => response.promise
  const reading = flow.open()
  userId.value = 8
  store.ownerEpoch += 1
  response.resolve([model('wrong owner')])
  await reading
  assert.deepEqual(flow.models.value, [])
  assert.equal(flow.visible.value, false)
  const second = deferred()
  store.fetchInviteModels = () => second.promise
  const more = flow.open()
  flow.dispose()
  second.resolve([model('unmounted')])
  await more
  assert.deepEqual(flow.models.value, [])
})

test('only the response role is selected, never another freshly listed guest', async () => {
  store.roles = [{ id: 120, kind: 'guest' }]
  let payload
  store.inviteGuest = async (...args) => { payload = args; return { id: 99, kind: 'guest' } }
  await flow.open()
  flow.selectedUrl.value = '/models/a.vrm'
  await flow.confirm()
  assert.deepEqual(payload, [7, 'rain-fireplace', '/models/a.vrm'])
  assert.deepEqual(selected, [[7, 99]])
  assert.equal(notices.success.length, 1)
})

test('timeout leaves selection unchanged and never retries a POST', async () => {
  let posts = 0
  store.inviteGuest = async () => { posts += 1; throw Object.assign(new Error('timeout'), { code: 'ECONNABORTED' }) }
  await flow.open()
  await flow.confirm()
  assert.equal(posts, 1)
  assert.deepEqual(selected, [])
  assert.equal(notices.unknown.length, 1)
  assert.equal(notices.success.length, 0)
})

test('unconfirmed or wrong-kind response does not claim a successful invitation', async () => {
  store.inviteGuest = async () => ({ id: 120, kind: 'regular' })
  await flow.open()
  await flow.confirm()
  assert.deepEqual(selected, [])
  assert.equal(notices.unknown.length, 1)
})

test('409 conflict reports busy instead of resubmitting', async () => {
  let calls = 0
  store.inviteGuest = async () => { calls += 1; throw { response: { status: 409 } } }
  await flow.open()
  await flow.confirm()
  assert.equal(calls, 1)
  assert.equal(notices.error.length, 1)
  assert.deepEqual(selected, [])
})

test('late invitation completion after A-B-A account switch cannot select or toast', async () => {
  const response = deferred()
  store.inviteGuest = () => response.promise
  await flow.open()
  const inviting = flow.confirm()
  userId.value = 8
  store.ownerEpoch += 1
  userId.value = 7
  store.ownerEpoch += 1
  response.resolve({ id: 99, kind: 'guest' })
  await inviting
  assert.deepEqual(selected, [])
  assert.equal(notices.success.length, 0)
})

test('component unmount prevents a late result changing selection', async () => {
  const response = deferred()
  store.inviteGuest = () => response.promise
  await flow.open()
  const inviting = flow.confirm()
  flow.dispose()
  response.resolve({ id: 99, kind: 'guest' })
  await inviting
  assert.deepEqual(selected, [])
})

test('only compatible VRM rows appear and an unknown selection cannot submit', async () => {
  store.fetchInviteModels = async () => [model('a'), { fileUrl: '/models/b.glb', fileName: 'b.glb', type: 'gltf' }]
  await flow.open()
  assert.deepEqual(flow.models.value.map(item => item.fileName), ['a.vrm'])
  flow.selectedUrl.value = '/models/b.glb'
  assert.equal(flow.canConfirm.value, false)
})

test('shape types preserve GLB/GLTF/FBX instead of assigning VRM universally', () => {
  assert.equal(modelAssetType({ fileUrl: '/models/a.glb' }), 'gltf')
  assert.equal(modelAssetType({ fileUrl: '/models/a.gltf' }), 'gltf')
  assert.equal(modelAssetType({ fileUrl: '/models/a.fbx' }), 'fbx')
  assert.equal(modelAssetType(model('a')), 'vrm')
  assert.equal(modelAssetType({ fileUrl: '/models/a.txt' }), null)
})

test('leaving A and returning to A does not revive an old upload continuation', t => {
  const roleId = ref(1)
  const target = useRoleModelTarget({ store, getUserId: () => userId.value, getRoleId: () => roleId.value })
  t.after(target.dispose)
  const request = target.capture()
  roleId.value = 2
  roleId.value = 1
  assert.equal(target.isOwner(request), true)
  assert.equal(target.isCurrent(request), false)
})

test('shared rename acknowledgement still belongs to the account after changing role or unmounting', t => {
  const roleId = ref(1)
  const target = useRoleModelTarget({ store, getUserId: () => userId.value, getRoleId: () => roleId.value })
  t.after(target.dispose)
  const request = target.capture()
  roleId.value = 2
  assert.equal(target.isOwner(request), true)
  assert.equal(target.isCurrent(request), false)
  target.dispose()
  assert.equal(target.isOwner(request), true)
  assert.equal(target.isCurrent(request), false)
})

test('shared rename acknowledgement is discarded after an account generation changes', t => {
  const roleId = ref(1)
  const target = useRoleModelTarget({ store, getUserId: () => userId.value, getRoleId: () => roleId.value })
  t.after(target.dispose)
  const request = target.capture()
  userId.value = 8
  store.ownerEpoch += 1
  userId.value = 7
  store.ownerEpoch += 1
  assert.equal(target.isOwner(request), false)
})

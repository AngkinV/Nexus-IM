import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import { createResourceOwner, attempt, releaseRenderer } from '../components/companion/resourceOwner.js'

const base = new URL('../components/companion/', import.meta.url)
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const resource = (extra = {}) => ({ count: 0, dispose() { this.count++ }, ...extra })
const root = (nodes) => ({ isObject3D: true, traverse(fn) { nodes.forEach(fn) }, removeFromParent() {} })

test('shared geometry/material/texture/skeleton dispose once; owned bitmap closes once after clones; shared bitmap stays open', () => {
  for (const ownImages of [true, false]) {
    const bitmap = { count: 0, close() { this.count++ } }
    const texture = resource({ isTexture: true, image: bitmap })
    const clone = resource({ isTexture: true, image: bitmap })
    const material = resource({ isMaterial: true, map: texture, normalMap: clone })
    const geometry = resource(), skeleton = resource()
    const owner = createResourceOwner({ ownImages })
    owner.track(root([{ geometry, material, skeleton }, { geometry, material, skeleton }]))
    owner.retire(); owner.retire()
    for (const value of [geometry, material, skeleton, texture, clone]) assert.equal(value.count, 1)
    assert.equal(bitmap.count, ownImages ? 1 : 0)
  }
})

test('parser late successes after cancellation/failure retire rather than apply; partial texture has an exit', async () => {
  for (const fail of [false, true]) {
    const late = deferred(), partial = deferred()
    const owner = createResourceOwner({ ownImages: true })
    const parser = { getDependency: () => late.promise, loadTextureImage: () => partial.promise }
    owner.bindParser(parser)
    const dependency = parser.getDependency('scene', 0)
    const imagePromise = parser.loadTextureImage()
    owner.retire()
    const texture = resource({ isTexture: true, image: { count: 0, close() { this.count++ } } })
    partial.resolve(texture)
    await imagePromise
    assert.equal(texture.count, 1); assert.equal(texture.image.count, 1)
    if (fail) { late.reject(new Error('parse')); await assert.rejects(dependency) }
    else { const geometry = resource(); late.resolve(root([{ geometry }])); await dependency; assert.equal(geometry.count, 1) }
  }
})

test('actual stage destroy survives actor/resource failures, stops scheduling first, releases context/canvas once', async () => {
  const source = await readFile(new URL('tavernStage.js', base), 'utf8')
  const body = source.slice(source.indexOf('    destroy() {'), source.indexOf('\n}\n\n/**', source.indexOf('    destroy() {')))
  // Extract the actual destroy method up to the enclosing API/function endings.
  const method = body.slice(0, body.indexOf('\n    }') + 6)
  const events = []
  const canvas = { removeEventListener() {}, remove() { events.push('canvas') } }
  const renderer = { domElement: canvas, dispose() { events.push('renderer'); throw Error('dispose') }, forceContextLoss() { events.push('context') } }
  const context = vm.createContext({ attempt, createResourceOwner, releaseRenderer, canvas, renderer, scene: root([{ geometry: resource({ dispose() { events.push('geometry'); throw Error('geometry') } }) }]), camera: {}, actor: { destroy() { events.push('actor'); throw Error('actor') } }, actorAbort: { abort() { events.push('abort') } }, destroyed: false, actorRequest: 0, frame: 1, cancelFrame() { events.push('stop') }, pause() { events.push('pause') }, stopQualityWatch() {}, sharedMaterials: [], window: { removeEventListener() {} }, document: { removeEventListener() {} }, onMouseMove() {}, onVisibility() {}, onContextLost() {}, onContextRestored() {}, resizeObserver: { disconnect() {} },
    // The stage listens for the pointer on its own element and owns a vignette overlay.
    container: { removeEventListener(type) { events.push(`unlisten:${type}`); if (type === 'pointerleave') throw Error('listener') } }, onPointerLeave() {},
    vignette: { remove() { events.push('vignette'); throw Error('vignette') } } })
  await vm.runInContext(`globalThis.api = { ${method} }; const retirement = api.destroy(); api.destroy(); retirement`, context)
  assert.ok(events.indexOf('stop') < events.indexOf('canvas'))
  assert.ok(events.indexOf('canvas') < events.indexOf('actor'))
  assert.ok(events.indexOf('canvas') < events.indexOf('geometry'))
  for (const key of ['renderer', 'context', 'canvas', 'actor', 'vignette', 'unlisten:pointermove', 'unlisten:pointerleave']) assert.equal(events.filter(e => e === key).length, 1)
  assert.ok(events.indexOf('stop') < events.indexOf('unlisten:pointermove'))
  assert.equal(context.renderer, null); assert.equal(context.frame, 0)
})

test('actual stage render/animate gate never draws or schedules after loss/destruction', async () => {
  const source = await readFile(new URL('tavernStage.js', base), 'utf8')
  const render = source.slice(source.indexOf('  function renderFrame('), source.indexOf('  const hush ='))
  const animate = source.slice(source.indexOf('  const animate = (now) => {'), source.indexOf('  const canvas = renderer.domElement'))
  for (const flags of [{ destroyed: true, contextLost: false }, { destroyed: false, contextLost: true }]) {
    const context = vm.createContext({ ...flags, renderer: {}, requestFrame() { throw Error('scheduled') }, step() { throw Error('step') } })
    vm.runInContext(`${render}\n${animate}\nrenderFrame(0,0,1); animate(0)`, context)
  }
})

test('actual route nextTick continuation cannot mount after unmount', async () => {
  const source = await readFile(new URL('../views/companion/RoleChatView.vue', import.meta.url), 'utf8')
  const start = source.indexOf('watch(stageVisible, async (visible) => {')
  const callback = source.slice(start, source.indexOf('\n})', start) + 3)
  const tick = deferred(); let mounts = 0, watcher
  const context = vm.createContext({ stagesAlive: true, stagesGeneration: 0, stageVisible: {}, watch(_, fn) { watcher = fn }, nextTick: () => tick.promise, mountStages() { mounts++ }, teardownStages() {} })
  vm.runInContext(callback, context)
  const pending = watcher(true)
  context.stagesAlive = false; context.stagesGeneration++
  tick.resolve(); await pending
  assert.equal(mounts, 0)
})

test('actual motion module singleflight, reverse returns, cache retirement, late unmount and default continuation', async () => {
  const source = await readFile(new URL('CompanionAvatar3D.vue', base), 'utf8')
  const load = source.slice(source.indexOf('const loadMixamoClips ='), source.indexOf('\nconst playMixamoAction'))
  const retire = source.slice(source.indexOf('const retireMotionActions ='), source.indexOf('\nconst disposeModel'))
  const play = source.slice(source.indexOf('const playDefaultMotion ='), source.indexOf('\nconst switchMotion'))
  const pending = [], applied = []
  const mixer = { cache: new Set(), clipAction(clip) { applied.push(clip.name); const action = { stop() {}, getClip: () => clip }; this.cache.add(action); return action }, uncacheAction(clip) { for (const a of this.cache) if (a.getClip() === clip) this.cache.delete(a) }, uncacheClip() {} }
  const context = vm.createContext({ console: { log() {}, warn() {} }, createResourceOwner, attempt, vrm: { humanoid: {} }, mixer, modelRoot: {}, motionGeneration: 0, motionFlight: null, disposed: false, contextLost: false, mixamoLoaded: false, mixamoActions: {}, mixamoCurrent: null, motionList: { value: [{ key: 'old', file: 'old' }] }, fetchMotionConfig: async () => {}, FBXLoader: class { setRequestHeader() {} load(url, resolve) { pending.push({ url, resolve }) } }, assetRequest: url => ({ url, headers: {} }), SERVER_BASE_URL: '', userStore: { token: '' }, THREE: { LoopRepeat: 1 }, retargetMixamoClipToVrm: clip => clip, stopMotionCycle() {}, settings: { petMotion: 'old' }, motionLibraryConfirmed: false, startMotionCycle() { throw Error('late timer') }, playMixamoAction() { throw Error('late play') } })
  vm.runInContext(`${load}\n${retire}\n${play}\nglobalThis.api = { loadMixamoClips, retireMotionActions, playDefaultMotion }`, context)
  const fbx = key => Object.assign(root([{ geometry: resource() }]), { animations: [{ name: key, tracks: [1], duration: 1 }] })
  const old = context.api.loadMixamoClips()
  assert.equal(context.api.loadMixamoClips(), old)
  context.api.playDefaultMotion()
  context.api.retireMotionActions()
  context.motionList.value = [{ key: 'new', file: 'new' }]
  const current = context.api.loadMixamoClips()
  pending[1].resolve(fbx('new')); await current
  pending[0].resolve(fbx('old')); await old
  await Promise.resolve()
  assert.deepEqual(applied, ['new'])
  for (let i = 0; i < 3; i++) {
    context.api.retireMotionActions(); assert.equal(mixer.cache.size, 0)
    const flight = context.api.loadMixamoClips(); pending.at(-1).resolve(fbx('new')); await flight
    assert.equal(mixer.cache.size, 1)
  }
  context.api.retireMotionActions()
  const late = context.api.loadMixamoClips(); context.api.playDefaultMotion(); context.disposed = true
  pending.at(-1).resolve(fbx('new')); await late; await Promise.resolve()
  assert.equal(mixer.cache.size, 0)
})


test('renderer retirement detaches first, is idempotent, and completes without hidden-tab rAF', async () => {
  const events = [], timers = new Map(), frames = new Map()
  let id = 0
  const scheduler = {
    setTimeout(fn, delay) { const key = ++id; timers.set(key, { fn, delay }); return key },
    clearTimeout(key) { timers.delete(key) },
    requestAnimationFrame(fn) { const key = ++id; frames.set(key, fn); return key },
    cancelAnimationFrame(key) { frames.delete(key) }
  }
  const renderer = {
    setAnimationLoop(value) { assert.equal(value, null); events.push('stop') },
    domElement: { remove() { events.push('detach') } },
    renderLists: { dispose() { events.push('lists') } },
    dispose() { events.push('dispose'); throw Error('cache') },
    forceContextLoss() { events.push('loss') }
  }
  const done = releaseRenderer(renderer, () => { events.push('bitmap'); throw Error('actor') }, scheduler)
  assert.equal(releaseRenderer(renderer, () => assert.fail('duplicate'), scheduler), done)
  assert.deepEqual(events, ['stop', 'detach'])
  // Simulate hidden document: rAF never fires; execute the finite fallback.
  assert.equal(timers.size, 1)
  const fallback = [...timers.values()][0]
  assert.equal(fallback.delay, 100)
  fallback.fn(); fallback.fn()
  await done
  assert.deepEqual(events, ['stop', 'detach', 'bitmap', 'lists', 'dispose', 'loss'])
  assert.equal(timers.size, 0); assert.equal(frames.size, 0)
})

test('post-frame task retires resources and cancels fallback without drawing', async () => {
  const timers = new Map(); let frame, id = 0, disposed = 0
  const scheduler = {
    setTimeout(fn, delay) { timers.set(++id, { fn, delay }); return id },
    clearTimeout(key) { timers.delete(key) },
    requestAnimationFrame(fn) { frame = fn; return 1 }, cancelAnimationFrame() {}
  }
  const done = releaseRenderer({ domElement: { remove() {} }, dispose() { disposed++ } }, () => {}, scheduler)
  frame()
  assert.equal(disposed, 0)
  const task = [...timers.values()].find(t => t.delay === 0)
  task.fn(); await done
  assert.equal(disposed, 1); assert.equal(timers.size, 0)
})

test('owned texture arrays and CubeTexture bitmap arrays deduplicate; shared images remain open', () => {
  for (const ownImages of [true, false]) {
    const bitmap = { count: 0, close() { this.count++ } }
    const cube = resource({ isTexture: true, isCubeTexture: true, image: [bitmap, bitmap] })
    const clone = resource({ isTexture: true, source: { data: [bitmap] } })
    const material = resource({ isMaterial: true, maps: [cube, clone, cube], uniforms: { maps: { value: [clone, cube] } } })
    const owner = createResourceOwner({ ownImages })
    owner.track(material); owner.retire(); owner.retire()
    assert.equal(cube.count, 1); assert.equal(clone.count, 1)
    assert.equal(material.count, 1); assert.equal(bitmap.count, ownImages ? 1 : 0)
  }
})

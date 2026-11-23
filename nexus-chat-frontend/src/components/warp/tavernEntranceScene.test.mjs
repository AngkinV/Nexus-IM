import { test } from 'node:test'
import assert from 'node:assert/strict'
import { setDocumentHidden } from '../../utils/renderGovernor.js'
import { createTavernEntranceScene } from './tavernEntranceScene.js'

function fakeCanvas(width = 400, height = 200) {
  const c = { width, height, style: {} }
  const ctx = {
    createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }),
    putImageData() {}, drawImage() {}, setTransform() {}, clearRect() {}, fillRect() {}, beginPath() {}, ellipse() {}, fill() {}, moveTo() {}, lineTo() {}, closePath() {}, arc() {}, save() {}, restore() {}, translate() {}, scale() {},
    createPattern() { return {} },
    createLinearGradient() { return { addColorStop() {} } },
    createRadialGradient() { return { addColorStop() {} } }
  }
  c.getContext = type => type === '2d' ? ctx : null
  return c
}

test('cold/warm readiness, exact seek, complete 2D fallback, and sound lifecycle', async () => {
  const old = { window: globalThis.window, document: globalThis.document, raf: globalThis.requestAnimationFrame, cancel: globalThis.cancelAnimationFrame, ImageData: globalThis.ImageData }
  globalThis.ImageData = class { constructor(w, h) { this.data = new Uint8ClampedArray(w * h * 4) } }
  let nextFrame, done = [], commits = 0
  globalThis.window = { innerWidth: 400, innerHeight: 200, devicePixelRatio: 1 }
  globalThis.document = { createElement: () => fakeCanvas() }
  globalThis.requestAnimationFrame = fn => { nextFrame = fn; return 1 }
  globalThis.cancelAnimationFrame = () => { nextFrame = null }
  const sounds = []
  const createMusic = ({ enabled }) => {
    const log = []
    const m = { enabled, log, playing: false,
      prepare() { log.push('prepare'); return Promise.resolve(true) },
      unlock() { log.push('unlock'); return true },
      start(speed) { log.push(['start', speed]); m.playing = true },
      tick(t) { log.push(['tick', t]) }, pause() { log.push('pause') }, resume() { log.push('resume') },
      stop() { log.push('stop'); m.playing = false }, dispose() { log.push('dispose') }
    }
    sounds.push(m); return m
  }
  const scene = createTavernEntranceScene(fakeCanvas(), fakeCanvas(), { createMusic, onCommit: () => commits++, onDone: reason => done.push(reason) })
  try {
    const ready = scene.prepare()
    assert.equal(typeof ready.then, 'function')
    assert.equal(scene.ready, false)
    scene.start()
    nextFrame(100)
    nextFrame(200)
    assert.equal(scene.diagnostics.seconds, 0, 'cold preparation is not transport time')
    assert.ok(sounds[0].log.includes('pause'))
    await ready
    assert.equal(scene.ready, true)
    assert.equal(scene.diagnostics.webgl, false)
    assert.equal(scene.diagnostics.coldStarts, 1)
    nextFrame(1000); nextFrame(1700)
    assert.equal(scene.diagnostics.seconds, .7, 'foreground long frames are not clamped to .05')
    assert.ok(sounds[0].log.some(v => Array.isArray(v) && v[0] === 'tick' && v[1] === .7))
    setDocumentHidden(true)
    assert.equal(sounds[0].log.at(-1), 'pause', 'background suspension pauses audio immediately')
    setDocumentHidden(false)
    nextFrame(5000)
    assert.equal(scene.diagnostics.seconds, .7, 'background interval is excluded')
    nextFrame(13000)
    assert.deepEqual(done, ['done'], 'no WebGL still runs the entire drawing timeline')
    assert.equal(commits, 1)
    scene.unlock(); assert.ok(sounds[0].log.includes('unlock'))
    const before = commits
    await scene.seek(.2)
    assert.equal(scene.diagnostics.seconds, .2)
    assert.equal(scene.diagnostics.phase, 'paper')
    await scene.seek(.65)
    assert.equal(scene.diagnostics.seconds, .65)
    assert.equal(scene.diagnostics.phase, 'drop')
    assert.equal(commits, before, 'seek cannot commit or mark seen')
    assert.equal(sounds[0].playing, false, 'seek remains silent')
    scene.setSoundEnabled(false)
    scene.start()
    assert.equal(sounds[1].enabled, false)
    assert.equal(sounds[1].playing, false)
    assert.ok(scene.diagnostics.warmStarts >= 1)
    scene.resize()
    assert.equal(done.at(-1), 'resized', 'resize is not natural completion')
  } finally {
    setDocumentHidden(false)
    scene.dispose()
    assert.ok(sounds.at(-1).log.includes('dispose'))
    globalThis.ImageData = old.ImageData
    globalThis.window = old.window; globalThis.document = old.document
    globalThis.requestAnimationFrame = old.raf; globalThis.cancelAnimationFrame = old.cancel
  }
})

import * as THREE from 'three'
import { createTavernSpeech } from './tavernSpeech.js'
import { createTavernAtmosphere } from './tavernAtmosphere.js'
import {
  getFrameCap,
  scaledPixelRatio,
  onRenderQualityChange,
  isRenderSuspended,
  requestFrame,
  cancelFrame
} from '../../utils/renderGovernor.js'

// The cap is read per frame so Settings → 渲染帧率上限 takes effect live.
const frameInterval = () => 1000 / getFrameCap()

// ── Deterministic randomness ─────────────────────────────────────────────────
function hashString(text) {
  let h = 2166136261
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

function mulberry32(seed) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const HEAD_SHAPES = ['icosa', 'octa', 'dodeca', 'sphere']
const BODY_SHAPES = ['cloak', 'coat', 'shawl']

/**
 * The silhouette a character gets. Pure, so the list, the stage and the header
 * badge can all draw the same person.
 */
export function characterShape(character) {
  const key = `${character?.id ?? ''}|${character?.name ?? ''}`
  const seed = hashString(key)
  const rand = mulberry32(seed)
  const pick = (list) => list[Math.floor(rand() * list.length)]
  return {
    seed,
    head: pick(HEAD_SHAPES),
    headDetail: rand() < 0.5 ? 1 : 2,
    headStretch: 0.88 + rand() * 0.3,
    headWidth: 0.9 + rand() * 0.2,
    body: pick(BODY_SHAPES),
    shoulderWidth: 0.82 + rand() * 0.42,
    shoulderSides: 6 + Math.floor(rand() * 13),
    rings: Math.floor(rand() * 3),
    ringTilt: 0.05 + rand() * 0.35,
    ringRadius: 1.0 + rand() * 0.3,
    lean: (rand() - 0.5) * 0.16
  }
}

function headGeometry(shape) {
  let geometry
  if (shape.head === 'octa') geometry = new THREE.OctahedronGeometry(0.74, shape.headDetail + 1)
  else if (shape.head === 'dodeca') geometry = new THREE.DodecahedronGeometry(0.72, shape.headDetail - 1)
  else if (shape.head === 'sphere') geometry = new THREE.SphereGeometry(0.7, 6 + shape.headDetail * 3, 5 + shape.headDetail * 2)
  else geometry = new THREE.IcosahedronGeometry(0.72, shape.headDetail)
  geometry.scale(shape.headWidth, shape.headStretch, shape.headWidth)
  return geometry
}

// ── Scenes ───────────────────────────────────────────────────────────────────
// Keys match tavernScenes.js. An unknown key draws the bare counter: a wrong
// room is worse than no room.
const SCENES = {
  'rain-fireplace': { light: 1.0, flicker: 0.16, window: 'small', rain: 46, rainSpeed: 2.6, hearth: true, dust: 40, dustDrift: 0.12 },
  'late-rain': { light: 0.72, flicker: 0.0, window: 'wide', rain: 120, rainSpeed: 4.2, hearth: false, dust: 24, dustDrift: 0.05 },
  'empty-bar': { light: 1.08, flicker: 0.0, window: null, rain: 0, rainSpeed: 0, hearth: false, shelf: true, dust: 70, dustDrift: 0.06 },
  'closing-time': { light: 0.56, flicker: 0.0, window: null, rain: 0, rainSpeed: 0, hearth: false, lamp: true, dust: 54, dustDrift: -0.08 }
}
const BARE_SCENE = { light: 0.9, flicker: 0, window: null, rain: 0, rainSpeed: 0, hearth: false, dust: 30, dustDrift: 0.05 }

// ── Mood and motion ──────────────────────────────────────────────────────────
// pitch: + looks down. yaw: + turns to her left. roll: head tilt.
const MOODS = {
  calm: { pitch: 0.0, yaw: 0.0, roll: 0.0, breath: 1.0, amp: 1.0, light: 1.0, jitter: 0 },
  warm: { pitch: 0.05, yaw: 0.0, roll: 0.04, breath: 0.95, amp: 1.1, light: 1.12, jitter: 0 },
  happy: { pitch: -0.1, yaw: 0.0, roll: 0.05, breath: 1.35, amp: 1.35, light: 1.2, jitter: 0 },
  shy: { pitch: 0.2, yaw: 0.32, roll: 0.08, breath: 1.15, amp: 0.8, light: 0.9, jitter: 0 },
  sad: { pitch: 0.3, yaw: 0.0, roll: -0.05, breath: 0.6, amp: 0.7, light: 0.7, jitter: 0 },
  worried: { pitch: 0.1, yaw: 0.0, roll: -0.1, breath: 1.3, amp: 0.9, light: 0.88, jitter: 0.012 },
  angry: { pitch: 0.06, yaw: 0.0, roll: 0.0, breath: 1.6, amp: 1.2, light: 1.22, jitter: 0.02 },
  surprised: { pitch: -0.2, yaw: 0.0, roll: 0.0, breath: 1.5, amp: 1.5, light: 1.25, jitter: 0 },
  tired: { pitch: 0.34, yaw: 0.0, roll: 0.12, breath: 0.5, amp: 0.6, light: 0.66, jitter: 0 },
  thoughtful: { pitch: -0.08, yaw: -0.24, roll: 0.14, breath: 0.8, amp: 0.85, light: 0.95, jitter: 0 }
}

const MOTIONS = {
  idle: { yaw: 0, pitch: 0, roll: 0, forward: 0, follow: 1.0, nod: false },
  listen: { yaw: 0, pitch: 0.04, roll: 0.05, forward: 0.12, follow: 1.5, nod: false },
  think: { yaw: -0.3, pitch: -0.1, roll: 0.16, forward: 0, follow: 0.35, nod: false },
  nod: { yaw: 0, pitch: 0, roll: 0, forward: 0.05, follow: 1.0, nod: true },
  lean_in: { yaw: 0, pitch: 0.08, roll: 0, forward: 0.5, follow: 1.3, nod: false },
  look_away: { yaw: 0.62, pitch: 0.05, roll: 0, forward: -0.08, follow: 0.15, nod: false }
}

const lerp = (from, to, t) => from + (to - from) * t

export function characterModelSource(character) {
  if (!character) return null
  const url = character.modelUrl || (character.kind === 'guest' ? null : '/models/SpringSnow.vrm')
  if (!url || (character.modelType && character.modelType !== 'vrm')) return null
  if (!character.modelType && !/\.vrm(?:[?#]|$)/i.test(url)) return null
  return url
}

export function mountTavernStage(container, { shouldRender = () => true, reducedMotion = false, onActorStatus = () => {}, requestAsset = (url) => ({ url, headers: {} }) } = {}) {
  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(42, 1.5, 0.1, 100)
  camera.position.set(0, 0.1, 6.4)
  const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: 'low-power' })
  renderer.localClippingEnabled = true
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.setPixelRatio(scaledPixelRatio(1.5))
  renderer.domElement.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;display:block'
  container.appendChild(renderer.domElement)

  const brightMaterial = new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.85 })
  const dimMaterial = new THREE.LineBasicMaterial({ color: 0x8e9192, transparent: true, opacity: 0.45 })
  const roomMaterial = new THREE.LineBasicMaterial({ color: 0x8e9192, transparent: true, opacity: 0.38 })
  const rainMaterial = new THREE.LineBasicMaterial({ color: 0xc4c7c8, transparent: true, opacity: 0.42 })
  const pointMaterial = new THREE.PointsMaterial({ color: 0xffffff, size: 0.045, transparent: true, opacity: 0.8 })
  const dustMaterial = new THREE.PointsMaterial({ color: 0xffffff, size: 0.028, transparent: true, opacity: 0.5 })
  const emberMaterial = new THREE.PointsMaterial({ color: 0xffffff, size: 0.05, transparent: true, opacity: 0.85 })
  const counterMaterial = new THREE.MeshStandardMaterial({ color: 0x353535, roughness: 0.78 })
  const windowMaterial = new THREE.MeshBasicMaterial({ color: 0x7e7e7e, transparent: true, opacity: 0.2, depthWrite: false })
  const sharedMaterials = [brightMaterial, dimMaterial, roomMaterial, rainMaterial, pointMaterial, dustMaterial, emberMaterial, counterMaterial, windowMaterial]
  const atmosphere = createTavernAtmosphere()
  const vignette = document.createElement('div')
  vignette.style.cssText = 'position:absolute;inset:0;pointer-events:none;background:radial-gradient(ellipse at 52% 45%,transparent 38%,rgba(0,0,0,.7) 100%);opacity:.1'
  vignette.setAttribute('aria-hidden', 'true')
  container.appendChild(vignette)

  const wire = (geometry, material) => {
    const lines = new THREE.LineSegments(new THREE.WireframeGeometry(geometry), material)
    geometry.dispose()
    return lines
  }
  const polyline = (points, material) => {
    const flat = []
    for (let i = 0; i < points.length - 1; i++) flat.push(...points[i], ...points[i + 1])
    const geometry = new THREE.BufferGeometry()
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(flat, 3))
    return new THREE.LineSegments(geometry, material)
  }
  const disposeGroup = (group) => {
    group.traverse((node) => node.geometry?.dispose())
    group.clear()
  }

  // ── The figure ─────────────────────────────────────────────────────────────
  const figure = new THREE.Group()
  const headPivot = new THREE.Group()
  const rings = []
  figure.position.set(-0.15, -0.35, 0)
  headPivot.position.set(0, 0.62, 0)
  figure.add(headPivot)
  scene.add(figure)
  let shape = null
  let headPoints = null
  let actor = null
  let actorKey = null
  let actorSeed = 0
  let actorSource = null
  let actorStatus = 'none'
  let actorAbort = null
  let actorRequest = 0
  let destroyed = false

  const ambient = new THREE.HemisphereLight(0xffffff, 0x484848, 0.7)
  const keyLight = new THREE.DirectionalLight(0xffffff, 0.86)
  keyLight.position.set(-3, 4, 5)
  const rimLight = new THREE.DirectionalLight(0xffffff, 0.36)
  rimLight.position.set(2, 3, -3)
  const roomLight = new THREE.PointLight(0xffffff, 0, 8, 2)
  roomLight.position.set(3, -0.35, -1.35)
  scene.add(ambient, keyLight, rimLight, roomLight)

  const reportActorStatus = (status) => {
    actorStatus = status
    onActorStatus(status === 'queued' ? 'loading' : status)
  }

  function selectActor(character) {
    const source = characterModelSource(character)
    const key = `${character?.id ?? ''}|${source ?? ''}`
    if (key === actorKey) return
    actorKey = key
    actorSeed = characterShape(character).seed
    actorSource = source
    actorRequest++
    actorAbort?.abort()
    actor?.destroy()
    actor = null
    figure.visible = true
    dirty = true
    reportActorStatus(source ? 'queued' : 'none')
  }

  async function loadActor() {
    if (actorStatus !== 'queued') return
    const request = actorRequest
    const source = actorSource
    const controller = new AbortController()
    actorAbort = controller
    reportActorStatus('loading')
    try {
      const { loadTavernActor } = await import('./tavernActor')
      controller.signal.throwIfAborted()
      const loaded = await loadTavernActor(source, controller.signal, { seed: actorSeed, request: requestAsset(source) })
      if (destroyed || request !== actorRequest) {
        loaded.destroy()
        return
      }
      actor = loaded
      if (currentTurn) actor.beginTurn(currentTurn)
      if (pendingGesture && !reducedMotion && performance.now() - pendingGesture.receivedAt < 3000 && shouldRender() && !document.hidden && !isRenderSuspended()) {
        actor.playGesture(pendingGesture.name, pendingGesture.cue)
      }
      pendingGesture = null
      scene.add(actor.root)
      figure.visible = false
      reportActorStatus('ready')
      dirty = true
    } catch (error) {
      if (controller.signal.aborted || destroyed || request !== actorRequest) return
      console.error('[Tavern] Character model could not be loaded:', error)
      reportActorStatus('error')
      dirty = true
    }
  }

  function buildFigure(next) {
    disposeGroup(headPivot)
    for (const child of [...figure.children]) {
      if (child === headPivot) continue
      child.geometry?.dispose()
      figure.remove(child)
    }
    rings.length = 0
    shape = next

    const headGeo = headGeometry(shape)
    headPoints = new THREE.Points(headGeo.clone(), pointMaterial)
    headPoints.scale.setScalar(0.96)
    headPivot.add(wire(headGeo, brightMaterial), headPoints)

    for (let i = 0; i < shape.rings; i++) {
      const ring = wire(new THREE.TorusGeometry(shape.ringRadius + i * 0.22, 0.008, 6, 48), i === 0 ? brightMaterial : dimMaterial)
      ring.position.set(0, 0, -0.2 - i * 0.04)
      ring.rotation.x = Math.PI * shape.ringTilt * (i % 2 ? -1 : 1)
      rings.push(ring)
      headPivot.add(ring)
    }

    const neck = wire(new THREE.CylinderGeometry(0.22, 0.3, 0.42, 10, 1, true), dimMaterial)
    neck.position.set(0, 0.02, 0)
    figure.add(neck)

    let bodyGeo
    if (shape.body === 'coat') {
      bodyGeo = new THREE.CylinderGeometry(0.62 * shape.shoulderWidth, 1.0 * shape.shoulderWidth, 1.3, shape.shoulderSides, 3, true)
      bodyGeo.scale(1.2, 1, 0.66)
    } else if (shape.body === 'shawl') {
      bodyGeo = new THREE.SphereGeometry(1.15 * shape.shoulderWidth, shape.shoulderSides, 5, 0, Math.PI * 2, 0, Math.PI * 0.5)
      bodyGeo.scale(1.15, 1.0, 0.7)
      bodyGeo.translate(0, -0.72, 0)
    } else {
      bodyGeo = new THREE.ConeGeometry(1.4 * shape.shoulderWidth, 1.25, shape.shoulderSides, 4, true)
      bodyGeo.scale(1.2, 0.95, 0.7)
    }
    const body = wire(bodyGeo, brightMaterial)
    body.position.set(0, -0.82, 0)
    figure.add(body)
    figure.rotation.z = shape.lean
  }

  // ── The room ───────────────────────────────────────────────────────────────
  const room = new THREE.Group()
  scene.add(room)
  let sceneKey = null
  let sceneConfig = BARE_SCENE
  let rain = null
  let embers = null
  let dust = null
  let lamp = null
  let windowBox = null
  let fallbackCup = null
  let roomBuilds = 0

  function buildRoom(key) {
    disposeGroup(room)
    rain = null
    embers = null
    dust = null
    lamp = null
    windowBox = null
    fallbackCup = null
    sceneKey = key
    roomBuilds++
    atmosphere.setScene(key)
    rainMaterial.clippingPlanes = []
    sceneConfig = SCENES[key] || BARE_SCENE
    const rand = mulberry32(hashString(`room|${key}`))

    const counter = new THREE.Mesh(new THREE.BoxGeometry(8.4, 0.12, 1.05), counterMaterial)
    counter.position.set(0, -1.28, 1.3)
    const counterFront = new THREE.Mesh(new THREE.BoxGeometry(8.4, 1.5, 0.12), counterMaterial)
    counterFront.position.set(0, -2.03, 1.72)
    room.add(counter, counterFront)
    room.add(polyline([[-4.2, -1.22, 1.825], [4.2, -1.22, 1.825]], roomMaterial))
    fallbackCup = polyline([
      [1.28, -1.22, 1.2], [1.24, -0.87, 1.2], [1.52, -0.87, 1.2], [1.48, -1.22, 1.2]
    ], roomMaterial)
    room.add(fallbackCup)

    if (sceneConfig.window) {
      const wide = sceneConfig.window === 'wide'
      windowBox = wide
        ? { left: -4.0, right: -1.5, bottom: -0.7, top: 1.75, z: -2.2 }
        : { left: -3.7, right: -2.3, bottom: -0.2, top: 1.5, z: -2.2 }
      const { left, right, bottom, top, z } = windowBox
      const outside = new THREE.Mesh(new THREE.PlaneGeometry(right - left, top - bottom), windowMaterial)
      outside.position.set((left + right) / 2, (bottom + top) / 2, z - 0.12)
      outside.renderOrder = -2
      room.add(outside)
      rainMaterial.clippingPlanes = [
        new THREE.Plane(new THREE.Vector3(0, 1, 0), -bottom), new THREE.Plane(new THREE.Vector3(0, -1, 0), top),
        new THREE.Plane(new THREE.Vector3(1, 0, 0), -left), new THREE.Plane(new THREE.Vector3(-1, 0, 0), right)
      ]
      room.add(polyline([[left, bottom, z], [left, top, z], [right, top, z], [right, bottom, z], [left, bottom, z]], roomMaterial))
      const mid = (left + right) / 2
      room.add(polyline([[mid, bottom, z], [mid, top, z]], roomMaterial))
      room.add(polyline([[left, (bottom + top) / 2, z], [right, (bottom + top) / 2, z]], roomMaterial))

      const count = sceneConfig.rain
      const positions = new Float32Array(count * 6)
      const speeds = new Float32Array(count)
      for (let i = 0; i < count; i++) {
        const x = lerp(left, right, rand())
        const y = lerp(bottom, top, rand())
        const length = 0.12 + rand() * 0.2
        positions.set([x, y, z - 0.05, x - 0.03, y - length, z - 0.05], i * 6)
        speeds[i] = sceneConfig.rainSpeed * (0.7 + rand() * 0.6)
      }
      const geometry = new THREE.BufferGeometry()
      geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3))
      rain = { lines: new THREE.LineSegments(geometry, rainMaterial), positions, speeds, count }
      room.add(rain.lines)
    }

    if (sceneConfig.hearth) {
      const x = 3.0
      const z = -2.0
      room.add(polyline([
        [x - 0.75, -1.4, z], [x - 0.75, -0.25, z], [x - 0.4, 0.1, z], [x + 0.4, 0.1, z], [x + 0.75, -0.25, z], [x + 0.75, -1.4, z]
      ], roomMaterial))
      room.add(polyline([[x - 1.0, -1.4, z], [x + 1.0, -1.4, z]], roomMaterial))
      const count = 26
      const positions = new Float32Array(count * 3)
      const speeds = new Float32Array(count)
      for (let i = 0; i < count; i++) {
        positions.set([x + (rand() - 0.5) * 1.1, -1.35 + rand() * 1.3, z + 0.05], i * 3)
        speeds[i] = 0.25 + rand() * 0.5
      }
      const geometry = new THREE.BufferGeometry()
      geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3))
      embers = { points: new THREE.Points(geometry, emberMaterial), positions, speeds, count, x, z }
      room.add(embers.points)
    }

    if (sceneConfig.shelf) {
      for (let row = 0; row < 3; row++) {
        const y = 0.2 + row * 0.62
        room.add(polyline([[-4.0, y, -2.4], [4.0, y, -2.4]], roomMaterial))
        for (let i = 0; i < 9; i++) {
          const x = -3.6 + i * 0.9 + (rand() - 0.5) * 0.3
          const height = 0.2 + rand() * 0.26
          room.add(polyline([[x, y, -2.4], [x, y + height, -2.4], [x + 0.12, y + height, -2.4], [x + 0.12, y, -2.4]], roomMaterial))
        }
      }
    }

    if (sceneConfig.lamp) {
      lamp = new THREE.Group()
      lamp.position.set(2.2, 2.6, -0.6)
      lamp.add(polyline([[0, 0, 0], [0, -1.1, 0]], roomMaterial))
      lamp.add(polyline([[-0.3, -1.4, 0], [0, -1.1, 0], [0.3, -1.4, 0], [-0.3, -1.4, 0]], brightMaterial))
      room.add(lamp)
    }

    const count = sceneConfig.dust
    const positions = new Float32Array(count * 3)
    const speeds = new Float32Array(count)
    for (let i = 0; i < count; i++) {
      positions.set([(rand() - 0.5) * 8, (rand() - 0.5) * 4.4, (rand() - 0.5) * 3], i * 3)
      speeds[i] = 0.5 + rand()
    }
    const geometry = new THREE.BufferGeometry()
    geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3))
    dust = { points: new THREE.Points(geometry, dustMaterial), positions, speeds, count }
    room.add(dust.points)
  }

  // ── State ──────────────────────────────────────────────────────────────────
  let moodKey = null
  let motionKey = null
  let speaking = false
  const speech = createTavernSpeech()
  let pendingGesture = null
  let currentTurn = null
  let finishIssued = false
  let turnCompleted = false
  let stageTime = 0
  const beginTurn = (id) => {
    if (!id || currentTurn === id) return
    if (currentTurn) speech.reset()
    currentTurn = id
    finishIssued = false
    turnCompleted = false
    pendingGesture = null
    actor?.beginTurn(id)
  }
  let nodStartedAt = -1
  const pose = { pitch: 0, yaw: 0, roll: 0, forward: 0, light: 1, breath: 1, amp: 1, jitter: 0, follow: 1, speak: 0 }
  let gazeX = 0
  let gazeY = 0
  let targetX = 0
  let targetY = 0
  let frames = 0
  let dirty = true

  const onMouseMove = (event) => {
    if (reducedMotion) return
    const rect = container.getBoundingClientRect()
    // Collapsed or hidden: a zero-size rect would turn the gaze into Infinity.
    if (!rect.width || !rect.height) return
    targetX = Math.max(-1.5, Math.min(1.5, ((event.clientX - rect.left) / rect.width) * 2 - 1))
    targetY = Math.max(-1.5, Math.min(1.5, ((event.clientY - rect.top) / rect.height) * 2 - 1))
  }
  const onPointerLeave = () => { targetX = 0; targetY = 0 }
  container.addEventListener('pointermove', onMouseMove, { passive: true })
  container.addEventListener('pointerleave', onPointerLeave)

  const resize = () => {
    if (destroyed) return
    const width = container.clientWidth
    const height = container.clientHeight
    if (!width || !height) return
    camera.aspect = width / height
    camera.updateProjectionMatrix()
    // Settings → 视口渲染分辨率.
    renderer.setPixelRatio(scaledPixelRatio(1.5))
    renderer.setSize(width, height, false)
    dirty = true
  }
  const resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(container)
  resize()
  const stopQualityWatch = onRenderQualityChange(resize)

  const clock = new THREE.Clock()
  let lastFrameAt = 0
  let lastTime = 0

  function step(time, delta, ease) {
    const mood = MOODS[moodKey] || MOODS.calm
    const motion = MOTIONS[motionKey] || MOTIONS.idle

    pose.pitch = lerp(pose.pitch, mood.pitch + motion.pitch, ease)
    pose.yaw = lerp(pose.yaw, mood.yaw + motion.yaw, ease)
    pose.roll = lerp(pose.roll, mood.roll + motion.roll, ease)
    pose.forward = lerp(pose.forward, motion.forward, ease)
    pose.light = lerp(pose.light, mood.light, ease)
    pose.breath = lerp(pose.breath, mood.breath, ease)
    pose.amp = lerp(pose.amp, mood.amp, ease)
    pose.jitter = lerp(pose.jitter, mood.jitter, ease)
    pose.follow = lerp(pose.follow, motion.follow, ease)
    pose.speak = lerp(pose.speak, speaking ? 1 : 0, Math.min(1, ease * 2.5))

    gazeX = lerp(gazeX, targetX, ease)
    gazeY = lerp(gazeY, targetY, ease)

    // Two nods, then still. Started by setState, never on a loop.
    let nod = 0
    if (nodStartedAt >= 0) {
      const t = time - nodStartedAt
      if (t > 1.3) nodStartedAt = -1
      else nod = Math.sin((t / 1.3) * Math.PI * 4) * 0.2 * (1 - t / 1.3)
    }

    const breath = Math.sin(time * 1.5 * pose.breath)
    const jx = pose.jitter ? (Math.sin(time * 31) + Math.sin(time * 47)) * pose.jitter : 0

    figure.position.y = -0.35 + breath * 0.035 * pose.amp
    figure.position.z = pose.forward
    figure.position.x = -0.15 + jx
    figure.rotation.y = pose.yaw * 0.35 + gazeX * 0.12 * pose.follow
    headPivot.rotation.x = pose.pitch + nod + gazeY * 0.16 * pose.follow
    headPivot.rotation.y = pose.yaw * 0.65 + gazeX * 0.3 * pose.follow
    headPivot.rotation.z = pose.roll
    const articulation = speech.update(reducedMotion ? 0 : delta)
    if (articulation.gesture) actor?.playGesture(articulation.gesture, { turnId: currentTurn, cueId: currentTurn, source: 'narration' })
    if (turnCompleted && !finishIssued && !articulation.mouth && speech.snapshot().queued === 0) {
      actor?.finishTurn?.()
      finishIssued = true
    }
    actor?.update({ time, delta, pose, mood: moodKey, motion: motionKey, nod, gazeX, gazeY, reducedMotion, articulation, settled: ease === 1, partner: camera.position })
    if (fallbackCup) fallbackCup.visible = !actor
    const speakPulse = articulation.open * 2
    if (headPoints) headPoints.scale.setScalar(0.96 + speakPulse * 0.035)
    pointMaterial.opacity = Math.min(1, 0.55 + speakPulse * 0.45)
    rings.forEach((ring, index) => {
      ring.rotation.z += delta * (index % 2 ? -0.1 : 0.16) * pose.breath
    })

    const air = atmosphere.update(delta, { reducedMotion, settled: ease === 1 })
    const flicker = air.flicker
    ambient.intensity = air.ambient
    keyLight.intensity = air.key
    rimLight.intensity = air.rim
    roomLight.intensity = sceneConfig.hearth ? air.hearthLight : sceneConfig.lamp ? air.key * 0.7 : 0
    roomLight.position.set(sceneConfig.lamp ? 2.2 : 3, sceneConfig.lamp ? 1.15 : -0.35, sceneConfig.lamp ? -0.4 : -1.35)
    actor?.setLight(1 + Math.max(-0.03, Math.min(0.03, pose.light - 1)))
    brightMaterial.opacity = Math.min(0.88, 0.73 + flicker)
    dimMaterial.opacity = Math.min(0.44, air.line * 0.85)
    roomMaterial.opacity = air.line
    dustMaterial.opacity = air.dust
    windowMaterial.opacity = air.window
    vignette.style.opacity = String(air.vignette)
    if (lamp) lamp.rotation.z = air.lampRotation

    if (rain && windowBox) {
      const { positions, speeds, count } = rain
      const height = windowBox.top - windowBox.bottom
      for (let i = 0; i < count; i++) {
        const fall = speeds[i] * delta
        positions[i * 6 + 1] -= fall
        positions[i * 6 + 4] -= fall
        if (positions[i * 6 + 4] < windowBox.bottom) {
          positions[i * 6 + 1] += height
          positions[i * 6 + 4] += height
        }
      }
      rain.lines.geometry.attributes.position.needsUpdate = true
    }

    if (embers) {
      const { positions, speeds, count, x } = embers
      for (let i = 0; i < count; i++) {
        positions[i * 3 + 1] += speeds[i] * delta
        positions[i * 3] += Math.sin(time * 2 + i) * delta * 0.08
        if (positions[i * 3 + 1] > 0.6) {
          positions[i * 3 + 1] = -1.35
          positions[i * 3] = x + ((i * 0.37) % 1 - 0.5) * 1.1
        }
      }
      embers.points.geometry.attributes.position.needsUpdate = true
      emberMaterial.opacity = Math.max(0.2, 0.7 + flicker * 2)
    }

    if (dust) {
      const { positions, speeds, count } = dust
      const drift = sceneConfig.dustDrift
      for (let i = 0; i < count; i++) {
        positions[i * 3 + 1] += speeds[i] * drift * delta
        if (positions[i * 3 + 1] > 2.2) positions[i * 3 + 1] = -2.2
        else if (positions[i * 3 + 1] < -2.2) positions[i * 3 + 1] = 2.2
      }
      dust.points.geometry.attributes.position.needsUpdate = true
    }
  }

  const drawable = () => !destroyed && !!shape && !!container.clientWidth && !!container.clientHeight

  function renderFrame(time, delta, ease) {
    if (destroyed) return
    stageTime += reducedMotion ? 0 : delta
    step(stageTime, delta, ease)
    renderer.render(scene, camera)
    frames++
  }

  const hush = (reason = 'cancelled') => {
    speech.reset()
    pendingGesture = null
    currentTurn = null
    finishIssued = false
    turnCompleted = false
    nodStartedAt = -1
    actor?.cancelTransient(reason, reason === 'character-change')
    dirty = true
  }
  const pause = () => {
    speech.pause()
    pendingGesture = null
    nodStartedAt = -1
    actor?.cancelTransient('paused')
    targetX = 0; targetY = 0
    dirty = true
  }
  const onVisibility = () => {
    if (document.hidden) pause()
    lastTime = clock.getElapsedTime()
    dirty = true
  }
  document.addEventListener('visibilitychange', onVisibility)

  let paused = false
  let frame = 0
  const animate = (now) => {
    if (destroyed) return
    frame = requestFrame(animate)
    // Settings → 后台失焦智能挂起 is treated exactly like a hidden tab here.
    if (!shouldRender() || document.hidden || isRenderSuspended() || !drawable()) {
      if (!paused) pause()
      paused = true
      lastTime = clock.getElapsedTime()
      cancelFrame(frame)
      frame = { timer: setTimeout(() => animate(performance.now()), 500) }
      return
    }
    paused = false
    if (actorStatus === 'queued') loadActor()
    if (reducedMotion) {
      // No clock: redraw only when something actually changed, already settled.
      if (!dirty) return
      dirty = false
      renderFrame(0, 0, 1)
      return
    }
    if (now - lastFrameAt < frameInterval() - 1) return
    lastFrameAt = now
    const time = clock.getElapsedTime()
    const delta = Math.min(0.1, time - lastTime)
    lastTime = time
    renderFrame(time, delta, Math.min(1, delta * 4))
  }
  frame = requestFrame(animate)

  return {
    /** Whose silhouette is on stage. Rebuilds only when the character changes. */
    setCharacter(character) {
      if (destroyed) return
      const key = String(character?.id ?? '') + '|' + String(characterModelSource(character) ?? '')
      if (key !== actorKey) {
        hush('character-change')
        moodKey = null; motionKey = null; speaking = false
        atmosphere.clearMood()
        gazeX = 0; gazeY = 0; targetX = 0; targetY = 0
      }
      selectActor(character)
      const next = characterShape(character)
      if (shape && shape.seed === next.seed) return
      buildFigure(next)
      dirty = true
    },
    /** Which room. Rebuilds only when the scene changes. */
    setScene(key) {
      if (destroyed) return
      const next = key || null
      if (sceneKey === next && room.children.length) return
      actor?.cancelTransient('scene-change')
      pendingGesture = null
      buildRoom(next)
      dirty = true
    },
    /** Mood and motion as she reported them. Unknown or missing keeps the pose. */
    setState(state, meta = {}) {
      if (destroyed || (meta.roleId != null && !actorKey?.startsWith(String(meta.roleId) + '|'))) return
      const newTurn = !!meta.turnId && meta.turnId !== currentTurn
      if (meta.turnId) beginTurn(meta.turnId)
      const mood = state?.mood && MOODS[state.mood] ? state.mood : null
      const motion = state?.motion && MOTIONS[state.motion] ? state.motion : null
      if (state?.gesture && meta.source !== 'stored' && !meta.replay && !reducedMotion && shouldRender() && !document.hidden && !isRenderSuspended()) {
        const cue = { turnId: currentTurn, cueId: currentTurn, source: meta.source || 'state' }
        if (actor) actor.playGesture(state.gesture, cue)
        else pendingGesture = { name: state.gesture, cue, receivedAt: performance.now() }
      }
      if (mood) { moodKey = mood; atmosphere.setMood(mood) }
      if (motion) {
        if (MOTIONS[motion].nod && !meta.replay && meta.source !== 'stored' && !reducedMotion && (motion !== motionKey || newTurn)) nodStartedAt = stageTime
        motionKey = motion
      }
      dirty = true
    },
    /** Back to the neutral pose, e.g. when switching to a character with no reported state. */
    clearState() {
      moodKey = null
      motionKey = null
      nodStartedAt = -1
      atmosphere.clearMood()
      dirty = true
    },
    setSpeaking(value) {
      const wasSpeaking = speaking
      speaking = !!value
      finishIssued = false
      if (speaking) turnCompleted = false
      else if (wasSpeaking) { turnCompleted = true; speech.finish() }
      dirty = true
    },
    speak(text, meta = {}) {
      if (destroyed || (meta.roleId != null && !actorKey?.startsWith(String(meta.roleId) + '|'))) return
      if (meta.turnId) beginTurn(meta.turnId)
      speech.push(text, reducedMotion || !shouldRender() || document.hidden || isRenderSuspended())
      dirty = true
    },
    beginTurn,
    hush,
    pause,
    setReducedMotion(value) {
      reducedMotion = !!value
      pause()
      nodStartedAt = -1
      lastTime = clock.getElapsedTime()
      dirty = true
    },
    /** What the stage is showing, as data. */
    snapshot() {
      return {
        character: { type: actor ? 'vrm' : 'silhouette', status: actorStatus, source: actorSource, ...actor?.snapshot() },
        renderer: { calls: renderer.info.render.calls, triangles: renderer.info.render.triangles, geometries: renderer.info.memory.geometries, textures: renderer.info.memory.textures },
        seed: shape?.seed ?? null,
        head: shape?.head ?? null,
        body: shape?.body ?? null,
        rings: shape?.rings ?? null,
        scene: sceneKey,
        mood: moodKey,
        motion: motionKey,
        speaking,
        speech: speech.snapshot(),
        frames,
        width: container.clientWidth,
        height: container.clientHeight,
        pose: { ...pose },
        turnId: currentTurn,
        reducedMotion,
        atmosphere: atmosphere.snapshot(),
        room: { rain: rain?.count ?? 0, embers: embers?.count ?? 0, dust: dust?.count ?? 0, lamp: !!lamp, window: !!windowBox, builds: roomBuilds,
          cups: actor || fallbackCup?.visible ? 1 : 0, localLight: { source: sceneConfig.hearth ? 'hearth' : sceneConfig.lamp ? 'lamp' : null, intensity: roomLight.intensity },
          keyIntensity: keyLight.intensity, windowOpacity: windowMaterial.opacity }
      }
    },
    /**
     * Draw one settled frame and read it back: how much of the canvas is lit,
     * and how bright. Lets a test tell "the room is drawn" from a black canvas
     * without anyone looking at it.
     */
    sample() {
      if (!drawable()) return null
      renderFrame(clock.getElapsedTime(), 0, 1)
      const gl = renderer.getContext()
      const width = gl.drawingBufferWidth
      const height = gl.drawingBufferHeight
      const pixels = new Uint8Array(width * height * 4)
      gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, pixels)
      const half = Math.floor(width / 2)
      let lit = 0
      let litLeft = 0
      let sum = 0
      let maxDeviation = 0
      for (let y = 0; y < height; y++) {
        for (let x = 0; x < width; x++) {
          const i = (y * width + x) * 4
          const alpha = pixels[i + 3]
          if (alpha <= 8) continue
          lit++
          if (x < half) litLeft++
          sum += alpha
          // Monochrome check: r, g and b must stay together.
          const deviation = Math.max(pixels[i], pixels[i + 1], pixels[i + 2]) - Math.min(pixels[i], pixels[i + 1], pixels[i + 2])
          if (deviation > maxDeviation) maxDeviation = deviation
        }
      }
      return {
        width,
        height,
        lit,
        litLeft,
        litRatio: lit / (width * height),
        meanAlpha: lit ? sum / lit : 0,
        maxChannelDeviation: maxDeviation
      }
    },
    destroy() {
      if (destroyed) return
      destroyed = true
      actorRequest++
      actorAbort?.abort()
      actor?.destroy()
      actor = null
      cancelFrame(frame)
      stopQualityWatch()
      container.removeEventListener('pointermove', onMouseMove)
      container.removeEventListener('pointerleave', onPointerLeave)
      document.removeEventListener('visibilitychange', onVisibility)
      resizeObserver.disconnect()
      scene.traverse((node) => node.geometry?.dispose())
      sharedMaterials.forEach((material) => material.dispose())
      renderer.dispose()
      renderer.forceContextLoss()
      renderer.domElement.remove()
      vignette.remove()
    }
  }
}

/**
 * The header badge shown while the stage is collapsed: the same character's
 * head, projected onto a 2D canvas. No WebGL context — the tavern keeps exactly
 * one, and only while the stage is open.
 */
export function mountTavernMini(container, { shouldRender = () => true, reducedMotion = false } = {}) {
  const canvas = document.createElement('canvas')
  canvas.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;display:block'
  container.appendChild(canvas)
  const context = canvas.getContext('2d')
  let edges = null
  let seed = null
  let angle = 0.6
  let dirty = true
  let lastFrameAt = 0
  let frames = 0
  const stopMiniQualityWatch = onRenderQualityChange(() => { dirty = true; draw() })

  function draw() {
    const ratio = scaledPixelRatio(2)
    const width = container.clientWidth
    const height = container.clientHeight
    if (!width || !height || !edges) return
    if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
      canvas.width = Math.round(width * ratio)
      canvas.height = Math.round(height * ratio)
    }
    context.setTransform(ratio, 0, 0, ratio, 0, 0)
    context.clearRect(0, 0, width, height)
    const scale = Math.min(width, height) * 0.48
    const cos = Math.cos(angle)
    const sin = Math.sin(angle)
    context.strokeStyle = 'rgba(255, 255, 255, 0.85)'
    context.lineWidth = 0.7
    context.beginPath()
    for (let i = 0; i < edges.length; i += 6) {
      const ax = edges[i] * cos + edges[i + 2] * sin
      const bx = edges[i + 3] * cos + edges[i + 5] * sin
      context.moveTo(width / 2 + ax * scale, height / 2 - edges[i + 1] * scale)
      context.lineTo(width / 2 + bx * scale, height / 2 - edges[i + 4] * scale)
    }
    context.stroke()
    frames++
  }

  let frame = 0
  const animate = (now) => {
    frame = requestFrame(animate)
    if (!shouldRender() || document.hidden || isRenderSuspended()) return
    if (reducedMotion) {
      if (!dirty) return
      dirty = false
      draw()
      return
    }
    // A badge this small does not need more than 20 fps.
    if (now - lastFrameAt < 50) return
    // Clamp the step: after a pause the badge resumes turning, it does not spin to catch up.
    angle += (Math.min(now - (lastFrameAt || now), 100) / 1000) * 0.5
    lastFrameAt = now
    draw()
  }
  frame = requestFrame(animate)

  return {
    setCharacter(character) {
      const shape = characterShape(character)
      if (shape.seed === seed) return
      seed = shape.seed
      const solid = headGeometry({ ...shape, headDetail: 1 })
      const wireframe = new THREE.WireframeGeometry(solid)
      edges = Float32Array.from(wireframe.attributes.position.array)
      solid.dispose()
      wireframe.dispose()
      dirty = true
    },
    setReducedMotion(value) {
      reducedMotion = !!value
      dirty = true
      lastFrameAt = 0
    },
    snapshot() {
      return { seed, edges: edges ? edges.length / 6 : 0, frames, webgl: false }
    },
    destroy() {
      cancelFrame(frame)
      stopMiniQualityWatch()
      canvas.remove()
    }
  }
}

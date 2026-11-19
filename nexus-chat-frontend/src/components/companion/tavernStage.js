import * as THREE from 'three'

/**
 * The tavern stage: the room a character is sitting in, drawn in white line.
 *
 * Everything here answers to something real:
 *   - the figure's shape is derived from the character (id + name), so two
 *     characters never share a silhouette and one character always has the same;
 *   - the room is the open scene (window and rain, hearth, back shelf, lamp);
 *   - posture and tempo follow the mood and motion the model reported with its
 *     last reply (see CompanionStateTag on the server);
 *   - "speaking" is on only while reply text is actually arriving.
 * Nothing moves because of a timer pretending to be a mind.
 *
 * One WebGL context, capped at 30 fps, and nothing is drawn while collapsed,
 * zero-sized, or in a background tab.
 */

const FRAME_MS = 1000 / 30

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

/**
 * Mount the stage into `container`.
 * @returns controller: setCharacter, setScene, setState, setSpeaking, sample, snapshot, destroy
 */
export function mountTavernStage(container, { shouldRender = () => true, reducedMotion = false } = {}) {
  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(42, 1.5, 0.1, 100)
  camera.position.set(0, 0.1, 6.4)
  const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: 'low-power' })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5))
  renderer.domElement.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;display:block'
  container.appendChild(renderer.domElement)

  const brightMaterial = new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.85 })
  const dimMaterial = new THREE.LineBasicMaterial({ color: 0x8e9192, transparent: true, opacity: 0.45 })
  const roomMaterial = new THREE.LineBasicMaterial({ color: 0x8e9192, transparent: true, opacity: 0.38 })
  const rainMaterial = new THREE.LineBasicMaterial({ color: 0xc4c7c8, transparent: true, opacity: 0.42 })
  const pointMaterial = new THREE.PointsMaterial({ color: 0xffffff, size: 0.045, transparent: true, opacity: 0.8 })
  const dustMaterial = new THREE.PointsMaterial({ color: 0xffffff, size: 0.028, transparent: true, opacity: 0.5 })
  const emberMaterial = new THREE.PointsMaterial({ color: 0xffffff, size: 0.05, transparent: true, opacity: 0.85 })
  const sharedMaterials = [brightMaterial, dimMaterial, roomMaterial, rainMaterial, pointMaterial, dustMaterial, emberMaterial]

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

  function buildRoom(key) {
    disposeGroup(room)
    rain = null
    embers = null
    dust = null
    lamp = null
    windowBox = null
    sceneKey = key
    sceneConfig = SCENES[key] || BARE_SCENE
    const rand = mulberry32(hashString(`room|${key}`))

    // The counter she sits at: the one thing every room has.
    room.add(polyline([[-4.2, -1.55, 1.2], [4.2, -1.55, 1.2]], brightMaterial))
    room.add(polyline([[-4.2, -1.72, 1.2], [4.2, -1.72, 1.2]], roomMaterial))
    // A glass on it.
    room.add(polyline([
      [1.28, -1.55, 1.2], [1.24, -1.2, 1.2], [1.52, -1.2, 1.2], [1.48, -1.55, 1.2]
    ], roomMaterial))

    if (sceneConfig.window) {
      const wide = sceneConfig.window === 'wide'
      windowBox = wide
        ? { left: -4.0, right: -1.5, bottom: -0.7, top: 1.75, z: -2.2 }
        : { left: -3.7, right: -2.3, bottom: -0.2, top: 1.5, z: -2.2 }
      const { left, right, bottom, top, z } = windowBox
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
  let nodStartedAt = -1
  const pose = { pitch: 0, yaw: 0, roll: 0, forward: 0, light: 1, breath: 1, amp: 1, jitter: 0, follow: 1, speak: 0 }
  let gazeX = 0
  let gazeY = 0
  let targetX = 0
  let targetY = 0
  let frames = 0
  let dirty = true

  const onMouseMove = (event) => {
    const rect = container.getBoundingClientRect()
    // Collapsed or hidden: a zero-size rect would turn the gaze into Infinity.
    if (!rect.width || !rect.height) return
    targetX = Math.max(-1.5, Math.min(1.5, ((event.clientX - rect.left) / rect.width) * 2 - 1))
    targetY = Math.max(-1.5, Math.min(1.5, ((event.clientY - rect.top) / rect.height) * 2 - 1))
  }
  if (!reducedMotion) window.addEventListener('mousemove', onMouseMove)

  const resize = () => {
    const width = container.clientWidth
    const height = container.clientHeight
    if (!width || !height) return
    camera.aspect = width / height
    camera.updateProjectionMatrix()
    renderer.setSize(width, height, false)
    dirty = true
  }
  const resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(container)
  resize()

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
    // Speaking: the face points pulse with the words coming in.
    const speakPulse = pose.speak * (0.5 + 0.5 * Math.sin(time * 14))
    if (headPoints) headPoints.scale.setScalar(0.96 + speakPulse * 0.035)
    pointMaterial.opacity = Math.min(1, 0.55 + speakPulse * 0.45)
    rings.forEach((ring, index) => {
      ring.rotation.z += delta * (index % 2 ? -0.1 : 0.16) * pose.breath
    })

    // The room's light: the scene's level, her mood, and a hearth's flicker.
    const flicker = sceneConfig.flicker
      ? (Math.sin(time * 7.3) * 0.5 + Math.sin(time * 12.1) * 0.3 + Math.sin(time * 2.9) * 0.2) * sceneConfig.flicker
      : 0
    const lampSwing = lamp ? Math.sin(time * 0.7) : 0
    const lampDim = lamp ? 0.82 + 0.18 * Math.sin(time * 0.35) : 1
    const light = Math.max(0.2, sceneConfig.light * pose.light * lampDim + flicker)
    brightMaterial.opacity = Math.min(1, 0.8 * light)
    dimMaterial.opacity = Math.min(1, 0.42 * light)
    roomMaterial.opacity = Math.min(1, 0.36 * sceneConfig.light * lampDim + flicker * 0.5)
    if (lamp) lamp.rotation.z = lampSwing * 0.07

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

  const drawable = () => !!shape && !!container.clientWidth && !!container.clientHeight

  function renderFrame(time, delta, ease) {
    step(time, delta, ease)
    renderer.render(scene, camera)
    frames++
  }

  let frame = 0
  const animate = (now) => {
    frame = requestAnimationFrame(animate)
    if (!shouldRender() || document.hidden || !drawable()) return
    if (reducedMotion) {
      // No clock: redraw only when something actually changed, already settled.
      if (!dirty) return
      dirty = false
      renderFrame(0, 0, 1)
      return
    }
    if (now - lastFrameAt < FRAME_MS - 1) return
    lastFrameAt = now
    const time = clock.getElapsedTime()
    const delta = Math.min(0.1, time - lastTime)
    lastTime = time
    renderFrame(time, delta, Math.min(1, delta * 4))
  }
  frame = requestAnimationFrame(animate)

  return {
    /** Whose silhouette is on stage. Rebuilds only when the character changes. */
    setCharacter(character) {
      const next = characterShape(character)
      if (shape && shape.seed === next.seed) return
      buildFigure(next)
      dirty = true
    },
    /** Which room. Rebuilds only when the scene changes. */
    setScene(key) {
      const next = key || null
      if (sceneKey === next && room.children.length) return
      buildRoom(next)
      dirty = true
    },
    /** Mood and motion as she reported them. Unknown or missing keeps the pose. */
    setState(state) {
      const mood = state?.mood && MOODS[state.mood] ? state.mood : null
      const motion = state?.motion && MOTIONS[state.motion] ? state.motion : null
      if (mood) moodKey = mood
      if (motion) {
        motionKey = motion
        if (MOTIONS[motion].nod && !reducedMotion) nodStartedAt = clock.getElapsedTime()
      }
      dirty = true
    },
    /** Back to the neutral pose, e.g. when switching to a character with no reported state. */
    clearState() {
      moodKey = null
      motionKey = null
      nodStartedAt = -1
      dirty = true
    },
    setSpeaking(value) {
      speaking = !!value
      dirty = true
    },
    /** What the stage is showing, as data. */
    snapshot() {
      return {
        seed: shape?.seed ?? null,
        head: shape?.head ?? null,
        body: shape?.body ?? null,
        rings: shape?.rings ?? null,
        scene: sceneKey,
        mood: moodKey,
        motion: motionKey,
        speaking,
        frames,
        width: container.clientWidth,
        height: container.clientHeight,
        pose: { ...pose },
        room: { rain: rain?.count ?? 0, embers: embers?.count ?? 0, dust: dust?.count ?? 0, lamp: !!lamp, window: !!windowBox }
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
      cancelAnimationFrame(frame)
      window.removeEventListener('mousemove', onMouseMove)
      resizeObserver.disconnect()
      scene.traverse((node) => node.geometry?.dispose())
      sharedMaterials.forEach((material) => material.dispose())
      renderer.dispose()
      renderer.forceContextLoss()
      renderer.domElement.remove()
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

  function draw() {
    const ratio = Math.min(window.devicePixelRatio || 1, 2)
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
    frame = requestAnimationFrame(animate)
    if (!shouldRender() || document.hidden) return
    if (reducedMotion) {
      if (!dirty) return
      dirty = false
      draw()
      return
    }
    // A badge this small does not need more than 20 fps.
    if (now - lastFrameAt < 50) return
    angle += ((now - (lastFrameAt || now)) / 1000) * 0.5
    lastFrameAt = now
    draw()
  }
  frame = requestAnimationFrame(animate)

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
    snapshot() {
      return { seed, edges: edges ? edges.length / 6 : 0, frames, webgl: false }
    },
    destroy() {
      cancelAnimationFrame(frame)
      canvas.remove()
    }
  }
}

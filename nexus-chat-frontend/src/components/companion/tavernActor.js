import { createResourceOwner } from './resourceOwner.js'
import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm'
import { createTavernExpression } from './tavernExpression.js'
import { createTavernGaze } from './tavernGaze.js'
import { calibrateCupGrip, calibrateHold, createCupAttachment } from './tavernCup.js'
import { createTavernLife } from './tavernLife.js'
import { createTavernGestures, seatedPose } from './tavernGestures.js'
import { createTavernBeats } from './tavernBeats.js'
import { createTavernArms } from './tavernArms.js'
import { measureTavernBody, approximateTavernBody } from './tavernBody.js'
import { styleTavernMaterial } from './tavernMaterial.js'
import { COUNTER } from './tavernLayout.js'

// What the rest of her does while a hand is busy, at the top of the gesture.
// pitch: + looks down. yaw: + turns to her left. roll: + tilts toward her right hand.
const POSTURE = {
  chin: { lean: 0.05, pitch: 0.05, yaw: 0, roll: 0.06 },
  sip: { lean: 0.02, pitch: -0.04, yaw: 0, roll: 0 },
  wipe: { lean: 0.04, pitch: 0.13, yaw: 0.03, roll: 0 },
  wave: { lean: 0, pitch: -0.02, yaw: 0, roll: -0.05 },
  shrug: { lean: 0, pitch: -0.02, yaw: 0, roll: 0.07 },
  cover: { lean: 0.01, pitch: 0.05, yaw: 0, roll: 0 },
  cross: { lean: -0.04, pitch: -0.01, yaw: 0, roll: 0 },
  tuck: { lean: 0, pitch: 0.03, yaw: 0.07, roll: 0.07 }
}
const NO_POSTURE = { lean: 0, pitch: 0, yaw: 0, roll: 0 }

export async function loadTavernActor(url, signal, { seed = 0, request = { url, headers: {} } } = {}) {
  const response = await fetch(request.url, { signal, headers: request.headers })
  if (!response.ok) throw new Error(`Character model request failed (${response.status})`)
  const buffer = await response.arrayBuffer()
  signal.throwIfAborted()
  const owner = createResourceOwner({ ownImages: !THREE.Cache.enabled })
  const cancel = () => owner.retire()
  signal.addEventListener('abort', cancel, { once: true })
  try {
    const loader = new GLTFLoader(new THREE.LoadingManager())
    loader.register(parser => {
      owner.bindParser(parser)
      return { name: 'NexusResourceOwner', beforeRoot: () => owner.prepareEmbeddedImages(parser) }
    })
    loader.register(parser => new VRMLoaderPlugin(parser))
    // Abort stops fetch, not GLTFLoader's already-running parse. Late dependencies
    // remain registered with the retired owner and may never be applied.
    const gltf = await loader.parseAsync(buffer, new URL('.', new URL(request.url, window.location.href)).href)
    owner.releaseURLs()
    owner.track(gltf.scene)
    signal.throwIfAborted()
    const vrm = gltf.userData.vrm
    if (!vrm?.humanoid) throw new Error('Character model has no VRM humanoid')
    const actor = createActor(vrm, gltf.parser.json, seed, owner)
    signal.throwIfAborted()
    return actor
  } catch (error) {
    owner.retire()
    throw error
  } finally {
    signal.removeEventListener('abort', cancel)
  }
}

function blobTexture() {
  const pixels = new Uint8Array(32 * 32 * 4)
  for (let y = 0; y < 32; y++) for (let x = 0; x < 32; x++) {
    const alpha = Math.max(0, 1 - Math.hypot((x - 15.5) / 15.5, (y - 15.5) / 15.5)) ** 2
    pixels[(y * 32 + x) * 4 + 3] = Math.round(alpha * 180)
  }
  const texture = new THREE.DataTexture(pixels, 32, 32, THREE.RGBAFormat)
  texture.needsUpdate = true
  return texture
}

function createActor(vrm, json, seed, owner) {
  VRMUtils.removeUnnecessaryVertices(vrm.scene)
  VRMUtils.removeUnnecessaryJoints(vrm.scene)
  if (vrm.meta.metaVersion === '0') VRMUtils.rotateVRM0(vrm)
  const root = new THREE.Group()
  root.add(vrm.scene)
  owner.track(root)
  root.updateMatrixWorld(true)
  const bones = new Map()
  const names = ['hips', 'spine', 'chest', 'upperChest', 'neck', 'head', 'leftEye', 'rightEye']
  for (const side of ['left', 'right']) {
    for (const part of ['Shoulder', 'UpperArm', 'LowerArm', 'Hand', 'UpperLeg', 'LowerLeg', 'Foot']) names.push(`${side}${part}`)
    for (const finger of ['Index', 'Middle', 'Ring', 'Little']) {
      for (const joint of ['Proximal', 'Intermediate', 'Distal']) names.push(`${side}${finger}${joint}`)
    }
    for (const joint of ['Metacarpal', 'Proximal', 'Distal']) names.push(`${side}Thumb${joint}`)
  }
  for (const name of names) {
    const bone = vrm.humanoid.getNormalizedBoneNode(name)
    if (bone) bones.set(name, {
      bone, rest: bone.quaternion.clone(), position: bone.getWorldPosition(new THREE.Vector3()),
      frame: bone.getWorldQuaternion(new THREE.Quaternion()),
      inverseFrame: bone.getWorldQuaternion(new THREE.Quaternion()).invert()
    })
  }
  const head = bones.get('head')
  const hips = bones.get('hips')
  if (!head || !hips) throw new Error('Character model has no head or hips')
  const torso = head.position.y - hips.position.y
  const bounds = new THREE.Box3().setFromObject(vrm.scene)
  const scale = Math.min(2.3 / torso, 1.65 / (bounds.max.y - head.position.y))
  if (!Number.isFinite(scale) || scale <= 0) throw new Error('Character model has invalid humanoid proportions')
  const shoulderWidth = bones.has('leftUpperArm') && bones.has('rightUpperArm')
    ? bones.get('leftUpperArm').position.distanceTo(bones.get('rightUpperArm').position) : torso * 0.45
  // Her own body as collision volumes, measured while she still stands at rest.
  const body = measureTavernBody(vrm, bones) || approximateTavernBody(bones)
  if (!body) throw new Error('Character model body could not be measured')
  const seat = seatedPose(seed)
  let entropy = seed >>> 0
  const random = () => { entropy = (Math.imul(entropy, 1664525) + 1013904223) >>> 0; return entropy / 4294967296 }
  const life = createTavernLife(random)
  const gestures = createTavernGestures()
  const beats = createTavernBeats({ random })
  const expressions = createTavernExpression(vrm, json)
  const gaze = createTavernGaze(vrm)
  const light = { value: 1 }
  const materials = new Set()
  vrm.scene.traverse(node => {
    if (!node.isMesh) return
    node.frustumCulled = false
    for (const material of Array.isArray(node.material) ? node.material : [node.material]) materials.add(material)
  })
  materials.forEach(material => styleTavernMaterial(material, light))

  const rotation = new THREE.Quaternion()
  const localRotation = new THREE.Quaternion()
  const rootRotation = new THREE.Quaternion()
  const euler = new THREE.Euler(0, 0, 0, 'YXZ')
  const rotate = (name, x, y, z) => {
    const entry = bones.get(name)
    if (!entry) return
    rotation.setFromEuler(euler.set(x, y, z, 'YXZ'))
    localRotation.copy(entry.inverseFrame).multiply(rotation).multiply(entry.frame)
    entry.bone.quaternion.copy(entry.rest).multiply(localRotation)
  }
  const point = new THREE.Vector3()
  const desired = new THREE.Vector3()
  const basis = new THREE.Matrix4()

  // ── Seat ───────────────────────────────────────────────────────────────────
  // A stool with no back: nothing behind her for her hair to pass through.
  const fabric = new THREE.MeshStandardMaterial({ color: 0x303030, roughness: 1 })
  const stool = new THREE.Mesh(new THREE.CylinderGeometry(torso * 0.5, torso * 0.5, torso * 0.12, 24), fabric)
  stool.position.copy(hips.position).add(new THREE.Vector3(0, -torso * 0.2, 0))
  root.add(stool)

  // ── Props ──────────────────────────────────────────────────────────────────
  const cupSize = { radius: torso * 0.064, height: torso * 0.155 }
  const cupMaterial = new THREE.MeshStandardMaterial({ color: 0xb7b9b9, roughness: 0.42 })
  const cupInside = new THREE.MeshStandardMaterial({ color: 0x252727, roughness: 0.8 })
  const cup = new THREE.Group()
  const wall = new THREE.Mesh(new THREE.CylinderGeometry(cupSize.radius, cupSize.radius * 0.84, cupSize.height, 28, 1, true), cupMaterial)
  const base = new THREE.Mesh(new THREE.CircleGeometry(cupSize.radius * 0.84, 28), cupMaterial)
  base.rotation.x = Math.PI / 2; base.position.y = -cupSize.height / 2
  const rim = new THREE.Mesh(new THREE.TorusGeometry(cupSize.radius, torso * 0.005, 6, 28), cupMaterial)
  rim.rotation.x = Math.PI / 2; rim.position.y = cupSize.height / 2
  const contents = new THREE.Mesh(new THREE.CircleGeometry(cupSize.radius * 0.93, 28), cupInside)
  contents.rotation.x = -Math.PI / 2; contents.position.y = cupSize.height * 0.3
  cup.add(wall, base, rim, contents)
  root.add(cup)
  const clothMaterial = new THREE.MeshStandardMaterial({ color: 0xc9caca, roughness: 1 })
  const cloth = new THREE.Group()
  const fold = new THREE.Mesh(new THREE.BoxGeometry(torso * 0.2, torso * 0.012, torso * 0.15), clothMaterial)
  const flap = new THREE.Mesh(new THREE.BoxGeometry(torso * 0.19, torso * 0.01, torso * 0.08), clothMaterial)
  flap.position.set(torso * 0.004, torso * 0.011, -torso * 0.03); flap.rotation.y = 0.05
  cloth.add(fold, flap)
  root.add(cloth)
  owner.track(root)

  root.scale.setScalar(scale)
  root.rotation.y = seat.yaw
  point.copy(head.position).multiplyScalar(scale).applyEuler(root.rotation)
  root.position.set(seat.x - point.x, seat.hipY + torso * scale - point.y, -point.z)
  root.updateMatrixWorld(true)
  // She sits behind the counter, never in it: move back until her front clears its edge.
  body.prepare()
  let overlap = 0
  for (let height = 0.02; height <= 0.62; height += 0.04) {
    for (const across of [-0.55, 0, 0.55]) {
      body.torsoPoint(across, height, 1, point)
      if (point.y < COUNTER.top + 0.08) overlap = Math.max(overlap, point.z - (COUNTER.back - 0.07))
    }
  }
  if (overlap > 0) { root.position.z -= overlap; root.updateMatrixWorld(true) }

  const arms = createTavernArms({ root, bones, body, unit: torso, scale, seat, cup: cupSize })
  const hands = arms.hands
  const cupTable = arms.cupTable, clothTable = arms.clothTable
  const cupWorld = new THREE.Vector3(), clothWorld = new THREE.Vector3()
  const rawRightHand = vrm.humanoid.getRawBoneNode('rightHand') || hands.right?.bone
  const rawLeftHand = vrm.humanoid.getRawBoneNode('leftHand') || hands.left?.bone
  const cupGrip = hands.right ? calibrateCupGrip(hands.right.bone, rawRightHand, hands.right.frame, cupSize.radius) : null
  const clothGrip = hands.left ? calibrateHold(hands.left.bone, rawLeftHand, hands.left.frame,
    arms.palmContact.map(value => value * hands.left.palm)) : null
  const tolerance = torso * scale * 0.03
  const cupOwner = createCupAttachment(cupTable, tolerance)
  const clothOwner = createCupAttachment(clothTable, tolerance * 1.6)
  const actualGrip = new THREE.Vector3()
  const propFinger = new THREE.Vector3(), propPalm = new THREE.Vector3(), propAcross = new THREE.Vector3()
  const propRotation = new THREE.Quaternion(), inverseRoot = new THREE.Quaternion()
  let cupLift = 0

  // Soft contact shadows on the counter: the glass and each hand.
  const shadowTexture = blobTexture()
  const shadows = ['cup', 'left', 'right'].map(name => {
    const material = new THREE.MeshBasicMaterial({ map: shadowTexture, transparent: true, opacity: 0, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -1 })
    const size = name === 'cup' ? [0.36, 0.28] : [0.44, 0.34]
    const mesh = new THREE.Mesh(new THREE.PlaneGeometry(torso * size[0], torso * size[1]), material)
    mesh.rotation.x = -Math.PI / 2
    root.add(mesh)
    return { name, mesh, material }
  })

  const partnerPoint = new THREE.Vector3(0, 0.42, 6.9)
  const gazeHead = new THREE.Vector3()
  const interest = new THREE.Vector3()
  const lookPoint = new THREE.Vector3()
  let hasInterest = false
  const lastHead = { pitch: 0, yaw: 0, roll: 0 }
  let headFollow = { pitch: 0, yaw: 0 }
  let lastGesture = null
  let physiology = null
  let beat = null
  let talk = 0
  let clock = 0
  let lean = 0
  let worstDepth = 0, depthNow = 0
  let springsReady = false
  let springsFrozen = false
  let springResets = 0
  let destroyed = false

  function follow(holder, grip, rawHand, world, table, active, gesture, mesh) {
    if (grip && rawHand && !gesture.reducedMotion) {
      rawHand.localToWorld(actualGrip.copy(grip.rawLocal))
      world.copy(holder.update({ handGrip: actualGrip, progress: gesture.weight, active, releasing: gesture.phase === 'returning' }))
    } else { holder.reset(); world.copy(table) }
    mesh.position.copy(root.worldToLocal(point.copy(world)))
    return holder.snapshot().attachment !== 'table'
  }

  return {
    root,
    beginTurn(id) { gestures.beginTurn(id) },
    /** The reply is over. Whatever she is doing finishes on its own time. */
    finishTurn() {},
    playGesture(name, cue = {}) {
      if (destroyed) return false
      if (name !== 'rest' && !arms.sides(name).every(side => hands[side])) return false
      if (name === 'sip' && !cupGrip) return false
      if (name === 'wipe' && (!cupGrip || !clothGrip)) return false
      return gestures.play(name, cue)
    },
    /** A small reaction of the head and shoulders. Never moves an arm. */
    playBeat(name, options = {}) {
      if (destroyed) return false
      if (name === 'lookAway' && hasInterest && options.side == null) {
        options = { ...options, side: interest.x >= gazeHead.x ? 1 : -1 }
      }
      return beats.play(name, options)
    },
    /** True while a gesture or a reaction is in progress. */
    isBusy() { return !!lastGesture && lastGesture.active !== 'rest' || !!beats.active() },
    /** Something in the room worth a glance: the window, the fire, the lamp. */
    setInterest(value) {
      hasInterest = !!value
      if (value) interest.copy(value)
    },
    cancelTransient(reason = 'cancelled', immediate = true) {
      if (immediate) gestures.reset(reason)
      else gestures.cancel(reason)
      beats.reset()
      gaze.reset()
      expressions.stopMouth()
      if (immediate) {
        springsFrozen = true
        cupLift = 0; cupOwner.reset(); clothOwner.reset(); cupWorld.copy(cupTable); clothWorld.copy(clothTable)
        arms.rebase()
      }
    },
    setLight(value) { light.value = Math.max(0.82, Math.min(1.04, value)) },
    update({ delta, pose, mood, motion, nod = 0, gazeX, gazeY, reducedMotion, articulation = {}, settled, partner, attention = 0 }) {
      if (destroyed || owner.retired) return
      delta = Number.isFinite(delta) ? Math.max(0, Math.min(0.1, delta)) : 0
      clock += reducedMotion ? 0 : delta
      physiology = life.update(delta, reducedMotion, pose.breath)
      const { breath, sway, drift, eyeX, eyeY } = physiology
      beat = beats.update(delta, reducedMotion)
      const gesture = gestures.update(delta, motion, reducedMotion)
      gesture.reducedMotion = reducedMotion
      lastGesture = gesture
      const posture = POSTURE[gesture.active] || NO_POSTURE
      const weight = gesture.weight
      // A little life in the head while she talks, on top of the per-syllable emphasis.
      talk += ((articulation.mouth && !reducedMotion ? 1 : 0) - talk) * (1 - Math.exp(-delta / 0.35))
      const emphasis = reducedMotion ? 0 : (articulation.open || 0) * 0.018 + (articulation.emphasis || 0) * 0.04
      const yaw = pose.yaw * 0.55 + headFollow.yaw + drift - seat.yaw * 0.6 + beat.yaw + posture.yaw * weight + Math.sin(clock * 1.3 + 1) * 0.02 * talk
      const pitch = pose.pitch * 0.6 + nod * 0.65 + headFollow.pitch + emphasis + beat.pitch + posture.pitch * weight + Math.sin(clock * 2.1) * 0.012 * talk
      const roll = pose.roll * 0.65 + beat.roll + posture.roll * weight + Math.sin(clock * 0.9) * 0.012 * talk
      // Quick enough that a nod still reads as a nod.
      const headEase = reducedMotion || settled ? 1 : 1 - Math.exp(-delta * 12)
      lastHead.pitch += (pitch - lastHead.pitch) * headEase
      lastHead.yaw += (yaw - lastHead.yaw) * headEase
      lastHead.roll += (roll - lastHead.roll) * headEase
      const leaning = (reducedMotion ? 0 : beat.lean + posture.lean * weight + attention)
      lean += (leaning - lean) * (reducedMotion || settled ? 1 : 1 - Math.exp(-delta * 5))
      rotate('hips', seat.lean * 0.3, 0, seat.yaw * 0.035)
      rotate('spine', seat.lean + pose.forward * 0.09 + lean * 0.5, pose.yaw * 0.08, sway + beat.sway)
      rotate('chest', breath + pose.forward * 0.1 + lean * 0.5, 0, -sway * 0.5)
      rotate('upperChest', breath * 0.5, 0, 0)
      rotate('neck', lastHead.pitch * 0.35, lastHead.yaw * 0.35, lastHead.roll * 0.3)
      rotate('head', lastHead.pitch * 0.65, lastHead.yaw * 0.65, lastHead.roll * 0.7 + drift)
      const touchingMouth = cupOwner.snapshot().attachment === 'rightHand'
        && cup.localToWorld(point.set(0, cupSize.height / 2, 0)).distanceTo(body.headPoint(body.landmarks.mouth, desired)) < torso * scale * 0.12
      const blockMouth = !!gesture.blocksMouth || touchingMouth
      expressions.update({ mood, delta, articulation, blink: Math.max(physiology.blink, beat.blink), reducedMotion, settled, blockMouth, accent: beat.accent })
      for (const side of ['left', 'right']) {
        const sign = side === 'left' ? 1 : -1
        rotate(`${side}UpperLeg`, seat.thigh, sign * 0.04, sign * 0.045)
        // Feet tucked back under the stool, clear of the counter's front.
        rotate(`${side}LowerLeg`, -seat.thigh + 0.5, 0, 0)
        rotate(`${side}Foot`, -0.35, 0, 0)
        const lift = Math.max(-0.06, Math.min(0.24, (gesture.lift?.[side] || 0) + beat.shoulders * 0.06 + breath * 1.2))
        rotate(`${side}Shoulder`, 0, 0, sign * lift)
        rotate(`${side}UpperArm`, 0, 0, sign * -0.85)
        rotate(`${side}LowerArm`, 0, 0, 0)
      }
      root.updateMatrixWorld(true)
      root.getWorldQuaternion(rootRotation)
      body.prepare()
      const cupHeld = cupOwner.snapshot().attachment !== 'table'
      const clothHeld = clothOwner.snapshot().attachment !== 'table'
      depthNow = arms.update({ gesture, delta, reducedMotion, settled, cupAttached: cupHeld, clothAttached: clothHeld, cupWorld })
      worstDepth = Math.max(worstDepth, depthNow)
      root.updateMatrixWorld(true)
      head.bone.getWorldPosition(gazeHead)
      if (partner) partnerPoint.copy(partner)
      let actionTarget = null
      if (['sip', 'wipe'].includes(gesture.active)) actionTarget = cupWorld
      let lookTarget = null
      if (beat.gaze === 'cup') lookTarget = cupWorld
      else if (beat.gaze === 'down') lookTarget = lookPoint.set(gazeHead.x, COUNTER.top, (COUNTER.back + COUNTER.front) / 2)
      else if (beat.gaze === 'scene') lookTarget = hasInterest ? interest : lookPoint.set(gazeHead.x + Math.sign(beat.yaw || 1) * 3, gazeHead.y, gazeHead.z - 2)
      headFollow = gaze.update({ delta, head: gazeHead, partner: partnerPoint, actionTarget,
        action: gesture.active, actionWeight: gesture.weight, motion, lookTarget, lookWeight: beat.gazeWeight,
        pointerX: gazeX * pose.follow, pointerY: gazeY * pose.follow, eyeX, eyeY, reducedMotion, settled })
      if (reducedMotion) {
        // Freeze simulation, not its remembered tail. Rebase when it resumes.
        vrm.update(0)
        springsFrozen = true
      } else {
        if (!springsReady || springsFrozen) {
          vrm.update(0)
          if (vrm.springBoneManager) { vrm.springBoneManager.reset(); springResets++ }
          springsReady = true
          springsFrozen = false
        }
        vrm.update(delta)
      }
      root.updateMatrixWorld(true)
      // Props follow the final raw skeleton AFTER humanoid/lookAt/spring update.
      inverseRoot.copy(rootRotation).invert()
      const holding = ['sip', 'wipe'].includes(gesture.active)
      if (follow(cupOwner, cupGrip, rawRightHand, cupWorld, cupTable, holding, gesture, cup)) {
        propFinger.copy(cupGrip.rawFinger).transformDirection(rawRightHand.matrixWorld)
        propPalm.copy(cupGrip.rawPalm).transformDirection(rawRightHand.matrixWorld)
        propPalm.addScaledVector(propFinger, -propPalm.dot(propFinger)).normalize()
        // The glass stands along the thumb side of the hand that holds it.
        propAcross.crossVectors(propFinger, propPalm).normalize()
        basis.makeBasis(propPalm, propAcross, propFinger)
        cup.quaternion.copy(inverseRoot).multiply(propRotation.setFromRotationMatrix(basis))
      } else cup.quaternion.identity()
      if (follow(clothOwner, clothGrip, rawLeftHand, clothWorld, clothTable, gesture.active === 'wipe', gesture, cloth)) {
        propFinger.copy(clothGrip.rawFinger).transformDirection(rawLeftHand.matrixWorld)
        propPalm.copy(clothGrip.rawPalm).transformDirection(rawLeftHand.matrixWorld)
        propPalm.addScaledVector(propFinger, -propPalm.dot(propFinger)).normalize()
        propAcross.crossVectors(propFinger, propPalm).normalize()
        // Flat against her palm.
        basis.makeBasis(propFinger, propPalm, propAcross)
        cloth.quaternion.copy(inverseRoot).multiply(propRotation.setFromRotationMatrix(basis))
      } else cloth.quaternion.identity()
      const heightAboveTable = Math.max(0, cupWorld.y - cupTable.y)
      cupLift = Math.min(1, heightAboveTable / (torso * scale * 0.45))
      for (const shadow of shadows) {
        let height = heightAboveTable, reach = cupSize.height * scale * 2
        if (shadow.name === 'cup') point.copy(cupWorld)
        else if (hands[shadow.name]) {
          const hand = hands[shadow.name]
          hand.bone.getWorldPosition(point)
          point.addScaledVector(desired.copy(hand.axes[0]).applyQuaternion(hand.bone.getWorldQuaternion(rotation)), hand.palm * scale * 0.7)
          height = point.y - COUNTER.top
          reach = torso * scale * 0.2
        } else { shadow.material.opacity = 0; continue }
        const over = point.z > COUNTER.back + 0.05 && point.z < COUNTER.front
        shadow.material.opacity = over ? 0.3 * Math.max(0, 1 - Math.max(0, height) / reach) : 0
        shadow.mesh.position.copy(root.worldToLocal(point.set(point.x, COUNTER.top + 0.005, point.z)))
      }
      root.updateMatrixWorld(true)
    },
    resourceSnapshot() { return owner.snapshot() },
    snapshot() {
      const joints = {}
      for (const name of ['hips', 'leftUpperLeg', 'leftLowerLeg', 'leftFoot', 'rightUpperArm', 'rightLowerArm', 'rightHand', 'leftLowerArm', 'leftHand']) {
        const entry = bones.get(name)
        if (entry) joints[name] = entry.bone.getWorldPosition(new THREE.Vector3()).toArray()
      }
      return {
        expressions: expressions.snapshot(), head: { ...lastHead },
        headQuaternion: vrm.humanoid.getRawBoneNode('head')?.quaternion.toArray() ?? null,
        eyeRotations: { left: vrm.humanoid.getRawBoneNode('leftEye')?.quaternion.toArray() ?? null, right: vrm.humanoid.getRawBoneNode('rightEye')?.quaternion.toArray() ?? null },
        eyeTracking: !!vrm.lookAt, gaze: gaze.snapshot(), physiology,
        gesture: gestures.snapshot(), beat: beats.snapshot(), springs: { ready: springsReady, frozen: springsFrozen, resets: springResets },
        seating: { ...seat, torso, shoulderWidth, scale }, joints,
        body: { source: body.source, depth: depthNow, worstDepth, ...body.snapshot() },
        cup: { count: 1, ...cupOwner.snapshot(), lift: cupLift,
          position: cupWorld.toArray(), table: cupTable.toArray(), contactShadow: 'stylized', shadowOpacity: shadows[0].material.opacity,
          rimToMouthAnchor: cup.localToWorld(new THREE.Vector3(0, cupSize.height / 2, 0)).distanceTo(body.headPoint(body.landmarks.mouth, new THREE.Vector3())) },
        cloth: { ...clothOwner.snapshot(), attachment: clothOwner.snapshot().attachment === 'table' ? 'table' : 'leftHand', table: clothTable.toArray() },
        wrist: { right: vrm.humanoid.getRawBoneNode('rightHand')?.quaternion.toArray() ?? null },
        arms: arms.snapshot()
      }
    },
    /** Forget the deepest contact seen so far; used when checking one gesture at a time. */
    resetDepth() { worstDepth = 0 },
    destroy() {
      if (destroyed) return
      destroyed = true
      gaze.reset()
      expressions.reset()
      // Everything under the root (model, stool, glass, cloth, contact shadows and
      // their shared texture) is released once by the owner that loaded it.
      owner.track(root)
      owner.retire()
    }
  }
}

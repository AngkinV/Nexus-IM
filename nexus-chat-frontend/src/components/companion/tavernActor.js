import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm'
import { createTavernExpression } from './tavernExpression.js'
import { createTavernGaze } from './tavernGaze.js'
import { sipPath, calibrateCupGrip, createCupAttachment } from './tavernCup.js'
import { createTavernLife } from './tavernLife.js'
import { createTavernGestures, seatedPose } from './tavernGestures.js'
import { styleTavernMaterial } from './tavernMaterial.js'
import { createLimbSolver, calibrateHand } from './tavernRig.js'

const HAND_DIRECTIONS = {
  table: [0.1, 0, 1], lap: [0.2, -0.25, 1], chin: [0, 1, 0], sip: [0, 1, 0],
  wipe: [0, 1, 0], wave: [0, 1, 0], shrug: [0, 0.1, 1], cover: [1, 0.15, 0],
  cross: [1, 0.1, 0], tuck: [0, 1, 0]
}
const PALM_NORMALS = {
  table: [0, -1, 0], lap: [0, -1, 0], chin: [0, 0, -1], sip: [1, 0, 0],
  wipe: [1, 0, 0], wave: [0, 0, 1], shrug: [0, 1, 0], cover: [0, 0, -1],
  cross: [0, 0, -1], tuck: [1, 0, 0]
}
const CURL = { chin: 1.2, sip: 0.95, wipe: 0.45, wave: 0.06, shrug: 0.16, cover: 0.12, cross: 0.32, tuck: 0.2 }
const TABLE_Y = -1.22

export async function loadTavernActor(url, signal, { seed = 0, request = { url, headers: {} } } = {}) {
  const response = await fetch(request.url, { signal, headers: request.headers })
  if (!response.ok) throw new Error(`Character model request failed (${response.status})`)
  const buffer = await response.arrayBuffer()
  signal.throwIfAborted()
  const loader = new GLTFLoader()
  loader.register(parser => new VRMLoaderPlugin(parser))
  const gltf = await loader.parseAsync(buffer, new URL('.', new URL(request.url, window.location.href)).href)
  const vrm = gltf.userData.vrm
  if (signal.aborted || !vrm?.humanoid) {
    VRMUtils.deepDispose(gltf.scene)
    signal.throwIfAborted()
    throw new Error('Character model has no VRM humanoid')
  }
  try {
    return createActor(vrm, gltf.parser.json, seed)
  } catch (error) {
    VRMUtils.deepDispose(gltf.scene)
    throw error
  }
}

function createActor(vrm, json, seed) {
  VRMUtils.removeUnnecessaryVertices(vrm.scene)
  VRMUtils.removeUnnecessaryJoints(vrm.scene)
  if (vrm.meta.metaVersion === '0') VRMUtils.rotateVRM0(vrm)
  const root = new THREE.Group()
  root.add(vrm.scene)
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
  const seat = seatedPose(seed)
  let entropy = seed >>> 0
  const random = () => { entropy = (Math.imul(entropy, 1664525) + 1013904223) >>> 0; return entropy / 4294967296 }
  const life = createTavernLife(random)
  const gestures = createTavernGestures()
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
  const parentRotation = new THREE.Quaternion()
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
  const target = new THREE.Vector3()
  const gestureTarget = new THREE.Vector3()
  const pole = new THREE.Vector3()
  const fingerDirection = new THREE.Vector3()
  const palmNormal = new THREE.Vector3()
  const tangent = new THREE.Vector3()
  const desired = new THREE.Vector3()
  const basis = new THREE.Matrix4()
  const eyes = bones.get('leftEye')?.position
  const eyeHeight = eyes ? eyes.y - head.position.y : torso * 0.15
  const faceDepth = Math.max((eyes?.z ?? head.position.z) - head.position.z + torso * 0.1, torso * 0.12)
  const facialPoints = {
    chin: new THREE.Vector3(0, eyeHeight * 0.04, faceDepth),
    mouth: new THREE.Vector3(0, eyeHeight * 0.3, faceDepth),
    temple: new THREE.Vector3(-shoulderWidth * 0.52, eyeHeight * 1.1, faceDepth * 0.35)
  }
  // Prefer the model's real oral mesh over a guessed head/eye proportion.
  let mouthMesh = null
  vrm.scene.traverse(node => {
    const list = Array.isArray(node.material) ? node.material : [node.material]
    if (!mouthMesh && node.isMesh && list.some(material => /FaceMouth/i.test(material?.name || ''))) mouthMesh = node
  })
  if (mouthMesh) {
    const mouthBounds = new THREE.Box3().setFromObject(mouthMesh)
    if (!mouthBounds.isEmpty()) {
      facialPoints.mouth.set((mouthBounds.min.x + mouthBounds.max.x) / 2, (mouthBounds.min.y + mouthBounds.max.y) / 2, mouthBounds.max.z + torso * 0.01).sub(head.position)
      facialPoints.chin.copy(facialPoints.mouth).add(new THREE.Vector3(0, -torso * 0.045, 0))
    }
  }
  for (const value of Object.values(facialPoints)) value.applyQuaternion(head.inverseFrame)
  const anchor = (name, x, y, z, out) => {
    const entry = bones.get(name) || hips
    out.set(x, y, z).applyQuaternion(entry.inverseFrame)
    return entry.bone.localToWorld(out)
  }
  const facialAnchor = (name, out) => head.bone.localToWorld(out.copy(facialPoints[name]))
  const armAnchor = (side, x, y, z, out) => {
    bones.get(`${side}UpperArm`).bone.getWorldPosition(out)
    return out.add(point.set(x, y, z).multiplyScalar(scale).applyQuaternion(rootRotation))
  }
  const hands = {}
  for (const side of ['left', 'right']) {
    const hand = bones.get(`${side}Hand`)
    const upper = bones.get(`${side}UpperArm`)
    const lower = bones.get(`${side}LowerArm`)
    if (!hand || !upper || !lower) continue
    const frame = calibrateHand(hand.bone, bones.get(`${side}IndexProximal`)?.bone,
      bones.get(`${side}MiddleProximal`)?.bone, bones.get(`${side}LittleProximal`)?.bone, side)
    const fingers = []
    if (frame) {
      for (const [name, entry] of bones) {
        if (!name.startsWith(side) || !/Index|Middle|Ring|Little|Thumb/.test(name)) continue
        const axis = frame.curlAxis.clone().applyQuaternion(hand.frame).applyQuaternion(entry.inverseFrame)
        fingers.push({ ...entry, axis, factor: name.includes('Thumb') ? 0.3 : name.endsWith('Proximal') ? 0.7 : name.endsWith('Distal') ? 0.6 : 1 })
      }
    }
    const orientations = {}
    if (frame) {
      for (const [name, direction] of Object.entries(HAND_DIRECTIONS)) {
        fingerDirection.fromArray(direction).normalize()
        palmNormal.fromArray(PALM_NORMALS[name])
        if (side === 'left') { fingerDirection.x *= -1; palmNormal.x *= -1 }
        palmNormal.addScaledVector(fingerDirection, -palmNormal.dot(fingerDirection)).normalize()
        tangent.crossVectors(palmNormal, fingerDirection).normalize()
        basis.makeBasis(fingerDirection, tangent, palmNormal)
        orientations[name] = new THREE.Quaternion().setFromRotationMatrix(basis).multiply(frame.rotation)
      }
    }
    hands[side] = { frame, fingers, orientations, hand: hand.bone, solver: createLimbSolver(upper.bone, lower.bone, hand.bone), target: new THREE.Vector3(), initialized: false }
  }

  const furniture = new THREE.Group()
  const wood = new THREE.MeshStandardMaterial({ color: 0x383838, roughness: 0.9 })
  const fabric = new THREE.MeshStandardMaterial({ color: 0x303030, roughness: 1 })
  const stool = new THREE.Mesh(new THREE.CylinderGeometry(torso * 0.55, torso * 0.55, torso * 0.12, 24), fabric)
  stool.position.copy(hips.position).add(new THREE.Vector3(0, -torso * 0.2, 0))
  const back = new THREE.Mesh(new THREE.BoxGeometry(torso * 0.95, torso * 0.9, torso * 0.1), wood)
  back.position.copy(hips.position).add(new THREE.Vector3(0, torso * 0.28, -torso * 0.3))
  furniture.add(stool, back)
  root.add(furniture)
  const cupMaterial = new THREE.MeshStandardMaterial({ color: 0xb7b9b9, roughness: 0.52 })
  const cupInside = new THREE.MeshStandardMaterial({ color: 0x252727, roughness: 0.8 })
  const cup = new THREE.Group()
  const cupHeight = torso * 0.13
  const vessel = new THREE.Mesh(new THREE.CylinderGeometry(torso * 0.065, torso * 0.055, cupHeight, 24, 1, true), cupMaterial)
  const rim = new THREE.Mesh(new THREE.TorusGeometry(torso * 0.065, torso * 0.006, 6, 24), cupMaterial)
  rim.rotation.x = Math.PI / 2; rim.position.y = cupHeight / 2
  const contents = new THREE.Mesh(new THREE.CircleGeometry(torso * 0.059, 24), cupInside)
  contents.rotation.x = -Math.PI / 2; contents.position.y = cupHeight * 0.4
  const handle = new THREE.Mesh(new THREE.TorusGeometry(torso * 0.04, torso * 0.008, 6, 18, Math.PI * 1.55), cupMaterial)
  handle.position.x = torso * 0.078; handle.rotation.z = -Math.PI * 0.275
  cup.add(vessel, rim, contents, handle)
  root.add(cup)
  // One primary cup: a stable table anchor, or the final rendered right hand.
  const cupTable = new THREE.Vector3(seat.x - 0.48, TABLE_Y + cupHeight * scale / 2 + 0.01, 0.96)
  const cupWorld = cupTable.clone()
  const rawRightHand = vrm.humanoid.getRawBoneNode('rightHand') || hands.right?.hand
  const cupGrip = hands.right?.frame ? calibrateCupGrip(hands.right.hand, rawRightHand, hands.right.frame, torso * 0.065) : null
  const cupOwner = createCupAttachment(cupTable, torso * scale * 0.016)
  const cupRotation = new THREE.Quaternion(), gripOrientation = new THREE.Quaternion()
  const gripScale = new THREE.Vector3(), actualGrip = new THREE.Vector3()
  const cupFinger = new THREE.Vector3(), cupPalm = new THREE.Vector3(), cupForward = new THREE.Vector3()
  let cupLift = 0
  const shadowPixels = new Uint8Array(32 * 32 * 4)
  for (let y = 0; y < 32; y++) for (let x = 0; x < 32; x++) {
    const alpha = Math.max(0, 1 - Math.hypot((x - 15.5) / 15.5, (y - 15.5) / 15.5)) ** 2
    shadowPixels[(y * 32 + x) * 4 + 3] = Math.round(alpha * 180)
  }
  const shadowTexture = new THREE.DataTexture(shadowPixels, 32, 32, THREE.RGBAFormat)
  shadowTexture.needsUpdate = true
  const shadowMaterial = new THREE.MeshBasicMaterial({ map: shadowTexture, transparent: true, opacity: 0.28,
    depthWrite: false, polygonOffset: true, polygonOffsetFactor: -1 })
  const contactShadow = new THREE.Mesh(new THREE.PlaneGeometry(torso * 0.35, torso * 0.27), shadowMaterial)
  contactShadow.rotation.x = -Math.PI / 2
  root.add(contactShadow)

  root.scale.setScalar(scale)
  root.rotation.y = seat.yaw
  point.copy(head.position).multiplyScalar(scale).applyEuler(root.rotation)
  root.position.set(seat.x - point.x, seat.hipY + torso * scale - point.y, -point.z)
  root.updateMatrixWorld(true)
  cup.position.copy(root.worldToLocal(cupTable.clone()))
  contactShadow.position.copy(root.worldToLocal(new THREE.Vector3(cupTable.x, TABLE_Y + 0.005, cupTable.z)))
  const partnerPoint = new THREE.Vector3(0, 0.1, 6.4)
  const gazeHead = new THREE.Vector3()
  const lastHead = { pitch: 0, yaw: 0 }
  let headFollow = { pitch: 0, yaw: 0 }
  let lastGesture = null
  let physiology = null
  let springsReady = false
  let springsFrozen = false
  let springResets = 0
  let destroyed = false

  function restTarget(side, out) {
    const sign = side === 'left' ? 1 : -1
    if (seat[side] === 'table') {
      anchor('hips', sign * shoulderWidth * 0.8, torso * 0.25, torso * 0.45, out)
      out.y = TABLE_Y + 0.055
    } else {
      anchor(`${side}UpperLeg`, sign * torso * 0.06, -torso * 0.2, torso * 0.26, out)
    }
    return out
  }

  function cupGripOffset(out, progress = 0.5) {
    const path = sipPath(progress)
    cupRotation.copy(rootRotation).multiply(localRotation.setFromEuler(euler.set(-0.18 * path.lift, 0, 0)))
    gripOrientation.copy(cupRotation).multiply(hands.right.orientations.sip)
    return out.copy(cupGrip.normalizedLocal).multiply(hands.right.hand.getWorldScale(gripScale)).applyQuaternion(gripOrientation)
  }

  function placeGesture(side, name, out, age, progress = 1) {
    const sign = side === 'left' ? 1 : -1
    const palm = hands[side].frame?.palmLength || torso * 0.12
    if (name === 'sip' && cupGrip) {
      const offset = cupGripOffset(desired, progress)
      facialAnchor('mouth', out)
      out.sub(point.set(0, cupHeight / 2, -torso * 0.065).multiplyScalar(scale).applyQuaternion(cupRotation))
      out.sub(offset)
      return
    }
    if (name === 'chin' || name === 'cover' || name === 'sip' || name === 'tuck') {
      facialAnchor(name === 'tuck' ? 'temple' : name === 'chin' ? 'chin' : 'mouth', out)
      if (name === 'chin') point.set(-palm * 0.2, -palm * 1.15, torso * 0.1)
      if (name === 'sip') point.set(-torso * 0.1, -torso * 0.13, torso * 0.13)
      if (name === 'cover') point.set(-palm * 0.8, -palm * 0.2, torso * 0.06)
      if (name === 'tuck') point.set(-palm * 0.25, -palm * 0.6, torso * 0.04)
      out.add(point.multiplyScalar(scale).applyQuaternion(rootRotation))
    } else if (name === 'cross') {
      armAnchor(side === 'right' ? 'left' : 'right', sign * palm * 0.7, -torso * 0.28, torso * 0.4 + (side === 'left' ? torso * 0.1 : 0), out)
    } else if (name === 'wave') {
      armAnchor(side, sign * torso * 0.45, torso * 0.48, torso * 0.24, out)
      out.x += Math.sin(age * 7) * torso * scale * 0.035
    } else if (name === 'shrug') {
      armAnchor(side, sign * torso * 0.45, -torso * 0.18, torso * 0.52, out)
    } else if (name === 'wipe') {
      out.copy(cupTable)
      if (side === 'right' && cupGrip) out.sub(cupGripOffset(desired))
      else out.add(point.set(torso * 0.09, -torso * 0.045 + Math.sin(age * 5) * torso * 0.02, -torso * 0.02).multiplyScalar(scale).applyQuaternion(rootRotation))
    }
  }

  function orientHand(side, name, weight, ease, wrist = null, tilt = 0) {
    const { hand, frame, orientations } = hands[side]
    if (!frame) return
    rotation.copy(orientations[seat[side]]).slerp(orientations[name] || orientations[seat[side]], weight)
    // Wrist recipes are already phased by the gesture track. Applying them here
    // (after IK) makes wave's oscillation reach the actual hand bone.
    if (wrist && weight > 0) rotation.multiply(localRotation.setFromEuler(euler.set(...wrist, 'YXZ')))
    if (tilt) rotation.premultiply(localRotation.setFromEuler(euler.set(tilt, 0, 0)))
    rotation.premultiply(rootRotation)
    hand.parent.getWorldQuaternion(parentRotation).invert()
    hand.quaternion.slerp(parentRotation.multiply(rotation), ease)
    hand.updateWorldMatrix(false, true)
  }

  return {
    root,
    beginTurn(id) { gestures.beginTurn(id) },
    finishTurn() { gestures.cancel('completed') },
    playGesture(name, cue = {}) {
      if (destroyed) return false
      if (name !== 'rest' && !hands.right) return false
      if (['sip', 'wipe'].includes(name) && !cupGrip) return false
      if (['cross', 'shrug', 'wipe'].includes(name) && !hands.left) return false
      return gestures.play(name, cue)
    },
    cancelTransient(reason = 'cancelled', immediate = true) {
      if (immediate) gestures.reset?.(reason)
      else gestures.cancel?.(reason)
      if (!gestures.reset && immediate) gestures.play('rest')
      gaze.reset()
      expressions.stopMouth()
      if (immediate) {
        springsFrozen = true
        cupLift = 0; cupOwner.reset(); cupWorld.copy(cupTable)
        for (const hand of Object.values(hands)) hand.initialized = false
      }
    },
    setLight(value) { light.value = Math.max(0.82, Math.min(1.04, value)) },
    update({ delta, pose, mood, motion, nod, gazeX, gazeY, reducedMotion, articulation = {}, settled, partner }) {
      if (destroyed) return
      delta = Number.isFinite(delta) ? Math.max(0, Math.min(0.1, delta)) : 0
      const ease = reducedMotion || settled ? 1 : 1 - Math.exp(-delta * 5)
      physiology = life.update(delta, reducedMotion, pose.breath)
      const { breath, blink, sway, drift, eyeX, eyeY } = physiology
      const emphasis = reducedMotion ? 0 : (articulation.open || 0) * 0.018 + (articulation.emphasis || 0) * 0.04
      const yaw = pose.yaw * 0.55 + headFollow.yaw + drift - seat.yaw * 0.6
      const pitch = pose.pitch * 0.6 + nod * 0.65 + headFollow.pitch + emphasis
      const headEase = reducedMotion || settled ? 1 : 1 - Math.exp(-delta * 3.5)
      lastHead.pitch += (pitch - lastHead.pitch) * headEase
      lastHead.yaw += (yaw - lastHead.yaw) * headEase
      rotate('hips', seat.lean * 0.3, 0, seat.yaw * 0.035)
      rotate('spine', seat.lean + pose.forward * 0.09, pose.yaw * 0.08, sway)
      rotate('chest', breath + pose.forward * 0.1, 0, -sway * 0.5)
      rotate('upperChest', breath * 0.5, 0, 0)
      rotate('neck', lastHead.pitch * 0.35, lastHead.yaw * 0.35, pose.roll * 0.25)
      rotate('head', lastHead.pitch * 0.65, lastHead.yaw * 0.65, pose.roll * 0.4 + drift)
      const gesture = gestures.update(delta, motion, reducedMotion, { speaking: !!articulation.open })
      lastGesture = gesture
      const touchingMouth = cupOwner.snapshot().attachment === 'rightHand'
        && cup.localToWorld(point.set(0, cupHeight / 2, -torso * 0.065)).distanceTo(facialAnchor('mouth', desired)) < torso * scale * 0.05
      const blockMouth = !!gesture.blocksMouth || touchingMouth
      expressions.update({ mood, delta, articulation, blink, reducedMotion, settled, blockMouth })
      for (const side of ['left', 'right']) {
        const sign = side === 'left' ? 1 : -1
        rotate(`${side}UpperLeg`, seat.thigh, sign * 0.04, sign * 0.045)
        rotate(`${side}LowerLeg`, -seat.thigh + 0.08, 0, 0)
        rotate(`${side}Foot`, -0.1, 0, 0)
        const shoulderPose = gesture.pose?.[`${side}Shoulder`] || [0, 0, sign * 0.015]
        rotate(`${side}Shoulder`, shoulderPose[0] * 0.3, shoulderPose[1] * 0.3, Math.max(-0.16, Math.min(0.16, shoulderPose[2])))
        rotate(`${side}UpperArm`, 0, 0, sign * -0.85)
        rotate(`${side}LowerArm`, 0, 0, 0)
      }
      root.updateMatrixWorld(true)
      root.getWorldQuaternion(rootRotation)
      const returningCup = cupOwner.snapshot().attachment === 'rightHand' && gesture.active !== 'sip'
      for (const [side, hand] of Object.entries(hands)) {
        const forcedReturn = side === 'right' && returningCup
        const posed = side === 'right' || ['shrug', 'cross', 'wipe'].includes(gesture.active)
        const thinking = !reducedMotion && motion === 'think' && side === 'right' && gesture.active === 'rest'
        const name = forcedReturn ? 'sip' : thinking ? 'chin' : posed && gesture.active !== 'rest' ? gesture.active : seat[side]
        const weight = forcedReturn ? 0.5 : thinking ? 1 : posed ? gesture.weight : 0
        const sip = name === 'sip' ? sipPath(weight) : null
        restTarget(side, target)
        if (weight > 0) {
          placeGesture(side, name, gestureTarget, gesture.age, weight)
          if (side === 'right' && name === 'sip' && cupGrip) {
            point.copy(cupTable).sub(cupGripOffset(desired, weight))
            if (!sip.lift) target.lerp(point, sip.reach)
            else target.copy(point).lerp(gestureTarget, sip.lift)
          } else target.lerp(gestureTarget, weight)
        }
        const handEase = reducedMotion || settled || !hand.initialized ? 1 : 1 - Math.exp(-delta / (name === 'sip' ? 0.055 : 0.1))
        hand.target.lerp(target, handEase)
        hand.initialized = true
        pole.set(side === 'left' ? 0.45 : -0.45, name === 'cross' ? -0.6 : -1, name === 'cross' ? 0.65 : -0.2).applyQuaternion(rootRotation)
        hand.solver.solve(hand.target, pole)
        const orientationWeight = sip ? sip.reach : weight
        const wristRecipe = ['sip', 'wipe'].includes(name) ? null : gesture.pose?.[`${side}Hand`]
        orientHand(side, side === 'right' && name === 'wipe' ? 'sip' : name, orientationWeight, handEase, wristRecipe, sip ? -0.18 * sip.lift : 0)
        const fingerCurl = side === 'right' && name === 'wipe' ? 0.85 : CURL[name] ?? 0.14
        const curl = 0.14 + (fingerCurl - 0.14) * orientationWeight
        for (const finger of hand.fingers) {
          rotation.setFromAxisAngle(finger.axis, curl * finger.factor)
          finger.bone.quaternion.copy(finger.rest).multiply(rotation)
        }
      }
      root.updateMatrixWorld(true)
      head.bone.getWorldPosition(gazeHead)
      if (partner) partnerPoint.copy(partner)
      let actionTarget = null
      if (['sip', 'wipe'].includes(gesture.active)) actionTarget = cupWorld
      else if (['chin', 'cover', 'tuck'].includes(gesture.active) && hands.right) {
        actionTarget = hands.right.hand.getWorldPosition(desired)
      }
      headFollow = gaze.update({ delta, head: gazeHead, partner: partnerPoint, actionTarget,
        action: gesture.active, actionWeight: gesture.weight, motion,
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
      if (cupGrip && rawRightHand && !reducedMotion) {
        rawRightHand.localToWorld(actualGrip.copy(cupGrip.rawLocal))
        cupWorld.copy(cupOwner.update({ handGrip: actualGrip, progress: gesture.weight,
          active: gesture.active === 'sip', releasing: gesture.phase === 'returning' }))
      } else { cupOwner.reset(); cupWorld.copy(cupTable) }
      cup.position.copy(root.worldToLocal(point.copy(cupWorld)))
      if (cupOwner.snapshot().attachment === 'rightHand') {
        cupFinger.copy(cupGrip.rawFinger).transformDirection(rawRightHand.matrixWorld)
        cupPalm.copy(cupGrip.rawPalm).transformDirection(rawRightHand.matrixWorld)
        cupPalm.addScaledVector(cupFinger, -cupPalm.dot(cupFinger)).normalize()
        cupForward.crossVectors(cupPalm, cupFinger).normalize()
        basis.makeBasis(cupPalm, cupFinger, cupForward)
        cupRotation.setFromRotationMatrix(basis)
        cup.quaternion.copy(parentRotation.copy(rootRotation).invert().multiply(cupRotation))
      } else cup.quaternion.identity()
      const heightAboveTable = Math.max(0, cupWorld.y - cupTable.y)
      cupLift = Math.min(1, heightAboveTable / (torso * scale * 0.45))
      contactShadow.position.copy(root.worldToLocal(point.set(cupTable.x, TABLE_Y + 0.005, cupTable.z)))
      shadowMaterial.opacity = 0.28 * Math.max(0, 1 - heightAboveTable / (cupHeight * scale * 2))
      root.updateMatrixWorld(true)
    },
    snapshot() {
      const joints = {}
      for (const name of ['hips', 'leftUpperLeg', 'leftLowerLeg', 'leftFoot', 'rightUpperArm', 'rightLowerArm', 'rightHand']) {
        const entry = bones.get(name)
        if (entry) joints[name] = entry.bone.getWorldPosition(new THREE.Vector3()).toArray()
      }
      return {
        expressions: expressions.snapshot(), head: { ...lastHead },
        headQuaternion: vrm.humanoid.getRawBoneNode('head')?.quaternion.toArray() ?? null,
        eyeRotations: { left: vrm.humanoid.getRawBoneNode('leftEye')?.quaternion.toArray() ?? null, right: vrm.humanoid.getRawBoneNode('rightEye')?.quaternion.toArray() ?? null },
        eyeTracking: !!vrm.lookAt, gaze: gaze.snapshot(), physiology,
        gesture: gestures.snapshot(), springs: { ready: springsReady, frozen: springsFrozen, resets: springResets }, seating: { ...seat, torso, shoulderWidth }, joints,
        cup: { count: 1, ...cupOwner.snapshot(), lift: cupLift,
          position: cupWorld.toArray(), table: cupTable.toArray(), contactShadow: 'stylized', shadowOpacity: shadowMaterial.opacity,
          rimToMouthAnchor: cup.localToWorld(new THREE.Vector3(0, cupHeight / 2, -torso * 0.065)).distanceTo(facialAnchor('mouth', new THREE.Vector3())) },
        wrist: { right: vrm.humanoid.getRawBoneNode('rightHand')?.quaternion.toArray() ?? null },
        arms: Object.fromEntries(Object.entries(hands).map(([side, hand]) => [side, hand.solver.snapshot()]))
      }
    },
    destroy() {
      if (destroyed) return
      destroyed = true
      gaze.reset()
      expressions.reset()
      root.removeFromParent()
      furniture.traverse(node => node.geometry?.dispose())
      wood.dispose()
      fabric.dispose()
      cup.traverse(node => node.geometry?.dispose())
      cupMaterial.dispose()
      cupInside.dispose()
      contactShadow.geometry.dispose()
      shadowMaterial.dispose()
      shadowTexture.dispose()
      VRMUtils.deepDispose(vrm.scene)
    }
  }
}

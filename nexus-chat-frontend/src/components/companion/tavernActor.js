import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm'
import { createVrmExpressions } from './vrmExpressions.js'
import { MOUTHS } from './tavernSpeech.js'
import { createTavernLife } from './tavernLife.js'
import { createTavernGestures, seatedPose } from './tavernGestures.js'
import { styleTavernMaterial } from './tavernMaterial.js'
import { createLimbSolver, calibrateHand } from './tavernRig.js'

const FACES = {
  calm: { relaxed: 0.15 }, warm: { relaxed: 0.25, happy: 0.35 }, happy: { happy: 0.7 },
  shy: { happy: 0.22, relaxed: 0.2 }, sad: { sad: 0.65 }, worried: { sad: 0.3, surprised: 0.12 },
  angry: { angry: 0.65 }, surprised: { surprised: 0.7 }, tired: { relaxed: 0.2, sad: 0.18 },
  thoughtful: { relaxed: 0.1 }
}
const FACE_NAMES = ['happy', 'relaxed', 'sad', 'angry', 'surprised']
const HAND_DIRECTIONS = {
  table: [0.1, 0, 1], lap: [0.2, -0.25, 1], chin: [0, 1, 0], sip: [0, 1, 0],
  wipe: [0.8, 0, 0.3], wave: [0, 1, 0], shrug: [0, 0.1, 1], cover: [1, 0.15, 0],
  cross: [1, 0.1, 0], tuck: [0, 1, 0]
}
const PALM_NORMALS = {
  table: [0, -1, 0], lap: [0, -1, 0], chin: [0, 0, -1], sip: [1, 0, 0],
  wipe: [0, -1, 0], wave: [0, 0, 1], shrug: [0, 1, 0], cover: [0, 0, -1],
  cross: [0, 0, -1], tuck: [1, 0, 0]
}
const CURL = { chin: 1.2, sip: 0.95, wipe: 0.45, wave: 0.06, shrug: 0.16, cover: 0.12, cross: 0.32, tuck: 0.2 }
const TABLE_Y = -1.22

export async function loadTavernActor(url, signal, { seed = 0 } = {}) {
  const response = await fetch(url, { signal })
  if (!response.ok) throw new Error(`Character model request failed (${response.status})`)
  const buffer = await response.arrayBuffer()
  signal.throwIfAborted()
  const loader = new GLTFLoader()
  loader.register(parser => new VRMLoaderPlugin(parser))
  const gltf = await loader.parseAsync(buffer, new URL('.', new URL(url, window.location.href)).href)
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
  const life = createTavernLife()
  const gestures = createTavernGestures()
  const expressions = createVrmExpressions(vrm, json)
  const face = Object.fromEntries(FACE_NAMES.map(name => [name, 0]))
  const mouth = Object.fromEntries(MOUTHS.map(name => [name, 0]))
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
  const wood = new THREE.MeshStandardMaterial({ color: 0x483128, roughness: 0.9 })
  const fabric = new THREE.MeshStandardMaterial({ color: 0x304440, roughness: 1 })
  const stool = new THREE.Mesh(new THREE.CylinderGeometry(torso * 0.55, torso * 0.55, torso * 0.12, 24), fabric)
  stool.position.copy(hips.position).add(new THREE.Vector3(0, -torso * 0.2, 0))
  const back = new THREE.Mesh(new THREE.BoxGeometry(torso * 0.95, torso * 0.9, torso * 0.1), wood)
  back.position.copy(hips.position).add(new THREE.Vector3(0, torso * 0.28, -torso * 0.3))
  furniture.add(stool, back)
  root.add(furniture)
  const cupMaterial = new THREE.MeshStandardMaterial({ color: 0x73988d, roughness: 0.35 })
  const cup = new THREE.Mesh(new THREE.CylinderGeometry(torso * 0.09, torso * 0.075, torso * 0.18, 24), cupMaterial)
  root.add(cup)
  cup.visible = false

  root.scale.setScalar(scale)
  root.rotation.y = seat.yaw
  point.copy(head.position).multiplyScalar(scale).applyEuler(root.rotation)
  root.position.set(seat.x - point.x, seat.hipY + torso * scale - point.y, -point.z)
  root.updateMatrixWorld(true)
  const gazeTarget = new THREE.Object3D()
  if (vrm.lookAt) vrm.lookAt.target = gazeTarget
  const lastHead = { pitch: 0, yaw: 0 }
  let physiology = null
  let springsReady = false

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

  function placeGesture(side, name, out, age) {
    const sign = side === 'left' ? 1 : -1
    const palm = hands[side].frame?.palmLength || torso * 0.12
    if (name === 'chin' || name === 'cover' || name === 'sip' || name === 'tuck') {
      facialAnchor(name === 'tuck' ? 'temple' : name === 'chin' ? 'chin' : 'mouth', out)
      if (name === 'chin') point.set(-palm * 0.2, -palm * 1.15, torso * 0.1)
      if (name === 'sip') point.set(-torso * 0.1, -torso * 0.13, torso * 0.13)
      if (name === 'cover') point.set(-palm * 0.8, -palm * 0.2, torso * 0.06)
      if (name === 'tuck') point.set(-palm * 0.25, -palm * 0.6, torso * 0.04)
      out.add(point.multiplyScalar(scale).applyQuaternion(rootRotation))
    } else if (name === 'cross') {
      armAnchor(side === 'right' ? 'left' : 'right', sign * palm * 0.7, -torso * 0.28, torso * 0.22 + (side === 'left' ? torso * 0.1 : 0), out)
    } else if (name === 'wave') {
      armAnchor(side, sign * torso * 0.45, torso * 0.48, torso * 0.24, out)
      out.x += Math.sin(age * 7) * torso * scale * 0.035
    } else if (name === 'shrug') {
      armAnchor(side, sign * torso * 0.45, -torso * 0.18, torso * 0.52, out)
    } else if (name === 'wipe') {
      anchor('hips', sign * torso * 0.12, torso * 0.2, torso * 0.5, out)
      out.y = TABLE_Y + 0.1
      if (side === 'right') out.x += Math.sin(age * 5) * torso * scale * 0.025
    }
  }

  function orientHand(side, name, weight, ease) {
    const { hand, frame, orientations } = hands[side]
    if (!frame) return
    rotation.copy(orientations[seat[side]]).slerp(orientations[name], weight).premultiply(rootRotation)
    hand.parent.getWorldQuaternion(parentRotation).invert()
    hand.quaternion.slerp(parentRotation.multiply(rotation), ease)
    hand.updateWorldMatrix(false, true)
  }

  return {
    root,
    playGesture(name) { gestures.play(name) },
    setLight(value) { light.value = Math.max(0.72, Math.min(1.05, value)) },
    update({ delta, pose, mood, motion, nod, gazeX, gazeY, reducedMotion, articulation, settled }) {
      const ease = reducedMotion || settled ? 1 : 1 - Math.exp(-delta * 5)
      physiology = life.update(delta, reducedMotion, pose.breath)
      const { breath, blink, sway, drift, eyeX, eyeY } = physiology
      const faceTarget = FACES[mood] || FACES.calm
      for (const name of FACE_NAMES) {
        face[name] += ((faceTarget[name] || 0) - face[name]) * ease
        const weight = ['happy', 'relaxed', 'surprised'].includes(name) ? 1 - articulation.open * 0.7 : 1
        expressions.setValue(name, face[name] * weight)
      }
      for (const name of MOUTHS) {
        const goal = !reducedMotion && articulation.mouth === name ? articulation.open : 0
        mouth[name] += (goal - mouth[name]) * (reducedMotion || settled ? 1 : 1 - Math.exp(-delta * 35))
        expressions.setValue(name, mouth[name])
      }
      expressions.setValue('blink', blink)
      expressions.update()
      const emphasis = reducedMotion ? 0 : articulation.open * 0.018 + articulation.emphasis * 0.04
      const yaw = pose.yaw * 0.55 + gazeX * 0.12 * pose.follow + drift - seat.yaw * 0.6
      const pitch = pose.pitch * 0.6 + nod * 0.65 + gazeY * 0.06 * pose.follow + emphasis
      const headEase = reducedMotion || settled ? 1 : 1 - Math.exp(-delta * 3.5)
      lastHead.pitch += (pitch - lastHead.pitch) * headEase
      lastHead.yaw += (yaw - lastHead.yaw) * headEase
      rotate('hips', seat.lean * 0.3, 0, seat.yaw * 0.035)
      rotate('spine', seat.lean + pose.forward * 0.09, pose.yaw * 0.08, sway)
      rotate('chest', breath + pose.forward * 0.1, 0, -sway * 0.5)
      rotate('upperChest', breath * 0.5, 0, 0)
      rotate('neck', lastHead.pitch * 0.35, lastHead.yaw * 0.35, pose.roll * 0.25)
      rotate('head', lastHead.pitch * 0.65, lastHead.yaw * 0.65, pose.roll * 0.4 + drift)
      const gesture = gestures.update(delta, motion, reducedMotion)
      for (const side of ['left', 'right']) {
        const sign = side === 'left' ? 1 : -1
        rotate(`${side}UpperLeg`, seat.thigh, sign * 0.04, sign * 0.045)
        rotate(`${side}LowerLeg`, -seat.thigh + 0.08, 0, 0)
        rotate(`${side}Foot`, -0.1, 0, 0)
        rotate(`${side}Shoulder`, 0, 0, sign * (gesture.active === 'shrug' ? 0.12 * gesture.weight : 0.015))
        rotate(`${side}UpperArm`, 0, 0, sign * -0.85)
        rotate(`${side}LowerArm`, 0, 0, 0)
      }
      root.updateMatrixWorld(true)
      root.getWorldQuaternion(rootRotation)
      for (const [side, hand] of Object.entries(hands)) {
        const posed = side === 'right' || ['shrug', 'cross', 'wipe'].includes(gesture.active)
        const thinking = motion === 'think' && side === 'right' && gesture.active === 'rest'
        const name = thinking ? 'chin' : posed && gesture.active !== 'rest' ? gesture.active : seat[side]
        const weight = thinking ? 1 : posed ? gesture.weight : 0
        restTarget(side, target)
        if (weight > 0) {
          placeGesture(side, name, gestureTarget, gesture.age)
          target.lerp(gestureTarget, weight)
        }
        const handEase = reducedMotion || settled || !hand.initialized ? 1 : 1 - Math.exp(-delta * 10)
        hand.target.lerp(target, handEase)
        hand.initialized = true
        pole.set(side === 'left' ? 0.45 : -0.45, -1, -0.2).applyQuaternion(rootRotation)
        hand.solver.solve(hand.target, pole)
        orientHand(side, name, weight, handEase)
        const curl = 0.14 + ((CURL[name] ?? 0.14) - 0.14) * weight
        for (const finger of hand.fingers) {
          rotation.setFromAxisAngle(finger.axis, curl * finger.factor)
          finger.bone.quaternion.copy(finger.rest).multiply(rotation)
        }
      }
      cup.visible = ['sip', 'wipe'].includes(gesture.active) && gesture.weight > 0.1 && !!hands.right
      if (cup.visible) {
        hands.right.hand.getWorldPosition(point)
        desired.set(torso * 0.1, torso * 0.04, -torso * 0.01).multiplyScalar(scale).applyQuaternion(rootRotation)
        cup.position.copy(root.worldToLocal(point.add(desired)))
        cup.rotation.x = gesture.active === 'sip' ? -0.2 * gesture.weight : 0
      }
      root.updateMatrixWorld(true)
      head.bone.getWorldPosition(gazeTarget.position)
      gazeTarget.position.add(point.set(gazeX * 0.55 * pose.follow + pose.yaw + eyeX, -gazeY * 0.3 - pose.pitch * 0.4 + eyeY, 5))
      gazeTarget.updateMatrixWorld(true)
      if (!springsReady) {
        vrm.update(0)
        vrm.springBoneManager?.reset()
        springsReady = true
      }
      vrm.update(reducedMotion ? 0 : delta)
    },
    snapshot() {
      const joints = {}
      for (const name of ['hips', 'leftUpperLeg', 'leftLowerLeg', 'leftFoot', 'rightUpperArm', 'rightLowerArm', 'rightHand']) {
        const entry = bones.get(name)
        if (entry) joints[name] = entry.bone.getWorldPosition(new THREE.Vector3()).toArray()
      }
      return {
        expressions: expressions.snapshot(), head: { ...lastHead }, eyeTracking: !!vrm.lookAt, physiology,
        gesture: gestures.snapshot(), seating: { ...seat, torso, shoulderWidth }, joints,
        arms: Object.fromEntries(Object.entries(hands).map(([side, hand]) => [side, hand.solver.snapshot()]))
      }
    },
    destroy() {
      root.removeFromParent()
      furniture.traverse(node => node.geometry?.dispose())
      wood.dispose()
      fabric.dispose()
      cup.geometry.dispose()
      cupMaterial.dispose()
      VRMUtils.deepDispose(vrm.scene)
    }
  }
}

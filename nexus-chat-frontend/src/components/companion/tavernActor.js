import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm'
import { createVrmExpressions } from './vrmExpressions'

const FACES = {
  calm: { relaxed: 0.15 },
  warm: { relaxed: 0.25, happy: 0.35 },
  happy: { happy: 0.7 },
  shy: { happy: 0.22, relaxed: 0.2 },
  sad: { sad: 0.65 },
  worried: { sad: 0.3, surprised: 0.12 },
  angry: { angry: 0.65 },
  surprised: { surprised: 0.7 },
  tired: { relaxed: 0.2, sad: 0.18 },
  thoughtful: { relaxed: 0.1 }
}
const FACE_NAMES = ['happy', 'relaxed', 'sad', 'angry', 'surprised']

function monochromeMaterial(material) {
  const compile = material.onBeforeCompile
  material.onBeforeCompile = function (shader, renderer) {
    compile.call(this, shader, renderer)
    shader.fragmentShader = shader.fragmentShader.replace(/}\s*$/, '\n gl_FragColor.rgb = vec3(dot(gl_FragColor.rgb, vec3(0.2126, 0.7152, 0.0722)));\n}')
  }
  material.customProgramCacheKey = () => 'tavern-monochrome-v1'
  material.clippingPlanes = [new THREE.Plane(new THREE.Vector3(0, 1, 0), 1.94)]
  material.clipping = true
  material.needsUpdate = true
}

export async function loadTavernActor(url, signal) {
  const response = await fetch(url, { signal })
  if (!response.ok) throw new Error(`Character model request failed (${response.status})`)
  const buffer = await response.arrayBuffer()
  signal.throwIfAborted()
  const loader = new GLTFLoader()
  loader.register(parser => new VRMLoaderPlugin(parser))
  const base = new URL('.', new URL(url, window.location.href)).href
  const gltf = await loader.parseAsync(buffer, base)
  const vrm = gltf.userData.vrm
  if (signal.aborted || !vrm?.humanoid) {
    VRMUtils.deepDispose(gltf.scene)
    signal.throwIfAborted()
    throw new Error('Character model has no VRM humanoid')
  }
  try {
    return createActor(vrm, gltf.parser.json)
  } catch (error) {
    VRMUtils.deepDispose(gltf.scene)
    throw error
  }
}

function createActor(vrm, json) {
  VRMUtils.removeUnnecessaryVertices(vrm.scene)
  VRMUtils.removeUnnecessaryJoints(vrm.scene)
  if (vrm.meta.metaVersion === '0') VRMUtils.rotateVRM0(vrm)
  const root = new THREE.Group()
  root.add(vrm.scene)
  root.updateMatrixWorld(true)
  const head = vrm.humanoid.getNormalizedBoneNode('head')
  const hips = vrm.humanoid.getNormalizedBoneNode('hips')
  const headPosition = head.getWorldPosition(new THREE.Vector3())
  const hipsPosition = hips.getWorldPosition(new THREE.Vector3())
  const bounds = new THREE.Box3().setFromObject(vrm.scene)
  const scale = Math.min(2.1 / (headPosition.y - hipsPosition.y), 2 / (bounds.max.y - headPosition.y))
  if (!Number.isFinite(scale) || scale <= 0) throw new Error('Character model has invalid humanoid proportions')
  root.scale.setScalar(scale)
  root.position.set(-headPosition.x * scale - 0.15, 0.05 - headPosition.y * scale, -headPosition.z * scale)

  const materials = new Set()
  vrm.scene.traverse(node => {
    if (!node.isMesh) return
    node.frustumCulled = false
    for (const material of Array.isArray(node.material) ? node.material : [node.material]) materials.add(material)
  })
  materials.forEach(monochromeMaterial)

  const expressions = createVrmExpressions(vrm, json)
  const face = Object.fromEntries(FACE_NAMES.map(name => [name, 0]))
  const bones = new Map()
  for (const name of ['spine', 'chest', 'upperChest', 'neck', 'head', 'leftUpperArm', 'rightUpperArm', 'leftLowerArm', 'rightLowerArm']) {
    const bone = vrm.humanoid.getNormalizedBoneNode(name)
    if (bone) bones.set(name, { bone, rest: bone.quaternion.clone() })
  }
  const rotation = new THREE.Quaternion()
  const euler = new THREE.Euler(0, 0, 0, 'YXZ')
  const rotate = (name, x, y, z) => {
    const entry = bones.get(name)
    if (!entry) return
    rotation.setFromEuler(euler.set(x, y, z, 'YXZ'))
    entry.bone.quaternion.copy(entry.rest).multiply(rotation)
  }
  const gazeTarget = new THREE.Object3D()
  if (vrm.lookAt) vrm.lookAt.target = gazeTarget
  let nextBlink = 2.5
  let blinkStart = -1
  let lastHead = { pitch: 0, yaw: 0 }

  return {
    root,
    update({ time, delta, pose, mood, nod, gazeX, gazeY, reducedMotion, speaking, settled }) {
      const ease = reducedMotion || settled ? 1 : 1 - Math.exp(-delta * 5)
      const target = FACES[mood] || FACES.calm
      for (const name of FACE_NAMES) {
        face[name] += ((target[name] || 0) - face[name]) * ease
        expressions.setValue(name, face[name])
      }
      let blink = 0
      if (!reducedMotion) {
        if (time >= nextBlink && blinkStart < 0) blinkStart = time
        if (blinkStart >= 0) {
          const progress = (time - blinkStart) / 0.2
          blink = Math.max(0, Math.sin(Math.min(1, progress) * Math.PI))
          if (progress >= 1) {
            blinkStart = -1
            nextBlink = time + 2.8 + Math.random() * 2.5
          }
        }
      }
      expressions.setValue('blink', blink)
      expressions.update()

      const breath = reducedMotion ? 0 : Math.sin(time * 1.5 * pose.breath) * 0.012
      const emphasis = !reducedMotion && speaking ? Math.sin(time * 2.4) * 0.014 : 0
      const yaw = pose.yaw * 0.55 + gazeX * 0.12 * pose.follow
      const pitch = pose.pitch * 0.6 + nod * 0.65 + gazeY * 0.06 * pose.follow
      rotate('spine', pose.forward * 0.06, pose.yaw * 0.08, 0)
      rotate('chest', breath + pose.forward * 0.1, 0, emphasis)
      rotate('upperChest', breath * 0.5, 0, 0)
      rotate('neck', pitch * 0.35, yaw * 0.35, pose.roll * 0.25)
      rotate('head', pitch * 0.65, yaw * 0.65, pose.roll * 0.55)
      rotate('leftUpperArm', -0.1 + breath, 0, 1.15)
      rotate('rightUpperArm', -0.1 - breath, 0, -1.15)
      rotate('leftLowerArm', -0.2, -0.12, 0.08)
      rotate('rightLowerArm', -0.2, 0.12, -0.08)
      lastHead = { pitch, yaw }
      root.updateMatrixWorld(true)
      head.getWorldPosition(gazeTarget.position)
      gazeTarget.position.add(new THREE.Vector3(gazeX * 0.55 * pose.follow + pose.yaw * 1.5, -gazeY * 0.3 - pose.pitch * 0.4, 5))
      gazeTarget.updateMatrixWorld(true)
      vrm.update(reducedMotion ? 0 : delta)
    },
    snapshot() {
      return { expressions: expressions.snapshot(), head: lastHead, eyeTracking: !!vrm.lookAt }
    },
    destroy() {
      root.removeFromParent()
      VRMUtils.deepDispose(vrm.scene)
    }
  }
}

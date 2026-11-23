<template>
  <div
    class="companion-3d"
    :style="{ transform: `translate(${position.x}px, ${position.y}px)` }"
    @pointerdown="handlePointerDown"
    @pointermove="handlePointerMove"
    @pointerup="handlePointerUp"
    @pointercancel="handlePointerUp"
    @contextmenu.prevent="handleContextMenu"
    @mouseenter="handleMouseEnter"
    @mouseleave="handleMouseLeave"
  >
    <div class="avatar-body" :class="{ idle: isIdle }">
      <canvas v-if="hasWebGL" ref="canvasRef" class="companion-canvas"></canvas>
      <div v-else class="fallback-avatar"></div>
      <div v-if="hasWebGL && !isReady" class="loading-badge">Loading 3D…</div>
      <div v-if="loadError" class="loading-badge error">{{ loadError }}</div>
    </div>

    <Teleport to="body">
      <div v-if="showContextMenu" class="motion-menu-overlay" @click="closeContextMenu">
        <div
          class="motion-menu"
          :style="{ left: contextMenuPos.x + 'px', top: contextMenuPos.y + 'px' }"
          @click.stop
        >
          <div class="motion-menu-title">{{ $t('companion.switchMotion') }}</div>
          <button
            v-for="m in motionList"
            :key="m.key"
            class="motion-menu-item"
            :class="{ active: m.key === currentMotionKey }"
            @click="switchMotion(m.key)"
          >
            {{ locale === 'zh' ? m.label : m.labelEn }}
          </button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { FBXLoader } from 'three/examples/jsm/loaders/FBXLoader.js'
import { VRMLoaderPlugin, VRMUtils, VRMExpressionPresetName } from '@pixiv/three-vrm'
import { createVrmExpressions } from './vrmExpressions'
import { createRenderLoop, scaledPixelRatio, onRenderQualityChange } from '@/utils/renderGovernor'
import { useSettingsStore } from '@/stores/settings'
import { useUserStore } from '@/stores/user'
import { userAPI } from '@/services/api'
import { attempt, createResourceOwner, releaseRenderer, resourceCounts } from './resourceOwner.js'
import { SERVER_BASE_URL } from '@/services/runtimeConfig'
import { assetRequest } from '@/utils/assetRequest'

const { locale } = useI18n()

const props = defineProps({
  activeRole: Object,
  status: Object,
  // Bond figures from the server. They colour the face and the breathing rate:
  // a closer bond reads as a softer, more settled presence. Null means "no
  // data yet", and then nothing is assumed.
  growth: Object,
  // 'idle' | 'listening' | 'warm' | 'charged' — derived from what is being said
  // in the tavern. Optional; without it the model follows `status` alone.
  mood: { type: String, default: '' }
})

const emit = defineEmits(['open'])

const canvasRef = ref(null)
const hasWebGL = ref(true)
const isReady = ref(false)
const loadError = ref('')
const settings = useSettingsStore()
const userStore = useUserStore()
let renderLoop = null
let stopQualityWatch = null
// Animation time, accumulated from clamped deltas (see render()).
let animationTime = 0
const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false

let renderer = null
let scene = null
let camera = null
let mixer = null
let clock = null
let modelRoot = null
let vrm = null
let vrmExpressions = null
let modelRequest = 0
let disposed = false
let contextLost = false
let motionGeneration = 0
let motionFlight = null
const modelOwners = new WeakMap()
let modelOwner = null
let currentAction = null
let availableClips = []
let basePosition = new THREE.Vector3()
let baseRotation = new THREE.Euler()
let pendingExpressionTimeout = null
const CANVAS_W = 200
const CANVAS_H = 240
const WIDGET_W = 200
const WIDGET_H = 290
const boneRest = new Map()
const boneNames = [
  'hips',
  'spine',
  'chest',
  'upperChest',
  'neck',
  'head',
  'leftShoulder',
  'leftUpperArm',
  'leftLowerArm',
  'leftHand',
  'rightShoulder',
  'rightUpperArm',
  'rightLowerArm',
  'rightHand',
  'leftUpperLeg',
  'leftLowerLeg',
  'leftFoot',
  'leftToes',
  'rightUpperLeg',
  'rightLowerLeg',
  'rightFoot',
  'rightToes'
]
const tempEuler = new THREE.Euler()
const tempQuat = new THREE.Quaternion()
const gazeState = {
  yaw: 0,
  pitch: 0,
  targetYaw: 0,
  targetPitch: 0,
  nextAt: 0
}

const motionConfig = ref(null)
let motionLibraryConfirmed = false
const motionList = ref([])
const currentMotionKey = ref('')
const defaultMotionKey = ref('walk')
const showContextMenu = ref(false)
const contextMenuPos = reactive({ x: 0, y: 0 })

const basePose = {
  leftShoulder: { x: 0, y: 0, z: 0.06 },
  rightShoulder: { x: 0, y: 0, z: -0.06 },
  leftUpperArm: { x: -0.6, y: 0, z: 0.12 },
  rightUpperArm: { x: -0.6, y: 0, z: -0.12 },
  leftLowerArm: { x: -0.35, y: 0, z: 0.02 },
  rightLowerArm: { x: -0.35, y: 0, z: -0.02 },
  leftHand: { x: 0.05, y: 0, z: 0 },
  rightHand: { x: 0.05, y: 0, z: 0 },
  leftUpperLeg: { x: 0.02, y: 0, z: 0 },
  rightUpperLeg: { x: -0.02, y: 0, z: 0 }
}

const mixamoBoneMap = {
  hips: 'Hips',
  spine: 'Spine',
  chest: 'Spine1',
  upperChest: 'Spine2',
  neck: 'Neck',
  head: 'Head',
  leftShoulder: 'LeftShoulder',
  leftUpperArm: 'LeftArm',
  leftLowerArm: 'LeftForeArm',
  leftHand: 'LeftHand',
  rightShoulder: 'RightShoulder',
  rightUpperArm: 'RightArm',
  rightLowerArm: 'RightForeArm',
  rightHand: 'RightHand',
  leftUpperLeg: 'LeftUpLeg',
  leftLowerLeg: 'LeftLeg',
  leftFoot: 'LeftFoot',
  leftToes: 'LeftToeBase',
  rightUpperLeg: 'RightUpLeg',
  rightLowerLeg: 'RightLeg',
  rightFoot: 'RightFoot',
  rightToes: 'RightToeBase'
}

let mixamoActions = {}
let mixamoCurrent = null
let mixamoLoaded = false
let hoverActive = false

const walkState = reactive({
  active: false,
  targetX: 0,
  targetY: 0,
  speed: 40
})

const position = reactive({
  x: 0,
  y: 0
})

const dragging = reactive({
  active: false,
  startX: 0,
  startY: 0,
  originX: 0,
  originY: 0,
  moved: false
})

const isIdle = computed(() => !dragging.active)

const modelSource = computed(() => {
  const role = props.activeRole
  return (
    role?.modelUrl ||
    role?.avatar3dUrl ||
    role?.model?.url ||
    '/models/SpringSnow.vrm'
  )
})

const modelType = computed(() => {
  const role = props.activeRole
  if (role?.modelType) return role.modelType
  if (role?.model?.type) return role.model.type
  const url = modelSource.value
  const ext = url?.split('?')[0]?.split('.').pop()?.toLowerCase()
  if (ext === 'vrm') return 'vrm'
  if (ext === 'fbx') return 'fbx'
  if (ext === 'glb' || ext === 'gltf') return 'gltf'
  return 'gltf'
})

const initPosition = () => {
  const saved = localStorage.getItem('companionPos')
  if (saved) {
    try {
      const parsed = JSON.parse(saved)
      position.x = parsed.x || 0
      position.y = parsed.y || 0
      return
    } catch (e) {}
  }
  const margin = 24
  position.x = window.innerWidth - WIDGET_W - margin
  position.y = window.innerHeight - WIDGET_H - margin
}

const clampPosition = () => {
  const maxX = window.innerWidth - WIDGET_W - 12
  const maxY = window.innerHeight - WIDGET_H - 12
  position.x = Math.max(12, Math.min(position.x, maxX))
  position.y = Math.max(12, Math.min(position.y, maxY))
}

const handlePointerDown = (event) => {
  dragging.active = true
  dragging.moved = false
  dragging.startX = event.clientX
  dragging.startY = event.clientY
  dragging.originX = position.x
  dragging.originY = position.y
  event.currentTarget.setPointerCapture(event.pointerId)
}

const handlePointerMove = (event) => {
  if (!dragging.active) return
  const dx = event.clientX - dragging.startX
  const dy = event.clientY - dragging.startY
  if (Math.abs(dx) > 4 || Math.abs(dy) > 4) dragging.moved = true
  position.x = dragging.originX + dx
  position.y = dragging.originY + dy
  clampPosition()
}

const handlePointerUp = (event) => {
  if (!dragging.active) return
  dragging.active = false
  // Capture can already be gone (pointer cancelled by the OS or a synthetic
  // event); releasing a lost pointer throws and would swallow the click.
  if (event.currentTarget.hasPointerCapture?.(event.pointerId)) {
    event.currentTarget.releasePointerCapture(event.pointerId)
  }
  localStorage.setItem('companionPos', JSON.stringify({ x: position.x, y: position.y }))
  if (!dragging.moved) {
    triggerClickExpression()
    emit('open')
  }
}

const initThree = () => {
  const canvas = canvasRef.value
  if (!canvas) return
  renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true })
  renderer.setPixelRatio(scaledPixelRatio(2))
  renderer.setClearColor(0x000000, 0)

  scene = new THREE.Scene()
  camera = new THREE.PerspectiveCamera(28, 1, 0.1, 20)
  camera.position.set(0, 1.35, 2.6)
  camera.lookAt(0, 1.2, 0)

  const hemi = new THREE.HemisphereLight(0xffffff, 0x334155, 0.85)
  hemi.position.set(0, 2, 0)
  scene.add(hemi)

  const dir = new THREE.DirectionalLight(0xffffff, 1.1)
  dir.position.set(-1.4, 2.2, 1.8)
  dir.castShadow = false
  scene.add(dir)

  const rim = new THREE.DirectionalLight(0xffffff, 0.4)
  rim.position.set(1.8, 1.2, -1.2)
  scene.add(rim)

  clock = new THREE.Clock()
}

const resizeRenderer = () => {
  if (!renderer || !camera) return
  renderer.setPixelRatio(scaledPixelRatio(2))
  renderer.setSize(CANVAS_W, CANVAS_H, false)
  camera.aspect = CANVAS_W / CANVAS_H
  camera.updateProjectionMatrix()
}

// Settings → 视口渲染分辨率 (or a window resize that changes the effective
// factor) must reapply the ratio and the drawing buffer.
const applyRenderQuality = () => {
  if (!renderer) return
  resizeRenderer()
}

const retireMotionActions = () => {
  motionGeneration++
  motionFlight = null
  stopMotionCycle()
  const oldMixer = mixer
  for (const action of new Set(Object.values(mixamoActions))) {
    attempt(() => action.stop())
    attempt(() => oldMixer?.uncacheAction(action.getClip(), modelRoot))
    attempt(() => oldMixer?.uncacheClip(action.getClip()))
  }
  mixamoActions = {}
  mixamoCurrent = null
  mixamoLoaded = false
}

const disposeModel = () => {
  retireMotionActions()
  attempt(() => clearMotionOffsets())
  attempt(() => stopMotionCycle())
  if (pendingExpressionTimeout) {
    clearTimeout(pendingExpressionTimeout)
    pendingExpressionTimeout = null
  }
  if (mixer) {
    attempt(() => mixer.stopAllAction())
    attempt(() => mixer.uncacheRoot(modelRoot))
  }
  mixer = null
  currentAction = null
  availableClips = []
  if (modelRoot && scene) scene.remove(modelRoot)
  if (modelRoot) {
    const owner = modelOwner || createResourceOwner()
    owner.track(modelRoot)
    owner.retire()
  }
  modelOwner = null
  modelRoot = null
  vrm = null
  vrmExpressions = null
  boneRest.clear()
  if (mixamoCurrent) {
    attempt(() => mixamoCurrent.fadeOut(0))
    mixamoCurrent = null
  }
  mixamoActions = {}
  mixamoLoaded = false
}

const loadGLTF = (url, isVrm) => new Promise((resolve, reject) => {
  const owner = createResourceOwner({ ownImages: !THREE.Cache.enabled })
  const loader = new GLTFLoader(new THREE.LoadingManager())
  loader.register(parser => {
    owner.bindParser(parser)
    return { name: 'NexusResourceOwner', beforeRoot: () => owner.prepareEmbeddedImages(parser) }
  })
  if (isVrm) {
    loader.register((parser) => new VRMLoaderPlugin(parser))
  }
  try {
    const request = assetRequest(url, SERVER_BASE_URL, userStore.token)
    loader.setRequestHeader(request.headers)
    loader.load(request.url, gltf => {
      owner.releaseURLs()
      owner.track(gltf.scene)
      modelOwners.set(gltf.scene, owner)
      resolve(gltf)
    }, undefined, error => { owner.retire(); reject(error) })
  } catch (error) { owner.retire(); reject(error) }
})

const loadFBX = (url) => new Promise((resolve, reject) => {
  const loader = new FBXLoader()
  try {
    const request = assetRequest(url, SERVER_BASE_URL, userStore.token)
    loader.setRequestHeader(request.headers)
    loader.load(request.url, resolve, undefined, reject)
  } catch (error) { reject(error) }
})

const fitModelToView = (object) => {
  if (!camera) return
  const box = new THREE.Box3().setFromObject(object)
  const size = box.getSize(new THREE.Vector3())
  const center = box.getCenter(new THREE.Vector3())

  object.position.sub(center)

  const max = Math.max(size.x, size.y, size.z)
  const target = 1.6
  const scale = max > 0 ? target / max : 1
  object.scale.setScalar(scale)

  const scaledSize = size.multiplyScalar(scale)
  const fov = THREE.MathUtils.degToRad(camera.fov)
  const fitHeightDistance = scaledSize.y / (2 * Math.tan(fov / 2))
  const fitWidthDistance = scaledSize.x / (2 * Math.tan(fov / 2)) / camera.aspect
  const distance = Math.max(fitHeightDistance, fitWidthDistance)

  camera.position.set(0, scaledSize.y * 0.45, distance * 2.2)
  camera.lookAt(0, scaledSize.y * 0.25, 0)

  basePosition.copy(object.position)
  baseRotation.copy(object.rotation)
}

const getVrmBone = (name) => {
  if (!vrm?.humanoid) return null
  return (
    vrm.humanoid.getNormalizedBoneNode?.(name) ||
    vrm.humanoid.getRawBoneNode?.(name) ||
    null
  )
}

const cacheBoneRestPose = () => {
  boneRest.clear()
  if (!vrm?.humanoid) return
  boneNames.forEach((name) => {
    const bone = getVrmBone(name)
    if (!bone) return
    boneRest.set(name, {
      quat: bone.quaternion.clone(),
      pos: bone.position.clone()
    })
  })
}

const resetBones = () => {
  if (!boneRest.size) return
  boneRest.forEach((rest, name) => {
    const bone = getVrmBone(name)
    if (!bone) return
    bone.quaternion.copy(rest.quat)
    bone.position.copy(rest.pos)
  })
}

const applyBoneRotation = (name, x, y, z) => {
  const bone = getVrmBone(name)
  const rest = boneRest.get(name)
  if (!bone || !rest) return false
  bone.quaternion.copy(rest.quat)
  tempEuler.set(x, y, z, 'YXZ')
  tempQuat.setFromEuler(tempEuler)
  bone.quaternion.multiply(tempQuat)
  return true
}

const updateGaze = (time) => {
  if (time >= gazeState.nextAt) {
    gazeState.targetYaw = (Math.random() * 2 - 1) * 0.25
    gazeState.targetPitch = (Math.random() * 2 - 1) * 0.18
    gazeState.nextAt = time + 2.2 + Math.random() * 2.8
  }
  const lerp = 0.05
  gazeState.yaw += (gazeState.targetYaw - gazeState.yaw) * lerp
  gazeState.pitch += (gazeState.targetPitch - gazeState.pitch) * lerp
}

const stripMixamoPrefix = (name) => {
  // Handle all variants: mixamorig:Bone, mixamorig1:Bone, mixamorig10.Bone, mixamorigBone
  // Also handle Armature| prefix from Blender exports
  return name
    .replace(/^mixamorig\d*[:\._]?/i, '')
    .replace(/^Armature\|/i, '')
}

// Reverse map: Mixamo standard bone name → VRM humanoid bone name
const mixamoToHumanoidMap = (() => {
  const map = {}
  for (const [humanoidName, mixamoName] of Object.entries(mixamoBoneMap)) {
    map[mixamoName] = humanoidName
  }
  return map
})()

// Comprehensive alias → VRM humanoid bone name mapping
// Covers: Mixamo, Blender, DAZ, iClone, Unity, and other common FBX naming conventions
const boneAliasMap = (() => {
  const aliases = {
    hips: ['Hips', 'Hip', 'Pelvis', 'hip', 'pelvis', 'CC_Base_Hip'],
    spine: ['Spine', 'spine', 'Spine0', 'CC_Base_Waist'],
    chest: ['Spine1', 'Chest', 'chest', 'spine1', 'CC_Base_Spine01'],
    upperChest: ['Spine2', 'UpperChest', 'upperChest', 'spine2', 'CC_Base_Spine02'],
    neck: ['Neck', 'neck', 'CC_Base_NeckTwist01', 'CC_Base_NeckTwist02'],
    head: ['Head', 'head', 'CC_Base_Head'],
    leftShoulder: ['LeftShoulder', 'Left_Shoulder', 'leftShoulder', 'L_Shoulder', 'CC_Base_L_Clavicle'],
    leftUpperArm: ['LeftArm', 'LeftUpperArm', 'Left_Arm', 'Left_UpperArm', 'leftArm', 'L_UpperArm', 'L_Arm', 'CC_Base_L_Upperarm'],
    leftLowerArm: ['LeftForeArm', 'LeftLowerArm', 'Left_ForeArm', 'Left_LowerArm', 'leftForeArm', 'L_ForeArm', 'L_LowerArm', 'CC_Base_L_Forearm'],
    leftHand: ['LeftHand', 'Left_Hand', 'leftHand', 'L_Hand', 'CC_Base_L_Hand'],
    rightShoulder: ['RightShoulder', 'Right_Shoulder', 'rightShoulder', 'R_Shoulder', 'CC_Base_R_Clavicle'],
    rightUpperArm: ['RightArm', 'RightUpperArm', 'Right_Arm', 'Right_UpperArm', 'rightArm', 'R_UpperArm', 'R_Arm', 'CC_Base_R_Upperarm'],
    rightLowerArm: ['RightForeArm', 'RightLowerArm', 'Right_ForeArm', 'Right_LowerArm', 'rightForeArm', 'R_ForeArm', 'R_LowerArm', 'CC_Base_R_Forearm'],
    rightHand: ['RightHand', 'Right_Hand', 'rightHand', 'R_Hand', 'CC_Base_R_Hand'],
    leftUpperLeg: ['LeftUpLeg', 'LeftUpperLeg', 'Left_UpLeg', 'Left_UpperLeg', 'leftUpLeg', 'L_UpLeg', 'L_UpperLeg', 'L_Thigh', 'CC_Base_L_Thigh'],
    leftLowerLeg: ['LeftLeg', 'LeftLowerLeg', 'Left_Leg', 'Left_LowerLeg', 'leftLeg', 'L_Leg', 'L_LowerLeg', 'L_Calf', 'CC_Base_L_Calf'],
    leftFoot: ['LeftFoot', 'Left_Foot', 'leftFoot', 'L_Foot', 'CC_Base_L_Foot'],
    leftToes: ['LeftToeBase', 'LeftToe', 'Left_ToeBase', 'leftToeBase', 'L_ToeBase', 'L_Toe', 'CC_Base_L_ToeBase'],
    rightUpperLeg: ['RightUpLeg', 'RightUpperLeg', 'Right_UpLeg', 'Right_UpperLeg', 'rightUpLeg', 'R_UpLeg', 'R_UpperLeg', 'R_Thigh', 'CC_Base_R_Thigh'],
    rightLowerLeg: ['RightLeg', 'RightLowerLeg', 'Right_Leg', 'Right_LowerLeg', 'rightLeg', 'R_Leg', 'R_LowerLeg', 'R_Calf', 'CC_Base_R_Calf'],
    rightFoot: ['RightFoot', 'Right_Foot', 'rightFoot', 'R_Foot', 'CC_Base_R_Foot'],
    rightToes: ['RightToeBase', 'RightToe', 'Right_ToeBase', 'rightToeBase', 'R_ToeBase', 'R_Toe', 'CC_Base_R_ToeBase']
  }
  // Build fast lookup: aliasLowerCase → humanoidName
  const map = {}
  for (const [humanoidName, aliasList] of Object.entries(aliases)) {
    for (const alias of aliasList) {
      map[alias.toLowerCase()] = humanoidName
    }
  }
  return map
})()

const resolveHumanoidName = (stripped) => {
  // 1. Exact match from Mixamo map
  if (mixamoToHumanoidMap[stripped]) return mixamoToHumanoidMap[stripped]
  // 2. Comprehensive alias lookup (case-insensitive)
  const lower = stripped.toLowerCase()
  if (boneAliasMap[lower]) return boneAliasMap[lower]
  // 3. Normalize: remove underscores, dashes, spaces, then match
  const normalized = lower.replace(/[-_ ]/g, '')
  if (boneAliasMap[normalized]) return boneAliasMap[normalized]
  return null
}

// Official pixiv/three-vrm Mixamo retargeting approach
// Uses world-space quaternion correction and targets normalized bones
const retargetMixamoClipToVrm = (clip, fbx) => {
  // Ensure FBX world matrices are up to date (T-pose)
  fbx.updateWorldMatrix(true, true)

  const _quatA = new THREE.Quaternion()
  const _vec3 = new THREE.Vector3()
  const restRotationInverse = new THREE.Quaternion()
  const parentRestWorldRotation = new THREE.Quaternion()

  // Calculate hips height ratio for position scaling
  // Find hips bone dynamically using the same matching logic as track processing
  let mixamoHipsNode = null
  fbx.traverse((node) => {
    if (mixamoHipsNode) return
    if (!node.isBone && node.type !== 'Bone') return
    const stripped = stripMixamoPrefix(node.name)
    if (resolveHumanoidName(stripped) === 'hips') {
      mixamoHipsNode = node
    }
  })
  const motionHipsHeight = (mixamoHipsNode && Math.abs(mixamoHipsNode.position.y) > 0.01)
    ? Math.abs(mixamoHipsNode.position.y) : 1

  const vrmHipsNode = vrm.humanoid.getNormalizedBoneNode('hips')
  const vrmHipsY = vrmHipsNode ? vrmHipsNode.getWorldPosition(_vec3).y : 0
  const vrmRootY = vrm.scene.getWorldPosition(_vec3).y
  const vrmHipsHeight = Math.abs(vrmHipsY - vrmRootY) || 1
  let hipsPositionScale = vrmHipsHeight / motionHipsHeight
  // Safety: if scale is unreasonable (FBX in cm, VRM in m), auto-correct
  if (hipsPositionScale > 2) hipsPositionScale = vrmHipsHeight / 100
  if (hipsPositionScale < 0.001) hipsPositionScale = 0

  const tracks = []
  const skippedTypes = {}

  for (const track of clip.tracks) {
    // Parse "mixamorigHips.quaternion" → bone name + property
    const splitIndex = track.name.lastIndexOf('.')
    if (splitIndex === -1) continue
    const mixamoRigName = track.name.substring(0, splitIndex)
    const propertyName = track.name.substring(splitIndex + 1)

    // Find the FBX bone node — try full name first, then stripped name
    let mixamoRigNode = fbx.getObjectByName(mixamoRigName)
    const stripped = stripMixamoPrefix(mixamoRigName)
    if (!mixamoRigNode) {
      mixamoRigNode = fbx.getObjectByName(stripped)
    }
    if (!mixamoRigNode || !mixamoRigNode.parent) continue

    // Map to VRM humanoid bone name
    const humanoidName = resolveHumanoidName(stripped)
    if (!humanoidName) continue

    // Get VRM normalized bone node
    const vrmBoneNode = vrm.humanoid.getNormalizedBoneNode(humanoidName)
    if (!vrmBoneNode) continue

    // Get rest-pose world quaternions from Mixamo skeleton
    mixamoRigNode.getWorldQuaternion(restRotationInverse).invert()
    mixamoRigNode.parent.getWorldQuaternion(parentRestWorldRotation)

    // VRM 0.x faces opposite direction — need to mirror across Y axis
    const isVrm0 = vrm.meta?.metaVersion === '0'

    if (track instanceof THREE.QuaternionKeyframeTrack) {
      // Retarget rotation: parentRestWorld * animQuat * inverse(boneRestWorld)
      const values = track.values.slice()
      for (let i = 0; i < values.length; i += 4) {
        _quatA.set(values[i], values[i + 1], values[i + 2], values[i + 3])
        _quatA.premultiply(parentRestWorldRotation).multiply(restRotationInverse)
        values[i] = _quatA.x
        values[i + 1] = _quatA.y
        values[i + 2] = _quatA.z
        values[i + 3] = _quatA.w
      }

      // VRM 0.x: negate x and z quaternion components (mirror Y axis)
      const finalValues = isVrm0
        ? values.map((v, i) => (i % 2 === 0 ? -v : v))
        : values

      tracks.push(
        new THREE.QuaternionKeyframeTrack(
          `${vrmBoneNode.name}.${propertyName}`,
          track.times,
          finalValues
        )
      )
    } else if (track instanceof THREE.VectorKeyframeTrack && propertyName === 'position' && humanoidName === 'hips') {
      // Position track: only keep hips Y-axis (vertical bob), zero X/Z to prevent drift
      const values = track.values.slice().map((v, i) =>
        i % 3 === 1 ? v * hipsPositionScale : 0
      )

      tracks.push(
        new THREE.VectorKeyframeTrack(
          `${vrmHipsNode.name}.${propertyName}`,
          track.times,
          values
        )
      )
    } else {
      // Track skipped (scale, non-hips position, etc.)
      const key = `${humanoidName}.${propertyName}`
      skippedTypes[key] = (skippedTypes[key] || 0) + 1
    }
  }

  if (Object.keys(skippedTypes).length > 0) {
    console.log(`[Companion] retarget "${clip.name}": ${tracks.length} matched, skipped:`, skippedTypes)
  }

  if (tracks.length === 0 && clip.tracks.length > 0) {
    const sampleNames = clip.tracks.slice(0, 8).map(t => {
      const si = t.name.lastIndexOf('.')
      return si >= 0 ? t.name.substring(0, si) : t.name
    })
    // Also dump all bone names from FBX skeleton for debugging
    const fbxBones = []
    fbx.traverse((node) => {
      if (node.isBone || node.type === 'Bone') fbxBones.push(node.name)
    })
    console.warn(`[Companion] retarget "${clip.name}": 0 matched from ${clip.tracks.length} tracks.`)
    console.warn(`[Companion] Track bone names:`, sampleNames)
    console.warn(`[Companion] FBX skeleton bones:`, fbxBones)
  }

  return new THREE.AnimationClip(clip.name, clip.duration, tracks)
}

const fetchMotionConfig = async () => {
  try {
    const owner = userStore.currentUser?.id
    const { data } = await userAPI.getSettingMotions()
    if (disposed || owner !== userStore.currentUser?.id) return
    if (!Array.isArray(data?.motions)) throw new Error('Invalid motion library response')
    motionLibraryConfirmed = true
    motionConfig.value = data
    motionList.value = data.motions
    defaultMotionKey.value = data.default || 'walk'
  } catch {
    motionLibraryConfirmed = false
    // Built-ins are a read-only fallback, never a stand-in for uploaded user data.
    motionList.value = [
      { key: 'idle', file: 'idle.fbx', label: '待机', labelEn: 'Idle', owned: false },
      { key: 'walk', file: 'walk.fbx', label: '走路', labelEn: 'Walk', owned: false },
      { key: 'think', file: 'think.fbx', label: '思考', labelEn: 'Think', owned: false }
    ]
    defaultMotionKey.value = 'walk'
  }
}

const loadMixamoClips = () => {
  if (motionFlight) return motionFlight
  const generation = motionGeneration
  const targetVrm = vrm
  const targetMixer = mixer
  const current = () => !disposed && generation === motionGeneration && vrm === targetVrm && mixer === targetMixer
  const run = async () => {
  if (!vrm?.humanoid) {
    console.warn('[Companion] loadMixamoClips: no VRM humanoid')
    return
  }
  if (mixamoLoaded) return
  if (!mixer) {
    console.warn('[Companion] loadMixamoClips: no mixer')
    return
  }

  if (!motionList.value.length) {
    await fetchMotionConfig()
  }

  const loader = new FBXLoader()
  if (!current()) return
  const motions = motionList.value.map(motion => ({ ...motion }))
  mixamoActions = {}

  for (const motion of motions) {
    if (!current()) return
    const url = motion.owned ? motion.file : `/motions/${motion.file}`
    let fbx = null
    const owner = createResourceOwner()
    try {
      const request = assetRequest(url, SERVER_BASE_URL, userStore.token)
      loader.setRequestHeader(request.headers)
      fbx = await new Promise((resolve, reject) => {
        loader.load(request.url, resolve, undefined, reject)
      })
      owner.track(fbx)

      if (!current()) {
        owner.retire()
        return
      }
      const clip = fbx.animations?.[0]
      if (!clip) {
        owner.retire()
        console.warn(`[Companion] FBX ${motion.key}: no animations found`)
        continue
      }
      clip.name = motion.key

      const retargetedClip = retargetMixamoClipToVrm(clip, fbx)
      owner.retire()

      if (retargetedClip.tracks.length === 0) {
        console.warn(`[Companion] clip ${motion.key}: 0 matched tracks`)
        continue
      }

      const action = targetMixer.clipAction(retargetedClip)
      action.clampWhenFinished = false
      action.loop = THREE.LoopRepeat
      mixamoActions[motion.key] = action
      console.log(`[Companion] clip "${motion.key}": ${retargetedClip.tracks.length} tracks, ${retargetedClip.duration.toFixed(2)}s (original: ${clip.tracks.length} tracks)`)
    } catch (error) {
      if (current()) console.warn(`[Companion] clip load failed for ${motion.key}:`, error)
    } finally {
      if (fbx) owner.track(fbx)
      owner.retire()
    }
  }

  if (current()) mixamoLoaded = Object.keys(mixamoActions).length > 0
  }
  const flight = run().finally(() => { if (motionFlight === flight) motionFlight = null })
  motionFlight = flight
  return flight
}

const playMixamoAction = (name) => {
  if (disposed || contextLost || !mixer) return false
  if (!mixamoLoaded) {
    console.warn(`[Companion] playMixamoAction("${name}"): not loaded`)
    return false
  }
  const action = mixamoActions[name]
  if (!action) {
    console.warn(`[Companion] playMixamoAction("${name}"): not found. Available:`, Object.keys(mixamoActions))
    return false
  }
  if (mixamoCurrent === action) return true

  const clip = action.getClip()
  console.log(`[Companion] playMixamoAction("${name}"): ${clip.tracks.length} tracks, ${clip.duration.toFixed(2)}s`)

  if (currentAction && currentAction !== action) {
    currentAction.fadeOut(0.4)
    currentAction = null
  }
  if (mixamoCurrent && mixamoCurrent !== action) {
    mixamoCurrent.fadeOut(0.4)
  }

  action.reset().fadeIn(0.5).play()
  mixamoCurrent = action
  currentAction = action
  currentMotionKey.value = name

  if (name === 'walk') {
    pickWalkTarget()
    walkState.active = true
  } else {
    walkState.active = false
  }

  return true
}

const applyModel = (object, animations = []) => {
  disposeModel()
  modelRoot = object
  modelOwner = modelOwners.get(object) || null
  scene.add(modelRoot)
  fitModelToView(modelRoot)
  mixer = new THREE.AnimationMixer(modelRoot)
  availableClips = animations
  cacheBoneRestPose()
  playDefaultMotion()
  isReady.value = true
}

const applyVRM = (loadedVrm, animations = [], json) => {
  disposeModel()
  vrm = loadedVrm
  vrmExpressions = createVrmExpressions(vrm, json)
  VRMUtils.removeUnnecessaryVertices(vrm.scene)
  VRMUtils.removeUnnecessaryJoints(vrm.scene)
  modelRoot = vrm.scene
  modelOwner = modelOwners.get(modelRoot) || null
  if (vrm.meta?.metaVersion === '0') {
    VRMUtils.rotateVRM0(vrm)
  }
  scene.add(modelRoot)
  fitModelToView(modelRoot)
  mixer = new THREE.AnimationMixer(modelRoot)
  availableClips = animations
  cacheBoneRestPose()
  playDefaultMotion()
  isReady.value = true
}

const loadModel = async () => {
  if (!hasWebGL.value || !renderer || disposed) return
  const url = modelSource.value
  if (!url) return
  const request = ++modelRequest
  isReady.value = false
  loadError.value = ''
  let loadedRoot = null
  try {
    if (modelType.value === 'fbx') {
      const fbx = await loadFBX(url)
      loadedRoot = fbx
      if (disposed || request !== modelRequest) {
        const owner = createResourceOwner()
        owner.track(fbx)
        owner.retire()
        return
      }
      applyModel(fbx, fbx.animations || [])
      return
    }
    const isVrm = modelType.value === 'vrm'
    const gltf = await loadGLTF(url, isVrm)
    loadedRoot = gltf.scene
    if (disposed || request !== modelRequest) {
      const owner = modelOwners.get(gltf.scene) || createResourceOwner()
      owner.track(gltf.scene)
      owner.retire()
      return
    }
    if (isVrm && gltf.userData?.vrm) {
      applyVRM(gltf.userData.vrm, gltf.animations || [], gltf.parser.json)
    } else {
      applyModel(gltf.scene, gltf.animations || [])
    }
  } catch (error) {
    if (loadedRoot) {
      if (modelRoot === loadedRoot) attempt(() => disposeModel())
      const owner = modelOwners.get(loadedRoot) || createResourceOwner()
      owner.track(loadedRoot)
      owner.retire()
    }
    if (disposed || request !== modelRequest) return
    console.error('Companion model load failed:', error)
    loadError.value = 'Model load failed'
    isReady.value = false
  }
}

// Track whether the user has explicitly selected a motion (from panel or context menu)
let userSelectedMotionKey = null

// --- Motion cycle system ---
// When a non-walk motion is selected, cycle: selected → walk (few seconds) → selected → ...
const WALK_BREAK_SECONDS = 5
let motionCycleTimer = null
let motionCycleActive = false

const stopMotionCycle = () => {
  if (motionCycleTimer) {
    clearTimeout(motionCycleTimer)
    motionCycleTimer = null
  }
  motionCycleActive = false
}

const startMotionCycle = (key) => {
  stopMotionCycle()
  if (disposed || contextLost || !mixer) return
  const generation = motionGeneration, targetMixer = mixer
  const current = () => !disposed && !contextLost && generation === motionGeneration && targetMixer === mixer
  if (!key || key === 'walk') {
    playMixamoAction('walk')
    return
  }
  motionCycleActive = true

  const playSelected = () => {
    if (!current() || !motionCycleActive || userSelectedMotionKey !== key) return
    playMixamoAction(key)
    const clip = mixamoActions[key]?.getClip()
    const duration = clip ? clip.duration : 8
    motionCycleTimer = setTimeout(playWalkBreak, duration * 1000)
  }

  const playWalkBreak = () => {
    if (!current() || !motionCycleActive || userSelectedMotionKey !== key) return
    playMixamoAction('walk')
    motionCycleTimer = setTimeout(playSelected, WALK_BREAK_SECONDS * 1000)
  }

  // Start with the selected motion
  playSelected()
}

const handleMouseEnter = () => {
  if (!mixamoLoaded) return
  if (userSelectedMotionKey) return  // Respect user's explicit choice
  hoverActive = true
  const keys = Object.keys(mixamoActions).filter(k => k !== 'walk')
  if (!keys.length) return
  const pick = keys[Math.floor(Math.random() * keys.length)]
  playMixamoAction(pick)
}

const handleMouseLeave = () => {
  hoverActive = false
  if (!mixamoLoaded) return
  // If user explicitly selected a motion, resume the cycle
  if (userSelectedMotionKey) {
    startMotionCycle(userSelectedMotionKey)
  } else {
    playMixamoAction('walk')
  }
}

// The motion the user last picked is one of their settings: it comes back
// after a reload, and after the pet is switched off and on again.
const rememberMotion = (key) => {
  userSelectedMotionKey = key
  settings.petMotion = key && key !== 'walk' ? key : ''
}

const playDefaultMotion = () => {
  const generation = motionGeneration, targetVrm = vrm, targetMixer = mixer
  loadMixamoClips().then(() => {
    if (disposed || contextLost || generation !== motionGeneration || vrm !== targetVrm || mixer !== targetMixer) return
    const remembered = settings.petMotion
    if (remembered && mixamoActions[remembered]) {
      userSelectedMotionKey = remembered
      startMotionCycle(remembered)
      return
    }
    // The remembered motion is gone (deleted, or not in this model's set).
    if (remembered && motionLibraryConfirmed && !motionList.value.some((m) => m.key === remembered)) settings.petMotion = ''
    playMixamoAction('walk')
  })
}

const switchMotion = (key) => {
  rememberMotion(key)
  startMotionCycle(key)
  showContextMenu.value = false
}

const handleContextMenu = (event) => {
  if (!motionList.value.length) return
  contextMenuPos.x = event.clientX
  contextMenuPos.y = event.clientY
  showContextMenu.value = true
}

const closeContextMenu = () => {
  showContextMenu.value = false
}

const handleEscape = (event) => {
  if (event.key === 'Escape') closeContextMenu()
}

const pickWalkTarget = () => {
  const margin = 40
  const maxX = window.innerWidth - WIDGET_W - margin
  const maxY = window.innerHeight - WIDGET_H - margin
  walkState.targetX = margin + Math.random() * (maxX - margin)
  walkState.targetY = margin + Math.random() * (maxY - margin)
}

const updateWalkMovement = (delta) => {
  if (!walkState.active || dragging.active) return
  const dx = walkState.targetX - position.x
  const dy = walkState.targetY - position.y
  const dist = Math.sqrt(dx * dx + dy * dy)
  if (dist < 5) {
    pickWalkTarget()
    return
  }
  const step = walkState.speed * delta
  const ratio = Math.min(step / dist, 1)
  position.x += dx * ratio
  position.y += dy * ratio
  clampPosition()
}

const setMorphTarget = (nameList, value) => {
  if (!modelRoot) return
  modelRoot.traverse((child) => {
    if (!child.isMesh || !child.morphTargetDictionary || !child.morphTargetInfluences) return
    nameList.forEach((name) => {
      const index = child.morphTargetDictionary[name]
      if (index !== undefined) {
        child.morphTargetInfluences[index] = value
      }
    })
  })
}

const setExpressionValue = (name, value) => {
  if (!name) return
  if (vrmExpressions) {
    vrmExpressions.setValue(name, value)
    return
  }
  const map = {
    [VRMExpressionPresetName.Blink]: ['Blink', 'blink', 'eyeBlink', 'blink_left', 'blink_right'],
    blink: ['Blink', 'blink', 'eyeBlink', 'blink_left', 'blink_right'],
    [VRMExpressionPresetName.Happy]: ['Smile', 'smile', 'happy'],
    happy: ['Smile', 'smile', 'happy'],
    [VRMExpressionPresetName.Angry]: ['angry', 'Angry'],
    angry: ['angry', 'Angry'],
    [VRMExpressionPresetName.Sad]: ['sad', 'Sad'],
    sad: ['sad', 'Sad'],
    [VRMExpressionPresetName.Aa]: ['AA', 'A', 'aa', 'mouthOpen', 'jawOpen'],
    aa: ['AA', 'A', 'aa', 'mouthOpen', 'jawOpen']
  }
  setMorphTarget(map[name] || [], value)
}

const triggerClickExpression = () => {
  const candidates = [
    VRMExpressionPresetName.Happy,
    VRMExpressionPresetName.Relaxed,
    VRMExpressionPresetName.Surprised
  ].filter(Boolean)
  if (!candidates.length) return
  const choice = candidates[Math.floor(Math.random() * candidates.length)]
  setExpressionValue(choice, 1)
  if (pendingExpressionTimeout) clearTimeout(pendingExpressionTimeout)
  pendingExpressionTimeout = setTimeout(() => setExpressionValue(choice, 0), 650)
}

let nextBlinkAt = 0
let blinkStart = 0
let blinkActive = false
const blinkExpression = VRMExpressionPresetName.Blink || 'blink'
const mouthExpression = VRMExpressionPresetName.Aa || VRMExpressionPresetName.A || 'aa'
const scheduleNextBlink = (time) => {
  nextBlinkAt = time + 2.4 + Math.random() * 2.8
}

const updateBlink = (time) => {
  if (reducedMotion) return
  if (!blinkActive && time >= nextBlinkAt) {
    blinkActive = true
    blinkStart = time
  }
  if (blinkActive) {
    const progress = (time - blinkStart) / 0.18
    const value = progress < 0.5 ? progress * 2 : Math.max(0, 1 - (progress - 0.5) * 2)
    setExpressionValue(blinkExpression, value)
    if (progress >= 1) {
      blinkActive = false
      scheduleNextBlink(time)
      setExpressionValue(blinkExpression, 0)
    }
  }
}

const updateMouth = (time) => {
  const statusType = props.status?.statusType || 'resting'
  const talking = statusType === 'chatting' || statusType === 'writing'
  const value = talking ? (Math.sin(time * 6) * 0.25 + 0.35) : 0
  setExpressionValue(mouthExpression, value)
}

// ── Mood layer ───────────────────────────────────────────────────────────────
// Bond and mood bend the face and the tempo, on top of whatever `status` is
// doing. Two inputs, one continuous blend, so nothing snaps between poses.
const MOOD_FACE = {
  idle: { relaxed: 0.35, happy: 0.1 },
  listening: { relaxed: 0.5, happy: 0.15 },
  warm: { relaxed: 0.3, happy: 0.55 },
  charged: { surprised: 0.3, happy: 0.3 }
}

// 0..1 how settled the bond is, from the real growth figures. Every stat is
// optional; the mean is taken over whatever the server actually returned.
const bondLevel = computed(() => {
  const g = props.growth
  if (!g) return null
  const values = [g.intimacy, g.trust, g.stability, g.coGrowth]
    .filter((v) => typeof v === 'number' && Number.isFinite(v))
  if (!values.length) return null
  return values.reduce((sum, v) => sum + v, 0) / values.length / 100
})

const faceTargets = {}
const updateFace = (time) => {
  const level = bondLevel.value
  const mood = MOOD_FACE[props.mood] || MOOD_FACE.idle
  // No growth data → keep the face neutral-but-present, not blank.
  const warm = level == null ? 0 : level
  const targets = {
    relaxed: mood.relaxed + warm * 0.35,
    happy: mood.happy + warm * 0.5,
    surprised: mood.surprised || 0
  }
  // A slow breath makes the smile come and go instead of freezing at one value.
  const pulse = 0.06 * Math.sin(time * 0.7)
  for (const [name, target] of Object.entries(targets)) {
    const clamped = Math.max(0, Math.min(1, target + pulse))
    faceTargets[name] = (faceTargets[name] ?? 0) + (clamped - (faceTargets[name] ?? 0)) * 0.02
    if (faceTargets[name] > 0.001) setExpressionValue(name, faceTargets[name])
  }
}

const motionOffsets = new Map()

const clearMotionOffsets = () => {
  for (const [bone, offset] of motionOffsets) bone.quaternion.multiply(offset.invert())
  motionOffsets.clear()
}

const updateProceduralMotion = (time) => {
  if (!modelRoot || reducedMotion) return
  if ((availableClips.length && currentAction) || mixamoCurrent) {
    updateGaze(time)
    const offsets = [
      ['head', gazeState.pitch * 0.4, gazeState.yaw * 0.4, 0],
      ['chest', Math.sin(time * 1.5) * 0.01, 0, 0]
    ]
    for (const [name, x, y, z] of offsets) {
      const bone = getVrmBone(name)
      if (!bone) continue
      const offset = new THREE.Quaternion().setFromEuler(new THREE.Euler(x, y, z, 'YXZ'))
      bone.quaternion.multiply(offset)
      motionOffsets.set(bone, offset)
    }
    return
  }

  const statusType = props.status?.statusType || 'resting'
  const motionMap = {
    resting: { bob: 0.015, sway: 0.03, headYaw: 0.06, headPitch: 0.02, headRoll: 0.02, shoulder: 0.015, lean: 0.02, headOffset: 0 },
    thinking: { bob: 0.02, sway: 0.04, headYaw: 0.05, headPitch: 0.04, headRoll: 0.12, shoulder: 0.02, lean: 0.03, headOffset: 0.05 },
    reading: { bob: 0.01, sway: 0.02, headYaw: 0.03, headPitch: 0.02, headRoll: 0.01, shoulder: 0.01, lean: 0.03, headOffset: -0.22 },
    walking: { bob: 0.06, sway: 0.12, headYaw: 0.08, headPitch: 0.05, headRoll: 0.06, shoulder: 0.03, lean: 0.08, headOffset: 0.02 },
    listening: { bob: 0.02, sway: 0.04, headYaw: 0.05, headPitch: 0.04, headRoll: 0.02, shoulder: 0.02, lean: 0.03, headOffset: 0.03 },
    chatting: { bob: 0.02, sway: 0.05, headYaw: 0.08, headPitch: 0.03, headRoll: 0.02, shoulder: 0.02, lean: 0.03, headOffset: 0.02 },
    writing: { bob: 0.01, sway: 0.02, headYaw: 0.03, headPitch: 0.02, headRoll: 0.01, shoulder: 0.01, lean: 0.03, headOffset: -0.16 },
    organizing: { bob: 0.015, sway: 0.03, headYaw: 0.05, headPitch: 0.02, headRoll: 0.03, shoulder: 0.02, lean: 0.03, headOffset: 0.01 }
  }

  const motion = motionMap[statusType] || motionMap.resting
  updateGaze(time)

  // A settled bond breathes slower and deeper; a charged exchange speeds it up.
  const bond = bondLevel.value ?? 0.3
  const moodRate = props.mood === 'charged' ? 2.2 : props.mood === 'warm' ? 1.35 : 1.6
  const breath = Math.sin(time * (moodRate - bond * 0.25)) * (0.016 + bond * 0.012)
  const bob = Math.sin(time * 2.4) * motion.bob
  const sway = Math.sin(time * 1.2) * motion.sway
  const headYaw = Math.sin(time * 1.1) * motion.headYaw + gazeState.yaw
  const headPitch = Math.sin(time * 1.4) * motion.headPitch + gazeState.pitch + motion.headOffset
  const headRoll = Math.sin(time * 1.0) * motion.headRoll
  const shoulderRoll = Math.sin(time * 1.6) * motion.shoulder
  const walkPhase = time * (statusType === 'walking' ? 3.2 : 1.2)
  const walkSwing = statusType === 'walking' ? 0.7 : 0.12
  const armSwing = Math.sin(walkPhase) * walkSwing
  const legSwing = Math.sin(walkPhase) * walkSwing * 0.9
  const kneeLiftL = Math.max(0, Math.sin(walkPhase)) * (statusType === 'walking' ? 0.9 : 0.2)
  const kneeLiftR = Math.max(0, Math.sin(walkPhase + Math.PI)) * (statusType === 'walking' ? 0.9 : 0.2)

  if (boneRest.size) {
    resetBones()
    applyBoneRotation('hips', bob * 0.2, sway * 0.6, 0)
    applyBoneRotation('spine', breath * 0.6 + motion.lean * 0.5, sway * 0.3, 0)
    applyBoneRotation('chest', breath + motion.lean, sway * 0.4, 0)
    applyBoneRotation('upperChest', breath * 0.8, sway * 0.5, 0)
    applyBoneRotation('neck', headPitch * 0.4, headYaw * 0.4, headRoll * 0.4)
    applyBoneRotation('head', headPitch, headYaw, headRoll + (statusType === 'thinking' ? 0.08 : 0))
    const baseLeftShoulder = basePose.leftShoulder || { x: 0, y: 0, z: 0 }
    const baseRightShoulder = basePose.rightShoulder || { x: 0, y: 0, z: 0 }
    const baseLeftUpperArm = basePose.leftUpperArm || { x: 0, y: 0, z: 0 }
    const baseRightUpperArm = basePose.rightUpperArm || { x: 0, y: 0, z: 0 }
    const baseLeftLowerArm = basePose.leftLowerArm || { x: 0, y: 0, z: 0 }
    const baseRightLowerArm = basePose.rightLowerArm || { x: 0, y: 0, z: 0 }
    const baseLeftHand = basePose.leftHand || { x: 0, y: 0, z: 0 }
    const baseRightHand = basePose.rightHand || { x: 0, y: 0, z: 0 }
    const baseLeftUpperLeg = basePose.leftUpperLeg || { x: 0, y: 0, z: 0 }
    const baseRightUpperLeg = basePose.rightUpperLeg || { x: 0, y: 0, z: 0 }

    applyBoneRotation('leftShoulder', baseLeftShoulder.x, baseLeftShoulder.y, baseLeftShoulder.z + shoulderRoll)
    applyBoneRotation('rightShoulder', baseRightShoulder.x, baseRightShoulder.y, baseRightShoulder.z - shoulderRoll)

    applyBoneRotation('leftUpperArm', baseLeftUpperArm.x + armSwing, baseLeftUpperArm.y, baseLeftUpperArm.z)
    applyBoneRotation('rightUpperArm', baseRightUpperArm.x - armSwing, baseRightUpperArm.y, baseRightUpperArm.z)
    applyBoneRotation('leftLowerArm', baseLeftLowerArm.x - armSwing * 0.25, baseLeftLowerArm.y, baseLeftLowerArm.z)
    applyBoneRotation('rightLowerArm', baseRightLowerArm.x + armSwing * 0.25, baseRightLowerArm.y, baseRightLowerArm.z)
    applyBoneRotation('leftHand', baseLeftHand.x, baseLeftHand.y, baseLeftHand.z)
    applyBoneRotation('rightHand', baseRightHand.x, baseRightHand.y, baseRightHand.z)

    applyBoneRotation('leftUpperLeg', baseLeftUpperLeg.x + legSwing, 0, 0)
    applyBoneRotation('rightUpperLeg', baseRightUpperLeg.x - legSwing, 0, 0)
    applyBoneRotation('leftLowerLeg', kneeLiftL, 0, 0)
    applyBoneRotation('rightLowerLeg', kneeLiftR, 0, 0)
    applyBoneRotation('leftFoot', -kneeLiftL * 0.35, 0, 0)
    applyBoneRotation('rightFoot', -kneeLiftR * 0.35, 0, 0)

    modelRoot.position.y = basePosition.y + bob * 0.6
    modelRoot.rotation.y = baseRotation.y + sway * 0.4
  } else {
    modelRoot.position.y = basePosition.y + bob
    modelRoot.rotation.y = baseRotation.y + sway
    modelRoot.rotation.x = baseRotation.x + headPitch * 0.2
  }
}

const render = () => {
  if (disposed || contextLost || !renderer || !scene || !camera) return
  // Clamp the step and keep our own running time. THREE.Clock keeps counting
  // while the loop is suspended or throttled, and feeding minutes of background
  // time into the mixer / VRM spring bones / walk in one frame makes the pet
  // lurch across the screen.
  const delta = Math.min(clock.getDelta(), 0.1)
  animationTime += delta
  const elapsed = animationTime
  // mixer targets normalized bones → vrm.update() transfers normalized → raw
  clearMotionOffsets()
  if (mixer) mixer.update(delta)
  updateBlink(elapsed)
  updateMouth(elapsed)
  updateFace(elapsed)
  updateProceduralMotion(elapsed)
  vrmExpressions?.update()
  if (vrm) vrm.update(delta)
  updateWalkMovement(delta)
  renderer.render(scene, camera)
}

const handleMotionsUpdated = async () => {
  // Re-fetch motions.json and reload all clips
  retireMotionActions()
  const generation = motionGeneration, targetMixer = mixer, targetVrm = vrm
  await fetchMotionConfig()
  if (disposed || generation !== motionGeneration || targetMixer !== mixer || targetVrm !== vrm) return
  if (vrm?.humanoid && mixer) {
    await loadMixamoClips()
    if (disposed || generation !== motionGeneration || targetMixer !== mixer || targetVrm !== vrm) return
    playMixamoAction(currentMotionKey.value || defaultMotionKey.value || 'walk')
  }
}

const handleSwitchMotionEvent = async (event) => {
  const key = event.detail?.key
  if (!key) return
  rememberMotion(key)
  // If the clip is already loaded, start the motion cycle
  if (mixamoLoaded && mixamoActions[key]) {
    startMotionCycle(key)
    return
  }
  // Clip not loaded yet (newly uploaded motion) — reload config & clips then play
  const targetMixer = mixer, targetVrm = vrm
  await handleMotionsUpdated()
  if (disposed || targetMixer !== mixer || targetVrm !== vrm) return
  if (mixamoActions[key]) {
    startMotionCycle(key)
  }
}

const onContextLost = (event) => {
  event.preventDefault()
  contextLost = true
  renderLoop?.stop()
  stopMotionCycle()
}
const onContextRestored = () => {
  if (disposed || !contextLost) return
  contextLost = false
  clock?.getDelta()
  renderLoop?.start()
}

onMounted(async () => {
  initPosition()
  clampPosition()
  await fetchMotionConfig()
  if (disposed) return
  try {
    initThree()
  } catch (error) {
    console.error('WebGL init failed:', error)
    hasWebGL.value = false
    releaseRenderer(renderer)
    renderer = scene = camera = clock = null
    return
  }
  resizeRenderer()
  scheduleNextBlink(0)
  loadModel()
  renderLoop = createRenderLoop(render, {
    // Back from a suspension: throw away the time that passed in the background.
    onResume: () => clock?.getDelta()
  })
  renderer.domElement.addEventListener('webglcontextlost', onContextLost)
  renderer.domElement.addEventListener('webglcontextrestored', onContextRestored)
  renderLoop.start()
  if (import.meta.env.DEV) window.__companionResources = () => ({
    disposed, contextLost, motionGeneration, loadingMotion: !!motionFlight,
    actions: Object.keys(mixamoActions).length,
    mixerActions: mixer?._actions?.length || 0, mixerBindings: mixer?._bindings?.length || 0,
    ...resourceCounts
  })
  stopQualityWatch = onRenderQualityChange(applyRenderQuality)
  window.addEventListener('resize', clampPosition)
  window.addEventListener('keydown', handleEscape)
  window.addEventListener('companion:motions-updated', handleMotionsUpdated)
  window.addEventListener('companion:switch-motion', handleSwitchMotionEvent)
})

onUnmounted(() => {
  disposed = true
  modelRequest++
  stopMotionCycle()
  attempt(() => renderLoop?.stop())
  renderLoop = null
  attempt(() => stopQualityWatch?.())
  stopQualityWatch = null
  window.removeEventListener('resize', clampPosition)
  window.removeEventListener('keydown', handleEscape)
  window.removeEventListener('companion:motions-updated', handleMotionsUpdated)
  window.removeEventListener('companion:switch-motion', handleSwitchMotionEvent)
  renderer?.domElement.removeEventListener('webglcontextlost', onContextLost)
  renderer?.domElement.removeEventListener('webglcontextrestored', onContextRestored)
  // Invalidate motion continuations before the deferred GPU retirement.
  motionGeneration++
  releaseRenderer(renderer, () => {
    try { disposeModel() } finally {
      renderer = scene = camera = clock = null
    }
  })
  if (import.meta.env.DEV) delete window.__companionResources
})

watch([modelSource, modelType], loadModel)
</script>

<style scoped>
.companion-3d {
  position: fixed;
  left: 0;
  top: 0;
  width: 200px;
  height: 290px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  z-index: 1200;
  pointer-events: auto;
  user-select: none;
  touch-action: none;
}

.companion-canvas {
  width: 200px;
  height: 240px;
}

.fallback-avatar {
  width: 160px;
  height: 160px;
  border-radius: 50%;
  background: radial-gradient(circle at 30% 30%, #e4e1e6, #8e9192 45%, #2a2a2d 75%);
  box-shadow: inset 0 -8px 12px rgba(255, 255, 255, 0.12), 0 24px 48px -24px rgba(0, 0, 0, 0.8);
}

.avatar-body {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  transition: transform 0.2s ease;
}

.avatar-body.idle {
  animation: idleFloat 4s ease-in-out infinite;
}

.loading-badge {
  position: absolute;
  top: 10px;
  right: 10px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 11px;
  background: rgba(14, 14, 17, 0.85);
  color: #c4c7c8;
  backdrop-filter: blur(calc(6px * var(--glass-blur-scale, 1)));
}

.loading-badge.error {
  background: rgba(179, 38, 30, 0.85);
}


@keyframes idleFloat {
  0% { transform: translateY(0px); }
  50% { transform: translateY(-6px); }
  100% { transform: translateY(0px); }
}
</style>

<style>
.motion-menu-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
}

.motion-menu {
  position: fixed;
  min-width: 132px;
  padding: 4px;
  background: rgba(27, 27, 30, 0.96);
  border: 1px solid #2c2c30;
  border-radius: 8px;
  box-shadow: 0 24px 48px -24px rgba(0, 0, 0, 0.8);
  backdrop-filter: blur(calc(12px * var(--glass-blur-scale, 1)));
}

.motion-menu-title {
  padding: 6px 10px;
  font-size: 10px;
  font-weight: 500;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  color: #6f7274;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.motion-menu-item {
  display: block;
  width: 100%;
  padding: 7px 10px;
  border: none;
  border-radius: 4px;
  background: transparent;
  text-align: left;
  font-size: 12px;
  color: #c4c7c8;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.motion-menu-item:hover {
  background: #2a2a2d;
  color: #e4e1e6;
}

.motion-menu-item.active {
  color: #ffffff;
  font-weight: 500;
  background: #2a2a2d;
}
</style>

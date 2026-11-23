import { VRMExpression, VRMExpressionMorphTargetBind } from '@pixiv/three-vrm'

// Only calibrated VRoid facial families. HA/SkinFung are not hair or smiles.
export const FINE_TARGETS = {
  browAngry: 'Fcl_BRW_Angry', browFun: 'Fcl_BRW_Fun', browJoy: 'Fcl_BRW_Joy',
  browSad: 'Fcl_BRW_Sorrow', browSurprised: 'Fcl_BRW_Surprised',
  eyeAngry: 'Fcl_EYE_Angry', eyeFun: 'Fcl_EYE_Fun', eyeJoy: 'Fcl_EYE_Joy',
  eyeSad: 'Fcl_EYE_Sorrow', eyeSurprised: 'Fcl_EYE_Surprised',
  eyeJoyLeft: 'Fcl_EYE_Joy_L', eyeJoyRight: 'Fcl_EYE_Joy_R',
  mouthUp: 'Fcl_MTH_Up', mouthDown: 'Fcl_MTH_Down', mouthSmall: 'Fcl_MTH_Small',
  mouthAngry: 'Fcl_MTH_Angry', mouthFun: 'Fcl_MTH_Fun', mouthJoy: 'Fcl_MTH_Joy',
  mouthSad: 'Fcl_MTH_Sorrow', mouthSurprised: 'Fcl_MTH_Surprised'
}

function hasDisplacement(attribute) {
  if (!attribute?.array) return false
  // Ignore numerical dust and the all-zero EYE_Natural placeholder.
  return attribute.array.some(value => Number.isFinite(value) && Math.abs(value) > 1e-7)
}

export function registerTavernProfile(vrm, json = {}) {
  const manager = vrm.expressionManager
  const registered = manager?.expressions?.map(expression => expression.expressionName) || []
  const groups = new Map()
  const morphSamples = new Map()
  const checked = new WeakMap()
  vrm.scene?.traverse(node => {
    if (!node.isMesh || !node.morphTargetDictionary || !node.morphTargetInfluences) return
    if (!node.geometry?.morphTargetsRelative) return
    for (const [name, index] of Object.entries(node.morphTargetDictionary)) {
      if (!Number.isInteger(index) || index >= node.morphTargetInfluences.length) continue
      if (!morphSamples.has(name)) morphSamples.set(name, [])
      morphSamples.get(name).push({ node, index })
    }
    for (const [semantic, rawName] of Object.entries(FINE_TARGETS)) {
      const index = node.morphTargetDictionary[rawName]
      const attribute = node.geometry.morphAttributes?.position?.[index]
      if (!Number.isInteger(index) || index >= node.morphTargetInfluences.length || !attribute) continue
      if (!checked.has(attribute)) checked.set(attribute, hasDisplacement(attribute))
      if (!checked.get(attribute)) continue
      if (!groups.has(semantic)) groups.set(semantic, new Map())
      const bindings = groups.get(semantic)
      if (!bindings.has(index)) bindings.set(index, [])
      bindings.get(index).push(node)
    }
  })
  const channels = {}
  const binds = {}
  if (manager?.registerExpression && manager?.getExpression) {
    for (const [semantic, indices] of groups) {
      const name = `tavern_${FINE_TARGETS[semantic]}`
      if (!manager.getExpression(name)) {
        const expression = new VRMExpression(name)
        for (const [index, primitives] of indices) {
          expression.addBind(new VRMExpressionMorphTargetBind({ primitives, index, weight: 1 }))
        }
        manager.registerExpression(expression)
      }
      if (manager.getExpression(name)) {
        channels[semantic] = name
        binds[semantic] = [...indices].map(([index, primitives]) => ({ index, primitives: primitives.length }))
      }
    }
  }
  // A partial family must not silence a working whole-face fallback.
  const fine = ['browAngry', 'browJoy', 'browSad', 'browSurprised',
    'eyeAngry', 'eyeJoy', 'eyeSad', 'eyeSurprised',
    'mouthAngry', 'mouthJoy', 'mouthSad', 'mouthSurprised'].every(name => !!channels[name])
  const boneNames = ['head', 'hips', 'leftEye', 'rightEye', 'leftHand', 'rightHand',
    'leftUpperArm', 'rightUpperArm', 'leftLowerArm', 'rightLowerArm']
  const bones = Object.fromEntries(boneNames.map(name => [name, !!vrm.humanoid?.getNormalizedBoneNode(name)]))
  return {
    fine, channels, binds,
    readMorphs() {
      return Object.fromEntries([...morphSamples].map(([name, entries]) => {
        const values = entries.map(({ node, index }) => node.morphTargetInfluences[index]).filter(Number.isFinite)
        return [name, { min: values.length ? Math.min(...values) : null, max: values.length ? Math.max(...values) : null, primitives: entries.length }]
      }))
    },
    capabilities: {
      expressionMode: fine ? 'regional' : 'preset', registeredExpressions: registered,
      fineChannels: Object.keys(channels), bones,
      lookAt: !!vrm.lookAt, eyeBones: bones.leftEye && bones.rightEye,
      springBones: !!vrm.springBoneManager,
      declaredSpringGroups: json.extensions?.VRM?.secondaryAnimation?.boneGroups?.length ?? null,
      asymmetricEyes: !!channels.eyeJoyLeft && !!channels.eyeJoyRight, asymmetricSmile: false
    }
  }
}

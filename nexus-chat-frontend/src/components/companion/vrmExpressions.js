const V0_PRESETS = {
  joy: 'happy', fun: 'relaxed', sorrow: 'sad', angry: 'angry',
  a: 'aa', i: 'ih', u: 'ou', e: 'ee', o: 'oh',
  blink: 'blink', blink_l: 'blinkLeft', blink_r: 'blinkRight', neutral: 'neutral',
  lookup: 'lookUp', lookdown: 'lookDown', lookleft: 'lookLeft', lookright: 'lookRight'
}

const ALTERNATIVES = {
  blink: [[['Blink', 1]], [['eyeBlinkLeft', 1], ['eyeBlinkRight', 1]]],
  happy: [[['Joy', 1]], [['mouthSmileLeft', 0.8], ['mouthSmileRight', 0.8], ['cheekSquintLeft', 0.2], ['cheekSquintRight', 0.2]]],
  relaxed: [[['Fun', 1]], [['mouthSmileLeft', 0.3], ['mouthSmileRight', 0.3], ['eyeSquintLeft', 0.15], ['eyeSquintRight', 0.15]]],
  sad: [[['Sorrow', 1]], [['browInnerUp', 0.55], ['mouthFrownLeft', 0.5], ['mouthFrownRight', 0.5]]],
  angry: [[['Angry', 1]], [['browDownLeft', 0.6], ['browDownRight', 0.6], ['mouthPressLeft', 0.2], ['mouthPressRight', 0.2]]],
  surprised: [[['Surprised', 1]], [['eyeWideLeft', 0.65], ['eyeWideRight', 0.65], ['browInnerUp', 0.5], ['jawOpen', 0.15]]],
  aa: [[['A', 1]], [['jawOpen', 1]]],
  ih: [[['I', 1]], [['mouthStretchLeft', 0.6], ['mouthStretchRight', 0.6], ['jawOpen', 0.2]]],
  ou: [[['U', 1]], [['mouthPucker', 0.7], ['jawOpen', 0.2]]],
  ee: [[['E', 1]], [['mouthSmileLeft', 0.45], ['mouthSmileRight', 0.45], ['jawOpen', 0.4]]],
  oh: [[['O', 1]], [['mouthFunnel', 0.7], ['jawOpen', 0.5]]]
}

export function expressionBindings(json) {
  const available = new Set()
  const seen = new Set()
  const v0 = json.extensions?.VRM?.blendShapeMaster?.blendShapeGroups
  if (v0) {
    for (const group of v0) {
      const name = V0_PRESETS[group.presetName] || group.name
      if (!name || seen.has(name)) continue
      seen.add(name)
      if (group.binds?.length || group.materialValues?.length) available.add(name)
    }
  } else {
    const expressions = json.extensions?.VRMC_vrm?.expressions
    for (const [name, expression] of Object.entries({ ...expressions?.preset, ...expressions?.custom })) {
      if (expression.morphTargetBinds?.length || expression.materialColorBinds?.length || expression.textureTransformBinds?.length) {
        available.add(name)
      }
    }
  }
  const bindings = {}
  for (const [name, alternatives] of Object.entries(ALTERNATIVES)) {
    bindings[name] = available.has(name)
      ? [[name, 1]]
      : alternatives.find(recipe => recipe.every(([target]) => available.has(target))) || []
  }
  return bindings
}

export function createVrmExpressions(vrm, json) {
  const bindings = expressionBindings(json)
  const values = {}
  const targets = new Set(Object.values(bindings).flat().map(([name]) => name))
  const manager = vrm.expressionManager
  return {
    setValue(name, value) {
      if (bindings[name]) values[name] = Math.max(0, Math.min(1, value))
    },
    update() {
      const weights = Object.fromEntries([...targets].map(name => [name, 0]))
      for (const [name, value] of Object.entries(values)) {
        for (const [target, weight] of bindings[name]) {
          weights[target] = Math.max(weights[target], value * weight)
        }
      }
      for (const [name, value] of Object.entries(weights)) manager?.setValue(name, value)
    },
    snapshot() {
      return { values: { ...values }, bindings }
    }
  }
}

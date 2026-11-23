import * as THREE from 'three'

/**
 * The model's own body as collision volumes, measured from its skinned vertices
 * in the rest pose: a stack of elliptical torso slices, a head ellipsoid and a
 * jaw sphere. Hands and forearms are kept outside these instead of being placed
 * by proportions that only fit one model.
 */

/** Slice points (x relative to the centre line) into horizontal bands. */
export function fitBands(points, yMin, yMax, count) {
  const span = yMax - yMin
  const bands = Array.from({ length: count }, (_, index) => ({
    y: yMin + span * (index + 0.5) / count, rx: 0, front: -Infinity, back: Infinity, samples: 0
  }))
  if (!(span > 0)) return []
  for (const point of points) {
    if (point.y < yMin || point.y >= yMax) continue
    const band = bands[Math.min(count - 1, Math.floor((point.y - yMin) / span * count))]
    band.rx = Math.max(band.rx, Math.abs(point.x))
    band.front = Math.max(band.front, point.z)
    band.back = Math.min(band.back, point.z)
    band.samples++
  }
  const measured = bands.filter(band => band.samples > 0)
  if (!measured.length) return []
  // A slice with no vertices borrows its nearest measured neighbour.
  return bands.map(band => {
    const source = band.samples ? band : measured.reduce((best, other) => Math.abs(other.y - band.y) < Math.abs(best.y - band.y) ? other : best)
    return { y: band.y, rx: source.rx, cz: (source.front + source.back) / 2, rz: (source.front - source.back) / 2 }
  })
}

/** Radii at a height, interpolated between slice centres so there are no steps. */
export function bandAt(bands, y, out = {}) {
  const last = bands.length - 1
  if (last < 0) return null
  const step = last > 0 ? bands[1].y - bands[0].y : 1
  const position = Math.max(0, Math.min(last, (y - bands[0].y) / step))
  const low = Math.floor(position), high = Math.min(last, low + 1), t = position - low
  out.rx = bands[low].rx + (bands[high].rx - bands[low].rx) * t
  out.cz = bands[low].cz + (bands[high].cz - bands[low].cz) * t
  out.rz = bands[low].rz + (bands[high].rz - bands[low].rz) * t
  return out
}

/** Push (x, z) out of an ellipse grown by `radius`. Returns how deep it was. */
export function pushOutEllipse(point, cx, cz, rx, rz, radius) {
  const ax = rx + radius, az = rz + radius
  if (!(ax > 0) || !(az > 0)) return 0
  const dx = point.x - cx, dz = point.z - cz
  const value = (dx / ax) ** 2 + (dz / az) ** 2
  if (value >= 1) return 0
  // Exactly on the axis there is no direction to leave by: go out the front.
  if (value < 1e-10) { point.x = cx; point.z = cz + az; return az }
  const factor = 1 / Math.sqrt(value)
  point.x = cx + dx * factor
  point.z = cz + dz * factor
  return (factor - 1) * Math.hypot(dx, dz)
}

/** Push a point out of an ellipsoid grown by `radius`. Returns how deep it was. */
export function pushOutEllipsoid(point, center, radii, radius) {
  const ax = radii.x + radius, ay = radii.y + radius, az = radii.z + radius
  let dx = point.x - center.x, dy = point.y - center.y, dz = point.z - center.z
  const value = (dx / ax) ** 2 + (dy / ay) ** 2 + (dz / az) ** 2
  if (value >= 1) return 0
  if (value < 1e-10) { dx = 0; dy = 0; dz = az * 1e-3 }
  const factor = 1 / Math.sqrt(Math.max(value, 1e-6))
  const depth = (factor - 1) * Math.hypot(dx, dy, dz)
  point.set(center.x + dx * factor, center.y + dy * factor, center.z + dz * factor)
  return depth
}

const TORSO_CHAIN = ['hips', 'spine', 'chest', 'upperChest', 'neck']
const LIMB_ROOTS = ['leftShoulder', 'rightShoulder', 'leftUpperArm', 'rightUpperArm', 'leftUpperLeg', 'rightUpperLeg', 'head']

/**
 * @param vrm    a loaded VRM in its rest pose, root transform still identity
 * @param nodes  name -> normalized humanoid node, already at the rest pose
 */
export function measureTavernBody(vrm, nodes) {
  const raw = name => vrm.humanoid?.getRawBoneNode?.(name)
  const hips = nodes.get('hips'), head = nodes.get('head')
  if (!hips || !head || !raw('hips') || !raw('head')) return null
  const stops = new Set(LIMB_ROOTS.map(raw).filter(Boolean))
  const torsoBones = new Set(), headBones = new Set()
  const walk = (node, set, blocked) => {
    if (!node || blocked?.has(node)) return
    set.add(node)
    for (const child of node.children) walk(child, set, blocked)
  }
  walk(raw('hips'), torsoBones, stops)
  walk(raw('head'), headBones, null)

  const torsoPoints = [], facePoints = [], hairPoints = []
  const mouthBox = new THREE.Box3()
  const vertex = new THREE.Vector3()
  vrm.scene.updateMatrixWorld(true)
  // The face is the mesh that carries the expressions. Whatever else rides on the
  // head — hair, ribbons, a halo — must not inflate the skull she can touch.
  let expressive = false
  vrm.scene.traverse(node => { if (node.isSkinnedMesh && Object.keys(node.morphTargetDictionary || {}).length) expressive = true })
  vrm.scene.traverse(node => {
    if (!node.isSkinnedMesh || !node.skeleton) return
    const { position, skinIndex, skinWeight } = node.geometry.attributes
    if (!position || !skinIndex || !skinWeight) return
    const names = (Array.isArray(node.material) ? node.material : [node.material]).map(material => material?.name || '').join(' ')
    const face = expressive ? Object.keys(node.morphTargetDictionary || {}).length > 0 : !/hair/i.test(names + ' ' + node.name)
    const hair = !face, mouth = /FaceMouth/i.test(names)
    for (let index = 0; index < position.count; index++) {
      let slot = 0, best = skinWeight.getX(index)
      for (let k = 1; k < 4; k++) {
        const weight = skinWeight.getComponent(index, k)
        if (weight > best) { best = weight; slot = k }
      }
      const bone = node.skeleton.bones[skinIndex.getComponent(index, slot)]
      const inTorso = torsoBones.has(bone), inHead = headBones.has(bone)
      if (!inTorso && !inHead) continue
      node.getVertexPosition(index, vertex).applyMatrix4(node.matrixWorld)
      if (!Number.isFinite(vertex.x + vertex.y + vertex.z)) continue
      if (mouth) mouthBox.expandByPoint(vertex)
      if (inTorso && !/hair/i.test(names + ' ' + node.name)) torsoPoints.push(vertex.clone())
      else if (inHead) (hair ? hairPoints : facePoints).push(vertex.clone())
    }
  })
  if (torsoPoints.length < 12 || facePoints.length < 12) return null

  const centre = hips.position.x
  const unit = head.position.y - hips.position.y
  const yMin = hips.position.y - unit * 0.14, yMax = head.position.y
  for (const point of torsoPoints) point.x -= centre
  const bands = fitBands(torsoPoints, yMin, yMax, 10)
  if (!bands.length) return null

  const faceBox = new THREE.Box3().setFromPoints(facePoints)
  const headCenter = faceBox.getCenter(new THREE.Vector3())
  const headRadii = faceBox.getSize(new THREE.Vector3()).multiplyScalar(0.5)
  const mouth = mouthBox.isEmpty()
    ? new THREE.Vector3(headCenter.x, headCenter.y - headRadii.y * 0.55, faceBox.max.z - headRadii.z * 0.12)
    : new THREE.Vector3((mouthBox.min.x + mouthBox.max.x) / 2, (mouthBox.min.y + mouthBox.max.y) / 2, mouthBox.max.z)
  // The chin: the lowest face vertex on the front centre line.
  const chin = new THREE.Vector3(headCenter.x, Infinity, mouth.z)
  for (const point of facePoints) {
    if (Math.abs(point.x - headCenter.x) > headRadii.x * 0.22 || point.z < headCenter.z + headRadii.z * 0.2) continue
    if (point.y < chin.y) { chin.y = point.y; chin.z = point.z }
  }
  if (!Number.isFinite(chin.y)) chin.set(headCenter.x, faceBox.min.y, mouth.z - headRadii.z * 0.2)
  const jawRadius = headRadii.x * 0.62
  const jaw = new THREE.Vector3(headCenter.x, chin.y + jawRadius * 0.92, Math.min(mouth.z, chin.z) - jawRadius * 0.82)
  // How far the hair stands out beside the ear, front half of the head only.
  const earY = headCenter.y - headRadii.y * 0.12
  let hairSide = headRadii.x
  for (const point of hairPoints) {
    if (Math.abs(point.y - earY) > headRadii.y * 0.22 || point.z < headCenter.z - headRadii.z * 0.1) continue
    hairSide = Math.max(hairSide, Math.abs(point.x - headCenter.x))
  }
  hairSide = Math.min(hairSide, headRadii.x * 1.45)

  return buildTavernBody(nodes, { source: 'mesh', centre, unit, yMin, yMax, bands, headCenter, headRadii, mouth, chin, jaw, jawRadius, hairSide, earY,
    samples: { torso: torsoPoints.length, face: facePoints.length, hair: hairPoints.length } })
}

/** The same volumes from bone proportions, for a model whose mesh cannot be read. */
export function approximateTavernBody(nodes) {
  const hips = nodes.get('hips'), head = nodes.get('head')
  if (!hips || !head) return null
  const unit = head.position.y - hips.position.y
  const left = nodes.get('leftUpperArm'), right = nodes.get('rightUpperArm')
  const half = left && right ? left.position.distanceTo(right.position) * 0.5 : unit * 0.21
  const centre = hips.position.x
  const yMin = hips.position.y - unit * 0.14, yMax = head.position.y
  const bands = Array.from({ length: 10 }, (_, index) => {
    const t = (index + 0.5) / 10
    // Hips, waist, chest, then narrowing into the neck.
    const width = t < 0.2 ? 0.86 : t < 0.42 ? 0.7 : t < 0.78 ? 0.84 : 0.84 - (t - 0.78) * 2.6
    return { y: yMin + (yMax - yMin) * t, rx: half * Math.max(0.3, width), cz: hips.position.z + unit * 0.02, rz: unit * (t < 0.78 ? 0.2 : 0.13) }
  })
  const headRadii = new THREE.Vector3(unit * 0.25, unit * 0.27, unit * 0.23)
  const headCenter = new THREE.Vector3(head.position.x, head.position.y + unit * 0.2, head.position.z + unit * 0.03)
  const mouth = new THREE.Vector3(headCenter.x, headCenter.y - headRadii.y * 0.55, headCenter.z + headRadii.z * 0.9)
  const chin = new THREE.Vector3(headCenter.x, headCenter.y - headRadii.y * 0.95, headCenter.z + headRadii.z * 0.66)
  const jawRadius = headRadii.x * 0.62
  const jaw = new THREE.Vector3(headCenter.x, chin.y + jawRadius * 0.92, Math.min(mouth.z, chin.z) - jawRadius * 0.82)
  return buildTavernBody(nodes, { source: 'proportions', centre, unit, yMin, yMax, bands, headCenter, headRadii, mouth, chin, jaw, jawRadius,
    hairSide: headRadii.x * 1.15, earY: headCenter.y - headRadii.y * 0.12, samples: null })
}

function buildTavernBody(nodes, shape) {
  const { centre, unit, yMin, yMax, bands, headCenter, headRadii, mouth, chin, jaw, jawRadius, hairSide, earY } = shape
  const jawRadii = { x: jawRadius, y: jawRadius, z: jawRadius }
  const frames = new Map()
  for (const name of [...TORSO_CHAIN, 'head']) {
    const entry = nodes.get(name)
    if (!entry) continue
    entry.bone.updateWorldMatrix(true, false)
    const rest = entry.bone.matrixWorld.clone()
    frames.set(name, { bone: entry.bone, rest, restInverse: rest.clone().invert(), inverse: rest.clone().invert(), y: entry.position.y })
  }
  /** Call once per frame after the torso and head are posed, before resolving. */
  const prepare = () => { for (const frame of frames.values()) frame.inverse.copy(frame.bone.matrixWorld).invert() }
  const chain = TORSO_CHAIN.filter(name => frames.has(name))
  const segments = chain.map((name, index) => ({
    name, frame: frames.get(name),
    low: index === 0 ? yMin : frames.get(name).y,
    high: index === chain.length - 1 ? yMax : frames.get(chain[index + 1]).y
  }))
  const headFrame = frames.get('head')
  const scratch = new THREE.Vector3(), band = {}
  const toRest = (frame, world, out) => out.copy(world).applyMatrix4(frame.inverse).applyMatrix4(frame.rest)
  const toWorld = (frame, rest, out) => out.copy(rest).applyMatrix4(frame.restInverse).applyMatrix4(frame.bone.matrixWorld)

  /** Moves `point` (world) out of the body. `radius` is in model units. */
  function resolve(point, radius = 0, { head: withHead = true, torso: withTorso = true } = {}) {
    let deepest = 0
    if (withTorso) {
      for (const segment of segments) {
        toRest(segment.frame, point, scratch)
        const pad = radius * 0.5
        if (scratch.y < segment.low - pad || scratch.y > segment.high + pad) continue
        bandAt(bands, scratch.y, band)
        scratch.x -= centre
        const depth = pushOutEllipse(scratch, 0, band.cz, band.rx, band.rz, radius)
        if (depth > 0) {
          scratch.x += centre
          toWorld(segment.frame, scratch, point)
          deepest = Math.max(deepest, depth)
        }
      }
    }
    if (withHead && headFrame) {
      toRest(headFrame, point, scratch)
      const depth = Math.max(pushOutEllipsoid(scratch, headCenter, headRadii, radius),
        pushOutEllipsoid(scratch, jaw, jawRadii, radius))
      if (depth > 0) {
        toWorld(headFrame, scratch, point)
        deepest = Math.max(deepest, depth)
      }
    }
    return deepest
  }
  const probe = new THREE.Vector3()
  return {
    source: shape.source, unit, bands, centre,
    landmarks: { mouth, chin, jaw, jawRadius, headCenter, headRadii, hairSide, earY },
    prepare, resolve,
    depth(point, radius = 0, options) { return resolve(probe.copy(point), radius, options) },
    /** A rest-space point on the torso surface, carried into the current pose. */
    torsoPoint(side, height, forward, out) {
      const y = yMin + (yMax - yMin) * height
      bandAt(bands, y, band)
      const segment = segments.find(item => y >= item.low && y <= item.high) || segments[segments.length - 1]
      scratch.set(centre + side * band.rx, y, band.cz + forward * band.rz)
      return segment ? toWorld(segment.frame, scratch, out) : out.copy(scratch)
    },
    /** A rest-space point on or near the head, carried into the current pose. */
    headPoint(rest, out) { return headFrame ? toWorld(headFrame, rest, out) : out.copy(rest) },
    snapshot() {
      return { source: shape.source, unit, bands: bands.map(item => ({ ...item })), headCenter: headCenter.toArray(), headRadii: headRadii.toArray(),
        mouth: mouth.toArray(), chin: chin.toArray(), jaw: jaw.toArray(), jawRadius, hairSide, samples: shape.samples }
    }
  }
}

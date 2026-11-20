import * as THREE from 'three'

const MIN_FLEX = 5 * Math.PI / 180
const MAX_FLEX = 145 * Math.PI / 180
const POLE_EPSILON_SQ = 1e-12

function perpendicular(direction, result) {
  const x = Math.abs(direction.x)
  const y = Math.abs(direction.y)
  const z = Math.abs(direction.z)
  if (x <= y && x <= z) result.set(1, 0, 0)
  else if (y <= z) result.set(0, 1, 0)
  else result.set(0, 0, 1)
  return result.addScaledVector(direction, -result.dot(direction)).normalize()
}

export function createLimbSolver(upper, lower, end) {
  const shoulder = new THREE.Vector3()
  const joint = new THREE.Vector3()
  const wrist = new THREE.Vector3()
  const requested = new THREE.Vector3()
  const direction = new THREE.Vector3()
  const offset = new THREE.Vector3()
  const pole = new THREE.Vector3()
  const elbowTarget = new THREE.Vector3()
  const wristTarget = new THREE.Vector3()
  // Aim scratch must not overwrite either analytic target or the reach direction.
  const aimOrigin = new THREE.Vector3()
  const aimFrom = new THREE.Vector3()
  const aimTo = new THREE.Vector3()
  const parentInverse = new THREE.Matrix4()
  const aimRotation = new THREE.Quaternion()

  function measure() {
    upper.updateWorldMatrix(true, true)
    shoulder.setFromMatrixPosition(upper.matrixWorld)
    joint.setFromMatrixPosition(lower.matrixWorld)
    wrist.setFromMatrixPosition(end.matrixWorld)
  }

  function aim(bone, child, target) {
    aimOrigin.setFromMatrixPosition(bone.matrixWorld)
    aimFrom.setFromMatrixPosition(child.matrixWorld).sub(aimOrigin)
    aimTo.copy(target).sub(aimOrigin)
    if (aimFrom.lengthSq() === 0 || aimTo.lengthSq() === 0) return
    if (bone.parent) {
      parentInverse.copy(bone.parent.matrixWorld).invert()
      aimFrom.transformDirection(parentInverse)
      aimTo.transformDirection(parentInverse)
    } else {
      aimFrom.normalize()
      aimTo.normalize()
    }
    aimRotation.setFromUnitVectors(aimFrom, aimTo)
    bone.quaternion.premultiply(aimRotation).normalize()
    bone.updateWorldMatrix(false, true)
  }

  measure()
  requested.copy(wrist)
  direction.subVectors(wrist, shoulder)
  if (direction.lengthSq() === 0) direction.subVectors(joint, shoulder)
  if (direction.lengthSq() === 0) direction.set(1, 0, 0)
  direction.normalize()

  return {
    solve(targetWorld, poleWorldDirection) {
      requested.copy(targetWorld)
      measure()
      const upperLength = shoulder.distanceTo(joint)
      const lowerLength = joint.distanceTo(wrist)
      if (upperLength === 0 || lowerLength === 0) return

      offset.subVectors(requested, shoulder)
      const requestedDistance = offset.length()
      if (requestedDistance > 0) direction.copy(offset).normalize()
      pole.copy(poleWorldDirection).normalize()
      pole.addScaledVector(direction, -pole.dot(direction))
      if (pole.lengthSq() < POLE_EPSILON_SQ) perpendicular(direction, pole)
      else pole.normalize()

      const squaredLengths = upperLength * upperLength + lowerLength * lowerLength
      const twiceProduct = 2 * upperLength * lowerLength
      const minDistance = Math.sqrt(squaredLengths + twiceProduct * Math.cos(MAX_FLEX))
      const maxDistance = Math.sqrt(squaredLengths + twiceProduct * Math.cos(MIN_FLEX))
      const distance = THREE.MathUtils.clamp(requestedDistance, minDistance, maxDistance)
      const along = (upperLength * upperLength - lowerLength * lowerLength + distance * distance) / (2 * distance)
      const height = Math.sqrt(Math.max(0, upperLength * upperLength - along * along))
      elbowTarget.copy(shoulder).addScaledVector(direction, along).addScaledVector(pole, height)
      wristTarget.copy(shoulder).addScaledVector(direction, distance)
      aim(upper, lower, elbowTarget)
      aim(lower, end, wristTarget)
    },
    snapshot() {
      measure()
      const error = wrist.distanceTo(requested)
      wrist.sub(joint)
      joint.sub(shoulder)
      const product = joint.length() * wrist.length()
      const bend = product > 0 ? Math.acos(THREE.MathUtils.clamp(joint.dot(wrist) / product, -1, 1)) : 0
      return { error, bend }
    }
  }
}

export function calibrateHand(hand, index, middle, little, side) {
  if (!hand || !middle) return null
  hand.updateWorldMatrix(true, true)
  const inverse = new THREE.Matrix4().copy(hand.matrixWorld).invert()
  const finger = middle.getWorldPosition(new THREE.Vector3()).applyMatrix4(inverse)
  const palmLength = finger.length()
  if (palmLength > 0) finger.normalize()
  else finger.set(side === 'right' ? -1 : 1, 0, 0).transformDirection(inverse)
  if (finger.lengthSq() === 0) finger.set(1, 0, 0)

  const palm = new THREE.Vector3()
  if (index && little) {
    const span = index.getWorldPosition(new THREE.Vector3()).applyMatrix4(inverse)
    span.sub(little.getWorldPosition(new THREE.Vector3()).applyMatrix4(inverse)).normalize()
    palm.crossVectors(finger, span)
    if (side === 'right') palm.negate()
  }
  if (palm.lengthSq() < POLE_EPSILON_SQ) {
    palm.set(0, -1, 0).transformDirection(inverse)
    palm.addScaledVector(finger, -palm.dot(finger))
    if (palm.lengthSq() < POLE_EPSILON_SQ) perpendicular(finger, palm)
  }
  palm.normalize()
  const tangent = new THREE.Vector3().crossVectors(palm, finger).normalize()
  const basis = new THREE.Matrix4().makeBasis(finger, tangent, palm)
  return {
    rotation: new THREE.Quaternion().setFromRotationMatrix(basis).normalize().invert(),
    curlAxis: new THREE.Vector3().crossVectors(finger, palm).normalize(),
    palmLength
  }
}

import * as THREE from 'three'
const clamp = value => Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0
const smooth = value => value * value * (3 - 2 * value)

/** The grip settles on the table before lifting, and follows the reverse path. */
export function sipPath(progress) {
  const value = clamp(progress)
  return { reach: smooth(clamp(value / 0.35)), lift: smooth(clamp((value - 0.6) / 0.4)) }
}

/** Convert a calibrated palm-frame grip into both normalized and raw hand space. */
export function calibrateCupGrip(hand, rawHand, frame, radius) {
  if (!hand || !rawHand || !frame) return null
  hand.updateWorldMatrix(true, true)
  rawHand.updateWorldMatrix(true, true)
  const localBasis = frame.rotation.clone().invert()
  const normalizedLocal = new THREE.Vector3(frame.palmLength * 0.55, 0, radius * 1.08).applyQuaternion(localBasis)
  const rawLocal = rawHand.worldToLocal(hand.localToWorld(normalizedLocal.clone()))
  const fingerLocal = new THREE.Vector3(1, 0, 0).applyQuaternion(localBasis)
  const palmLocal = new THREE.Vector3(0, 0, 1).applyQuaternion(localBasis)
  const inverseRaw = rawHand.matrixWorld.clone().invert()
  const rawFinger = fingerLocal.clone().transformDirection(hand.matrixWorld).transformDirection(inverseRaw)
  const rawPalm = palmLocal.clone().transformDirection(hand.matrixWorld).transformDirection(inverseRaw)
  return { normalizedLocal, rawLocal, rawFinger, rawPalm }
}

/** One cup owner. A held cup is rigidly attached, never independently lerped. */
export function createCupAttachment(table, tolerance = 0.04) {
  const position = table.clone()
  let attached = false, returning = false, error = null
  return {
    reset() { attached = false; returning = false; error = null; position.copy(table) },
    update({ handGrip, progress = 0, active = false, releasing = false } = {}) {
      if (!handGrip || !handGrip.toArray().every(Number.isFinite)) {
        attached = false; returning = false; error = null; position.copy(table)
        return position
      }
      const distance = handGrip.distanceTo(table)
      if (!attached && active && !releasing && progress >= 0.35 && progress < 0.85 && distance <= tolerance) attached = true
      if (attached) {
        position.copy(handGrip)
        returning = !active || releasing
        if ((!active || (releasing && progress < 0.55)) && distance <= tolerance * 0.8) {
          attached = false; returning = false; position.copy(table)
        }
      } else position.copy(table)
      error = attached ? position.distanceTo(handGrip) : null
      return position
    },
    snapshot() { return { attachment: attached ? 'rightHand' : 'table', returning, position: position.toArray(), gripError: error } }
  }
}

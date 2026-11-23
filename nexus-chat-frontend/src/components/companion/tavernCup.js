import * as THREE from 'three'
const clamp = value => Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0
const smooth = value => value * value * (3 - 2 * value)

/** The grip settles on the table before lifting, and follows the reverse path. */
export function sipPath(progress) {
  const value = clamp(progress)
  return { reach: smooth(clamp(value / 0.35)), lift: smooth(clamp((value - 0.6) / 0.4)) }
}

/** Where a held glass sits in the hand, in palm lengths: along the fingers, and out from the palm past the glass's own radius. */
export const GRIP = { along: 0.85, out: 0.12 }

/**
 * A point fixed in the palm frame (finger, tangent, palm normal; model units),
 * expressed in both the normalized and the raw hand bone so a prop can follow
 * the final rendered skeleton.
 */
export function calibrateHold(hand, rawHand, frame, offset) {
  if (!hand || !rawHand || !frame) return null
  hand.updateWorldMatrix(true, true)
  rawHand.updateWorldMatrix(true, true)
  const localBasis = frame.rotation.clone().invert()
  const normalizedLocal = new THREE.Vector3(offset[0], offset[1], offset[2]).applyQuaternion(localBasis)
  const rawLocal = rawHand.worldToLocal(hand.localToWorld(normalizedLocal.clone()))
  const fingerLocal = new THREE.Vector3(1, 0, 0).applyQuaternion(localBasis)
  const palmLocal = new THREE.Vector3(0, 0, 1).applyQuaternion(localBasis)
  const inverseRaw = rawHand.matrixWorld.clone().invert()
  const rawFinger = fingerLocal.clone().transformDirection(hand.matrixWorld).transformDirection(inverseRaw)
  const rawPalm = palmLocal.clone().transformDirection(hand.matrixWorld).transformDirection(inverseRaw)
  return { normalizedLocal, rawLocal, rawFinger, rawPalm }
}

/** The palm rests against the side of the glass and the fingers close around it. */
export function calibrateCupGrip(hand, rawHand, frame, radius) {
  if (!frame) return null
  return calibrateHold(hand, rawHand, frame, [frame.palmLength * GRIP.along, 0, radius + frame.palmLength * GRIP.out])
}

/** One owner for a prop. A held prop is rigidly attached, never independently lerped. */
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

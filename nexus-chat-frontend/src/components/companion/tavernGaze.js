import * as THREE from 'three'

const finite = value => Number.isFinite(value) ? value : 0
const clamp = (value, min, max) => Math.max(min, Math.min(max, finite(value)))

/** Owns the lookAt target, never writes eye bones alongside the official applier. */
export function createTavernGaze(vrm) {
  const target = new THREE.Object3D()
  const desired = new THREE.Vector3(), direction = new THREE.Vector3()
  const headFollow = { yaw: 0, pitch: 0 }
  let source = 'partner', initialized = false
  if (vrm.lookAt) vrm.lookAt.target = target
  return {
    update({ delta = 0, head, partner, actionTarget, actionWeight = 0, action = 'rest',
      motion = 'idle', pointerX = 0, pointerY = 0, eyeX = 0, eyeY = 0,
      lookTarget = null, lookWeight = 0, reducedMotion = false, settled = false } = {}) {
      if (!head || !partner) return { ...headFollow }
      delta = clamp(delta, 0, 0.1)
      desired.copy(partner)
      source = 'partner'
      if (!reducedMotion && actionTarget && actionWeight > 0.12 && ['sip', 'wipe'].includes(action)) {
        // A nearby hand must not make the eyes converge violently. Track its
        // direction at a comfortable distance, limited by the model's lookAt map.
        direction.copy(actionTarget).sub(head)
        if (direction.lengthSq() > 1e-6) {
          direction.normalize().multiplyScalar(5).add(head)
          desired.lerp(direction, Math.min(0.65, actionWeight * 0.7))
          source = 'action'
        }
      } else if (!reducedMotion && lookTarget && lookWeight > 0.05) {
        // A glance she chose: at the glass, the window, her own hands.
        direction.copy(lookTarget).sub(head)
        if (direction.lengthSq() > 1e-6) {
          direction.normalize().multiplyScalar(5).add(head)
          desired.lerp(direction, Math.min(0.9, lookWeight))
          source = 'glance'
        }
      } else if (!reducedMotion && motion === 'look_away') {
        desired.x += 1.6; source = 'motion'
      } else if (!reducedMotion && motion === 'think') {
        desired.x -= 0.75; desired.y -= 0.25; source = 'motion'
      }
      if (!reducedMotion && source === 'partner' && (pointerX || pointerY)) {
        desired.x += clamp(pointerX, -1, 1) * 0.65
        desired.y -= clamp(pointerY, -1, 1) * 0.4
        source = 'pointer'
      }
      if (!reducedMotion && source !== 'action' && source !== 'glance') {
        desired.x += clamp(eyeX, -0.1, 0.1)
        desired.y += clamp(eyeY, -0.1, 0.1)
      }
      if (reducedMotion) {
        source = 'static'
        if (vrm.lookAt) { vrm.lookAt.autoUpdate = false; vrm.lookAt.reset() }
        headFollow.yaw = 0; headFollow.pitch = 0
      } else if (vrm.lookAt) vrm.lookAt.autoUpdate = true
      const ease = settled || reducedMotion || !initialized ? 1 : 1 - Math.exp(-delta / 0.11)
      target.position.lerp(desired, ease)
      initialized = true
      target.updateMatrixWorld(true)
      direction.copy(target.position).sub(head)
      const yaw = reducedMotion ? 0 : clamp(Math.atan2(direction.x, Math.max(0.1, direction.z)) * 0.22, -0.2, 0.2)
      const pitch = reducedMotion ? 0 : clamp(-Math.atan2(direction.y, Math.max(0.1, direction.z)) * 0.18, -0.13, 0.13)
      const headEase = settled || reducedMotion ? 1 : 1 - Math.exp(-delta / 0.34)
      headFollow.yaw += (yaw - headFollow.yaw) * headEase
      headFollow.pitch += (pitch - headFollow.pitch) * headEase
      return { ...headFollow }
    },
    reset() {
      initialized = false; source = 'partner'; headFollow.yaw = 0; headFollow.pitch = 0
      vrm.lookAt?.reset()
    },
    snapshot() {
      return { source, target: target.position.toArray(), headFollow: { ...headFollow },
        available: !!vrm.lookAt, autoUpdate: vrm.lookAt?.autoUpdate ?? false,
        yaw: vrm.lookAt?.yaw ?? null, pitch: vrm.lookAt?.pitch ?? null }
    }
  }
}

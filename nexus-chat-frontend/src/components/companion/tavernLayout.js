/**
 * Where things are in the room, shared by the stage (which draws the counter and
 * aims the camera) and the actor (which must keep her hands on top of it).
 * World units; the camera looks from +z toward the character.
 */
export const COUNTER = {
  top: -1.22,
  back: 0.775,
  front: 1.825,
  thickness: 0.12,
  width: 8.4
}

/** A medium shot: head, hands and the counter top fill the frame. */
export const STAGE_CAMERA = {
  fov: 31,
  position: [0, 0.46, 7.1],
  target: [0, 0.04, 0.3]
}

/**
 * The lowest a hand part may be at depth z. Over the counter that is its top.
 * Behind its back edge the limit falls away steeply, so a hand in her lap is
 * free, and one coming forward has to rise over the edge instead of through it.
 */
export function counterFloor(z, clearance = 0) {
  if (z > COUNTER.front + 0.05) return -Infinity
  return COUNTER.top + clearance - Math.max(0, COUNTER.back - z) * 6
}

/**
 * Tavern scenes.
 *
 * A scene is atmosphere, not a settings screen: where the character is, what the
 * room sounds and smells like, and how they behave in it. The copy goes into the
 * model's system prompt (see CompanionModelService.SCENE_PROMPTS), so the
 * character reacts to the room rather than to a label.
 *
 * Only the structure lives here — keys and icons. Every string is in the locale
 * files (`companion.sceneNames.*`, `companion.sceneAmbience.*`) so the room
 * speaks the user's language. The prompt copy on the server is Chinese only
 * because that is the language the characters reply in.
 *
 * Kept on the client on purpose. Scenes are hand-written prose, they change with
 * a release, and nothing about them is per-user — a table would only add a join.
 * If user-authored scenes ever become a thing, this is the file to lift into one.
 */

export const TAVERN_SCENES = [
  { key: 'rain-fireplace', icon: 'local_fire_department' },
  { key: 'late-rain', icon: 'water_drop' },
  { key: 'empty-bar', icon: 'nightlife' },
  { key: 'closing-time', icon: 'schedule' }
]

export const DEFAULT_SCENE_KEY = 'rain-fireplace'

export function findScene(key) {
  return TAVERN_SCENES.find((scene) => scene.key === key) || TAVERN_SCENES[0]
}

/**
 * Which scene a role is sitting in.
 *
 * Stored per role so switching characters does not drag the room along. Phase 1
 * keeps this in localStorage; once sessions exist this is read from the open
 * session instead and the local copy becomes a cache.
 */
const SCENE_STORAGE_PREFIX = 'tavern.scene.'

export function readSceneKey(roleId) {
  if (!roleId) return DEFAULT_SCENE_KEY
  try {
    const stored = localStorage.getItem(SCENE_STORAGE_PREFIX + roleId)
    return stored && TAVERN_SCENES.some((s) => s.key === stored) ? stored : DEFAULT_SCENE_KEY
  } catch {
    return DEFAULT_SCENE_KEY
  }
}

export function writeSceneKey(roleId, key) {
  if (!roleId) return
  try {
    localStorage.setItem(SCENE_STORAGE_PREFIX + roleId, key)
  } catch {
    // A blocked storage quota must not break the conversation.
  }
}

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile, access } from 'node:fs/promises'

// The entrance music went silent once because the files that make it were never
// committed: the branch that shipped had the picture but no score, and nothing failed.
// These checks fail when any link between the sound setting and the speakers is missing.
const src = path => new URL(`../${path}`, import.meta.url)
const read = path => readFile(src(path), 'utf8')

test('the score, its clock and the stage that plays them are all in the tree', async () => {
  for (const file of ['components/warp/tavernEntranceMusic.js', 'components/warp/tavernEntranceGrowth.js',
    'components/warp/tavernEntranceScene.js', 'components/warp/TavernEntrance.vue']) await access(src(file))
  const { MUSIC_SCORE, MUSIC_CUES, createTavernEntranceMusic } = await import(src('components/warp/tavernEntranceMusic.js'))
  assert.ok(MUSIC_SCORE.length > 0 && MUSIC_SCORE.every(cue => cue.time < MUSIC_CUES.end && cue.gain > 0))
  assert.equal(typeof createTavernEntranceMusic, 'function')
})

test('the account sound setting reaches the stage, and the stage drives the score', async () => {
  const [main, entrance, scene] = await Promise.all(
    ['views/Main.vue', 'components/warp/TavernEntrance.vue', 'components/warp/tavernEntranceScene.js'].map(read))
  assert.match(main, /<TavernEntrance\s+:sound-enabled="settings\.soundEnabled"/, 'Main passes the setting')
  assert.match(entrance, /defineProps\(\{\s*soundEnabled:/, 'the entrance accepts it')
  assert.match(entrance, /soundEnabled:\s*props\.soundEnabled/, 'and hands it to the scene')
  assert.match(entrance, /setSoundEnabled\(/, 'a change in settings reaches a scene that already exists')
  assert.match(entrance, /\.unlock\(\)/, 'audio is unlocked inside the user gesture')
  assert.match(scene, /import \{ createTavernEntranceMusic \} from '\.\/tavernEntranceMusic\.js'/)
  for (const call of ['music.prepare()', 'music.start(', 'music.tick(', 'music.stop()']) assert.ok(scene.includes(call), `the scene calls ${call}`)
})

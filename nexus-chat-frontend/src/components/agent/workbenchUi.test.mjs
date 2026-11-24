import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { readFile } from 'node:fs/promises'
import { ID_INJECTION_KEY, ZINDEX_INJECTION_KEY } from 'element-plus'
import { buildRunPresentation } from './runPresentation.js'
import { processMessages } from './processMessages.js'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp, h } from 'vue'
import { createI18n } from 'vue-i18n'
import { renderToString } from 'vue/server-renderer'
import zh from '../../locales/zh.json' with { type: 'json' }

let server
const root = fileURLToPath(new URL('../../../', import.meta.url))
before(async () => {
  server = await createServer({ configFile: false, root, plugins: [vue()], resolve: { alias: { '@': `${root}src` } }, server: { middlewareMode: true, hmr: false, watch: null }, logLevel: 'error' })
})
after(() => server?.close())

async function render(name, props) {
  const component = (await server.ssrLoadModule(`/src/components/agent/${name}.vue`)).default
  const app = createSSRApp({ render: () => h(component, props) })
  app.provide(ID_INJECTION_KEY, { prefix: 7100, current: 0 })
  app.provide(ZINDEX_INJECTION_KEY, { current: 0 })
  app.use(createI18n({ legacy: false, locale: 'zh', messages: { zh } }))
  return renderToString(app)
}

const trace = [
  { kind: 'model', nodeId: 'real-1', agentId: 'assistant', iteration: 1, model: 'model-under-test', status: 'done', ms: 85 },
  { kind: 'reasoning', nodeId: 'real-1', agentId: 'assistant', iteration: 1, text: '公开的推理摘要', at: 45 },
  { kind: 'done', finishReason: 'stop', at: 96 }
]

test('each delivered answer offers a folded thought ribbon and genuine trace actions', async () => {
  const html = await render('AgentTrace', { trace, elapsedMs: 96 })
  assert.match(html, /aria-expanded="false"/)
  assert.match(html, /复制思考流/)
  assert.match(html, /思考过程/)
  assert.doesNotMatch(html, /过程终端|4 智能体|53 步|4,237|公开的推理摘要/)
})

test('top board is empty before a real event arrives and does not advertise demo artifacts', async () => {
  const html = await render('AgentExecutionBoard', { run: null, modelLabel: '用户选择的模型' })
  assert.match(html, /执行看板/)
  assert.match(html, /暂无产物/)
  assert.doesNotMatch(html, /AST_Diff|DeepSeek-R1|92%|18ms/)
})

test('the board displays the actual active run and the artifact produced, not a fixture', async () => {
  const html = await render('AgentExecutionBoard', { run: { trace, status: 'done', elapsedMs: 96, artifacts: [{ id: 'result-1', kind: 'json', name: 'actual-result.json', content: '{}' }] }, modelLabel: '用户选择的模型' })
  assert.match(html, /actual-result\.json/)
  assert.match(html, /model-under-test/)
  assert.doesNotMatch(html, /4 智能体|3 项产物/)
})


// SSR and source guards are contracts, not actual viewport/input/motion passes.
// T1 must still execute the real 390/820/1440 and keyboard/touch/browser probes.
test('live traces default to summary rather than auto-expanding model reasoning', async () => {
  const html = await render('AgentTrace', { trace, live: true })
  assert.match(html, /aria-expanded="false"/)
  assert.match(html, /仅看结论/)
  assert.doesNotMatch(html, /公开的推理摘要/)
})

test('expanded trace renders one selectable pixel node per actual projected execution record', async () => {
  const html = await render('AgentTrace', { trace, defaultExpanded: true })
  assert.equal((html.match(/data-exec-node=/g) || []).length, buildRunPresentation(trace).nodes.length)
  assert.match(html, /agent-pixel/)
  assert.match(html, /data-agent-id="assistant"/)
  assert.match(html, /aria-pressed="true"/)
  assert.match(html, /模型原生推理/)
  assert.match(html, /不是执行审计/)
  assert.match(html, /公开的推理摘要/)
})

const liveTrace = [
  { name: 'stage', data: { phase: 'context', status: 'done', runId: 'r', attemptId: 'a' } },
  { name: 'stage', data: { phase: 'model', status: 'start', agentId: 'assistant', nodeId: 'model-1', parentIds: ['context'], runId: 'r', attemptId: 'a' } },
  { name: 'reasoning', data: { agentId: 'assistant', nodeId: 'model-1', text: '正在核对接口字段', runId: 'r', attemptId: 'a' } }
]

test('the pixel workspace sits in the folded thinking process and draws one cell per reported agent or step', async () => {
  const html = await render('AgentTrace', { trace: liveTrace, live: true })
  assert.match(html, /aria-expanded="false"/)
  assert.match(html, /aria-label="像素工作区"/)
  assert.equal((html.match(/data-stage-node=/g) || []).length, 2)
  assert.equal((html.match(/<li class="actor /g) || []).length, 1)
  assert.match(html, /<li class="actor is-working cell"/)
  assert.match(html, /--px-main:#5b8cff/)
  // What the running step is saying is the model's own text, labelled as such; the full reasoning stays folded.
  assert.match(html, /class="dialog" title="这是模型原生推理[^"]*"/)
  assert.match(html, /正在核对接口字段/)
  // Server rendering cannot see the page, so nothing is set moving.
  assert.doesNotMatch(html, /pixel-stage playing/)
})

test('a finished turn keeps a still room, and a turn with no events has no figures at all', async () => {
  const done = await render('AgentTrace', { trace })
  assert.match(done, /<li class="actor is-done cell"/)
  assert.doesNotMatch(done, /class="dialog"/)
  const empty = await render('AgentPixelStage', { view: buildRunPresentation([]) })
  assert.doesNotMatch(empty, /data-stage-node=|agent-pixel/)
  assert.match(empty, /暂无已上报的执行节点/)
})

test('the pixel map moves only for a step that is under way and on screen, and never keeps its own clock', async () => {
  const stage = await readFile(new URL('./AgentPixelStage.vue', import.meta.url), 'utf8')
  const scene = await readFile(new URL('./pixelScene.js', import.meta.url), 'utf8')
  for (const source of [stage, scene]) assert.doesNotMatch(source, /setInterval|setTimeout|requestAnimationFrame|performance\.now\(\)|Date\.now\(\)|Math\.random\(\)/)
  const rules = stage.slice(stage.indexOf('<style')).replace(/\/\*[^]*?\*\//g, '').split('}').map(rule => rule.trim()).filter(rule => /animation:(?!none)/.test(rule) && !rule.startsWith('@'))
  const moving = rules.filter(rule => /animation:[^;}]*infinite/.test(rule))
  assert.ok(moving.length >= 12)
  // Continuous movement needs the map to be playing, and belongs to a figure, a place or a road whose step is under way.
  for (const rule of moving) assert.match(rule, /^\.pixel-stage\.playing (?:\.walker)?\.is-(working|waiting|away)[ .]|^\.pixel-stage\.playing \.road\.live /)
  // Walking a round is one of them: only a working figure that the report gave somewhere to go.
  assert.ok(moving.some(rule => rule.startsWith('.pixel-stage.playing .walker.is-working.loop{animation:px-go')))
  // jump-none needs at least two steps; a rule written with one is dropped by the browser and nothing moves.
  assert.doesNotMatch(stage, /steps\(1,\s*jump-none\)/)
  // Everything else is one movement for one report, and it has an end.
  const once = rules.filter(rule => !moving.includes(rule))
  assert.ok(once.length >= 8)
  for (const rule of once) {
    assert.match(rule, /^\.walker\.go[ {]|^\.just-(gift|oops|failed|done|enter) /)
    for (const part of rule.match(/animation:[^;}]*/g)) assert.ok(/ 1(?: both)?$/.test(part) || rule.includes('animation-iteration-count:var(--px-steps)'), rule)
  }
  // A reaction is kept only if the map was on screen when the report arrived.
  assert.match(stage, /if \(ONCE\.has\(kind\) && seen\) next\[key\]/)
  // A new path starts from where the figure is at that moment, and only if it was being watched and motion is allowed.
  assert.match(stage, /const seen = inView\.value && pageVisible\.value && !still\(\)/)
  assert.match(stage, /if \(old && seen\) \{\s*const share = parseFloat\(bodies\.has\(key\) \? getComputedStyle\(bodies\.get\(key\)\)\.offsetDistance : ''\) \/ 100/)
  assert.match(stage, /gait: loop \? 'loop' : from && total > 2 \? 'go' : null/)
  assert.match(stage, /offsetPath: `path\('\$\{now\.d\}'\)`/)
  // The only transitions are a place gliding to its new spot when the map grows, and a figure standing at it going along.
  assert.match(stage, /\.map\.settled \.cell\{transition:left \.3s steps\(6,end\),top \.3s steps\(6,end\)\}/)
  assert.match(stage, /\.map\.settled \.walker:not\(\.go\):not\(\.loop\)\{transition:offset-path \.3s steps\(6,end\)\}/)
  assert.match(stage, /if \(old && old\.mode === 'home' && plan\.mode === 'home'\) \{ next\[key\] = walk\(plan, null, old\.n\); continue \}/)
  assert.doesNotMatch(stage.replace(/\.map\.settled \.(?:cell|walker:not\(\.go\):not\(\.loop\))\{transition:[^}]*\}/g, ''), /transition:(?!none)/)
  // The row of seats on the board shrinks its name tags before it has to scroll.
  assert.match(stage, /\.lane \.cell\{flex:0 1 auto;min-width:44px\}/)
  assert.match(stage, /playing = computed\(\(\) => scene\.value\.working && inView\.value && pageVisible\.value\)/)
  assert.match(stage, /IntersectionObserver/)
  assert.match(stage, /visibilitychange/)
  assert.match(stage, /prefers-reduced-motion:reduce\)\{[^}]*animation:none!important/)
  assert.match(stage, /min-width:44px;min-height:44px/)
  assert.match(stage, /focus-visible/)
})

const pendingTool = [
  { name: 'stage', data: { phase: 'model', status: 'done', agentId: 'assistant', nodeId: 'model-1', model: 'model-under-test', finishReason: 'tool_calls', ms: 85, runId: 'r', attemptId: 'a' } },
  { name: 'tool_call', data: { toolName: 'get_recent_messages', callId: 'c1', nodeId: 'tool-c1', agentId: 'assistant', parentIds: ['model-1'], runId: 'r', attemptId: 'a' } }
]
// The walking figure the map draws for an agent: its classes and the path it was given.
function walker(html, agentId) {
  const tag = html.match(new RegExp(`<span class="([^"]*)" style="([^"]*)"[^>]*data-walker="${agentId}"`))
  if (!tag) return null
  const path = tag[2].match(/offset-path:path\(&#39;([^&]*)&#39;\)/)?.[1] || ''
  return { classes: tag[1].split(' ').sort(), points: [...path.matchAll(/(\d+) (\d+)/g)].map(match => [Number(match[1]), Number(match[2])]), at: tag[2].match(/offset-distance:([\d.]+)%/)?.[1] }
}
const places = html => [...html.matchAll(/<li class="[^"]*cell" style="[^"]*left:(-?\d+)px;top:(-?\d+)px/g)].map(match => [Number(match[1]), Number(match[2])])

test('figures are small whole figures on a map of places joined by roads, and one waiting on a tool stands beside that tool', async () => {
  const sprite = await readFile(new URL('./AgentPixelSprite.vue', import.meta.url), 'utf8')
  assert.match(sprite, /viewBox="0 0 12 16" width="24" height="32"/)
  for (const part of ['leg-l', 'leg-r', 'arm-l', 'arm-r', 'head']) assert.match(sprite, new RegExp(`class="${part}"`))
  const html = await render('AgentTrace', { trace: pendingTool, live: true })
  assert.match(html, /class="pixel-stage map"/)
  assert.match(html, /<li class="actor is-away cell"/)
  assert.match(html, /等待 get_recent_messages/)
  // Places are set in two dimensions: the desk and the tool share neither a column nor a height.
  const [desk, tool] = places(html)
  assert.ok(tool[0] > desk[0] && tool[1] !== desk[1], JSON.stringify([desk, tool]))
  // One reported dependency, one road, live while the tool is under way, and drawn from the desk to the tool.
  const roads = [...html.matchAll(/<g class="([^"]*road[^"]*)"[^>]*><path class="bed" d="M(\d+) (\d+)[^"]*L(\d+) (\d+)"/g)]
  assert.equal(roads.length, 1)
  assert.match(roads[0][1], /\blive\b/)
  // The figure is not at its desk: its path leaves the desk and ends beside the tool, and that is where it stands.
  const figure = walker(html, 'assistant')
  assert.deepEqual(figure.classes, ['is-away', 'walker'])
  assert.deepEqual(figure.points[0], [Number(roads[0][2]), Number(roads[0][3])])
  assert.ok(Math.abs(figure.points.at(-1)[0] - Number(roads[0][4])) <= 28 && figure.points.at(-1)[1] === Number(roads[0][5]))
  assert.equal(figure.at, '100')
  // Nothing has been reported to react to yet, so nothing is startled.
  assert.doesNotMatch(html, /just-|bubble flash/)
})

test('a working figure walks to and fro only where its running step reported a dependency; otherwise it stays at its desk', async () => {
  const from = data => ({ name: 'stage', data: { runId: 'r', attemptId: 'a', ...data } })
  // The context step was reported under the id the model call names as its parent: there is a road, and a round to walk.
  const tied = await render('AgentTrace', { trace: [from({ phase: 'context', nodeId: 'context', status: 'done' }), from({ phase: 'model', status: 'start', agentId: 'assistant', nodeId: 'model-1', parentIds: ['context'] })], live: true })
  const going = walker(tied, 'assistant')
  assert.deepEqual(going.classes, ['is-working', 'loop', 'walker'])
  assert.ok(going.points.length >= 2)
  assert.equal((tied.match(/<g class="[^"]*road[^"]*"/g) || []).length, 1)
  // Until the page can be seen it stands at its desk, which is the end of that path.
  assert.equal(going.at, '100')
  // The same call with a parent nobody reported: no road is invented and the figure has nowhere to go.
  const loose = await render('AgentTrace', { trace: liveTrace, live: true })
  const staying = walker(loose, 'assistant')
  assert.deepEqual(staying.classes, ['is-working', 'walker'])
  assert.equal(staying.points.length, 1)
  assert.doesNotMatch(loose, /<g class="[^"]*road/)
  // A finished turn keeps its roads and walks nobody.
  const done = await render('AgentTrace', { trace: [from({ phase: 'context', nodeId: 'context', status: 'done' }), from({ phase: 'model', status: 'start', agentId: 'assistant', nodeId: 'model-1', parentIds: ['context'] }), from({ phase: 'model', status: 'done', agentId: 'assistant', nodeId: 'model-1' }), { name: 'done', data: { finishReason: 'stop', runId: 'r', attemptId: 'a' } }] })
  assert.deepEqual(walker(done, 'assistant').classes, ['is-done', 'walker'])
  assert.equal((done.match(/<g class="road"/g) || []).length, 1)
})

test('the board is one slim bar with every reported role seated in a row, and its details wait in a panel that opens over the page', async () => {
  const html = await render('AgentExecutionBoard', { run: { trace, status: 'done', elapsedMs: 96 }, modelLabel: '用户选择的模型' })
  const bar = html.slice(html.indexOf('class="board-bar"'), html.indexOf('class="board-panel"'))
  const panel = html.slice(html.indexOf('class="board-panel"'))
  // The bar: title, state, the roles in one row, and how many nodes are under way, finished and failed.
  assert.match(bar, /执行看板/)
  assert.match(bar, /class="pixel-stage lane board-lane"/)
  assert.equal((bar.match(/data-board-node=/g) || []).length, 1)
  assert.match(bar, /class="seat"/)
  assert.match(bar, /<dd[^>]*>1<\/dd><dt[^>]*>已完成<\/dt>/)
  // A state nothing is in is not counted, so its word does not appear.
  assert.doesNotMatch(bar, /<dt[^>]*>(运行中|失败)<\/dt>/)
  // Nobody walks on the board, a seat is not a control, and the only control in the bar is the one that opens the panel.
  assert.doesNotMatch(html, /data-stage-node=|data-walker=|class="roads"|class="dialog"/)
  assert.equal((bar.match(/<button/g) || []).length, 1)
  assert.match(bar, /aria-expanded="false"[^>]*aria-controls="execution-board-\d+"|aria-controls="execution-board-\d+"[^>]*aria-expanded="false"/)
  // The panel is closed until asked for, and holds what each desk reported.
  assert.match(panel, /^class="board-panel"[^>]*style="display:none;"|^class="board-panel"[^>]*aria-hidden="true"[^>]*style="display:none;"/)
  assert.match(panel, /执行岗位/)
  assert.match(panel, /已记录 1 个节点/)
  assert.match(panel, /class="pixel-stage board desks"/)
  assert.equal((panel.match(/data-board-node=/g) || []).length, 1)
  assert.match(panel, /model-under-test/)
  // The tile carries the short word; the exact label and value are in its tooltip.
  assert.match(panel, />耗时 85 ms</)
  assert.match(panel, /title="assistant · 已完成 · 节点上报耗时: 85 ms"/)
  assert.doesNotMatch(panel.slice(panel.indexOf('pixel-stage board'), panel.indexOf('</ol>')), /<button/)
  const waiting = await render('AgentExecutionBoard', { run: { trace: pendingTool, status: 'streaming' } })
  assert.equal((waiting.match(/<li class="actor is-away cell"/g) || []).length, 2)
  assert.match(waiting, /<dd[^>]*>1<\/dd><dt[^>]*>运行中<\/dt><\/div><div[^>]*><dd[^>]*>1<\/dd><dt[^>]*>已完成<\/dt>/)
  assert.match(waiting, /等待 get_recent_messages/)
  assert.match(waiting, />耗时 未上报</)
  // A desk whose step named no model says what kind of step it is, never a bare phase word.
  const unnamed = await render('AgentExecutionBoard', { run: { trace: [{ name: 'stage', data: { phase: 'model', status: 'start', agentId: 'code', nodeId: 'm', runId: 'r', attemptId: 'a' } }], status: 'streaming' } })
  assert.match(unnamed, /<span class="sub"[^>]*>模型调用<\/span>/)
  // Before anything is reported the bar says so and counts nothing.
  const idle = await render('AgentExecutionBoard', { run: null })
  assert.match(idle.slice(idle.indexOf('class="board-bar"'), idle.indexOf('class="board-panel"')), /class="board-idle"[^>]*>暂无已上报的执行节点。</)
  assert.doesNotMatch(idle, /board-tally|data-board-node=/)
})

test('the board stays above the conversation instead of scrolling with it, and is small', async () => {
  const board = await readFile(new URL('./AgentExecutionBoard.vue', import.meta.url), 'utf8')
  const stage = await readFile(new URL('./AgentPixelStage.vue', import.meta.url), 'utf8')
  const view = await readFile(new URL('./AgentChatView.vue', import.meta.url), 'utf8')
  // It is mounted once, before the scrolling feed and outside it, in a dock that never shrinks or scrolls.
  assert.equal((view.match(/<AgentExecutionBoard /g) || []).length, 1)
  assert.match(view, /<div class="board-dock"><AgentExecutionBoard [^>]*\/><\/div>\s*<div ref="feed" class="feed"/)
  assert.match(view, /\.board-dock\{position:relative;z-index:3;flex:none;/)
  assert.doesNotMatch(view.slice(view.indexOf('class="reading-column"'), view.indexOf('<AgentComposer')), /AgentExecutionBoard/)
  // One row, 40px, and the figures in it are the 32px sprites.
  assert.match(board, /\.board-bar\{display:flex;align-items:center;gap:12px;min-width:0;min-height:40px;/)
  assert.match(stage, /\.lane \.art\{flex:none;width:44px;height:32px\}/)
  // Its details open over the conversation and do not push it down; they scroll inside themselves.
  assert.match(board, /\.board-panel\{position:absolute;z-index:5;[^}]*max-height:min\(56vh,340px\);[^}]*overflow:auto/)
  assert.match(board, /function pressedOutside\(event\) \{ if \(expanded\.value && root\.value && !root\.value\.contains\(event\.target\)\) expanded\.value = false \}/)
  assert.match(board, /const expanded = ref\(false\)/)
  // The 3D scene is not loaded with the bar, only when the panel is first opened.
  assert.doesNotMatch(board, /^import [^\n]*abyssStage/m)
  assert.match(board, /watch\(expanded, async open => \{ if \(open\) \{ await nextTick\(\); await mountStage\(\) \} \}\)/)
  // Type is the size of the surrounding text or smaller; 15px is the icon glyph.
  const sizes = [...board.matchAll(/font(?:-size)?:(?:[0-9 ]*?)(\d+)px/g)].map(match => Number(match[1]))
  assert.ok(sizes.length >= 8)
  assert.ok(Math.max(...sizes.filter(size => size !== 15)) <= 12, String(sizes))
  assert.match(board, /\.board-title h3\{margin:0;font-size:12px/)
  assert.match(board, /\.execution-board h4\{[^}]*font-size:11px/)
  // The opener is compact for a mouse and a full 44px target on a touch screen.
  assert.match(board, /\.board-toggle\{[^}]*min-width:28px;height:28px/)
  assert.match(board, /@media\(pointer:coarse\)\{\.board-toggle\{min-width:44px;min-height:44px;height:44px\}\.file-item\{min-height:44px\}\}/)
})

test('folded and expanded disclosure targets remain unique for two copies of the same run', async () => {
  const Component = (await server.ssrLoadModule('/src/components/agent/AgentTrace.vue')).default
  const app = createSSRApp({ render: () => h('div', [h(Component, { trace, runId: 'same', defaultExpanded: true }), h(Component, { trace, runId: 'same' })]) })
  app.provide(ID_INJECTION_KEY, { prefix: 7200, current: 0 }); app.provide(ZINDEX_INJECTION_KEY, { current: 0 })
  app.use(createI18n({ legacy: false, locale: 'zh', messages: { zh } }))
  const html = await renderToString(app)
  const ids = [...html.matchAll(/\sid="([^"]+)"/g)].map(match => match[1])
  assert.equal(new Set(ids).size, ids.length)
  for (const match of html.matchAll(/aria-controls="([^"]+)"/g)) for (const target of match[1].split(/\s+/)) assert.equal(ids.filter(id => id === target).length, 1)
})

test('unreported values and local cancellation never become a completed board', async () => {
  const stopped = [{ kind: 'model', nodeId: 'n1', status: 'cancelled' }, { kind: 'done', local: true, finishReason: 'cancelled' }]
  const html = await render('AgentExecutionBoard', { run: { trace: stopped, status: 'cancelled' } })
  assert.match(html, /后端取消未确认/)
  assert.match(html, /未上报/)
  assert.doesNotMatch(html, /已取消|已完成|92%/)
})

test('source guards keep clocks and continuous sprite animations out of the process UI', async () => {
  for (const name of ['AgentTrace.vue', 'AgentPixelSprite.vue', 'runPresentation.js']) {
    const source = await readFile(new URL('./' + name, import.meta.url), 'utf8')
    assert.doesNotMatch(source, /setInterval|performance\.now\(\)|Date\.now\(\)|Math\.random\(\)/)
    assert.doesNotMatch(source, /animation:[^;\n}]*infinite/)
  }
})

test('source guards require 44px controls, visible focus, responsive columns and reduced-motion handling', async () => {
  const traceSource = await readFile(new URL('./AgentTrace.vue', import.meta.url), 'utf8')
  const graphSource = await readFile(new URL('./AgentRunGraph.vue', import.meta.url), 'utf8')
  const boardSource = await readFile(new URL('./AgentExecutionBoard.vue', import.meta.url), 'utf8')
  for (const source of [traceSource, graphSource, boardSource]) {
    assert.match(source, /min-width:44px/)
    assert.match(source, /focus-visible/)
    assert.match(source, /prefers-reduced-motion:reduce/)
  }
  for (const source of [traceSource, graphSource]) assert.match(source, /min-height:(?:44|100)px/)
  // The board is a slim bar: its controls are 44px on a touch screen (asserted with the board's own test).
  assert.match(boardSource, /@media\(pointer:coarse\)\{[^@]*min-height:44px/)
  assert.match(traceSource, /@container\(min-width:680px\)/)
  assert.match(graphSource, /@media\(max-width:600px\)/)
  assert.match(boardSource, /visibilitychange/)
  assert.match(boardSource, /onDemand: true/)
  assert.match(traceSource, /watch\(open,/)
  assert.match(traceSource, /removedFocusedNode/)
})

test('existing stage retains its scene but on-demand mode pauses recurring frames and exposes invalidation', async () => {
  const source = await readFile(new URL('./abyssStage.js', import.meta.url), 'utf8')
  assert.match(source, /mountAbyssStage/)
  assert.match(source, /onDemand = false/)
  assert.match(source, /if \(!onDemand \|\| !reducedMotion\.matches\) frame = requestFrame/)
  assert.match(source, /dispose\.refresh = refresh/)
  assert.match(source, /onRenderSuspendChange\(refresh\)/)
})

test('T6 local translations have the same keys for Chinese and English', () => {
  const keys = (value, prefix = '') => Object.entries(value).flatMap(([key, item]) => item && typeof item === 'object' ? keys(item, prefix + key + '.') : [prefix + key]).sort()
  assert.deepEqual(keys(processMessages.zh), keys(processMessages.en))
})

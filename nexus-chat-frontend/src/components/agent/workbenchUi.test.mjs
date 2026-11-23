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

test('the pixel workspace moves only while a step is running and on screen, and never keeps its own clock', async () => {
  const stage = await readFile(new URL('./AgentPixelStage.vue', import.meta.url), 'utf8')
  const scene = await readFile(new URL('./pixelScene.js', import.meta.url), 'utf8')
  for (const source of [stage, scene]) assert.doesNotMatch(source, /setInterval|setTimeout|requestAnimationFrame|performance\.now\(\)|Date\.now\(\)|Math\.random\(\)/)
  const moving = stage.split('\n').filter(line => /animation:[^;}]*infinite/.test(line))
  assert.ok(moving.length >= 4)
  for (const line of moving) for (const rule of line.split('}').filter(rule => /animation:[^;}]*infinite/.test(rule))) {
    assert.match(rule, /^\.pixel-stage\.playing \.is-(working|waiting) /)
  }
  assert.match(stage, /playing = computed\(\(\) => scene\.value\.working && inView\.value && pageVisible\.value\)/)
  assert.match(stage, /IntersectionObserver/)
  assert.match(stage, /visibilitychange/)
  assert.match(stage, /prefers-reduced-motion:reduce\)\{[^}]*animation:none!important/)
  assert.match(stage, /min-width:44px;min-height:44px/)
  assert.match(stage, /focus-visible/)
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
    assert.match(source, /min-height:(?:44|100)px/)
    assert.match(source, /focus-visible/)
    assert.match(source, /prefers-reduced-motion:reduce/)
  }
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

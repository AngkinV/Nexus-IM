import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
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

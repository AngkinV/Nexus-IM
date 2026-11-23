<template>
  <div ref="host" class="pixel-stage" :class="{ playing }" role="group" :aria-label="t('stage')">
    <p v-if="!scene.cells.length" class="stage-empty">{{ t('emptyNodes') }}</p>
    <ol v-else class="stage-row">
      <li v-for="cell in scene.cells" :key="cell.key" class="cell" :class="[cell.type, 'is-' + (cell.pose || 'done'), { selected: cell.nodeId === selectedId }]" :style="tint(cell)">
        <ElButton class="cell-btn" :data-stage-node="cell.nodeId" :data-agent-id="cell.agentId || undefined" :aria-pressed="cell.nodeId === selectedId" :aria-label="describe(cell)" :title="describe(cell)" @click="emit('focus-node', cell.nodeId)">
          <span class="cell-body">
            <template v-if="cell.type === 'actor'">
              <span v-if="cell.pose === 'working'" class="bubble dots" aria-hidden="true"><i></i><i></i><i></i></span>
              <span v-else class="bubble mark" aria-hidden="true">{{ MARK[cell.pose] }}</span>
              <span class="figure"><AgentPixelSprite :agent-id="cell.agentId" :node-id="cell.nodeId" :state="cell.state" /><span class="desk" aria-hidden="true"><i v-for="paper in cell.handoffs" :key="paper.agentId" class="paper" :style="{ background: paper.color.main }"></i></span></span>
              <span class="name">{{ cell.label }}</span>
              <span class="sub">{{ t('state.' + cell.state) }}<template v-if="cell.calls > 1"> ×{{ cell.calls }}</template></span>
            </template>
            <template v-else-if="cell.type === 'prop'">
              <span class="bubble mark" aria-hidden="true">{{ cell.reused ? '↻' : MARK[cell.pose] }}</span>
              <span class="figure"><svg class="prop-icon" viewBox="0 0 16 16" width="48" height="48" aria-hidden="true" focusable="false" shape-rendering="crispEdges"><path class="prop-body" fill-rule="evenodd" :d="ICONS[cell.icon]" /><path class="led" d="M12 13h2v2h-2z" /></svg></span>
              <span class="name">{{ cell.label || t('kind.' + cell.kind) }}</span>
              <span class="sub">{{ t(cell.reused ? 'reused' : 'state.' + cell.state) }}</span>
            </template>
            <template v-else>
              <span class="figure fold">+{{ cell.count }}</span>
              <span class="sub">{{ t('more', { n: cell.count }) }}</span>
            </template>
          </span>
        </ElButton>
      </li>
    </ol>
    <div v-if="scene.lines.length" class="dialog" :title="t('reasoningNote')">
      <p v-for="line in scene.lines" :key="line.agentId" class="dialog-line" :style="{ '--px-main': line.color.main }"><b>{{ line.label }}</b><span class="said"><span>{{ line.text }}</span></span></p>
    </div>
    <p v-if="scene.earlier" class="stage-note">{{ t('earlierAttempts', { n: scene.earlier }) }}</p>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElButton } from 'element-plus'
import { useI18n } from 'vue-i18n'
import AgentPixelSprite from './AgentPixelSprite.vue'
import { buildPixelScene } from './pixelScene.js'
import { processMessages } from './processMessages.js'

// The room is drawn from the run presentation alone. A figure moves only while
// the step it stands for is reported as running, and only while it can be seen.
const props = defineProps({ view: { type: Object, required: true }, selectedId: { type: String, default: null } })
const emit = defineEmits(['focus-node'])
const { t, te } = useI18n({ useScope: 'local', messages: processMessages })
const scene = computed(() => buildPixelScene(props.view))
const host = ref(null), inView = ref(false), pageVisible = ref(true)
const playing = computed(() => scene.value.working && inView.value && pageVisible.value)

const MARK = { done: '✓', failed: '!', limited: '…', stopped: '‖', unknown: '?', waiting: '…', idle: '', skipped: '–', working: '' }
const ICONS = {
  shelf: 'M2 2h12v12H2zM3 3v4h10V3zM3 8v5h10V8zM4 4h1v3H4zM6 3h2v4H6zM9 4h1v3H9zM11 3h1v4h-1zM4 9h2v4H4zM7 10h1v3H7zM9 9h3v4H9z',
  book: 'M2 3h5v1h2V3h5v10H9v1H7v-1H2zM3 4v8h4V4zM9 4v8h4V4zM4 6h2v1H4zM4 8h2v1H4zM10 6h2v1h-2zM10 8h2v1h-2z',
  paper: 'M4 2h6v1h1v1h1v10H4zM5 3v10h6V5H9V3zM6 7h4v1H6zM6 9h4v1H6zM6 11h3v1H6z',
  globe: 'M5 2h6v1h2v2h1v6h-1v2h-2v1H5v-1H3v-2H2V5h1V3h2zM5 3v1H4v1H3v2h3V3zM7 3v4h2V3zM10 3v4h3V5h-1V4h-1V3zM3 9v2h1v1h1v1h1V9zM7 9v4h2V9zM10 9v4h1v-1h1v-1h1V9z',
  terminal: 'M2 3h12v9h-4v1h2v1H4v-1h2v-1H2zM3 4v7h10V4zM4 5h1v1h1v1h1v1H6v1H5v1H4V9h1V8h1V7H5V6H4zM8 9h3v1H8z',
  toolbox: 'M5 3h6v2h3v8H2V5h3zM6 4v1h4V4zM3 6v2h4V7h2v1h4V6zM3 9v3h10V9H9v1H7V9z',
  table: 'M6 3h4v3h4v2h-1v5h-2V8H5v5H3V8H2V6h4zM7 4v2h2V4z',
  check: 'M2 3h12v10H2zM3 4v8h10V4zM11 6h1v1h-1v1h-1v1H9v1H8v1H7v-1H6V9H5V8h1v1h1v1h1V9h1V8h1V7h1z',
  flask: 'M6 2h4v1H9v3h1v2h1v2h1v2h1v2H3v-2h1v-2h1V8h1V6h1V3H6zM6 9v1H5v2h6v-2h-1V9z',
  chip: 'M6 2h1v2h2V2h1v2h2v2h2v1h-2v2h2v1h-2v2h-2v2H9v-2H7v2H6v-2H4v-2H2V9h2V7H2V6h2V4h2zM5 5v6h6V5zM7 7h2v2H7z',
  crate: 'M2 4h12v9H2zM3 5v7h4V5zM9 5v7h4V5z'
}

const tint = cell => cell.color ? { '--px-main': cell.color.main, '--px-dark': cell.color.dark } : null
function describe(cell) {
  if (cell.type === 'more') return t('more', { n: cell.count })
  const name = cell.label || (te('kind.' + cell.kind) ? t('kind.' + cell.kind) : cell.kind) || t('unreported')
  const parts = [name, t('state.' + cell.state)]
  if (cell.reused) parts.push(t('reused'))
  if (cell.handoffs?.length) parts.push(t('handoff', { from: cell.handoffs.map(paper => paper.agentId).join(' / ') }))
  return parts.join(' · ')
}

let observer
const visibilityChanged = () => { pageVisible.value = !document.hidden }
onMounted(() => {
  visibilityChanged(); document.addEventListener('visibilitychange', visibilityChanged)
  if (typeof IntersectionObserver !== 'undefined') { observer = new IntersectionObserver(entries => { inView.value = !!entries.at(-1)?.isIntersecting }); observer.observe(host.value) } else inView.value = true
})
onUnmounted(() => { observer?.disconnect(); if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', visibilityChanged) })
</script>

<style scoped>
.pixel-stage{--px-ok:#3ecf8e;--px-bad:#ff5d5d;--px-warn:#f5a524;--px-live:#5b8cff;--px-off:#7b8087;min-width:0;margin:8px 0 2px;padding:6px 6px 0;border:1px solid var(--wb-line,#303034);border-radius:8px;overflow:hidden;
  background-color:#15161a;background-image:linear-gradient(rgba(255,255,255,.035) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,.035) 1px,transparent 1px);background-size:12px 12px}
.stage-row{display:flex;flex-wrap:wrap;align-items:flex-end;gap:2px 4px;margin:0;padding:0 0 6px;list-style:none;min-width:0;border-bottom:3px solid #2a2c33;box-shadow:inset 0 -6px 0 -3px #1f2026}
.cell{flex:0 0 auto;width:76px;min-width:0}
.cell-btn{display:block;width:100%;height:auto;min-width:44px;min-height:44px;margin:0;padding:2px 2px 4px;border:1px solid transparent;border-radius:6px;background:transparent;color:#e6e6e6;white-space:normal}
.cell-btn:hover{background:rgba(255,255,255,.06);border-color:#3a3c44;color:#fff}.cell-btn:focus-visible{outline:2px solid #eee;outline-offset:2px}.cell.selected .cell-btn{background:rgba(255,255,255,.08);border-color:var(--px-main,#8a8f98)}
.cell-btn :deep(>span){display:block;width:100%;min-width:0}.cell-body{display:grid;justify-items:center;gap:1px;width:100%;min-width:0}
.bubble{display:block;box-sizing:border-box;min-height:18px;padding:1px 5px;border:2px solid #2a2c33;border-radius:2px;background:#f4f4f4;color:#16171a;font:700 12px/12px var(--wb-mono,monospace);text-align:center}
.bubble.mark{border-color:transparent;background:transparent;color:var(--px-off)}
.bubble.dots{display:flex;gap:2px;align-items:center;justify-content:center}.bubble.dots i{width:3px;height:3px;background:#16171a;opacity:.25}
.is-done .bubble.mark{color:var(--px-ok)}.is-failed .bubble.mark{color:var(--px-bad)}.is-limited .bubble.mark,.is-waiting .bubble.mark{color:var(--px-warn)}
.figure{position:relative;display:block;width:48px;height:48px}.figure :deep(.agent-pixel){position:relative;z-index:1}
.desk{position:absolute;z-index:2;left:-4px;right:-4px;bottom:0;height:6px;display:flex;gap:2px;align-items:flex-start;justify-content:center;background:#6b4a2e;border-top:3px solid #9a7048}
.paper{width:6px;height:3px;margin-top:-3px;box-shadow:0 0 0 1px #16171a}
.prop-icon{display:block;width:48px;height:48px;image-rendering:pixelated}.prop-body{fill:var(--px-main,#9aa0a6)}.led{fill:var(--px-off)}
.is-working .led,.is-waiting .led{fill:var(--px-live)}.is-done .led{fill:var(--px-ok)}.is-failed .led{fill:var(--px-bad)}.is-limited .led{fill:var(--px-warn)}
.is-stopped .prop-icon,.is-unknown .prop-icon,.is-skipped .prop-icon{filter:grayscale(1) brightness(.8)}.is-failed .prop-body{fill:var(--px-bad)}
.fold{display:grid;place-items:center;border:2px dashed #4a4d57;border-radius:2px;color:#cfd1d6;font:700 13px/1 var(--wb-mono,monospace);box-sizing:border-box}
.name,.sub{display:block;max-width:100%;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;font:11px/14px var(--wb-mono,monospace)}.name{color:#f0f0f0}.actor .name{color:var(--px-main,#f0f0f0);font-weight:700}.sub{color:#b4b7be;font-size:10px}
.stage-empty,.stage-note{margin:0;padding:6px 2px 8px;color:#b4b7be;font-size:12px;line-height:1.5;overflow-wrap:anywhere}
/* One line per figure that is working: its name, then the end of what the model last wrote. */
.dialog{margin:6px 0;padding:4px 8px;border:2px solid #e6e6e6;border-radius:2px;background:#0c0d10;box-shadow:inset 0 0 0 2px #0c0d10,inset 0 0 0 3px #4a4d57;min-width:0}
.dialog-line{display:flex;gap:8px;align-items:baseline;margin:0;min-width:0;font:12px/18px var(--wb-mono,monospace);color:#e8e8e8}.dialog-line b{flex:none;max-width:40%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--px-main)}
.said{display:flex;flex:1 1 0;justify-content:flex-end;min-width:0;overflow:hidden;white-space:nowrap}.said>span{flex:none;margin-right:auto}

/* Movement belongs to a step that is reported as running, in a room that is on screen. */
.pixel-stage.playing .is-working :deep(.hands){animation:px-hands .45s steps(2,jump-none) infinite}
.pixel-stage.playing .is-working :deep(.eyes){animation:px-blink 2.4s steps(1,jump-none) infinite}
.pixel-stage.playing .is-waiting :deep(.agent-pixel){animation:px-nod 1.2s steps(2,jump-none) infinite}
.pixel-stage.playing .is-working .led,.pixel-stage.playing .is-waiting .led{animation:px-led .9s steps(2,jump-none) infinite}
.pixel-stage.playing .is-working .bubble.dots i{animation:px-dot 1.2s steps(1,jump-none) infinite}.pixel-stage.playing .bubble.dots i:nth-child(2){animation-delay:.4s}.pixel-stage.playing .bubble.dots i:nth-child(3){animation-delay:.8s}
@keyframes px-hands{from{transform:translateY(0)}to{transform:translateY(1px)}}
@keyframes px-blink{0%,92%{opacity:1}93%,100%{opacity:0}}
@keyframes px-nod{from{transform:translateY(0)}to{transform:translateY(1px)}}
@keyframes px-led{from{opacity:1}to{opacity:.25}}
@keyframes px-dot{0%,33%{opacity:1}34%,100%{opacity:.25}}
@media(max-width:600px){.cell{width:68px}.pixel-stage{padding:4px 4px 0}}
@media(prefers-reduced-motion:reduce){.pixel-stage :deep(*),.pixel-stage *{animation:none!important;transition:none!important}}
</style>

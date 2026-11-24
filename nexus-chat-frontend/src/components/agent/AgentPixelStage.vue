<template>
  <div ref="host" class="pixel-stage" :class="{ playing, board, lane, map: !flat, settled }" role="group" :aria-label="t('stage')">
    <p v-if="!scene.cells.length" class="stage-empty">{{ t('emptyNodes') }}</p>
    <div v-else class="room" :style="layout ? { height: layout.height + 'px' } : null">
      <svg v-if="layout" class="roads" :width="layout.width" :height="layout.height" aria-hidden="true" focusable="false" shape-rendering="crispEdges">
        <g v-for="road in layout.roads" :key="road.key" class="road" :class="{ live: road.live, back: road.back }" :style="road.color ? { '--px-main': road.color.main } : null"><path class="bed" :d="road.d" /><path class="dash" :d="road.d" /></g>
      </svg>
      <ol class="sites">
        <li v-for="cell in scene.cells" :key="cell.key" class="cell" :class="[cell.type, 'is-' + (cell.stance || 'done'), reaction(cell), { selected: cell.nodeId === selectedId }]" :style="place(cell)">
          <component :is="flat ? 'div' : ElButton" class="cell-btn" v-bind="shell(cell)" :data-agent-id="cell.agentId || undefined" @click="pick(cell)">
            <span class="cell-body">
              <span class="art">
                <template v-if="cell.type === 'actor'">
                  <span class="plot" aria-hidden="true"></span>
                  <span v-if="flat" class="seat"><span :key="marks[cell.key]?.n || 0" class="react">
                    <span v-if="board && cell.stance === 'working'" class="bubble dots" aria-hidden="true"><i></i><i></i><i></i></span>
                    <span v-else-if="board && MARK[cell.stance]" class="bubble mark" aria-hidden="true">{{ MARK[cell.stance] }}</span>
                    <AgentPixelSprite :agent-id="cell.agentId" :node-id="cell.nodeId" :state="cell.state" />
                  </span></span>
                  <span class="monitor" aria-hidden="true"><i></i></span>
                  <span class="desk" aria-hidden="true"><i v-for="paper in cell.handoffs" :key="paper.agentId" class="paper" :style="{ background: paper.color.main }"></i></span>
                </template>
                <template v-else-if="cell.type === 'prop'">
                  <span class="plot" aria-hidden="true"></span>
                  <span v-if="cell.reused || MARK[cell.stance]" class="badge" aria-hidden="true">{{ cell.reused ? '↻' : MARK[cell.stance] }}</span>
                  <svg class="prop-icon" viewBox="0 0 16 16" width="32" height="32" aria-hidden="true" focusable="false" shape-rendering="crispEdges"><path class="prop-body" fill-rule="evenodd" :d="PROP_ICONS[cell.icon]" /><path class="led" d="M12 13h2v2h-2z" /></svg>
                </template>
                <span v-else class="fold">+{{ cell.count }}</span>
              </span>
              <span class="tag">
                <template v-if="cell.type === 'more'"><span class="sub">{{ t('more', { n: cell.count }) }}</span></template>
                <template v-else>
                  <span class="name">{{ cell.label || t('kind.' + cell.kind) }}</span>
                  <span v-if="board && cell.type === 'actor'" class="sub">{{ cell.title || t('kind.' + cell.kind) }}</span>
                  <span class="sub state">{{ cell.at ? t('waitingOn', { step: stepName(cell.at) }) : t(cell.reused ? 'reused' : 'state.' + cell.state) }}<template v-if="cell.calls > 1"> ×{{ cell.calls }}</template></span>
                  <span v-if="board" class="sub">{{ t('elapsed') }} {{ cell.ms == null ? t('unreported') : duration(cell.ms) }}</span>
                </template>
              </span>
            </span>
          </component>
        </li>
      </ol>
      <template v-if="layout">
        <span v-for="cell in figures" :key="cell.key + '#' + (walks[cell.key]?.n || 0)" :ref="el => keep(cell.key, el)" class="walker" :class="['is-' + cell.stance, walks[cell.key]?.gait, reaction(cell)]" :style="pace(cell)" :data-walker="cell.agentId" aria-hidden="true" @click="pick(cell)" @animationend="arrived(cell.key, $event)">
          <span :key="marks[cell.key]?.n || 0" class="react">
            <span v-if="cell.stance === 'working'" class="bubble dots"><i></i><i></i><i></i></span>
            <span v-else-if="MARK[cell.stance]" class="bubble mark">{{ MARK[cell.stance] }}</span>
            <span v-if="STARTLED.has(marks[cell.key]?.kind)" class="bubble flash">!</span>
            <i v-if="cell.stance === 'limited'" class="sweat"></i>
            <AgentPixelSprite :agent-id="cell.agentId" :node-id="cell.nodeId" :state="cell.state" />
          </span>
        </span>
      </template>
    </div>
    <div v-if="!flat && scene.lines.length" class="dialog" :title="t('reasoningNote')">
      <p v-for="line in scene.lines" :key="line.agentId" class="dialog-line" :style="{ '--px-main': line.color.main }"><b>{{ line.label }}</b><span class="said"><span>{{ line.text }}</span></span></p>
    </div>
    <p v-if="!flat && scene.earlier" class="stage-note">{{ t('earlierAttempts', { n: scene.earlier }) }}</p>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElButton } from 'element-plus'
import { useI18n } from 'vue-i18n'
import AgentPixelSprite from './AgentPixelSprite.vue'
import { buildPixelScene, layoutMap, MAP, pathData, pathLength, pointAt, PROP_ICONS, routeBetween, SPEED } from './pixelScene.js'
import { processMessages } from './processMessages.js'

// A small map of the turn, drawn from the run presentation alone. Places are the reported
// steps and agents, roads are the reported dependencies, and a figure walks only along a
// road, only while a step of its own is reported as under way, and only while it can be seen.
// `lane` and `board` draw the same figures seated at their desks instead: a single compact
// row for the pinned board, and a grid of desks with their details for its opened panel.
const props = defineProps({ view: { type: Object, required: true }, selectedId: { type: String, default: null }, board: Boolean, lane: Boolean, max: { type: Number, default: null } })
const emit = defineEmits(['focus-node'])
const { t, te } = useI18n({ useScope: 'local', messages: processMessages })
const flat = computed(() => props.board || props.lane)
const scene = computed(() => buildPixelScene(props.view, { max: props.max ?? (flat.value ? 24 : 12) }))
const host = ref(null), inView = ref(false), pageVisible = ref(true), width = ref(0), settled = ref(false)
const playing = computed(() => scene.value.working && inView.value && pageVisible.value)
const layout = computed(() => flat.value ? null : layoutMap(scene.value, width.value || 320))
const figures = computed(() => layout.value ? scene.value.cells.filter(cell => cell.type === 'actor') : [])

const MARK = { done: '✓', failed: '!', limited: '', stopped: '‖', unknown: '?', waiting: '…', away: '…', idle: '', skipped: '–', working: '' }
// What was just reported that makes a figure start: a result handed to it, or a step of its own failing.
const STARTLED = new Set(['gift', 'oops'])

const duration = ms => ms < 1000 ? Math.round(ms) + ' ms' : (ms / 1000).toFixed(1) + ' s'
const stepName = step => step.label || (te('kind.' + step.kind) ? t('kind.' + step.kind) : step.kind) || t('unreported')
function place(cell) {
  const style = cell.color ? { '--px-main': cell.color.main, '--px-dark': cell.color.dark } : {}
  const spot = layout.value?.spots[cell.key]
  if (spot) Object.assign(style, { left: spot.x - MAP.site / 2 + 'px', top: spot.y - MAP.art + 'px' })
  return style
}
function describe(cell) {
  if (cell.type === 'more') return t('more', { n: cell.count })
  const name = cell.label || (te('kind.' + cell.kind) ? t('kind.' + cell.kind) : cell.kind) || t('unreported')
  const parts = [name, t('state.' + cell.state)]
  if (cell.reused) parts.push(t('reused'))
  if (cell.at) parts.push(t('waitingOn', { step: stepName(cell.at) }))
  if (cell.handoffs?.length) parts.push(t('handoff', { from: cell.handoffs.map(paper => paper.agentId).join(' / ') }))
  if (props.board) parts.push(t('duration') + ': ' + (cell.ms == null ? t('unreported') : duration(cell.ms)))
  return parts.join(' · ')
}
// On the map a place is a button that opens its step; on the board it is a plain tile.
const shell = cell => flat.value ? { 'data-board-node': cell.nodeId, title: describe(cell) }
  : { 'data-stage-node': cell.nodeId, 'aria-pressed': cell.nodeId === props.selectedId, 'aria-label': describe(cell), title: describe(cell) }
function pick(cell) { if (!flat.value) emit('focus-node', cell.nodeId) }

// --- Walking ------------------------------------------------------------------------------
// Each figure has one path at a time. When the report changes where it is headed, the new
// path starts from where the figure is at that moment, so it never jumps across the map.
const walks = ref({})
const bodies = new Map()
const keep = (key, el) => { if (el) bodies.set(key, el); else bodies.delete(key) }
const still = () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
const headings = computed(() => Object.fromEntries(Object.entries(layout.value?.plans || {}).map(([key, plan]) => [key, plan.mode + ':' + plan.points.join(';')])))
// Figures sent to the same place share a road; each later one sets off a little after the one before.
const lags = computed(() => { const seen = {}; return Object.fromEntries(figures.value.map(cell => { const first = cell.stance === 'working' ? cell.inputs?.[0] : null; if (!first) return [cell.key, 0]; seen[first] = (seen[first] ?? -1) + 1; return [cell.key, seen[first]] })) })
function walk(plan, from, n) {
  const start = plan.points[0]
  // It first walks from where it is now to where its path begins, turning the way the roads do.
  const lead = from && Math.abs(from[0] - start[0]) + Math.abs(from[1] - start[1]) > 2 ? routeBetween(from, start, layout.value.sideways) : [start]
  const points = [...lead, ...plan.points.slice(1)], total = pathLength(points), approach = pathLength(lead)
  const loop = plan.mode === 'loop'
  const turn = loop && total ? approach / total : 1
  return {
    n, loop, mode: plan.mode, points, round: plan.points, d: pathData(points), turn,
    // Where it stands when nothing may move: at its desk, or beside the step it is waiting on.
    rest: loop ? (approach + plan.rest * pathLength(plan.points)) / (total || 1) : 1,
    leadMs: Math.round((loop ? approach : total) / SPEED * 1000), loopMs: Math.round(pathLength(plan.points) / SPEED * 1000 / 0.72),
    gait: loop ? 'loop' : from && total > 2 ? 'go' : null
  }
}
watch(headings, (now, before = {}) => {
  const seen = inView.value && pageVisible.value && !still(), next = {}
  for (const key of Object.keys(now)) {
    const old = walks.value[key], plan = layout.value.plans[key]
    if (old && before[key] === now[key]) { next[key] = old; continue }
    // Its desk was its destination and still is, and the growing map moved the desk: it goes with the desk, which is not a walk.
    if (old && old.mode === 'home' && plan.mode === 'home') { next[key] = walk(plan, null, old.n); continue }
    let from = null
    if (old && seen) {
      const share = parseFloat(bodies.has(key) ? getComputedStyle(bodies.get(key)).offsetDistance : '') / 100
      from = pointAt(old.points, Number.isFinite(share) ? share : old.rest)
    }
    next[key] = walk(plan, from, (old?.n ?? 0) + 1)
  }
  walks.value = next
}, { immediate: true, flush: 'pre' })
// Coming back on screen, a figure on a round sets off from its desk, where it stood while unseen.
watch(playing, on => {
  if (!on || still()) return
  const next = { ...walks.value }
  for (const [key, old] of Object.entries(walks.value)) if (old.loop && layout.value?.plans[key]) next[key] = walk(layout.value.plans[key], pointAt(old.points, old.rest), old.n)
  walks.value = next
}, { flush: 'pre' })
// Once it has walked onto its round, only the round is kept.
function arrived(key, event) {
  const now = walks.value[key]
  if (event.target !== event.currentTarget || !now?.loop || now.turn === 0 || !String(event.animationName).startsWith('px-go')) return
  walks.value = { ...walks.value, [key]: { ...now, points: now.round, d: pathData(now.round), turn: 0, rest: layout.value?.plans[key]?.rest ?? 1 } }
}
function pace(cell) {
  const now = walks.value[cell.key], style = cell.color ? { '--px-main': cell.color.main, '--px-dark': cell.color.dark } : {}
  if (!now) return style
  return { ...style, offsetPath: `path('${now.d}')`, offsetDistance: +(now.rest * 100).toFixed(2) + '%', '--px-turn': +(now.turn * 100).toFixed(2) + '%',
    '--px-lead': now.leadMs + 'ms', '--px-loop': now.loopMs + 'ms', '--px-steps': Math.max(1, Math.ceil(now.leadMs / 200)),
    '--px-lag': (lags.value[cell.key] || 0) * 500 + 'ms' }
}

// --- Reactions ----------------------------------------------------------------------------
// One reaction per report. It is remembered only when the map was on screen as the report
// arrived, so a turn that is opened later, or scrolled back to, stands still.
const marks = ref({})
// The reports a figure or a place answers with a movement of its own.
const ONCE = new Set(['enter', 'gift', 'oops', 'failed', 'done'])
const reaction = cell => marks.value[cell.key] ? 'just-' + marks.value[cell.key].kind : null
const facts = computed(() => Object.fromEntries(scene.value.cells.filter(cell => cell.type !== 'more')
  .map(cell => [cell.key, [cell.stance, cell.handoffs?.length ?? 0, cell.trouble ?? 0]])))
watch(facts, (now, before) => {
  const seen = inView.value && pageVisible.value, next = {}
  let changed = false
  for (const [key, [stance, papers, trouble]] of Object.entries(now)) {
    const old = before[key], last = marks.value[key]
    const kind = !old ? (papers ? 'gift' : 'enter') : trouble > old[2] ? 'oops' : papers > old[1] ? 'gift' : stance !== old[0] ? stance : null
    if (ONCE.has(kind)) changed = true
    if (ONCE.has(kind) && seen) next[key] = { kind, n: (last?.n ?? 0) + 1 }
    else if (!ONCE.has(kind) && last) next[key] = last
  }
  if (changed || Object.keys(next).length !== Object.keys(marks.value).length) marks.value = next
})

let observer, sizer
const visibilityChanged = () => { pageVisible.value = !document.hidden }
onMounted(async () => {
  visibilityChanged(); document.addEventListener('visibilitychange', visibilityChanged)
  if (typeof IntersectionObserver !== 'undefined') { observer = new IntersectionObserver(entries => { inView.value = !!entries.at(-1)?.isIntersecting }); observer.observe(host.value) } else inView.value = true
  width.value = host.value.clientWidth
  if (typeof ResizeObserver !== 'undefined') { sizer = new ResizeObserver(() => { if (host.value?.clientWidth) width.value = host.value.clientWidth }); sizer.observe(host.value) }
  // Places glide to a new spot when the map grows, but not on the first measurement.
  await nextTick(); settled.value = true
})
onUnmounted(() => { observer?.disconnect(); sizer?.disconnect(); bodies.clear(); if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', visibilityChanged) })
</script>

<style scoped>
.pixel-stage{--px-ok:#3ecf8e;--px-bad:#ff5d5d;--px-warn:#f5a524;--px-live:#5b8cff;--px-off:#7b8087;min-width:0;margin:8px 0 2px;border:1px solid var(--wb-line,#303034);border-radius:8px;overflow:hidden;
  background-color:#15161a;background-image:linear-gradient(rgba(255,255,255,.035) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,.035) 1px,transparent 1px);background-size:12px 12px}
.room{position:relative;isolation:isolate;min-width:0}.sites{margin:0;padding:0;list-style:none;min-width:0}

/* Roads: one per reported dependency. A road whose receiving step is under way carries moving dashes. */
.roads{position:absolute;left:0;top:0;overflow:visible;pointer-events:none}
.bed{fill:none;stroke:#23252c;stroke-width:8;stroke-linejoin:miter}.dash{fill:none;stroke:#3b3e48;stroke-width:2;stroke-dasharray:4 6}
.road.live .dash{stroke:var(--px-main,var(--px-live))}

/* A place: its drawing stands on the ground line, its name tag hangs below it. */
.map .cell{position:absolute;width:84px;min-width:0}.map.settled .cell{transition:left .3s steps(6,end),top .3s steps(6,end)}
.map.settled .walker:not(.go):not(.loop){transition:offset-path .3s steps(6,end)}
.cell-btn{display:block;box-sizing:border-box;width:100%;height:auto;min-width:44px;min-height:44px;margin:0;padding:0 0 2px;border:1px solid transparent;border-radius:6px;background:transparent;color:#e6e6e6;white-space:normal;overflow:visible}
button.cell-btn:hover{background:rgba(255,255,255,.05);border-color:#3a3c44;color:#fff}.cell-btn:focus-visible{outline:2px solid #eee;outline-offset:2px}.cell.selected .cell-btn{background:rgba(255,255,255,.07);border-color:var(--px-main,#8a8f98)}
.cell-btn :deep(>span){display:block;width:100%;min-width:0}.cell-body{display:block;width:100%;min-width:0}
.art{position:relative;display:block;height:36px}
.plot{position:absolute;left:50%;bottom:-4px;width:46px;height:8px;margin-left:-23px;box-sizing:border-box;border:2px solid #2c2e36;background:#1c1d23}.actor .plot{width:58px;margin-left:-29px}
.desk{position:absolute;z-index:3;left:50%;bottom:0;width:44px;height:7px;margin-left:-22px;display:flex;gap:2px;align-items:flex-start;padding-left:3px;box-sizing:border-box;background:#6b4a2e;border-top:3px solid #9a7048}
.paper{width:6px;height:3px;margin-top:-3px;box-shadow:0 0 0 1px #16171a}
.monitor{position:absolute;z-index:3;left:50%;bottom:10px;width:12px;height:10px;margin-left:9px;box-sizing:border-box;border:2px solid #8a8f98;background:#101114}.monitor::after{content:"";position:absolute;left:2px;right:2px;bottom:-4px;height:2px;background:#8a8f98}
.monitor i{display:block;width:100%;height:100%;background:#2a2c33}.is-working .monitor i{background:var(--px-main,#9aa0a6)}.is-done .monitor i{background:var(--px-ok)}.is-failed .monitor i{background:var(--px-bad)}.is-limited .monitor i,.is-waiting .monitor i{background:var(--px-warn)}
.prop-icon{position:absolute;left:50%;bottom:0;width:32px;height:32px;margin-left:-16px;image-rendering:pixelated}.prop-body{fill:var(--px-main,#9aa0a6)}.led{fill:var(--px-off)}
.badge{position:absolute;z-index:1;left:50%;bottom:24px;min-width:10px;margin-left:15px;font:700 10px/10px var(--wb-mono,monospace);text-align:center;color:var(--px-off)}
.is-done .badge{color:var(--px-ok)}.is-failed .badge{color:var(--px-bad)}.is-waiting .badge{color:var(--px-warn)}
.is-working .led,.is-waiting .led{fill:var(--px-live)}.is-done .led{fill:var(--px-ok)}.is-failed .led{fill:var(--px-bad)}.is-limited .led{fill:var(--px-warn)}
.is-stopped .prop-icon,.is-unknown .prop-icon,.is-skipped .prop-icon{filter:grayscale(1) brightness(.8)}.is-failed .prop-body{fill:var(--px-bad)}
.fold{position:absolute;left:50%;bottom:0;width:32px;height:26px;margin-left:-16px;display:grid;place-items:center;border:2px dashed #4a4d57;border-radius:2px;color:#cfd1d6;font:700 11px/1 var(--wb-mono,monospace);box-sizing:border-box}
.tag{display:block;margin:6px 2px 0;padding:1px 3px;border:1px solid #2a2c33;border-radius:3px;background:#0e0f12;min-width:0}
.name,.sub{display:block;max-width:100%;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;text-align:center;font:11px/13px var(--wb-mono,monospace)}.name{color:#f0f0f0}.actor .name{color:var(--px-main,#f0f0f0);font-weight:700}.sub{color:#b4b7be;font-size:10px;line-height:12px}
.is-working .state{color:#e6e6e6}.is-failed .state{color:var(--px-bad)}.is-done .state{color:#b4b7be}

/* A figure on the map. Its feet follow its path; its desk is drawn in front of it. */
.walker{position:absolute;left:0;top:0;z-index:2;width:24px;height:32px;offset-rotate:0deg;offset-anchor:50% 100%;cursor:pointer}
.seat{position:absolute;z-index:1;left:50%;bottom:0;width:24px;height:32px;margin-left:-12px}
.react{position:relative;display:block;width:24px;height:32px}
.bubble{position:absolute;z-index:3;left:50%;bottom:100%;box-sizing:border-box;min-width:12px;height:12px;margin-bottom:1px;padding:0 3px;transform:translateX(-50%);border:2px solid #2a2c33;border-radius:2px;background:#f4f4f4;color:#16171a;font:700 10px/8px var(--wb-mono,monospace);text-align:center;white-space:nowrap}
.bubble.mark{border-color:transparent;background:transparent;color:var(--px-off);line-height:10px}
.bubble.dots{display:flex;gap:2px;align-items:center;justify-content:center}.bubble.dots i{width:2px;height:2px;background:#16171a;opacity:.25}
.is-done .bubble.mark{color:var(--px-ok)}.is-failed .bubble.mark{color:var(--px-bad)}.is-waiting .bubble.mark{color:var(--px-warn)}.is-away .bubble.mark{color:var(--px-live)}
/* The start a figure gives is shown once and then gone; without motion it is not shown at all. */
.bubble.flash{z-index:4;opacity:0;color:var(--px-bad)}
.sweat{position:absolute;z-index:3;right:-2px;top:4px;width:2px;height:2px;background:#7cc4ff;box-shadow:0 2px 0 1px #7cc4ff}
.is-unknown :deep(.head){transform:translateX(1px)}

.stage-empty,.stage-note{margin:0;padding:6px 8px 8px;color:#b4b7be;font-size:12px;line-height:1.5;overflow-wrap:anywhere}
/* One line per figure that is working: its name, then the end of what the model last wrote. */
.dialog{margin:6px;padding:4px 8px;border:2px solid #e6e6e6;border-radius:2px;background:#0c0d10;box-shadow:inset 0 0 0 2px #0c0d10,inset 0 0 0 3px #4a4d57;min-width:0}
.dialog-line{display:flex;gap:8px;align-items:baseline;margin:0;min-width:0;font:11px/17px var(--wb-mono,monospace);color:#e8e8e8}.dialog-line b{flex:none;max-width:40%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--px-main)}
.said{display:flex;flex:1 1 0;justify-content:flex-end;min-width:0;overflow:hidden;white-space:nowrap}.said>span{flex:none;margin-right:auto}

/* The pinned board: one compact row, each figure seated at its desk with its name beside it. */
.pixel-stage.lane{margin:0;border:0;border-radius:0;background:none}
.lane .sites{display:flex;align-items:center;gap:12px;overflow-x:auto;scrollbar-width:none}.lane .sites::-webkit-scrollbar{display:none}
.lane .cell{flex:0 1 auto;min-width:44px}.lane .cell.prop,.lane .cell.more{min-width:20px}.lane .cell-btn{min-width:0;min-height:0;padding:0;border:0;cursor:default}.lane .cell-body{display:flex;align-items:center;gap:6px;min-width:0}
.lane .art{flex:none;width:44px;height:32px}.lane .prop .art,.lane .more .art{width:20px;height:20px}.lane .plot,.lane .badge{display:none}
.lane .prop-icon{left:0;bottom:2px;width:16px;height:16px;margin-left:2px}.lane .fold{left:0;bottom:1px;width:20px;height:18px;margin-left:0;font-size:9px}
.lane .tag{flex:0 1 auto;margin:0;padding:0;border:0;background:none;max-width:104px;overflow:hidden}.lane .name,.lane .sub{text-align:left}
@container(max-width:520px){.lane .sites{gap:6px}}
/* The board's opened panel: the same figures as a grid of desks, with what each one reported. */
.pixel-stage.board{margin:0}
.board .sites{display:grid;grid-template-columns:repeat(auto-fill,minmax(92px,1fr));gap:10px 4px;padding:16px 6px 6px}
.board .cell{min-width:0}.board .cell-btn{min-height:0;cursor:default}.board .tag{border-color:transparent;background:transparent}.board .sub{white-space:normal;overflow-wrap:anywhere}

/* A reader pointing at a figure makes it look up; pressing it gives a small hop. */
.walker:hover :deep(.head),button.cell-btn:hover :deep(.head){transform:translateY(-1px)}
.walker:active .react{transform:translateY(-3px)}

/* Continuous movement belongs to a step that is reported as under way, on a map that is on screen. */
.pixel-stage.playing .walker.is-working.loop{animation:px-go var(--px-lead) linear var(--px-lag,0ms) 1 both,px-round var(--px-loop) linear calc(var(--px-lead) + var(--px-lag,0ms)) infinite alternate}
.pixel-stage.playing .walker.is-working.loop :deep(.leg-l){animation:px-step .2s steps(2,jump-none) infinite}
.pixel-stage.playing .walker.is-working.loop :deep(.leg-r){animation:px-step .2s steps(2,jump-none) -.1s infinite}
.pixel-stage.playing .is-working :deep(.arm-l){animation:px-type .32s steps(2,jump-none) infinite}
.pixel-stage.playing .is-working :deep(.arm-r){animation:px-type .32s steps(2,jump-none) -.16s infinite}
.pixel-stage.playing .is-working :deep(.head){animation:px-nod 1.6s step-end infinite}
.pixel-stage.playing .is-working :deep(.eyes){animation:px-blink 2.4s step-end infinite}
.pixel-stage.playing .is-working .monitor i{animation:px-led .7s steps(2,jump-none) infinite}
.pixel-stage.playing .is-working .bubble.dots i{animation:px-dot 1.2s step-end infinite}
.pixel-stage.playing .is-working .led{animation:px-led .9s steps(2,jump-none) infinite}
.pixel-stage.playing .is-waiting .led{animation:px-led .9s steps(2,jump-none) infinite}
.pixel-stage.playing .is-waiting :deep(.leg-r){animation:px-tap .9s step-end infinite}
.pixel-stage.playing .is-waiting :deep(.eyes){animation:px-blink 2.4s step-end infinite}
.pixel-stage.playing .is-away :deep(.leg-r){animation:px-tap .9s step-end infinite}
.pixel-stage.playing .is-away :deep(.eyes){animation:px-blink 3s step-end infinite}
.pixel-stage.playing .road.live .dash{animation:px-flow .8s steps(5,end) infinite}
.pixel-stage.playing .road.live.back .dash{animation-direction:reverse}
.pixel-stage.playing .bubble.dots i:nth-child(2){animation-delay:.4s}.pixel-stage.playing .bubble.dots i:nth-child(3){animation-delay:.8s}
/* One movement for one report: walk to where it is now headed, start, hop, appear. */
.walker.go{animation:px-go var(--px-lead) linear 1 both}
.walker.go :deep(.leg-l){animation:px-step .2s steps(2,jump-none);animation-iteration-count:var(--px-steps)}
.walker.go :deep(.leg-r){animation:px-step .2s steps(2,jump-none) -.1s;animation-iteration-count:var(--px-steps)}
.just-oops .react,.just-failed .react{animation:px-jolt .5s steps(5,end) 1}
.just-done .react{animation:px-hop .4s steps(4,end) 1}
.just-gift .react{animation:px-hop .4s steps(4,end) 1}
.just-enter .art{animation:px-pop .3s steps(3,end) 1}
.just-gift .bubble.flash,.just-oops .bubble.flash{animation:px-flash 1.6s step-end 1}
@keyframes px-go{from{offset-distance:0%}to{offset-distance:var(--px-turn)}}
@keyframes px-round{0%,6%{offset-distance:var(--px-turn)}78%,100%{offset-distance:100%}}
@keyframes px-flow{from{stroke-dashoffset:10}to{stroke-dashoffset:0}}
@keyframes px-type{from{transform:translateY(0)}to{transform:translateY(-1px)}}
@keyframes px-nod{0%,74%{transform:translateY(0)}75%,100%{transform:translateY(1px)}}
@keyframes px-blink{0%,92%{opacity:1}93%,100%{opacity:0}}
@keyframes px-tap{0%,59%{transform:translateY(0)}60%,100%{transform:translateY(-1px)}}
@keyframes px-led{from{opacity:1}to{opacity:.35}}
@keyframes px-dot{0%,33%{opacity:1}34%,100%{opacity:.25}}
@keyframes px-step{from{transform:translateY(0)}to{transform:translateY(-1px)}}
@keyframes px-hop{0%,100%{transform:translateY(0)}40%{transform:translateY(-5px)}}
@keyframes px-jolt{0%,100%{transform:translate(0,0)}20%{transform:translate(-3px,-5px)}50%{transform:translate(-4px,0)}}
@keyframes px-pop{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:translateY(0)}}
@keyframes px-flash{0%,84%{opacity:1}85%,100%{opacity:0}}
@media(prefers-reduced-motion:reduce){.pixel-stage :deep(*),.pixel-stage *{animation:none!important;transition:none!important}}
</style>

<template>
  <section ref="root" class="execution-board" :class="{ open: expanded }" :aria-label="t('board')">
    <div class="board-bar">
      <div class="board-title"><span class="material-symbols-outlined" aria-hidden="true">account_tree</span><h3>{{ t('board') }}</h3><ElTag effect="plain" :disable-transitions="true" role="status">{{ t('state.' + view.summary.state) }}</ElTag></div>
      <AgentPixelStage v-if="view.nodes.length" class="board-lane" lane :view="view" />
      <p v-else class="board-idle">{{ t('emptyNodes') }}</p>
      <p v-if="view.summary.cancel.unconfirmed" class="board-note">{{ t('cancelPending') }}</p>
      <dl v-if="tally.length" class="board-tally"><div v-for="item in tally" :key="item.state" :class="{ bad: item.state === 'failed' }"><dd>{{ item.n }}</dd><dt>{{ t('state.' + item.state) }}</dt></div></dl>
      <ElButton ref="boardToggle" class="board-toggle" :aria-expanded="expanded" :aria-controls="detailsId" :aria-label="t(expanded ? 'hideBoard' : 'showBoard')" :title="t(expanded ? 'hideBoard' : 'showBoard')" @click="toggle"><span v-if="outputs.length" class="file-count"><span class="material-symbols-outlined" aria-hidden="true">draft</span>{{ outputs.length }}</span><span class="material-symbols-outlined" aria-hidden="true">{{ expanded ? 'expand_less' : 'expand_more' }}</span></ElButton>
    </div>
    <div :id="detailsId" v-show="expanded" class="board-panel" :aria-hidden="!expanded">
      <div class="bust-cell"><div ref="stageHost" class="bust" aria-hidden="true"></div><span v-if="unavailable" class="no-stage">{{ t('noWebgl') }}</span><span class="bust-caption" aria-hidden="true">NEXUS AI</span></div>
      <div class="stage-cards"><h4>{{ t('pipeline') }} <small>{{ t('recorded', { n: view.summary.recordedNodes }) }}</small></h4><p v-if="!view.nodes.length" class="empty">{{ t('emptyNodes') }}</p>
        <AgentPixelStage v-else class="desks" board :view="view" />
        <span v-if="modelLabel" class="model-label">{{ t('requestModel') }}: {{ modelLabel }}</span>
      </div>
      <div class="artifacts"><h4>{{ t('artifacts') }} <small>{{ outputs.length }}</small></h4><p v-if="!outputs.length" class="empty">{{ t('emptyArtifacts') }}</p>
        <ElButton v-for="item in outputs" :key="item.evidenceKey || artifactKey(item)" class="file-item" :disabled="!canPreview(item)" :aria-label="t('preview') + ' · ' + (item.name || item.id || t('unreported'))" @click="emit('preview', item)"><span aria-hidden="true" class="material-symbols-outlined">{{ item.kind === 'graph' ? 'account_tree' : item.kind === 'patch' ? 'difference' : 'draft' }}</span><span>{{ item.name || item.id || t('unreported') }}</span><small>{{ size(item.sizeBytes) }}</small></ElButton>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, getCurrentInstance, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElButton, ElTag } from 'element-plus'
import { useI18n } from 'vue-i18n'
import AgentPixelStage from './AgentPixelStage.vue'
import { buildRunPresentation } from './runPresentation.js'
import { processMessages } from './processMessages.js'

// The board is a slim bar that stays above the conversation: who is at work and in what
// state, in one row. Its details open as a panel over the conversation, so reading the
// answer never loses the bar and the bar never pushes the answer down.
const props = defineProps({ run: { type: Object, default: null }, modelLabel: String, artifacts: { type: Array, default: undefined } })
const emit = defineEmits(['preview'])
const { t } = useI18n({ useScope: 'local', messages: processMessages })
const detailsId = 'execution-board-' + getCurrentInstance().uid
const expanded = ref(false), root = ref(null), stageHost = ref(null), boardToggle = ref(null), unavailable = ref(false), inView = ref(false), pageVisible = ref(true)
const view = computed(() => buildRunPresentation(props.run?.trace || [], { ...props.run, live: props.run?.status === 'streaming' }))
const outputs = computed(() => props.artifacts ?? view.value.artifacts)
// How many recorded nodes are under way, finished and failed, counted from their reported states.
// A count of none is left out, so the bar never carries the word for a state nothing is in.
const UNDER_WAY = new Set(['running', 'waiting_tool', 'waiting_approval'])
const tally = computed(() => [['running', node => UNDER_WAY.has(node.state)], ['succeeded', node => node.state === 'succeeded'], ['failed', node => node.state === 'failed']]
  .map(([state, test]) => ({ state, n: view.value.nodes.filter(test).length })).filter(item => item.n > 0))
const size = bytes => Number.isFinite(bytes) && bytes >= 0 ? (bytes < 1024 ? bytes + ' B' : (bytes / 1024).toFixed(1) + ' KB') : t('unreported')
const artifactKey = item => JSON.stringify([item.runId ?? props.run?.runId ?? null, item.attemptId ?? item.traceId ?? null, item.id])
const canPreview = item => item.id != null && outputs.value.filter(other => other.id === item.id).length === 1
let dispose, observer, mounted = false, loading = false
const shouldRender = () => expanded.value && inView.value && pageVisible.value
const visibilityChanged = () => { pageVisible.value = !document.hidden }
function toggle() { expanded.value = !expanded.value }
async function close() { if (!expanded.value) return; expanded.value = false; await nextTick(); (boardToggle.value?.$el || boardToggle.value)?.focus({ preventScroll: true }) }
// Escape puts the panel away wherever the focus is, and hands the focus back to the control that opened it.
function onEscape(event) { if (event.key !== 'Escape' || !expanded.value) return; event.preventDefault(); close() }
// The panel lies over the conversation, so a press anywhere else puts it away.
function pressedOutside(event) { if (expanded.value && root.value && !root.value.contains(event.target)) expanded.value = false }
// The 3D scene is loaded the first time the panel is opened, not with the bar.
async function mountStage() {
  if (dispose || loading || !mounted || !stageHost.value) return
  loading = true
  try {
    const { mountAbyssStage } = await import('./abyssStage.js')
    if (!mounted || !stageHost.value) return
    // Existing 3D scene; on-demand lifecycle prevents hidden/folded/background
    // polling, and reduced motion draws only a static frame until invalidated.
    dispose = mountAbyssStage(stageHost.value, { shouldRender, onDemand: true }); dispose?.refresh?.()
  } catch { if (mounted) unavailable.value = true } finally { loading = false }
}
watch(expanded, async open => { if (open) { await nextTick(); await mountStage() } })
watch([expanded, inView, pageVisible], () => dispose?.refresh?.())
onMounted(() => {
  mounted = true; visibilityChanged(); document.addEventListener('visibilitychange', visibilityChanged); document.addEventListener('pointerdown', pressedOutside); document.addEventListener('keydown', onEscape)
  if (typeof IntersectionObserver !== 'undefined') { observer = new IntersectionObserver(entries => { inView.value = !!entries.at(-1)?.isIntersecting }); observer.observe(stageHost.value) } else inView.value = true
})
onUnmounted(() => { mounted = false; observer?.disconnect(); if (typeof document !== 'undefined') { document.removeEventListener('visibilitychange', visibilityChanged); document.removeEventListener('pointerdown', pressedOutside); document.removeEventListener('keydown', onEscape) } dispose?.() })
</script>

<style scoped>
.execution-board{position:relative;container-type:inline-size;min-width:0;background:var(--wb-panel,#1b1b1b);border:1px solid var(--wb-line,#414141);border-radius:8px;color:#e8e8e8}
.board-bar{display:flex;align-items:center;gap:12px;min-width:0;min-height:40px;padding:0 4px 0 10px}
.board-title{display:flex;align-items:center;gap:6px;flex:none;min-width:0}.board-title h3{margin:0;font-size:12px;font-weight:600;white-space:nowrap}.material-symbols-outlined{font-size:15px}
.execution-board :deep(.el-tag){height:18px;max-width:100%;padding:0 6px;background:#262626;border-color:#555;color:#ddd;font-size:10px;line-height:16px}
.board-lane{flex:1 1 0;min-width:0}
.board-idle,.board-note,.board-tally,.model-label,.empty,.file-item small{margin:0;font-size:10px;line-height:1.6;color:#b0b3ba}
.board-idle{flex:1 1 0;min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.board-note{flex:0 1 auto;min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;color:#e0c070}
.board-tally{flex:none;display:flex;gap:10px;white-space:nowrap}.board-tally div{display:flex;align-items:baseline;gap:3px}.board-tally dd{margin:0;font:600 12px/1 var(--wb-mono,monospace);color:#f0f0f0}.board-tally dt{font-size:10px}.board-tally .bad dd{color:#ff7a7a}
.board-toggle{flex:none;display:inline-flex;align-items:center;justify-content:center;gap:2px;min-width:28px;height:28px;margin:0;padding:0 4px;border-color:transparent;background:transparent;color:#cfd1d6}
.board-toggle:hover,.execution-board.open .board-toggle{background:#26262a;border-color:#4a4a50;color:#fff}.board-toggle :deep(>span){display:inline-flex;align-items:center;gap:2px}
.file-count{display:inline-flex;align-items:center;gap:1px;font:10px/1 var(--wb-mono,monospace)}
.execution-board :deep(.el-button:focus-visible){outline:2px solid #eee;outline-offset:2px}
.board-panel{position:absolute;z-index:5;left:-1px;right:-1px;top:calc(100% + 4px);display:grid;grid-template-columns:minmax(0,1fr);gap:10px;max-height:min(56vh,340px);padding:10px;overflow:auto;scrollbar-width:thin;background:var(--wb-panel,#1b1b1b);border:1px solid var(--wb-line,#414141);border-radius:8px;box-shadow:0 14px 30px rgba(0,0,0,.5);min-width:0}
.bust-cell{position:relative;min-height:100px;background:#101010;border:1px solid #333;border-radius:6px;overflow:hidden;min-width:0}.bust{position:absolute;inset:0}
.bust-caption{position:absolute;left:8px;bottom:6px;font:10px/1.4 var(--wb-mono,monospace);letter-spacing:.08em;color:#ccc;pointer-events:none}.no-stage{position:absolute;inset:0;display:grid;place-items:center;text-align:center;padding:8px;font-size:11px;color:#ccc}
.stage-cards,.artifacts{min-width:0}.desks{max-height:220px;overflow:auto;scrollbar-width:thin}.model-label{display:block;margin-top:6px;overflow-wrap:anywhere}
.execution-board h4{display:flex;align-items:center;gap:6px;margin:0 0 6px;font-size:11px;font-weight:600;color:#eee}.execution-board h4 small{font-size:10px;font-weight:400;color:#b0b3ba}.empty{margin:4px 0}
.artifacts{border-top:1px solid #3a3a3e;padding-top:8px}
.file-item{display:flex;width:100%;height:auto;min-width:44px;min-height:32px;justify-content:flex-start;padding:5px 8px;margin:0 0 6px!important;background:#202020;color:#ddd;border-color:#484848;white-space:normal;text-align:left}
.file-item :deep(>span){display:flex;align-items:center;gap:6px;min-width:0;width:100%;white-space:normal}.file-item :deep(>span)>span:nth-child(2){min-width:0;overflow-wrap:anywhere;font-size:11px}.file-item small{margin-left:auto;flex:none}.file-item .material-symbols-outlined{flex:none}
@container(max-width:520px){.board-tally,.board-title h3{display:none}.board-bar{gap:8px;padding-left:8px}}
@container(min-width:580px){.board-panel{grid-template-columns:minmax(120px,.6fr) minmax(0,1.8fr)}.artifacts{grid-column:1/-1}}
@container(min-width:900px){.board-panel{grid-template-columns:150px minmax(0,1.8fr) minmax(160px,1fr)}.artifacts{grid-column:auto;border-top:0;border-left:1px solid #3a3a3e;padding:0 0 0 10px}}
/* On a touch screen the controls keep a full-size target; the bar grows a little to hold them. */
@media(pointer:coarse){.board-toggle{min-width:44px;min-height:44px;height:44px}.file-item{min-height:44px}}
@media(prefers-reduced-motion:reduce){.execution-board :deep(*){animation:none!important;transition:none!important}}
</style>

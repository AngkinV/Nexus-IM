<template>
  <section ref="processHost" class="thought-ribbon" :aria-label="t('thought')" @keydown.esc="onEscape">
    <header class="ribbon-bar">
      <ElButton ref="ribbonToggle" class="ribbon-toggle" :aria-expanded="open" :aria-controls="detailsId" @click="manualOpen = !open"><span class="material-symbols-outlined chevron" :class="{ open }" aria-hidden="true">chevron_right</span><strong>{{ t('thought') }}</strong><ElTag effect="plain" :disable-transitions="true" role="status">{{ t('state.' + view.summary.state) }}</ElTag></ElButton>
      <div class="ribbon-actions"><ElButton @click="copy" :disabled="!canCopy">{{ t('copy') }}</ElButton><ElButton @click="onlyAnswer">{{ t('conclusion') }}</ElButton></div>
    </header>
    <AgentPixelStage v-if="stageShown" :view="view" :selected-id="open ? selectedId : null" @focus-node="openStep" />
    <dl class="summary-fields"><div><dt>{{ t('task') }}</dt><dd>{{ view.task || t('unreported') }}</dd></div><div><dt>{{ t('scope') }}</dt><dd>{{ evidenceText(view.scope) || t('unreported') }}</dd></div></dl>
    <div class="metrics"><span>{{ t('recorded', { n: view.summary.recordedNodes }) }}</span><span>{{ view.summary.roles ? t('roles', { n: view.summary.roles }) : t('role') + ': ' + t('unreported') }}</span><span>{{ t('elapsed') }}: {{ view.summary.elapsed.ms == null ? t('unreported') : duration(view.summary.elapsed.ms) }}<small v-if="view.summary.elapsed.basis"> · {{ t(view.summary.elapsed.basis === 'wall' ? 'wallElapsed' : 'eventElapsed') }}</small></span><span>{{ t('totalTokens') }}: {{ count(view.summary.usage?.totalTokens) }}</span></div>
    <p v-if="view.summary.cancel.unconfirmed" class="diagnostic">{{ t('cancelPending') }}</p><p v-else-if="view.summary.state === 'interrupted'" class="diagnostic">{{ t('interruptedNote') }}</p>
    <div :id="detailsId" v-show="open" class="ribbon-details" :aria-hidden="!open">
      <div class="trace-layout">
        <section v-if="open" class="graph-panel"><h4>{{ t('graph') }}</h4><AgentRunGraph v-if="view.nodes.length" :nodes="view.nodes" :edges="view.edges" :selected-id="selectedId" :details-id="nodeDetailsId" :active="open" @focus-node="focusStep" /><p v-else>{{ t('emptyNodes') }}</p></section>
        <section :id="nodeDetailsId" ref="nodeDetails" class="node-detail" tabindex="-1" :aria-labelledby="nodeTitleId">
          <h4 :id="nodeTitleId">{{ t('detail') }}</h4>
          <template v-if="open && selected">
            <div class="detail-identity"><AgentPixelSprite :agent-id="selected.agentId" :node-id="selected.id" :state="selected.state" /><div><strong>{{ title(selected) }}</strong><ElTag effect="plain" :disable-transitions="true">{{ t('state.' + selected.state) }}</ElTag></div></div>
            <p v-if="!selected.identityComplete" class="explanation">{{ t('identityNote') }}</p><p v-if="selected.stateBasis !== 'reported'" class="explanation">{{ t('stateBasis') }} {{ t('lastState') }}: {{ selected.lastReportedState ? t('state.' + selected.lastReportedState) : t('unreported') }}</p>
            <dl class="node-facts"><div v-for="fact in nodeFacts" :key="fact.key"><dt>{{ t(fact.key) }}</dt><dd>{{ fact.value ?? t('unreported') }}</dd></div></dl>
            <p class="explanation">{{ t('elapsedNote') }}</p><p class="explanation">{{ t('usageNote') }}</p>
            <ElCollapse v-model="detailPanels" class="evidence-panels">
              <ElCollapseItem v-if="selected.kind === 'tool' || selected.args != null" name="args" :title="t('args')"><pre>{{ evidenceText(selected.args) || t('unreported') }}</pre></ElCollapseItem>
              <ElCollapseItem name="reasoning" :title="t('reasoning')"><p class="explanation">{{ t('reasoningNote') }}</p><blockquote>{{ selected.reasoning || t('unreported') }}</blockquote></ElCollapseItem>
              <ElCollapseItem name="output" :title="t('output')"><pre>{{ selected.output || t('unreported') }}</pre></ElCollapseItem>
              <ElCollapseItem name="parents" :title="t('parents')"><pre>{{ selected.reportedParentIds.length ? selected.reportedParentIds.join(' / ') : t('unreported') }}</pre><p v-if="selected.unresolvedParentIds.length">{{ t('unresolvedParents') }}: {{ selected.unresolvedParentIds.join(' / ') }}</p></ElCollapseItem>
              <ElCollapseItem name="sources" :title="t('sources')"><p class="explanation">{{ t('evidenceNote') }}</p><p v-if="!selected.sources.length">{{ t('unreported') }}</p><section v-for="item in selected.sources" :key="item.id" class="source-item"><strong>{{ item.title || item.id || t('unreported') }}</strong><small>{{ item.id }} · {{ item.kind || t('unreported') }}</small><p v-if="item.excerpt">{{ item.excerpt }}</p><ElLink v-if="webUrl(item.url)" :href="webUrl(item.url)" target="_blank" rel="noopener noreferrer">{{ item.url }}</ElLink></section><pre v-if="selected.sourceIds.length">{{ selected.sourceIds.join(' / ') }}</pre></ElCollapseItem>
              <ElCollapseItem name="artifacts" :title="t('artifacts')"><p v-if="!selected.artifacts.length">{{ t('unreported') }}</p><ElButton v-for="item in selected.artifacts" :key="item.id" class="artifact-action" :disabled="!canPreview(item)" @click="emit('preview', item)">{{ t('artifactRelation.' + item.relation) }} · {{ t('preview') }} · {{ item.name || item.id || t('unreported') }}</ElButton></ElCollapseItem>
              <ElCollapseItem name="error" :title="t('error')"><pre>{{ evidenceText(selected.detail) || t('unreported') }}</pre></ElCollapseItem>
            </ElCollapse>
          </template><p v-else-if="open">{{ t('unreported') }}</p>
        </section>
      </div>
      <ElCollapse v-if="open" v-model="runPanels" class="evidence-panels run-evidence">
        <ElCollapseItem name="scope" :title="t('scopeDetail')"><pre>{{ evidenceText(view.scope) || t('unreported') }}</pre></ElCollapseItem>
        <ElCollapseItem name="usage" :title="t('usage')"><dl class="node-facts"><div v-for="key in tokenKeys" :key="key"><dt>{{ t(key) }}</dt><dd>{{ count(view.summary.usage?.[key]) }}</dd></div><div><dt>{{ t('reportedCalls') }}</dt><dd>{{ count(view.summary.reportedModelCalls) }}</dd></div></dl><p class="explanation">{{ t('usageNote') }}</p><p>{{ t('calls', { n: view.summary.recordedModelCalls }) }} · {{ t('tools', { n: view.summary.recordedToolCalls }) }}</p></ElCollapseItem>
        <ElCollapseItem name="unassigned" :title="t('unassigned')"><p class="explanation">{{ t('evidenceNote') }}</p><p v-if="!unassignedSources.length && !unassignedArtifacts.length && !view.unattributed.length">{{ t('unreported') }}</p><section v-for="item in unassignedSources" :key="item.evidenceKey" class="source-item"><strong>{{ item.title || item.id }}</strong><p v-if="item.excerpt">{{ item.excerpt }}</p></section><ElButton v-for="item in unassignedArtifacts" :key="item.evidenceKey" class="artifact-action" :disabled="!canPreview(item)" @click="emit('preview', item)">{{ t('preview') }} · {{ item.name || item.id }}</ElButton><section v-for="(part, index) in view.unattributed" :key="index"><small>{{ t(part.kind === 'reasoning' ? 'reasoning' : 'output') }}</small><p v-if="part.kind === 'reasoning'" class="explanation">{{ t('reasoningNote') }}</p><pre>{{ part.text }}</pre></section></ElCollapseItem>
        <ElCollapseItem name="run-error" :title="t('error')"><pre>{{ evidenceText(view.summary.error) || t('unreported') }}</pre></ElCollapseItem>
      </ElCollapse>
    </div>
    <span class="copy-feedback" role="status">{{ copyFeedback }}</span>
  </section>
</template>

<script setup>
import { computed, getCurrentInstance, nextTick, onUnmounted, ref, watch } from 'vue'
import { ElButton, ElTag, ElCollapse, ElCollapseItem, ElLink } from 'element-plus'
import { useI18n } from 'vue-i18n'
import AgentRunGraph from './AgentRunGraph.vue'
import AgentPixelSprite from './AgentPixelSprite.vue'
import AgentPixelStage from './AgentPixelStage.vue'
import { buildRunPresentation, evidenceText, formatPresentationText } from './runPresentation.js'
import { processMessages } from './processMessages.js'

const props = defineProps({ trace: { type: Array, required: true }, run: { type: Object, default: null }, live: Boolean, elapsedMs: { type: Number, default: null }, usage: Object, status: String, runId: String, defaultExpanded: Boolean })
const emit = defineEmits(['preview'])
const { t, te } = useI18n({ useScope: 'local', messages: processMessages })
const instance = getCurrentInstance().uid
const detailsId = 'thought-' + instance, nodeDetailsId = 'thought-node-' + instance, nodeTitleId = 'thought-node-title-' + instance
const manualOpen = ref(null), open = computed(() => manualOpen.value ?? props.defaultExpanded)
// The room stays with the reply until the reader asks for the conclusion only.
const stageShown = computed(() => manualOpen.value !== false)
const options = computed(() => ({ ...props.run, runId: props.runId ?? props.run?.runId, status: props.status ?? props.run?.status, usage: props.usage ?? props.run?.usage, elapsedMs: props.elapsedMs ?? props.run?.elapsedMs, live: props.live || props.run?.status === 'streaming' }))
const view = computed(() => buildRunPresentation(props.trace, options.value))
const selectedId = ref(null), selected = computed(() => view.value.nodes.find(node => node.id === selectedId.value) || null)
const ribbonToggle = ref(null), nodeDetails = ref(null), processHost = ref(null), copyFeedback = ref('')
const detailPanels = ref(['reasoning']), runPanels = ref([]), tokenKeys = ['inputTokens', 'outputTokens', 'totalTokens']
let copyTimer
const title = node => node.reportedTitle || node.model || node.toolName || node.agentName || (te('kind.' + node.kind) ? t('kind.' + node.kind) : node.title || t('unreported'))
const duration = ms => ms < 1000 ? Math.round(ms) + ' ms' : (ms / 1000).toFixed(1) + ' s'
const count = value => value == null ? t('unreported') : value.toLocaleString()
const canCopy = computed(() => !!(view.value.nodes.length || view.value.unattributed.length || view.value.summary.error))
const canPreview = item => item.id != null && view.value.artifacts.filter(other => other.id === item.id).length === 1
const nodeFacts = computed(() => selected.value ? [
  { key: 'role', value: selected.value.role || selected.value.agentId }, { key: 'phase', value: te('kind.' + selected.value.kind) ? t('kind.' + selected.value.kind) : selected.value.kind },
  { key: 'run', value: selected.value.runId }, { key: 'attempt', value: selected.value.attemptId }, { key: 'node', value: selected.value.nodeId }, { key: 'call', value: selected.value.callId },
  { key: 'model', value: selected.value.model }, { key: 'provider', value: selected.value.provider }, { key: 'server', value: selected.value.serverId },
  { key: 'duration', value: selected.value.ms == null ? null : duration(selected.value.ms) }, ...tokenKeys.map(key => ({ key, value: selected.value.usage?.[key] == null ? null : count(selected.value.usage[key]) }))
] : [])
const unassignedSources = computed(() => { const linked = new Set(view.value.nodes.flatMap(node => node.sources.map(item => JSON.stringify([node.attemptKey, item.id])))); return view.value.sources.filter(item => !linked.has(item.evidenceKey)) })
const unassignedArtifacts = computed(() => { const linked = new Set(view.value.nodes.flatMap(node => node.artifacts.map(item => JSON.stringify([node.attemptKey, item.id])))); return view.value.artifacts.filter(item => !linked.has(item.evidenceKey)) })
function toggleElement() { return ribbonToggle.value?.$el || ribbonToggle.value }
watch(open, (shown, previous) => {
  if (!shown && previous && typeof document !== 'undefined' && processHost.value?.contains(document.activeElement)) nextTick(() => toggleElement()?.focus({ preventScroll: true }))
})
async function onlyAnswer() { manualOpen.value = false; await nextTick(); toggleElement()?.focus({ preventScroll: true }) }
function onEscape(event) { if (!open.value) return; event.preventDefault(); event.stopPropagation(); onlyAnswer() }
async function focusStep(id) { if (!view.value.nodes.some(node => node.id === id)) return; selectedId.value = id; await nextTick(); nodeDetails.value?.focus({ preventScroll: true }); nodeDetails.value?.scrollIntoView({ block: 'nearest', behavior: 'auto' }) }
watch(() => view.value.nodes.map(node => node.id), ids => {
  const focused = typeof document === 'undefined' ? null : document.activeElement
  const removedFocusedNode = focused?.closest?.('[data-exec-node]')?.dataset.execNode
  const selectedRemoved = selectedId.value != null && !ids.includes(selectedId.value)
  const restore = (selectedRemoved && nodeDetails.value?.contains(focused)) || (removedFocusedNode && !ids.includes(removedFocusedNode))
  if (!ids.includes(selectedId.value)) selectedId.value = ids.at(-1) ?? null
  if (restore) nextTick(() => toggleElement()?.focus({ preventScroll: true }))
}, { immediate: true })
async function openStep(id) { manualOpen.value = true; await nextTick(); focusStep(id) }
function webUrl(value) { if (typeof value !== 'string') return null; try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) ? url.href : null } catch { return null } }
async function copy() {
  try { await navigator.clipboard.writeText(formatPresentationText(view.value, { reasoning: t('reasoningNote') })); copyFeedback.value = t('copied') }
  catch { copyFeedback.value = t('copyFailed') }
  clearTimeout(copyTimer); copyTimer = setTimeout(() => { copyFeedback.value = '' }, 2200)
}
onUnmounted(() => clearTimeout(copyTimer))
</script>

<style scoped>
.thought-ribbon{--wb-pixel-dark:#161616;--wb-pixel-light:#ececec;--wb-pixel-mid:#999;container-type:inline-size;min-width:0;background:var(--wb-panel,#1b1b1b);border:1px solid var(--wb-line,#414141);border-radius:10px;padding:10px;color:#e8e8e8;line-height:1.6;font-size:13px}
.ribbon-bar,.ribbon-toggle,.ribbon-actions,.metrics,.detail-identity{display:flex;align-items:center;gap:8px;min-width:0}.ribbon-bar{flex-wrap:wrap}.ribbon-toggle{height:auto;min-height:44px;min-width:44px;max-width:100%;flex:1 1 190px;justify-content:flex-start;white-space:normal;text-align:left;padding:8px;color:#eee;background:transparent;border-color:transparent}.ribbon-toggle :deep(>span){display:flex;align-items:center;flex-wrap:wrap;gap:8px;min-width:0}.ribbon-toggle strong{font-size:13px;font-weight:600}.ribbon-toggle:hover{background:#2b2b2b;color:#fff;border-color:#555}.ribbon-actions{flex-wrap:wrap;gap:8px}.ribbon-actions :deep(.el-button){height:auto;min-height:44px;min-width:44px;margin:0;padding:8px;color:#ccc;background:transparent;border-color:#484848;font-size:12px;white-space:normal}.thought-ribbon :deep(.el-button:focus-visible),.node-detail:focus-visible{outline:2px solid #eee;outline-offset:3px}.material-symbols-outlined{font-size:17px}.chevron{transition:transform .16s}.chevron.open{transform:rotate(90deg)}
.thought-ribbon :deep(.el-tag){max-width:100%;height:auto;min-height:24px;background:#272727;color:#eee;border-color:#555;white-space:normal;font-size:11px}.summary-fields,.node-facts{padding:0;margin:10px 0;min-width:0}.summary-fields>div,.node-facts>div{display:grid;grid-template-columns:90px minmax(0,1fr);gap:8px;min-width:0}.summary-fields dt,.node-facts dt{color:#bdbdbd;font-size:12px}.summary-fields dd,.node-facts dd{margin:0;min-width:0;overflow-wrap:anywhere}.summary-fields dd{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;white-space:pre-wrap}.metrics{flex-wrap:wrap;font:12px/1.65 var(--wb-mono,monospace);color:#c4c4c4;gap:5px 14px}.metrics small{font-size:11px}.diagnostic{margin:8px 0 0;padding:8px;border-left:2px solid #bbb;background:#242424;color:#ddd;overflow-wrap:anywhere}.ribbon-details{border-top:1px solid var(--wb-line,#444);margin-top:10px;padding-top:14px;min-width:0}.trace-layout{display:grid;grid-template-columns:minmax(0,1fr);gap:16px;min-width:0}.graph-panel,.node-detail{min-width:0}.node-detail{background:var(--wb-card,#171717);border:1px solid var(--wb-line,#444);border-radius:8px;padding:12px;scroll-margin:12px}.thought-ribbon h4{margin:0 0 10px;font-size:13px;font-weight:600;color:#ededed}.detail-identity>div{display:grid;gap:5px;min-width:0}.detail-identity strong{overflow-wrap:anywhere;font-size:14px}.node-facts>div{padding:3px 0}.node-facts{font:12px/1.6 var(--wb-mono,monospace)}.explanation{font-size:12px;line-height:1.6;color:#bcbcbc;margin:8px 0;overflow-wrap:anywhere}
.evidence-panels{--el-collapse-border-color:var(--wb-line,#444);--el-collapse-header-bg-color:transparent;--el-collapse-header-text-color:#eee;--el-collapse-content-bg-color:transparent;--el-collapse-content-text-color:#ddd;--el-collapse-header-height:48px;margin-top:12px;min-width:0}.evidence-panels :deep(.el-collapse-item__header){min-height:44px;height:auto;padding:10px 2px;white-space:normal;font-size:13px;line-height:1.6;overflow-wrap:anywhere}.evidence-panels :deep(.el-collapse-item__header:focus-visible){outline:2px solid #eee;outline-offset:-2px}.evidence-panels :deep(.el-collapse-item__content){padding:8px 0 14px;font-size:13px;line-height:1.65}.thought-ribbon pre,.thought-ribbon blockquote,.source-item p{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;max-width:100%;margin:6px 0}.thought-ribbon pre{font:12px/1.65 var(--wb-mono,monospace)}.thought-ribbon blockquote{padding-left:10px;border-left:2px solid #777}.source-item{display:grid;gap:4px;padding:8px 0;border-bottom:1px solid #414141;min-width:0}.source-item small{color:#bbb;overflow-wrap:anywhere}.source-item :deep(.el-link){min-width:44px;min-height:44px;justify-content:start;color:#ddd;overflow-wrap:anywhere;word-break:break-word}.artifact-action{display:flex;width:100%;height:auto;min-height:44px;min-width:44px;margin:8px 0!important;padding:8px;text-align:left;white-space:normal;background:#252525;color:#eee;border-color:#555}.artifact-action :deep(>span){white-space:normal;overflow-wrap:anywhere;max-width:100%}.copy-feedback{display:block;font-size:12px;color:#ccc;min-width:0}.copy-feedback:empty{display:none}
@container(min-width:680px){.trace-layout{grid-template-columns:minmax(0,1.1fr) minmax(0,1fr);align-items:start}.node-detail{position:relative}}
@media(max-width:600px){.ribbon-bar{align-items:stretch}.ribbon-toggle{flex-basis:100%}.ribbon-actions{width:100%}.ribbon-actions :deep(.el-button){flex:1}.summary-fields>div{grid-template-columns:76px minmax(0,1fr)}.thought-ribbon{padding:8px}.node-facts>div{grid-template-columns:90px minmax(0,1fr)}}
@media(prefers-reduced-motion:reduce){.chevron,.thought-ribbon :deep(*){animation:none!important;transition:none!important;scroll-behavior:auto!important}}
</style>

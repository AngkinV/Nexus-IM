<template>
  <section class="thought-ribbon" :aria-label="t('agent.workbench.thought')">
    <header class="ribbon-bar">
      <button ref="ribbonToggle" type="button" class="ribbon-toggle" :aria-expanded="open" :aria-controls="detailsId" @click="manualOpen = !open">
        <span class="material-symbols-outlined chevron" :class="{ open }" aria-hidden="true">chevron_right</span>
        <span class="material-symbols-outlined" aria-hidden="true">hub</span>
        <strong>{{ t(cooperative ? 'agent.workbench.coThought' : 'agent.workbench.thought') }}</strong>
        <span class="metrics"><span class="ribbon-status"><i :class="{ pulse: live }"></i>{{ t(`agent.workbench.state.${view.summary.status}`) }}</span><span v-if="view.agents.length > 1">{{ t('agent.workbench.roles', { n: view.agents.length }) }}</span><span>{{ t('agent.workbench.steps', { n: view.summary.steps }) }}</span><span v-if="elapsed != null">{{ duration(elapsed) }}</span><span v-if="view.summary.totalTokens != null">{{ view.summary.totalTokens.toLocaleString() }} tok</span></span>
      </button>
      <div class="ribbon-actions"><button type="button" @click="copy" :disabled="!view.summary.calls">{{ t('agent.workbench.copyThought') }}</button><button v-if="open" type="button" @click="onlyAnswer">{{ t('agent.workbench.conclusion') }}</button></div>
    </header>
    <div v-if="open" :id="detailsId" class="ribbon-details">
      <template v-if="view.nodes.length">
        <h4><span aria-hidden="true" class="material-symbols-outlined">account_tree</span>{{ t('agent.workbench.graph') }}<span v-if="view.summary.runningAgents" class="live-label">{{ t('agent.workbench.parallel', { n: view.summary.runningAgents }) }}</span></h4>
        <AgentRunGraph :nodes="view.nodes" :edges="view.edges" @focus-node="focusStep" />
        <ol class="step-list" :aria-label="t('agent.workbench.timeline')">
          <li v-for="node in view.steps" :key="node.id" :ref="el => bindStep(node.id, el)" tabindex="-1" :class="{ active: node.state === 'running' }">
            <span class="step-mark material-symbols-outlined" aria-hidden="true">{{ icons[node.state] || 'circle' }}</span>
            <div class="step-head"><span v-if="node.agentId" class="role">{{ te(`agent.workbench.role.${node.agentId}`) ? t(`agent.workbench.role.${node.agentId}`) : node.agentId }}</span><strong>{{ title(node) }}</strong><small>{{ t(`agent.workbench.state.${node.state}`) }}<template v-if="node.ms != null"> · {{ duration(node.ms) }}</template><template v-if="node.usage?.totalTokens != null"> · {{ node.usage.totalTokens }} tok</template></small></div>
            <p v-if="node.kind === 'context' && node.shortTerm != null">{{ t('agent.trace.context', { shortTerm: node.shortTerm, recalled: node.recalled ?? '—' }) }}</p>
            <p v-if="node.kind === 'knowledge'">{{ node.chunks == null ? t('agent.trace.kbSkipped') : t('agent.trace.kbHits', { n: node.chunks }) }}</p>
            <p v-if="node.kind === 'tools' && node.state === 'skipped'">{{ t('agent.trace.toolsUnavailable') }}</p>
            <p v-if="node.detail">{{ node.detail }}</p>
            <details v-if="node.args && Object.keys(node.args).length" class="args"><summary>{{ t('agent.workbench.args') }}</summary><code>{{ JSON.stringify(node.args, null, 2) }}</code></details>
            <blockquote v-if="node.reasoning" :aria-label="t('agent.workbench.reasoning')">{{ node.reasoning }}</blockquote>
            <div v-if="node.output" class="role-output"><small>{{ t('agent.workbench.analysis') }}</small><p>{{ node.output }}</p></div>
          </li>
        </ol>
      </template>
      <p v-else-if="!view.unattributed.length">{{ live ? t('agent.workbench.waiting') : t('agent.workbench.unreported') }}</p>
      <div v-for="(part, i) in view.unattributed" :key="i" class="role-output"><small>{{ t(part.kind === 'reasoning' ? 'agent.workbench.reasoning' : 'agent.workbench.analysis') }}</small><p class="whitespace-pre-wrap break-words">{{ part.text }}</p></div>
      <p v-if="!live && view.summary.calls && !view.reasoning" class="no-reasoning">{{ t('agent.workbench.unreported') }}</p>
    </div>
    <span class="sr-only" role="status">{{ copyFeedback }}</span>
    <small v-if="copyFeedback" class="copy-feedback">{{ copyFeedback }}</small>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AgentRunGraph from './AgentRunGraph.vue'
import { buildRunView, formatTraceText } from './runView.js'

const props = defineProps({ trace: { type: Array, required: true }, live: Boolean, elapsedMs: { type: Number, default: null }, usage: Object, status: String, runId: String })
const { t, te } = useI18n()
const manualOpen = ref(null)
const open = computed(() => manualOpen.value ?? props.live)
const detailsId = computed(() => props.runId ? `thought-${props.runId}` : undefined)
const view = computed(() => buildRunView(props.trace, props))
const cooperative = computed(() => view.value.agents.length > 1 || view.value.meta.executionMode === 'collaboration')
const copyFeedback = ref('')
const elapsed = ref(null)
const steps = new Map()
let timer, copyTimer, stopClock
const icons = { done: 'check', error: 'error', running: 'radio_button_checked', queued: 'more_horiz', skipped: 'remove', cancelled: 'stop', interrupted: 'pause', limited: 'more_horiz' }
const duration = ms => ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(1)}s`
const title = n => n.model || n.toolName || (te(`agent.workbench.phase.${n.kind}`) ? t(`agent.workbench.phase.${n.kind}`) : n.title)
const bindStep = (id, el) => el ? steps.set(id, el) : steps.delete(id)
function focusStep(id) { const el = steps.get(id); el?.focus({ preventScroll: true }); el?.scrollIntoView({ block: 'nearest' }) }
const ribbonToggle = ref(null)
function onlyAnswer() { manualOpen.value = false; ribbonToggle.value?.focus() }
async function copy() {
  try { await navigator.clipboard.writeText(formatTraceText(props.trace, props)); copyFeedback.value = t('agent.copied') }
  catch { copyFeedback.value = t('common.failed') }
  clearTimeout(copyTimer); copyTimer = setTimeout(() => { copyFeedback.value = '' }, 2200)
}
onMounted(() => {
  // A resumed attempt is a fresh clock: only fold in a completed elapsed when this is
  // still the same run/attempt the value was reported for.
  const clockBase = computed(() => view.value.meta.attemptId ?? props.runId ?? view.value.meta.traceId)
  let lastBase = clockBase.value
  stopClock = watch(() => [props.live, props.elapsedMs, clockBase.value], ([live, ms, base]) => {
    clearInterval(timer)
    const changed = base !== lastBase
    lastBase = base
    elapsed.value = changed ? 0 : ms ?? view.value.summary.elapsedMs
    if (!live) return
    const start = performance.now() - (elapsed.value ?? 0)
    timer = setInterval(() => { elapsed.value = performance.now() - start }, 250)
  }, { immediate: true })
})
onUnmounted(() => { stopClock?.(); clearInterval(timer); clearTimeout(copyTimer) })
</script>

<style scoped>
.thought-ribbon{color:#e4e4e7;background:var(--wb-panel,#1a1a1d);border:1px solid var(--wb-line,#303034);border-radius:10px;padding:5px 8px;min-width:0}.ribbon-bar,.ribbon-toggle,.ribbon-actions,.metrics,.step-head,h4{display:flex;align-items:center}.ribbon-bar{flex-wrap:wrap;gap:0 6px}.ribbon-toggle{flex:1 1 200px;text-align:left;flex-wrap:wrap;gap:5px;min-width:0;padding:6px 2px;line-height:1.45;font-size:12px}.ribbon-toggle strong{font-weight:550}.material-symbols-outlined{font-size:15px}.chevron{transition:transform .18s}.chevron.open{transform:rotate(90deg)}.metrics,.ribbon-actions,.role,.step-head small,.args,h4,.copy-feedback{font-family:var(--wb-mono,ui-monospace,monospace)}.metrics{flex-wrap:wrap;color:#aaaab2;font-size:10.5px;gap:2px 7px}.metrics>span+span::before{content:'·';margin-right:6px;color:#73737a}.ribbon-status i{display:inline-block;width:5px;height:5px;border-radius:50%;background:#d4d4d8;vertical-align:middle;margin:0 4px}.ribbon-actions button{background:transparent;color:#c3c3c9;font-size:11px;padding:5px 6px;border-radius:5px}.ribbon-actions button:hover,.ribbon-actions button:focus-visible{background:#323235;color:#fff}.thought-ribbon button:focus-visible,.step-list li:focus-visible,.args summary:focus-visible{outline:2px solid #d4d4d8;outline-offset:1px}.ribbon-actions button:disabled{opacity:.45}.ribbon-details{margin:3px 2px 5px;padding:11px 4px 3px;border-top:1px solid var(--wb-line,#303034);font-size:12px;line-height:1.55;color:#c4c4ca}h4{gap:6px;margin:0 0 9px;font-size:11px;font-weight:550;letter-spacing:.035em}.live-label{margin-left:auto;font-size:10px;color:#c0c0c7}.step-list{list-style:none;padding:0;margin:16px 0 0}.step-list li{position:relative;padding:0 0 15px 29px}.step-list li:not(:last-child)::before{content:'';position:absolute;left:9px;top:21px;bottom:1px;border-left:1px solid #3a3a3e}.step-mark{position:absolute;top:0;left:0;width:19px;height:19px;display:grid;place-items:center;border:1px solid #444448;background:#202023;border-radius:50%;font-size:12px;color:#ccc}.active .step-mark{border-color:#ddd;color:#fff}.step-head{min-height:20px;gap:5px 8px;flex-wrap:wrap}.step-head strong{font-size:12px;font-weight:550;overflow-wrap:anywhere;color:#ebebed}.step-head small{margin-left:auto;font-size:10px;color:#a6a6ad}.role{background:#303033;color:#e4e4e7;border-radius:4px;padding:0 4px;font-size:10px}.step-list p,.step-list blockquote,.args code{white-space:pre-wrap;overflow-wrap:anywhere}.step-list p,.role-output{margin:4px 0}.step-list blockquote{color:#d4d4d8;border-left:1px solid #535358;margin:7px 0 0;padding:0 8px;font-size:12px;line-height:1.6}.args{margin-top:5px;font-size:11px}.args summary{cursor:pointer}.args code{display:block}.copy-feedback,.no-reasoning{font-size:11px;color:#b3b3bb}.pulse{animation:blink 1.6s ease-in-out infinite}@keyframes blink{50%{opacity:.4}}@media(prefers-reduced-motion:reduce){.pulse{animation:none}.chevron{transition:none}}@media(pointer:coarse){.ribbon-toggle,.ribbon-actions button{min-height:44px}}
</style>

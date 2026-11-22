<template>
  <section class="execution-board" :aria-label="t('agent.workbench.board')">
    <div class="board-head"><div><span class="material-symbols-outlined" aria-hidden="true">account_tree</span><h3>{{ t('agent.workbench.board') }}</h3><span class="board-state" role="status">{{ t(`agent.workbench.state.${view.summary.status}`) }}</span></div><button type="button" :aria-expanded="expanded" :aria-label="t(expanded ? 'agent.workbench.boardHide' : 'agent.workbench.boardShow')" :title="t(expanded ? 'agent.workbench.boardHide' : 'agent.workbench.boardShow')" @click="expanded = !expanded"><span class="material-symbols-outlined" aria-hidden="true">{{ expanded ? 'unfold_less' : 'unfold_more' }}</span></button></div>
    <div v-show="expanded" class="board-grid">
      <div class="bust-cell"><div ref="stageHost" class="bust" aria-hidden="true"></div><span v-if="unavailable" class="no-stage">{{ t('agent.workbench.noWebgl') }}</span><span class="bust-caption" :title="t('agent.stage.hint')"><span class="material-symbols-outlined" aria-hidden="true">touch_app</span> NEXUS AI</span></div>
      <div class="stage-cards"><h4>{{ t('agent.workbench.pipeline') }}</h4>
        <p v-if="!view.nodes.length" class="empty">{{ t('agent.workbench.emptyPipeline') }}</p>
        <div v-else class="stage-list"><div v-for="n in stages" :key="n.id" class="stage-chip" :class="{ live: n.state === 'running' }"><b>{{ n.title }}</b><span>{{ t(`agent.workbench.state.${n.state}`) }}</span></div></div>
        <small v-if="view.nodes.length" class="progress-note">{{ t('agent.workbench.progress', { done: view.summary.finishedSteps, total: view.summary.steps }) }}</small>
        <span v-if="modelLabel" class="model-label" :title="modelLabel"><span class="material-symbols-outlined" aria-hidden="true">memory</span>{{ modelLabel }}</span>
      </div>
      <div class="artifacts"><h4>{{ t('agent.workbench.outputs') }} <small>{{ outputs.length }}</small></h4>
        <p v-if="!outputs.length" class="empty">{{ t('agent.workbench.emptyOutputs') }}</p>
        <button v-for="item in outputs" :key="item.id" type="button" class="file-item" :title="`${t('agent.workbench.preview')} · ${item.name}`" @click="emit('preview', item)"><span aria-hidden="true" class="material-symbols-outlined">{{ item.kind === 'graph' ? 'account_tree' : item.kind === 'patch' ? 'difference' : 'draft' }}</span><span>{{ item.name }}</span><small v-if="Number.isFinite(item.sizeBytes)">{{ item.sizeBytes < 1024 ? `${item.sizeBytes} B` : `${(item.sizeBytes / 1024).toFixed(1)} KB` }}</small></button>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { buildRunView } from './runView.js'

const props = defineProps({ run: { type: Object, default: null }, modelLabel: String, artifacts: { type: Array, default: undefined } })
const emit = defineEmits(['preview'])
const { t, te } = useI18n()
const expanded = ref(true)
const stageHost = ref(null)
const unavailable = ref(false)
const view = computed(() => buildRunView(props.run?.trace || [], { ...props.run, live: props.run?.status === 'streaming' }))
const outputs = computed(() => props.artifacts || view.value.artifacts)
const stages = computed(() => {
  const v = view.value
  if (v.agents.length > 1) return v.agents.map(a => ({ id: a.id, title: `${a.id} · ${a.model || a.nodes.at(-1).title}`, state: a.state }))
  return v.nodes.slice(-4).map(n => ({ id: n.id, title: n.model || n.toolName || (te(`agent.workbench.phase.${n.kind}`) ? t(`agent.workbench.phase.${n.kind}`) : n.title), state: n.state }))
})
let dispose, observer, mounted = true, inView = true
onMounted(async () => {
  observer = new IntersectionObserver(entries => { inView = !!entries[0]?.isIntersecting })
  observer.observe(stageHost.value)
  try {
    const { mountAbyssStage } = await import('./abyssStage.js')
    if (!mounted || !stageHost.value) return
    dispose = mountAbyssStage(stageHost.value, { shouldRender: () => expanded.value && inView })
  } catch { unavailable.value = true }
})
onUnmounted(() => { mounted = false; observer?.disconnect(); dispose?.() })
</script>

<style scoped>
.execution-board{color:#e4e4e7;background:rgba(27,27,30,.55);border:1px solid rgba(42,42,45,.45);border-radius:12px;padding:0 12px 0;min-width:0;container-type:inline-size}.board-head,.board-head>div,.model-label,.file-item,h4{display:flex;align-items:center;gap:6px}.board-head{justify-content:space-between;padding:5px 0;min-height:35px}.board-head h3{font-size:13px;font-weight:550}.board-state,h4,.model-label,.progress-note,.file-item{font-family:var(--wb-mono,ui-monospace,monospace)}.board-state,h4 small{font-size:10px;background:#29292d;border-radius:4px;padding:1px 5px;color:#c7c7cd}.material-symbols-outlined{font-size:15px}.board-head button,.file-item{border-radius:5px;color:#ccc}.board-head button{display:grid;place-items:center;min-width:28px;min-height:28px}.board-head button:hover,.file-item:hover{background:#303034;color:#fff}.execution-board button:focus-visible{outline:2px solid #ddd;outline-offset:2px}.board-grid{display:grid;grid-template-columns:minmax(105px,.83fr) minmax(185px,1.7fr) minmax(125px,1.05fr);gap:12px;border-top:1px solid var(--wb-line,#303034);padding:9px 0 12px}.bust-cell{position:relative;background:#101012;border:1px solid #29292d;border-radius:8px;overflow:hidden;min-height:112px}.bust{position:absolute;inset:0}.bust-caption{position:absolute;left:6px;bottom:5px;color:#c4c4cc;font:9px/1.3 var(--wb-mono,monospace);letter-spacing:.12em;display:flex;align-items:center;gap:3px;pointer-events:none}.bust-caption .material-symbols-outlined{font-size:10px}.no-stage{font-size:10px;color:#ababaf;position:absolute;inset:0;display:grid;place-items:center;text-align:center;padding:8px}.stage-cards,.artifacts{min-width:0}.stage-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:5px}.stage-chip,.file-item{min-width:0;padding:4px 6px;background:#111113;border:1px solid #303034;border-radius:5px}.stage-chip{display:grid;gap:2px}.stage-chip b,.file-item>span:nth-child(2),.model-label{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.stage-chip b{font-size:11px;font-weight:550}.stage-chip span,.progress-note,.model-label,.empty,.file-item small{color:#aaaab2;font-size:10px}.stage-chip.live{border-color:#a1a1aa}.progress-note{display:block;margin-top:5px}.model-label{margin-top:6px;display:block}.model-label .material-symbols-outlined{font-size:12px;vertical-align:middle;margin-right:3px}.empty{font-size:12px;line-height:1.5}.artifacts{border-left:1px solid #343437;padding-left:10px}.file-item{width:100%;text-align:left;margin-bottom:4px;line-height:1.5;font-size:11px}.file-item small{margin-left:auto;white-space:nowrap}.file-item>span:first-child{flex-shrink:0}h4{margin:0 0 7px;font-size:11px;font-weight:550;color:#d9d9dd}
@container(max-width:530px){.board-grid{grid-template-columns:minmax(90px,.65fr) minmax(0,1.35fr)}.artifacts{grid-column:1/-1;padding:6px 0 0;border-left:0;border-top:1px solid #303034}.bust-cell{min-height:92px}.artifacts:has(.empty){display:flex;align-items:center;gap:8px}.artifacts:has(.empty) h4{margin:0}}
@media(pointer:coarse){.board-head button,.file-item{min-height:44px}}
</style>

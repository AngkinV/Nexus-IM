<template>
  <section class="execution-board" :aria-label="t('board')">
    <div class="board-head"><div><span class="material-symbols-outlined" aria-hidden="true">account_tree</span><h3>{{ t('board') }}</h3><ElTag effect="plain" :disable-transitions="true" role="status">{{ t('state.' + view.summary.state) }}</ElTag></div><ElButton ref="boardToggle" :aria-expanded="expanded" :aria-controls="detailsId" :aria-label="t(expanded ? 'hideBoard' : 'showBoard')" @click="toggle"><span class="material-symbols-outlined" aria-hidden="true">{{ expanded ? 'unfold_less' : 'unfold_more' }}</span></ElButton></div>
    <p v-if="view.summary.cancel.unconfirmed" class="board-note">{{ t('cancelPending') }}</p>
    <div :id="detailsId" v-show="expanded" class="board-grid" :aria-hidden="!expanded" @keydown.esc="onEscape">
      <div class="bust-cell"><div ref="stageHost" class="bust" aria-hidden="true"></div><span v-if="unavailable" class="no-stage">{{ t('noWebgl') }}</span><span class="bust-caption" aria-hidden="true">NEXUS AI</span></div>
      <div class="stage-cards"><h4>{{ t('pipeline') }}</h4><p v-if="!view.nodes.length" class="empty">{{ t('emptyNodes') }}</p>
        <div v-else class="stage-list"><ElCard v-for="node in view.nodes" :key="node.id" shadow="never" class="stage-chip" :class="{ live: node.state === 'running' }" :data-exec-node="node.id" :data-agent-id="node.agentId || undefined"><div class="stage-identity"><AgentPixelSprite :agent-id="node.agentId" :node-id="node.id" :state="node.state" /><span><strong>{{ title(node) }}</strong><small>{{ node.role || node.agentId || t('unreported') }}</small></span></div><ElTag effect="plain" :disable-transitions="true">{{ t('state.' + node.state) }}</ElTag><small>{{ t('duration') }}: {{ node.ms == null ? t('unreported') : duration(node.ms) }}</small></ElCard></div>
        <small class="board-note">{{ t('recorded', { n: view.summary.recordedNodes }) }}</small><span v-if="modelLabel" class="model-label">{{ t('requestModel') }}: {{ modelLabel }}</span>
      </div>
      <div class="artifacts"><h4>{{ t('artifacts') }} <small>{{ outputs.length }}</small></h4><p v-if="!outputs.length" class="empty">{{ t('emptyArtifacts') }}</p>
        <ElButton v-for="item in outputs" :key="item.evidenceKey || artifactKey(item)" class="file-item" :disabled="!canPreview(item)" :aria-label="t('preview') + ' · ' + (item.name || item.id || t('unreported'))" @click="emit('preview', item)"><span aria-hidden="true" class="material-symbols-outlined">{{ item.kind === 'graph' ? 'account_tree' : item.kind === 'patch' ? 'difference' : 'draft' }}</span><span>{{ item.name || item.id || t('unreported') }}</span><small>{{ size(item.sizeBytes) }}</small></ElButton>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, getCurrentInstance, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElButton, ElCard, ElTag } from 'element-plus'
import { useI18n } from 'vue-i18n'
import AgentPixelSprite from './AgentPixelSprite.vue'
import { buildRunPresentation } from './runPresentation.js'
import { processMessages } from './processMessages.js'

const props = defineProps({ run: { type: Object, default: null }, modelLabel: String, artifacts: { type: Array, default: undefined } })
const emit = defineEmits(['preview'])
const { t, te } = useI18n({ useScope: 'local', messages: processMessages })
const detailsId = 'execution-board-' + getCurrentInstance().uid
const expanded = ref(true), stageHost = ref(null), boardToggle = ref(null), unavailable = ref(false), inView = ref(false), pageVisible = ref(true)
const view = computed(() => buildRunPresentation(props.run?.trace || [], { ...props.run, live: props.run?.status === 'streaming' }))
const outputs = computed(() => props.artifacts ?? view.value.artifacts)
const title = node => node.reportedTitle || node.model || node.toolName || node.agentName || (te('kind.' + node.kind) ? t('kind.' + node.kind) : node.title || t('unreported'))
const duration = ms => ms < 1000 ? Math.round(ms) + ' ms' : (ms / 1000).toFixed(1) + ' s'
const size = bytes => Number.isFinite(bytes) && bytes >= 0 ? (bytes < 1024 ? bytes + ' B' : (bytes / 1024).toFixed(1) + ' KB') : t('unreported')
const artifactKey = item => JSON.stringify([item.runId ?? props.run?.runId ?? null, item.attemptId ?? item.traceId ?? null, item.id])
const canPreview = item => item.id != null && outputs.value.filter(other => other.id === item.id).length === 1
let dispose, observer, mounted = false
const shouldRender = () => expanded.value && inView.value && pageVisible.value
const visibilityChanged = () => { pageVisible.value = !document.hidden }
function toggle() { expanded.value = !expanded.value }
async function onEscape(event) { if (!expanded.value) return; event.preventDefault(); event.stopPropagation(); expanded.value = false; await nextTick(); (boardToggle.value?.$el || boardToggle.value)?.focus({ preventScroll: true }) }
watch([expanded, inView, pageVisible], () => dispose?.refresh?.())
onMounted(async () => {
  mounted = true; visibilityChanged(); document.addEventListener('visibilitychange', visibilityChanged)
  if (typeof IntersectionObserver !== 'undefined') { observer = new IntersectionObserver(entries => { inView.value = !!entries[0]?.isIntersecting }); observer.observe(stageHost.value) } else inView.value = true
  try {
    const { mountAbyssStage } = await import('./abyssStage.js')
    if (!mounted || !stageHost.value) return
    // Existing 3D scene; on-demand lifecycle prevents hidden/folded/background
    // polling, and reduced motion draws only a static frame until invalidated.
    dispose = mountAbyssStage(stageHost.value, { shouldRender, onDemand: true }); dispose.refresh?.()
  } catch { if (mounted) unavailable.value = true }
})
onUnmounted(() => { mounted = false; observer?.disconnect(); if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', visibilityChanged); dispose?.() })
</script>

<style scoped>
.execution-board{container-type:inline-size;min-width:0;background:var(--wb-panel,#1b1b1b);border:1px solid var(--wb-line,#414141);border-radius:12px;padding:8px 12px;color:#e8e8e8}.board-head,.board-head>div,.stage-identity,.file-item,h4{display:flex;align-items:center;gap:8px;min-width:0}.board-head{justify-content:space-between;min-height:44px}.board-head>div{flex-wrap:wrap}.board-head h3{font-size:14px;font-weight:600;margin:0}.board-head :deep(.el-button){min-width:44px;min-height:44px;height:auto;margin:0;background:transparent;border-color:#555;color:#ddd;padding:8px;flex:none}.execution-board :deep(.el-button:focus-visible){outline:2px solid #eee;outline-offset:3px}.execution-board :deep(.el-tag){height:auto;min-height:24px;max-width:100%;background:#262626;border-color:#555;color:#ddd;font-size:11px;white-space:normal}.material-symbols-outlined{font-size:17px}.board-grid{display:grid;grid-template-columns:minmax(0,1fr);gap:14px;margin-top:8px;border-top:1px solid var(--wb-line,#444);padding-top:12px;min-width:0}.bust-cell{position:relative;min-height:150px;background:#101010;border:1px solid #333;border-radius:8px;overflow:hidden;min-width:0}.bust{position:absolute;inset:0}.bust-caption{position:absolute;left:8px;bottom:6px;font:11px/1.4 var(--wb-mono,monospace);letter-spacing:.08em;color:#ccc;pointer-events:none}.no-stage{position:absolute;inset:0;display:grid;place-items:center;text-align:center;padding:8px;font-size:12px;color:#ccc}.stage-cards,.artifacts{min-width:0}.stage-list{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(180px,100%),1fr));gap:8px;min-width:0;max-height:320px;overflow:auto;scrollbar-width:thin}.stage-chip{min-width:0;border-color:var(--wb-line,#444);background:var(--wb-card,#141414);color:#eee;border-radius:7px}.stage-chip.live{border-color:#aaa}.stage-chip :deep(.el-card__body){padding:10px;display:grid;gap:8px;min-width:0}.stage-identity>span{display:grid;gap:3px;min-width:0}.stage-identity strong{font-size:13px;font-weight:600;overflow-wrap:anywhere}.stage-chip small,.model-label,.board-note,.empty,.file-item small{font-size:12px;line-height:1.6;color:#bbb;overflow-wrap:anywhere}.board-note{display:block;margin:8px 0 0}.model-label{display:block;margin-top:6px}.artifacts{border-top:1px solid #444;padding-top:10px}.file-item{width:100%;height:auto;min-width:44px;min-height:44px;justify-content:flex-start;padding:8px;margin:0 0 8px!important;background:#202020;color:#ddd;border-color:#484848;white-space:normal;text-align:left}.file-item :deep(>span){display:flex;align-items:center;gap:6px;min-width:0;width:100%;white-space:normal}.file-item :deep(>span)>span:nth-child(2){min-width:0;overflow-wrap:anywhere;font-size:12px}.file-item small{margin-left:auto;flex:none;white-space:normal}.file-item .material-symbols-outlined{flex:none}.execution-board h4{margin:0 0 8px;font-size:13px;font-weight:600;color:#eee}.execution-board h4 small{font-size:12px;color:#bbb}.empty{margin:6px 0}
@container(min-width:580px){.board-grid{grid-template-columns:minmax(130px,.7fr) minmax(0,1.7fr)}.artifacts{grid-column:1/-1}.bust-cell{min-height:170px}}
@container(min-width:900px){.board-grid{grid-template-columns:minmax(140px,.7fr) minmax(0,1.8fr) minmax(160px,1fr)}.artifacts{grid-column:auto;border-top:0;border-left:1px solid #444;padding:0 0 0 12px}}
@media(max-width:600px){.execution-board{padding:8px}.stage-list{grid-template-columns:minmax(0,1fr)}.board-grid{gap:10px}}
@media(prefers-reduced-motion:reduce){.execution-board :deep(*){animation:none!important;transition:none!important}}
</style>

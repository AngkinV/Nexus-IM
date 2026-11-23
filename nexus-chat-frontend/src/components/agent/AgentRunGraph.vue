<template>
  <div ref="host" class="dag" :aria-label="t('graph')">
    <svg aria-hidden="true" class="wires"><path v-for="path in paths" :key="path.key" :d="path.d" /></svg>
    <ol class="dag-layers">
      <li v-for="layer in layout.layers" :key="layer.key" class="dag-layer" :data-dependency-layer="layer.level">
        <p class="layer-caption">{{ t(layer.unresolved ? 'cyclic' : 'layer', { n: (layer.level ?? 0) + 1 }) }}</p>
        <div class="layer-nodes">
          <ElButton v-for="node in layer.nodes" :key="node.id" :data-exec-node="node.id" :data-agent-id="node.agentId || undefined" class="vertex" :class="{ selected: selectedId === node.id, live: node.state === 'running' }" :aria-pressed="selectedId === node.id" :aria-controls="detailsId || undefined" :aria-label="title(node) + ' · ' + t('state.' + node.state)" @click="emit('focus-node', node.id)">
            <span class="node-content"><span class="cap"><span>{{ [node.nodeId, node.callId].filter(Boolean).join(' / ') || t('unreported') }}</span><ElTag effect="plain" :disable-transitions="true">{{ t('state.' + node.state) }}</ElTag></span>
              <span class="node-identity"><AgentPixelSprite :agent-id="node.agentId" :node-id="node.id" :state="node.state" /><span><strong>{{ title(node) }}</strong><small>{{ node.role || node.agentId || t('unreported') }}</small></span></span>
              <span v-if="incoming.get(node.id)?.length" class="parents">{{ t('parents') }}: {{ incoming.get(node.id).join(' / ') }}</span>
              <span v-if="node.unresolvedParentIds?.length" class="parents unresolved">{{ t('unresolvedParents') }}: {{ node.unresolvedParentIds.join(' / ') }}</span>
              <span class="node-time">{{ t('duration') }}: {{ node.ms == null ? t('unreported') : duration(node.ms) }}</span>
            </span>
          </ElButton>
        </div>
      </li>
    </ol>
    <p class="graph-note">{{ t('graphNote') }}</p>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElButton, ElTag } from 'element-plus'
import { useI18n } from 'vue-i18n'
import AgentPixelSprite from './AgentPixelSprite.vue'
import { dependencyLayers } from './runPresentation.js'
import { processMessages } from './processMessages.js'

const props = defineProps({ nodes: { type: Array, required: true }, edges: { type: Array, required: true }, selectedId: String, detailsId: String, active: { type: Boolean, default: true } })
const emit = defineEmits(['focus-node'])
const { t, te } = useI18n({ useScope: 'local', messages: processMessages })
const host = ref(null), paths = ref([])
const layout = computed(() => dependencyLayers(props.nodes, props.edges))
const title = node => node.reportedTitle || node.model || node.toolName || node.agentName || (te('kind.' + node.kind) ? t('kind.' + node.kind) : node.title || t('unreported'))
const duration = ms => ms < 1000 ? Math.round(ms) + ' ms' : (ms / 1000).toFixed(1) + ' s'
const incoming = computed(() => {
  const labels = new Map(props.nodes.map(node => [node.id, node.nodeId || node.callId || t('unreported')]))
  const result = new Map()
  for (const edge of props.edges) if (labels.has(edge.from) && labels.has(edge.to)) result.set(edge.to, [...(result.get(edge.to) || []), labels.get(edge.from)])
  return result
})
let observer, frame = 0
function clear() { if (frame) cancelAnimationFrame(frame); frame = 0; paths.value = [] }
function measure() {
  frame = 0
  if (!host.value || !props.active || document.hidden) { paths.value = []; return }
  const base = host.value.getBoundingClientRect()
  if (!base.width || !base.height) { paths.value = []; return }
  const rects = new Map([...host.value.querySelectorAll('[data-exec-node]')].map(el => [el.dataset.execNode, el.getBoundingClientRect()]))
  paths.value = props.edges.flatMap(edge => {
    const a = rects.get(edge.from), b = rects.get(edge.to)
    if (!a || !b) return []
    const x1 = a.left + a.width / 2 - base.left, x2 = b.left + b.width / 2 - base.left
    const y1 = a.bottom - base.top, y2 = b.top - base.top, mid = (y1 + y2) / 2
    return [{ key: JSON.stringify([edge.from, edge.to]), d: 'M' + x1 + ' ' + y1 + ' C' + x1 + ' ' + mid + ',' + x2 + ' ' + mid + ',' + x2 + ' ' + y2 }]
  })
}
function schedule() {
  if (!host.value || !props.active || document.hidden) { clear(); return }
  if (frame) cancelAnimationFrame(frame)
  frame = requestAnimationFrame(measure)
}
onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') { observer = new ResizeObserver(schedule); observer.observe(host.value) }
  window.addEventListener('resize', schedule); document.addEventListener('visibilitychange', schedule); schedule()
})
watch(() => JSON.stringify([props.active, props.edges.map(edge => [edge.from, edge.to]), props.nodes.map(node => [node.id, node.state, node.title, node.ms])]), () => nextTick(schedule))
onUnmounted(() => { observer?.disconnect(); clear(); if (typeof window !== 'undefined') window.removeEventListener('resize', schedule); if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', schedule) })
</script>

<style scoped>
.dag{position:relative;display:grid;grid-template-columns:minmax(0,1fr);gap:10px;min-width:0;isolation:isolate}
.wires{position:absolute;inset:0;width:100%;height:100%;pointer-events:none;z-index:0;overflow:hidden;fill:none;stroke:var(--wb-line,#606060);stroke-width:1.5}
.dag-layers{position:relative;z-index:1;list-style:none;padding:0;margin:0;display:grid;gap:20px;min-width:0}.dag-layer{min-width:0}.layer-caption{font:12px/1.5 var(--wb-mono,monospace);color:#bbb;margin:0 0 8px}.layer-nodes{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(176px,100%),1fr));gap:12px;min-width:0}
.vertex{width:100%;height:auto;min-width:44px;min-height:100px;padding:12px;background:var(--wb-card,#151515);border:1px solid var(--wb-line,#444);border-radius:8px;text-align:left;color:#eee;white-space:normal;align-items:flex-start;justify-content:flex-start;margin:0!important}
.vertex:hover,.vertex.live{background:#222;color:#fff;border-color:#aaa}.vertex.selected{border-color:#eee;background:#262626}.vertex:focus-visible{outline:2px solid #fafafa;outline-offset:3px}.vertex :deep(>span){display:block;width:100%;min-width:0}.node-content{display:grid;gap:8px;min-width:0}.cap{display:flex;align-items:start;justify-content:space-between;gap:6px;font:11px/1.5 var(--wb-mono,monospace);color:#bbb;flex-wrap:wrap;overflow-wrap:anywhere}.cap :deep(.el-tag){max-width:100%;height:auto;min-height:24px;font-size:11px;color:#ddd;border-color:#555;background:#202020;white-space:normal}
.node-identity{display:flex;align-items:center;gap:8px;min-width:0}.node-identity>span{display:grid;gap:3px;min-width:0}.node-identity strong{font-size:13px;line-height:1.5;font-weight:600;overflow-wrap:anywhere}.node-identity small,.parents,.node-time,.graph-note{font-size:12px;line-height:1.55;color:#bdbdbd;overflow-wrap:anywhere}.parents{display:block}.unresolved{border-left:2px dashed #aaa;padding-left:6px}.graph-note{position:relative;z-index:1;margin:0}
@media(max-width:600px){.layer-nodes{grid-template-columns:minmax(0,1fr)}}
@media(prefers-reduced-motion:reduce){.vertex{transition:none!important}}
</style>

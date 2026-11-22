<template>
  <div class="dag" ref="host" :aria-label="t('agent.workbench.graph')">
    <svg aria-hidden="true" class="wires"><path v-for="p in paths" :key="p.key" :d="p.d" :class="{ flowing: p.live }" /></svg>
    <button v-for="(node, i) in nodes" :key="node.id" :data-exec-node="node.id" class="vertex" :class="{ live: node.state === 'running' }" type="button" @click="emit('focus-node', node.id)">
      <span class="cap"><span>NODE {{ String(i + 1).padStart(2, '0') }}</span><span>{{ t(`agent.workbench.state.${node.state}`) }}</span></span>
      <strong :title="title(node)">{{ title(node) }}</strong>
      <span v-if="incoming.get(node.id)?.length" class="parents">{{ t('agent.workbench.from', { nodes: incoming.get(node.id).join(' / ') }) }}</span>
    </button>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({ nodes: { type: Array, required: true }, edges: { type: Array, required: true } })
const emit = defineEmits(['focus-node'])
const { t, te } = useI18n()
const host = ref(null)
const paths = ref([])
let observer
let frame = 0
const title = n => n.kind === 'model' && n.agentId && n.agentId !== 'assistant' ? n.agentId : n.model || n.toolName || (te(`agent.workbench.phase.${n.kind}`) ? t(`agent.workbench.phase.${n.kind}`) : n.title)
const incoming = computed(() => {
  const labels = new Map(props.nodes.map((n, i) => [n.id, String(i + 1).padStart(2, '0')]))
  const map = new Map()
  for (const edge of props.edges) map.set(edge.to, [...(map.get(edge.to) || []), labels.get(edge.from)])
  return map
})
function measure() {
  if (!host.value) return
  const base = host.value.getBoundingClientRect()
  const rects = new Map([...host.value.querySelectorAll('[data-exec-node]')].map(el => [el.dataset.execNode, el.getBoundingClientRect()]))
  const active = new Set(props.nodes.filter(n => n.state === 'running').map(n => n.id))
  paths.value = props.edges.flatMap(edge => {
    const a = rects.get(edge.from), b = rects.get(edge.to)
    if (!a || !b) return []
    let d
    if (Math.abs(a.top - b.top) < a.height / 2 && b.left >= a.right) {
      const x1 = a.right - base.left, x2 = b.left - base.left, y = a.top + a.height / 2 - base.top
      d = `M${x1} ${y} H${x2}`
    } else {
      const x1 = a.left + a.width / 2 - base.left, x2 = b.left + b.width / 2 - base.left
      const y1 = a.bottom - base.top, y2 = b.top - base.top, mid = (y1 + y2) / 2
      d = `M${x1} ${y1} C${x1} ${mid},${x2} ${mid},${x2} ${y2}`
    }
    return [{ key: `${edge.from}/${edge.to}`, d, live: active.has(edge.to) }]
  })
}
function schedule() {
  if (!host.value) return
  cancelAnimationFrame(frame)
  frame = requestAnimationFrame(measure)
}
onMounted(() => { observer = new ResizeObserver(schedule); observer.observe(host.value); schedule() })
watch(() => [props.edges.map(e => `${e.from}/${e.to}`).join('|'), props.nodes.map(n => `${n.id}:${n.state}`).join('|')], () => nextTick(schedule))
onUnmounted(() => { observer?.disconnect(); cancelAnimationFrame(frame) })
</script>

<style scoped>
.dag{position:relative;display:grid;grid-template-columns:repeat(auto-fit,minmax(min(108px,100%),1fr));gap:13px 11px;isolation:isolate}
.wires{position:absolute;inset:0;width:100%;height:100%;pointer-events:none;z-index:-1;overflow:visible;fill:none;stroke:var(--wb-line,#424246);stroke-width:1.5}
.flowing{stroke:#d4d4d8;stroke-dasharray:3 4;animation:flow 1.2s linear infinite}.vertex{background:var(--wb-card,#151517);border:1px solid var(--wb-line,#3b3b3e);color:#e4e4e7;border-radius:7px;padding:7px 8px;text-align:left;min-width:0;display:grid;gap:4px}.vertex:hover,.vertex:focus-visible,.vertex.live{border-color:#a1a1aa}.vertex:focus-visible{outline:2px solid #eee;outline-offset:2px}.cap,.parents{font:10px/1.35 var(--wb-mono,monospace);color:#aaaab2}.cap{display:flex;justify-content:space-between;gap:4px;flex-wrap:wrap}.vertex strong{font-size:12px;font-weight:500;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.parents{overflow-wrap:anywhere}
@keyframes flow{to{stroke-dashoffset:-14}}@media(prefers-reduced-motion:reduce){.flowing{animation:none}}
</style>

<template>
  <svg class="agent-pixel" :class="'pose-' + pose" :style="tint" :data-agent-id="agentId || undefined" :data-node-key="nodeId" viewBox="0 0 12 16" width="24" height="32" aria-hidden="true" focusable="false" shape-rendering="crispEdges">
    <template v-if="agentId">
      <!-- A whole small figure: each limb is its own group so the room can move it. -->
      <path class="shadow" d="M3 15h6v1H3z" />
      <g class="leg-l"><path class="pants" d="M4 12h2v2H4z" /><path class="shoe" d="M4 14h2v1H4z" /></g>
      <g class="leg-r"><path class="pants" d="M6 12h2v2H6z" /><path class="shoe" d="M6 14h2v1H6z" /></g>
      <g class="torso"><path class="shirt" d="M3 7h6v5H3z" /><path class="trim" d="M5 7h2v1H5z" /></g>
      <g class="arm-l"><path class="shirt" d="M2 7h1v3H2z" /><path class="skin" d="M2 10h1v1H2z" /></g>
      <g class="arm-r"><path class="shirt" d="M9 7h1v3H9z" /><path class="skin" d="M9 10h1v1H9z" /></g>
      <g class="head"><path class="skin" d="M3 2h6v5H3z" /><path class="hair" :d="hair" /><path class="ink eyes" d="M4 4h1v1H4zm3 0h1v1H7z" /></g>
    </template>
    <template v-else>
      <!-- A node without a reported agent gets a document glyph, not an invented role. -->
      <path class="sheet" d="M2 2h6v2h2v12H2z" /><path class="ink" d="M8 2v2h2zM4 7h4v1H4zm0 3h4v1H4zm0 3h3v1H4z" />
    </template>
  </svg>
</template>

<script setup>
import { computed } from 'vue'
import { agentColor, poseOf } from './pixelScene.js'

const props = defineProps({ agentId: { type: String, default: null }, nodeId: { type: String, required: true }, state: { type: String, default: null } })
// Colour and hair follow the stable reported agent ID, the pose follows the
// reported state. Decoration only: never a source of roles, state or progress.
// The figure never moves on its own; whoever places it decides when it may.
const color = computed(() => agentColor(props.agentId))
const tint = computed(() => color.value ? { '--px-main': color.value.main, '--px-dark': color.value.dark } : null)
const pose = computed(() => props.state ? poseOf(props.state) : 'idle')
const variant = computed(() => { let hash = 0; for (const ch of props.agentId || '') hash = (Math.imul(hash, 31) + ch.charCodeAt(0)) >>> 0; return hash % 3 })
const hair = computed(() => [
  'M3 0h6v1h1v3H9V2H3v2H2V1h1z',
  'M2 1h1V0h2v1h2V0h2v1h1v3H9V2H3v2H2z',
  'M3 0h6v1h1v6H9V2H3v5H2V1h1z'
][variant.value])
</script>

<style scoped>
.agent-pixel{display:block;flex:none;width:calc(12*var(--px-unit,2px));height:calc(16*var(--px-unit,2px));image-rendering:pixelated;overflow:visible}
.skin{fill:#f0c8a0}.hair{fill:var(--px-dark,#6b7078)}.shirt{fill:var(--px-main,#9aa0a6)}.trim{fill:#f4f4f4}.ink{fill:#17181c}.sheet{fill:#b9bdc4}
.pants{fill:#4b5060}.shoe{fill:#d0d3da}.shadow{fill:#000;opacity:.35}
/* The eyes turn toward whatever the room says the figure is looking at. */
.eyes{transform:translateX(calc(var(--px-look,0)*1px))}
/* A step that stopped, or whose state was never reported, is drawn without its colour. */
.pose-stopped,.pose-unknown,.pose-skipped{filter:grayscale(1) brightness(.85)}
</style>

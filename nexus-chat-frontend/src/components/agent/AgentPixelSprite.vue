<template>
  <svg class="agent-pixel" :class="'pose-' + pose" :style="tint" :data-agent-id="agentId || undefined" :data-node-key="nodeId" viewBox="0 0 16 16" width="48" height="48" aria-hidden="true" focusable="false" shape-rendering="crispEdges">
    <template v-if="agentId">
      <!-- Head and shoulders, drawn without an outline so it reads on the dark panel. -->
      <path class="skin" d="M5 4h6v5H5zM7 9h2v1H7z" />
      <path class="hair" :d="hair" />
      <path class="ink eyes" d="M6 6h1v1H6zm3 0h1v1H9z" />
      <path class="shirt" d="M4 10h8v1h1v5H3v-5h1z" /><path class="trim" d="M7 10h2v1H7z" />
      <path class="skin hands" d="M4 12h2v1H4zm6 0h2v1h-2z" />
    </template>
    <template v-else>
      <!-- A node without a reported agent gets a document glyph, not an invented role. -->
      <path class="sheet" d="M3 1h8v2h2v12H3z" /><path class="ink" d="M5 6h6v1H5zm0 3h6v1H5zm0 3h4v1H5zM11 1v2h2z" />
    </template>
  </svg>
</template>

<script setup>
import { computed } from 'vue'
import { agentColor, poseOf } from './pixelScene.js'

const props = defineProps({ agentId: { type: String, default: null }, nodeId: { type: String, required: true }, state: { type: String, default: null } })
// Colour and hair follow the stable reported agent ID, the pose follows the
// reported state. Decoration only: never a source of roles, state or progress.
const color = computed(() => agentColor(props.agentId))
const tint = computed(() => color.value ? { '--px-main': color.value.main, '--px-dark': color.value.dark } : null)
const pose = computed(() => props.state ? poseOf(props.state) : 'idle')
const variant = computed(() => { let hash = 0; for (const ch of props.agentId || '') hash = (Math.imul(hash, 31) + ch.charCodeAt(0)) >>> 0; return hash % 3 })
const hair = computed(() => [
  'M5 1h6v1h1v3h-1V4H5v1H4V2h1z',
  'M4 2h1V1h2v1h2V1h2v1h1v3h-1V4H5v1H4z',
  'M5 1h6v1h1v6h-1V4H5v4H4V2h1z'
][variant.value])
</script>

<style scoped>
.agent-pixel{display:block;flex:none;width:48px;height:48px;image-rendering:pixelated}
.skin{fill:#f0c8a0}.hair{fill:var(--px-dark,#6b7078)}.shirt{fill:var(--px-main,#9aa0a6)}.trim{fill:#f4f4f4}.ink{fill:#17181c}.sheet{fill:#b9bdc4}
/* A step that stopped, or whose state was never reported, is drawn without its colour. */
.pose-stopped,.pose-unknown,.pose-skipped{filter:grayscale(1) brightness(.85)}
</style>

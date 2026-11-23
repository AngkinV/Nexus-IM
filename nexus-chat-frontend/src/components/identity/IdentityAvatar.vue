<template>
  <span class="identity-avatar" :style="sizeStyle">
    <img v-if="imageVisible(url, failedUrl)" :key="url" :src="url" :alt="label || kind" @error="onImageError" />
    <span v-else class="identity-fallback" data-avatar-fallback="true" :style="{ backgroundColor: fallbackColor(entityId, kind) }" role="img" :aria-label="label || kind">{{ initialOf(label, kind) }}</span>
  </span>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import { fallbackColor } from './displayFacts.mjs'
import { resolveFileUrl } from '@/services/api'
import { initialOf, imageVisible } from './presentation.mjs'
const props = defineProps({ src: String, entityId: [String, Number], label: String, kind: { type: String, default: 'user' }, size: [Number, String] })
const failedUrl = ref('')
const url = computed(() => props.src ? resolveFileUrl(props.src) : '')
watch(url, () => { failedUrl.value = '' }, { flush: 'sync' })
const onImageError = event => {
  // Read the emitting image's source, not the closure's latest reactive URL.
  const source = event?.currentTarget?.getAttribute('src')
  if (source && source === url.value) failedUrl.value = source
}
const sizeStyle = computed(() => props.size ? { width: typeof props.size === 'number' ? `${props.size}px` : props.size, height: typeof props.size === 'number' ? `${props.size}px` : props.size } : undefined)
</script>
<style scoped>
.identity-avatar { display: inline-flex; width: 100%; height: 100%; flex-shrink: 0; border-radius: inherit; overflow: hidden; filter: none; mix-blend-mode: normal; }
.identity-avatar img { width: 100%; height: 100%; object-fit: cover; filter: none; mix-blend-mode: normal; }
.identity-fallback { display: flex; width: 100%; height: 100%; align-items: center; justify-content: center; color: #fff; font-weight: 600; }
</style>

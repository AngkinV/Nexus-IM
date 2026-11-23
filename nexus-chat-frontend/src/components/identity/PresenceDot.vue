<template>
  <span v-if="!isGroup(type) && presence(value) !== null" class="identity-presence" :class="{ online: value === true }" role="img" :aria-label="label" :title="label"></span>
</template>
<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { isGroup, presence } from './presentation.mjs'
const props = defineProps({ value: { default: null }, type: String })
const { t } = useI18n()
const label = computed(() => t(props.value === true ? 'chat.online' : 'chat.offline'))
</script>
<style scoped>
.identity-presence { display: inline-block; border-radius: 9999px; background: #8e9192 !important; }
.identity-presence.online { background: #22c55e !important; }
</style>

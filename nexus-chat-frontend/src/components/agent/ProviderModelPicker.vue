<template>
  <div class="model-picker">
    <div v-if="provider" class="catalog-controls">
      <el-select
        filterable :teleported="false" :model-value="selected?.id" :disabled="disabled || !models.length"
        :aria-label="$t('agent.providers.searchModels')" :placeholder="$t('agent.providers.searchModels')"
        @update:model-value="choose"
      >
        <el-option v-for="m in models" :key="m.id" :value="m.id" :label="m.id" :disabled="m[purpose] === false" />
      </el-select>
      <el-button :disabled="disabled" :loading="catalog?.status === 'loading'" @click="store.fetchModels(provider.id, { force: true })">
        {{ $t('common.refresh') }}
      </el-button>
    </div>
    <span class="picker-note" role="status">{{ catalogNote }}</span>
    <el-input :model-value="modelValue" :aria-label="label" :disabled="disabled" clearable
      :placeholder="$t('agent.providers.fieldModelHint')" @update:model-value="emit('update:modelValue', $event)" />
    <span v-if="modelNote" class="picker-note" role="status">{{ $t(`agent.providers.${modelNote}`) }}</span>
  </div>
</template>

<script setup>
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { modelCatalogNote, useAgentProvidersStore } from '@/stores/agentProviders'

const props = defineProps({
  modelValue: { type: String, default: '' }, providerId: [Number, String],
  purpose: { type: String, default: 'chat' }, label: { type: String, required: true },
  unavailableHint: String, disabled: Boolean
})
const emit = defineEmits(['update:modelValue'])
const { t } = useI18n()
const store = useAgentProvidersStore()
const provider = computed(() => store.getById(props.providerId))
const catalog = computed(() => store.getCatalog(props.providerId))
const models = computed(() => catalog.value?.models || [])
const selected = computed(() => models.value.find(m => m.id === props.modelValue.trim()))
const modelNote = computed(() => modelCatalogNote(models.value, props.modelValue, props.purpose))
const catalogNote = computed(() => {
  if (!provider.value) return props.unavailableHint
  const c = catalog.value
  if (!c || c.status === 'loading') return t('common.loading')
  if (c.status === 'unsupported') return t('agent.providers.catalogUnsupported')
  if (c.error || (!c.models.length && !['empty', 'ready'].includes(c.status))) {
    const detail = c.error?.summary || c.error?.code || (typeof c.error === 'string' && c.error)
    return [t('agent.providers.catalogFailed'), detail].filter(Boolean).join(' · ')
  }
  if (!c.models.length) return t('agent.providers.catalogEmpty')
  return t('agent.providers.modelsLoaded', { count: c.models.length }) + (c.truncated ? ` · ${t('agent.providers.truncated')}` : '')
})
watch([() => store.accountId, () => provider.value?.id, () => provider.value?.revision ?? provider.value?.updatedAt], () => {
  if (provider.value) store.fetchModels(provider.value.id)
}, { immediate: true })
function choose(id) {
  const option = models.value.find(m => m.id === id)
  if (option && option[props.purpose] !== false) emit('update:modelValue', option.id)
}
</script>

<style scoped>
.model-picker { display: grid; gap: 6px; width: 100%; min-width: 0; }
.catalog-controls { display: flex; align-items: center; gap: 6px; }
.catalog-controls .el-select { flex: 1; min-width: 0; }
.picker-note { font-size: 12px; line-height: 1.4; color: var(--tg-text-secondary, #afb2b4); }
</style>

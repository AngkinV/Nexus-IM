<template>
  <!--
    主管理对话框：可拖动悬浮窗。
    - draggable        : 标题栏可拖
    - :modal="false"   : 不显示遮罩，避免被 Companion 3D 等高 z-index 元素挡住，
                         也方便用户拖到不挡视线的位置后继续操作 IM 主界面
    - :z-index="10010" : 显式压过 CompanionAvatar3D 的 9999
    - overflow="false" : 拖出视口外仍可见
  -->
  <el-dialog
    v-model="visibleModel"
    :title="$t('agent.providers.title')"
    width="min(900px, 94vw)"
    draggable
    overflow
    :modal="false"
    :close-on-click-modal="false"
    :z-index="10010"
    append-to-body
    class="provider-settings-dialog"
  >
    <el-tabs v-model="activeTab" class="purpose-tabs">
      <el-tab-pane :label="$t('agent.providers.tabChat')" name="chat" />
      <el-tab-pane :label="$t('agent.providers.tabEmbedding')" name="embedding" />
    </el-tabs>

    <div class="provider-toolbar">
      <p class="provider-help">
        {{ activeTab === 'embedding'
          ? ($t('agent.providers.embeddingHelp'))
          : $t('agent.providers.help') }}
      </p>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        <span>{{ $t('agent.providers.add') }}</span>
      </el-button>
    </div>

    <div v-if="tabProviders.length" class="provider-filter">
      <el-input
        v-model="query" clearable class="provider-search"
        :placeholder="$t('agent.providers.searchChannels')" :aria-label="$t('agent.providers.searchChannels')"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <span class="provider-count" role="status">{{ query.trim()
        ? $t('agent.providers.countFiltered', { shown: visibleProviders.length, n: tabProviders.length })
        : $t('agent.providers.count', { n: tabProviders.length }) }}</span>
    </div>

    <el-table
      v-if="tabProviders.length || store.loading" :data="visibleProviders" v-loading="store.loading" stripe size="default"
      max-height="420" row-key="id" :empty-text="$t('agent.providers.noMatch')"
    >
      <el-table-column prop="displayName" :label="$t('agent.providers.colName')" min-width="190">
        <template #default="{ row }">
          <div class="cell-name">
            <strong>{{ row.displayName || row.provider }}</strong>
            <el-tag v-if="row.isDefault" type="success" size="small" effect="light">
              {{ $t('agent.providers.default') }}
            </el-tag>
          </div>
          <div class="cell-sub">{{ presetLabel(vendorOf(row, activeTab)) }} · {{ row.provider }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="defaultModel" :label="$t('agent.providers.colModel')" min-width="170">
        <template #default="{ row }">
          <span class="model-text">{{ row.defaultModel || '—' }}</span>
          <div class="cell-sub">{{ formatLabel(formatOf(row)) }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="$t('agent.providers.colKey')" width="130">
        <template #default="{ row }">
          <code v-if="row.hasApiKey" class="key-mask">{{ row.apiKeyMask || '***' }}</code>
          <span v-else-if="keyOptionalFor(row)" class="cell-sub">{{ $t('agent.providers.keyNotNeeded') }}</span>
          <span v-else class="missing">{{ $t('agent.providers.noKey') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="$t('agent.providers.colStatus')" width="96">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="$t('agent.providers.colActions')" width="220" align="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" :disabled="!canTest(row)" :loading="store.isTesting(row.id)" :title="$t('agent.providers.testHint')" @click="testOne(row)">{{ $t('agent.providers.test') }}</el-button>
          <el-button link size="small" @click="openEdit(row)">{{ $t('common.edit') }}</el-button>
          <el-button link size="small" :disabled="row.isDefault || store.isTesting(row.id)" @click="setDefault(row)">
            {{ $t('agent.providers.setDefault') }}
          </el-button>
          <el-button link type="danger" size="small" :disabled="store.isTesting(row.id)" @click="removeOne(row)">{{ $t('common.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!store.loading && !tabProviders.length" :description="$t('agent.providers.empty')" />
  </el-dialog>

  <!-- Edit / Create dialog: 仍然走模态遮罩（表单需要专注），z-index 在主悬浮窗之上 -->
  <el-dialog
    v-model="formDialog"
    :title="form.id ? $t('agent.providers.edit') : $t('agent.providers.add')"
    width="min(580px, 94vw)"
    :close-on-click-modal="false"
    :z-index="10020"
    append-to-body
    draggable
  >
    <el-form :model="form" label-width="110px" label-position="left" :disabled="saving">
      <el-form-item :label="$t('agent.providers.preset')">
        <el-select
          v-model="form.preset" filterable :teleported="false" :aria-label="$t('agent.providers.preset')"
          :placeholder="$t('agent.providers.searchVendors')" @change="applyPreset"
        >
          <el-option-group v-for="group in presetGroups" :key="group.key" :label="$t(`agent.providers.group.${group.key}`)">
            <el-option v-for="p in group.items" :key="p.key" :label="presetLabel(p)" :value="p.key" />
          </el-option-group>
        </el-select>
        <div v-if="activePreset.key === 'custom'" class="form-hint">{{ $t('agent.providers.customHint') }}</div>
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldDisplay')">
        <el-input v-model="form.displayName" maxlength="80" :placeholder="presetLabel(activePreset)" />
        <div v-if="form.id" class="form-hint">{{ $t('agent.providers.channelId', { id: form.provider }) }}</div>
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldFormat')">
        <el-radio-group v-model="form.apiFormat" class="provider-api-format" :aria-label="$t('agent.providers.fieldFormat')">
          <el-radio-button v-for="f in formatChoices" :key="f" :value="f">{{ formatLabel(f) }}</el-radio-button>
        </el-radio-group>
        <div v-if="formatChoices.length === 1" class="form-hint">{{ $t('agent.providers.formatFixed') }}</div>
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldBaseUrl')" :required="baseUrlRequired">
        <el-input v-model="form.baseUrl" maxlength="255" :placeholder="activePreset.baseUrl || 'https://api.example.com/v1'" />
        <div v-if="form.apiFormat === 'openai-compatible'" class="form-hint">{{ $t('agent.providers.baseUrlHint') }}</div>
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldKey')">
        <el-input
          v-model="form.apiKey"
          type="password"
          show-password
          autocomplete="new-password"
          :placeholder="form.id ? $t('agent.providers.fieldKeyKeep') : 'sk-...'"
        />
        <div v-if="activePreset.keyOptional" class="form-hint">{{ $t('agent.providers.keyOptionalHint') }}</div>
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldModel')">
        <ProviderModelPicker v-if="formDialog" v-model="form.defaultModel" :provider-id="connectionDirty ? null : form.id"
          :purpose="form.purpose" :label="$t('agent.providers.fieldModel')" :unavailable-hint="$t('agent.providers.saveForCatalog')" />
      </el-form-item>

      <el-form-item>
        <el-checkbox v-model="form.makeDefault">{{ $t('agent.providers.makeDefault') }}</el-checkbox>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="formDialog = false">{{ $t('common.cancel') }}</el-button>
      <el-button
        v-if="form.id"
        :loading="store.isTesting(form.id)" :disabled="!canTest(store.getById(form.id))"
        :title="$t('agent.providers.testHint')" @click="testOne(store.getById(form.id))"
      >
        {{ $t('agent.providers.test') }}
      </el-button>
      <el-button type="primary" :loading="saving" :disabled="incompatible || store.isTesting(form.id)" @click="save">{{ $t('common.save') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import {
  useAgentProvidersStore,
  API_FORMAT,
  VENDOR_GROUPS,
  PROVIDER_PURPOSE,
  presetsFor,
  presetLabel,
  vendorOf,
  formatOf,
  keyOptionalFor,
  freeChannelId,
  filterChannels
} from '@/stores/agentProviders'
import { useAgentStore } from '@/stores/agent'
import ProviderModelPicker from './ProviderModelPicker.vue'

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['update:visible'])
const visibleModel = computed({
  get: () => props.visible,
  set: (v) => emit('update:visible', v)
})

const { t } = useI18n()
const store = useAgentProvidersStore()
const agentStore = useAgentStore()

const activeTab = ref('chat') // 'chat' | 'embedding'
const formDialog = ref(false)
const saving = ref(false)
const form = ref(emptyForm())

const query = ref('')
const presetsForActiveTab = computed(() => presetsFor(activeTab.value))
const presetGroups = computed(() => VENDOR_GROUPS
  .map(key => ({ key, items: presetsForActiveTab.value.filter(p => p.group === key) })).filter(g => g.items.length))

// Every saved channel of this tab, and the ones the search box leaves visible.
const tabProviders = computed(() =>
  activeTab.value === 'embedding' ? store.embeddingProviders : store.chatProviders
)
const visibleProviders = computed(() => filterChannels(tabProviders.value, query.value))

const activePreset = computed(() =>
  presetsForActiveTab.value.find(p => p.key === form.value.preset) || presetsForActiveTab.value.find(p => p.key === 'custom'))
// An official vendor speaks one protocol; only a custom endpoint chooses.
const formatChoices = computed(() => {
  const offered = activePreset.value.formats || [activePreset.value.format]
  return offered.includes(form.value.apiFormat) ? offered : [...offered, form.value.apiFormat].filter(Boolean)
})
// Anthropic and Gemini have one well-known address; an OpenAI-compatible endpoint must be given.
const baseUrlRequired = computed(() => form.value.apiFormat === API_FORMAT.OPENAI)

function emptyForm(purpose = 'chat') {
  return {
    id: null,
    purpose,
    preset: 'openai',
    provider: '',
    apiFormat: API_FORMAT.OPENAI,
    displayName: '',
    baseUrl: '',
    defaultModel: '',
    apiKey: '',
    makeDefault: false
  }
}

const original = ref(null)
const dirty = computed(() => !original.value || Object.keys(original.value).some(k => form.value[k] !== original.value[k]))
const connectionDirty = computed(() => !form.value.id || !original.value ||
  ['provider', 'purpose', 'preset', 'apiFormat', 'baseUrl', 'apiKey'].some(k => form.value[k] !== original.value[k]))
const incompatible = computed(() => !connectionDirty.value && store.getModelsForProvider(form.value.id)
  .find(m => m.id === form.value.defaultModel.trim())?.[form.value.purpose] === false)

watch([visibleModel, activeTab, () => store.accountId], async ([open, tab, accountId], _, onCleanup) => {
  let current = true
  onCleanup(() => { current = false })
  formDialog.value = false
  form.value = emptyForm(tab)
  original.value = null
  query.value = ''
  if (!open || accountId == null) return
  // Catalogues are read per channel when it is edited: reading every saved
  // channel's model list on open does not scale to dozens of channels.
  await store.fetchAll()
}, { immediate: true })

watch(formDialog, open => { if (!open) form.value.apiKey = '' })

function statusTagType(status) {
  if (status === 'ok') return 'success'
  if (status === 'invalid') return 'danger'
  return 'info'
}

// Message keys are plain words; the protocol name itself carries a hyphen.
function formatLabel(format) {
  return t(`agent.providers.format.${format === API_FORMAT.OPENAI ? 'openai' : format}`)
}

function statusLabel(status) {
  return t(`agent.providers.status.${['ok', 'invalid'].includes(status) ? status : 'unknown'}`)
}

/** The vendor's name, numbered when a channel of this tab already uses it. */
function freeDisplayName(preset) {
  const base = presetLabel(preset), taken = new Set(tabProviders.value.map(p => p.displayName))
  if (!taken.has(base)) return base
  for (let n = 2; ; n++) if (!taken.has(`${base} ${n}`)) return `${base} ${n}`
}

function openCreate() {
  form.value = emptyForm(activeTab.value)
  original.value = null
  applyPreset(form.value.preset)
  formDialog.value = true
}

function openEdit(row) {
  const preset = vendorOf(row, row.purpose || activeTab.value)
  form.value = {
    id: row.id,
    purpose: row.purpose || activeTab.value,
    preset: preset.key,
    provider: row.provider,
    apiFormat: formatOf(row),
    displayName: row.displayName || '',
    baseUrl: row.baseUrl || '',
    defaultModel: row.defaultModel || '',
    apiKey: '',
    makeDefault: !!(row.isDefault ?? row.default)
  }
  original.value = { ...form.value }
  formDialog.value = true
}

function applyPreset(key) {
  const preset = presetsForActiveTab.value.find(p => p.key === key)
  if (!preset) return
  if (!form.value.id) {
    // A new channel gets an id and a name nobody holds, so adding a second channel
    // of the same vendor (or a second custom endpoint) never replaces the first.
    form.value.provider = freeChannelId(preset.key, tabProviders.value)
    form.value.displayName = freeDisplayName(preset)
  }
  // A custom endpoint has no address or protocol of its own: when an existing channel
  // is re-labelled as custom, what it already uses stays.
  const editing = !!form.value.id
  form.value.apiFormat = editing && preset.formats?.includes(form.value.apiFormat) ? form.value.apiFormat : preset.format
  form.value.baseUrl = preset.baseUrl || (editing ? form.value.baseUrl : '')
}

async function save() {
  if (saving.value || incompatible.value || store.isTesting(form.value.id)) return
  if (baseUrlRequired.value && !form.value.baseUrl?.trim()) {
    ElMessage.warning(t('agent.providers.baseUrlRequired'))
    return
  }
  saving.value = true
  try {
    const payload = {
      id: form.value.id || undefined,
      provider: form.value.provider.trim() || freeChannelId(activePreset.value.key, tabProviders.value),
      vendor: activePreset.value.key,
      apiFormat: form.value.apiFormat,
      purpose: form.value.purpose || PROVIDER_PURPOSE.CHAT,
      displayName: form.value.displayName?.trim() || presetLabel(activePreset.value),
      baseUrl: form.value.baseUrl?.trim() || null,
      defaultModel: form.value.defaultModel?.trim() || null,
      apiKey: form.value.apiKey || null,
      makeDefault: form.value.makeDefault
    }
    const editing = form.value
    const saved = await store.upsert(payload)
    if (!saved?.id) return
    store.fetchModels(saved.id)
    if (!formDialog.value || form.value !== editing) return
    ElMessage.success(t('common.savedSuccess'))
    // A connection can be saved before choosing a model. Stay here for that choice.
    if (!payload.defaultModel) openEdit(store.getById(saved.id) || saved)
    else formDialog.value = false
  } catch (err) {
    ElMessage.error(err?.response?.data?.message || err.message || t('agent.providers.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function removeOne(row) {
  try {
    await ElMessageBox.confirm(
      t('agent.providers.confirmDelete', { name: row.displayName || row.provider }),
      t('common.delete'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning',
        // This dialog is pinned at z-index 10010; ElMessageBox uses the global
        // nextZIndex() counter (~2000), so without an override the confirm box
        // renders behind the dialog. See the unscoped <style> at the end.
        customClass: 'provider-confirm-on-top',
        modalClass: 'provider-confirm-on-top'
      }
    )
  } catch { return }
  try {
    await store.remove(row.id)
    if (agentStore.activeProviderId === row.id) {
      agentStore.setActiveProviderId(null)
    }
    ElMessage.success(t('agent.providers.deleted'))
  } catch (err) {
    ElMessage.error(err.message || t('agent.providers.deleteFailed'))
  }
}

async function setDefault(row) {
  try {
    await store.setDefault(row.id)
    ElMessage.success(t('agent.providers.defaultUpdated'))
  } catch (err) {
    ElMessage.error(err.message || t('common.failed'))
  }
}

function canTest(row) {
  return !!row?.id && !!row.defaultModel?.trim() && !saving.value && !store.isTesting(row.id) &&
    !(formDialog.value && dirty.value) &&
    store.getModelsForProvider(row.id).find(m => m.id === row.defaultModel.trim())?.[row.purpose || 'chat'] !== false
}

function testOne(row) {
  if (!canTest(row)) return
  // The store keeps the request alive; App's root delivers the result even
  // after this dialog or the whole workbench has unmounted.
  void store.test(row.id)
}

</script>

<style scoped>
.purpose-tabs {
  margin-bottom: 8px;
}
.provider-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 16px;
}
.provider-filter {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.provider-search {
  flex: 1;
  min-width: 0;
}
.provider-count {
  flex: none;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px;
  color: var(--tg-text-tertiary);
}
.provider-help {
  margin: 0;
  font-size: 13px;
  color: var(--tg-text-secondary);
  line-height: 1.5;
}
.cell-name {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cell-sub {
  font-size: 11px;
  color: var(--tg-text-secondary);
  margin-top: 2px;
}
.model-text {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12.5px;
}
.key-mask {
  background: rgba(255, 255, 255, 0.1);
  color: var(--tg-primary);
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 12px;
}
.missing {
  color: #f2b8b5;
  font-size: 12px;
}
/* Element's selected radio-button defaults to white text on primary. Primary
   is white in this theme: explicitly use the design's on-primary ink. Keep
   the override local to API formats, including hover/disabled/focus states. */
.provider-api-format {
  --el-radio-button-checked-text-color: #2f3131;
  --el-radio-button-checked-bg-color: var(--tg-primary);
  --el-radio-button-checked-border-color: var(--tg-primary);
  max-width: 100%;
}
.provider-api-format :deep(.el-radio-button__inner) {
  background: var(--tg-surface);
  color: var(--tg-text-primary);
  border-color: var(--el-border-color);
}
.provider-api-format :deep(.el-radio-button:not(.is-disabled):not(.is-active) .el-radio-button__inner:hover) {
  background: var(--tg-surface-hover);
  color: var(--tg-text-primary);
}
.provider-api-format :deep(.el-radio-button.is-active:not(.is-disabled) .el-radio-button__inner) {
  background: var(--tg-primary);
  color: #2f3131;
  border-color: var(--tg-primary);
  box-shadow: -1px 0 0 0 var(--tg-primary);
}
.provider-api-format :deep(.el-radio-button.is-disabled .el-radio-button__inner) {
  background: var(--tg-surface);
  color: var(--tg-text-tertiary);
  border-color: var(--el-border-color);
  box-shadow: none;
}
.provider-api-format :deep(.el-radio-button.is-disabled.is-active .el-radio-button__inner) {
  background: var(--tg-surface-hover);
  color: var(--tg-text-secondary);
}
.provider-api-format :deep(.el-radio-button.is-focus .el-radio-button__inner),
.provider-api-format :deep(.el-radio-button__original-radio:focus-visible + .el-radio-button__inner) {
  outline: 2px solid var(--tg-text-tertiary);
  outline-offset: 2px;
}
.form-hint {
  font-size: 11px;
  color: var(--tg-text-secondary);
  margin-top: 4px;
}
</style>

<style>
/* ElMessageBox is teleported to <body>, so this rule cannot be scoped.
   The provider dialog is pinned at z-index 10010 (10020 for the edit dialog);
   the delete confirm must render above both.
   Why :has(): in Element Plus 2.x the inline z-index from nextZIndex() is set on
   the .el-overlay-message-box wrapper, not the inner .el-message-box that
   customClass targets. */
.provider-confirm-on-top,
.el-overlay-message-box:has(.provider-confirm-on-top),
.el-overlay:has(.provider-confirm-on-top) {
  z-index: 20000 !important;
}
</style>

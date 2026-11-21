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
    width="760px"
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

    <el-table :data="visibleProviders" v-loading="store.loading" stripe size="default">
      <el-table-column prop="displayName" :label="$t('agent.providers.colName')" min-width="160">
        <template #default="{ row }">
          <div class="cell-name">
            <strong>{{ row.displayName || row.provider }}</strong>
            <el-tag v-if="row.isDefault" type="success" size="small" effect="light">
              {{ $t('agent.providers.default') }}
            </el-tag>
          </div>
          <div class="cell-sub">{{ row.provider }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="defaultModel" :label="$t('agent.providers.colModel')" min-width="160">
        <template #default="{ row }">
          <span class="model-text">{{ row.defaultModel || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="$t('agent.providers.colKey')" width="140">
        <template #default="{ row }">
          <code v-if="row.hasApiKey" class="key-mask">{{ row.apiKeyMask || '***' }}</code>
          <span v-else class="missing">{{ $t('agent.providers.noKey') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="$t('agent.providers.colStatus')" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="$t('agent.providers.colActions')" width="220" align="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" :disabled="!canTest(row)" :title="$t('agent.providers.testHint')" @click="testOne(row)">{{ $t('agent.providers.test') }}</el-button>
          <el-button link size="small" @click="openEdit(row)">{{ $t('common.edit') }}</el-button>
          <el-button link size="small" :disabled="row.isDefault" @click="setDefault(row)">
            {{ $t('agent.providers.setDefault') }}
          </el-button>
          <el-button link type="danger" size="small" @click="removeOne(row)">{{ $t('common.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!store.loading && !visibleProviders.length" :description="$t('agent.providers.empty')" />
  </el-dialog>

  <!-- Edit / Create dialog: 仍然走模态遮罩（表单需要专注），z-index 在主悬浮窗之上 -->
  <el-dialog
    v-model="formDialog"
    :title="form.id ? $t('agent.providers.edit') : $t('agent.providers.add')"
    width="560px"
    :close-on-click-modal="false"
    :z-index="10020"
    append-to-body
    draggable
  >
    <el-form :model="form" label-width="110px" label-position="left" :disabled="saving">
      <el-form-item :label="$t('agent.providers.preset')">
        <el-select v-model="form.preset" :teleported="false" :aria-label="$t('agent.providers.preset')" @change="applyPreset">
          <el-option v-for="p in presetsForActiveTab" :key="p.key" :label="presetLabel(p)" :value="p.key" />
        </el-select>
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldId')" required>
        <el-input v-model="form.provider" :disabled="!!form.id" placeholder="openai" />
        <div class="form-hint">{{ $t('agent.providers.fieldIdHint') }}</div>
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldDisplay')">
        <el-input v-model="form.displayName" :placeholder="$t('agent.providers.displayPlaceholder')" />
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldBaseUrl')">
        <el-input v-model="form.baseUrl" placeholder="https://api.openai.com/v1" />
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldKey')">
        <el-input
          v-model="form.apiKey"
          type="password"
          show-password
          :placeholder="form.id ? $t('agent.providers.fieldKeyKeep') : 'sk-...'"
        />
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
        :loading="testingId === form.id" :disabled="!canTest(store.getById(form.id))"
        :title="$t('agent.providers.testHint')" @click="testOne(store.getById(form.id))"
      >
        {{ $t('agent.providers.test') }}
      </el-button>
      <el-button type="primary" :loading="saving" :disabled="incompatible || testingId != null" @click="save">{{ $t('common.save') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  useAgentProvidersStore,
  PROVIDER_PRESETS,
  EMBEDDING_PRESETS,
  presetLabel,
  PROVIDER_PURPOSE
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
const testingId = ref(null)
const form = ref(emptyForm())

const presetsForActiveTab = computed(() =>
  activeTab.value === 'embedding' ? EMBEDDING_PRESETS : PROVIDER_PRESETS
)

const visibleProviders = computed(() =>
  activeTab.value === 'embedding' ? store.embeddingProviders : store.chatProviders
)

function emptyForm(purpose = 'chat') {
  return {
    id: null,
    purpose,
    preset: 'openai',
    provider: '',
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
  ['provider', 'purpose', 'baseUrl', 'apiKey'].some(k => form.value[k] !== original.value[k]))
const incompatible = computed(() => !connectionDirty.value && store.getModelsForProvider(form.value.id)
  .find(m => m.id === form.value.defaultModel.trim())?.[form.value.purpose] === false)

watch([visibleModel, activeTab, () => store.accountId], async ([open, tab, accountId], _, onCleanup) => {
  let current = true
  onCleanup(() => { current = false })
  formDialog.value = false
  form.value = emptyForm(tab)
  original.value = null
  if (!open || accountId == null) return
  await store.fetchAll()
  if (current) visibleProviders.value.forEach(p => store.fetchModels(p.id))
}, { immediate: true })

watch(formDialog, open => { if (!open) form.value.apiKey = '' })

function statusTagType(status) {
  if (status === 'ok') return 'success'
  if (status === 'invalid') return 'danger'
  return 'info'
}

function openCreate() {
  form.value = emptyForm(activeTab.value)
  original.value = null
  applyPreset(form.value.preset)
  formDialog.value = true
}

function openEdit(row) {
  const list = presetsForActiveTab.value
  const preset = list.find(p => row.provider?.startsWith(p.key)) || list.find(p => p.key === 'custom')
  form.value = {
    id: row.id,
    purpose: row.purpose || activeTab.value,
    preset: preset.key,
    provider: row.provider,
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
    form.value.provider = preset.key
    form.value.displayName = presetLabel(preset)
  }
  form.value.baseUrl = preset.baseUrl
}

async function save() {
  if (saving.value || incompatible.value || testingId.value != null) return
  if (!form.value.provider.trim()) {
    ElMessage.warning(t('agent.providers.fieldIdHint'))
    return
  }
  saving.value = true
  try {
    const payload = {
      provider: form.value.provider.trim(),
      purpose: form.value.purpose || PROVIDER_PURPOSE.CHAT,
      displayName: form.value.displayName?.trim() || null,
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
        type: 'warning'
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
  return !!row?.id && !!row.defaultModel?.trim() && !saving.value && testingId.value == null &&
    !(formDialog.value && dirty.value) &&
    store.getModelsForProvider(row.id).find(m => m.id === row.defaultModel.trim())?.[row.purpose || 'chat'] !== false
}

async function testOne(row) {
  if (!canTest(row)) return
  try {
    testingId.value = row.id
    const result = await store.test(row.id)
    if (result?.ok) {
      ElMessage.success(t('agent.providers.testOk', { latency: result.latencyMs ?? '?' }))
      if (result?.embeddingDimension) {
        ElMessage.info(`embedding dim = ${result.embeddingDimension}`)
      }
    } else {
      const error = result?.error
      ElMessage.warning([t('agent.providers.testFail'), error?.code, error?.summary || result?.message,
        error?.retryAfterSeconds != null && t('companion.session.retryAfter', { seconds: error.retryAfterSeconds })
      ].filter(Boolean).join(' · '))
    }
  } catch (err) {
    ElMessage.error(err.message || t('agent.providers.testFail'))
  } finally {
    testingId.value = null
  }
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
.form-hint {
  font-size: 11px;
  color: var(--tg-text-secondary);
  margin-top: 4px;
}
</style>

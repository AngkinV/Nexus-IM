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
      <el-tab-pane :label="$t('agent.providers.tabChat') || '对话模型'" name="chat" />
      <el-tab-pane :label="$t('agent.providers.tabEmbedding') || '向量 Embedding'" name="embedding" />
    </el-tabs>

    <div class="provider-toolbar">
      <p class="provider-help">
        {{ activeTab === 'embedding'
          ? ($t('agent.providers.embeddingHelp') || '为知识库选择支持 /embeddings 的服务商；与对话模型独立配置。')
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
          <el-button link type="primary" size="small" @click="testOne(row)">{{ $t('agent.providers.test') }}</el-button>
          <el-button link size="small" @click="openEdit(row)">{{ $t('common.edit') }}</el-button>
          <el-button link type="success" size="small" :disabled="row.isDefault" @click="setDefault(row)">
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
    <el-form :model="form" label-width="110px" label-position="left" ref="formRef">
      <el-form-item :label="$t('agent.providers.preset')">
        <el-select v-model="form.preset" placeholder="OpenAI / DeepSeek / ..." @change="applyPreset">
          <el-option v-for="p in presetsForActiveTab" :key="p.key" :label="p.label" :value="p.key" />
        </el-select>
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldId')" required>
        <el-input v-model="form.provider" :disabled="!!form.id" placeholder="openai" />
        <div class="form-hint">{{ $t('agent.providers.fieldIdHint') }}</div>
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldDisplay')">
        <el-input v-model="form.displayName" placeholder="OpenAI 主账号" />
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldBaseUrl')">
        <el-input v-model="form.baseUrl" placeholder="https://api.openai.com/v1" />
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldModel')">
        <el-autocomplete
          v-model="form.defaultModel"
          :fetch-suggestions="queryModelSuggestions"
          clearable
          :placeholder="$t('agent.providers.fieldModelHint')"
          @select="onModelPicked"
        />
        <div v-if="remoteModelCount > 0" class="form-hint">
          {{ t('agent.providers.modelsLoaded', { count: remoteModelCount }) }}
        </div>
      </el-form-item>

      <el-form-item :label="$t('agent.providers.fieldKey')">
        <el-input
          v-model="form.apiKey"
          type="password"
          show-password
          :placeholder="form.id ? $t('agent.providers.fieldKeyKeep') : 'sk-...'"
        />
      </el-form-item>

      <el-form-item>
        <el-checkbox v-model="form.makeDefault">{{ $t('agent.providers.makeDefault') }}</el-checkbox>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="formDialog = false">{{ $t('common.cancel') }}</el-button>
      <el-button
        v-if="form.id"
        :loading="testingId === form.id"
        @click="testFromForm"
      >
        {{ $t('agent.providers.testAndFetch') }}
      </el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ $t('common.save') }}</el-button>
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
  PROVIDER_PURPOSE
} from '@/stores/agentProviders'
import { useAgentStore } from '@/stores/agent'

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['update:visible'])
const visibleModel = computed({
  get: () => props.visible,
  set: (v) => emit('update:visible', v)
})

const { t } = useI18n()
const store = useAgentProvidersStore()
const agentStore = useAgentStore()

const presets = PROVIDER_PRESETS
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

const modelSuggestions = computed(() => {
  const preset = presetsForActiveTab.value.find(p => p.key === form.value.preset)
  return preset?.suggestedModels || []
})

const remoteModels = computed(() => store.getModelsForProvider(form.value.id))
const remoteModelCount = computed(() => remoteModels.value.length)
const mergedModelSuggestions = computed(() => {
  const merged = new Set()
  for (const m of modelSuggestions.value) {
    const v = (m || '').toString().trim()
    if (v) merged.add(v)
  }
  for (const m of remoteModels.value) {
    const v = (m || '').toString().trim()
    if (v) merged.add(v)
  }
  const current = (form.value.defaultModel || '').trim()
  if (current) merged.add(current)
  return Array.from(merged)
})

watch(visibleModel, async (val) => {
  if (val) {
    await store.fetchAll()
  }
})

function statusTagType(status) {
  if (status === 'ok') return 'success'
  if (status === 'invalid') return 'danger'
  return 'info'
}

function openCreate() {
  form.value = emptyForm(activeTab.value)
  applyPreset(presetsForActiveTab.value[0]?.key || 'openai')
  formDialog.value = true
}

function openEdit(row) {
  const list = presetsForActiveTab.value
  const preset = list.find(p => row.provider?.startsWith(p.key)) || list[0] || presets[0]
  form.value = {
    id: row.id,
    purpose: row.purpose || activeTab.value,
    preset: preset.key,
    provider: row.provider,
    displayName: row.displayName || '',
    baseUrl: row.baseUrl || '',
    defaultModel: row.defaultModel || '',
    apiKey: '',
    makeDefault: !!row.isDefault
  }
  formDialog.value = true
}

function applyPreset(key) {
  const preset = presetsForActiveTab.value.find(p => p.key === key)
  if (!preset) return
  if (!form.value.id) {
    form.value.provider = preset.key
    form.value.displayName = preset.label
  }
  if (preset.baseUrl) form.value.baseUrl = preset.baseUrl
  if (preset.suggestedModels?.length && !form.value.defaultModel) {
    form.value.defaultModel = preset.suggestedModels[0]
  }
}

function queryModelSuggestions(queryString, cb) {
  const q = (queryString || '').trim().toLowerCase()
  const candidates = mergedModelSuggestions.value
    .filter(m => !q || m.toLowerCase().includes(q))
    .slice(0, 50)
    .map(m => ({ value: m }))
  cb(candidates)
}

function onModelPicked(item) {
  if (!item?.value) return
  form.value.defaultModel = item.value
}

async function save() {
  if (!form.value.provider) {
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
    await store.upsert(payload)
    ElMessage.success(t('common.savedSuccess'))
    formDialog.value = false
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

async function testOne(row) {
  try {
    testingId.value = row.id
    const result = await store.test(row.id)
    if (result?.ok) {
      ElMessage.success(t('agent.providers.testOk', { latency: result.latencyMs ?? '?' }))
      if (result?.embeddingDimension) {
        ElMessage.info(`embedding dim = ${result.embeddingDimension}`)
      }
      if (result?.availableModels?.length) {
        ElMessage.info(t('agent.providers.modelsLoaded', { count: result.availableModels.length }))
      }
    } else {
      ElMessage.warning(`${t('agent.providers.testFail')}: ${result?.message || 'unknown'}`)
    }
  } catch (err) {
    ElMessage.error(err.message || t('agent.providers.testFail'))
  } finally {
    testingId.value = null
  }
}

async function testFromForm() {
  if (!form.value.id) return
  await testOne({ id: form.value.id })
  const fetched = store.getModelsForProvider(form.value.id)
  if (!fetched.length) return
  const current = (form.value.defaultModel || '').trim()
  if (!current || !fetched.includes(current)) {
    form.value.defaultModel = fetched[0]
    ElMessage.success(t('agent.providers.modelAutoFilled'))
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
  background: rgba(6, 182, 212, 0.1);
  color: var(--tg-primary);
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 12px;
}
.missing {
  color: #ef4444;
  font-size: 12px;
}
.form-hint {
  font-size: 11px;
  color: var(--tg-text-secondary);
  margin-top: 4px;
}
</style>

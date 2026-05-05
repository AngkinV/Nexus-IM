<template>
  <!--
    Module B knowledge-base manager dialog. Layout pattern matches
    AgentProviderSettings.vue: a non-modal draggable dialog so users can
    keep the IM main view visible while they manage KBs.
  -->
  <el-dialog
    v-model="visibleModel"
    :title="$t('agent.knowledge.title')"
    width="820px"
    draggable
    overflow
    :modal="false"
    :close-on-click-modal="false"
    :z-index="10010"
    append-to-body
    class="knowledge-manager-dialog"
    @close="onDialogClose"
  >
    <div class="kb-toolbar">
      <p class="kb-help">{{ $t('agent.knowledge.help') }}</p>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        <span>{{ $t('agent.knowledge.create') }}</span>
      </el-button>
    </div>

    <el-empty
      v-if="!store.loading && !store.kbs.length"
      :description="$t('agent.knowledge.empty')"
    />

    <el-collapse v-else v-model="expandedKbIds" @change="onExpandChange">
      <el-collapse-item
        v-for="kb in store.kbs"
        :key="kb.kbId"
        :name="kb.kbId"
      >
        <template #title>
          <div class="kb-row-header">
            <strong class="kb-name">{{ kb.name }}</strong>
            <span class="kb-counters">
              {{ $t('agent.knowledge.docs') }}: {{ kb.documentCount }} ·
              {{ $t('agent.knowledge.chunks') }}: {{ kb.chunkCount }}
            </span>
            <el-tag v-if="kb.embeddingProviderLabel" size="small" type="info" effect="plain">
              {{ kb.embeddingProviderLabel }}
            </el-tag>
            <el-tag size="small" effect="plain">{{ kb.embeddingModel }}</el-tag>
            <el-tag v-if="kb.embeddingDimension" size="small" type="success" effect="plain">
              dim={{ kb.embeddingDimension }}
            </el-tag>
          </div>
        </template>

        <div class="kb-body">
          <p v-if="kb.description" class="kb-desc">{{ kb.description }}</p>

          <div class="kb-actions">
            <el-button size="small" @click.stop="openEdit(kb)">
              <el-icon><Edit /></el-icon>
              <span>{{ $t('agent.knowledge.rename') }}</span>
            </el-button>
            <el-button size="small" type="danger" plain @click.stop="removeKb(kb)">
              <el-icon><Delete /></el-icon>
              <span>{{ $t('agent.knowledge.deleteKb') }}</span>
            </el-button>
            <el-upload
              :show-file-list="false"
              :before-upload="(file) => beforeUpload(kb, file)"
              :http-request="(opts) => doUpload(kb, opts)"
              :accept="acceptAttr"
              class="kb-upload-button"
            >
              <el-button size="small" type="primary">
                <el-icon><Upload /></el-icon>
                <span>{{ $t('agent.knowledge.uploadDocument') }}</span>
              </el-button>
            </el-upload>
            <span class="kb-format-hint">{{ $t('agent.knowledge.formatHint') }}</span>
          </div>

          <el-table
            :data="store.documentsByKb[kb.kbId] || []"
            v-loading="loadingByKb[kb.kbId]"
            size="small"
            stripe
            empty-text=" "
            class="kb-doc-table"
          >
            <el-table-column prop="fileName" :label="$t('agent.knowledge.colFileName')" min-width="200">
              <template #default="{ row }">
                <span class="cell-filename">{{ row.fileName }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="fileType" :label="$t('agent.knowledge.colType')" width="80" />
            <el-table-column :label="$t('agent.knowledge.colSize')" width="100">
              <template #default="{ row }">
                {{ formatSize(row.fileSize) }}
              </template>
            </el-table-column>
            <el-table-column prop="chunkCount" :label="$t('agent.knowledge.colChunks')" width="80" />
            <el-table-column :label="$t('agent.knowledge.colStatus')" width="130">
              <template #default="{ row }">
                <el-tooltip
                  :disabled="!row.errorMessage"
                  :content="row.errorMessage || ''"
                  placement="top"
                >
                  <el-tag :type="statusTagType(row.status)" size="small">
                    <span v-if="row.status === 'PROCESSING'" class="status-spinner">⟳</span>
                    {{ statusLabel(row.status) }}
                  </el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column :label="$t('agent.knowledge.colActions')" width="100" align="right">
              <template #default="{ row }">
                <el-button link type="danger" size="small" @click="removeDocument(kb, row)">
                  {{ $t('common.delete') }}
                </el-button>
              </template>
            </el-table-column>

            <template #empty>
              <el-empty :description="$t('agent.knowledge.docEmpty')" :image-size="60" />
            </template>
          </el-table>
        </div>
      </el-collapse-item>
    </el-collapse>
  </el-dialog>

  <!-- Create / edit dialog -->
  <el-dialog
    v-model="formDialog"
    :title="form.kbId ? $t('agent.knowledge.editTitle') : $t('agent.knowledge.createTitle')"
    width="540px"
    :close-on-click-modal="false"
    :z-index="10020"
    append-to-body
    draggable
  >
    <el-form :model="form" label-width="120px" label-position="left">
      <el-form-item :label="$t('agent.knowledge.fieldName')" required>
        <el-input v-model="form.name" maxlength="120" show-word-limit />
      </el-form-item>
      <el-form-item :label="$t('agent.knowledge.fieldDescription')">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
        />
      </el-form-item>

      <!--
        Embedding 服务 / model: locked once the KB has produced vectors
        (kb.embeddingDimension is non-null). Switching providers later would
        change the vector dimension and break Chroma's locked collection.
      -->
      <el-form-item label="Embedding 服务">
        <el-select
          v-model="form.embeddingCredentialId"
          :disabled="embeddingLocked"
          clearable
          placeholder="选择已配置的 Embedding 凭据 (留空使用服务器默认)"
          style="width: 100%"
        >
          <el-option
            v-for="p in embeddingProviderOptions"
            :key="p.id"
            :value="p.id"
            :label="`${p.displayName || p.provider}${p.defaultModel ? '  ·  ' + p.defaultModel : ''}`"
            :disabled="p.status === 'invalid'"
          >
            <span style="float: left">{{ p.displayName || p.provider }}</span>
            <span style="float: right; color: var(--el-text-color-secondary); font-size: 12px;">
              {{ p.defaultModel || '—' }} ·
              <el-tag :type="statusTagFor(p.status)" size="small" effect="plain">{{ p.status }}</el-tag>
            </span>
          </el-option>
          <template #empty>
            <div style="padding: 12px; font-size: 12px; color: var(--el-text-color-secondary);">
              还没有 Embedding 凭据。请先打开"AI 设置 → 向量 Embedding"添加一条。
            </div>
          </template>
        </el-select>
        <div class="form-hint" v-if="embeddingLocked">
          已经入库过文档，dim={{ form._lockedDimension }}，不能再改 Embedding 配置（会与现有向量维度冲突）。
          要换 Embedding 请新建一个知识库。
        </div>
        <div class="form-hint" v-else-if="!form.embeddingCredentialId">
          ⚠️ 留空时会回退到 Python 服务的 EMBEDDING_API_KEY 环境变量；推荐在前端绑定一条凭据。
        </div>
      </el-form-item>

      <el-form-item label="Embedding 模型">
        <el-input
          v-model="form.embeddingModel"
          :disabled="embeddingLocked"
          :placeholder="embeddingModelPlaceholder"
        />
        <div class="form-hint">
          留空将使用所选凭据的默认模型（{{ defaultModelHint || 'text-embedding-3-small' }}）。
        </div>
      </el-form-item>

      <el-form-item v-if="!form.kbId" :label="$t('agent.knowledge.fieldChunkSize')">
        <el-input-number v-model="form.chunkSize" :min="64" :max="2048" :step="64" />
      </el-form-item>
      <el-form-item v-if="!form.kbId" :label="$t('agent.knowledge.fieldChunkOverlap')">
        <el-input-number v-model="form.chunkOverlap" :min="0" :max="512" :step="16" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="formDialog = false">{{ $t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ $t('common.save') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Upload } from '@element-plus/icons-vue'

import { useKnowledgeStore } from '@/stores/knowledge'
import { useAgentProvidersStore } from '@/stores/agentProviders'
import { KB_FILE_MAX_BYTES, KB_ALLOWED_EXT } from '@/services/knowledgeApi'

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['update:visible'])

const { t } = useI18n()
const store = useKnowledgeStore()
const providersStore = useAgentProvidersStore()

const visibleModel = computed({
  get: () => props.visible,
  set: (v) => emit('update:visible', v)
})

const expandedKbIds = ref([])
const loadingByKb = ref({})
const formDialog = ref(false)
const saving = ref(false)
const form = ref(emptyForm())

const acceptAttr = '.' + KB_ALLOWED_EXT.join(',.')

const embeddingProviderOptions = computed(() => providersStore.embeddingProviders)
const embeddingLocked = computed(() => !!form.value._lockedDimension)
const defaultModelHint = computed(() => {
  if (!form.value.embeddingCredentialId) return ''
  const p = providersStore.getById(form.value.embeddingCredentialId)
  return p?.defaultModel || ''
})
const embeddingModelPlaceholder = computed(() =>
  defaultModelHint.value || 'text-embedding-3-small / text-embedding-v3 / bge-m3 ...'
)

watch(visibleModel, async (val) => {
  if (val) {
    await Promise.all([
      store.fetchKbs(),
      providersStore.fetchAll()
    ])
  } else {
    store.cancelAllPolls()
  }
})

onBeforeUnmount(() => {
  store.cancelAllPolls()
})

function emptyForm() {
  return {
    kbId: null,
    name: '',
    description: '',
    embeddingCredentialId: null,
    embeddingModel: '',
    chunkSize: 512,
    chunkOverlap: 64,
    _lockedDimension: null
  }
}

function statusTagFor(status) {
  if (status === 'ok') return 'success'
  if (status === 'invalid') return 'danger'
  return 'info'
}

function openCreate() {
  form.value = emptyForm()
  // Pre-fill with the user's default embedding credential when one exists.
  const def = providersStore.defaultEmbeddingProvider
  if (def) {
    form.value.embeddingCredentialId = def.id
    form.value.embeddingModel = def.defaultModel || ''
  }
  formDialog.value = true
}

function openEdit(kb) {
  form.value = {
    kbId: kb.kbId,
    name: kb.name,
    description: kb.description || '',
    embeddingCredentialId: kb.embeddingCredentialId || null,
    embeddingModel: kb.embeddingModel || '',
    chunkSize: kb.chunkSize,
    chunkOverlap: kb.chunkOverlap,
    _lockedDimension: kb.embeddingDimension || null
  }
  formDialog.value = true
}

async function save() {
  if (!form.value.name.trim()) {
    ElMessage.warning(t('agent.knowledge.nameRequired'))
    return
  }
  saving.value = true
  try {
    if (form.value.kbId) {
      const payload = {
        name: form.value.name.trim(),
        description: form.value.description?.trim() || null
      }
      // Only forward embedding fields when not locked — server enforces too,
      // but skipping the noisy "no change" round-trip is cleaner.
      if (!embeddingLocked.value) {
        payload.embeddingCredentialId = form.value.embeddingCredentialId || null
        payload.embeddingModel = form.value.embeddingModel?.trim() || null
      }
      await store.updateKb(form.value.kbId, payload)
    } else {
      await store.createKb({
        name: form.value.name.trim(),
        description: form.value.description?.trim() || null,
        embeddingCredentialId: form.value.embeddingCredentialId || null,
        embeddingModel: form.value.embeddingModel?.trim() || null,
        chunkSize: form.value.chunkSize,
        chunkOverlap: form.value.chunkOverlap
      })
    }
    ElMessage.success(t('common.savedSuccess'))
    formDialog.value = false
  } catch (err) {
    ElMessage.error(err?.response?.data?.message || err.message || t('agent.knowledge.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function removeKb(kb) {
  try {
    await ElMessageBox.confirm(
      t('agent.knowledge.confirmDeleteKb', { name: kb.name }),
      t('common.delete'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning',
        // Parent dialog forces z-index 10010; ElMessageBox uses the global
        // nextZIndex() counter (starts ~2000), so without this override the
        // confirm box renders behind the manager dialog.
        customClass: 'kb-confirm-on-top',
        modalClass: 'kb-confirm-on-top'
      }
    )
  } catch { return }
  try {
    await store.removeKb(kb.kbId)
    ElMessage.success(t('agent.knowledge.deletedKb'))
  } catch (err) {
    ElMessage.error(err?.response?.data?.message || err.message || t('common.failed'))
  }
}

async function removeDocument(kb, doc) {
  try {
    await ElMessageBox.confirm(
      t('agent.knowledge.confirmDeleteDoc', { name: doc.fileName }),
      t('common.delete'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning',
        customClass: 'kb-confirm-on-top',
        modalClass: 'kb-confirm-on-top'
      }
    )
  } catch { return }
  try {
    await store.removeDocument(kb.kbId, doc.docId)
    ElMessage.success(t('agent.knowledge.deletedDoc'))
  } catch (err) {
    ElMessage.error(err?.response?.data?.message || err.message || t('common.failed'))
  }
}

async function onExpandChange(activeNames) {
  // El-Collapse's v-model can be array or string depending on the accordion
  // mode; normalise to array.
  const names = Array.isArray(activeNames) ? activeNames : [activeNames]
  for (const kbId of names) {
    if (!kbId) continue
    if (!store.documentsByKb[kbId]) {
      try {
        loadingByKb.value = { ...loadingByKb.value, [kbId]: true }
        await store.fetchDocuments(kbId)
      } finally {
        loadingByKb.value = { ...loadingByKb.value, [kbId]: false }
      }
    }
  }
}

function beforeUpload(kb, file) {
  const ext = (file.name.split('.').pop() || '').toLowerCase()
  if (!KB_ALLOWED_EXT.includes(ext)) {
    ElMessage.error(t('agent.knowledge.unsupportedExt', { ext, allowed: KB_ALLOWED_EXT.join(', ') }))
    return false
  }
  if (file.size > KB_FILE_MAX_BYTES) {
    ElMessage.error(t('agent.knowledge.fileTooLarge', { max: '50 MB' }))
    return false
  }
  return true
}

async function doUpload(kb, opts) {
  try {
    await store.uploadDocument(kb.kbId, opts.file, evt => {
      if (typeof opts.onProgress === 'function' && evt?.total) {
        opts.onProgress({ percent: Math.round((evt.loaded * 100) / evt.total) })
      }
    })
    if (typeof opts.onSuccess === 'function') opts.onSuccess()
    // Make sure the row we just added is visible.
    if (!expandedKbIds.value.includes(kb.kbId)) {
      expandedKbIds.value = [...expandedKbIds.value, kb.kbId]
    }
    ElMessage.success(t('agent.knowledge.uploadAccepted'))
  } catch (err) {
    if (typeof opts.onError === 'function') opts.onError(err)
    ElMessage.error(err?.response?.data?.message || err.message || t('agent.knowledge.uploadFailed'))
  }
}

function statusLabel(status) {
  switch (status) {
    case 'PENDING': return t('agent.knowledge.statusPending')
    case 'PROCESSING': return t('agent.knowledge.statusProcessing')
    case 'READY': return t('agent.knowledge.statusReady')
    case 'FAILED': return t('agent.knowledge.statusFailed')
    default: return status || '—'
  }
}

function statusTagType(status) {
  switch (status) {
    case 'READY': return 'success'
    case 'FAILED': return 'danger'
    case 'PROCESSING': return 'warning'
    case 'PENDING':
    default: return 'info'
  }
}

function formatSize(bytes) {
  if (bytes == null) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let v = bytes, i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(v >= 10 || i === 0 ? 0 : 1)} ${units[i]}`
}

function onDialogClose() {
  store.cancelAllPolls()
}
</script>

<style scoped>
.kb-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 12px;
}

.kb-help {
  flex: 1;
  margin: 0;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.kb-row-header {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.kb-name {
  font-size: 15px;
}

.kb-counters {
  flex: 1;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.kb-body {
  padding: 4px 8px 8px;
}

.kb-desc {
  margin: 0 0 12px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.5;
}

.kb-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.kb-format-hint {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.kb-upload-button :deep(.el-upload) {
  display: inline-block;
}

.kb-doc-table {
  margin-top: 4px;
}

.cell-filename {
  word-break: break-all;
}

.form-hint {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
  line-height: 1.5;
}

.status-spinner {
  display: inline-block;
  margin-right: 4px;
  animation: kb-spin 1.4s linear infinite;
}

@keyframes kb-spin {
  from { transform: rotate(0deg); }
  to   { transform: rotate(360deg); }
}
</style>

<style>
/*
  ElMessageBox is teleported to <body>, so the rule cannot be scoped.
  The manager dialog is pinned at z-index 10010 / 10020; the confirm overlay
  must render above both.

  Why :has(): in Element Plus 2.x the inline z-index from nextZIndex() is set
  on the .el-overlay-message-box wrapper, NOT the inner .el-message-box that
  customClass targets. Without overriding the wrapper, the overlay still
  renders at ~2000 and gets covered by the manager dialog at 10010.
*/
.kb-confirm-on-top,
.el-overlay-message-box:has(.kb-confirm-on-top),
.el-overlay:has(.kb-confirm-on-top) {
  z-index: 20000 !important;
}
</style>

<template>
  <!-- Prism settings: companion role + 3D model + motions + model credential.
       Moved out of the floating companion panel so the role has one home. -->
  <div class="role-settings">
    <div v-if="!activeRole" class="nx-empty">{{ $t('companion.emptyRole') }}</div>

    <template v-else>
      <section class="role-section">
        <div class="role-section-head">
          <span class="material-symbols-outlined">badge</span>
          <span>{{ $t('companion.roleSection') }}</span>
        </div>
        <div class="role-current">
          <div class="role-avatar">
            <img v-if="activeRole.avatarUrl" :src="activeRole.avatarUrl" alt="" />
            <span v-else class="material-symbols-outlined">person</span>
          </div>
          <div class="role-current-meta">
            <div class="role-current-name">{{ activeRole.name || activeRole.nickname || $t('companion.defaultName') }}</div>
            <div class="role-current-sub">{{ activeRole.persona || activeRole.description || $t('companion.defaultStatus') }}</div>
          </div>
        </div>
      </section>

      <section class="role-section">
        <div class="role-section-head">
          <span class="material-symbols-outlined">view_in_ar</span>
          <span>{{ $t('companion.model3d') }}</span>
        </div>

        <div class="role-field">
          <label>{{ $t('companion.modelUrl') }}</label>
          <input v-model="roleModelUrl" class="role-input" type="text" placeholder="/models/SpringSnow.vrm" />
        </div>

        <div class="role-field">
          <label>{{ $t('companion.modelType') }}</label>
          <select v-model="roleModelType" class="role-select">
            <option value="vrm">VRM</option>
            <option value="gltf">GLB / GLTF</option>
            <option value="fbx">FBX</option>
          </select>
        </div>

        <div class="role-actions">
          <button class="role-btn role-btn--primary" type="button" @click="saveRoleModel">
            {{ $t('companion.saveModel') }}
          </button>
          <button class="role-btn" type="button" @click="modelFileInput?.click()">
            {{ $t('companion.importModel') }}
          </button>
        </div>
        <p class="role-help">{{ $t('companion.modelHelp') }}</p>
        <input ref="modelFileInput" type="file" accept=".vrm" class="role-hidden-input" @change="onModelFile" />

        <div v-if="uploadedModels.length" class="role-subhead">{{ $t('companion.modelLibrary') }}</div>
        <div v-if="uploadedModels.length" class="role-list">
          <div
            v-for="model in uploadedModels"
            :key="model.fileUrl"
            class="role-row"
            :class="{ 'is-active': model.fileUrl === roleModelUrl }"
            role="button"
            tabindex="0"
            @click="switchModel(model)"
            @keydown.enter="switchModel(model)"
          >
            <input
              v-if="renamingModelUrl === model.fileUrl"
              v-model="renamingValue"
              class="role-input role-rename"
              type="text"
              @click.stop
              @keyup.enter="confirmRenameModel(model)"
              @keyup.escape="renamingModelUrl = null"
              @blur="confirmRenameModel(model)"
            />
            <span v-else class="role-row-name" @dblclick.stop="startRenameModel(model)">{{ model.fileName }}</span>
            <span v-if="model.fileUrl === roleModelUrl" class="role-chip">{{ $t('companion.modelInUse') }}</span>
          </div>
        </div>
      </section>

      <section class="role-section">
        <div class="role-section-head">
          <span class="material-symbols-outlined">animation</span>
          <span>{{ $t('companion.localMotions') }}</span>
        </div>
        <div class="role-actions">
          <button class="role-btn" type="button" @click="motionFileInput?.click()">
            {{ $t('companion.importMotion') }}
          </button>
        </div>
        <p v-if="!localMotions.length" class="role-help">{{ $t('companion.motionEmpty') }}</p>
        <div v-else class="role-list">
          <div
            v-for="motion in localMotions"
            :key="motion.key"
            class="role-row"
            :class="{ 'is-active': motion.key === activeMotionKey }"
            role="button"
            tabindex="0"
            @click="switchMotion(motion)"
            @keydown.enter="switchMotion(motion)"
          >
            <input
              v-if="renamingMotionKey === motion.key"
              v-model="renamingValue"
              class="role-input role-rename"
              type="text"
              @click.stop
              @keyup.enter="confirmRenameMotion(motion)"
              @keyup.escape="renamingMotionKey = null"
              @blur="confirmRenameMotion(motion)"
            />
            <span v-else class="role-row-name" @dblclick.stop="startRenameMotion(motion)">
              {{ motion.label || motion.labelEn || motion.key }}
            </span>
            <span v-if="motion.key === activeMotionKey" class="role-chip">{{ $t('companion.motionInUse') }}</span>
            <button
              v-if="motion.uploaded"
              class="role-row-remove"
              type="button"
              :title="$t('companion.delete')"
              @click.stop="removeMotion(motion)"
            >
              <span class="material-symbols-outlined">delete</span>
            </button>
          </div>
        </div>
        <input ref="motionFileInput" type="file" accept=".fbx" class="role-hidden-input" @change="onMotionFile" />
      </section>

      <section class="role-section">
        <div class="role-section-head">
          <span class="material-symbols-outlined">key</span>
          <span>{{ $t('companion.modelCredential') }}</span>
        </div>
        <div class="role-field">
          <label>{{ $t('companion.apiKey') }}</label>
          <input v-model="apiKey" class="role-input" type="password" placeholder="sk-..." autocomplete="off" />
        </div>
        <div class="role-field">
          <label>{{ $t('companion.endpoint') }}</label>
          <input v-model="endpoint" class="role-input" type="text" placeholder="https://api.example.com" />
        </div>
        <div class="role-field">
          <label>{{ $t('companion.modelName') }}</label>
          <input v-model="modelName" class="role-input" type="text" placeholder="gpt-3.5-turbo" />
        </div>
        <div class="role-actions">
          <button class="role-btn role-btn--primary" type="button" @click="saveCredential">
            {{ $t('companion.saveKey') }}
          </button>
          <button class="role-btn" type="button" @click="bindModel">
            {{ $t('companion.bindModel') }}
          </button>
        </div>
        <p v-if="modelStatus" class="role-help">
          {{ $t('companion.keyStatus') }}: {{ modelStatus.status }}
          <span v-if="modelStatus.maskedKey">({{ modelStatus.maskedKey }})</span>
        </p>
      </section>
    </template>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCompanionStore } from '@/stores/companion'
import { useUserStore } from '@/stores/user'
import { companionAPI } from '@/services/api'

const { t } = useI18n()
const userStore = useUserStore()
const companionStore = useCompanionStore()
const { roles, activeRoleId, activeRole, modelStatus } = storeToRefs(companionStore)

const roleModelUrl = ref('')
const roleModelType = ref('vrm')
const apiKey = ref('')
const endpoint = ref('')
const modelName = ref('')
const localMotions = ref([])
const uploadedModels = ref([])
const activeMotionKey = ref('walk')
const renamingModelUrl = ref(null)
const renamingMotionKey = ref(null)
const renamingValue = ref('')
const modelFileInput = ref(null)
const motionFileInput = ref(null)

const userId = () => userStore.currentUser?.id

onMounted(async () => {
  if (!userId()) return
  await companionStore.ensureInitialized(userId())
  await Promise.all([loadMotions(), loadModels(), companionStore.fetchModelStatus(userId())])
  syncRoleModel()
})

watch(activeRole, syncRoleModel)

function syncRoleModel() {
  const role = activeRole.value
  if (!role) return
  roleModelUrl.value = role.modelUrl || '/models/SpringSnow.vrm'
  roleModelType.value = role.modelType || 'vrm'
}

async function loadMotions() {
  try {
    const response = await companionAPI.getMotionAssets()
    localMotions.value = Array.isArray(response.data?.motions) ? response.data.motions : []
  } catch (error) {
    console.error('Fetch motion assets failed:', error)
  }
}

async function loadModels() {
  try {
    const response = await companionAPI.getModelAssets()
    uploadedModels.value = Array.isArray(response.data) ? response.data : []
  } catch (error) {
    console.error('Fetch model assets failed:', error)
  }
}

// applyRoleModel persists the role's model and keeps the local role list in sync,
// so the on-screen widget picks the new model up without a reload.
async function applyRoleModel(payload) {
  const roleId = activeRoleId.value
  const updated = await companionStore.updateRole(userId(), roleId, payload)
  const index = roles.value.findIndex((r) => r.id === roleId)
  if (index < 0) return
  roles.value.splice(index, 1, updated || { ...roles.value[index], ...payload })
}

async function saveRoleModel() {
  const url = roleModelUrl.value.trim()
  if (!url) {
    ElMessage.error(t('companion.modelUrlRequired'))
    return
  }
  await applyRoleModel({ modelUrl: url, modelType: roleModelType.value || 'vrm' })
  ElMessage.success(t('companion.modelSaved'))
}

async function onModelFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.vrm')) {
    ElMessage.error(t('companion.modelFileInvalid'))
    return
  }
  const existing = uploadedModels.value.find((m) => m.fileName === file.name)
  if (existing) {
    await switchModel(existing)
    ElMessage.success(t('companion.modelAlreadyExists'))
    return
  }
  try {
    const upload = await companionAPI.uploadModelAsset(file)
    const fileUrl = upload.data?.fileUrl
    if (!fileUrl) throw new Error('Missing fileUrl')
    await applyRoleModel({ modelUrl: fileUrl, modelType: 'vrm' })
    roleModelUrl.value = fileUrl
    roleModelType.value = 'vrm'
    await loadModels()
    ElMessage.success(t('companion.modelFileApplied'))
  } catch (error) {
    console.error('Model upload failed:', error)
    ElMessage.error(t('companion.modelSaveFailed'))
  }
}

async function switchModel(model) {
  if (!model?.fileUrl || model.fileUrl === roleModelUrl.value) return
  await applyRoleModel({ modelUrl: model.fileUrl, modelType: 'vrm' })
  roleModelUrl.value = model.fileUrl
  roleModelType.value = 'vrm'
  ElMessage.success(t('companion.modelSwitched'))
}

function startRenameModel(model) {
  renamingModelUrl.value = model.fileUrl
  renamingValue.value = model.fileName.replace(/\.vrm$/i, '')
}

async function confirmRenameModel(model) {
  const raw = renamingValue.value.trim()
  renamingModelUrl.value = null
  if (!raw || raw === model.fileName.replace(/\.vrm$/i, '')) return
  const nextName = raw.endsWith('.vrm') ? raw : `${raw}.vrm`
  try {
    const response = await companionAPI.renameModelAsset(model.fileName, nextName)
    if (model.fileUrl === roleModelUrl.value && response.data?.newFileUrl) {
      await applyRoleModel({ modelUrl: response.data.newFileUrl, modelType: 'vrm' })
      roleModelUrl.value = response.data.newFileUrl
    }
    await loadModels()
    ElMessage.success(t('companion.modelRenamed'))
  } catch (error) {
    console.error('Rename model failed:', error)
    ElMessage.error(t('companion.renameFailed'))
  }
}

async function onMotionFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.fbx')) {
    ElMessage.error(t('companion.motionFileInvalid'))
    return
  }
  try {
    await companionAPI.uploadMotionAsset(file)
    await loadMotions()
    window.dispatchEvent(new CustomEvent('companion:motions-updated'))
    ElMessage.success(t('companion.motionAdded'))
  } catch (error) {
    console.error('Motion upload failed:', error)
    ElMessage.error(t('companion.motionUploadFailed'))
  }
}

function switchMotion(motion) {
  if (!motion?.key) return
  activeMotionKey.value = motion.key
  window.dispatchEvent(new CustomEvent('companion:switch-motion', { detail: { key: motion.key } }))
}

function startRenameMotion(motion) {
  renamingMotionKey.value = motion.key
  renamingValue.value = motion.label || motion.labelEn || motion.key
}

async function confirmRenameMotion(motion) {
  const raw = renamingValue.value.trim()
  renamingMotionKey.value = null
  if (!raw || raw === (motion.label || motion.labelEn || motion.key)) return
  try {
    await companionAPI.renameMotionAsset(motion.key, raw)
    await loadMotions()
    window.dispatchEvent(new CustomEvent('companion:motions-updated'))
    ElMessage.success(t('companion.motionRenamed'))
  } catch (error) {
    console.error('Rename motion failed:', error)
    ElMessage.error(t('companion.renameFailed'))
  }
}

async function removeMotion(motion) {
  try {
    await ElMessageBox.confirm(
      t('companion.motionDeleteConfirm', { name: motion.label || motion.key }),
      t('common.delete'),
      { confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel'), type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await companionAPI.deleteMotionAsset(motion.key)
    await loadMotions()
    window.dispatchEvent(new CustomEvent('companion:motions-updated'))
    ElMessage.success(t('companion.motionRemoved'))
  } catch (error) {
    console.error('Remove motion failed:', error)
    ElMessage.error(t('companion.motionRemoveFailed'))
  }
}

async function saveCredential() {
  if (!apiKey.value.trim()) return
  await companionStore.saveCredential(userId(), {
    provider: 'openai-compatible',
    apiKey: apiKey.value.trim()
  })
  apiKey.value = ''
  ElMessage.success(t('common.savedSuccess'))
}

async function bindModel() {
  if (!activeRoleId.value) return
  await companionStore.bindModel(userId(), activeRoleId.value, {
    provider: 'openai-compatible',
    modelName: modelName.value.trim(),
    endpoint: endpoint.value.trim()
  })
  ElMessage.success(t('common.savedSuccess'))
}
</script>

<style scoped>
.role-settings {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.role-section {
  padding: 14px 16px;
  border-radius: 12px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.3);
}

.role-section-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  color: #ffffff;
  font-size: 13.5px;
  font-weight: 500;
}

.role-section-head .material-symbols-outlined {
  font-size: 18px;
  color: #c4c7c8;
}

.role-current {
  display: flex;
  align-items: center;
  gap: 12px;
}

.role-avatar {
  width: 44px;
  height: 44px;
  border-radius: 9999px;
  overflow: hidden;
  background: #2a2a2d;
  border: 1px solid rgba(68, 71, 72, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c4c7c8;
  flex-shrink: 0;
}

.role-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  filter: grayscale(1);
}

.role-current-meta {
  min-width: 0;
}

.role-current-name {
  color: #e4e1e6;
  font-size: 13.5px;
  font-weight: 500;
}

.role-current-sub {
  margin-top: 2px;
  color: #8e9192;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-field {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.role-field label {
  flex: 0 0 88px;
  color: #8e9192;
  font-size: 12.5px;
}

.role-input,
.role-select {
  flex: 1;
  min-width: 0;
  height: 32px;
  padding: 0 10px;
  border-radius: 4px;
  background: #131316;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #e4e1e6;
  font-family: inherit;
  font-size: 12.5px;
  outline: none;
  transition: border-color 0.15s;
}

.role-input:focus,
.role-select:focus {
  border-color: #8e9192;
}

.role-input::placeholder {
  color: #6f7274;
}

.role-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
}

.role-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 12px;
  border-radius: 4px;
  border: 1px solid rgba(68, 71, 72, 0.3);
  background: transparent;
  color: #e4e1e6;
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.role-btn:hover {
  background: #1f1f22;
  border-color: #444748;
}

.role-btn--primary {
  background: #ffffff;
  border-color: #ffffff;
  color: #131316;
}

.role-btn--primary:hover {
  background: #ffffff;
  opacity: 0.9;
}

.role-help {
  margin: 8px 0 0;
  color: #8e9192;
  font-size: 11.5px;
  line-height: 1.6;
}

.role-subhead {
  margin-top: 14px;
  margin-bottom: 6px;
  color: #8e9192;
  font-size: 11.5px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.role-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.role-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 4px;
  border: 1px solid transparent;
  color: #c4c7c8;
  font-size: 12.5px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.role-row:hover {
  background: #1f1f22;
}

.role-row.is-active {
  background: #2a2a2d;
  border-color: rgba(68, 71, 72, 0.4);
  color: #ffffff;
}

.role-row-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-rename {
  height: 26px;
  flex: 1;
}

.role-chip {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  background: #131316;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #e4e1e6;
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
  font-size: 10px;
}

.role-row-remove {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #8e9192;
  cursor: pointer;
}

.role-row-remove:hover {
  background: #131316;
  color: #ffb4ab;
}

.role-row-remove .material-symbols-outlined {
  font-size: 15px;
}

.role-hidden-input {
  display: none;
}

.nx-empty {
  padding: 32px 0;
  text-align: center;
  color: #8e9192;
  font-size: 13px;
}
</style>

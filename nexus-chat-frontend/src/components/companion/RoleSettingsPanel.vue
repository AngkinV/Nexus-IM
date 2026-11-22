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
            <div class="role-current-name">{{ activeRole.name || $t('companion.defaultName') }}</div>
            <div class="role-current-sub">{{ activeRole.presence || activeRole.tone || $t('companion.defaultStatus') }}</div>
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
          <button class="role-btn role-btn--primary" type="button" :disabled="savingModel" :aria-busy="savingModel" @click="saveRoleModel">
            {{ $t('companion.saveModel') }}
          </button>
          <button class="role-btn" type="button" :disabled="savingModel" @click="modelFileInput?.click()">
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
            :aria-disabled="savingModel"
            :tabindex="savingModel ? -1 : 0"
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
        <button class="role-btn" type="button" @click="modelPanelOpen = true">{{ $t('companion.modelSettings.trigger') }}</button>
        <ModelSettingsPanel v-model:open="modelPanelOpen" />
      </section>
    </template>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCompanionStore } from '@/stores/companion'
import { useUserStore } from '@/stores/user'
import { companionAPI } from '@/services/api'
import ModelSettingsPanel from './ModelSettingsPanel.vue'
import { modelAssetType } from './modelAssetType'
import { useRoleModelTarget } from '@/composables/useRoleModelTarget'

const { t } = useI18n()
const userStore = useUserStore()
const companionStore = useCompanionStore()
const { activeRoleId, activeRole } = storeToRefs(companionStore)
const savingModel = ref(false)
let alive = true, modelReadVersion = 0, motionReadVersion = 0
const modelContext = useRoleModelTarget({ store: companionStore,
  getUserId: () => userStore.currentUser?.id, getRoleId: () => activeRoleId.value })
const target = modelContext.capture
const ownerCurrent = modelContext.isOwner
const targetCurrent = modelContext.isCurrent
onBeforeUnmount(() => { alive = false; modelContext.dispose(); modelReadVersion += 1; motionReadVersion += 1 })

const roleModelUrl = ref('')
const roleModelType = ref('vrm')
const modelPanelOpen = ref(false)
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
  const user = userId(), epoch = companionStore.ownerEpoch
  if (!user) return
  await companionStore.ensureInitialized(user)
  if (!alive || epoch !== companionStore.ownerEpoch || !companionStore.isUserCurrent(user)) return
  await Promise.all([loadMotions(), loadModels()])
  syncRoleModel()
})

watch(activeRole, syncRoleModel)
watch(() => companionStore.ownerEpoch, () => {
  modelReadVersion += 1
  motionReadVersion += 1
  uploadedModels.value = []
  localMotions.value = []
  savingModel.value = false
  modelPanelOpen.value = false
  renamingModelUrl.value = null
  renamingMotionKey.value = null
  renamingValue.value = ''
}, { flush: 'sync' })

function syncRoleModel() {
  const role = activeRole.value
  if (!role) {
    roleModelUrl.value = ''
    roleModelType.value = 'vrm'
    return
  }
  roleModelUrl.value = role.modelUrl || (role.kind === 'guest' ? '' : '/models/SpringSnow.vrm')
  roleModelType.value = role.modelType || 'vrm'
}

async function loadMotions() {
  const request = target(), version = ++motionReadVersion
  try {
    const response = await companionAPI.getMotionAssets()
    if (!targetCurrent(request) || version !== motionReadVersion) return
    localMotions.value = Array.isArray(response.data?.motions) ? response.data.motions : []
  } catch (error) {
    if (targetCurrent(request)) console.error('Fetch motion assets failed:', error)
  }
}

async function loadModels() {
  const request = target(), version = ++modelReadVersion
  try {
    const response = await companionAPI.getModelAssets()
    if (!targetCurrent(request) || version !== modelReadVersion) return
    uploadedModels.value = Array.isArray(response.data) ? response.data : []
  } catch (error) {
    if (targetCurrent(request)) ElMessage.error(t('companion.modelSaveFailed'))
  }
}

// applyRoleModel persists the role's model and keeps the local role list in sync,
// so the on-screen widget picks the new model up without a reload.
async function applyRoleModel(payload, request = target()) {
  if (!targetCurrent(request)) return false
  // The store already applies the response with preview/owner guards. Replacing
  // it a second time here would undo those guards after a concurrent reply.
  await companionStore.updateRole(request.userId, request.roleId, payload)
  return targetCurrent(request)
}

async function saveRoleModel() {
  const request = target(), url = roleModelUrl.value.trim(), type = roleModelType.value || 'vrm'
  if (savingModel.value || !targetCurrent(request)) return
  if (!url) {
    ElMessage.error(t('companion.modelUrlRequired'))
    return
  }
  savingModel.value = true
  try {
    if (await applyRoleModel({ modelUrl: url, modelType: type }, request)) ElMessage.success(t('companion.modelSaved'))
  } catch {
    if (targetCurrent(request)) ElMessage.error(t('companion.modelSaveFailed'))
  } finally {
    if (request.epoch === companionStore.ownerEpoch) savingModel.value = false
  }
}

async function onModelFile(event) {
  const file = event.target.files?.[0], request = target()
  event.target.value = ''
  if (!file || savingModel.value || !targetCurrent(request)) return
  if (!file.name.toLowerCase().endsWith('.vrm')) {
    ElMessage.error(t('companion.modelFileInvalid'))
    return
  }
  savingModel.value = true
  try {
    // The server assigns a unique name. A matching filename is not proof that
    // a different upload contains the same model.
    const upload = await companionAPI.uploadModelAsset(file)
    if (!targetCurrent(request)) return
    const fileUrl = upload.data?.fileUrl
    if (!fileUrl) throw new Error('Missing fileUrl')
    if (!await applyRoleModel({ modelUrl: fileUrl, modelType: 'vrm' }, request)) return
    roleModelUrl.value = fileUrl
    roleModelType.value = 'vrm'
    await loadModels()
    if (targetCurrent(request)) ElMessage.success(t('companion.modelFileApplied'))
  } catch {
    if (targetCurrent(request)) ElMessage.error(t('companion.modelSaveFailed'))
  } finally {
    if (request.epoch === companionStore.ownerEpoch) savingModel.value = false
  }
}

async function switchModel(model) {
  const request = target(), type = modelAssetType(model)
  if (savingModel.value || !targetCurrent(request) || !model?.fileUrl || model.fileUrl === roleModelUrl.value) return
  if (!type) { ElMessage.error(t('companion.modelFileInvalid')); return }
  savingModel.value = true
  try {
    if (!await applyRoleModel({ modelUrl: model.fileUrl, modelType: type }, request)) return
    roleModelUrl.value = model.fileUrl
    roleModelType.value = type
    ElMessage.success(t('companion.modelSwitched'))
  } catch {
    if (targetCurrent(request)) ElMessage.error(t('companion.modelSaveFailed'))
  } finally {
    if (request.epoch === companionStore.ownerEpoch) savingModel.value = false
  }
}

function startRenameModel(model) {
  if (savingModel.value) return
  renamingModelUrl.value = model.fileUrl
  renamingValue.value = model.fileName.replace(/\.(vrm|glb|gltf|fbx)$/i, '')
}

async function confirmRenameModel(model) {
  if (renamingModelUrl.value !== model.fileUrl || savingModel.value) return
  const request = target(), raw = renamingValue.value.trim()
  renamingModelUrl.value = null
  if (!targetCurrent(request) || !raw || raw === model.fileName.replace(/\.(vrm|glb|gltf|fbx)$/i, '')) return
  const extension = model.fileName.match(/\.(vrm|glb|gltf|fbx)$/i)?.[0]
  if (!extension) return
  const nextName = raw.toLowerCase().endsWith(extension.toLowerCase()) ? raw : `${raw}${extension}`
  savingModel.value = true
  try {
    const response = await companionAPI.renameModelAsset(model.fileName, nextName)
    // The file is shared: even if the form moved to another role (or unmounted),
    // its acknowledged rename must still update this account's cached URLs.
    if (!ownerCurrent(request)) return
    if (!response.data?.newFileUrl) throw new Error('Missing renamed model URL')
    companionStore.syncRenamedModel(request.userId, model.fileName, response.data.newFileUrl)
    await companionStore.fetchRoles(request.userId)
    if (alive && ownerCurrent(request)) await loadModels()
    if (targetCurrent(request)) ElMessage.success(t('companion.modelRenamed'))
  } catch {
    if (targetCurrent(request)) ElMessage.error(t('companion.renameFailed'))
  } finally {
    if (request.epoch === companionStore.ownerEpoch) savingModel.value = false
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

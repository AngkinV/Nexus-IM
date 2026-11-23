<template>
  <!-- Prism settings: companion role + 3D model + motions + model credential.
       Moved out of the floating companion panel so the role has one home. -->
  <div class="role-settings">
    <div v-if="panelLoading" class="nx-empty">{{ $t('common.loading') }}</div>
    <div v-else-if="panelError" class="nx-empty">{{ $t('settings.roleLoadFailed') }} <button type="button" class="role-btn" @click="loadPanel">{{ $t('common.retry') }}</button></div>
    <div v-else-if="!activeRole" class="nx-empty">{{ $t('companion.emptyRole') }}</div>

    <template v-else>
      <p v-if="libraryError" class="role-help">{{ $t('settings.assetLoadFailed') }}</p>
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
            :class="{ 'is-active': model.fileUrl === activeRole.modelUrl }"
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
            <span v-if="model.fileUrl === activeRole.modelUrl" class="role-chip">{{ $t('companion.modelInUse') }}</span>
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
              v-if="motion.owned"
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
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCompanionStore } from '@/stores/companion'
import { useUserStore } from '@/stores/user'
import { useSettingsStore } from '@/stores/settings'
import { userAPI, companionAPI } from '@/services/api'
import ModelSettingsPanel from './ModelSettingsPanel.vue'
import { useRoleModelTarget } from '@/composables/useRoleModelTarget'

const { t } = useI18n()
const userStore = useUserStore()
const companionStore = useCompanionStore()
const settings = useSettingsStore()
const { roles, activeRoleId, activeRole } = storeToRefs(companionStore)

const roleModelUrl = ref('')
const roleModelType = ref('vrm')
const modelPanelOpen = ref(false)
const localMotions = ref([])
const uploadedModels = ref([])
// The pet's motion is remembered in the user's settings ('' = its default walk).
const activeMotionKey = computed(() => settings.petMotion || 'walk')
const renamingModelUrl = ref(null)
const renamingMotionKey = ref(null)
const renamingValue = ref('')
const modelFileInput = ref(null)
const motionFileInput = ref(null)

const panelLoading = ref(true)
const panelError = ref(false)
const libraryError = ref(false)
let panelDisposed = false
const savingModel = ref(false)
const userId = () => userStore.currentUser?.id
// A bound request belongs to one selection of one role: leaving the role and
// coming back to it does not make an older request current again.
const modelTarget = useRoleModelTarget({ store: companionStore, getUserId: userId, getRoleId: () => activeRoleId.value })
const context = () => ({ owner: userId(), session: userStore.token, roleId: activeRoleId.value, target: modelTarget.capture() })
const current = (ctx, bound = false) => !!ctx?.owner && !!ctx.session && !panelDisposed && userId() === ctx.owner &&
  userStore.token === ctx.session && (!bound || modelTarget.isCurrent(ctx.target))


async function loadPanel() {
  const owner = userId()
  if (!owner) return
  panelLoading.value = true
  panelError.value = false
  libraryError.value = false
  try {
    await companionStore.ensureInitialized(owner)
    const response = await companionAPI.getRoles(owner)
    if (panelDisposed || userId() !== owner) return
    if (!Array.isArray(response.data)) throw new Error('Invalid role response')
    roles.value = response.data // server data, not stale local model overrides
    companionStore.rolesOwnerId = owner
    await Promise.all([loadMotions(), loadModels()])
    syncRoleModel()
  } catch {
    if (!panelDisposed && userId() === owner) panelError.value = true
  } finally { panelLoading.value = false }
}
onMounted(loadPanel)
onBeforeUnmount(() => { panelDisposed = true; modelTarget.dispose() })

watch(activeRole, syncRoleModel)
// Another account's library and half-finished edits must not stay on screen.
watch(() => companionStore.ownerEpoch, () => {
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
  // A guest invited without a model has none; the default belongs to regulars.
  roleModelUrl.value = role.modelUrl || (role.kind === 'guest' ? '' : '/models/SpringSnow.vrm')
  roleModelType.value = role.modelType || 'vrm'
}

async function loadMotions(ctx = context()) {
  try {
    const response = await userAPI.getSettingMotions(ctx)
    if (!current(ctx)) return
    if (!Array.isArray(response.data?.motions)) throw new Error('Invalid motion library response')
    localMotions.value = response.data.motions
  } catch (error) {
    if (current(ctx)) libraryError.value = true
  }
}

async function loadModels(ctx = context()) {
  try {
    const response = await userAPI.getSettingModels(ctx)
    if (!current(ctx)) return
    if (!Array.isArray(response.data)) throw new Error('Invalid model library response')
    uploadedModels.value = response.data
  } catch (error) {
    if (current(ctx)) libraryError.value = true
  }
}

// applyRoleModel persists the role's model. The store puts the server's answer
// in the list with its preview and owner guards, so the on-screen widget picks
// the new model up without a reload; replacing the row again here would undo
// those guards after a reply that finished while the save was in flight.
async function applyRoleModel(payload, ctx = context()) {
  if (!current(ctx, true)) throw new Error('Account or role changed')
  await companionStore.updateRole(ctx.owner, ctx.roleId, payload)
}

async function saveRoleModel() {
  const ctx = context()
  if (savingModel.value || !current(ctx, true)) return
  const url = roleModelUrl.value.trim()
  if (!url) {
    ElMessage.error(t('companion.modelUrlRequired'))
    return
  }
  savingModel.value = true
  try {
    await applyRoleModel({ modelUrl: url, modelType: roleModelType.value || 'vrm' }, ctx)
    if (!current(ctx, true)) return
    ElMessage.success(t('companion.modelSaved'))
  } catch {
    if (current(ctx)) ElMessage.error(t('companion.modelSaveFailed'))
  } finally {
    if (ctx.target.epoch === companionStore.ownerEpoch) savingModel.value = false
  }
}

async function onModelFile(event) {
  const ctx = context()
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || savingModel.value || !current(ctx, true)) return
  if (!file.name.toLowerCase().endsWith('.vrm')) {
    ElMessage.error(t('companion.modelFileInvalid'))
    return
  }
  savingModel.value = true
  try {
    const upload = await userAPI.uploadSettingAsset('model', file, ctx)
    if (!current(ctx, true)) return
    const fileUrl = upload.data?.fileUrl
    if (!fileUrl) throw new Error('Missing fileUrl')
    await applyRoleModel({ modelUrl: fileUrl, modelType: 'vrm' }, ctx)
    if (!current(ctx, true)) return
    roleModelUrl.value = fileUrl
    roleModelType.value = 'vrm'
    await loadModels(ctx)
    if (!current(ctx)) return
    ElMessage.success(t('companion.modelFileApplied'))
  } catch (error) {
    if (current(ctx)) ElMessage.error(t('companion.modelSaveFailed'))
  } finally {
    if (ctx.target.epoch === companionStore.ownerEpoch) savingModel.value = false
  }
}

async function switchModel(model) {
  const ctx = context()
  if (savingModel.value || !current(ctx, true)) return
  if (!model?.fileUrl || model.fileUrl === activeRole.value?.modelUrl) return
  savingModel.value = true
  try {
    // The settings library lists VRM files only.
    await applyRoleModel({ modelUrl: model.fileUrl, modelType: 'vrm' }, ctx)
    if (!current(ctx, true)) return
    roleModelUrl.value = model.fileUrl
    roleModelType.value = 'vrm'
    ElMessage.success(t('companion.modelSwitched'))
  } catch {
    if (current(ctx)) ElMessage.error(t('companion.modelSaveFailed'))
  } finally {
    if (ctx.target.epoch === companionStore.ownerEpoch) savingModel.value = false
  }
}

function startRenameModel(model) {
  if (!model.owned) return
  renamingModelUrl.value = model.fileUrl
  renamingValue.value = model.fileName.replace(/\.vrm$/i, '')
}

async function confirmRenameModel(model) {
  const ctx = context()
  if (!current(ctx)) return
  const raw = renamingValue.value.trim()
  renamingModelUrl.value = null
  if (!raw || raw === model.fileName.replace(/\.vrm$/i, '')) return
  const nextName = raw.endsWith('.vrm') ? raw : `${raw}.vrm`
  try {
    const response = await userAPI.renameSettingAsset('model', model.id, nextName, ctx)
    if (!current(ctx)) return
    // Display-name edits keep the stable fileUrl; existing role bindings need no rewrite.
    if (!response.data?.fileUrl) throw new Error('Invalid asset rename response')
    await loadModels(ctx)
    if (!current(ctx)) return
    ElMessage.success(t('companion.modelRenamed'))
  } catch (error) {
    if (current(ctx)) ElMessage.error(t('companion.renameFailed'))
  }
}

async function onMotionFile(event) {
  const ctx = context()
  if (!current(ctx)) return
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.fbx')) {
    ElMessage.error(t('companion.motionFileInvalid'))
    return
  }
  try {
    await userAPI.uploadSettingAsset('motion', file, ctx)
    if (!current(ctx)) return
    await loadMotions(ctx)
    if (!current(ctx)) return
    window.dispatchEvent(new CustomEvent('companion:motions-updated'))
    ElMessage.success(t('companion.motionAdded'))
  } catch (error) {
    if (current(ctx)) ElMessage.error(t('companion.motionUploadFailed'))
  }
}

function switchMotion(motion) {
  if (!motion?.key || !settings.ready || settings.busy) return
  // Saved first: the pet may be switched off right now, in which case nobody
  // hears the event and the choice applies when it is next shown.
  settings.petMotion = motion.key === 'walk' ? '' : motion.key
  window.dispatchEvent(new CustomEvent('companion:switch-motion', { detail: { key: motion.key } }))
}

function startRenameMotion(motion) {
  if (!motion.owned) return
  renamingMotionKey.value = motion.key
  renamingValue.value = motion.label || motion.labelEn || motion.key
}

async function confirmRenameMotion(motion) {
  const ctx = context()
  if (!current(ctx)) return
  const raw = renamingValue.value.trim()
  renamingMotionKey.value = null
  if (!raw || raw === (motion.label || motion.labelEn || motion.key)) return
  try {
    await userAPI.renameSettingAsset('motion', motion.id, raw, ctx)
    if (!current(ctx)) return
    await loadMotions(ctx)
    if (!current(ctx)) return
    window.dispatchEvent(new CustomEvent('companion:motions-updated'))
    ElMessage.success(t('companion.motionRenamed'))
  } catch (error) {
    if (current(ctx)) ElMessage.error(t('companion.renameFailed'))
  }
}

async function removeMotion(motion) {
  const ctx = context()
  if (!current(ctx)) return
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
    if (!current(ctx)) return
    await userAPI.deleteSettingMotion(motion.id, ctx)
    if (!current(ctx)) return
    if (settings.petMotion === motion.key) settings.petMotion = ''
    await loadMotions(ctx)
    if (!current(ctx)) return
    window.dispatchEvent(new CustomEvent('companion:motions-updated'))
    ElMessage.success(t('companion.motionRemoved'))
  } catch (error) {
    if (current(ctx)) ElMessage.error(t('companion.motionRemoveFailed'))
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

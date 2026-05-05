<template>
  <Teleport to="body">
    <!-- Backdrop: click to close -->
    <Transition name="cp-backdrop">
      <div v-if="drawerVisible" class="cp-backdrop" @click="drawerVisible = false"></div>
    </Transition>

    <!-- Floating window -->
    <Transition name="cp-window">
      <div
        v-if="drawerVisible"
        ref="windowRef"
        class="cp-window"
        :style="windowStyle"
        @mousedown="bringToFront"
      >
        <!-- Resize handles -->
        <div class="cp-resize cp-resize-n" @mousedown.stop="startResize($event, 'n')"></div>
        <div class="cp-resize cp-resize-s" @mousedown.stop="startResize($event, 's')"></div>
        <div class="cp-resize cp-resize-e" @mousedown.stop="startResize($event, 'e')"></div>
        <div class="cp-resize cp-resize-w" @mousedown.stop="startResize($event, 'w')"></div>
        <div class="cp-resize cp-resize-ne" @mousedown.stop="startResize($event, 'ne')"></div>
        <div class="cp-resize cp-resize-nw" @mousedown.stop="startResize($event, 'nw')"></div>
        <div class="cp-resize cp-resize-se" @mousedown.stop="startResize($event, 'se')"></div>
        <div class="cp-resize cp-resize-sw" @mousedown.stop="startResize($event, 'sw')"></div>

        <!-- Title bar (drag handle) -->
        <div class="cp-titlebar" @mousedown.stop="startDrag">
          <div class="cp-titlebar-left">
            <div class="title-row">
              <div class="title">{{ $t('companion.title') }}</div>
              <span class="badge">Beta</span>
            </div>
            <div class="subtitle">{{ status?.summary || $t('companion.defaultStatus') }}</div>
          </div>
          <button class="cp-close-btn" @click="drawerVisible = false" aria-label="Close">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M1 1L13 13M13 1L1 13" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
          </button>
        </div>

        <!-- Role strip -->
        <div class="role-strip">
          <button
            v-for="role in roles"
            :key="role.id"
            class="role-btn"
            :class="{ active: role.id === activeRoleId }"
            @click="selectRole(role)"
          >
            <span class="role-name">{{ role.name }}</span>
          </button>
        </div>

        <!-- Content area -->
        <div class="cp-body">
          <el-tabs v-model="activeTab" class="companion-tabs">
            <el-tab-pane :label="$t('companion.tabs.chat')" name="chat">
              <div class="chat-pane">
                <el-scrollbar class="chat-scroll" max-height="420px">
                  <div
                    v-for="msg in messages"
                    :key="msg.id"
                    class="chat-bubble"
                    :class="msg.senderType === 'user' ? 'from-user' : 'from-role'"
                  >
                    <div class="bubble-content">{{ msg.content }}</div>
                    <div v-if="msg.senderType !== 'user' && msg.fallback" class="bubble-tag">
                      {{ $t('companion.fallback') }}
                    </div>
                  </div>
                </el-scrollbar>

                <div class="chat-input">
                  <el-input
                    v-model="messageInput"
                    :placeholder="$t('companion.inputPlaceholder')"
                    @keyup.enter="handleSend"
                    clearable
                  />
                  <el-button type="primary" @click="handleSend">
                    {{ $t('companion.send') }}
                  </el-button>
                </div>
              </div>
            </el-tab-pane>

            <el-tab-pane :label="$t('companion.tabs.memory')" name="memory">
              <div class="memory-pane">
                <div class="memory-new">
                  <el-select v-model="newMemoryType" size="small" class="memory-select" popper-class="companion-popper">
                    <el-option label="短期" value="short_term" />
                    <el-option label="中期" value="mid" />
                    <el-option label="长期" value="long_term" />
                  </el-select>
                  <el-input v-model="newMemoryContent" :placeholder="$t('companion.memoryPlaceholder')" />
                  <el-button type="primary" size="small" @click="handleAddMemory">
                    {{ $t('companion.saveMemory') }}
                  </el-button>
                </div>

                <div class="memory-list">
                  <div v-if="memories.length === 0" class="empty-state">
                    {{ $t('companion.emptyMemory') }}
                  </div>
                  <div v-for="memory in memories" :key="memory.id" class="memory-card">
                    <div class="memory-content">{{ memory.content }}</div>
                    <div class="memory-meta">
                      <span>{{ memory.type }}</span>
                      <span v-if="memory.confirmed" class="tag confirmed">{{ $t('companion.confirmed') }}</span>
                    </div>
                    <div class="memory-actions">
                      <el-button
                        v-if="!memory.confirmed"
                        size="small"
                        @click="handleConfirmMemory(memory)"
                      >
                        {{ $t('companion.confirm') }}
                      </el-button>
                      <el-button
                        size="small"
                        type="danger"
                        plain
                        @click="handleDeleteMemory(memory)"
                      >
                        {{ $t('companion.delete') }}
                      </el-button>
                    </div>
                  </div>
                </div>
              </div>
            </el-tab-pane>

            <el-tab-pane :label="$t('companion.tabs.growth')" name="growth">
              <div class="growth-pane" v-if="growth">
                <div class="growth-item">
                  <span>{{ $t('companion.intimacy') }}</span>
                  <el-progress :percentage="growth.intimacy" />
                </div>
                <div class="growth-item">
                  <span>{{ $t('companion.trust') }}</span>
                  <el-progress :percentage="growth.trust" status="success" />
                </div>
                <div class="growth-item">
                  <span>{{ $t('companion.stability') }}</span>
                  <el-progress :percentage="growth.stability" status="warning" />
                </div>
                <div class="growth-item">
                  <span>{{ $t('companion.coGrowth') }}</span>
                  <el-progress :percentage="growth.coGrowth" status="success" />
                </div>
              </div>
              <div v-else class="empty-state">{{ $t('companion.emptyGrowth') }}</div>
            </el-tab-pane>

            <el-tab-pane :label="$t('companion.tabs.model')" name="model">
              <div class="model-pane">
                <div class="model-section">
                  <div class="model-title">{{ $t('companion.model3d') }}</div>
                  <div class="model-row">
                    <div class="label">{{ $t('companion.modelUrl') }}</div>
                    <el-input v-model="roleModelUrl" placeholder="/models/SpringSnow.vrm" />
                  </div>
                  <div class="model-row">
                    <div class="label">{{ $t('companion.modelType') }}</div>
                    <el-select v-model="roleModelType" size="small" class="model-select" popper-class="companion-popper">
                      <el-option label="VRM" value="vrm" />
                      <el-option label="GLB/GLTF" value="gltf" />
                      <el-option label="FBX" value="fbx" />
                    </el-select>
                  </div>
                  <div class="model-actions">
                    <el-button type="primary" @click="handleSaveRoleModel">
                      {{ $t('companion.saveModel') }}
                    </el-button>
                  </div>
                  <div class="model-help">{{ $t('companion.modelHelp') }}</div>
                </div>

                <div class="model-section">
                  <div class="model-title">{{ $t('companion.localModel') }}</div>
                  <div class="model-row">
                    <div class="label">{{ $t('companion.localModelFile') }}</div>
                    <div class="inline-actions">
                      <el-button size="small" @click="triggerModelFilePicker">
                        {{ $t('companion.importModel') }}
                      </el-button>
                    </div>
                  </div>
                  <div class="model-help" v-if="localModelInfo">
                    {{ $t('companion.localModelSelected') }} {{ localModelInfo.name }}
                  </div>
                  <div class="model-help" v-else>
                    {{ $t('companion.localModelHint') }}
                  </div>
                  <input
                    ref="modelFileInput"
                    type="file"
                    class="hidden-input"
                    accept=".vrm"
                    @change="handleModelFileChange"
                  />

                  <div class="model-library" v-if="uploadedModels.length > 0">
                    <div class="label" style="margin-top: 10px;">{{ $t('companion.modelLibrary') }}</div>
                    <div class="local-motion-list">
                      <div
                        v-for="model in uploadedModels"
                        :key="model.fileUrl"
                        class="local-motion-item"
                        :class="{ active: model.fileUrl === roleModelUrl }"
                        @click="handleSwitchModel(model)"
                        style="cursor: pointer;"
                      >
                        <input
                          v-if="renamingModelUrl === model.fileUrl"
                          class="inline-rename-input"
                          v-model="renamingValue"
                          @keyup.enter="confirmRenameModel(model)"
                          @blur="confirmRenameModel(model)"
                          @click.stop
                          @keyup.escape="renamingModelUrl = null"
                          v-focus
                        />
                        <div v-else class="local-motion-name" @dblclick.stop="startRenameModel(model)">{{ model.fileName }}</div>
                        <span v-if="model.fileUrl === roleModelUrl" class="tag confirmed">{{ $t('companion.modelInUse') }}</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div class="model-section">
                  <div class="model-title">{{ $t('companion.localMotions') }}</div>
                  <div class="model-row">
                    <div class="label">{{ $t('companion.localMotionFile') }}</div>
                    <div class="inline-actions">
                      <el-button size="small" @click="triggerMotionFilePicker">
                        {{ $t('companion.importMotion') }}
                      </el-button>
                    </div>
                  </div>
                  <div class="model-help" v-if="localMotions.length === 0">
                    {{ $t('companion.motionEmpty') }}
                  </div>
                  <div class="local-motion-list" v-else>
                    <div
                      v-for="motion in localMotions"
                      :key="motion.key"
                      class="local-motion-item"
                      :class="{ active: motion.key === activeMotionKey }"
                      @click="handleSwitchMotion(motion)"
                      style="cursor: pointer;"
                    >
                      <input
                        v-if="renamingMotionKey === motion.key"
                        class="inline-rename-input"
                        v-model="renamingValue"
                        @keyup.enter="confirmRenameMotion(motion)"
                        @blur="confirmRenameMotion(motion)"
                        @click.stop
                        @keyup.escape="renamingMotionKey = null"
                        v-focus
                      />
                      <div v-else class="local-motion-name" @dblclick.stop="startRenameMotion(motion)">{{ motion.label || motion.labelEn || motion.key }}</div>
                      <div class="motion-item-actions">
                        <span v-if="motion.key === activeMotionKey" class="tag confirmed">{{ $t('companion.motionInUse') }}</span>
                        <el-button v-if="motion.uploaded" size="small" text @click.stop="handleRemoveMotion(motion)">
                          {{ $t('companion.delete') }}
                        </el-button>
                      </div>
                    </div>
                  </div>
                  <input
                    ref="motionFileInput"
                    type="file"
                    class="hidden-input"
                    accept=".fbx"
                    @change="handleMotionFileChange"
                  />
                </div>

                <div class="model-divider"></div>

                <div class="model-row">
                  <div class="label">{{ $t('companion.apiKey') }}</div>
                  <el-input v-model="apiKey" placeholder="sk-..." show-password />
                </div>
                <div class="model-row">
                  <div class="label">{{ $t('companion.endpoint') }}</div>
                  <el-input v-model="endpoint" placeholder="https://api.example.com" />
                </div>
                <div class="model-row">
                  <div class="label">{{ $t('companion.modelName') }}</div>
                  <el-input v-model="modelName" placeholder="gpt-3.5-turbo" />
                </div>
                <div class="model-actions">
                  <el-button type="primary" @click="handleSaveCredential">
                    {{ $t('companion.saveKey') }}
                  </el-button>
                  <el-button @click="handleBindModel">
                    {{ $t('companion.bindModel') }}
                  </el-button>
                </div>
                <div class="model-status" v-if="modelStatus">
                  {{ $t('companion.keyStatus') }}: {{ modelStatus.status }}
                  <span v-if="modelStatus.maskedKey">({{ modelStatus.maskedKey }})</span>
                </div>
              </div>
            </el-tab-pane>
          </el-tabs>
        </div>

        <!-- Footer -->
        <div class="panel-footer">
          <div class="status-card">
            <div class="status-avatar">
              <div class="status-initial">{{ roleInitial }}</div>
            </div>
            <div class="status-info">
              <div class="status-title">{{ activeRole?.name || $t('companion.defaultName') }}</div>
              <div class="status-meta">
                <span class="status-dot"></span>
                <span class="status-text">{{ $t('online') }}</span>
              </div>
            </div>
            <button class="icon-btn ghost" @click="handleRefreshStatus" aria-label="Refresh">
              <span class="icon">r</span>
            </button>
          </div>
          <div class="status-sub">{{ status?.summary || $t('companion.defaultStatus') }}</div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { computed, ref, watch, reactive, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useCompanionStore } from '@/stores/companion'
import { useUserStore } from '@/stores/user'
import { companionAPI } from '@/services/api'

const props = defineProps({
  modelValue: Boolean
})

const emit = defineEmits(['update:modelValue'])

const drawerVisible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const companionStore = useCompanionStore()
const userStore = useUserStore()
const { t } = useI18n()

const activeTab = ref('chat')
const messageInput = ref('')
const newMemoryContent = ref('')
const newMemoryType = ref('mid')
const apiKey = ref('')
const endpoint = ref('')
const modelName = ref('')
const roleModelUrl = ref('')
const roleModelType = ref('vrm')
const modelFileInput = ref(null)
const motionFileInput = ref(null)
const localModelInfo = ref(null)
const localMotions = ref([])
const uploadedModels = ref([])
const activeMotionKey = ref('walk')
const renamingModelUrl = ref(null)
const renamingMotionKey = ref(null)
const renamingValue = ref('')
const windowRef = ref(null)

const vFocus = { mounted: (el) => el.focus() }

const { roles, activeRoleId, messages, memories, growth, status, modelStatus } = storeToRefs(companionStore)

// ── Floating window state ──
const MIN_W = 360
const MIN_H = 400
const STORAGE_KEY = 'companionWindowRect'

const loadSavedRect = () => {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY))
    if (saved && saved.x != null) return saved
  } catch {}
  return null
}

const getDefaultRect = () => {
  const w = 420
  const h = 580
  return {
    x: window.innerWidth - w - 40,
    y: window.innerHeight - h - 60,
    w,
    h,
  }
}

const rect = reactive(loadSavedRect() || getDefaultRect())

const saveRect = () => {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({ x: rect.x, y: rect.y, w: rect.w, h: rect.h }))
}

const clampRect = () => {
  const maxX = window.innerWidth - rect.w
  const maxY = window.innerHeight - rect.h
  rect.x = Math.max(0, Math.min(rect.x, maxX))
  rect.y = Math.max(0, Math.min(rect.y, maxY))
  rect.w = Math.max(MIN_W, Math.min(rect.w, window.innerWidth))
  rect.h = Math.max(MIN_H, Math.min(rect.h, window.innerHeight))
}

const windowStyle = computed(() => ({
  left: `${rect.x}px`,
  top: `${rect.y}px`,
  width: `${rect.w}px`,
  height: `${rect.h}px`,
}))

// Reset position when opening if it's off-screen
watch(drawerVisible, (visible) => {
  if (visible) {
    clampRect()
  }
})

// ── Drag logic ──
let dragState = null

const startDrag = (e) => {
  if (e.button !== 0) return
  dragState = { startX: e.clientX, startY: e.clientY, origX: rect.x, origY: rect.y }
  document.addEventListener('mousemove', onDrag)
  document.addEventListener('mouseup', stopDrag)
}

const onDrag = (e) => {
  if (!dragState) return
  const dx = e.clientX - dragState.startX
  const dy = e.clientY - dragState.startY
  rect.x = Math.max(0, Math.min(dragState.origX + dx, window.innerWidth - rect.w))
  rect.y = Math.max(0, Math.min(dragState.origY + dy, window.innerHeight - rect.h))
}

const stopDrag = () => {
  dragState = null
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', stopDrag)
  saveRect()
}

// ── Resize logic ──
let resizeState = null

const startResize = (e, direction) => {
  if (e.button !== 0) return
  resizeState = {
    dir: direction,
    startX: e.clientX,
    startY: e.clientY,
    origX: rect.x,
    origY: rect.y,
    origW: rect.w,
    origH: rect.h,
  }
  document.addEventListener('mousemove', onResize)
  document.addEventListener('mouseup', stopResize)
}

const onResize = (e) => {
  if (!resizeState) return
  const { dir, startX, startY, origX, origY, origW, origH } = resizeState
  const dx = e.clientX - startX
  const dy = e.clientY - startY

  if (dir.includes('e')) {
    rect.w = Math.max(MIN_W, Math.min(origW + dx, window.innerWidth - rect.x))
  }
  if (dir.includes('w')) {
    const newW = Math.max(MIN_W, origW - dx)
    const maxLeftShift = origW - MIN_W
    const actualDx = Math.max(-maxLeftShift, Math.min(dx, origX))
    rect.x = origX + actualDx
    rect.w = origW - actualDx
  }
  if (dir.includes('s')) {
    rect.h = Math.max(MIN_H, Math.min(origH + dy, window.innerHeight - rect.y))
  }
  if (dir.includes('n')) {
    const newH = Math.max(MIN_H, origH - dy)
    const maxTopShift = origH - MIN_H
    const actualDy = Math.max(-maxTopShift, Math.min(dy, origY))
    rect.y = origY + actualDy
    rect.h = origH - actualDy
  }
}

const stopResize = () => {
  resizeState = null
  document.removeEventListener('mousemove', onResize)
  document.removeEventListener('mouseup', stopResize)
  saveRect()
}

const bringToFront = () => {
  // placeholder: if multiple floating windows exist in the future
}

// Handle window resize
const handleWindowResize = () => {
  clampRect()
  saveRect()
}

onMounted(() => {
  window.addEventListener('resize', handleWindowResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleWindowResize)
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', stopDrag)
  document.removeEventListener('mousemove', onResize)
  document.removeEventListener('mouseup', stopResize)
})

// ── Business logic (unchanged) ──

const activeRole = computed(() => {
  return roles.value.find(r => r.id === activeRoleId.value) || roles.value[0] || null
})

const roleInitial = computed(() => {
  const name = activeRole.value?.name || t('companion.defaultName')
  return name?.trim()?.charAt(0) || 'A'
})

const getLocalOverrides = () => {
  try {
    return JSON.parse(localStorage.getItem('companionModelOverrides') || '{}')
  } catch (e) {
    return {}
  }
}

const saveLocalOverride = (roleId, payload) => {
  if (!roleId) return
  const map = getLocalOverrides()
  map[roleId] = payload
  localStorage.setItem('companionModelOverrides', JSON.stringify(map))
}

const fetchServerMotions = async () => {
  try {
    const response = await companionAPI.getMotionAssets()
    const data = response.data || {}
    const motions = Array.isArray(data.motions) ? data.motions : []
    localMotions.value = motions
  } catch (error) {
    console.error('Fetch motion assets failed:', error)
  }
}

const fetchServerModels = async () => {
  try {
    const response = await companionAPI.getModelAssets()
    uploadedModels.value = Array.isArray(response.data) ? response.data : []
  } catch (error) {
    console.error('Fetch model assets failed:', error)
  }
}

const syncRoleModelFields = () => {
  const role = activeRole.value
  if (!role) return
  const overrides = getLocalOverrides()
  let local = overrides[role.id]

  if (local?.modelUrl?.startsWith('asset://')) {
    delete overrides[role.id]
    localStorage.setItem('companionModelOverrides', JSON.stringify(overrides))
    local = null
  }

  const nextUrl = local?.modelUrl || role.modelUrl || '/models/SpringSnow.vrm'
  const nextType = local?.modelType || role.modelType || 'vrm'
  roleModelUrl.value = nextUrl
  roleModelType.value = nextType
  const mName = (local?.modelUrl || role.modelUrl || '').startsWith('/models/')
    ? (local?.modelUrl || role.modelUrl).split('/').pop()
    : null
  localModelInfo.value = mName ? { name: mName } : null

  if (local && (role.modelUrl !== local.modelUrl || role.modelType !== local.modelType)) {
    const idx = roles.value.findIndex(r => r.id === role.id)
    if (idx >= 0) {
      roles.value.splice(idx, 1, { ...role, ...local })
    }
  }
}

const selectRole = async (role) => {
  const userId = userStore.currentUser?.id
  if (!userId) return
  await companionStore.setActiveRole(userId, role.id)
}

const handleSend = async () => {
  const userId = userStore.currentUser?.id
  const roleId = activeRoleId.value
  if (!userId || !roleId || !messageInput.value.trim()) return
  await companionStore.sendMessage(userId, roleId, messageInput.value.trim())
  messageInput.value = ''
}

const handleAddMemory = async () => {
  const userId = userStore.currentUser?.id
  const roleId = activeRoleId.value
  if (!userId || !roleId || !newMemoryContent.value.trim()) return
  await companionStore.createMemory(userId, roleId, newMemoryType.value, newMemoryContent.value.trim())
  newMemoryContent.value = ''
}

const handleConfirmMemory = async (memory) => {
  const userId = userStore.currentUser?.id
  if (!userId) return
  await companionStore.confirmMemory(userId, memory.id)
}

const handleDeleteMemory = async (memory) => {
  const userId = userStore.currentUser?.id
  if (!userId) return
  await companionStore.deleteMemory(userId, memory.id)
}

const handleSaveCredential = async () => {
  const userId = userStore.currentUser?.id
  if (!userId || !apiKey.value.trim()) return
  await companionStore.saveCredential(userId, {
    provider: 'openai-compatible',
    apiKey: apiKey.value.trim()
  })
  apiKey.value = ''
}

const handleBindModel = async () => {
  const userId = userStore.currentUser?.id
  const roleId = activeRoleId.value
  if (!userId || !roleId) return
  await companionStore.bindModel(userId, roleId, {
    provider: 'openai-compatible',
    modelName: modelName.value.trim(),
    endpoint: endpoint.value.trim()
  })
}

const handleSaveRoleModel = async () => {
  const roleId = activeRoleId.value
  const url = roleModelUrl.value.trim()
  if (!roleId || !url) {
    ElMessage.error(t('companion.modelUrlRequired'))
    return
  }

  const payload = {
    modelUrl: url,
    modelType: roleModelType.value || 'vrm'
  }

  let updated = null
  try {
    const userId = userStore.currentUser?.id
    if (userId) {
      updated = await companionStore.updateRole(userId, roleId, payload)
    }
  } catch (error) {
    console.error('Save role model failed:', error)
  }

  const idx = roles.value.findIndex(r => r.id === roleId)
  if (idx >= 0) {
    roles.value.splice(idx, 1, updated || { ...roles.value[idx], ...payload })
  }

  if (updated) {
    const overrides = getLocalOverrides()
    if (overrides[roleId]) {
      delete overrides[roleId]
      localStorage.setItem('companionModelOverrides', JSON.stringify(overrides))
    }
    ElMessage.success(t('companion.modelSaved'))
  } else {
    saveLocalOverride(roleId, payload)
    ElMessage.warning(t('companion.modelSaveFailed'))
  }
}

const triggerModelFilePicker = () => {
  modelFileInput.value?.click?.()
}

const handleModelFileChange = async (event) => {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.vrm')) {
    ElMessage.error(t('companion.modelFileInvalid'))
    return
  }
  const roleId = activeRoleId.value
  if (!roleId) return

  const existing = uploadedModels.value.find(m => m.fileName === file.name)
  if (existing) {
    await handleSwitchModel(existing)
    ElMessage.success(t('companion.modelAlreadyExists'))
    return
  }

  try {
    const upload = await companionAPI.uploadModelAsset(file)
    const fileUrl = upload.data?.fileUrl
    if (!fileUrl) throw new Error('Missing fileUrl')
    const payload = {
      modelUrl: fileUrl,
      modelType: 'vrm'
    }
    const userId = userStore.currentUser?.id
    let updated = null
    if (userId) {
      updated = await companionStore.updateRole(userId, roleId, payload)
    }
    roleModelUrl.value = payload.modelUrl
    roleModelType.value = payload.modelType
    localModelInfo.value = { name: upload.data?.originalName || file.name }
    const idx = roles.value.findIndex(r => r.id === roleId)
    if (idx >= 0) {
      roles.value.splice(idx, 1, updated || { ...roles.value[idx], ...payload })
    }
    const overrides = getLocalOverrides()
    if (overrides[roleId]) {
      delete overrides[roleId]
      localStorage.setItem('companionModelOverrides', JSON.stringify(overrides))
    }
    await fetchServerModels()
    ElMessage.success(t('companion.modelFileApplied'))
  } catch (error) {
    console.error('Model upload failed:', error)
    ElMessage.error(t('companion.modelSaveFailed'))
  }
}

const handleSwitchModel = async (model) => {
  const roleId = activeRoleId.value
  if (!roleId || !model?.fileUrl) return
  if (model.fileUrl === roleModelUrl.value) return

  const payload = {
    modelUrl: model.fileUrl,
    modelType: 'vrm'
  }
  const userId = userStore.currentUser?.id
  let updated = null
  try {
    if (userId) {
      updated = await companionStore.updateRole(userId, roleId, payload)
    }
  } catch (error) {
    console.error('Switch model failed:', error)
  }

  roleModelUrl.value = payload.modelUrl
  roleModelType.value = payload.modelType
  localModelInfo.value = { name: model.fileName }
  const idx = roles.value.findIndex(r => r.id === roleId)
  if (idx >= 0) {
    roles.value.splice(idx, 1, updated || { ...roles.value[idx], ...payload })
  }
  const overrides = getLocalOverrides()
  if (overrides[roleId]) {
    delete overrides[roleId]
    localStorage.setItem('companionModelOverrides', JSON.stringify(overrides))
  }
  ElMessage.success(t('companion.modelSwitched'))
}

const triggerMotionFilePicker = () => {
  motionFileInput.value?.click?.()
}

const handleMotionFileChange = async (event) => {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.fbx')) {
    ElMessage.error(t('companion.motionFileInvalid'))
    return
  }

  const existing = localMotions.value.find(m => m.file === file.name)
  if (existing) {
    handleSwitchMotion(existing)
    ElMessage.success(t('companion.motionAlreadyExists'))
    return
  }

  try {
    await companionAPI.uploadMotionAsset(file)
    await fetchServerMotions()
    window.dispatchEvent(new CustomEvent('companion:motions-updated'))
    ElMessage.success(t('companion.motionAdded'))
  } catch (error) {
    console.error('Motion upload failed:', error)
    ElMessage.error(t('companion.motionUploadFailed'))
  }
}

const handleSwitchMotion = (motion) => {
  if (!motion?.key) return
  activeMotionKey.value = motion.key
  window.dispatchEvent(new CustomEvent('companion:switch-motion', { detail: { key: motion.key } }))
}

const startRenameModel = (model) => {
  renamingModelUrl.value = model.fileUrl
  renamingValue.value = stripExtForDisplay(model.fileName)
}

const confirmRenameModel = async (model) => {
  const raw = renamingValue.value.trim()
  renamingModelUrl.value = null
  if (!raw || raw === stripExtForDisplay(model.fileName)) return
  const newFileName = raw.endsWith('.vrm') ? raw : raw + '.vrm'
  try {
    const res = await companionAPI.renameModelAsset(model.fileName, newFileName)
    const data = res.data
    if (model.fileUrl === roleModelUrl.value && data.newFileUrl) {
      roleModelUrl.value = data.newFileUrl
      const roleId = activeRoleId.value
      const userId = userStore.currentUser?.id
      if (roleId && userId) {
        await companionStore.updateRole(userId, roleId, { modelUrl: data.newFileUrl, modelType: 'vrm' })
      }
    }
    await fetchServerModels()
    ElMessage.success(t('companion.modelRenamed'))
  } catch (error) {
    console.error('Rename model failed:', error)
    ElMessage.error(t('companion.renameFailed'))
  }
}

const startRenameMotion = (motion) => {
  renamingMotionKey.value = motion.key
  renamingValue.value = motion.label || motion.labelEn || motion.key
}

const confirmRenameMotion = async (motion) => {
  const raw = renamingValue.value.trim()
  renamingMotionKey.value = null
  if (!raw || raw === (motion.label || motion.labelEn || motion.key)) return
  try {
    await companionAPI.renameMotionAsset(motion.key, raw)
    await fetchServerMotions()
    window.dispatchEvent(new CustomEvent('companion:motions-updated'))
    ElMessage.success(t('companion.motionRenamed'))
  } catch (error) {
    console.error('Rename motion failed:', error)
    ElMessage.error(t('companion.renameFailed'))
  }
}

const stripExtForDisplay = (name) => {
  if (!name) return ''
  const idx = name.lastIndexOf('.')
  return idx > 0 ? name.substring(0, idx) : name
}

const handleRemoveMotion = async (motion) => {
  if (!motion?.key) return
  try {
    await companionAPI.deleteMotionAsset(motion.key)
    await fetchServerMotions()
    window.dispatchEvent(new CustomEvent('companion:motions-updated'))
    ElMessage.success(t('companion.motionRemoved'))
  } catch (error) {
    console.error('Remove motion failed:', error)
    ElMessage.error(t('companion.motionRemoveFailed'))
  }
}

const handleRefreshStatus = async () => {
  const userId = userStore.currentUser?.id
  const roleId = activeRoleId.value
  if (!userId || !roleId) return
  await companionStore.fetchStatus(userId, roleId)
}

watch(drawerVisible, async (visible) => {
  if (!visible) return
  const userId = userStore.currentUser?.id
  if (!userId) return
  await companionStore.ensureInitialized(userId)
  fetchServerMotions()
  fetchServerModels()
})

watch([activeRoleId, roles], syncRoleModelFields, { immediate: true, deep: true })

fetchServerMotions()
fetchServerModels()
</script>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@300;400;500;600;700&display=swap');

/* ── CSS Variables ── */
.cp-window {
  --cp-bg: #f7f8f6;
  --cp-surface: #ffffff;
  --cp-surface-muted: #f1f5f1;
  --cp-border: #e2e8f0;
  --cp-text: #0f172a;
  --cp-muted: #64748b;
  --cp-primary: #bae699;
  --cp-primary-strong: #9ed46f;
  --cp-primary-ink: #1b2a16;
  --cp-shadow: 0 20px 60px rgba(15, 23, 42, 0.18);
  --el-color-primary: #bae699;
  --el-color-primary-light-3: rgba(186, 230, 153, 0.55);
  --el-color-primary-light-5: rgba(186, 230, 153, 0.35);
  --el-color-primary-light-7: rgba(186, 230, 153, 0.2);
  --el-color-primary-light-8: rgba(186, 230, 153, 0.16);
  --el-color-primary-light-9: rgba(186, 230, 153, 0.12);
  --el-color-primary-dark-2: #9ed46f;
  --el-text-color-primary: #0f172a;
  --el-border-color: #e2e8f0;
  --el-border-color-light: #e2e8f0;
  --el-fill-color-light: rgba(186, 230, 153, 0.15);
  font-family: 'Space Grotesk', 'Segoe UI', system-ui, -apple-system, sans-serif;
}

/* ── Backdrop ── */
.cp-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1999;
  background: rgba(0, 0, 0, 0.18);
  backdrop-filter: blur(2px);
}

/* ── Floating Window ── */
.cp-window {
  position: fixed;
  z-index: 2000;
  display: flex;
  flex-direction: column;
  background: var(--cp-bg);
  border: 1px solid var(--cp-border);
  border-radius: 16px;
  box-shadow: var(--cp-shadow);
  overflow: hidden;
}

/* ── Title Bar ── */
.cp-titlebar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px 12px;
  cursor: grab;
  user-select: none;
  background: linear-gradient(180deg, var(--cp-surface), var(--cp-bg));
  border-bottom: 1px solid var(--cp-border);
  flex-shrink: 0;
}

.cp-titlebar:active {
  cursor: grabbing;
}

.cp-titlebar-left {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.title {
  font-size: 18px;
  font-weight: 700;
  color: var(--cp-text);
  white-space: nowrap;
}

.badge {
  background: rgba(186, 230, 153, 0.35);
  color: var(--cp-primary-ink);
  font-size: 10px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  border: 1px solid rgba(186, 230, 153, 0.7);
  flex-shrink: 0;
}

.subtitle {
  font-size: 12px;
  color: var(--cp-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.cp-close-btn {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  border: 1px solid var(--cp-border);
  background: var(--cp-surface);
  color: var(--cp-muted);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
  flex-shrink: 0;
}

.cp-close-btn:hover {
  border-color: #f87171;
  color: #ef4444;
  background: rgba(239, 68, 68, 0.08);
}

/* ── Role strip ── */
.role-strip {
  display: flex;
  gap: 8px;
  padding: 10px 20px;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--cp-border);
  flex-shrink: 0;
}

.role-btn {
  border: 1px solid var(--cp-border);
  background: var(--cp-surface);
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
  color: var(--cp-text);
  min-height: 32px;
}

.role-btn.active {
  background: var(--cp-primary);
  border-color: var(--cp-primary);
  color: var(--cp-primary-ink);
  box-shadow: 0 6px 14px rgba(186, 230, 153, 0.35);
}

.role-btn:hover {
  border-color: var(--cp-primary-strong);
  background: var(--cp-surface-muted);
}

/* ── Scrollable body ── */
.cp-body {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  min-height: 0;
}

.companion-tabs {
  padding: 12px 20px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.companion-tabs :deep(.el-tabs__content) {
  flex: 1;
  overflow-y: auto;
}

/* ── Resize Handles ── */
.cp-resize {
  position: absolute;
  z-index: 10;
}

.cp-resize-n { top: -4px; left: 8px; right: 8px; height: 8px; cursor: n-resize; }
.cp-resize-s { bottom: -4px; left: 8px; right: 8px; height: 8px; cursor: s-resize; }
.cp-resize-e { top: 8px; right: -4px; bottom: 8px; width: 8px; cursor: e-resize; }
.cp-resize-w { top: 8px; left: -4px; bottom: 8px; width: 8px; cursor: w-resize; }
.cp-resize-ne { top: -4px; right: -4px; width: 14px; height: 14px; cursor: ne-resize; }
.cp-resize-nw { top: -4px; left: -4px; width: 14px; height: 14px; cursor: nw-resize; }
.cp-resize-se { bottom: -4px; right: -4px; width: 14px; height: 14px; cursor: se-resize; }
.cp-resize-sw { bottom: -4px; left: -4px; width: 14px; height: 14px; cursor: sw-resize; }

/* ── Footer ── */
.panel-footer {
  padding: 14px 20px 18px;
  border-top: 1px solid var(--cp-border);
  background: linear-gradient(0deg, var(--cp-surface), var(--cp-bg));
  flex-shrink: 0;
}

.status-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: 14px;
  border: 1px solid var(--cp-border);
  background: var(--cp-surface);
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.08);
}

.status-avatar {
  width: 44px;
  height: 44px;
  border-radius: 999px;
  background: rgba(186, 230, 153, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(186, 230, 153, 0.6);
  flex-shrink: 0;
}

.status-initial {
  font-size: 16px;
  font-weight: 700;
  color: var(--cp-primary-ink);
  text-transform: uppercase;
}

.status-info {
  flex: 1;
  min-width: 0;
}

.status-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--cp-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.status-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #22c55e;
  box-shadow: 0 0 0 4px rgba(34, 197, 94, 0.15);
}

.status-text {
  font-size: 11px;
  font-weight: 600;
  color: var(--cp-muted);
}

.status-sub {
  margin-top: 10px;
  font-size: 12px;
  color: var(--cp-muted);
}

.icon-btn {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  border: 1px solid var(--cp-border);
  background: var(--cp-surface);
  color: var(--cp-muted);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
}

.icon-btn:hover {
  border-color: var(--cp-primary-strong);
  color: var(--cp-text);
  background: var(--cp-surface-muted);
}

.icon-btn.ghost {
  background: transparent;
}

.icon-btn .icon {
  font-size: 14px;
  line-height: 1;
  font-weight: 700;
  text-transform: uppercase;
}

/* ── Tabs overrides ── */
.cp-window :deep(.el-tabs__header) {
  margin: 0 0 16px;
}

.cp-window :deep(.el-tabs__nav-wrap::after) {
  background-color: var(--cp-border);
}

.cp-window :deep(.el-tabs__item) {
  color: var(--cp-muted);
  font-weight: 500;
  height: 36px;
  padding: 0 12px;
}

.cp-window :deep(.el-tabs__item.is-active) {
  color: var(--cp-text);
  font-weight: 700;
}

.cp-window :deep(.el-tabs__active-bar) {
  background: var(--cp-primary-strong);
  height: 2px;
}

/* ── Chat pane ── */
.chat-pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.chat-scroll {
  border: 1px solid var(--cp-border);
  border-radius: 14px;
  padding: 12px;
  background: var(--cp-surface);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
}

.chat-bubble {
  max-width: 85%;
  padding: 10px 12px;
  border-radius: 12px;
  margin-bottom: 8px;
  line-height: 1.4;
  font-size: 14px;
}

.chat-bubble.from-user {
  background: var(--cp-primary);
  color: var(--cp-primary-ink);
  margin-left: auto;
}

.chat-bubble.from-role {
  background: var(--cp-surface);
  border: 1px solid var(--cp-border);
  color: var(--cp-text);
}

.bubble-tag {
  margin-top: 6px;
  font-size: 11px;
  color: #b45309;
}

.chat-input {
  display: flex;
  gap: 8px;
}

.chat-input :deep(.el-input) {
  flex: 1;
}

/* ── Memory pane ── */
.memory-pane {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.memory-new {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.memory-select {
  width: 90px;
}

.memory-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.memory-card {
  border: 1px solid var(--cp-border);
  border-radius: 12px;
  padding: 12px;
  background: var(--cp-surface);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
}

.memory-meta {
  display: flex;
  gap: 8px;
  font-size: 12px;
  color: var(--cp-muted);
  margin-top: 6px;
}

.tag.confirmed {
  color: #16a34a;
}

.memory-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

/* ── Growth pane ── */
.growth-pane {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.growth-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* ── Model pane ── */
.model-pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.model-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: var(--cp-surface);
  border: 1px solid var(--cp-border);
  border-radius: 14px;
  padding: 14px;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
}

.model-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--cp-muted);
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.model-select {
  width: 140px;
}

.model-help {
  font-size: 12px;
  color: var(--cp-muted);
}

.model-divider {
  height: 1px;
  background: var(--cp-border);
  margin: 8px 0;
}

.model-row .label {
  font-size: 12px;
  color: var(--cp-muted);
  margin-bottom: 6px;
}

.model-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.model-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.inline-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.hidden-input {
  display: none;
}

.local-motion-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 6px;
}

.local-motion-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--cp-border);
  border-radius: 10px;
  background: var(--cp-surface);
  transition: all 0.2s ease;
}

.local-motion-item.active {
  border-color: var(--cp-primary-strong);
  background: rgba(186, 230, 153, 0.2);
}

.local-motion-name {
  font-size: 12px;
  color: var(--cp-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: text;
}

.inline-rename-input {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  padding: 2px 6px;
  border: 1px solid var(--cp-primary-strong);
  border-radius: 6px;
  background: var(--cp-surface);
  color: var(--cp-text);
  outline: none;
  box-shadow: 0 0 0 2px rgba(186, 230, 153, 0.35);
  font-family: inherit;
}

.model-status {
  font-size: 12px;
  color: var(--cp-muted);
}

.empty-state {
  color: var(--cp-muted);
  font-size: 13px;
}

/* ── Transitions ── */
.cp-backdrop-enter-active,
.cp-backdrop-leave-active {
  transition: opacity 0.25s ease;
}
.cp-backdrop-enter-from,
.cp-backdrop-leave-to {
  opacity: 0;
}

.cp-window-enter-active {
  transition: opacity 0.25s ease, transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.cp-window-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.cp-window-enter-from {
  opacity: 0;
  transform: scale(0.92) translateY(20px);
}
.cp-window-leave-to {
  opacity: 0;
  transform: scale(0.95) translateY(10px);
}

/* ── Dark mode ── */
[data-theme="dark"] .cp-backdrop {
  background: rgba(0, 0, 0, 0.35);
}

[data-theme="dark"] .cp-window {
  --cp-bg: #181f13;
  --cp-surface: #0f1510;
  --cp-surface-muted: #131b12;
  --cp-border: #26311f;
  --cp-text: #f1f5f9;
  --cp-muted: #9aa68d;
  --cp-primary: #bae699;
  --cp-primary-strong: #c6f0a6;
  --cp-primary-ink: #121a10;
  --cp-shadow: 0 18px 40px rgba(3, 8, 4, 0.6);
  --el-color-primary: #bae699;
  --el-color-primary-light-3: rgba(186, 230, 153, 0.55);
  --el-color-primary-light-5: rgba(186, 230, 153, 0.35);
  --el-color-primary-light-7: rgba(186, 230, 153, 0.22);
  --el-color-primary-light-8: rgba(186, 230, 153, 0.18);
  --el-color-primary-light-9: rgba(186, 230, 153, 0.14);
  --el-color-primary-dark-2: #9ed46f;
  --el-text-color-primary: #f1f5f9;
  --el-border-color: #26311f;
  --el-border-color-light: #26311f;
  --el-fill-color-light: rgba(186, 230, 153, 0.18);
}

/* ── Element Plus overrides ── */
.cp-window :deep(.el-button) {
  border-color: var(--cp-border);
  background: var(--cp-surface);
  color: var(--cp-text);
  border-radius: 10px;
  height: 36px;
  font-weight: 600;
}

.cp-window :deep(.el-button:hover) {
  background: rgba(186, 230, 153, 0.15);
  border-color: var(--cp-primary);
  color: var(--cp-text);
}

.cp-window :deep(.el-button--primary) {
  background: var(--cp-primary);
  border-color: var(--cp-primary);
  color: var(--cp-primary-ink);
  box-shadow: 0 10px 20px rgba(186, 230, 153, 0.3);
}

.cp-window :deep(.el-button--primary:hover) {
  background: var(--cp-primary-strong);
  border-color: var(--cp-primary-strong);
}

.cp-window :deep(.el-button.is-text) {
  color: var(--cp-muted);
}

.cp-window :deep(.el-button.is-text:hover) {
  color: var(--cp-text);
  background: rgba(186, 230, 153, 0.12);
}

.cp-window :deep(.el-input__wrapper),
.cp-window :deep(.el-select__wrapper) {
  background: var(--cp-surface);
  box-shadow: inset 0 0 0 1px var(--cp-border);
  border-radius: 10px;
  padding: 4px 10px;
  min-height: 36px;
}

.cp-window :deep(.el-input__wrapper.is-focus),
.cp-window :deep(.el-select__wrapper.is-focus) {
  box-shadow: inset 0 0 0 1px var(--cp-primary-strong), 0 0 0 3px rgba(186, 230, 153, 0.35);
}

.cp-window :deep(.el-progress-bar__inner) {
  background: var(--cp-primary);
}

.cp-window :deep(.el-input__inner) {
  color: var(--cp-text);
}
</style>

<style>
/* Global styles for popper (not scoped) */
.companion-popper {
  --el-color-primary: #bae699;
  --el-color-primary-light-9: rgba(186, 230, 153, 0.15);
  --el-text-color-primary: #0f172a;
  --el-border-color-light: #e2e8f0;
  --el-bg-color-overlay: #ffffff;
}

.companion-popper .el-select-dropdown__item.is-selected {
  color: #1b2a16;
  font-weight: 600;
}

.companion-popper .el-select-dropdown__item.is-hovering {
  background: rgba(186, 230, 153, 0.2);
}
</style>

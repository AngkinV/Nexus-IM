<template>
  <el-dialog
    v-model="dialogVisible"
    :title="currentStep === 1 ? $t('group.createGroup') : $t('group.addMembers')"
    width="360px"
    class="create-group-dialog"
    :before-close="handleClose"
    :close-on-click-modal="false"
  >
    <!-- Step 1: Group Info -->
    <div v-if="currentStep === 1" class="group-form">
      <div class="avatar-upload-section">
        <el-upload
          class="avatar-uploader"
          action="#"
          :show-file-list="false"
          :auto-upload="false"
          :on-change="handleAvatarChange"
        >
          <div class="avatar-wrapper">
            <img v-if="groupAvatar" :src="groupAvatar" class="avatar" />
            <div v-else class="avatar-placeholder">
              <el-icon class="camera-icon"><Camera /></el-icon>
              <span>{{ $t('group.addPhoto') }}</span>
            </div>
            <div class="avatar-hover-overlay">
              <el-icon><Camera /></el-icon>
            </div>
          </div>
        </el-upload>
      </div>
      
      <el-input
        v-model="groupName"
        :placeholder="$t('group.groupName')"
        class="group-name-input"
        maxlength="50"
        show-word-limit
      />

      <el-input
        v-model="groupDescription"
        type="textarea"
        :placeholder="$t('group.description')"
        :rows="3"
        class="group-desc-input"
        maxlength="200"
        show-word-limit
      />

      <div class="group-settings">
        <div class="setting-item">
          <div class="setting-left">
            <el-icon><Lock /></el-icon>
            <span>{{ $t('group.privateGroup') }}</span>
          </div>
          <el-switch v-model="isPrivate" />
        </div>
        <p class="setting-hint">{{ isPrivate ? $t('group.privateHint') : $t('group.publicHint') }}</p>
      </div>
    </div>

    <!-- Step 2: Add Members -->
    <div v-else class="member-selection">
      <el-input
        v-model="searchQuery"
        :placeholder="$t('contact.searchUsers')"
        class="member-search"
        clearable
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <!-- Selected Members Preview -->
      <div v-if="selectedUsers.length > 0" class="selected-members">
        <div
          v-for="user in selectedMembersList"
          :key="user.id"
          class="selected-member-tag"
        >
          <el-avatar :size="24" :src="user.avatar || defaultAvatar" />
          <span>{{ user.nickname }}</span>
          <el-icon class="remove-icon" @click="toggleUser(user.id)"><Close /></el-icon>
        </div>
      </div>

      <!-- Available Users List -->
      <div class="available-users">
        <div class="section-title">{{ $t('group.availableMembers') }}</div>
        <el-scrollbar max-height="300px">
          <!-- Empty state when no contacts -->
          <div v-if="availableUsers.length === 0" class="empty-contacts">
            <el-icon class="empty-icon"><User /></el-icon>
            <p>{{ $t('group.noContacts') }}</p>
            <span>{{ $t('group.addContactsFirst') }}</span>
          </div>
          <!-- No search results -->
          <div v-else-if="filteredUsers.length === 0" class="empty-contacts">
            <el-icon class="empty-icon"><Search /></el-icon>
            <p>{{ $t('group.noSearchResults') }}</p>
          </div>
          <!-- User list -->
          <div
            v-else
            v-for="user in filteredUsers"
            :key="user.id"
            class="user-item"
            :class="{ selected: selectedUsers.includes(user.id) }"
            @click="toggleUser(user.id)"
          >
            <el-avatar :size="40" :src="user.avatar || defaultAvatar" />
            <div class="user-info">
              <span class="user-name">{{ user.nickname }}</span>
              <span class="user-username">@{{ user.username }}</span>
            </div>
            <div class="check-box" :class="{ checked: selectedUsers.includes(user.id) }">
              <el-icon v-if="selectedUsers.includes(user.id)"><Check /></el-icon>
            </div>
          </div>
        </el-scrollbar>
      </div>
      
      <div class="member-count">
        {{ $t('group.selectedCount', { count: selectedUsers.length }) }}
      </div>
    </div>

    <template #footer>
      <span class="dialog-footer">
        <el-button v-if="currentStep === 2" @click="currentStep = 1">
          {{ $t('common.back') }}
        </el-button>
        <el-button @click="handleClose">{{ $t('common.cancel') }}</el-button>
        <el-button 
          v-if="currentStep === 1"
          type="primary" 
          @click="goToStep2"
          :disabled="!groupName.trim()"
        >
          {{ $t('common.next') }}
        </el-button>
        <el-button 
          v-else
          type="primary" 
          @click="createGroup" 
          :disabled="selectedUsers.length === 0"
          :loading="isCreating"
        >
          {{ $t('group.createButton') }}
        </el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { Camera, Check, Search, Lock, Close, User } from '@element-plus/icons-vue'
import { useChatStore } from '@/stores/chat'
import { useContactStore } from '@/stores/contact'
import { useUserStore } from '@/stores/user'
import { chatAPI, fileAPI, resolveFileUrl } from '@/services/api'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps({
  visible: Boolean
})

const emit = defineEmits(['update:visible', 'created'])

const chatStore = useChatStore()
const contactStore = useContactStore()
const userStore = useUserStore()

const currentStep = ref(1)
const groupName = ref('')
const groupDescription = ref('')
const groupAvatar = ref('')
const groupAvatarFile = ref(null)  // 保存原始文件用于上传
const isPrivate = ref(false)
const selectedUsers = ref([])
const searchQuery = ref('')
const isCreating = ref(false)
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'

// Use real contacts from contactStore
const availableUsers = computed(() => {
  return contactStore.contacts.map(contact => ({
    id: contact.id,
    nickname: contact.nickname || contact.username,
    username: contact.username,
    avatar: contact.avatar || contact.avatarUrl || '',
    isOnline: contact.isOnline || false
  }))
})

const filteredUsers = computed(() => {
  if (!searchQuery.value) return availableUsers.value
  const query = searchQuery.value.toLowerCase()
  return availableUsers.value.filter(user =>
    user.nickname.toLowerCase().includes(query) ||
    user.username.toLowerCase().includes(query)
  )
})

const selectedMembersList = computed(() => {
  return availableUsers.value.filter(user => selectedUsers.value.includes(user.id))
})

const dialogVisible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

// Reset form when dialog opens
watch(() => props.visible, (val) => {
  if (val) {
    resetForm()
  }
})

const handleClose = () => {
  dialogVisible.value = false
  resetForm()
}

const resetForm = () => {
  currentStep.value = 1
  groupName.value = ''
  groupDescription.value = ''
  groupAvatar.value = ''
  groupAvatarFile.value = null
  isPrivate.value = false
  selectedUsers.value = []
  searchQuery.value = ''
  isCreating.value = false
}

const handleAvatarChange = (file) => {
  if (file.raw.size > 2 * 1024 * 1024) {
    ElMessage.warning(t('auth.imageSizeWarning'))
    return
  }
  groupAvatarFile.value = file.raw  // 保存文件对象用于上传
  const reader = new FileReader()
  reader.onload = (e) => {
    groupAvatar.value = e.target.result
  }
  reader.readAsDataURL(file.raw)
}

const toggleUser = (userId) => {
  const index = selectedUsers.value.indexOf(userId)
  if (index === -1) {
    selectedUsers.value.push(userId)
  } else {
    selectedUsers.value.splice(index, 1)
  }
}

const goToStep2 = () => {
  if (!groupName.value.trim()) {
    ElMessage.warning(t('group.nameRequired'))
    return
  }
  currentStep.value = 2
}

const createGroup = async () => {
  if (selectedUsers.value.length === 0) {
    ElMessage.warning(t('group.selectMembers'))
    return
  }

  isCreating.value = true

  try {
    const userId = userStore.currentUser?.id
    if (!userId) {
      ElMessage.error(t('auth.pleaseLogin'))
      return
    }

    // 如果有头像文件，先上传
    let uploadedAvatarUrl = null
    if (groupAvatarFile.value) {
      try {
        const uploadResponse = await fileAPI.uploadFile(groupAvatarFile.value)
        uploadedAvatarUrl = uploadResponse.data.fileUrl
      } catch (uploadError) {
        console.error('Group avatar upload failed:', uploadError)
        // 头像上传失败不阻止创建群组
      }
    }

    // Call real API
    const response = await chatAPI.createGroupChat(userId, {
      name: groupName.value,
      description: groupDescription.value,
      avatar: uploadedAvatarUrl,  // 使用上传后的 URL
      isPrivate: isPrivate.value,
      memberIds: selectedUsers.value
    })

    const newGroup = response.data

    // Transform to frontend format and add to store. The backend returns the
    // relative file URL (e.g. "/uploads/.../x.png"); resolve it to the full
    // backend host or the creator will see a broken avatar while other
    // members (who receive the URL via the WebSocket handler that already
    // resolves) see it correctly.
    const groupForStore = {
      id: newGroup.id,
      name: newGroup.name,
      description: newGroup.description,
      avatar: resolveFileUrl(newGroup.avatar || ''),
      lastMessage: t('group.created'),
      lastMessageTime: newGroup.createdAt,
      unreadCount: 0,
      online: true,
      type: 'GROUP',
      isPrivate: newGroup.isPrivate,
      members: newGroup.members || [],
      memberCount: newGroup.memberCount || selectedUsers.value.length + 1
    }

    chatStore.chats.unshift(groupForStore)
    emit('created', groupForStore)
    ElMessage.success(t('group.createSuccess'))
    handleClose()
  } catch (error) {
    console.error('Failed to create group:', error)
    ElMessage.error(t('group.createFailed'))
  } finally {
    isCreating.value = false
  }
}
</script>

<style scoped>
.create-group-dialog :deep(.el-dialog__body) {
  padding: 20px 24px;
}

.group-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.avatar-upload-section {
  display: flex;
  justify-content: center;
}

.avatar-wrapper {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  background: #10B981;
  display: flex;
  justify-content: center;
  align-items: center;
  cursor: pointer;
  overflow: hidden;
  position: relative;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 0 8px 24px -4px rgba(16, 185, 129, 0.4);
}

.avatar-wrapper:hover {
  transform: scale(1.05) translateY(-2px);
  box-shadow: 0 12px 28px -4px rgba(16, 185, 129, 0.5);
}

.avatar-wrapper:hover .avatar-hover-overlay {
  opacity: 1;
}

.avatar-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  color: white;
  gap: 5px;
}

.camera-icon {
  font-size: 28px;
}

.avatar-placeholder span {
  font-size: 12px;
}

.avatar {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-hover-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: center;
  align-items: center;
  color: white;
  font-size: 24px;
  opacity: 0;
  transition: opacity 0.2s;
}

.group-name-input :deep(.el-input__wrapper) {
  border-radius: 12px;
  padding: 10px 15px;
}

.group-desc-input :deep(.el-textarea__inner) {
  border-radius: 12px;
  padding: 12px 15px;
}

.group-settings {
  background: #f8f9fa;
  border-radius: 12px;
  padding: 15px;
}

.setting-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.setting-left {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #333;
}

.setting-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #999;
}

/* Step 2 Styles */
.member-selection {
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.member-search :deep(.el-input__wrapper) {
  border-radius: 20px;
  background: #f5f5f5;
}

.selected-members {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px;
  background: #f8f9fa;
  border-radius: 12px;
  min-height: 50px;
}

.selected-member-tag {
  display: flex;
  align-items: center;
  gap: 6px;
  background: rgba(16, 185, 129, 0.1);
  border-radius: 20px;
  padding: 4px 10px 4px 4px;
  font-size: 13px;
  color: #10B981;
}

.remove-icon {
  cursor: pointer;
  font-size: 12px;
  padding: 2px;
  border-radius: 50%;
  transition: background 0.2s;
}

.remove-icon:hover {
  background: rgba(0, 0, 0, 0.1);
}

.section-title {
  font-size: 13px;
  color: #999;
  margin-bottom: 10px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.available-users {
  border: 1px solid #e5e5e5;
  border-radius: 12px;
  padding: 15px;
  background: #fafafa;
}

.user-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  cursor: pointer;
  border-radius: 10px;
  transition: all 0.2s;
}

.user-item:hover {
  background: #f0f0f0;
}

.user-item.selected {
  background: rgba(16, 185, 129, 0.1);
}

.user-info {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.user-name {
  font-weight: 500;
  font-size: 14px;
  color: #333;
}

.user-username {
  font-size: 12px;
  color: #999;
}

.check-box {
  width: 22px;
  height: 22px;
  border: 2px solid #ddd;
  border-radius: 50%;
  display: flex;
  justify-content: center;
  align-items: center;
  transition: all 0.2s;
}

.check-box.checked {
  background: #10B981;
  border-color: #10B981;
  color: white;
}

.member-count {
  text-align: center;
  font-size: 13px;
  color: #666;
}

.empty-contacts {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: #999;
  text-align: center;
}

.empty-contacts .empty-icon {
  font-size: 48px;
  color: #ddd;
  margin-bottom: 12px;
}

.empty-contacts p {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: #666;
}

.empty-contacts span {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}

.dialog-footer {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .step-indicator {
    gap: 20px;
  }

  .step {
    font-size: 11px;
  }

  .step-num {
    width: 26px;
    height: 26px;
    font-size: 12px;
  }

  .avatar-wrapper {
    width: 80px;
    height: 80px;
  }

  .camera-icon {
    font-size: 24px;
  }

  .avatar-placeholder span {
    font-size: 10px;
  }

  .group-name-input :deep(.el-input__wrapper) {
    padding: 8px 12px;
  }

  .group-name-input :deep(.el-input__inner) {
    font-size: 16px;
  }

  .group-desc-input :deep(.el-textarea__inner) {
    padding: 10px 12px;
    font-size: 16px;
  }

  .group-settings {
    padding: 12px;
  }

  .setting-left {
    font-size: 14px;
  }

  .selected-members {
    padding: 8px;
    gap: 6px;
  }

  .selected-member-tag {
    font-size: 12px;
    padding: 3px 8px 3px 3px;
  }

  .available-users {
    padding: 10px;
  }

  .user-item {
    padding: 8px 10px;
  }

  .user-name {
    font-size: 13px;
  }

  .user-username {
    font-size: 11px;
  }

  .check-box {
    width: 20px;
    height: 20px;
  }

  .dialog-footer {
    gap: 8px;
  }
}
</style>

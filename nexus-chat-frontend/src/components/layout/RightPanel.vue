<template>
  <div class="right-panel" v-if="chatStore.activeChat">
    <!-- Header -->
    <div class="panel-header">
      <h3 class="panel-title">{{ isGroupChat ? $t('group.groupInfo') : $t('settings.profile') }}</h3>
      <button class="close-btn" @click="closePanel">
        <span class="material-icons-round">close</span>
      </button>
    </div>

    <div class="profile-content custom-scrollbar">
      <!-- Profile/Group Header -->
      <div class="profile-header">
        <div class="avatar-wrapper">
          <div class="avatar-glow"></div>
          <img
            :src="chatStore.activeChat.avatar || defaultAvatar"
            :alt="chatStore.activeChat.name"
            class="profile-avatar"
          />
        </div>
        <h2 class="profile-name">{{ chatStore.activeChat.name }}</h2>
        <p class="profile-status" v-if="!isGroupChat">
          {{ $t('chat.' + (chatStore.activeChat.status || 'offline')) }}
        </p>
        <p class="profile-status member-count" v-else>
          {{ members.length }} {{ $t('group.members') }}
        </p>
      </div>

      <!-- Direct Chat Info -->
      <template v-if="!isGroupChat">
        <div class="profile-actions">
          <div class="action-item">
            <el-icon><InfoFilled /></el-icon>
            <div class="action-text">
              <span class="label">Mobile</span>
              <span class="value">+1 234 567 890</span>
            </div>
          </div>
          <div class="action-item">
            <el-icon><Bell /></el-icon>
            <div class="action-text">
              <span class="label">{{ $t('settings.notifications') }}</span>
              <span class="value">{{ notificationsEnabled ? $t('common.enabled') : $t('common.disabled') }}</span>
            </div>
            <el-switch v-model="notificationsEnabled" />
          </div>
        </div>
      </template>

      <!-- Group Chat Members -->
      <template v-else>
        <!-- Members Section -->
        <div class="members-section">
          <div class="section-header">
            <h4 class="section-title">{{ $t('group.memberList') }}</h4>
            <button class="add-member-header-btn" @click="handleAddMember">
              <span class="material-icons-round">person_add</span>
            </button>
          </div>

          <!-- Members Grid -->
          <div class="members-grid" v-loading="loadingMembers">
            <div
              v-for="member in displayedMembers"
              :key="member.id"
              class="member-grid-item"
              @click="handleMemberClick(member)"
            >
              <div class="member-avatar-box">
                <img
                  :src="member.avatarUrl || defaultAvatar"
                  :alt="member.nickname"
                  class="member-avatar-img"
                />
                <span class="online-indicator" :class="{ online: member.isOnline }"></span>
                <!-- Role indicator for owner/admin -->
                <span v-if="member.role === 'owner'" class="role-indicator owner">
                  <span class="material-icons-round">star</span>
                </span>
                <span v-else-if="member.role === 'admin'" class="role-indicator admin">
                  <span class="material-icons-round">verified_user</span>
                </span>
              </div>
              <span class="member-label">{{ getMemberLabel(member) }}</span>
              <!-- Context menu for member management -->
              <el-dropdown
                v-if="canManageMember(member)"
                trigger="click"
                @command="(cmd) => handleMemberAction(cmd, member)"
                @click.stop
                class="member-dropdown"
              >
                <button class="member-more-btn" @click.stop>
                  <span class="material-icons-round">more_vert</span>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <template v-if="isOwner">
                      <el-dropdown-item v-if="member.role !== 'admin'" command="setAdmin">
                        <span class="material-icons-round menu-icon">admin_panel_settings</span>
                        {{ $t('group.setAdmin') }}
                      </el-dropdown-item>
                      <el-dropdown-item v-if="member.role === 'admin'" command="removeAdmin">
                        <span class="material-icons-round menu-icon">remove_moderator</span>
                        {{ $t('group.removeAdmin') }}
                      </el-dropdown-item>
                      <el-dropdown-item command="transfer">
                        <span class="material-icons-round menu-icon">swap_horiz</span>
                        {{ $t('group.transferOwnership') }}
                      </el-dropdown-item>
                    </template>
                    <el-dropdown-item v-if="canKickMember(member)" command="kick" divided>
                      <span class="material-icons-round menu-icon text-danger">person_remove</span>
                      {{ $t('group.removeMember') }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>

            <!-- Add Member Button -->
            <button class="add-member-btn" @click="handleAddMember">
              <span class="material-icons-round">add</span>
            </button>
          </div>
        </div>

        <!-- Divider -->
        <div class="section-divider"></div>

        <!-- Settings Section -->
        <div class="settings-section">
          <button class="setting-item" @click="handleSearchHistory">
            <span class="material-icons-round setting-icon">search</span>
            <span class="setting-text">{{ $t('group.searchHistory') || 'Search History' }}</span>
            <span class="material-icons-round setting-arrow">chevron_right</span>
          </button>

          <button class="setting-item" @click="toggleMute">
            <span class="material-icons-round setting-icon">notifications_off</span>
            <span class="setting-text">{{ $t('group.muteNotifications') || 'Mute Notifications' }}</span>
            <div class="toggle-switch" :class="{ active: isMuted }">
              <div class="toggle-thumb"></div>
            </div>
          </button>

          <button class="setting-item" @click="toggleSticky">
            <span class="material-icons-round setting-icon">push_pin</span>
            <span class="setting-text">{{ $t('group.stickyOnTop') || 'Sticky on Top' }}</span>
            <div class="toggle-switch" :class="{ active: isSticky }">
              <div class="toggle-thumb"></div>
            </div>
          </button>
        </div>
      </template>
    </div>

    <!-- Bottom Actions -->
    <div class="bottom-actions" v-if="isGroupChat">
      <button
        v-if="!isOwner"
        class="leave-group-btn"
        @click="handleLeaveGroup"
      >
        <span class="material-icons-round">logout</span>
        <span>{{ $t('group.leaveGroup') }}</span>
      </button>

      <button
        v-if="isOwner"
        class="leave-group-btn dissolve"
        @click="handleDissolveGroup"
      >
        <span class="material-icons-round">delete_forever</span>
        <span>{{ $t('group.dissolveGroup') }}</span>
      </button>
    </div>

    <!-- Confirm Dialog -->
    <el-dialog
      v-model="confirmDialog.visible"
      :title="confirmDialog.title"
      width="320px"
      :close-on-click-modal="false"
      append-to-body
      class="group-confirm-dialog"
    >
      <p class="confirm-message">{{ confirmDialog.message }}</p>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="confirmDialog.visible = false">{{ $t('common.cancel') }}</el-button>
          <el-button type="danger" @click="executeConfirmedAction" :loading="confirmDialog.loading">
            {{ $t('common.confirm') }}
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- Add Member Modal -->
    <AddGroupMemberModal
      v-model="showAddMemberModal"
      :group-id="chatStore.activeChat?.id"
      :existing-member-ids="existingMemberIds"
      @members-added="handleMembersAdded"
    />

    <!-- Search Messages Modal -->
    <SearchMessagesModal
      v-model="showSearchModal"
      :chat-id="chatStore.activeChat?.id"
      @message-selected="handleMessageSelected"
    />
  </div>
</template>

<script setup>
import { ref, inject, computed, watch } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useUserStore } from '@/stores/user'
import { groupAPI } from '@/services/api'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { InfoFilled, Bell } from '@element-plus/icons-vue'
import AddGroupMemberModal from '@/components/chat/AddGroupMemberModal.vue'
import SearchMessagesModal from '@/components/chat/SearchMessagesModal.vue'

const { t } = useI18n()
const chatStore = useChatStore()
const userStore = useUserStore()
const toggleRightPanel = inject('toggleRightPanel')
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'
const notificationsEnabled = ref(true)

// Members data
const members = ref([])
const loadingMembers = ref(false)

// Modal visibility
const showAddMemberModal = ref(false)
const showSearchModal = ref(false)

// Settings state - use chatStore
const isMuted = computed(() => {
  return chatStore.activeChat ? chatStore.isChatMuted(chatStore.activeChat.id) : false
})
const isSticky = computed(() => {
  return chatStore.activeChat ? chatStore.isChatPinned(chatStore.activeChat.id) : false
})

// Current user
const currentUserId = computed(() => userStore.currentUser?.id)

// Existing member IDs for add member modal
const existingMemberIds = computed(() => {
  return members.value.map(m => m.id)
})

// Check if current chat is a group
const isGroupChat = computed(() => {
  return chatStore.activeChat?.type === 'GROUP' || chatStore.activeChat?.type === 'group'
})

// Current user's role in group
const currentUserRole = computed(() => {
  const member = members.value.find(m => m.id === currentUserId.value)
  return member?.role || 'member'
})

const isOwner = computed(() => currentUserRole.value === 'owner')
const isAdmin = computed(() => currentUserRole.value === 'admin' || currentUserRole.value === 'owner')

// Display members sorted and limited for grid view
const displayedMembers = computed(() => {
  return [...members.value].sort((a, b) => {
    const roleOrder = { owner: 0, admin: 1, member: 2 }
    return (roleOrder[a.role] || 2) - (roleOrder[b.role] || 2)
  })
})

// Get member label (You for self, nickname for others)
const getMemberLabel = (member) => {
  if (member.id === currentUserId.value) {
    return t('group.me') || 'You'
  }
  // Truncate long names
  const name = member.nickname || 'User'
  return name.length > 6 ? name.substring(0, 6) + '...' : name
}

// Confirm dialog state
const confirmDialog = ref({
  visible: false,
  title: '',
  message: '',
  action: null,
  data: null,
  loading: false
})

// Check if current user can manage a specific member
const canManageMember = (member) => {
  if (member.id === currentUserId.value) return false
  if (isOwner.value) return true
  if (isAdmin.value && member.role === 'member') return true
  return false
}

// Check if current user can kick a specific member
const canKickMember = (member) => {
  if (member.id === currentUserId.value) return false
  if (member.role === 'owner') return false
  if (isOwner.value) return true
  if (isAdmin.value && member.role === 'member') return true
  return false
}

// Load group members
const loadGroupMembers = async () => {
  if (!chatStore.activeChat?.id || !isGroupChat.value) return

  loadingMembers.value = true
  try {
    const response = await groupAPI.getGroupMembers(chatStore.activeChat.id)
    members.value = response.data || []
  } catch (error) {
    console.error('Failed to load group members:', error)
    ElMessage.error(t('group.loadMembersFailed'))
  } finally {
    loadingMembers.value = false
  }
}

// Watch for active chat changes
watch(() => chatStore.activeChat?.id, (newId) => {
  if (newId && isGroupChat.value) {
    loadGroupMembers()
  }
}, { immediate: true })

// Watch for memberCount changes to refresh member list
watch(() => chatStore.activeChat?.memberCount, (newCount, oldCount) => {
  if (newCount !== oldCount && isGroupChat.value && chatStore.activeChat?.id) {
    loadGroupMembers()
  }
})

// Handle member click
const handleMemberClick = (member) => {
  // Could open member profile or start direct chat
  console.log('Member clicked:', member)
}

// Handle add member
const handleAddMember = () => {
  showAddMemberModal.value = true
}

// Handle members added callback
const handleMembersAdded = () => {
  // Reload members list
  loadGroupMembers()
}

// Toggle mute notifications
const toggleMute = () => {
  if (!chatStore.activeChat) return
  const newState = chatStore.toggleMuteChat(chatStore.activeChat.id)
  ElMessage.success(newState
    ? t('group.mutedSuccess')
    : t('group.unmutedSuccess')
  )
}

// Toggle sticky on top
const toggleSticky = () => {
  if (!chatStore.activeChat) return
  const newState = chatStore.togglePinChat(chatStore.activeChat.id)
  ElMessage.success(newState
    ? t('group.stickyEnabled')
    : t('group.stickyDisabled')
  )
}

// Handle search history
const handleSearchHistory = () => {
  showSearchModal.value = true
}

// Handle message selected from search
const handleMessageSelected = (message) => {
  // Could scroll to message or highlight it
  console.log('Message selected:', message)
}

// Handle member actions
const handleMemberAction = (command, member) => {
  switch (command) {
    case 'setAdmin':
      showConfirmDialog(
        t('group.setAdmin'),
        t('group.confirmSetAdmin', { name: member.nickname }),
        'setAdmin',
        { memberId: member.id, isAdmin: true }
      )
      break
    case 'removeAdmin':
      showConfirmDialog(
        t('group.removeAdmin'),
        t('group.confirmRemoveAdmin', { name: member.nickname }),
        'removeAdmin',
        { memberId: member.id, isAdmin: false }
      )
      break
    case 'transfer':
      showConfirmDialog(
        t('group.transferOwnership'),
        t('group.confirmTransfer', { name: member.nickname }),
        'transfer',
        { newOwnerId: member.id }
      )
      break
    case 'kick':
      showConfirmDialog(
        t('group.removeMember'),
        t('group.confirmKick', { name: member.nickname }),
        'kick',
        { memberId: member.id }
      )
      break
  }
}

// Handle leave group
const handleLeaveGroup = () => {
  showConfirmDialog(
    t('group.leaveGroup'),
    t('group.confirmLeave'),
    'leave',
    {}
  )
}

// Handle dissolve group
const handleDissolveGroup = () => {
  showConfirmDialog(
    t('group.dissolveGroup'),
    t('group.confirmDissolve'),
    'dissolve',
    {}
  )
}

// Show confirm dialog
const showConfirmDialog = (title, message, action, data) => {
  confirmDialog.value = {
    visible: true,
    title,
    message,
    action,
    data,
    loading: false
  }
}

// Execute confirmed action
const executeConfirmedAction = async () => {
  const { action, data } = confirmDialog.value
  const groupId = chatStore.activeChat.id
  const userId = currentUserId.value

  confirmDialog.value.loading = true

  try {
    switch (action) {
      case 'setAdmin':
      case 'removeAdmin':
        await groupAPI.setAdmin(groupId, data.memberId, userId, data.isAdmin)
        ElMessage.success(t(data.isAdmin ? 'group.setAdminSuccess' : 'group.removeAdminSuccess'))
        break
      case 'transfer':
        await groupAPI.transferOwnership(groupId, userId, data.newOwnerId)
        ElMessage.success(t('group.transferSuccess'))
        break
      case 'kick':
        await groupAPI.removeMember(groupId, data.memberId, userId)
        ElMessage.success(t('group.removeMemberSuccess'))
        break
      case 'leave':
        await groupAPI.leaveGroup(groupId, userId)
        ElMessage.success(t('group.leaveSuccess'))
        chatStore.leaveGroup(groupId)
        closePanel()
        return
      case 'dissolve':
        await groupAPI.deleteGroup(groupId, userId)
        ElMessage.success(t('group.dissolveSuccess'))
        chatStore.deleteChat(groupId)
        closePanel()
        return
    }

    // Reload members after action
    await loadGroupMembers()
  } catch (error) {
    console.error('Action failed:', error)
    const errorMessage = error.response?.data?.message || t('common.operationFailed')
    ElMessage.error(errorMessage)
  } finally {
    confirmDialog.value.loading = false
    confirmDialog.value.visible = false
  }
}

const closePanel = () => {
  toggleRightPanel()
}
</script>

<style scoped>
/* Primary color: #00bfa5 (Teal) */
.right-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #ffffff;
  transition: all 0.3s ease;
}

[data-theme="dark"] .right-panel {
  background: #1e1e1e;
}

/* Header */
.panel-header {
  height: 56px;
  padding: 0 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid transparent;
  flex-shrink: 0;
}

[data-theme="dark"] .panel-header {
  border-bottom-color: #333;
}

.panel-title {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: #111827;
}

[data-theme="dark"] .panel-title {
  color: #f3f4f6;
}

.close-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  border-radius: 50%;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.2s ease;
}

.close-btn:hover {
  background: #f3f4f6;
  color: #111827;
}

[data-theme="dark"] .close-btn:hover {
  background: #374151;
  color: #f3f4f6;
}

.close-btn .material-icons-round {
  font-size: 18px;
}

/* Scrollable Content */
.profile-content {
  flex: 1;
  overflow-y: auto;
  padding: 24px 16px;
}

/* Custom Scrollbar */
.custom-scrollbar::-webkit-scrollbar {
  width: 4px;
}

.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}

.custom-scrollbar::-webkit-scrollbar-thumb {
  background-color: rgba(156, 163, 175, 0.3);
  border-radius: 20px;
}

[data-theme="dark"] .custom-scrollbar::-webkit-scrollbar-thumb {
  background-color: rgba(156, 163, 175, 0.2);
}

/* Profile Header with Avatar */
.profile-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding-bottom: 24px;
}

.avatar-wrapper {
  position: relative;
  margin-bottom: 12px;
}

.avatar-glow {
  position: absolute;
  inset: 0;
  background: rgba(0, 191, 165, 0.2);
  filter: blur(20px);
  border-radius: 50%;
  opacity: 0;
  transition: opacity 0.5s ease;
}

.avatar-wrapper:hover .avatar-glow {
  opacity: 1;
}

.profile-avatar {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  object-fit: cover;
  position: relative;
  z-index: 10;
  border: 4px solid #ffffff;
  box-shadow: 0 8px 24px -4px rgba(0, 0, 0, 0.1);
}

[data-theme="dark"] .profile-avatar {
  border-color: #1e1e1e;
}

.profile-name {
  margin: 0 0 4px 0;
  font-size: 16px;
  font-weight: 700;
  color: #111827;
}

[data-theme="dark"] .profile-name {
  color: #f3f4f6;
}

.profile-status {
  margin: 0;
  font-size: 12px;
  color: #6b7280;
}

.profile-status.member-count {
  color: #00bfa5;
}

/* Profile Actions (for direct chat) */
.profile-actions {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.action-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: 12px;
  transition: background 0.2s ease;
  cursor: pointer;
}

.action-item:hover {
  background: #f9fafb;
}

[data-theme="dark"] .action-item:hover {
  background: #262626;
}

.action-text {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.label {
  font-size: 14px;
  font-weight: 500;
  color: #111827;
}

[data-theme="dark"] .label {
  color: #f3f4f6;
}

.value {
  font-size: 12px;
  color: #6b7280;
}

/* Members Section */
.members-section {
  padding: 0;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  margin-top: 8px;
}

.section-title {
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.add-member-header-btn {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: #6b7280;
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.2s ease;
}

.add-member-header-btn:hover {
  color: #00bfa5;
  background: rgba(0, 191, 165, 0.1);
}

.add-member-header-btn .material-icons-round {
  font-size: 16px;
}

/* Members Grid */
.members-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px 8px;
  margin-bottom: 24px;
}

.member-grid-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  cursor: pointer;
  position: relative;
}

.member-grid-item:hover .member-avatar-img {
  transform: translateY(-2px);
}

.member-avatar-box {
  position: relative;
  width: 40px;
  height: 40px;
  margin-bottom: 4px;
}

.member-avatar-img {
  width: 100%;
  height: 100%;
  border-radius: 8px;
  object-fit: cover;
  box-shadow: 0 2px 8px -2px rgba(0, 0, 0, 0.1);
  transition: transform 0.2s ease;
}

.online-indicator {
  position: absolute;
  bottom: -2px;
  right: -2px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #9ca3af;
  border: 2px solid #ffffff;
}

.online-indicator.online {
  background: #10b981;
}

[data-theme="dark"] .online-indicator {
  border-color: #1e1e1e;
}

/* Role Indicator */
.role-indicator {
  position: absolute;
  top: -4px;
  right: -4px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid #ffffff;
}

.role-indicator .material-icons-round {
  font-size: 10px;
}

.role-indicator.owner {
  background: linear-gradient(135deg, #fbbf24, #f59e0b);
  color: #ffffff;
}

.role-indicator.admin {
  background: linear-gradient(135deg, #60a5fa, #3b82f6);
  color: #ffffff;
}

[data-theme="dark"] .role-indicator {
  border-color: #1e1e1e;
}

.member-label {
  font-size: 10px;
  color: #6b7280;
  text-align: center;
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.2s ease;
}

.member-grid-item:hover .member-label {
  color: #111827;
}

[data-theme="dark"] .member-grid-item:hover .member-label {
  color: #f3f4f6;
}

/* Member Dropdown */
.member-dropdown {
  position: absolute;
  top: 0;
  right: -4px;
  z-index: 10;
}

.member-more-btn {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: rgba(255, 255, 255, 0.9);
  border-radius: 50%;
  cursor: pointer;
  opacity: 0;
  transition: all 0.2s ease;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.member-grid-item:hover .member-more-btn {
  opacity: 1;
}

.member-more-btn:hover {
  background: #ffffff;
}

.member-more-btn .material-icons-round {
  font-size: 14px;
  color: #6b7280;
}

[data-theme="dark"] .member-more-btn {
  background: rgba(38, 38, 38, 0.9);
}

[data-theme="dark"] .member-more-btn:hover {
  background: #374151;
}

.menu-icon {
  font-size: 16px;
  margin-right: 8px;
}

.menu-icon.text-danger {
  color: #ef4444;
}

/* Add Member Button */
.add-member-btn {
  width: 40px;
  height: 40px;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  color: #9ca3af;
  cursor: pointer;
  transition: all 0.2s ease;
  margin: 0 auto;
}

.add-member-btn:hover {
  border-color: #00bfa5;
  color: #00bfa5;
  background: rgba(0, 191, 165, 0.05);
}

.add-member-btn .material-icons-round {
  font-size: 18px;
}

/* Section Divider */
.section-divider {
  width: 100%;
  height: 1px;
  background: #f3f4f6;
  margin: 16px 0;
}

[data-theme="dark"] .section-divider {
  background: #333;
}

/* Settings Section */
.settings-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.setting-item {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px;
  border: none;
  background: transparent;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.setting-item:hover {
  background: #f9fafb;
}

[data-theme="dark"] .setting-item:hover {
  background: #262626;
}

.setting-icon {
  font-size: 18px;
  color: #9ca3af;
  margin-right: 12px;
  transition: color 0.2s ease;
}

.setting-item:hover .setting-icon {
  color: #00bfa5;
}

.setting-text {
  flex: 1;
  text-align: left;
  font-size: 12px;
  font-weight: 500;
  color: #111827;
}

[data-theme="dark"] .setting-text {
  color: #f3f4f6;
}

.setting-arrow {
  font-size: 16px;
  color: #d1d5db;
}

[data-theme="dark"] .setting-arrow {
  color: #4b5563;
}

/* Toggle Switch */
.toggle-switch {
  width: 32px;
  height: 16px;
  background: #e5e7eb;
  border-radius: 8px;
  position: relative;
  cursor: pointer;
  transition: background 0.2s ease;
}

.toggle-switch.active {
  background: #00bfa5;
}

.toggle-thumb {
  width: 12px;
  height: 12px;
  background: #ffffff;
  border-radius: 50%;
  position: absolute;
  top: 2px;
  left: 2px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  transition: transform 0.2s ease;
}

.toggle-switch.active .toggle-thumb {
  transform: translateX(16px);
}

[data-theme="dark"] .toggle-switch {
  background: #374151;
}

/* Bottom Actions */
.bottom-actions {
  padding: 16px;
  border-top: 1px solid transparent;
  margin-top: auto;
}

[data-theme="dark"] .bottom-actions {
  border-top-color: #333;
}

.leave-group-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px 16px;
  border: none;
  background: transparent;
  border-radius: 12px;
  color: #ef4444;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.leave-group-btn:hover {
  background: rgba(239, 68, 68, 0.1);
}

.leave-group-btn:hover .material-icons-round {
  transform: translateX(-4px);
}

.leave-group-btn .material-icons-round {
  font-size: 18px;
  transition: transform 0.2s ease;
}

[data-theme="dark"] .leave-group-btn:hover {
  background: rgba(239, 68, 68, 0.15);
}

/* Confirm Dialog */
.confirm-message {
  margin: 0;
  font-size: 14px;
  color: #475569;
  line-height: 1.6;
}

[data-theme="dark"] .confirm-message {
  color: #94a3b8;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .right-panel {
    border-left: none;
  }

  .panel-header {
    height: 52px;
    padding: 0 12px;
  }

  .panel-title {
    font-size: 15px;
  }

  .profile-content {
    padding: 20px 12px;
  }

  .profile-avatar {
    width: 70px;
    height: 70px;
  }

  .profile-name {
    font-size: 15px;
  }

  .profile-status {
    font-size: 11px;
  }

  .members-grid {
    grid-template-columns: repeat(4, 1fr);
    gap: 12px 6px;
  }

  .member-avatar-box {
    width: 36px;
    height: 36px;
  }

  .member-label {
    font-size: 9px;
  }

  .add-member-btn {
    width: 36px;
    height: 36px;
  }

  .setting-item {
    padding: 10px 6px;
  }

  .setting-text {
    font-size: 13px;
  }

  .bottom-actions {
    padding: 12px;
  }

  .leave-group-btn {
    padding: 10px 12px;
    font-size: 13px;
  }
}
</style>

<template>
  <div class="right-panel" v-if="chatStore.activeChat">
    <!-- Header -->
    <div class="panel-header">
      <h3 class="panel-title">{{ isGroupChat ? $t('group.groupInfo') : $t('settings.profile') }}</h3>
      <button class="close-btn" @click="closePanel">
        <span class="material-symbols-outlined">close</span>
      </button>
    </div>

    <div class="profile-content custom-scrollbar">
      <!-- Profile / Group header -->
      <div class="profile-header">
        <div class="avatar-wrapper">
          <IdentityAvatar :src="chatStore.activeChat.avatar || chatStore.activeChat.avatarUrl" :entity-id="chatStore.activeChat.id" :label="chatStore.activeChat.name" :kind="isGroup(chatStore.activeChat.type) ? 'group' : 'user'" class="profile-avatar" /></div>
        <h2 class="profile-name">{{ chatStore.activeChat.name }}</h2>
        <p class="profile-status" v-if="!isGroupChat">
          <PresenceDot class="status-pip" :value="chatPresence(chatStore.activeChat)" :type="chatStore.activeChat.type" />
          {{ chatPresence(chatStore.activeChat) === null ? '' : $t(chatPresence(chatStore.activeChat) === true ? 'chat.online' : 'chat.offline') }}
        </p>
        <p class="profile-status member-count" v-else-if="isGroupChat">
          {{ chatStore.activeChat.memberCount ?? members.length }} {{ $t('group.members') }}
        </p>
      </div>

      <!-- Direct chat info -->
      <template v-if="!isGroupChat">
        <div class="profile-actions">
          <div class="action-item">
            <span class="material-symbols-outlined action-icon">notifications</span>
            <div class="action-text">
              <span class="label">{{ $t('settings.notifications') }}</span>
              <span class="value">{{ notificationsEnabled ? $t('common.enabled') : $t('common.disabled') }}</span>
            </div>
            <el-switch v-model="notificationsEnabled" />
          </div>
        </div>
      </template>

      <!-- Group members -->
      <template v-else>
        <div class="members-section">
          <div class="section-header">
            <h4 class="section-title">{{ $t('group.memberList') }}</h4>
            <button class="add-member-header-btn" @click="handleAddMember">
              <span class="material-symbols-outlined">person_add</span>
            </button>
          </div>

          <div class="members-grid" v-loading="loadingMembers">
            <div
              v-for="member in displayedMembers"
              :key="member.id"
              class="member-grid-item"
              @click="handleMemberClick(member)"
            >
              <div class="member-avatar-box">
                <IdentityAvatar :src="member.avatarUrl || member.avatar" :entity-id="member.id" :label="member.nickname" kind="user" class="member-avatar-img" /><PresenceDot class="online-indicator" :value="member.isOnline" />
                <span v-if="member.role === 'owner'" class="role-indicator owner">
                  <span class="material-symbols-outlined">star</span>
                </span>
                <span v-else-if="member.role === 'admin'" class="role-indicator admin">
                  <span class="material-symbols-outlined">verified_user</span>
                </span>
              </div>
              <span class="member-label">{{ getMemberLabel(member) }}</span>
              <el-dropdown
                v-if="canManageMember(member)"
                trigger="click"
                @command="(cmd) => handleMemberAction(cmd, member)"
                @click.stop
                class="member-dropdown"
              >
                <button class="member-more-btn" @click.stop>
                  <span class="material-symbols-outlined">more_vert</span>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <template v-if="isOwner">
                      <el-dropdown-item v-if="member.role !== 'admin'" command="setAdmin">
                        <span class="material-symbols-outlined menu-icon">admin_panel_settings</span>
                        {{ $t('group.setAdmin') }}
                      </el-dropdown-item>
                      <el-dropdown-item v-if="member.role === 'admin'" command="removeAdmin">
                        <span class="material-symbols-outlined menu-icon">remove_moderator</span>
                        {{ $t('group.removeAdmin') }}
                      </el-dropdown-item>
                      <el-dropdown-item command="transfer">
                        <span class="material-symbols-outlined menu-icon">swap_horiz</span>
                        {{ $t('group.transferOwnership') }}
                      </el-dropdown-item>
                    </template>
                    <el-dropdown-item v-if="canKickMember(member)" command="kick" divided>
                      <span class="material-symbols-outlined menu-icon text-danger">person_remove</span>
                      {{ $t('group.removeMember') }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>

            <button class="add-member-btn" @click="handleAddMember">
              <span class="material-symbols-outlined">add</span>
            </button>
          </div>
        </div>

        <div class="section-divider"></div>

        <div class="settings-section">
          <button class="setting-item" @click="handleSearchHistory">
            <span class="material-symbols-outlined setting-icon">search</span>
            <span class="setting-text">{{ $t('group.searchHistory') || 'Search History' }}</span>
            <span class="material-symbols-outlined setting-arrow">chevron_right</span>
          </button>

          <button class="setting-item" @click="toggleMute">
            <span class="material-symbols-outlined setting-icon">notifications_off</span>
            <span class="setting-text">{{ $t('group.muteNotifications') || 'Mute Notifications' }}</span>
            <div class="toggle-switch" :class="{ active: isMuted }">
              <div class="toggle-thumb"></div>
            </div>
          </button>

          <button class="setting-item" @click="toggleSticky">
            <span class="material-symbols-outlined setting-icon">push_pin</span>
            <span class="setting-text">{{ $t('group.stickyOnTop') || 'Sticky on Top' }}</span>
            <div class="toggle-switch" :class="{ active: isSticky }">
              <div class="toggle-thumb"></div>
            </div>
          </button>
        </div>
      </template>
    </div>

    <!-- Bottom actions -->
    <div class="bottom-actions" v-if="isGroupChat">
      <button
        v-if="!isOwner"
        class="leave-group-btn"
        @click="handleLeaveGroup"
      >
        <span class="material-symbols-outlined">logout</span>
        <span>{{ $t('group.leaveGroup') }}</span>
      </button>

      <button
        v-if="isOwner"
        class="leave-group-btn dissolve"
        @click="handleDissolveGroup"
      >
        <span class="material-symbols-outlined">delete_forever</span>
        <span>{{ $t('group.dissolveGroup') }}</span>
      </button>
    </div>

    <!-- Confirm dialog -->
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

    <AddGroupMemberModal
      v-model="showAddMemberModal"
      :group-id="chatStore.activeChat?.id"
      :existing-member-ids="existingMemberIds"
      @members-added="handleMembersAdded"
    />

    <SearchMessagesModal
      v-model="showSearchModal"
      :chat-id="chatStore.activeChat?.id"
      @message-selected="handleMessageSelected"
    />
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useContactStore } from '@/stores/contact'
import { membershipSignature, currentGroupRecord } from '@/components/identity/displayFacts.mjs'
import { memberRows, requestGate } from '@/components/identity/presentation.mjs'
import IdentityAvatar from '@/components/identity/IdentityAvatar.vue'
import PresenceDot from '@/components/identity/PresenceDot.vue'
import { isGroup, presence, chatPresence } from '@/components/identity/presentation.mjs'
import { ref, inject, computed, watch, onBeforeUnmount } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useUserStore } from '@/stores/user'
import { sameId } from '@/utils/socialData.mjs'
import { groupAPI } from '@/services/api'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import AddGroupMemberModal from '@/components/chat/AddGroupMemberModal.vue'
import SearchMessagesModal from '@/components/chat/SearchMessagesModal.vue'

const { t } = useI18n()
const chatStore = useChatStore()
const userStore = useUserStore()
const toggleRightPanel = inject('toggleRightPanel')
const notificationsEnabled = ref(true)

// Members data
const router = useRouter()
const contactStore = useContactStore()
const members = ref([])
const identityMembers = computed(() => memberRows(members.value, id => contactStore.getUserIdentity(id)))
const loadingMembers = ref(false)

// Modal visibility
const showAddMemberModal = ref(false)
const showSearchModal = ref(false)

// The chat header's search button opens this dialog through a signal.
const searchSignal = inject('searchSignal', ref(0))
watch(searchSignal, () => {
  if (chatStore.activeChat) showSearchModal.value = true
})

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
  const member = identityMembers.value.find(m => sameId(m.id, currentUserId.value))
  return member?.role || 'member'
})

const isOwner = computed(() => currentUserRole.value === 'owner')
const isAdmin = computed(() => currentUserRole.value === 'admin' || currentUserRole.value === 'owner')

// Display members sorted and limited for grid view
const displayedMembers = computed(() => {
  return [...identityMembers.value].sort((a, b) => {
    const roleOrder = { owner: 0, admin: 1, member: 2 }
    return (roleOrder[a.role] ?? 2) - (roleOrder[b.role] ?? 2)
  })
})

// Get member label (You for self, nickname for others)
const getMemberLabel = (member) => {
  if (sameId(member.id, currentUserId.value)) {
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
  if (sameId(member.id, currentUserId.value)) return false
  if (isOwner.value) return true
  if (isAdmin.value && member.role === 'member') return true
  return false
}

// Check if current user can kick a specific member
const canKickMember = (member) => {
  if (sameId(member.id, currentUserId.value)) return false
  if (member.role === 'owner') return false
  if (isOwner.value) return true
  if (isAdmin.value && member.role === 'member') return true
  return false
}

// Load group members
const memberGate = requestGate()
onBeforeUnmount(() => memberGate.dispose())
const loadGroupMembers = async () => {
  const id = chatStore.activeChat?.id
  const ticket = memberGate.begin()
  const identityToken = contactStore.captureIdentityRevision()
  members.value = []
  loadingMembers.value = false
  if (!id || !isGroupChat.value || currentUserId.value == null) return
  const current = () => memberGate.current(ticket) && chatStore.activeChat?.id === id && isGroupChat.value && contactStore.isIdentityRevisionCurrent(identityToken)
  loadingMembers.value = true
  try {
    const response = await groupAPI.getGroupMembers(id)
    if (!current()) return
    const rows = response.data || []
    if (!contactStore.ingestUserSnapshots(rows, identityToken)) return
    members.value = rows
  } catch (error) {
    if (current()) ElMessage.error(t('group.loadMembersFailed'))
  } finally {
    if (current()) loadingMembers.value = false
  }
}

// A scalar signature coalesces selection + membership events, excluding identity ticks.
const membershipFacts = computed(() => membershipSignature(currentGroupRecord(chatStore, chatStore.activeChat?.id)))
watch(() => [currentUserId.value, chatStore.activeChat?.id, isGroupChat.value], loadGroupMembers, { immediate: true, flush: 'sync' })
watch(() => JSON.stringify([currentUserId.value, chatStore.activeChat?.id, membershipFacts.value]), (next, previous) => {
  const a = JSON.parse(next), b = JSON.parse(previous)
  if (a[0] === b[0] && a[1] === b[1] && isGroupChat.value) loadGroupMembers()
})

// Handle member click
const handleMemberClick = (member) => {
  router.push(`/user/${member.id}`)
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
.right-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #0e0e11;
  border-left: 1px solid rgba(68, 71, 72, 0.2);
  min-width: 0;
}

/* ── Header ─────────────────────────────────────────────── */
.panel-header {
  height: 56px;
  flex-shrink: 0;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(68, 71, 72, 0.2);
}

.panel-title {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: #ffffff;
}

.close-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  border-radius: 4px;
  color: #8e9192;
  cursor: pointer;
  transition: all 0.15s ease;
}

.close-btn .material-symbols-outlined {
  font-size: 18px;
}

.close-btn:hover {
  background: #2a2a2d;
  color: #ffffff;
}

/* ── Scroll body ────────────────────────────────────────── */
.profile-content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
}

.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}

.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}

.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #353438;
  border-radius: 9999px;
}

.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: #8e9192;
}

/* ── Profile header ─────────────────────────────────────── */
.profile-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding-bottom: 16px;
}

.avatar-wrapper {
  width: 72px;
  height: 72px;
  border-radius: 9999px;
  overflow: hidden;
  background: #2a2a2d;
  box-shadow: 0 0 0 1px rgba(68, 71, 72, 0.4);
  margin-bottom: 12px;
}

.profile-avatar {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.profile-name {
  margin: 0 0 4px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: #ffffff;
}

.profile-status {
  margin: 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #c4c7c8;
}

.status-pip {
  width: 5px;
  height: 5px;
  border-radius: 9999px;
  background: #6f7274;
}

.status-pip.online {
  background: #22c55e;
}

/* ── Info rows ──────────────────────────────────────────── */
.profile-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: 8px;
  border-top: 1px solid rgba(68, 71, 72, 0.2);
}

.action-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  background: #16161a;
  border: 1px solid rgba(68, 71, 72, 0.2);
}

.action-icon {
  font-size: 16px;
  color: #8e9192;
  flex-shrink: 0;
}

.action-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  flex: 1;
  min-width: 0;
}

.action-text .label {
  font-size: 11px;
  color: #8e9192;
}

.action-text .value {
  font-size: 12px;
  color: #e4e1e6;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ── Members ────────────────────────────────────────────── */
.members-section {
  padding-top: 12px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.section-title {
  margin: 0;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #6f7274;
}

.add-member-header-btn {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  border-radius: 4px;
  color: #8e9192;
  cursor: pointer;
  transition: all 0.15s ease;
}

.add-member-header-btn .material-symbols-outlined {
  font-size: 16px;
}

.add-member-header-btn:hover {
  background: #2a2a2d;
  color: #ffffff;
}

.members-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.member-grid-item {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  padding: 6px 2px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.member-grid-item:hover {
  background: #16161a;
}

.member-avatar-box {
  position: relative;
  width: 38px;
  height: 38px;
  border-radius: 9999px;
  overflow: visible;
}

.member-avatar-img {
  width: 38px;
  height: 38px;
  border-radius: 9999px;
  object-fit: cover;
  background: #2a2a2d;
  box-shadow: 0 0 0 1px rgba(68, 71, 72, 0.35);
}

.online-indicator {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 9px;
  height: 9px;
  border-radius: 9999px;
  background: #6f7274;
  box-shadow: 0 0 0 2px #0e0e11;
}

.online-indicator.online {
  background: #22c55e;
}

.role-indicator {
  position: absolute;
  top: -3px;
  left: -3px;
  width: 14px;
  height: 14px;
  border-radius: 9999px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 0 0 2px #0e0e11;
}

.role-indicator.owner {
  background: #ffffff;
  color: #2f3131;
}

.role-indicator.admin {
  background: #353438;
  color: #e4e1e6;
}

.role-indicator .material-symbols-outlined {
  font-size: 9px;
}

.member-label {
  font-size: 10px;
  color: #c4c7c8;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.member-more-btn {
  position: absolute;
  top: -2px;
  right: -2px;
  width: 16px;
  height: 16px;
  display: none;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 9999px;
  background: #353438;
  color: #e4e1e6;
  cursor: pointer;
}

.member-more-btn .material-symbols-outlined {
  font-size: 11px;
}

.member-grid-item:hover .member-more-btn {
  display: flex;
}

.add-member-btn {
  width: 38px;
  height: 38px;
  margin-top: 6px;
  align-self: center;
  border: 1px dashed rgba(68, 71, 72, 0.6);
  border-radius: 9999px;
  background: transparent;
  color: #8e9192;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.add-member-btn .material-symbols-outlined {
  font-size: 16px;
}

.add-member-btn:hover {
  border-color: #8e9192;
  color: #ffffff;
  background: #16161a;
}

.section-divider {
  height: 1px;
  background: rgba(68, 71, 72, 0.2);
  margin: 14px 0;
}

/* ── Settings rows ──────────────────────────────────────── */
.settings-section {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.setting-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #c4c7c8;
  cursor: pointer;
  text-align: left;
  transition: background-color 0.15s ease;
}

.setting-item:hover {
  background: #16161a;
}

.setting-icon {
  font-size: 16px;
  color: #8e9192;
  flex-shrink: 0;
}

.setting-text {
  flex: 1;
  font-size: 12px;
  color: #e4e1e6;
}

.setting-arrow {
  font-size: 16px;
  color: #6f7274;
  flex-shrink: 0;
}

.toggle-switch {
  width: 30px;
  height: 17px;
  border-radius: 9999px;
  background: #353438;
  position: relative;
  flex-shrink: 0;
  transition: background-color 0.18s ease;
}

.toggle-switch.active {
  background: #ffffff;
}

.toggle-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 13px;
  height: 13px;
  border-radius: 9999px;
  background: #0e0e11;
  transition: transform 0.18s ease;
}

.toggle-switch.active .toggle-thumb {
  transform: translateX(13px);
}

/* ── Bottom actions ─────────────────────────────────────── */
.bottom-actions {
  flex-shrink: 0;
  padding: 12px 16px;
  border-top: 1px solid rgba(68, 71, 72, 0.2);
}

.leave-group-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px;
  border: 1px solid rgba(68, 71, 72, 0.6);
  border-radius: 4px;
  background: transparent;
  color: #e4e1e6;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.leave-group-btn .material-symbols-outlined {
  font-size: 15px;
}

.leave-group-btn:hover {
  background: #1b1b1e;
}

.leave-group-btn.dissolve {
  border-color: rgba(255, 180, 171, 0.4);
  color: #ffb4ab;
}

.leave-group-btn.dissolve:hover {
  background: rgba(255, 180, 171, 0.08);
}

/* ── Dialog bits ────────────────────────────────────────── */
.confirm-message {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: #c4c7c8;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.menu-icon {
  font-size: 15px;
  margin-right: 6px;
  vertical-align: middle;
  color: #8e9192;
}

.text-danger {
  color: #ffb4ab !important;
}
</style>

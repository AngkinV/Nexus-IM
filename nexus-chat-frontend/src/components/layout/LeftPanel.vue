<template>
  <div class="left-panel" :class="{ 'is-mac': isMac }">
    <!-- Header: Logo, Search & Settings -->
    <div class="panel-header drag-region">
      <div class="header-top">
        <h1 class="app-logo">
          <span class="text-gradient">Nexus</span>
        </h1>
        <div class="header-actions no-drag">
          <button class="icon-btn-new" @click="openNewChat" :title="$t('group.createGroup')">
            <el-icon><EditPen /></el-icon>
          </button>
          <button class="icon-btn-new settings-btn" @click="goToSettings" :title="$t('settings.settings')">
            <el-icon><Setting /></el-icon>
          </button>
          <!-- Window controls for Windows -->
          <template v-if="isElectron && !isMac">
            <button class="window-ctrl-btn minimize" @click="minimizeWindow" title="最小化">
              <svg width="12" height="12" viewBox="0 0 12 12">
                <rect width="10" height="1" x="1" y="6" fill="currentColor"/>
              </svg>
            </button>
            <button class="window-ctrl-btn maximize" @click="maximizeWindow" title="最大化">
              <svg width="12" height="12" viewBox="0 0 12 12">
                <rect width="9" height="9" x="1.5" y="1.5" fill="none" stroke="currentColor" stroke-width="1"/>
              </svg>
            </button>
            <button class="window-ctrl-btn close" @click="closeWindow" title="关闭">
              <svg width="12" height="12" viewBox="0 0 12 12">
                <path d="M1 1 L11 11 M1 11 L11 1" stroke="currentColor" stroke-width="1.5"/>
              </svg>
            </button>
          </template>
        </div>
      </div>
      <div class="search-bar-new no-drag">
        <el-icon class="search-icon"><Search /></el-icon>
        <input
          v-model="searchQuery"
          :placeholder="$t('chat.search')"
          class="search-input-new"
          type="text"
        />
      </div>
    </div>

    <!-- Tabs -->
    <div class="tabs-container-new">
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'chats' }"
        @click="activeTab = 'chats'"
      >
        {{ $t('chat.recentChats') }}
        <span v-if="chatStore.totalUnreadCount > 0" class="tab-badge-new">{{ chatStore.totalUnreadCount }}</span>
      </button>
      <button
        class="tab-btn contacts-tab"
        :class="{ active: activeTab === 'contacts' }"
        @click="activeTab = 'contacts'"
      >
        {{ $t('chat.contacts') }}
      </button>
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'groups' }"
        @click="activeTab = 'groups'"
      >
        {{ $t('chat.groups') }}
      </button>
    </div>

    <!-- Content based on active tab -->
    <div class="list-container">
      <!-- Chats Tab -->
      <template v-if="activeTab === 'chats'">
        <ChatList :search-query="searchQuery" />
      </template>

      <!-- Contacts Tab -->
      <template v-else-if="activeTab === 'contacts'">
        <ContactList :search-query="searchQuery" @select="handleContactSelect" />
      </template>

      <!-- Groups Tab -->
      <template v-else>
        <GroupList :search-query="searchQuery" @select="handleGroupSelect" />
      </template>
    </div>

    <!-- Bottom: User Profile & Add -->
    <div class="panel-footer-new">
      <div class="user-profile-new" @click="goToProfile">
        <div class="avatar-wrapper">
          <el-avatar :size="44" :src="userStore.currentUser?.avatar || defaultAvatar" />
          <span class="online-indicator"></span>
        </div>
        <div class="user-info-new">
          <span class="username-new">{{ userStore.currentUser?.nickname || 'User' }}</span>
          <span class="user-status-new">{{ $t('chat.online') }}</span>
        </div>
      </div>
      <button class="add-btn-new" @click="openAddContact" :title="$t('contact.addContact')">
        <el-icon><Plus /></el-icon>
      </button>
    </div>

    <CreateGroupModal
      v-model:visible="showCreateGroup"
      @created="handleGroupCreated"
    />
    
    <AddContactModal
      v-model:visible="showAddContact"
      @added="handleContactAdded"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search, Setting, EditPen, Plus } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { useContactStore } from '@/stores/contact'
import ChatList from '@/components/chat/ChatList.vue'
import ContactList from '@/components/contact/ContactList.vue'
import GroupList from '@/components/chat/GroupList.vue'
import CreateGroupModal from '@/components/chat/CreateGroupModal.vue'
import AddContactModal from '@/components/contact/AddContactModal.vue'

const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()
const contactStore = useContactStore()

const searchQuery = ref('')
const activeTab = ref('chats')
const showCreateGroup = ref(false)
const showAddContact = ref(false)
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'

// Electron detection
const isElectron = ref(false)
const isMac = ref(false)

onMounted(() => {
  isElectron.value = !!window.electronAPI
  if (window.electronAPI) {
    isMac.value = window.electronAPI.platform === 'darwin'
  }
})

// Window control methods
const minimizeWindow = () => {
  if (window.electronAPI) {
    window.electronAPI.minimizeWindow()
  }
}

const maximizeWindow = () => {
  if (window.electronAPI) {
    window.electronAPI.maximizeWindow()
  }
}

const closeWindow = () => {
  if (window.electronAPI) {
    window.electronAPI.closeWindow()
  }
}

const goToSettings = () => {
  router.push('/settings')
}

const goToProfile = () => {
  router.push('/profile')
}

const openNewChat = () => {
  showCreateGroup.value = true
}

const openAddContact = () => {
  showAddContact.value = true
}

const handleGroupCreated = (group) => {
  chatStore.setActiveChat(group)
  activeTab.value = 'chats'
}

const handleContactAdded = (contact) => {
  console.log('Contact added:', contact)
}

const handleContactSelect = async (contact) => {
  // Create or open direct chat with contact
  await chatStore.createDirectChat(userStore.currentUser?.id, contact)
  activeTab.value = 'chats'
}

const handleGroupSelect = (group) => {
  chatStore.setActiveChat(group)
  activeTab.value = 'chats'
}
</script>

<style scoped>
.left-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: rgba(255, 255, 255, 0.8);
  transition: background 0.3s ease;
}

/* macOS traffic light spacing */
.left-panel.is-mac .panel-header {
  padding-top: 32px;
}

/* Header */
.panel-header {
  padding: 16px 20px 8px 20px;
}

/* Drag region for window dragging */
.drag-region {
  -webkit-app-region: drag;
}

.no-drag {
  -webkit-app-region: no-drag;
}

.header-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.app-logo {
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.5px;
  margin: 0;
  -webkit-app-region: drag;
}

.text-gradient {
  background: linear-gradient(135deg, #14b8a6 0%, #06b6d4 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.icon-btn-new {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  border: none;
  background: #f1f5f9;
  color: #64748b;
  cursor: pointer;
  transition: all 0.3s ease;
}

.icon-btn-new:hover {
  background: #ccfbf1;
  color: #14b8a6;
  transform: scale(1.05);
}

.icon-btn-new.settings-btn:hover {
  transform: rotate(90deg);
}

.icon-btn-new .el-icon {
  font-size: 18px;
}

/* Window control buttons */
.window-ctrl-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  border-radius: 10px;
  transition: all 0.2s ease;
  margin-left: 2px;
}

.window-ctrl-btn:hover {
  background: #f1f5f9;
}

.window-ctrl-btn.close:hover {
  background: #fee2e2;
  color: #ef4444;
}

.window-ctrl-btn:active {
  transform: scale(0.95);
}

/* Search Bar */
.search-bar-new {
  position: relative;
  margin-bottom: 12px;
}

.search-icon {
  position: absolute;
  left: 16px;
  top: 50%;
  transform: translateY(-50%);
  color: #94a3b8;
  font-size: 18px;
  transition: color 0.3s ease;
  pointer-events: none;
}

.search-input-new {
  width: 100%;
  padding: 14px 16px 14px 44px;
  border: none;
  border-radius: 16px;
  background: #f1f5f9;
  font-size: 14px;
  color: #1e293b;
  outline: none;
  transition: all 0.3s ease;
}

.search-input-new::placeholder {
  color: #94a3b8;
}

.search-input-new:focus {
  background: #ffffff;
  box-shadow: 0 0 0 2px rgba(20, 184, 166, 0.3);
  transform: scale(1.02);
}

.search-bar-new:focus-within .search-icon {
  color: #14b8a6;
}

/* Tabs */
.tabs-container-new {
  display: flex;
  gap: 4px;
  padding: 4px;
  margin: 0 16px 12px 16px;
  background: #f1f5f9;
  border-radius: 14px;
}

.tab-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 8px;
  border: none;
  border-radius: 10px;
  background: transparent;
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
  cursor: pointer;
  transition: all 0.3s ease;
}

.tab-btn:hover {
  color: #1e293b;
  background: rgba(255, 255, 255, 0.5);
}

.tab-btn.active {
  background: #ffffff;
  color: #1e293b;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
}

.tab-badge-new {
  background: linear-gradient(135deg, #14b8a6 0%, #06b6d4 100%);
  color: white;
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  border-radius: 8px;
  min-width: 18px;
}

/* List Container */
.list-container {
  flex: 1;
  overflow-y: auto;
  padding: 0 12px;
}

.list-container::-webkit-scrollbar {
  width: 6px;
}

.list-container::-webkit-scrollbar-track {
  background: transparent;
}

.list-container::-webkit-scrollbar-thumb {
  background: #cbd5e1;
  border-radius: 3px;
}

/* Footer */
.panel-footer-new {
  padding: 20px;
  border-top: 1px solid #e2e8f0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}

.user-profile-new {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  padding: 8px 12px 8px 8px;
  margin: -8px;
  border-radius: 18px;
  transition: all 0.3s ease;
}

.user-profile-new:hover {
  background: rgba(14, 165, 233, 0.08);
}

.user-profile-new:active {
  transform: scale(0.98);
}

.avatar-wrapper {
  position: relative;
}

.avatar-wrapper .el-avatar {
  border: 2px solid #e2e8f0;
  transition: all 0.3s ease;
}

.user-profile-new:hover .avatar-wrapper .el-avatar {
  border-color: #0ea5e9;
}

.online-indicator {
  position: absolute;
  bottom: -2px;
  right: -2px;
  width: 14px;
  height: 14px;
  background: #10b981;
  border: 3px solid #ffffff;
  border-radius: 50%;
  box-shadow: 0 0 0 2px rgba(16, 185, 129, 0.2);
}

.user-info-new {
  display: flex;
  flex-direction: column;
}

.username-new {
  font-weight: 700;
  font-size: 14px;
  color: #1e293b;
  transition: color 0.3s ease;
  line-height: 1.2;
}

.user-profile-new:hover .username-new {
  color: #0ea5e9;
}

.user-status-new {
  font-size: 11px;
  font-weight: 600;
  color: #10b981;
  margin-top: 2px;
}

.add-btn-new {
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 18px;
  border: none;
  background: linear-gradient(135deg, #0ea5e9 0%, #10b981 100%);
  color: white;
  cursor: pointer;
  box-shadow: 0 8px 24px -4px rgba(14, 165, 233, 0.4);
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.add-btn-new:hover {
  transform: translateY(-3px) rotate(90deg);
  box-shadow: 0 12px 28px -4px rgba(14, 165, 233, 0.5);
}

.add-btn-new:active {
  transform: translateY(0) rotate(90deg) scale(0.92);
  box-shadow: 0 4px 12px -2px rgba(14, 165, 233, 0.4);
}

.add-btn-new .el-icon {
  font-size: 24px;
}

/* Dark Mode */
[data-theme="dark"] .left-panel {
  background: rgba(24, 27, 33, 0.8);
}

[data-theme="dark"] .icon-btn-new {
  background: #232730;
  color: #94a3b8;
}

[data-theme="dark"] .icon-btn-new:hover {
  background: rgba(20, 184, 166, 0.2);
  color: #5eead4;
}

[data-theme="dark"] .window-ctrl-btn {
  color: #94a3b8;
}

[data-theme="dark"] .window-ctrl-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #e2e8f0;
}

[data-theme="dark"] .window-ctrl-btn.close:hover {
  background: rgba(239, 68, 68, 0.2);
  color: #f87171;
}

[data-theme="dark"] .search-input-new {
  background: rgba(0, 0, 0, 0.2);
  color: #e2e8f0;
}

[data-theme="dark"] .search-input-new:focus {
  background: rgba(0, 0, 0, 0.3);
}

[data-theme="dark"] .tabs-container-new {
  background: rgba(0, 0, 0, 0.2);
}

[data-theme="dark"] .tab-btn {
  color: #94a3b8;
}

[data-theme="dark"] .tab-btn:hover {
  color: #e2e8f0;
  background: rgba(255, 255, 255, 0.05);
}

[data-theme="dark"] .tab-btn.active {
  background: #232730;
  color: #ffffff;
}

[data-theme="dark"] .panel-footer-new {
  border-top-color: #232A30;
  background: rgba(24, 30, 33, 0.9);
}

[data-theme="dark"] .user-profile-new:hover {
  background: rgba(14, 165, 233, 0.1);
}

[data-theme="dark"] .avatar-wrapper .el-avatar {
  border-color: #334155;
}

[data-theme="dark"] .user-profile-new:hover .avatar-wrapper .el-avatar {
  border-color: #0ea5e9;
}

[data-theme="dark"] .online-indicator {
  border-color: #181E21;
}

[data-theme="dark"] .username-new {
  color: #e2e8f0;
}

[data-theme="dark"] .user-profile-new:hover .username-new {
  color: #38bdf8;
}

[data-theme="dark"] .add-btn-new {
  background: linear-gradient(135deg, #0ea5e9 0%, #10b981 100%);
  box-shadow: 0 8px 24px -4px rgba(14, 165, 233, 0.3);
}

[data-theme="dark"] .add-btn-new:hover {
  box-shadow: 0 12px 28px -4px rgba(14, 165, 233, 0.4);
}

[data-theme="dark"] .list-container::-webkit-scrollbar-thumb {
  background: #475569;
}

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .left-panel {
    border-right: none;
  }

  .left-panel.is-mac .panel-header {
    padding-top: 16px;
  }

  .panel-header {
    padding: 12px 16px 8px 16px;
  }

  .header-top {
    margin-bottom: 12px;
  }

  .app-logo {
    font-size: 24px;
  }

  .search-input-new {
    padding: 12px 16px 12px 40px;
    font-size: 16px; /* Prevent iOS zoom */
  }

  .tabs-container-new {
    margin: 0 12px 10px 12px;
  }

  .tab-btn {
    padding: 8px 6px;
    font-size: 12px;
  }

  .list-container {
    padding: 0 8px;
  }

  .panel-footer-new {
    padding: 16px;
  }

  .user-profile-new {
    padding: 6px 10px 6px 6px;
    margin: -6px;
  }

  .avatar-wrapper .el-avatar {
    width: 40px !important;
    height: 40px !important;
  }

  .add-btn-new {
    width: 44px;
    height: 44px;
    border-radius: 14px;
  }
}

/* Small phones */
@media (max-width: 375px) {
  .panel-header {
    padding: 10px 12px 6px 12px;
  }

  .tabs-container-new {
    margin: 0 10px 8px 10px;
  }

  .list-container {
    padding: 0 6px;
  }
}
</style>

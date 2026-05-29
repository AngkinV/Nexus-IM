<template>
  <div class="left-panel">
    <!-- Header: Search & quick add -->
    <div class="panel-header drag-region">
      <!-- Window controls for Windows -->
      <div v-if="isElectron && !isMac" class="win-controls no-drag">
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
      </div>

      <div class="header-row no-drag">
        <div class="search-bar-new">
          <el-icon class="search-icon"><Search /></el-icon>
          <input
            v-model="searchQuery"
            :placeholder="$t('chat.search')"
            class="search-input-new"
            type="text"
          />
        </div>
        <el-dropdown trigger="click" placement="bottom-end" @command="handleAddCommand">
          <button class="icon-btn-add" :title="$t('chat.newChat')">
            <el-icon><Plus /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="group">{{ $t('group.createGroup') }}</el-dropdown-item>
              <el-dropdown-item command="contact">{{ $t('contact.addContact') }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <!-- Content based on active view (driven by NavRail) -->
    <div class="list-container">
      <template v-if="activeView === 'chats'">
        <ChatList :search-query="searchQuery" />
      </template>

      <template v-else-if="activeView === 'contacts'">
        <ContactList :search-query="searchQuery" @select="handleContactSelect" />
      </template>

      <template v-else>
        <GroupList :search-query="searchQuery" @select="handleGroupSelect" />
      </template>
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
import { ref, inject, onMounted } from 'vue'
import { Search, Plus } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import ChatList from '@/components/chat/ChatList.vue'
import ContactList from '@/components/contact/ContactList.vue'
import GroupList from '@/components/chat/GroupList.vue'
import CreateGroupModal from '@/components/chat/CreateGroupModal.vue'
import AddContactModal from '@/components/contact/AddContactModal.vue'

const userStore = useUserStore()
const chatStore = useChatStore()

const activeView = inject('activeView', ref('chats'))
const setActiveView = inject('setActiveView', () => {})

const searchQuery = ref('')
const showCreateGroup = ref(false)
const showAddContact = ref(false)

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

const handleAddCommand = (command) => {
  if (command === 'group') {
    showCreateGroup.value = true
  } else if (command === 'contact') {
    showAddContact.value = true
  }
}

const handleGroupCreated = (group) => {
  chatStore.setActiveChat(group)
  setActiveView('chats')
}

const handleContactAdded = () => {}

const handleContactSelect = async (contact) => {
  // Create or open direct chat with contact
  await chatStore.createDirectChat(userStore.currentUser?.id, contact)
  setActiveView('chats')
}

const handleGroupSelect = (group) => {
  chatStore.setActiveChat(group)
  setActiveView('chats')
}
</script>

<style scoped>
.left-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: rgba(255, 255, 255, 0.72);
  transition: background 0.3s ease;
}

/* Header */
.panel-header {
  padding: 24px 16px 12px 16px;
}

/* Drag region for window dragging */
.drag-region {
  -webkit-app-region: drag;
}

.no-drag {
  -webkit-app-region: no-drag;
}

.win-controls {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
  margin-bottom: 8px;
}

.header-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Window control buttons */
.window-ctrl-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  border-radius: 8px;
  transition: all 0.2s ease;
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
  flex: 1;
  min-width: 0;
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
  padding: 13px 16px 13px 44px;
  border: none;
  border-radius: 16px;
  background: #f1f5f9;
  font-size: 14px;
  color: #1e293b;
  outline: none;
  transition: all 0.3s ease;
  box-sizing: border-box;
}

.search-input-new::placeholder {
  color: #64748b;
}

.search-input-new:focus {
  background: #ffffff;
  box-shadow: 0 0 0 2px rgba(20, 184, 166, 0.3);
}

.search-bar-new:focus-within .search-icon {
  color: #14b8a6;
}

/* Add button */
.icon-btn-add {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  border: none;
  background: linear-gradient(135deg, #14b8a6 0%, #06b6d4 100%);
  color: #fff;
  cursor: pointer;
  box-shadow: 0 4px 12px -4px rgba(20, 184, 166, 0.4);
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.icon-btn-add:hover {
  transform: translateY(-1px) rotate(90deg);
  box-shadow: 0 8px 18px -5px rgba(20, 184, 166, 0.5);
}

.icon-btn-add:active {
  transform: translateY(0) rotate(90deg) scale(0.94);
}

.icon-btn-add .el-icon {
  font-size: 18px;
}

/* List Container */
.list-container {
  flex: 1;
  overflow-y: auto;
  padding: 4px 12px 12px 12px;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.list-container::-webkit-scrollbar {
  width: 0;
  height: 0;
  display: none;
}

/* Dark Mode */
[data-theme="dark"] .left-panel {
  background: rgba(24, 27, 33, 0.72);
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

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .left-panel {
    border-right: none;
  }

  .panel-header {
    padding: 12px 12px 8px 12px;
  }

  .search-input-new {
    padding: 12px 16px 12px 40px;
    font-size: 16px; /* Prevent iOS zoom */
  }

  .icon-btn-add {
    width: 44px;
    height: 44px;
  }

  .list-container {
    padding: 4px 8px 8px 8px;
  }
}

/* Small phones */
@media (max-width: 375px) {
  .panel-header {
    padding: 10px 10px 6px 10px;
  }

  .list-container {
    padding: 4px 6px 6px 6px;
  }
}
</style>

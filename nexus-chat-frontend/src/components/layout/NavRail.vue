<template>
  <div class="nav-rail drag-region" :class="{ 'is-mac': isMac }">
    <!-- Primary navigation -->
    <nav class="rail-nav no-drag">
      <button
        v-for="item in navItems"
        :key="item.key"
        class="rail-item"
        :class="{ active: activeView === item.key }"
        :title="$t(item.labelKey)"
        @click="setActiveView(item.key)"
      >
        <span class="rail-icon-wrap">
          <span class="material-icons-round">{{ item.icon }}</span>
          <span
            v-if="item.key === 'chats' && chatStore.totalUnreadCount > 0"
            class="rail-badge"
          >{{ chatStore.totalUnreadCount > 99 ? '99+' : chatStore.totalUnreadCount }}</span>
        </span>
        <span class="rail-label">{{ $t(item.labelKey) }}</span>
      </button>
    </nav>

    <!-- Bottom: settings + profile -->
    <div class="rail-bottom no-drag">
      <button class="rail-item settings-item" :title="$t('nav.settings')" @click="goToSettings">
        <span class="rail-icon-wrap">
          <span class="material-icons-round">settings</span>
        </span>
        <span class="rail-label">{{ $t('nav.settings') }}</span>
      </button>
      <div
        class="rail-avatar"
        :title="userStore.currentUser?.nickname || 'User'"
        @click="goToProfile"
      >
        <el-avatar :size="38" :src="userStore.currentUser?.avatar || defaultAvatar" />
        <span class="online-dot"></span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, inject, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'

const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()

const activeView = inject('activeView', ref('chats'))
const setActiveView = inject('setActiveView', () => {})

const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'

const navItems = [
  { key: 'chats', icon: 'chat', labelKey: 'nav.messages' },
  { key: 'contacts', icon: 'person', labelKey: 'nav.contacts' },
  { key: 'groups', icon: 'groups', labelKey: 'nav.groups' }
]

const isMac = ref(false)

onMounted(() => {
  if (window.electronAPI) {
    isMac.value = window.electronAPI.platform === 'darwin'
  }
})

const goToSettings = () => router.push('/settings')
const goToProfile = () => router.push('/profile')
</script>

<style scoped>
.nav-rail {
  width: 64px;
  flex-shrink: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 28px 0 16px;
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  z-index: 11;
  position: relative;
  transition: background 0.3s ease;
}

/* macOS traffic-light clearance */
.nav-rail.is-mac {
  padding-top: 46px;
}

.drag-region {
  -webkit-app-region: drag;
}

.no-drag {
  -webkit-app-region: no-drag;
}

/* Navigation */
.rail-nav {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  width: 100%;
}

.rail-item {
  width: 52px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: 8px 0;
  border: none;
  background: transparent;
  border-radius: 14px;
  color: #64748b;
  cursor: pointer;
  transition: transform 0.28s cubic-bezier(0.34, 1.56, 0.64, 1), background 0.2s ease, color 0.2s ease, box-shadow 0.25s ease;
  position: relative;
  will-change: transform;
}

.rail-item:hover {
  transform: scale(1.18);
  z-index: 2;
}

.rail-item:active {
  transform: scale(1.08);
}

.rail-item.active {
  background: #ffffff;
  color: #0d9488;
  box-shadow: 0 6px 18px -7px rgba(20, 184, 166, 0.5), 0 2px 6px rgba(15, 23, 42, 0.08);
}

.rail-icon-wrap {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}

.rail-icon-wrap .material-icons-round {
  font-size: 24px;
}

.rail-badge {
  position: absolute;
  top: -6px;
  right: -8px;
  background: linear-gradient(135deg, #14b8a6 0%, #06b6d4 100%);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  padding: 2px 5px;
  border-radius: 9px;
  min-width: 16px;
  text-align: center;
  border: 2px solid #fff;
}

.rail-label {
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
}

/* Bottom */
.rail-bottom {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  width: 100%;
}

.settings-item .material-icons-round {
  transition: transform 0.3s ease;
}

.settings-item:hover .material-icons-round {
  transform: rotate(90deg);
}

.rail-avatar {
  position: relative;
  cursor: pointer;
  border-radius: 50%;
  transition: transform 0.25s ease;
}

.rail-avatar:hover {
  transform: scale(1.12);
}

.rail-avatar .el-avatar {
  border: 2px solid #e2e8f0;
  transition: border-color 0.25s ease;
}

.rail-avatar:hover .el-avatar {
  border-color: #14b8a6;
}

.online-dot {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 11px;
  height: 11px;
  background: var(--tg-online);
  border: 2px solid #fff;
  border-radius: 50%;
}

/* Dark Mode */
[data-theme="dark"] .nav-rail {
  background: rgba(24, 27, 33, 0.72);
}

[data-theme="dark"] .rail-item {
  color: #94a3b8;
}

[data-theme="dark"] .rail-item.active {
  background: #232730;
  color: #5eead4;
  box-shadow: 0 6px 18px -7px rgba(0, 0, 0, 0.55), 0 0 0 1px rgba(20, 184, 166, 0.25);
}

[data-theme="dark"] .rail-badge {
  border-color: #181B21;
}

[data-theme="dark"] .rail-avatar .el-avatar {
  border-color: #334155;
}

[data-theme="dark"] .rail-avatar:hover .el-avatar {
  border-color: #14b8a6;
}

[data-theme="dark"] .online-dot {
  border-color: #181B21;
}

/* Mobile */
@media (max-width: 768px) {
  .nav-rail {
    width: 56px;
    padding: 12px 0;
  }

  .nav-rail.is-mac {
    padding-top: 16px;
  }

  .rail-item {
    width: 48px;
  }
}
</style>

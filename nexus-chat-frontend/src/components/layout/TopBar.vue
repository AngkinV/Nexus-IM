<template>
  <!-- Prism workspace bar: 56px, breadcrumb + status pill + search/tune/avatar.
       Purely presentational — all state comes from the existing stores. -->
  <header
    class="top-bar drag-region shrink-0 h-14 bg-surface/90 backdrop-blur-md border-b border-outline-variant/30 flex items-center justify-between px-space-lg select-none"
  >
    <div class="flex items-center gap-space-sm font-label-md text-label-md text-on-surface-variant min-w-0">
      <span class="text-on-surface-variant/60 shrink-0">{{ $t('topBar.workspace') }}</span>
      <span class="material-symbols-outlined text-[14px] text-on-surface-variant/40 shrink-0">chevron_right</span>
      <span class="text-primary font-medium tracking-tight truncate">{{ title }}</span>
    </div>

    <div class="flex items-center gap-space-sm no-drag shrink-0">
      <!-- Status pill: monospace, dot + label, exactly like the export. -->
      <div
        class="flex items-center gap-space-xs px-2 py-1 rounded bg-surface-container-low border border-outline-variant/30 text-on-surface-variant font-code text-code"
      >
        <span class="w-1.5 h-1.5 rounded-full bg-primary inline-block"></span>
        <span>{{ status }}</span>
      </div>

      <!-- Inline search box only on the screens that have one (群组). -->
      <div
        v-if="searchPlaceholder"
        class="hidden md:flex items-center gap-space-xs h-8 px-2 rounded bg-surface-container-low border border-outline-variant/30 w-56"
      >
        <span class="material-symbols-outlined text-[16px] text-on-surface-variant/60">search</span>
        <input
          v-model="searchValue"
          type="text"
          :placeholder="searchPlaceholder"
          class="flex-1 bg-transparent text-on-surface font-label-sm text-label-sm outline-none placeholder-on-surface-variant/40"
        />
      </div>

      <button
        v-else
        class="w-8 h-8 flex items-center justify-center rounded text-on-surface-variant hover:text-on-surface hover:bg-surface-container transition-colors"
        type="button"
        :title="$t('topBar.search')"
        @click="emit('open-command')"
      >
        <span class="material-symbols-outlined text-[18px]">search</span>
      </button>

      <button
        class="w-8 h-8 flex items-center justify-center rounded text-on-surface-variant hover:text-on-surface hover:bg-surface-container transition-colors"
        type="button"
        :title="$t('topBar.filter')"
        @click="emit('open-command')"
      >
        <span class="material-symbols-outlined text-[18px]">tune</span>
      </button>

      <div
        class="w-8 h-8 rounded-full bg-primary flex items-center justify-center shrink-0 overflow-hidden cursor-pointer"
        :title="displayName"
        @click="$router.push('/profile')"
      >
        <img
          v-if="userStore.currentUser?.avatar"
          class="w-full h-full object-cover grayscale"
          :src="userStore.currentUser.avatar"
          alt=""
        />
        <span v-else class="material-symbols-outlined text-on-primary text-[18px]">person</span>
      </div>

      <!-- Frameless-window controls (Windows/Linux only; macOS keeps its traffic lights). -->
      <div v-if="isElectron && !isMac" class="win-controls no-drag">
        <button class="window-ctrl-btn" :title="$t('topBar.minimize')" type="button" @click="minimizeWindow">
          <svg width="10" height="10" viewBox="0 0 12 12">
            <rect width="10" height="1" x="1" y="6" fill="currentColor" />
          </svg>
        </button>
        <button class="window-ctrl-btn" :title="$t('topBar.maximize')" type="button" @click="maximizeWindow">
          <svg width="10" height="10" viewBox="0 0 12 12">
            <rect width="9" height="9" x="1.5" y="1.5" fill="none" stroke="currentColor" stroke-width="1" />
          </svg>
        </button>
        <button class="window-ctrl-btn close" :title="$t('topBar.close')" type="button" @click="closeWindow">
          <svg width="10" height="10" viewBox="0 0 12 12">
            <path d="M1 1 L11 11 M1 11 L11 1" stroke="currentColor" stroke-width="1.5" />
          </svg>
        </button>
      </div>
    </div>
  </header>
</template>

<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'

const { t } = useI18n()

const emit = defineEmits(['open-command'])

const userStore = useUserStore()
const chatStore = useChatStore()

const activeView = inject('activeView', ref('chats'))
const topBarSearch = inject('topBarSearch', ref(''))

const isElectron = ref(false)
const isMac = ref(false)

onMounted(() => {
  if (window.electronAPI) {
    isElectron.value = true
    isMac.value = window.electronAPI.platform === 'darwin'
  }
})

const minimizeWindow = () => window.electronAPI?.minimizeWindow?.()
const maximizeWindow = () => window.electronAPI?.maximizeWindow?.()
const closeWindow = () => window.electronAPI?.closeWindow?.()

const displayName = computed(() => {
  const u = userStore.currentUser
  return u?.nickname || u?.username || 'Developer'
})

const searchValue = ref('')
const searchPlaceholder = computed(() =>
  activeView.value === 'groups' ? t('topBar.searchGroups') : ''
)

// Breadcrumb title + status mirror the six exported screens.
const title = computed(() => {
  const chat = chatStore.activeChat
  if (activeView.value === 'contacts') return t('topBar.contacts')
  if (activeView.value === 'groups') return t('topBar.groups')
  if (activeView.value === 'ai') return t('topBar.ai')
  // chats
  if (!chat) return t('topBar.chats')
  const role =
    chat.role ||
    chat.memberRole ||
    (chat.type === 'GROUP' ? t('topBar.groupRole') : t('topBar.defaultRole'))
  return `${chat.name || chat.nickname || t('topBar.conversation')} (${role})`
})

const status = computed(() => {
  if (activeView.value === 'ai') return 'AI ENGINE ONLINE'
  return 'SYNCED'
})
</script>

<style scoped>
.drag-region {
  -webkit-app-region: drag;
}

.no-drag {
  -webkit-app-region: no-drag;
}

/* Frameless window controls — monochrome, 28px hit area. */
.win-controls {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: 4px;
}

.window-ctrl-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #8e9192;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.window-ctrl-btn:hover {
  background: #2a2a2d;
  color: #ffffff;
}

.window-ctrl-btn.close:hover {
  background: #b3261e;
  color: #ffffff;
}
</style>

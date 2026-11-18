<template>
  <!-- Prism sidebar: 256px rail, brand row, Navigation list, user footer.
       Same props/injects as before — only the skin changed. -->
  <aside
    class="nav-rail w-64 shrink-0 h-full bg-surface-container-lowest border-r border-outline-variant/30 flex flex-col justify-between select-none"
    :class="{ 'is-mac': isMac }"
  >
    <div class="flex flex-col w-full">
      <!-- Brand row -->
      <div
        class="rail-brand drag-region h-14 px-space-md flex items-center justify-between border-b border-outline-variant/20"
      >
        <div class="flex items-center gap-space-sm no-drag">
          <div class="w-6 h-6 rounded bg-primary flex items-center justify-center shrink-0">
            <span class="material-symbols-outlined text-on-primary text-[16px]">polyline</span>
          </div>
          <span class="rail-brand-text font-headline-md text-headline-md tracking-tight text-primary font-semibold">Prism</span>
        </div>
        <button
          class="no-drag w-7 h-7 flex items-center justify-center rounded text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors"
          :title="brandTitle"
          type="button"
          @click="onBrandAction"
        >
          <span class="material-symbols-outlined text-[18px]">{{ brandIcon }}</span>
        </button>
      </div>

      <!-- Navigation -->
      <div class="p-space-xs">
        <div
          class="rail-section-label px-space-sm pt-space-md pb-space-xs text-on-surface-variant/60 font-label-sm text-label-sm uppercase tracking-wider"
        >
          {{ $t('nav.sectionLabel') }}
        </div>
        <nav class="flex flex-col gap-0.5">
          <a
            v-for="item in navItems"
            :key="item.key"
            class="rail-item no-drag relative flex items-center gap-space-sm px-space-sm py-2 rounded cursor-pointer transition-colors"
            :class="
              activeView === item.key
                ? 'bg-surface-container text-primary font-body-md text-body-md font-medium'
                : 'text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface font-label-md text-label-md'
            "
            :aria-current="activeView === item.key ? 'page' : undefined"
            href="#"
            @click.prevent="setActiveView(item.key)"
          >
            <span class="material-symbols-outlined text-[18px]">{{ item.icon }}</span>
            <span class="rail-item-label flex-1 truncate">{{ item.label }}</span>
            <!-- Unread count keeps the badge, restyled monochrome. -->
            <span
              v-if="item.key === 'chats' && chatStore.totalUnreadCount > 0"
              class="rail-badge shrink-0 h-4 min-w-[1rem] px-1 rounded-full bg-primary text-on-primary font-code text-[11px] font-bold flex items-center justify-center"
            >{{ chatStore.totalUnreadCount > 99 ? '99+' : chatStore.totalUnreadCount }}</span>
          </a>
        </nav>
      </div>
    </div>

    <!-- User footer -->
    <div
      class="no-drag border-t border-outline-variant/20 p-space-sm flex items-center justify-between bg-surface-container-lowest"
    >
      <div class="flex items-center gap-space-sm min-w-0">
        <div
          class="w-8 h-8 rounded-full bg-primary flex items-center justify-center shrink-0 overflow-hidden cursor-pointer"
          @click="goToProfile"
        >
          <img
            v-if="userStore.currentUser?.avatar"
            class="w-full h-full object-cover grayscale"
            :src="userStore.currentUser.avatar"
            alt=""
          />
          <span v-else class="material-symbols-outlined text-on-primary text-[18px]">person</span>
        </div>
        <div class="rail-user-meta flex flex-col min-w-0">
          <span class="font-label-md text-label-md text-on-surface truncate">{{ displayName }}</span>
          <span class="font-label-sm text-label-sm text-on-surface-variant truncate">{{ displayMail }}</span>
        </div>
      </div>
      <a
        class="w-8 h-8 flex items-center justify-center rounded text-on-surface-variant hover:text-on-surface hover:bg-surface-container transition-colors shrink-0"
        href="#"
        title="Settings"
        @click.prevent="goToSettings"
      >
        <span class="material-symbols-outlined text-[18px]">settings</span>
      </a>
    </div>
  </aside>
</template>

<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'

const router = useRouter()
const { t } = useI18n()
const userStore = useUserStore()
const chatStore = useChatStore()

const activeView = inject('activeView', ref('chats'))
const setActiveView = inject('setActiveView', () => {})
const openNewChat = inject('openNewChat', null)
const requestAddContact = inject('requestAddContact', null)

const navItems = computed(() => [
  { key: 'chats', icon: 'chat_bubble', label: t('leftPanel.navChats') },
  { key: 'contacts', icon: 'person_book', label: t('leftPanel.navContacts') },
  { key: 'groups', icon: 'group', label: t('leftPanel.navGroups') },
  { key: 'ai', icon: 'auto_awesome', label: t('leftPanel.navAi') }
])

const brandIcon = computed(() =>
  activeView.value === 'contacts' ? 'person_add' : 'edit_square'
)
const brandTitle = computed(() =>
  activeView.value === 'contacts' ? t('leftPanel.addContact') : t('leftPanel.newConversation')
)

const onBrandAction = () => {
  // On the contacts pane the button reads "添加联系人", so it opens that dialog.
  if (activeView.value === 'contacts' && typeof requestAddContact === 'function') {
    requestAddContact()
    return
  }
  if (typeof openNewChat === 'function') {
    openNewChat()
    return
  }
  setActiveView('contacts')
}

const displayName = computed(() => {
  const u = userStore.currentUser
  return u?.nickname || u?.username || 'Developer'
})

const displayMail = computed(() => {
  const u = userStore.currentUser
  return u?.email || 'dev@prism.local'
})

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
.drag-region {
  -webkit-app-region: drag;
}

.no-drag {
  -webkit-app-region: no-drag;
}

/* macOS traffic-light clearance keeps the brand row below the buttons. */
.nav-rail.is-mac {
  padding-top: 0;
}

.nav-rail.is-mac .drag-region {
  padding-left: 78px;
}

/* Thin monochrome scrollbar for the nav list on short windows. */
.nav-rail {
  overflow: hidden;
}

/* Phones: the rail collapses to an icon strip so the panes keep their width. */
@media (max-width: 768px) {
  .nav-rail {
    width: 64px;
  }

  .nav-rail .p-space-xs {
    padding: 4px;
  }

  .rail-brand {
    display: none;
  }

  .rail-section-label {
    display: none;
  }

  .rail-item {
    justify-content: center;
    padding-left: 0;
    padding-right: 0;
  }

  .rail-item-label {
    display: none;
  }

  .rail-badge {
    position: absolute;
    top: 2px;
    right: 4px;
    height: 14px;
    min-width: 14px;
    font-size: 9px;
  }

  .rail-user-meta {
    display: none;
  }

  .nav-rail > div:last-child {
    justify-content: center;
    padding: 8px 0;
  }

  .nav-rail > div:last-child > a {
    display: none;
  }
}
</style>

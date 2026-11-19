<template>
  <div class="blade-shell" :class="{ 'is-collapsed': isFolded }">
    <!-- ── Collapsed: activity signal ribbon ─────────────────────────────── -->
    <div
      v-if="isFolded"
      class="blade-ribbon"
      :title="$t('leftPanel.expandList')"
      @click="toggleList"
    >
      <div class="flex flex-col items-center gap-2.5 w-full px-1">
        <button class="ribbon-expand" type="button" :title="$t('leftPanel.expandList')" @click.stop="toggleList">
          <span class="material-symbols-outlined text-[16px]">keyboard_double_arrow_right</span>
        </button>

        <!-- Unread capsule + hover preview -->
        <div class="relative group/popover flex flex-col items-center justify-center py-1 w-full" @click.stop>
          <div
            class="flex items-center justify-center gap-1 px-1.5 py-0.5 rounded-full"
            :class="unreadChats.length ? 'ribbon-capsule' : 'ribbon-capsule is-empty'"
            :title="$t('leftPanel.unreadCount', { n: unreadTotal })"
          >
            <span v-if="unreadTotal" class="w-1.5 h-1.5 rounded-full bg-primary animate-pulse"></span>
            <span class="font-code text-[10px] font-bold">{{ unreadTotal > 99 ? '99+' : unreadTotal }}</span>
          </div>

          <!-- Active chat micro avatar -->
          <div
            v-if="chatStore.activeChat"
            class="relative w-6 h-6 rounded-full overflow-hidden mt-2 ribbon-micro-avatar"
            :title="chatStore.activeChat.name"
            @click.stop="toggleList"
          >
            <img
              v-if="chatStore.activeChat.avatar"
              class="w-full h-full object-cover grayscale"
              :src="chatStore.activeChat.avatar"
              alt=""
            />
            <div v-else class="w-full h-full flex items-center justify-center bg-surface-container-high text-[10px] font-code text-primary">
              {{ initialOf(chatStore.activeChat.name) }}
            </div>
          </div>

          <!-- Popover -->
          <div class="ribbon-popover">
            <div class="flex items-center justify-between pb-2 mb-2.5 border-b border-outline-variant/20">
              <div class="flex items-center gap-1.5">
                <span class="material-symbols-outlined text-[16px] text-primary">mark_chat_unread</span>
                <span class="text-[14px] font-semibold text-primary">{{ $t('leftPanel.unreadPreview') }}</span>
                <span class="px-1.5 py-0.5 rounded-full bg-primary text-zinc-950 font-code text-[10px] font-bold">{{ unreadTotal }}</span>
              </div>
              <button class="text-[11px] font-code text-on-surface-variant hover:text-primary transition-colors flex items-center gap-0.5" type="button" @click.stop="markAllRead">
                <span class="material-symbols-outlined text-[13px]">done_all</span>
                <span>{{ $t('leftPanel.markAllRead') }}</span>
              </button>
            </div>
            <div v-if="unreadChats.length" class="space-y-1.5">
              <div
                v-for="(chat, i) in unreadChats.slice(0, 2)"
                :key="chat.id"
                class="ribbon-preview-row"
                :class="{ 'is-muted': i > 0 }"
                @click.stop="openFromRibbon(chat)"
              >
                <div class="relative shrink-0 w-8 h-8 rounded-full overflow-hidden bg-surface-container-high ring-1 ring-outline-variant/40 mt-0.5">
                  <img v-if="chat.avatar" class="w-full h-full object-cover grayscale" :src="chat.avatar" alt="" />
                  <div v-else class="w-full h-full flex items-center justify-center text-[11px] font-code text-primary">
                    {{ initialOf(chat.name) }}
                  </div>
                </div>
                <div class="flex-1 min-w-0">
                  <div class="flex items-center justify-between mb-0.5">
                    <span class="text-[14px] font-medium text-primary truncate">{{ chat.name }}</span>
                    <span class="font-code text-[11px] text-on-surface-variant/70 shrink-0 ml-2">{{ formatTime(chat.lastMessageTime) }}</span>
                  </div>
                  <p class="text-[14px] text-on-surface-variant truncate">{{ chat.lastMessage || $t('common.noMessages') }}</p>
                </div>
                <span v-if="chat.unreadCount" class="shrink-0 h-3.5 min-w-[14px] px-1 rounded-full bg-primary text-zinc-950 font-code text-[10px] font-bold flex items-center justify-center self-center">
                  {{ chat.unreadCount > 99 ? '99+' : chat.unreadCount }}
                </span>
                <span v-else class="material-symbols-outlined text-[14px] text-on-surface-variant/40 self-center">chevron_right</span>
              </div>
            </div>
            <p v-else class="text-[14px] text-on-surface-variant/70 py-2">{{ $t('leftPanel.noUnread') }}</p>
            <div class="mt-2.5 pt-2 border-t border-outline-variant/20 flex items-center justify-between text-on-surface-variant/60 font-code text-[11px]">
              <span>{{ $t('leftPanel.shortcutExpand') }}</span>
              <span class="text-primary hover:underline cursor-pointer" @click.stop="toggleList">{{ $t('leftPanel.expandAll', { n: chatStore.chats?.length || 0 }) }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Middle: vertical ribbon label -->
      <div class="flex flex-col items-center justify-center gap-3 my-auto py-2">
        <div class="ribbon-hairline"></div>
        <div class="ribbon-vertical flex items-center gap-2">
          <span class="ribbon-vertical-label">{{ ribbonLabel }}</span>
          <span class="w-1 h-1 rounded-full bg-primary/60 my-0.5"></span>
          <span class="ribbon-vertical-count">{{ paneCount }}</span>
        </div>
        <div class="ribbon-hairline"></div>
      </div>

      <!-- Bottom: expand tip -->
      <div class="flex flex-col items-center gap-1.5 pb-1" @click.stop="toggleList">
        <div class="w-6 h-6 rounded-md bg-surface-container-high/40 flex items-center justify-center text-on-surface-variant">
          <span class="material-symbols-outlined text-[15px]">tune</span>
        </div>
        <span class="font-code text-[9px] text-on-surface-variant/50 tracking-tighter">] {{ $t('leftPanel.expandShort') }}</span>
      </div>
    </div>

    <!-- ── Expanded: the standard list ───────────────────────────────────── -->
    <div v-show="!isFolded" class="left-panel">
    <AgentSessionList v-if="activeView === 'ai'" />
    <RoleList v-else-if="activeView === 'companion'" />

    <!-- Prism pane header: title + count + filter, search with ⌘K, filter tags -->
    <div
      v-if="activeView !== 'ai' && activeView !== 'companion'"
      class="p-space-md flex flex-col gap-space-sm shrink-0"
      :class="{ 'border-b border-outline-variant/20': activeView === 'contacts' }"
    >
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-space-xs min-w-0">
          <span class="font-headline-md text-[14px] leading-5 tracking-tight text-primary font-medium truncate">
            {{ paneTitle }}
          </span>
          <span
            v-if="activeView !== 'contacts'"
            class="shrink-0 px-2 py-0.5 rounded-full bg-surface-container-high text-primary font-code text-label-sm"
          >{{ paneCount }}</span>
        </div>
        <!-- Header actions differ per pane, as in the exported screens. -->
        <div v-if="activeView === 'contacts'" class="flex items-center gap-1 shrink-0">
          <button class="shrink-0 w-7 h-7 rounded bg-surface-container-low hover:bg-surface-container-high text-on-surface-variant hover:text-primary flex items-center justify-center transition-colors" :title="$t('contact.addContact')" type="button" @click="showAddContact = true">
            <span class="material-symbols-outlined text-[16px]">person_add</span>
          </button>
          <el-dropdown trigger="click" @command="handleMoreCommand">
            <button class="shrink-0 w-7 h-7 rounded bg-surface-container-low hover:bg-surface-container-high text-on-surface-variant hover:text-primary flex items-center justify-center transition-colors" :title="$t('leftPanel.moreOptions')" type="button">
              <span class="material-symbols-outlined text-[16px]">more_horiz</span>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="contact">{{ $t('contact.addContact') }}</el-dropdown-item>
                <el-dropdown-item command="group">{{ $t('chat.newGroup') }}</el-dropdown-item>
                <el-dropdown-item command="refresh" divided>{{ $t('leftPanel.refreshDirectory') }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <button
            class="blade-collapse-btn shrink-0 w-7 h-7 rounded bg-surface-container-low hover:bg-primary hover:text-on-primary text-on-surface-variant flex items-center justify-center transition-all duration-200 active:scale-90"
            :title="$t('leftPanel.collapseList')"
            type="button"
            @click="toggleList"
          >
            <span class="material-symbols-outlined text-[16px]">keyboard_double_arrow_left</span>
          </button>
        </div>
        <div v-else-if="activeView === 'groups'" class="flex items-center gap-1 shrink-0">
          <button
            class="shrink-0 w-7 h-7 rounded bg-surface-container-low hover:bg-surface-container-high text-on-surface-variant hover:text-primary flex items-center justify-center transition-colors"
            :title="$t('chat.newGroup')"
            type="button"
            @click="showCreateGroup = true"
          >
            <span class="material-symbols-outlined text-[16px]">add</span>
          </button>
          <button
            class="blade-collapse-btn shrink-0 w-7 h-7 rounded bg-surface-container-low hover:bg-primary hover:text-on-primary text-on-surface-variant flex items-center justify-center transition-all duration-200 active:scale-90"
            :title="$t('leftPanel.collapseList')"
            type="button"
            @click="toggleList"
          >
            <span class="material-symbols-outlined text-[16px]">keyboard_double_arrow_left</span>
          </button>
        </div>
        <div v-else class="flex items-center gap-1 shrink-0">
          <button
            class="shrink-0 w-7 h-7 rounded bg-surface-container-low hover:bg-surface-container-high text-on-surface-variant hover:text-primary flex items-center justify-center transition-colors"
            :title="$t('leftPanel.quickFilter')"
            type="button"
            @click="cycleFilter"
          >
            <span class="material-symbols-outlined text-[16px]">filter_list</span>
          </button>
          <button
            class="blade-collapse-btn shrink-0 w-7 h-7 rounded bg-surface-container-low hover:bg-primary hover:text-on-primary text-on-surface-variant flex items-center justify-center transition-all duration-200 active:scale-90"
            :title="$t('leftPanel.collapseList')"
            type="button"
            @click="toggleList"
          >
            <span class="material-symbols-outlined text-[16px]">keyboard_double_arrow_left</span>
          </button>
        </div>
      </div>

      <!-- Search -->
      <div class="relative w-full flex items-center">
        <span
          class="material-symbols-outlined absolute left-2.5 text-[16px] text-on-surface-variant/60 pointer-events-none"
        >search</span>
        <input
          ref="searchInput"
          v-model="searchQuery"
          class="w-full bg-surface-container-low text-primary placeholder-on-surface-variant/40 text-body-md font-body-md pl-8 pr-12 py-1.5 rounded border border-transparent focus:border-outline-variant/40 focus:outline-none focus:bg-surface-container transition-colors"
          :placeholder="searchPlaceholder"
          type="text"
          @keydown.meta.k.prevent="openPalette"
          @keydown.ctrl.k.prevent="openPalette"
        />
        <button
          class="absolute right-2 px-1.5 py-0.5 rounded bg-surface-container-high hover:bg-surface-container-highest text-on-surface-variant hover:text-primary font-code text-label-sm transition-colors"
          type="button"
          :title="activeView === 'contacts' ? $t('common.search') : $t('leftPanel.openPalette')"
          @click="activeView === 'contacts' ? focusSearch() : openPalette()"
        >{{ activeView === 'contacts' ? '⌘F' : '⌘K' }}</button>
      </div>

      <!-- Filter tags (chats only — the other panes have their own headers) -->
      <div v-if="activeView === 'chats'" class="flex items-center gap-1.5 pt-1 overflow-x-auto">
        <button
          v-for="tag in filterTags"
          :key="tag.key"
          class="shrink-0 px-2.5 py-1 rounded-full font-label-sm text-[14px] leading-5 tracking-tight transition-colors"
          :class="
            activeFilter === tag.key
              ? 'bg-primary text-on-primary font-medium'
              : 'bg-surface-container-low hover:bg-surface-container hover:text-primary text-on-surface-variant'
          "
          type="button"
          @click="activeFilter = tag.key"
        >{{ tag.label }}</button>
      </div>
    </div>

    <!-- List -->
    <div v-if="activeView !== 'ai' && activeView !== 'companion'" class="list-container" :class="{ 'is-directory': activeView === 'contacts' }">
      <template v-if="activeView === 'chats'">
        <ChatList :search-query="searchQuery" :filter="activeFilter" />
      </template>

      <template v-else-if="activeView === 'contacts'">
        <ContactList
          :search-query="searchQuery"
          :selected-id="selectedContactId"
          @select="handleContactSelect"
          @chat="openContactChat"
        />
      </template>

      <template v-else>
        <GroupList :search-query="searchQuery" :selected-id="selectedGroupId" @select="handleGroupSelect" />
      </template>
    </div>

    <!-- Link status strip (chats pane) -->
    <div
      v-if="activeView === 'chats'"
      class="shrink-0 p-3 bg-surface-container-low/50 flex items-center justify-between"
    >
      <div class="flex items-center gap-2 min-w-0">
        <span
          class="w-2 h-2 shrink-0 rounded-full"
          :class="connectionStore.isConnected ? 'bg-primary animate-pulse' : 'bg-on-surface-variant/40'"
        ></span>
        <span class="font-label-sm text-label-sm text-on-surface-variant uppercase tracking-wider truncate">
          {{ connectionStore.isConnected ? $t('leftPanel.linkActive') : $t('leftPanel.linkReconnecting') }}
        </span>
      </div>
      <span class="shrink-0 font-code text-label-sm text-on-surface-variant/60">v{{ appVersion }}</span>
    </div>

    <!-- Directory totals (contacts pane) -->
    <div
      v-if="activeView === 'contacts'"
      class="shrink-0 px-space-md py-2.5 bg-surface-container-low border-t border-outline-variant/20 flex items-center justify-between"
    >
      <span class="font-code text-label-sm text-on-surface-variant/70">
        {{ $t('leftPanel.directoryTotal', { n: contactStore.contacts?.length || 0 }) }}
      </span>
      <div class="flex items-center gap-1.5 text-on-surface-variant font-code text-label-sm">
        <span class="w-1.5 h-1.5 rounded-full bg-primary"></span>
        <span>{{ $t('leftPanel.directoryOnline', { n: contactStore.onlineContacts?.length || 0 }) }}</span>
      </div>
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
  </div>
</template>

<script setup>
import { computed, ref, inject, watch, onMounted, onBeforeUnmount } from 'vue'
import dayjs from 'dayjs'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { useContactStore } from '@/stores/contact'
import { useConnectionStore } from '@/stores/connection'
import { version as appVersion } from '../../../package.json'
import ChatList from '@/components/chat/ChatList.vue'
import ContactList from '@/components/contact/ContactList.vue'
import GroupList from '@/components/chat/GroupList.vue'
import AgentSessionList from '@/components/agent/AgentSessionList.vue'
import RoleList from '@/components/companion/RoleList.vue'
import CreateGroupModal from '@/components/chat/CreateGroupModal.vue'
import AddContactModal from '@/components/contact/AddContactModal.vue'
import { useAgentStore } from '@/stores/agent'
import { useCompanionStore } from '@/stores/companion'

const { t } = useI18n()
const userStore = useUserStore()
const chatStore = useChatStore()
const contactStore = useContactStore()
const connectionStore = useConnectionStore()
const agentStore = useAgentStore()
const companionStore = useCompanionStore()

const activeView = inject('activeView', ref('chats'))
const setActiveView = inject('setActiveView', () => {})
const openPalette = inject('openPalette', () => {})
const createGroupSignal = inject('createGroupSignal', ref(0))

// ── Blade collapse (list 320px <-> 44px ribbon) ─────────────────────────────
// State lives in Main.vue so the chat header can offer a restore button.
const listCollapsed = inject('listCollapsed', ref(false))
const toggleList = inject('toggleList', () => {})
const isMobile = inject('isMobile', ref(false))

// Phones have no room for a ribbon: the blade stays expanded there.
const isFolded = computed(() => listCollapsed.value && !isMobile.value)

const ribbonLabel = computed(() => paneTitle.value.toUpperCase())

const unreadChats = computed(() =>
  (chatStore.sortedChats || []).filter((c) => (c.unreadCount || 0) > 0)
)
const unreadTotal = computed(() =>
  unreadChats.value.reduce((sum, c) => sum + (c.unreadCount || 0), 0)
)
const initialOf = (name) => (name || '?').trim().charAt(0) || '?'
const formatTime = (time) => (time ? dayjs(time).format('HH:mm') : '')

const markAllRead = () => {
  ;(chatStore.chats || []).forEach((c) => { c.unreadCount = 0 })
}

const openFromRibbon = (chat) => {
  if (chat.isAi) {
    setActiveView('ai')
    chatStore.setActiveChat(chat)
  } else {
    setActiveView('chats')
    chatStore.setActiveChat(chat)
  }
  toggleList()
}

const searchQuery = ref('')
const showCreateGroup = ref(false)
const showAddContact = ref(false)

// Same counter pattern for "add contact" (nav-rail button, command palette).
const addContactSignal = inject('addContactSignal', ref(0))
watch(addContactSignal, () => {
  showAddContact.value = true
})

const handleMoreCommand = (command) => {
  if (command === 'contact') showAddContact.value = true
  else if (command === 'group') showCreateGroup.value = true
  else if (command === 'refresh' && userStore.currentUser?.id) {
    contactStore.fetchContacts(userStore.currentUser.id)
  }
}

// ⌘F focuses the directory search while the contacts pane is open.
const searchInput = ref(null)
const focusSearch = () => searchInput.value?.focus()
const onDirectoryKeydown = (event) => {
  if (activeView.value !== 'contacts') return
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'f') {
    event.preventDefault()
    focusSearch()
  }
}
onMounted(() => window.addEventListener('keydown', onDirectoryKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onDirectoryKeydown))

// The standby screen asks for the create-group dialog through this signal.
watch(createGroupSignal, () => {
  showCreateGroup.value = true
})

// Pane header adapts to whichever nav section is active.
const paneTitle = computed(() => {
  if (activeView.value === 'contacts') return t('leftPanel.contacts')
  if (activeView.value === 'groups') return t('leftPanel.groups')
  if (activeView.value === 'ai') return t('leftPanel.ai')
  if (activeView.value === 'companion') return t('leftPanel.navCompanion')
  return t('leftPanel.chats')
})

const paneCount = computed(() => {
  if (activeView.value === 'contacts') return contactStore.contacts?.length || 0
  if (activeView.value === 'groups') return chatStore.groupChats?.length || 0
  if (activeView.value === 'ai') return agentStore.sessions?.length || 0
  if (activeView.value === 'companion') return companionStore.roles?.length || 0
  return chatStore.chats?.length || 0
})

const searchPlaceholder = computed(() => {
  if (activeView.value === 'contacts') return t('leftPanel.searchContacts')
  if (activeView.value === 'groups') return t('leftPanel.searchGroups')
  return t('leftPanel.searchChats')
})

const activeFilter = ref('all')

const filterTags = computed(() => [
  { key: 'all', label: t('leftPanel.filterAll') },
  { key: 'unread', label: t('leftPanel.filterUnread', { n: chatStore.totalUnreadCount || 0 }) },
  { key: 'starred', label: t('leftPanel.filterStarred') },
  { key: 'groups', label: t('leftPanel.filterTeams') }
])

const cycleFilter = () => {
  const keys = filterTags.value.map((t) => t.key)
  const i = keys.indexOf(activeFilter.value)
  activeFilter.value = keys[(i + 1) % keys.length]
}

const handleGroupCreated = (group) => {
  chatStore.setActiveChat(group)
  setActiveView('chats')
}

const handleContactAdded = () => {}

const selectedContactId = inject('selectedContactId', ref(null))
const selectedGroupId = inject('selectedGroupId', ref(null))

// Create or open the direct chat with a contact.
const openContactChat = async (contact) => {
  await chatStore.createDirectChat(userStore.currentUser?.id, contact)
  setActiveView('chats')
}

// Desktop: a row click selects the record and the middle pane shows its
// detail card. Phones have no room for that pane, so they keep the direct jump.
const handleContactSelect = (contact) => {
  if (isMobile.value) return openContactChat(contact)
  selectedContactId.value = contact.id
}

const handleGroupSelect = (group) => {
  if (isMobile.value) {
    chatStore.setActiveChat(group)
    setActiveView('chats')
    return
  }
  selectedGroupId.value = group.id
}
</script>

<style scoped>
/* ── Blade shell: 320px list <-> 44px signal ribbon ─────────────────────── */
.blade-shell {
  width: 320px;
  flex-shrink: 0;
  height: 100%;
  min-width: 0;
  display: flex;
  background: #0e0e11;
  border-right: 1px solid rgba(68, 71, 72, 0.2);
  position: relative;
  z-index: 10;
  transition: width 360ms cubic-bezier(0.16, 1, 0.3, 1);
}

.blade-shell.is-collapsed {
  width: 44px;
}

/* Phones: the pane is the whole screen, so the blade just fills it. */
@media (max-width: 768px) {
  .blade-shell,
  .blade-shell.is-collapsed {
    width: auto;
    flex: 1;
    min-width: 0;
    border-right: none;
  }

  /* No ribbon on phones — the fold controls would do nothing. */
  .blade-collapse-btn {
    display: none;
  }
}

/* ── Ribbon ─────────────────────────────────────────────── */
.blade-ribbon {
  width: 44px;
  flex-shrink: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
  background: #0e0e11;
  border-right: 1px solid rgba(68, 71, 72, 0.3);
  cursor: pointer;
  user-select: none;
  position: relative;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: background 0.2s ease;
}

.blade-ribbon:hover {
  background: rgba(27, 27, 30, 0.6);
}

.ribbon-expand {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  border: 1px solid rgba(68, 71, 72, 0.3);
  background: rgba(42, 42, 45, 0.6);
  color: #c4c7c8;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.ribbon-expand:hover {
  background: #ffffff;
  color: #2f3131;
  border-color: rgba(255, 255, 255, 0.3);
}

.ribbon-expand:active {
  transform: scale(0.9);
}

.ribbon-capsule {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 2px 6px;
  border-radius: 9999px;
  background: #2a2a2d;
  border: 1px solid rgba(68, 71, 72, 0.4);
  color: #ffffff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
  transition: border-color 0.15s ease;
}

.ribbon-capsule.is-empty {
  color: #71717a;
}

.ribbon-capsule:hover {
  border-color: rgba(255, 255, 255, 0.4);
}

.ribbon-micro-avatar {
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.3);
  filter: grayscale(100%);
  transition: box-shadow 0.15s ease;
}

.ribbon-micro-avatar:hover {
  box-shadow: 0 0 0 1px #ffffff;
}

.ribbon-hairline {
  width: 1px;
  height: 40px;
  background: linear-gradient(to bottom, transparent, rgba(68, 71, 72, 0.4), transparent);
}

.ribbon-vertical {
  writing-mode: vertical-rl;
  text-orientation: mixed;
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(196, 199, 200, 0.7);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  letter-spacing: 0.25em;
  text-transform: uppercase;
  user-select: none;
  transition: color 0.15s ease;
}

.blade-ribbon:hover .ribbon-vertical {
  color: #ffffff;
}

.ribbon-vertical-label {
  font-weight: 600;
  letter-spacing: 0.25em;
}

.ribbon-vertical-count {
  color: rgba(255, 255, 255, 0.5);
}

/* Unread popover */
.ribbon-popover {
  position: absolute;
  left: 52px;
  top: 4px;
  width: 330px;
  max-width: calc(100vw - 150px);
  border-radius: 16px;
  background: rgba(14, 14, 17, 0.95);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 14px;
  z-index: 50;
  text-align: left;
  cursor: default;
  opacity: 0;
  transform: scale(0.95);
  pointer-events: none;
  transition: opacity 0.2s ease, transform 0.2s ease;
  box-shadow: 0 20px 45px rgba(0, 0, 0, 0.8), 0 0 0 1px rgba(255, 255, 255, 0.1) inset;
}

.group\/popover:hover .ribbon-popover {
  opacity: 1;
  transform: scale(1);
  pointer-events: auto;
}

.ribbon-preview-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px;
  border-radius: 12px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.2);
  cursor: pointer;
  transition: background 0.15s ease;
}

.ribbon-preview-row.is-muted {
  background: #0e0e11;
  border-color: rgba(68, 71, 72, 0.1);
}

.ribbon-preview-row:hover {
  background: #1f1f22;
}

.left-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #0e0e11;
  min-width: 0;
  flex: 1;
}

.list-container {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 0 8px;
}

/* The contacts directory scrolls itself (it owns the A–Z index rail). */
.list-container.is-directory {
  padding: 0;
  overflow: hidden;
}

.list-container::-webkit-scrollbar {
  width: 6px;
}

.list-container::-webkit-scrollbar-track {
  background: transparent;
}

.list-container::-webkit-scrollbar-thumb {
  background: #353438;
  border-radius: 9999px;
}

.list-container::-webkit-scrollbar-thumb:hover {
  background: #8e9192;
}
</style>

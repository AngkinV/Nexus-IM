<template>
  <div class="left-panel">
    <AgentSessionList v-if="activeView === 'ai'" />

    <!-- Prism pane header: title + count + filter, search with ⌘K, filter tags -->
    <div
      v-if="activeView !== 'ai'"
      class="p-space-md flex flex-col gap-space-sm shrink-0"
      :class="{ 'border-b border-outline-variant/20': activeView === 'contacts' }"
    >
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-space-xs min-w-0">
          <span class="font-headline-md text-headline-md tracking-tight text-primary font-medium truncate">
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
        </div>
        <button
          v-else-if="activeView === 'groups'"
          class="shrink-0 w-7 h-7 rounded bg-surface-container-low hover:bg-surface-container-high text-on-surface-variant hover:text-primary flex items-center justify-center transition-colors"
          :title="$t('chat.newGroup')"
          type="button"
          @click="showCreateGroup = true"
        >
          <span class="material-symbols-outlined text-[16px]">add</span>
        </button>
        <button
          v-else
          class="shrink-0 w-7 h-7 rounded bg-surface-container-low hover:bg-surface-container-high text-on-surface-variant hover:text-primary flex items-center justify-center transition-colors"
          :title="$t('leftPanel.quickFilter')"
          type="button"
          @click="cycleFilter"
        >
          <span class="material-symbols-outlined text-[16px]">filter_list</span>
        </button>
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
          class="shrink-0 px-2.5 py-1 rounded-full font-label-sm text-label-sm tracking-tight transition-colors"
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
    <div v-if="activeView !== 'ai'" class="list-container" :class="{ 'is-directory': activeView === 'contacts' }">
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
</template>

<script setup>
import { computed, ref, inject, watch, onMounted, onBeforeUnmount } from 'vue'
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
import CreateGroupModal from '@/components/chat/CreateGroupModal.vue'
import AddContactModal from '@/components/contact/AddContactModal.vue'

const { t } = useI18n()
const userStore = useUserStore()
const chatStore = useChatStore()
const contactStore = useContactStore()
const connectionStore = useConnectionStore()

const activeView = inject('activeView', ref('chats'))
const setActiveView = inject('setActiveView', () => {})
const openPalette = inject('openPalette', () => {})
const createGroupSignal = inject('createGroupSignal', ref(0))

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
  return t('leftPanel.chats')
})

const paneCount = computed(() => {
  if (activeView.value === 'contacts') return contactStore.contacts?.length || 0
  if (activeView.value === 'groups') return chatStore.groupChats?.length || 0
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
const isMobile = inject('isMobile', ref(false))

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
.left-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #0e0e11;
  min-width: 0;
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

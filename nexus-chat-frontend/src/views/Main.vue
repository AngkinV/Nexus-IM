<template>
  <div class="main-layout">
    <NavRail class="panel-nav" />

    <!-- Prism shell: fixed 56px workspace bar above the three panes. -->
    <div class="shell-column">
      <TopBar @open-command="openPalette" />
      <div class="shell-panes">
        <LeftPanel class="panel-left" />
        <MiddlePanel
          class="panel-middle"
          :class="{ 'mobile-active': isMobile && chatStore.activeChat }"
          @open-new-chat="handleOpenNewChat"
        />
        <RightPanel class="panel-right" :class="{ 'collapsed': !showRightPanel || isAiChat }" />
      </div>
    </div>

    <!-- Companion 3D avatar + panel -->
    <CompanionAvatar3D
      :active-role="companionStore.activeRole"
      :status="companionStore.status"
      @open="showCompanion = true"
    />
    <CompanionPanel v-model="showCompanion" />

    <!-- Prism c5: ⌘K command palette (global, overlays whatever is beneath). -->
    <CommandPalette v-model="paletteOpen" @action="handlePaletteAction" />

    <!-- Creator's Eye: fullscreen transition played when entering the AI section -->
    <CreatorEyeWarp />

    <!-- Mobile overlay for right panel -->
    <div
      v-if="isMobile && showRightPanel"
      class="mobile-overlay"
      @click="toggleRightPanel"
    ></div>

    <!-- Call components -->
    <IncomingCallModal />
    <OutgoingCallModal />
    <CallView />
    <CallEndModal />
  </div>
</template>

<script setup>
import { ref, provide, onMounted, onUnmounted, computed, watch } from 'vue'
import NavRail from '@/components/layout/NavRail.vue'
import TopBar from '@/components/layout/TopBar.vue'
import LeftPanel from '@/components/layout/LeftPanel.vue'
import MiddlePanel from '@/components/layout/MiddlePanel.vue'
import RightPanel from '@/components/layout/RightPanel.vue'
import IncomingCallModal from '@/components/call/IncomingCallModal.vue'
import OutgoingCallModal from '@/components/call/OutgoingCallModal.vue'
import CallView from '@/components/call/CallView.vue'
import CallEndModal from '@/components/call/CallEndModal.vue'
import CompanionAvatar3D from '@/components/companion/CompanionAvatar3D.vue'
import CompanionPanel from '@/components/companion/CompanionPanel.vue'
import CommandPalette from '@/components/layout/CommandPalette.vue'
import CreatorEyeWarp from '@/components/warp/CreatorEyeWarp.vue'
import { useCreatorWarp, WARP_PHASE } from '@/composables/useCreatorWarp'
import { useUserStore } from '@/stores/user'
import { useContactStore } from '@/stores/contact'
import { useChatStore } from '@/stores/chat'
import { useMessageStore } from '@/stores/message'
import { useCallStore, CallStatus } from '@/stores/call'
import { useCompanionStore } from '@/stores/companion'
import { useAgentStore } from '@/stores/agent'
import { ElMessage } from 'element-plus'
import websocket from '@/services/websocket'
import syncService from '@/services/syncService'

const userStore = useUserStore()
const contactStore = useContactStore()
const chatStore = useChatStore()
const messageStore = useMessageStore()
const callStore = useCallStore()
const companionStore = useCompanionStore()
const agentStore = useAgentStore()
const creatorWarp = useCreatorWarp()

const showRightPanel = ref(false)
const showCompanion = ref(false)
const paletteOpen = ref(false)

// Active left-nav view, driven by the NavRail and consumed by the LeftPanel.
const activeView = ref('chats')
const applyActiveView = (view) => {
  activeView.value = view
  // The AI section is a single canvas: opening it shows the assistant right away.
  // On phones the session list stays in front until one is picked.
  if (view !== 'ai' || isMobile.value) return
  chatStore.setActiveChat(chatStore.sortedChats.find((c) => c.id === 'ai-assistant'))
}

const setActiveView = (view) => {
  if (view === 'ai') enterAi(() => applyActiveView('ai'))
  else applyActiveView(view)
}

// Is the AI assistant currently selected? Used to hide the right info panel for the virtual chat.
const isAiChat = computed(() => chatStore.activeChat?.id === 'ai-assistant' || chatStore.activeChat?.type === 'AI')

// ── Entering the AI section ──────────────────────────────────────────────────
// Every way into AI goes through here. Coming from outside, the Creator's Eye plays
// first and `commit` (the caller's own way of opening AI) only runs once the user
// confirms on the arrival stage; ESC backs out and nothing changes. Already inside
// AI, `commit` runs straight away.
let enteringAi = false
const enterAi = (commit) => {
  if (enteringAi || activeView.value === 'ai' || isAiChat.value) {
    commit()
    return
  }
  if (creatorWarp.state.phase !== WARP_PHASE.IDLE) return
  creatorWarp.trigger({
    onEnter: () => {
      enteringAi = true
      try {
        commit()
      } finally {
        enteringAi = false
      }
    }
  })
}

// A call must never ring behind the stage: the warp yields to it immediately.
watch(
  () => callStore.callStatus,
  (status) => {
    if (status !== CallStatus.IDLE && status !== CallStatus.ENDED) creatorWarp.cancel()
  }
)

// Mobile detection
const isMobile = ref(window.innerWidth <= 768)
const handleResize = () => {
  isMobile.value = window.innerWidth <= 768
}

// Handle open new chat from MiddlePanel
const handleOpenNewChat = () => {
  // Switch to the contacts view so the user can pick someone to message.
  setActiveView('contacts')
}

// ── Prism c5: command palette ────────────────────────────────────────────────
const openPalette = () => {
  paletteOpen.value = true
}

const openAiAssistant = () => enterAi(() => {
  activeView.value = 'ai'
  // AI 助手是 sortedChats 里的虚拟条目，不在 chats 数组里
  chatStore.setActiveChat(chatStore.sortedChats.find((c) => c.id === 'ai-assistant'))
})

const markAllRead = () => {
  ;(chatStore.chats || []).forEach((c) => { c.unreadCount = 0 })
}

const handlePaletteAction = (command) => {
  switch (command) {
    case 'search':
      // Focus the left-pane search field the palette points at.
      setActiveView('chats')
      setTimeout(() => {
        document.querySelector('.left-panel input[type="text"]')?.focus()
      }, 60)
      break
    case 'new-chat':
      handleOpenNewChat()
      break
    case 'new-group':
      requestCreateGroup()
      break
    case 'mark-read':
      markAllRead()
      break
    case 'ai':
      openAiAssistant()
      break
    case 'nav-1':
      setActiveView('chats')
      break
    case 'nav-2':
      setActiveView('contacts')
      break
    case 'nav-3':
      setActiveView('groups')
      break
    case 'nav-4':
      setActiveView('ai')
      break
    case 'help':
    case 'palette':
      paletteOpen.value = true
      break
    default:
      break
  }
}

// Global ⌘K / Ctrl+K + the shortcuts the palette advertises.
const handleGlobalKeydown = (event) => {
  const mod = event.metaKey || event.ctrlKey
  if (!mod) return
  const key = event.key.toLowerCase()

  if (key === 'k') {
    event.preventDefault()
    paletteOpen.value = !paletteOpen.value
    return
  }
  // While the palette is open it owns the keyboard.
  if (paletteOpen.value) return

  if (key === 'n') {
    event.preventDefault()
    if (activeView.value !== 'ai') return handleOpenNewChat()
    agentStore.newSession().catch((err) => ElMessage.error(err.message))
  } else if (key === 'g') {
    event.preventDefault()
    setActiveView('groups')
  } else if (key === 'j') {
    event.preventDefault()
    openAiAssistant()
  } else if (key === '/') {
    event.preventDefault()
    paletteOpen.value = true
  } else if (['1', '2', '3', '4'].includes(key)) {
    event.preventDefault()
    handlePaletteAction(`nav-${key}`)
  }
}

// Provide global state for panels
const toggleRightPanel = () => {
  showRightPanel.value = !showRightPanel.value
}
provide('toggleRightPanel', toggleRightPanel)
provide('showRightPanel', showRightPanel)
provide('isMobile', isMobile)
provide('activeView', activeView)
provide('setActiveView', setActiveView)
provide('enterAi', enterAi)
provide('openPalette', openPalette)

// A tiny event counter used by the standby screen to ask the left pane to open
// its create-group dialog (the dialog instance lives inside LeftPanel).
const createGroupSignal = ref(0)
provide('createGroupSignal', createGroupSignal)
const requestCreateGroup = () => {
  setActiveView('groups')
  createGroupSignal.value += 1
}
provide('requestCreateGroup', requestCreateGroup)

// Which contact / group the directory panes have selected. The middle pane
// shows that record's detail card (Prism c2 / c3); null keeps the old view.
const selectedContactId = ref(null)
const selectedGroupId = ref(null)
provide('selectedContactId', selectedContactId)
provide('selectedGroupId', selectedGroupId)

// "Add contact" (nav-rail button on the contacts pane) uses the same counter.
const addContactSignal = ref(0)
provide('addContactSignal', addContactSignal)
provide('requestAddContact', () => {
  setActiveView('contacts')
  addContactSignal.value += 1
})

// Same pattern for the chat header's search button: the search dialog lives
// inside RightPanel, so the header asks for it through a counter.
const searchSignal = ref(0)
provide('searchSignal', searchSignal)
provide('requestSearch', () => { searchSignal.value += 1 })

/**
 * Optimized startup flow (WeChat-style):
 *
 * Phase 1: Load from IndexedDB instantly (UI opens immediately)
 * Phase 2: Connect WebSocket (close message-loss window)
 * Phase 3: Parallel network sync (delta sync + pending requests)
 * Phase 4: Flush offline outbox
 */
onMounted(async () => {
  // Add resize listener for mobile detection
  window.addEventListener('resize', handleResize)
  window.addEventListener('keydown', handleGlobalKeydown)

  // Ensure user is loaded
  if (!userStore.currentUser) {
    userStore.loadUserFromStorage()
  }

  const userId = userStore.currentUser?.id
  if (!userId) return

  try {
    // ── Phase 1: Instant load from IndexedDB cache ──
    await Promise.all([
      chatStore.loadFromCache(),
      contactStore.loadFromCache()
    ])

    // ── Phase 2: Connect WebSocket immediately ──
    // No more waiting for API fetches before connecting.
    // The unified channel receives all events from the moment of connect.
    websocket.connect(userId, async () => {
      // Initialize call service after WebSocket connects
      callStore.initialize()

      // Initialize companion widget data
      companionStore.ensureInitialized(userId).catch(() => {})

      // ── Phase 3: Parallel network sync (runs after WS is connected) ──
      try {
        await Promise.all([
          chatStore.fetchChats(userId),
          contactStore.fetchContacts(userId),
          contactStore.fetchPendingRequests(userId),
          syncService.performDeltaSync({
            chatStore,
            messageStore,
            contactStore,
            userStore
          })
        ])
      } catch (error) {
        console.error('[Main] Network sync failed:', error)
      }

      // ── Phase 4: Flush offline outbox ──
      try {
        await syncService.flushPendingMessages((msg) => {
          return websocket.sendMessage(
            msg.chatId, msg.senderId, msg.content, msg.messageType, msg.fileUrl
          ).promise
        })
      } catch (error) {
        console.error('[Main] Offline flush failed:', error)
      }
    })
  } catch (error) {
    console.error('[Main] Startup failed:', error)
  }
})

// Cleanup on unmount
onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('keydown', handleGlobalKeydown)
  callStore.cleanup()
})
</script>

<style scoped>
.main-layout {
  display: flex;
  width: 100vw;
  height: 100vh;
  background: #131316;
  overflow: hidden;
  position: relative;
}

/* Right of the 256px rail: bar on top, panes below. */
.shell-column {
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.shell-panes {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
  /* Containing block for the mobile overlay pane, so the sliding chat panel
     covers only the pane area and never the nav rail. */
  position: relative;
}

.panel-nav {
  flex-shrink: 0;
  height: 100%;
  z-index: 11;
}

.panel-left {
  width: 320px;
  min-width: 280px;
  max-width: 380px;
  border-right: 1px solid rgba(68, 71, 72, 0.2);
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #0e0e11;
  z-index: 10;
  position: relative;
}

.panel-middle {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  min-width: 360px;
  position: relative;
  background: #131316;
  z-index: 1;
}

.panel-right {
  width: 25%;
  min-width: 280px;
  max-width: 380px;
  border-left: 1px solid rgba(68, 71, 72, 0.2);
  height: 100%;
  background: #131316;
  z-index: 5;
  position: relative;
}

.panel-right.collapsed {
  margin-right: -25%;
  display: none;
}

/* Dark Mode */
/* Tablet breakpoint */
@media (max-width: 1024px) {
  .panel-left {
    width: 300px;
    min-width: 260px;
  }

  .panel-right {
    width: 280px;
    min-width: 260px;
  }
}

/* Mobile breakpoint */
@media (max-width: 768px) {
  .main-layout {
    position: relative;
  }

  .panel-left {
    position: relative;
    flex: 1;
    width: auto;
    max-width: none;
    min-width: 0;
    height: 100%;
    z-index: 10;
    border-right: none;
  }

  .panel-middle {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    min-width: unset;
    z-index: 20;
    transform: translateX(100%);
    transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    background: #131316;
  }

  .panel-middle.mobile-active {
    transform: translateX(0);
  }

  .panel-right {
    position: fixed;
    right: 0;
    top: 0;
    bottom: 0;
    width: 85%;
    max-width: 320px;
    min-width: unset;
    z-index: 100;
    transform: translateX(100%);
    transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    box-shadow: -4px 0 20px rgba(0, 0, 0, 0.15);
  }

  .panel-right:not(.collapsed) {
    transform: translateX(0);
  }

  .panel-right.collapsed {
    margin-right: 0;
    display: flex;
  }

  .panel-right {
    box-shadow: none;
  }
}

/* Small phones */
@media (max-width: 375px) {
  .panel-right {
    width: 90%;
  }
}

/* Mobile overlay */
.mobile-overlay {
  display: none;
}

@media (max-width: 768px) {
  .mobile-overlay {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    z-index: 99;
    animation: fadeIn 0.2s ease;
  }

  @keyframes fadeIn {
    from { opacity: 0; }
    to { opacity: 1; }
  }
}
</style>

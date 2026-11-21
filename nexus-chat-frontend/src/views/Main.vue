<template>
  <div class="main-layout">
    <NavRail class="panel-nav" />

    <!-- Prism shell: fixed 56px workspace bar above the three panes. -->
    <div class="shell-column">
      <TopBar @open-command="openPalette" />
      <div class="shell-panes">
        <LeftPanel class="panel-left" />
        <RoleChatView
          v-if="activeView === 'companion' && companionStore.activeRole"
          class="panel-middle"
          @back="closeRoleChat"
          @go-ai="openAiAssistant"
          @mood="tavernMood = $event"
        />
        <div
          v-else-if="activeView === 'companion'"
          class="panel-middle flex flex-col items-center justify-center gap-3 text-on-surface-variant"
          role="status"
        >
          <span class="material-symbols-outlined text-[32px]" aria-hidden="true">chat_bubble_outline</span>
          <p>{{ $t('chat.selectChat') }}</p>
        </div>
        <MiddlePanel
          v-else
          class="panel-middle"
          :class="{ 'mobile-active': isMobile && chatStore.activeChat }"
          @open-new-chat="handleOpenNewChat"
        />
        <RightPanel class="panel-right" :class="{ 'collapsed': !showRightPanel || isAiChat }" />
      </div>
    </div>

    <!-- Desktop pet. v-if, not v-show: turning it off must release the WebGL
         context and the loaded VRM, not just hide them. -->
    <CompanionAvatar3D
      v-if="settings.ready && userStore.isLoggedIn && String(companionStore.rolesOwnerId) === String(userStore.currentUser.id) && settings.petVisible && activeView !== 'companion'"
      :active-role="companionStore.activeRole"
      :status="companionStore.status"
      :growth="companionStore.growth"
      :mood="tavernMood"
      @open="openRoleChat"
    />

    <!-- Prism c5: ⌘K command palette (global, overlays whatever is beneath). -->
    <CommandPalette v-model="paletteOpen" @action="handlePaletteAction" />

    <!-- Creator's Eye: fullscreen transition played when entering the AI section -->
    <TavernEntrance />

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
import RoleChatView from '@/views/companion/RoleChatView.vue'
import CommandPalette from '@/components/layout/CommandPalette.vue'
import TavernEntrance from '@/components/warp/TavernEntrance.vue'
import { useTavernEntrance, ENTRANCE_PHASE } from '@/composables/useTavernEntrance'
import { useUserStore } from '@/stores/user'
import { useContactStore } from '@/stores/contact'
import { useChatStore } from '@/stores/chat'
import { useMessageStore } from '@/stores/message'
import { useCallStore, CallStatus } from '@/stores/call'
import { useCompanionStore } from '@/stores/companion'
import { useSettingsStore } from '@/stores/settings'
import { AI_ASSISTANT_CHAT_ID, useAgentStore } from '@/stores/agent'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import websocket from '@/services/websocket'
import syncService from '@/services/syncService'

const userStore = useUserStore()
const contactStore = useContactStore()
const chatStore = useChatStore()
const messageStore = useMessageStore()
const callStore = useCallStore()
const companionStore = useCompanionStore()
const settings = useSettingsStore()
const agentStore = useAgentStore()
const tavernEntrance = useTavernEntrance()
const { t } = useI18n()
const activeView = ref('chats')
const width = ref(window.innerWidth)
const isMobile = computed(() => width.value <= 768)
const handleResize = () => { width.value = window.innerWidth }
const compactWorkbench = computed(() => activeView.value === 'ai' && !isMobile.value && width.value <= 1024)

// ── Dual collapse (design: 200px<->64px dock, 320px<->44px blade) ───────────
// [ toggles the global rail, ] toggles the message-list blade. Both live here so
// the top bar / chat header can render their restore affordances.
const NAV_COLLAPSE_KEY = 'prism.navCollapsed'
const LIST_COLLAPSE_KEY = 'prism.listCollapsed'

// Tablet AI navigation starts compact. Expanding it must not change another
// section (or the user's saved desktop layout when the window grows again).
function paneFold(key) {
  const saved = ref(localStorage.getItem(key) === '1')
  const tablet = ref(true)
  return computed({
    get: () => compactWorkbench.value ? tablet.value : saved.value,
    set: (folded) => {
      if (compactWorkbench.value) tablet.value = folded
      else {
        saved.value = folded
        localStorage.setItem(key, folded ? '1' : '0')
      }
    }
  })
}
const navCollapsed = paneFold(NAV_COLLAPSE_KEY)
const listCollapsed = paneFold(LIST_COLLAPSE_KEY)
const toggleNav = () => { navCollapsed.value = !navCollapsed.value }
const toggleList = () => {
  if (isMobile.value && activeView.value === 'ai') chatStore.clearActiveChat()
  else listCollapsed.value = !listCollapsed.value
}

// One keystroke into a clean canvas: collapse both, or restore both.
const toggleCinematic = () => {
  const collapsing = !(navCollapsed.value && listCollapsed.value)
  navCollapsed.value = collapsing
  listCollapsed.value = collapsing
}

const showRightPanel = ref(false)
// The on-screen companion is a shortcut into the same place the nav goes, so
// there is exactly one way in and one source of truth for "where am I".
const openRoleChat = () => setActiveView('companion')

// ── Going back, one level ────────────────────────────────────────────────────
// The back arrow in the tavern is a step out of a room, not a jump to the front
// door. Where you were before you walked in is remembered and restored; falling
// back to the chat list only when there is nothing to return to (a cold start
// straight into the tavern, e.g. from a deep link).
const viewBeforeTavern = ref(null)
// The conversation that was open when the user walked into the tavern. Entering
// clears the active chat (the tavern draws its own canvas), so without this the
// chat list comes back with nothing selected.
const chatBeforeTavern = ref(null)

const closeRoleChat = () => {
  const previous = viewBeforeTavern.value
  const previousChat = chatBeforeTavern.value
  viewBeforeTavern.value = null
  chatBeforeTavern.value = null
  const target = previous && previous !== 'companion' ? previous : 'chats'
  setActiveView(target)
  // Restore the conversation the user was reading, once the shell is back.
  if (previousChat && target === 'chats') chatStore.setActiveChat(previousChat)
}
const paletteOpen = ref(false)

// What the tavern's mind orbit reads right now. The desktop pet's face follows
// it, so the character on screen and the character in the pet agree.
const tavernMood = ref('idle')

// The assistant is a virtual sortedChats item, not a persisted chat record.
const activateAssistantChat = () => {
  chatStore.setActiveChat(chatStore.sortedChats.find(c => c.id === AI_ASSISTANT_CHAT_ID))
}
const applyActiveView = (view) => {
  activeView.value = view
  if (view === 'companion' || (view === 'ai' && isMobile.value)) {
    // On phones the AI list must be reachable even when coming from an open chat.
    chatStore.clearActiveChat()
  } else if (view === 'ai') activateAssistantChat()
}

const setActiveView = (view) => {
  if (view === 'companion') {
    // Remember where we came from, so the back arrow can return there. Only the
    // first step in counts: navigating around inside the tavern must not
    // overwrite the place we are eventually returning to.
    if (activeView.value !== 'companion') {
      viewBeforeTavern.value = activeView.value
      chatBeforeTavern.value = chatStore.activeChat || null
    }
    enterTavern(() => applyActiveView('companion'))
    return
  }
  applyActiveView(view)
}

// Is the AI assistant currently selected? Used to hide the right info panel for the virtual chat.
const isAiChat = computed(() => chatStore.activeChat?.id === AI_ASSISTANT_CHAT_ID || chatStore.activeChat?.type === 'AI')

// ── Entering the tavern ──────────────────────────────────────────────────────
// The entrance plays where characters actually are: the companion roles. The AI
// assistant is a work tool and opens directly. Coming from outside, a painting
// is drawn over the app and `commit` runs by itself once it covers the screen;
// the painting is then crumpled away to show the tavern. There is nothing to
// confirm. ESC in the first moments, before `commit`, backs out and nothing
// changes. Already inside, `commit` runs straight away.
let enteringTavern = false
const enterTavern = (commit) => {
  if (enteringTavern || activeView.value === 'companion') {
    commit()
    return
  }
  if (tavernEntrance.state.phase !== ENTRANCE_PHASE.IDLE) return
  tavernEntrance.enter({
    onCommit: () => {
      enteringTavern = true
      try {
        commit()
      } finally {
        enteringTavern = false
      }
    }
  })
}

// A call must never ring behind the stage: the entrance yields to it immediately.
watch(
  () => callStore.callStatus,
  (status) => {
    if (status !== CallStatus.IDLE && status !== CallStatus.ENDED) tavernEntrance.cancel()
  }
)

// Handle open new chat from MiddlePanel
const handleOpenNewChat = () => {
  // Switch to the contacts view so the user can pick someone to message.
  setActiveView('contacts')
}

// ── Prism c5: command palette ────────────────────────────────────────────────
const openPalette = () => {
  paletteOpen.value = true
}

const openAiAssistant = () => {
  activeView.value = 'ai'
  activateAssistantChat()
}

const agentHistoryFocusSignal = ref(0)
const focusAgentHistory = () => {
  if (activeView.value !== 'ai') return
  if (isMobile.value) chatStore.clearActiveChat()
  else if (listCollapsed.value) listCollapsed.value = false
  agentHistoryFocusSignal.value++
}

const creatingAgentSession = ref(false)
const newAgentSession = async () => {
  if (activeView.value !== 'ai' || creatingAgentSession.value || agentStore.streaming || agentStore.switching) return
  creatingAgentSession.value = true
  try {
    await agentStore.newSession()
    // Don't navigate back into AI if the user left during the request.
    if (activeView.value === 'ai') openAiAssistant()
    ElMessage.success(t('agent.history.created'))
  } catch (err) {
    ElMessage.error(err.message || t('common.failed'))
  } finally {
    creatingAgentSession.value = false
  }
}

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
    case 'nav-5':
      setActiveView('companion')
      break
    case 'help':
    case 'palette':
      paletteOpen.value = true
      break
    default:
      break
  }
}

// Bare [ / ] collapse the rail and the message blade, as in the design.
const isTypingTarget = (el) =>
  !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)

const handleCollapseKey = (event) => {
  if (event.metaKey || event.ctrlKey || event.altKey) return
  if (isTypingTarget(event.target)) return
  if (paletteOpen.value) return
  if (event.key === '[') {
    event.preventDefault()
    toggleNav()
  } else if (event.key === ']') {
    event.preventDefault()
    toggleList()
  }
}

// Global ⌘K / Ctrl+K + the shortcuts the palette advertises.
const handleGlobalKeydown = (event) => {
  // Bubble on window: document capture (e.g. artifact tabs 1–3) gets first refusal.
  if (event.defaultPrevented || event.isComposing) return
  handleCollapseKey(event)
  const mod = event.metaKey || event.ctrlKey
  if (!mod) return
  const key = event.key.toLowerCase()

  if (key === 'k') {
    event.preventDefault()
    if (activeView.value === 'ai') {
      paletteOpen.value = false
      focusAgentHistory()
    } else paletteOpen.value = !paletteOpen.value
    return
  }
  // While the palette is open it owns the keyboard.
  if (paletteOpen.value) return

  if (key === 'n') {
    event.preventDefault()
    if (activeView.value !== 'ai') return handleOpenNewChat()
    if (!event.repeat) newAgentSession()
  } else if (key === 'g') {
    event.preventDefault()
    setActiveView('groups')
  } else if (key === 'j') {
    event.preventDefault()
    openAiAssistant()
  } else if (key === '/') {
    event.preventDefault()
    paletteOpen.value = true
  } else if (['1', '2', '3', '4', '5'].includes(key)) {
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
provide('openPalette', openPalette)
provide('navCollapsed', navCollapsed)
provide('toggleNav', toggleNav)
provide('listCollapsed', listCollapsed)
provide('toggleList', toggleList)
provide('toggleCinematic', toggleCinematic)
provide('focusAgentHistory', focusAgentHistory)
provide('agentHistoryFocusSignal', agentHistoryFocusSignal)
provide('newAgentSession', newAgentSession)
provide('creatingAgentSession', creatingAgentSession)

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

/* The blade owns its own 320px <-> 44px width (see LeftPanel). */
.panel-left {
  flex-shrink: 0;
  height: 100%;
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
  background: #0e0e11;
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
    height: 100%;
    z-index: 10;
    min-width: 0;
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

<template>
  <div class="main-layout">
    <LeftPanel class="panel-left" ref="leftPanelRef" />
    <MiddlePanel
      class="panel-middle"
      :class="{ 'mobile-active': isMobile && chatStore.activeChat }"
      @open-new-chat="handleOpenNewChat"
    />
    <RightPanel class="panel-right" :class="{ 'collapsed': !showRightPanel || isAiChat }" />

    <!-- Companion 3D avatar + panel -->
    <CompanionAvatar3D
      :active-role="companionStore.activeRole"
      :status="companionStore.status"
      @open="showCompanion = true"
    />
    <CompanionPanel v-model="showCompanion" />

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
import { ref, provide, onMounted, onUnmounted, computed } from 'vue'
import LeftPanel from '@/components/layout/LeftPanel.vue'
import MiddlePanel from '@/components/layout/MiddlePanel.vue'
import RightPanel from '@/components/layout/RightPanel.vue'
import IncomingCallModal from '@/components/call/IncomingCallModal.vue'
import OutgoingCallModal from '@/components/call/OutgoingCallModal.vue'
import CallView from '@/components/call/CallView.vue'
import CallEndModal from '@/components/call/CallEndModal.vue'
import CompanionAvatar3D from '@/components/companion/CompanionAvatar3D.vue'
import CompanionPanel from '@/components/companion/CompanionPanel.vue'
import { useUserStore } from '@/stores/user'
import { useContactStore } from '@/stores/contact'
import { useChatStore } from '@/stores/chat'
import { useMessageStore } from '@/stores/message'
import { useCallStore } from '@/stores/call'
import { useCompanionStore } from '@/stores/companion'
import websocket from '@/services/websocket'
import syncService from '@/services/syncService'

const userStore = useUserStore()
const contactStore = useContactStore()
const chatStore = useChatStore()
const messageStore = useMessageStore()
const callStore = useCallStore()
const companionStore = useCompanionStore()

const showRightPanel = ref(false)
const leftPanelRef = ref(null)
const showCompanion = ref(false)

// Is the AI assistant currently selected? Used to hide the right info panel for the virtual chat.
const isAiChat = computed(() => chatStore.activeChat?.id === 'ai-assistant' || chatStore.activeChat?.type === 'AI')

// Mobile detection
const isMobile = ref(window.innerWidth <= 768)
const handleResize = () => {
  isMobile.value = window.innerWidth <= 768
}

// Handle open new chat from MiddlePanel
const handleOpenNewChat = () => {
  // Switch to contacts tab in LeftPanel
  if (leftPanelRef.value) {
    leftPanelRef.value.$el.querySelector('.contacts-tab')?.click()
  }
}

// Provide global state for panels
const toggleRightPanel = () => {
  showRightPanel.value = !showRightPanel.value
}
provide('toggleRightPanel', toggleRightPanel)
provide('showRightPanel', showRightPanel)
provide('isMobile', isMobile)

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
            contactStore
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
  callStore.cleanup()
})
</script>

<style scoped>
.main-layout {
  display: flex;
  width: 100vw;
  height: 100vh;
  background: #f8fafc;
  overflow: hidden;
  position: relative;
  transition: background 0.3s ease;
}

.panel-left {
  width: 380px;
  min-width: 320px;
  max-width: 420px;
  border-right: 1px solid #f1f5f9;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  z-index: 10;
  position: relative;
  transition: all 0.3s ease;
}

.panel-middle {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  min-width: 400px;
  position: relative;
  background: transparent;
  z-index: 1;
}

.panel-right {
  width: 25%;
  min-width: 280px;
  max-width: 380px;
  border-left: 1px solid #f1f5f9;
  height: 100%;
  transition: all 0.3s ease;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  z-index: 5;
  position: relative;
}

.panel-right.collapsed {
  margin-right: -25%;
  display: none;
}

/* Dark Mode */
[data-theme="dark"] .main-layout {
  background: #0F1115;
}

[data-theme="dark"] .panel-left {
  background: rgba(24, 27, 33, 0.8);
  border-right-color: #232730;
}

[data-theme="dark"] .panel-right {
  background: rgba(24, 27, 33, 0.9);
  border-left-color: #232730;
}

/* Tablet breakpoint */
@media (max-width: 1024px) {
  .panel-left {
    width: 320px;
    min-width: 280px;
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
    position: absolute;
    width: 100%;
    max-width: none;
    min-width: unset;
    height: 100%;
    z-index: 10;
    border-right: none;
  }

  .panel-middle {
    position: absolute;
    width: 100%;
    height: 100%;
    min-width: unset;
    z-index: 20;
    transform: translateX(100%);
    transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    background: var(--tg-surface);
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

  [data-theme="dark"] .panel-right {
    box-shadow: -4px 0 20px rgba(0, 0, 0, 0.4);
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

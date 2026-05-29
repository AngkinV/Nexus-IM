<template>
  <div class="middle-panel">
    <!-- AI Assistant view (Mode A) -->
    <template v-if="isAiAssistant">
      <AgentChatView />
    </template>

    <template v-else-if="chatStore.activeChat">
      <!-- Chat Header -->
      <header class="chat-header">
        <div class="header-info" @click="toggleRightPanel">
          <!-- Mobile back button -->
          <button v-if="isMobile" class="mobile-back-btn" :aria-label="$t('common.back')" @click.stop="handleMobileBack">
            <el-icon :size="22"><ArrowLeft /></el-icon>
          </button>
          <div class="avatar-wrapper">
            <el-avatar :size="44" :src="chatStore.activeChat.avatar || defaultAvatar" class="chat-avatar" />
            <span v-if="chatStore.activeChat.type !== 'GROUP'" class="status-dot" :class="{ online: chatStore.activeChat.status === 'online' }"></span>
          </div>
          <div class="chat-meta">
            <div class="name-row">
              <span class="chat-name">{{ chatStore.activeChat.name }}</span>
              <span v-if="chatStore.activeChat.type !== 'GROUP' && chatStore.activeChat.status === 'online'" class="online-badge">{{ $t('chat.online').toUpperCase() }}</span>
            </div>
            <span class="chat-status">
              <template v-if="chatStore.activeChat.type === 'GROUP'">
                <el-icon class="member-icon"><User /></el-icon>
                <span>{{ chatStore.activeChat.memberCount || 0 }} {{ $t('group.members') }}</span>
              </template>
              <template v-else>
                {{ chatStore.activeChat.status === 'online' ? $t('chat.activeNow') : $t('chat.' + (chatStore.activeChat.status || 'offline')) }}
              </template>
            </span>
          </div>
        </div>
        <div class="header-actions">
          <button class="action-btn" :title="$t('chat.audioCall')" :aria-label="$t('chat.audioCall')" @click="startAudioCall" :disabled="!canCall">
            <el-icon :size="22"><Phone /></el-icon>
          </button>
          <button class="action-btn" :title="$t('chat.videoCall')" :aria-label="$t('chat.videoCall')" @click="startVideoCall" :disabled="!canCall">
            <el-icon :size="22"><VideoCamera /></el-icon>
          </button>
          <div class="action-divider"></div>
          <button class="action-btn" @click="toggleRightPanel" :title="$t('chat.moreInfo')" :aria-label="$t('chat.moreInfo')">
            <el-icon :size="22"><MoreFilled /></el-icon>
          </button>
        </div>
      </header>

      <!-- Message List -->
      <div class="messages-container">
        <!-- Loading (cold start) -->
        <div v-if="isLoadingMessages && !currentMessages.length" class="msg-state">
          <span class="msg-spinner"></span>
        </div>
        <!-- Load error -->
        <div v-else-if="messageLoadError && !currentMessages.length" class="msg-state">
          <el-icon :size="40" class="msg-state-icon msg-state-icon--error"><WarningFilled /></el-icon>
          <p class="msg-state-text">{{ $t('chat.messagesLoadFailed') }}</p>
          <button class="msg-retry" @click="retryLoadMessages">{{ $t('common.retry') }}</button>
        </div>
        <!-- Empty conversation -->
        <div v-else-if="!currentMessages.length" class="msg-state">
          <el-icon :size="46" class="msg-state-icon"><ChatDotRound /></el-icon>
          <p class="msg-state-text">{{ $t('chat.noMessagesYet') }}</p>
        </div>
        <!-- Messages -->
        <MessageList
          v-else
          :messages="currentMessages"
          @reply="handleReply"
          @edit="handleEditMessage"
          @recall="handleRecallMessage"
          @react="handleReactMessage"
          @view-edit-history="handleViewEditHistory"
          @resend="handleResend"
        />
      </div>

      <!-- Input Area -->
      <div class="input-area">
        <MessageInput
          :reply-to="replyToMessage"
          @send="handleSendMessage"
          @cancel-reply="replyToMessage = null"
        />
      </div>
    </template>

    <div v-else class="empty-state">
      <!-- Animated background decorations -->
      <div class="bg-decorations">
        <div class="bg-circle bg-circle-1"></div>
        <div class="bg-circle bg-circle-2"></div>
        <div class="bg-circle bg-circle-3"></div>
      </div>

      <!-- Main content -->
      <div class="empty-content animate-fade-in-up">
        <div class="icon-container animate-float">
          <img src="@/assets/images/image.jpg" alt="Nexus Logo" class="logo-img" />
        </div>

        <h2 class="welcome-title">
          {{ $t('chat.welcomeTo') }} <span class="text-gradient">Nexus</span>
        </h2>

        <p class="welcome-subtitle">
          {{ $t('chat.welcomeSubtitle') }}
        </p>

        <button class="start-chat-btn" @click="openNewChat">
          <el-icon><ChatLineSquare /></el-icon>
          <span>{{ $t('chat.startNewChat') }}</span>
        </button>
      </div>

      <!-- Bottom encrypted label -->
      <div class="encrypted-label">
        <el-icon><Lock /></el-icon>
        <span>{{ $t('chat.endToEndEncrypted') }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { inject, watch, computed, ref, h } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useMessageStore } from '@/stores/message'
import { useUserStore } from '@/stores/user'
import { useCallStore } from '@/stores/call'
import { messageAPI, resolveFileUrl } from '@/services/api'
import websocket from '@/services/websocket'
import { Phone, MoreFilled, ChatLineSquare, Lock, VideoCamera, User, ArrowLeft, WarningFilled, ChatDotRound } from '@element-plus/icons-vue'
import MessageList from '@/components/chat/MessageList.vue'
import MessageInput from '@/components/chat/MessageInput.vue'
import AgentChatView from '@/components/agent/AgentChatView.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'

const chatStore = useChatStore()
const messageStore = useMessageStore()
const userStore = useUserStore()
const callStore = useCallStore()
const { t } = useI18n()
const toggleRightPanel = inject('toggleRightPanel')
const isMobile = inject('isMobile', { value: false })
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'
const replyToMessage = ref(null)

// Detect virtual AI assistant chat — its messages flow through HTTP/SSE, not WebSocket.
const isAiAssistant = computed(() => chatStore.activeChat?.id === 'ai-assistant' || chatStore.activeChat?.type === 'AI')

// Handle mobile back - clear active chat to return to chat list
const handleMobileBack = () => {
  chatStore.clearActiveChat()
}

// Can only call in direct chats (not groups)
const canCall = computed(() => {
  return chatStore.activeChat && chatStore.activeChat.type !== 'GROUP'
})

// Get remote user info for calling
const remoteUser = computed(() => {
  if (!chatStore.activeChat || chatStore.activeChat.type === 'GROUP') return null
  return {
    id: chatStore.activeChat.contactId,
    name: chatStore.activeChat.name,
    nickname: chatStore.activeChat.name,
    avatar: chatStore.activeChat.avatar,
    avatarUrl: chatStore.activeChat.avatar
  }
})

// Start audio call
async function startAudioCall() {
  if (!canCall.value || !remoteUser.value) return
  try {
    await callStore.initiateCall(remoteUser.value, 'audio')
  } catch (error) {
    console.error('Failed to start audio call:', error)
    ElMessage.error(error.message || 'Failed to start call')
  }
}

// Start video call
async function startVideoCall() {
  if (!canCall.value || !remoteUser.value) return
  try {
    await callStore.initiateCall(remoteUser.value, 'video')
  } catch (error) {
    console.error('Failed to start video call:', error)
    ElMessage.error(error.message || 'Failed to start call')
  }
}

// Emit event to open new chat modal (will be handled by parent)
const emit = defineEmits(['openNewChat'])
const openNewChat = () => {
  emit('openNewChat')
}

// Get messages for current chat from messageStore
const currentMessages = computed(() => {
  if (!chatStore.activeChat) return []
  return messageStore.getMessages(chatStore.activeChat.id)
})

const isLoadingMessages = ref(false)
const messageLoadError = ref(false)

// Load chat history for a conversation, tracking loading/error state so the
// message area can show a spinner / retry instead of a silent blank.
const loadMessages = async (chat) => {
  if (!chat) return
  // Skip backend message fetch for the virtual AI assistant chat
  if (chat.id === 'ai-assistant' || chat.type === 'AI') return
  isLoadingMessages.value = true
  messageLoadError.value = false
  try {
    const response = await messageAPI.getChatMessages(
      chat.id,
      userStore.currentUser?.id,
      0,
      50
    )
    // Transform messages to match frontend format
    const transformedMessages = (response.data || []).map(m => ({
      id: m.id,
      chatId: m.chatId,
      senderId: m.senderId,
      senderName: m.senderNickname,
      senderAvatar: resolveFileUrl(m.senderAvatar),
      content: m.content,
      type: m.messageType?.toUpperCase() || 'TEXT',
      fileUrl: m.fileUrl,
      fileId: m.fileId,
      fileName: m.fileName,
      fileSize: m.fileSize,
      mimeType: m.mimeType,
      downloadUrl: m.downloadUrl,
      previewUrl: m.previewUrl,
      timestamp: m.createdAt,
      createdAt: m.createdAt,
      isRead: m.isRead,
      isSelf: m.senderId === userStore.currentUser?.id,
      isEdited: m.isEdited,
      editedAt: m.editedAt,
      editCount: m.editCount || 0,
      canEdit: m.canEdit,
      canRecall: m.canRecall,
      isRecalled: m.isRecalled,
      recalledAt: m.recalledAt,
      replyToMessageId: m.replyToMessageId,
      replyToMessage: m.replyToMessage,
      reactions: m.reactions || [],
      deliveredCount: m.deliveredCount || 0,
      readCount: m.readCount || 0,
      clientMsgId: m.clientMsgId,
      sequenceNumber: m.sequenceNumber
    }))
    messageStore.setMessages(chat.id, transformedMessages)
  } catch (error) {
    console.error('Failed to load messages:', error)
    messageLoadError.value = true
  } finally {
    isLoadingMessages.value = false
  }
}

const retryLoadMessages = () => loadMessages(chatStore.activeChat)

// Watch activeChat changes to load messages
watch(() => chatStore.activeChat, (newChat, oldChat) => {
  if (!newChat) return
  if (newChat.id !== oldChat?.id) {
    loadMessages(newChat)
  }
}, { immediate: true })

const handleSendMessage = (data, type = 'TEXT') => {
  if (!chatStore.activeChat || !userStore.currentUser) return

  const chatId = chatStore.activeChat.id
  const user = userStore.currentUser

  // Handle file message (data is an object) vs text message (data is a string)
  const isFileMessage = typeof data === 'object' && data.fileUrl
  const content = isFileMessage ? data.content : data
  const messageType = isFileMessage ? data.type : type
  const fileUrl = isFileMessage ? data.fileUrl : null

  const sendResult = websocket.sendMessage(
    chatId,
    user.id,
    content,
    messageType.toLowerCase(),
    fileUrl,
    replyToMessage.value?.id || null
  )

  // Create optimistic message (show immediately)
  const optimisticMessage = {
    id: `temp-${Date.now()}`,
    chatId: chatId,
    senderId: user.id,
    senderName: user.nickname,
    senderAvatar: user.avatar,
    content: content,
    type: messageType,
    fileUrl: fileUrl,
    fileId: isFileMessage ? data.fileId : null,
    fileName: isFileMessage ? data.fileName : null,
    fileSize: isFileMessage ? data.fileSize : null,
    mimeType: isFileMessage ? data.mimeType : null,
    downloadUrl: isFileMessage ? data.fileUrl : null,
    previewUrl: isFileMessage ? data.previewUrl : null,
    timestamp: new Date().toISOString(),
    createdAt: new Date().toISOString(),
    isRead: false,
    isSelf: true,
    replyToMessageId: replyToMessage.value?.id || null,
    replyToMessage: replyToMessage.value || null,
    reactions: [],
    clientMsgId: sendResult.clientMsgId,
    status: 'sending'
  }

  // Add message to store immediately
  messageStore.addMessage(chatId, optimisticMessage)

  // Consume the send promise so failures (not connected / ACK timeout) flip the
  // bubble to a retryable "failed" state right away instead of hanging.
  if (sendResult?.promise) {
    sendResult.promise.catch(() => {
      messageStore.markMessageFailed(chatId, sendResult.clientMsgId)
    })
  }

  replyToMessage.value = null
}

const handleResend = (msg) => {
  if (!msg) return
  const chatId = msg.chatId || chatStore.activeChat?.id
  if (!chatId) return
  // Drop the failed optimistic copy, then re-send the same payload fresh.
  messageStore.removeMessage(chatId, msg.id)
  if (msg.type === 'TEXT') {
    handleSendMessage(msg.content, 'TEXT')
  } else {
    handleSendMessage({
      content: msg.content,
      type: msg.type,
      fileUrl: msg.fileUrl || msg.downloadUrl,
      fileId: msg.fileId,
      fileName: msg.fileName,
      fileSize: msg.fileSize,
      mimeType: msg.mimeType,
      previewUrl: msg.previewUrl
    }, msg.type)
  }
}

const handleReply = (msg) => {
  if (!msg || msg.isRecalled) return
  replyToMessage.value = msg
}

const handleEditMessage = async (msg) => {
  if (!msg || msg.isRecalled) return
  try {
    if (msg.canEdit === false) {
      ElMessage.warning(t('chat.editExpired'))
      return
    }
    const { value } = await ElMessageBox.prompt(t('chat.editMessage'), t('chat.edit'), {
      inputValue: msg.content,
      inputType: 'textarea',
      confirmButtonText: t('chat.save'),
      cancelButtonText: t('chat.cancel')
    })
    const next = (value || '').trim()
    if (!next || next === msg.content) return
    const response = await messageAPI.editMessage(msg.id, next)
    messageStore.applyServerMessage(msg.chatId, {
      ...response.data,
      senderNickname: msg.senderName,
      senderAvatar: msg.senderAvatar
    })
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    const key = error?.response?.data?.messageKey
    if (key === 'error.message.edit.too_many') {
      ElMessage.warning(t('chat.editTooMany'))
    } else if (key === 'error.message.edit.expired') {
      ElMessage.warning(t('chat.editExpired'))
    } else {
      console.error('Failed to edit message:', error)
      ElMessage.error(t('chat.editFailed'))
    }
  }
}

const handleRecallMessage = async (msg) => {
  if (!msg || msg.isRecalled) return
  try {
    if (msg.canRecall === false) {
      ElMessage.warning(t('chat.recallExpired'))
      return
    }
    await ElMessageBox.confirm(t('chat.confirmRecallMessage'), t('chat.recallMessage'), {
      confirmButtonText: t('chat.recall'),
      cancelButtonText: t('chat.cancel'),
      type: 'warning'
    })
    const response = await messageAPI.recallMessage(msg.id)
    messageStore.applyServerMessage(msg.chatId, {
      ...response.data,
      senderNickname: msg.senderName,
      senderAvatar: msg.senderAvatar
    })
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    const key = error?.response?.data?.messageKey
    if (key === 'error.message.recall.expired') {
      ElMessage.warning(t('chat.recallExpired'))
    } else {
      console.error('Failed to recall message:', error)
      ElMessage.error(t('chat.recallFailed'))
    }
  }
}

const formatHistoryTime = (iso) => {
  if (!iso) return ''
  const d = new Date(iso)
  return Number.isFinite(d.getTime()) ? d.toLocaleString() : ''
}

const handleViewEditHistory = async (msg) => {
  if (!msg || !msg.id) return
  try {
    const response = await messageAPI.getEditHistory(msg.id)
    const history = response.data || []
    if (!history.length) {
      ElMessage.info(t('chat.editHistoryEmpty'))
      return
    }
    const items = history.map((item, idx) => h('div', {
      key: item.id,
      style: {
        padding: '10px 0',
        borderBottom: idx === history.length - 1 ? 'none' : '1px solid rgba(148, 163, 184, 0.25)'
      }
    }, [
      h('div', {
        style: { fontSize: '12px', color: '#94a3b8', marginBottom: '6px', fontWeight: '600' }
      }, `#${idx + 1} · ${formatHistoryTime(item.editedAt)}`),
      h('div', {
        style: { fontSize: '13px', color: '#ef4444', marginBottom: '4px', wordBreak: 'break-word' }
      }, `− ${item.previousContent || ''}`),
      h('div', {
        style: { fontSize: '13px', color: '#10b981', wordBreak: 'break-word' }
      }, `+ ${item.newContent || ''}`)
    ]))
    ElMessageBox.alert(
      h('div', { style: { maxHeight: '320px', overflowY: 'auto' } }, items),
      t('chat.editHistory'),
      {
        confirmButtonText: t('common.ok')
      }
    )
  } catch (error) {
    console.error('Failed to load edit history:', error)
    ElMessage.error(t('chat.editHistoryFailed'))
  }
}

const handleReactMessage = async (msg, emoji) => {
  if (!msg || !emoji) return
  try {
    const response = await messageAPI.toggleReaction(msg.id, emoji)
    messageStore.updateReactions(msg.chatId, msg.id, response.data || [])
  } catch (error) {
    console.error('Failed to update reaction:', error)
    ElMessage.error(t('chat.reactionFailed'))
  }
}
</script>

<style scoped>
.middle-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f8fafc;
  position: relative;
}

[data-theme="dark"] .middle-panel {
  background: #0F1115;
}

.chat-header {
  height: 80px;
  padding: 0 32px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
  z-index: 10;
  position: sticky;
  top: 0;
}

[data-theme="dark"] .chat-header {
  background: rgba(24, 27, 33, 0.72);
  border-bottom: 1px solid #232730;
}

.header-info {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: 1;
  cursor: pointer;
}

.avatar-wrapper {
  position: relative;
}

.chat-avatar {
  border: 2px solid rgba(226, 232, 240, 0.5);
  transition: var(--tg-transition);
}

.header-info:hover .chat-avatar {
  border-color: var(--tg-primary);
}

.status-dot {
  position: absolute;
  bottom: 2px;
  right: 2px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--tg-offline);
  border: 3px solid var(--tg-surface);
}

.status-dot.online {
  background: var(--tg-online);
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

.chat-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat-name {
  font-weight: 700;
  font-size: 17px;
  color: var(--tg-text-primary);
}

.online-badge {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: var(--tg-radius-full);
  background: rgba(20, 184, 166, 0.15);
  color: var(--tg-online);
  letter-spacing: 0.5px;
}

.chat-status {
  font-size: 13px;
  color: var(--tg-text-secondary);
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 4px;
}

.chat-status .member-icon {
  font-size: 14px;
  color: var(--tg-primary);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.action-btn {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  border-radius: 50%;
  color: var(--tg-text-secondary);
  cursor: pointer;
  transition: var(--tg-transition);
}

.action-btn:hover {
  background: rgba(6, 182, 212, 0.1);
  color: var(--tg-primary);
}

.action-btn:nth-child(2):hover {
  background: rgba(16, 185, 129, 0.1);
  color: var(--tg-secondary);
}

.action-divider {
  width: 1px;
  height: 24px;
  background: var(--tg-divider);
  margin: 0 8px;
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 10px 0;
  background: linear-gradient(180deg, #f8fafc 0%, #eef2f6 100%);
  position: relative;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.messages-container::-webkit-scrollbar {
  width: 0;
  height: 0;
  display: none;
}

/* Message-area states (loading / error / empty conversation) */
.msg-state {
  height: 100%;
  min-height: 240px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  text-align: center;
  padding: 24px;
}

.msg-state-icon {
  color: var(--tg-text-tertiary);
  opacity: 0.6;
}

.msg-state-icon--error {
  color: #ef4444;
  opacity: 0.85;
}

.msg-state-text {
  font-size: 13px;
  color: var(--tg-text-secondary);
  margin: 0;
}

.msg-retry {
  margin-top: 4px;
  padding: 7px 20px;
  border: none;
  border-radius: var(--tg-radius-sm);
  background: var(--teal-700);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s ease;
}

.msg-retry:hover {
  background: #0c5e57;
}

.msg-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--tg-surface-hover);
  border-top-color: var(--tg-primary);
  border-radius: 50%;
  animation: msg-spin 0.8s linear infinite;
}

@keyframes msg-spin {
  to { transform: rotate(360deg); }
}

.messages-container::before {
  content: '';
  position: absolute;
  inset: 0;
  opacity: 0.3;
  pointer-events: none;
  background-image: radial-gradient(#E2E8F0 2px, transparent 2px);
  background-size: 40px 40px;
}

[data-theme="dark"] .messages-container {
  background: linear-gradient(180deg, #0f1115 0%, #14171c 100%);
}

[data-theme="dark"] .messages-container::before {
  opacity: 0.1;
}

.input-area {
  padding: 16px 24px;
  background: transparent;
}

.empty-state {
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  background: linear-gradient(180deg, #f8fafc 0%, #f1f5f9 100%);
  position: relative;
  overflow: hidden;
  z-index: 1;
}

/* Animated background decorations */
.bg-decorations {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
  z-index: 0;
}

.bg-circle {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
}

.bg-circle-1 {
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 500px;
  height: 500px;
  background: rgba(20, 184, 166, 0.15);
  animation: float 6s ease-in-out infinite;
}

.bg-circle-2 {
  top: 25%;
  right: 25%;
  width: 350px;
  height: 350px;
  background: rgba(6, 182, 212, 0.15);
  animation: pulse-slow 4s ease-in-out infinite;
}

.bg-circle-3 {
  bottom: 25%;
  left: 25%;
  width: 400px;
  height: 400px;
  background: rgba(16, 185, 129, 0.12);
  animation: float 8s ease-in-out infinite reverse;
}

/* Main content */
.empty-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  z-index: 2;
  padding: 32px;
  position: relative;
}

.icon-container {
  width: 140px;
  height: 140px;
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 40px;
  box-shadow: 0 0 30px -5px rgba(20, 184, 166, 0.3);
  border: 1px solid rgba(255, 255, 255, 0.5);
  cursor: pointer;
  transition: transform 0.5s ease;
  position: relative;
  overflow: hidden;
}

.icon-container:hover {
  transform: scale(1.1);
}

.logo-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 40px;
}

.welcome-title {
  font-size: 36px;
  font-weight: 800;
  color: #1e293b;
  margin: 0 0 16px 0;
  letter-spacing: -0.5px;
}

.text-gradient {
  background: linear-gradient(135deg, #14b8a6 0%, #06b6d4 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.welcome-subtitle {
  font-size: 16px;
  color: #64748b;
  line-height: 1.6;
  margin: 0 0 40px 0;
  font-weight: 500;
}

.start-chat-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 32px;
  background: linear-gradient(135deg, #14b8a6 0%, #0d9488 100%);
  border: none;
  border-radius: 16px;
  color: white;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  box-shadow: 0 10px 30px -10px rgba(20, 184, 166, 0.5);
  transition: all 0.3s ease;
}

.start-chat-btn:hover {
  transform: translateY(-3px) scale(1.02);
  box-shadow: 0 15px 40px -10px rgba(20, 184, 166, 0.6);
}

.start-chat-btn:active {
  transform: scale(0.98);
}

.start-chat-btn .el-icon {
  font-size: 20px;
}

/* Encrypted label */
.encrypted-label {
  position: absolute;
  bottom: 32px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: rgba(100, 116, 139, 0.1);
  backdrop-filter: blur(10px);
  border-radius: 20px;
  border: 1px solid rgba(255, 255, 255, 0.2);
}

.encrypted-label .el-icon {
  font-size: 14px;
  color: #64748b;
}

.encrypted-label span {
  font-size: 11px;
  font-weight: 700;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 1px;
}

/* Animations */
@keyframes float {
  0%, 100% { transform: translate(-50%, -50%); }
  50% { transform: translate(-50%, calc(-50% - 15px)); }
}

@keyframes pulse-slow {
  0%, 100% { opacity: 0.5; transform: scale(1); }
  50% { opacity: 0.8; transform: scale(1.05); }
}

/* Animation class */
.animate-fade-in-up {
  animation: fadeInUp 0.8s cubic-bezier(0.2, 0.8, 0.2, 1) forwards;
}

.animate-float {
  animation: floatIcon 6s ease-in-out infinite;
}

@keyframes floatIcon {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-10px); }
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(30px) scale(0.95);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

/* Dark mode */
[data-theme="dark"] .empty-state {
  background: linear-gradient(180deg, #0F1115 0%, #181B21 100%);
}

[data-theme="dark"] .bg-circle-1 {
  background: rgba(20, 184, 166, 0.1);
}

[data-theme="dark"] .bg-circle-2 {
  background: rgba(6, 182, 212, 0.1);
}

[data-theme="dark"] .bg-circle-3 {
  background: rgba(16, 185, 129, 0.08);
}

[data-theme="dark"] .icon-container {
  background: rgba(24, 27, 33, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

[data-theme="dark"] .welcome-title {
  color: #ffffff;
}

[data-theme="dark"] .welcome-subtitle {
  color: #94a3b8;
}

[data-theme="dark"] .encrypted-label {
  background: rgba(30, 41, 59, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.05);
}

[data-theme="dark"] .encrypted-label .el-icon,
[data-theme="dark"] .encrypted-label span {
  color: #94a3b8;
}

/* Mobile back button */
.mobile-back-btn {
  display: none;
}

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .mobile-back-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border: none;
    background: transparent;
    border-radius: 50%;
    color: var(--tg-text-secondary);
    cursor: pointer;
    margin-right: 8px;
    flex-shrink: 0;
    transition: all 0.2s ease;
  }

  .mobile-back-btn:hover {
    background: rgba(0, 0, 0, 0.05);
    color: var(--tg-text-primary);
  }

  .mobile-back-btn:active {
    transform: scale(0.95);
  }

  .chat-header {
    height: 64px;
    padding: 0 12px;
  }

  .header-info {
    gap: 8px;
  }

  .chat-avatar {
    width: 40px !important;
    height: 40px !important;
  }

  .chat-name {
    font-size: 15px;
  }

  .chat-status {
    font-size: 12px;
  }

  .header-actions {
    gap: 2px;
  }

  .action-btn {
    width: 36px;
    height: 36px;
  }

  .action-divider {
    margin: 0 4px;
  }

  .input-area {
    padding: 12px 16px;
  }

  /* Empty state adjustments */
  .welcome-title {
    font-size: 28px;
  }

  .welcome-subtitle {
    font-size: 14px;
    padding: 0 20px;
  }

  .icon-container {
    width: 100px;
    height: 100px;
    border-radius: 32px;
    margin-bottom: 24px;
  }

  .start-chat-btn {
    padding: 14px 24px;
    font-size: 14px;
  }

  .encrypted-label {
    bottom: 20px;
  }
}

[data-theme="dark"] .mobile-back-btn:hover {
  background: rgba(255, 255, 255, 0.1);
}
</style>

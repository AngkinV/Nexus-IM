<template>
  <div class="middle-panel">
    <!-- AI Assistant view (Mode A) -->
    <template v-if="isAiAssistant">
      <AgentChatView />
    </template>

    <!-- Prism c2 / c3: directory detail cards for the selected contact / group -->
    <ContactDetail
      v-else-if="detailContact"
      :contact="detailContact"
      @message="openContactChat"
      @call="callContact"
      @remove="removeContact"
      @select="selectMutual"
      @close="selectedContactId = null"
    />
    <GroupDetail
      v-else-if="detailGroup"
      :group="detailGroup"
      @enter="enterGroup"
      @close="selectedGroupId = null"
    />

    <template v-else-if="chatStore.activeChat">
      <!-- Conversation header — Prism 56px bar -->
      <header class="chat-header">
        <div class="header-info" @click="toggleRightPanel">
          <button v-if="isMobile" class="mobile-back-btn" :aria-label="$t('common.back')" @click.stop="handleMobileBack">
            <span class="material-symbols-outlined">arrow_back</span>
          </button>

          <div class="avatar-wrapper">
            <div class="avatar-frame">
              <img
                v-if="chatStore.activeChat.avatar"
                class="chat-avatar"
                :src="chatStore.activeChat.avatar"
                alt=""
              />
              <div
                v-else
                class="avatar-fallback"
                :data-initial="(chatStore.activeChat.name || '?').trim().charAt(0) || '?'"
              ></div>
            </div>
            <span
              v-if="chatStore.activeChat.type !== 'GROUP'"
              class="status-dot"
              :class="{ online: chatStore.activeChat.status === 'online' }"
            ></span>
          </div>

          <div class="chat-meta">
            <div class="name-row">
              <span class="chat-name">{{ chatStore.activeChat.name }}</span>
              <span v-if="chatStore.activeChat.type === 'GROUP'" class="role-chip">
                {{ t('chat.memberCount', { n: chatStore.activeChat.memberCount || 0 }) }}
              </span>
            </div>
            <div class="status-row">
              <span
                class="status-pip"
                :class="{ online: chatStore.activeChat.status === 'online' }"
              ></span>
              <span class="chat-status">
                <template v-if="chatStore.activeChat.type === 'GROUP'">
                  {{ chatStore.activeChat.memberCount || 0 }} {{ $t('group.members') }}
                </template>
                <template v-else>
                  {{ chatStore.activeChat.status === 'online' ? $t('chat.activeNow') : $t('chat.' + (chatStore.activeChat.status || 'offline')) }}
                </template>
              </span>
            </div>
          </div>
        </div>

        <div class="header-actions">
          <button class="action-btn" :title="$t('chat.audioCall')" :aria-label="$t('chat.audioCall')" @click="startAudioCall" :disabled="!canCall">
            <span class="material-symbols-outlined">call</span>
          </button>
          <button class="action-btn" :title="$t('chat.videoCall')" :aria-label="$t('chat.videoCall')" @click="startVideoCall" :disabled="!canCall">
            <span class="material-symbols-outlined">videocam</span>
          </button>
          <div class="action-divider"></div>
          <button class="action-btn" @click="requestSearch" :title="$t('group.searchMessages')" :aria-label="$t('group.searchMessages')">
            <span class="material-symbols-outlined">search</span>
          </button>
          <button class="action-btn" @click="toggleRightPanel" :title="$t('chat.moreInfo')" :aria-label="$t('chat.moreInfo')">
            <span class="material-symbols-outlined">info</span>
          </button>
        </div>
      </header>

      <!-- Message stream -->
      <div class="messages-container">
        <div class="grid-overlay"></div>

        <!-- Loading (cold start) -->
        <div v-if="isLoadingMessages && !currentMessages.length" class="msg-state">
          <span class="msg-spinner"></span>
        </div>
        <!-- Load error -->
        <div v-else-if="messageLoadError && !currentMessages.length" class="msg-state">
          <span class="material-symbols-outlined msg-state-icon msg-state-icon--error">cloud_off</span>
          <p class="msg-state-text">{{ $t('chat.messagesLoadFailed') }}</p>
          <button class="msg-retry" @click="retryLoadMessages">{{ $t('common.retry') }}</button>
        </div>
        <!-- Empty conversation -->
        <div v-else-if="!currentMessages.length" class="msg-state">
          <span class="material-symbols-outlined msg-state-icon">forum</span>
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

      <!-- Composer -->
      <div class="input-area">
        <MessageInput
          :reply-to="replyToMessage"
          @send="handleSendMessage"
          @cancel-reply="replyToMessage = null"
        />
      </div>
    </template>

    <!-- Prism c0: standby state — cybernetic eye + action triggers + status strip. -->
    <div v-else class="empty-state">
      <div class="grid-overlay"></div>

      <!-- Top alignment bar -->
      <div class="standby-topbar">
        <div class="standby-state">
          <span class="material-symbols-outlined">visibility</span>
          <span>{{ $t('chat.systemState') }}</span>
        </div>
        <div class="standby-telemetry">
          <div class="standby-ping">
            <span>{{ $t('chat.pingLabel') }}</span>
            <span class="bright">{{ $t('chat.pingValue') }}</span>
          </div>
          <div class="standby-sep"></div>
          <div>{{ $t('chat.cipherLabel') }}</div>
        </div>
      </div>

      <!-- Centerpiece visual & copy -->
      <div class="standby-center">
        <div class="standby-eye-wrap">
          <div class="standby-eye-halo"></div>
          <div class="standby-eye" ref="eyeHost" aria-hidden="true"></div>
        </div>

        <div class="standby-copy">
          <div class="standby-pill">
            <span class="standby-dot"></span>
            {{ $t('chat.standbyMode') }}
          </div>

          <h2 class="welcome-title">{{ $t('chat.standbyTitle') }}</h2>

          <i18n-t keypath="chat.standbyDesc" tag="p" class="welcome-subtitle" scope="global">
            <template #kbd><kbd class="standby-inline-kbd">⌘N</kbd></template>
          </i18n-t>
        </div>

        <div class="standby-actions">
          <button class="standby-btn primary" type="button" @click="openNewChat">
            <span class="material-symbols-outlined">add</span>
            <span class="standby-btn-label">{{ $t('chat.startPrivateChat') }}</span>
            <span class="standby-kbd">⌘N</span>
          </button>
          <button class="standby-btn" type="button" @click="openNewGroup">
            <span class="material-symbols-outlined">group_add</span>
            <span class="standby-btn-label">{{ $t('chat.newGroup') }}</span>
            <span class="standby-kbd dim">⌘G</span>
          </button>
          <button class="standby-btn" type="button" @click="wakeAi">
            <span class="material-symbols-outlined">auto_awesome</span>
            <span class="standby-btn-label">{{ $t('chat.wakeAi') }}</span>
            <span class="standby-kbd dim">⌘J</span>
          </button>
        </div>
      </div>

      <!-- Bottom hardware-inspired status strip -->
      <div class="standby-strip">
        <div class="standby-rule"></div>
        <div class="standby-meta">
          <span class="standby-meta-item">
            <span class="material-symbols-outlined">lock</span>
            {{ $t('chat.encryptReady') }}
          </span>
          <span>·</span>
          <span>{{ $t('chat.latencyLabel') }}</span>
          <span>·</span>
          <span class="bright">{{ $t('chat.monoMode') }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { inject, watch, computed, ref, h, onMounted, onUnmounted, nextTick } from 'vue'
import * as THREE from 'three'
import { useChatStore } from '@/stores/chat'
import { useMessageStore } from '@/stores/message'
import { useUserStore } from '@/stores/user'
import { useCallStore } from '@/stores/call'
import { messageAPI, resolveFileUrl } from '@/services/api'
import websocket from '@/services/websocket'
import MessageList from '@/components/chat/MessageList.vue'
import MessageInput from '@/components/chat/MessageInput.vue'
import AgentChatView from '@/components/agent/AgentChatView.vue'
import ContactDetail from '@/components/contact/ContactDetail.vue'
import GroupDetail from '@/components/chat/GroupDetail.vue'
import { useContactStore } from '@/stores/contact'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'

const chatStore = useChatStore()
const messageStore = useMessageStore()
const userStore = useUserStore()
const callStore = useCallStore()
const { t } = useI18n()
const toggleRightPanel = inject('toggleRightPanel')
const requestSearch = inject('requestSearch', () => {})
const isMobile = inject('isMobile', { value: false })
const replyToMessage = ref(null)

// Detect virtual AI assistant chat — its messages flow through HTTP/SSE, not WebSocket.
const isAiAssistant = computed(() => chatStore.activeChat?.id === 'ai-assistant' || chatStore.activeChat?.type === 'AI')

// ── Directory detail panes (Prism c2 / c3) ───────────────────────────────────
// The left pane owns the selection; records are re-read from the stores so
// presence and member counts stay live while a card is open.
const contactStore = useContactStore()
const router = useRouter()
const activeView = inject('activeView', ref('chats'))
const setActiveView = inject('setActiveView', () => {})
const enterAi = inject('enterAi', null)
const selectedContactId = inject('selectedContactId', ref(null))
const selectedGroupId = inject('selectedGroupId', ref(null))

const detailContact = computed(() => {
  if (activeView.value !== 'contacts' || selectedContactId.value == null) return null
  return contactStore.contacts.find((c) => c.id === selectedContactId.value) || null
})
const detailGroup = computed(() => {
  if (activeView.value !== 'groups' || selectedGroupId.value == null) return null
  return chatStore.groupChats.find((g) => g.id === selectedGroupId.value) || null
})

const openContactChat = async (contact) => {
  await chatStore.createDirectChat(userStore.currentUser?.id, contact)
  setActiveView('chats')
}

const callContact = async (contact, type) => {
  try {
    await callStore.initiateCall(
      {
        id: contact.id,
        name: contact.nickname,
        nickname: contact.nickname,
        avatar: contact.avatar,
        avatarUrl: contact.avatar
      },
      type
    )
  } catch (error) {
    console.error('Failed to start call:', error)
    ElMessage.error(error.message || 'Failed to start call')
  }
}

const removeContact = async (contact) => {
  try {
    await ElMessageBox.confirm(
      t('contact.confirmRemove', { name: contact.nickname }),
      t('contact.removeContact'),
      { confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel'), type: 'warning' }
    )
    await contactStore.removeContactRequest(userStore.currentUser?.id, contact.id)
    selectedContactId.value = null
    ElMessage.success(t('contact.removeSuccess'))
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to remove contact:', error)
      ElMessage.error(t('contact.removeFailed'))
    }
  }
}

// A mutual contact opens in place when it is one of ours, else its profile page.
const selectMutual = (user) => {
  if (contactStore.contacts.some((c) => c.id === user.id)) selectedContactId.value = user.id
  else router.push(`/user/${user.id}`)
}

const enterGroup = (group) => {
  chatStore.setActiveChat(group)
  setActiveView('chats')
}

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

// ── Prism c0: standby actions ────────────────────────────────────────────────
const requestCreateGroup = inject('requestCreateGroup', null)

const openNewGroup = () => {
  if (requestCreateGroup) {
    requestCreateGroup()
  } else {
    setActiveView('groups')
  }
}
const wakeAi = () => {
  const aiChat = chatStore.sortedChats?.find((c) => c.id === 'ai-assistant')
  const open = () => {
    setActiveView('ai')
    if (aiChat) chatStore.setActiveChat(aiChat)
  }
  // Through the Creator's Eye warp when Main provides it; the chat only opens on confirm.
  if (enterAi) enterAi(open)
  else open()
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
        borderBottom: idx === history.length - 1 ? 'none' : '1px solid rgba(68, 71, 72, 0.3)'
      }
    }, [
      h('div', {
        style: { fontSize: '12px', color: '#8e9192', marginBottom: '6px', fontWeight: '600' }
      }, `#${idx + 1} · ${formatHistoryTime(item.editedAt)}`),
      h('div', {
        style: { fontSize: '13px', color: '#f2b8b5', marginBottom: '4px', wordBreak: 'break-word' }
      }, `− ${item.previousContent || ''}`),
      h('div', {
        style: { fontSize: '13px', color: '#ffffff', wordBreak: 'break-word' }
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

// ── Prism c0: cybernetic "eye of the machine" on the standby screen ──────────
const eyeHost = ref(null)
let eyeTeardown = null

const mountStandbyEye = async () => {
  if (eyeTeardown || !eyeHost.value) return
  const container = eyeHost.value
  const width = container.clientWidth || 360
  const height = container.clientHeight || 360

  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 1000)
  camera.position.z = 7

  const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true })
  renderer.setSize(width, height)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  container.appendChild(renderer.domElement)

  const eyeGroup = new THREE.Group()
  scene.add(eyeGroup)

  // Outer orbit rings (mechanical aperture)
  eyeGroup.add(new THREE.Mesh(
    new THREE.RingGeometry(2.35, 2.38, 64),
    new THREE.MeshBasicMaterial({ color: 0x52525b, side: THREE.DoubleSide, transparent: true, opacity: 0.6 })
  ))
  const ring2 = new THREE.Mesh(
    new THREE.RingGeometry(2.0, 2.02, 64),
    new THREE.MeshBasicMaterial({ color: 0x71717a, side: THREE.DoubleSide, transparent: true, opacity: 0.4 })
  )
  eyeGroup.add(ring2)

  // Segmented dashed outer ring
  const segmentGroup = new THREE.Group()
  const segCount = 36
  for (let i = 0; i < segCount; i++) {
    if (i % 3 === 0) continue
    const angle = (i / segCount) * Math.PI * 2
    const r = 2.2
    const dotGeo = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(Math.cos(angle) * r, Math.sin(angle) * r, 0),
      new THREE.Vector3(Math.cos(angle) * (r + 0.08), Math.sin(angle) * (r + 0.08), 0)
    ])
    segmentGroup.add(new THREE.Line(dotGeo, new THREE.LineBasicMaterial({ color: 0xa1a1aa, transparent: true, opacity: 0.5 })))
  }
  eyeGroup.add(segmentGroup)

  // Sclera / dark metallic sphere
  const eyeball = new THREE.Mesh(
    new THREE.SphereGeometry(1.6, 64, 64),
    new THREE.MeshStandardMaterial({ color: 0x18181b, roughness: 0.2, metalness: 0.8 })
  )
  eyeGroup.add(eyeball)

  // Iris: rim + spokes + pupil + glint
  const irisGroup = new THREE.Group()
  irisGroup.position.z = 1.35
  eyeball.add(irisGroup)
  irisGroup.add(new THREE.Mesh(
    new THREE.RingGeometry(0.7, 0.74, 48),
    new THREE.MeshBasicMaterial({ color: 0xffffff, side: THREE.DoubleSide })
  ))
  for (let j = 0; j < 16; j++) {
    const theta = (j / 16) * Math.PI * 2
    const spokeGeo = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(Math.cos(theta) * 0.35, Math.sin(theta) * 0.35, 0),
      new THREE.Vector3(Math.cos(theta) * 0.68, Math.sin(theta) * 0.68, 0)
    ])
    irisGroup.add(new THREE.Line(spokeGeo, new THREE.LineBasicMaterial({ color: 0x71717a, transparent: true, opacity: 0.7 })))
  }
  const pupil = new THREE.Mesh(
    new THREE.CircleGeometry(0.35, 48),
    new THREE.MeshBasicMaterial({ color: 0x09090b })
  )
  pupil.position.z = 0.02
  irisGroup.add(pupil)
  const glint = new THREE.Mesh(
    new THREE.CircleGeometry(0.08, 16),
    new THREE.MeshBasicMaterial({ color: 0xffffff })
  )
  glint.position.set(0.12, 0.12, 0.03)
  irisGroup.add(glint)

  // Lighting
  scene.add(new THREE.AmbientLight(0xffffff, 0.8))
  const dirLight = new THREE.DirectionalLight(0xffffff, 2.0)
  dirLight.position.set(3, 4, 5)
  scene.add(dirLight)
  const rimLight = new THREE.DirectionalLight(0xa1a1aa, 1.2)
  rimLight.position.set(-3, -3, 2)
  scene.add(rimLight)

  // Cursor-driven gaze, clamped so the eye never rolls unnaturally
  let targetRotX = 0
  let targetRotY = 0
  const onMouseMove = (event) => {
    const rect = container.getBoundingClientRect()
    const x = ((event.clientX - rect.left) / rect.width) * 2 - 1
    const y = -(((event.clientY - rect.top) / rect.height) * 2 - 1)
    targetRotY = Math.max(-0.65, Math.min(0.65, x * 0.7))
    targetRotX = Math.max(-0.5, Math.min(0.5, -y * 0.55))
  }
  window.addEventListener('mousemove', onMouseMove)

  const onResize = () => {
    const w = container.clientWidth || width
    const h = container.clientHeight || height
    if (!w || !h) return
    camera.aspect = w / h
    camera.updateProjectionMatrix()
    renderer.setSize(w, h)
  }
  onResize()
  window.addEventListener('resize', onResize)
  // The host is sized with vh units, so it changes without a window resize event.
  const resizeObserver = new ResizeObserver(onResize)
  resizeObserver.observe(container)

  const clock = new THREE.Clock()
  let frame = 0
  const animate = () => {
    frame = requestAnimationFrame(animate)
    const time = clock.getElapsedTime()
    eyeball.rotation.y += (targetRotY - eyeball.rotation.y) * 0.08
    eyeball.rotation.x += (targetRotX - eyeball.rotation.x) * 0.08
    ring2.rotation.z = time * 0.2
    segmentGroup.rotation.z = time * 0.08
    const scale = 1.0 + Math.sin(time * 1.5) * 0.015
    eyeGroup.scale.set(scale, scale, scale)
    renderer.render(scene, camera)
  }
  animate()

  eyeTeardown = () => {
    cancelAnimationFrame(frame)
    window.removeEventListener('mousemove', onMouseMove)
    window.removeEventListener('resize', onResize)
    resizeObserver.disconnect()
    renderer.dispose()
    container.removeChild(renderer.domElement)
  }
}

// Only mount the WebGL canvas while the standby screen is actually on screen.
watch(
  () => !chatStore.activeChat,
  async (isStandby) => {
    if (isStandby) {
      await nextTick()
      mountStandbyEye()
    } else if (eyeTeardown) {
      eyeTeardown()
      eyeTeardown = null
    }
  },
  { immediate: true }
)

onUnmounted(() => {
  if (eyeTeardown) {
    eyeTeardown()
    eyeTeardown = null
  }
})
</script>


<style scoped>
.middle-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #131316;
  position: relative;
  min-width: 0;
}

/* Faint monochrome dot grid used across Prism panes */
.grid-overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0.025;
  background-image: radial-gradient(#ffffff 1px, transparent 1px);
  background-size: 24px 24px;
  z-index: 0;
}

/* ── Header ─────────────────────────────────────────────── */
.chat-header {
  height: 56px;
  padding: 0 24px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  background: rgba(14, 14, 17, 0.8);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(68, 71, 72, 0.2);
  z-index: 20;
  flex-shrink: 0;
}

.header-info {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
  flex: 1;
  cursor: pointer;
}

.avatar-wrapper {
  position: relative;
  flex-shrink: 0;
}

.avatar-frame {
  width: 36px;
  height: 36px;
  border-radius: 9999px;
  overflow: hidden;
  background: #2a2a2d;
  box-shadow: 0 0 0 1px rgba(68, 71, 72, 0.4);
}

.avatar-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #2a2a2d;
  color: #ffffff;
}

.chat-avatar {
  width: 100%;
  height: 100%;
  object-fit: cover;
  filter: grayscale(100%) contrast(1.1);
}

.status-dot {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 10px;
  height: 10px;
  border-radius: 9999px;
  background: #8e9192;
  box-shadow: 0 0 0 2px #0e0e11;
}

.status-dot.online {
  background: #ffffff;
}

.chat-meta {
  display: flex;
  flex-direction: column;
  gap: 0;
  min-width: 0;
}

.name-row {
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 28px;
  min-width: 0;
}

.chat-name {
  font-size: 13px;
  line-height: 18px;
  font-weight: 600;
  letter-spacing: -0.025em;
  color: #ffffff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.role-chip {
  flex-shrink: 0;
  padding: 2px 6px;
  border-radius: 4px;
  background: #2a2a2d;
  color: #c4c7c8;
  line-height: 16px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px;
}

.status-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-pip {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: #6f7274;
  flex-shrink: 0;
}

.status-pip.online {
  background: #ffffff;
}

.chat-status {
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: #c4c7c8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.action-btn {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: #1b1b1e;
  border-radius: 4px;
  color: #c4c7c8;
  cursor: pointer;
  transition: all 0.15s ease;
}

.action-btn .material-symbols-outlined {
  font-size: 18px;
}

.action-btn:hover:not(:disabled) {
  background: #2a2a2d;
  color: #ffffff;
}

.action-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.action-divider {
  width: 1px;
  height: 16px;
  background: rgba(68, 71, 72, 0.3);
  margin: 0 4px;
}

/* ── Message stream ─────────────────────────────────────── */
.messages-container {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0;
  background: #131316;
  position: relative;
  z-index: 1;
}

.messages-container::-webkit-scrollbar {
  width: 6px;
}

.messages-container::-webkit-scrollbar-track {
  background: transparent;
}

.messages-container::-webkit-scrollbar-thumb {
  background: #353438;
  border-radius: 9999px;
}

.messages-container::-webkit-scrollbar-thumb:hover {
  background: #8e9192;
}

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
  position: relative;
  z-index: 1;
}

.msg-state-icon {
  font-size: 40px;
  color: #6f7274;
}

.msg-state-icon--error {
  color: #ffb4ab;
}

.msg-state-text {
  font-size: 13px;
  color: #c4c7c8;
  margin: 0;
}

.msg-retry {
  margin-top: 4px;
  padding: 6px 16px;
  border: 1px solid rgba(68, 71, 72, 0.6);
  border-radius: 4px;
  background: #1b1b1e;
  color: #e4e1e6;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.msg-retry:hover {
  background: #2a2a2d;
}

.msg-spinner {
  width: 22px;
  height: 22px;
  border-radius: 9999px;
  border: 2px solid #2a2a2d;
  border-top-color: #ffffff;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ── Composer ───────────────────────────────────────────── */
.input-area {
  flex-shrink: 0;
  padding: 0 16px 16px;
  background: #131316;
  z-index: 20;
}

/* ── Standby state (Prism c0) ─────────────────────────────
   Values below are the exported screen's Tailwind classes resolved to CSS:
   section = flex-col justify-between items-center px-space-lg py-space-xl. */
.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: space-between;
  position: relative;
  background: #131316;
  overflow: hidden;
  padding: 40px 24px;
}

.empty-state .grid-overlay {
  opacity: 0.03;
}

/* Top alignment bar: w-full max-w-3xl, font-code */
.standby-topbar {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 768px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  color: #c4c7c8;
}

.standby-state {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  line-height: 22px;
  font-weight: 400;
  white-space: nowrap;
}

.standby-state .material-symbols-outlined {
  font-size: 16px;
  color: #ffffff;
}

.standby-telemetry {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

.standby-ping {
  display: flex;
  align-items: center;
  gap: 6px;
}

.standby-telemetry .bright {
  color: #ffffff;
}

.standby-sep {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: rgba(196, 199, 200, 0.3);
}

/* Centerpiece: my-auto max-w-xl */
.standby-center {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  margin: auto 0;
  max-width: 576px;
}

.standby-eye-wrap {
  position: relative;
  cursor: crosshair;
}

.standby-eye-halo {
  position: absolute;
  inset: -16px;
  border-radius: 9999px;
  background: rgba(27, 27, 30, 0.6);
  filter: blur(24px);
  opacity: 0.5;
  transition: opacity 0.7s ease;
  pointer-events: none;
}

.standby-eye-wrap:hover .standby-eye-halo {
  opacity: 1;
}

.standby-eye {
  --eye-size: min(360px, 70vw, 42vh);
  width: var(--eye-size);
  height: var(--eye-size);
  position: relative;
  z-index: 1;
  flex-shrink: 0;
  margin: 0 auto;
}

.standby-copy {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  margin-top: -8px;
}

.standby-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  border-radius: 9999px;
  background: #1b1b1e;
  color: #c4c7c8;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.standby-dot {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: #ffffff;
  display: inline-block;
}

.welcome-title {
  font-size: 32px;
  line-height: 1.25;
  font-weight: 600;
  letter-spacing: -0.025em;
  color: #ffffff;
  margin: 0;
}

.welcome-subtitle {
  max-width: 448px;
  font-size: 16px;
  line-height: 1.625;
  letter-spacing: -0.005em;
  font-weight: 400;
  color: #c4c7c8;
  margin: 0;
}

.standby-inline-kbd {
  padding: 2px 6px;
  border-radius: 4px;
  background: #2a2a2d;
  color: #ffffff;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
}

.standby-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 24px;
}

.standby-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border: none;
  border-radius: 8px;
  background: #1f1f22;
  color: #ffffff;
  cursor: pointer;
  transition: all 0.15s cubic-bezier(0.4, 0, 0.2, 1);
}

.standby-btn-label {
  font-size: 13px;
  line-height: 18px;
  font-weight: 500;
  letter-spacing: 0.01em;
}

.standby-btn .material-symbols-outlined {
  font-size: 18px;
  color: #c4c7c8;
}

.standby-btn:hover {
  background: #2a2a2d;
}

.standby-btn:active {
  transform: scale(0.98);
}

.standby-btn.primary {
  background: #ffffff;
  color: #2f3131;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -2px rgba(0, 0, 0, 0.1);
}

.standby-btn.primary .material-symbols-outlined {
  color: #2f3131;
}

.standby-btn.primary:hover {
  background: #e2e2e2;
}

.standby-kbd {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
  opacity: 0.6;
  margin-left: 2px;
}

.standby-kbd.dim {
  color: #c4c7c8;
  opacity: 1;
}

/* Bottom strip: w-full max-w-2xl, in flow (justify-between pins it) */
.standby-strip {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  width: 100%;
  max-width: 672px;
}

.standby-rule {
  height: 1px;
  width: 96px;
  background: #353438;
}

.standby-meta {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  flex-wrap: wrap;
  color: rgba(196, 199, 200, 0.7);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
  text-align: center;
}

.standby-meta-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.standby-meta .material-symbols-outlined {
  font-size: 14px;
}

.standby-meta .bright {
  color: #e4e1e6;
}

/* ── Mobile back button ─────────────────────────────────── */
.mobile-back-btn {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: #1b1b1e;
  border-radius: 4px;
  color: #c4c7c8;
  cursor: pointer;
  transition: all 0.15s ease;
}

.mobile-back-btn .material-symbols-outlined {
  font-size: 18px;
}

.mobile-back-btn:hover {
  background: #2a2a2d;
  color: #ffffff;
}

/* ── Standby: compact layouts ───────────────────────────── */
@media (max-width: 900px) {
  .standby-eye {
    --eye-size: min(280px, 62vw, 30vh);
  }

  .welcome-title {
    font-size: 24px;
  }

  .welcome-subtitle {
    font-size: 14px;
  }

  .standby-actions {
    gap: 8px;
    margin-top: 20px;
  }

  .standby-btn {
    padding: 9px 13px;
    font-size: 12.5px;
  }
}

@media (max-width: 560px) {
  .standby-topbar {
    display: none;
  }

  .empty-state {
    padding: 16px;
    justify-content: flex-start;
    padding-top: 12px;
  }

  .standby-eye {
    --eye-size: min(200px, 58vw, 24vh);
    margin-bottom: -4px;
  }

  .standby-copy {
    gap: 6px;
  }

  .welcome-title {
    font-size: 20px;
  }

  .welcome-subtitle {
    font-size: 13px;
    line-height: 1.55;
  }

  .standby-actions {
    flex-direction: column;
    width: 100%;
    max-width: 260px;
    margin-top: 16px;
  }

  .standby-btn {
    width: 100%;
    justify-content: center;
  }

  .standby-strip {
    position: static;
    transform: none;
    margin-top: auto;
    padding-top: 16px;
    padding-bottom: 4px;
    max-width: 100%;
  }

  .standby-meta {
    gap: 8px;
    font-size: 10px;
  }
}

@media (max-height: 620px) and (min-width: 561px) {
  .standby-topbar {
    display: none;
  }

  .empty-state {
    padding: 16px 24px;
  }

  .standby-eye {
    --eye-size: min(220px, 55vw, 30vh);
  }

  .welcome-title {
    font-size: 22px;
  }

  .standby-actions {
    margin-top: 16px;
  }
}
</style>

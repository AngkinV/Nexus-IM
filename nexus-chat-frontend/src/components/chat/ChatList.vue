<template>
  <div class="chat-list">
    <!-- Loading skeleton (cold start only) -->
    <div v-if="chatStore.isLoading && !chatStore.chats.length" class="chat-skeleton">
      <div v-for="n in 7" :key="n" class="skeleton-item">
        <div class="skeleton-avatar"></div>
        <div class="skeleton-lines">
          <div class="skeleton-line skeleton-line--short"></div>
          <div class="skeleton-line skeleton-line--long"></div>
        </div>
      </div>
    </div>

    <!-- Load error -->
    <div v-else-if="chatStore.loadError && !chatStore.chats.length" class="chat-state">
      <span class="material-symbols-outlined state-icon state-icon--error">cloud_off</span>
      <p class="state-title">{{ $t('chat.loadFailed') }}</p>
      <button class="state-action" @click="chatStore.retryFetchChats()">{{ $t('common.retry') }}</button>
    </div>

    <!-- Empty: no conversations yet -->
    <div v-else-if="!filteredChats.length && !searchQuery" class="chat-state">
      <span class="material-symbols-outlined state-icon">forum</span>
      <p class="state-title">{{ $t('chat.noChats') }}</p>
      <p class="state-desc">{{ $t('chat.noChatsDesc') }}</p>
    </div>

    <!-- Empty: search returned nothing -->
    <div v-else-if="!filteredChats.length && searchQuery" class="chat-state">
      <span class="material-symbols-outlined state-icon">search_off</span>
      <p class="state-title">{{ $t('chat.noSearchResults') }}</p>
    </div>

    <!-- Chat rows — Prism monochrome list item -->
    <template v-else>
    <div
      v-for="chat in filteredChats"
      :key="chat.id"
      class="chat-item-wrapper"
      :class="{ swiped: swipedChatId === chat.id }"
    >
      <!-- Swipe action buttons -->
      <div class="swipe-actions" :class="{ revealed: getSwipeOffset(chat.id) !== 0 }">
        <button
          class="action-btn pin-btn"
          @click.stop="handleTogglePin(chat)"
        >
          <span class="material-symbols-outlined">push_pin</span>
          <span class="action-text">{{ chatStore.isChatPinned(chat.id) ? $t('chat.unpin') : $t('chat.pin') }}</span>
        </button>
        <button
          class="action-btn delete-btn"
          @click.stop="handleDelete(chat)"
        >
          <span class="material-symbols-outlined">delete</span>
          <span class="action-text">{{ $t('common.delete') }}</span>
        </button>
      </div>

      <!-- Chat item (swipeable) -->
      <div
        class="chat-item"
        :class="{
          active: chatStore.activeChat?.id === chat.id,
          'ai-item': chat.isAi
        }"
        :style="{ transform: `translateX(${getSwipeOffset(chat.id)}px)` }"
        @click="handleChatClick(chat)"
        @touchstart="onTouchStart($event, chat.id)"
        @touchmove="onTouchMove($event)"
        @touchend="onTouchEnd"
      >
        <!-- Avatar -->
        <div class="chat-avatar">
          <template v-if="chat.isAi">
            <div class="ai-avatar">
              <span class="material-symbols-outlined">auto_awesome</span>
            </div>
            <span class="presence-dot online"></span>
          </template>
          <template v-else>
            <div class="avatar-frame">
              <IdentityAvatar :src="chat.avatarUrl || chat.avatar" :entity-id="chat.id" :label="chat.name" :kind="isGroup(chat.type) ? 'group' : 'user'" class="avatar-img" />
            </div>
            <PresenceDot class="presence-dot" :value="chatPresence(chat)" :type="chat.type" />
            <div v-if="isGroup(chat.type)" class="group-badge">
              <span class="material-symbols-outlined">group</span>
            </div>
          </template>
        </div>

        <!-- Content -->
        <div class="chat-content">
          <div class="chat-top">
            <span class="chat-name">{{ chat.name }}</span>
            <span class="chat-time">{{ formatTime(chat.lastMessageTime) }}</span>
          </div>
          <div class="chat-bottom">
            <span class="last-message">{{ chat.lastMessage || $t('common.noMessages') }}</span>
            <span
              v-if="chat.unreadCount > 0"
              class="unread-badge"
            >{{ chat.unreadCount > 99 ? '99+' : chat.unreadCount }}</span>
            <span
              v-else-if="chatStore.isChatPinned(chat.id)"
              class="material-symbols-outlined pin-indicator"
            >push_pin</span>
          </div>
        </div>

        <!-- Desktop hover actions (touch devices use swipe) -->
        <div class="chat-hover-actions">
          <button
            class="hover-action-btn pin"
            :class="{ active: chatStore.isChatPinned(chat.id) }"
            :aria-label="chatStore.isChatPinned(chat.id) ? $t('chat.unpin') : $t('chat.pin')"
            :title="chatStore.isChatPinned(chat.id) ? $t('chat.unpin') : $t('chat.pin')"
            @click.stop="handleTogglePin(chat)"
          >
            <span class="material-symbols-outlined">push_pin</span>
          </button>
          <button
            class="hover-action-btn danger"
            :aria-label="$t('common.delete')"
            :title="$t('common.delete')"
            @click.stop="handleDelete(chat)"
          >
            <span class="material-symbols-outlined">delete</span>
          </button>
        </div>
      </div>
    </div>
    </template>
  </div>
</template>

<script setup>
import IdentityAvatar from '@/components/identity/IdentityAvatar.vue'
import PresenceDot from '@/components/identity/PresenceDot.vue'
import { isGroup, presence, chatPresence } from '@/components/identity/presentation.mjs'
import { computed, inject, reactive, ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useMessageStore } from '@/stores/message'
import { useSettingsStore } from '@/stores/settings'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'

const props = defineProps({
  searchQuery: {
    type: String,
    default: ''
  },
  filter: {
    type: String,
    default: 'all'
  }
})

const { t } = useI18n()
const chatStore = useChatStore()
const settings = useSettingsStore()
const messageStore = useMessageStore()

// Remote avatars can 404; remember which ones did so we render the initial tile.
const failedAvatars = reactive({})

const initialOf = (name) => (name || '?').trim().charAt(0) || '?'

// Use sortedChats for pinned sorting, then apply the search box + filter pill.
const filteredChats = computed(() => {
  let list = chatStore.sortedChats

  if (props.filter === 'unread') {
    list = list.filter((c) => (c.unreadCount || 0) > 0)
  } else if (props.filter === 'starred') {
    list = list.filter((c) => chatStore.isChatPinned(c.id))
  } else if (props.filter === 'groups') {
    list = list.filter((c) => c.type === 'GROUP' || c.isAi)
  }

  if (!props.searchQuery) return list
  const q = props.searchQuery.toLowerCase()
  return list.filter((chat) => (chat.name || '').toLowerCase().includes(q))
})

// Swipe state
const swipedChatId = ref(null)
const swipeOffset = ref({})
const touchStartX = ref(0)
const touchCurrentX = ref(0)
const isSwiping = ref(false)
const SWIPE_THRESHOLD = 60
const SWIPE_MAX = -128 // 64px * 2 buttons

const getSwipeOffset = (chatId) => {
  return swipeOffset.value[chatId] || 0
}

const onTouchStart = (event, chatId) => {
  if (swipedChatId.value && swipedChatId.value !== chatId) {
    closeSwipe(swipedChatId.value)
  }

  touchStartX.value = event.touches[0].clientX
  touchCurrentX.value = touchStartX.value
  isSwiping.value = false
}

const onTouchMove = (event) => {
  if (!touchStartX.value) return

  touchCurrentX.value = event.touches[0].clientX
  const deltaX = touchCurrentX.value - touchStartX.value

  if (deltaX < -10) {
    isSwiping.value = true
    const chatId = getCurrentSwipingChatId(event)
    if (chatId) {
      const offset = Math.max(deltaX, SWIPE_MAX)
      swipeOffset.value[chatId] = offset
    }
  }
}

const onTouchEnd = () => {
  if (!isSwiping.value) {
    touchStartX.value = 0
    return
  }

  for (const chatId in swipeOffset.value) {
    if (swipeOffset.value[chatId] < -SWIPE_THRESHOLD) {
      swipeOffset.value[chatId] = SWIPE_MAX
      swipedChatId.value = chatId
    } else {
      closeSwipe(chatId)
    }
  }

  touchStartX.value = 0
  touchCurrentX.value = 0

  setTimeout(() => {
    isSwiping.value = false
  }, 50)
}

const getCurrentSwipingChatId = (event) => {
  let target = event.target
  while (target && !target.classList?.contains('chat-item-wrapper')) {
    target = target.parentElement
  }
  if (target) {
    const wrapper = target
    const index = Array.from(wrapper.parentElement.children).indexOf(wrapper)
    if (index >= 0 && filteredChats.value[index]) {
      return filteredChats.value[index].id
    }
  }
  return null
}

const closeSwipe = (chatId) => {
  swipeOffset.value[chatId] = 0
  if (swipedChatId.value === chatId) {
    swipedChatId.value = null
  }
}

const handleChatClick = (chat) => {
  if (isSwiping.value) return

  if (swipedChatId.value === chat.id) {
    closeSwipe(chat.id)
    return
  }

  if (swipedChatId.value) {
    closeSwipe(swipedChatId.value)
  }

  chatStore.toggleActiveChat(chat)
}

const handleTogglePin = (chat) => {
  const isPinned = chatStore.togglePinChat(chat.id)
  ElMessage.success(isPinned ? t('chat.pinSuccess') : t('chat.unpinSuccess'))
  closeSwipe(chat.id)
}

const handleDelete = async (chat) => {
  closeSwipe(chat.id)

  try {
    await ElMessageBox.confirm(
      t('chat.confirmDeleteChat', { name: chat.name }),
      t('chat.deleteChat'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )

    chatStore.deleteChat(chat.id)
    messageStore.clearMessages(chat.id)
    ElMessage.success(t('chat.deleteChatSuccess'))
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to delete chat:', error)
      ElMessage.error(t('chat.deleteChatFailed'))
    }
  }
}

const formatTime = (time) => settings.format(time)
</script>

<style scoped>
.chat-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

/* ── Empty / error states ───────────────────────────────── */
.chat-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 56px 24px;
  gap: 6px;
}

.state-icon {
  font-size: 40px;
  color: #6f7274;
  margin-bottom: 4px;
}

.state-icon--error {
  color: #ffb4ab;
}

.state-title {
  font-size: 14px;
  font-weight: 500;
  color: #c4c7c8;
}

.state-desc {
  font-size: 14px;
  color: #6f7274;
  line-height: 1.5;
}

.state-action {
  margin-top: 6px;
  padding: 5px 12px;
  border-radius: 4px;
  border: 1px solid rgba(68, 71, 72, 0.6);
  background: #1b1b1e;
  color: #e4e1e6;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.state-action:hover {
  background: #2a2a2d;
}

/* ── Skeleton ───────────────────────────────────────────── */
.chat-skeleton {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 0;
}

.skeleton-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px;
}

.skeleton-avatar {
  width: 40px;
  height: 40px;
  border-radius: 9999px;
  flex-shrink: 0;
}

.skeleton-lines {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.skeleton-line {
  height: 9px;
  border-radius: 4px;
}

.skeleton-line--short { width: 38%; }
.skeleton-line--long { width: 72%; }

.skeleton-avatar,
.skeleton-line {
  background: #1f1f22;
  overflow: hidden;
  position: relative;
}

.skeleton-avatar::after,
.skeleton-line::after {
  content: '';
  position: absolute;
  inset: 0;
  transform: translateX(-100%);
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.04), transparent);
  animation: shimmer 1.4s infinite;
}

@keyframes shimmer {
  100% { transform: translateX(100%); }
}

/* ── Row wrapper + swipe ────────────────────────────────── */
.chat-item-wrapper {
  position: relative;
  overflow: hidden;
  border-radius: 8px;
}

/* Kept out of paint until the row is dragged: otherwise the red delete
   button bleeds through the row's anti-aliased rounded corners. */
.swipe-actions {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  visibility: hidden;
}

.swipe-actions.revealed {
  visibility: visible;
}

.action-btn {
  width: 64px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  border: none;
  cursor: pointer;
  color: #e4e1e6;
}

.action-btn .material-symbols-outlined {
  font-size: 18px;
}

.action-text {
  font-size: 10px;
}

.pin-btn { background: #353438; }
.delete-btn { background: #b3261e; }

/* ── Row ────────────────────────────────────────────────── */
.chat-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
  background: #0e0e11;
}

.chat-item:hover {
  background: #1b1b1e;
}

/* Selected row: bg-surface-container + border-outline-variant/30 */
.chat-item.active {
  background: #1f1f22;
  border: 1px solid rgba(68, 71, 72, 0.3);
}

/* Selected row reads brighter: semibold name, white time, on-surface preview. */
.chat-item.active .chat-name {
  font-weight: 600;
}

.chat-item.active .chat-time {
  color: #ffffff;
}

.chat-item.active .last-message {
  color: #e4e1e6;
}

.chat-item.active .presence-dot {
  box-shadow: 0 0 0 2px #1f1f22;
}

/* ── Avatar ─────────────────────────────────────────────── */
.chat-avatar {
  position: relative;
  width: 40px;
  height: 40px;
  flex-shrink: 0;
}

.avatar-frame {
  width: 40px;
  height: 40px;
  border-radius: 9999px;
  overflow: hidden;
  background: #2a2a2d;
  box-shadow: 0 0 0 1px rgba(14, 14, 17, 1);
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  background: #2a2a2d;
}

.ai-avatar {
  width: 40px;
  height: 40px;
  border-radius: 9999px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #2a2a2d;
  color: #ffffff;
}

.ai-avatar .material-symbols-outlined {
  font-size: 20px;
}

.presence-dot {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 10px;
  height: 10px;
  border-radius: 9999px;
  background: rgba(196, 199, 200, 0.4);
  box-shadow: 0 0 0 2px #0e0e11;
}

.presence-dot.online {
  background: #22c55e;
}

.group-badge {
  position: absolute;
  right: -2px;
  bottom: -2px;
  width: 16px;
  height: 16px;
  border-radius: 9999px;
  background: #353438;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 0 0 2px #0e0e11;
}

.group-badge .material-symbols-outlined {
  font-size: 11px;
  color: #c4c7c8;
}

/* ── Text block ─────────────────────────────────────────── */
.chat-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.chat-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.chat-name {
  font-size: 14px;
  line-height: 18px;
  letter-spacing: 0.01em;
  font-weight: 500;
  color: #ffffff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat-time {
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  color: rgba(196, 199, 200, 0.7);
  flex-shrink: 0;
}

.chat-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
}

.last-message {
  font-size: 14px;
  line-height: 24px;
  color: #c4c7c8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.unread-badge {
  flex-shrink: 0;
  height: 16px;
  min-width: 16px;
  padding: 0 4px;
  border-radius: 9999px;
  background: #ffffff;
  color: #2f3131;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ── Pin + hover actions ────────────────────────────────── */
.pin-indicator {
  flex-shrink: 0;
  font-size: 14px;
  color: rgba(196, 199, 200, 0.8);
}

.chat-hover-actions {
  position: absolute;
  top: 50%;
  right: 8px;
  transform: translateY(-50%);
  display: none;
  gap: 2px;
  background: #1b1b1e;
  border-radius: 4px;
  padding: 2px;
}

.chat-item:hover .chat-hover-actions {
  display: flex;
}

.chat-item:hover .chat-time,
.chat-item:hover .pin-indicator {
  opacity: 0;
}

.hover-action-btn {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: #8e9192;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.hover-action-btn:hover {
  background: #2a2a2d;
  color: #ffffff;
}

.hover-action-btn.pin.active {
  color: #ffffff;
}

.hover-action-btn.danger:hover {
  color: #ffb4ab;
}

.hover-action-btn .material-symbols-outlined {
  font-size: 15px;
}
</style>

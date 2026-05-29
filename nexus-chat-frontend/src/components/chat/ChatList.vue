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
      <span class="material-icons-round state-icon state-icon--error">cloud_off</span>
      <p class="state-title">{{ $t('chat.loadFailed') }}</p>
      <button class="state-action" @click="chatStore.retryFetchChats()">{{ $t('common.retry') }}</button>
    </div>

    <!-- Empty: no conversations yet -->
    <div v-else-if="!filteredChats.length && !searchQuery" class="chat-state">
      <span class="material-icons-round state-icon">forum</span>
      <p class="state-title">{{ $t('chat.noChats') }}</p>
      <p class="state-desc">{{ $t('chat.noChatsDesc') }}</p>
    </div>

    <!-- Empty: search returned nothing -->
    <div v-else-if="!filteredChats.length && searchQuery" class="chat-state">
      <span class="material-icons-round state-icon">search_off</span>
      <p class="state-title">{{ $t('chat.noSearchResults') }}</p>
    </div>

    <!-- Chat items -->
    <template v-else>
    <div
      v-for="chat in filteredChats"
      :key="chat.id"
      class="chat-item-wrapper"
      :class="{ swiped: swipedChatId === chat.id }"
    >
      <!-- Swipe action buttons -->
      <div class="swipe-actions">
        <button
          class="action-btn pin-btn"
          @click.stop="handleTogglePin(chat)"
        >
          <span class="material-icons-round">push_pin</span>
          <span class="action-text">{{ chatStore.isChatPinned(chat.id) ? $t('chat.unpin') : $t('chat.pin') }}</span>
        </button>
        <button
          class="action-btn delete-btn"
          @click.stop="handleDelete(chat)"
        >
          <span class="material-icons-round">delete</span>
          <span class="action-text">{{ $t('common.delete') }}</span>
        </button>
      </div>

      <!-- Chat item (swipeable) -->
      <div
        class="chat-item"
        :class="{
          active: chatStore.activeChat?.id === chat.id
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
              <span class="material-icons-round">auto_awesome</span>
            </div>
          </template>
          <template v-else>
            <el-avatar :size="44" :src="chat.avatar || defaultAvatar" class="avatar-img" />
            <div v-if="chat.online && chat.type !== 'GROUP'" class="online-badge"></div>
            <!-- Group badge -->
            <div v-if="chat.type === 'GROUP'" class="group-badge">
              <span class="material-icons-round">groups</span>
            </div>
            <!-- Unread badge on avatar -->
            <div v-if="chat.unreadCount > 0" class="unread-badge">
              {{ chat.unreadCount > 99 ? '99+' : chat.unreadCount }}
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
            <span class="last-message">{{ chat.lastMessage }}</span>
          </div>
        </div>

        <!-- Pin indicator -->
        <div v-if="chatStore.isChatPinned(chat.id)" class="pin-indicator">
          <span class="material-icons-round">push_pin</span>
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
            <span class="material-icons-round">push_pin</span>
          </button>
          <button
            class="hover-action-btn danger"
            :aria-label="$t('common.delete')"
            :title="$t('common.delete')"
            @click.stop="handleDelete(chat)"
          >
            <span class="material-icons-round">delete</span>
          </button>
        </div>
      </div>
    </div>
    </template>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useMessageStore } from '@/stores/message'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'

const props = defineProps({
  searchQuery: {
    type: String,
    default: ''
  }
})

const { t } = useI18n()
const chatStore = useChatStore()
const messageStore = useMessageStore()
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'

// Use sortedChats for pinned sorting
const filteredChats = computed(() => {
  const sorted = chatStore.sortedChats
  if (!props.searchQuery) return sorted
  return sorted.filter(chat =>
    chat.name.toLowerCase().includes(props.searchQuery.toLowerCase())
  )
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

const formatTime = (time) => {
  if (!time) return ''
  return dayjs(time).format('HH:mm')
}
</script>

<style scoped>
.chat-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

/* Empty / error states */
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
  font-size: 46px;
  color: var(--tg-text-tertiary);
  opacity: 0.6;
  margin-bottom: 4px;
}

.state-icon--error {
  color: #ef4444;
  opacity: 0.85;
}

.state-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--tg-text-secondary);
  margin: 0;
}

.state-desc {
  font-size: 12px;
  color: var(--tg-text-tertiary);
  margin: 0;
  max-width: 220px;
  line-height: 1.5;
}

.state-action {
  margin-top: 10px;
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

.state-action:hover {
  background: #0c5e57;
}

/* Loading skeleton */
.chat-skeleton {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 12px;
}

.skeleton-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
}

.skeleton-avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  flex-shrink: 0;
}

.skeleton-lines {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.skeleton-line {
  height: 10px;
  border-radius: 6px;
}

.skeleton-line--short { width: 38%; }
.skeleton-line--long { width: 72%; }

.skeleton-avatar,
.skeleton-line {
  position: relative;
  overflow: hidden;
  background: var(--tg-surface-hover);
}

.skeleton-avatar::after,
.skeleton-line::after {
  content: '';
  position: absolute;
  inset: 0;
  transform: translateX(-100%);
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.35), transparent);
  animation: skeleton-shimmer 1.4s ease-in-out infinite;
}

@keyframes skeleton-shimmer {
  100% { transform: translateX(100%); }
}

.chat-item-wrapper {
  position: relative;
  overflow: hidden;
  border-radius: 16px;
  margin: 0 6px;
}

.swipe-actions {
  position: absolute;
  right: -1px;
  top: 0;
  bottom: 0;
  display: flex;
  align-items: stretch;
  z-index: 0;
}

.action-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 64px;
  border: none;
  cursor: pointer;
  color: white;
  gap: 2px;
  transition: opacity 0.15s;
}

.action-btn:hover {
  opacity: 0.9;
}

.action-btn:active {
  opacity: 0.8;
}

.action-btn .material-icons-round {
  font-size: 18px;
}

.action-text {
  font-size: 10px;
  font-weight: 600;
}

.pin-btn {
  background: #00B4D8;
}

.delete-btn {
  background: #ef4444;
}

.chat-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  cursor: pointer;
  transition: transform 0.2s ease-out, background 0.15s ease;
  position: relative;
  background: #ffffff;
  z-index: 1;
  width: calc(100% + 2px);
  margin-right: -2px;
  box-sizing: border-box;
}

.chat-item:hover {
  background: #f1f5f9;
}

.chat-item.active {
  background: #e2e8f0;
}

.chat-avatar {
  position: relative;
  margin-right: 12px;
  flex-shrink: 0;
}

.avatar-img {
  border-radius: 50%;
}

.avatar-img :deep(.el-avatar) {
  border-radius: 50%;
}

.ai-avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #06b6d4 0%, #14b8a6 50%, #0d9488 100%);
  color: #fff;
  box-shadow: 0 6px 18px -8px rgba(20, 184, 166, 0.55);
}

.ai-avatar .material-icons-round {
  font-size: 24px;
}

.online-badge {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 12px;
  height: 12px;
  background: var(--tg-online);
  border: 2px solid #ffffff;
  border-radius: 50%;
}

.group-badge {
  position: absolute;
  bottom: -2px;
  right: -2px;
  width: 18px;
  height: 18px;
  background: #00B4D8;
  border: 2px solid #ffffff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.group-badge .material-icons-round {
  font-size: 11px;
  color: white;
}

.unread-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  background: var(--tg-unread);
  color: white;
  font-size: 10px;
  font-weight: 700;
  padding: 0 5px;
  height: 18px;
  min-width: 18px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid #ffffff;
}

.chat-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  overflow: hidden;
  min-width: 0;
}

.chat-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.chat-name {
  font-weight: 600;
  font-size: 13px;
  color: #1e293b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat-time {
  font-size: 11px;
  font-weight: 500;
  color: #94a3b8;
  flex-shrink: 0;
  margin-left: 8px;
}

.chat-bottom {
  display: flex;
  align-items: center;
}

.last-message {
  font-size: 12px;
  color: #64748b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}

.pin-indicator {
  color: #00B4D8;
  margin-left: 8px;
  flex-shrink: 0;
  opacity: 0.7;
}

.pin-indicator .material-icons-round {
  font-size: 16px;
}

/* Desktop hover actions — refined pill buttons, replace the swipe colour bars on pointer devices */
.chat-hover-actions {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  padding: 0 10px 0 32px;
  border-radius: 0 16px 16px 0;
  opacity: 0;
  transform: translateX(8px);
  pointer-events: none;
  transition: opacity 0.18s ease, transform 0.18s ease;
  z-index: 3;
  background: linear-gradient(to right, rgba(241, 245, 249, 0) 0%, #f1f5f9 32px);
}

.chat-item:hover .chat-hover-actions {
  opacity: 1;
  transform: translateX(0);
  pointer-events: auto;
}

/* Fade the meta (time / pin marker) out on hover so the actions get clean space */
.chat-time,
.pin-indicator {
  transition: opacity 0.18s ease;
}

.chat-item:hover .chat-time,
.chat-item:hover .pin-indicator {
  opacity: 0;
}

.hover-action-btn {
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 9px;
  background: #ffffff;
  color: #64748b;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.14);
  transition: transform 0.15s ease, background 0.15s ease, color 0.15s ease, box-shadow 0.15s ease;
}

.hover-action-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 10px -2px rgba(15, 23, 42, 0.18);
}

.hover-action-btn:active {
  transform: translateY(0) scale(0.92);
}

.hover-action-btn.pin:hover,
.hover-action-btn.pin.active {
  background: #ccfbf1;
  color: #0d9488;
}

.hover-action-btn.danger:hover {
  background: #fee2e2;
  color: #ef4444;
}

.hover-action-btn .material-icons-round {
  font-size: 17px;
}

/* On pointer/hover devices use the hover actions and hide the swipe colour bars entirely */
@media (hover: hover) and (pointer: fine) {
  .swipe-actions {
    display: none;
  }
}

/* Touch devices keep using swipe gestures, no hover actions */
@media (hover: none) {
  .chat-hover-actions {
    display: none;
  }
}

/* Dark mode hover actions */
[data-theme="dark"] .chat-hover-actions {
  background: linear-gradient(to right, rgba(51, 65, 85, 0) 0%, #334155 32px);
}

[data-theme="dark"] .hover-action-btn {
  background: #1e293b;
  color: #94a3b8;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.4);
}

[data-theme="dark"] .hover-action-btn.pin:hover,
[data-theme="dark"] .hover-action-btn.pin.active {
  background: rgba(20, 184, 166, 0.2);
  color: #5eead4;
}

[data-theme="dark"] .hover-action-btn.danger:hover {
  background: rgba(239, 68, 68, 0.22);
  color: #f87171;
}

/* Swiped state */
.chat-item-wrapper.swiped {
  background: #00B4D8;
}

.chat-item-wrapper.swiped .chat-item {
  transition: none;
}

/* Dark Mode */
[data-theme="dark"] .chat-item {
  background: #1e293b;
}

[data-theme="dark"] .chat-item:hover {
  background: #334155;
}

[data-theme="dark"] .chat-item.active {
  background: #475569;
}

[data-theme="dark"] .online-badge {
  border-color: #1e293b;
}

[data-theme="dark"] .group-badge {
  border-color: #1e293b;
  background: #0891b2;
}

[data-theme="dark"] .unread-badge {
  border-color: #1e293b;
}

[data-theme="dark"] .chat-name {
  color: #f1f5f9;
}

[data-theme="dark"] .chat-time {
  color: #64748b;
}

[data-theme="dark"] .last-message {
  color: #94a3b8;
}

[data-theme="dark"] .pin-indicator {
  color: #38bdf8;
}

[data-theme="dark"] .pin-btn {
  background: #0891b2;
}

[data-theme="dark"] .delete-btn {
  background: #dc2626;
}

[data-theme="dark"] .chat-item-wrapper.swiped {
  background: #0891b2;
}

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .chat-item-wrapper {
    margin: 0 4px;
    border-radius: 14px;
  }

  .chat-item {
    padding: 12px 10px;
  }

  .chat-avatar {
    margin-right: 10px;
  }

  .avatar-img :deep(.el-avatar) {
    width: 48px !important;
    height: 48px !important;
  }

  .chat-name {
    font-size: 14px;
  }

  .chat-time {
    font-size: 10px;
  }

  .last-message {
    font-size: 13px;
  }

  .unread-badge {
    font-size: 9px;
    height: 16px;
    min-width: 16px;
    padding: 0 4px;
  }

  .action-btn {
    width: 56px;
  }

  .action-text {
    font-size: 9px;
  }
}
</style>

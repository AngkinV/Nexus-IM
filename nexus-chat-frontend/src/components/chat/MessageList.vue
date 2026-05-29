<template>
  <div class="message-list" ref="listRef">
    <div
      v-for="(msg, i) in messages"
      :key="msg.id"
      class="message-wrapper"
      :class="{ 'sent': msg.isSelf, 'grouped': isGroupedWithPrev(i), 'picker-open': openPicker === msg.id }"
    >
      <!-- Avatar for received messages (only the last of a group shows it) -->
      <div v-if="!msg.isSelf" class="message-avatar">
        <el-avatar
          v-if="!isGroupedWithNext(i)"
          :size="36"
          :src="msg.senderAvatar || defaultAvatar"
          :alt="msg.senderName"
          class="clickable-avatar"
          @click="handleAvatarClick(msg.senderId)"
        />
      </div>

      <div class="message-content">
        <div class="message-bubble" :class="[msg.isSelf ? 'bubble-out' : 'bubble-in', { 'grp-cont': isGroupedWithPrev(i) }]">
          <div v-if="!msg.isSelf && !isGroupedWithPrev(i)" class="sender-name">{{ msg.senderName }}</div>

          <div v-if="msg.replyToMessage" class="reply-preview">
            <span class="material-icons-round reply-icon">reply</span>
            <div class="reply-body">
              <span class="reply-author">{{ msg.replyToMessage.senderNickname || msg.replyToMessage.senderName || $t('chat.unknownUser') }}</span>
              <span class="reply-text">{{ msg.replyToMessage.isRecalled ? $t('chat.messageRecalled') : msg.replyToMessage.content }}</span>
            </div>
          </div>

          <div v-if="msg.isRecalled" class="message-recalled">
            {{ msg.isSelf
              ? $t('chat.messageRecalledByYou')
              : $t('chat.messageRecalledBy', { name: msg.senderName || $t('chat.unknownUser') }) }}
          </div>

          <!-- Text Message -->
          <div v-else-if="msg.type === 'TEXT'" class="message-text">
            {{ msg.content }}
          </div>

          <!-- Image Message -->
          <div v-else-if="msg.type === 'IMAGE'" class="message-image">
            <el-image
              :src="getPreviewSrc(msg)"
              :preview-src-list="[getPreviewSrc(msg)]"
              :alt="msg.fileName || $t('chat.image')"
              fit="cover"
              class="image-content"
              loading="lazy"
            >
              <template #error>
                <div class="image-error">
                  <el-icon :size="32"><PictureFilled /></el-icon>
                  <span>{{ $t('chat.failedToLoad') }}</span>
                </div>
              </template>
            </el-image>
          </div>

          <!-- Video Message -->
          <div v-else-if="msg.type === 'VIDEO'" class="message-video">
            <video
              :src="getPreviewSrc(msg)"
              controls
              preload="metadata"
              class="video-content"
            />
            <div class="file-name-row" v-if="msg.fileName">
              <span class="file-label">{{ msg.fileName }}</span>
              <a :href="getDownloadSrc(msg)" download class="download-link" :title="$t('chat.download')">
                <el-icon><Download /></el-icon>
              </a>
            </div>
          </div>

          <!-- Audio Message -->
          <div v-else-if="msg.type === 'AUDIO'" class="message-audio">
            <audio :src="getPreviewSrc(msg)" controls preload="metadata" class="audio-content" />
            <div class="file-name-row" v-if="msg.fileName">
              <span class="file-label">{{ msg.fileName }}</span>
            </div>
          </div>

          <!-- File Message -->
          <div v-else-if="msg.type === 'FILE'" class="message-file" @click="handleFileClick(msg)">
            <div class="file-icon-wrapper">
              <el-icon :size="28"><Document /></el-icon>
            </div>
            <div class="file-details">
              <span class="file-name">{{ msg.fileName || msg.content }}</span>
              <span class="file-meta">{{ formatFileSize(msg.fileSize) }}</span>
            </div>
            <a
              :href="getDownloadSrc(msg)"
              download
              class="file-download-btn"
              :title="$t('chat.download')"
              @click.stop
            >
              <el-icon :size="20"><Download /></el-icon>
            </a>
          </div>
        </div>

        <div v-if="msg.reactions?.length" class="reaction-row" :class="{ 'reaction-sent': msg.isSelf }">
          <button
            v-for="reaction in msg.reactions"
            :key="reaction.emoji"
            class="reaction-pill"
            :class="{ active: reaction.reactedByMe }"
            @click="$emit('react', msg, reaction.emoji)"
          >
            <span>{{ reaction.emoji }}</span>
            <span>{{ reaction.count }}</span>
          </button>
        </div>

        <div class="message-meta" :class="{ 'meta-sent': msg.isSelf }">
          <span class="message-time">{{ formatTime(msg.timestamp) }}</span>
          <button
            v-if="msg.isEdited && !msg.isRecalled"
            class="edited-label clickable"
            :title="$t('chat.editHistory')"
            @click="$emit('viewEditHistory', msg)"
          >{{ $t('chat.editedCount', { count: msg.editCount || 1 }) }}</button>
          <!-- Sending indicator -->
          <span v-if="msg.isSelf && msg.status === 'sending'" class="sending-status" :title="$t('chat.sending')">
            <el-icon :size="14" class="spin"><Loading /></el-icon>
          </span>
          <!-- Failed indicator (click to resend) -->
          <button v-else-if="msg.isSelf && msg.failed" class="failed-status" :title="$t('chat.clickToResend')" @click="$emit('resend', msg)">
            <el-icon color="#ef4444" :size="16"><WarningFilled /></el-icon>
          </button>
          <!-- Read status -->
          <span v-else-if="msg.isSelf" class="read-status">
            <svg v-if="msg.read" class="check-icon read" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <path d="M2 12l5 5L20 4M7 12l5 5L22 4" />
            </svg>
            <svg v-else class="check-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <path d="M5 12l5 5L20 7" />
            </svg>
          </span>
          <div class="message-actions">
            <button class="inline-action" :title="$t('chat.reply')" :aria-label="$t('chat.reply')" @click="$emit('reply', msg)"><span class="material-icons-round">reply</span></button>
            <el-popover
              placement="top"
              trigger="manual"
              :visible="openPicker === msg.id"
              :width="300"
              :show-arrow="false"
              popper-class="emoji-popper"
            >
              <template #reference>
                <button class="inline-action react-trigger" :title="$t('chat.react')" :aria-label="$t('chat.react')" @click.stop="togglePicker(msg.id)"><span class="material-icons-round">add_reaction</span></button>
              </template>
              <div class="emoji-grid">
                <EmojiPicker
                  :native="true"
                  :theme="pickerTheme"
                  :disable-skin-tones="true"
                  @select="(e) => pickEmoji(msg, e.i)"
                />
              </div>
            </el-popover>
            <button v-if="canEditMessage(msg)" class="inline-action" :title="$t('chat.edit')" :aria-label="$t('chat.edit')" @click="$emit('edit', msg)"><span class="material-icons-round">edit</span></button>
            <button v-if="canRecallMessage(msg)" class="inline-action danger" :title="$t('chat.recall')" :aria-label="$t('chat.recall')" @click="$emit('recall', msg)"><span class="material-icons-round">undo</span></button>
          </div>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, onUpdated, watch } from 'vue'
import { useRouter } from 'vue-router'
import { WarningFilled, Download, Document, PictureFilled, Loading } from '@element-plus/icons-vue'
import { API_BASE_URL } from '@/services/runtimeConfig'

import dayjs from 'dayjs'
import EmojiPicker from 'vue3-emoji-picker'
import 'vue3-emoji-picker/css'

const router = useRouter()

const props = defineProps({
  messages: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['reply', 'edit', 'recall', 'react', 'viewEditHistory', 'resend'])

const listRef = ref(null)
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'

// Emoji reaction picker open state (keyed by message id) + theme sync.
const openPicker = ref(null)
const pickerTheme = ref('light')
const currentTheme = () => {
  const attr = document.documentElement.getAttribute('data-theme') || document.body.getAttribute('data-theme')
  return attr === 'dark' ? 'dark' : 'light'
}
const togglePicker = (id) => {
  if (openPicker.value === id) {
    openPicker.value = null
  } else {
    pickerTheme.value = currentTheme()
    openPicker.value = id
  }
}
const pickEmoji = (msg, emoji) => {
  emit('react', msg, emoji)
  openPicker.value = null
}
const handleDocPointerDown = (e) => {
  if (openPicker.value === null) return
  const target = e.target
  if (target?.closest?.('.emoji-popper') || target?.closest?.('.react-trigger')) return
  openPicker.value = null
}

// Reactive clock for auto-expiring edit/recall buttons.
// Backend windows: 15 min edit, 2 min recall. Tick at 15s precision is enough.
const EDIT_WINDOW_MS = 15 * 60 * 1000
const RECALL_WINDOW_MS = 2 * 60 * 1000
const MAX_EDIT_COUNT = 3
const now = ref(Date.now())
let clockTimer = null
onMounted(() => {
  clockTimer = setInterval(() => { now.value = Date.now() }, 15000)
  document.addEventListener('mousedown', handleDocPointerDown)
})
onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
  document.removeEventListener('mousedown', handleDocPointerDown)
})

const messageCreatedMs = (msg) => {
  const raw = msg?.createdAt || msg?.timestamp
  if (!raw) return 0
  const t = new Date(raw).getTime()
  return Number.isFinite(t) ? t : 0
}

// Consecutive-message grouping: same sender within a short window collapses
// avatar/name and tightens spacing for a cleaner, more mature thread look.
const GROUP_GAP_MS = 5 * 60 * 1000

const isGroupedWithPrev = (i) => {
  const cur = props.messages[i]
  const prev = props.messages[i - 1]
  if (!cur || !prev) return false
  if (prev.senderId !== cur.senderId) return false
  return (messageCreatedMs(cur) - messageCreatedMs(prev)) < GROUP_GAP_MS
}

const isGroupedWithNext = (i) => {
  const cur = props.messages[i]
  const next = props.messages[i + 1]
  if (!cur || !next) return false
  if (next.senderId !== cur.senderId) return false
  return (messageCreatedMs(next) - messageCreatedMs(cur)) < GROUP_GAP_MS
}

const canEditMessage = (msg) => {
  if (!msg || !msg.isSelf || msg.isRecalled) return false
  if (msg.type !== 'TEXT') return false
  if (msg.canEdit === false) return false
  if ((msg.editCount || 0) >= MAX_EDIT_COUNT) return false
  const created = messageCreatedMs(msg)
  return created > 0 && (now.value - created) < EDIT_WINDOW_MS
}

const canRecallMessage = (msg) => {
  if (!msg || !msg.isSelf || msg.isRecalled) return false
  if (msg.canRecall === false) return false
  const created = messageCreatedMs(msg)
  return created > 0 && (now.value - created) < RECALL_WINDOW_MS
}


const scrollToBottom = () => {
  if (listRef.value) {
    listRef.value.scrollTop = listRef.value.scrollHeight
  }
}

onUpdated(() => {
  scrollToBottom()
})

watch(() => props.messages, () => {
  scrollToBottom()
}, { deep: true })

const formatTime = (time) => {
  return dayjs(time).format('HH:mm')
}

const formatFileSize = (bytes) => {
  if (!bytes) return ''
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

const getPreviewSrc = (msg) => {
  // Priority: previewUrl > fileUrl > content
  if (msg.previewUrl) {
    return msg.previewUrl.startsWith('http') ? msg.previewUrl : `${API_BASE_URL}${msg.previewUrl}`
  }
  if (msg.downloadUrl) {
    return msg.downloadUrl.startsWith('http') ? msg.downloadUrl : `${API_BASE_URL}${msg.downloadUrl}`
  }
  if (msg.fileUrl) {
    return msg.fileUrl.startsWith('http') ? msg.fileUrl : `${API_BASE_URL}${msg.fileUrl}`
  }
  return msg.content
}

const getDownloadSrc = (msg) => {
  if (msg.downloadUrl) {
    return msg.downloadUrl.startsWith('http') ? msg.downloadUrl : `${API_BASE_URL}${msg.downloadUrl}`
  }
  if (msg.fileUrl) {
    return msg.fileUrl.startsWith('http') ? msg.fileUrl : `${API_BASE_URL}${msg.fileUrl}`
  }
  return '#'
}


  const handleFileClick = (msg) => {
    // 直接下载文件
    const url = getDownloadSrc(msg)
    if (url && url !== '#') {
      window.open(url, '_blank')
    }
  }

const handleAvatarClick = (senderId) => {
  if (senderId) {
    router.push(`/user/${senderId}`)
  }
}
</script>

<style scoped>
.message-list {
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 12px 20px;
  display: flex;
  flex-direction: column;
  position: relative;
  z-index: 1;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.message-list::-webkit-scrollbar {
  width: 0;
  height: 0;
  display: none;
}

.message-wrapper {
  display: flex;
  gap: 8px;
  max-width: 70%;
  margin-top: 10px;
  animation: fadeInUp 0.3s ease-out;
}

.message-wrapper.grouped {
  margin-top: 2px;
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.message-wrapper.sent {
  margin-left: auto;
  flex-direction: row-reverse;
}

.message-avatar {
  flex-shrink: 0;
  align-self: flex-end;
  margin-bottom: 18px;
  width: 36px;
}

.message-avatar .el-avatar {
  border: 1px solid rgba(15, 23, 42, 0.08);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
}

[data-theme="dark"] .message-avatar .el-avatar {
  border-color: rgba(255, 255, 255, 0.12);
}

.clickable-avatar {
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.clickable-avatar:hover {
  transform: scale(1.1);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.message-content {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.message-wrapper.sent .message-content {
  align-items: flex-end;
}

.message-bubble {
  padding: 10px 14px;
  position: relative;
  transition: box-shadow 0.25s ease;
}

/* Received message bubble */
.bubble-in {
  background: var(--tg-message-in);
  border-radius: 18px 18px 18px 4px;
  border: 1px solid rgba(226, 232, 240, 0.7);
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.06);
}

.bubble-in:hover {
  box-shadow: 0 6px 16px -6px rgba(16, 24, 40, 0.18);
}

.bubble-in .message-text {
  color: var(--tg-text-primary);
}

.bubble-in .sender-name {
  color: var(--tg-primary);
}

[data-theme="dark"] .bubble-in {
  border: 1px solid rgba(51, 65, 85, 0.5);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.35);
}

[data-theme="dark"] .bubble-in:hover {
  box-shadow: 0 6px 16px -6px rgba(0, 0, 0, 0.5);
}

/* Sent message bubble */
.bubble-out {
  background: linear-gradient(135deg, #0d9488 0%, #0f766e 100%);
  border-radius: 18px 18px 4px 18px;
  color: #FFFFFF;
  box-shadow: 0 4px 14px -5px rgba(15, 118, 110, 0.5);
}

.bubble-out:hover {
  box-shadow: 0 8px 20px -6px rgba(15, 118, 110, 0.62);
}

/* Grouped continuation: flatten the corner that connects to the message above */
.bubble-in.grp-cont {
  border-top-left-radius: 4px;
}

.bubble-out.grp-cont {
  border-top-right-radius: 4px;
}

.bubble-out .message-text {
  color: #FFFFFF;
}

.bubble-out .reply-author {
  color: #FFFFFF;
}

.bubble-out .message-recalled {
  color: rgba(255, 255, 255, 0.75);
}

.sender-name {
  font-size: 12px;
  color: var(--tg-primary);
  font-weight: 700;
  margin-bottom: 2px;
}

.message-text {
  font-size: 15px;
  line-height: 1.5;
  color: var(--tg-text-primary);
  word-wrap: break-word;
}

.message-recalled {
  font-size: 14px;
  font-style: italic;
  color: var(--tg-text-tertiary);
}

.reply-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  margin-bottom: 6px;
  background: rgba(15, 23, 42, 0.06);
  border-radius: 12px;
  max-width: 280px;
  overflow: hidden;
}

.reply-icon {
  font-size: 16px;
  color: var(--tg-primary);
  opacity: 0.85;
  flex-shrink: 0;
}

.reply-body {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.bubble-out .reply-preview {
  background: rgba(255, 255, 255, 0.16);
}

.bubble-out .reply-icon {
  color: rgba(255, 255, 255, 0.9);
}

[data-theme="dark"] .reply-preview {
  background: rgba(255, 255, 255, 0.07);
}

.reply-author {
  font-size: 12px;
  font-weight: 700;
  color: var(--tg-primary);
}

.reply-text {
  font-size: 12px;
  color: inherit;
  opacity: 0.8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.reaction-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 2px;
}

.reaction-row.reaction-sent {
  justify-content: flex-end;
}

.reaction-pill {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: 1px solid rgba(148, 163, 184, 0.35);
  background: var(--tg-surface);
  border-radius: 999px;
  padding: 2px 7px;
  font-size: 12px;
  color: var(--tg-text-secondary);
  cursor: pointer;
}

.reaction-pill.active {
  border-color: var(--tg-primary);
  color: var(--tg-primary);
  background: rgba(6, 182, 212, 0.08);
}

/* Image message */
.message-image {
  max-width: 300px;
  border-radius: 12px;
  overflow: hidden;
}

.image-content {
  width: 100%;
  height: auto;
  display: block;
}

.image-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 32px;
  color: var(--tg-text-tertiary);
  font-size: 12px;
}

/* Video message */
.message-video {
  max-width: 320px;
}

.video-content {
  width: 100%;
  max-height: 240px;
  border-radius: 12px;
  background: #000;
}

/* Audio message */
.message-audio {
  min-width: 240px;
}

.audio-content {
  width: 100%;
  height: 36px;
}

.file-name-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
}

.file-label {
  font-size: 12px;
  color: var(--tg-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}

.bubble-out .file-label {
  color: rgba(255, 255, 255, 0.85);
}

.download-link {
  color: var(--tg-text-secondary);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  transition: color 0.2s;
}

.download-link:hover {
  color: var(--tg-primary);
}

.bubble-out .download-link {
  color: rgba(255, 255, 255, 0.7);
}

.bubble-out .download-link:hover {
  color: #FFFFFF;
}

/* File message */
.message-file {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 0;
  cursor: pointer;
  min-width: 220px;
}

.file-icon-wrapper {
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 12px;
  color: var(--tg-primary);
  flex-shrink: 0;
}

.bubble-out .file-icon-wrapper {
  background: rgba(255, 255, 255, 0.2);
  color: #FFFFFF;
}

.file-details {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.file-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--tg-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bubble-out .file-name {
  color: #FFFFFF;
}

.file-meta {
  font-size: 12px;
  color: var(--tg-text-tertiary);
}

.bubble-out .file-meta {
  color: rgba(255, 255, 255, 0.7);
}

.file-download-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.05);
  color: var(--tg-primary);
  transition: all 0.2s;
  flex-shrink: 0;
  text-decoration: none;
}

.file-download-btn:hover {
  background: rgba(0, 0, 0, 0.1);
  transform: scale(1.1);
}

.bubble-out .file-download-btn {
  background: rgba(255, 255, 255, 0.2);
  color: #FFFFFF;
}

.bubble-out .file-download-btn:hover {
  background: rgba(255, 255, 255, 0.3);
}

.message-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-left: 4px;
}

.message-meta.meta-sent {
  padding-right: 4px;
  padding-left: 0;
}

.message-time {
  font-size: 11px;
  color: var(--tg-text-tertiary);
  font-weight: 500;
}

.edited-label {
  font-size: 11px;
  color: var(--tg-text-tertiary);
}

.edited-label.clickable {
  border: none;
  background: transparent;
  padding: 0 2px;
  cursor: pointer;
  font-weight: 600;
  text-decoration: underline dotted;
  text-underline-offset: 2px;
}

.edited-label.clickable:hover {
  color: var(--tg-primary);
}

.message-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.inline-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  background: transparent;
  color: var(--tg-text-tertiary);
  border-radius: 7px;
  cursor: pointer;
  padding: 0;
  transition: background 0.15s ease, color 0.15s ease;
}

.inline-action:hover {
  background: rgba(15, 23, 42, 0.06);
  color: var(--tg-primary);
}

.inline-action.danger:hover {
  background: rgba(239, 68, 68, 0.1);
  color: #ef4444;
}

.inline-action .material-icons-round {
  font-size: 17px;
}

[data-theme="dark"] .inline-action:hover {
  background: rgba(255, 255, 255, 0.08);
}

/* Desktop: float the action toolbar beside the bubble so the resting row stays clean */
@media (hover: hover) and (pointer: fine) {
  .message-content {
    position: relative;
  }

  .message-actions {
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    padding: 3px;
    background: #ffffff;
    border: 1px solid rgba(15, 23, 42, 0.06);
    border-radius: 12px;
    box-shadow: 0 6px 18px -6px rgba(15, 23, 42, 0.22);
    opacity: 0;
    pointer-events: none;
    transition: opacity 0.15s ease;
    z-index: 5;
  }

  .message-wrapper:not(.sent) .message-actions {
    left: calc(100% + 8px);
  }

  .message-wrapper.sent .message-actions {
    right: calc(100% + 8px);
  }

  .message-wrapper:hover .message-actions,
  .message-wrapper.picker-open .message-actions {
    opacity: 1;
    pointer-events: auto;
  }

  /* Transparent bridge across the gap so moving the cursor from the bubble to
     the toolbar doesn't drop :hover (which would hide it before a click lands). */
  .message-actions::before {
    content: '';
    position: absolute;
    top: 0;
    bottom: 0;
    width: 14px;
  }

  .message-wrapper:not(.sent) .message-actions::before {
    left: -14px;
  }

  .message-wrapper.sent .message-actions::before {
    right: -14px;
  }

  [data-theme="dark"] .message-actions {
    background: #1e293b;
    border-color: rgba(255, 255, 255, 0.08);
    box-shadow: 0 6px 18px -6px rgba(0, 0, 0, 0.5);
  }
}

.read-status {
  display: flex;
  align-items: center;
}

.check-icon {
  width: 16px;
  height: 16px;
  color: var(--tg-text-tertiary);
}

.check-icon.read {
  color: var(--tg-primary);
}

.failed-status {
  display: flex;
  align-items: center;
  cursor: pointer;
  border: none;
  background: transparent;
  padding: 0;
}

.sending-status {
  display: flex;
  align-items: center;
  color: var(--tg-text-tertiary);
}

.sending-status .spin {
  animation: msg-status-spin 0.9s linear infinite;
}

@keyframes msg-status-spin {
  to { transform: rotate(360deg); }
}

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .message-list {
    padding: 10px 12px;
  }

  .message-wrapper {
    max-width: 85%;
  }

  .message-avatar {
    margin-bottom: 16px;
  }

  .message-avatar .el-avatar {
    width: 32px !important;
    height: 32px !important;
  }

  .message-bubble {
    padding: 10px 12px;
  }

  .bubble-in {
    border-radius: 16px 16px 16px 4px;
  }

  .bubble-out {
    border-radius: 16px 16px 4px 16px;
  }

  .sender-name {
    font-size: 11px;
  }

  .message-text {
    font-size: 14px;
    line-height: 1.4;
  }

  .message-image {
    max-width: 240px;
  }

  .message-video {
    max-width: 260px;
  }

  .message-audio {
    min-width: 200px;
  }

  .message-file {
    min-width: 180px;
    gap: 10px;
  }

  .file-icon-wrapper {
    width: 40px;
    height: 40px;
    border-radius: 8px;
  }

  .file-name {
    font-size: 13px;
  }

  .file-meta {
    font-size: 11px;
  }

  .file-download-btn {
    width: 32px;
    height: 32px;
  }

  .message-time {
    font-size: 10px;
  }

  .check-icon {
    width: 14px;
    height: 14px;
  }
}

/* Small phones */
@media (max-width: 375px) {
  .message-wrapper {
    max-width: 88%;
  }

  .message-list {
    padding: 8px 10px;
  }
}
</style>

<style>
/* Emoji picker popover (teleported to body, so it can't be scoped).
   The popover acts as a transparent wrapper; vue3-emoji-picker provides its own
   themed container. */
.emoji-popper.el-popper {
  padding: 0 !important;
  border: none !important;
  background: transparent !important;
  box-shadow: none !important;
  min-width: unset !important;
}

.emoji-popper .v3-emoji-picker {
  width: 100% !important;
  border-radius: 14px !important;
  box-shadow: 0 12px 32px -8px rgba(15, 23, 42, 0.28) !important;
}

[data-theme="dark"] .emoji-popper .v3-emoji-picker {
  box-shadow: 0 12px 32px -8px rgba(0, 0, 0, 0.6) !important;
}
</style>

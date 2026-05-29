<template>
  <div class="message-list" ref="listRef">
    <div
      v-for="msg in messages"
      :key="msg.id"
      class="message-wrapper"
      :class="{ 'sent': msg.isSelf }"
    >
      <!-- Avatar for received messages -->
      <div v-if="!msg.isSelf" class="message-avatar">
        <el-avatar
          :size="36"
          :src="msg.senderAvatar || defaultAvatar"
          class="clickable-avatar"
          @click="handleAvatarClick(msg.senderId)"
        />
      </div>

      <div class="message-content">
        <div class="message-bubble" :class="msg.isSelf ? 'bubble-out' : 'bubble-in'">
          <div v-if="!msg.isSelf" class="sender-name">{{ msg.senderName }}</div>

          <div v-if="msg.replyToMessage" class="reply-preview">
            <span class="reply-author">{{ msg.replyToMessage.senderNickname || msg.replyToMessage.senderName || $t('chat.unknownUser') }}</span>
            <span class="reply-text">{{ msg.replyToMessage.isRecalled ? $t('chat.messageRecalled') : msg.replyToMessage.content }}</span>
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
          <!-- Failed indicator -->
          <span v-if="msg.isSelf && msg.failed" class="failed-status" :title="$t('chat.sendFailed')">
            <el-icon color="#ef4444" :size="16"><WarningFilled /></el-icon>
          </span>
          <!-- Read status -->
          <span v-else-if="msg.isSelf" class="read-status">
            <svg v-if="msg.read" class="check-icon read" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <path d="M2 12l5 5L20 4M7 12l5 5L22 4" />
            </svg>
            <svg v-else class="check-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <path d="M5 12l5 5L20 7" />
            </svg>
          </span>
          <button class="inline-action" :title="$t('chat.reply')" @click="$emit('reply', msg)">{{ $t('chat.reply') }}</button>
          <button class="inline-action" :title="$t('chat.react')" @click="$emit('react', msg, '👍')">👍</button>
          <button v-if="canEditMessage(msg)" class="inline-action" :title="$t('chat.edit')" @click="$emit('edit', msg)">{{ $t('chat.edit') }}</button>
          <button v-if="canRecallMessage(msg)" class="inline-action danger" :title="$t('chat.recall')" @click="$emit('recall', msg)">{{ $t('chat.recall') }}</button>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, onUpdated, watch } from 'vue'
import { useRouter } from 'vue-router'
import { WarningFilled, Download, Document, PictureFilled } from '@element-plus/icons-vue'
import { API_BASE_URL } from '@/services/runtimeConfig'

import dayjs from 'dayjs'

const router = useRouter()

const props = defineProps({
  messages: {
    type: Array,
    default: () => []
  }
})

defineEmits(['reply', 'edit', 'recall', 'react', 'viewEditHistory'])

const listRef = ref(null)
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'

// Reactive clock for auto-expiring edit/recall buttons.
// Backend windows: 15 min edit, 2 min recall. Tick at 15s precision is enough.
const EDIT_WINDOW_MS = 15 * 60 * 1000
const RECALL_WINDOW_MS = 2 * 60 * 1000
const MAX_EDIT_COUNT = 3
const now = ref(Date.now())
let clockTimer = null
onMounted(() => {
  clockTimer = setInterval(() => { now.value = Date.now() }, 15000)
})
onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
})

const messageCreatedMs = (msg) => {
  const raw = msg?.createdAt || msg?.timestamp
  if (!raw) return 0
  const t = new Date(raw).getTime()
  return Number.isFinite(t) ? t : 0
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
  padding: 12px 20px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  position: relative;
  z-index: 1;
}

.message-wrapper {
  display: flex;
  gap: 8px;
  max-width: 70%;
  animation: fadeInUp 0.3s ease-out;
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
}

.message-avatar .el-avatar {
  border: 2px solid var(--tg-surface);
  box-shadow: var(--tg-shadow-sm);
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
  box-shadow: var(--tg-shadow-soft);
  transition: transform 0.2s ease;
}

.message-bubble:hover {
  transform: scale(1.01);
}

/* Received message bubble */
.bubble-in {
  background: var(--tg-message-in);
  border-radius: 18px 18px 18px 4px;
  border: 1px solid rgba(226, 232, 240, 0.5);
}

.bubble-in .message-text {
  color: var(--tg-text-primary);
}

.bubble-in .sender-name {
  color: var(--tg-primary);
}

[data-theme="dark"] .bubble-in {
  border: 1px solid rgba(51, 65, 85, 0.5);
}

/* Sent message bubble */
.bubble-out {
  background: #10B981;
  border-radius: 18px 18px 4px 18px;
}

.bubble-out .message-text {
  color: #FFFFFF !important;
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
  flex-direction: column;
  gap: 2px;
  padding: 6px 8px;
  margin-bottom: 6px;
  border-left: 3px solid rgba(6, 182, 212, 0.7);
  background: rgba(255, 255, 255, 0.18);
  border-radius: 6px;
  max-width: 280px;
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
  border-radius: 8px;
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
  border-radius: 10px;
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
  font-weight: 600;
  letter-spacing: 0.3px;
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

.inline-action {
  border: none;
  background: transparent;
  color: var(--tg-text-tertiary);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  padding: 0 2px;
}

.inline-action:hover {
  color: var(--tg-primary);
}

.inline-action.danger:hover {
  color: #ef4444;
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
}

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .message-list {
    padding: 10px 12px;
    gap: 4px;
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

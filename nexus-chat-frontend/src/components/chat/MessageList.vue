<template>
  <div class="message-list" ref="listRef">
    <template v-for="(msg, i) in messages" :key="msg.id">
    <div v-if="startsNewDay(i)" class="date-divider">
      <span class="date-pill">{{ formatDayLabel(msg.timestamp) }}</span>
    </div>
    <div
      class="message-wrapper"
      :class="{ 'sent': msg.isSelf, 'grouped': isGroupedWithPrev(i), 'picker-open': openPicker === msg.id }"
    >
      <!-- Avatar for received messages; no portrait → monochrome initial -->
      <div v-if="!msg.isSelf" class="message-avatar">
        <el-avatar
          v-if="msg.senderAvatar"
          :size="32"
          :src="msg.senderAvatar"
          :alt="msg.senderName"
          class="clickable-avatar"
          @click="handleAvatarClick(msg.senderId)"
        />
        <div
          v-else
          class="avatar-initial clickable-avatar"
          :data-initial="(msg.senderName || '?').trim().charAt(0) || '?'"
          @click="handleAvatarClick(msg.senderId)"
        ></div>
      </div>

      <div class="message-content">
        <div class="message-meta" :class="{ 'meta-sent': msg.isSelf }">
          <span class="head-name">{{ msg.isSelf ? $t('group.me') : msg.senderName }}</span>
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
            <el-icon color="#f2b8b5" :size="16"><WarningFilled /></el-icon>
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
            <button class="inline-action" :title="$t('chat.reply')" :aria-label="$t('chat.reply')" @click="$emit('reply', msg)"><span class="material-symbols-outlined">reply</span></button>
            <el-popover
              placement="top"
              trigger="manual"
              :visible="openPicker === msg.id"
              :width="300"
              :show-arrow="false"
              popper-class="emoji-popper"
            >
              <template #reference>
                <button class="inline-action react-trigger" :title="$t('chat.react')" :aria-label="$t('chat.react')" @click.stop="togglePicker(msg.id)"><span class="material-symbols-outlined">add_reaction</span></button>
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
            <button v-if="canEditMessage(msg)" class="inline-action" :title="$t('chat.edit')" :aria-label="$t('chat.edit')" @click="$emit('edit', msg)"><span class="material-symbols-outlined">edit</span></button>
            <button v-if="canRecallMessage(msg)" class="inline-action danger" :title="$t('chat.recall')" :aria-label="$t('chat.recall')" @click="$emit('recall', msg)"><span class="material-symbols-outlined">undo</span></button>
          </div>
        </div>
        <div class="message-bubble" :class="[msg.isSelf ? 'bubble-out' : 'bubble-in', { 'grp-cont': isGroupedWithPrev(i) }]">

          <div v-if="msg.replyToMessage" class="reply-preview">
            <span class="material-symbols-outlined reply-icon">reply</span>
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
              <span class="material-symbols-outlined">description</span>
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
              <span class="material-symbols-outlined">download</span>
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

      </div>
    </div>
    </template>

  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, onUpdated, watch } from 'vue'
import { useRouter } from 'vue-router'
import { WarningFilled, Download, PictureFilled, Loading } from '@element-plus/icons-vue'
import { API_BASE_URL } from '@/services/runtimeConfig'

import dayjs from 'dayjs'
import { useI18n } from 'vue-i18n'
import EmojiPicker from 'vue3-emoji-picker'
import 'vue3-emoji-picker/css'

const router = useRouter()
const { t } = useI18n()

const props = defineProps({
  messages: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['reply', 'edit', 'recall', 'react', 'viewEditHistory', 'resend'])

const listRef = ref(null)

// Emoji reaction picker open state (keyed by message id). Prism is dark-only.
const openPicker = ref(null)
const pickerTheme = ref('dark')
const currentTheme = () => 'dark'
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

// A day pill opens every calendar day in the stream ("今天 14:10").
const startsNewDay = (i) => {
  const cur = props.messages[i]
  if (!cur?.timestamp) return false
  if (i === 0) return true
  const prev = props.messages[i - 1]
  return !prev?.timestamp || !dayjs(cur.timestamp).isSame(dayjs(prev.timestamp), 'day')
}

const formatDayLabel = (time) => {
  const d = dayjs(time)
  const day = d.isSame(dayjs(), 'day') ? t('chat.today') : d.format('YYYY-MM-DD')
  return `${day} ${d.format('HH:mm')}`
}

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
  padding: 16px 24px;
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

/* ── Row ────────────────────────────────────────────────── */
.date-divider {
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 4px 0;
}

.date-divider + .message-wrapper {
  margin-top: 16px;
}

.message-wrapper + .date-divider {
  margin-top: 20px;
}

.date-pill {
  padding: 4px 12px;
  border-radius: 9999px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.2);
  color: rgba(196, 199, 200, 0.7);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
}

.message-wrapper {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  max-width: 75%;
  margin-top: 16px;
  animation: fadeInUp 0.2s ease-out;
}

.message-list > .message-wrapper:first-child {
  margin-top: 0;
}

@keyframes fadeInUp {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

.message-wrapper.sent {
  margin-left: auto;
  flex-direction: row-reverse;
}

.message-avatar {
  flex-shrink: 0;
  align-self: flex-start;
  margin-top: 2px;
  width: 32px;
}

.message-avatar .el-avatar {
  background: #2a2a2d;
  box-shadow: 0 0 0 1px rgba(68, 71, 72, 0.3);
  filter: grayscale(100%) contrast(1.1);
}

.avatar-initial {
  width: 32px;
  height: 32px;
  border-radius: 9999px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #2a2a2d;
  color: #ffffff;
  box-shadow: 0 0 0 1px rgba(68, 71, 72, 0.3);
}

.clickable-avatar {
  cursor: pointer;
  transition: opacity 0.15s ease;
}

.clickable-avatar:hover {
  opacity: 0.8;
}

.message-content {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  min-width: 0;
}

.message-wrapper.sent .message-content {
  align-items: flex-end;
}

/* ── Bubbles ──────────────────────────────────────────────
   Received: rounded-xl rounded-tl-none bg-surface-container-low/90 + border.
   Sent:     rounded-xl rounded-tr-none bg-white text-zinc-900. */
.message-bubble {
  padding: 16px;
  position: relative;
  max-width: 100%;
  transition: border-color 0.15s ease;
}

.bubble-in {
  background: rgba(27, 27, 30, 0.9);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid rgba(68, 71, 72, 0.3);
  border-radius: 0 12px 12px 12px;
  box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
}

.bubble-in:hover {
  border-color: rgba(68, 71, 72, 0.5);
}

.bubble-out {
  background: #ffffff;
  border-radius: 12px 0 12px 12px;
  color: #18181b;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -2px rgba(0, 0, 0, 0.1);
}

.bubble-out ::selection {
  background: #e2e2e2;
  color: #131316;
}

.bubble-in .message-text {
  color: #e4e1e6;
}

.bubble-out .message-text {
  color: #18181b;
}

.bubble-out .reply-author {
  color: #2f3131;
}

.bubble-out .message-recalled {
  color: rgba(19, 19, 22, 0.6);
}

.message-text {
  font-size: 15px;
  line-height: 1.625;
  letter-spacing: -0.005em;
  word-wrap: break-word;
  white-space: pre-wrap;
}

.message-recalled {
  font-size: 13px;
  font-style: italic;
  color: #6f7274;
}

/* ── Reply quote ────────────────────────────────────────── */
.reply-preview {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 5px 8px;
  margin-bottom: 6px;
  background: rgba(255, 255, 255, 0.05);
  border-left: 2px solid rgba(255, 255, 255, 0.4);
  border-radius: 3px;
  max-width: 260px;
  overflow: hidden;
}

.bubble-out .reply-preview {
  background: rgba(19, 19, 22, 0.06);
  border-left-color: rgba(19, 19, 22, 0.4);
}

.reply-icon {
  font-size: 14px;
  color: #8e9192;
  flex-shrink: 0;
  margin-top: 1px;
}

.bubble-out .reply-icon {
  color: rgba(19, 19, 22, 0.55);
}

.reply-body {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.reply-author {
  font-size: 11px;
  font-weight: 600;
}

.reply-text {
  font-size: 11px;
  color: inherit;
  opacity: 0.75;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ── Reactions ──────────────────────────────────────────── */
.reaction-row {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
  margin-top: 3px;
}

.reaction-row.reaction-sent {
  justify-content: flex-end;
}

.reaction-pill {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 1px 6px;
  border: 1px solid rgba(68, 71, 72, 0.4);
  background: #16161a;
  border-radius: 999px;
  font-size: 11px;
  color: #c4c7c8;
  cursor: pointer;
  transition: all 0.15s ease;
}

.reaction-pill:hover {
  border-color: rgba(68, 71, 72, 0.7);
}

.reaction-pill.active {
  border-color: #ffffff;
  color: #ffffff;
  background: rgba(255, 255, 255, 0.08);
}

/* ── Attachments ────────────────────────────────────────── */
.message-image {
  max-width: 300px;
  border-radius: 8px;
  overflow: hidden;
  filter: grayscale(100%) contrast(1.05);
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
  color: #6f7274;
  font-size: 12px;
}

.message-video {
  max-width: 320px;
}

.video-content {
  width: 100%;
  max-height: 240px;
  border-radius: 8px;
  background: #0e0e11;
  filter: grayscale(100%) contrast(1.05);
}

.message-audio {
  min-width: 240px;
}

.audio-content {
  width: 100%;
  height: 34px;
  filter: grayscale(100%);
}

.file-name-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
}

.file-label {
  font-size: 11px;
  color: #8e9192;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}

.bubble-out .file-label {
  color: rgba(19, 19, 22, 0.65);
}

.download-link {
  color: #8e9192;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  transition: color 0.15s ease;
}

.download-link:hover {
  color: #ffffff;
}

.bubble-out .download-link {
  color: rgba(19, 19, 22, 0.6);
}

.bubble-out .download-link:hover {
  color: #2f3131;
}

.message-file {
  display: flex;
  align-items: center;
  gap: 16px;
  cursor: pointer;
  min-width: 286px;
}

.file-icon-wrapper {
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #2a2a2d;
  border: 1px solid rgba(68, 71, 72, 0.3);
  border-radius: 8px;
  color: #ffffff;
  flex-shrink: 0;
}

.file-icon-wrapper .material-symbols-outlined {
  font-size: 22px;
}

.bubble-out .file-icon-wrapper {
  background: rgba(19, 19, 22, 0.06);
  border-color: rgba(19, 19, 22, 0.12);
  color: #2f3131;
}

.file-details {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.file-name {
  font-size: 13px;
  line-height: 18px;
  letter-spacing: 0.01em;
  font-weight: 500;
  color: #ffffff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bubble-out .file-name {
  color: #2f3131;
}

.file-meta {
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: rgba(196, 199, 200, 0.7);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
}

.bubble-out .file-meta {
  color: rgba(19, 19, 22, 0.55);
}

.file-download-btn {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  background: #2a2a2d;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #ffffff;
  transition: all 0.15s ease;
  flex-shrink: 0;
  text-decoration: none;
}

.file-download-btn:hover {
  background: #353438;
  color: #ffffff;
}

.file-download-btn .material-symbols-outlined {
  font-size: 18px;
}

.bubble-out .file-download-btn {
  background: rgba(19, 19, 22, 0.06);
  border-color: rgba(19, 19, 22, 0.12);
  color: rgba(19, 19, 22, 0.7);
}

.bubble-out .file-download-btn:hover {
  background: rgba(19, 19, 22, 0.08);
  color: #2f3131;
}

/* ── Head row: name + time above the bubble ─────────────── */
.message-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 16px;
}

.message-meta.meta-sent {
  justify-content: flex-end;
}

.head-name {
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: #c4c7c8;
}

/* Sent rows read "14:20 我": status → time → name */
.meta-sent .head-name {
  order: 3;
  color: #ffffff;
}

.meta-sent .message-time {
  order: 2;
}

.message-time {
  font-size: 12px;
  line-height: 16px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: rgba(196, 199, 200, 0.6);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
}

.edited-label {
  font-size: 10px;
  color: #6f7274;
}

.edited-label.clickable {
  border: none;
  background: transparent;
  padding: 0;
  cursor: pointer;
  text-decoration: underline dotted;
  text-underline-offset: 2px;
}

.edited-label.clickable:hover {
  color: #c4c7c8;
}

.message-actions {
  display: inline-flex;
  align-items: center;
  gap: 1px;
}

.inline-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  background: transparent;
  color: #6f7274;
  border-radius: 4px;
  cursor: pointer;
  padding: 0;
  transition: background 0.15s ease, color 0.15s ease;
}

.inline-action:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #ffffff;
}

.inline-action.danger:hover {
  background: rgba(255, 180, 171, 0.12);
  color: #ffb4ab;
}

.inline-action .material-symbols-outlined {
  font-size: 16px;
}

/* Desktop: float the action toolbar beside the bubble */
@media (hover: hover) and (pointer: fine) {
  .message-content {
    position: relative;
  }

  .message-actions {
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    padding: 2px;
    background: #1b1b1e;
    border: 1px solid rgba(68, 71, 72, 0.4);
    border-radius: 6px;
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
}

/* ── Delivery status ────────────────────────────────────── */
.read-status {
  display: flex;
  align-items: center;
}

.check-icon {
  width: 14px;
  height: 14px;
  color: #6f7274;
}

.check-icon.read {
  color: #ffffff;
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
  color: #6f7274;
}

.sending-status .spin {
  animation: msg-status-spin 0.9s linear infinite;
}

@keyframes msg-status-spin {
  to { transform: rotate(360deg); }
}

/* ── Mobile ─────────────────────────────────────────────── */
@media (max-width: 768px) {
  .message-list {
    padding: 10px 12px;
  }

  .message-wrapper {
    max-width: 88%;
  }

  .message-avatar {
    width: 28px;
  }

  .message-avatar .el-avatar {
    width: 28px !important;
    height: 28px !important;
  }

  .message-bubble {
    padding: 7px 10px;
  }

  .message-text {
    font-size: 14px;
    line-height: 1.45;
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
    min-width: 170px;
  }
}

@media (max-width: 375px) {
  .message-wrapper {
    max-width: 90%;
  }

  .message-list {
    padding: 8px 10px;
  }
}
</style>

<style>
/* Emoji picker popover is teleported to body, so it can't be scoped. */
.emoji-popper.el-popper {
  padding: 0 !important;
  border: none !important;
  background: transparent !important;
  box-shadow: none !important;
  min-width: unset !important;
}

.emoji-popper .v3-emoji-picker {
  width: 100% !important;
  border-radius: 8px !important;
  box-shadow: 0 16px 32px -12px rgba(0, 0, 0, 0.7) !important;
}
</style>

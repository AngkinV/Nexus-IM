<template>
  <div class="input-container">
    <div v-if="replyTo" class="replying-bar">
      <div>
        <span class="replying-label">{{ $t('chat.replyingTo', { name: replyTo.senderName || $t('chat.unknownUser') }) }}</span>
        <span class="replying-text">{{ replyTo.content }}</span>
      </div>
      <button class="replying-close" @click="$emit('cancelReply')" :title="$t('chat.cancelReply')">×</button>
    </div>
    <div class="input-wrapper">
      <button class="input-btn attach-btn" @click="triggerUpload" :title="$t('chat.attachFile')" :aria-label="$t('chat.attachFile')">
        <el-icon :size="24"><Plus /></el-icon>
      </button>

      <div class="input-field">
        <el-input
          v-model="content"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 4 }"
          :placeholder="$t('chat.typeMessage')"
          resize="none"
          class="custom-textarea"
          @keydown.enter.prevent="handleEnter"
        />
      </div>

      <div class="input-actions">
        <el-popover
          placement="top-end"
          trigger="click"
          :width="320"
          :show-arrow="false"
          popper-class="emoji-popper"
        >
          <template #reference>
            <button class="input-btn emoji-btn" :title="$t('chat.emoji')" :aria-label="$t('chat.emoji')" @click="pickerTheme = currentTheme()">
              <el-icon :size="22"><Sunny /></el-icon>
            </button>
          </template>
          <EmojiPicker
            :native="true"
            :theme="pickerTheme"
            :disable-skin-tones="true"
            @select="onEmoji"
          />
        </el-popover>
        <button v-if="!content.trim()" class="input-btn mic-btn" :title="$t('chat.voiceMessage')" :aria-label="$t('chat.voiceMessage')" @click="showComingSoon">
          <el-icon :size="22"><Microphone /></el-icon>
        </button>
        <button
          v-else
          class="send-btn"
          @click="sendMessage"
          :title="$t('chat.sendMessage')"
          :aria-label="$t('chat.sendMessage')"
        >
          <el-icon :size="22"><Promotion /></el-icon>
        </button>
      </div>
    </div>

    <div class="input-hint">
      <span>{{ $t('chat.enterToSend') }} <strong>{{ $t('chat.enterKey') }}</strong> {{ $t('chat.toSend') }}</span>
    </div>

    <FileUpload
      ref="fileUploadRef"
      @complete="handleUploadComplete"
      @error="handleUploadError"
    />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { Plus, Sunny, Microphone, Promotion } from '@element-plus/icons-vue'
import FileUpload from '@/components/common/FileUpload.vue'
import EmojiPicker from 'vue3-emoji-picker'
import 'vue3-emoji-picker/css'

const { t } = useI18n()
const showComingSoon = () => ElMessage.info(t('common.comingSoon'))

const emit = defineEmits(['send', 'cancelReply'])
defineProps({
  replyTo: {
    type: Object,
    default: null
  }
})
const content = ref('')
const fileUploadRef = ref(null)

// Emoji picker theme follows the app's light/dark setting.
const pickerTheme = ref('light')
const currentTheme = () => {
  const attr = document.documentElement.getAttribute('data-theme') || document.body.getAttribute('data-theme')
  return attr === 'dark' ? 'dark' : 'light'
}
const onEmoji = (e) => {
  content.value += e.i
}

const handleEnter = (e) => {
  if (e.shiftKey) {
    // Allow new line
    return
  }
  sendMessage()
}

const sendMessage = () => {
  if (!content.value.trim()) return
  emit('send', content.value)
  content.value = ''
}

const triggerUpload = () => {
  fileUploadRef.value?.trigger()
}

const handleUploadComplete = (fileData) => {
  // Determine message type based on mime type
  let type = 'FILE'
  if (fileData.type?.startsWith('image/')) {
    type = 'IMAGE'
  } else if (fileData.type?.startsWith('video/')) {
    type = 'VIDEO'
  } else if (fileData.type?.startsWith('audio/')) {
    type = 'AUDIO'
  }

  // Emit with file data for the message
  emit('send', {
    content: fileData.name,  // Use filename as content
    type: type,
    fileUrl: fileData.downloadUrl,
    fileId: fileData.fileId,
    fileName: fileData.name,
    fileSize: fileData.size,
    mimeType: fileData.mimeType || fileData.type,
    previewUrl: fileData.previewUrl
  })
}

const handleUploadError = (error) => {
  console.error('Upload error:', error)
}
</script>

<style scoped>
.input-container {
  padding: 8px 0;
}

.replying-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0 8px 8px;
  padding: 8px 10px;
  border-left: 3px solid var(--tg-primary);
  background: rgba(6, 182, 212, 0.08);
  border-radius: 8px;
}

.replying-label,
.replying-text {
  display: block;
  max-width: 520px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.replying-label {
  font-size: 12px;
  font-weight: 700;
  color: var(--tg-primary);
}

.replying-text {
  font-size: 12px;
  color: var(--tg-text-secondary);
}

.replying-close {
  border: none;
  background: transparent;
  color: var(--tg-text-secondary);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}

.input-wrapper {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 8px 8px 12px;
  background: var(--tg-surface);
  border-radius: 9999px;
  box-shadow: var(--tg-shadow-lg);
  border: 1px solid rgba(226, 232, 240, 0.5);
  transition: var(--tg-transition-slow);
}

.input-wrapper:focus-within {
  box-shadow: var(--tg-shadow-lg), 0 0 0 4px rgba(6, 182, 212, 0.1);
  border-color: rgba(6, 182, 212, 0.3);
}

[data-theme="dark"] .input-wrapper {
  border: 1px solid rgba(51, 65, 85, 0.5);
}

.input-btn {
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  border-radius: 50%;
  color: var(--tg-text-secondary);
  cursor: pointer;
  transition: var(--tg-transition);
  flex-shrink: 0;
}

.attach-btn:hover {
  background: rgba(16, 185, 129, 0.1);
  color: var(--tg-secondary);
}

.emoji-btn:hover {
  background: rgba(251, 191, 36, 0.1);
  color: #F59E0B;
}

.mic-btn:hover {
  background: rgba(6, 182, 212, 0.1);
  color: var(--tg-primary);
}

.input-field {
  flex: 1;
  min-height: 44px;
  display: flex;
  align-items: center;
}

.custom-textarea :deep(.el-textarea__inner) {
  background: transparent;
  box-shadow: none;
  border: none;
  padding: 10px 0;
  max-height: 150px;
  font-size: 15px;
  font-weight: 500;
  color: var(--tg-text-primary);
  line-height: 1.5;
}

.custom-textarea :deep(.el-textarea__inner)::placeholder {
  color: var(--tg-text-tertiary);
}

.input-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.send-btn {
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: rgba(6, 182, 212, 0.1);
  border-radius: 50%;
  color: #0891b2;
  cursor: pointer;
  transition: var(--tg-transition);
  flex-shrink: 0;
}

.send-btn:hover {
  background: rgba(6, 182, 212, 0.2);
  transform: scale(1.05);
}

.send-btn:active {
  transform: scale(0.95);
}

.send-btn .el-icon {
  margin-left: 2px;
}

.input-hint {
  text-align: center;
  margin-top: 12px;
}

.input-hint span {
  font-size: 11px;
  color: var(--tg-text-tertiary);
  font-weight: 500;
}

.input-hint strong {
  color: var(--tg-text-secondary);
  font-weight: 700;
}

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .input-container {
    padding: 6px 0;
  }

  .input-wrapper {
    gap: 4px;
    padding: 6px 6px 6px 10px;
  }

  .input-btn {
    width: 40px;
    height: 40px;
  }

  .input-field {
    min-height: 40px;
  }

  .custom-textarea :deep(.el-textarea__inner) {
    padding: 8px 0;
    font-size: 16px; /* Prevent iOS zoom */
  }

  .send-btn {
    width: 44px;
    height: 44px;
  }

  .input-hint {
    margin-top: 8px;
    display: none; /* Hide on mobile */
  }

  .input-actions {
    gap: 2px;
  }
}

/* Small phones */
@media (max-width: 375px) {
  .input-wrapper {
    padding: 4px 4px 4px 8px;
  }

  .input-btn {
    width: 36px;
    height: 36px;
  }

  .send-btn {
    width: 40px;
    height: 40px;
  }
}
</style>

<style>
/* Emoji picker popover (teleported to body, so it can't be scoped) */
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

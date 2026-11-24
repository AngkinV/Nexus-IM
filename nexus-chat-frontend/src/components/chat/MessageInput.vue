<template>
  <div class="input-container">
    <div v-if="replyTo" class="replying-bar">
      <div class="replying-body">
        <span class="replying-label">{{ $t('chat.replyingTo', { name: replyTo.senderName || $t('chat.unknownUser') }) }}</span>
        <span class="replying-text">{{ replyTo.content }}</span>
      </div>
      <button class="replying-close" @click="$emit('cancelReply')" :title="$t('chat.cancelReply')">
        <span class="material-symbols-outlined">close</span>
      </button>
    </div>

    <!-- One capsule: attach on the left, the text, then the quiet extras and send. -->
    <div class="composer">
      <button class="tool-btn tool-add" type="button" @click="triggerUpload" :title="$t('chat.attachFile')" :aria-label="$t('chat.attachFile')">
        <span class="material-symbols-outlined">add</span>
      </button>

      <div class="input-field">
        <el-input
          v-model="content"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 5 }"
          :placeholder="$t('chat.composerPlaceholder')"
          resize="none"
          class="custom-textarea"
          @keydown.enter.prevent="handleEnter"
        />
      </div>

      <div class="composer-tools">
        <el-popover
          placement="top-end"
          trigger="click"
          :width="320"
          :show-arrow="false"
          popper-class="emoji-popper"
        >
          <template #reference>
            <button class="tool-btn" type="button" :title="$t('chat.emoji')" :aria-label="$t('chat.emoji')" @click="pickerTheme = currentTheme()">
              <span class="material-symbols-outlined">sentiment_satisfied</span>
            </button>
          </template>
          <EmojiPicker
            :native="true"
            :theme="pickerTheme"
            :disable-skin-tones="true"
            @select="onEmoji"
          />
        </el-popover>
        <button class="tool-btn" type="button" @click="triggerUpload" :title="$t('chat.image')" :aria-label="$t('chat.image')">
          <span class="material-symbols-outlined">image</span>
        </button>
        <button class="tool-btn" type="button" :title="$t('chat.voiceMessage')" :aria-label="$t('chat.voiceMessage')" @click="showComingSoon">
          <span class="material-symbols-outlined">mic</span>
        </button>
      </div>

      <button
        class="send-btn"
        type="button"
        @click="sendMessage"
        :title="$t('chat.sendMessage')"
        :aria-label="$t('chat.sendMessage')"
      >
        <span class="material-symbols-outlined">arrow_upward</span>
      </button>
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

// Prism is dark-only, so the emoji picker always renders in dark mode.
const pickerTheme = ref('dark')
const currentTheme = () => 'dark'
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
  width: 100%;
}

/* ── Reply banner ───────────────────────────────────────── */
.replying-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  margin-bottom: 8px;
  background: #16161a;
  border-left: 2px solid #ffffff;
  border-radius: 3px;
}

.replying-body {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
}

.replying-label {
  font-size: 11px;
  font-weight: 600;
  color: #ffffff;
}

.replying-text {
  font-size: 11px;
  color: #8e9192;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.replying-close {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #6f7274;
  cursor: pointer;
  transition: all 0.15s ease;
}

.replying-close .material-symbols-outlined {
  font-size: 15px;
}

.replying-close:hover {
  background: #2a2a2d;
  color: #ffffff;
}

/* ── Composer ───────────────────────────────────────────── */
.composer {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 6px;
  background: var(--field-bg);
  border-radius: 22px;
  box-shadow: 0 0 0 1px var(--field-ring) inset;
  transition: box-shadow var(--field-ease);
}

.composer:hover {
  box-shadow: 0 0 0 1px var(--field-ring-hover) inset;
}

.composer:focus-within {
  box-shadow: 0 0 0 1px var(--field-ring-focus) inset;
}

.composer-tools {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.tool-btn,
.send-btn {
  flex-shrink: 0;
  width: var(--field-btn);
  height: var(--field-btn);
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  transition: background-color var(--field-ease), color var(--field-ease), transform 0.1s ease;
}

.tool-btn {
  background: transparent;
  color: var(--field-icon);
}

.tool-btn .material-symbols-outlined,
.send-btn .material-symbols-outlined {
  font-size: 18px;
}

.tool-btn:hover {
  background: var(--field-btn-bg);
  color: #ffffff;
}

.tool-add {
  background: var(--field-btn-bg);
  color: var(--field-btn-ink);
}

.tool-add:hover {
  background: var(--field-btn-bg-hover);
}

.tool-btn:active,
.send-btn:active {
  transform: scale(0.94);
}

.tool-btn:focus-visible,
.send-btn:focus-visible {
  outline: 2px solid #e4e1e6;
  outline-offset: 2px;
}

.input-field {
  flex: 1;
  min-width: 0;
}

/* The capsule owns the outline, so the textarea inside it is bare. */
.custom-textarea :deep(.el-textarea__inner) {
  min-height: 32px !important;
  max-height: 132px;
  padding: 6px 2px;
  border: none;
  border-radius: 0;
  box-shadow: none;
  background: transparent;
  color: var(--field-text);
  font-family: inherit;
  font-size: 13.5px;
  line-height: 20px;
  resize: none;
}

.custom-textarea :deep(.el-textarea__inner):hover,
.custom-textarea :deep(.el-textarea__inner):focus {
  box-shadow: none;
}

.custom-textarea :deep(.el-textarea__inner)::placeholder {
  color: var(--field-placeholder);
}

.send-btn {
  background: #ffffff;
  color: #2f3131;
}

.send-btn:hover {
  background: #e2e2e2;
}

@media (pointer: coarse) {
  .composer {
    border-radius: 28px;
  }

  .tool-btn,
  .send-btn {
    width: 44px;
    height: 44px;
  }

  .custom-textarea :deep(.el-textarea__inner) {
    min-height: 44px !important;
    padding: 10px 2px;
    font-size: 16px;
    line-height: 24px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .composer,
  .tool-btn,
  .send-btn {
    transition: none;
  }
}
</style>

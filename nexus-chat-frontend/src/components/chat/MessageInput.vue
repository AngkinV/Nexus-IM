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

    <div class="composer">
      <!-- Toolbar -->
      <div class="composer-toolbar">
        <div class="toolbar-left">
          <el-popover
            placement="top-start"
            trigger="click"
            :width="320"
            :show-arrow="false"
            popper-class="emoji-popper"
          >
            <template #reference>
              <button class="tool-btn" :title="$t('chat.emoji')" :aria-label="$t('chat.emoji')" @click="pickerTheme = currentTheme()">
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
          <button class="tool-btn" @click="triggerUpload" :title="$t('chat.attachFile')" :aria-label="$t('chat.attachFile')">
            <span class="material-symbols-outlined">attach_file</span>
          </button>

          <button class="tool-btn" @click="triggerUpload" :title="$t('chat.image')" :aria-label="$t('chat.image')">
            <span class="material-symbols-outlined">image</span>
          </button>
          <button class="tool-btn" :title="$t('chat.voiceMessage')" :aria-label="$t('chat.voiceMessage')" @click="showComingSoon">
            <span class="material-symbols-outlined">mic</span>
          </button>
        </div>
      </div>

      <!-- Input + send -->
      <div class="composer-row">
        <div class="input-field">
          <el-input
            v-model="content"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            :placeholder="$t('chat.composerPlaceholder')"
            resize="none"
            class="custom-textarea"
            @keydown.enter.prevent="handleEnter"
          />
        </div>

        <button
          class="send-btn"
          type="button"
          @click="sendMessage"
          :title="$t('chat.sendMessage')"
          :aria-label="$t('chat.sendMessage')"
        >
          <span class="send-label">{{ $t('chat.send') }}</span>
          <span class="material-symbols-outlined">send</span>
        </button>
      </div>
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

/* ── Composer shell ─────────────────────────────────────── */
/* Composer card — mirrors the exported screen:
   rounded-xl bg-surface-container-low border-outline-variant/30 p-space-sm gap-space-xs */
.composer {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.3);
  border-radius: 12px;
  transition: all 0.15s cubic-bezier(0.4, 0, 0.2, 1);
}

.composer:focus-within {
  border-color: rgba(68, 71, 72, 0.6);
  background: #1f1f22;
}

.composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 4px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 2px;
}

.tool-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  border-radius: 4px;
  color: #c4c7c8;
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease, transform 0.1s ease;
}

.tool-btn .material-symbols-outlined {
  font-size: 18px;
}

.tool-btn:hover {
  background: #2a2a2d;
  color: #ffffff;
}

.tool-btn:active {
  transform: scale(0.95);
}

.composer-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 0 4px;
}

.input-field {
  flex: 1;
  min-width: 0;
}

.custom-textarea :deep(.el-textarea__inner) {
  min-height: 44px !important;
  max-height: 112px;
  padding: 0;
  border: none;
  border-radius: 0;
  box-shadow: none;
  background: transparent;
  color: #ffffff;
  font-family: inherit;
  font-size: 15px;
  line-height: 1.625;
  letter-spacing: -0.005em;
  resize: none;
}

.custom-textarea :deep(.el-textarea__inner):focus {
  box-shadow: none;
}

.custom-textarea :deep(.el-textarea__inner)::placeholder {
  color: rgba(196, 199, 200, 0.4);
}

.send-btn {
  flex-shrink: 0;
  height: 36px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  gap: 6px;
  border: none;
  border-radius: 8px;
  background: #ffffff;
  color: #2f3131;
  font-size: 13px;
  line-height: 18px;
  letter-spacing: 0.01em;
  font-weight: 500;
  cursor: pointer;
  box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
  transition: all 0.15s cubic-bezier(0.4, 0, 0.2, 1);
}

.send-btn .material-symbols-outlined {
  font-size: 16px;
}

.send-btn:hover {
  background: #e2e2e2;
}

.send-btn:active {
  transform: scale(0.95);
}

@media (max-width: 768px) {
  .composer {
    padding: 6px;
  }

  .send-label {
    display: none;
  }

  .send-btn {
    width: 32px;
    padding: 0;
    justify-content: center;
  }
}
</style>

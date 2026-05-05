<template>
  <div class="input-container">
    <div class="input-wrapper">
      <button class="input-btn attach-btn" @click="triggerUpload" title="Attach file">
        <el-icon :size="24"><Plus /></el-icon>
      </button>

      <div class="input-field">
        <el-input
          v-model="content"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 4 }"
          placeholder="Type a message..."
          resize="none"
          class="custom-textarea"
          @keydown.enter.prevent="handleEnter"
        />
      </div>

      <div class="input-actions">
        <button class="input-btn emoji-btn" title="Emoji">
          <el-icon :size="22"><Sunny /></el-icon>
        </button>
        <button v-if="!content.trim()" class="input-btn mic-btn" title="Voice message">
          <el-icon :size="22"><Microphone /></el-icon>
        </button>
        <button
          v-else
          class="send-btn"
          @click="sendMessage"
          title="Send message"
        >
          <el-icon :size="22"><Promotion /></el-icon>
        </button>
      </div>
    </div>

    <div class="input-hint">
      <span>Press <strong>Enter</strong> to send</span>
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
import { Plus, Sunny, Microphone, Promotion } from '@element-plus/icons-vue'
import FileUpload from '@/components/common/FileUpload.vue'

const emit = defineEmits(['send'])
const content = ref('')
const fileUploadRef = ref(null)

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

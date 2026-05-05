<template>
  <div class="file-upload-container">
    <input
      type="file"
      ref="fileInput"
      class="hidden-input"
      :accept="accept"
      @change="handleFileSelect"
    />

    <el-dialog
      v-model="uploading"
      :title="$t('file.uploading')"
      width="360px"
      :close-on-click-modal="false"
      :show-close="canCancel"
      @close="handleCancel"
    >
      <div class="upload-progress">
        <div class="file-preview" v-if="previewUrl">
          <img v-if="isImage" :src="previewUrl" class="preview-image" />
          <div v-else class="file-icon">
            <el-icon :size="48"><Document /></el-icon>
          </div>
        </div>
        <div class="file-info">
          <span class="filename" :title="currentFile?.name">{{ currentFile?.name }}</span>
          <span class="file-size">{{ formatFileSize(currentFile?.size) }}</span>
        </div>
        <div class="progress-row">
          <el-progress
            :percentage="progress"
            :status="status"
            :stroke-width="8"
          />
          <span class="percentage">{{ progress }}%</span>
        </div>
        <div v-if="uploadSpeed" class="upload-speed">
          {{ uploadSpeed }}
        </div>
      </div>
      <template #footer v-if="canCancel">
        <el-button @click="handleCancel">{{ $t('common.cancel') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { Document } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { fileAPI } from '@/services/api'
import { useUserStore } from '@/stores/user'

const generateUUID = () => {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0
    const v = c === 'x' ? r : (r & 0x3 | 0x8)
    return v.toString(16)
  })
}

const emit = defineEmits(['complete', 'error'])
const props = defineProps({
  accept: {
    type: String,
    default: '*/*'  // All files by default
  },
  maxSize: {
    type: Number,
    default: 100 * 1024 * 1024  // 100MB
  }
})

const userStore = useUserStore()
const fileInput = ref(null)
const uploading = ref(false)
const progress = ref(0)
const currentFile = ref(null)
const status = ref('')
const previewUrl = ref(null)
const uploadSpeed = ref('')
const canCancel = ref(true)
const abortController = ref(null)

const CHUNK_SIZE = 5 * 1024 * 1024  // 5MB chunks
const DIRECT_UPLOAD_LIMIT = 5 * 1024 * 1024  // Files under 5MB use direct upload

const isImage = computed(() => {
  return currentFile.value?.type?.startsWith('image/')
})

const trigger = () => {
  fileInput.value.click()
}

const formatFileSize = (bytes) => {
  if (!bytes) return ''
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

const handleFileSelect = async (event) => {
  const file = event.target.files[0]
  if (!file) return

  // Validate file size
  if (file.size > props.maxSize) {
    ElMessage.error(`File too large. Maximum size is ${formatFileSize(props.maxSize)}`)
    fileInput.value.value = ''
    return
  }

  currentFile.value = file
  uploading.value = true
  progress.value = 0
  status.value = ''
  canCancel.value = true
  uploadSpeed.value = ''

  // Generate preview for images
  if (file.type.startsWith('image/')) {
    previewUrl.value = URL.createObjectURL(file)
  } else {
    previewUrl.value = null
  }

  const startTime = Date.now()
  let lastLoaded = 0
  let lastTime = startTime

  try {
    let result

    if (file.size <= DIRECT_UPLOAD_LIMIT) {
      // Direct upload for small files
      result = await fileAPI.uploadFile(
        file,
        userStore.currentUser?.id,
        (percent) => {
          progress.value = percent
          // Calculate speed
          const now = Date.now()
          const elapsed = (now - lastTime) / 1000
          if (elapsed > 0.5) {
            const loaded = (percent / 100) * file.size
            const speed = (loaded - lastLoaded) / elapsed
            uploadSpeed.value = `${formatFileSize(speed)}/s`
            lastLoaded = loaded
            lastTime = now
          }
        }
      )
    } else {
      // Chunked upload for large files
      result = await uploadInChunks(file, (percent) => {
        progress.value = percent
        const now = Date.now()
        const elapsed = (now - lastTime) / 1000
        if (elapsed > 0.5) {
          const loaded = (percent / 100) * file.size
          const speed = (loaded - lastLoaded) / elapsed
          uploadSpeed.value = `${formatFileSize(speed)}/s`
          lastLoaded = loaded
          lastTime = now
        }
      })
    }

    status.value = 'success'
    canCancel.value = false

    // Emit complete with file data
    setTimeout(() => {
      uploading.value = false
      if (previewUrl.value) {
        URL.revokeObjectURL(previewUrl.value)
      }
      emit('complete', {
        fileId: result.data.fileId,
        name: result.data.originalName || file.name,
        size: result.data.size || file.size,
        type: file.type,
        mimeType: result.data.mimeType,
        url: result.data.fileUrl,
        downloadUrl: result.data.downloadUrl,
        previewUrl: result.data.previewUrl
      })
    }, 800)

  } catch (error) {
    console.error('Upload failed:', error)
    status.value = 'exception'

    if (error.name === 'AbortError' || error.message === 'cancelled') {
      ElMessage.info('Upload cancelled')
    } else {
      ElMessage.error('Upload failed: ' + (error.response?.data?.error || error.message))
      emit('error', error)
    }

    setTimeout(() => {
      uploading.value = false
      if (previewUrl.value) {
        URL.revokeObjectURL(previewUrl.value)
      }
    }, 1500)
  } finally {
    fileInput.value.value = ''
    abortController.value = null
  }
}

const uploadInChunks = async (file, onProgress) => {
  const totalChunks = Math.ceil(file.size / CHUNK_SIZE)
  const fileId = generateUUID()
  let result = null

  for (let i = 0; i < totalChunks; i++) {
    const start = i * CHUNK_SIZE
    const end = Math.min(file.size, start + CHUNK_SIZE)
    const chunk = file.slice(start, end)

    result = await fileAPI.uploadChunk(
      chunk,
      i,
      totalChunks,
      fileId,
      file.name,
      file.size,
      userStore.currentUser?.id
    )

    const percent = Math.round(((i + 1) / totalChunks) * 100)
    onProgress(percent)
  }

  return result
}

const handleCancel = () => {
  if (abortController.value) {
    abortController.value.abort()
  }
  uploading.value = false
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
  }
}

defineExpose({
  trigger
})
</script>

<style scoped>
.hidden-input {
  display: none;
}

.upload-progress {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.file-preview {
  display: flex;
  justify-content: center;
  margin-bottom: 8px;
}

.preview-image {
  max-width: 200px;
  max-height: 150px;
  border-radius: 8px;
  object-fit: cover;
}

.file-icon {
  width: 80px;
  height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light);
  border-radius: 12px;
  color: var(--el-text-color-secondary);
}

.file-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.filename {
  font-size: 14px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-size {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.progress-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.progress-row .el-progress {
  flex: 1;
}

.percentage {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-color-primary);
  min-width: 45px;
  text-align: right;
}

.upload-speed {
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>

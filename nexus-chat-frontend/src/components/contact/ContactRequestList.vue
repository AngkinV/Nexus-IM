<template>
  <el-dialog
    v-model="dialogVisible"
    :title="$t('contact.friendRequests')"
    width="420px"
    class="friend-requests-dialog"
    :before-close="handleClose"
  >
    <div class="requests-container">
      <!-- Tabs for received and sent requests -->
      <el-tabs v-model="activeTab" class="requests-tabs">
        <el-tab-pane :label="receivedTabLabel" name="received">
          <div v-if="contactStore.pendingRequests.length === 0" class="empty-state">
            <el-icon class="empty-icon"><Bell /></el-icon>
            <span>{{ $t('contact.noRequests') }}</span>
          </div>
          <el-scrollbar v-else max-height="400px">
            <div
              v-for="request in contactStore.pendingRequests"
              :key="request.id"
              class="request-card"
            >
              <div class="request-left">
                <el-avatar :size="50" :src="request.fromAvatarUrl || defaultAvatar">
                  <span v-if="request.fromIsOnline" class="online-dot"></span>
                </el-avatar>
                <div class="request-info">
                  <span class="request-nickname">{{ request.fromNickname }}</span>
                  <span class="request-username">@{{ request.fromUsername }}</span>
                  <span v-if="request.message" class="request-message">
                    "{{ request.message }}"
                  </span>
                  <span class="request-time">{{ formatTime(request.createdAt) }}</span>
                </div>
              </div>
              <div class="request-actions">
                <el-button
                  type="primary"
                  size="small"
                  @click="handleAccept(request)"
                  :loading="processingId === request.id"
                >
                  <el-icon><Check /></el-icon>
                  {{ $t('contact.accept') }}
                </el-button>
                <el-button
                  type="danger"
                  size="small"
                  plain
                  @click="handleReject(request)"
                  :loading="processingId === request.id"
                >
                  <el-icon><Close /></el-icon>
                  {{ $t('contact.reject') }}
                </el-button>
              </div>
            </div>
          </el-scrollbar>
        </el-tab-pane>

        <el-tab-pane :label="sentTabLabel" name="sent">
          <div v-if="contactStore.sentRequests.length === 0" class="empty-state">
            <el-icon class="empty-icon"><Message /></el-icon>
            <span>{{ $t('contact.noSentRequests') }}</span>
          </div>
          <el-scrollbar v-else max-height="400px">
            <div
              v-for="request in contactStore.sentRequests"
              :key="request.id"
              class="request-card sent"
            >
              <div class="request-left">
                <el-avatar :size="50" :src="request.toAvatarUrl || defaultAvatar" />
                <div class="request-info">
                  <span class="request-nickname">{{ request.toNickname }}</span>
                  <span class="request-username">@{{ request.toUsername }}</span>
                  <span v-if="request.message" class="request-message">
                    "{{ request.message }}"
                  </span>
                  <span class="request-time">{{ formatTime(request.createdAt) }}</span>
                </div>
              </div>
              <div class="request-status">
                <el-tag type="warning" size="small">
                  {{ $t('contact.pending') }}
                </el-tag>
              </div>
            </div>
          </el-scrollbar>
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { Bell, Check, Close, Message } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useContactStore } from '@/stores/contact'
import { useUserStore } from '@/stores/user'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps({
  visible: Boolean
})

const emit = defineEmits(['update:visible'])

const contactStore = useContactStore()
const userStore = useUserStore()

const dialogVisible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const activeTab = ref('received')
const processingId = ref(null)
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'

const currentUserId = computed(() => userStore.currentUser?.id)

const receivedTabLabel = computed(() => {
  const count = contactStore.pendingRequestCount
  return count > 0
    ? `${t('contact.received')} (${count})`
    : t('contact.received')
})

const sentTabLabel = computed(() => {
  const count = contactStore.sentRequests.length
  return count > 0
    ? `${t('contact.sent')} (${count})`
    : t('contact.sent')
})

// Load requests when dialog opens
watch(() => props.visible, async (val) => {
  if (val && currentUserId.value) {
    await Promise.all([
      contactStore.fetchPendingRequests(currentUserId.value),
      contactStore.fetchSentRequests(currentUserId.value)
    ])
  }
})

const handleClose = () => {
  dialogVisible.value = false
}

const handleAccept = async (request) => {
  if (!currentUserId.value) return

  processingId.value = request.id
  try {
    await contactStore.acceptRequest(request.id, currentUserId.value)
    ElMessage.success(t('contact.acceptSuccess', { name: request.fromNickname }))
  } catch (error) {
    ElMessage.error(t('contact.acceptFailed'))
  } finally {
    processingId.value = null
  }
}

const handleReject = async (request) => {
  if (!currentUserId.value) return

  processingId.value = request.id
  try {
    await contactStore.rejectRequest(request.id, currentUserId.value)
    ElMessage.success(t('contact.rejectSuccess'))
  } catch (error) {
    ElMessage.error(t('contact.rejectFailed'))
  } finally {
    processingId.value = null
  }
}

const formatTime = (dateStr) => {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const now = new Date()
  const diff = now - date

  // Less than 1 minute
  if (diff < 60000) {
    return t('time.justNow')
  }
  // Less than 1 hour
  if (diff < 3600000) {
    const minutes = Math.floor(diff / 60000)
    return t('time.minutesAgo', { n: minutes })
  }
  // Less than 1 day
  if (diff < 86400000) {
    const hours = Math.floor(diff / 3600000)
    return t('time.hoursAgo', { n: hours })
  }
  // Less than 7 days
  if (diff < 604800000) {
    const days = Math.floor(diff / 86400000)
    return t('time.daysAgo', { n: days })
  }
  // Otherwise show date
  return date.toLocaleDateString()
}
</script>

<style scoped>
.friend-requests-dialog :deep(.el-dialog__body) {
  padding: 0 20px 20px;
}

.requests-container {
  min-height: 200px;
}

.requests-tabs :deep(.el-tabs__header) {
  margin-bottom: 15px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: #999;
  gap: 15px;
}

.empty-icon {
  font-size: 48px;
  color: #ddd;
}

.request-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 15px;
  background: #f8f9fa;
  border-radius: 12px;
  margin-bottom: 10px;
  transition: all 0.2s;
}

.request-card:hover {
  background: #f0f2f5;
  transform: translateY(-1px);
}

.request-card.sent {
  background: #fff8e6;
}

.request-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
}

.request-left :deep(.el-avatar) {
  position: relative;
}

.online-dot {
  position: absolute;
  bottom: 2px;
  right: 2px;
  width: 10px;
  height: 10px;
  background: #52c41a;
  border: 2px solid white;
  border-radius: 50%;
}

.request-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.request-nickname {
  font-weight: 600;
  font-size: 15px;
  color: #1c1c1e;
}

.request-username {
  font-size: 13px;
  color: #3390ec;
}

.request-message {
  font-size: 12px;
  color: #666;
  font-style: italic;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.request-time {
  font-size: 11px;
  color: #999;
}

.request-actions {
  display: flex;
  gap: 8px;
}

.request-status {
  display: flex;
  align-items: center;
}

/* Dark mode support */
@media (prefers-color-scheme: dark) {
  .request-card {
    background: #2a2a2a;
  }

  .request-card:hover {
    background: #333;
  }

  .request-nickname {
    color: #fff;
  }
}
</style>

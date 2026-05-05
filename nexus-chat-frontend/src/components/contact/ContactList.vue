<template>
  <div class="contact-list">
    <!-- Pending Friend Requests -->
    <div v-if="contactStore.pendingRequests.length > 0" class="request-section">
      <div class="section-header request-header">
        <el-icon><Bell /></el-icon>
        {{ $t('contact.friendRequests') }} ({{ contactStore.pendingRequests.length }})
      </div>
      <div
        v-for="request in contactStore.pendingRequests"
        :key="request.id"
        class="request-item"
      >
        <el-avatar :size="45" :src="request.fromAvatarUrl || defaultAvatar" />
        <div class="request-info">
          <span class="request-name">{{ request.fromNickname }}</span>
          <span class="request-username">@{{ request.fromUsername }}</span>
          <span v-if="request.message" class="request-message">{{ request.message }}</span>
        </div>
        <div class="request-actions">
          <el-button type="primary" size="small" @click="acceptRequest(request)">
            <el-icon><Check /></el-icon>
          </el-button>
          <el-button type="danger" size="small" @click="rejectRequest(request)">
            <el-icon><Close /></el-icon>
          </el-button>
        </div>
      </div>
    </div>

    <div v-if="filteredContacts.length === 0 && contactStore.pendingRequests.length === 0" class="empty-state">
      <div class="empty-icon">
        <el-icon :size="48"><User /></el-icon>
      </div>
      <p class="empty-text">{{ $t('contact.noContacts') }}</p>
    </div>

    <template v-else>
      <!-- Online Contacts -->
      <div v-if="onlineContacts.length > 0" class="contact-section">
        <div class="section-header">
          <span class="online-dot"></span>
          {{ $t('chat.online') }} ({{ onlineContacts.length }})
        </div>
        <div
          v-for="contact in onlineContacts"
          :key="contact.id"
          class="contact-item"
          @click="selectContact(contact)"
        >
          <div class="contact-avatar">
            <el-avatar :size="45" :src="contact.avatar || defaultAvatar" />
            <span class="online-indicator"></span>
          </div>
          <div class="contact-info">
            <span class="contact-name">{{ contact.nickname }}</span>
            <span class="contact-username">@{{ contact.username }}</span>
          </div>
          <div class="contact-actions">
            <el-button circle text @click.stop="startChat(contact)">
              <el-icon><ChatDotRound /></el-icon>
            </el-button>
            <el-button circle text type="danger" @click.stop="deleteContact(contact)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </div>
      </div>

      <!-- Offline Contacts -->
      <div v-if="offlineContacts.length > 0" class="contact-section">
        <div class="section-header">
          {{ $t('chat.offline') }} ({{ offlineContacts.length }})
        </div>
        <div
          v-for="contact in offlineContacts"
          :key="contact.id"
          class="contact-item offline"
          @click="selectContact(contact)"
        >
          <el-avatar :size="45" :src="contact.avatar || defaultAvatar" />
          <div class="contact-info">
            <span class="contact-name">{{ contact.nickname }}</span>
            <span class="contact-status">{{ formatLastSeen(contact.lastSeen) }}</span>
          </div>
          <div class="contact-actions">
            <el-button circle text @click.stop="startChat(contact)">
              <el-icon><ChatDotRound /></el-icon>
            </el-button>
            <el-button circle text type="danger" @click.stop="deleteContact(contact)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { User, ChatDotRound, Delete, Bell, Check, Close } from '@element-plus/icons-vue'
import { useContactStore } from '@/stores/contact'
import { useUserStore } from '@/stores/user'
import { useI18n } from 'vue-i18n'
import { ElMessageBox, ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'

dayjs.extend(relativeTime)

const { t } = useI18n()

const props = defineProps({
  searchQuery: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['select'])

const contactStore = useContactStore()
const userStore = useUserStore()
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'

const filteredContacts = computed(() => {
  if (!props.searchQuery) {
    return contactStore.contacts
  }
  const query = props.searchQuery.toLowerCase()
  return contactStore.contacts.filter(contact =>
    contact.nickname.toLowerCase().includes(query) ||
    contact.username.toLowerCase().includes(query)
  )
})

const onlineContacts = computed(() => {
  return filteredContacts.value.filter(c => c.isOnline)
})

const offlineContacts = computed(() => {
  return filteredContacts.value.filter(c => !c.isOnline)
})

const selectContact = (contact) => {
  emit('select', contact)
}

const startChat = (contact) => {
  emit('select', contact)
}

const formatLastSeen = (lastSeen) => {
  if (!lastSeen) return ''
  return `${t('chat.lastSeen')} ${dayjs(lastSeen).fromNow()}`
}

// Accept friend request
const acceptRequest = async (request) => {
  try {
    await contactStore.acceptRequest(request.id, userStore.currentUser?.id)
    ElMessage.success(t('contact.requestAccepted'))
  } catch (error) {
    console.error('Failed to accept request:', error)
    ElMessage.error(t('contact.acceptFailed'))
  }
}

// Reject friend request
const rejectRequest = async (request) => {
  try {
    await ElMessageBox.confirm(
      t('contact.confirmReject', { name: request.fromNickname }),
      t('contact.rejectRequest'),
      {
        confirmButtonText: t('common.ok'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )
    await contactStore.rejectRequest(request.id, userStore.currentUser?.id)
    ElMessage.success(t('contact.requestRejected'))
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to reject request:', error)
      ElMessage.error(t('contact.rejectFailed'))
    }
  }
}

// Delete contact with confirmation
const deleteContact = async (contact) => {
  try {
    await ElMessageBox.confirm(
      t('contact.confirmRemove', { name: contact.nickname }),
      t('contact.removeContact'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )

    // Call store method to remove contact
    await contactStore.removeContactRequest(userStore.currentUser?.id, contact.id)
    ElMessage.success(t('contact.removeSuccess'))
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Failed to remove contact:', error)
      ElMessage.error(t('contact.removeFailed'))
    }
  }
}
</script>

<style scoped>
.contact-list {
  padding: 10px 0;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 50px 20px;
  gap: 15px;
}

.empty-icon {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: #f5f5f5;
  display: flex;
  justify-content: center;
  align-items: center;
  color: #ccc;
}

.empty-text {
  color: #999;
  font-size: 14px;
}

.contact-section {
  margin-bottom: 10px;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 15px;
  font-size: 12px;
  font-weight: 500;
  color: #999;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.online-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #4caf50;
}

.contact-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 15px;
  cursor: pointer;
  transition: background 0.2s;
}

.contact-item:hover {
  background: #f5f5f5;
}

.contact-avatar {
  position: relative;
}

.online-indicator {
  position: absolute;
  bottom: 2px;
  right: 2px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #4caf50;
  border: 2px solid white;
}

.contact-info {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.contact-name {
  font-weight: 500;
  font-size: 15px;
  color: #333;
}

.contact-username {
  font-size: 13px;
  color: #3390ec;
}

.contact-status {
  font-size: 12px;
  color: #999;
}

.contact-item.offline .contact-name {
  color: #666;
}

.contact-actions {
  opacity: 0;
  transition: opacity 0.2s;
}

.contact-item:hover .contact-actions {
  opacity: 1;
}

/* Friend Request Styles */
.request-section {
  margin-bottom: 15px;
  background: #fff8e1;
  border-radius: 8px;
  margin: 10px 15px;
  overflow: hidden;
}

.request-header {
  background: #ffecb3;
  color: #f57c00;
  padding: 10px 15px;
}

.request-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 15px;
  border-top: 1px solid #ffe082;
}

.request-info {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.request-name {
  font-weight: 500;
  font-size: 15px;
  color: #333;
}

.request-username {
  font-size: 13px;
  color: #3390ec;
}

.request-message {
  font-size: 12px;
  color: #666;
  font-style: italic;
  margin-top: 2px;
}

.request-actions {
  display: flex;
  gap: 8px;
}
</style>

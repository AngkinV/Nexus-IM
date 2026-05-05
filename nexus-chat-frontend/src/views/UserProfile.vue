<template>
  <div class="user-profile-page custom-scrollbar">
    <!-- Drag region for window dragging -->
    <div class="drag-region"></div>

    <div class="profile-container animate-fade-in" v-loading="loading">
      <!-- Back button -->
      <el-button class="back-btn glass-btn" circle @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
      </el-button>

      <!-- Cover image -->
      <div class="cover-section" :style="coverStyle" :class="{ 'default-gradient': !userProfile?.profileBackground }">
        <div class="cover-overlay"></div>
      </div>

      <!-- User card -->
      <div class="user-card glass-panel">
        <div class="avatar-section">
          <el-avatar :size="120" :src="userProfile?.avatarUrl || defaultAvatar" class="avatar-img" />
          <div class="status-dot" :class="{ online: isOnline }" v-if="showOnlineStatus"></div>
        </div>

        <div class="user-info">
          <h1 class="nickname">{{ userProfile?.nickname || $t('common.loading') }}</h1>
          <p class="username">@{{ userProfile?.username }}</p>
          <p class="bio" v-if="userProfile?.bio">{{ userProfile.bio }}</p>

          <!-- Online status text -->
          <div class="status-text" v-if="showOnlineStatus">
            <span v-if="isOnline" class="online-text">{{ $t('chat.online') }}</span>
            <span v-else-if="userProfile?.lastSeen" class="offline-text">
              {{ $t('profile.lastSeen') }}: {{ formatLastSeen(userProfile.lastSeen) }}
            </span>
          </div>
        </div>

        <!-- Action buttons -->
        <div class="action-buttons">
          <!-- Already a contact: Send message -->
          <el-button v-if="isContact" type="primary" class="action-btn" @click="startChat">
            <el-icon><ChatDotRound /></el-icon>
            {{ $t('chat.sendMessage') }}
          </el-button>

          <!-- Not a contact: Add friend -->
          <el-button v-else type="primary" class="action-btn" @click="addContact" :loading="addingContact">
            <el-icon><Plus /></el-icon>
            {{ $t('contact.addContact') }}
          </el-button>
        </div>
      </div>

      <!-- Details section -->
      <div class="details-section glass-panel">
        <h3 class="section-title">{{ $t('profile.about') }}</h3>

        <div class="detail-grid">
          <!-- Email (based on privacy settings) -->
          <div class="detail-item" v-if="userProfile?.showEmail && userProfile?.email">
            <el-icon><Message /></el-icon>
            <div class="detail-content">
              <span class="label">{{ $t('auth.email') }}</span>
              <span class="value">{{ userProfile.email }}</span>
            </div>
          </div>

          <!-- Phone (based on privacy settings) -->
          <div class="detail-item" v-if="userProfile?.showPhone && userProfile?.phone">
            <el-icon><Phone /></el-icon>
            <div class="detail-content">
              <span class="label">{{ $t('auth.phone') }}</span>
              <span class="value">{{ userProfile.phone }}</span>
            </div>
          </div>

          <!-- Join date -->
          <div class="detail-item" v-if="userProfile?.createdAt">
            <el-icon><Calendar /></el-icon>
            <div class="detail-content">
              <span class="label">{{ $t('profile.joined') }}</span>
              <span class="value">{{ formatDate(userProfile.createdAt) }}</span>
            </div>
          </div>
        </div>

        <!-- Stats row -->
        <div class="stats-row" v-if="userStats">
          <div class="stat">
            <span class="stat-value">{{ userStats.contactCount || 0 }}</span>
            <span class="stat-label">{{ $t('profile.contacts') }}</span>
          </div>
          <div class="stat">
            <span class="stat-value">{{ userStats.groupCount || 0 }}</span>
            <span class="stat-label">{{ $t('profile.groups') }}</span>
          </div>
        </div>

        <!-- Mutual contacts -->
        <div class="mutual-section" v-if="mutualContacts.length > 0">
          <h4 class="mutual-title">{{ $t('profile.mutualContacts') }} ({{ mutualContacts.length }})</h4>
          <div class="mutual-list">
            <el-avatar
              v-for="contact in mutualContacts.slice(0, 5)"
              :key="contact.id"
              :size="36"
              :src="contact.avatar || contact.avatarUrl || defaultAvatar"
              class="mutual-avatar"
            />
            <span v-if="mutualContacts.length > 5" class="mutual-more">+{{ mutualContacts.length - 5 }}</span>
          </div>
        </div>
      </div>

      <!-- Social links section -->
      <div class="social-section glass-panel" v-if="socialLinks && Object.keys(socialLinks).length > 0">
        <h3 class="section-title">{{ $t('profile.socialLinks') }}</h3>
        <div class="social-links">
          <a
            v-for="(url, platform) in socialLinks"
            :key="platform"
            :href="url"
            target="_blank"
            rel="noopener noreferrer"
            class="social-link"
          >
            <el-icon><Link /></el-icon>
            <span>{{ platform }}</span>
          </a>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useContactStore } from '@/stores/contact'
import { useChatStore } from '@/stores/chat'
import { userAPI, contactAPI, chatAPI } from '@/services/api'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import {
  ArrowLeft, ChatDotRound, Plus, Message, Phone,
  Calendar, Link
} from '@element-plus/icons-vue'

dayjs.extend(relativeTime)

const props = defineProps({
  id: {
    type: [String, Number],
    required: true
  }
})

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const contactStore = useContactStore()
const chatStore = useChatStore()

const loading = ref(true)
const addingContact = ref(false)
const userProfile = ref(null)
const userStats = ref(null)
const isContact = ref(false)
const mutualContacts = ref([])
const socialLinks = ref({})

const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'

// Computed properties
const userId = computed(() => Number(props.id) || Number(route.params.id))
const isSelf = computed(() => userId.value === userStore.currentUser?.id)
const isOnline = computed(() => userProfile.value?.isOnline)
const showOnlineStatus = computed(() => userProfile.value?.showOnlineStatus !== false)

const coverStyle = computed(() => {
  const bg = userProfile.value?.profileBackground
  if (bg) {
    if (bg.startsWith('http') || bg.startsWith('data:image')) {
      return { backgroundImage: `url(${bg})` }
    }
    return { background: bg }
  }
  return {}
})

// Load user profile
const loadProfile = async () => {
  loading.value = true
  try {
    const viewerId = userStore.currentUser?.id

    // If viewing self, redirect to /profile
    if (isSelf.value) {
      router.replace('/profile')
      return
    }

    // Get user profile (with privacy filtering)
    const response = await userAPI.getUserProfileForViewer(userId.value, viewerId)
    userProfile.value = response.data

    // Check if already a contact
    try {
      const contactCheck = await contactAPI.checkIsContact(viewerId, userId.value)
      isContact.value = contactCheck.data
    } catch (e) {
      isContact.value = false
    }

    // Get mutual contacts
    try {
      const mutualResponse = await contactAPI.getMutualContacts(viewerId, userId.value)
      mutualContacts.value = mutualResponse.data || []
    } catch (e) {
      mutualContacts.value = []
    }

    // Get social links
    try {
      const linksResponse = await userAPI.getSocialLinks(userId.value)
      socialLinks.value = linksResponse.data || {}
    } catch (e) {
      socialLinks.value = {}
    }

    // Get user stats (contacts count, groups count)
    try {
      const statsResponse = await userAPI.getUserStats(userId.value)
      userStats.value = statsResponse.data
    } catch (e) {
      userStats.value = null
    }

  } catch (error) {
    console.error('Failed to load user profile:', error)
    ElMessage.error('Failed to load user profile')
    router.back()
  } finally {
    loading.value = false
  }
}

// Go back
const goBack = () => router.back()

// Add contact
const addContact = async () => {
  addingContact.value = true
  try {
    await contactAPI.addContact(userStore.currentUser.id, userId.value)
    ElMessage.success('Friend request sent!')
    // Refresh contact status
    const contactCheck = await contactAPI.checkIsContact(userStore.currentUser.id, userId.value)
    isContact.value = contactCheck.data
  } catch (error) {
    console.error('Failed to add contact:', error)
    ElMessage.error('Failed to send friend request')
  } finally {
    addingContact.value = false
  }
}

// Start chat
const startChat = async () => {
  try {
    // Create or get direct chat
    const response = await chatAPI.createDirectChat(userStore.currentUser.id, userId.value)
    const chat = response.data

    // Set active chat and navigate
    chatStore.setActiveChat(chat)
    router.push('/main')
  } catch (error) {
    console.error('Failed to start chat:', error)
    ElMessage.error('Failed to start chat')
  }
}

// Format time
const formatLastSeen = (time) => dayjs(time).fromNow()
const formatDate = (time) => dayjs(time).format('MMMM YYYY')

// Watch route changes
watch(() => route.params.id, (newId) => {
  if (newId) {
    loadProfile()
  }
})

onMounted(() => {
  loadProfile()
})
</script>

<style scoped>
/* Page container */
.user-profile-page {
  height: 100vh;
  background: var(--tg-background);
  overflow-y: auto;
}

.drag-region {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 32px;
  -webkit-app-region: drag;
  z-index: 1000;
}

.profile-container {
  max-width: 680px;
  margin: 0 auto;
  padding: 48px 24px 32px;
  position: relative;
}

.back-btn {
  position: fixed;
  top: 48px;
  left: 24px;
  z-index: 100;
  background: rgba(255, 255, 255, 0.9) !important;
  backdrop-filter: blur(10px);
  border: none !important;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
}

.back-btn:hover {
  transform: scale(1.05);
}

[data-theme="dark"] .back-btn {
  background: rgba(30, 41, 59, 0.9) !important;
}

/* Cover section */
.cover-section {
  height: 180px;
  border-radius: 20px;
  background-size: cover;
  background-position: center;
  position: relative;
  margin-bottom: -50px;
}

.default-gradient {
  background: linear-gradient(135deg, #14b8a6 0%, #3b82f6 50%, #8b5cf6 100%);
}

.cover-overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(to bottom, transparent 40%, rgba(0, 0, 0, 0.4));
  border-radius: 20px;
}

/* User card */
.user-card {
  position: relative;
  padding: 70px 32px 32px;
  text-align: center;
  border-radius: 20px;
  margin-bottom: 20px;
}

.avatar-section {
  position: absolute;
  top: -50px;
  left: 50%;
  transform: translateX(-50%);
}

.avatar-section .avatar-img {
  border: 4px solid var(--tg-surface);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
}

.status-dot {
  position: absolute;
  bottom: 6px;
  right: 6px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #9ca3af;
  border: 3px solid var(--tg-surface);
}

.status-dot.online {
  background: #10b981;
}

.user-info {
  margin-bottom: 20px;
}

.nickname {
  font-size: 26px;
  font-weight: 700;
  margin: 0 0 4px;
  color: var(--tg-text-primary);
}

.username {
  color: var(--tg-text-secondary);
  font-size: 14px;
  margin: 0 0 12px;
}

.bio {
  color: var(--tg-text-secondary);
  font-size: 15px;
  line-height: 1.6;
  margin: 0;
  max-width: 400px;
  margin-left: auto;
  margin-right: auto;
}

.status-text {
  margin-top: 12px;
  font-size: 13px;
}

.online-text {
  color: #10b981;
  font-weight: 600;
}

.offline-text {
  color: var(--tg-text-tertiary);
}

/* Action buttons */
.action-buttons {
  display: flex;
  justify-content: center;
  gap: 12px;
}

.action-btn {
  min-width: 160px;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  border-radius: 12px;
}

/* Details section */
.details-section,
.social-section {
  border-radius: 20px;
  padding: 24px;
  margin-bottom: 20px;
}

.section-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--tg-text-tertiary);
  margin: 0 0 16px;
  text-transform: uppercase;
  letter-spacing: 1px;
}

.detail-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.detail-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  background: var(--tg-background);
  border-radius: 12px;
}

.detail-item .el-icon {
  font-size: 20px;
  color: var(--tg-primary);
}

.detail-content {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.detail-content .label {
  font-size: 12px;
  color: var(--tg-text-tertiary);
  font-weight: 600;
}

.detail-content .value {
  font-size: 14px;
  color: var(--tg-text-primary);
}

/* Stats row */
.stats-row {
  display: flex;
  justify-content: center;
  gap: 60px;
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid var(--tg-divider);
}

.stat {
  text-align: center;
}

.stat-value {
  display: block;
  font-size: 26px;
  font-weight: 700;
  color: var(--tg-primary);
}

.stat-label {
  font-size: 13px;
  color: var(--tg-text-tertiary);
  font-weight: 500;
}

/* Mutual contacts */
.mutual-section {
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid var(--tg-divider);
}

.mutual-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--tg-text-secondary);
  margin: 0 0 12px;
}

.mutual-list {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mutual-avatar {
  border: 2px solid var(--tg-surface);
}

.mutual-more {
  font-size: 13px;
  color: var(--tg-text-tertiary);
  font-weight: 600;
  margin-left: 4px;
}

/* Social links */
.social-links {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.social-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  background: var(--tg-background);
  border-radius: 10px;
  color: var(--tg-text-secondary);
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  transition: all 0.2s ease;
}

.social-link:hover {
  background: var(--tg-primary);
  color: white;
  transform: translateY(-2px);
}

.social-link .el-icon {
  font-size: 16px;
}

/* Glass panel */
.glass-panel {
  background: var(--tg-surface);
  border: 1px solid var(--tg-divider);
  box-shadow: var(--tg-shadow-soft);
}

/* Animation */
.animate-fade-in {
  animation: fadeIn 0.4s ease-out;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* Responsive */
@media (max-width: 640px) {
  .profile-container {
    padding: 40px 16px 24px;
  }

  .cover-section {
    height: 140px;
    border-radius: 16px;
  }

  .user-card {
    padding: 60px 20px 24px;
  }

  .avatar-section .avatar-img {
    width: 100px !important;
    height: 100px !important;
  }

  .nickname {
    font-size: 22px;
  }

  .stats-row {
    gap: 40px;
  }

  .stat-value {
    font-size: 22px;
  }
}
</style>

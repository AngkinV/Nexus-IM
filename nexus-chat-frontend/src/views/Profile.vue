<template>
  <div class="profile-dashboard custom-scrollbar">
    <!-- Drag region for window dragging -->
    <div class="drag-region"></div>
    <div class="dashboard-container animate-fade-in">
      
      <!-- LEFT SIDEBAR: Identity & Navigation -->
      <aside class="dashboard-sidebar">
        <!-- User Identity Card -->
        <div class="identity-card glass-panel">
          <div class="cover-image" :style="heroStyle" :class="{ 'default-gradient': !heroBackground }">
             <div class="cover-overlay"></div>
             <el-button class="back-btn glass-btn" circle size="small" @click="goBack">
                <el-icon><ArrowLeft /></el-icon>
             </el-button>

             <input
                type="file"
                ref="fileInput"
                accept="image/*"
                style="display: none"
                @change="handleBackgroundUpload"
              >
              <!-- <el-button class="edit-cover-btn glass-btn" circle size="small" @click="triggerFileUpload">
                <el-icon><Camera /></el-icon>
              </el-button> -->
          </div>
          
          <div class="profile-avatar-section">
            <div class="avatar-container">
              <el-avatar :size="100" :src="userStore.currentUser?.avatar || defaultAvatar" class="avatar-img" />
              <div class="status-dot" :class="{ online: isOnline }"></div>
            </div>
          </div>

          <div class="user-info">
            <h2 class="user-nickname">{{ userStore.currentUser?.nickname || 'Nexus User' }}</h2>
            <p class="user-username">@{{ userStore.currentUser?.username }}</p>
            <p class="user-bio" v-if="userStore.currentUser?.bio">{{ userStore.currentUser.bio }}</p>
          </div>

          <div class="action-buttons">
            <el-button type="primary" class="edit-btn" @click="showEditProfile = true">
              {{ $t('settings.editProfile') }}
            </el-button>
            <el-button class="share-btn" @click="copyProfileLink">
              <el-icon><Share /></el-icon>
            </el-button>
          </div>
        </div>

        <!-- Navigation Menu -->
        <nav class="nav-menu glass-panel">
          <div 
            class="nav-item" 
            :class="{ active: activeSection === 'overview' }"
            @click="activeSection = 'overview'"
          >
            <el-icon><Postcard /></el-icon>
            <span>{{ $t('profile.overview') }}</span>
          </div>
          <div 
            class="nav-item" 
            :class="{ active: activeSection === 'social' }"
            @click="activeSection = 'social'"
          >
            <el-icon><Connection /></el-icon>
            <span>{{ $t('profile.social') }}</span>
          </div>
          <div 
            class="nav-item" 
            :class="{ active: activeSection === 'security' }"
            @click="activeSection = 'security'"
          >
            <el-icon><Lock /></el-icon>
            <span>{{ $t('profile.security') }}</span>
          </div>
        </nav>
      </aside>

      <!-- RIGHT CONTENT AREA -->
      <main class="dashboard-content glass-panel">
        <!-- Floating Header (Mobile/Tablet helper or Breadcrumb) -->
        <header class="content-header">
          <div class="header-left">
            <el-button v-if="isMobile" @click="goBack" circle text>
              <el-icon><ArrowLeft /></el-icon>
            </el-button>
            <h1 class="section-heading">{{ sectionTitle }}</h1>
          </div>
           <!-- Optional global actions could go here -->
        </header>

        <div class="content-body custom-scrollbar">
          <Transition name="fade-slide" mode="out-in">
            
            <!-- OVERVIEW TAB -->
            <div v-if="activeSection === 'overview'" class="tab-view overview-view">
              <!-- Stats Row -->
              <div class="stats-grid">
                <div class="stat-card">
                  <div class="stat-icon blue-bg"><el-icon><User /></el-icon></div>
                  <div class="stat-info">
                    <span class="stat-value">{{ contactCount }}</span>
                    <span class="stat-label">{{ $t('profile.contacts') }}</span>
                  </div>
                </div>
                <div class="stat-card">
                  <div class="stat-icon purple-bg"><el-icon><ChatDotRound /></el-icon></div>
                  <div class="stat-info">
                    <span class="stat-value">{{ groupCount }}</span>
                    <span class="stat-label">{{ $t('profile.groups') }}</span>
                  </div>
                </div>
                <div class="stat-card">
                  <div class="stat-icon green-bg"><el-icon><Message /></el-icon></div>
                  <div class="stat-info">
                    <span class="stat-value">{{ messageCount }}</span>
                    <span class="stat-label">{{ $t('profile.messages') }}</span>
                  </div>
                </div>
              </div>

              <!-- Contact Info Grid -->
              <div class="detail-grid">
                <h3 class="subsection-title">{{ $t('profile.about') }}</h3>
                <div class="detail-cards">
                  <div class="detail-card">
                    <span class="detail-label">{{ $t('auth.phone') }}</span>
                    <div class="detail-content">
                      <el-icon><Phone /></el-icon>
                      <span>{{ userStore.currentUser?.phone || '-' }}</span>
                    </div>
                  </div>
                   <div class="detail-card">
                    <span class="detail-label">{{ $t('auth.email') }}</span>
                    <div class="detail-content">
                      <el-icon><Message /></el-icon>
                      <span>{{ userStore.currentUser?.email || '-' }}</span>
                    </div>
                  </div>
                   <div class="detail-card full-width">
                    <span class="detail-label">{{ $t('auth.username') }}</span>
                    <div class="detail-content">
                      <el-icon><User /></el-icon>
                      <span>@{{ userStore.currentUser?.username }}</span>
                    </div>
                  </div>
                </div>
              </div>

              <!-- Recent Activity -->
              <div class="activity-section">
                <h3 class="subsection-title">{{ $t('profile.recentActivity') }}</h3>
                <div class="activity-timeline">
                  <div v-if="recentActivities.length === 0" class="empty-activity">
                    <p>{{ $t('profile.noRecentActivity') }}</p>
                  </div>
                  <div v-for="activity in recentActivities" :key="activity.id" class="activity-row">
                    <div class="timeline-dot" :class="activity.type"></div>
                    <div class="activity-desc">{{ activity.text }}</div>
                    <div class="activity-time">{{ formatTime(activity.time) }}</div>
                  </div>
                </div>
              </div>
            </div>

            <!-- SOCIAL TAB -->
            <div v-else-if="activeSection === 'social'" class="tab-view social-view">
              <SocialModule />
            </div>

            <!-- SECURITY TAB -->
            <div v-else-if="activeSection === 'security'" class="tab-view security-view">
              <AccountSecurityModule />
            </div>

          </Transition>
        </div>
      </main>

    </div>

    <!-- Edit Modal -->
    <EditProfileModal v-model:visible="showEditProfile" @updated="handleProfileUpdated" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useContactStore } from '@/stores/contact'
import { useChatStore } from '@/stores/chat'
import { userAPI, fileAPI } from '@/services/api'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft, EditPen, Phone, Message, User, Share, Link, Camera,
  Connection, Lock, Clock, UserFilled, Key, ChatDotRound, Postcard
} from '@element-plus/icons-vue'
import EditProfileModal from '@/components/common/EditProfileModal.vue'
import SocialModule from '@/components/profile/SocialModule.vue'
import AccountSecurityModule from '@/components/profile/AccountSecurityModule.vue'

const router = useRouter()
const userStore = useUserStore()
const contactStore = useContactStore()
const chatStore = useChatStore()

const showEditProfile = ref(false)
const fileInput = ref(null)
const isOnline = ref(true)
const activeSection = ref('overview')
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'
const userStats = ref(null)

// Stats (从 API 获取，保留 store 作为后备)
const contactCount = computed(() => userStats.value?.contactCount ?? contactStore.contacts.length)
// Groups: server stat counts every group the user is still a member of, but
// the user may have locally hidden some via the recent-list "delete" action.
// Subtract those so the count matches what the groups list actually displays.
const groupCount = computed(() => {
  const fromServer = userStats.value?.groupCount
  if (fromServer != null) {
    return Math.max(0, fromServer - chatStore.hiddenGroupCount)
  }
  return chatStore.groupChats.length
})
const messageCount = computed(() => userStats.value?.messageCount ?? chatStore.messages.length)

// Determine if mobile for back button logic (simple width check or could be a composable)
const isMobile = ref(window.innerWidth < 768)
window.addEventListener('resize', () => {
  isMobile.value = window.innerWidth < 768
})

const sectionTitle = computed(() => {
  switch(activeSection.value) {
    case 'overview': return 'Profile Overview';
    case 'social': return 'Social Links';
    case 'security': return 'Account Security';
    default: return 'Profile';
  }
})

// Activity & Security
const recentActivities = ref([])
const twoFactorEnabled = ref(false)
const passwordStrength = ref(60)
const activeSessions = ref(2)

// Hero Background
const heroBackground = computed(() => userStore.currentUser?.profileBackground)
const heroStyle = computed(() => {
  const bg = heroBackground.value
  if (bg) {
    if (bg.startsWith('http') || bg.startsWith('data:image')) {
      return { backgroundImage: `url(${bg})` }
    } else {
      return { background: bg }
    }
  }
  return {}
})

onMounted(async () => {
  await loadProfileData()
})

const loadProfileData = async () => {
  try {
    await userStore.loadUserProfile()
    if (userStore.currentUser?.id) {
      // 获取用户统计数据
      try {
        const statsResponse = await userAPI.getUserStats(userStore.currentUser.id)
        userStats.value = statsResponse.data
      } catch (e) {
        userStats.value = null
      }

      try {
        const response = await userAPI.getUserActivities(userStore.currentUser.id, 10)
        recentActivities.value = response.data.map(activity => ({
          id: activity.id,
          type: activity.activityType.toLowerCase(),
          text: activity.description || getActivityText(activity.activityType),
          time: activity.createdAt
        }))
      } catch (e) {}

      try {
        const profileResponse = await userAPI.getUserProfile(userStore.currentUser.id)
        twoFactorEnabled.value = profileResponse.data.twoFactorEnabled || false
      } catch (e) {}
    }
  } catch (e) {}
}

const getActivityText = (type) => {
  const texts = { message: 'Sent a message', contact: 'Added a new contact', group: 'Joined a group', login: 'Logged in' }
  return texts[type.toLowerCase()] || 'Activity recorded'
}

const goBack = () => router.back()
const triggerFileUpload = () => fileInput.value.click()

const handleBackgroundUpload = async (event) => {
  const file = event.target.files[0]
  if (!file) return
  if (!file.type.startsWith('image/')) return ElMessage.error('Please upload an image file')
  if (file.size > 5 * 1024 * 1024) return ElMessage.error('Image size should be less than 5MB')

  try {
    // 上传文件到服务器，获取 URL
    const uploadResponse = await fileAPI.uploadFile(file)
    const imageUrl = uploadResponse.data.url

    // 保存 URL 到用户资料
    await userStore.updateBackground(imageUrl)
    ElMessage.success('Background updated successfully')
  } catch (error) {
    console.error('Failed to upload background:', error)
    ElMessage.error('Failed to update background')
  }
}

const copyProfileLink = () => {
  navigator.clipboard.writeText(`${window.location.origin}/profile/${userStore.currentUser?.username}`)
  ElMessage.success('Profile link copied!')
}

const handleProfileUpdated = () => loadProfileData()
const formatTime = (time) => {
  const date = new Date(time); const now = new Date(); const diff = now - date;
  const hours = Math.floor(diff / (1000 * 60 * 60))
  if (hours < 1) return 'Just now'
  if (hours < 24) return `${hours}h ago`
  return `${Math.floor(hours / 24)}d ago`
}
</script>

<style scoped>
/* Page Layout */
.profile-dashboard {
  height: 100vh;
  background-color: #f3f4f6;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 40px;
  box-sizing: border-box;
  position: relative;
}

/* Drag region for window dragging */
.drag-region {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 32px;
  -webkit-app-region: drag;
  z-index: 1000;
}

[data-theme="dark"] .profile-dashboard {
  background-color: #111827;
}

.dashboard-container {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 24px;
  width: 100%;
  max-width: 1200px;
  height: 100%;
  max-height: 900px;
}

/* Sidebar */
.dashboard-sidebar {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.glass-panel {
  background: white;
  border-radius: 16px;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.5);
  overflow: hidden;
}

[data-theme="dark"] .glass-panel {
  background: #1f2937;
  border-color: #374151;
}

/* Identity Card */
.identity-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-bottom: 24px;
}

.cover-image {
  width: 100%;
  height: 180px;
  background-size: cover;
  background-position: center;
  position: relative;
}

.default-gradient {
  background: linear-gradient(135deg, #14b8a6 0%, #3b82f6 50%, #8b5cf6 100%);
}

.cover-overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(to bottom, transparent 40%, rgba(0, 0, 0, 0.4));
  pointer-events: none;
}

.back-btn {
  position: absolute;
  top: 8px;
  left: 8px;
  background: rgba(0,0,0,0.3);
  color: white;
  border: none;
  z-index: 10;
}

.back-btn:hover {
  background: rgba(0,0,0,0.4);
}

.edit-cover-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  background: rgba(0,0,0,0.3);
  color: white;
  border: none;
}

.profile-avatar-section {
  margin-top: -50px;
  margin-bottom: 12px;
}

.avatar-container {
  position: relative;
  padding: 4px;
  background: white;
  border-radius: 50%;
}

[data-theme="dark"] .avatar-container {
  background: #1f2937;
}

.status-dot {
  width: 16px;
  height: 16px;
  background: #9ca3af;
  border: 3px solid white;
  border-radius: 50%;
  position: absolute;
  bottom: 5px;
  right: 5px;
}

[data-theme="dark"] .status-dot {
  border-color: #1f2937;
}

.status-dot.online {
  background: #10b981;
}

.user-info {
  text-align: center;
  padding: 0 20px;
  margin-bottom: 20px;
}

.user-nickname {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: #111827;
}

[data-theme="dark"] .user-nickname {
  color: #f9fafb;
}

.user-username {
  color: #6b7280;
  font-size: 14px;
  margin: 4px 0 8px;
}

.user-bio {
  font-size: 13px;
  color: #4b5563;
  line-height: 1.4;
  margin: 0;
}

[data-theme="dark"] .user-bio {
  color: #9ca3af;
}

.action-buttons {
  display: flex;
  gap: 12px;
}

.edit-btn {
  padding: 8px 24px;
  border-radius: 8px;
  font-weight: 600;
}

.share-btn {
  padding: 8px 12px;
  border-radius: 8px;
}

/* Nav Menu */
.nav-menu {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-radius: 8px;
  color: #4b5563;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

[data-theme="dark"] .nav-item {
  color: #d1d5db;
}

.nav-item:hover {
  background: #f3f4f6;
  color: #3b82f6;
}

[data-theme="dark"] .nav-item:hover {
  background: #374151;
}

.nav-item.active {
  background: #eff6ff;
  color: #3b82f6;
}

[data-theme="dark"] .nav-item.active {
  background: rgba(59, 130, 246, 0.15);
}

/* Content Area */
.dashboard-content {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.content-header {
  padding: 24px;
  border-bottom: 1px solid #e5e7eb;
}

[data-theme="dark"] .content-header {
  border-color: #374151;
}

.section-heading {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: #111827;
}

[data-theme="dark"] .section-heading {
  color: #f9fafb;
}

.content-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

/* Overview Styles */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
  margin-bottom: 32px;
}

.stat-card {
  background: #f9fafb;
  padding: 16px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  gap: 16px;
  border: 1px solid #e5e7eb;
}

[data-theme="dark"] .stat-card {
  background: #283141; /* Slightly lighter than panel */
  border-color: #374151;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: white;
}

.blue-bg { background: #3b82f6; }
.purple-bg { background: #4cd7f6; }
.green-bg { background: #10b981; }

.stat-info {
  display: flex;
  flex-direction: column;
}

.stat-value {
  font-size: 20px;
  font-weight: 700;
  color: #111827;
}

[data-theme="dark"] .stat-value {
  color: #f9fafb;
}

.stat-label {
  font-size: 13px;
  color: #6b7280;
}

.subsection-title {
  font-size: 16px;
  font-weight: 600;
  color: #374151;
  margin: 0 0 16px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

[data-theme="dark"] .subsection-title {
  color: #9ca3af;
}

/* Detail Grid */
.detail-grid {
  margin-bottom: 32px;
}

.detail-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.detail-card {
  background: #f9fafb;
  padding: 16px;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  border: 1px solid #e5e7eb;
}

[data-theme="dark"] .detail-card {
  background: #283141;
  border-color: #374151;
}

.full-width {
  grid-column: span 2;
}

.detail-label {
  font-size: 12px;
  color: #6b7280;
  font-weight: 500;
}

.detail-content {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  color: #111827;
  font-weight: 500;
}

[data-theme="dark"] .detail-content {
  color: #f9fafb;
}

/* Activity */
.activity-timeline {
  display: flex;
  flex-direction: column;
  gap: 16px;
  position: relative;
}

.activity-timeline::before {
  content: '';
  position: absolute;
  left: 7px;
  top: 10px;
  bottom: 10px;
  width: 2px;
  background: #e5e7eb;
}

[data-theme="dark"] .activity-timeline::before {
  background: #374151;
}

.activity-row {
  display: flex;
  align-items: center;
  gap: 16px;
  position: relative;
  padding-left: 24px;
}

.timeline-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #d1d5db;
  position: absolute;
  left: 3px;
  border: 2px solid white;
}

[data-theme="dark"] .timeline-dot {
  border-color: #1f2937;
}

.timeline-dot.message { background: #3b82f6; }
.timeline-dot.login { background: #10b981; }

.activity-desc {
  flex: 1;
  font-size: 14px;
  color: #374151;
}

[data-theme="dark"] .activity-desc {
  color: #d1d5db;
}

.activity-time {
  font-size: 12px;
  color: #9ca3af;
}

/* Transitions */
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: all 0.3s ease;
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateY(10px);
}

.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-10px);
}

.animate-fade-in {
  animation: fadeIn 0.5s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: scale(0.98); }
  to { opacity: 1; transform: scale(1); }
}

/* Scrollbar */
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #cbd5e1;
  border-radius: 3px;
}
[data-theme="dark"] .custom-scrollbar::-webkit-scrollbar-thumb {
  background: #4b5563;
}

/* Mobile Breakpoint */
@media (max-width: 768px) {
  .profile-dashboard {
    padding: 0;
    align-items: flex-start;
  }

  .drag-region {
    height: 44px;
  }

  .dashboard-container {
    grid-template-columns: 1fr;
    max-height: none;
    height: auto;
    gap: 0;
  }

  .dashboard-sidebar, .glass-panel {
    border-radius: 0;
    border: none;
    box-shadow: none;
  }

  .cover-image {
    height: 140px;
  }

  .profile-avatar-section {
    margin-top: -40px;
  }

  .avatar-container {
    padding: 3px;
  }

  .avatar-img {
    width: 80px !important;
    height: 80px !important;
  }

  .user-nickname {
    font-size: 18px;
  }

  .user-info {
    padding: 0 16px;
    margin-bottom: 16px;
  }

  .action-buttons {
    gap: 10px;
  }

  .edit-btn {
    padding: 8px 20px;
    font-size: 14px;
  }

  .nav-menu {
    flex-direction: row;
    overflow-x: auto;
    border-bottom: 1px solid #e5e7eb;
    padding: 8px;
    gap: 8px;
  }

  .nav-item {
    flex-shrink: 0;
    padding: 10px 16px;
    font-size: 13px;
  }

  .dashboard-content {
    min-height: 400px;
  }

  .content-header {
    padding: 16px;
  }

  .section-heading {
    font-size: 20px;
  }

  .content-body {
    padding: 16px;
  }

  .stats-grid {
    grid-template-columns: 1fr;
    gap: 12px;
    margin-bottom: 24px;
  }

  .stat-card {
    padding: 14px;
  }

  .stat-icon {
    width: 42px;
    height: 42px;
  }

  .stat-value {
    font-size: 18px;
  }

  .detail-cards {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .detail-card {
    padding: 14px;
  }

  .full-width {
    grid-column: auto;
  }

  .subsection-title {
    font-size: 14px;
    margin-bottom: 12px;
  }

  .activity-row {
    padding-left: 20px;
  }

  .activity-desc {
    font-size: 13px;
  }
}

/* Small phones */
@media (max-width: 375px) {
  .cover-image {
    height: 120px;
  }

  .avatar-img {
    width: 70px !important;
    height: 70px !important;
  }

  .user-nickname {
    font-size: 16px;
  }

  .nav-item {
    padding: 8px 12px;
    font-size: 12px;
  }

  .stat-card {
    padding: 12px;
    gap: 12px;
  }

  .stat-icon {
    width: 38px;
    height: 38px;
    font-size: 16px;
  }

  .stat-value {
    font-size: 16px;
  }
}
</style>

<template>
  <div class="social-module">
    <!-- Social Links Section -->
    <div class="section-card glass-card">
      <div class="section-header">
        <h3 class="section-title">{{ $t('profile.socialLinks') }}</h3>
        <el-button type="primary" text @click="showAddLinkDialog = true">
          <el-icon><Plus /></el-icon>
          {{ $t('profile.addLink') }}
        </el-button>
      </div>

      <div class="social-links-list">
        <div v-if="socialLinks.length === 0" class="empty-state">
          <el-icon class="empty-icon"><Link /></el-icon>
          <p>{{ $t('profile.noSocialLinks') }}</p>
          <el-button type="primary" @click="showAddLinkDialog = true">
            {{ $t('profile.addFirstLink') }}
          </el-button>
        </div>

        <div v-for="link in socialLinks" :key="link.platform" class="social-link-item">
          <div class="link-info">
            <div class="platform-icon" :class="getPlatformClass(link.platform)">
              <el-icon><component :is="getPlatformIcon(link.platform)" /></el-icon>
            </div>
            <div class="link-details">
              <span class="platform-name">{{ link.platform }}</span>
              <span class="link-url">{{ link.url }}</span>
            </div>
          </div>
          <div class="link-actions">
            <el-button text circle @click="openLink(link.url)">
              <el-icon><Link /></el-icon>
            </el-button>
            <el-button text circle type="danger" @click="removeLink(link.platform)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- Online Friends Section -->
    <div class="section-card glass-card">
      <h3 class="section-title">{{ $t('profile.onlineFriends') }}</h3>
      <div class="online-friends">
        <div v-if="onlineFriends.length === 0" class="empty-state small">
          <p>{{ $t('profile.noOnlineFriends') }}</p>
        </div>
        <div class="friends-grid" v-else>
          <div
            v-for="friend in onlineFriends.slice(0, 8)"
            :key="friend.id"
            class="friend-avatar-item"
            @click="viewFriendProfile(friend)"
          >
            <el-avatar :size="48" :src="friend.avatar || defaultAvatar" />
            <div class="online-dot"></div>
            <span class="friend-nickname">{{ friend.nickname }}</span>
          </div>
        </div>
        <el-button v-if="onlineFriends.length > 8" text class="see-all">
          {{ $t('profile.seeAll') }} ({{ onlineFriends.length }})
        </el-button>
      </div>
    </div>

    <!-- Recent Activities Section -->
    <div class="section-card glass-card">
      <h3 class="section-title">{{ $t('profile.friendActivity') }}</h3>
      <div class="activity-feed">
        <div v-if="friendActivities.length === 0" class="empty-state small">
          <p>{{ $t('profile.noFriendActivity') }}</p>
        </div>
        <div v-for="activity in friendActivities" :key="activity.id" class="activity-feed-item">
          <el-avatar :size="40" :src="activity.user.avatar || defaultAvatar" />
          <div class="activity-feed-content">
            <p class="activity-text">
              <strong>{{ activity.user.nickname }}</strong>
              {{ activity.action }}
            </p>
            <span class="activity-time">{{ formatActivityTime(activity.time) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Add Social Link Dialog -->
    <el-dialog
      v-model="showAddLinkDialog"
      :title="$t('profile.addSocialLink')"
      width="380px"
      class="social-dialog"
    >
      <el-form :model="newLinkForm" label-position="top">
        <el-form-item :label="$t('profile.platform')">
          <el-select v-model="newLinkForm.platform" :placeholder="$t('profile.selectPlatform')" class="full-width">
            <el-option label="GitHub" value="GitHub">
              <el-icon><Link /></el-icon>
              <span>GitHub</span>
            </el-option>
            <el-option label="Twitter" value="Twitter">
              <el-icon><Link /></el-icon>
              <span>Twitter / X</span>
            </el-option>
            <el-option label="LinkedIn" value="LinkedIn">
              <el-icon><Link /></el-icon>
              <span>LinkedIn</span>
            </el-option>
            <el-option label="Instagram" value="Instagram">
              <el-icon><Link /></el-icon>
              <span>Instagram</span>
            </el-option>
            <el-option label="Website" value="Website">
              <el-icon><Link /></el-icon>
              <span>Personal Website</span>
            </el-option>
            <el-option label="Other" value="Other">
              <el-icon><Link /></el-icon>
              <span>Other</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item :label="$t('profile.linkUrl')">
          <el-input
            v-model="newLinkForm.url"
            :placeholder="$t('profile.enterLinkUrl')"
            prefix-icon="Link"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddLinkDialog = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" @click="addSocialLink" :loading="isAddingLink">
          {{ $t('common.save') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useUserStore } from '@/stores/user'
import { useContactStore } from '@/stores/contact'
import { userAPI } from '@/services/api'
import { ElMessage } from 'element-plus'
import { Link, Plus, Delete } from '@element-plus/icons-vue'

const userStore = useUserStore()
const contactStore = useContactStore()

const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'
const showAddLinkDialog = ref(false)
const isAddingLink = ref(false)

const newLinkForm = ref({
  platform: '',
  url: ''
})

// Social links from user store
const socialLinks = computed(() => {
  const links = userStore.currentUser?.socialLinks || {}
  return Object.entries(links).map(([platform, url]) => ({ platform, url }))
})

// Online friends from contacts
const onlineFriends = computed(() => {
  return contactStore.contacts.filter(c => c.online).slice(0, 12)
})

// Friend activities
const friendActivities = ref([])

// Load friend activities on mount
onMounted(async () => {
  await loadFriendActivities()
})

const loadFriendActivities = async () => {
  if (!userStore.currentUser?.id) return
  try {
    const response = await userAPI.getFriendActivities(userStore.currentUser.id, 10)
    friendActivities.value = response.data.map(activity => ({
      id: activity.id,
      user: activity.user || { nickname: 'User', avatar: null },
      action: activity.description || getActivityAction(activity.activityType),
      time: activity.createdAt
    }))
  } catch (error) {
    console.error('Failed to load friend activities:', error)
  }
}

const getActivityAction = (type) => {
  const actions = {
    message: 'sent a message',
    contact: 'added a new contact',
    group: 'joined a group',
    login: 'logged in',
    profile_update: 'updated their profile'
  }
  return actions[type?.toLowerCase()] || 'was active'
}

const getPlatformClass = (platform) => {
  const classes = {
    'GitHub': 'github',
    'Twitter': 'twitter',
    'LinkedIn': 'linkedin',
    'Instagram': 'instagram',
    'Website': 'website'
  }
  return classes[platform] || 'default'
}

const getPlatformIcon = () => {
  return Link
}

const openLink = (url) => {
  window.open(url, '_blank')
}

const removeLink = async (platform) => {
  try {
    await userStore.removeSocialLink(platform)
    ElMessage.success('Link removed')
  } catch (error) {
    ElMessage.error('Failed to remove link')
  }
}

const addSocialLink = async () => {
  if (!newLinkForm.value.platform || !newLinkForm.value.url) {
    ElMessage.warning('Please fill in all fields')
    return
  }

  isAddingLink.value = true
  try {
    await userStore.addSocialLink(newLinkForm.value.platform, newLinkForm.value.url)
    ElMessage.success('Link added successfully')
    showAddLinkDialog.value = false
    newLinkForm.value = { platform: '', url: '' }
  } catch (error) {
    ElMessage.error('Failed to add link')
  } finally {
    isAddingLink.value = false
  }
}

const viewFriendProfile = (friend) => {
  // Navigate to friend's profile or open chat
  console.log('View profile:', friend)
}

const formatActivityTime = (time) => {
  const date = new Date(time)
  const now = new Date()
  const diff = now - date
  const minutes = Math.floor(diff / (1000 * 60))
  if (minutes < 1) return 'Just now'
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.floor(hours / 24)}d ago`
}
</script>

<style scoped>
.social-module {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.section-card {
  padding: 20px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.section-title {
  margin: 0 0 16px;
  font-size: 13px;
  text-transform: uppercase;
  letter-spacing: 1.2px;
  font-weight: 700;
  color: #8e8e93;
}

.section-header .section-title {
  margin-bottom: 0;
}

/* Social Links */
.social-links-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.social-link-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: rgba(51, 144, 236, 0.04);
  border-radius: 12px;
  transition: background 0.2s;
}

.social-link-item:hover {
  background: rgba(51, 144, 236, 0.08);
}

.link-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.platform-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 18px;
}

.platform-icon.github { background: linear-gradient(135deg, #333, #24292e); }
.platform-icon.twitter { background: linear-gradient(135deg, #1da1f2, #0d8bd9); }
.platform-icon.linkedin { background: linear-gradient(135deg, #0077b5, #005582); }
.platform-icon.instagram { background: linear-gradient(135deg, #e4405f, #c13584); }
.platform-icon.website { background: linear-gradient(135deg, #3390ec, #667eea); }
.platform-icon.default { background: linear-gradient(135deg, #8e8e93, #636366); }

.link-details {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.platform-name {
  font-size: 14px;
  font-weight: 600;
  color: #1c1c1e;
}

.link-url {
  font-size: 12px;
  color: #8e8e93;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.link-actions {
  display: flex;
  gap: 4px;
}

/* Online Friends */
.online-friends {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.friends-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.friend-avatar-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  position: relative;
}

.friend-avatar-item:hover .el-avatar {
  transform: scale(1.05);
}

.friend-avatar-item .el-avatar {
  transition: transform 0.2s;
}

.online-dot {
  position: absolute;
  top: 0;
  right: calc(50% - 28px);
  width: 12px;
  height: 12px;
  background: #00c853;
  border: 2px solid white;
  border-radius: 50%;
}

.friend-nickname {
  font-size: 12px;
  color: #666;
  text-align: center;
  max-width: 60px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Activity Feed */
.activity-feed {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.activity-feed-item {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.activity-feed-content {
  flex: 1;
}

.activity-feed-content .activity-text {
  margin: 0 0 4px;
  font-size: 14px;
  color: #1c1c1e;
  line-height: 1.4;
}

.activity-feed-content .activity-time {
  font-size: 12px;
  color: #8e8e93;
}

/* Empty States */
.empty-state {
  text-align: center;
  padding: 32px 16px;
  color: #8e8e93;
}

.empty-state.small {
  padding: 16px;
}

.empty-state p {
  margin: 0 0 16px;
  font-size: 14px;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
  opacity: 0.5;
}

.see-all {
  color: #3390ec;
  font-weight: 600;
}

/* Dialog */
.full-width {
  width: 100%;
}

/* Glass Card */
.glass-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.9);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.04), 0 1px 4px rgba(0, 0, 0, 0.02);
  border-radius: 20px;
}

/* Responsive */
@media (max-width: 640px) {
  .friends-grid {
    grid-template-columns: repeat(3, 1fr);
    gap: 12px;
  }
}
</style>

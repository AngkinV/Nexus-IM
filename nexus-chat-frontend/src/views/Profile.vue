<template>
  <div class="profile-page">
    <!-- Drag region for window dragging -->
    <div class="drag-region"></div>

    <div class="profile-shell animate-fade-in">
      <!-- LEFT: identity sidebar -->
      <aside class="profile-aside">
        <button class="aside-back no-drag" @click="goBack" :title="$t('common.back')">
          <el-icon><ArrowLeft /></el-icon>
        </button>

        <div class="aside-identity">
          <div class="identity-avatar">
            <el-avatar :size="84" :src="userStore.currentUser?.avatar || defaultAvatar" />
            <span class="status-dot" :class="{ online: isOnline }"></span>
          </div>
          <div class="identity-name-row">
            <h2 class="identity-name">{{ displayName }}</h2>
            <button class="name-edit" @click="showEditProfile = true" :title="$t('settings.editProfile')">
              <el-icon><EditPen /></el-icon>
            </button>
          </div>
          <p class="identity-handle">ID: {{ userStore.currentUser?.username }}</p>
          <p class="identity-tagline">{{ userStore.currentUser?.bio || $t('profile.noSignature') }}</p>
        </div>

        <nav class="aside-nav">
          <button
            class="nav-item"
            :class="{ active: activeSection === 'overview' }"
            @click="activeSection = 'overview'"
          >
            <el-icon><Menu /></el-icon>
            <span>{{ $t('profile.overview') }}</span>
          </button>
          <button
            class="nav-item"
            :class="{ active: activeSection === 'security' }"
            @click="activeSection = 'security'"
          >
            <el-icon><Lock /></el-icon>
            <span>{{ $t('profile.security') }}</span>
          </button>
          <button
            class="nav-item"
            :class="{ active: activeSection === 'social' }"
            @click="activeSection = 'social'"
          >
            <el-icon><Connection /></el-icon>
            <span>{{ $t('profile.social') }}</span>
          </button>
        </nav>

        <!-- Decorative planet -->
        <div class="aside-decoration" aria-hidden="true">
          <svg viewBox="0 0 160 160" width="150" height="150">
            <defs>
              <linearGradient id="planetBody" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stop-color="#cfe0ff" />
                <stop offset="55%" stop-color="#a9c4ff" />
                <stop offset="100%" stop-color="#7aa0f5" />
              </linearGradient>
              <linearGradient id="planetRing" x1="0" y1="0" x2="1" y2="0">
                <stop offset="0%" stop-color="#bcd0ff" />
                <stop offset="100%" stop-color="#dfe9ff" />
              </linearGradient>
            </defs>
            <circle cx="80" cy="78" r="40" fill="url(#planetBody)" />
            <ellipse cx="80" cy="78" rx="38" ry="14" fill="#ffffff" opacity="0.22" />
            <circle cx="66" cy="66" r="6" fill="#ffffff" opacity="0.4" />
            <circle cx="92" cy="86" r="9" fill="#ffffff" opacity="0.16" />
            <ellipse cx="80" cy="86" rx="64" ry="20" fill="none" stroke="url(#planetRing)" stroke-width="7" opacity="0.85" transform="rotate(-18 80 86)" />
            <circle cx="32" cy="40" r="2.4" fill="#9db9ff" />
            <circle cx="128" cy="44" r="2" fill="#9db9ff" />
            <circle cx="120" cy="118" r="2.6" fill="#9db9ff" />
          </svg>
        </div>
      </aside>

      <!-- RIGHT: content -->
      <main class="profile-main">
        <div class="main-body">
          <Transition name="fade-slide" mode="out-in">
            <!-- OVERVIEW -->
            <div v-if="activeSection === 'overview'" key="overview" class="tab-overview">
              <!-- Header -->
              <header class="ov-header">
                <div class="ov-heading">
                  <h1 class="ov-title">{{ $t('profile.overview') }}</h1>
                  <p class="ov-greeting">{{ $t('profile.welcomeBack', { name: displayName }) }} 👋</p>
                </div>
                <p class="ov-quote">{{ $t('profile.heroQuote') }}</p>
              </header>

              <!-- Hero welcome banner -->
              <section class="hero-card">
                <div class="hero-text">
                  <h2 class="hero-title">{{ $t('profile.heroTitle') }}</h2>
                  <p class="hero-sub">{{ $t('profile.heroSubtitle') }}</p>
                </div>
                <div class="hero-art" aria-hidden="true">
                  <svg viewBox="0 0 220 150" width="220" height="150">
                    <defs>
                      <linearGradient id="podium" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stop-color="#c9d8ff" />
                        <stop offset="100%" stop-color="#aec4f7" />
                      </linearGradient>
                      <linearGradient id="bubbleA" x1="0" y1="0" x2="1" y2="1">
                        <stop offset="0%" stop-color="#ffffff" />
                        <stop offset="100%" stop-color="#dbe6ff" />
                      </linearGradient>
                      <linearGradient id="bubbleB" x1="0" y1="0" x2="1" y2="1">
                        <stop offset="0%" stop-color="#7aa0f5" />
                        <stop offset="100%" stop-color="#6aa8f6" />
                      </linearGradient>
                    </defs>
                    <ellipse cx="120" cy="120" rx="92" ry="20" fill="url(#podium)" opacity="0.55" />
                    <ellipse cx="120" cy="116" rx="70" ry="14" fill="#ffffff" opacity="0.5" />
                    <rect x="58" y="44" width="78" height="54" rx="16" fill="url(#bubbleA)" />
                    <path d="M78 96 l0 16 l16 -12 z" fill="#dbe6ff" />
                    <circle cx="80" cy="71" r="4.4" fill="#9db9ff" />
                    <circle cx="97" cy="71" r="4.4" fill="#9db9ff" />
                    <circle cx="114" cy="71" r="4.4" fill="#9db9ff" />
                    <rect x="120" y="30" width="70" height="48" rx="15" fill="url(#bubbleB)" />
                    <path d="M168 76 l0 15 l15 -11 z" fill="#6aa8f6" />
                    <rect x="132" y="46" width="46" height="6" rx="3" fill="#ffffff" opacity="0.85" />
                    <rect x="132" y="58" width="30" height="6" rx="3" fill="#ffffff" opacity="0.6" />
                    <circle cx="44" cy="40" r="4" fill="#bcd0ff" />
                    <circle cx="196" cy="96" r="5" fill="#bcd0ff" />
                    <circle cx="182" cy="20" r="3" fill="#d7e2ff" />
                  </svg>
                </div>
              </section>

              <!-- Stat cards -->
              <section class="stat-grid">
                <div v-for="s in statCards" :key="s.key" class="stat-card">
                  <span class="stat-icon" :class="s.tone">
                    <el-icon><component :is="s.icon" /></el-icon>
                  </span>
                  <div class="stat-body">
                    <span class="stat-label">{{ s.label }}</span>
                    <span class="stat-value">
                      {{ s.value }}<i v-if="s.unit" class="stat-unit"> {{ s.unit }}</i>
                    </span>
                    <span class="stat-delta" :class="s.trend">
                      <template v-if="s.trend === 'flat'">{{ $t('profile.vsYesterday') }} —</template>
                      <template v-else>
                        {{ $t('profile.vsYesterday') }}
                        <el-icon><component :is="s.trend === 'up' ? CaretTop : CaretBottom" /></el-icon>{{ s.delta }}%
                      </template>
                    </span>
                  </div>
                </div>
              </section>

              <!-- Bottom two columns -->
              <section class="ov-columns">
                <!-- Personal info -->
                <div class="panel">
                  <div class="panel-head">
                    <h3 class="panel-title">{{ $t('profile.personalInfo') }}</h3>
                  </div>
                  <div class="pi-rows">
                    <button class="pi-row" @click="showEditProfile = true">
                      <span class="pi-ic blue"><el-icon><User /></el-icon></span>
                      <span class="pi-label">{{ $t('auth.username') }}</span>
                      <span class="pi-value">{{ displayName }}</span>
                      <el-icon class="pi-arrow"><ArrowRight /></el-icon>
                    </button>
                    <div class="pi-row static">
                      <span class="pi-ic green"><el-icon><CircleCheck /></el-icon></span>
                      <span class="pi-label">{{ $t('profile.status') }}</span>
                      <span class="pi-value">
                        <span class="dot-online" :class="{ on: isOnline }"></span>
                        {{ isOnline ? $t('profile.online') : $t('profile.offline') }}
                      </span>
                    </div>
                    <button class="pi-row" @click="showEditProfile = true">
                      <span class="pi-ic purple"><el-icon><EditPen /></el-icon></span>
                      <span class="pi-label">{{ $t('profile.signature') }}</span>
                      <span class="pi-value">{{ userStore.currentUser?.bio || $t('profile.noSignature') }}</span>
                      <el-icon class="pi-arrow"><ArrowRight /></el-icon>
                    </button>
                    <div class="pi-row static">
                      <span class="pi-ic orange"><el-icon><Calendar /></el-icon></span>
                      <span class="pi-label">{{ $t('profile.registeredAt') }}</span>
                      <span class="pi-value">{{ registeredAt }}</span>
                    </div>
                  </div>
                </div>

                <!-- Recent activity -->
                <div class="panel">
                  <div class="panel-head">
                    <h3 class="panel-title">{{ $t('profile.recentActivity') }}</h3>
                    <button class="panel-link" @click="activeSection = 'social'">
                      {{ $t('profile.seeAll') }}<el-icon><ArrowRight /></el-icon>
                    </button>
                  </div>
                  <div class="act-list">
                    <div v-if="recentActivities.length === 0" class="act-empty">
                      {{ $t('profile.noRecentActivity') }}
                    </div>
                    <div v-for="a in recentActivities" :key="a.id" class="act-row">
                      <el-avatar :size="34" :src="userStore.currentUser?.avatar || defaultAvatar" />
                      <span class="act-text">{{ a.text }}</span>
                      <span class="act-time">{{ formatTime(a.time) }}</span>
                      <span class="act-type" :class="a.tone"><el-icon><component :is="a.icon" /></el-icon></span>
                    </div>
                  </div>
                </div>
              </section>
            </div>

            <!-- SOCIAL -->
            <div v-else-if="activeSection === 'social'" key="social" class="tab-overview">
              <SocialModule />
            </div>

            <!-- SECURITY -->
            <div v-else key="security" class="tab-overview">
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
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '@/stores/user'
import { useContactStore } from '@/stores/contact'
import { useChatStore } from '@/stores/chat'
import { useConnectionStore } from '@/stores/connection'
import { userAPI } from '@/services/api'
import {
  ArrowLeft, ArrowRight, EditPen, Lock, Connection, Menu,
  User, UserFilled, ChatDotRound, Clock, Calendar, CircleCheck,
  CaretTop, CaretBottom, Plus, Bell
} from '@element-plus/icons-vue'
import EditProfileModal from '@/components/common/EditProfileModal.vue'
import SocialModule from '@/components/profile/SocialModule.vue'
import AccountSecurityModule from '@/components/profile/AccountSecurityModule.vue'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const userStore = useUserStore()
const contactStore = useContactStore()
const chatStore = useChatStore()
const connectionStore = useConnectionStore()

const showEditProfile = ref(false)
// Own online status reflects the live WebSocket connection, not a hard-coded value.
const isOnline = computed(() => connectionStore.isConnected)
// Active tab persists in the URL so returning from a sub-page (e.g. /user/:id) restores it
const PROFILE_TABS = ['overview', 'security', 'social']
const activeSection = ref(PROFILE_TABS.includes(route.query.tab) ? route.query.tab : 'overview')

watch(activeSection, (tab) => {
  if (route.query.tab !== tab) {
    router.replace({ query: { ...route.query, tab } })
  }
})
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'
const userStats = ref(null)

const displayName = computed(() =>
  userStore.currentUser?.nickname || userStore.currentUser?.username || 'Nexus User'
)

// Stats (从 API 获取，保留 store 作为后备)
const contactCount = computed(() => userStats.value?.contactCount ?? contactStore.contacts.length)
const groupCount = computed(() => {
  const fromServer = userStats.value?.groupCount
  if (fromServer != null) {
    return Math.max(0, fromServer - chatStore.hiddenGroupCount)
  }
  return chatStore.groupChats.length
})
const messageCount = computed(() => userStats.value?.messageCount ?? chatStore.messages.length)
// Cumulative online hours come from the backend (Redis heartbeat accounting).
const onlineHours = computed(() => {
  const h = userStats.value?.onlineHours
  return h != null ? h : 0
})

const formatNum = (n) => (n ?? 0).toLocaleString('en-US')

// Signed percent delta -> trend direction. null => flat (no data yet).
const trendOf = (d) => (d == null ? 'flat' : d > 0 ? 'up' : d < 0 ? 'down' : 'flat')

const statCards = computed(() => {
  const s = userStats.value || {}
  return [
    { key: 'messages', icon: ChatDotRound, tone: 'blue', label: t('profile.totalMessages'), value: formatNum(messageCount.value), trend: trendOf(s.messagesDelta), delta: Math.abs(s.messagesDelta ?? 0) },
    { key: 'contacts', icon: User, tone: 'green', label: t('profile.contacts'), value: formatNum(contactCount.value), trend: trendOf(s.contactsDelta), delta: Math.abs(s.contactsDelta ?? 0) },
    { key: 'groups', icon: UserFilled, tone: 'purple', label: t('profile.groupChats'), value: formatNum(groupCount.value), trend: trendOf(s.groupsDelta), delta: Math.abs(s.groupsDelta ?? 0) },
    { key: 'online', icon: Clock, tone: 'orange', label: t('profile.onlineDuration'), value: onlineHours.value, unit: t('profile.hourUnit'), trend: trendOf(s.onlineDelta), delta: Math.abs(s.onlineDelta ?? 0) }
  ]
})

// Activity feed
const recentActivities = ref([])

const ACTIVITY_VISUAL = {
  message: { icon: ChatDotRound, tone: 'blue' },
  contact: { icon: Plus, tone: 'green' },
  group: { icon: UserFilled, tone: 'purple' },
  login: { icon: CircleCheck, tone: 'green' },
  profile_update: { icon: EditPen, tone: 'orange' }
}

const registeredAt = computed(() => {
  const c = userStore.currentUser?.createdAt
  if (!c) return '—'
  const d = new Date(c)
  if (isNaN(d.getTime())) return '—'
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
})

onMounted(async () => {
  await loadProfileData()
})

const loadProfileData = async () => {
  try {
    await userStore.loadUserProfile()
    if (userStore.currentUser?.id) {
      try {
        const statsResponse = await userAPI.getUserStats(userStore.currentUser.id)
        userStats.value = statsResponse.data
      } catch (e) {
        userStats.value = null
      }

      try {
        const response = await userAPI.getUserActivities(userStore.currentUser.id, 8)
        recentActivities.value = response.data.map(activity => {
          const type = (activity.activityType || '').toLowerCase()
          const visual = ACTIVITY_VISUAL[type] || { icon: Bell, tone: 'blue' }
          return {
            id: activity.id,
            type,
            icon: visual.icon,
            tone: visual.tone,
            text: activity.description || getActivityText(activity.activityType),
            time: activity.createdAt
          }
        })
      } catch (e) {}
    }
  } catch (e) {}
}

const getActivityText = (type) => {
  const keys = { message: 'activitySentMessage', contact: 'activityAddedContact', group: 'activityJoinedGroup', login: 'activityLoggedIn' }
  const key = keys[type.toLowerCase()]
  return key ? t(`profile.${key}`) : t('profile.activityRecorded')
}

const goBack = () => router.back()

const handleProfileUpdated = () => loadProfileData()

const formatTime = (time) => {
  const date = new Date(time); const now = new Date(); const diff = now - date
  const hours = Math.floor(diff / (1000 * 60 * 60))
  if (hours < 1) return t('profile.justNow')
  if (hours < 24) return t('profile.hoursAgo', { n: hours })
  return t('profile.daysAgo', { n: Math.floor(hours / 24) })
}
</script>

<style scoped>
/* ===== Page ===== */
.profile-page {
  position: relative;
  height: 100vh;
  box-sizing: border-box;
  padding: 0;
  background: #f4f6fb;
  overflow: hidden;
}

[data-theme="dark"] .profile-page {
  background: #14161c;
}

.drag-region {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 32px;
  -webkit-app-region: drag;
  z-index: 1000;
}

.no-drag {
  -webkit-app-region: no-drag;
}

.profile-shell {
  display: flex;
  gap: 0;
  height: 100%;
}

/* ===== Left identity sidebar ===== */
.profile-aside {
  position: relative;
  width: 248px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  padding: 38px 18px 18px;
  background: #ffffff;
  border-right: 1px solid #eef0f4;
  overflow: hidden;
}

[data-theme="dark"] .profile-aside {
  background: #1b1e26;
  border-right-color: #262a34;
}

.aside-back {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 10px;
  background: #f1f4f9;
  color: #8a93a5;
  cursor: pointer;
  transition: all 0.2s ease;
}

.aside-back:hover {
  background: #eef2ff;
  color: #4f8ef0;
}

[data-theme="dark"] .aside-back {
  background: #262a34;
  color: #aab2c0;
}

[data-theme="dark"] .aside-back:hover {
  background: rgba(79, 142, 240, 0.2);
  color: #9cc6f8;
}

.aside-identity {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 18px 8px 22px;
}

.identity-avatar {
  position: relative;
}

.identity-avatar .el-avatar {
  border: 3px solid #ffffff;
  box-shadow: 0 6px 18px -6px rgba(79, 142, 240, 0.35);
}

[data-theme="dark"] .identity-avatar .el-avatar {
  border-color: #232730;
}

.status-dot {
  position: absolute;
  bottom: 4px;
  right: 4px;
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: #c2c9d6;
  border: 3px solid #ffffff;
}

[data-theme="dark"] .status-dot {
  border-color: #1b1e26;
}

.status-dot.online {
  background: #22c55e;
}

.identity-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
}

.identity-name {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #1f2430;
}

[data-theme="dark"] .identity-name {
  color: #eef1f6;
}

.name-edit {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #aab2c0;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.name-edit:hover {
  background: #eef2ff;
  color: #4f8ef0;
}

.identity-handle {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: #8a93a5;
}

.identity-tagline {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: #aab2c0;
  text-align: center;
  max-width: 200px;
}

/* Nav */
.aside-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-top: 8px;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: none;
  border-radius: 12px;
  background: transparent;
  color: #6b7585;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  text-align: left;
}

.nav-item .el-icon {
  font-size: 18px;
}

.nav-item:hover {
  background: #f1f4f9;
  color: #1f2430;
}

.nav-item.active {
  background: #eef2ff;
  color: #4f8ef0;
}

.nav-item.active::before {
  content: "";
  position: absolute;
  left: 4px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 18px;
  border-radius: 2px;
  background: #4f8ef0;
}

[data-theme="dark"] .nav-item {
  color: #aab2c0;
}

[data-theme="dark"] .nav-item:hover {
  background: #262a34;
  color: #eef1f6;
}

[data-theme="dark"] .nav-item.active {
  background: rgba(79, 142, 240, 0.18);
  color: #9cc6f8;
}

/* Decoration */
.aside-decoration {
  margin-top: auto;
  display: flex;
  justify-content: center;
  align-items: flex-end;
  padding-top: 12px;
  opacity: 0.95;
  pointer-events: none;
}

/* ===== Right content ===== */
.profile-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #f7f9fd 0%, #f4f6fb 100%);
}

[data-theme="dark"] .profile-main {
  background: #14161c;
}

.main-body {
  flex: 1;
  overflow-y: auto;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.main-body::-webkit-scrollbar {
  width: 0;
  height: 0;
  display: none;
}

.tab-overview {
  max-width: 1120px;
  margin: 0 auto;
  padding: 34px 36px 44px;
}

/* Header */
.ov-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 22px;
}

.ov-title {
  margin: 0;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.4px;
  color: #1f2430;
}

[data-theme="dark"] .ov-title {
  color: #eef1f6;
}

.ov-greeting {
  margin: 6px 0 0;
  font-size: 14px;
  color: #8a93a5;
}

.ov-quote {
  position: relative;
  margin: 6px 0 0;
  padding: 0 20px;
  max-width: 300px;
  font-size: 13px;
  font-style: italic;
  line-height: 1.5;
  color: #aab2c0;
  text-align: right;
  flex-shrink: 0;
}

.ov-quote::before,
.ov-quote::after {
  position: absolute;
  font-size: 30px;
  font-style: normal;
  line-height: 1;
  color: #d7deea;
}

.ov-quote::before { content: "\201C"; left: 0; top: -6px; }
.ov-quote::after { content: "\201D"; right: 2px; bottom: -14px; }

/* Hero banner */
.hero-card {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 132px;
  padding: 26px 30px;
  border-radius: 20px;
  background: linear-gradient(120deg, #e8edfd 0%, #ebe8fb 55%, #f0ecfd 100%);
  overflow: hidden;
}

[data-theme="dark"] .hero-card {
  background: linear-gradient(120deg, #232a45 0%, #2a2742 100%);
}

.hero-text {
  position: relative;
  z-index: 1;
  max-width: 60%;
}

.hero-title {
  margin: 0;
  font-size: 19px;
  font-weight: 800;
  color: #2b3350;
}

[data-theme="dark"] .hero-title {
  color: #eef1f6;
}

.hero-sub {
  margin: 10px 0 0;
  font-size: 13.5px;
  line-height: 1.6;
  color: #6b7592;
}

[data-theme="dark"] .hero-sub {
  color: #aab2c0;
}

.hero-art {
  position: absolute;
  right: 20px;
  bottom: 0;
  display: flex;
  align-items: flex-end;
}

/* Stat cards */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-top: 18px;
}

.stat-card {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 18px 18px 16px;
  background: #ffffff;
  border: 1px solid #eef0f4;
  border-radius: 16px;
  box-shadow: 0 2px 12px rgba(20, 30, 60, 0.04);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 24px -10px rgba(30, 45, 90, 0.18);
}

[data-theme="dark"] .stat-card {
  background: #1e2230;
  border-color: #262a34;
  box-shadow: none;
}

.stat-icon {
  width: 42px;
  height: 42px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  font-size: 21px;
}

.stat-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.stat-label {
  font-size: 12.5px;
  color: #8a93a5;
}

.stat-value {
  margin-top: 3px;
  font-size: 25px;
  font-weight: 800;
  line-height: 1.1;
  color: #1f2430;
}

[data-theme="dark"] .stat-value {
  color: #eef1f6;
}

.stat-unit {
  font-size: 15px;
  font-weight: 700;
  font-style: normal;
  color: #8a93a5;
}

.stat-delta {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-top: 6px;
  font-size: 11.5px;
  font-weight: 600;
  color: #aab2c0;
}

.stat-delta .el-icon {
  font-size: 12px;
}

.stat-delta.up { color: #22c55e; }
.stat-delta.down { color: #ef4444; }
.stat-delta.flat { color: #aab2c0; }

/* Icon tones */
.blue { background: #e8f1fd; color: #3b82f6; }
.green { background: #e7f8ef; color: #22c55e; }
.purple { background: #f1ecfe; color: #8b5cf6; }
.orange { background: #fff2e3; color: #f59e0b; }

[data-theme="dark"] .blue { background: rgba(59, 130, 246, 0.16); color: #7eb0ff; }
[data-theme="dark"] .green { background: rgba(34, 197, 94, 0.16); color: #5ee19a; }
[data-theme="dark"] .purple { background: rgba(139, 92, 246, 0.16); color: #b69bff; }
[data-theme="dark"] .orange { background: rgba(245, 158, 11, 0.16); color: #ffc35c; }

/* Bottom columns */
.ov-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.08fr);
  gap: 16px;
  margin-top: 16px;
}

.panel {
  background: #ffffff;
  border: 1px solid #eef0f4;
  border-radius: 16px;
  padding: 20px 22px;
  box-shadow: 0 2px 12px rgba(20, 30, 60, 0.04);
}

[data-theme="dark"] .panel {
  background: #1e2230;
  border-color: #262a34;
  box-shadow: none;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.panel-title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: #1f2430;
}

[data-theme="dark"] .panel-title {
  color: #eef1f6;
}

.panel-link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border: none;
  background: transparent;
  color: #4f8ef0;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.2s ease;
}

.panel-link:hover { opacity: 0.7; }

/* Personal info rows */
.pi-rows {
  display: flex;
  flex-direction: column;
}

.pi-row {
  display: flex;
  align-items: center;
  gap: 13px;
  width: 100%;
  padding: 13px 8px;
  border: none;
  border-bottom: 1px solid #f1f3f7;
  background: transparent;
  cursor: pointer;
  text-align: left;
  border-radius: 8px;
  transition: background 0.15s ease;
}

.pi-row:last-child { border-bottom: none; }
.pi-row.static { cursor: default; }
.pi-row:not(.static):hover { background: #f7f9fd; }

[data-theme="dark"] .pi-row { border-bottom-color: #262a34; }
[data-theme="dark"] .pi-row:not(.static):hover { background: #232733; }

.pi-ic {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  font-size: 16px;
}

.pi-label {
  font-size: 13px;
  color: #8a93a5;
  width: 76px;
  flex-shrink: 0;
}

.pi-value {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13.5px;
  font-weight: 600;
  color: #1f2430;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  justify-content: flex-end;
  text-align: right;
}

[data-theme="dark"] .pi-value { color: #eef1f6; }

.dot-online {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c2c9d6;
  flex-shrink: 0;
}

.dot-online.on { background: #22c55e; }

.pi-arrow {
  font-size: 14px;
  color: #c2c9d6;
  flex-shrink: 0;
}

/* Activity list */
.act-list {
  display: flex;
  flex-direction: column;
}

.act-empty {
  padding: 18px 4px;
  font-size: 13px;
  color: #aab2c0;
}

.act-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 4px;
  border-bottom: 1px solid #f1f3f7;
}

.act-row:last-child { border-bottom: none; }

[data-theme="dark"] .act-row { border-bottom-color: #262a34; }

.act-text {
  flex: 1;
  min-width: 0;
  font-size: 13.5px;
  color: #2b3350;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

[data-theme="dark"] .act-text { color: #d7dbe4; }

.act-time {
  font-size: 12px;
  color: #aab2c0;
  flex-shrink: 0;
}

.act-type {
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  font-size: 14px;
}

/* ===== Transitions ===== */
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: all 0.28s cubic-bezier(0.2, 0.8, 0.2, 1);
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateY(10px);
}

.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

.animate-fade-in {
  animation: profileIn 0.4s cubic-bezier(0.2, 0.8, 0.2, 1);
}

@keyframes profileIn {
  from { opacity: 0; transform: scale(0.99); }
  to { opacity: 1; transform: scale(1); }
}

/* ===== Responsive ===== */
@media (max-width: 1080px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .ov-columns {
    grid-template-columns: 1fr;
  }
  .ov-quote { display: none; }
}

@media (max-width: 768px) {
  .profile-page {
    padding: 0;
    overflow-y: auto;
  }

  .drag-region { height: 44px; }

  .profile-shell {
    flex-direction: column;
    gap: 0;
    height: auto;
    min-height: 100vh;
  }

  .profile-aside {
    width: auto;
    padding: 44px 16px 12px;
    border-right: none;
    border-bottom: 1px solid #eef0f4;
  }

  .aside-nav {
    flex-direction: row;
    overflow-x: auto;
    padding-top: 12px;
  }

  .nav-item { flex-shrink: 0; }

  .aside-decoration { display: none; }

  .tab-overview { padding: 22px 16px 28px; }

  .stat-grid { grid-template-columns: repeat(2, 1fr); }
}

@media (max-width: 480px) {
  .stat-grid { grid-template-columns: 1fr; }
}
</style>

<template>
  <div class="social-overview">
    <!-- Header -->
    <header class="sec-header">
      <div class="sec-heading">
        <h1 class="sec-title">{{ $t('profile.social') }}</h1>
        <p class="sec-sub">{{ $t('profile.socialSub') }}</p>
      </div>
      <p class="so-quote">{{ $t('profile.heroQuote') }}</p>
    </header>

    <!-- Top row: friend count / friend requests / recent interactions -->
    <section class="sec-grid-top">
      <!-- Friend count -->
      <div class="panel">
        <div class="panel-head">
          <div class="head-left">
            <span class="head-ic blue"><el-icon><User /></el-icon></span>
            <h3 class="panel-title">{{ $t('profile.friendsTotal') }}</h3>
          </div>
        </div>
        <div class="fc-stat">
          <span class="dm-count">{{ friendCount }}</span>
          <span class="dm-count-label">{{ $t('profile.friendsUnit') }}</span>
        </div>
        <span class="fc-delta" :class="friendTrend">
          <template v-if="friendTrend === 'flat'">{{ $t('profile.vsYesterday') }} —</template>
          <template v-else>
            {{ $t('profile.vsYesterday') }}
            <el-icon><component :is="friendTrend === 'up' ? CaretTop : CaretBottom" /></el-icon>{{ Math.abs(contactsDelta) }}%
          </template>
        </span>
        <!-- Decorative trend line: no friend-count time series endpoint exists yet -->
        <div class="fc-spark" aria-hidden="true">
          <svg viewBox="0 0 240 64" preserveAspectRatio="none" width="100%" height="56">
            <defs>
              <linearGradient id="fcFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="#ffffff" stop-opacity="0.22" />
                <stop offset="100%" stop-color="#ffffff" stop-opacity="0" />
              </linearGradient>
            </defs>
            <path d="M0,50 C28,46 46,30 70,30 C98,30 110,42 138,35 C168,27 196,14 240,9 L240,64 L0,64 Z" fill="url(#fcFill)" />
            <path d="M0,50 C28,46 46,30 70,30 C98,30 110,42 138,35 C168,27 196,14 240,9" fill="none" stroke="#c4c7c8" stroke-width="2.5" stroke-linecap="round" />
          </svg>
        </div>
        <button class="panel-link foot" @click="goContacts">
          {{ $t('profile.viewAllFriends') }}<el-icon><ArrowRight /></el-icon>
        </button>
      </div>

      <!-- Friend requests -->
      <div class="panel">
        <div class="panel-head">
          <div class="head-left">
            <span class="head-ic orange"><el-icon><Bell /></el-icon></span>
            <h3 class="panel-title">{{ $t('profile.friendRequestsTitle') }}</h3>
          </div>
          <span v-if="pendingCount > 0" class="req-count">{{ pendingCount }}</span>
        </div>
        <div class="row-list">
          <div v-if="pendingRequests.length === 0" class="list-empty">{{ $t('profile.noFriendRequests') }}</div>
          <div v-for="r in pendingRequests" :key="r.id" class="row">
            <el-avatar :size="34" :src="r.fromAvatarUrl || defaultAvatar" />
            <div class="row-main">
              <span class="row-name">{{ r.fromNickname }}</span>
              <span class="row-sub">{{ r.message || '@' + r.fromUsername }}</span>
            </div>
            <div class="req-actions">
              <button
                class="req-btn accept"
                :disabled="processingId === r.id"
                :title="$t('contact.accept')"
                @click="handleAccept(r)"
              >
                <el-icon><Check /></el-icon>
              </button>
              <button
                class="req-btn reject"
                :disabled="processingId === r.id"
                :title="$t('contact.reject')"
                @click="handleReject(r)"
              >
                <el-icon><Close /></el-icon>
              </button>
            </div>
          </div>
        </div>
        <button v-if="pendingCount > pendingRequests.length" class="panel-link foot" @click="goContacts">
          {{ $t('profile.seeAll') }}<el-icon><ArrowRight /></el-icon>
        </button>
      </div>

      <!-- Recent interactions -->
      <div class="panel">
        <div class="panel-head">
          <div class="head-left">
            <span class="head-ic purple"><el-icon><ChatDotRound /></el-icon></span>
            <h3 class="panel-title">{{ $t('profile.recentInteractions') }}</h3>
          </div>
        </div>
        <div class="row-list">
          <div v-if="recentContacts.length === 0" class="list-empty">{{ $t('profile.noRecentInteractions') }}</div>
          <div
            v-for="c in recentContacts"
            :key="c.id"
            class="row clickable"
            @click="viewProfile(c)"
          >
            <span class="ri-avatar">
              <el-avatar :size="34" :src="c.avatar || c.avatarUrl || defaultAvatar" />
              <span class="online-dot" :class="{ on: c.isOnline }"></span>
            </span>
            <div class="row-main">
              <span class="row-name">{{ c.nickname }}</span>
              <span class="row-sub" :class="{ ok: c.isOnline }">{{ statusText(c) }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Bottom row: social preferences / blacklist -->
    <section class="sec-grid-bottom">
      <!-- Social preferences -->
      <div class="panel">
        <div class="panel-head">
          <div class="head-left">
            <span class="head-ic teal"><el-icon><Setting /></el-icon></span>
            <h3 class="panel-title">{{ $t('profile.socialPreferences') }}</h3>
          </div>
        </div>
        <div class="row-list">
          <div class="row pref">
            <div class="row-main">
              <span class="row-name">{{ $t('profile.showOnlineStatus') }}</span>
              <span class="row-sub">{{ $t('profile.showOnlineStatusDesc') }}</span>
            </div>
            <el-switch v-model="prefShowOnline" :loading="savingPref" @change="savePreferences" />
          </div>
          <div class="row pref">
            <div class="row-main">
              <span class="row-name">{{ $t('profile.showLastSeen') }}</span>
              <span class="row-sub">{{ $t('profile.showLastSeenDesc') }}</span>
            </div>
            <el-switch v-model="prefShowLastSeen" :loading="savingPref" @change="savePreferences" />
          </div>
          <div class="row pref">
            <div class="row-main">
              <span class="row-name">{{ $t('profile.friendVerify') }}</span>
              <span class="row-sub">{{ $t('profile.friendVerifyDesc') }}</span>
            </div>
            <el-switch v-model="prefFriendVerify" :loading="savingPref" @change="savePreferences" />
          </div>
        </div>
      </div>

      <!-- Blacklist -->
      <div class="panel">
        <div class="panel-head">
          <div class="head-left">
            <span class="head-ic red"><el-icon><CircleClose /></el-icon></span>
            <h3 class="panel-title">{{ $t('profile.blacklistManagement') }}</h3>
          </div>
          <span v-if="blacklist.length > 0" class="req-count red">{{ blacklist.length }}</span>
        </div>
        <div class="row-list" v-loading="loadingBlacklist">
          <div v-if="!loadingBlacklist && blacklist.length === 0" class="list-empty tall">
            {{ $t('profile.blacklistEmpty') }}
          </div>
          <div v-for="b in blacklist" :key="b.userId" class="row">
            <el-avatar :size="34" :src="resolveFileUrl(b.avatarUrl) || defaultAvatar" />
            <div class="row-main">
              <span class="row-name">{{ b.nickname }}</span>
              <span class="row-sub">@{{ b.username }}</span>
            </div>
            <button
              class="row-action"
              :disabled="removingId === b.userId"
              @click="handleUnblock(b)"
            >
              {{ $t('profile.removeAction') }}
            </button>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { mainLocation } from '@/router/navigation'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '@/stores/user'
import { useContactStore } from '@/stores/contact'
import { userAPI, contactAPI, resolveFileUrl } from '@/services/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  User, Bell, ChatDotRound, Setting, CircleClose,
  ArrowRight, Check, Close, CaretTop, CaretBottom
} from '@element-plus/icons-vue'

const { t } = useI18n()
const router = useRouter()
const userStore = useUserStore()
const contactStore = useContactStore()

const HOUR = 3600000
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'

const userId = computed(() => userStore.currentUser?.id)

// --- Friend count (stats with store fallback) ---
const userStats = ref(null)
const friendCount = computed(() => userStats.value?.contactCount ?? contactStore.contacts.length)
const contactsDelta = computed(() => userStats.value?.contactsDelta ?? null)
const friendTrend = computed(() =>
  contactsDelta.value == null ? 'flat' : contactsDelta.value > 0 ? 'up' : contactsDelta.value < 0 ? 'down' : 'flat'
)

// --- Friend requests ---
const pendingRequests = computed(() => contactStore.pendingRequests.slice(0, 3))
const pendingCount = computed(() => contactStore.pendingRequestCount)
const processingId = ref(null)

const handleAccept = async (req) => {
  if (!userId.value) return
  processingId.value = req.id
  try {
    await contactStore.acceptRequest(req.id, userId.value)
    ElMessage.success(t('contact.requestAccepted'))
  } catch (e) {
    ElMessage.error(t('contact.acceptFailed'))
  } finally {
    processingId.value = null
  }
}

const handleReject = async (req) => {
  if (!userId.value) return
  processingId.value = req.id
  try {
    await contactStore.rejectRequest(req.id, userId.value)
    ElMessage.success(t('contact.requestRejected'))
  } catch (e) {
    ElMessage.error(t('contact.rejectFailed'))
  } finally {
    processingId.value = null
  }
}

// --- Recent interactions (online contacts first) ---
const recentContacts = computed(() => {
  const online = contactStore.contacts.filter(c => c.isOnline)
  const offline = contactStore.contacts.filter(c => !c.isOnline)
  return [...online, ...offline].slice(0, 5)
})

const statusText = (c) => {
  if (c.isOnline) return t('profile.online')
  if (!c.lastSeen) return t('profile.offline')
  return relTime(c.lastSeen)
}

const relTime = (ts) => {
  const hours = Math.floor((Date.now() - new Date(ts).getTime()) / HOUR)
  if (hours < 1) return t('profile.justNow')
  if (hours < 24) return t('profile.hoursAgo', { n: hours })
  return t('profile.daysAgo', { n: Math.floor(hours / 24) })
}

// --- Social preferences ---
const prefShowOnline = ref(true)
const prefShowLastSeen = ref(true)
const prefFriendVerify = ref(true)
const savingPref = ref(false)
let prevPrefs = { online: true, lastSeen: true, verify: true }

const initPrefs = () => {
  const u = userStore.currentUser || {}
  prefShowOnline.value = u.showOnlineStatus ?? true
  prefShowLastSeen.value = u.showLastSeen ?? true
  prefFriendVerify.value = (u.friendRequestMode ?? 'VERIFY') === 'VERIFY'
  snapshotPrefs()
}

const snapshotPrefs = () => {
  prevPrefs = {
    online: prefShowOnline.value,
    lastSeen: prefShowLastSeen.value,
    verify: prefFriendVerify.value
  }
}

const restorePrefs = () => {
  prefShowOnline.value = prevPrefs.online
  prefShowLastSeen.value = prevPrefs.lastSeen
  prefFriendVerify.value = prevPrefs.verify
}

const savePreferences = async () => {
  if (!userId.value) return
  savingPref.value = true
  const settings = {
    showOnlineStatus: prefShowOnline.value,
    showLastSeen: prefShowLastSeen.value,
    friendRequestMode: prefFriendVerify.value ? 'VERIFY' : 'DIRECT'
  }
  try {
    await userAPI.updatePrivacySettings(userId.value, settings)
    userStore.updatePrivacySettings({
      showOnlineStatus: prefShowOnline.value,
      showLastSeen: prefShowLastSeen.value,
      showEmail: userStore.currentUser?.showEmail,
      showPhone: userStore.currentUser?.showPhone,
      friendRequestMode: settings.friendRequestMode
    })
    snapshotPrefs()
    ElMessage.success(t('profile.updateSuccess'))
  } catch (e) {
    restorePrefs()
    ElMessage.error(t('profile.updateFailed'))
  } finally {
    savingPref.value = false
  }
}

// --- Blacklist ---
const blacklist = ref([])
const loadingBlacklist = ref(false)
const removingId = ref(null)

const fetchBlacklist = async () => {
  if (!userId.value) return
  loadingBlacklist.value = true
  try {
    const res = await contactAPI.getBlacklist(userId.value)
    blacklist.value = res.data || []
  } catch (e) {
    blacklist.value = []
  } finally {
    loadingBlacklist.value = false
  }
}

const handleUnblock = async (b) => {
  try {
    await ElMessageBox.confirm(
      t('profile.confirmUnblock', { name: b.nickname }),
      t('profile.blacklistManagement'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )
  } catch (e) {
    return
  }
  removingId.value = b.userId
  try {
    await contactAPI.unblockUser(userId.value, b.userId)
    blacklist.value = blacklist.value.filter(x => x.userId !== b.userId)
    ElMessage.success(t('profile.unblockSuccess'))
  } catch (e) {
    ElMessage.error(t('profile.unblockFailed'))
  } finally {
    removingId.value = null
  }
}

// --- Navigation ---
const goContacts = () => router.push(mainLocation('contacts'))
const viewProfile = (c) => {
  if (c?.id) router.push(`/user/${c.id}`)
}

onMounted(async () => {
  if (!userId.value) return
  if (contactStore.contacts.length === 0) {
    try { await contactStore.fetchContacts(userId.value) } catch (e) {}
  }
  try { await contactStore.fetchPendingRequests(userId.value) } catch (e) {}
  try {
    const res = await userAPI.getUserStats(userId.value)
    userStats.value = res.data
  } catch (e) {}
  await fetchBlacklist()
  initPrefs()
})
</script>

<style scoped>
.social-overview {
  display: flex;
  flex-direction: column;
}

/* ===== Header ===== */
.sec-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 22px;
}

.sec-title {
  margin: 0;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.4px;
  color: #e4e1e6;
}

.sec-sub {
  margin: 6px 0 0;
  font-size: 14px;
  color: #8e9192;
}

.so-quote {
  position: relative;
  margin: 6px 0 0;
  padding: 0 20px;
  max-width: 300px;
  font-size: 13px;
  font-style: italic;
  line-height: 1.5;
  color: #6f7274;
  text-align: right;
  flex-shrink: 0;
}

.so-quote::before,
.so-quote::after {
  position: absolute;
  font-size: 30px;
  font-style: normal;
  line-height: 1;
  color: #353438;
}

.so-quote::before { content: "\201C"; left: 0; top: -6px; }
.so-quote::after { content: "\201D"; right: 2px; bottom: -14px; }

/* ===== Grids ===== */
.sec-grid-top {
  display: grid;
  grid-template-columns: 1.2fr 1fr 1fr;
  gap: 16px;
}

.sec-grid-bottom {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
  margin-top: 16px;
}

/* ===== Panel ===== */
.panel {
  display: flex;
  flex-direction: column;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.25);
  border-radius: 12px;
  padding: 20px 22px;
  box-shadow: none;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.panel:hover {
  transform: translateY(-2px);
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.03);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.head-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.head-ic {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  font-size: 16px;
}

.panel-title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: #e4e1e6;
}

.panel-link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 0;
  border: none;
  background: transparent;
  color: #ffffff;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.2s ease;
}

.panel-link:hover { opacity: 0.7; }

.panel-link.foot {
  margin-top: auto;
  padding-top: 14px;
  align-self: flex-start;
}

/* ===== Icon tones ===== */
.blue { background: rgba(255, 255, 255, 0.06); color: #c4c7c8; }
.green { background: rgba(255, 255, 255, 0.06); color: #ffffff; }
.purple { background: rgba(255, 255, 255, 0.06); color: #c4c7c8; }
.orange { background: rgba(255, 255, 255, 0.06); color: #c4c7c8; }
.teal { background: rgba(255, 255, 255, 0.06); color: #c4c7c8; }
.red { background: rgba(242, 184, 181, 0.12); color: #f2b8b5; }

/* ===== Friend count ===== */
.fc-stat {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.dm-count {
  font-size: 38px;
  font-weight: 800;
  line-height: 1;
  color: #e4e1e6;
}

.dm-count-label {
  font-size: 13px;
  color: #8e9192;
}

.fc-delta {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-top: 6px;
  font-size: 11.5px;
  font-weight: 600;
  color: #6f7274;
}

.fc-delta .el-icon { font-size: 12px; }
.fc-delta.up { color: #ffffff; }
.fc-delta.down { color: #f2b8b5; }
.fc-delta.flat { color: #6f7274; }

.fc-spark {
  margin-top: 12px;
  line-height: 0;
}

/* ===== Rows ===== */
.row-list {
  display: flex;
  flex-direction: column;
}

.row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 0;
  border-bottom: 1px solid rgba(68, 71, 72, 0.18);
}

.row:last-child { border-bottom: none; }

.row.clickable {
  cursor: pointer;
  padding-left: 8px;
  padding-right: 8px;
  margin-left: -8px;
  margin-right: -8px;
  border-radius: 8px;
}

.row.clickable:hover { background: #222225; }

.row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.row-name {
  font-size: 13.5px;
  font-weight: 600;
  color: #e4e1e6;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row-sub {
  font-size: 12px;
  color: #8e9192;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row-sub.ok { color: #ffffff; }

.list-empty {
  padding: 18px 4px;
  font-size: 13px;
  color: #6f7274;
}

.list-empty.tall { padding: 32px 4px; text-align: center; }

/* ===== Friend requests ===== */
.req-count {
  flex-shrink: 0;
  min-width: 22px;
  height: 22px;
  padding: 0 7px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  color: #c4c7c8;
  font-size: 12px;
  font-weight: 700;
}

.req-count.red { background: rgba(242, 184, 181, 0.12); color: #f2b8b5; }

.req-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.req-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: opacity 0.2s ease, transform 0.15s ease;
}

.req-btn:disabled { opacity: 0.5; cursor: default; }
.req-btn:not(:disabled):hover { transform: scale(1.06); }
.req-btn.accept { background: rgba(255, 255, 255, 0.06); color: #ffffff; }
.req-btn.reject { background: rgba(242, 184, 181, 0.12); color: #f2b8b5; }

/* ===== Row action (unblock) ===== */
.row-action {
  flex-shrink: 0;
  border: none;
  background: transparent;
  color: #6f7274;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: color 0.2s ease;
}

.row-action:hover { color: #f2b8b5; }
.row-action:disabled { opacity: 0.5; cursor: default; }

/* ===== Recent interactions ===== */
.ri-avatar {
  position: relative;
  flex-shrink: 0;
  line-height: 0;
}

.online-dot {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #6f7274;
  border: 2px solid #131316;
}

.online-dot.on { background: #ffffff; }

/* ===== Preferences ===== */
.row.pref { align-items: center; }

/* ===== Responsive ===== */
@media (max-width: 1080px) {
  .sec-grid-top { grid-template-columns: 1fr 1fr; }
  .sec-grid-bottom { grid-template-columns: 1fr; }
  .so-quote { display: none; }
}

@media (max-width: 720px) {
  .sec-grid-top { grid-template-columns: 1fr; }
}
</style>

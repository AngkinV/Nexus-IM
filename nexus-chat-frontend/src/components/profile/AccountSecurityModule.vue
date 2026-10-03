<template>
  <div class="security-overview">
    <!-- Header -->
    <header class="sec-header">
      <div class="sec-heading">
        <h1 class="sec-title">{{ $t('profile.securityOverview') }}</h1>
        <p class="sec-sub">{{ $t('profile.securityOverviewSub') }}</p>
      </div>
      <button
        class="sec-refresh"
        :class="{ spinning: refreshing }"
        :title="$t('common.refresh')"
        @click="refresh"
      >
        <el-icon><Refresh /></el-icon>
      </button>
    </header>

    <!-- Top row: devices / password strength / two-step -->
    <section class="sec-grid-top">
      <!-- Login devices -->
      <div class="panel">
        <div class="panel-head">
          <div class="head-left">
            <span class="head-ic blue"><el-icon><Monitor /></el-icon></span>
            <h3 class="panel-title">{{ $t('profile.deviceManagement') }}</h3>
          </div>
        </div>
        <div class="dm-stat">
          <span class="dm-count">{{ devices.length }}</span>
          <span class="dm-count-label">{{ $t('profile.devicesLoggedIn') }}</span>
        </div>
        <div class="row-list">
          <div v-if="devices.length === 0" class="empty-row">{{ $t('profile.noDevices') }}</div>
          <div v-for="d in devices" :key="d.id" class="row">
            <span class="row-ic" :class="d.tone"><el-icon><component :is="d.icon" /></el-icon></span>
            <div class="row-main">
              <span class="row-name">{{ d.name }}</span>
              <span class="row-sub">{{ deviceMeta(d) }}</span>
            </div>
            <span v-if="d.current" class="badge-current">{{ $t('profile.currentSession') }}</span>
            <button v-else class="row-action" @click="revokeDevice(d)">{{ $t('profile.logoutDevice') }}</button>
          </div>
        </div>
        <button
          v-if="devices.length > 1"
          class="panel-link foot"
          @click="logoutOthers"
        >
          {{ $t('profile.logoutAll') }}<el-icon><ArrowRight /></el-icon>
        </button>
      </div>

      <!-- Password strength -->
      <div class="panel">
        <div class="panel-head">
          <div class="head-left">
            <span class="head-ic green"><el-icon><Key /></el-icon></span>
            <h3 class="panel-title">{{ $t('profile.passwordSecurityLevel') }}</h3>
          </div>
        </div>
        <div class="gauge-wrap">
          <el-progress
            type="dashboard"
            :percentage="securityScore"
            :width="124"
            :stroke-width="9"
            :color="scoreColor"
          >
            <template #default>
              <div class="gauge-center">
                <span class="gauge-grade" :style="{ color: scoreColor }">{{ scoreGrade }}</span>
                <span class="gauge-score">{{ securityScore }}/100</span>
              </div>
            </template>
          </el-progress>
          <p v-if="lastChangedDays !== null" class="gauge-hint">
            {{ $t('profile.lastChangedDays', { n: lastChangedDays }) }}
          </p>
        </div>
        <button class="panel-link foot" @click="showChangePasswordDialog = true">
          {{ $t('profile.changePassword') }}<el-icon><ArrowRight /></el-icon>
        </button>
      </div>

      <!-- Two-step verification -->
      <div class="panel">
        <div class="panel-head">
          <div class="head-left">
            <span class="head-ic purple"><el-icon><Lock /></el-icon></span>
            <h3 class="panel-title">{{ $t('profile.twoFactorAuth') }}</h3>
          </div>
        </div>
        <div class="ts-status" :class="{ off: !twoFactorEnabled }">
          <span class="ts-badge" :class="{ off: !twoFactorEnabled }">
            <el-icon><component :is="twoFactorEnabled ? CircleCheck : Lock" /></el-icon>
          </span>
          <div class="ts-status-text">
            <span class="ts-state" :class="{ off: !twoFactorEnabled }">
              {{ twoFactorEnabled ? $t('common.enabled') : $t('common.disabled') }}
            </span>
            <span class="ts-desc">
              {{ twoFactorEnabled ? $t('profile.twoStepActiveDesc') : $t('profile.twoStepDisabledDesc') }}
            </span>
          </div>
        </div>
        <div class="ts-action-row">
          <span class="ts-toggle-label">{{ $t('profile.twoFactorAuth') }}</span>
          <el-button
            v-if="twoFactorEnabled"
            size="small"
            :loading="twoFactorBusy"
            @click="disableTwoFactor"
          >{{ $t('profile.disableAction') }}</el-button>
          <el-button
            v-else
            type="primary"
            size="small"
            :loading="twoFactorBusy"
            @click="openTwoFactorSetup"
          >{{ $t('profile.enableAction') }}</el-button>
        </div>
      </div>
    </section>

    <!-- Bottom row: recent activity / recommendations -->
    <section class="sec-grid-bottom">
      <!-- Recent login activity -->
      <div class="panel">
        <div class="panel-head">
          <div class="head-left">
            <span class="head-ic orange"><el-icon><Clock /></el-icon></span>
            <h3 class="panel-title">{{ $t('profile.recentLoginActivity') }}</h3>
          </div>
          <button
            v-if="!historyExpanded"
            class="panel-link"
            @click="showFullHistory"
          >
            {{ $t('profile.viewFullHistory') }}<el-icon><ArrowRight /></el-icon>
          </button>
        </div>
        <div class="row-list">
          <div v-if="loginActivity.length === 0" class="empty-row">{{ $t('profile.noLoginHistory') }}</div>
          <div v-for="a in loginActivity" :key="a.id" class="row">
            <span class="status-dot-ic" :class="a.ok ? 'success' : 'failed'">
              <el-icon><component :is="a.ok ? Check : Warning" /></el-icon>
            </span>
            <div class="row-main">
              <span class="row-name">{{ a.label }}</span>
              <span class="row-sub" :class="{ ok: a.ok, fail: !a.ok }">
                {{ a.ok ? $t('profile.loginSuccess') : $t('profile.loginFailed') }}
              </span>
            </div>
            <span class="row-time">{{ relTime(a.ts) }}</span>
          </div>
        </div>
      </div>

      <!-- Security recommendations -->
      <div class="panel">
        <div class="panel-head">
          <div class="head-left">
            <span class="head-ic teal"><el-icon><CircleCheck /></el-icon></span>
            <h3 class="panel-title">{{ $t('profile.securityTips') }}</h3>
          </div>
        </div>
        <div class="row-list">
          <div v-for="r in recommendations" :key="r.id" class="row">
            <span class="rec-ic" :class="r.done ? 'done' : 'todo'">
              <el-icon><component :is="r.done ? CircleCheck : WarningFilled" /></el-icon>
            </span>
            <span class="row-name" :class="{ muted: r.done }">{{ r.text }}</span>
            <button v-if="!r.done" class="rec-action" @click="applyRecommendation(r)">
              {{ $t('profile.goSetup') }}
            </button>
          </div>
        </div>
      </div>
    </section>

    <!-- Change Password Dialog -->
    <el-dialog
      v-model="showChangePasswordDialog"
      :title="$t('profile.changePassword')"
      width="380px"
      class="security-dialog"
      append-to-body
    >
      <el-form :model="passwordForm" label-position="top">
        <el-form-item :label="$t('profile.currentPassword')">
          <el-input
            v-model="passwordForm.currentPassword"
            type="password"
            show-password
            :placeholder="$t('profile.enterCurrentPassword')"
          />
        </el-form-item>
        <el-form-item :label="$t('profile.newPassword')">
          <el-input
            v-model="passwordForm.newPassword"
            type="password"
            show-password
            :placeholder="$t('profile.enterNewPassword')"
          />
          <div v-if="passwordForm.newPassword" class="strength-meter">
            <el-progress
              :percentage="passwordStrength"
              :color="getStrengthColor(passwordStrength)"
              :show-text="false"
              :stroke-width="6"
            />
            <span class="strength-value" :class="strengthClass">{{ strengthText }}</span>
          </div>
        </el-form-item>
        <el-form-item :label="$t('profile.confirmNewPassword')">
          <el-input
            v-model="passwordForm.confirmPassword"
            type="password"
            show-password
            :placeholder="$t('profile.confirmNewPasswordPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showChangePasswordDialog = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" @click="changePassword" :loading="isChangingPassword">
          {{ $t('profile.updatePassword') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Two-factor setup dialog -->
    <el-dialog
      v-model="showTwoFactorDialog"
      :title="$t('profile.twoFactorSetup')"
      width="420px"
      append-to-body
      @closed="resetTwoFactorDialog"
    >
      <div v-if="twoFactorStep === 'setup'" class="tfa-setup">
        <p class="tfa-hint">{{ $t('profile.twoFactorSetupHint') }}</p>
        <div class="tfa-secret">
          <span class="tfa-secret-label">{{ $t('profile.secretKey') }}</span>
          <code class="tfa-secret-key">{{ setupSecret }}</code>
          <button class="tfa-copy" @click="copySecret">
            <el-icon><CopyDocument /></el-icon>
          </button>
        </div>
        <el-input
          v-model="authCode"
          maxlength="6"
          :placeholder="$t('profile.enterAuthCode')"
          class="tfa-code-input"
          @keyup.enter="verifyTwoFactorCode"
        />
      </div>

      <div v-else class="tfa-codes">
        <p class="tfa-hint">{{ $t('profile.backupCodesHint') }}</p>
        <div class="tfa-code-grid">
          <code v-for="c in backupCodes" :key="c" class="tfa-backup-code">{{ c }}</code>
        </div>
      </div>

      <template #footer>
        <template v-if="twoFactorStep === 'setup'">
          <el-button @click="showTwoFactorDialog = false">{{ $t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="verifying" @click="verifyTwoFactorCode">
            {{ $t('profile.verifyEnable') }}
          </el-button>
        </template>
        <el-button v-else type="primary" @click="showTwoFactorDialog = false">
          {{ $t('profile.savedBackupCodes') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useUserStore } from '@/stores/user'
import { authAPI, securityAPI } from '@/services/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import {
  Key, Lock, Monitor, Clock, Iphone, Platform, Refresh, ArrowRight,
  CircleCheck, Check, Warning, WarningFilled, CopyDocument
} from '@element-plus/icons-vue'

const { t } = useI18n()
const userStore = useUserStore()

const DAY = 86400000
const HOUR = 3600000

const DEVICE_VISUAL = {
  desktop: { icon: Monitor, tone: 'blue' },
  mobile: { icon: Iphone, tone: 'green' },
  tablet: { icon: Platform, tone: 'purple' },
  unknown: { icon: Monitor, tone: 'blue' }
}

// --- Real state, loaded from the backend ---
const devices = ref([])
const loginActivity = ref([])
const securityScore = ref(0)
const passwordChangedAt = ref(null)
const twoFactorEnabled = ref(false)
const twoFactorBusy = ref(false)
const historyExpanded = ref(false)

// Two-factor setup dialog state
const showTwoFactorDialog = ref(false)
const twoFactorStep = ref('setup') // 'setup' | 'codes'
const setupSecret = ref('')
const setupOtpauth = ref('')
const authCode = ref('')
const verifying = ref(false)
const backupCodes = ref([])

const userId = computed(() => userStore.currentUser?.id)

const lastChangedDays = computed(() => {
  if (!passwordChangedAt.value) return null
  const ms = Date.now() - new Date(passwordChangedAt.value).getTime()
  if (isNaN(ms)) return null
  return Math.max(0, Math.floor(ms / DAY))
})

const scoreColor = computed(() =>
  securityScore.value >= 70 ? '#22c55e' : securityScore.value >= 40 ? '#e6a23c' : '#f56c6c'
)
const scoreGrade = computed(() =>
  securityScore.value >= 70 ? t('profile.strengthStrong')
    : securityScore.value >= 40 ? t('profile.strengthMedium')
      : t('profile.strengthWeak')
)

const recommendations = computed(() => {
  const list = []
  if (securityScore.value >= 70) {
    list.push({ id: 'pwd', done: true, text: t('profile.recPasswordGood') })
  } else {
    list.push({ id: 'pwd', done: false, text: t('profile.recImprovePassword'), action: 'password' })
  }
  if (twoFactorEnabled.value) {
    list.push({ id: '2fa', done: true, text: t('profile.recTwoStepOn') })
  } else {
    list.push({ id: '2fa', done: false, text: t('profile.recEnableTwoStep'), action: 'twofactor' })
  }
  return list
})

const relTime = (ts) => {
  if (!ts) return ''
  const hours = Math.floor((Date.now() - new Date(ts).getTime()) / HOUR)
  if (isNaN(hours)) return ''
  if (hours < 1) return t('profile.justNow')
  if (hours < 24) return t('profile.hoursAgo', { n: hours })
  return t('profile.daysAgo', { n: Math.floor(hours / 24) })
}

const deviceMeta = (d) => {
  const parts = []
  if (d.location) parts.push(d.location)
  parts.push(d.current ? t('profile.online') : relTime(d.ts))
  return parts.join(' · ')
}

const mapSession = (s) => {
  const visual = DEVICE_VISUAL[s.deviceType] || DEVICE_VISUAL.unknown
  return {
    id: s.id,
    name: s.deviceName || s.deviceType || '—',
    location: s.location || null,
    current: !!s.isCurrent,
    ts: s.lastActive,
    icon: visual.icon,
    tone: visual.tone
  }
}

const mapHistory = (h) => ({
  id: h.id,
  label: [h.device, h.browser].filter(Boolean).join(' · ') || '—',
  ok: !!h.success,
  ts: h.createdAt
})

const loadSessions = async () => {
  if (!userId.value) return
  try {
    const { data } = await securityAPI.getSessions(userId.value)
    devices.value = (data || []).map(mapSession)
  } catch (e) { devices.value = [] }
}

const loadHistory = async () => {
  if (!userId.value) return
  try {
    const { data } = await securityAPI.getLoginHistory(userId.value, historyExpanded.value ? 50 : 10)
    loginActivity.value = (data || []).map(mapHistory)
  } catch (e) { loginActivity.value = [] }
}

const loadSecurity = async () => {
  if (!userId.value) return
  try {
    const { data } = await securityAPI.getSecuritySettings(userId.value)
    securityScore.value = data.passwordStrength ?? 0
    passwordChangedAt.value = data.passwordChangedAt ?? null
    twoFactorEnabled.value = !!data.twoFactorEnabled
  } catch (e) { /* keep defaults */ }
}

const loadAll = () => Promise.all([loadSessions(), loadHistory(), loadSecurity()])

onMounted(loadAll)

const refreshing = ref(false)
const refresh = async () => {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await loadAll()
  } finally {
    setTimeout(() => { refreshing.value = false }, 400)
  }
}

const revokeDevice = async (d) => {
  if (!userId.value) return
  try {
    await securityAPI.revokeSession(userId.value, d.id)
    await loadSessions()
  } catch (e) {
    ElMessage.error(t('common.operationFailed'))
  }
}

const logoutOthers = async () => {
  if (!userId.value) return
  try {
    await securityAPI.revokeOtherSessions(userId.value)
    await loadSessions()
  } catch (e) {
    ElMessage.error(t('common.operationFailed'))
  }
}

const openTwoFactorSetup = async () => {
  if (!userId.value) return
  twoFactorBusy.value = true
  try {
    const { data } = await securityAPI.setupTwoFactor(userId.value)
    setupSecret.value = data.secret
    setupOtpauth.value = data.otpauthUri
    authCode.value = ''
    twoFactorStep.value = 'setup'
    showTwoFactorDialog.value = true
  } catch (e) {
    ElMessage.error(t('profile.twoFactorSetupFailed'))
  } finally {
    twoFactorBusy.value = false
  }
}

const verifyTwoFactorCode = async () => {
  if (!userId.value || verifying.value) return
  if (!/^\d{6}$/.test((authCode.value || '').trim())) {
    ElMessage.warning(t('profile.enterAuthCode'))
    return
  }
  verifying.value = true
  try {
    const { data } = await securityAPI.verifyTwoFactor(userId.value, authCode.value.trim())
    twoFactorEnabled.value = true
    backupCodes.value = data.backupCodes || []
    twoFactorStep.value = 'codes'
    ElMessage.success(t('profile.twoFactorEnabledMsg'))
  } catch (error) {
    const key = error?.response?.data?.message
    const msg = key === 'error.auth.2fa.invalid.code'
      ? t('profile.twoFactorInvalidCode')
      : t('common.operationFailed')
    ElMessage.error(msg)
  } finally {
    verifying.value = false
  }
}

const disableTwoFactor = async () => {
  if (!userId.value) return
  try {
    await ElMessageBox.confirm(t('profile.confirmDisable2fa'), t('profile.twoFactorAuth'), {
      confirmButtonText: t('profile.disableAction'),
      cancelButtonText: t('common.cancel'),
      type: 'warning'
    })
  } catch (e) {
    return // user cancelled
  }
  twoFactorBusy.value = true
  try {
    const { data } = await securityAPI.disableTwoFactor(userId.value)
    twoFactorEnabled.value = !!data.twoFactorEnabled
    ElMessage.success(t('profile.twoFactorDisabledMsg'))
  } catch (e) {
    ElMessage.error(t('common.operationFailed'))
  } finally {
    twoFactorBusy.value = false
  }
}

const copySecret = async () => {
  try {
    await navigator.clipboard.writeText(setupSecret.value)
    ElMessage.success(t('profile.copied'))
  } catch (e) { /* clipboard unavailable */ }
}

const resetTwoFactorDialog = () => {
  twoFactorStep.value = 'setup'
  setupSecret.value = ''
  setupOtpauth.value = ''
  authCode.value = ''
  backupCodes.value = []
}

const showFullHistory = async () => {
  historyExpanded.value = true
  await loadHistory()
}

const applyRecommendation = (r) => {
  if (r.action === 'password') {
    showChangePasswordDialog.value = true
  } else if (r.action === 'twofactor') {
    openTwoFactorSetup()
  }
}

// --- Change password (live) ---
const showChangePasswordDialog = ref(false)
const isChangingPassword = ref(false)
const passwordForm = ref({
  currentPassword: '',
  newPassword: '',
  confirmPassword: ''
})

// Live strength of the typed new password (client-side heuristic).
const passwordStrength = computed(() => {
  const p = passwordForm.value.newPassword || ''
  if (!p) return 0
  let score = 0
  if (p.length >= 8) score += 30
  if (p.length >= 12) score += 15
  if (/[a-z]/.test(p) && /[A-Z]/.test(p)) score += 20
  if (/\d/.test(p)) score += 20
  if (/[^A-Za-z0-9]/.test(p)) score += 15
  return Math.min(100, score)
})

const strengthClass = computed(() => {
  if (passwordStrength.value < 40) return 'weak'
  if (passwordStrength.value < 70) return 'medium'
  return 'strong'
})

const strengthText = computed(() => {
  if (passwordStrength.value < 40) return t('profile.strengthWeak')
  if (passwordStrength.value < 70) return t('profile.strengthMedium')
  return t('profile.strengthStrong')
})

const getStrengthColor = (strength) => {
  if (strength < 40) return '#f56c6c'
  if (strength < 70) return '#e6a23c'
  return '#14b8a6'
}

// Map backend BusinessException keys to friendly i18n messages.
const PASSWORD_ERROR_KEYS = {
  'error.auth.password.incorrect': 'profile.currentPasswordWrong',
  'error.auth.password.too.short': 'profile.passwordTooShort',
  'error.auth.password.same': 'profile.passwordSame'
}

const changePassword = async () => {
  const { currentPassword, newPassword, confirmPassword } = passwordForm.value

  if (!currentPassword || !newPassword) {
    ElMessage.warning(t('profile.fillAllFields'))
    return
  }
  if (newPassword !== confirmPassword) {
    ElMessage.error(t('profile.passwordsDoNotMatch'))
    return
  }
  if (newPassword.length < 8) {
    ElMessage.error(t('profile.passwordTooShort'))
    return
  }

  if (!userId.value) {
    ElMessage.error(t('auth.pleaseLogin'))
    return
  }

  isChangingPassword.value = true
  try {
    await authAPI.changePassword(userId.value, currentPassword, newPassword)
    ElMessage.success(t('profile.passwordChanged'))
    showChangePasswordDialog.value = false
    passwordForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
    // Reflect the new strength + change time on the gauge.
    await loadSecurity()
  } catch (error) {
    const key = error?.response?.data?.message
    const mapped = PASSWORD_ERROR_KEYS[key]
    ElMessage.error(mapped ? t(mapped) : t('profile.passwordChangeFailed'))
  } finally {
    isChangingPassword.value = false
  }
}
</script>

<style scoped>
.security-overview {
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
  color: #1f2430;
}

.sec-sub {
  margin: 6px 0 0;
  font-size: 14px;
  color: #8a93a5;
}

.sec-refresh {
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #eef0f4;
  border-radius: 50%;
  background: #ffffff;
  color: #8a93a5;
  cursor: pointer;
  transition: all 0.2s ease;
}

.sec-refresh:hover {
  color: #4f8ef0;
  border-color: #dbe3ff;
}

.sec-refresh.spinning .el-icon {
  animation: secSpin 0.6s linear infinite;
}

@keyframes secSpin {
  to { transform: rotate(360deg); }
}

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

/* ===== Panel (mirrors overview) ===== */
.panel {
  display: flex;
  flex-direction: column;
  background: #ffffff;
  border: 1px solid #eef0f4;
  border-radius: 16px;
  padding: 20px 22px;
  box-shadow: 0 2px 12px rgba(20, 30, 60, 0.04);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.panel:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 24px -10px rgba(30, 45, 90, 0.18);
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
  color: #1f2430;
}

.panel-link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 0;
  border: none;
  background: transparent;
  color: #4f8ef0;
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
.blue { background: #e8f1fd; color: #3b82f6; }
.green { background: #e7f8ef; color: #22c55e; }
.purple { background: #f1ecfe; color: #8b5cf6; }
.orange { background: #fff2e3; color: #f59e0b; }
.teal { background: #e6f7f4; color: #14b8a6; }

/* ===== Device count ===== */
.dm-stat {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
}

.dm-count {
  font-size: 38px;
  font-weight: 800;
  line-height: 1;
  color: #1f2430;
}

.dm-count-label {
  font-size: 13px;
  color: #8a93a5;
}

/* ===== Generic rows ===== */
.row-list {
  display: flex;
  flex-direction: column;
}

.empty-row {
  padding: 16px 2px;
  font-size: 13px;
  color: #aab2c0;
}

.row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 0;
  border-bottom: 1px solid #f1f3f7;
}

.row:last-child { border-bottom: none; }

.row-ic {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  font-size: 15px;
}

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
  color: #1f2430;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row-name.muted { color: #8a93a5; }

.row-sub {
  font-size: 12px;
  color: #8a93a5;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row-sub.ok { color: #22c55e; }
.row-sub.fail { color: #ef4444; }

.row-time {
  font-size: 12px;
  color: #aab2c0;
  flex-shrink: 0;
}

.badge-current {
  flex-shrink: 0;
  padding: 2px 9px;
  border-radius: 999px;
  background: #e7f8ef;
  color: #22c55e;
  font-size: 10.5px;
  font-weight: 700;
}

.row-action {
  flex-shrink: 0;
  border: none;
  background: transparent;
  color: #aab2c0;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: color 0.2s ease;
}

.row-action:hover { color: #ef4444; }

/* ===== Password gauge ===== */
.gauge-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 6px 0 2px;
}

.gauge-center {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.gauge-grade {
  font-size: 22px;
  font-weight: 800;
}

.gauge-score {
  font-size: 12px;
  color: #8a93a5;
}

.gauge-hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: #8a93a5;
}

/* ===== Two-step ===== */
.ts-status {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: 12px;
  background: rgba(34, 197, 94, 0.08);
  margin-bottom: 12px;
}

.ts-status.off {
  background: rgba(138, 147, 165, 0.1);
}

.ts-badge {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: #22c55e;
  color: #ffffff;
  font-size: 20px;
}

.ts-badge.off {
  background: #aab2c0;
}

.ts-status-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ts-state {
  font-size: 15px;
  font-weight: 700;
  color: #22c55e;
}

.ts-state.off {
  color: #8a93a5;
}

.ts-desc {
  font-size: 12px;
  color: #8a93a5;
}

.ts-action-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
  padding-top: 6px;
}

.ts-toggle-label {
  font-size: 13px;
  color: #6b7585;
  font-weight: 600;
}

/* ===== Two-factor setup dialog ===== */
.tfa-hint {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.6;
  color: #6b7585;
}

.tfa-secret {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f4f6fb;
  margin-bottom: 14px;
}

.tfa-secret-label {
  font-size: 12px;
  color: #8a93a5;
  flex-shrink: 0;
}

.tfa-secret-key {
  flex: 1;
  min-width: 0;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 1px;
  color: #1f2430;
  word-break: break-all;
}

.tfa-copy {
  flex-shrink: 0;
  border: none;
  background: transparent;
  color: #8a93a5;
  cursor: pointer;
  display: flex;
  align-items: center;
}

.tfa-copy:hover { color: #4f8ef0; }

.tfa-code-input :deep(.el-input__inner) {
  letter-spacing: 6px;
  text-align: center;
  font-size: 18px;
  font-weight: 700;
}

.tfa-code-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.tfa-backup-code {
  padding: 8px 10px;
  border-radius: 8px;
  background: #f4f6fb;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 1px;
  text-align: center;
  color: #1f2430;
}

[data-theme="dark"] .tfa-secret,
[data-theme="dark"] .tfa-backup-code {
  background: #262a34;
}

[data-theme="dark"] .tfa-secret-key,
[data-theme="dark"] .tfa-backup-code {
  color: #eef1f6;
}

/* ===== Login activity status ===== */
.status-dot-ic {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 14px;
}

.status-dot-ic.success { background: rgba(34, 197, 94, 0.15); color: #22c55e; }
.status-dot-ic.failed { background: rgba(239, 68, 68, 0.15); color: #ef4444; }

/* ===== Recommendations ===== */
.rec-ic {
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}

.rec-ic.done { color: #22c55e; }
.rec-ic.todo { color: #f59e0b; }

.rec-action {
  flex-shrink: 0;
  border: none;
  background: transparent;
  color: #4f8ef0;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.rec-action:hover { opacity: 0.7; }

/* ===== Change-password dialog strength meter ===== */
.strength-meter {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}

.strength-meter :deep(.el-progress) {
  flex: 1;
}

.strength-value {
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.strength-value.weak { color: #f56c6c; }
.strength-value.medium { color: #e6a23c; }
.strength-value.strong { color: var(--tg-primary); }

/* ===== Dark mode ===== */
[data-theme="dark"] .sec-title { color: #eef1f6; }
[data-theme="dark"] .sec-sub { color: #aab2c0; }

[data-theme="dark"] .sec-refresh {
  background: #1e2230;
  border-color: #262a34;
  color: #aab2c0;
}

[data-theme="dark"] .sec-refresh:hover {
  color: #9cc6f8;
  border-color: rgba(79, 142, 240, 0.4);
}

[data-theme="dark"] .panel {
  background: #1e2230;
  border-color: #262a34;
  box-shadow: none;
}

[data-theme="dark"] .panel-title,
[data-theme="dark"] .dm-count,
[data-theme="dark"] .row-name { color: #eef1f6; }

[data-theme="dark"] .row-name.muted { color: #8a93a5; }
[data-theme="dark"] .row { border-bottom-color: #262a34; }
[data-theme="dark"] .panel-link { color: #9cc6f8; }

[data-theme="dark"] .blue { background: rgba(59, 130, 246, 0.16); color: #7eb0ff; }
[data-theme="dark"] .green { background: rgba(34, 197, 94, 0.16); color: #5ee19a; }
[data-theme="dark"] .purple { background: rgba(139, 92, 246, 0.16); color: #b69bff; }
[data-theme="dark"] .orange { background: rgba(245, 158, 11, 0.16); color: #ffc35c; }
[data-theme="dark"] .teal { background: rgba(20, 184, 166, 0.18); color: #4fd6c4; }

[data-theme="dark"] .badge-current { background: rgba(34, 197, 94, 0.16); color: #5ee19a; }
[data-theme="dark"] .ts-status.off { background: rgba(138, 147, 165, 0.12); }
[data-theme="dark"] .ts-toggle-label { color: #aab2c0; }

/* ===== Responsive ===== */
@media (max-width: 1080px) {
  .sec-grid-top { grid-template-columns: 1fr 1fr; }
  .sec-grid-bottom { grid-template-columns: 1fr; }
}

@media (max-width: 720px) {
  .sec-grid-top { grid-template-columns: 1fr; }
}
</style>

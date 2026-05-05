<template>
  <div class="security-module">
    <!-- Password Section -->
    <div class="section-card glass-card">
      <div class="section-header">
        <div class="section-title-group">
          <div class="icon-box green-gradient">
            <el-icon><Key /></el-icon>
          </div>
          <div>
            <h3 class="section-title">{{ $t('profile.password') }}</h3>
            <p class="section-desc">{{ $t('profile.passwordDesc') }}</p>
          </div>
        </div>
        <el-button type="primary" @click="showChangePasswordDialog = true">
          {{ $t('profile.changePassword') }}
        </el-button>
      </div>

      <div class="password-strength-section">
        <div class="strength-header">
          <span class="strength-label">{{ $t('profile.passwordStrength') }}</span>
          <span class="strength-value" :class="strengthClass">{{ strengthText }}</span>
        </div>
        <el-progress
          :percentage="passwordStrength"
          :color="getStrengthColor(passwordStrength)"
          :show-text="false"
          :stroke-width="8"
        />
        <p class="strength-tip">{{ $t('profile.passwordTip') }}</p>
      </div>
    </div>

    <!-- Two-Factor Authentication -->
    <div class="section-card glass-card">
      <div class="section-header">
        <div class="section-title-group">
          <div class="icon-box blue-gradient">
            <el-icon><Lock /></el-icon>
          </div>
          <div>
            <h3 class="section-title">{{ $t('profile.twoFactorAuth') }}</h3>
            <p class="section-desc">{{ $t('profile.twoFactorDesc') }}</p>
          </div>
        </div>
        <el-switch
          v-model="twoFactorEnabled"
          :loading="isTogglingTwoFactor"
          @change="toggleTwoFactor"
        />
      </div>

      <div v-if="twoFactorEnabled" class="two-factor-info">
        <div class="info-item">
          <el-icon class="info-icon success"><CircleCheck /></el-icon>
          <span>{{ $t('profile.twoFactorActive') }}</span>
        </div>
        <el-button text type="primary" @click="showBackupCodes = true">
          {{ $t('profile.viewBackupCodes') }}
        </el-button>
      </div>

      <div v-else class="two-factor-benefits">
        <div class="benefit-item">
          <el-icon><Check /></el-icon>
          <span>{{ $t('profile.twoFactorBenefit1') }}</span>
        </div>
        <div class="benefit-item">
          <el-icon><Check /></el-icon>
          <span>{{ $t('profile.twoFactorBenefit2') }}</span>
        </div>
        <div class="benefit-item">
          <el-icon><Check /></el-icon>
          <span>{{ $t('profile.twoFactorBenefit3') }}</span>
        </div>
      </div>
    </div>

    <!-- Active Sessions -->
    <div class="section-card glass-card">
      <div class="section-header">
        <div class="section-title-group">
          <div class="icon-box purple-gradient">
            <el-icon><Monitor /></el-icon>
          </div>
          <div>
            <h3 class="section-title">{{ $t('profile.activeSessions') }}</h3>
            <p class="section-desc">{{ $t('profile.activeSessionsDesc') }}</p>
          </div>
        </div>
        <el-button type="danger" text @click="logoutAllSessions">
          {{ $t('profile.logoutAll') }}
        </el-button>
      </div>

      <div class="sessions-list">
        <div v-for="session in sessions" :key="session.id" class="session-item">
          <div class="session-icon" :class="session.deviceType">
            <el-icon><component :is="getDeviceIcon(session.deviceType)" /></el-icon>
          </div>
          <div class="session-info">
            <div class="session-header">
              <span class="session-device">{{ session.device }}</span>
              <el-tag v-if="session.current" type="success" size="small">
                {{ $t('profile.currentSession') }}
              </el-tag>
            </div>
            <span class="session-details">
              {{ session.location }} · {{ session.browser }}
            </span>
            <span class="session-time">{{ formatSessionTime(session.lastActive) }}</span>
          </div>
          <el-button
            v-if="!session.current"
            type="danger"
            text
            circle
            @click="terminateSession(session.id)"
          >
            <el-icon><Close /></el-icon>
          </el-button>
        </div>
      </div>
    </div>

    <!-- Login History -->
    <div class="section-card glass-card">
      <div class="section-header">
        <div class="section-title-group">
          <div class="icon-box orange-gradient">
            <el-icon><Clock /></el-icon>
          </div>
          <div>
            <h3 class="section-title">{{ $t('profile.loginHistory') }}</h3>
            <p class="section-desc">{{ $t('profile.loginHistoryDesc') }}</p>
          </div>
        </div>
      </div>

      <div class="login-history-list">
        <div v-if="loginHistory.length === 0" class="empty-state">
          <p>{{ $t('profile.noLoginHistory') }}</p>
        </div>
        <div v-for="login in loginHistory" :key="login.id" class="login-item">
          <div class="login-status" :class="login.success ? 'success' : 'failed'">
            <el-icon>
              <CircleCheck v-if="login.success" />
              <CircleClose v-else />
            </el-icon>
          </div>
          <div class="login-info">
            <span class="login-action">
              {{ login.success ? $t('profile.loginSuccess') : $t('profile.loginFailed') }}
            </span>
            <span class="login-details">
              {{ login.location }} · {{ login.device }}
            </span>
          </div>
          <span class="login-time">{{ formatLoginTime(login.time) }}</span>
        </div>
      </div>
    </div>

    <!-- Change Password Dialog -->
    <el-dialog
      v-model="showChangePasswordDialog"
      :title="$t('profile.changePassword')"
      width="380px"
      class="security-dialog"
    >
      <el-form :model="passwordForm" label-position="top" ref="passwordFormRef">
        <el-form-item :label="$t('profile.currentPassword')" prop="currentPassword">
          <el-input
            v-model="passwordForm.currentPassword"
            type="password"
            show-password
            :placeholder="$t('profile.enterCurrentPassword')"
          />
        </el-form-item>
        <el-form-item :label="$t('profile.newPassword')" prop="newPassword">
          <el-input
            v-model="passwordForm.newPassword"
            type="password"
            show-password
            :placeholder="$t('profile.enterNewPassword')"
          />
        </el-form-item>
        <el-form-item :label="$t('profile.confirmNewPassword')" prop="confirmPassword">
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

    <!-- Backup Codes Dialog -->
    <el-dialog
      v-model="showBackupCodes"
      :title="$t('profile.backupCodes')"
      width="380px"
      class="security-dialog"
    >
      <div class="backup-codes-content">
        <p class="backup-warning">
          <el-icon><Warning /></el-icon>
          {{ $t('profile.backupCodesWarning') }}
        </p>
        <div class="backup-codes-grid">
          <div v-for="(code, index) in backupCodes" :key="index" class="backup-code">
            {{ code }}
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="showBackupCodes = false">{{ $t('common.ok') }}</el-button>
        <el-button type="primary" @click="downloadBackupCodes">
          <el-icon><Download /></el-icon>
          {{ $t('profile.downloadCodes') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useUserStore } from '@/stores/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Key, Lock, Monitor, Clock, Close, Check, CircleCheck, CircleClose,
  Warning, Download, Cellphone, Monitor as Desktop
} from '@element-plus/icons-vue'

const userStore = useUserStore()

// Password
const showChangePasswordDialog = ref(false)
const isChangingPassword = ref(false)
const passwordStrength = ref(60) // Mock data
const passwordForm = ref({
  currentPassword: '',
  newPassword: '',
  confirmPassword: ''
})

// Two-factor
const twoFactorEnabled = ref(false)
const isTogglingTwoFactor = ref(false)
const showBackupCodes = ref(false)
const backupCodes = ref([
  'XXXX-XXXX-XXXX',
  'YYYY-YYYY-YYYY',
  'ZZZZ-ZZZZ-ZZZZ',
  'AAAA-AAAA-AAAA',
  'BBBB-BBBB-BBBB',
  'CCCC-CCCC-CCCC'
])

// Sessions (mock data)
const sessions = ref([
  {
    id: 1,
    device: 'MacBook Pro',
    deviceType: 'desktop',
    browser: 'Chrome 120',
    location: 'Beijing, China',
    lastActive: new Date(),
    current: true
  },
  {
    id: 2,
    device: 'iPhone 15',
    deviceType: 'mobile',
    browser: 'Safari',
    location: 'Shanghai, China',
    lastActive: new Date(Date.now() - 3600000),
    current: false
  }
])

// Login history (mock data)
const loginHistory = ref([
  {
    id: 1,
    success: true,
    location: 'Beijing, China',
    device: 'Chrome on MacBook',
    time: new Date()
  },
  {
    id: 2,
    success: true,
    location: 'Beijing, China',
    device: 'Safari on iPhone',
    time: new Date(Date.now() - 86400000)
  },
  {
    id: 3,
    success: false,
    location: 'Unknown',
    device: 'Firefox on Windows',
    time: new Date(Date.now() - 172800000)
  }
])

// Computed
const strengthClass = computed(() => {
  if (passwordStrength.value < 40) return 'weak'
  if (passwordStrength.value < 70) return 'medium'
  return 'strong'
})

const strengthText = computed(() => {
  if (passwordStrength.value < 40) return 'Weak'
  if (passwordStrength.value < 70) return 'Medium'
  return 'Strong'
})

// Methods
const getStrengthColor = (strength) => {
  if (strength < 40) return '#f56c6c'
  if (strength < 70) return '#e6a23c'
  return '#67c23a'
}

const getDeviceIcon = (type) => {
  return type === 'mobile' ? Cellphone : Desktop
}

const formatSessionTime = (time) => {
  const date = new Date(time)
  const now = new Date()
  const diff = now - date
  const minutes = Math.floor(diff / (1000 * 60))
  if (minutes < 1) return 'Active now'
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.floor(hours / 24)}d ago`
}

const formatLoginTime = (time) => {
  const date = new Date(time)
  return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

const changePassword = async () => {
  if (!passwordForm.value.currentPassword || !passwordForm.value.newPassword) {
    ElMessage.warning('Please fill in all fields')
    return
  }

  if (passwordForm.value.newPassword !== passwordForm.value.confirmPassword) {
    ElMessage.error('New passwords do not match')
    return
  }

  if (passwordForm.value.newPassword.length < 8) {
    ElMessage.error('Password must be at least 8 characters')
    return
  }

  isChangingPassword.value = true
  try {
    // API call to change password
    await new Promise(resolve => setTimeout(resolve, 1000))
    ElMessage.success('Password changed successfully')
    showChangePasswordDialog.value = false
    passwordForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
    passwordStrength.value = 85 // Update strength
  } catch (error) {
    ElMessage.error('Failed to change password')
  } finally {
    isChangingPassword.value = false
  }
}

const toggleTwoFactor = async (value) => {
  isTogglingTwoFactor.value = true
  try {
    await new Promise(resolve => setTimeout(resolve, 800))
    if (value) {
      ElMessage.success('Two-factor authentication enabled')
    } else {
      ElMessage.success('Two-factor authentication disabled')
    }
  } catch (error) {
    ElMessage.error('Failed to update two-factor settings')
    twoFactorEnabled.value = !value
  } finally {
    isTogglingTwoFactor.value = false
  }
}

const terminateSession = async (sessionId) => {
  try {
    await ElMessageBox.confirm(
      'This will log out the device. Continue?',
      'Terminate Session',
      { type: 'warning' }
    )
    sessions.value = sessions.value.filter(s => s.id !== sessionId)
    ElMessage.success('Session terminated')
  } catch {
    // Cancelled
  }
}

const logoutAllSessions = async () => {
  try {
    await ElMessageBox.confirm(
      'This will log out all devices except this one. Continue?',
      'Logout All Sessions',
      { type: 'warning' }
    )
    sessions.value = sessions.value.filter(s => s.current)
    ElMessage.success('All other sessions terminated')
  } catch {
    // Cancelled
  }
}

const downloadBackupCodes = () => {
  const content = backupCodes.value.join('\n')
  const blob = new Blob([content], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'nexus-chat-backup-codes.txt'
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.security-module {
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
  align-items: flex-start;
  margin-bottom: 20px;
}

.section-title-group {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.section-title {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 700;
  color: #1c1c1e;
}

.section-desc {
  margin: 0;
  font-size: 13px;
  color: #8e8e93;
}

/* Icon Boxes */
.icon-box {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: white;
  flex-shrink: 0;
}

.green-gradient { background: linear-gradient(135deg, #22c55e, #10b981); }
.blue-gradient { background: linear-gradient(135deg, #3390ec, #00a3ff); }
.purple-gradient { background: linear-gradient(135deg, #a855f7, #8b5cf6); }
.orange-gradient { background: linear-gradient(135deg, #f97316, #fb923c); }

/* Password Strength */
.password-strength-section {
  background: rgba(51, 144, 236, 0.04);
  padding: 16px;
  border-radius: 12px;
}

.strength-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
}

.strength-label {
  font-size: 14px;
  color: #666;
}

.strength-value {
  font-size: 14px;
  font-weight: 600;
}

.strength-value.weak { color: #f56c6c; }
.strength-value.medium { color: #e6a23c; }
.strength-value.strong { color: #67c23a; }

.strength-tip {
  margin: 12px 0 0;
  font-size: 12px;
  color: #8e8e93;
}

/* Two Factor */
.two-factor-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: rgba(34, 197, 94, 0.08);
  border-radius: 12px;
}

.info-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #22c55e;
}

.info-icon.success {
  color: #22c55e;
}

.two-factor-benefits {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  background: rgba(51, 144, 236, 0.04);
  border-radius: 12px;
}

.benefit-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #666;
}

.benefit-item .el-icon {
  color: #3390ec;
}

/* Sessions */
.sessions-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background: rgba(51, 144, 236, 0.04);
  border-radius: 12px;
}

.session-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: white;
}

.session-icon.desktop { background: linear-gradient(135deg, #3390ec, #667eea); }
.session-icon.mobile { background: linear-gradient(135deg, #22c55e, #10b981); }

.session-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.session-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.session-device {
  font-size: 14px;
  font-weight: 600;
  color: #1c1c1e;
}

.session-details {
  font-size: 13px;
  color: #666;
}

.session-time {
  font-size: 12px;
  color: #8e8e93;
}

/* Login History */
.login-history-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.login-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: rgba(51, 144, 236, 0.04);
  border-radius: 12px;
}

.login-status {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
}

.login-status.success {
  background: rgba(34, 197, 94, 0.15);
  color: #22c55e;
}

.login-status.failed {
  background: rgba(239, 68, 68, 0.15);
  color: #ef4444;
}

.login-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.login-action {
  font-size: 14px;
  font-weight: 500;
  color: #1c1c1e;
}

.login-details {
  font-size: 12px;
  color: #8e8e93;
}

.login-time {
  font-size: 12px;
  color: #8e8e93;
}

/* Backup Codes */
.backup-codes-content {
  text-align: center;
}

.backup-warning {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px;
  background: rgba(245, 158, 11, 0.1);
  color: #f59e0b;
  border-radius: 8px;
  font-size: 13px;
  margin-bottom: 20px;
}

.backup-codes-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.backup-code {
  padding: 12px;
  background: #f5f5f5;
  border-radius: 8px;
  font-family: monospace;
  font-size: 14px;
  font-weight: 600;
  color: #333;
}

/* Empty State */
.empty-state {
  text-align: center;
  padding: 24px;
  color: #8e8e93;
}

.empty-state p {
  margin: 0;
  font-size: 14px;
}

/* Glass Card */
.glass-card {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.9);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.04), 0 1px 4px rgba(0, 0, 0, 0.02);
  border-radius: 20px;
}
</style>

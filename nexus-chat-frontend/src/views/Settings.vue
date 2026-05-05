<template>
  <div class="settings-container">
    <!-- Drag region for window dragging -->
    <div class="drag-region"></div>
    <div class="settings-header">
      <el-button circle @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
      </el-button>
      <h2>{{ $t('settings.settings') }}</h2>
    </div>

    <div class="settings-content">
      <div class="settings-list">
        <!-- Language Setting -->
        <div class="setting-item">
          <div class="setting-icon" style="background: #00c853">
            <el-icon color="white"><Globe /></el-icon>
          </div>
          <span>{{ $t('settings.language') }}</span>
          <el-select 
            v-model="currentLocale" 
            @change="handleLanguageChange"
            class="language-select"
            size="small"
          >
            <el-option value="zh" label="中文" />
            <el-option value="en" label="English" />
          </el-select>
        </div>

        <!-- Notifications -->
        <div class="setting-item">
          <div class="setting-icon" style="background: #3390ec">
            <el-icon color="white"><Bell /></el-icon>
          </div>
          <span>{{ $t('settings.notifications') }}</span>
          <el-switch v-model="notifications" />
        </div>

        <!-- Privacy and Security -->
        <div class="setting-item" @click="showPrivacyDialog = true">
          <div class="setting-icon" style="background: #8774e1">
            <el-icon color="white"><Lock /></el-icon>
          </div>
          <span>{{ $t('profile.privacySettings') }}</span>
          <el-icon class="arrow"><ArrowRight /></el-icon>
        </div>

        <!-- Check for Updates (only show in Electron) -->
        <div v-if="isElectron" class="setting-item" @click="checkForUpdates">
          <div class="setting-icon" style="background: #007aff">
            <el-icon color="white"><Refresh /></el-icon>
          </div>
          <span>{{ $t('settings.checkForUpdates') }}</span>
          <span v-if="appVersion" class="version-tag">v{{ appVersion }}</span>
          <el-icon v-if="isCheckingUpdate" class="is-loading"><Loading /></el-icon>
          <el-icon v-else class="arrow"><ArrowRight /></el-icon>
        </div>

        <!-- Logout -->
        <div class="setting-item logout" @click="handleLogout">
          <div class="setting-icon" style="background: #ff3b30">
            <el-icon color="white"><SwitchButton /></el-icon>
          </div>
          <span>{{ $t('auth.logout') }}</span>
        </div>
      </div>
    </div>

    <!-- Privacy Settings Dialog -->
    <el-dialog
      v-model="showPrivacyDialog"
      :title="$t('profile.privacySettings')"
      width="420px"
    >
      <div class="privacy-settings">
        <!-- 好友验证方式设置 -->
        <div class="privacy-item friend-request-mode">
          <div class="privacy-info">
            <h4>{{ $t('privacy.friendRequestMode') }}</h4>
            <p>{{ $t('privacy.friendRequestModeDesc') }}</p>
          </div>
          <el-radio-group v-model="privacySettings.friendRequestMode" class="mode-radio-group">
            <el-radio value="DIRECT">
              <div class="radio-content">
                <span class="radio-label">{{ $t('privacy.directMode') }}</span>
                <span class="radio-desc">{{ $t('privacy.directModeDesc') }}</span>
              </div>
            </el-radio>
            <el-radio value="VERIFY">
              <div class="radio-content">
                <span class="radio-label">{{ $t('privacy.verifyMode') }}</span>
                <span class="radio-desc">{{ $t('privacy.verifyModeDesc') }}</span>
              </div>
            </el-radio>
          </el-radio-group>
        </div>

        <div class="privacy-divider"></div>

        <div class="privacy-item">
          <div class="privacy-info">
            <h4>{{ $t('profile.showOnlineStatus') }}</h4>
            <p>{{ $t('profile.showOnlineStatusDesc') }}</p>
          </div>
          <el-switch v-model="privacySettings.showOnlineStatus" />
        </div>

        <div class="privacy-item">
          <div class="privacy-info">
            <h4>{{ $t('profile.showLastSeen') }}</h4>
            <p>{{ $t('profile.showLastSeenDesc') }}</p>
          </div>
          <el-switch v-model="privacySettings.showLastSeen" />
        </div>

        <div class="privacy-item">
          <div class="privacy-info">
            <h4>{{ $t('profile.showPhone') }}</h4>
            <p>{{ $t('profile.showPhoneDesc') }}</p>
          </div>
          <el-switch v-model="privacySettings.showPhone" />
        </div>

        <div class="privacy-item">
          <div class="privacy-info">
            <h4>{{ $t('profile.showEmail') }}</h4>
            <p>{{ $t('profile.showEmailDesc') }}</p>
          </div>
          <el-switch v-model="privacySettings.showEmail" />
        </div>
      </div>

      <template #footer>
        <el-button @click="showPrivacyDialog = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" @click="savePrivacySettings">{{ $t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- Update Dialog -->
    <el-dialog
      v-model="showUpdateDialog"
      :title="$t('settings.updateAvailable')"
      width="400px"
    >
      <div class="update-content" v-if="updateInfo">
        <div class="update-icon">
          <el-icon :size="48" color="#007aff"><Refresh /></el-icon>
        </div>

        <div v-if="updateInfo.hasUpdate" class="update-info">
          <h3>{{ updateInfo.releaseName }}</h3>
          <div class="version-compare">
            <span class="current">v{{ updateInfo.currentVersion }}</span>
            <el-icon><ArrowRight /></el-icon>
            <span class="latest">v{{ updateInfo.latestVersion }}</span>
          </div>
          <div v-if="updateInfo.releaseNotes" class="release-notes">
            <h4>{{ $t('settings.releaseNotes') }}</h4>
            <p>{{ updateInfo.releaseNotes }}</p>
          </div>
        </div>

        <div v-else class="no-update">
          <el-icon :size="48" color="#34c759"><CircleCheck /></el-icon>
          <h3>{{ $t('settings.upToDate') }}</h3>
          <p>{{ $t('settings.currentVersionIs') }} v{{ updateInfo.currentVersion }}</p>
        </div>
      </div>

      <template #footer>
        <el-button @click="showUpdateDialog = false">{{ $t('common.cancel') }}</el-button>
        <el-button v-if="updateInfo?.hasUpdate" type="primary" @click="downloadUpdate">
          {{ $t('settings.downloadUpdate') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, h, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ArrowRight, Bell, Lock, SwitchButton, Refresh, Loading, CircleCheck } from '@element-plus/icons-vue'

// Globe icon for language using Vue's h() function
const Globe = {
  name: 'Globe',
  render() {
    return h('svg', {
      xmlns: 'http://www.w3.org/2000/svg',
      viewBox: '0 0 24 24',
      fill: 'currentColor',
      width: '16',
      height: '16'
    }, [
      h('path', {
        d: 'M12 2C6.477 2 2 6.477 2 12s4.477 10 10 10 10-4.477 10-10S17.523 2 12 2zm7.93 9h-3.98c-.08-2.34-.58-4.53-1.42-6.35A8.02 8.02 0 0117.93 11zM12 4c.04 0 .08.01.12.01C13.2 6.01 13.88 8.43 13.95 11h-3.9c.07-2.57.75-4.99 1.83-6.99.04 0 .08-.01.12-.01zM4.07 11c.4-2.77 2.02-5.14 4.28-6.35C7.51 6.47 7.01 8.66 6.93 11H4.07zm-1 2h3.98c.08 2.34.58 4.53 1.42 6.35A8.02 8.02 0 014.07 13zM12 20c-.04 0-.08-.01-.12-.01-1.08-2-1.76-4.42-1.83-6.99h3.9c-.07 2.57-.75 4.99-1.83 6.99-.04 0-.08.01-.12.01zm2.35-.65c.84-1.82 1.34-4.01 1.42-6.35h3.98a8.02 8.02 0 01-5.4 6.35z'
      })
    ])
  }
}

const router = useRouter()
const { locale, t } = useI18n()
const userStore = useUserStore()
const notifications = ref(true)
const showPrivacyDialog = ref(false)

// Update checking
const showUpdateDialog = ref(false)
const updateInfo = ref(null)
const isCheckingUpdate = ref(false)
const appVersion = ref('')

// Check if running in Electron
const isElectron = computed(() => {
  return typeof window !== 'undefined' && window.electronAPI
})

// Initialize current locale from localStorage or default
const currentLocale = ref(localStorage.getItem('locale') || 'en')

// Privacy settings
const privacySettings = reactive({
  showOnlineStatus: userStore.currentUser?.showOnlineStatus ?? true,
  showLastSeen: userStore.currentUser?.showLastSeen ?? true,
  showPhone: userStore.currentUser?.showPhone ?? false,
  showEmail: userStore.currentUser?.showEmail ?? false,
  friendRequestMode: userStore.currentUser?.friendRequestMode ?? 'DIRECT'
})

const goBack = () => {
  router.back()
}

const handleLanguageChange = (lang) => {
  locale.value = lang
  localStorage.setItem('locale', lang)
}

const handleLogout = async () => {
  await userStore.logout()
  router.push('/login')
}

const savePrivacySettings = () => {
  try {
    userStore.updatePrivacySettings(privacySettings)
    ElMessage.success('Privacy settings updated successfully')
    showPrivacyDialog.value = false
  } catch (error) {
    ElMessage.error('Failed to update privacy settings')
  }
}

// Get app version on mount
onMounted(async () => {
  if (isElectron.value && window.electronAPI.getAppVersion) {
    try {
      appVersion.value = await window.electronAPI.getAppVersion()
    } catch (e) {
      console.error('Failed to get app version:', e)
    }
  }
})

// Check for updates
const checkForUpdates = async () => {
  if (!isElectron.value || isCheckingUpdate.value) return

  isCheckingUpdate.value = true
  try {
    const result = await window.electronAPI.checkForUpdates()
    updateInfo.value = result
    showUpdateDialog.value = true

    if (!result.hasUpdate) {
      ElMessage.success(t('settings.upToDate'))
    }
  } catch (error) {
    console.error('Failed to check for updates:', error)
    ElMessage.error(t('settings.checkUpdateFailed'))
  } finally {
    isCheckingUpdate.value = false
  }
}

// Download update
const downloadUpdate = () => {
  if (updateInfo.value?.downloadUrl && window.electronAPI?.openExternal) {
    window.electronAPI.openExternal(updateInfo.value.downloadUrl)
    showUpdateDialog.value = false
  }
}
</script>

<style scoped>
.settings-container {
  height: 100vh;
  background: linear-gradient(180deg, #f8fafc 0%, #f0f2f5 100%);
  display: flex;
  flex-direction: column;
  overflow: hidden;
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

/* 全局隐藏滚动条 */
.settings-container *::-webkit-scrollbar {
  display: none;
}

.settings-container * {
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.settings-header {
  background: white;
  padding: 16px 20px;
  padding-top: 40px; /* Extra padding for drag region */
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.05);
  flex-shrink: 0;
}

.settings-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #1c1c1e;
}

.settings-header :deep(.el-button) {
  border-radius: 10px;
}

.settings-content {
  flex: 1;
  max-width: 600px;
  margin: 0 auto;
  width: 100%;
  padding: 20px;
  overflow-y: auto;
  overflow-x: hidden;
}

.settings-list {
  background: white;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0,0,0,0.04);
}

.setting-item {
  display: flex;
  align-items: center;
  padding: 14px 18px;
  cursor: pointer;
  transition: background 0.2s ease;
  border-bottom: 1px solid #f5f5f5;
}

.setting-item:last-child {
  border-bottom: none;
}

.setting-item:hover {
  background: #f8f9fa;
}

.setting-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  justify-content: center;
  align-items: center;
  margin-right: 14px;
  flex-shrink: 0;
}

.setting-item span {
  flex: 1;
  font-size: 15px;
  font-weight: 500;
  color: #1c1c1e;
}

.arrow {
  color: #c7c7cc;
  font-size: 16px;
}

.logout span {
  color: #ff3b30;
  font-weight: 600;
}

.language-select {
  width: 100px;
}

.language-select :deep(.el-input__wrapper) {
  background-color: #f5f5f7;
  border-radius: 10px;
  box-shadow: none;
  transition: all 0.2s ease;
}

.language-select :deep(.el-input__wrapper:hover) {
  background-color: #ebebed;
}

.language-select :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px #3390ec inset;
  background-color: white;
}

.privacy-settings {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.privacy-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  background: #f8f9fa;
  border-radius: 12px;
  transition: background 0.2s ease;
}

.privacy-item:hover {
  background: #f0f2f5;
}

.privacy-info {
  flex: 1;
}

.privacy-info h4 {
  margin: 0 0 4px 0;
  font-size: 14px;
  font-weight: 600;
  color: #1c1c1e;
}

.privacy-info p {
  margin: 0;
  font-size: 12px;
  color: #8e8e93;
}

/* Friend request mode styles */
.privacy-item.friend-request-mode {
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
}

.mode-radio-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.mode-radio-group :deep(.el-radio) {
  display: flex;
  align-items: flex-start;
  height: auto;
  padding: 12px;
  background: #f0f2f5;
  border-radius: 10px;
  margin-right: 0;
  width: 100%;
}

.mode-radio-group :deep(.el-radio.is-checked) {
  background: #e8f4ff;
  border: 1px solid #3390ec;
}

.mode-radio-group :deep(.el-radio__label) {
  padding-left: 10px;
}

.radio-content {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.radio-label {
  font-size: 14px;
  font-weight: 600;
  color: #1c1c1e;
}

.radio-desc {
  font-size: 12px;
  color: #8e8e93;
}

.privacy-divider {
  height: 1px;
  background: #e5e5e5;
  margin: 8px 0;
}

/* Version tag */
.version-tag {
  font-size: 12px;
  color: #8e8e93;
  background: #f0f2f5;
  padding: 2px 8px;
  border-radius: 8px;
  margin-right: 8px;
}

/* Update dialog styles */
.update-content {
  text-align: center;
  padding: 16px 0;
}

.update-icon {
  margin-bottom: 16px;
}

.update-info h3 {
  margin: 0 0 12px 0;
  font-size: 18px;
  font-weight: 600;
  color: #1c1c1e;
}

.version-compare {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-bottom: 20px;
}

.version-compare .current {
  color: #8e8e93;
  font-size: 14px;
}

.version-compare .latest {
  color: #007aff;
  font-size: 14px;
  font-weight: 600;
}

.release-notes {
  text-align: left;
  background: #f8f9fa;
  padding: 16px;
  border-radius: 12px;
  margin-top: 16px;
}

.release-notes h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  font-weight: 600;
  color: #1c1c1e;
}

.release-notes p {
  margin: 0;
  font-size: 13px;
  color: #636366;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.no-update {
  padding: 20px 0;
}

.no-update h3 {
  margin: 16px 0 8px 0;
  font-size: 18px;
  font-weight: 600;
  color: #1c1c1e;
}

.no-update p {
  margin: 0;
  font-size: 14px;
  color: #8e8e93;
}

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .settings-header {
    padding: 12px 16px;
    padding-top: 48px;
  }

  .settings-header h2 {
    font-size: 17px;
  }

  .settings-content {
    padding: 16px 12px;
  }

  .settings-list {
    border-radius: 14px;
  }

  .setting-item {
    padding: 12px 14px;
  }

  .setting-icon {
    width: 32px;
    height: 32px;
    border-radius: 8px;
    margin-right: 12px;
  }

  .setting-item span {
    font-size: 14px;
  }

  .language-select {
    width: 90px;
  }

  .privacy-item {
    padding: 12px 14px;
  }

  .privacy-info h4 {
    font-size: 13px;
  }

  .privacy-info p {
    font-size: 11px;
  }

  .mode-radio-group :deep(.el-radio) {
    padding: 10px;
  }

  .radio-label {
    font-size: 13px;
  }

  .radio-desc {
    font-size: 11px;
  }

  .version-tag {
    font-size: 11px;
    padding: 2px 6px;
  }

  .update-info h3 {
    font-size: 16px;
  }

  .version-compare .current,
  .version-compare .latest {
    font-size: 13px;
  }

  .release-notes {
    padding: 14px;
  }

  .release-notes h4 {
    font-size: 13px;
  }

  .release-notes p {
    font-size: 12px;
  }

  .no-update h3 {
    font-size: 16px;
  }

  .no-update p {
    font-size: 13px;
  }
}

/* Small phones */
@media (max-width: 375px) {
  .settings-header {
    padding: 10px 12px;
    padding-top: 44px;
  }

  .settings-content {
    padding: 12px 10px;
  }

  .setting-item {
    padding: 10px 12px;
  }

  .setting-icon {
    width: 30px;
    height: 30px;
    margin-right: 10px;
  }

  .setting-item span {
    font-size: 13px;
  }
}
</style>

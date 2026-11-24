<template>
  <el-config-provider :locale="elementLocale">
    <div class="app-container">
      <ConnectionBanner />
      <router-view />
    </div>
  </el-config-provider>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { useSettingsStore } from '@/stores/settings'
import { useAgentProvidersStore } from '@/stores/agentProviders'
import { installProviderTestFeedback } from '@/utils/providerTestFeedback'
import { unlockAudio, disposeAudio } from '@/utils/uiSound'
import ConnectionBanner from '@/components/common/ConnectionBanner.vue'

const userStore = useUserStore()
const chatStore = useChatStore()
const settings = useSettingsStore()
const { locale, t } = useI18n()
const providers = useAgentProvidersStore()
const stopProviderFeedback = installProviderTestFeedback(providers, t)

// Dock / taskbar badge. It lives at the app root, not on the settings screen:
// unread counts change while the user is anywhere in the app, and signing out
// has to clear it.
watch(
  [() => chatStore.totalUnreadCount, () => settings.badgeEnabled, () => userStore.isLoggedIn],
  ([total, enabled, loggedIn]) => {
    if (!window.electronAPI?.setBadge) return
    const count = loggedIn && enabled ? Math.max(0, Math.floor(Number(total)) || 0) : 0
    Promise.resolve(window.electronAPI.setBadge(count)).catch(() => {})
  },
  { immediate: true }
)

// Element Plus ships its own copy (table "No Data", dialog close labels, …).
// Keep it in step with the app language so no stray English leaks through.
const elementLocale = computed(() => (locale.value === 'zh' ? zhCn : en))

// Settings are the per-account authority for the language. The global locale
// key is only a login-screen hint, never a source for another account's bucket.
watch(() => settings.locale, (next) => { locale.value = next }, { immediate: true })
watch(locale, (next) => {
  document.documentElement.lang = next === 'zh' ? 'zh-CN' : 'en'
  localStorage.setItem('locale', next)
  if (window.electronAPI?.setMenuLocale) {
    Promise.resolve(window.electronAPI.setMenuLocale(next)).catch(() => {})
  }
}, { immediate: true })

const onAudioGesture = () => { if (userStore.isLoggedIn && settings.soundEnabled) unlockAudio() }
onMounted(() => {
  window.addEventListener('pointerdown', onAudioGesture, { passive: true })
  window.addEventListener('keydown', onAudioGesture)
  // Check if user is logged in
  const token = localStorage.getItem('token')
  if (token) {
    userStore.loadUserFromStorage()
  }
})
onBeforeUnmount(() => {
  stopProviderFeedback()
  window.removeEventListener('pointerdown', onAudioGesture)
  window.removeEventListener('keydown', onAudioGesture)
  disposeAudio()
})
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body {
  width: 100%;
  height: 100%;
  overflow: hidden;
}

#app {
  font-family: 'Geist Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
}

.app-container {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

/* Ensure Element Plus message components appear correctly */
.el-message {
  z-index: 10001 !important;
}

.el-notification {
  z-index: 10001 !important;
}

/* Provider dialogs use 10010/10020. Probe feedback must stay above both,
   including their modal scrim, without recolouring unrelated UI. */
.el-message.provider-test-progress,
.el-notification.provider-test-notification {
  z-index: 10040 !important;
  max-width: calc(100vw - 32px);
}
.el-notification.provider-test-notification--success .el-notification__icon {
  color: #22c55e;
}
.el-notification.provider-test-notification--error .el-notification__icon {
  color: #ef4444;
}
</style>

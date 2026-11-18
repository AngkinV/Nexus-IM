<template>
  <el-config-provider :locale="elementLocale">
    <div class="app-container">
      <ConnectionBanner />
      <router-view />
    </div>
  </el-config-provider>
</template>

<script setup>
import { computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import { useUserStore } from '@/stores/user'
import ConnectionBanner from '@/components/common/ConnectionBanner.vue'

const userStore = useUserStore()
const { locale } = useI18n()

// Element Plus ships its own copy (table "No Data", dialog close labels, …).
// Keep it in step with the app language so no stray English leaks through.
const elementLocale = computed(() => (locale.value === 'zh' ? zhCn : en))

// Keep <html lang> and the persisted preference in step with the active locale,
// so the document declares the language it is actually rendering (screen
// readers, hyphenation, and the Electron title bar all read this).
watch(
  locale,
  (next) => {
    document.documentElement.lang = next === 'zh' ? 'zh-CN' : 'en'
    localStorage.setItem('locale', next)
  },
  { immediate: true }
)

onMounted(() => {
  // Check if user is logged in
  const token = localStorage.getItem('token')
  if (token) {
    userStore.loadUserFromStorage()
  }
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
</style>

import { createApp, watch } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import router from './router'
import i18n from './locales/i18n'
import App from './App.vue'
import '@/styles/nexus-theme.css'
import '@/styles/prism.css'
import { useSettingsStore } from '@/stores/settings'
import { useUserStore } from '@/stores/user'
import { userAPI } from '@/services/api'
import { startRenderGovernor } from '@/utils/renderGovernor'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus)
app.use(i18n)

// Register all Element Plus icons
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
}

// Settings belong to the signed-in account. The settings store is told who
// that is here, in one place, and follows every later change (sign-in, sign-out,
// registration, a session restored from storage) through the user store.
const userStore = useUserStore(pinia)
const settingsStore = useSettingsStore(pinia)
settingsStore.bindAccountApi({ get: userAPI.getPreferences, update: userAPI.updatePreferences })
startRenderGovernor()

const signedInUserId = () => (userStore.isLoggedIn ? userStore.currentUser.id : null)
userStore.loadUserFromStorage()
watch(signedInUserId, (id) => settingsStore.switchUser(id))

// Load this account's settings (decrypting them when encryption is on) before
// mounting, so the first render already has the right zoom, glass and time
// format instead of flashing defaults. The backend sync runs in the background:
// a slow network must not hold the app back, and the local values already work.
settingsStore.switchUser(signedInUserId()).finally(() => app.mount('#app'))

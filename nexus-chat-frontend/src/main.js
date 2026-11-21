import { createApp } from 'vue'
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

// Load persisted preferences (decrypting the blob when encryption is on)
// before mounting, so the first render already has the right zoom, glass and
// sound settings rather than flashing defaults.
useSettingsStore(pinia)
  .hydrate()
  .finally(() => app.mount('#app'))

<template>
  <!--
    Settings — full-window layout, no centred card.
    Header (48px) / left category rail (256px) / scrolling content.
    Every control here is wired to real behaviour; see docs/superpowers/plans/2026-11-21-settings-redesign.md
  -->
  <div class="settings-root">
    <!-- ── Header ───────────────────────────────────────────── -->
    <header class="settings-header">
      <div class="header-left">
        <!-- macOS draws its own traffic lights over the frameless window; this
             spacer keeps the back pill clear of them. Mirrors NavRail's 78px. -->
        <div v-if="isMac" class="traffic-spacer" aria-hidden="true"></div>
        <button type="button" class="back-pill" :title="t('settings.backToChat')" @click="goBack">
          <span class="material-symbols-outlined">arrow_back</span>
          <span>{{ t('settings.backToChat') }}</span>
          <kbd>ESC</kbd>
        </button>
      </div>

      <div class="header-center">
        <span class="brand-mark">N</span>
        <span class="brand-name">NEXUS-IM</span>
        <span class="crumb-sep">/</span>
        <span class="crumb-current">{{ t('settings.settings') }}</span>
        <span v-if="appVersion" class="version-chip">Desktop Core v{{ appVersion }}</span>
      </div>

      <div class="header-right">
        <div class="header-search">
          <span class="material-symbols-outlined">search</span>
          <input
            ref="searchInput"
            v-model="searchQuery"
            type="text"
            :placeholder="t('settings.searchPlaceholder')"
          />
          <kbd>⌘K</kbd>
        </div>
        <button type="button" class="reset-pill" :title="t('settings.resetHint')" @click="resetDefaults">
          <span class="material-symbols-outlined">history</span>
          <span>{{ t('settings.resetDefaults') }}</span>
        </button>
      </div>
    </header>

    <div class="settings-body">
      <!-- ── Left category rail ─────────────────────────────── -->
      <aside class="settings-rail">
        <nav class="rail-nav">
          <div class="rail-caption">{{ t('settings.categories') }}</div>
          <button
            v-for="item in navItems"
            :key="item.key"
            type="button"
            class="rail-item"
            :class="{ active: activeSection === item.key }"
            @click="scrollTo(item.key)"
          >
            <span class="material-symbols-outlined">{{ item.icon }}</span>
            <span class="rail-label">{{ t(item.labelKey) }}</span>
            <span v-if="item.badge" class="rail-badge">{{ item.badge }}</span>
            <span v-if="item.key === 'about'" class="rail-dot"></span>
          </button>
        </nav>

        <div class="rail-footer">
          <div class="gpu-block">
            <div class="gpu-row">
              <span class="gpu-label">
                <span class="material-symbols-outlined">memory</span>
                {{ t('settings.gpuMemory') }}
              </span>
              <span class="gpu-value">{{ gpuText }}</span>
            </div>
            <div class="gpu-bar"><div class="gpu-bar-fill" :style="{ width: gpuBarWidth }"></div></div>
            <div class="gpu-foot">
              <span>{{ gpuEngineText }}</span>
              <span :class="['gpu-state', { on: gpuStatus.gpuAccelerated }]">{{ gpuEngineState }}</span>
            </div>
          </div>

          <button type="button" class="logout-btn" @click="handleLogout">
            <span class="material-symbols-outlined">power_settings_new</span>
            <span>{{ t('settings.logoutCurrent') }}</span>
          </button>
        </div>
      </aside>

      <!-- ── Content ────────────────────────────────────────── -->
      <main ref="contentEl" class="settings-content prism-scroll" @scroll="onScroll">
        <!-- 1. 通用与界面 -->
        <section id="sec-general" class="settings-section">
          <div class="section-head">
            <div class="section-title">
              <span class="material-symbols-outlined">tune</span>
              <h2>{{ t('settings.sectionGeneral') }}</h2>
            </div>
            <span class="section-sub">General &amp; Liquid Interface</span>
          </div>

          <div class="card-grid cols-2">
            <!-- 语言 -->
            <div class="card" :class="{ dimmed: !matches('language') }">
              <div class="card-head">
                <div class="card-heading">
                  <span class="material-symbols-outlined">language</span>
                  <div>
                    <h3>{{ t('settings.language') }}</h3>
                    <p>{{ t('settings.languageDesc') }}</p>
                  </div>
                </div>
              </div>
              <div class="card-body">
                <div class="select-wrap">
                  <select v-model="currentLocale" class="native-select" @change="handleLanguageChange">
                    <option v-for="opt in languageOptions" :key="opt.value" :value="opt.value">
                      {{ opt.label }}
                    </option>
                  </select>
                  <span class="material-symbols-outlined select-caret">unfold_more</span>
                </div>
              </div>
            </div>

            <!-- 时间格式 -->
            <div class="card" :class="{ dimmed: !matches('hourCycle') }">
              <div class="card-head">
                <div class="card-heading">
                  <span class="material-symbols-outlined">schedule</span>
                  <div>
                    <h3>{{ t('settings.hourCycle') }}</h3>
                    <p>{{ t('settings.hourCycleDesc') }}</p>
                  </div>
                </div>
              </div>
              <div class="card-body">
                <div class="segmented">
                  <button
                    type="button"
                    :class="{ on: settings.hourCycle === '24' }"
                    @click="settings.hourCycle = '24'"
                  >
                    {{ t('settings.hour24') }}
                  </button>
                  <button
                    type="button"
                    :class="{ on: settings.hourCycle === '12' }"
                    @click="settings.hourCycle = '12'"
                  >
                    {{ t('settings.hour12') }}
                  </button>
                </div>
                <p class="preview">{{ t('settings.preview') }}: {{ timePreview }}</p>
              </div>
            </div>
          </div>
        </section>

        <!-- 2. 桌面宠物与 3D -->
        <section id="sec-3dpet" class="settings-section">
          <div class="section-head">
            <div class="section-title">
              <span class="material-symbols-outlined">smart_toy</span>
              <h2>{{ t('settings.section3d') }}</h2>
            </div>
            <span class="section-sub">WebGL &amp; VRM Pipeline</span>
          </div>

          <div class="card-grid cols-1">
            <div class="card card-row" :class="{ dimmed: !matches('desktopPet') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">smart_toy</span>
                <div>
                  <h3>
                    {{ t('settings.desktopPet') }}
                    <span class="chip">VRM / Live2D</span>
                    <span class="chip">Three.js</span>
                  </h3>
                  <p>{{ t('settings.desktopPetHint') }}</p>
                </div>
              </div>
              <button
                type="button"
                class="toggle"
                :class="{ on: companionStore.petVisible }"
                role="switch"
                :aria-checked="companionStore.petVisible"
                @click="companionStore.setPetVisible(!companionStore.petVisible)"
              >
                <span class="toggle-knob"></span>
              </button>
            </div>
          </div>

          <div class="card-grid cols-3">
            <div class="mini-card">
              <span class="mini-label">{{ t('settings.memoryUse') }}</span>
              <b class="mini-value">{{ gpuStatus.available ? gpuStatus.memoryMB + ' MB' : '—' }}</b>
              <span class="mini-note">{{ t('settings.memoryUseNote') }}</span>
            </div>
            <div class="mini-card">
              <span class="mini-label">{{ t('settings.peakMemory') }}</span>
              <b class="mini-value">{{ gpuStatus.available ? gpuStatus.peakMemoryMB + ' MB' : '—' }}</b>
              <span class="mini-note">{{ t('settings.peakMemoryNote') }}</span>
            </div>
            <div class="mini-card">
              <span class="mini-label">{{ t('settings.acceleration') }}</span>
              <b class="mini-value">{{ gpuEngineState }}</b>
              <span class="mini-note">{{ t('settings.accelerationNote') }}</span>
            </div>
          </div>
        </section>

        <!-- 3. 消息与通知 -->
        <section id="sec-notifs" class="settings-section">
          <div class="section-head">
            <div class="section-title">
              <span class="material-symbols-outlined">notifications_active</span>
              <h2>{{ t('settings.sectionNotifs') }}</h2>
            </div>
            <span class="section-sub">Desktop Notifications</span>
          </div>

          <div class="card-grid cols-2">
            <div class="card card-row" :class="{ dimmed: !matches('notifications') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">desktop_windows</span>
                <div>
                  <h3>{{ t('settings.notifications') }}</h3>
                  <p>{{ t('settings.notificationsDesc') }}</p>
                </div>
              </div>
              <button
                type="button"
                class="toggle"
                :class="{ on: settings.notificationsEnabled }"
                role="switch"
                :aria-checked="settings.notificationsEnabled"
                :disabled="!isElectron"
                @click="settings.notificationsEnabled = !settings.notificationsEnabled"
              >
                <span class="toggle-knob"></span>
              </button>
            </div>

            <div class="card card-row" :class="{ dimmed: !matches('unread') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">mark_chat_unread</span>
                <div>
                  <h3>{{ t('settings.unreadBadge') }}</h3>
                  <p>{{ t('settings.unreadBadgeDesc') }}</p>
                </div>
              </div>
              <span class="value-note">{{ unreadTotal }}</span>
            </div>
          </div>
        </section>

        <!-- 4. 角色与模型 / 隐私 -->
        <section id="sec-ai" class="settings-section">
          <div class="section-head">
            <div class="section-title">
              <span class="material-symbols-outlined">psychology</span>
              <h2>{{ t('settings.sectionAi') }}</h2>
            </div>
            <span class="section-sub">AI Context &amp; Privacy</span>
          </div>

          <div class="card-grid cols-2">
            <!-- 角色与模型：内联展开 -->
            <div class="card" :class="{ dimmed: !matches('role') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">psychology</span>
                <div>
                  <h3>{{ t('settings.roleAndModel') }}</h3>
                  <p>{{ roleSummary }}</p>
                </div>
              </div>
              <div class="card-foot">
                <button type="button" class="ghost-btn" @click="roleExpanded = !roleExpanded">
                  {{ roleExpanded ? t('common.collapse') : t('settings.switchPreset') }}
                  <span class="material-symbols-outlined">{{ roleExpanded ? 'expand_less' : 'chevron_right' }}</span>
                </button>
              </div>
              <div v-if="roleExpanded" class="inline-panel">
                <RoleSettingsPanel />
              </div>
            </div>

            <!-- 隐私：内联展开 -->
            <div class="card" :class="{ dimmed: !matches('privacy') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">enhanced_encryption</span>
                <div>
                  <h3>{{ t('profile.privacySettings') }}</h3>
                  <p>{{ t('settings.privacyDesc') }}</p>
                </div>
              </div>
              <div class="card-foot">
                <button type="button" class="ghost-btn" @click="privacyExpanded = !privacyExpanded">
                  {{ privacyExpanded ? t('common.collapse') : t('common.expand') }}
                  <span class="material-symbols-outlined">{{ privacyExpanded ? 'expand_less' : 'chevron_right' }}</span>
                </button>
              </div>
              <div v-if="privacyExpanded" class="inline-panel">
                <div class="privacy-row">
                  <div class="privacy-info">
                    <h4>{{ t('profile.showOnlineStatus') }}</h4>
                    <p>{{ t('profile.showOnlineStatusDesc') }}</p>
                  </div>
                  <button
                    type="button"
                    class="toggle sm"
                    :class="{ on: privacySettings.showOnlineStatus }"
                    role="switch"
                    :aria-checked="privacySettings.showOnlineStatus"
                    @click="privacySettings.showOnlineStatus = !privacySettings.showOnlineStatus"
                  >
                    <span class="toggle-knob"></span>
                  </button>
                </div>
                <div class="privacy-row">
                  <div class="privacy-info">
                    <h4>{{ t('profile.showLastSeen') }}</h4>
                    <p>{{ t('profile.showLastSeenDesc') }}</p>
                  </div>
                  <button
                    type="button"
                    class="toggle sm"
                    :class="{ on: privacySettings.showLastSeen }"
                    role="switch"
                    :aria-checked="privacySettings.showLastSeen"
                    @click="privacySettings.showLastSeen = !privacySettings.showLastSeen"
                  >
                    <span class="toggle-knob"></span>
                  </button>
                </div>
                <div class="privacy-row">
                  <div class="privacy-info">
                    <h4>{{ t('profile.showPhone') }}</h4>
                    <p>{{ t('profile.showPhoneDesc') }}</p>
                  </div>
                  <button
                    type="button"
                    class="toggle sm"
                    :class="{ on: privacySettings.showPhone }"
                    role="switch"
                    :aria-checked="privacySettings.showPhone"
                    @click="privacySettings.showPhone = !privacySettings.showPhone"
                  >
                    <span class="toggle-knob"></span>
                  </button>
                </div>
                <div class="privacy-row">
                  <div class="privacy-info">
                    <h4>{{ t('profile.showEmail') }}</h4>
                    <p>{{ t('profile.showEmailDesc') }}</p>
                  </div>
                  <button
                    type="button"
                    class="toggle sm"
                    :class="{ on: privacySettings.showEmail }"
                    role="switch"
                    :aria-checked="privacySettings.showEmail"
                    @click="privacySettings.showEmail = !privacySettings.showEmail"
                  >
                    <span class="toggle-knob"></span>
                  </button>
                </div>
                <div class="privacy-row column">
                  <div class="privacy-info">
                    <h4>{{ t('privacy.friendRequestMode') }}</h4>
                    <p>{{ t('privacy.friendRequestModeDesc') }}</p>
                  </div>
                  <div class="segmented">
                    <button
                      type="button"
                      :class="{ on: privacySettings.friendRequestMode === 'DIRECT' }"
                      @click="privacySettings.friendRequestMode = 'DIRECT'"
                    >
                      {{ t('privacy.directMode') }}
                    </button>
                    <button
                      type="button"
                      :class="{ on: privacySettings.friendRequestMode === 'VERIFY' }"
                      @click="privacySettings.friendRequestMode = 'VERIFY'"
                    >
                      {{ t('privacy.verifyMode') }}
                    </button>
                  </div>
                </div>
                <div class="inline-actions">
                  <button type="button" class="primary-btn" :disabled="savingPrivacy" @click="savePrivacySettings">
                    {{ savingPrivacy ? t('common.saving') : t('common.save') }}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- 5. 快捷键与更新 -->
        <section id="sec-about" class="settings-section">
          <div class="section-head">
            <div class="section-title">
              <span class="material-symbols-outlined">info</span>
              <h2>{{ t('settings.sectionAbout') }}</h2>
            </div>
            <span class="section-sub">System &amp; Shortcuts</span>
          </div>

          <div class="card-grid cols-2">
            <!-- 快捷键：只读，列出真实键位 -->
            <div class="card" :class="{ dimmed: !matches('shortcuts') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">keyboard</span>
                <div>
                  <h3>{{ t('settings.shortcuts') }}</h3>
                  <p>{{ shortcutPlatform }}</p>
                </div>
              </div>
              <div class="shortcut-list">
                <div v-for="sc in shortcuts" :key="sc.label" class="shortcut-row">
                  <span>{{ t(sc.label) }}</span>
                  <kbd>{{ sc.keys }}</kbd>
                </div>
              </div>
            </div>

            <!-- 版本与更新 -->
            <div class="card" :class="{ dimmed: !matches('update') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">verified</span>
                <div>
                  <h3>Nexus-IM Desktop Client</h3>
                  <p>{{ appVersion ? `v${appVersion}` : t('settings.webMode') }} · {{ t('settings.stableChannel') }}</p>
                </div>
              </div>
              <div class="card-foot">
                <button type="button" class="ghost-btn" :disabled="!isElectron || isCheckingUpdate" @click="checkForUpdates">
                  <span class="material-symbols-outlined" :class="{ spinning: isCheckingUpdate }">sync</span>
                  {{ isCheckingUpdate ? t('settings.checking') : t('settings.checkForUpdates') }}
                </button>
              </div>
              <div v-if="updateInfo" class="update-note">
                {{ updateInfo.hasUpdate ? t('settings.updateAvailable') : t('settings.upToDate') }}
              </div>
            </div>
          </div>
        </section>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { useCompanionStore } from '@/stores/companion'
import { useSettingsStore } from '@/stores/settings'
import { userAPI } from '@/services/api'
import RoleSettingsPanel from '@/components/companion/RoleSettingsPanel.vue'

const router = useRouter()
const { locale, t } = useI18n()
const userStore = useUserStore()
const chatStore = useChatStore()
const companionStore = useCompanionStore()
const settings = useSettingsStore()

const isElectron = computed(() => typeof window !== 'undefined' && !!window.electronAPI)
const isMac = computed(() => (typeof window !== 'undefined' && window.electronAPI?.platform) === 'darwin')

/* ── Header ─────────────────────────────────────────────── */
const appVersion = ref('')
const searchQuery = ref('')
const searchInput = ref(null)

/* ── Navigation ─────────────────────────────────────────── */
const activeSection = ref('general')
const contentEl = ref(null)

const navItems = [
  { key: 'general', icon: 'tune', labelKey: 'settings.sectionGeneral' },
  { key: '3dpet', icon: 'smart_toy', labelKey: 'settings.nav3d', badge: '3D' },
  { key: 'notifs', icon: 'notifications_active', labelKey: 'settings.navNotifs' },
  { key: 'ai', icon: 'psychology', labelKey: 'settings.navAi' },
  { key: 'privacy', icon: 'lock', labelKey: 'settings.navPrivacy' },
  { key: 'shortcuts', icon: 'keyboard', labelKey: 'settings.navShortcuts', badge: '⌘/Ctrl' },
  { key: 'about', icon: 'info', labelKey: 'settings.navAbout' }
]

/** Map a nav key to the section element it scrolls to. */
const sectionIdFor = (key) => {
  if (key === 'privacy') return 'sec-ai'
  if (key === 'shortcuts') return 'sec-about'
  return `sec-${key}`
}

// Set while a programmatic scroll is in flight, so the scroll handler does not
// fight the click and snap the highlight back to the previous section.
let lockActiveUntil = 0

const scrollTo = (key) => {
  activeSection.value = key
  lockActiveUntil = Date.now() + 900
  const el = document.getElementById(sectionIdFor(key))
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** Highlight the section currently in view. */
const onScroll = () => {
  const host = contentEl.value
  if (!host) return
  if (Date.now() < lockActiveUntil) return
  const top = host.getBoundingClientRect().top
  let current = 'general'
  for (const item of navItems) {
    const el = document.getElementById(sectionIdFor(item.key))
    if (el && el.getBoundingClientRect().top - top <= 80) current = item.key
  }
  activeSection.value = current
}

/* ── Search: filters cards, not a fake box ──────────────── */
/** Which searchable keywords belong to which card. */
const CARD_KEYWORDS = {
  language: ['language', '语言', 'locale'],
  hourCycle: ['time', '时间', 'hour', '24', '12'],
  desktopPet: ['pet', '宠物', '3d', 'vrm'],
  notifications: ['notification', '通知'],
  unread: ['unread', '未读', 'badge', '角标'],
  role: ['role', '角色', 'model', '模型'],
  privacy: ['privacy', '隐私', 'security', '安全'],
  shortcuts: ['shortcut', '快捷键', 'keyboard'],
  update: ['update', '更新', 'version', '版本']
}

const matches = (key) => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return true
  const words = CARD_KEYWORDS[key] || []
  // Only forward matching: a keyword contains the query. The reverse direction
  // made short keywords (e.g. "3d") match unrelated cards.
  return words.some((w) => w.toLowerCase().includes(q))
}

/* ── Language ───────────────────────────────────────────── */
const currentLocale = ref(localStorage.getItem('locale') || 'zh')

const languageOptions = computed(() => [
  { value: 'zh', label: t('settings.langZh') },
  { value: 'en', label: t('settings.langEn') }
])

const handleLanguageChange = () => {
  locale.value = currentLocale.value
  localStorage.setItem('locale', currentLocale.value)
}

/* ── Hour cycle preview ─────────────────────────────────── */
const timePreview = computed(() => settings.format(new Date(), 'HH:mm'))

/* ── GPU / memory ───────────────────────────────────────── */
const gpuStatus = reactive({
  available: false,
  memoryMB: 0,
  peakMemoryMB: 0,
  gpuAccelerated: false
})

let gpuTimer = null

const refreshGpu = async () => {
  if (!isElectron.value || !window.electronAPI.getGpuStatus) return
  try {
    const status = await window.electronAPI.getGpuStatus()
    Object.assign(gpuStatus, status)
  } catch {
    gpuStatus.available = false
  }
}

const gpuText = computed(() => {
  if (!isElectron.value) return '—'
  if (!gpuStatus.available) return '—'
  return `${gpuStatus.memoryMB} MB`
})

const gpuBarWidth = computed(() => {
  // Bar is relative to a 2 GB budget: enough to show movement without pretending
  // to know the machine's total VRAM.
  if (!gpuStatus.available) return '0%'
  const pct = Math.min(100, (gpuStatus.memoryMB / 2048) * 100)
  return `${pct.toFixed(1)}%`
})

const gpuEngineText = 'WebGL 渲染引擎'
const gpuEngineState = computed(() => {
  if (!isElectron.value) return 'N/A'
  if (!gpuStatus.available) return '—'
  return gpuStatus.gpuAccelerated ? 'ENABLED' : 'SOFTWARE'
})

/* ── Role / privacy inline panels ───────────────────────── */
const roleExpanded = ref(false)
const privacyExpanded = ref(false)
const savingPrivacy = ref(false)

const roleSummary = computed(() => {
  const role = companionStore.activeRole
  if (!role) return t('settings.roleEmpty')
  return `${role.name || t('companion.defaultName')} · ${t('settings.roleActive')}`
})

const privacySettings = reactive({
  showOnlineStatus: userStore.currentUser?.showOnlineStatus ?? true,
  showLastSeen: userStore.currentUser?.showLastSeen ?? true,
  showPhone: userStore.currentUser?.showPhone ?? false,
  showEmail: userStore.currentUser?.showEmail ?? false,
  friendRequestMode: userStore.currentUser?.friendRequestMode ?? null
})

const savePrivacySettings = async () => {
  savingPrivacy.value = true
  try {
    const response = await userAPI.updatePrivacySettings(userStore.currentUser.id, privacySettings)
    userStore.updatePrivacySettings(response.data)
    privacySettings.friendRequestMode = response.data.friendRequestMode
    ElMessage.success(t('common.savedSuccess'))
  } catch {
    ElMessage.error(t('profile.updateFailed'))
  } finally {
    savingPrivacy.value = false
  }
}

/* ── Unread total (real number, not a mock) ─────────────── */
const unreadTotal = computed(() => {
  const chats = chatStore.chats || []
  const groups = chatStore.groupChats || []
  const sum = [...chats, ...groups].reduce((acc, c) => acc + (c.unreadCount || 0), 0)
  return String(sum)
})

/* ── Shortcuts: the keys we actually bind ───────────────── */
const shortcutPlatform = computed(() => (window.electronAPI?.platform === 'darwin' ? 'macOS' : 'Windows / Linux'))

const shortcuts = computed(() => [
  { label: 'settings.shortcutCommand', keys: window.electronAPI?.platform === 'darwin' ? '⌘ K' : 'Ctrl K' },
  { label: 'settings.shortcutBack', keys: 'ESC' }
])

/* ── Update check ───────────────────────────────────────── */
const isCheckingUpdate = ref(false)
const updateInfo = ref(null)

const checkForUpdates = async () => {
  if (!isElectron.value || isCheckingUpdate.value) return
  isCheckingUpdate.value = true
  try {
    const result = await window.electronAPI.checkForUpdates()
    updateInfo.value = result
    if (!result.hasUpdate) ElMessage.success(t('settings.upToDate'))
  } catch {
    ElMessage.error(t('settings.checkUpdateFailed'))
  } finally {
    isCheckingUpdate.value = false
  }
}

/* ── Reset / logout / back ──────────────────────────────── */
const resetDefaults = () => {
  settings.resetToDefaults()
  currentLocale.value = 'zh'
  handleLanguageChange()
  ElMessage.success(t('settings.resetDone'))
}

const handleLogout = async () => {
  await userStore.logout()
  router.push('/login')
}

const goBack = () => router.back()

/* ── Keyboard: ESC back, ⌘K focus search ────────────────── */
const onKeydown = (event) => {
  const mod = event.metaKey || event.ctrlKey
  if (mod && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    searchInput.value?.focus()
    return
  }
  if (event.key === 'Escape' && document.activeElement !== searchInput.value) {
    goBack()
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)

  const profile = await userStore.loadUserProfile()
  if (profile) {
    privacySettings.showOnlineStatus = profile.showOnlineStatus
    privacySettings.showLastSeen = profile.showLastSeen
    privacySettings.showPhone = profile.showPhone
    privacySettings.showEmail = profile.showEmail
    privacySettings.friendRequestMode = profile.friendRequestMode ?? privacySettings.friendRequestMode
  }

  if (isElectron.value && window.electronAPI.getAppVersion) {
    try {
      appVersion.value = await window.electronAPI.getAppVersion()
    } catch {
      /* version chip simply stays hidden */
    }
  }

  await nextTick()
  await refreshGpu()
  if (isElectron.value) gpuTimer = setInterval(refreshGpu, 5000)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  if (gpuTimer) clearInterval(gpuTimer)
})
</script>

<style scoped>
/* ── Root: full window, no centred card ───────────────────── */
.settings-root {
  display: flex;
  flex-direction: column;
  height: 100vh;
  width: 100%;
  background: #131316;
  color: #e4e1e6;
  font-family: Geist, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  overflow: hidden;
}

/* ── Header ───────────────────────────────────────────────── */
.settings-header {
  height: 48px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: rgba(13, 13, 17, 0.65);
  backdrop-filter: blur(28px) saturate(180%);
  -webkit-backdrop-filter: blur(28px) saturate(180%);
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
  -webkit-app-region: drag;
  user-select: none;
  z-index: 30;
}

.settings-header button,
.settings-header input,
.settings-header .header-search {
  -webkit-app-region: no-drag;
}

/* On macOS the spacer must start at the window edge so the traffic lights sit
   exactly where the system puts them. */
.settings-header:has(.traffic-spacer) {
  padding-left: 0;
}

.header-left,
.header-center,
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Reserved for the macOS traffic lights, which float over this frameless
   window. The trailing hairline matches the reference design's divider. */
.traffic-spacer {
  /* Measured against the live window: the third light ends at x=65, so 72px puts
     the hairline a few pixels past it instead of floating in dead space. */
  width: 72px;
  height: 100%;
  flex-shrink: 0;
  border-right: 1px solid rgba(255, 255, 255, 0.1);
}

.back-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  color: #c4c7c8;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.09);
  box-shadow: inset 0 0.5px 0 rgba(255, 255, 255, 0.12);
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}

.back-pill:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}

.back-pill .material-symbols-outlined {
  font-size: 16px;
}

.back-pill kbd {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 9px;
  padding: 1px 4px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: rgba(196, 199, 200, 0.8);
}

.brand-mark {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.15);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  flex-shrink: 0;
}

.brand-name {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: #fff;
}

.crumb-sep {
  color: rgba(255, 255, 255, 0.2);
  font-size: 12px;
}

.crumb-current {
  font-size: 12px;
  font-weight: 500;
  color: #fff;
}

.version-chip {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: rgba(196, 199, 200, 0.7);
}

.header-search {
  position: relative;
  display: flex;
  align-items: center;
}

.header-search .material-symbols-outlined {
  position: absolute;
  left: 8px;
  font-size: 16px;
  color: rgba(196, 199, 200, 0.6);
  pointer-events: none;
}

.header-search input {
  width: 144px;
  height: 28px;
  padding: 0 26px 0 24px;
  border-radius: 999px;
  font-size: 11px;
  color: #fff;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.25);
  outline: none;
  transition: border-color 0.15s;
}

.header-search input::placeholder {
  color: rgba(196, 199, 200, 0.4);
}

.header-search input:focus {
  border-color: rgba(255, 255, 255, 0.3);
}

.header-search kbd {
  position: absolute;
  right: 8px;
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 9px;
  color: rgba(196, 199, 200, 0.6);
  pointer-events: none;
}

.reset-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 10.5px;
  font-weight: 500;
  color: #c4c7c8;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.07);
  cursor: pointer;
  transition: color 0.15s;
}

.reset-pill:hover {
  color: #fff;
}

.reset-pill .material-symbols-outlined {
  font-size: 16px;
}

/* ── Body: rail + content ─────────────────────────────────── */
.settings-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

/* ── Left rail ────────────────────────────────────────────── */
.settings-rail {
  width: 256px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  background: #0e0e11;
  border-right: 1px solid rgba(255, 255, 255, 0.08);
}

.rail-nav {
  padding: 8px;
  overflow-y: auto;
}

.rail-caption {
  padding: 6px 12px 4px;
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 9px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: rgba(196, 199, 200, 0.45);
}

.rail-item {
  width: 100%;
  height: 32px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  margin-bottom: 2px;
  border-radius: 12px;
  border: 1px solid transparent;
  background: transparent;
  color: #c4c7c8;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.rail-item:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.04);
}

.rail-item.active {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(255, 255, 255, 0.1);
}

.rail-item .material-symbols-outlined {
  font-size: 16px;
  flex-shrink: 0;
}

.rail-label {
  flex: 1;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rail-badge {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 9px;
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.8);
}

.rail-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 6px rgba(255, 255, 255, 0.9);
  flex-shrink: 0;
}

/* ── Rail footer ──────────────────────────────────────────── */
.rail-footer {
  padding: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(14, 14, 17, 0.4);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.gpu-block {
  padding: 8px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.gpu-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 10px;
}

.gpu-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: rgba(196, 199, 200, 0.8);
}

.gpu-label .material-symbols-outlined {
  font-size: 16px;
}

.gpu-value {
  color: #fff;
  font-weight: 500;
}

.gpu-bar {
  width: 100%;
  height: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}

.gpu-bar-fill {
  height: 100%;
  background: #fff;
  transition: width 0.3s ease;
}

.gpu-foot {
  display: flex;
  justify-content: space-between;
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 9px;
  color: rgba(196, 199, 200, 0.6);
  padding-top: 2px;
}

.gpu-state {
  font-weight: 500;
  color: rgba(196, 199, 200, 0.6);
}

.gpu-state.on {
  color: #34d399;
}

.logout-btn {
  width: 100%;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 500;
  color: #fca5a5;
  background: rgba(220, 38, 38, 0.1);
  border: 1px solid rgba(248, 113, 113, 0.2);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.logout-btn:hover {
  background: rgba(220, 38, 38, 0.16);
  color: #fecaca;
}

.logout-btn .material-symbols-outlined {
  font-size: 16px;
}

/* ── Content ──────────────────────────────────────────────── */
.settings-content {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 24px;
  background: #131316;
  scroll-behavior: smooth;
}

.settings-section {
  margin-bottom: 28px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  padding-bottom: 6px;
  margin-bottom: 10px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.section-title .material-symbols-outlined {
  font-size: 16px;
  color: #fff;
}

.section-title h2 {
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  letter-spacing: -0.01em;
  margin: 0;
}

.section-sub {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 10px;
  letter-spacing: 0.04em;
  color: rgba(196, 199, 200, 0.7);
}

/* ── Card grids ───────────────────────────────────────────── */
.card-grid {
  display: grid;
  gap: 10px;
}

.card-grid.cols-1 {
  grid-template-columns: 1fr;
}

.card-grid.cols-2 {
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
}

.card-grid.cols-3 {
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  margin-top: 10px;
}

/* ── Cards ────────────────────────────────────────────────── */
.card {
  border-radius: 12px;
  padding: 12px;
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid rgba(255, 255, 255, 0.07);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
  transition: border-color 0.15s;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.card:hover {
  border-color: rgba(255, 255, 255, 0.2);
}

.card.dimmed {
  opacity: 0.32;
  pointer-events: none;
}

.card-row {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-heading {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 0;
}

.card-heading > .material-symbols-outlined {
  font-size: 16px;
  color: #fff;
  margin-top: 1px;
  flex-shrink: 0;
}

.card-heading h3 {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 500;
  color: #fafafa;
  margin: 0 0 2px;
}

.card-heading p {
  font-size: 10.5px;
  line-height: 1.5;
  color: #a1a1aa;
  margin: 0;
}

.chip {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 9px;
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.75);
  font-weight: 400;
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 6px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  margin-top: auto;
}

/* ── Mini stat cards ──────────────────────────────────────── */
.mini-card {
  border-radius: 8px;
  padding: 8px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.05);
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.mini-label {
  font-size: 10px;
  color: #a1a1aa;
}

.mini-value {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 13px;
  font-weight: 500;
  color: #fff;
}

.mini-note {
  font-size: 9.5px;
  color: rgba(196, 199, 200, 0.55);
  line-height: 1.4;
}

/* ── Native select ────────────────────────────────────────── */
.select-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.native-select {
  width: 100%;
  height: 32px;
  padding: 0 28px 0 10px;
  border-radius: 8px;
  font-size: 11.5px;
  color: #fafafa;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  outline: none;
  appearance: none;
  cursor: pointer;
}

.native-select:focus {
  border-color: rgba(255, 255, 255, 0.3);
}

.native-select option {
  background: #1b1b1e;
  color: #fafafa;
}

.select-caret {
  position: absolute;
  right: 8px;
  font-size: 15px;
  color: rgba(196, 199, 200, 0.6);
  pointer-events: none;
}

/* ── Segmented control ────────────────────────────────────── */
.segmented {
  display: inline-flex;
  padding: 2px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.07);
  gap: 2px;
}

.segmented button {
  flex: 1;
  height: 26px;
  padding: 0 10px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: #a1a1aa;
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s, color 0.15s;
}

.segmented button.on {
  background: #fafafa;
  color: #09090b;
}

.preview {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 10px;
  color: rgba(196, 199, 200, 0.6);
  margin: 0;
}

/* ── Toggle ───────────────────────────────────────────────── */
.toggle {
  width: 44px;
  height: 24px;
  flex-shrink: 0;
  padding: 2px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.1);
  cursor: pointer;
  transition: background 0.2s, border-color 0.2s;
  display: flex;
  align-items: center;
}

.toggle.on {
  background: #fafafa;
  border-color: #fafafa;
}

.toggle:disabled {
  opacity: 0.4;
  cursor: default;
}

.toggle.sm {
  width: 38px;
  height: 20px;
}

.toggle-knob {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #6f7274;
  transition: transform 0.2s, background 0.2s;
  display: block;
}

.toggle.sm .toggle-knob {
  width: 14px;
  height: 14px;
}

.toggle.on .toggle-knob {
  background: #09090b;
  transform: translateX(20px);
}

.toggle.sm.on .toggle-knob {
  transform: translateX(18px);
}

/* ── Buttons ──────────────────────────────────────────────── */
.ghost-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 8px;
  border-radius: 6px;
  font-size: 10.5px;
  font-weight: 500;
  color: #a1a1aa;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.08);
  cursor: pointer;
  transition: color 0.15s, background 0.15s, border-color 0.15s;
}

.ghost-btn:hover:not(:disabled) {
  color: #fafafa;
  background: rgba(255, 255, 255, 0.05);
  border-color: rgba(255, 255, 255, 0.2);
}

.ghost-btn:disabled {
  opacity: 0.45;
  cursor: default;
}

.ghost-btn .material-symbols-outlined {
  font-size: 16px;
}

.primary-btn {
  height: 28px;
  padding: 0 14px;
  border-radius: 8px;
  font-size: 11.5px;
  font-weight: 500;
  color: #09090b;
  background: #fafafa;
  border: none;
  cursor: pointer;
  transition: opacity 0.15s;
}

.primary-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.primary-btn:disabled {
  opacity: 0.5;
  cursor: default;
}

/* ── Inline panels ────────────────────────────────────────── */
.inline-panel {
  margin-top: 8px;
  padding-top: 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.privacy-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.privacy-row.column {
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
}

.privacy-info h4 {
  font-size: 11.5px;
  font-weight: 500;
  color: #fafafa;
  margin: 0 0 2px;
}

.privacy-info p {
  font-size: 10px;
  color: #a1a1aa;
  margin: 0;
  line-height: 1.45;
}

.inline-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 4px;
}

/* ── Shortcut list ────────────────────────────────────────── */
.shortcut-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.shortcut-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: #c4c7c8;
  padding: 3px 0;
}

.shortcut-row kbd {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #fafafa;
}

.value-note {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 14px;
  font-weight: 500;
  color: #fafafa;
}

.update-note {
  font-size: 10.5px;
  color: #a1a1aa;
  padding-top: 4px;
}

.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* ── Responsive: rail collapses under 900px ───────────────── */
@media (max-width: 900px) {
  .settings-rail {
    width: 200px;
  }

  .settings-content {
    padding: 16px;
  }

  .header-search {
    display: none;
  }

  /* Narrow windows: keep the back pill but drop its label so the traffic lights
     and the pill never compete for the same pixels. */
  .back-pill span:not(.material-symbols-outlined) {
    display: none;
  }

  .header-center .version-chip {
    display: none;
  }
}
</style>

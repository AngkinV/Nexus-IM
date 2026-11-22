<template>
  <!-- Prism global rail: 200px "Linear minimal" list <-> 64px floating glass dock.
       The notch button on the right border (and the [ key) flips between them. -->
  <aside
    class="nav-rail shrink-0 h-full bg-surface-container-lowest border-r border-outline-variant/30 flex flex-col justify-between select-none"
    :class="{ 'is-mac': isMac, 'is-collapsed': navCollapsed }"
  >
    <!-- Border notch toggle -->
    <button
      class="rail-notch no-drag"
      type="button"
      :title="navCollapsed ? $t('rail.expand') : $t('rail.collapse')"
      @click="toggleNav"
    >
      <span class="material-symbols-outlined">{{ navCollapsed ? 'chevron_right' : 'chevron_left' }}</span>
    </button>

    <!-- Top: brand -->
    <div class="flex flex-col w-full min-w-0 shrink-0">
      <div class="rail-brand drag-region h-14 flex items-center border-b border-outline-variant/20 overflow-hidden shrink-0">
        <div class="rail-brand-inner flex items-center min-w-0 w-full">
          <div
            class="rail-logo no-drag w-8 h-8 rounded-lg bg-white flex items-center justify-center shrink-0 cursor-pointer"
            :title="navCollapsed ? 'Prism' : undefined"
            @click="navCollapsed && toggleNav()"
          >
            <span class="material-symbols-outlined text-zinc-950 text-[18px]">polyline</span>
          </div>
          <div v-if="!navCollapsed" class="rail-expanded flex items-center justify-between flex-1 min-w-0">
            <span class="font-headline-md text-[15px] font-semibold text-primary tracking-tight truncate">Prism</span>
            <button
              class="no-drag w-6 h-6 rounded flex items-center justify-center text-on-surface-variant hover:text-primary hover:bg-surface-container-high transition-colors"
              :title="brandTitle"
              type="button"
              @click="onBrandAction"
            >
              <span class="material-symbols-outlined text-[16px]">{{ brandIcon }}</span>
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Middle: dock shelf (collapsed) or navigation list (expanded) -->
    <div class="rail-middle flex-1 flex flex-col items-center w-full py-3 min-h-0">
      <!-- Collapsed: floating glass dock -->
      <div v-if="navCollapsed" class="rail-dock-wrap w-full flex flex-col items-center justify-center my-auto">
        <div
          ref="dockShelf"
          class="rail-dock w-11 rounded-2xl py-2 px-1 backdrop-blur-xl flex flex-col items-center gap-2"
          @mousemove="onDockMove"
          @mouseleave="resetDock"
        >
          <button
            v-for="(item, index) in navItems"
            :key="item.key"
            :ref="(el) => setDockItem(el, index)"
            class="dock-item w-9 h-9 flex items-center justify-center rounded-xl cursor-pointer relative"
            :class="activeView === item.key ? 'is-active' : ''"
            :data-dock-index="index"
            :title="item.label"
            type="button"
            @click="setActiveView(item.key)"
          >
            <span class="material-symbols-outlined text-[18px]">{{ item.icon }}</span>
            <span
              v-if="item.key === 'chats' && chatStore.totalUnreadCount > 0"
              class="dock-dot absolute top-1 right-1 w-1.5 h-1.5 rounded-full"
            ></span>
          </button>
        </div>
      </div>

      <!-- Expanded: navigation list -->
      <nav v-else class="rail-nav flex flex-col gap-1 w-full pt-1 overflow-y-auto">
        <div class="px-2.5 pt-1 pb-2 text-on-surface-variant/40 font-label-sm text-[11px] uppercase tracking-wider font-medium flex items-center justify-between">
          <span>{{ $t('nav.sectionLabel') }}</span>
          <span class="w-1 h-1 rounded-full bg-outline-variant/40"></span>
        </div>
        <a
          v-for="item in navItems"
          :key="item.key"
          class="rail-item relative flex items-center gap-2.5 px-2.5 py-2 rounded-lg cursor-pointer transition-colors"
          :class="
            activeView === item.key
              ? 'is-active bg-surface-container-high/60 text-primary font-medium text-label-md border border-outline-variant/20'
              : 'text-on-surface-variant hover:text-primary hover:bg-surface-container-low font-normal text-label-md border border-transparent'
          "
          :aria-current="activeView === item.key ? 'page' : undefined"
          href="#"
          @click.prevent="setActiveView(item.key)"
        >
          <span class="material-symbols-outlined text-[18px] shrink-0">{{ item.icon }}</span>
          <span class="rail-item-label truncate flex-1 text-left">{{ item.label }}</span>
          <span
            v-if="item.key === 'chats' && chatStore.totalUnreadCount > 0"
            class="rail-badge shrink-0 h-4 min-w-[1rem] px-1.5 rounded-full bg-primary text-on-primary font-code text-[10px] font-bold flex items-center justify-center"
          >{{ chatStore.totalUnreadCount > 99 ? '99+' : chatStore.totalUnreadCount }}</span>
          <span
            v-else-if="item.key === 'ai'"
            class="shrink-0 px-1 py-0.5 rounded bg-surface-container text-on-surface-variant font-code text-[10px]"
          >NEW</span>
        </a>
      </nav>
    </div>

    <!-- Bottom: user pod -->
    <div class="rail-foot no-drag border-t border-outline-variant/20 p-2.5 flex flex-col justify-center bg-surface-container-lowest shrink-0 overflow-hidden">
      <!-- Collapsed pod -->
      <div v-if="navCollapsed" class="flex items-center justify-center w-full">
        <div class="dock-user relative cursor-pointer group/user flex items-center justify-center" @click="goToProfile">
          <div class="w-8 h-8 rounded-full bg-white flex items-center justify-center shrink-0 ring-1 ring-white/20 overflow-hidden">
            <img
              v-if="userStore.currentUser?.avatar"
              class="w-full h-full object-cover grayscale"
              :src="userStore.currentUser.avatar"
              alt=""
            />
            <span v-else class="material-symbols-outlined text-zinc-950 text-[17px]">person</span>
          </div>
          <span class="absolute bottom-0 right-0 w-2 h-2 rounded-full bg-emerald-400 ring-2 ring-surface-container-lowest"></span>
          <div class="dock-tip">{{ displayName }}</div>
        </div>
      </div>

      <!-- Expanded row -->
      <div v-else class="flex items-center justify-between gap-2 min-w-0">
        <div class="flex items-center gap-2.5 min-w-0 flex-1">
          <div class="relative shrink-0">
            <div class="w-8 h-8 rounded-full bg-white flex items-center justify-center ring-1 ring-white/20 overflow-hidden">
              <img
                v-if="userStore.currentUser?.avatar"
                class="w-full h-full object-cover grayscale"
                :src="userStore.currentUser.avatar"
                alt=""
              />
              <span v-else class="material-symbols-outlined text-zinc-950 text-[17px]">person</span>
            </div>
            <span class="absolute bottom-0 right-0 w-2 h-2 rounded-full bg-emerald-400 ring-2 ring-surface-container-lowest"></span>
          </div>
          <div class="rail-user-meta flex flex-col min-w-0">
            <span class="font-label-md text-primary text-xs font-medium truncate">{{ displayName }}</span>
            <span class="font-code text-[10px] text-on-surface-variant/60 truncate">{{ displayMail }}</span>
          </div>
        </div>
        <button
          class="w-7 h-7 rounded flex items-center justify-center text-on-surface-variant hover:text-primary hover:bg-surface-container-high transition-colors shrink-0"
          :title="$t('nav.settings')"
          type="button"
          @click="goToSettings"
        >
          <span class="material-symbols-outlined text-[17px]">settings</span>
        </button>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'

const router = useRouter()
const { t } = useI18n()
const userStore = useUserStore()
const chatStore = useChatStore()

const activeView = inject('activeView', ref('chats'))
const setActiveView = inject('setActiveView', () => {})
const openNewChat = inject('openNewChat', null)
const requestAddContact = inject('requestAddContact', null)

// Collapse state lives in Main.vue so the top bar can mirror it.
const navCollapsed = inject('navCollapsed', ref(false))
const toggleNav = inject('toggleNav', () => {})

const navItems = computed(() => [
  { key: 'chats', icon: 'chat_bubble', label: t('leftPanel.navChats') },
  { key: 'contacts', icon: 'person_book', label: t('leftPanel.navContacts') },
  { key: 'groups', icon: 'group', label: t('leftPanel.navGroups') },
  { key: 'ai', icon: 'auto_awesome', label: t('leftPanel.navAi') }
])

const brandIcon = computed(() =>
  activeView.value === 'contacts' ? 'person_add' : 'edit_square'
)
const brandTitle = computed(() =>
  activeView.value === 'contacts' ? t('leftPanel.addContact') : t('leftPanel.newConversation')
)

const onBrandAction = () => {
  // On the contacts pane the button reads "添加联系人", so it opens that dialog.
  if (activeView.value === 'contacts' && typeof requestAddContact === 'function') {
    requestAddContact()
    return
  }
  if (typeof openNewChat === 'function') {
    openNewChat()
    return
  }
  setActiveView('contacts')
}

const displayName = computed(() => {
  const u = userStore.currentUser
  return u?.nickname || u?.username || 'Developer'
})

const displayMail = computed(() => {
  const u = userStore.currentUser
  return u?.email || ''
})

// ── macOS dock lens ─────────────────────────────────────────────────────────
// Pure gaussian bell curve: scale peaks at the cursor row and falls off with
// sigma, plus a small magnetic translateY so items lean toward the pointer.
const dockShelf = ref(null)
const dockItems = []

const setDockItem = (el, index) => {
  dockItems[index] = el
}
const MAX_SCALE = 0.38
const SIGMA = 52

const onDockMove = (event) => {
  const mouseY = event.clientY
  dockItems.forEach((el) => {
    if (!el) return
    const rect = el.getBoundingClientRect()
    const center = rect.top + rect.height / 2
    const dist = mouseY - center
    const factor = Math.exp(-(dist * dist) / (2 * SIGMA * SIGMA))
    const scale = 1 + MAX_SCALE * factor
    const shiftY = -(dist * 0.1) * factor
    el.classList.remove('dock-rebounding')
    el.style.transform = `scale(${scale.toFixed(3)}) translateY(${shiftY.toFixed(2)}px)`
    el.style.zIndex = factor > 0.08 ? '25' : '10'
  })
}

const resetDock = () => {
  dockItems.forEach((el) => {
    if (!el) return
    el.classList.add('dock-rebounding')
    el.style.transform = 'scale(1) translateY(0px)'
    el.style.zIndex = ''
  })
}

const isMac = ref(false)

onMounted(() => {
  if (window.electronAPI) {
    isMac.value = window.electronAPI.platform === 'darwin'
  }
})

const goToSettings = () => router.push('/settings')
const goToProfile = () => router.push('/profile')
</script>

<style scoped>
.drag-region {
  -webkit-app-region: drag;
}

.no-drag {
  -webkit-app-region: no-drag;
}

/* ── Rail shell ──────────────────────────────────────────── */
.nav-rail {
  position: relative;
  width: 200px;
  /* The notch button hangs past the right edge, so the rail itself must not clip. */
  overflow: visible;
  transition: width 320ms cubic-bezier(0.16, 1, 0.3, 1);
  z-index: 12;
}

.nav-rail.is-collapsed {
  width: 64px;
}

.rail-brand {
  padding: 0 14px;
}

.nav-rail.is-collapsed .rail-brand {
  padding: 0;
  justify-content: center;
}

.rail-brand-inner {
  min-width: 0;
}

.nav-rail.is-collapsed .rail-brand-inner {
  justify-content: center;
}

.rail-logo {
  box-shadow: 0 0 12px rgba(255, 255, 255, 0.12);
  transition: transform 0.15s ease;
}

.rail-logo:hover {
  transform: scale(1.05);
}

/* macOS traffic lights: expanded pushes the brand text right, collapsed needs
   vertical clearance so the lights never sit on top of the logo. */
.nav-rail.is-mac .rail-brand {
  padding-left: 78px;
}

.nav-rail.is-mac.is-collapsed {
  padding-top: 34px;
}

.nav-rail.is-mac.is-collapsed .rail-brand {
  padding-left: 0;
}

/* ── Notch toggle ────────────────────────────────────────── */
.rail-notch {
  position: absolute;
  right: -12px;
  top: 64px;
  width: 24px;
  height: 24px;
  border-radius: 9999px;
  background: #2a2a2d;
  border: 1px solid rgba(68, 71, 72, 0.5);
  color: rgba(255, 255, 255, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 50;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.6);
  transition: transform 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}

.rail-notch .material-symbols-outlined {
  font-size: 13px;
  transition: transform 0.3s ease;
}

.rail-notch:hover {
  color: #ffffff;
  border-color: rgba(255, 255, 255, 0.4);
  transform: scale(1.1);
}

.rail-notch:active {
  transform: scale(0.95);
}

/* ── Middle area ─────────────────────────────────────────── */
.rail-middle {
  padding-left: 4px;
  padding-right: 4px;
}

.rail-nav {
  min-height: 0;
}

.rail-nav::-webkit-scrollbar {
  width: 0;
}

/* ── Collapsed glass dock ────────────────────────────────── */
.rail-dock {
  background: rgba(27, 27, 30, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 4px 20px 0 rgba(0, 0, 0, 0.5), 0 0 0 1px rgba(255, 255, 255, 0.07) inset;
}

.dock-item {
  color: rgba(255, 255, 255, 0.6);
  background: transparent;
  border: none;
  transition: color 0.15s ease, background 0.15s ease, transform 320ms cubic-bezier(0.175, 0.885, 0.32, 1.25);
  transform-origin: center center;
  will-change: transform;
}

.dock-item:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.1);
}

.dock-item.is-active {
  background: #ffffff;
  color: #09090b;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
}

.dock-item.is-active:hover {
  background: #ffffff;
  color: #09090b;
}

.dock-dot {
  background: #34d399;
  box-shadow: 0 0 0 2px #09090b;
}

.dock-rebounding {
  transition: transform 320ms cubic-bezier(0.175, 0.885, 0.32, 1.25);
}

/* Collapsed user pod hover lens + tooltip */
.dock-user {
  transition: transform 320ms cubic-bezier(0.175, 0.885, 0.32, 1.25);
}

.dock-user:hover {
  transform: scale(1.22);
}

.dock-tip {
  position: absolute;
  left: 46px;
  top: 50%;
  transform: translateY(-50%) translateX(4px);
  padding: 4px 10px;
  border-radius: 6px;
  background: rgba(53, 52, 56, 0.95);
  backdrop-filter: blur(8px);
  color: #ffffff;
  font-size: 12px;
  line-height: 16px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  white-space: nowrap;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease, transform 0.15s ease;
  z-index: 60;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.6);
}

.dock-user:hover .dock-tip {
  opacity: 1;
  transform: translateY(-50%) translateX(0);
}

/* ── Foot ────────────────────────────────────────────────── */
.nav-rail.is-collapsed .rail-foot {
  padding: 10px 0;
}

/* Phones keep the narrow icon strip; the notch has no room there. */
@media (max-width: 768px) {
  .nav-rail,
  .nav-rail.is-collapsed {
    width: 64px;
  }

  .rail-notch {
    display: none;
  }

  .rail-brand {
    display: none;
  }

  .rail-user-meta {
    display: none;
  }
}
</style>

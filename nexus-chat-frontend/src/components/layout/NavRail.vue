<template>
  <!-- Prism global rail: 200px "Linear minimal" list <-> 64px floating glass dock.
       The notch button on the right border (and the [ key) flips between them.

       Both forms are ONE set of elements. Collapsing never swaps DOM: the same
       logo, nav items and avatar morph between the two layouts with CSS
       transitions, so the icons glide from the top of the rail to the centred
       dock (and back) instead of teleporting there on the first frame.

       The vertical travel is a FLIP: layout snaps to its final place and one
       compositor-driven `translate` carries each piece there (see glide()). -->
  <aside
    class="nav-rail shrink-0 h-full bg-surface-container-lowest border-r border-outline-variant/30 flex flex-col justify-between select-none"
    :class="{ 'is-mac': isMac, 'is-collapsed': collapsed }"
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
      <div ref="brandEl" class="rail-brand drag-region h-14 flex items-center border-b border-outline-variant/20 overflow-hidden shrink-0">
        <div class="rail-brand-inner flex items-center min-w-0 w-full">
          <div
            class="rail-logo no-drag w-8 h-8 rounded-lg bg-white flex items-center justify-center shrink-0 cursor-pointer"
            :title="collapsed ? 'Prism' : undefined"
            @click="navCollapsed && toggleNav()"
          >
            <span class="material-symbols-outlined text-zinc-950 text-[18px]">polyline</span>
          </div>
          <div class="rail-reveal flex items-center justify-between flex-1 min-w-0">
            <span class="font-headline-md text-[15px] font-semibold text-primary tracking-tight truncate">Prism</span>
            <button
              class="no-drag w-6 h-6 rounded flex items-center justify-center shrink-0 text-on-surface-variant hover:text-primary hover:bg-surface-container-high transition-colors"
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

    <!-- Middle: one navigation list. Expanded it sits at the top of the rail;
         collapsed it becomes the glass dock, centred vertically. -->
    <div class="rail-middle flex-1 w-full min-h-0">
      <nav
        ref="dockShelf"
        class="rail-list"
        @mousemove="onDockMove"
        @mouseleave="resetDock"
      >
        <div class="rail-section text-on-surface-variant/40 font-label-sm text-[11px] uppercase tracking-wider font-medium">
          <span>{{ $t('nav.sectionLabel') }}</span>
          <span class="w-1 h-1 rounded-full bg-outline-variant/40"></span>
        </div>
        <a
          v-for="(item, index) in navItems"
          :key="item.key"
          :ref="(el) => setDockItem(el, index)"
          class="rail-item"
          :class="{ 'is-active': activeView === item.key }"
          :aria-current="activeView === item.key ? 'page' : undefined"
          :aria-label="item.label"
          :title="collapsed ? item.label : undefined"
          :data-dock-index="index"
          href="#"
          @click.prevent="setActiveView(item.key)"
        >
          <span class="material-symbols-outlined text-[18px] shrink-0">{{ item.icon }}</span>
          <span class="rail-item-label">{{ item.label }}</span>
          <span v-if="item.key === 'chats' && chatStore.totalUnreadCount > 0" class="rail-item-tail">
            <span
              class="rail-badge shrink-0 h-4 min-w-[1rem] px-1.5 rounded-full bg-primary text-on-primary font-code text-[10px] font-bold flex items-center justify-center"
            >{{ chatStore.totalUnreadCount > 99 ? '99+' : chatStore.totalUnreadCount }}</span>
          </span>
          <span v-else-if="item.key === 'ai'" class="rail-item-tail">
            <span class="shrink-0 px-1 py-0.5 rounded bg-surface-container text-on-surface-variant font-code text-[10px]">NEW</span>
          </span>
          <!-- Collapsed form of the unread badge -->
          <span
            v-if="item.key === 'chats' && chatStore.totalUnreadCount > 0"
            class="dock-dot"
          ></span>
        </a>
      </nav>
    </div>

    <!-- Bottom: user pod -->
    <div class="rail-foot no-drag border-t border-outline-variant/20 flex items-center bg-surface-container-lowest shrink-0 overflow-hidden">
      <div class="rail-user relative shrink-0" @click="collapsed && goToProfile()">
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
        <div class="dock-tip">{{ displayName }}</div>
      </div>
      <div class="rail-user-meta rail-reveal flex flex-col min-w-0 flex-1">
        <span class="font-label-md text-primary text-xs font-medium truncate">{{ displayName }}</span>
        <span class="font-code text-[10px] text-on-surface-variant/60 truncate">{{ displayMail }}</span>
      </div>
      <button
        class="rail-settings rail-reveal w-7 h-7 rounded flex items-center justify-center text-on-surface-variant hover:text-primary hover:bg-surface-container-high shrink-0"
        :title="$t('nav.settings')"
        type="button"
        @click="goToSettings"
      >
        <span class="material-symbols-outlined text-[17px]">settings</span>
      </button>
    </div>
  </aside>
</template>

<script setup>
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
// Phones only ever have room for the narrow strip, so they always get the dock.
const isMobile = inject('isMobile', ref(false))
const collapsed = computed(() => navCollapsed.value || isMobile.value)

const navItems = computed(() => [
  { key: 'chats', icon: 'chat_bubble', label: t('leftPanel.navChats') },
  { key: 'contacts', icon: 'person_book', label: t('leftPanel.navContacts') },
  { key: 'groups', icon: 'group', label: t('leftPanel.navGroups') },
  { key: 'ai', icon: 'auto_awesome', label: t('leftPanel.navAi') },
  { key: 'companion', icon: 'nightlife', label: t('leftPanel.navCompanion') }
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
  if (!collapsed.value) return
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
  if (!collapsed.value) return
  dockItems.forEach((el) => {
    if (!el) return
    el.classList.add('dock-rebounding')
    el.style.transform = 'scale(1) translateY(0px)'
    el.style.zIndex = ''
  })
}

// ── Vertical glide (FLIP) ───────────────────────────────────────────────────
// Collapsing moves the nav a long way down the rail (and expanding brings it
// back). That travel used to be split across two properties: `top`, which the
// main thread interpolates frame by frame, and `transform`, which the
// compositor runs on its own. Toggling the rail also reflows the whole page
// every frame, so the main thread regularly misses a beat — and whenever it
// did, `top` froze while `transform` kept going: the nav drifted the wrong way
// and then snapped. That was the hitch.
//
// Now every vertical layout change lands instantly, and the visual travel is a
// single `translate` animation per element. `translate` runs on the compositor,
// so the glide keeps its pace no matter what the main thread is doing, and
// there is no second property for it to fall out of step with.
const brandEl = ref(null)
const GLIDE_TIMING = { duration: 420, easing: 'cubic-bezier(0.2, 0, 0, 1)' }
let glideAnimations = []

// Centre, not top: the dock lens scales items about their centre.
const centreY = (el) => {
  const rect = el.getBoundingClientRect()
  return rect.top + rect.height / 2
}

const snapshot = () => ({
  list: dockShelf.value ? dockShelf.value.getBoundingClientRect().top : null,
  brand: brandEl.value ? brandEl.value.getBoundingClientRect().top : null,
  items: dockItems.map((el) => (el ? centreY(el) : null))
})

const stopGlide = () => {
  glideAnimations.forEach((animation) => animation.cancel())
  glideAnimations = []
}

const glide = (el, dy) => {
  if (!el || !Number.isFinite(dy) || Math.abs(dy) < 0.5 || typeof el.animate !== 'function') return
  glideAnimations.push(el.animate([{ translate: `0 ${dy}px` }, { translate: '0 0' }], GLIDE_TIMING))
}

// flush: 'pre' — this runs while the DOM still shows the outgoing form, so the
// first snapshot is exactly what is on screen (including a glide in flight,
// which is what keeps a rapid double toggle continuous).
watch(
  collapsed,
  async () => {
    // The lens writes inline transforms. Drop them whenever the rail changes
    // form, so a magnified item can never be carried into the expanded list.
    dockItems.forEach((el) => {
      if (!el) return
      el.classList.remove('dock-rebounding')
      el.style.transform = ''
      el.style.zIndex = ''
    })

    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return

    const first = snapshot()
    await nextTick()
    stopGlide()
    const last = snapshot()
    if (first.list === null || last.list === null) return

    // Items ride inside the list, so they only need the part of their own
    // travel the list is not already covering.
    const listDy = first.list - last.list
    glide(dockShelf.value, listDy)
    if (first.brand !== null && last.brand !== null) glide(brandEl.value, first.brand - last.brand)
    dockItems.forEach((el, index) => {
      if (!el || first.items[index] === null || last.items[index] === null) return
      glide(el, first.items[index] - last.items[index] - listDy)
    })
  },
  { flush: 'pre' }
)

onBeforeUnmount(stopGlide)

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
  --rail-expanded: 200px;
  --rail-collapsed: 64px;
  /* Inner width of the collapsed rail (minus its 1px right border), and the
     left inset that centres a 32px logo / avatar inside it. Fixed lengths on
     purpose: a percentage of a width that is itself animating overshoots. */
  --rail-inner-collapsed: calc(var(--rail-collapsed) - 1px);
  --rail-pod-inset: calc((var(--rail-inner-collapsed) - 32px) / 2);

  /* Timing of everything that morphs in layout (width, paddings, radii).
     The long vertical glide has its own, gentler curve; it is a script-driven
     compositor animation, so its timing lives in GLIDE_TIMING above. */
  --rail-ease: cubic-bezier(0.16, 1, 0.3, 1);
  --rail-dur: 320ms;

  position: relative;
  width: var(--rail-expanded);
  /* The notch button hangs past the right edge, so the rail itself must not clip. */
  overflow: visible;
  /* Only the width animates in layout. Anything that moves pieces vertically
     (this padding included) changes instantly and is carried by glide(). */
  transition: width var(--rail-dur) var(--rail-ease);
  z-index: 12;
}

.nav-rail.is-collapsed {
  width: var(--rail-collapsed);
}

/* ── Brand ───────────────────────────────────────────────── */
.rail-brand {
  padding: 0 14px;
  will-change: translate;
  transition: padding var(--rail-dur) var(--rail-ease);
}

.nav-rail.is-collapsed .rail-brand {
  padding: 0 0 0 var(--rail-pod-inset);
}

.rail-brand-inner {
  min-width: 0;
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
  padding-left: var(--rail-pod-inset);
}

/* Parts that only exist in the expanded form (brand title, user meta,
   settings). They stay mounted and fade; `visibility` keeps them out of the
   tab order and away from the pointer once they are gone. */
.rail-reveal {
  opacity: 1;
  visibility: visible;
  transition: opacity 180ms ease 110ms, visibility 0s linear 0s;
}

.nav-rail.is-collapsed .rail-reveal {
  opacity: 0;
  visibility: hidden;
  pointer-events: none;
  transition: opacity 110ms ease, visibility 0s linear 110ms;
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

/* ── Navigation list ⇄ glass dock ────────────────────────── */
.rail-middle {
  position: relative;
}

/* Expanded: a plain list pinned to the top (its transparent 1px border is
   already there, so the dock outline can fade in without shifting anything).
   Collapsed: a 44px glass capsule centred in the rail.
   Its resting place (`top`, the centring `transform`, vertical padding) is NOT
   transitioned: those snap, and glide() animates `translate` on the compositor
   to cover the distance. Do not add them back to the transition list — a
   main-thread `top` running beside a compositor `transform` is exactly what
   made the nav stutter. Horizontal morphing stays in layout as before. */
.rail-list {
  position: absolute;
  top: 11px;
  left: 3px;
  width: calc(var(--rail-expanded) - 1px - 6px);
  max-height: calc(100% - 22px);
  /* 36px = the section label's 32px slot (it is absolutely placed) + 4px. */
  padding: 36px 0 0;
  display: flex;
  flex-direction: column;
  overflow-x: hidden;
  overflow-y: auto;
  scrollbar-width: none;
  border: 1px solid transparent;
  border-radius: 9px;
  background-color: rgba(27, 27, 30, 0);
  box-shadow: 0 4px 20px 0 rgba(0, 0, 0, 0), 0 0 0 1px rgba(255, 255, 255, 0) inset;
  transform: translateY(0);
  will-change: translate;
  transition:
    left var(--rail-dur) var(--rail-ease),
    width var(--rail-dur) var(--rail-ease),
    padding-left var(--rail-dur) var(--rail-ease),
    padding-right var(--rail-dur) var(--rail-ease),
    border-radius var(--rail-dur) var(--rail-ease),
    background-color 220ms ease,
    border-color 220ms ease,
    box-shadow 220ms ease;
}

.rail-list::-webkit-scrollbar {
  width: 0;
}

.nav-rail.is-collapsed .rail-list {
  top: 50%;
  left: calc((var(--rail-inner-collapsed) - 44px) / 2);
  width: 44px;
  max-height: none;
  padding: 8px 3px;
  /* The lens scales items past the capsule, so it must not clip. */
  overflow: visible;
  border-color: rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  background-color: rgba(27, 27, 30, 0.5);
  box-shadow: 0 4px 20px 0 rgba(0, 0, 0, 0.5), 0 0 0 1px rgba(255, 255, 255, 0.07) inset;
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  transform: translateY(-50%);
}

/* Out of flow, so showing or hiding it never moves the items: it just fades
   in the slot the list's top padding keeps for it. */
.rail-section {
  position: absolute;
  top: 4px;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 28px;
  padding: 4px 10px 8px;
  overflow: hidden;
  white-space: nowrap;
  opacity: 1;
  visibility: visible;
  transition: opacity 180ms ease 110ms, visibility 0s linear 0s;
}

.nav-rail.is-collapsed .rail-section {
  opacity: 0;
  visibility: hidden;
  transition: opacity 110ms ease, visibility 0s linear 110ms;
}

/* One element per destination. Expanded: icon + label row. Collapsed: 36px
   dock button with the icon centred (8px padding + 1px border each side). */
.rail-item {
  position: relative;
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  color: #c4c7c8;
  font-size: 13px;
  line-height: 18px;
  letter-spacing: 0.01em;
  font-weight: 400;
  text-decoration: none;
  cursor: pointer;
  transform-origin: center center;
  will-change: translate;
  transition:
    gap var(--rail-dur) var(--rail-ease),
    padding var(--rail-dur) var(--rail-ease),
    border-radius var(--rail-dur) var(--rail-ease),
    color 0.15s ease,
    background-color 0.15s ease,
    border-color 0.15s ease,
    box-shadow 0.15s ease,
    transform 200ms ease;
}

.rail-item + .rail-item {
  margin-top: 4px;
}

.rail-item:hover {
  color: #ffffff;
  background-color: #1b1b1e;
}

.rail-item.is-active {
  color: #ffffff;
  font-weight: 500;
  background-color: rgba(42, 42, 45, 0.6);
  border-color: rgba(68, 71, 72, 0.2);
}

.nav-rail.is-collapsed .rail-item {
  gap: 0;
  padding: 8px;
  border-radius: 12px;
  color: rgba(255, 255, 255, 0.6);
  will-change: translate, transform;
  transition:
    gap var(--rail-dur) var(--rail-ease),
    padding var(--rail-dur) var(--rail-ease),
    border-radius var(--rail-dur) var(--rail-ease),
    color 0.15s ease,
    background-color 0.15s ease,
    border-color 0.15s ease,
    box-shadow 0.15s ease,
    transform 320ms cubic-bezier(0.175, 0.885, 0.32, 1.25);
}

.nav-rail.is-collapsed .rail-item + .rail-item {
  margin-top: 8px;
}

.nav-rail.is-collapsed .rail-item:hover {
  color: #ffffff;
  background-color: rgba(255, 255, 255, 0.1);
}

.nav-rail.is-collapsed .rail-item.is-active,
.nav-rail.is-collapsed .rail-item.is-active:hover {
  color: #09090b;
  background-color: #ffffff;
  border-color: transparent;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
}

.rail-item-label {
  flex: 1 1 0%;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  text-align: left;
  opacity: 1;
  transition: opacity 180ms ease 110ms;
}

.nav-rail.is-collapsed .rail-item-label {
  opacity: 0;
  transition: opacity 110ms ease;
}

/* Trailing badge / chip. Its box collapses to nothing so the dock button ends
   up exactly icon-sized, and its height limit settles the row at 36px. */
.rail-item-tail {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  overflow: hidden;
  max-width: 72px;
  max-height: 24px;
  opacity: 1;
  transition:
    max-width var(--rail-dur) var(--rail-ease),
    opacity 180ms ease 110ms;
}

.nav-rail.is-collapsed .rail-item-tail {
  max-width: 0;
  max-height: 18px;
  opacity: 0;
  transition:
    max-width var(--rail-dur) var(--rail-ease),
    opacity 110ms ease;
}

.dock-dot {
  position: absolute;
  top: 3px;
  right: 3px;
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  /* Monochrome only: this is an unread marker, not a status colour. */
  background: #ffffff;
  box-shadow: 0 0 0 2px #09090b;
  opacity: 0;
  transform: scale(0.4);
  pointer-events: none;
  transition: opacity 110ms ease, transform 160ms ease;
}

.nav-rail.is-collapsed .dock-dot {
  opacity: 1;
  transform: scale(1);
  transition: opacity 180ms ease 160ms, transform 220ms ease 160ms;
}

.dock-rebounding {
  transition: transform 320ms cubic-bezier(0.175, 0.885, 0.32, 1.25);
}

/* ── Foot ────────────────────────────────────────────────── */
.rail-foot {
  padding: 10px;
  transition: padding var(--rail-dur) var(--rail-ease);
}

.nav-rail.is-collapsed .rail-foot {
  padding: 10px 0 10px var(--rail-pod-inset);
}

.rail-user-meta {
  margin-left: 10px;
}

.rail-settings {
  margin-left: 8px;
  transition: opacity 180ms ease 110ms, visibility 0s linear 0s, color 0.15s ease, background-color 0.15s ease;
}

.nav-rail.is-collapsed .rail-settings {
  transition: opacity 110ms ease, visibility 0s linear 110ms;
}

/* Collapsed user pod: hover lens + tooltip */
.rail-user {
  transition: transform 320ms cubic-bezier(0.175, 0.885, 0.32, 1.25);
}

.nav-rail.is-collapsed .rail-user {
  cursor: pointer;
}

.nav-rail.is-collapsed .rail-user:hover {
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

.nav-rail.is-collapsed .rail-user:hover .dock-tip {
  opacity: 1;
  transform: translateY(-50%) translateX(0);
}

/* Phones keep the narrow icon strip; the notch has no room there. */
@media (max-width: 768px) {
  .nav-rail,
  .nav-rail.is-collapsed {
    width: var(--rail-collapsed);
  }

  .rail-notch {
    display: none;
  }

  .rail-brand {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .nav-rail,
  .nav-rail * {
    transition-duration: 0.01ms !important;
    transition-delay: 0s !important;
  }
}
</style>

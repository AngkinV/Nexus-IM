<template>
  <!-- Prism c5: the ⌘K command palette overlay.
       c5.html is c1.html plus this modal — same page underneath, so it lives
       globally in Main.vue instead of being a route. -->
  <Teleport to="body">
    <div
      v-if="modelValue"
      class="palette-overlay"
      role="dialog"
      aria-modal="true"
      @mousedown.self="close"
    >
      <div class="palette-card">
        <!-- Search row -->
        <div class="palette-search">
          <span class="material-symbols-outlined palette-search-icon">search</span>
          <input
            ref="inputRef"
            v-model="query"
            class="palette-input"
            :placeholder="$t('palette.placeholder')"
            autocomplete="off"
            spellcheck="false"
            type="text"
            @keydown.down.prevent="move(1)"
            @keydown.up.prevent="move(-1)"
            @keydown.tab.prevent="cycleCategory"
            @keydown.enter.prevent="runActive"
            @keydown.esc.prevent="close"
          />
          <div class="palette-search-actions">
            <button
              v-if="query"
              class="palette-clear"
              type="button"
              @click="query = ''"
            >{{ $t('palette.clear') }}</button>
            <span class="palette-esc">ESC</span>
            <button class="palette-icon-btn" type="button" :title="$t('palette.close')" @click="close">
              <span class="material-symbols-outlined">close</span>
            </button>
          </div>
        </div>

        <!-- Category pills -->
        <div class="palette-cats">
          <button
            v-for="cat in categories"
            :key="cat.key"
            class="palette-pill"
            :class="{ active: activeCategory === cat.key }"
            type="button"
            @click="activeCategory = cat.key"
          >{{ cat.label }}</button>
          <span class="palette-counter">{{ $t('palette.available', { n: flatResults.length }) }}</span>
        </div>

        <!-- Scrollable body -->
        <div class="palette-body">
          <template v-if="flatResults.length">
            <!-- Section 1: actions -->
            <div v-if="actionResults.length" class="palette-section">
              <div class="palette-section-head">
                <span>{{ $t('palette.actionsTitle') }}</span>
                <span class="font-code text-[11px]">ACTIONS</span>
              </div>
              <div class="palette-actions">
                <div
                  v-for="item in actionResults"
                  :key="item.key"
                  class="palette-item palette-action"
                  :class="{ active: activeIndex === indexOf(item) }"
                  @mouseenter="activeIndex = indexOf(item)"
                  @click="run(item)"
                >
                  <div class="palette-item-main">
                    <div class="palette-icon-box">
                      <span class="material-symbols-outlined">{{ item.icon }}</span>
                    </div>
                    <div class="palette-item-text">
                      <span class="palette-item-title">{{ item.title }}</span>
                      <span v-if="item.desc" class="palette-item-desc">{{ item.desc }}</span>
                    </div>
                  </div>
                  <span class="palette-key">{{ item.shortcut }}</span>
                </div>
              </div>
            </div>

            <!-- Section 2: shortcuts cheatsheet -->
            <div v-if="cheatResults.length" class="palette-section palette-section-bordered">
              <div class="palette-section-head">
                <span>{{ $t('palette.cheatsheetTitle') }}</span>
                <span class="font-code text-[11px]">CHEATSHEET</span>
              </div>
              <div class="palette-cheats">
                <div
                  v-for="item in cheatResults"
                  :key="item.key"
                  class="palette-item palette-cheat"
                  :class="{ active: activeIndex === indexOf(item) }"
                  @mouseenter="activeIndex = indexOf(item)"
                  @click="run(item)"
                >
                  <span class="palette-cheat-title">{{ item.title }}</span>
                  <span class="palette-key">{{ item.shortcut }}</span>
                </div>
              </div>
            </div>
          </template>

          <!-- Empty state -->
          <div v-else class="palette-empty">
            <div class="palette-empty-icon">
              <span class="material-symbols-outlined">search_off</span>
            </div>
            <h4>{{ $t('palette.emptyTitle') }}</h4>
            <p>{{ $t('palette.emptyDesc') }}</p>
            <button class="palette-reset" type="button" @click="resetQuery">
              {{ $t('palette.emptyReset') }}
            </button>
          </div>
        </div>

        <!-- Footer hints -->
        <div class="palette-footer">
          <div class="palette-hints">
            <span><kbd>↑</kbd><kbd>↓</kbd> {{ $t('palette.hintMove') }}</span>
            <span class="palette-dot">·</span>
            <span><kbd>↵</kbd> {{ $t('palette.hintConfirm') }}</span>
            <span class="palette-dot">·</span>
            <span><kbd>Tab</kbd> {{ $t('palette.hintGroup') }}</span>
          </div>
          <span class="palette-engine font-code">Prism Command Engine v2.7</span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'action'])

const { t } = useI18n()

const query = ref('')
const activeCategory = ref('all')
const activeIndex = ref(0)
const inputRef = ref(null)

const categories = computed(() => [
  { key: 'all', label: t('palette.catAll') },
  { key: 'actions', label: t('palette.catActions') },
  { key: 'cheatsheet', label: t('palette.catCheatsheet') }
])

// Every entry maps to something the app can actually do. `command` is the key
// Main.vue dispatches on; `shortcut` is the label shown on the right.
const actions = computed(() => [
  {
    key: 'search',
    category: 'actions',
    icon: 'search',
    shortcut: '⌘ F',
    title: t('palette.actSearch'),
    desc: t('palette.actSearchDesc'),
    command: 'search'
  },
  {
    key: 'new-chat',
    category: 'actions',
    icon: 'edit_square',
    shortcut: '⌘ N',
    title: t('palette.actNewChat'),
    desc: t('palette.actNewChatDesc'),
    command: 'new-chat'
  },
  {
    key: 'new-group',
    category: 'actions',
    icon: 'group_add',
    shortcut: '⇧ ⌘ G',
    title: t('palette.actNewGroup'),
    desc: t('palette.actNewGroupDesc'),
    command: 'new-group'
  },
  {
    key: 'mark-read',
    category: 'actions',
    icon: 'mark_chat_read',
    shortcut: '⇧ ⌘ U',
    title: t('palette.actMarkRead'),
    desc: t('palette.actMarkReadDesc'),
    command: 'mark-read'
  }
])

const cheatsheet = computed(() => [
  { key: 'c-palette', category: 'cheatsheet', shortcut: '⌘ K', title: t('palette.cheatPalette'), command: 'palette' },
  { key: 'c-new', category: 'cheatsheet', shortcut: '⌘ N', title: t('palette.cheatNewChat'), command: 'new-chat' },
  { key: 'c-group', category: 'cheatsheet', shortcut: '⌘ G', title: t('palette.cheatGroup'), command: 'new-group' },
  { key: 'c-ai', category: 'cheatsheet', shortcut: '⌘ J', title: t('palette.cheatAi'), command: 'ai' },
  { key: 'c-nav', category: 'cheatsheet', shortcut: '⌘ 1~4', title: t('palette.cheatNav'), command: 'nav-1' },
  { key: 'c-help', category: 'cheatsheet', shortcut: '⌘ /', title: t('palette.cheatHelp'), command: 'help' }
])

const matches = (item) => {
  const q = query.value.trim().toLowerCase()
  if (!q) return true
  return (item.title + ' ' + (item.desc || '') + ' ' + item.shortcut + ' ' + item.command)
    .toLowerCase()
    .includes(q)
}

const actionResults = computed(() =>
  activeCategory.value === 'cheatsheet' ? [] : actions.value.filter(matches)
)
const cheatResults = computed(() =>
  activeCategory.value === 'actions' ? [] : cheatsheet.value.filter(matches)
)
const flatResults = computed(() => [...actionResults.value, ...cheatResults.value])

const indexOf = (item) => flatResults.value.findIndex((i) => i.key === item.key)

const move = (delta) => {
  const total = flatResults.value.length
  if (!total) return
  activeIndex.value = (activeIndex.value + delta + total) % total
}

const cycleCategory = () => {
  const keys = categories.value.map((c) => c.key)
  const i = keys.indexOf(activeCategory.value)
  activeCategory.value = keys[(i + 1) % keys.length]
}

const resetQuery = () => {
  query.value = ''
  activeCategory.value = 'all'
  activeIndex.value = 0
}

const close = () => emit('update:modelValue', false)

const run = (item) => {
  if (!item) return
  emit('action', item.command)
  if (item.command !== 'palette') close()
}

const runActive = () => run(flatResults.value[activeIndex.value])

watch(query, () => { activeIndex.value = 0 })
watch(activeCategory, () => { activeIndex.value = 0 })

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    resetQuery()
    await nextTick()
    inputRef.value?.focus()
  },
  { immediate: true }
)
</script>

<style scoped>
.palette-overlay {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: rgba(14, 14, 17, 0.8);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  animation: palette-fade 160ms ease-out;
}

.palette-card {
  width: 100%;
  max-width: 672px;
  display: flex;
  flex-direction: column;
  background: #0e0e11;
  border: 1px solid rgba(68, 71, 72, 0.3);
  border-radius: 0.75rem;
  overflow: hidden;
  box-shadow: 0 32px 64px -24px rgba(0, 0, 0, 0.9);
  animation: palette-in 200ms cubic-bezier(0.16, 1, 0.3, 1);
}

/* ── search row ── */
.palette-search {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.875rem 1rem;
  border-bottom: 1px solid rgba(68, 71, 72, 0.2);
  background: rgba(27, 27, 30, 0.5);
}

.palette-search-icon {
  font-size: 20px;
  color: #ffffff;
  flex-shrink: 0;
}

.palette-input {
  flex: 1;
  min-width: 0;
  background: transparent;
  border: none;
  outline: none;
  color: #e4e1e6;
  font-family: 'Geist Sans', system-ui, sans-serif;
  font-size: 1rem;
  line-height: 1.5rem;
}

.palette-input::placeholder {
  color: rgba(196, 199, 200, 0.4);
}

.palette-search-actions {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  flex-shrink: 0;
}

.palette-clear {
  background: transparent;
  border: none;
  padding: 0 0.5rem;
  font-size: 0.75rem;
  color: rgba(196, 199, 200, 0.6);
  cursor: pointer;
  transition: color 0.15s;
}

.palette-clear:hover {
  color: #ffffff;
}

.palette-esc,
.palette-key {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 0.75rem;
  line-height: 1rem;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: #c4c7c8;
  background: #1f1f22;
  border: 1px solid rgba(68, 71, 72, 0.3);
  border-radius: 0.25rem;
  padding: 0.125rem 0.5rem;
  white-space: nowrap;
}

.palette-icon-btn {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  border-radius: 0.25rem;
  color: #c4c7c8;
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}

.palette-icon-btn:hover {
  color: #ffffff;
  background: #1f1f22;
}

.palette-icon-btn .material-symbols-outlined {
  font-size: 16px;
}

/* ── category pills ── */
.palette-cats {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid rgba(68, 71, 72, 0.1);
  background: rgba(27, 27, 30, 0.3);
  overflow-x: auto;
}

.palette-pill {
  flex-shrink: 0;
  padding: 0.25rem 0.625rem;
  border: none;
  border-radius: 0.375rem;
  background: rgba(42, 42, 45, 0.6);
  color: #c4c7c8;
  font-family: 'Geist Sans', system-ui, sans-serif;
  font-size: 0.75rem;
  line-height: 1rem;
  font-weight: 500;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.palette-pill:hover {
  color: #ffffff;
  background: #2a2a2d;
}

.palette-pill.active {
  background: #ffffff;
  color: #09090b;
  font-weight: 500;
}

.palette-counter {
  margin-left: auto;
  flex-shrink: 0;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 0.6875rem;
  color: rgba(196, 199, 200, 0.5);
}

/* ── body ── */
.palette-body {
  max-height: 60vh;
  overflow-y: auto;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.palette-body::-webkit-scrollbar {
  width: 6px;
}

.palette-body::-webkit-scrollbar-thumb {
  background: #353438;
  border-radius: 9999px;
}

.palette-section {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.palette-section-bordered {
  padding-top: 0.5rem;
  border-top: 1px solid rgba(68, 71, 72, 0.2);
}

.palette-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0.25rem;
  font-size: 0.75rem;
  line-height: 1rem;
  font-weight: 500;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: rgba(196, 199, 200, 0.6);
}

.palette-actions {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.palette-cheats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.5rem;
}

.palette-item {
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.palette-action {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.625rem;
  border-radius: 0.5rem;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.2);
  color: #e4e1e6;
}

.palette-action:hover,
.palette-action.active {
  background: #1f1f22;
  color: #ffffff;
  border-color: rgba(68, 71, 72, 0.5);
}

.palette-action.active {
  background: #ffffff;
  color: #09090b;
  border-color: #ffffff;
}

.palette-item-main {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-width: 0;
}

.palette-icon-box {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border-radius: 0.25rem;
  background: #2a2a2d;
  color: #ffffff;
}

.palette-action.active .palette-icon-box {
  background: #18181b;
  color: #ffffff;
}

.palette-icon-box .material-symbols-outlined {
  font-size: 16px;
}

.palette-item-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.palette-item-title {
  font-family: 'Geist Sans', system-ui, sans-serif;
  font-size: 0.8125rem;
  line-height: 1.125rem;
  letter-spacing: 0.01em;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.palette-action.active .palette-item-title {
  font-weight: 600;
}

.palette-item-desc {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 0.6875rem;
  line-height: 1rem;
  opacity: 0.75;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.palette-action .palette-key {
  background: #2a2a2d;
  flex-shrink: 0;
}

/* Selected row: zinc-900 chip with white glyphs, as rendered by the export. */
.palette-action.active .palette-key {
  background: #18181b;
  color: #ffffff;
  font-weight: 600;
  border-color: transparent;
}

.palette-cheat {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.5rem;
  border-radius: 0.25rem;
  background: rgba(27, 27, 30, 0.6);
  border: 1px solid rgba(68, 71, 72, 0.2);
}

.palette-cheat:hover,
.palette-cheat.active {
  border-color: rgba(68, 71, 72, 0.55);
  background: #1b1b1e;
}

.palette-cheat-title {
  font-family: 'Geist Sans', system-ui, sans-serif;
  font-size: 0.875rem;
  line-height: 1.25rem;
  color: #c4c7c8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.palette-cheat:hover .palette-cheat-title,
.palette-cheat.active .palette-cheat-title {
  color: #ffffff;
}

.palette-cheat .palette-key {
  background: #1f1f22;
  color: #ffffff;
  flex-shrink: 0;
}

/* ── empty ── */
.palette-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 3rem 1rem;
}

.palette-empty-icon {
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 9999px;
  background: rgba(42, 42, 45, 0.6);
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #c4c7c8;
  margin-bottom: 0.75rem;
}

.palette-empty-icon .material-symbols-outlined {
  font-size: 24px;
}

.palette-empty h4 {
  margin: 0 0 0.25rem;
  font-size: 0.875rem;
  line-height: 1.25rem;
  font-weight: 500;
  color: #ffffff;
}

.palette-empty p {
  margin: 0 0 1rem;
  max-width: 20rem;
  font-size: 0.875rem;
  line-height: 1.35rem;
  color: rgba(196, 199, 200, 0.6);
}

.palette-reset {
  padding: 0.375rem 0.75rem;
  border-radius: 0.5rem;
  background: #2a2a2d;
  border: 1px solid rgba(68, 71, 72, 0.4);
  color: #ffffff;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 0.75rem;
  cursor: pointer;
  transition: background 0.15s;
}

.palette-reset:hover {
  background: #353438;
}

/* ── footer ── */
.palette-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.625rem 1rem;
  border-top: 1px solid rgba(68, 71, 72, 0.2);
  background: #1b1b1e;
  font-size: 0.6875rem;
  line-height: 1rem;
  color: rgba(196, 199, 200, 0.7);
}

.palette-hints {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-width: 0;
}

.palette-hints > span {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  white-space: nowrap;
}

.palette-hints kbd {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 0.6875rem;
  line-height: 1rem;
  color: #ffffff;
  background: #1f1f22;
  border: 1px solid rgba(68, 71, 72, 0.2);
  border-radius: 0.25rem;
  padding: 0 0.25rem;
}

.palette-dot {
  color: rgba(68, 71, 72, 0.6);
}

.palette-engine {
  color: rgba(196, 199, 200, 0.5);
  white-space: nowrap;
}

@keyframes palette-in {
  from {
    opacity: 0;
    transform: translateY(-8px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@keyframes palette-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}

@media (max-width: 768px) {
  .palette-overlay {
    padding: 6vh 12px 12px;
  }

  .palette-cheats {
    grid-template-columns: 1fr;
  }

  .palette-engine {
    display: none;
  }
}
</style>

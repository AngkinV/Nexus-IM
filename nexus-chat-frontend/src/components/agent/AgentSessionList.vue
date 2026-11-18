<template>
  <!-- Prism AI pane: header + new-conversation trigger + search, the session
       history grouped as current / earlier, and the engine status card. -->
  <div class="flex flex-col h-full min-h-0">
    <div class="p-space-md flex flex-col gap-space-sm border-b border-outline-variant/20 shrink-0">
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-2 min-w-0">
          <span class="material-symbols-outlined text-primary text-[18px]">auto_awesome</span>
          <span class="font-headline-md text-headline-md tracking-tight text-primary font-medium truncate">{{ $t('leftPanel.ai') }}</span>
        </div>
        <span
          class="shrink-0 font-code text-[11px] px-1.5 py-0.5 rounded border border-outline-variant/30 bg-surface-container-low text-on-surface-variant"
        >{{ $t('agent.history.count', { n: agentStore.sessions.length }) }}</span>
      </div>

      <button
        class="w-full flex items-center justify-between py-2 px-3 rounded-lg bg-surface-container-low border border-outline-variant/40 text-primary hover:bg-surface-container hover:border-outline-variant transition-all duration-150 active:scale-[0.99]"
        type="button"
        @click="onNew"
      >
        <div class="flex items-center gap-2">
          <span class="material-symbols-outlined text-[18px] text-primary">add</span>
          <span class="font-label-md text-label-md font-medium">{{ $t('agent.history.newConversation') }}</span>
        </div>
        <kbd class="px-1.5 py-0.5 rounded bg-surface-container-high text-on-surface-variant font-code text-[11px]">⌘N</kbd>
      </button>

      <div class="relative w-full flex items-center mt-1">
        <span
          class="material-symbols-outlined absolute left-2.5 text-[16px] text-on-surface-variant/60 pointer-events-none"
        >search</span>
        <input
          v-model="searchQuery"
          class="w-full bg-surface-container-low text-primary placeholder-on-surface-variant/40 text-body-md font-body-md pl-8 pr-12 py-1.5 rounded border border-outline-variant/20 focus:outline-none focus:border-outline-variant focus:bg-surface-container transition-colors"
          :placeholder="$t('agent.history.search')"
          type="text"
        />
        <button
          class="absolute right-2 px-1.5 py-0.5 rounded bg-surface-container-high text-on-surface-variant hover:text-primary font-code text-[11px] transition-colors"
          type="button"
          :title="$t('leftPanel.openPalette')"
          @click="openPalette"
        >⌘K</button>
      </div>
    </div>

    <div class="session-scroll flex-1 min-h-0 overflow-y-auto px-2 py-3 space-y-4">
      <div v-for="group in groups" :key="group.key" class="space-y-1">
        <div
          class="px-3 py-1 flex items-center justify-between text-on-surface-variant/60 font-label-sm text-label-sm uppercase tracking-wider"
        >
          <span>{{ group.label }}</span>
          <span class="font-code text-[10px]">{{ group.meta }}</span>
        </div>

        <div
          v-for="s in group.items"
          :key="s.sessionId"
          class="relative group cursor-pointer px-3 py-2.5 rounded-lg transition-all flex items-start gap-3"
          :class="
            s.sessionId === agentStore.sessionId
              ? 'bg-surface-container border border-outline-variant/40 text-primary'
              : 'hover:bg-surface-container-low text-on-surface-variant hover:text-primary'
          "
          role="button"
          tabindex="0"
          @click="onSelect(s.sessionId)"
          @keydown.enter="onSelect(s.sessionId)"
        >
          <div
            v-if="s.sessionId === agentStore.sessionId"
            class="w-1 absolute left-0 top-2 bottom-2 bg-primary rounded-r"
          ></div>
          <div
            class="p-1 rounded shrink-0 mt-0.5 flex"
            :class="
              s.sessionId === agentStore.sessionId
                ? 'bg-surface-container-high text-primary'
                : 'bg-surface-container-low text-on-surface-variant group-hover:text-primary'
            "
          >
            <span class="material-symbols-outlined text-[16px]">{{
              s.sessionId === agentStore.sessionId ? 'auto_awesome' : 'chat_bubble'
            }}</span>
          </div>
          <div class="flex-1 min-w-0">
            <div
              class="font-label-md text-label-md truncate"
              :class="s.sessionId === agentStore.sessionId ? 'text-primary font-medium' : 'group-hover:text-primary'"
            >{{ s.title || $t('agent.history.untitled') }}</div>
            <div
              class="font-body-md text-[12px] truncate mt-0.5"
              :class="s.sessionId === agentStore.sessionId ? 'text-on-surface-variant' : 'text-on-surface-variant/70'"
            >{{ previewOf(s) }}</div>
          </div>
          <span
            class="font-code text-[11px] mt-0.5 shrink-0 group-hover:hidden group-focus-within:hidden"
            :class="s.sessionId === agentStore.sessionId ? 'text-on-surface-variant/60' : 'text-on-surface-variant/50'"
          >{{ formatTime(s.updatedAt) }}</span>
          <div class="hidden group-hover:flex group-focus-within:flex items-center gap-0.5 shrink-0">
            <button
              class="w-6 h-6 rounded flex items-center justify-center text-on-surface-variant hover:bg-surface-container-high hover:text-primary transition-colors"
              type="button"
              :title="$t('agent.history.rename')"
              @click.stop="onRename(s)"
            >
              <span class="material-symbols-outlined text-[14px]">edit</span>
            </button>
            <button
              class="w-6 h-6 rounded flex items-center justify-center text-on-surface-variant hover:bg-surface-container-high hover:text-error transition-colors"
              type="button"
              :title="$t('common.delete')"
              @click.stop="onDelete(s)"
            >
              <span class="material-symbols-outlined text-[14px]">delete</span>
            </button>
          </div>
        </div>
      </div>

      <div v-if="!groups.length" class="px-3 py-6 text-center font-body-md text-[12px] text-on-surface-variant/60">
        {{ searchQuery.trim() ? $t('agent.history.noMatch') : $t('agent.history.empty') }}
      </div>
    </div>

    <div class="p-3 bg-surface-container-low/70 border-t border-outline-variant/20 flex items-center justify-between shrink-0">
      <div class="flex items-center gap-2.5 min-w-0">
        <div
          class="w-7 h-7 rounded bg-surface-container-high border border-outline-variant/30 flex items-center justify-center text-primary shrink-0"
        >
          <span class="material-symbols-outlined text-[16px]">memory</span>
        </div>
        <div class="min-w-0">
          <div class="font-label-md text-label-md text-primary truncate">
            {{ activeProvider ? activeProvider.displayName || activeProvider.provider : $t('agent.providers.mock') }}
          </div>
          <div class="font-code text-[10px] text-on-surface-variant/70 truncate">
            {{ activeProvider?.defaultModel || '—' }} · {{ knowledgeStore.activeKb?.name || $t('agent.knowledge.selectorNone') }}
          </div>
        </div>
      </div>
      <span
        class="w-1.5 h-1.5 rounded-full bg-primary inline-block shrink-0"
        :class="{ 'animate-pulse': agentStore.streaming }"
      ></span>
    </div>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { useAgentStore, AI_ASSISTANT_CHAT_ID } from '@/stores/agent'
import { useAgentProvidersStore } from '@/stores/agentProviders'
import { useChatStore } from '@/stores/chat'
import { useKnowledgeStore } from '@/stores/knowledge'

const { t } = useI18n()
const agentStore = useAgentStore()
const providersStore = useAgentProvidersStore()
const chatStore = useChatStore()
const knowledgeStore = useKnowledgeStore()
const openPalette = inject('openPalette', () => {})

const searchQuery = ref('')

const activeProvider = computed(() =>
  providersStore.providers.find((p) => p.id === agentStore.activeProviderId) || providersStore.defaultProvider
)

const groups = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  const matched = agentStore.sessions.filter((s) =>
    (s.title || t('agent.history.untitled')).toLowerCase().includes(query)
  )
  const current = matched.filter((s) => s.sessionId === agentStore.sessionId)
  const earlier = matched.filter((s) => s.sessionId !== agentStore.sessionId)
  return [
    {
      key: 'current',
      label: t('agent.history.current'),
      meta: t('agent.history.messageCount', { n: agentStore.messages.length }),
      items: current
    },
    {
      key: 'earlier',
      label: t('agent.history.title'),
      meta: t('agent.history.count', { n: earlier.length }),
      items: earlier
    }
  ].filter((g) => g.items.length)
})

onMounted(() => {
  agentStore.loadSessions()
})

// The server keeps no per-session preview, so the second line comes from the
// transcript this browser already holds; sessions it has never opened fall
// back to their creation time.
function previewOf(s) {
  const transcript = s.sessionId === agentStore.sessionId
    ? agentStore.messages
    : agentStore.messagesBySessionId[s.sessionId]
  const last = transcript?.[transcript.length - 1]?.content
  if (last) return last.replace(/\s+/g, ' ').slice(0, 80)
  return t('agent.history.createdAt', { time: dayjs(s.createdAt).format('YYYY-MM-DD HH:mm') })
}

function formatTime(time) {
  if (!time) return ''
  const d = dayjs(time)
  if (!d.isValid()) return ''
  const today = dayjs().startOf('day')
  if (d.isAfter(today)) return d.format('HH:mm')
  if (d.isSame(today, 'year')) return d.format('MM-DD HH:mm')
  return d.format('YYYY-MM-DD')
}

// The AI canvas only renders while the virtual assistant chat is the active one.
function openAssistant() {
  chatStore.setActiveChat(chatStore.sortedChats.find((c) => c.id === AI_ASSISTANT_CHAT_ID))
}

async function onNew() {
  try {
    await agentStore.newSession()
    openAssistant()
    ElMessage.success(t('agent.history.created'))
  } catch (err) {
    ElMessage.error(err.message || t('common.failed'))
  }
}

async function onSelect(sessionId) {
  openAssistant()
  await agentStore.switchSession(sessionId)
}

async function onRename(s) {
  let next
  try {
    const result = await ElMessageBox.prompt(
      t('agent.history.renamePrompt'),
      t('agent.history.rename'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        inputValue: s.title || '',
        inputValidator: (v) => !!v && v.trim().length > 0,
        inputErrorMessage: t('agent.history.renameRequired')
      }
    )
    next = result.value
  } catch {
    return
  }
  try {
    await agentStore.renameSession(s.sessionId, next.trim())
    ElMessage.success(t('agent.history.renamed'))
  } catch (err) {
    ElMessage.error(err.message || t('common.failed'))
  }
}

async function onDelete(s) {
  try {
    await ElMessageBox.confirm(
      t('agent.history.deleteConfirm', { name: s.title || t('agent.history.untitled') }),
      t('common.delete'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )
  } catch { return }
  try {
    await agentStore.deleteSession(s.sessionId)
    ElMessage.success(t('agent.history.deleted'))
  } catch (err) {
    ElMessage.error(err.message || t('common.failed'))
  }
}
</script>

<style scoped>
.session-scroll::-webkit-scrollbar {
  width: 6px;
}

.session-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.session-scroll::-webkit-scrollbar-thumb {
  background: #353438;
  border-radius: 9999px;
}

.session-scroll::-webkit-scrollbar-thumb:hover {
  background: #8e9192;
}
</style>

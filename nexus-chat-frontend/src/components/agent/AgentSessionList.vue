<template>
  <aside class="agent-history flex h-full min-h-0 flex-col border-r border-outline-variant/20 bg-surface-container-lowest" :aria-label="t('workbench')">
    <header class="flex h-14 shrink-0 items-center gap-2 border-b border-outline-variant/20 px-3">
      <span class="material-symbols-outlined text-primary" aria-hidden="true">auto_awesome</span>
      <h2 class="min-w-0 truncate text-sm font-medium text-primary">{{ t('workbench') }}</h2>
      <span class="shrink-0 rounded bg-surface-container-high px-1.5 py-0.5 font-code text-[10px] text-on-surface-variant">
        {{ $t('agent.history.count', { n: agentStore.sessions.length }) }}
      </span>
      <button v-if="!isMobile" class="icon-button ml-auto" type="button" :title="$t('leftPanel.collapseList')"
        :aria-label="$t('leftPanel.collapseList')" @click="toggleList">
        <span class="material-symbols-outlined" aria-hidden="true">keyboard_double_arrow_left</span>
      </button>
    </header>

    <div class="flex shrink-0 flex-col gap-2 border-b border-outline-variant/20 p-3">
      <button class="flex w-full items-center gap-2 rounded-md bg-surface-container px-3 py-2 text-sm font-medium text-primary hover:bg-surface-container-high"
        type="button" :disabled="newDisabled" aria-keyshortcuts="Meta+N Control+N" @click="newAgentSession">
        <span class="material-symbols-outlined text-[17px]" aria-hidden="true">add</span>
        {{ $t('agent.history.newConversation') }}
        <kbd class="ml-auto font-code text-[11px] text-on-surface-variant" aria-hidden="true">{{ modifier }}N</kbd>
      </button>
      <div class="relative flex items-center">
        <span class="material-symbols-outlined pointer-events-none absolute left-2 text-[16px] text-on-surface-variant" aria-hidden="true">search</span>
        <input ref="searchInput" v-model="searchQuery" type="search" autocomplete="off" aria-keyshortcuts="Meta+K Control+K"
          class="h-9 w-full rounded-md border border-outline-variant/30 bg-surface-container-low pl-8 pr-14 text-sm text-primary"
          :placeholder="$t('agent.history.search')" :aria-label="$t('agent.history.search')" />
        <kbd v-if="!searchQuery" class="pointer-events-none absolute right-2 font-code text-[11px] text-on-surface-variant" aria-hidden="true">{{ modifier }}K</kbd>
        <button v-else type="button" class="icon-button absolute right-1" :aria-label="$t('palette.clear')" :title="$t('palette.clear')"
          @click="searchQuery = ''; focusSearch()">
          <span class="material-symbols-outlined" aria-hidden="true">close</span>
        </button>
      </div>
    </div>

    <div class="session-scroll min-h-0 flex-1 space-y-4 overflow-y-auto px-2 py-3">
      <section v-if="agentStore.sessionId" :aria-label="t('activeTask')">
        <h3 class="section-label">{{ t('activeTask') }}</h3>
        <button type="button" class="block w-full rounded-lg border border-outline-variant/40 bg-surface-container p-2.5 text-left text-sm text-primary"
          :disabled="creating || agentStore.switching" @click="onSelect(agentStore.sessionId)">
          <span class="flex items-center gap-2">
            <span class="h-1.5 w-1.5 shrink-0 rounded-full bg-current" aria-hidden="true"></span>
            <span class="min-w-0 flex-1 truncate font-medium">{{ titleOf(agentStore.activeSession) }}</span>
            <time v-if="agentStore.activeSession?.updatedAt" class="stamp" :datetime="agentStore.activeSession.updatedAt">{{ formatTime(agentStore.activeSession.updatedAt) }}</time>
          </span>
          <span v-if="activePreview" class="mt-1 block truncate pl-3.5 text-xs text-on-surface-variant">{{ activePreview }}</span>
          <span class="mt-2 flex flex-wrap items-center gap-1.5 pl-3.5 font-code text-[11px]">
            <span v-if="modelLabel" class="max-w-full truncate rounded bg-surface-container-high px-1.5 text-on-surface-variant" :title="modelLabel">{{ modelLabel }}</span>
            <span role="status" class="inline-flex items-center gap-1">
              <span v-if="run.summary.status === 'running'" aria-hidden="true" class="h-1.5 w-1.5 rounded-full bg-current motion-safe:animate-pulse"></span>
              {{ t(`state.${run.summary.status}`) }}
            </span>
          </span>
        </button>
      </section>

      <section :aria-label="t('recent')">
        <h3 class="section-label flex items-center justify-between">
          {{ t('recent') }} <span v-if="searchQuery.trim()">{{ $t('agent.history.count', { n: matchedSessions.length }) }}</span>
        </h3>
        <ul v-if="matchedSessions.length" class="space-y-1">
          <li v-for="s in matchedSessions" :key="s.sessionId" class="flex items-center rounded-md pr-0.5 hover:bg-surface-container"
            :class="{ 'bg-surface-container': s.sessionId === agentStore.sessionId }">
            <button type="button" class="min-w-0 flex-1 rounded-md px-2 py-2 text-left" :disabled="creating || agentStore.switching"
              :aria-current="s.sessionId === agentStore.sessionId ? 'true' : undefined" @click="onSelect(s.sessionId)">
              <span class="flex items-baseline gap-1.5">
                <span class="min-w-0 flex-1 truncate text-sm font-medium text-primary">{{ titleOf(s) }}</span>
                <time v-if="s.updatedAt" class="stamp" :datetime="s.updatedAt">{{ formatTime(s.updatedAt) }}</time>
              </span>
              <span class="block truncate text-xs text-on-surface-variant">{{ previewOf(s) || createdLabel(s) }}</span>
            </button>
            <button type="button" class="icon-button" :title="$t('agent.history.rename')" :aria-label="`${$t('agent.history.rename')}: ${titleOf(s)}`" @click="onRename(s)">
              <span class="material-symbols-outlined" aria-hidden="true">edit</span>
            </button>
            <button type="button" class="icon-button hover:text-error" :title="$t('common.delete')" :aria-label="`${$t('common.delete')}: ${titleOf(s)}`" @click="onDelete(s)">
              <span class="material-symbols-outlined" aria-hidden="true">delete</span>
            </button>
          </li>
        </ul>
        <p v-else class="px-2 py-5 text-center text-sm text-on-surface-variant">
          {{ agentStore.sessionsLoading ? $t('common.loading') : searchQuery.trim() ? $t('agent.history.noMatch') : $t('agent.history.empty') }}
        </p>
      </section>
    </div>

    <footer class="shrink-0 space-y-1.5 border-t border-outline-variant/25 bg-surface-container-low/70 p-3 font-code text-[11px] text-on-surface-variant">
      <div class="flex items-center justify-between gap-2">
        <span class="flex min-w-0 items-center gap-1.5 text-primary" :title="modelLabel">
          <span class="material-symbols-outlined text-[15px]" aria-hidden="true">memory</span>
          <span class="truncate">{{ modelLabel || t('modelUnknown') }}</span>
        </span>
        <span v-if="latency != null" class="shrink-0">{{ t('modelLatency', { ms: latency }) }}</span>
      </div>
      <div class="flex flex-wrap items-baseline justify-between gap-x-2">
        <span class="min-w-0 truncate">{{ kbLabel || $t('agent.knowledge.selectorNone') }}</span>
        <span v-if="inputTokens != null">{{ $t('agent.contextTokens', { n: inputTokens }) }}</span>
      </div>
      <div v-if="kbSources.length" class="truncate" :title="kbSources.map(s => s.title).filter(Boolean).join(', ')">{{ t('kbSources', kbSources.length) }}</div>
    </footer>
  </aside>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { AI_ASSISTANT_CHAT_ID, useAgentStore } from '@/stores/agent'
import { useAgentProvidersStore } from '@/stores/agentProviders'
import { useChatStore } from '@/stores/chat'
import { useSettingsStore } from '@/stores/settings'
import { useKnowledgeStore } from '@/stores/knowledge'
import { buildRunView } from './runView.js'

const { t: globalT } = useI18n({ useScope: 'global' })
const { t } = useI18n({ messages: {
  en: {
    workbench: 'AI workbench', activeTask: 'Active task', recent: 'Recent sessions', modelUnknown: 'Model not reported',
    modelLatency: 'Model: {ms} ms', kbSources: '{n} KB source | {n} KB sources',
    state: { idle: 'Idle', queued: 'Queued', running: 'Running', done: 'Completed', interrupted: 'Interrupted', cancelled: 'Cancelled', error: 'Failed', limited: 'Limit reached', skipped: 'Skipped' }
  },
  zh: {
    workbench: 'AI 工作台', activeTask: '当前任务', recent: '最近会话', modelUnknown: '模型未报告',
    modelLatency: '模型耗时 {ms} 毫秒', kbSources: '{n} 个知识库来源',
    state: { idle: '空闲', queued: '排队中', running: '运行中', done: '已完成', interrupted: '已中断', cancelled: '已取消', error: '失败', limited: '已达上限', skipped: '已跳过' }
  }
} })
const agentStore = useAgentStore()
const settings = useSettingsStore()
const providersStore = useAgentProvidersStore()
const chatStore = useChatStore()
const knowledgeStore = useKnowledgeStore()
const toggleList = inject('toggleList')
const isMobile = inject('isMobile', ref(false))
const newAgentSession = inject('newAgentSession')
const creating = inject('creatingAgentSession', ref(false))
const newDisabled = computed(() => creating.value || agentStore.streaming || agentStore.switching)
const modifier = /Mac|iPad|iPhone/.test(globalThis.navigator?.platform || '') ? '⌘' : 'Ctrl+'

const searchQuery = ref('')
const searchInput = ref(null)
const focusSearch = () => searchInput.value?.focus()
const focusSignal = inject('agentHistoryFocusSignal', ref(0))
// Only an explicit request moves the caret. Mounting the pane (e.g. after switching
// sections) must leave focus wherever the user had it.
watch(focusSignal, focusSearch, { flush: 'post' })
onMounted(() => {
  // A focus request that arrived before this pane existed is served by the watcher
  // above once the signal changes; taking focus here would also fire on an ordinary
  // visit, pulling the caret out of whatever the user was in.
  agentStore.loadSessions().catch(err => ElMessage.error(err.message || globalT('common.failed')))
})

function titleOf(s) { return s?.title || globalT('agent.history.untitled') }
function previewOf(s) {
  const transcript = s.sessionId === agentStore.sessionId ? agentStore.messages : agentStore.messagesBySessionId[s.sessionId]
  const last = transcript?.findLast(m => typeof m.content === 'string' && m.content.trim())
  return last?.content.trim().replace(/\s+/g, ' ').slice(0, 160) || ''
}
function createdLabel(s) {
  return s.createdAt && dayjs(s.createdAt).isValid()
    ? globalT('agent.history.createdAt', { time: settings.format(s.createdAt, 'YYYY-MM-DD HH:mm') }) : ''
}
function formatTime(time) {
  if (!time || !dayjs(time).isValid()) return ''
  const d = dayjs(time)
  // Settings → 时间显示格式 applies to every timestamp, this list included.
  return settings.format(time, d.isSame(dayjs(), 'day') ? 'HH:mm' : d.isSame(dayjs(), 'year') ? 'MM-DD' : 'YYYY-MM-DD')
}

// Sorting depends only on metadata, not selection, text deltas or the filter.
const orderedSessions = computed(() => [...agentStore.sessions].sort((a, b) =>
  (Date.parse(b.updatedAt) || 0) - (Date.parse(a.updatedAt) || 0)))
const matchedSessions = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  return query ? orderedSessions.value.filter(s => titleOf(s).toLowerCase().includes(query) || previewOf(s).toLowerCase().includes(query)) : orderedSessions.value
})
const activePreview = computed(() => previewOf({ sessionId: agentStore.sessionId }))
const activeMessage = computed(() => {
  const messages = agentStore.messages
  return (agentStore.streaming && messages.findLast(m => m.role === 'assistant' && m.status === 'streaming')) || messages.findLast(m => m.role === 'assistant')
})
const run = computed(() => {
  const m = activeMessage.value
  return buildRunView(m?.trace || [], {
    // A turn that stopped without a terminal frame has no finish reason; its own
    // recorded status (interrupted, cancelled, limited…) is what it ended as.
    live: agentStore.streaming && m?.status === 'streaming', status: m?.finishReason || m?.status,
    usage: m?.usage, sources: m?.sources
  })
})
const modelNode = computed(() => run.value.nodes.findLast(n => n.kind === 'model'))
const selectedProvider = computed(() => providersStore.getById(agentStore.activeProviderId) || providersStore.defaultProvider)
// What this run reported, or nothing. A channel's configured model says what the
// next turn would use, not what this one did; and a demo answer is not a model.
const modelLabel = computed(() => modelNode.value?.model || run.value.meta.model || '')
const latency = computed(() => Number.isFinite(modelNode.value?.ms) ? modelNode.value.ms : null)
const inputTokens = computed(() => modelNode.value?.usage?.inputTokens ?? run.value.usage?.inputTokens)
const kbSources = computed(() => run.value.sources.filter(s => s.kind === 'knowledge'))
// The knowledge base this turn actually used: its own record first, then the selection for the next one.
const kbLabel = computed(() => {
  const used = run.value.meta.linkedKbId || activeMessage.value?.linkedKbId
  return knowledgeStore.kbs.find(kb => kb.kbId === used)?.name || used || knowledgeStore.activeKb?.name || knowledgeStore.activeKbId || ''
})

async function onSelect(sid) {
  if (creating.value || agentStore.switching) return
  // The canvas opens with the tap. Waiting for the history read would leave a phone
  // on the list until the request returns, and keep it there if the request fails.
  chatStore.setActiveChat(chatStore.sortedChats.find(c => c.id === AI_ASSISTANT_CHAT_ID))
  try {
    if (sid !== agentStore.sessionId) {
      await agentStore.switchSession(sid)
      ElMessage.success(globalT('agent.history.switched', { name: titleOf(agentStore.activeSession) }))
    }
  } catch (err) { ElMessage.error(err.message || globalT('common.failed')) }
}

async function onRename(s) {
  let next
  try {
    const result = await ElMessageBox.prompt(
      globalT('agent.history.renamePrompt'), globalT('agent.history.rename'), {
        confirmButtonText: globalT('common.confirm'), cancelButtonText: globalT('common.cancel'),
        inputValue: s.title || '', inputValidator: v => !!v?.trim(), inputErrorMessage: globalT('agent.history.renameRequired')
      }
    )
    next = result.value.trim()
  } catch { return }
  try {
    await agentStore.renameSession(s.sessionId, next)
    ElMessage.success(globalT('agent.history.renamed'))
  } catch (err) { ElMessage.error(err.message || globalT('common.failed')) }
}

async function onDelete(s) {
  try {
    await ElMessageBox.confirm(globalT('agent.history.deleteConfirm', { name: titleOf(s) }), globalT('common.delete'), {
      confirmButtonText: globalT('common.confirm'), cancelButtonText: globalT('common.cancel'), type: 'warning'
    })
  } catch { return }
  try {
    await agentStore.deleteSession(s.sessionId)
    ElMessage.success(globalT('agent.history.deleted'))
  } catch (err) { ElMessage.error(err.message || globalT('common.failed')) }
}
</script>

<style scoped>
.icon-button { @apply inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-on-surface-variant hover:bg-surface-container-high; }
.icon-button > span { font-size: 16px; }
.section-label { @apply mb-1 px-2 font-code text-[11px] uppercase tracking-wider text-on-surface-variant; }
.stamp { @apply shrink-0 font-code text-[10px] text-on-surface-variant; }
.agent-history :is(button, input):focus-visible { outline: 2px solid currentColor; outline-offset: -2px; }
.agent-history button:disabled { cursor: default; opacity: .55; }
.agent-history input[type='search']::-webkit-search-cancel-button { display: none; }
.session-scroll { scrollbar-width: thin; }
@media (pointer: coarse) { .icon-button { min-width: 40px; min-height: 44px; } }
</style>

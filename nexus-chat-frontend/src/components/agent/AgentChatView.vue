<template>
  <div class="agent-chat-view">
    <!-- Header -->
    <header class="agent-header">
      <div class="agent-header-left">
        <button v-if="isMobile" class="mobile-back-btn" @click="handleMobileBack">
          <el-icon :size="22"><ArrowLeft /></el-icon>
        </button>
        <div class="agent-avatar">
          <span class="material-icons-round">auto_awesome</span>
        </div>
        <div class="agent-meta">
          <div class="agent-name-row">
            <span class="agent-name">{{ $t('agent.title') }}</span>
            <span class="agent-online">{{ $t('agent.online') }}</span>
          </div>
          <span class="agent-subtitle">{{ $t('agent.subtitle') }}</span>
        </div>
      </div>

      <div class="agent-header-actions">
        <!-- History sessions dropdown -->
        <AgentSessionDropdown />

        <!-- Knowledge base selector (Module B) -->
        <KnowledgeBaseSelector @manage="kbDialogVisible = true" />

        <!-- Model switcher -->
        <el-dropdown trigger="click" @command="onSelectProvider">
          <button class="model-switcher" :title="$t('agent.providers.title')">
            <span class="material-icons-round">memory</span>
            <span class="model-text">{{ activeProviderLabel }}</span>
            <el-icon><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="p in providersStore.providers"
                :key="p.id"
                :command="p.id"
                :disabled="!p.hasApiKey"
              >
                <div class="provider-row">
                  <strong>{{ p.displayName || p.provider }}</strong>
                  <span class="provider-model">{{ p.defaultModel || '—' }}</span>
                  <el-tag v-if="agentStore.activeProviderId === p.id" type="success" size="small" effect="light">
                    {{ $t('agent.providers.using') }}
                  </el-tag>
                </div>
              </el-dropdown-item>
              <el-dropdown-item v-if="!providersStore.providers.length" disabled>
                {{ $t('agent.providers.empty') }}
              </el-dropdown-item>
              <el-dropdown-item divided command="__settings__">
                <el-icon><Setting /></el-icon>
                <span>{{ $t('agent.providers.manage') }}</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>

        <el-tooltip :content="$t('agent.knowledge.manage')" placement="bottom">
          <button class="action-btn" @click="kbDialogVisible = true">
            <el-icon :size="20"><FolderOpened /></el-icon>
          </button>
        </el-tooltip>

        <el-tooltip :content="$t('agent.clearMemory')" placement="bottom">
          <button class="action-btn" @click="onClear" :disabled="agentStore.streaming">
            <el-icon :size="20"><Delete /></el-icon>
          </button>
        </el-tooltip>
      </div>
    </header>

    <AgentProviderSettings v-model:visible="providerDialogVisible" />
    <KnowledgeBaseManager v-model:visible="kbDialogVisible" />

    <!-- Message stream -->
    <div ref="scrollEl" class="agent-messages">
      <div v-if="!agentStore.hasMessages" class="agent-empty">
        <div class="hero-top">
          <div class="hero-copy">
            <h2 class="hero-title">
              {{ $t('agent.welcomeTitlePre') }}<span class="hero-brand">{{ $t('agent.welcomeBrand') }}</span>
            </h2>
            <p class="hero-sub">{{ $t('agent.welcomeBody') }}</p>
          </div>
          <div class="hero-art" aria-hidden="true">
            <svg class="art-svg" viewBox="0 0 240 200" fill="none" xmlns="http://www.w3.org/2000/svg">
              <defs>
                <radialGradient id="aiOrbGrad" cx="0.36" cy="0.30" r="0.78">
                  <stop offset="0" stop-color="#D9ECFD" />
                  <stop offset="0.45" stop-color="#7DB8F5" />
                  <stop offset="1" stop-color="#3B78DD" />
                </radialGradient>
                <radialGradient id="aiHaloGrad" cx="0.5" cy="0.5" r="0.5">
                  <stop stop-color="#4F8EF0" stop-opacity="0.22" />
                  <stop offset="1" stop-color="#4F8EF0" stop-opacity="0" />
                </radialGradient>
              </defs>

              <circle cx="120" cy="102" r="98" fill="url(#aiHaloGrad)" />
              <ellipse cx="120" cy="166" rx="44" ry="9" fill="#3B78DD" fill-opacity="0.14" />

              <g transform="translate(48,58)"><g class="art-spark art-spark--1">
                <path d="M0 -7C1.2 -1.5 1.5 -1.2 7 0C1.5 1.2 1.2 1.5 0 7C-1.2 1.5 -1.5 1.2 -7 0C-1.5 -1.2 -1.2 -1.5 0 -7Z" fill="#7DB8F5" />
              </g></g>
              <g transform="translate(198,76)"><g class="art-spark art-spark--2">
                <path d="M0 -6C1 -1.3 1.3 -1 6 0C1.3 1 1 1.3 0 6C-1 1.3 -1.3 1 -6 0C-1.3 -1 -1 -1.3 0 -6Z" fill="#A9D2FA" />
              </g></g>
              <g transform="translate(64,150)"><g class="art-spark art-spark--3">
                <circle r="4" fill="#8FC0F8" />
              </g></g>

              <g class="art-orb">
                <circle cx="120" cy="104" r="52" fill="url(#aiOrbGrad)" />
                <ellipse cx="102" cy="86" rx="16" ry="10.5" fill="#FFFFFF" fill-opacity="0.45" transform="rotate(-28 102 86)" />
                <circle cx="108" cy="82" r="3.6" fill="#FFFFFF" fill-opacity="0.7" />
              </g>
            </svg>
          </div>
        </div>

        <div class="hero-cards">
          <button
            v-for="(card, idx) in welcomeCards"
            :key="card.key"
            class="hero-card"
            :style="{ '--i': idx }"
            @click="prefill(card.prompt)"
          >
            <span class="hero-card-icon" :class="`ic-${card.key}`">
              <span class="material-icons-round">{{ card.icon }}</span>
            </span>
            <span class="hero-card-text">
              <span class="hero-card-title">{{ card.title }}</span>
              <span class="hero-card-desc">{{ card.desc }}</span>
            </span>
          </button>
        </div>
      </div>

      <div
        v-for="msg in agentStore.messages"
        :key="msg.id"
        class="agent-msg"
        :class="msg.role === 'user' ? 'msg-user' : 'msg-assistant'"
      >
        <div class="msg-bubble">
          <!-- Tool call chips (assistant only) -->
          <div v-if="msg.toolCalls && msg.toolCalls.length" class="tool-calls">
            <span
              v-for="(tc, i) in msg.toolCalls"
              :key="i"
              class="tool-chip"
              :class="`tool-${(tc.status || 'running').toLowerCase()}`"
            >
              <span class="material-icons-round">build</span>
              <span class="tool-name">{{ tc.toolName }}</span>
              <span v-if="tc.status === 'running'" class="tool-spinner" />
              <span v-else-if="tc.latencyMs != null" class="tool-latency">{{ tc.latencyMs }}ms</span>
            </span>
          </div>

          <!-- Content -->
          <div class="msg-text" v-text="msg.content"></div>

          <!-- Streaming caret -->
          <span v-if="msg.role === 'assistant' && msg.status === 'streaming'" class="caret" />

          <!-- Footer (errors / usage on assistant msgs) -->
          <div v-if="msg.role === 'assistant' && msg.status === 'error'" class="msg-error">
            {{ msg.error?.message || $t('agent.error') }}
          </div>
        </div>
      </div>
    </div>

    <!-- Input -->
    <div class="agent-input-area">
      <div class="agent-input-wrapper">
        <el-input
          v-model="draft"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 4 }"
          :placeholder="$t('agent.placeholder')"
          resize="none"
          class="agent-textarea"
          :disabled="agentStore.streaming"
          @keydown.enter.prevent="onEnter"
        />
        <button
          v-if="!agentStore.streaming"
          class="agent-send"
          :disabled="!draft.trim()"
          @click="onSend"
          :title="$t('agent.send')"
        >
          <el-icon :size="20"><Promotion /></el-icon>
        </button>
        <button
          v-else
          class="agent-send agent-stop"
          @click="agentStore.cancelStream()"
          :title="$t('agent.stop')"
        >
          <span class="material-icons-round">stop</span>
        </button>
      </div>
      <div class="agent-hint">
        <span>{{ $t('agent.inputHint') }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, inject, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { ArrowLeft, ArrowDown, Promotion, Delete, Setting, FolderOpened } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { useAgentStore } from '@/stores/agent'
import { useAgentProvidersStore } from '@/stores/agentProviders'
import { useChatStore } from '@/stores/chat'
import { useKnowledgeStore } from '@/stores/knowledge'
import AgentProviderSettings from '@/components/agent/AgentProviderSettings.vue'
import AgentSessionDropdown from '@/components/agent/AgentSessionDropdown.vue'
import KnowledgeBaseManager from '@/components/agent/KnowledgeBaseManager.vue'
import KnowledgeBaseSelector from '@/components/agent/KnowledgeBaseSelector.vue'

const { t } = useI18n()
const agentStore = useAgentStore()
const providersStore = useAgentProvidersStore()
const chatStore = useChatStore()
const knowledgeStore = useKnowledgeStore()
const isMobile = inject('isMobile', { value: false })

const draft = ref('')
const scrollEl = ref(null)
const providerDialogVisible = ref(false)
const kbDialogVisible = ref(false)

const welcomeCards = computed(() => [
  {
    key: 'summary',
    icon: 'summarize',
    title: t('agent.cards.summary.title'),
    desc: t('agent.cards.summary.desc'),
    prompt: t('agent.examples.summarize')
  },
  {
    key: 'report',
    icon: 'event_note',
    title: t('agent.cards.report.title'),
    desc: t('agent.cards.report.desc'),
    prompt: t('agent.examples.weeklyReport')
  },
  {
    key: 'reply',
    icon: 'quickreply',
    title: t('agent.cards.reply.title'),
    desc: t('agent.cards.reply.desc'),
    prompt: t('agent.examples.quickReply')
  }
])

const activeProviderLabel = computed(() => {
  const p = providersStore.providers.find(x => x.id === agentStore.activeProviderId)
        || providersStore.defaultProvider
  if (!p) return t('agent.providers.mock')
  return p.defaultModel ? `${p.displayName || p.provider} · ${p.defaultModel}` : (p.displayName || p.provider)
})

onMounted(async () => {
  await providersStore.fetchAll()
  // If user has no explicit provider preference, fall back to the server default.
  if (!agentStore.activeProviderId && providersStore.defaultProvider) {
    agentStore.setActiveProviderId(providersStore.defaultProvider.id)
  }
  // Re-hydrate the active session's messages on mount. Without this, the very
  // first time the user reopens AI 助手 after a refresh — or after switching to
  // another chat and back, if Pinia happened to be reset — they would see an
  // empty bubble list even though a real session id is still in localStorage.
  await agentStore.hydrateActiveSession()
  // Default scroll position is the latest message — matches normal chat behavior.
  await nextTick()
  scrollToBottom(false)
  // Track whether the user is currently parked near the bottom. This drives
  // the sticky-bottom auto-scroll below.
  scrollEl.value?.addEventListener('scroll', onUserScroll, { passive: true })
})

onUnmounted(() => {
  scrollEl.value?.removeEventListener('scroll', onUserScroll)
})

// "Stuck to bottom" pattern: only auto-scroll on streaming deltas if the user
// is already parked near the bottom; otherwise leave their scroll alone so
// they can read history without being yanked down.
const STICKY_THRESHOLD_PX = 80
const stuckToBottom = ref(true)

function isNearBottom() {
  const el = scrollEl.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < STICKY_THRESHOLD_PX
}

function onUserScroll() {
  stuckToBottom.value = isNearBottom()
}

function scrollToBottom(smooth = true) {
  const el = scrollEl.value
  if (!el) return
  el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
  stuckToBottom.value = true
}

function onSelectProvider(command) {
  if (command === '__settings__') {
    providerDialogVisible.value = true
    return
  }
  agentStore.setActiveProviderId(command)
  ElMessage.success(t('agent.providers.switched'))
}

function prefill(text) {
  draft.value = text
}

async function onSend() {
  const text = draft.value.trim()
  if (!text || agentStore.streaming) return
  draft.value = ''
  try {
    await agentStore.sendMessage(text, { linkedKbId: knowledgeStore.activeKbId })
  } catch (err) {
    ElMessage.error(err?.message || t('agent.error'))
  }
}

function onEnter(e) {
  if (e.shiftKey) {
    draft.value += '\n'
    return
  }
  onSend()
}

async function onClear() {
  try {
    await ElMessageBox.confirm(t('agent.clearConfirm'), t('agent.clearMemory'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      type: 'warning'
    })
  } catch {
    return
  }
  await agentStore.clearConversation()
  ElMessage.success(t('agent.cleared'))
}

function handleMobileBack() {
  chatStore.activeChat = null
}

// Auto-scroll on new messages / streaming deltas — but only when the user is
// already at the bottom. This is the standard "stuck to bottom" pattern: the
// view follows the latest message during a stream, but once the user scrolls
// up to read older messages we stop yanking them back down.
watch(
  () => agentStore.messages.length,
  async () => {
    // A brand-new message always counts as "user wants to see this" — snap.
    await nextTick()
    scrollToBottom(false)
  }
)

watch(
  () => agentStore.messages.map(m => m.content + (m.toolCalls?.length || 0)).join('|'),
  async () => {
    if (!stuckToBottom.value) return
    await nextTick()
    scrollToBottom(false)
  }
)
</script>

<style scoped>
.agent-chat-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--tg-surface);
}

.agent-header {
  height: 80px;
  padding: 0 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-bottom: 1px solid rgba(226, 232, 240, 0.5);
  z-index: 10;
}

[data-theme="dark"] .agent-header {
  background: rgba(30, 41, 59, 0.9);
  border-bottom: 1px solid rgba(51, 65, 85, 0.5);
}

.agent-header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.mobile-back-btn {
  display: none;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  background: transparent;
  border-radius: 50%;
  color: var(--tg-text-secondary);
  cursor: pointer;
}

.agent-avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--ai-coral-grad);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  box-shadow: 0 6px 18px -6px rgba(59, 120, 221, 0.5);
}

.agent-avatar .material-icons-round {
  font-size: 24px;
}

.agent-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.agent-name {
  font-weight: 700;
  font-size: 17px;
  color: var(--tg-text-primary);
}

.agent-online {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: var(--tg-radius-full);
  background: rgba(34, 197, 94, 0.15);
  color: #22c55e;
  letter-spacing: 0.5px;
}

.agent-subtitle {
  font-size: 13px;
  color: var(--tg-text-secondary);
}

.agent-header-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.action-btn {
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  border-radius: 50%;
  color: var(--tg-text-secondary);
  cursor: pointer;
  transition: var(--tg-transition);
}

.action-btn:hover:not(:disabled) {
  background: var(--ai-coral-soft);
  color: var(--ai-coral);
}

.action-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.model-switcher {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--ai-coral-border);
  background: var(--ai-coral-soft);
  color: var(--ai-coral);
  font-size: 13px;
  cursor: pointer;
  max-width: 240px;
  transition: var(--tg-transition);
}

.model-switcher:hover {
  background: var(--ai-coral-soft-hover);
}

.model-switcher .model-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 160px;
  font-weight: 600;
}

.model-switcher .material-icons-round {
  font-size: 16px;
}

.provider-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 220px;
}
.provider-row strong {
  flex: 0 0 auto;
}
.provider-row .provider-model {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--tg-text-secondary);
  margin-left: auto;
}

.agent-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  /* Prevent overscroll from chaining to the parent layout — without this,
     reaching the bottom on macOS/iOS momentum scroll can ripple up the page
     and snap the view back to the top. */
  overscroll-behavior: contain;
  /* Avoid the browser's "scroll anchoring" feature shifting the viewport
     when streaming deltas mutate the DOM near the bottom. */
  overflow-anchor: none;
  padding: 24px 28px;
  background: var(--ai-hero-bg);
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.agent-empty {
  margin: auto;
  width: 100%;
  max-width: 760px;
  display: flex;
  flex-direction: column;
  gap: 30px;
  padding: 10px 4px;
}

.hero-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
}

.hero-copy {
  flex: 1 1 auto;
  min-width: 0;
  text-align: left;
  animation: aiRise 0.6s cubic-bezier(0.2, 0.8, 0.2, 1) both;
}

.hero-title {
  margin: 0 0 12px;
  font-size: clamp(24px, 3vw, 32px);
  font-weight: 800;
  line-height: 1.2;
  letter-spacing: -0.4px;
  color: var(--tg-text-primary);
}

.hero-brand {
  background: var(--ai-coral-grad);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.hero-sub {
  margin: 0;
  max-width: 440px;
  font-size: 14px;
  line-height: 1.65;
  color: var(--tg-text-secondary);
}

.hero-art {
  flex: 0 0 auto;
  width: 200px;
  animation: aiRise 0.6s 0.08s cubic-bezier(0.2, 0.8, 0.2, 1) both;
}

.art-svg {
  display: block;
  width: 100%;
  height: auto;
}

.art-orb {
  animation: float 6s ease-in-out infinite;
}

.art-spark {
  animation: aiTwinkle 2.6s ease-in-out infinite;
}

.art-spark--2 { animation-delay: 0.7s; }
.art-spark--3 { animation-delay: 1.3s; }

.hero-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.hero-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px;
  text-align: left;
  font-family: inherit;
  border-radius: 16px;
  background: var(--ai-card-bg);
  border: 1px solid var(--ai-card-border);
  box-shadow: 0 4px 16px -10px rgba(15, 23, 42, 0.2);
  cursor: pointer;
  transition: box-shadow 0.25s ease, border-color 0.25s ease, background 0.2s ease;
  animation: aiRise 0.55s both;
  animation-delay: calc(0.18s + var(--i) * 0.09s);
}

.hero-card:hover {
  border-color: var(--ai-coral-border);
  background: var(--ai-coral-soft);
  box-shadow: 0 14px 28px -16px rgba(59, 120, 221, 0.5);
}

.hero-card-icon {
  flex: 0 0 auto;
  width: 38px;
  height: 38px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.hero-card:hover .hero-card-icon {
  transform: scale(1.1) rotate(-4deg);
}

.hero-card-icon .material-icons-round {
  font-size: 20px;
}

.ic-summary { background: rgba(79, 142, 240, 0.14); color: #4f8ef0; }
.ic-report { background: rgba(245, 158, 11, 0.14); color: #f59e0b; }
.ic-reply { background: rgba(20, 184, 166, 0.14); color: #14b8a6; }

.hero-card-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.hero-card-title {
  font-size: 13.5px;
  font-weight: 700;
  color: var(--tg-text-primary);
}

.hero-card-desc {
  font-size: 11.5px;
  line-height: 1.4;
  color: var(--tg-text-secondary);
}

@keyframes aiRise {
  from { opacity: 0; transform: translateY(22px) scale(0.96); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

@keyframes aiTwinkle {
  0%, 100% { opacity: 0.4; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1.12); }
}

.agent-msg {
  display: flex;
}

.msg-user {
  justify-content: flex-end;
}

.msg-assistant {
  justify-content: flex-start;
}

.msg-bubble {
  max-width: 72%;
  padding: 12px 16px;
  border-radius: 18px;
  font-size: 14.5px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  position: relative;
}

.msg-user .msg-bubble {
  background: var(--ai-coral-grad);
  color: #fff;
  border-bottom-right-radius: 6px;
}

.msg-assistant .msg-bubble {
  background: rgba(255, 255, 255, 0.95);
  color: var(--tg-text-primary);
  border: 1px solid rgba(226, 232, 240, 0.6);
  border-bottom-left-radius: 6px;
}

[data-theme="dark"] .msg-assistant .msg-bubble {
  background: rgba(30, 41, 59, 0.85);
  border-color: rgba(51, 65, 85, 0.6);
  color: var(--tg-text-primary);
}

.tool-calls {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.tool-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  background: var(--ai-coral-soft);
  color: var(--ai-coral);
  border: 1px solid var(--ai-coral-border);
}

.tool-chip .material-icons-round {
  font-size: 14px;
}

.tool-success {
  background: rgba(16, 185, 129, 0.1);
  color: #10b981;
  border-color: rgba(16, 185, 129, 0.25);
}

.tool-failed,
.tool-timeout {
  background: rgba(239, 68, 68, 0.1);
  color: #ef4444;
  border-color: rgba(239, 68, 68, 0.25);
}

.tool-spinner {
  width: 10px;
  height: 10px;
  border: 2px solid var(--ai-coral-border);
  border-top-color: var(--ai-coral);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.tool-latency {
  font-size: 11px;
  opacity: 0.75;
}

.msg-text {
  white-space: pre-wrap;
}

.caret {
  display: inline-block;
  width: 6px;
  height: 14px;
  margin-left: 2px;
  background: var(--ai-coral);
  vertical-align: middle;
  border-radius: 1px;
  animation: blink 1s steps(2) infinite;
}

@keyframes blink {
  50% { opacity: 0; }
}

.msg-error {
  margin-top: 8px;
  font-size: 12px;
  color: #ef4444;
}

.agent-input-area {
  padding: 16px 24px 14px;
  border-top: 1px solid rgba(226, 232, 240, 0.5);
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(20px);
}

[data-theme="dark"] .agent-input-area {
  background: rgba(30, 41, 59, 0.85);
  border-top-color: rgba(51, 65, 85, 0.5);
}

.agent-input-wrapper {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}

.agent-textarea :deep(.el-textarea__inner) {
  border-radius: 14px;
  padding: 12px 14px;
  font-size: 14.5px;
  border-color: rgba(226, 232, 240, 0.8);
  resize: none;
}

.agent-textarea :deep(.el-textarea__inner):focus {
  border-color: var(--ai-coral);
  box-shadow: 0 0 0 2px var(--ai-coral-soft);
}

.agent-send {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: none;
  background: var(--ai-coral-grad);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: var(--ai-coral-shadow);
  transition: var(--tg-transition);
}

.agent-send:hover:not(:disabled) {
  transform: translateY(-1px) scale(1.03);
}

.agent-send:disabled {
  opacity: 0.4;
  cursor: not-allowed;
  box-shadow: none;
}

.agent-stop {
  background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
}

.agent-hint {
  margin-top: 6px;
  font-size: 11px;
  color: var(--tg-text-secondary);
  text-align: center;
}

@media (max-width: 768px) {
  .mobile-back-btn { display: flex; }
  .agent-header { height: 64px; padding: 0 12px; }
  .agent-messages { padding: 16px; }
  .msg-bubble { max-width: 85%; }
  .hero-top { flex-direction: column; align-items: center; text-align: center; }
  .hero-copy { text-align: center; }
  .hero-sub { margin-left: auto; margin-right: auto; }
  .hero-art { width: 150px; order: -1; }
  .hero-cards { grid-template-columns: 1fr; }
}
</style>

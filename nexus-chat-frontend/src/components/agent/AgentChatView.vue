<template>
  <!-- Prism AI canvas: header with model switcher, abyssal stage, message stream
       with a live process terminal per answer, floating composer dock. -->
  <section class="agent-chat-view flex-1 flex flex-col h-full min-h-0 bg-surface relative overflow-hidden">
    <div
      class="absolute inset-0 pointer-events-none opacity-[0.03] bg-[radial-gradient(#ffffff_1px,transparent_1px)] [background-size:24px_24px]"
    ></div>

    <header
      class="h-14 px-space-md md:px-space-lg flex items-center justify-between gap-space-md bg-surface-container-lowest/80 backdrop-blur-md border-b border-outline-variant/30 z-20 shrink-0"
    >
      <div class="flex items-center gap-space-sm md:gap-space-md min-w-0">
        <button
          v-if="isMobile"
          class="w-8 h-8 rounded flex items-center justify-center text-on-surface-variant hover:text-primary hover:bg-surface-container-low transition-colors shrink-0"
          type="button"
          :aria-label="$t('common.back')"
          @click="handleMobileBack"
        >
          <span class="material-symbols-outlined text-[18px]">arrow_back</span>
        </button>
        <div
          class="w-8 h-8 rounded bg-surface-container-high border border-outline-variant/40 flex items-center justify-center text-primary shrink-0"
        >
          <span class="material-symbols-outlined text-[18px]">auto_awesome</span>
        </div>
        <div class="min-w-0">
          <div class="flex items-center gap-2">
            <h2 class="font-headline-md text-headline-md text-primary font-medium tracking-tight truncate">
              {{ agentStore.activeSession?.title || $t('agent.title') }}
            </h2>
            <span class="w-1.5 h-1.5 rounded-full bg-primary animate-pulse shrink-0"></span>
            <span class="hidden lg:inline font-code text-label-sm text-on-surface-variant/70 shrink-0">{{ $t('agent.online') }}</span>
          </div>
          <div class="font-code text-[11px] text-on-surface-variant/60 truncate">{{ $t('agent.subtitle') }}</div>
        </div>
      </div>

      <div class="flex items-center gap-space-xs lg:gap-space-sm shrink-0">
        <el-dropdown trigger="click" @command="onSelectProvider">
          <button
            class="flex items-center gap-2 px-3 py-1.5 rounded bg-surface-container-low border border-outline-variant/40 text-primary font-label-md text-label-md hover:bg-surface-container hover:border-outline-variant transition-colors"
            type="button"
            :title="$t('agent.providers.title')"
          >
            <span class="material-symbols-outlined text-[16px] text-primary">memory</span>
            <span class="hidden xl:inline max-w-[220px] truncate">{{ activeProviderLabel }}</span>
            <span class="material-symbols-outlined text-[16px] text-on-surface-variant">expand_more</span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="p in providersStore.providers"
                :key="p.id"
                :command="p.id"
                :disabled="!p.hasApiKey"
              >
                <div class="flex items-center gap-2 min-w-[220px]">
                  <strong class="font-medium">{{ p.displayName || p.provider }}</strong>
                  <span class="ml-auto font-code text-[12px] text-on-surface-variant">{{ p.defaultModel || '—' }}</span>
                  <span
                    v-if="agentStore.activeProviderId === p.id"
                    class="font-code text-[10px] px-1.5 py-0.5 rounded bg-surface-container-high border border-outline-variant/30 text-primary"
                  >{{ $t('agent.providers.using') }}</span>
                </div>
              </el-dropdown-item>
              <el-dropdown-item v-if="!providersStore.providers.length" disabled>
                {{ $t('agent.providers.empty') }}
              </el-dropdown-item>
              <el-dropdown-item divided command="__settings__">
                <span class="material-symbols-outlined text-[16px] mr-1.5">tune</span>
                <span>{{ $t('agent.providers.manage') }}</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>

        <button
          class="w-8 h-8 rounded flex items-center justify-center text-on-surface-variant hover:text-primary hover:bg-surface-container-low transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
          type="button"
          :title="$t('agent.clearMemory')"
          :disabled="agentStore.streaming"
          @click="onClear"
        >
          <span class="material-symbols-outlined text-[18px]">restart_alt</span>
        </button>
        <button
          class="w-8 h-8 rounded flex items-center justify-center text-on-surface-variant hover:text-primary hover:bg-surface-container-low transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
          type="button"
          :title="$t('agent.export')"
          :disabled="!agentStore.hasMessages"
          @click="onExport"
        >
          <span class="material-symbols-outlined text-[18px]">ios_share</span>
        </button>
        <button
          class="w-8 h-8 rounded flex items-center justify-center text-on-surface-variant hover:text-primary hover:bg-surface-container-low transition-colors"
          type="button"
          :title="$t('agent.providers.manage')"
          @click="providerDialogVisible = true"
        >
          <span class="material-symbols-outlined text-[18px]">tune</span>
        </button>
      </div>
    </header>

    <AgentProviderSettings v-model:visible="providerDialogVisible" />
    <KnowledgeBaseManager v-model:visible="kbDialogVisible" />

    <!-- Abyssal stage -->
    <div class="agent-stage px-space-md md:px-space-xl pt-3 pb-1 shrink-0 z-20 flex justify-center w-full">
      <div
        class="w-full max-w-xl relative rounded-xl border border-outline-variant/40 bg-surface-container-lowest/80 backdrop-blur-md shadow-2xl overflow-hidden"
      >
        <div
          class="absolute inset-0 pointer-events-none opacity-[0.06] bg-[radial-gradient(#ffffff_1px,transparent_1px)] [background-size:16px_16px]"
        ></div>
        <div
          class="absolute -top-12 left-1/2 -translate-x-1/2 w-72 h-16 bg-white/10 blur-2xl pointer-events-none rounded-full"
        ></div>

        <div
          class="relative z-10 px-3.5 py-2 flex items-center justify-between gap-2 border-b border-outline-variant/20 bg-surface-container-low/40"
        >
          <div class="flex items-center gap-2 min-w-0">
            <span class="w-2 h-2 rounded-full bg-primary animate-ping opacity-75 shrink-0"></span>
            <span class="font-headline-md text-[13px] text-primary font-medium tracking-tight truncate">{{ $t('agent.title') }} // HOLOGRAPHIC 3D</span>
          </div>
          <div class="flex items-center gap-2 font-code text-[11px] text-on-surface-variant shrink-0">
            <span class="inline-flex items-center gap-1 text-primary">
              <span class="material-symbols-outlined text-[13px]">air</span>
              <span>{{ agentStore.streaming ? $t('agent.thinking') : $t('agent.stage.idle') }}</span>
            </span>
            <span class="text-outline-variant/50">|</span>
            <span>{{ $t('agent.history.messageCount', { n: agentStore.messages.length }) }}</span>
          </div>
        </div>

        <div class="relative w-full h-[180px] overflow-hidden">
          <div ref="stageHost" class="absolute inset-0"></div>
          <!-- Corner reticles: each readout is live state, not decoration. -->
          <div class="absolute top-2 left-2 pointer-events-none z-10 flex items-center gap-1 opacity-70">
            <div class="w-2.5 h-2.5 border-t border-l border-primary"></div>
            <span class="font-code text-[9px] text-on-surface-variant/80 tracking-tighter">SID:{{ (agentStore.sessionId || '----').slice(-4).toUpperCase() }}</span>
          </div>
          <div class="absolute top-2 right-2 pointer-events-none z-10 flex items-center gap-1 opacity-70">
            <span class="font-code text-[9px] text-on-surface-variant/80 tracking-tighter">CTX:{{ lastInputTokens }}</span>
            <div class="w-2.5 h-2.5 border-t border-r border-primary"></div>
          </div>
          <div class="absolute bottom-2 left-2 pointer-events-none z-10 flex items-center gap-1 opacity-70">
            <div class="w-2.5 h-2.5 border-b border-l border-primary"></div>
            <span class="font-code text-[9px] text-on-surface-variant/80 tracking-tighter">KB:{{ knowledgeStore.activeKbId ? 'LINKED' : 'OFF' }}</span>
          </div>
          <div class="absolute bottom-2 right-2 pointer-events-none z-10 flex items-center gap-1 opacity-70">
            <span class="font-code text-[9px] text-on-surface-variant/80 tracking-tighter">CORE:{{ agentStore.streaming ? 'LIVE' : 'IDLE' }}</span>
            <div class="w-2.5 h-2.5 border-b border-r border-primary"></div>
          </div>
          <div
            class="absolute inset-x-0 bottom-0 h-10 pointer-events-none bg-gradient-to-t from-surface-container-lowest to-transparent opacity-80"
          ></div>
          <div
            class="absolute inset-x-0 top-0 h-4 pointer-events-none bg-gradient-to-b from-surface-container-lowest/60 to-transparent"
          ></div>
        </div>

        <div
          class="relative z-10 px-3.5 py-1.5 flex items-center justify-between gap-2 border-t border-outline-variant/20 bg-surface-container-lowest/60 text-on-surface-variant font-code text-[11px]"
        >
          <span
            class="px-2 py-0.5 rounded bg-surface-container border border-outline-variant/30 text-primary truncate"
          >{{ activeProviderLabel }}</span>
          <div class="hidden sm:flex items-center gap-1.5 text-on-surface-variant/60 text-[10px] shrink-0">
            <span class="material-symbols-outlined text-[13px] text-primary">touch_app</span>
            <span>{{ $t('agent.stage.hint') }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Message stream -->
    <div
      ref="scrollEl"
      class="agent-messages flex-1 min-h-0 overflow-y-auto px-space-md md:px-space-xl py-space-lg space-y-space-lg relative z-10"
    >
      <div v-if="agentStore.hasMessages" class="flex items-center justify-center gap-3 my-2">
        <div class="h-px w-16 bg-outline-variant/30"></div>
        <span class="font-code text-label-sm text-on-surface-variant/60 tracking-widest uppercase truncate">
          {{ $t('agent.sessionAnchor', { title: agentStore.activeSession?.title || $t('agent.history.untitled') }) }}
        </span>
        <div class="h-px w-16 bg-outline-variant/30"></div>
      </div>

      <div v-else class="max-w-3xl flex flex-col gap-space-sm">
        <h2 class="font-headline-lg-mobile text-headline-lg-mobile text-primary">{{ $t('agent.welcomeTitle') }}</h2>
        <p class="font-body-lg text-body-lg text-on-surface-variant max-w-2xl">{{ $t('agent.welcomeBody') }}</p>
        <div class="mt-3 flex flex-wrap items-center gap-2">
          <span class="font-code text-[11px] text-on-surface-variant/70 px-1 flex items-center gap-1">
            <span class="material-symbols-outlined text-[13px] text-primary">lightbulb</span>
            {{ $t('agent.suggestions') }}
          </span>
          <button
            v-for="card in welcomeCards"
            :key="card.key"
            class="px-3 py-1 rounded bg-surface-container-low border border-outline-variant/30 text-on-surface font-label-md text-label-md hover:bg-surface-container hover:text-primary transition-colors flex items-center gap-1"
            type="button"
            :title="card.desc"
            @click="prefill(card.prompt)"
          >
            <span>{{ card.title }}</span>
            <span class="material-symbols-outlined text-[13px]">arrow_forward</span>
          </button>
        </div>
      </div>

      <template v-for="msg in agentStore.messages" :key="msg.id">
        <!-- User prompt -->
        <div v-if="msg.role === 'user'" class="flex justify-end items-start gap-3 pl-8 md:pl-16">
          <div class="flex flex-col items-end max-w-2xl min-w-0">
            <div class="flex items-center gap-2 mb-1 px-1">
              <span class="font-label-sm text-label-sm text-on-surface-variant">{{ $t('agent.you') }}</span>
              <span v-if="msg.createdAt" class="font-code text-[11px] text-on-surface-variant/60">{{ dayjs(msg.createdAt).format('HH:mm') }}</span>
            </div>
            <div class="p-space-md rounded-xl bg-surface-container-high border border-outline-variant/40 text-primary max-w-full">
              <p class="font-body-lg text-body-lg text-primary leading-relaxed whitespace-pre-wrap break-words">{{ msg.content }}</p>
            </div>
          </div>
          <div
            class="w-8 h-8 rounded-full bg-surface-container-high border border-outline-variant/40 flex items-center justify-center shrink-0 mt-5 overflow-hidden"
          >
            <img
              v-if="userStore.currentUser?.avatar"
              class="w-full h-full object-cover grayscale"
              :src="userStore.currentUser.avatar"
              alt=""
            />
            <span v-else class="material-symbols-outlined text-primary text-[18px]">person</span>
          </div>
        </div>

        <!-- AI response -->
        <div v-else class="flex justify-start items-start gap-3 pr-4 md:pr-12">
          <div
            class="w-8 h-8 rounded bg-surface-container-high border border-outline-variant/40 flex items-center justify-center shrink-0 text-primary mt-1"
          >
            <span class="material-symbols-outlined text-[18px]">auto_awesome</span>
          </div>
          <div class="flex-1 max-w-3xl min-w-0 flex flex-col">
            <div class="flex items-center gap-2 mb-1.5 px-1">
              <span class="font-headline-md text-headline-md text-primary font-medium">{{ $t('agent.title') }}</span>
              <span
                v-if="msg.status === 'streaming'"
                class="font-code text-[10px] px-1.5 py-0.5 rounded bg-surface-container-low border border-outline-variant/30 text-on-surface-variant"
              >{{ $t('agent.thinking') }}</span>
              <span v-if="msg.createdAt" class="font-code text-[10px] text-on-surface-variant/50 ml-auto">{{ dayjs(msg.createdAt).format('HH:mm:ss') }}</span>
            </div>
            <div
              class="rounded-xl bg-surface-container-lowest/90 border border-outline-variant/40 p-space-md md:p-space-lg text-on-surface space-y-space-md shadow-lg"
            >
              <AgentTrace
                v-if="msg.trace?.length || msg.status === 'streaming'"
                :trace="msg.trace || []"
                :live="msg.status === 'streaming'"
                :elapsed-ms="msg.elapsedMs"
              />

              <p
                v-if="msg.content"
                class="font-body-lg text-body-lg leading-relaxed whitespace-pre-wrap break-words"
                :class="msg.status === 'error' ? 'text-error' : 'text-on-surface'"
              >{{ msg.content }}<span v-if="msg.status === 'streaming'" class="caret"></span></p>

              <div
                v-if="msg.status !== 'streaming'"
                class="pt-3 border-t border-outline-variant/20 flex flex-wrap items-center justify-between gap-2"
              >
                <button
                  class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded bg-surface-container-low border border-outline-variant/30 text-on-surface font-label-md text-label-md hover:bg-surface-container hover:text-primary transition-colors"
                  type="button"
                  @click="onCopy(msg)"
                >
                  <span class="material-symbols-outlined text-[15px]">content_copy</span>
                  <span>{{ $t('agent.copy') }}</span>
                </button>
                <span v-if="msg.usage?.totalTokens" class="font-code text-[10px] text-on-surface-variant/60">
                  {{ $t('agent.tokens', { n: msg.usage.totalTokens }) }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- Composer dock -->
    <footer class="p-space-md md:p-space-lg pt-0 md:pt-0 relative z-20 shrink-0">
      <div
        class="relative w-full rounded-xl bg-surface-container-lowest/90 backdrop-blur-md border border-outline-variant/40 p-2.5 shadow-xl transition-all focus-within:border-outline"
      >
        <div
          class="px-1.5 py-1 flex items-center justify-between gap-2 text-on-surface-variant border-b border-outline-variant/20 mb-1.5"
        >
          <div class="flex items-center gap-1.5 min-w-0">
            <div
              class="flex items-center gap-1.5 px-2 py-0.5 rounded bg-surface-container border border-outline-variant/30 text-primary font-code text-[11px] shrink-0"
            >
              <span class="w-1.5 h-1.5 rounded-full bg-primary" :class="{ 'animate-pulse': agentStore.streaming }"></span>
              <span>{{ $t('agent.contextCount', { n: agentStore.messages.length }) }}</span>
              <span v-if="lastInputTokens" class="hidden sm:inline text-on-surface-variant">· {{ $t('agent.contextTokens', { n: lastInputTokens }) }}</span>
            </div>
            <!-- One-click prompts: prefill the input, nothing is sent until the user does. -->
            <button
              v-for="card in welcomeCards.slice(0, 2)"
              :key="card.key"
              class="hidden lg:flex items-center gap-1 px-2 py-0.5 rounded hover:bg-surface-container text-on-surface-variant hover:text-primary font-label-sm text-label-sm transition-colors min-w-0"
              type="button"
              :title="card.desc"
              @click="prefill(card.prompt)"
            >
              <span class="material-symbols-outlined text-[14px] shrink-0">{{ card.icon }}</span>
              <span class="truncate">{{ card.title }}</span>
            </button>
          </div>
          <KnowledgeBaseSelector @manage="kbDialogVisible = true" />
        </div>

        <div class="flex items-end gap-2">
          <div class="flex items-center gap-0.5 pb-0.5 text-on-surface-variant">
            <button
              class="w-8 h-8 rounded flex items-center justify-center hover:bg-surface-container hover:text-primary transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              type="button"
              :title="$t('agent.history.newConversation')"
              :disabled="agentStore.streaming"
              @click="onNewSession"
            >
              <span class="material-symbols-outlined text-[18px]">add_comment</span>
            </button>
            <button
              class="w-8 h-8 rounded flex items-center justify-center hover:bg-surface-container hover:text-primary transition-colors"
              type="button"
              :title="$t('agent.knowledge.manage')"
              @click="kbDialogVisible = true"
            >
              <span class="material-symbols-outlined text-[18px]">folder_open</span>
            </button>
          </div>
          <div class="flex-1 min-w-0">
            <textarea
              ref="inputEl"
              v-model="draft"
              class="agent-input block w-full bg-transparent text-primary font-body-lg text-body-lg placeholder:text-on-surface-variant/40 focus:outline-none resize-none py-1 px-1.5 max-h-32 leading-relaxed"
              :placeholder="$t('agent.placeholder')"
              :disabled="agentStore.streaming"
              rows="1"
              @keydown.enter="onEnter"
            ></textarea>
          </div>
          <div class="flex items-center gap-1.5 pb-0.5">
            <button
              class="w-8 h-8 rounded flex items-center justify-center text-on-surface-variant hover:bg-surface-container hover:text-primary transition-colors"
              type="button"
              :title="$t('agent.providers.manage')"
              @click="providerDialogVisible = true"
            >
              <span class="material-symbols-outlined text-[18px]">tune</span>
            </button>
            <button
              v-if="!agentStore.streaming"
              class="group px-4 py-2 rounded-lg bg-primary text-on-primary hover:bg-primary-container active:scale-95 font-label-md text-label-md font-medium flex items-center gap-1.5 transition-all shadow disabled:opacity-30 disabled:cursor-not-allowed disabled:active:scale-100"
              type="button"
              :disabled="!draft.trim()"
              @click="onSend"
            >
              <span>{{ $t('agent.send') }}</span>
              <span class="material-symbols-outlined text-[16px] transition-transform group-hover:translate-x-0.5">send</span>
            </button>
            <button
              v-else
              class="px-4 py-2 rounded-lg bg-[#b3261e] text-primary active:scale-95 font-label-md text-label-md font-medium flex items-center gap-1.5 transition-all shadow"
              type="button"
              @click="agentStore.cancelStream()"
            >
              <span>{{ $t('agent.stop') }}</span>
              <span class="material-symbols-outlined text-[16px]">stop</span>
            </button>
          </div>
        </div>
      </div>

      <div class="w-full flex flex-wrap items-center justify-center gap-x-3 gap-y-0.5 mt-2 text-center font-code text-[11px] text-on-surface-variant/60">
        <span class="flex items-center gap-1">
          <span class="material-symbols-outlined text-[13px]">lock</span>
          {{ $t('agent.inputHint') }}
        </span>
        <span class="hidden sm:inline">·</span>
        <span class="hidden sm:inline">{{ activeProviderLabel }}</span>
        <span class="hidden md:inline">·</span>
        <span class="hidden md:inline text-on-surface">{{ $t('agent.traceHint') }}</span>
      </div>
    </footer>
  </section>
</template>

<script setup>
import { computed, inject, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { useI18n } from 'vue-i18n'
import { useAgentStore } from '@/stores/agent'
import { useAgentProvidersStore } from '@/stores/agentProviders'
import { useChatStore } from '@/stores/chat'
import { useKnowledgeStore } from '@/stores/knowledge'
import { useUserStore } from '@/stores/user'
import AgentProviderSettings from '@/components/agent/AgentProviderSettings.vue'
import KnowledgeBaseManager from '@/components/agent/KnowledgeBaseManager.vue'
import KnowledgeBaseSelector from '@/components/agent/KnowledgeBaseSelector.vue'
import AgentTrace from '@/components/agent/AgentTrace.vue'
import { mountAbyssStage } from '@/components/agent/abyssStage'

const { t } = useI18n()
const agentStore = useAgentStore()
const providersStore = useAgentProvidersStore()
const chatStore = useChatStore()
const knowledgeStore = useKnowledgeStore()
const userStore = useUserStore()
const isMobile = inject('isMobile', { value: false })

const draft = ref('')
const scrollEl = ref(null)
const inputEl = ref(null)
const stageHost = ref(null)
let stageTeardown = null
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

// Input tokens of the latest finished turn: how much context the model was
// actually sent, as reported by the provider.
const lastInputTokens = computed(() =>
  agentStore.messages.findLast((m) => m.usage?.inputTokens)?.usage.inputTokens ?? 0
)

async function onNewSession() {
  try {
    await agentStore.newSession()
    ElMessage.success(t('agent.history.created'))
  } catch (err) {
    ElMessage.error(err.message || t('common.failed'))
  }
}

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
  stageTeardown = mountAbyssStage(stageHost.value)
})

onUnmounted(() => {
  scrollEl.value?.removeEventListener('scroll', onUserScroll)
  stageTeardown?.()
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

// Enter sends; Shift+Enter breaks the line. Enter that only confirms an IME
// candidate (Chinese / Japanese input) must not send.
function onEnter(e) {
  if (e.isComposing || e.shiftKey) return
  e.preventDefault()
  onSend()
}

watch(draft, async () => {
  await nextTick()
  inputEl.value.style.height = 'auto'
  inputEl.value.style.height = `${inputEl.value.scrollHeight}px`
})

async function onCopy(msg) {
  try {
    await navigator.clipboard.writeText(msg.content)
    ElMessage.success(t('agent.copied'))
  } catch {
    ElMessage.error(t('common.failed'))
  }
}

function onExport() {
  const title = agentStore.activeSession?.title || t('agent.title')
  const body = agentStore.messages
    .map((m) => `## ${m.role === 'user' ? t('agent.you') : t('agent.title')}\n\n${m.content}`)
    .join('\n\n')
  const link = document.createElement('a')
  link.href = URL.createObjectURL(new Blob([`# ${title}\n\n${body}\n`], { type: 'text/markdown' }))
  link.download = `${title}.md`
  link.click()
  URL.revokeObjectURL(link.href)
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
  chatStore.clearActiveChat()
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
  () => agentStore.messages.map(m => m.content.length + ':' + (m.trace?.length || 0)).join('|'),
  async () => {
    if (!stuckToBottom.value) return
    await nextTick()
    scrollToBottom(false)
  }
)
</script>

<style scoped>
/* Short windows fall back to the stage-less layout so the stream keeps its room. */
@media (max-height: 719px) {
  .agent-stage {
    display: none;
  }
}

.agent-messages {
  /* Reaching the bottom on momentum scroll must not ripple up the page. */
  overscroll-behavior: contain;
  /* Streaming deltas mutate the DOM near the bottom; no scroll anchoring. */
  overflow-anchor: none;
}

.agent-messages::-webkit-scrollbar {
  width: 6px;
}

.agent-messages::-webkit-scrollbar-thumb {
  background: #353438;
  border-radius: 9999px;
}

.caret {
  display: inline-block;
  width: 5px;
  height: 1em;
  margin-left: 2px;
  background: #ffffff;
  vertical-align: text-bottom;
  border-radius: 1px;
  animation: blink 1s steps(2) infinite;
}

@keyframes blink {
  50% { opacity: 0; }
}
</style>

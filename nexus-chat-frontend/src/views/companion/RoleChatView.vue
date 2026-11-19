<template>
  <!-- Prism role conversation: the companion lives in the main area now, with
       its own dossier beside it instead of a floating window. -->
  <section class="role-chat">
    <header class="role-chat-head">
      <button
        class="role-chat-back"
        type="button"
        :aria-label="$t('common.back')"
        @click="emit('back')"
      >
        <span class="material-symbols-outlined">arrow_back</span>
      </button>

      <div class="role-chat-avatar">
        <img v-if="activeRole?.avatarUrl" :src="activeRole.avatarUrl" alt="" />
        <span v-else class="material-symbols-outlined">person</span>
      </div>

      <div class="role-chat-id">
        <div class="role-chat-name">{{ activeRole?.name || $t('companion.defaultName') }}</div>
        <div class="role-chat-sub">{{ activeRole?.persona || $t('companion.defaultStatus') }}</div>
      </div>

      <button
        v-if="stageVisible"
        class="role-mini-badge"
        :class="{ 'is-visible': stageCollapsed }"
        type="button"
        :aria-label="$t('companion.stage.expand')"
        :aria-hidden="!stageCollapsed"
        :tabindex="stageCollapsed ? 0 : -1"
        :title="$t('companion.stage.expand')"
        @click="expandStage"
      >
        <span class="role-mini-visual" aria-hidden="true">
          <span ref="miniHoloHost" class="role-mini-canvas"></span>
          <span class="role-mini-radar"></span>
          <span class="role-mini-node"></span>
        </span>
        <span class="role-mini-copy">
          <span>{{ $t('companion.stage.online') }}</span>
          <span class="role-mini-value">{{ growth?.intimacy ?? '—' }}%</span>
        </span>
        <span class="material-symbols-outlined role-mini-expand">expand_more</span>
      </button>

      <div class="role-chat-actions">
        <!-- The model capsule from the design export: which engine is behind
             this character, one click from its settings. -->
        <button
          class="role-model-capsule"
          type="button"
          :title="$t('companion.modelSettings.trigger')"
          @click="modelPanelOpen = true"
        >
          <span class="material-symbols-outlined role-model-icon">memory</span>
          <span class="role-model-text">
            <span class="role-model-name">{{ modelLabel }}</span>
            <span class="role-model-sub">{{ modelSubLabel }}</span>
          </span>
          <span class="material-symbols-outlined role-model-chevron">expand_more</span>
        </button>

        <button class="role-chat-switch" type="button" @click="emit('goAi')">
          <span class="material-symbols-outlined">auto_awesome</span>
          <span>{{ $t('companion.goAssistant') }}</span>
        </button>
        <button
          class="role-chat-icon"
          type="button"
          :class="{ 'is-on': dossierOpen }"
          :title="$t('companion.dossier')"
          @click="dossierOpen = !dossierOpen"
        >
          <span class="material-symbols-outlined">badge</span>
        </button>
      </div>
    </header>

    <ModelSettingsPanel v-model:open="modelPanelOpen" @saved="refreshModelChannel" />

    <div
      v-if="stageVisible"
      class="role-stage"
      :class="{ 'is-collapsed': stageCollapsed }"
      :aria-hidden="stageCollapsed"
      :inert="stageCollapsed"
    >
      <div class="role-stage-frame">
        <div class="role-stage-glow" aria-hidden="true"></div>
        <div ref="stageHost" class="role-stage-canvas"></div>
        <span class="role-stage-corner is-tl" aria-hidden="true"></span>
        <span class="role-stage-corner is-tr" aria-hidden="true"></span>
        <span class="role-stage-corner is-bl" aria-hidden="true"></span>
        <span class="role-stage-corner is-br" aria-hidden="true"></span>

        <div class="role-stage-sync">
          <span class="role-stage-live-dot"></span>
          <span>{{ $t('companion.stage.sync') }}</span>
          <span class="role-stage-sync-code">{{ $t('companion.stage.synchronized') }}</span>
        </div>

        <button
          class="role-stage-collapse"
          type="button"
          :title="$t('companion.stage.collapse')"
          @click="setStageCollapsed(true)"
        >
          <span class="material-symbols-outlined">expand_less</span>
          <span>{{ $t('companion.stage.collapse') }}</span>
        </button>

        <div class="role-stage-detail">
          <div class="role-stage-kicker">{{ $t('companion.stage.projection') }}</div>
          <div class="role-stage-title">{{ activeRole?.name || $t('companion.defaultName') }}</div>
          <p class="role-stage-summary">{{ stageStatusText }}</p>
          <div class="role-stage-metrics">
            <div class="role-stage-metric">
              <span>{{ $t('companion.stage.intimacy') }}</span>
              <strong>{{ growth?.intimacy ?? '—' }}%</strong>
            </div>
            <div class="role-stage-metric">
              <span>{{ $t('companion.stage.mood') }}</span>
              <strong>{{ stageMoodText }}</strong>
            </div>
            <div class="role-stage-metric">
              <span>{{ $t('companion.stage.scene') }}</span>
              <strong>{{ $t(`companion.sceneNames.${scene.key}`) }}</strong>
            </div>
          </div>
        </div>

        <div class="role-stage-mesh" aria-hidden="true">
          <span class="role-stage-live-dot"></span>
          <span>{{ $t('companion.stage.live') }}</span>
        </div>
      </div>
    </div>

    <MindOrbit
      ref="orbitRef"
      @mood="onOrbitMood"
      :text="draft"
      :sending="sending"
      :intimacy="growth?.intimacy ?? null"
      :status-type="status?.statusType || ''"
      :status-summary="status?.summary || ''"
      :memory="latestMemory"
      :last-role-reply="lastRoleReply"
      :load-whisper="loadWhisper"
    />

    <div class="role-chat-body">
      <div class="role-chat-main">
        <div ref="scrollEl" class="role-chat-stream" tabindex="-1" @scroll.passive="onStreamScroll">
          <!-- The room, stated once at the top of the stream. This is the scene
               the character is actually in — the same text goes into the prompt. -->
          <div class="role-scene-anchor">
            <div class="role-scene-rule"></div>
            <span class="role-scene-text">SCENE // {{ $t(`companion.sceneAmbience.${scene.key}`) }}</span>
            <div class="role-scene-rule"></div>
          </div>

          <!-- Loading and "nothing said yet" are different rooms. Without the
               spinner the tavern looks frozen for the first second. -->
          <div v-if="isLoadingRoleData" class="role-loading">
            <span class="role-loading-spinner"></span>
            <span>{{ $t('companion.loadingConversation') }}</span>
          </div>
          <p v-else-if="!messages.length" class="role-chat-empty">{{ $t('companion.chatEmpty') }}</p>
          <div
            v-for="msg in messages"
            :key="msg.id"
            class="role-msg"
            :class="msg.senderType === 'user' ? 'is-user' : 'is-role'"
          >
            <div class="role-bubble">
              <p class="role-text">{{ msg.content }}</p>
              <span v-if="msg.senderType !== 'user' && msg.fallback" class="role-tag">
                {{ $t('companion.fallback') }}
              </span>
            </div>
            <div class="role-meta">
              <span>{{ dayjs(msg.createdAt).format('HH:mm') }}</span>
              <!-- What this exchange changed, read back from the server after the reply. -->
              <template v-if="growthGain?.messageId === msg.id">
                <span v-for="gain in growthGain.items" :key="gain.key" class="role-gain">
                  {{ $t(`companion.${gain.key}`) }} +{{ gain.delta }}
                </span>
              </template>
            </div>
          </div>
          <div v-if="sending" class="role-msg is-role">
            <div class="role-bubble role-typing">
              <span class="material-symbols-outlined">progress_activity</span>
              <span>{{ $t('companion.typing') }}</span>
            </div>
          </div>
        </div>

        <button
          v-if="stageVisible"
          class="role-stage-demo"
          type="button"
          :aria-pressed="stageCollapsed"
          @click="toggleStageDemo"
        >
          <span class="role-stage-demo-dot" aria-hidden="true"></span>
          <span class="material-symbols-outlined">motion_mode</span>
          <span>{{ $t(stageCollapsed ? 'companion.stage.demoExpand' : 'companion.stage.demoCollapse') }}</span>
        </button>

        <div class="role-composer">
          <!-- Scene switch: sits above the input like the design export's
               "切换场景氛围", because changing the room is a choice about the
               next message, not about the conversation's history. -->
          <div class="role-scene-bar">
            <button
              class="role-scene-btn"
              type="button"
              :aria-expanded="scenePickerOpen"
              @click="scenePickerOpen = !scenePickerOpen"
            >
              <span class="material-symbols-outlined">{{ scene.icon }}</span>
              <span>{{ $t('companion.scene.switchTo', { name: $t(`companion.sceneNames.${scene.key}`) }) }}</span>
              <span class="material-symbols-outlined role-scene-caret">
                {{ scenePickerOpen ? 'expand_less' : 'expand_more' }}
              </span>
            </button>

            <!-- Closing the evening: only when one is actually open. -->
            <button
              v-if="activeSession"
              class="role-close-btn"
              type="button"
              :disabled="closing"
              :title="$t('companion.session.closeHint')"
              @click="closeEvening"
            >
              <span class="material-symbols-outlined">{{ closing ? 'progress_activity' : 'door_open' }}</span>
              <span>{{ closing ? $t('companion.session.closing') : $t('companion.session.close') }}</span>
            </button>
          </div>

          <Transition name="role-scene-fold">
            <div v-if="scenePickerOpen" class="role-scene-menu">
              <button
                v-for="option in scenes"
                :key="option.key"
                class="role-scene-option"
                :class="{ 'is-active': option.key === sceneKey }"
                type="button"
                @click="chooseScene(option.key)"
              >
                <span class="material-symbols-outlined">{{ option.icon }}</span>
                <span class="role-scene-option-text">
                  <span class="role-scene-option-name">{{ $t(`companion.sceneNames.${option.key}`) }}</span>
                  <span class="role-scene-option-desc">{{ $t(`companion.sceneAmbience.${option.key}`) }}</span>
                </span>
                <span v-if="option.key === sceneKey" class="material-symbols-outlined role-scene-check">check</span>
              </button>
            </div>
          </Transition>

          <div class="role-composer-box">
            <textarea
              ref="inputEl"
              v-model="draft"
              class="role-input-area"
              :placeholder="$t('companion.inputPlaceholder')"
              rows="1"
              :disabled="sending"
              @keydown.enter="onEnter"
              @keydown="orbitRef?.noteKeystroke()"
            ></textarea>
            <button
              class="role-send"
              type="button"
              :disabled="!draft.trim() || sending"
              @click="send"
            >
              <span>{{ $t('companion.send') }}</span>
              <span class="material-symbols-outlined">send</span>
            </button>
          </div>
        </div>
      </div>

      <TavernBusy
        :visible="closing"
        :title="$t('companion.session.closingTitle')"
        :hint="$t('companion.session.closingHint')"
      />

      <div v-if="dossierOpen && narrow" class="role-chat-scrim" @click="dossierOpen = false"></div>
      <RoleDossier v-if="dossierOpen" @close="dossierOpen = false" />
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { useCompanionStore } from '@/stores/companion'
import { useUserStore } from '@/stores/user'
import RoleDossier from '@/components/companion/RoleDossier.vue'
import MindOrbit from '@/components/companion/MindOrbit.vue'
import ModelSettingsPanel from '@/components/companion/ModelSettingsPanel.vue'
import TavernBusy from '@/components/companion/TavernBusy.vue'
import { mountAbyssStage, mountMiniHologram } from '@/components/agent/abyssStage'
import { TAVERN_SCENES, findScene, readSceneKey, writeSceneKey } from '@/components/companion/tavernScenes'

const emit = defineEmits(['back', 'goAi', 'mood'])

const { t } = useI18n()
const userStore = useUserStore()
const companionStore = useCompanionStore()
const { activeRole, activeRoleId, messages, growth, status, memories, activeSession, isLoadingRoleData } = storeToRefs(companionStore)

const draft = ref('')
const sending = ref(false)
// The dossier is reference material, not something every message needs: closed
// until asked for.
const dossierOpen = ref(false)
// The model settings popover, opened from the header capsule.
const modelPanelOpen = ref(false)
// What the tavern is actually wired to. Read from the server so the capsule can
// never claim a model the backend is not using.
const modelChannel = ref(null)
// { messageId, items: [{ key, delta }] } — growth the last exchange earned.
const growthGain = ref(null)
const scrollEl = ref(null)
const inputEl = ref(null)
// Mood reported by the orbit, handed up to the shell so the desktop pet's face
// follows the same reading as the light under the stage.
const onOrbitMood = (value) => emit('mood', value)

// ── Scene ────────────────────────────────────────────────────────────────────
// Which room this character is sitting in. Per role, so switching characters
// does not drag the room along.
const scenes = TAVERN_SCENES
const sceneKey = ref(readSceneKey(activeRoleId.value))
const scenePickerOpen = ref(false)
const scene = computed(() => findScene(sceneKey.value))

watch(activeRoleId, async (roleId) => {
  sceneKey.value = readSceneKey(roleId)
  scenePickerOpen.value = false
  await openEvening()
})

function chooseScene(key) {
  sceneKey.value = key
  writeSceneKey(activeRoleId.value, key)
  scenePickerOpen.value = false
  // Changing the room mid-evening is allowed — it is the same visit.
  if (activeSession.value) {
    companionStore.openSession(userId(), activeRoleId.value, key)
  }
}

// ── The evening ──────────────────────────────────────────────────────────────
// Opening on mount and closing on request. A closed evening leaves a last line
// in the stream and one sentence in the memory list; the next visit starts clean.
const closing = ref(false)

async function openEvening() {
  if (!userId() || !activeRoleId.value) return
  await companionStore.openSession(userId(), activeRoleId.value, sceneKey.value)
}

async function closeEvening() {
  const session = activeSession.value
  if (!session || closing.value) return
  closing.value = true
  try {
    const closed = await companionStore.closeSession(userId(), session.id)
    if (!closed) {
      // A timeout may still have closed the evening server-side. Re-read before
      // claiming failure, otherwise the button stays on a session that is gone.
      const refreshed = await companionStore.fetchActiveSession(userId(), activeRoleId.value)
      if (!refreshed || refreshed.status !== 'active') {
        ElMessage.success(t('companion.session.closed'))
        return
      }
      ElMessage.error(t('common.operationFailed'))
      return
    }
    // Show the last line where it belongs: at the end of the conversation.
    if (closed.closingLine) {
      messages.value.push({
        id: `closing-${closed.id}`,
        roleId: activeRoleId.value,
        senderType: 'role',
        content: closed.closingLine,
        fallback: false,
        createdAt: closed.closedAt || new Date().toISOString()
      })
    }
    await nextTick()
    scrollToBottom()
    ElMessage.success(t('companion.session.closed'))
  } finally {
    closing.value = false
  }
}

// Capsule copy. When nothing usable is configured the capsule says so plainly
// instead of showing a model that would not answer.
const modelLabel = computed(() => {
  const channel = modelChannel.value
  if (!channel) return t('companion.modelSettings.noModel')
  if (!channel.usable) return t('companion.modelSettings.notConnected')
  return channel.modelName || channel.provider || t('companion.modelSettings.noModel')
})

const modelSubLabel = computed(() => {
  const channel = modelChannel.value
  if (!channel) return t('companion.modelSettings.clickToConfigure')
  if (!channel.usable) return t('companion.modelSettings.clickToFix')
  return channel.borrowed
    ? t('companion.modelSettings.borrowedTag')
    : t('companion.modelSettings.boundTag')
})

const refreshModelChannel = async () => {
  const roleId = activeRoleId.value
  if (!roleId || !userId()) return
  modelChannel.value = await companionStore.fetchModelChannel(userId(), roleId)
}

watch(activeRoleId, refreshModelChannel)

// The whisper layer asks for a real monologue the first time it is opened. One
// model call, never on load; a server without the endpoint just returns null and
// the orbit falls back to her own words.
const loadWhisper = async () => {
  const roleId = activeRoleId.value
  if (!roleId || !userId()) return null
  return companionStore.fetchInnerVoice(userId(), roleId)
}

const stageHost = ref(null)
const miniHoloHost = ref(null)
const orbitRef = ref(null)
const stageCollapsed = ref(false)
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
let stageTeardown = null
let miniHoloTeardown = null
let scrollFrame = 0
let expandingStage = false
let expandTimer = null

const stageStatusText = computed(() =>
  status.value?.summary || activeRole.value?.presence || activeRole.value?.persona || t('companion.defaultStatus')
)
const stageMoodText = computed(() => status.value?.statusType || activeRole.value?.tone || '—')

function setStageCollapsed(collapsed) {
  if (stageCollapsed.value === collapsed) return
  const active = document.activeElement
  const focusWillBeHidden = collapsed
    ? active?.closest('.role-stage')
    : active?.closest('.role-mini-badge')
  if (focusWillBeHidden) scrollEl.value?.focus({ preventScroll: true })
  stageCollapsed.value = collapsed
}

function onStreamScroll() {
  if (scrollFrame) return
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0
    const top = scrollEl.value?.scrollTop ?? 0
    if (top <= 5) {
      expandingStage = false
      clearTimeout(expandTimer)
      setStageCollapsed(false)
    } else if (top > 45 && !expandingStage) {
      setStageCollapsed(true)
    }
  })
}

function expandStage() {
  const el = scrollEl.value
  expandingStage = !!el && el.scrollTop > 5
  clearTimeout(expandTimer)
  setStageCollapsed(false)
  el?.scrollTo({ top: 0, behavior: reducedMotion ? 'auto' : 'smooth' })
  if (expandingStage) {
    expandTimer = setTimeout(() => {
      expandingStage = false
    }, 800)
  }
}

function toggleStageDemo() {
  setStageCollapsed(!stageCollapsed.value)
}

// The newest memory fragment, newest first as the server returns them.
const latestMemory = computed(() => memories.value[0] || null)
// Her latest reply. Used as the whisper body when no model is configured to
// write an inner monologue — her own words, not something we invented.
const lastRoleReply = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const msg = messages.value[i]
    if (msg.senderType !== 'user' && msg.content) return msg.content
  }
  return ''
})

// Below the single 899px breakpoint the dossier becomes a drawer over the
// conversation, so it needs a scrim.
const narrowQuery = window.matchMedia('(max-width: 899px)')
const narrow = ref(narrowQuery.matches)
const onBreakpoint = (event) => {
  narrow.value = event.matches
}
narrowQuery.addEventListener('change', onBreakpoint)
onUnmounted(() => narrowQuery.removeEventListener('change', onBreakpoint))

const userId = () => userStore.currentUser?.id

// The stage costs a WebGL context and a shader clock. Short windows keep the
// conversation room instead — the orbit below it stays, because that is cheap.
const SHORT_WINDOW_PX = 720
const stageVisible = ref(window.innerHeight >= SHORT_WINDOW_PX)
const onViewport = () => {
  const next = window.innerHeight >= SHORT_WINDOW_PX
  if (next === stageVisible.value) return
  stageVisible.value = next
}
window.addEventListener('resize', onViewport)

function mountStages() {
  if (stageHost.value && !stageTeardown) {
    stageTeardown = mountAbyssStage(stageHost.value, { shouldRender: () => !stageCollapsed.value })
  }
  if (miniHoloHost.value && !miniHoloTeardown) {
    miniHoloTeardown = mountMiniHologram(miniHoloHost.value, { shouldRender: () => stageCollapsed.value })
  }
}

function teardownStages() {
  stageTeardown?.()
  stageTeardown = null
  miniHoloTeardown?.()
  miniHoloTeardown = null
}

watch(stageVisible, async (visible) => {
  if (visible) {
    await nextTick()
    mountStages()
  } else {
    teardownStages()
  }
})

onMounted(async () => {
  if (!userId()) return
  await companionStore.ensureInitialized(userId())
  await nextTick()
  scrollToBottom()
  if (stageVisible.value) mountStages()
  // Which model is behind this character — shown in the header capsule.
  await refreshModelChannel()
  // Open the evening. Idempotent: if one is already running this returns it.
  await openEvening()
})

onUnmounted(() => {
  window.removeEventListener('resize', onViewport)
  cancelAnimationFrame(scrollFrame)
  clearTimeout(expandTimer)
  teardownStages()
})

watch(
  () => [messages.value.length, sending.value],
  async () => {
    await nextTick()
    scrollToBottom()
  }
)

watch(draft, async () => {
  await nextTick()
  inputEl.value.style.height = 'auto'
  inputEl.value.style.height = `${Math.min(inputEl.value.scrollHeight, 160)}px`
})

function scrollToBottom() {
  const el = scrollEl.value
  if (el) el.scrollTop = el.scrollHeight
}

// Enter sends, Shift+Enter breaks the line, and Enter that only confirms an IME
// candidate must not send.
function onEnter(event) {
  if (event.isComposing || event.shiftKey) return
  event.preventDefault()
  send()
}

async function send() {
  const content = draft.value.trim()
  const roleId = activeRoleId.value
  if (!content || !roleId || sending.value) return
  sending.value = true
  draft.value = ''
  growthGain.value = null
  const before = companionStore.growth
  // The store reports a failed send by resolving to null, never by throwing.
  // Streaming first: the reply grows in place instead of arriving in one block.
  // A server that predates the stream endpoint answers 404, and then the plain
  // request still works — the conversation must not break because of a deploy.
  const reply = (await companionStore.sendMessageStream(userId(), roleId, content, sceneKey.value))
    || (await companionStore.sendMessage(userId(), roleId, content, sceneKey.value))
  sending.value = false
  if (!reply?.roleMessage) return ElMessage.error(t('common.operationFailed'))
  await companionStore.fetchGrowth(userId(), roleId)
  const after = companionStore.growth
  if (!before || !after) return
  growthGain.value = {
    messageId: reply.roleMessage.id,
    items: ['intimacy', 'trust', 'stability', 'coGrowth']
      .map((key) => ({ key, delta: after[key] - before[key] }))
      .filter((gain) => gain.delta > 0)
  }
}
</script>

<style scoped>
.role-chat {
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #131316;
}

.role-chat-head {
  height: 56px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  background: rgba(14, 14, 17, 0.8);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(68, 71, 72, 0.3);
}

.role-chat-back {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #c4c7c8;
  cursor: pointer;
  flex-shrink: 0;
}

.role-chat-back:hover {
  background: #1f1f22;
  color: #ffffff;
}

.role-chat-avatar {
  width: 32px;
  height: 32px;
  border-radius: 9999px;
  overflow: hidden;
  flex-shrink: 0;
  background: #2a2a2d;
  border: 1px solid rgba(68, 71, 72, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c4c7c8;
}

.role-chat-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  filter: grayscale(1);
}

.role-chat-id {
  flex: 1;
  min-width: 0;
}

.role-chat-name {
  color: #ffffff;
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-chat-sub {
  margin-top: 1px;
  color: #8e9192;
  font-size: 11.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-mini-badge {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  box-sizing: border-box;
  height: 34px;
  max-width: 0;
  min-width: 0;
  margin-left: 0;
  padding: 0;
  overflow: hidden;
  flex-shrink: 0;
  border: 0 solid rgba(142, 145, 146, 0.42);
  border-radius: 9999px;
  background: rgba(42, 42, 45, 0.92);
  color: #e4e1e6;
  font-family: inherit;
  white-space: nowrap;
  opacity: 0;
  transform: translateX(-8px) scale(0.94);
  pointer-events: none;
  cursor: pointer;
  transition:
    max-width 0.46s cubic-bezier(0.4, 0, 0.2, 1),
    padding 0.46s cubic-bezier(0.4, 0, 0.2, 1),
    opacity 0.24s ease,
    transform 0.46s cubic-bezier(0.4, 0, 0.2, 1),
    border-color 0.15s,
    background 0.15s;
}

.role-mini-badge.is-visible {
  max-width: 180px;
  margin-left: 2px;
  padding: 3px 7px 3px 4px;
  border-width: 1px;
  opacity: 1;
  transform: translateX(0) scale(1);
  pointer-events: auto;
}

.role-mini-badge.is-visible:hover {
  border-color: rgba(255, 255, 255, 0.68);
  background: #353438;
}

.role-mini-badge:focus-visible {
  outline: 1px solid #ffffff;
  outline-offset: 2px;
}

.role-mini-visual {
  position: relative;
  display: inline-block;
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  border: 1px solid rgba(196, 199, 200, 0.35);
  border-radius: 9999px;
  background: #0e0e11;
}

.role-mini-canvas {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  overflow: hidden;
}

.role-mini-radar {
  position: absolute;
  inset: -3px;
  border: 1px dashed rgba(196, 199, 200, 0.34);
  border-radius: inherit;
  animation: role-mini-radar-spin 8s linear infinite;
}

.role-mini-node {
  position: absolute;
  top: -2px;
  right: -2px;
  width: 5px;
  height: 5px;
  border-radius: 9999px;
  background: #ffffff;
  box-shadow: 0 0 7px rgba(255, 255, 255, 0.7);
  animation: role-mini-node-pulse 1.8s ease-in-out infinite;
}

.role-mini-copy {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 10.5px;
  font-weight: 500;
}

.role-mini-value {
  color: #a1a1aa;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 9.5px;
  font-weight: 400;
}

.role-mini-expand {
  color: #c4c7c8;
  font-size: 14px;
}

@keyframes role-mini-radar-spin {
  to { transform: rotate(360deg); }
}

@keyframes role-mini-node-pulse {
  0%, 100% { opacity: 0.45; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1.25); }
}

.role-chat-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

/* Model capsule: the engine behind this character, plus a way into its
   settings. Two lines, like the design export, so the model name and its role
   both fit without a tooltip. */
.role-model-capsule {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  padding: 0 8px;
  border-radius: 8px;
  border: 1px solid rgba(68, 71, 72, 0.4);
  background: #1b1b1e;
  color: #ffffff;
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
  max-width: 240px;
}

.role-model-capsule:hover {
  border-color: rgba(255, 255, 255, 0.6);
  background: #1f1f22;
}

.role-model-icon {
  font-size: 16px;
  color: #ffffff;
  flex-shrink: 0;
  transition: transform 0.2s;
}

.role-model-capsule:hover .role-model-icon {
  transform: rotate(45deg);
}

.role-model-text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.2;
  min-width: 0;
}

.role-model-name {
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  font-weight: 600;
  color: #ffffff;
}

.role-model-sub {
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 9px;
  color: rgba(142, 145, 146, 0.85);
}

.role-model-chevron {
  font-size: 15px;
  color: #8e9192;
  flex-shrink: 0;
}

.role-model-capsule:hover .role-model-chevron {
  color: #ffffff;
}

@media (max-width: 1100px) {
  .role-model-text {
    display: none;
  }

  .role-model-capsule {
    max-width: none;
    padding: 0 6px;
  }
}

/* The way out to the other canvas is a labelled button, not a hidden icon:
   the two canvases look alike, so leaving one must be obvious. */
.role-chat-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border-radius: 4px;
  border: 1px solid rgba(68, 71, 72, 0.4);
  background: #1b1b1e;
  color: #c4c7c8;
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.role-chat-switch:hover {
  background: #2a2a2d;
  border-color: #444748;
  color: #ffffff;
}

.role-chat-switch .material-symbols-outlined {
  font-size: 15px;
}

@media (max-width: 899px) {
  .role-chat-switch span:last-child {
    display: none;
  }
}

.role-chat-icon {
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #8e9192;
  cursor: pointer;
}

.role-chat-icon:hover,
.role-chat-icon.is-on {
  background: #1f1f22;
  color: #ffffff;
}

.role-chat-icon .material-symbols-outlined {
  font-size: 18px;
}

.role-stage {
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  box-sizing: border-box;
  width: 100%;
  max-height: 260px;
  padding: 12px 20px;
  overflow: hidden;
  opacity: 1;
  transform: scale(1);
  transform-origin: top center;
  transition:
    max-height 0.48s cubic-bezier(0.4, 0, 0.2, 1),
    padding 0.48s cubic-bezier(0.4, 0, 0.2, 1),
    opacity 0.28s ease,
    transform 0.48s cubic-bezier(0.4, 0, 0.2, 1);
}

.role-stage.is-collapsed {
  max-height: 0;
  padding-top: 0;
  padding-bottom: 0;
  opacity: 0;
  transform: scale(0.96);
  pointer-events: none;
}

.role-stage-frame {
  position: relative;
  width: 100%;
  max-width: 760px;
  height: 236px;
  flex-shrink: 0;
  overflow: hidden;
  border: 1px solid rgba(68, 71, 72, 0.32);
  border-radius: 10px;
  background: rgba(14, 14, 17, 0.68);
}

.role-stage-glow {
  position: absolute;
  top: -48px;
  left: 24%;
  transform: translateX(-50%);
  width: 288px;
  height: 64px;
  border-radius: 9999px;
  background: rgba(255, 255, 255, 0.1);
  filter: blur(24px);
  pointer-events: none;
}

.role-stage-canvas {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  z-index: 1;
  width: 47%;
}

.role-stage-corner {
  position: absolute;
  z-index: 4;
  width: 11px;
  height: 11px;
  border-color: rgba(228, 225, 230, 0.52);
  border-style: solid;
  pointer-events: none;
}

.role-stage-corner.is-tl {
  top: 8px;
  left: 8px;
  border-width: 1px 0 0 1px;
}

.role-stage-corner.is-tr {
  top: 8px;
  right: 8px;
  border-width: 1px 1px 0 0;
}

.role-stage-corner.is-bl {
  bottom: 8px;
  left: 8px;
  border-width: 0 0 1px 1px;
}

.role-stage-corner.is-br {
  right: 8px;
  bottom: 8px;
  border-width: 0 1px 1px 0;
}

.role-stage-sync,
.role-stage-mesh {
  position: absolute;
  left: 16px;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 6px;
  color: rgba(196, 199, 200, 0.82);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  pointer-events: none;
}

.role-stage-sync {
  top: 15px;
  font-size: 9px;
}

.role-stage-sync-code {
  color: rgba(142, 145, 146, 0.66);
  font-size: 8px;
  letter-spacing: 0.06em;
}

.role-stage-live-dot {
  display: inline-block;
  width: 5px;
  height: 5px;
  flex-shrink: 0;
  border-radius: 9999px;
  background: #ffffff;
  box-shadow: 0 0 6px rgba(255, 255, 255, 0.5);
  animation: role-mini-node-pulse 1.8s ease-in-out infinite;
}

.role-stage-collapse {
  position: absolute;
  top: 10px;
  right: 12px;
  z-index: 5;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 27px;
  padding: 0 9px;
  border: 1px solid rgba(68, 71, 72, 0.48);
  border-radius: 9999px;
  background: rgba(42, 42, 45, 0.88);
  color: #e4e1e6;
  font-family: inherit;
  font-size: 10.5px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.role-stage-collapse:hover {
  border-color: rgba(255, 255, 255, 0.55);
  background: #353438;
}

.role-stage-collapse:focus-visible,
.role-stage-demo:focus-visible {
  outline: 1px solid #ffffff;
  outline-offset: 2px;
}

.role-stage-collapse .material-symbols-outlined {
  font-size: 14px;
}

.role-stage-detail {
  position: absolute;
  top: 48px;
  right: 16px;
  bottom: 14px;
  z-index: 3;
  display: flex;
  flex-direction: column;
  width: 46%;
  min-width: 0;
}

.role-stage-kicker {
  color: rgba(142, 145, 146, 0.78);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 9.5px;
  letter-spacing: 0.06em;
}

.role-stage-title {
  margin-top: 4px;
  overflow: hidden;
  color: #ffffff;
  font-size: 15px;
  font-weight: 500;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-stage-summary {
  display: -webkit-box;
  margin: 7px 0 10px;
  overflow: hidden;
  color: rgba(196, 199, 200, 0.82);
  font-size: 13px;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.role-stage-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  margin-top: auto;
}

.role-stage-metric {
  min-width: 0;
  padding: 7px 8px;
  border: 1px solid rgba(68, 71, 72, 0.3);
  border-radius: 6px;
  background: rgba(27, 27, 30, 0.72);
}

.role-stage-metric span {
  display: block;
  overflow: hidden;
  color: rgba(142, 145, 146, 0.75);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-stage-metric strong {
  display: block;
  margin-top: 4px;
  overflow: hidden;
  color: #e4e1e6;
  font-size: 12px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-stage-mesh {
  bottom: 12px;
  font-size: 8.5px;
  letter-spacing: 0.04em;
}

@media (max-width: 1100px) {
  .role-mini-copy,
  .role-stage-detail,
  .role-stage-sync-code {
    display: none;
  }

  .role-stage-canvas {
    width: 100%;
    opacity: 0.68;
  }
}

.role-chat-body {
  flex: 1;
  min-height: 0;
  display: flex;
  position: relative;
}

.role-chat-scrim {
  display: none;
}

.role-chat-main {
  position: relative;
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.role-chat-stream {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 16px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.role-chat-stream::-webkit-scrollbar {
  width: 6px;
}

.role-chat-stream::-webkit-scrollbar-thumb {
  background: #353438;
  border-radius: 9999px;
}

/* Scene anchor: the room, stated once. Hairlines either side, like the design
   export's "SCENE //" divider. */
.role-scene-anchor {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin: 4px 0 2px;
  flex-shrink: 0;
}

.role-scene-rule {
  height: 1px;
  width: 64px;
  background: rgba(68, 71, 72, 0.35);
  flex-shrink: 0;
}

.role-scene-text {
  color: rgba(142, 145, 146, 0.75);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10.5px;
  letter-spacing: 0.08em;
  text-align: center;
  line-height: 1.5;
}

/* Scene switch bar above the composer. */
.role-scene-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0 2px 6px;
}

.role-scene-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: rgba(196, 199, 200, 0.75);
  font-family: inherit;
  font-size: 11px;
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}

.role-scene-btn:hover {
  background: #1f1f22;
  color: #ffffff;
}

.role-scene-btn .material-symbols-outlined {
  font-size: 14px;
  color: #ffffff;
}

.role-scene-caret {
  font-size: 13px !important;
  color: rgba(142, 145, 146, 0.8) !important;
}

/* Closing the evening. Quiet, not a red "delete" button: ending a visit is a
   normal thing to do, not a destructive one. */
.role-close-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  padding: 2px 8px;
  border: 1px solid rgba(68, 71, 72, 0.35);
  border-radius: 4px;
  background: transparent;
  color: rgba(142, 145, 146, 0.85);
  font-family: inherit;
  font-size: 11px;
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}

.role-close-btn:hover:not(:disabled) {
  color: #ffffff;
  border-color: rgba(255, 255, 255, 0.5);
}

.role-close-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.role-close-btn .material-symbols-outlined {
  font-size: 14px;
}

.role-scene-menu {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px;
  margin-bottom: 6px;
  border-radius: 10px;
  background: rgba(14, 14, 17, 0.95);
  border: 1px solid rgba(68, 71, 72, 0.4);
}

.role-scene-option {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
  transition: background 0.15s, border-color 0.15s;
}

.role-scene-option:hover {
  background: #1f1f22;
}

.role-scene-option.is-active {
  background: #1f1f22;
  border-color: rgba(255, 255, 255, 0.35);
}

.role-scene-option > .material-symbols-outlined {
  font-size: 16px;
  color: rgba(196, 199, 200, 0.8);
  margin-top: 1px;
  flex-shrink: 0;
}

.role-scene-option.is-active > .material-symbols-outlined {
  color: #ffffff;
}

.role-scene-option-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.role-scene-option-name {
  color: #e4e1e6;
  font-size: 12.5px;
  font-weight: 500;
}

.role-scene-option.is-active .role-scene-option-name {
  color: #ffffff;
}

.role-scene-option-desc {
  color: rgba(142, 145, 146, 0.75);
  font-size: 11px;
  line-height: 1.45;
}

.role-scene-check {
  font-size: 15px !important;
  color: #ffffff !important;
  flex-shrink: 0;
  margin-top: 1px;
}

.role-scene-fold-enter-active,
.role-scene-fold-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.role-scene-fold-enter-from,
.role-scene-fold-leave-to {
  opacity: 0;
  transform: translateY(4px);
}

/* Loading a conversation: a spinner and a word, centred where the first
   message would be. Never a blank room with no explanation. */
.role-loading {
  margin: auto;
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(142, 145, 146, 0.85);
  font-size: 12.5px;
}

.role-loading-spinner {
  width: 13px;
  height: 13px;
  border: 1.5px solid rgba(142, 145, 146, 0.3);
  border-top-color: #ffffff;
  border-radius: 9999px;
  animation: role-loading-spin 0.8s linear infinite;
}

@keyframes role-loading-spin {
  to { transform: rotate(360deg); }
}

.role-chat-empty {
  margin: auto;
  color: #6f7274;
  font-size: 13px;
  text-align: center;
}

.role-msg {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 76%;
}

.role-msg.is-user {
  align-items: flex-end;
}

.role-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 0 4px;
  color: #6f7274;
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
  font-size: 11px;
}

.role-gain {
  padding: 0 6px;
  border-radius: 4px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #e4e1e6;
}

.role-typing {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #8e9192;
  font-size: 13px;
}

.role-typing .material-symbols-outlined {
  font-size: 15px;
  color: #ffffff;
  animation: role-spin 0.9s linear infinite;
}

@keyframes role-spin {
  to { transform: rotate(360deg); }
}

.role-msg.is-user {
  align-self: flex-end;
}

.role-msg.is-role {
  align-self: flex-start;
}

.role-bubble {
  padding: 9px 12px;
  border-radius: 10px;
  border: 1px solid rgba(68, 71, 72, 0.4);
}

.is-user .role-bubble {
  background: #2a2a2d;
}

.is-role .role-bubble {
  background: rgba(14, 14, 17, 0.9);
}

.role-text {
  margin: 0;
  color: #ffffff;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.role-tag {
  display: inline-block;
  margin-top: 8px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #8e9192;
  font-size: 10px;
}

.role-stage-demo {
  position: absolute;
  right: 24px;
  bottom: 82px;
  z-index: 12;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 31px;
  padding: 5px 10px;
  border: 1px solid rgba(142, 145, 146, 0.42);
  border-radius: 9999px;
  background: rgba(42, 42, 45, 0.92);
  backdrop-filter: blur(12px);
  color: #e4e1e6;
  font-family: inherit;
  font-size: 10.5px;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.32);
  transition: background 0.15s, border-color 0.15s, transform 0.15s;
}

.role-stage-demo:hover {
  border-color: rgba(255, 255, 255, 0.62);
  background: #353438;
}

.role-stage-demo:active {
  transform: scale(0.98);
}

.role-stage-demo .material-symbols-outlined {
  font-size: 14px;
}

.role-stage-demo-dot {
  width: 5px;
  height: 5px;
  border-radius: 9999px;
  background: #ffffff;
  animation: role-mini-node-pulse 1.8s ease-in-out infinite;
}

.role-composer {
  flex-shrink: 0;
  padding: 0 24px 14px;
}

.role-composer-box {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 8px 8px 8px 12px;
  border-radius: 10px;
  background: rgba(14, 14, 17, 0.9);
  border: 1px solid rgba(68, 71, 72, 0.4);
}

.role-composer-box:focus-within {
  border-color: #8e9192;
}

.role-input-area {
  flex: 1;
  min-width: 0;
  max-height: 160px;
  padding: 4px 0;
  border: none;
  background: transparent;
  color: #ffffff;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.55;
  resize: none;
  outline: none;
}

.role-input-area::placeholder {
  color: #6f7274;
}

.role-send {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 14px;
  border: none;
  border-radius: 8px;
  background: #ffffff;
  color: #131316;
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.15s;
}

.role-send:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.role-send .material-symbols-outlined {
  font-size: 16px;
}

@media (max-width: 899px) {
  .role-stage {
    padding-left: 14px;
    padding-right: 14px;
  }

  .role-chat-scrim {
    display: block;
    position: absolute;
    inset: 0;
    z-index: 19;
    background: rgba(6, 6, 8, 0.6);
  }

  .role-chat-body :deep(.dossier) {
    position: absolute;
    right: 0;
    top: 56px;
    bottom: 0;
    width: 86%;
    max-width: 320px;
    z-index: 20;
    box-shadow: -8px 0 24px rgba(0, 0, 0, 0.5);
  }

  .role-msg {
    max-width: 88%;
  }

  .role-chat-stream,
  .role-composer {
    padding-left: 14px;
    padding-right: 14px;
  }

  .role-stage-demo {
    right: 14px;
    bottom: 78px;
    max-width: calc(100% - 28px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .role-stage,
  .role-mini-badge {
    transition: none;
  }

  .role-mini-radar,
  .role-mini-node,
  .role-stage-live-dot,
  .role-stage-demo-dot {
    animation: none;
  }
}
</style>

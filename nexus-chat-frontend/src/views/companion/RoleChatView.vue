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
        <div class="role-chat-sub">{{ headerSubText }}</div>
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
          <!-- Lit only while an evening is actually open. -->
          <span v-if="activeSession" class="role-mini-node"></span>
        </span>
        <span class="role-mini-copy">
          <span>{{ sceneName }}</span>
          <span class="role-mini-value">{{ moodLabel || eveningShort }}</span>
        </span>
        <span class="material-symbols-outlined role-mini-expand">expand_more</span>
      </button>

      <div class="role-chat-actions">
        <!-- The model capsule from the design export: which engine is behind
             this character, one click from its settings. -->
        <button
          class="role-model-capsule"
          type="button"
          :title="$t('companion.modelSettings.trigger')" :aria-label="$t('companion.modelSettings.trigger')" :aria-expanded="modelPanelOpen"
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
          :aria-label="$t('companion.dossier')"
          :aria-expanded="dossierOpen"
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
        <!-- The room: the open scene, the character on stage, and the state she
             last reported. Drawn by tavernStage.js; nothing in it is preset. -->
        <div ref="stageHost" class="role-stage-canvas" aria-hidden="true"></div>
        <span class="role-stage-corner is-tl" aria-hidden="true"></span>
        <span class="role-stage-corner is-tr" aria-hidden="true"></span>
        <span class="role-stage-corner is-bl" aria-hidden="true"></span>
        <span class="role-stage-corner is-br" aria-hidden="true"></span>

        <div class="role-stage-sync">
          <span class="role-stage-live-dot" :class="{ 'is-on': !!activeSession }"></span>
          <span>{{ sceneName }}</span>
          <span class="role-stage-sync-code">{{ eveningText }}</span>
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
          <div class="role-stage-kicker">{{ $t('companion.stage.now') }}</div>
          <div class="role-stage-title">{{ activeRole?.name || $t('companion.defaultName') }}</div>
          <p class="role-stage-summary">{{ stageStatusText }}</p>
          <!-- Which memories the server put in front of the model for the last
               reply. Opens the dossier, where they can be read and removed. -->
          <button
            v-if="memoriesUsed.length"
            class="role-stage-memory"
            type="button"
            :title="memoriesUsedTitle"
            @click="dossierOpen = true"
          >
            <span class="material-symbols-outlined">bookmark</span>
            <span>{{ $t('companion.stage.memoriesUsed', { n: memoriesUsed.length }) }}</span>
          </button>
          <div class="role-stage-metrics">
            <div class="role-stage-metric">
              <span>{{ $t('companion.intimacy') }}</span>
              <strong>{{ growth?.intimacy != null ? `${growth.intimacy}%` : '—' }}</strong>
            </div>
            <div class="role-stage-metric">
              <span>{{ $t('companion.stage.mood') }}</span>
              <strong>{{ moodLabel || '—' }}</strong>
            </div>
            <div class="role-stage-metric">
              <span>{{ $t('companion.stage.evening') }}</span>
              <strong>{{ eveningShort }}</strong>
            </div>
          </div>
        </div>

        <div v-if="actorStatus === 'loading' || actorStatus === 'error'" class="role-stage-mesh" role="status">
          <span>{{ $t(actorStatus === 'loading' ? 'companion.stage.loadingCharacter' : 'companion.stage.characterUnavailable') }}</span>
        </div>
        <div v-else-if="sending" class="role-stage-mesh" aria-hidden="true">
          <span class="role-stage-live-dot is-on"></span>
          <span>{{ $t('companion.typing') }}</span>
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
        <div
          ref="scrollEl"
          class="role-chat-stream"
          tabindex="-1"
          @scroll.passive="onStreamScroll"
          @wheel.passive="noteUserScroll"
          @touchmove.passive="noteUserScroll"
          @keydown="noteUserScroll"
          @pointerdown="onStreamPointerDown"
        >
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
          <p v-else-if="!streamItems.length && !openingPreview" class="role-chat-empty">{{ $t('companion.chatEmpty') }}</p>

          <template v-for="item in streamItems" :key="item.key">
            <!-- Where one evening starts: when, and in which room. -->
            <div v-if="item.type === 'divider'" class="role-session-divider">
              <span class="role-session-rule"></span>
              <span class="role-session-label">{{ item.label }}</span>
              <span class="role-session-rule"></span>
            </div>
            <div v-else-if="item.type === 'end'" class="role-session-end">{{ $t('companion.session.closed') }}</div>
            <div
              v-else
              class="role-msg"
              :class="[item.msg.senderType === 'user' ? 'is-user' : 'is-role', { 'is-failed': item.msg.failed }]"
            >
              <div class="role-bubble">
                <!-- Kept on one line: the paragraph preserves whitespace. -->
                <p class="role-text"><span v-for="(seg, i) in segmentsOf(item.msg.content)" :key="i" :class="seg.narration ? 'role-narr' : 'role-speech'">{{ seg.text }}</span></p>
                <span v-if="item.msg.senderType !== 'user' && item.msg.fallback" class="role-tag">
                  {{ $t('companion.session.cannedTag') }}
                </span>
              </div>
              <div class="role-meta">
                <span>{{ settings.format(item.msg.createdAt, 'HH:mm') }}</span>
                <!-- The mood she reported with this reply. Absent when she reported none. -->
                <span v-if="item.msg.state?.mood" class="role-mood">{{ $t(`companion.mood.${item.msg.state.mood}`) }}</span>
                <span v-if="item.msg.stopped">{{ $t('companion.session.stopped') }}</span>
                <span v-else-if="item.msg.partial">{{ $t('companion.session.partialReply') }}</span>
                <template v-if="item.msg.modelError">
                  <span role="alert" class="role-model-error">{{ modelErrorText(item.msg.modelError) }}</span>
                  <span>{{ describeModelError(item.msg.modelError).detail }}</span>
                  <button
                    v-if="describeModelError(item.msg.modelError).settings"
                    class="role-retry"
                    type="button"
                    @click="modelPanelOpen = true"
                  >
                    <span class="material-symbols-outlined">tune</span>
                    <span>{{ $t('modelError.openSettings') }}</span>
                  </button>
                </template>
                <!-- What this exchange changed, read back from the server after the reply. -->
                <template v-if="growthGain?.messageId === item.msg.id">
                  <span v-for="gain in growthGain.items" :key="gain.key" class="role-gain">
                    {{ $t(`companion.${gain.key}`) }} +{{ gain.delta }}
                  </span>
                </template>
                <button
                  v-if="item.retry"
                  class="role-retry"
                  type="button"
                  :disabled="sending || waitSeconds(item.msg.retryAt) > 0"
                  @click="send(item.msg)"
                >
                  <span class="material-symbols-outlined">refresh</span>
                  <span v-if="waitSeconds(item.msg.retryAt) > 0">{{ $t('companion.session.retryAfter', { seconds: waitSeconds(item.msg.retryAt) }) }}</span>
                  <span v-else>{{ $t(item.msg.modelError ? 'common.retry' : item.msg.failed ? 'companion.session.sendFailed' : 'companion.session.noReply') }}</span>
                </button>
              </div>
            </div>
          </template>

          <!-- Her opening line for an evening that has not started yet. It is
               filed as a real message the moment the first line is sent. -->
          <div v-if="openingPreview" class="role-msg is-role is-preview">
            <div class="role-bubble">
              <p class="role-text"><span v-for="(seg, i) in segmentsOf(openingPreview)" :key="i" :class="seg.narration ? 'role-narr' : 'role-speech'">{{ seg.text }}</span></p>
              <span class="role-tag">{{ $t('companion.session.openingTag') }}</span>
            </div>
          </div>

          <div v-if="awaitingFirstWord" class="role-msg is-role">
            <div class="role-bubble role-typing">
              <span class="material-symbols-outlined">progress_activity</span>
              <span>{{ $t('companion.typing') }}</span>
            </div>
          </div>
        </div>

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
            <!-- While she is writing, the same button stops her. What she has
                 said so far stays. -->
            <button v-if="sending" class="role-send is-stop" type="button" @click="stop">
              <span>{{ $t('companion.session.stop') }}</span>
              <span class="material-symbols-outlined">stop</span>
            </button>
            <button
              v-else
              class="role-send"
              type="button"
              :disabled="!draft.trim()"
              @click="send()"
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
import { describeModelError } from '@/stores/agentProviders'
import { useUserStore } from '@/stores/user'
import { useSettingsStore } from '@/stores/settings'
import { SERVER_BASE_URL } from '@/services/runtimeConfig'
import { assetRequest } from '@/utils/assetRequest'
import RoleDossier from '@/components/companion/RoleDossier.vue'
import MindOrbit from '@/components/companion/MindOrbit.vue'
import ModelSettingsPanel from '@/components/companion/ModelSettingsPanel.vue'
import TavernBusy from '@/components/companion/TavernBusy.vue'
import { mountTavernStage, mountTavernMini } from '@/components/companion/tavernStage'
import { stageReplyMeta, interruptedStageReply } from '@/components/companion/tavernEvents'
import { TAVERN_SCENES, findScene, readSceneKey, writeSceneKey } from '@/components/companion/tavernScenes'
// Narration — （…）, (…) or *…* — is set apart from what is said aloud. The role
// list previews a line through the same cut.
import { segmentsOf } from '@/components/companion/roleText'

const emit = defineEmits(['back', 'goAi', 'mood'])

const { t } = useI18n()
const userStore = useUserStore()
const companionStore = useCompanionStore()
const settings = useSettingsStore()
const {
  activeRole, activeRoleId, messages, growth, status, memories, activeSession, sessionHistory,
  isLoadingRoleData, isSending: sending, speaking, liveState, lastContext
} = storeToRefs(companionStore)

const draft = ref('')
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
// One source of truth: an open evening knows its own room. The stored choice is
// only what the next evening will open in.
const localSceneKey = ref(readSceneKey(activeRoleId.value))
const sceneKey = computed(() => {
  const open = activeSession.value?.sceneKey
  return open && TAVERN_SCENES.some((s) => s.key === open) ? open : localSceneKey.value
})
const scenePickerOpen = ref(false)
const scene = computed(() => findScene(sceneKey.value))
const sceneName = computed(() => t(`companion.sceneNames.${scene.value.key}`))

watch(activeRoleId, (roleId) => {
  localSceneKey.value = readSceneKey(roleId)
  scenePickerOpen.value = false
})

async function chooseScene(key) {
  localSceneKey.value = key
  writeSceneKey(activeRoleId.value, key)
  scenePickerOpen.value = false
  // Changing the room mid-evening is allowed — it is the same visit.
  if (activeSession.value) {
    await companionStore.openSession(userId(), activeRoleId.value, key)
  }
}

// ── The evening ──────────────────────────────────────────────────────────────
// An evening opens on the server when the first line is sent — never by looking
// at a character — and closes on request. Closing files her last line as the
// evening's final message and one sentence in the memory list.
const closing = ref(false)

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
    // Her last line is a stored message now; read the conversation back rather
    // than drawing a local copy that would vanish on reload.
    const roleId = activeRoleId.value
    await Promise.all([
      companionStore.fetchConversation(userId(), roleId),
      companionStore.fetchMemories(userId(), roleId),
      companionStore.fetchSessionHistory(userId(), roleId)
    ])
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

let channelRequest = 0
const refreshModelChannel = async () => {
  const request = ++channelRequest
  const roleId = activeRoleId.value, accountId = userId()
  modelChannel.value = null
  if (!roleId || !accountId) return
  const description = await companionStore.fetchModelChannel(accountId, roleId)
  if (request === channelRequest && roleId === activeRoleId.value && accountId === userId()) modelChannel.value = description
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
const actorStatus = ref('none')
const motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
let reducedMotion = motionQuery.matches
const onMotionPreference = (event) => {
  reducedMotion = event.matches
  stage?.setReducedMotion(reducedMotion)
  mini?.setReducedMotion(reducedMotion)
}
motionQuery.addEventListener('change', onMotionPreference)
let stage = null
let mini = null
let scrollFrame = 0

// ── What the stage shows ─────────────────────────────────────────────────────
// Mood and motion come from the model: the state streaming in right now, or
// else the last one stored. No report, no pose — the figure stays neutral.
const stageState = computed(() => {
  if (liveState.value) return liveState.value
  const stored = status.value
  return stored?.mood || stored?.motion ? { mood: stored.mood, motion: stored.motion } : null
})
const stageMeta = computed(() => stageReplyMeta(activeRoleId.value, messages.value.at(-1), liveState.value, activeSession.value?.id))
const moodLabel = computed(() => (stageState.value?.mood ? t(`companion.mood.${stageState.value.mood}`) : ''))

// What she is doing: her own report first, then the presence line the server
// holds for her.
const stageStatusText = computed(() =>
  liveState.value?.doing || status.value?.summary || activeRole.value?.presence || t('companion.defaultStatus')
)
const headerSubText = computed(() =>
  status.value?.summary || activeRole.value?.presence || activeRole.value?.tone || t('companion.defaultStatus')
)

// How long tonight has been going, from the session's own start time.
const now = ref(Date.now())
const clockTimer = setInterval(() => {
  now.value = Date.now()
}, 30000)
const eveningMinutes = computed(() => {
  const startedAt = activeSession.value?.startedAt
  if (!startedAt) return 0
  const ms = now.value - new Date(startedAt).getTime()
  return Number.isFinite(ms) ? Math.max(0, Math.floor(ms / 60000)) : 0
})
const eveningLines = computed(() => {
  const id = activeSession.value?.id
  return id ? messages.value.filter((m) => m.sessionId === id).length : 0
})
const eveningText = computed(() =>
  activeSession.value
    ? t('companion.stage.eveningOpen', { min: eveningMinutes.value, n: eveningLines.value })
    : t('companion.stage.eveningNotOpen')
)
const eveningShort = computed(() =>
  activeSession.value ? t('companion.stage.eveningShort', { min: eveningMinutes.value }) : '—'
)

// Memories the server reported using for the last reply, resolved to the rows
// the dossier shows. Ids it no longer finds are simply not listed.
const memoriesUsed = computed(() =>
  (lastContext.value?.memoryIds || [])
    .map((id) => memories.value.find((m) => m.id === id))
    .filter(Boolean)
)
const memoriesUsedTitle = computed(() => memoriesUsed.value.map((m) => m.content).join('\n'))

function setStageCollapsed(collapsed) {
  if (stageCollapsed.value === collapsed) return
  const active = document.activeElement
  const focusWillBeHidden = collapsed
    ? active?.closest('.role-stage')
    : active?.closest('.role-mini-badge')
  if (focusWillBeHidden) scrollEl.value?.focus({ preventScroll: true })
  const pinned = isNearBottom()
  if (collapsed) stage?.pause()
  stageCollapsed.value = collapsed
  // The stream changes height with the stage; stay on the newest line if the
  // reader was there.
  if (pinned) setTimeout(scrollToBottom, reducedMotion ? 0 : 500)
}

// Only the reader's own scrolling folds the stage away. Following the
// conversation to its newest line is not a request to hide the room — without
// this distinction the stage collapsed on load for any character with history
// and was, in practice, never seen.
let userScrollUntil = 0
let draggingScrollbar = false
function noteUserScroll() {
  userScrollUntil = performance.now() + 400
}
function onStreamPointerDown(event) {
  // A press on the stream element itself (not on a message) is the scrollbar.
  if (event.target !== scrollEl.value) return
  draggingScrollbar = true
  window.addEventListener('pointerup', () => {
    draggingScrollbar = false
    noteUserScroll()
  }, { once: true })
}

function onStreamScroll() {
  if (scrollFrame) return
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0
    const byReader = draggingScrollbar || performance.now() < userScrollUntil
    if (!byReader) return
    const top = scrollEl.value?.scrollTop ?? 0
    if (top <= 5) setStageCollapsed(false)
    else if (top > 45) setStageCollapsed(true)
  })
}

function expandStage() {
  setStageCollapsed(false)
}

function isNearBottom() {
  const el = scrollEl.value
  return !el || el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

// ── The stream ───────────────────────────────────────────────────────────────
const sessionsById = computed(() => {
  const map = new Map()
  for (const session of sessionHistory.value) map.set(session.id, session)
  if (activeSession.value) map.set(activeSession.value.id, activeSession.value)
  return map
})

// Messages, with a divider where each evening starts and a mark where a closed
// one ends. Derived from the session each message was filed under.
const streamItems = computed(() => {
  const items = []
  let current = null
  let ended = true
  const endCurrent = () => {
    if (!ended && current != null && sessionsById.value.get(current)?.status === 'closed') {
      items.push({ type: 'end', key: `end-${current}` })
    }
    ended = true
  }
  for (const msg of messages.value) {
    // The placeholder a reply streams into: nothing to show until it has words.
    if (msg.live && !msg.content) continue
    const sessionId = msg.sessionId ?? null
    if (sessionId !== current) {
      if (sessionId != null) {
        endCurrent()
        const session = sessionsById.value.get(sessionId)
        const known = session?.sceneKey && TAVERN_SCENES.some((s) => s.key === session.sceneKey)
        const label = [
          settings.format(session?.startedAt || msg.createdAt, 'MM-DD HH:mm'),
          known ? t(`companion.sceneNames.${session.sceneKey}`) : ''
        ].filter(Boolean).join(' · ')
        items.push({ type: 'divider', key: `start-${sessionId}`, label })
        current = sessionId
        ended = false
      } else if (!msg.pending && !msg.stopped) {
        // A stored message filed under no evening: from before sessions existed.
        endCurrent()
        current = null
      } else if (sessionsById.value.get(current)?.status === 'closed') {
        // A line just sent after a closed evening: that evening ends above it.
        endCurrent()
      }
    }
    items.push({ type: 'msg', key: `m-${msg.id}`, msg, retry: false })
  }
  endCurrent()

  // A line of yours that got no answer can be sent again: one that failed
  // outright, or the last line in the stream once nothing is in flight.
  let last = null
  for (let i = items.length - 1; i >= 0; i--) {
    if (items[i].type === 'msg') {
      last = items[i]
      break
    }
  }
  for (const item of items) {
    if (item.type !== 'msg' || item.msg.senderType !== 'user' || !item.msg.clientMsgId) continue
    item.retry = !!item.msg.failed || item.msg.partialId != null || (item === last && !sending.value)
  }
  return items
})

// Her opening line, shown before the evening exists.
const openingPreview = computed(() => {
  if (isLoadingRoleData.value || activeSession.value || sending.value) return ''
  return activeRole.value?.greeting || ''
})

// Sent, and not a word back yet.
const awaitingFirstWord = computed(() => {
  if (!sending.value) return false
  const last = messages.value[messages.value.length - 1]
  return !(last?.live && last.content)
})

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

let stagesAlive = true
let stagesGeneration = 0
function mountStages() {
  if (!stagesAlive || !stageVisible.value) return
  try {
  if (stageHost.value && !stage) {
    stage = mountTavernStage(stageHost.value, {
      shouldRender: () => !stageCollapsed.value,
      reducedMotion,
      onActorStatus: (value) => { actorStatus.value = value },
      requestAsset: (url) => assetRequest(url, SERVER_BASE_URL, userStore.token)
    })
    stage.setCharacter(activeRole.value)
    stage.setScene(sceneKey.value)
    // Reopening the stage restores persistent mood, never an old one-shot cue.
    stage.setState(stageState.value, { ...stageMeta.value, replay: true })
    stage.setSpeaking(speaking.value)
    stage.setWaiting(awaitingFirstWord.value)
    // Lets the stage be checked by reading it instead of looking at it.
    if (import.meta.env.DEV) window.__tavernStage = stage
  }
  if (miniHoloHost.value && !mini) {
    mini = mountTavernMini(miniHoloHost.value, { shouldRender: () => stageCollapsed.value, reducedMotion })
    mini.setCharacter(activeRole.value)
    if (import.meta.env.DEV) window.__tavernMini = mini
  }
  } catch (error) {
    teardownStages()
    throw error
  }
}

function teardownStages() {
  stagesGeneration++
  const oldStage = stage, oldMini = mini
  stage = null
  mini = null
  try { oldStage?.destroy() } catch (error) { console.warn('Stage cleanup failed', error) }
  try { oldMini?.destroy() } catch (error) { console.warn('Mini cleanup failed', error) }
  if (import.meta.env.DEV) {
    delete window.__tavernStage
    delete window.__tavernMini
  }
}

watch(() => [activeRole.value?.id, activeRole.value?.name, activeRole.value?.modelUrl, activeRole.value?.modelType, activeRole.value?.kind], () => {
  stage?.setCharacter(activeRole.value)
  mini?.setCharacter(activeRole.value)
})
watch(sceneKey, (key) => stage?.setScene(key))
watch(stageState, (state) => {
  if (state) stage?.setState(state, stageMeta.value)
  else stage?.clearState()
})
watch(speaking, (value) => stage?.setSpeaking(value))
// What is really happening in the conversation: she attends while the user
// writes, and thinks while the model has not yet answered.
let listeningTimer = 0
const settleListening = () => {
  clearTimeout(listeningTimer)
  const writing = !!draft.value.trim() && !sending.value
  stage?.setListening(writing)
  // Text left sitting in the box is not someone writing to her.
  if (writing) listeningTimer = setTimeout(() => stage?.setListening(false), 4000)
}
watch(draft, settleListening)
watch(() => awaitingFirstWord.value, (value) => stage?.setWaiting(value))
watch(sending, settleListening)
watch(activeRoleId, () => stage?.hush('role-change'), { flush: 'sync' })
watch(() => activeSession.value?.id, () => stage?.hush('session-change'), { flush: 'sync' })
watch(() => interruptedStageReply(messages.value.at(-1)), (interrupted) => {
  if (interrupted) stage?.hush('reply-interrupted')
}, { flush: 'sync' })
watch(() => messages.value.length, (length) => { if (!length) stage?.hush() })

watch(() => {
  const reply = messages.value.at(-1)
  return { id: reply?.live ? reply.id : null, text: reply?.live ? reply.content : '', roleId: activeRoleId.value }
}, (reply, previous) => {
  if (!reply.id || reply.roleId !== previous?.roleId) return
  if (reply.id !== previous?.id) stage?.hush()
  const offset = reply.id === previous?.id ? previous.text.length : 0
  if (reply.text.length > offset) stage?.speak(reply.text.slice(offset), { turnId: reply.id, roleId: reply.roleId })
}, { flush: 'sync' })

watch(stageVisible, async (visible) => {
  const generation = ++stagesGeneration
  if (visible) {
    await nextTick()
    if (!stagesAlive || generation !== stagesGeneration) return
    mountStages()
  } else {
    teardownStages()
  }
})

onMounted(async () => {
  if (!userId()) return
  const generation = stagesGeneration
  await companionStore.ensureInitialized(userId())
  if (!stagesAlive) return
  await nextTick()
  if (!stagesAlive || generation !== stagesGeneration) return
  scrollToBottom()
  if (stageVisible.value) mountStages()
  // Which model is behind this character — shown in the header capsule.
  await refreshModelChannel()
})

onUnmounted(() => {
  stagesAlive = false
  window.removeEventListener('resize', onViewport)
  motionQuery.removeEventListener('change', onMotionPreference)
  cancelAnimationFrame(scrollFrame)
  clearTimeout(listeningTimer)
  clearInterval(clockTimer)
  clearInterval(waitTimer)
  teardownStages()
})

// Follow the conversation: a new line, a reply starting, and a reply growing.
watch(
  () => [messages.value.length, sending.value, messages.value[messages.value.length - 1]?.content?.length],
  async () => {
    await nextTick()
    scrollToBottom()
  }
)

watch(draft, async () => {
  await nextTick()
  if (!inputEl.value) return
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

function modelErrorText(error) {
  const described = describeModelError(error)
  return described.key ? t(described.key) : described.fallback
}

// A rate-limited line can be sent again once the wait the backend asked for is
// over. The clock only re-enables the button; it never sends anything itself.
const waitNow = ref(Date.now())
let waitTimer = 0
const waitSeconds = (retryAt) => Math.max(0, Math.ceil((retryAt - waitNow.value) / 1000))
watch(() => Math.max(0, ...messages.value.map(m => m.retryAt || 0)), (until) => {
  clearInterval(waitTimer)
  waitNow.value = Date.now()
  if (until <= waitNow.value) return
  waitTimer = setInterval(() => {
    waitNow.value = Date.now()
    if (waitNow.value >= until) clearInterval(waitTimer)
  }, 1000)
}, { immediate: true })

// Send a new line, or send one again. A retry goes out under the id the line
// was first sent with, so the server answers it without filing it twice.
async function send(retryOf = null) {
  const content = retryOf ? retryOf.content : draft.value.trim()
  const roleId = activeRoleId.value
  if (!content || !roleId || sending.value) return
  if (!retryOf) draft.value = ''
  growthGain.value = null
  const before = companionStore.growth
  const room = sceneKey.value

  let reply = await companionStore.sendMessageStream(userId(), roleId, content, room, retryOf?.clientMsgId || null)
  // Only a server without the stream endpoint is sent to again — it did nothing
  // with the first request. Any other failure may already be filed.
  if (reply?.unsupported && !reply.modelError) {
    reply = await companionStore.sendMessage(userId(), roleId, content, room, reply.clientMsgId)
  }
  if (activeRoleId.value !== roleId) return
  if (reply?.stopped) {
    stage?.hush()
    return
  }
  if (reply?.modelError) {
    stage?.hush()
    return // The inline diagnostic stays with this turn, including any partial reply.
  }
  if (!reply?.roleMessage) {
    stage?.hush()
    return ElMessage.error(t('companion.session.sendFailedToast'))
  }

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

function stop() {
  stage?.hush()
  companionStore.stopStreaming()
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
  backdrop-filter: blur(calc(12px * var(--glass-blur-scale, 1)));
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
}

.role-chat-id {
  flex: 1;
  min-width: 0;
}

.role-chat-name {
  color: #ffffff;
  font-size: 12px;
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
  max-height: 324px;
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
  max-width: 840px;
  height: clamp(236px, 30vh, 300px);
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
  width: 58%;
  /* The room fades out toward the text beside it instead of ending on an edge. */
  -webkit-mask-image: linear-gradient(to right, #000 78%, transparent);
  mask-image: linear-gradient(to right, #000 78%, transparent);
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
  /* Dim and still until there is something live to mark. */
  opacity: 0.32;
}

.role-stage-live-dot.is-on {
  opacity: 1;
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

.role-stage-collapse:focus-visible {
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
  width: 40%;
  min-width: 0;
}

.role-stage-memory {
  display: inline-flex;
  align-items: center;
  align-self: flex-start;
  gap: 4px;
  margin: -4px 0 8px;
  padding: 2px 7px 2px 5px;
  border: 1px solid rgba(68, 71, 72, 0.4);
  border-radius: 9999px;
  background: transparent;
  color: rgba(196, 199, 200, 0.82);
  font-family: inherit;
  font-size: 10.5px;
  line-height: 1.5;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;
}

.role-stage-memory:hover {
  border-color: rgba(255, 255, 255, 0.5);
  color: #ffffff;
}

.role-stage-memory:focus-visible {
  outline: 1px solid #ffffff;
  outline-offset: 2px;
}

.role-stage-memory .material-symbols-outlined {
  font-size: 12px;
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
  font-size: 14px;
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
  font-size: 12px;
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
    -webkit-mask-image: none;
    mask-image: none;
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
  font-size: 12px;
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
  font-size: 12px;
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
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

/* What she does, as opposed to what she says. */
.role-narr {
  color: #8e9192;
  font-style: italic;
}

.role-msg.is-preview .role-bubble {
  border-style: dashed;
}

.role-model-error {
  color: #ffb4ab;
  overflow-wrap: anywhere;
}

.role-msg.is-failed .role-bubble {
  border-color: rgba(142, 145, 146, 0.7);
  border-style: dashed;
  opacity: 0.72;
}

.role-mood {
  padding: 0 6px;
  border-radius: 4px;
  border: 1px solid rgba(68, 71, 72, 0.3);
  color: #c4c7c8;
}

.role-retry {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0 6px;
  border: 1px solid rgba(142, 145, 146, 0.5);
  border-radius: 4px;
  background: transparent;
  color: #e4e1e6;
  font-family: inherit;
  font-size: 11px;
  line-height: 1.6;
  cursor: pointer;
}

.role-retry:hover:not(:disabled) {
  border-color: #ffffff;
  color: #ffffff;
}

.role-retry:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.role-retry:focus-visible {
  outline: 1px solid #ffffff;
  outline-offset: 2px;
}

.role-retry .material-symbols-outlined {
  font-size: 12px;
}

.role-session-divider {
  display: flex;
  align-items: center;
  align-self: stretch;
  gap: 12px;
  margin: 10px 0 2px;
}

.role-session-rule {
  flex: 1;
  height: 1px;
  background: rgba(68, 71, 72, 0.35);
}

.role-session-label {
  flex-shrink: 0;
  color: #6f7274;
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
  font-size: 10.5px;
  letter-spacing: 0.04em;
}

.role-session-end {
  align-self: center;
  margin: 2px 0 6px;
  color: #6f7274;
  font-size: 11px;
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
  font-size: 12px;
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
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.15s;
}

.role-send:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.role-send.is-stop {
  background: transparent;
  border: 1px solid #8e9192;
  color: #ffffff;
}

.role-send.is-stop:hover {
  border-color: #ffffff;
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

}

@media (prefers-reduced-motion: reduce) {
  .role-stage,
  .role-mini-badge {
    transition: none;
  }

  .role-mini-radar,
  .role-mini-node,
  .role-stage-live-dot {
    animation: none;
  }
}
</style>

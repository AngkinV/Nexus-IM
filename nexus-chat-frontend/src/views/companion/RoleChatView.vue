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

      <div class="role-chat-actions">
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
        <button
          class="role-chat-icon"
          type="button"
          :title="$t('settings.roleAndModel')"
          @click="$router.push('/settings')"
        >
          <span class="material-symbols-outlined">tune</span>
        </button>
      </div>
    </header>

    <!-- The character's holographic stage. Same renderer as the AI canvas, so
         the two sections read as one system; the stage is what the mind orbit
         sits underneath. -->
    <div v-if="stageVisible" class="role-stage">
      <div class="role-stage-frame">
        <div class="role-stage-glow" aria-hidden="true"></div>
        <div ref="stageHost" class="role-stage-canvas"></div>
      </div>
    </div>

    <MindOrbit
      ref="orbitRef"
      :text="draft"
      :sending="sending"
      :intimacy="growth?.intimacy ?? null"
      :trust="growth?.trust ?? null"
      :status-type="status?.statusType || ''"
      :status-summary="status?.summary || ''"
      :memory="latestMemory"
      :last-role-reply="lastRoleReply"
    />

    <div class="role-chat-body">
      <div class="role-chat-main">
        <div ref="scrollEl" class="role-chat-stream">
          <p v-if="!messages.length" class="role-chat-empty">{{ $t('companion.chatEmpty') }}</p>
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

        <div class="role-composer">
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
import { mountAbyssStage } from '@/components/agent/abyssStage'

const emit = defineEmits(['back', 'goAi'])

const { t } = useI18n()
const userStore = useUserStore()
const companionStore = useCompanionStore()
const { activeRole, activeRoleId, messages, growth, status, memories } = storeToRefs(companionStore)

const draft = ref('')
const sending = ref(false)
// The dossier is reference material, not something every message needs: closed
// until asked for.
const dossierOpen = ref(false)
// { messageId, items: [{ key, delta }] } — growth the last exchange earned.
const growthGain = ref(null)
const scrollEl = ref(null)
const inputEl = ref(null)
const stageHost = ref(null)
const orbitRef = ref(null)
let stageTeardown = null

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

watch(stageVisible, async (visible) => {
  if (visible) {
    await nextTick()
    stageTeardown = mountAbyssStage(stageHost.value)
  } else {
    stageTeardown?.()
    stageTeardown = null
  }
})

onMounted(async () => {
  if (!userId()) return
  await companionStore.ensureInitialized(userId())
  await nextTick()
  scrollToBottom()
  if (stageVisible.value && stageHost.value) stageTeardown = mountAbyssStage(stageHost.value)
})

onUnmounted(() => {
  window.removeEventListener('resize', onViewport)
  stageTeardown?.()
  stageTeardown = null
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
  const reply = await companionStore.sendMessage(userId(), roleId, content)
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
  font-size: 13.5px;
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

.role-chat-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
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

/* Stage: the same silhouette as the AI canvas, kept free of chrome so the
   character reads as standing in the room rather than in a box. */
.role-stage {
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  width: 100%;
  padding: 12px 24px 0;
}

.role-stage-frame {
  position: relative;
  width: 100%;
  max-width: 576px;
  height: 180px;
  overflow: hidden;
}

.role-stage-glow {
  position: absolute;
  top: -48px;
  left: 50%;
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
  inset: 0;
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
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.role-chat-stream::-webkit-scrollbar {
  width: 6px;
}

.role-chat-stream::-webkit-scrollbar-thumb {
  background: #353438;
  border-radius: 9999px;
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
  padding: 12px 14px;
  border-radius: 12px;
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
  font-size: 14px;
  line-height: 1.65;
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

.role-composer {
  flex-shrink: 0;
  padding: 0 24px 18px;
}

.role-composer-box {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 10px 10px 10px 14px;
  border-radius: 12px;
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
  font-size: 14px;
  line-height: 1.6;
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
</style>

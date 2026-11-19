<template>
  <!-- Mind orbit: a bare line under the character stage. No card, no border, no
       panel background — the glow lives on the dot, the words and the wave bars,
       never on a rectangle. Everything shown is real state: the CPS comes from
       actual keystrokes, the figures come from growth / status / memories. -->
  <div
    class="mind-orbit"
    :class="`is-${themeKey}`"
    :style="themeVars"
  >
    <div class="orbit-rule" aria-hidden="true"></div>

    <div class="orbit-row">
      <button
        class="orbit-main"
        type="button"
        :aria-expanded="whisperOpen"
        :title="$t('companion.orbit.toggleWhisper')"
        @click="whisperOpen = !whisperOpen"
      >
        <span class="orbit-dot">
          <span class="orbit-ping"></span>
          <span class="orbit-core"></span>
        </span>
        <span class="orbit-prefix">{{ prefixLabel }}</span>
        <span class="orbit-thought">{{ thoughtLabel }}</span>
        <span class="orbit-sep" aria-hidden="true">── ✧ ──</span>
        <span class="orbit-metric">{{ metricLabel }}</span>
        <span class="orbit-sep" aria-hidden="true">── ✧ ──</span>
        <span class="orbit-state">{{ stateLabel }}</span>
      </button>

      <div class="orbit-tail">
        <span class="orbit-pill">
          <span class="orbit-pill-dot"></span>
          <span>{{ cpsLabel }}</span>
        </span>

        <span class="orbit-waves" aria-hidden="true">
          <span
            v-for="(bar, i) in waves"
            :key="i"
            class="orbit-wave"
            :style="bar.style"
          ></span>
        </span>

        <button
          class="orbit-whisper-btn"
          type="button"
          :aria-expanded="whisperOpen"
          @click="whisperOpen = !whisperOpen"
        >
          <span>{{ $t('companion.orbit.whisper') }}</span>
          <span class="material-symbols-outlined">{{ whisperOpen ? 'expand_less' : 'expand_more' }}</span>
        </button>
      </div>
    </div>

    <Transition name="orbit-fold">
      <div v-if="whisperOpen" class="orbit-whisper">
        <div class="orbit-quote-block">
          <p v-if="whisperLoading" class="orbit-quote is-loading">{{ $t('companion.orbit.whisperLoading') }}</p>
          <p v-else class="orbit-quote">{{ whisperQuote }}</p>
          <p v-if="whisperUnavailable" class="orbit-note">{{ $t('companion.orbit.whisperUnavailable') }}</p>
          <div class="orbit-meta">
            <span class="orbit-meta-freq">
              <span class="material-symbols-outlined">neurology</span>
              <span>{{ $t('companion.orbit.freq', { n: resonance }) }}</span>
            </span>
            <span aria-hidden="true">·</span>
            <span>{{ whisperEcho }}</span>
            <span aria-hidden="true">·</span>
            <span>{{ whisperFragment }}</span>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({
  /** Current composer text — drives the semantic branch (action / warm / cold). */
  text: { type: String, default: '' },
  /** A reply is in flight. */
  sending: { type: Boolean, default: false },
  /** Real figures from the server. Missing values render as a dash, never a guess. */
  intimacy: { type: Number, default: null },
  statusType: { type: String, default: '' },
  statusSummary: { type: String, default: '' },
  /** Latest memory fragment: { id, content }. */
  memory: { type: Object, default: null },
  /** The character's most recent reply, used as the whisper body while no model
   *  is configured to write an inner monologue. It is her own words, not ours. */
  lastRoleReply: { type: String, default: '' },
  /** Async loader for the real monologue. Called the first time the whisper
   *  layer opens, never on mount — it costs a model call. Resolves to
   *  { text, generated } or null. */
  loadWhisper: { type: Function, default: null }
})

const emit = defineEmits(['mood'])
const { t } = useI18n()

// ── CPS: keystroke density over the last two seconds ─────────────────────────
const KEY_WINDOW_MS = 2000
const IDLE_DECAY_MS = 1200
let keyTimes = []
let decayTimer = null
const cps = ref(0)
const hasText = computed(() => !!props.text.trim())
const whisperOpen = ref(false)

function noteKeystroke() {
  const now = performance.now()
  keyTimes.push(now)
  keyTimes = keyTimes.filter((t) => now - t <= KEY_WINDOW_MS)
  recompute()
  clearTimeout(decayTimer)
  decayTimer = setTimeout(() => {
    keyTimes = []
    recompute()
  }, IDLE_DECAY_MS)
}

function recompute() {
  const now = performance.now()
  keyTimes = keyTimes.filter((t) => now - t <= KEY_WINDOW_MS)
  if (keyTimes.length < 2) {
    cps.value = 0
    return
  }
  const span = (keyTimes[keyTimes.length - 1] - keyTimes[0]) / 1000
  cps.value = span <= 0.05 ? keyTimes.length : keyTimes.length / Math.max(span, 0.5)
}

onUnmounted(() => clearTimeout(decayTimer))

// ── Theme: colour temperature follows the typing branch ──────────────────────
const THEMES = {
  idle: { color: '#c4c7c8', glow: '0 0 10px rgba(255,255,255,0.03)', shadow: '0 0 6px rgba(255,255,255,0.10)' },
  deliberate: { color: '#e4e1e6', glow: '0 0 12px rgba(255,255,255,0.08)', shadow: '0 0 12px rgba(255,255,255,0.22)' },
  fast: { color: '#ffffff', glow: '0 0 24px rgba(255,255,255,0.22)', shadow: '0 0 24px rgba(255,255,255,0.55)' },
  action: { color: '#fdf6e2', glow: '0 0 18px rgba(254,243,199,0.16)', shadow: '0 0 18px rgba(254,243,199,0.40)' }
}

// Words that change the light. Deliberately narrow: a branch that fires on
// half of all sentences is noise, not a reading of the mood.
const WARM_WORDS = ['暖', '热酒', '壁炉', '抱', '喜欢你', '温柔', '想你', '谢谢你', '陪我']
const COLD_WORDS = ['雪', '冰', '寒', '风暴', '离开', '孤独', '难过', '再见']

const themeKey = computed(() => {
  if (props.sending) return 'fast'
  // Idle is "nothing on the line", not "no keystrokes in the last two seconds":
  // text sitting in the composer is still someone thinking.
  if (!hasText.value) return 'idle'
  if (props.text.includes('*')) return 'action'
  const warm = WARM_WORDS.some((w) => props.text.includes(w))
  const cold = COLD_WORDS.some((w) => props.text.includes(w))
  if (warm && !cold) return 'action'
  if (cps.value > 5) return 'fast'
  return 'deliberate'
})

const themeVars = computed(() => {
  const theme = THEMES[themeKey.value] || THEMES.idle
  return {
    '--orbit-color': theme.color,
    '--orbit-glow': theme.glow,
    '--orbit-text-shadow': theme.shadow
  }
})

// The same branch that drives the light drives the companion's face, so the two
// cannot drift apart. Declared after `themeKey` on purpose: an immediate watcher
// here would read a binding that is still in its temporal dead zone.
const mood = computed(() => {
  if (themeKey.value === 'fast') return 'charged'
  if (themeKey.value === 'action') return 'warm'
  if (themeKey.value === 'deliberate') return 'listening'
  return 'idle'
})

watch(mood, (value) => emit('mood', value), { immediate: true })

defineExpose({ noteKeystroke, mood })

// ── Wave bars: height and cadence scale with CPS ─────────────────────────────
const waves = computed(() => {
  const speed = Math.min(Math.max(cps.value / 2.5, 0.4), 3)
  const heights =
    cps.value > 4 ? [4, 6, 3, 7, 5] : cps.value > 1 ? [2.5, 4.5, 2, 5, 3] : [2, 3, 1.5, 3.5, 2]
  return heights.map((h, i) => ({
    style: {
      height: `${h * 4}px`,
      animationDuration: `${(1.6 / speed).toFixed(2)}s`,
      animationDelay: `${i * 110}ms`
    }
  }))
})

const cpsLabel = computed(() => {
  const value = cps.value > 0 ? cps.value.toFixed(1) : '0'
  return t('companion.orbit.cps', { n: value, label: t(`companion.orbit.temp.${themeKey.value}`) })
})

// ── Copy per branch. No invented numbers: only branch text and real figures. ─
const prefixLabel = computed(() => t(`companion.orbit.prefix.${themeKey.value}`))
const thoughtLabel = computed(() => t(`companion.orbit.thought.${themeKey.value}`))
const stateLabel = computed(() => t(`companion.orbit.state.${themeKey.value}`))

const resonance = computed(() => (props.intimacy == null ? '—' : `${props.intimacy}%`))

const metricLabel = computed(() => {
  if (props.intimacy == null) return t('companion.orbit.noGrowth')
  return t(`companion.orbit.metric.${themeKey.value}`, { n: props.intimacy })
})

const whisperEcho = computed(() => {
  if (props.statusSummary) return t('companion.orbit.echoLive', { text: props.statusSummary })
  if (props.statusType) return t('companion.orbit.echoState', { state: props.statusType })
  return t('companion.orbit.echoEmpty')
})

const whisperFragment = computed(() =>
  props.memory?.content
    ? t('companion.orbit.fragLive', { id: props.memory.id, text: props.memory.content })
    : t('companion.orbit.fragEmpty')
)

// The model-written monologue wins when it exists. Until it arrives — or when
// no model is configured — her own last reply stands in, clearly labelled.
const whisperText = ref(null)
const whisperLoading = ref(false)
const whisperUnavailable = ref(false)
let whisperRequested = false

const whisperQuote = computed(
  () => whisperText.value || props.lastRoleReply || t('companion.orbit.quoteEmpty')
)

async function loadWhisperOnce() {
  if (whisperRequested || !props.loadWhisper) return
  whisperRequested = true
  whisperLoading.value = true
  try {
    const result = await props.loadWhisper()
    if (result?.generated && result.text) {
      whisperText.value = result.text
      whisperUnavailable.value = false
    } else {
      whisperUnavailable.value = true
    }
  } catch {
    whisperUnavailable.value = true
  } finally {
    whisperLoading.value = false
  }
}

watch(whisperOpen, (open) => {
  if (open) loadWhisperOnce()
})

// Leaving the field with an empty composer drops straight back to idle.
watch(
  () => props.text,
  (value) => {
    if (!value.trim()) {
      keyTimes = []
      recompute()
    }
  }
)
</script>

<style scoped>
.mind-orbit {
  flex-shrink: 0;
  position: relative;
  padding: 0 24px 6px;
  z-index: 10;
}

/* A single hairline that fades out at both ends. It is the only structural mark:
   no box, no fill, no radius. */
.orbit-rule {
  height: 1px;
  background: linear-gradient(
    to right,
    transparent,
    rgba(68, 71, 72, 0.45) 18%,
    rgba(68, 71, 72, 0.45) 82%,
    transparent
  );
}

.orbit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 34px;
}

.orbit-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
  font-size: 11px;
  color: #c4c7c8;
  transition: color 0.3s;
}

.orbit-main:hover {
  color: #e4e1e6;
}

.orbit-dot {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 8px;
  height: 8px;
  flex-shrink: 0;
}

.orbit-ping {
  position: absolute;
  inset: 0;
  border-radius: 9999px;
  background: var(--orbit-color);
  opacity: 0.55;
  animation: orbit-ping 1.9s cubic-bezier(0, 0, 0.2, 1) infinite;
}

.orbit-core {
  position: relative;
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: var(--orbit-color);
  box-shadow: 0 0 8px var(--orbit-color);
  transition: background-color 0.3s, box-shadow 0.3s;
}

@keyframes orbit-ping {
  0% { transform: scale(1); opacity: 0.5; }
  75%, 100% { transform: scale(2.4); opacity: 0; }
}

.orbit-prefix {
  flex-shrink: 0;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  letter-spacing: -0.01em;
  color: var(--orbit-color);
  text-shadow: var(--orbit-text-shadow);
  transition: color 0.3s, text-shadow 0.3s;
}

.orbit-thought,
.orbit-metric,
.orbit-state {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: rgba(196, 199, 200, 0.78);
  transition: color 0.3s;
}

.orbit-thought {
  max-width: 34%;
}

.orbit-metric {
  max-width: 22%;
}

.orbit-state {
  max-width: 26%;
}

.orbit-sep {
  flex-shrink: 0;
  color: rgba(68, 71, 72, 0.6);
  font-size: 9px;
}

.orbit-tail {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.orbit-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 1px 7px;
  border-radius: 3px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  color: rgba(196, 199, 200, 0.8);
  background: transparent;
  transition: color 0.3s;
}

.is-deliberate .orbit-pill,
.is-fast .orbit-pill,
.is-action .orbit-pill {
  color: var(--orbit-color);
}

.orbit-pill-dot {
  width: 5px;
  height: 5px;
  border-radius: 9999px;
  background: var(--orbit-color);
  transition: background-color 0.3s;
}

.orbit-waves {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  opacity: 0.6;
  transition: opacity 0.3s;
}

.orbit-main:hover ~ .orbit-tail .orbit-waves,
.orbit-row:hover .orbit-waves {
  opacity: 1;
}

.orbit-wave {
  display: inline-block;
  width: 2px;
  border-radius: 9999px;
  background: var(--orbit-color);
  animation-name: orbit-wave;
  animation-timing-function: ease-in-out;
  animation-iteration-count: infinite;
  transition: background-color 0.3s, height 0.2s;
}

.is-idle .orbit-wave {
  animation-duration: 3.2s !important;
}

@keyframes orbit-wave {
  0%, 100% { transform: scaleY(0.55); opacity: 0.55; }
  50% { transform: scaleY(1.15); opacity: 1; }
}

.orbit-whisper-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 0;
  border: none;
  background: transparent;
  color: rgba(142, 145, 146, 0.9);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  cursor: pointer;
  transition: color 0.3s;
}

.orbit-whisper-btn:hover {
  color: var(--orbit-color);
}

.orbit-whisper-btn .material-symbols-outlined {
  font-size: 13px;
}

/* ── Whisper archive ─────────────────────────────────────────────────────── */
.orbit-whisper {
  padding: 6px 0 4px;
}

.orbit-quote-block {
  padding-left: 14px;
  border-left: 1px solid rgba(68, 71, 72, 0.45);
  transition: border-color 0.3s;
}

.is-deliberate .orbit-quote-block,
.is-fast .orbit-quote-block,
.is-action .orbit-quote-block {
  border-left-color: color-mix(in srgb, var(--orbit-color) 40%, transparent);
}

.orbit-quote {
  margin: 0;
  color: rgba(228, 225, 230, 0.92);
  font-size: 12.5px;
  font-style: italic;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}

.orbit-quote.is-loading {
  color: rgba(142, 145, 146, 0.8);
}

.orbit-note {
  margin: 4px 0 0;
  color: rgba(142, 145, 146, 0.7);
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
}

.orbit-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding-top: 3px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  color: rgba(142, 145, 146, 0.75);
}

.orbit-meta-freq {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: color-mix(in srgb, var(--orbit-color) 80%, #8e9192);
}

.orbit-meta-freq .material-symbols-outlined {
  font-size: 12px;
}

.orbit-fold-enter-active,
.orbit-fold-leave-active {
  transition: opacity 0.24s ease, transform 0.24s ease;
}

.orbit-fold-enter-from,
.orbit-fold-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 1100px) {
  .orbit-metric,
  .orbit-sep {
    display: none;
  }
  .orbit-thought {
    max-width: 46%;
  }
  .orbit-state {
    max-width: 40%;
  }
}

@media (max-width: 899px) {
  .mind-orbit {
    padding-left: 14px;
    padding-right: 14px;
  }
  .orbit-thought {
    display: none;
  }
  .orbit-state {
    max-width: 60%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .orbit-ping,
  .orbit-wave {
    animation: none;
  }
}
</style>

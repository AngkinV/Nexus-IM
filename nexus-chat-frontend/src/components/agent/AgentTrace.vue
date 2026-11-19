<template>
  <!-- Process terminal: one line per event the backend really sent for this
       turn, stamped with the moment it reached the browser. Nothing is scripted. -->
  <div class="rounded-lg bg-surface-container-lowest border border-outline-variant/40 overflow-hidden font-code">
    <button
      class="w-full flex items-center gap-2 px-3 py-1.5 bg-surface-container-low/60 text-[11px] text-on-surface-variant hover:text-primary transition-colors"
      :class="{ 'border-b border-outline-variant/20': open }"
      type="button"
      :aria-expanded="open"
      @click="userOpen = !open"
    >
      <span class="material-symbols-outlined text-[14px] text-primary">terminal</span>
      <span class="tracking-wider">{{ $t('agent.trace.title') }}</span>
      <span class="text-on-surface-variant/50 truncate">· {{ summary }}</span>
      <span class="ml-auto flex items-center gap-1.5 shrink-0">
        <span v-if="live" class="w-1.5 h-1.5 rounded-full bg-primary animate-pulse"></span>
        <span :class="live ? 'text-primary' : 'text-on-surface-variant/60'">{{ clock(live ? now : elapsedMs) }}</span>
        <span class="material-symbols-outlined text-[15px]">{{ open ? 'expand_less' : 'expand_more' }}</span>
      </span>
    </button>

    <div
      v-if="open"
      ref="bodyEl"
      class="trace-body max-h-64 overflow-y-auto px-3 py-2 space-y-0.5 text-[12px] leading-[1.6]"
    >
      <template v-for="(line, i) in lines" :key="i">
        <div class="flex items-start gap-2">
          <span class="shrink-0 w-12 text-right tabular-nums text-on-surface-variant/40">{{ clock(line.at) }}</span>
          <span
            class="shrink-0 w-3 text-center"
            :class="{
              'text-primary': line.state === 'ok',
              'text-error': line.state === 'fail',
              'text-on-surface-variant/60': line.state === 'idle'
            }"
          >
            <span
              v-if="line.state === 'run'"
              class="material-symbols-outlined text-[12px] text-primary animate-spin align-middle"
            >progress_activity</span>
            <template v-else>{{ MARK[line.state] }}</template>
          </span>
          <span class="shrink-0 w-14 uppercase text-on-surface-variant/60">{{ line.tag }}</span>
          <span class="min-w-0 break-words" :class="line.state === 'fail' ? 'text-error' : 'text-on-surface'">
            {{ line.text }}
            <span v-if="line.meta" class="text-on-surface-variant/60"> · {{ line.meta }}</span>
          </span>
        </div>
        <p
          v-if="line.detail"
          class="ml-[4.75rem] pl-2 border-l border-outline-variant/40 text-on-surface-variant whitespace-pre-wrap break-words"
        >{{ line.detail }}</p>
      </template>

      <div v-if="live" class="flex items-start gap-2 text-on-surface-variant/60">
        <span class="shrink-0 w-12 text-right tabular-nums text-on-surface-variant/40">{{ clock(now) }}</span>
        <span class="shrink-0 w-3 text-center text-primary">›</span>
        <span>{{ waiting }}<span class="trace-caret"></span></span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({
  // [{ at, kind, ... }] — appended by the agent store as SSE events arrive.
  trace: { type: Array, required: true },
  live: { type: Boolean, default: false },
  elapsedMs: { type: Number, default: null }
})

const { t } = useI18n()

const MARK = { ok: '✓', fail: '✕', idle: '·' }

const clock = (ms) => `${((ms ?? 0) / 1000).toFixed(1)}s`

// While the turn is running the terminal is open; once it ends it folds to its
// one-line summary. A click overrides either default.
const userOpen = ref(null)
const open = computed(() => userOpen.value ?? props.live)

// A ticking clock for the header and the waiting line, only while live.
const now = ref(props.trace[props.trace.length - 1]?.at ?? 0)
let tick = 0
watch(
  () => props.live,
  (live) => {
    clearInterval(tick)
    if (!live) return
    const base = performance.now() - now.value
    tick = setInterval(() => { now.value = performance.now() - base }, 100)
  },
  { immediate: true }
)
onUnmounted(() => clearInterval(tick))

const runState = (status) => {
  if (status === 'running') return 'run'
  if (['failed', 'FAILED', 'TIMEOUT', 'aborted'].includes(status)) return 'fail'
  return 'ok'
}

const LINE = {
  meta: (s) => ({ state: 'idle', tag: 'trace', text: s.traceId }),
  context: (s) => ({
    state: 'ok',
    tag: 'context',
    text: t('agent.trace.context', { shortTerm: s.shortTerm, recalled: s.recalled }),
    meta: `${s.ms}ms`
  }),
  knowledge: (s) => s.chunks == null
    ? { state: 'fail', tag: 'kb', text: t('agent.trace.kbSkipped') }
    : { state: 'ok', tag: 'kb', text: t('agent.trace.kbHits', { n: s.chunks }) },
  mock: () => ({ state: 'idle', tag: 'model', text: t('agent.trace.mock') }),
  model: (s) => {
    const base = { state: runState(s.status), tag: 'model', text: `#${s.iteration} ${s.model || s.provider}` }
    if (s.status === 'running') return base
    if (s.status === 'aborted') return { ...base, meta: t('agent.trace.aborted') }
    if (s.status === 'failed') return { ...base, meta: `${t('agent.trace.failed')} · ${clock(s.ms)}` }
    if (s.finishReason === 'timeout' || s.finishReason === 'error') {
      return { ...base, state: 'fail', meta: `${t(`agent.trace.${s.finishReason}`)} · ${clock(s.ms)}` }
    }
    const outcome = s.toolCalls ? t('agent.trace.wantsTools', { n: s.toolCalls }) : t('agent.trace.answered')
    return { ...base, meta: `${outcome} · ${clock(s.ms)} · ${s.inputTokens}→${s.outputTokens} tok` }
  },
  reasoning: (s) => ({ state: 'idle', tag: 'think', text: t('agent.trace.reasoning', { n: s.iteration }), detail: s.text }),
  tool: (s) => ({
    state: runState(s.status),
    tag: 'tool',
    text: `${s.toolName}(${Object.entries(s.args || {}).map(([k, v]) => `${k}=${JSON.stringify(v)}`).join(', ')})`,
    meta: s.status === 'running' ? '' : s.error || (s.status === 'aborted' ? t('agent.trace.aborted') : `${s.latencyMs}ms`)
  }),
  answer: (s) => ({ state: 'ok', tag: 'answer', text: t('agent.trace.answer', { n: s.chars }) }),
  usage: (s) => ({ state: 'idle', tag: 'usage', text: `${s.inputTokens} → ${s.outputTokens} · ${s.totalTokens} tokens` }),
  done: (s) => ({ state: 'ok', tag: 'done', text: s.finishReason }),
  error: (s) => ({ state: 'fail', tag: 'error', text: s.message || s.code })
}

const lines = computed(() => props.trace.map((step) => ({ at: step.at, ...LINE[step.kind](step) })))

// What the turn is waiting on right now, read off the last real event.
const waiting = computed(() => {
  const last = props.trace[props.trace.length - 1]
  if (!last) return t('agent.trace.waitConnect')
  if (last.kind === 'meta') return t('agent.trace.waitContext')
  if (last.kind === 'model' && last.status === 'running') return t('agent.trace.waitModel', { model: last.model || last.provider })
  if (last.kind === 'tool' && last.status === 'running') return t('agent.trace.waitTool', { tool: last.toolName })
  return t('agent.trace.waitNext')
})

const summary = computed(() => {
  const calls = props.trace.filter((s) => s.kind === 'model').length
  const tools = props.trace.filter((s) => s.kind === 'tool').length
  return t('agent.trace.summary', { steps: props.trace.length, calls, tools })
})

// Follow the newest line while the turn is running.
const bodyEl = ref(null)
watch(
  () => [props.trace.length, open.value],
  async () => {
    await nextTick()
    if (bodyEl.value) bodyEl.value.scrollTop = bodyEl.value.scrollHeight
  }
)
</script>

<style scoped>
.trace-body::-webkit-scrollbar {
  width: 6px;
}

.trace-body::-webkit-scrollbar-thumb {
  background: #353438;
  border-radius: 9999px;
}

.trace-caret {
  display: inline-block;
  width: 6px;
  height: 1em;
  margin-left: 4px;
  background: #ffffff;
  vertical-align: text-bottom;
  animation: trace-blink 1s steps(2) infinite;
}

@keyframes trace-blink {
  50% { opacity: 0; }
}
</style>

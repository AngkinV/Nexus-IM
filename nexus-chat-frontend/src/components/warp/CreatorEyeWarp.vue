<template>
  <!--
    Creator's Eye warp: the fullscreen transition into the AI dimension.
    Mount once per screen; drive it through useCreatorWarp().trigger().
    Stays in the DOM while idle (hidden) so the WebGL program is compiled once.
  -->
  <Teleport to="body">
    <div
      ref="overlayRef"
      class="cw-overlay"
      :class="{
        'is-open': isOpen,
        'is-leaving': isLeaving,
        'is-jitter': blinked,
        'no-webgl': !webglReady
      }"
      role="dialog"
      aria-modal="true"
      :aria-label="t('dialogLabel')"
      :aria-hidden="!isOpen"
      :data-phase="state.phase"
    >
      <!-- 1. Fullscreen WebGL stage -->
      <div class="cw-shader" :class="{ 'is-blinking': blinked }">
        <canvas ref="canvasRef" class="cw-canvas"></canvas>
      </div>

      <!-- 2. Binary fragments thrown outward by the blink -->
      <div class="cw-particles" aria-hidden="true">
        <span
          v-for="p in particles"
          :key="p.id"
          class="cw-particle"
          :style="p.style"
        >{{ p.symbol }}</span>
      </div>

      <!-- 3. Laser slit + shockwave -->
      <div class="cw-flash" aria-hidden="true">
        <div class="cw-laser" :class="{ 'is-firing': blinked }"></div>
        <div class="cw-shockwave" :class="{ 'is-firing': blinked }"></div>
      </div>

      <!-- 4. Top HUD -->
      <div class="cw-hud cw-hud--top">
        <div class="cw-pill cw-pill--status">
          <span class="cw-ping"></span>
          <span class="cw-pill-strong">{{ t('hudLabel') }}</span>
          <span class="cw-sep cw-from-sm">|</span>
          <span class="cw-from-sm" aria-live="polite">{{ copy.phase }}</span>
        </div>
        <button ref="exitRef" class="cw-btn cw-btn--exit" type="button" @click="warp.cancel()">
          <span>{{ t('exit') }}</span>
          <span class="cw-kbd">[ESC]</span>
        </button>
      </div>

      <!-- 5. Bottom HUD -->
      <div class="cw-hud cw-hud--bottom">
        <div class="cw-pill cw-pill--readout">
          <div>{{ t('observer') }}: <span class="cw-readout">{{ copy.observer }}</span></div>
          <div class="cw-from-sm">{{ t('resolution') }}: <span class="cw-readout-plain">{{ t('resolutionValue') }}</span></div>
          <div class="cw-from-md">{{ t('targetLabel') }}: <span class="cw-readout-plain">{{ t('targetValue', { target: targetFull }) }}</span></div>
        </div>
        <button
          class="cw-btn cw-btn--leap"
          type="button"
          :disabled="state.phase !== WARP_PHASE.GAZE"
          @click="warp.skip()"
        >
          <span>{{ t('leap') }}</span>
          <span class="cw-bolt" aria-hidden="true">⚡</span>
        </button>
      </div>

      <!-- 6. Stage label + progress -->
      <div class="cw-stage-label">
        <div class="cw-title" :class="{ 'is-singularity': displayPhase !== WARP_PHASE.GAZE }">
          <span>{{ copy.title }}</span>
        </div>
        <p class="cw-subtitle">{{ copy.subtitle }}</p>
        <div
          class="cw-progress"
          role="progressbar"
          aria-valuemin="0"
          aria-valuemax="100"
          :aria-valuenow="copy.percent"
        >
          <div class="cw-progress-fill" :style="{ width: `${copy.percent}%` }"></div>
        </div>
        <div class="cw-progress-meta">
          <span>{{ copy.step }}</span>
          <span class="cw-percent">{{ copy.percent }}%</span>
        </div>
      </div>

      <!-- 7. Arrival: the tavern -->
      <div class="cw-destination" :class="{ 'is-revealed': arrived }" :aria-hidden="!arrived">
        <div ref="cardRef" class="cw-card">
          <div class="cw-card-beam"></div>
          <div class="cw-card-icon">
            <svg viewBox="0 0 24 24" width="30" height="30" fill="currentColor" aria-hidden="true">
              <circle v-for="d in ANCHOR_DOTS" :key="d.k" :cx="d.x" :cy="d.y" :r="d.r" />
            </svg>
          </div>
          <div class="cw-card-anchor">{{ t('destination.anchor') }}</div>
          <h2 class="cw-card-title">{{ t('destination.title', { target: targetFull }) }}</h2>
          <p class="cw-card-desc">{{ t('destination.desc', { target: targetShort }) }}</p>
          <div class="cw-card-actions">
            <button
              ref="enterRef"
              class="cw-btn cw-btn--primary"
              type="button"
              :tabindex="arrived ? 0 : -1"
              @click="warp.confirm()"
            >{{ t('destination.enter') }}</button>
            <button
              class="cw-btn cw-btn--secondary"
              type="button"
              :tabindex="arrived ? 0 : -1"
              @click="warp.cancel()"
            >{{ t('destination.back') }}</button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCreatorWarp, WARP_PHASE } from '@/composables/useCreatorWarp'
import { createCreatorEyeRenderer } from './creatorEyeShader'
import messages from './warpMessages'

// Class names toggled on <html>; the global rules at the bottom of this file key off them.
const ACTIVE_CLASS = 'creator-warp-active'
const RELEASE_CLASS = 'creator-warp-release'
const RELEASE_MS = 500
// brightness() applied to the app while the eye is open. 0.2 is the design value and
// leaves a dark shell near black. A light shell would land on mid grey at 0.2 (the eye
// loses its black and the UI shows through the pupil), so it is pushed further down.
const BRIGHTNESS_VAR = '--creator-warp-brightness'
const DARK_WORLD_BRIGHTNESS = '0.2'
const LIGHT_WORLD_BRIGHTNESS = '0.04'

const FRAGMENT_SYMBOLS = ['0', '1', 'ø', '0101', '1010', '///', '§', '0x7F', '1100', '0011', 'Ø', '01', '10', '::', 'ABYSS', 'VOID', '0x00']
const FRAGMENT_COUNT = 110

// "blur_on"-style dot lattice for the arrival card: dots swell toward the centre.
const ANCHOR_DOTS = (() => {
  const dots = []
  for (let row = 0; row < 5; row++) {
    for (let col = 0; col < 5; col++) {
      const reach = Math.max(Math.abs(row - 2), Math.abs(col - 2))
      dots.push({ k: `${row}-${col}`, x: 4 + col * 4, y: 4 + row * 4, r: [1.5, 1, 0.5][reach] })
    }
  }
  return dots
})()

const { t } = useI18n({ useScope: 'local', messages })
const warp = useCreatorWarp()
const { state } = warp

const overlayRef = ref(null)
const canvasRef = ref(null)
const exitRef = ref(null)
const enterRef = ref(null)
const cardRef = ref(null)
const particles = ref([])
const webglReady = ref(true)

let renderer = null
let releaseTimer = null
let returnFocusTo = null

const isOpen = computed(() =>
  state.phase === WARP_PHASE.GAZE ||
  state.phase === WARP_PHASE.COLLAPSE ||
  state.phase === WARP_PHASE.ARRIVED
)
const isLeaving = computed(() => state.phase === WARP_PHASE.LEAVING)
const arrived = computed(() => state.phase === WARP_PHASE.ARRIVED)
// One-shot blink effects (collapse, jitter, laser, shockwave) fire from the collapse onward.
const blinked = computed(() =>
  !state.reducedMotion &&
  (state.phase === WARP_PHASE.COLLAPSE || state.phase === WARP_PHASE.ARRIVED)
)

// The HUD keeps showing the last live stage while the overlay fades out.
const displayPhase = ref(WARP_PHASE.GAZE)

const targetFull = computed(() => state.target || t('defaultTarget'))
const targetShort = computed(() => state.target || t('defaultTargetShort'))

const copy = computed(() => {
  const target = targetShort.value
  if (displayPhase.value === WARP_PHASE.GAZE) {
    return {
      phase: t('gaze.phase'),
      title: t('gaze.title'),
      subtitle: t('gaze.subtitle'),
      step: t('gaze.step'),
      observer: t('gaze.observer'),
      percent: 35
    }
  }
  const landed = displayPhase.value === WARP_PHASE.ARRIVED
  return {
    phase: t('collapse.phase', { target }),
    title: t('collapse.title'),
    subtitle: t('collapse.subtitle'),
    step: landed ? t('arrived.step') : t('collapse.step'),
    observer: landed ? t('arrived.observer') : t('collapse.observer'),
    percent: landed ? 100 : 84
  }
})

/** Fragments burst from the singularity in every direction, then fade. */
const spawnFragments = () => {
  const reach = Math.max(window.innerWidth, window.innerHeight) * 0.5
  const run = state.runId
  particles.value = Array.from({ length: FRAGMENT_COUNT }, (_, i) => {
    const angle = Math.random() * Math.PI * 2
    const distance = 240 + Math.random() * reach
    const duration = 0.45 + Math.random() * 0.45
    return {
      id: `${run}-${i}`,
      symbol: FRAGMENT_SYMBOLS[Math.floor(Math.random() * FRAGMENT_SYMBOLS.length)],
      style: {
        fontSize: `${11 + Math.floor(Math.random() * 20)}px`,
        '--tx': `${Math.cos(angle) * distance}px`,
        '--ty': `${Math.sin(angle) * distance * 0.85}px`,
        '--rot': `${(Math.random() - 0.5) * 720}deg`,
        '--sc': `${0.15 + Math.random() * 0.65}`,
        '--blr': `${Math.random() * 2.5}px`,
        animationDuration: `${duration}s`
      }
    }
  })
}

const startRenderer = () => {
  if (state.reducedMotion) return
  if (!renderer && canvasRef.value) renderer = createCreatorEyeRenderer(canvasRef.value)
  webglReady.value = renderer ? renderer.start() : false
}

// Viewport points sampled to judge the world behind the stage (fractions of width / height).
const WORLD_SAMPLE_POINTS = [[0.5, 0.5], [0.2, 0.25], [0.8, 0.25], [0.2, 0.75], [0.8, 0.75]]

/** Lightness (0..1) of the nearest painted background colour at or above an element. */
const paintedLightness = (el) => {
  for (let node = el; node; node = node.parentElement) {
    const parts = getComputedStyle(node).backgroundColor.match(/[\d.]+/g)?.map(Number)
    if (!parts || parts.length < 3) continue
    if (parts.length >= 4 && parts[3] < 0.5) continue // (nearly) transparent: keep climbing
    return (0.2126 * parts[0] + 0.7152 * parts[1] + 0.0722 * parts[2]) / 255
  }
  return null
}

/**
 * How light the app behind the stage actually looks: the median over a few points of
 * what is painted there. The body colour alone is not enough, since a dark shell can
 * sit on a light body and the other way round.
 */
const worldLightness = () => {
  const stage = overlayRef.value
  const samples = []
  for (const [fx, fy] of WORLD_SAMPLE_POINTS) {
    const hit = document
      .elementsFromPoint(window.innerWidth * fx, window.innerHeight * fy)
      .find((el) => !stage?.contains(el))
    const lightness = hit ? paintedLightness(hit) : null
    if (lightness !== null) samples.push(lightness)
  }
  if (!samples.length) return 0
  samples.sort((a, b) => a - b)
  return samples[Math.floor(samples.length / 2)]
}

const dimApp = () => {
  clearTimeout(releaseTimer)
  const root = document.documentElement
  // Both classes force the body black, so measure with neither applied.
  root.classList.remove(RELEASE_CLASS, ACTIVE_CLASS)
  const brightness = worldLightness() > 0.5 ? LIGHT_WORLD_BRIGHTNESS : DARK_WORLD_BRIGHTNESS
  root.style.setProperty(BRIGHTNESS_VAR, brightness)
  root.classList.add(ACTIVE_CLASS)
}

const releaseApp = (immediate = false) => {
  clearTimeout(releaseTimer)
  const root = document.documentElement
  if (!root.classList.contains(ACTIVE_CLASS) && !root.classList.contains(RELEASE_CLASS)) return
  root.classList.remove(ACTIVE_CLASS)
  root.style.removeProperty(BRIGHTNESS_VAR)
  if (immediate) {
    root.classList.remove(RELEASE_CLASS)
    return
  }
  root.classList.add(RELEASE_CLASS)
  releaseTimer = setTimeout(() => root.classList.remove(RELEASE_CLASS), RELEASE_MS)
}

const focusables = () => {
  const scope = arrived.value ? cardRef.value : overlayRef.value
  if (!scope) return []
  return [...scope.querySelectorAll('button:not([disabled]):not([tabindex="-1"])')]
}

const onKeydown = (event) => {
  if (!isOpen.value) return
  // The stage is modal: the host app's own shortcuts must not act on the screen behind it.
  event.stopPropagation()
  if (event.key === 'Escape') {
    event.preventDefault()
    warp.cancel()
    return
  }
  if (event.key !== 'Tab') return
  // Keep focus inside the stage; the app underneath is not reachable mid-warp.
  const items = focusables()
  if (!items.length) {
    event.preventDefault()
    return
  }
  const index = items.indexOf(document.activeElement)
  const next = event.shiftKey
    ? (index <= 0 ? items.length - 1 : index - 1)
    : (index === -1 || index === items.length - 1 ? 0 : index + 1)
  event.preventDefault()
  items[next].focus()
}

watch(
  () => state.phase,
  (phase, previous) => {
    if (isOpen.value) displayPhase.value = phase

    if (previous === WARP_PHASE.IDLE && isOpen.value) {
      // Opening
      returnFocusTo = document.activeElement instanceof HTMLElement ? document.activeElement : null
      particles.value = []
      dimApp()
      startRenderer()
      window.addEventListener('keydown', onKeydown, true)
      nextTick(() => (arrived.value ? enterRef.value : exitRef.value)?.focus({ preventScroll: true }))
    }

    if (phase === WARP_PHASE.COLLAPSE) spawnFragments()

    if (phase === WARP_PHASE.ARRIVED && previous !== WARP_PHASE.IDLE) {
      nextTick(() => enterRef.value?.focus({ preventScroll: true }))
    }

    if (phase === WARP_PHASE.LEAVING) {
      window.removeEventListener('keydown', onKeydown, true)
      releaseApp()
      particles.value = []
    }

    if (phase === WARP_PHASE.IDLE) {
      // Fully faded out (or reset): stop drawing and hand focus back.
      window.removeEventListener('keydown', onKeydown, true)
      renderer?.stop()
      particles.value = []
      if (previous !== WARP_PHASE.LEAVING) releaseApp(true)
      if (returnFocusTo?.isConnected) returnFocusTo.focus({ preventScroll: true })
      returnFocusTo = null
    }
  },
  { flush: 'post' }
)

onMounted(() => {
  warp.registerHost()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown, true)
  warp.unregisterHost()
  releaseApp(true)
  renderer?.destroy()
  renderer = null
})
</script>

<style scoped>
/* Monochrome Minimalist tokens used by the stage (see the design system). */
.cw-overlay {
  --cw-font: 'Geist Sans', 'Geist', 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  --cw-mono: 'JetBrains Mono', ui-monospace, 'SF Mono', Menlo, Consolas, 'PingFang SC', monospace;
  --cw-zinc-200: #e4e4e7;
  --cw-zinc-300: #d4d4d8;
  --cw-zinc-400: #a1a1aa;
  --cw-zinc-500: #71717a;
  --cw-zinc-600: #52525b;
  --cw-zinc-900: #18181b;
  --cw-zinc-950: #09090b;

  position: fixed;
  inset: 0;
  /* Above the app shell and the companion widget; call modals (9998+) and toasts stay on top. */
  z-index: 9990;
  overflow: hidden;
  opacity: 0;
  visibility: hidden;
  pointer-events: none;
  user-select: none;
  font-family: var(--cw-font);
  color: #fff;
  transition: opacity 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.cw-overlay.is-open {
  opacity: 1;
  visibility: visible;
  pointer-events: auto;
}

/* Fading out: still painted, no longer interactive. */
.cw-overlay.is-leaving {
  visibility: visible;
}

/* Without WebGL there is no eye to carry the stage, so darken it for the HUD. */
.cw-overlay.no-webgl {
  background: rgba(0, 0, 0, 0.82);
}

.cw-overlay.is-jitter {
  animation: cw-reality-jitter 0.45s ease-out forwards;
}

/* ── 1. Shader stage ─────────────────────────────────────────── */
.cw-shader {
  position: absolute;
  inset: 0;
  z-index: 10;
  overflow: hidden;
}

.cw-shader.is-blinking {
  animation: cw-blink-collapse 0.72s cubic-bezier(0.85, 0, 0.15, 1) forwards;
}

.cw-canvas {
  display: block;
  width: 100%;
  height: 100%;
}

/* ── 2. Fragments ────────────────────────────────────────────── */
.cw-particles {
  position: absolute;
  inset: 0;
  z-index: 20;
  overflow: hidden;
  pointer-events: none;
}

.cw-particle {
  position: absolute;
  left: 50%;
  top: 50%;
  font-family: var(--cw-mono);
  font-weight: 700;
  color: #fff;
  white-space: nowrap;
  /* Duration is set per fragment; each one ends fully transparent. */
  animation: cw-particle-burst 0.6s cubic-bezier(0.12, 0.85, 0.25, 1) forwards;
}

/* ── 3. Laser slit + shockwave ───────────────────────────────── */
.cw-flash {
  position: absolute;
  inset: 0;
  z-index: 25;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.cw-laser {
  position: absolute;
  width: 100%;
  height: 2px;
  background: #fff;
  opacity: 0;
}

.cw-laser.is-firing {
  animation: cw-laser-tear 0.55s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

.cw-shockwave {
  position: absolute;
  width: 860px;
  height: 520px;
  border: 2px solid #fff;
  border-radius: 9999px;
  opacity: 0;
}

.cw-shockwave.is-firing {
  animation: cw-shockwave 0.6s ease-out forwards;
}

/* ── 4/5. HUD ────────────────────────────────────────────────── */
.cw-hud {
  position: absolute;
  left: 2rem;
  right: 2rem;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  pointer-events: none;
}

.cw-hud--top { top: 1.5rem; }
.cw-hud--bottom { bottom: 1.5rem; }

.cw-pill {
  display: flex;
  align-items: center;
  min-width: 0;
  border-radius: 9999px;
  background: rgba(0, 0, 0, 0.75);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
  font-family: var(--cw-mono);
  white-space: nowrap;
}

.cw-pill--status {
  gap: 0.75rem;
  padding: 0.375rem 0.875rem;
  border: 1px solid rgba(255, 255, 255, 0.2);
  box-shadow: 0 0 20px rgba(0, 0, 0, 0.8);
  font-size: 0.75rem;
  line-height: 1rem;
  letter-spacing: 0.05em;
  color: var(--cw-zinc-300);
}

.cw-pill--status > span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
}

.cw-pill--readout {
  gap: 1.5rem;
  padding: 0.375rem 1rem;
  border: 1px solid rgba(255, 255, 255, 0.15);
  font-size: 11px;
  line-height: 1rem;
  color: var(--cw-zinc-400);
}

.cw-ping {
  flex-shrink: 0;
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 9999px;
  background: #fff;
  animation: cw-ping 1s cubic-bezier(0, 0, 0.2, 1) infinite;
}

.cw-pill-strong {
  color: #fff;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.cw-sep { color: var(--cw-zinc-600); }
.cw-readout { color: #fff; font-weight: 600; }
.cw-readout-plain { color: #fff; }

/* Buttons */
.cw-btn {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  margin: 0;
  border-radius: 9999px;
  font-family: var(--cw-font);
  cursor: pointer;
  pointer-events: auto;
  transition: background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease,
    transform 0.15s ease, opacity 0.15s ease;
}

.cw-btn:active { transform: scale(0.95); }

.cw-btn:focus-visible {
  outline: 2px solid #fff;
  outline-offset: 2px;
}

.cw-btn--exit {
  gap: 0.5rem;
  padding: 0.375rem 0.75rem;
  border: 1px solid rgba(255, 255, 255, 0.2);
  background: rgba(0, 0, 0, 0.8);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -4px rgba(0, 0, 0, 0.1);
  font-family: var(--cw-mono);
  font-size: 0.75rem;
  line-height: 1rem;
  color: var(--cw-zinc-300);
}

.cw-btn--exit:hover {
  background: var(--cw-zinc-900);
  border-color: rgba(255, 255, 255, 0.5);
  color: #fff;
}

.cw-kbd {
  font-family: var(--cw-font);
  font-size: 10px;
  letter-spacing: -0.025em;
  color: var(--cw-zinc-500);
}

.cw-btn--leap {
  gap: 0.375rem;
  padding: 0.375rem 1rem;
  border: none;
  background: #fff;
  box-shadow: 0 0 24px rgba(255, 255, 255, 0.45);
  font-size: 0.75rem;
  line-height: 1rem;
  font-weight: 700;
  letter-spacing: -0.025em;
  color: var(--cw-zinc-950);
}

.cw-btn--leap:hover { background: var(--cw-zinc-200); }

/* The leap only exists during the opening gaze. */
.cw-btn--leap:disabled {
  opacity: 0.35;
  cursor: default;
  box-shadow: none;
}

.cw-bolt { font-size: 0.75rem; }

/* ── 6. Stage label + progress ───────────────────────────────── */
.cw-stage-label {
  position: absolute;
  bottom: 4rem;
  left: 50%;
  z-index: 30;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  max-width: 620px;
  padding: 0 1rem;
  text-align: center;
  transform: translateX(-50%);
  pointer-events: none;
}

/* The label sits right on the lower lid's bright glyphs; a soft black pool keeps it legible. */
.cw-stage-label::before {
  content: '';
  position: absolute;
  inset: -1.75rem -3rem -1.5rem;
  z-index: -1;
  border-radius: 50%;
  background: radial-gradient(ellipse closest-side, rgba(0, 0, 0, 0.92) 0%, rgba(0, 0, 0, 0.86) 55%, rgba(0, 0, 0, 0) 100%);
}

.cw-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 1.125rem;
  line-height: 1.75rem;
  font-weight: 700;
  letter-spacing: -0.025em;
  color: #fff;
  filter: drop-shadow(0 0 18px rgba(255, 255, 255, 0.85));
}

.cw-title.is-singularity > span {
  filter: drop-shadow(0 0 32px rgba(255, 255, 255, 1));
}

.cw-subtitle {
  max-width: 36rem;
  margin: 0.25rem 0 0;
  font-family: var(--cw-mono);
  font-size: 11px;
  line-height: 1.625;
  color: var(--cw-zinc-300);
}

.cw-progress {
  position: relative;
  width: 100%;
  max-width: 20rem;
  height: 0.375rem;
  margin-top: 0.625rem;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.3);
  border-radius: 9999px;
  background: rgba(24, 24, 27, 0.9);
}

.cw-progress-fill {
  width: 0;
  height: 100%;
  background: #fff;
  box-shadow: 0 0 12px #fff;
  transition: width 0.3s cubic-bezier(0, 0, 0.2, 1);
}

.cw-progress-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  max-width: 20rem;
  margin-top: 0.25rem;
  font-family: var(--cw-mono);
  font-size: 10px;
  color: var(--cw-zinc-400);
}

.cw-percent { color: #fff; font-weight: 500; }

/* ── 7. Arrival stage ────────────────────────────────────────── */
.cw-destination {
  position: absolute;
  inset: 0;
  z-index: 40;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 2rem;
  background: rgba(9, 9, 11, 0.95);
  -webkit-backdrop-filter: blur(40px);
  backdrop-filter: blur(40px);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.7s cubic-bezier(0.4, 0, 0.2, 1);
}

.cw-destination.is-revealed {
  opacity: 1;
  pointer-events: auto;
}

.cw-card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  max-width: 42rem;
  padding: 2rem;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 1rem;
  background: rgba(27, 27, 30, 0.8);
  box-shadow: 0 0 80px rgba(255, 255, 255, 0.15);
  text-align: center;
}

.cw-card-beam {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 0.25rem;
  background: linear-gradient(to right, transparent, #fff, transparent);
}

.cw-card-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 4rem;
  height: 4rem;
  margin-bottom: 1rem;
  border: 1px solid rgba(255, 255, 255, 0.4);
  border-radius: 9999px;
  background: rgba(255, 255, 255, 0.05);
  box-shadow: 0 0 25px rgba(255, 255, 255, 0.2);
  color: #fff;
}

.cw-card-anchor {
  margin-bottom: 0.25rem;
  font-family: var(--cw-mono);
  font-size: 0.75rem;
  line-height: 1rem;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--cw-zinc-400);
}

.cw-card-title {
  margin: 0 0 0.5rem;
  font-size: 1.5rem;
  line-height: 2rem;
  font-weight: 700;
  letter-spacing: -0.025em;
  color: #fff;
}

.cw-card-desc {
  max-width: 28rem;
  margin: 0 0 1.5rem;
  font-size: 0.875rem;
  line-height: 1.625;
  color: var(--cw-zinc-300);
}

.cw-card-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
}

.cw-btn--primary,
.cw-btn--secondary {
  font-size: 0.8125rem;
  line-height: 1.125rem;
  letter-spacing: 0.01em;
}

.cw-btn--primary {
  padding: 0.5rem 1.25rem;
  border: none;
  background: #fff;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -2px rgba(0, 0, 0, 0.1);
  font-weight: 600;
  color: var(--cw-zinc-950);
}

.cw-btn--primary:hover { background: var(--cw-zinc-200); }

.cw-btn--secondary {
  padding: 0.5rem 1rem;
  border: 1px solid rgba(255, 255, 255, 0.2);
  background: #2a2a2d;
  font-weight: 500;
  color: var(--cw-zinc-300);
}

.cw-btn--secondary:hover {
  background: #39393c;
  color: #fff;
}

/* ── Responsive (matches the prototype's sm / md breakpoints) ── */
.cw-from-sm,
.cw-from-md {
  display: none;
}

@media (min-width: 640px) {
  .cw-from-sm { display: inline; }
  div.cw-from-sm { display: block; }
}

@media (min-width: 768px) {
  .cw-from-md { display: block; }
  .cw-title { font-size: 1.25rem; }
  .cw-card-title { font-size: 1.875rem; line-height: 2.25rem; }
}

@media (max-width: 639px) {
  .cw-hud { left: 1rem; right: 1rem; }
  .cw-hud--top { top: 1rem; }
  .cw-hud--bottom { bottom: 1rem; }
  .cw-destination { padding: 1rem; }
  .cw-card { padding: 1.5rem; }
}

/* ── Keyframes (from the prototype) ──────────────────────────── */
@keyframes cw-blink-collapse {
  0% { transform: scale(1, 1); filter: contrast(100%) brightness(1); }
  35% { transform: scale(1.05, 0.08); filter: contrast(220%) brightness(1.8); }
  50% { transform: scale(1.18, 0.005); filter: contrast(450%) brightness(4.5); }
  65% { transform: scale(0.04, 0.001); filter: contrast(600%) brightness(6); }
  85% { transform: scale(1.3, 1.3); filter: contrast(160%) brightness(1.4); }
  100% { transform: scale(1, 1); filter: contrast(100%) brightness(1); }
}

@keyframes cw-laser-tear {
  0% { transform: scaleX(0.01) scaleY(1); opacity: 0; box-shadow: 0 0 0 #fff; }
  30% { transform: scaleX(1.4) scaleY(2.5); opacity: 1; box-shadow: 0 0 50px #fff, 0 0 100px #fff; }
  70% { transform: scaleX(1.8) scaleY(0.4); opacity: 0.9; box-shadow: 0 0 80px #fff; }
  100% { transform: scaleX(2.5) scaleY(0.01); opacity: 0; box-shadow: 0 0 0 transparent; }
}

@keyframes cw-reality-jitter {
  0% { transform: translate(0, 0) skewX(0deg); filter: contrast(100%); }
  20% { transform: translate(-10px, 6px) skewX(-2.5deg); filter: contrast(280%) invert(0.2); }
  40% { transform: translate(12px, -8px) skewX(3.5deg); filter: contrast(400%) invert(0.4); }
  60% { transform: translate(-15px, -4px) skewX(-2deg); filter: contrast(450%) brightness(2.5); }
  80% { transform: translate(8px, 6px) skewX(1.5deg); filter: contrast(200%); }
  100% { transform: translate(0, 0) skewX(0deg); filter: contrast(100%); }
}

@keyframes cw-particle-burst {
  0% {
    transform: translate(0, 0) scale(1) rotate(0deg);
    opacity: 1;
    filter: blur(0);
  }
  100% {
    transform: translate(var(--tx, 150px), var(--ty, -150px)) scale(var(--sc, 0.3)) rotate(var(--rot, 180deg));
    opacity: 0;
    filter: blur(var(--blr, 2px));
  }
}

@keyframes cw-shockwave {
  0% { opacity: 0; transform: scale(0.85); box-shadow: inset 0 0 0 #fff; }
  45% { opacity: 1; transform: scale(1.15); box-shadow: inset 0 0 150px #fff, 0 0 140px rgba(255, 255, 255, 0.95); }
  100% { opacity: 0; transform: scale(1.65); box-shadow: inset 0 0 0 #fff; }
}

@keyframes cw-ping {
  75%, 100% { transform: scale(2); opacity: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .cw-ping { animation: none; }
  .cw-destination { transition-duration: 0.2s; }
}
</style>

<style>
/*
  The world behind the eye. While the warp is open the app root is drained of
  colour and pushed back; <html>/<body> go black so the shrunken root never
  shows a light frame around it. Released over 500ms when the warp closes.
*/
html.creator-warp-active,
html.creator-warp-active body,
html.creator-warp-release,
html.creator-warp-release body {
  background: #000 !important;
}

html.creator-warp-active #app {
  filter: grayscale(100%) contrast(150%) brightness(var(--creator-warp-brightness, 0.2));
  transform: scale(0.96);
  transition: filter 0.6s cubic-bezier(0.16, 1, 0.3, 1), transform 0.6s cubic-bezier(0.16, 1, 0.3, 1);
}

html.creator-warp-release #app {
  transition: filter 0.5s ease-out, transform 0.5s ease-out;
}

@media (prefers-reduced-motion: reduce) {
  html.creator-warp-active #app {
    transform: none;
    transition: filter 0.2s ease-out;
  }

  html.creator-warp-release #app {
    transition: filter 0.2s ease-out;
  }
}
</style>

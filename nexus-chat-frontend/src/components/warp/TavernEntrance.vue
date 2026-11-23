<template>
  <!--
    Tavern entrance: the fullscreen transition into the tavern.
    Mount once per screen; drive it through useTavernEntrance().enter().
    The canvases are decorative; the repeat-view skip button is accessible.
  -->
  <Teleport to="body">
    <div
      ref="overlayRef"
      class="tavern-entrance"
      :class="{ 'is-open': isOpen }"
      :aria-hidden="!isOpen"
      role="dialog"
      aria-label="酒馆入场"
      :aria-modal="isOpen ? true : undefined"
      :data-phase="state.phase"
      :data-skippable="state.canSkip ? 'true' : 'false'"
      :data-dark="darkShell ? 'true' : 'false'"
      @pointerdown="onPointerdown"
    >
      <!-- The picture: a drop of ink, the wash it becomes, then colour. -->
      <canvas aria-hidden="true" ref="paintRef" class="te-canvas"></canvas>
      <!-- The same picture as a sheet of paper, once it starts to crumple. -->
      <canvas aria-hidden="true" ref="paperRef" class="te-canvas te-paper"></canvas>

      <!--
        Skip: only ever offered from the second entrance on. The first one plays in
        full on purpose — it is the one time the picture is actually seen.
      -->
      <button
        ref="skipRef"
        v-if="showSkip"
        class="te-skip"
        type="button"
        @pointerdown.stop
        @click.stop="onSkipClick"
      >
        跳过
      </button>
    </div>
  </Teleport>
</template>

<script setup>
import { nextTick, computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTavernEntrance, ENTRANCE_PHASE } from '@/composables/useTavernEntrance'
import { createTavernEntranceScene } from './tavernEntranceScene'

// How long after a click the picture still starts from where the pointer was.
// Keyboard and command-palette entrances have no such point and start from the centre.
const POINTER_FRESH_MS = 600
// Baking takes a few hundred ms of background slices; start once the app has settled.
const PREPARE_DELAY_MS = 600
// The skip button appears a moment in, so it is not the first thing the eye lands on.
const SKIP_APPEAR_MS = 700

const props = defineProps({ soundEnabled: { type: Boolean, default: true } })
const skipRef = ref(null)
const entrance = useTavernEntrance()
const { state } = entrance

const overlayRef = ref(null)
const paintRef = ref(null)
const paperRef = ref(null)
// Review only: the picture is frozen at one moment (see `seek`).
const frozen = ref(false)
const skipReady = ref(false)
// Which palette the picture is using, so the skip button can invert with it.
const darkShell = ref(true)

const isOpen = computed(() => state.phase !== ENTRANCE_PHASE.IDLE || frozen.value)
// Both conditions matter: this is not the first viewing, and it has settled in a little.
const showSkip = computed(
  () => isOpen.value && !frozen.value && state.canSkip && skipReady.value
)

let scene = null
let sceneFailed = false
let prepareTimer = null
let skipTimer = null
let lastPointer = null
let previousFocus = null

const ensureScene = () => {
  if (!scene && !sceneFailed && paintRef.value && paperRef.value) {
    try {
      scene = createTavernEntranceScene(paintRef.value, paperRef.value, {
        soundEnabled: props.soundEnabled,
        onCommit: () => entrance.commit(),
        onDone: (reason) => {
          frozen.value = false
          skipReady.value = false
          clearTimeout(skipTimer)
          // Only a first viewing that played to its end counts as seen. Being cut short
          // (a resize, a call) means it was not actually watched, so it plays in full
          // again next time.
          if (reason === 'done' && !state.canSkip) entrance.markSeen()
          entrance.finish()
          if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true })
          previousFocus = null
        }
      })
    } catch (error) {
      // No usable canvas: the entrance is skipped, the tavern still opens.
      sceneFailed = true
      console.warn('[tavern-entrance] scene unavailable', error)
    }
  }
  return scene
}

// Viewport points sampled to judge the app behind the stage (fractions of width / height).
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
 * Whether the app behind the stage looks dark: the median over a few points of what is
 * painted there. The picture starts as a drawing on the app itself, so it must use
 * light lines on a dark shell and ink lines on a light one. The body colour alone is
 * not enough, since a dark shell can sit on a light body and the other way round.
 */
const worldIsDark = () => {
  const samples = []
  for (const [fx, fy] of WORLD_SAMPLE_POINTS) {
    const hit = document
      .elementsFromPoint(window.innerWidth * fx, window.innerHeight * fy)
      .find((el) => !overlayRef.value?.contains(el))
    const lightness = hit ? paintedLightness(hit) : null
    if (lightness !== null) samples.push(lightness)
  }
  if (!samples.length) return true
  samples.sort((a, b) => a - b)
  return samples[Math.floor(samples.length / 2)] <= 0.5
}

const originX = () =>
  lastPointer && performance.now() - lastPointer.time < POINTER_FRESH_MS ? lastPointer.x : window.innerWidth * .57

const trackPointer = (event) => {
  ensureScene()?.unlock()
  lastPointer = { x: event.clientX, time: performance.now() }
}

const onPointerdown = (event) => {
  event.preventDefault()
  event.stopPropagation()
  if (frozen.value) {
    scene?.leave('skipped')
    return
  }
  // The first viewing cannot be cut short: the click is simply not an exit.
  if (!state.canSkip) return
  entrance.skip()
}

const onSkipClick = () => {
  if (state.phase !== ENTRANCE_PHASE.PLAYING) return
  entrance.skip()
}

const onKeydown = (event) => {
  if (state.phase === ENTRANCE_PHASE.IDLE) {
    if (['Enter', ' '].includes(event.key) || event.metaKey || event.ctrlKey) ensureScene()?.unlock()
    return
  }
  // The stage owns the keyboard: the app's shortcuts must not act on a screen that is covered.
  event.stopImmediatePropagation()
  if (['Enter', ' ', 'Escape'].includes(event.key)) { event.preventDefault(); ensureScene()?.unlock() }
  if (event.key === 'Tab') {
    // The skip button is the one focusable thing here; keep focus inside the stage.
    event.preventDefault()
    if (state.canSkip) { skipReady.value = true; nextTick(() => skipRef.value?.focus()) }
    return
  }
  if (!state.canSkip) return
  if (event.key === 'Escape') {
    event.preventDefault()
    entrance.cancel()
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    entrance.skip()
  }
}

// The picture is built for one window size; a resize mid-entrance goes straight in.
// This ignores the first-viewing lock on purpose: the picture can no longer be drawn
// correctly, so holding the user here would be worse than cutting it short.
const onResize = () => {
  if (state.phase !== ENTRANCE_PHASE.PLAYING) return
  scene?.resize()
}

watch(
  () => state.phase,
  (phase, previous) => {
    if (phase === ENTRANCE_PHASE.PLAYING) {
      previousFocus = document.activeElement
      skipReady.value = false
      clearTimeout(skipTimer)
      if (state.canSkip) skipTimer = setTimeout(() => (skipReady.value = true), SKIP_APPEAR_MS)
      // The stage is still transparent here, so the samples see the app itself.
      darkShell.value = worldIsDark()
      const started = ensureScene()?.start({ x: originX(), dark: darkShell.value, fast: state.fast })
      if (!started) {
        // No canvas to draw on: never strand the caller.
        entrance.commit()
        entrance.finish()
      }
    } else if (phase === ENTRANCE_PHASE.LEAVING) {
      clearTimeout(skipTimer)
      skipReady.value = false
      // Asked to leave before the scene ever started (same tick as `enter`): nothing
      // would report back, so finish here.
      if (scene?.running) scene.leave(state.committed ? 'skipped' : 'cancelled')
      else entrance.finish()
    } else if (previous !== ENTRANCE_PHASE.IDLE && scene?.running && !frozen.value) {
      // Reset from outside while still drawing.
      scene.leave('cancelled')
    }
  }
)

/**
 * Review only: freeze the picture at `seconds` into the transition (0 to 8.7), so a
 * single moment can be inspected. A click releases it.
 */
const seek = async (seconds, { x = window.innerWidth * .57, dark = worldIsDark() } = {}) => {
  if (state.phase !== ENTRANCE_PHASE.IDLE || !ensureScene()) return
  frozen.value = true
  darkShell.value = dark
  const ok = await scene.seek(seconds, { x, dark })
  if (!ok) frozen.value = false
  return ok
}

const prepare = () => ensureScene()?.prepare({ dark: worldIsDark() }) || Promise.resolve(false)
const diagnostics = () => ensureScene()?.diagnostics
watch(() => props.soundEnabled, value => scene?.setSoundEnabled(value))
defineExpose({ seek, prepare, diagnostics, get ready() { return scene?.ready || false } })

onMounted(() => {
  entrance.registerHost()
  window.addEventListener('pointerdown', trackPointer, { capture: true, passive: true })
  window.addEventListener('keydown', onKeydown, true)
  window.addEventListener('resize', onResize)
  // Bake the picture and the folds while the app is idle, so the click that starts the
  // entrance has nothing left to compute. Short delay: this must be ready before the
  // first click when possible; cold starts expose their actual preparation wait.
  prepareTimer = setTimeout(() => ensureScene()?.prepare({ dark: worldIsDark() }), PREPARE_DELAY_MS)
})

onBeforeUnmount(() => {
  clearTimeout(prepareTimer)
  clearTimeout(skipTimer)
  window.removeEventListener('pointerdown', trackPointer, { capture: true })
  window.removeEventListener('keydown', onKeydown, true)
  window.removeEventListener('resize', onResize)
  entrance.unregisterHost()
  scene?.dispose()
  scene = null
})
</script>

<style scoped>
.tavern-entrance {
  position: fixed;
  inset: 0;
  /* Above the app shell and the companion widget; call modals (9998+) and toasts stay on top. */
  z-index: 9990;
  overflow: hidden;
  pointer-events: none;
  user-select: none;
  /* Preparation paints cached frames too. Inactive must mean visually hidden,
     not merely aria-hidden or unable to receive pointer events. */
  visibility: hidden;
  opacity: 0;
}

.tavern-entrance.is-open {
  pointer-events: auto;
  visibility: visible;
  opacity: 1;
}

.te-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  /* The scene fades both canvases through inline opacity when the entrance is cut short. */
  transition: opacity 0.25s ease;
}

/* Shown by the scene only while the sheet is being crumpled. */
.te-paper {
  visibility: hidden;
}

/*
  Skip. Deliberately quiet: a hairline pill in the corner, paper-coloured, no accent.
  It sits above both canvases and appears only after the first viewing.
*/
.te-skip {
  position: absolute;
  right: 2.25rem;
  bottom: 2rem;
  z-index: 1;
  padding: 0.4rem 1rem;
  border: 1px solid rgba(255, 255, 255, 0.22);
  border-radius: 9999px;
  background: rgba(0, 0, 0, 0.18);
  color: rgba(255, 255, 255, 0.82);
  font-size: 0.8125rem;
  letter-spacing: 0.06em;
  line-height: 1.5;
  cursor: pointer;
  backdrop-filter: blur(6px);
  transition: background 0.2s ease, border-color 0.2s ease, color 0.2s ease;
}

.te-skip:hover {
  background: rgba(0, 0, 0, 0.32);
  border-color: rgba(255, 255, 255, 0.38);
  color: #fff;
}

.te-skip:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.55);
  outline-offset: 2px;
}

/* On the light palette the picture is pale paper, so the button inverts. */
.tavern-entrance[data-dark='false'] .te-skip {
  border-color: rgba(0, 0, 0, 0.2);
  background: rgba(255, 255, 255, 0.32);
  color: rgba(0, 0, 0, 0.72);
}

.tavern-entrance[data-dark='false'] .te-skip:hover {
  background: rgba(255, 255, 255, 0.55);
  border-color: rgba(0, 0, 0, 0.32);
  color: #000;
}

@media (prefers-reduced-motion: reduce) {
  .te-canvas,
  .te-skip {
    transition: none;
  }
}
</style>

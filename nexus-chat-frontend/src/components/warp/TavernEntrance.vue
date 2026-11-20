<template>
  <!--
    Tavern entrance: the fullscreen transition into the tavern.
    Mount once per screen; drive it through useTavernEntrance().enter().
    Purely decorative, so it is hidden from assistive tech and takes no focus.
  -->
  <Teleport to="body">
    <div
      ref="overlayRef"
      class="tavern-entrance"
      :class="{ 'is-open': isOpen }"
      aria-hidden="true"
      :data-phase="state.phase"
      @pointerdown="onPointerdown"
    >
      <!-- The picture: line, ink, colour. -->
      <canvas ref="paintRef" class="te-canvas"></canvas>
      <!-- The same picture as a sheet of paper, once it starts to crumple. -->
      <canvas ref="paperRef" class="te-canvas te-paper"></canvas>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTavernEntrance, ENTRANCE_PHASE } from '@/composables/useTavernEntrance'
import { createTavernEntranceScene } from './tavernEntranceScene'

// How long after a click the picture still starts from where the pointer was.
// Keyboard and command-palette entrances have no such point and start from the centre.
const POINTER_FRESH_MS = 600
// Baking the mountains takes a few hundred ms of background slices; start once the app has settled.
const PREPARE_DELAY_MS = 1500

const entrance = useTavernEntrance()
const { state } = entrance

const overlayRef = ref(null)
const paintRef = ref(null)
const paperRef = ref(null)
// Review only: the picture is frozen at one moment (see `seek`).
const frozen = ref(false)

const isOpen = computed(() => state.phase !== ENTRANCE_PHASE.IDLE || frozen.value)

let scene = null
let sceneFailed = false
let prepareTimer = null
let lastPointer = null

const ensureScene = () => {
  if (!scene && !sceneFailed && paintRef.value && paperRef.value) {
    try {
      scene = createTavernEntranceScene(paintRef.value, paperRef.value, {
        onCommit: () => entrance.commit(),
        onDone: () => {
          frozen.value = false
          entrance.finish()
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
  lastPointer && performance.now() - lastPointer.time < POINTER_FRESH_MS ? lastPointer.x : window.innerWidth / 2

const trackPointer = (event) => {
  lastPointer = { x: event.clientX, time: performance.now() }
}

const onPointerdown = () => {
  if (frozen.value) scene?.leave('skipped')
  else entrance.skip()
}

const onKeydown = (event) => {
  if (state.phase === ENTRANCE_PHASE.IDLE) return
  // The stage owns the keyboard: the app's shortcuts must not act on a screen that is covered.
  event.stopPropagation()
  if (event.key === 'Escape') {
    event.preventDefault()
    entrance.cancel()
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    entrance.skip()
  } else if (event.key === 'Tab') {
    event.preventDefault()
  }
}

// The picture is built for one window size; a resize mid-entrance goes straight in.
const onResize = () => {
  if (state.phase === ENTRANCE_PHASE.PLAYING) entrance.skip()
}

watch(
  () => state.phase,
  (phase, previous) => {
    if (phase === ENTRANCE_PHASE.PLAYING) {
      // The stage is still transparent here, so the samples see the app itself.
      const started = ensureScene()?.start({ x: originX(), dark: worldIsDark(), fast: state.fast })
      if (!started) {
        // No canvas to draw on: never strand the caller.
        entrance.commit()
        entrance.finish()
      }
    } else if (phase === ENTRANCE_PHASE.LEAVING) {
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
 * Review only: freeze the picture at `seconds` into the transition (0 to 4.55), so a
 * single moment can be inspected. A click releases it.
 */
const seek = (seconds, { x = window.innerWidth / 2, dark = worldIsDark() } = {}) => {
  if (state.phase !== ENTRANCE_PHASE.IDLE || !ensureScene()) return
  frozen.value = true
  scene.seek(seconds, { x, dark })
}

defineExpose({ seek })

onMounted(() => {
  entrance.registerHost()
  window.addEventListener('pointerdown', trackPointer, { capture: true, passive: true })
  window.addEventListener('keydown', onKeydown, true)
  window.addEventListener('resize', onResize)
  prepareTimer = setTimeout(() => ensureScene()?.prepare(), PREPARE_DELAY_MS)
})

onBeforeUnmount(() => {
  clearTimeout(prepareTimer)
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
}

.tavern-entrance.is-open {
  pointer-events: auto;
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
</style>

<template>
  <canvas ref="canvasRef" class="computer-eye" @pointerdown="poke"></canvas>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * Standby eye — a machine eye built only from 0/1 and letters.
 *
 * Geometry follows the proven proportions of the site cover eye:
 *   eye half-width  A
 *   upper lid       0.50·A above the midline   (asymmetric on purpose —
 *   lower lid       0.38·A below the midline    symmetric lids read as a lens)
 *   iris radius     0.40·A, pupil 0.42·iris
 *   gaze offset     0.30·A horizontally, 0.13·A vertically
 *
 * The pupil is solid ink with a hole punched for the catchlight; the lids are
 * filled almost solid so the outline stays crisp; the sclera is left nearly
 * empty so the shape reads at a glance.
 */
const canvasRef = ref(null)

const BITS = '01'
const LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'

// Prism monochrome ramp.
const INK = '#ffffff'
const INK2 = '#c4c7c8'
const MUTED = '#8e9192'
const FAINT = '#353438'

let canvas = null
let ctx = null
let W = 0
let H = 0
let A = 0
let font = '500 11px monospace'
let cw = 6
let ch = 13
let c0 = 0
let r0 = 0
let cols = 0
let rows = 0
let rnd = null
let glyph = null

let lookX = 0
let lookY = 0
let toX = 0
let toY = 0
let closed = 0

let raf = 0
let last = 0
let blinkFrom = -1
let blinkAt = 0
let doubleQueued = 0
let visible = true
let running = false
let reduced = false
let ro = null
let io = null

const layout = () => {
  if (!canvas) return false
  W = canvas.clientWidth
  H = canvas.clientHeight
  if (!W || !H) return false
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  canvas.width = Math.round(W * dpr)
  canvas.height = Math.round(H * dpr)
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

  // The eye's proportions are fixed; the canvas is sized to fit them, not the
  // other way round. Work from the smaller dimension so a wide canvas gives a
  // wide eye rather than a squashed one.
  //
  //   opening height = (0.5 + 0.38)·A = 0.88·A
  //   lid bands      = 3.3 cells
  //   A is capped by width (0.46·W) and by the height budget.
  const MAX_CELL = 26
  const MIN_CELL = 12
  const fsFromW = Math.max(MIN_CELL, Math.min(MAX_CELL, (W * 0.44) / 17))
  // Total height needed for a given A: 0.88A + 3.3 cells, and cells scale as A/17.
  const heightFor = (aa) => aa * 0.88 + 5.3 * Math.max(MIN_CELL, Math.min(MAX_CELL, aa / 17)) * 0.78
  let aa = W * 0.44
  // Shrink A until the eye fits the canvas height with a 4% margin.
  let guard = 0
  while (heightFor(aa) > H * 0.92 && aa > 20 && guard++ < 200) aa *= 0.985
  A = aa
  const fs = Math.max(MIN_CELL, Math.min(MAX_CELL, A / 17))
  font = `500 ${fs.toFixed(1)}px 'JetBrains Mono', ui-monospace, monospace`
  ctx.font = font
  cw = ctx.measureText('0').width || fs * 0.6
  ch = Math.max(4, Math.round(fs * 0.78))

  c0 = Math.ceil(W / 2 / cw)
  r0 = Math.ceil(H / 2 / ch)
  cols = c0 * 2 + 1
  rows = r0 * 2 + 1
  rnd = new Float32Array(cols * rows)
  glyph = new Uint8Array(cols * rows)
  for (let i = 0; i < rnd.length; i += 1) {
    rnd[i] = Math.random()
    glyph[i] = (Math.random() * 256) | 0
  }
  return true
}

const draw = () => {
  if (!W || !ctx) return
  const open = 1 - closed
  const a = A
  // Lids: the upper is thicker and sits further from the midline.
  const bu = a * 0.5 * open
  const bl = a * 0.38 * open
  const tu = ch * 3.4
  const tl = ch * 1.9
  const ao = a + cw * 3

  const ri = a * 0.4
  const rp = ri * 0.42
  // Both axes travel. Vertical travel is generous on purpose: the iris slides
  // under the lid and gets clipped by the opening, which is what makes the
  // eye read as looking up/down instead of just sliding sideways.
  const ix = lookX * a * 0.26
  const iy = lookY * a * 0.22
  const hx = ix - ri * 0.3
  const hy = iy - ri * 0.32
  const hr = ri * 0.14

  ctx.clearRect(0, 0, W, H)
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.font = font

  // Bold glyphs are batched so the font switch happens once per frame.
  const bold = []
  let lastFill = null
  let lastAlpha = -1
  let i = 0
  // Glyph advance is narrower than the cell; scale horizontally so cells meet.

  for (let r = 0; r < rows; r += 1) {
    const y = (r - r0) * ch
    for (let c = 0; c < cols; c += 1, i += 1) {
      const x = (c - c0) * cw
      const p = rnd[i]

      // Nothing is drawn outside the eye: glyphs scattered around it read as
      // noise, not as part of the shape.
      let set = BITS
      let fill = FAINT
      let alpha = 1
      let heavy = false
      let on = false

      // Outer aperture (lid + thickness), then the inner opening.
      const to = x / ao
      let inOuter = false
      let inInner = false
      if (to > -1 && to < 1) {
        const fo = 1 - to * to
        // The band follows the almond: its thickness tapers toward the corners
        // by the same factor as the opening, so the silhouette stays a lens.
        const upperEdge = -(bu + tu) * Math.pow(fo, 0.8)
        const lowerEdge = (bl + tl) * fo
        inOuter = y > upperEdge && y < lowerEdge
      }
      if (inOuter) {
        const ti = x / a
        if (ti > -1 && ti < 1) {
          const fi = 1 - ti * ti
          inInner = y > -bu * Math.pow(fi, 0.8) && y < bl * fi
        }
        alpha = 1

        // Is this cell part of the iris/pupil? The iris is allowed to extend
        // past the opening — it gets clipped by the lid, which is what makes
        // an upward glance read as upward instead of just shifted.
        const dx = x - ix
        const dy = y - iy
        const d = Math.sqrt(dx * dx + dy * dy)
        const inIris = d < ri
        const catchlight = Math.sqrt((x - hx) ** 2 + (y - hy) ** 2) < hr

        if (!inInner && !inIris) {
          // Lid: a solid block of letters. Filling it is what makes the
          // almond silhouette read — a sparse lid looks like dotted noise.
          on = p < 0.99
          set = LETTERS
          fill = INK
          heavy = true
        } else if (catchlight) {
          on = false
        } else if (d < rp) {
          on = true
          fill = INK
          heavy = true
        } else if (d < ri) {
          set = LETTERS
          if (d > ri * 0.88) {
            on = true
            fill = INK
            heavy = true
          } else {
            on = p < 0.95
            fill = p < 0.5 ? INK2 : MUTED
          }
        } else {
          // Sclera: deliberately sparse.
          on = p < 0.22
          fill = FAINT
        }
      }

      if (!on) continue
      const char = set[glyph[i] % set.length]
      if (heavy) {
        bold.push(char, x, y)
        continue
      }
      if (fill !== lastFill) ctx.fillStyle = lastFill = fill
      if (alpha !== lastAlpha) ctx.globalAlpha = lastAlpha = alpha
      ctx.fillText(char, W / 2 + x, H / 2 + y)
    }
  }

  ctx.globalAlpha = 1
  ctx.fillStyle = INK
  ctx.font = `700 ${font.slice(4)}`
  for (let b = 0; b < bold.length; b += 3) ctx.fillText(bold[b], W / 2 + bold[b + 1], H / 2 + bold[b + 2])
}

const BLINK_MS = 240

const frame = (now) => {
  raf = 0
  if (!running) return
  const dt = Math.min(now - last, 60)
  last = now

  // Exponential smoothing: the gaze eases toward the pointer.
  const k = 1 - Math.exp(-dt / 110)
  lookX += (toX - lookX) * k
  lookY += (toY - lookY) * k

  if (blinkFrom < 0 && now >= blinkAt) blinkFrom = now
  if (blinkFrom >= 0) {
    const u = (now - blinkFrom) / BLINK_MS
    if (u >= 1) {
      closed = 0
      blinkFrom = -1
      if (doubleQueued > 0) {
        doubleQueued -= 1
        blinkAt = now + 90
      } else {
        blinkAt = now + 2800 + Math.random() * 4200
      }
    } else {
      closed = Math.sin(Math.PI * u)
    }
  }

  // Glyphs churn slowly so the field never looks like a frozen bitmap.
  const churn = Math.ceil((rnd.length * dt) / 1000 * 0.5)
  for (let n = churn; n > 0; n -= 1) glyph[(Math.random() * glyph.length) | 0] = (Math.random() * 256) | 0

  draw()
  raf = requestAnimationFrame(frame)
}

const wake = () => {
  if (raf || !running || !W) return
  last = performance.now()
  if (!blinkAt) blinkAt = last + 2200
  raf = requestAnimationFrame(frame)
}

const still = () => {
  lookX = lookY = toX = toY = closed = 0
  draw()
}

const onPointerMove = (e) => {
  if (e.pointerType === 'touch' || !W) return
  const box = canvas.getBoundingClientRect()
  const dx = e.clientX - (box.left + box.width / 2)
  const dy = e.clientY - (box.top + box.height / 2)
  // Distance-normalised: far away saturates, never leaves the socket.
  toX = dx / (Math.abs(dx) + 260)
  toY = dy / (Math.abs(dy) + 200)
}

const poke = () => {
  if (blinkFrom < 0 && !reduced) {
    doubleQueued = 1
    blinkAt = 0
  }
}

const onVisibility = () => {
  if (document.hidden) {
    running = false
    if (raf) cancelAnimationFrame(raf)
    raf = 0
  } else {
    running = true
    wake()
  }
}

onMounted(() => {
  canvas = canvasRef.value
  ctx = canvas?.getContext('2d')
  if (!ctx) return
  const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
  reduced = mq.matches
  running = !reduced

  if (layout()) {
    if (reduced) still()
    else wake()
  }

  ro = new ResizeObserver(() => {
    if (layout()) {
      if (reduced) still()
      else wake()
    }
  })
  ro.observe(canvas)

  io = new IntersectionObserver((entries) => {
    visible = entries[entries.length - 1].isIntersecting
    if (!visible) {
      running = false
      if (raf) cancelAnimationFrame(raf)
      raf = 0
    } else if (!reduced) {
      running = true
      wake()
    }
  })
  io.observe(canvas)

  window.addEventListener('pointermove', onPointerMove, { passive: true })
  document.addEventListener('visibilitychange', onVisibility)
})

onBeforeUnmount(() => {
  running = false
  if (raf) cancelAnimationFrame(raf)
  raf = 0
  ro?.disconnect()
  io?.disconnect()
  window.removeEventListener('pointermove', onPointerMove)
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<style scoped>
.computer-eye {
  display: block;
  width: 100%;
  height: 100%;
  cursor: crosshair;
}
</style>

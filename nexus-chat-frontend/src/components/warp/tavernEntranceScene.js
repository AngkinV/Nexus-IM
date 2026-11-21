import { createFrameGate } from '../../utils/frameGate.js'
/**
 * Tavern entrance scene: the picture that plays when the user walks into the tavern.
 *
 * A distant mountain range is drawn over the app as a line drawing, washed in ink and
 * then in colour; the finished sheet is crumpled into a paper ball and thrown away,
 * leaving the tavern, which the app switched to while the picture covered the screen.
 *
 * Everything is procedural (no image assets): Canvas 2D paints the picture, WebGL2
 * crumples it. Baking runs in small slices so it never blocks a frame.
 *
 * @param {HTMLCanvasElement} cv    2D canvas, fullscreen
 * @param {HTMLCanvasElement} glcv  WebGL canvas, fullscreen, stacked above `cv`
 * @param {{ onCommit?: () => void, onDone?: (reason: 'done' | 'skipped' | 'cancelled') => void }} hooks
 *   onCommit fires once the picture covers the app (0.62s): switch the view now.
 *   onDone fires when the stage is clear again.
 */
export function createTavernEntranceScene(cv, glcv, hooks = {}) {
  // ── Helpers ──────────────────────────────────────────────────────────────────
  const TAU = Math.PI * 2
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v)
  const lerp = (a, b, t) => a + (b - a) * t
  const ss = (a, b, v) => {
    const t = clamp((v - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)
  }
  const mod = (a, n) => ((a % n) + n) % n
  const hex = (h) => [
    parseInt(h.slice(1, 3), 16) / 255,
    parseInt(h.slice(3, 5), 16) / 255,
    parseInt(h.slice(5, 7), 16) / 255
  ]
  const mk = (w, h) => {
    const c = document.createElement('canvas')
    c.width = w
    c.height = h
    return c
  }
  function mulberry32(seed) {
    let a = seed >>> 0
    return () => {
      a = (a + 0x6d2b79f5) >>> 0
      let t = a
      t = Math.imul(t ^ (t >>> 15), t | 1)
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296
    }
  }
  function h2(x, y) {
    let h = (Math.imul(x, 374761393) + Math.imul(y, 668265263)) | 0
    h = Math.imul(h ^ (h >>> 13), 1274126177)
    return (h ^ (h >>> 16)) >>> 0
  }
  const GX = [1, -1, 1, -1, 1, -1, 0, 0],
    GY = [1, 1, -1, -1, 0, 0, 1, -1]
  // Gradient noise, periodic in x with an integer period `px`, so the scroll tiles seamlessly.
  function pn(x, y, px) {
    const xi = Math.floor(x),
      yi = Math.floor(y),
      xf = x - xi,
      yf = y - yi
    const x0 = ((xi % px) + px) % px,
      x1 = x0 + 1 === px ? 0 : x0 + 1,
      y1 = yi + 1
    const u = xf * xf * xf * (xf * (xf * 6 - 15) + 10),
      v = yf * yf * yf * (yf * (yf * 6 - 15) + 10)
    const a = h2(x0, yi) & 7,
      b = h2(x1, yi) & 7,
      c = h2(x0, y1) & 7,
      d = h2(x1, y1) & 7
    const n00 = GX[a] * xf + GY[a] * yf,
      n10 = GX[b] * (xf - 1) + GY[b] * yf
    const n01 = GX[c] * xf + GY[c] * (yf - 1),
      n11 = GX[d] * (xf - 1) + GY[d] * (yf - 1)
    const nx0 = n00 + (n10 - n00) * u,
      nx1 = n01 + (n11 - n01) * u
    return nx0 + (nx1 - nx0) * v
  }

  // ── Palettes: [0] for a dark shell, [1] for a light shell ────────────────────
  const PAL = [
    {
      // dark
      sky0: '#0A0D12',
      sky1: '#10171F',
      sky2: '#131B24',
      glow: ['rgba(244,247,245,.085)', 'rgba(244,211,94,.07)'],
      glow0: ['rgba(244,247,245,0)', 'rgba(244,211,94,0)'],
      water: [11, 17, 24],
      mist: [205, 219, 224],
      mistA: 0.27,
      ink: '5,7,10',
      cunS: '142,184,190',
      gold: '244,211,94',
      goldA: 0.85,
      dots: ['5,7,10', '82,183,136', '255,143,163'],
      seamL: '255,226,130',
      seamC: '255,246,214',
      vig: '2,4,8',
      vigA: 0.5,
      tint: 'rgba(19,34,52,.07)',
      hair: '244,247,245',
      shoreA: 0.42,
      g0: hex('#1E5C50'),
      g1: hex('#52B788'),
      G1: hex('#74C69D'),
      Gd: hex('#17463F'),
      P1: hex('#F26D85'),
      P2: hex('#FF8FA3'),
      Y1: hex('#F4D35E'),
      Y2: hex('#FFE17D'),
      Wt: hex('#F4F7F5'),
      sh0: hex('#0D2331'),
      sh1: hex('#1B414D'),
      paper: hex('#131B24'),
      haze: hex('#16232E'),
      far0: hex('#1A2A36'),
      far1: hex('#294350')
    },
    {
      // light
      sky0: '#EAE4D7',
      sky1: '#E2DBCB',
      sky2: '#DCD3C1',
      glow: ['rgba(250,248,245,.55)', 'rgba(233,196,106,.16)'],
      glow0: ['rgba(250,248,245,0)', 'rgba(233,196,106,0)'],
      water: [199, 202, 188],
      mist: [250, 248, 245],
      mistA: 0.8,
      ink: '30,36,43',
      cunS: '30,36,43',
      gold: '196,150,62',
      goldA: 0.35,
      dots: ['30,36,43', '64,145,108', '227,93,117'],
      seamL: '226,178,84',
      seamC: '176,120,40',
      vig: '92,74,50',
      vigA: 0.24,
      tint: 'rgba(214,190,150,.06)',
      hair: '250,248,245',
      shoreA: 0.9,
      g0: hex('#40916C'),
      g1: hex('#74C69D'),
      G1: hex('#A9DBBF'),
      Gd: hex('#2E6E58'),
      P1: hex('#E35D75'),
      P2: hex('#F598A8'),
      Y1: hex('#E9C46A'),
      Y2: hex('#F4D35E'),
      Wt: hex('#FAF8F5'),
      sh0: hex('#55767A'),
      sh1: hex('#7C9A96'),
      paper: hex('#EAE4D7'),
      haze: hex('#DDD7C8'),
      far0: hex('#BFC3B6'),
      far1: hex('#A9B6AC')
    }
  ]
  for (const p of PAL) {
    const mx3 = (a, b, t) => [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)]
    p.rim = mx3(p.Wt, p.Y2, 0.35)
    p.pale = mx3(p.P2, p.Wt, 0.4)
    p.cream = mx3(p.Y2, p.Wt, 0.3)
  }

  // ── Mountains (logical units: one scroll is 1536 × 320, y = 320 is the shore) ─
  const U = 1536,
    RN = U * 2,
    LH = 320
  const LAY = [
    {
      k: 0,
      far: true,
      par: 0.15,
      F: 0.22,
      P: 5,
      seed: 3.1,
      hi: 58,
      lo: 230,
      gam: 1.4,
      eps: 0.05,
      oct: 4,
      skew: 0.45,
      alpha: 0.62,
      fade0: 150,
      fade1: 266,
      lineW: 0.55
    },
    {
      k: 1,
      par: 0.4,
      F: 0.5,
      P: 3,
      seed: 8.7,
      hi: 20,
      lo: 238,
      gam: 1.35,
      eps: 0.035,
      oct: 6,
      skew: 0.6,
      alpha: 0.97,
      fade0: 214,
      fade1: 302,
      PA: 11,
      slant: 0.32,
      fy: 230,
      rimD: 9,
      lineW: 1,
      haze: 0.1,
      fk: 110,
      minCh: 12
    },
    {
      k: 2,
      par: 0.6,
      F: 0.74,
      P: 4,
      seed: 15.3,
      hi: 98,
      lo: 268,
      gam: 1.2,
      eps: 0.035,
      oct: 6,
      skew: -0.55,
      alpha: 0.97,
      fade0: 252,
      fade1: 316,
      PA: 13,
      slant: -0.26,
      fy: 200,
      rimD: 8,
      lineW: 1,
      haze: 0.05,
      fk: 110,
      minCh: 10
    },
    {
      k: 3,
      par: 0.8,
      F: 1,
      P: 6,
      seed: 23.9,
      hi: 184,
      lo: 300,
      gam: 1.1,
      eps: 0.045,
      oct: 5,
      skew: 0.5,
      alpha: 0.95,
      fade0: 286,
      fade1: 322,
      PA: 16,
      slant: 0.3,
      fy: 150,
      rimD: 6,
      lineW: 0.95,
      haze: 0,
      fk: 110,
      minCh: 7
    }
  ]
  const MIST = [
    {
      mist: true,
      y0: 148,
      h: 80,
      F: 0.34,
      par: 0.25,
      sp: 2.2,
      seed: 2.1,
      am: 1,
      amp: [9, 6, 3],
      k: 2,
      bias: 0.02,
      th: 58
    },
    {
      mist: true,
      y0: 204,
      h: 72,
      F: 0.62,
      par: 0.5,
      sp: -1.5,
      seed: 6.4,
      am: 0.9,
      amp: [8, 5, 3],
      k: 3,
      bias: -0.05,
      th: 52
    },
    {
      mist: true,
      y0: 262,
      h: 56,
      F: 0.86,
      par: 0.7,
      sp: 1.1,
      seed: 9.9,
      am: 0.8,
      amp: [6, 4, 2],
      k: 2,
      bias: 0.08,
      th: 40
    }
  ]
  const STACK = [LAY[0], MIST[0], LAY[1], MIST[1], LAY[2], MIST[2], LAY[3]]
  for (const it of STACK) {
    it.atlas = [null, null]
    it.t0 = 0
  }

  // Ridged multifractal under a range envelope, skewed so peaks, spurs and slopes are asymmetric.
  function buildRidge(L) {
    const raw = new Float32Array(RN)
    let mn = 1e9,
      mx = -1e9
    for (let i = 0; i < RN; i++) {
      const x = i / RN
      const sk = x + (L.skew * Math.sin(TAU * (L.P * x + L.seed))) / (TAU * L.P) + 0.012 * pn(x * 6, L.seed + 3.7, 6)
      const e0 = pn(x * 3 + L.seed, L.seed * 1.31 + 0.5, 3) * 1.15 + pn(x * 7, L.seed * 2.17 + 9.5, 7) * 0.5
      const E = ss(-0.38, 0.42, e0)
      let f = L.P,
        a = 1,
        w = 1,
        sum = 0,
        nrm = 0
      for (let o = 0; o < L.oct; o++) {
        const n = pn(sk * f, L.seed * 5.3 + o * 13.7, f) * 1.9
        let r = 1 - Math.sqrt(n * n + L.eps)
        if (r < 0) r = 0
        r = Math.pow(r, 1.5) * w
        w = clamp(r * 1.8, 0, 1)
        sum += r * a
        nrm += a
        a *= 0.5
        f *= 2
      }
      const v = E * (0.2 + (0.8 * sum) / nrm) + 0.1 * pn(x * 5, L.seed + 41.5, 5) + 0.05 * E
      raw[i] = v
      if (v < mn) mn = v
      if (v > mx) mx = v
    }
    const R = new Float32Array(RN)
    for (let i = 0; i < RN; i++) R[i] = L.lo - (L.lo - L.hi) * Math.pow(clamp((raw[i] - mn) / (mx - mn), 0, 1), L.gam)
    // One soft pass: drops the high-frequency teeth, keeps the shoulders.
    const S = new Float32Array(RN)
    for (let i = 0; i < RN; i++)
      S[i] = (R[(i + RN - 2) % RN] + 2 * R[(i + RN - 1) % RN] + 3 * R[i] + 2 * R[(i + 1) % RN] + R[(i + 2) % RN]) / 9
    L.R = S
    L.Ry = new Float32Array(U)
    L.top = new Int16Array(U)
    L.sl = new Float32Array(U)
    for (let x = 0; x < U; x++) {
      L.Ry[x] = S[x * 2]
      L.top[x] = Math.max(0, Math.floor(S[x * 2]) - 2)
    }
    for (let x = 0; x < U; x++) {
      let s = 0
      for (let j = -6; j <= 6; j++) s += S[mod((x + 14 + j) * 2, RN)] - S[mod((x - 14 + j) * 2, RN)]
      L.sl[x] = s / 13 / 28
    }
  }

  // A pigment stain: a crisp, slightly pooled tide line on one side, bleeding inward on the other.
  function stain(v, th) {
    const e = v - th
    if (e <= 0) return 0
    const aa = e < 0.022 ? e / 0.022 : 1
    return aa * (1 - 0.6 * ss(0, 0.34, e)) + 0.3 * aa * (1 - ss(0, 0.03, e))
  }
  let cr = 0,
    cg = 0,
    cb = 0
  const SH = [0, 0, 0]
  const ov = (c, a) => {
    cr += (c[0] - cr) * a
    cg += (c[1] - cg) * a
    cb += (c[2] - cb) * a
  }

  // Wash for a main range: warped noise fields → light on the rock edges → several thin mineral glazes → dry brush and grain.
  function* bakeBody(L) {
    const FW = U,
      FH = LH,
      s = L.seed,
      PA = L.PA,
      fx = PA / U,
      Ry = L.Ry,
      top = L.top,
      sl = L.sl
    const N = new Float32Array(FW * FH),
      WX = new Float32Array(FW * FH),
      WY = new Float32Array(FW * FH)
    let yMin = FH
    for (let x = 0; x < FW; x++) if (top[x] < yMin) yMin = top[x]
    for (let y = yMin; y < FH; y++) {
      const row = y * FW
      for (let x = 0; x < FW; x++) {
        if (y < top[x]) continue
        const d = y - Ry[x]
        const wx = pn((x * 8) / U + s, y / 170 + s * 1.7, 8) * 30,
          wy = pn((x * 8) / U + s + 5.2, y / 170 + s * 2.9 + 11, 8) * 24
        WX[row + x] = wx
        WY[row + x] = wy
        const a = (x + L.slant * d + wx) * fx,
          b = (y + wy) / L.fy
        N[row + x] = pn(a + s * 3.1, b + s, PA) + 0.42 * pn(a * 2 + s * 1.3, b * 2 + s * 4.4, PA * 2)
      }
      if ((y & 7) === 7) yield
    }
    const imgs = [new ImageData(FW, FH), new ImageData(FW, FH)]
    for (let y = yMin; y < FH; y++) {
      const row = y * FW
      for (let x = 0; x < FW; x++) {
        if (y < top[x]) continue
        const i = row + x,
          d = y - Ry[x],
          n = N[i]
        const xl = x ? x - 1 : FW - 1,
          xr = x === FW - 1 ? 0 : x + 1
        const nl = y < top[xl] ? n : N[row + xl],
          nr = y < top[xr] ? n : N[row + xr]
        const sgn = clamp((Math.abs(nl) - Math.abs(nr)) * 0.5 * L.fk, -1, 1),
          near = Math.exp(-Math.abs(n) / 0.13)
        const macro = clamp(-sl[mod(Math.round(x - L.slant * d), FW)] * 2.4, -1, 1)
        const shadow = Math.max(0, -sgn) * near,
          hl = Math.max(0, sgn) * near,
          lit = 1 - shadow
        const qx = x + L.slant * d * 0.5 + WX[i] * 1.5,
          qy = y * 0.45 + d * 0.55 + WY[i] * 1.5
        const ax = (x + L.slant * d) * 0.75
        const sp = pn(ax + s, y * 0.2 + s, 1152) + 0.5 * pn(ax * 2 + s, y * 0.42 + s * 2, 2304)
        const dt = pn((qx * 38) / U + s * 3, qy / 19 + s * 2, 38)
        const gA =
          pn((qx * 6) / U + s * 2, qy / 120 + s, 6) +
          0.5 * pn((qx * 12) / U + s, qy / 60 + s * 3, 12) +
          0.13 * dt +
          0.014 * sp
        const gB =
          pn((qx * 5) / U + s * 7, qy / 100 + s * 5, 5) +
          0.5 * pn((qx * 10) / U + s * 2, qy / 50 + s * 9, 10) -
          0.12 * dt +
          0.012 * sp
        const gC =
          pn((qx * 9) / U + s * 4, qy / 70 + s * 6, 9) +
          0.5 * pn((qx * 18) / U + s * 8, qy / 35 + s * 2, 18) +
          0.1 * dt -
          0.012 * sp
        const hgt = clamp(1 - d / 150, 0, 1),
          rim = Math.exp(-Math.max(0, d) / L.rimD)
        const vB = gB + 0.16 * hgt + 0.02,
          vC = gC + 0.22 * rim
        const dry = ss(0.3, 0.5, sp) * lit * (1 - ss(0, 0.1, Math.abs(vB - 0.12))) * ss(6, 30, d)
        const grain = 1 + ((h2(x, y) & 255) / 255 - 0.5) * 0.08
        const s1 = stain(gA, 0.06),
          s2 = stain(-gA, 0.1),
          s3 = stain(vB, 0.12),
          s4 = stain(vB, 0.2),
          s5 = stain(vB, 0.3)
        const s6 = stain(vC, 0.2),
          s7 = stain(-gC + 0.3 * rim, 0.22)
        const foot = ss(L.fade0 - 44, L.fade1, y),
          mP = ss(0.12, 0.34, vB),
          mY = ss(0.2, 0.42, vC)
        const A = L.alpha * (1 - ss(L.fade0, L.fade1, y + gA * 26))
        for (let t = 0; t < 2; t++) {
          const p = PAL[t],
            k = clamp(0.4 + 0.9 * gA + 0.3 * hgt, 0, 1)
          cr = p.g0[0] + (p.g1[0] - p.g0[0]) * k
          cg = p.g0[1] + (p.g1[1] - p.g0[1]) * k
          cb = p.g0[2] + (p.g1[2] - p.g0[2]) * k
          ov(p.G1, 0.26 * s1)
          ov(p.Gd, 0.22 * s2)
          ov(p.pale, 0.8 * mP)
          ov(p.P1, 0.45 * s3)
          ov(p.P2, 0.3 * s4)
          ov(p.P1, 0.16 * s5)
          ov(p.cream, 0.5 * mY)
          ov(p.Y1, 0.3 * s6)
          ov(p.Wt, 0.26 * s7)
          ov(p.rim, 0.55 * rim * (0.55 + 0.45 * Math.max(0, macro)))
          ov(p.rim, 0.22 * hl * (0.4 + 0.6 * hgt))
          const k2 = clamp(0.5 + gA, 0, 1)
          SH[0] = lerp(p.sh0[0], p.sh1[0], k2)
          SH[1] = lerp(p.sh0[1], p.sh1[1], k2)
          SH[2] = lerp(p.sh0[2], p.sh1[2], k2)
          ov(SH, (0.62 * shadow * (0.45 + 0.55 * hgt) + 0.2 * Math.max(0, -macro)) * (1 - foot))
          ov(p.paper, 0.4 * dry)
          ov(p.haze, L.haze + 0.25 * foot)
          const o = i * 4,
            D = imgs[t].data
          D[o] = cr * grain * 255
          D[o + 1] = cg * grain * 255
          D[o + 2] = cb * grain * 255
          D[o + 3] = A * 255
        }
      }
      if ((y & 7) === 7) yield
    }
    L.field = imgs.map((im) => {
      const c = mk(FW, FH)
      c.getContext('2d').putImageData(im, 0, 0)
      return c
    })
    // Rock edges: trace the zero contour of the noise, which is exactly where light meets shade.
    const res = []
    let act = []
    for (let y = yMin; y < FH; y++) {
      const row = y * FW,
        found = []
      for (let x = 0; x < FW; x++) {
        const xr = x === FW - 1 ? 0 : x + 1
        if (y < top[x] + 2 || y < top[xr] + 2) continue
        const a = N[row + x],
          b = N[row + xr]
        if (a < 0 !== b < 0) found.push(x + a / (a - b))
      }
      const next = []
      for (const c of act) {
        let best = -1,
          bd = 3.2,
          bdx = 0
        const lx = mod(c.lx, FW)
        for (let j = 0; j < found.length; j++) {
          const f = found[j]
          if (f < 0) continue
          let dx = f - lx
          if (dx > FW / 2) dx -= FW
          else if (dx < -FW / 2) dx += FW
          if (Math.abs(dx) < bd) {
            bd = Math.abs(dx)
            best = j
            bdx = dx
          }
        }
        if (best >= 0) {
          c.lx += bdx
          c.p.push(c.lx, y)
          found[best] = -1
          next.push(c)
        } else if (c.p.length >= 2 * L.minCh) res.push(c.p)
      }
      for (const f of found) if (f >= 0) next.push({ lx: f, p: [f, y] })
      act = next
      if ((y & 31) === 31) yield
    }
    for (const c of act) if (c.p.length >= 2 * L.minCh) res.push(c.p)
    L.S = buildStrokes(L, res, N)
  }

  // The farthest range: a thin, low-contrast wash only.
  function* bakeFar(L) {
    const FW = 768,
      FH = 160,
      imgs = [new ImageData(FW, FH), new ImageData(FW, FH)],
      s = L.seed
    for (let y = 0; y < FH; y++) {
      for (let x = 0; x < FW; x++) {
        const Y = y * 2,
          ry = L.R[x * 4]
        if (Y < ry - 3) continue
        const d = Y - ry
        const gA = pn((x * 6) / FW + s, Y / 90 + s, 6) + 0.5 * pn((x * 12) / FW + s, Y / 45 + 3, 12)
        const macro = clamp(-L.sl[x * 2] * 2.4, -1, 1),
          rim = Math.exp(-Math.max(0, d) / 7)
        const A = L.alpha * (1 - ss(L.fade0, L.fade1, Y + gA * 20))
        for (let t = 0; t < 2; t++) {
          const p = PAL[t],
            k = clamp(0.5 + gA + 0.25 * macro, 0, 1)
          cr = lerp(p.far0[0], p.far1[0], k)
          cg = lerp(p.far0[1], p.far1[1], k)
          cb = lerp(p.far0[2], p.far1[2], k)
          ov(p.P1, 0.1 * stain(gA, 0.12))
          ov(p.G1, 0.12 * stain(-gA, 0.06))
          ov(p.Wt, 0.22 * rim * Math.max(0, macro))
          const o = (y * FW + x) * 4,
            D = imgs[t].data
          D[o] = cr * 255
          D[o + 1] = cg * 255
          D[o + 2] = cb * 255
          D[o + 3] = A * 255
        }
      }
      if ((y & 15) === 15) yield
    }
    L.field = imgs.map((im) => {
      const c = mk(FW, FH)
      c.getContext('2d').putImageData(im, 0, 0)
      return c
    })
    L.S = buildStrokes(L, [])
  }

  // Stroke data: ridge lines, gold lines, rock edges, texture strokes, moss dots. Independent of palette and resolution; computed once.
  function buildStrokes(L, chs, N) {
    const R = L.R,
      rnd = mulberry32(Math.floor(L.seed * 1000) + 17)
    const S = { ridge: [], gold: [], arete: [], cun: [], dots: [] }
    const Rat = (u) => {
      const f = mod(u, U) * 2,
        i = Math.floor(f),
        t = f - i
      return R[i] + (R[(i + 1) % RN] - R[i]) * t
    }
    const cluster = (x, y, m, spread, sc) => {
      for (let q = 0; q < m; q++) {
        const v = rnd()
        S.dots.push(
          x + (rnd() + rnd() + rnd() - 1.5) * spread * 1.3,
          y + (rnd() + rnd() - 1) * spread * 0.5,
          (0.45 + rnd() * rnd() * 0.72) * sc,
          v < 0.4 ? 0 : v < 0.75 ? 1 : 2,
          0.5 + rnd() * 0.45
        )
      }
    }
    // Ridge lines: a small dab at the start, hair-thin travel, a little weight on turns, a fading end. Low ridges are sometimes left undrawn.
    let i = Math.floor(rnd() * 60)
    const end = RN + i
    while (i < end - 8) {
      let len = Math.floor((80 + rnd() * 320) * 2)
      if (i + len > end) len = end - i
      const n = len + 1,
        P = new Float32Array(n * 2),
        Wd = new Float32Array(n)
      let ym = 0
      for (let j = 0; j < n; j++) {
        const ii = i + j,
          u = ii / 2,
          y = R[ii % RN]
        ym += y
        const t = j / (n - 1),
          k = Math.abs(R[(ii + 6) % RN] - 2 * y + R[(ii + RN - 6) % RN])
        const press = 0.6 + 0.22 * pn(u * 0.03, L.seed + 9.1, 65536) + 0.5 * clamp(k * 0.8, 0, 1)
        const tin = ss(0, 0.03, t),
          dab = 0.5 * Math.exp(-Math.pow((t - 0.018) / 0.016, 2)),
          tout = 1 - Math.pow(ss(0.68, 1, t), 1.5)
        Wd[j] = clamp((press + dab) * tin * tout, 0, 1.3) * L.lineW
        P[j * 2] = u
        P[j * 2 + 1] = y + 0.28 * pn(u * 0.19, L.seed + 3.3, 65536)
      }
      ym /= n
      if (!(ym > L.lo - 12 && rnd() < 0.5))
        S.ridge.push({ P, Wd, n, a: L.far ? 0.55 : lerp(0.42, 1, ss(L.lo - 4, L.lo - 70, ym)) })
      i += len + (rnd() < 0.38 ? Math.floor((3 + rnd() * 18) * 2) : -2)
    }
    // Moss dots, clustered along ridges and in the saddles.
    for (let u = rnd() * 20; u < U; u += 4 + rnd() * 17) {
      const y = Rat(u),
        dens = ss(-0.2, 0.3, pn((u * 14) / U + L.seed, 7.7, 14)),
        conc = clamp((y - (Rat(u - 12) + Rat(u + 12)) / 2) * 0.3, 0, 1)
      if (rnd() >= (L.far ? 0.12 : 0.26 + 0.5 * dens + 0.5 * conc)) continue
      const m = L.far ? 1 + Math.floor(rnd() * 3) : 2 + Math.floor(rnd() * 9 * (0.4 + dens))
      for (let q = 0; q < m; q++) {
        const xx = u + (rnd() + rnd() + rnd() - 1.5) * 9
        cluster(xx, Rat(xx) + rnd() * 3 - 0.4, 1, 0, L.far ? 0.7 : 1)
      }
    }
    if (L.far) return S
    // Gold lines, only on high ridges that face the light.
    let run = -1
    for (let h = 0; h <= RN; h++) {
      const ok = h < RN && L.sl[(h >> 1) % U] < -0.1 && R[h % RN] < L.lo - 26
      if (ok) {
        if (run < 0) run = h
        continue
      }
      if (run >= 0 && h - run > 44 && rnd() < 0.8) {
        const a0 = run + 6,
          n = h - 4 - a0,
          P = new Float32Array(n * 2),
          Wd = new Float32Array(n)
        for (let j = 0; j < n; j++) {
          const t = j / (n - 1)
          P[j * 2] = (a0 + j) / 2
          P[j * 2 + 1] = R[(a0 + j) % RN]
          Wd[j] = 0.58 * ss(0, 0.12, t) * (1 - ss(0.7, 1, t))
        }
        S.gold.push({ P, Wd, n, a: 0.6 + rnd() * 0.4 })
      }
      run = -1
    }
    for (const ch of chs) {
      const n0 = ch.length / 2,
        xs = new Float32Array(n0)
      for (let j = 0; j < n0; j++) {
        let a = 0,
          c = 0
        for (let q = -2; q <= 2; q++) {
          const jj = j + q
          if (jj >= 0 && jj < n0) {
            a += ch[jj * 2]
            c++
          }
        }
        xs[j] = a / c
      }
      const x0 = ch[0],
        prim = ch[1] - Rat(x0) < 7 && n0 > 26
      if (!prim && rnd() < 0.35) continue
      const n = Math.max(6, Math.floor(n0 * (0.55 + 0.45 * rnd()))),
        P = new Float32Array(n * 2),
        Wd = new Float32Array(n)
      for (let j = 0; j < n; j++) {
        const t = j / (n - 1)
        P[j * 2] = xs[j] + 0.25 * pn(j * 0.21, x0, 65536)
        P[j * 2 + 1] = ch[j * 2 + 1]
        Wd[j] =
          L.lineW *
          (prim ? 0.95 : 0.7) *
          (1 - 0.55 * t) *
          ss(0, 0.06, t) *
          (1 - Math.pow(ss(0.6, 1, t), 1.3)) *
          (0.85 + 0.3 * pn(j * 0.07, x0 + 5, 65536))
      }
      S.arete.push({ P, Wd, n, a: prim ? 0.8 : 0.5 })
      // Texture strokes: short groups on the shaded side, long and soft mostly, short and angular where it is steep.
      for (let j = 3 + Math.floor(rnd() * 6); j < n0 - 4; j += 3 + Math.floor(rnd() * 6)) {
        const x = xs[j],
          y = ch[j * 2 + 1],
          dens = ss(-0.2, 0.28, pn((x * 10) / U + L.seed, y / 55 + L.seed, 10))
        if (rnd() > 0.25 + 0.7 * dens) continue
        const base = Math.atan2(6, xs[Math.min(n0 - 1, j + 3)] - xs[Math.max(0, j - 3)])
        const side = rnd() < 0.78 ? 1 : -1,
          axe = rnd() < 0.36,
          m = side > 0 ? 3 + Math.floor(rnd() * 5) : 1 + Math.floor(rnd() * 2.4)
        for (let q = 0; q < m; q++) {
          const sx0 = x + side * (1.5 + q * (1.7 + rnd() * 1.5) + rnd()),
            sy0 = y + (rnd() - 0.5) * 5 + q * 1.2
          const ang = base + (axe ? -side * (0.5 + rnd() * 0.45) : (rnd() - 0.5) * 0.24)
          const len = axe ? 5 + rnd() * 8 : 9 + rnd() * 16,
            bow = axe ? 0 : (rnd() - 0.5) * 2.4,
            dx = Math.cos(ang),
            dy = Math.sin(ang)
          const P2 = new Float32Array(10),
            W2 = new Float32Array(5)
          for (let e = 0; e < 5; e++) {
            const t = e / 4,
              b = Math.sin(Math.PI * t) * bow
            P2[e * 2] = sx0 + dx * len * t - dy * b
            P2[e * 2 + 1] = sy0 + dy * len * t + dx * b
            W2[e] = (axe ? 0.85 - 0.6 * t : 0.42 + 0.3 * Math.sin(Math.PI * t)) * (0.8 + 0.4 * rnd())
          }
          S.cun.push({ P: P2, Wd: W2, n: 5, a: 0.15 + rnd() * 0.2, side })
        }
      }
      if (prim || rnd() < 0.3) {
        const cnt = 1 + Math.floor(rnd() * 2)
        for (let q = 0; q < cnt; q++) {
          const j = Math.floor(rnd() * n0 * 0.6)
          cluster(xs[j], ch[j * 2 + 1], 2 + Math.floor(rnd() * 4), 3.2, 1)
        }
      }
    }
    // Hemp-fibre strokes: groups of fine lines that follow the rock face, densest on the shaded side of an edge.
    const at = (x, y) => N[y * U + mod(x, U)]
    for (let q = 0; q < 560; q++) {
      const sx0 = rnd() * U,
        xi = Math.floor(sx0),
        sy0 = L.Ry[xi] + 6 + Math.pow(rnd(), 1.3) * 120
      if (sy0 > L.fade0 + 6) continue
      const yi = Math.floor(sy0)
      if (yi >= LH - 3 || Math.abs(at(xi, yi)) > 0.3) continue
      const shade = Math.abs(at(xi + 2, yi)) > Math.abs(at(xi - 2, yi))
      if (rnd() > (shade ? 0.95 : 0.22)) continue
      const m = shade ? 2 + Math.floor(rnd() * 4) : 1 + Math.floor(rnd() * 2),
        gap = 1.5 + rnd() * 1.4,
        step = 2.4 + rnd() * 2
      for (let k = 0; k < m; k++) {
        let x = sx0 + k * gap * (shade ? 1 : -1),
          y = sy0 + k * 0.8 + (rnd() - 0.5) * 3,
          cnt = 0
        const P2 = new Float32Array(12),
          W2 = new Float32Array(6)
        for (; cnt < 6; cnt++) {
          const X = Math.round(x),
            Y = Math.round(y)
          if (Y >= LH - 3 || Y < L.top[mod(X, U)] + 4 || Y < L.top[mod(X + 2, U)] + 4 || Y < L.top[mod(X - 2, U)] + 4)
            break
          P2[cnt * 2] = x
          P2[cnt * 2 + 1] = y
          let tx = at(X, Y - 2) - at(X, Y + 2),
            ty = at(X + 2, Y) - at(X - 2, Y)
          const l = Math.hypot(tx, ty)
          if (l < 1e-5) {
            cnt++
            break
          }
          if (ty < 0) {
            tx = -tx
            ty = -ty
          }
          x += (tx / l) * step
          y += (ty / l) * step
        }
        if (cnt < 4) continue
        for (let e = 0; e < cnt; e++) W2[e] = (0.42 + 0.3 * Math.sin((Math.PI * e) / (cnt - 1))) * (0.8 + 0.4 * rnd())
        S.cun.push({
          P: P2.subarray(0, cnt * 2),
          Wd: W2.subarray(0, cnt),
          n: cnt,
          a: 0.15 + rnd() * 0.2,
          side: shade ? 1 : -1
        })
      }
    }
    return S
  }

  // A stroke of varying width: both edges are traced and the shape filled, so width varies per point and edges stay sharp.
  const TL = new Float32Array(8192)
  function ribbon(c, P, Wd, n, sx, sy, wk, ox, oy) {
    if (n < 2) return
    c.beginPath()
    for (let i = 0; i < n; i++) {
      const a = i ? i - 1 : 0,
        b = i < n - 1 ? i + 1 : n - 1
      let tx = (P[b * 2] - P[a * 2]) * sx,
        ty = (P[b * 2 + 1] - P[a * 2 + 1]) * sy
      const l = Math.hypot(tx, ty) || 1
      tx /= l
      ty /= l
      const hw = Math.max(0.06, Wd[i] * wk * 0.5),
        x = P[i * 2] * sx + ox,
        y = P[i * 2 + 1] * sy + oy
      TL[i * 2] = x + ty * hw
      TL[i * 2 + 1] = y - tx * hw
      if (i) c.lineTo(x - ty * hw, y + tx * hw)
      else c.moveTo(x - ty * hw, y + tx * hw)
    }
    for (let i = n - 1; i >= 0; i--) c.lineTo(TL[i * 2], TL[i * 2 + 1])
    c.closePath()
    c.fill()
  }
  // Strokes that cross the seam are drawn a second time, so lines do not break where the scroll repeats.
  function strokes(c, list, sx, sy, wk, oy, aMul) {
    const span = U * sx
    for (const s of list) {
      c.globalAlpha = Math.min(1, s.a * aMul)
      const xa = s.P[0],
        xb = s.P[(s.n - 1) * 2],
        lo = Math.min(xa, xb),
        hi = Math.max(xa, xb)
      ribbon(c, s.P, s.Wd, s.n, sx, sy, wk, 0, oy)
      if (hi > U - 30) ribbon(c, s.P, s.Wd, s.n, sx, sy, wk, -span, oy)
      if (lo < 30) ribbon(c, s.P, s.Wd, s.n, sx, sy, wk, span, oy)
    }
    c.globalAlpha = 1
  }

  // Mist: a silk band with a clean upper edge and a dissolving lower edge, with two fine pleats.
  function* bakeMist(M, ppu) {
    const AW = Math.round(U * ppu),
      BH = Math.round(M.h * ppu)
    const top = new Float32Array(AW),
      thk = new Float32Array(AW),
      al = new Float32Array(AW)
    for (let X = 0; X < AW; X++) {
      const u = X / AW
      top[X] =
        (M.h * 0.3 +
          M.amp[0] * Math.sin(TAU * (u + M.seed)) +
          M.amp[1] * Math.sin(TAU * (2 * u + M.seed * 1.7)) +
          M.amp[2] * Math.sin(TAU * (3 * u + M.seed * 2.9))) *
        ppu
      const p = ss(-0.1, 0.5, 0.8 * Math.sin(TAU * (M.k * u + M.seed * 0.7)) + 0.9 * pn(u * 5, M.seed, 5) + M.bias)
      thk[X] = Math.max(0.001, M.th * ppu * (0.25 + 0.75 * p) * Math.pow(p, 0.3))
      al[X] = M.am * ss(0, 0.3, p)
    }
    const imgs = [new ImageData(AW, BH), new ImageData(AW, BH)],
      eg = 1.6 * ppu,
      fw = 0.7 * ppu
    for (let Y = 0; Y < BH; Y++) {
      for (let X = 0; X < AW; X++) {
        const a = al[X]
        if (a <= 0) continue
        const yy = Y - top[X]
        if (yy < -1) continue
        let v
        if (yy < 0) v = a * (yy + 1) * 0.95
        else {
          const t = yy / thk[X]
          if (t >= 1) continue
          v = a * (Math.pow(1 - t, 1.7) * 0.62 + (yy < eg ? 0.38 * (1 - yy / eg) : 0))
          const d1 = Math.abs(yy - 0.22 * thk[X]),
            d2 = Math.abs(yy - 0.47 * thk[X])
          if (d1 < fw) v += a * 0.1 * (1 - d1 / fw) * 0.78
          if (d2 < fw) v += a * 0.1 * (1 - d2 / fw) * 0.53
        }
        const o = (Y * AW + X) * 4
        for (let t = 0; t < 2; t++) {
          const D = imgs[t].data,
            m = PAL[t].mist
          D[o] = m[0]
          D[o + 1] = m[1]
          D[o + 2] = m[2]
          D[o + 3] = Math.min(1, v * PAL[t].mistA) * 255
        }
      }
      if ((Y & 31) === 31) yield
    }
    for (let t = 0; t < 2; t++) {
      const c = mk(AW, BH)
      c.getContext('2d').putImageData(imgs[t], 0, 0)
      M.atlas[t] = c
    }
  }

  // ── Canvases and background baking ───────────────────────────────────────────
  const g = cv.getContext('2d')
  const up = mk(1, 1),
    upx = up.getContext('2d'),
    tmp = mk(1, 1),
    tmpx = tmp.getContext('2d'),
    wb = mk(1, 1),
    wbx = wb.getContext('2d', { alpha: false })
  let W = 0,
    H = 0,
    dpr = 1,
    Wpx = 0,
    Hpx = 0,
    waterY = 0,
    PU = 1,
    sCss = 1,
    tileW = 0,
    layH = 0
  let th = 0,
    pano = null,
    wantPano = '',
    crease = null,
    wantCrease = '',
    fieldsQueued = false,
    disposed = false
  const queue = []
  let pumping = false,
    budget = 6
  function pump() {
    if (disposed) {
      queue.length = 0
      pumping = false
      return
    }
    const t0 = performance.now()
    while (queue.length && performance.now() - t0 < budget) if (queue[0].next().done) queue.shift()
    if (queue.length) setTimeout(pump, 0)
    else pumping = false
  }
  function enqueue(gen) {
    queue.push(gen)
    if (!pumping) {
      pumping = true
      setTimeout(pump, 0)
    }
  }
  // Ridge shapes and washes are computed once, for both palettes. They are small and stay in memory.
  function* bakeFields() {
    for (const L of LAY) {
      buildRidge(L)
      yield
    }
    for (const L of LAY) {
      yield* L.far ? bakeFar(L) : bakeBody(L)
      yield
    }
  }
  function prepare() {
    if (!fieldsQueued && !disposed) {
      fieldsQueued = true
      enqueue(bakeFields())
    }
  }
  function measure() {
    W = window.innerWidth
    H = window.innerHeight
    dpr = Math.min(2, window.devicePixelRatio || 1)
    Wpx = Math.round(W * dpr)
    Hpx = Math.round(H * dpr)
    cv.width = Wpx
    cv.height = Hpx
    waterY = Math.round(Hpx * 0.75)
    sCss = Math.min((H * 0.39) / LH, W / 560)
    PU = sCss * dpr
    tileW = Math.round(U * PU)
    layH = Math.round(LH * PU)
    up.width = tmp.width = wb.width = Wpx
    up.height = tmp.height = waterY
    wb.height = Hpx - waterY
  }
  function tiles(c, img, y, h) {
    for (let x = 0; x < Wpx; x += tileW) c.drawImage(img, x, y, tileW, h)
  }

  // Two images per range: the finished colour, and the line drawing alone.
  function* composeLayer(L, ppu, lw, t) {
    const AW = Math.round(U * ppu),
      AH = Math.round(LH * ppu),
      sx = AW / U,
      sy = AH / LH,
      S = L.S,
      p = PAL[t]
    const colour = mk(AW, AH),
      c = colour.getContext('2d')
    c.imageSmoothingEnabled = true
    c.imageSmoothingQuality = 'high'
    c.drawImage(L.field[t], 0, 0, AW, AH)
    // The skyline is cut with a vector path, so the hard edge is truly sharp.
    c.globalCompositeOperation = 'destination-in'
    c.beginPath()
    c.moveTo(-2, AH + 2)
    for (let i = 0; i <= RN; i++) c.lineTo((i / 2) * sx, L.R[i % RN] * sy)
    c.lineTo(AW + 2, AH + 2)
    c.closePath()
    c.fill()
    yield
    if (!L.far) {
      // Strokes inside the mountain dissolve with its foot.
      c.globalCompositeOperation = 'source-atop'
      c.fillStyle = `rgb(${p.cunS})`
      strokes(
        c,
        S.cun.filter((s) => s.side > 0),
        sx,
        sy,
        lw,
        0,
        t ? 1 : 0.9
      )
      c.fillStyle = `rgb(${p.ink})`
      strokes(
        c,
        S.cun.filter((s) => s.side < 0),
        sx,
        sy,
        lw,
        0,
        1.1
      )
      strokes(c, S.arete, sx, sy, lw, 0, 1)
      yield
    }
    c.globalCompositeOperation = 'source-over'
    const D = S.dots
    for (let i = 0; i < D.length; i += 5) {
      c.fillStyle = `rgb(${p.dots[D[i + 3]]})`
      c.globalAlpha = D[i + 4] * (L.far ? 0.5 : 1)
      for (const o of D[i] < 4 ? [0, U] : D[i] > U - 4 ? [0, -U] : [0]) {
        c.beginPath()
        c.ellipse((D[i] + o) * sx, D[i + 1] * sy, D[i + 2] * lw * 1.15, D[i + 2] * lw * 0.85, 0, 0, TAU)
        c.fill()
      }
    }
    c.fillStyle = `rgb(${p.ink})`
    strokes(c, S.ridge, sx, sy, lw, 0, 1)
    c.fillStyle = `rgb(${p.gold})`
    strokes(c, S.gold, sx, sy, lw, 1.15 * lw, p.goldA)
    yield
    // Line drawing: warm white lines on a dark shell, ink lines on a light one.
    const line = mk(AW, AH),
      l = line.getContext('2d')
    l.fillStyle = t ? 'rgb(30,36,43)' : 'rgb(255,238,196)'
    strokes(l, S.ridge, sx, sy, lw * 1.1, 0, 1)
    if (!L.far) {
      strokes(l, S.arete, sx, sy, lw, 0, 0.8)
      strokes(l, S.cun, sx, sy, lw, 0, 1)
    }
    for (let i = 0; i < D.length; i += 5) {
      l.globalAlpha = D[i + 4] * (L.far ? 0.4 : 0.8)
      l.beginPath()
      l.arc(D[i] * sx, D[i + 1] * sy, D[i + 2] * lw, 0, TAU)
      l.fill()
    }
    return { colour, line }
  }
  // The whole picture at window size, as three images: line, ink, colour. Released after the transition and rebuilt on the next one.
  function* buildPano(t, key) {
    const ppu = clamp(PU, 0.75, 2),
      lw = (ppu / sCss) * clamp(sCss, 0.62, 1),
      p = PAL[t],
      y = waterY - layH
    const colour = mk(Wpx, waterY),
      cc = colour.getContext('2d'),
      line = mk(Wpx, waterY),
      lc = line.getContext('2d')
    for (const it of STACK) {
      if (key !== wantPano || disposed) return
      if (it.mist) {
        if (it.ppu !== ppu) {
          yield* bakeMist(it, ppu)
          it.ppu = ppu
        }
        tiles(cc, it.atlas[t], waterY - Math.round((LH - it.y0) * PU), Math.round(it.h * PU))
        continue
      }
      const a = yield* composeLayer(it, ppu, lw, t)
      if (it.k === 3) {
        // A thin light on the water before the nearest range; its foot dissolves into it.
        const hh = waterY * 0.17,
          lg = cc.createLinearGradient(0, waterY - hh, 0, waterY),
          m = p.mist.join(',')
        lg.addColorStop(0, `rgba(${m},0)`)
        lg.addColorStop(1, `rgba(${m},${t ? 0.5 : 0.12})`)
        cc.fillStyle = lg
        cc.fillRect(0, waterY - hh, Wpx, hh)
      }
      tiles(cc, a.colour, y, layH)
      // Each range erases the lines behind it before drawing its own, so far ranges do not show through near ones.
      lc.globalCompositeOperation = 'destination-out'
      lc.globalAlpha = 1
      tiles(lc, a.colour, y, layH)
      lc.globalCompositeOperation = 'source-over'
      lc.globalAlpha = it.far ? 0.5 : 1
      tiles(lc, a.line, y, layH)
      yield
    }
    lc.globalAlpha = 1
    lc.globalCompositeOperation = 'destination-in'
    const fg = lc.createLinearGradient(0, waterY - layH * 0.2, 0, waterY)
    fg.addColorStop(0, 'rgba(0,0,0,1)')
    fg.addColorStop(1, 'rgba(0,0,0,.12)')
    lc.fillStyle = fg
    lc.fillRect(0, 0, Wpx, waterY)
    // Ink: the colour image without its colour; one more layer of depth on a light shell.
    const ink = mk(Wpx, waterY),
      ic = ink.getContext('2d')
    ic.drawImage(colour, 0, 0)
    ic.globalCompositeOperation = 'saturation'
    ic.fillStyle = 'rgb(128,128,128)'
    ic.fillRect(0, 0, Wpx, waterY)
    if (t) {
      ic.globalCompositeOperation = 'multiply'
      ic.globalAlpha = 0.45
      ic.drawImage(ink, 0, 0)
      ic.globalAlpha = 1
    }
    ic.globalCompositeOperation = 'destination-in'
    ic.drawImage(colour, 0, 0)
    yield
    // The shoreline: a broken, hair-thin line of bare paper.
    const shore = mk(Wpx, 1),
      sc = shore.getContext('2d')
    for (let x = 0; x < Wpx; x++) {
      const u = x / tileW,
        a =
          ss(-0.28, 0.2, pn(u * 13, 5.5, 13) + 0.35 * pn(u * 46, 9.1, 46)) *
          (0.55 + 0.45 * ss(-0.3, 0.3, pn(u * 90, 2.2, 90)))
      if (a > 0.01) {
        sc.fillStyle = `rgba(255,255,255,${a})`
        sc.fillRect(x, 0, 1, 1)
      }
    }
    if (key === wantPano) pano = { key, colour, line, ink, shore }
  }

  // Silk: a weave plus mineral specks, blended with soft-light on dark and multiply on light so the pigment sits in the fibre.
  const silk = [null, null]
  ;(() => {
    const n = 384,
      rnd = mulberry32(99),
      rowJ = new Float32Array(n),
      colJ = new Float32Array(n)
    for (let i = 0; i < n; i++) {
      rowJ[i] = rnd() - 0.5
      colJ[i] = rnd() - 0.5
    }
    const im = [new ImageData(n, n), new ImageData(n, n)]
    for (let y = 0; y < n; y++)
      for (let x = 0; x < n; x++) {
        let v =
          0.04 * Math.sin((TAU * x) / 3) * Math.sin((TAU * y) / 3) +
          0.034 * rowJ[y] +
          0.034 * colJ[x] +
          0.02 * (rnd() - 0.5)
        if (rnd() < 0.006) v += (rnd() - 0.5) * 0.3
        const o = (y * n + x) * 4,
          d = clamp(128 + v * 255, 0, 255),
          l = clamp(246 + v * 215, 0, 255)
        im[0].data[o] = d
        im[0].data[o + 1] = d
        im[0].data[o + 2] = d + 2
        im[0].data[o + 3] = 255
        im[1].data[o] = l
        im[1].data[o + 1] = l - 2
        im[1].data[o + 2] = l - 9
        im[1].data[o + 3] = 255
      }
    for (let t = 0; t < 2; t++) {
      const c = mk(n, n)
      c.getContext('2d').putImageData(im[t], 0, 0)
      silk[t] = g.createPattern(c, 'repeat')
    }
  })()
  const hair = PAL.map((p) => {
    const c = mk(256, 1),
      x = c.getContext('2d'),
      gr = x.createLinearGradient(0, 0, 256, 0)
    gr.addColorStop(0, `rgba(${p.hair},0)`)
    gr.addColorStop(0.3, `rgba(${p.hair},1)`)
    gr.addColorStop(0.62, `rgba(${p.hair},.9)`)
    gr.addColorStop(1, `rgba(${p.hair},0)`)
    x.fillStyle = gr
    x.fillRect(0, 0, 256, 1)
    return c
  })
  const hairInk = (() => {
    const c = mk(256, 1),
      x = c.getContext('2d'),
      gr = x.createLinearGradient(0, 0, 256, 0)
    gr.addColorStop(0, 'rgba(30,36,43,0)')
    gr.addColorStop(0.35, 'rgba(30,36,43,1)')
    gr.addColorStop(1, 'rgba(30,36,43,0)')
    x.fillStyle = gr
    x.fillRect(0, 0, 256, 1)
    return c
  })()
  const RIP = (() => {
    const r = mulberry32(5),
      a = []
    for (let i = 0; i < 38; i++)
      a.push({
        k: Math.pow(r(), 1.7),
        ph: r(),
        sp: (r() - 0.5) * 14,
        len: 30 + r() * 190,
        a: 0.1 + r() * 0.22,
        br: i < 5
      })
    return a
  })()

  // ── Painting: line → ink → colour ────────────────────────────────────────────
  // Seconds: 0 lines grow from the origin → .62 the app behind switches to the tavern → .75 ink spreads → 1.3 colour spreads
  // → 2.35 full colour → 2.65 the sheet is crumpled → 4.55 done. Nothing moves between .62 and .75 on purpose: a stall while
  // the tavern mounts is not seen there.
  const T_COMMIT = 0.62,
    T_INK = 0.75,
    T_COL = 1.3,
    T_FULL = 2.35,
    T_CRUMPLE = 2.65,
    CRUMPLE_LEN = 1.9,
    T_END = T_CRUMPLE + CRUMPLE_LEN,
    FAST = 2
  let ox = 0,
    oy = 0
  // Spread from the origin: kept inside radius R, fading over the last f.
  function stamp(img, R, f, Rmax) {
    if (R - f >= Rmax) {
      upx.drawImage(img, 0, 0)
      return
    }
    const x = tmpx
    x.setTransform(1, 0, 0, 1, 0, 0)
    x.globalAlpha = 1
    x.globalCompositeOperation = 'source-over'
    x.clearRect(0, 0, Wpx, waterY)
    x.drawImage(img, 0, 0)
    x.globalCompositeOperation = 'destination-in'
    const gr = x.createRadialGradient(ox, oy, Math.max(0, R - f), ox, oy, Math.max(1, R))
    gr.addColorStop(0, 'rgba(0,0,0,1)')
    gr.addColorStop(1, 'rgba(0,0,0,0)')
    x.fillStyle = gr
    x.fillRect(0, 0, Wpx, waterY)
    x.globalCompositeOperation = 'source-over'
    upx.drawImage(tmp, 0, 0)
  }
  function renderWater(t) {
    const c = wbx,
      hw = wb.height,
      wc = PAL[th].water.join(',')
    c.setTransform(1, 0, 0, 1, 0, 0)
    c.globalAlpha = 1
    c.globalCompositeOperation = 'source-over'
    c.fillStyle = `rgb(${wc})`
    c.fillRect(0, 0, Wpx, hw)
    // The reflection is taken strip by strip from the upper half, shifted sideways more with depth; a second, opposite shift
    // is laid over the deeper part so it loses focus.
    const sh = Math.max(1, Math.round(dpr)),
      comp = 1.45,
      m = Math.ceil(8 * dpr)
    for (let dy = 0; dy < hw; dy += sh) {
      const k = dy / hw,
        sy = waterY - (dy + sh) * comp
      if (sy < 0) break
      const amp = (0.25 + 1.4 * k + 5.2 * k * k) * dpr,
        dx = amp * (0.7 * Math.sin(k * 26 + t * 1.25) + 0.3 * Math.sin(k * 58 - t * 2.05 + 1.7))
      c.drawImage(up, 0, sy, Wpx, sh * comp, dx - m, dy, Wpx + 2 * m, sh)
    }
    c.globalAlpha = 0.42
    for (let dy = Math.floor((hw * 0.22) / sh) * sh; dy < hw; dy += sh) {
      const k = dy / hw,
        sy = waterY - (dy + sh * 2.2) * comp
      if (sy < 0) break
      const amp = (0.25 + 1.4 * k + 5.2 * k * k) * dpr,
        dx = -amp * (0.7 * Math.sin(k * 21 - t * 1.6 + 0.9) + 0.3 * Math.sin(k * 66 + t * 2.6))
      c.drawImage(up, 0, sy, Wpx, sh * comp * 1.6, dx - m, dy, Wpx + 2 * m, sh)
    }
    c.globalAlpha = 1
    const gr = c.createLinearGradient(0, 0, 0, hw)
    gr.addColorStop(0, `rgba(${wc},.16)`)
    gr.addColorStop(0.45, `rgba(${wc},.5)`)
    gr.addColorStop(1, `rgba(${wc},.9)`)
    c.fillStyle = gr
    c.fillRect(0, 0, Wpx, hw)
    const lt = Math.max(1, Math.round(0.75 * dpr))
    for (let i = 0; i < RIP.length; i++) {
      const r = RIP[i],
        len = r.len * (0.6 + 0.9 * r.k) * (r.br ? 2.2 : 1) * dpr,
        span = Wpx + len * 2,
        x = mod(r.ph * span * 2 + t * r.sp * dpr, span) - len
      c.globalAlpha = clamp(r.a * (r.br ? 1.8 : 1) * (0.6 + 0.4 * Math.sin(t * 0.7 + r.ph * 9)), 0, 1)
      c.drawImage(th && i % 3 === 0 ? hairInk : hair[th], x, Math.round(r.k * hw * 0.96) + 3 * dpr, len, lt)
    }
    c.globalAlpha = PAL[th].shoreA
    c.drawImage(pano.shore, 0, 0, Wpx, lt)
    c.globalAlpha = 1
  }
  const smooth = (u) => {
    const v = clamp(u, 0, 1)
    return v * v * (3 - 2 * v)
  }
  function paint(t, T, aS) {
    const P = PAL[th],
      c = upx
    c.setTransform(1, 0, 0, 1, 0, 0)
    c.globalCompositeOperation = 'source-over'
    c.globalAlpha = 1
    c.clearRect(0, 0, Wpx, waterY)
    // Paper: the app first recedes into the paper colour.
    c.globalAlpha = aS
    const gr = c.createLinearGradient(0, 0, 0, waterY)
    gr.addColorStop(0, P.sky0)
    gr.addColorStop(0.66, P.sky1)
    gr.addColorStop(1, P.sky2)
    c.fillStyle = gr
    c.fillRect(0, 0, Wpx, waterY)
    for (let i = 0; i < 2; i++) {
      c.save()
      c.translate(Wpx * (i ? 0.68 : 0.3) + Wpx * 0.14 * Math.sin(t * 0.05 + i * 2.4), waterY)
      c.scale(Wpx * (i ? 0.6 : 0.75), waterY * (i ? 0.34 : 0.26))
      const rg = c.createRadialGradient(0, 0, 0, 0, 0, 1)
      rg.addColorStop(0, P.glow[i])
      rg.addColorStop(1, P.glow0[i])
      c.fillStyle = rg
      c.fillRect(-1, -1, 2, 1)
      c.restore()
    }
    c.globalAlpha = 1
    let aW = 0,
      aK = 0
    if (pano) {
      const Rmax = Math.hypot(Math.max(ox, Wpx - ox), Math.max(oy, waterY - oy)) + 2,
        lf = 90 * dpr
      const e1 = 1 - Math.pow(1 - clamp(T / 0.6, 0, 1), 3),
        ui = smooth((T - T_INK) / 0.95),
        uc = smooth((T - T_COL) / (T_FULL - T_COL))
      if (uc < 1) {
        if (ui < 1) stamp(pano.line, e1 * (Rmax + lf), lf, Rmax)
        if (ui > 0) {
          const R = ui * Rmax * 1.85
          stamp(pano.ink, R, Math.max(160 * dpr, 0.45 * R), Rmax)
        }
      }
      if (uc > 0) {
        const R = uc * Rmax * 1.85
        stamp(pano.colour, R, Math.max(160 * dpr, 0.45 * R), Rmax)
      }
      aW = ss(0.5, 1, T) * aS
      aK = ss(0.4, 0.9, T) * aS
      if (aW > 0) renderWater(t)
    }
    g.setTransform(1, 0, 0, 1, 0, 0)
    g.globalAlpha = 1
    g.globalCompositeOperation = 'source-over'
    g.clearRect(0, 0, Wpx, Hpx)
    g.drawImage(up, 0, 0)
    g.globalAlpha = aS
    g.fillStyle = `rgb(${P.water.join(',')})`
    g.fillRect(0, waterY, Wpx, Hpx - waterY)
    if (aW > 0) {
      g.globalAlpha = aW
      g.drawImage(wb, 0, waterY)
    }
    if (aK > 0) {
      // Silk weave and one shared tint of air.
      g.globalCompositeOperation = th ? 'multiply' : 'soft-light'
      g.globalAlpha = aK * (th ? 0.62 : 0.5)
      g.fillStyle = silk[th]
      g.fillRect(0, 0, Wpx, Hpx)
      g.globalCompositeOperation = 'source-over'
      g.globalAlpha = aK
      g.fillStyle = P.tint
      g.fillRect(0, 0, Wpx, Hpx)
      const dg = Math.hypot(Wpx, Hpx),
        vg = g.createRadialGradient(Wpx / 2, Hpx * 0.46, dg * 0.3, Wpx / 2, Hpx * 0.46, dg * 0.68)
      vg.addColorStop(0, `rgba(${P.vig},0)`)
      vg.addColorStop(1, `rgba(${P.vig},${P.vigA})`)
      g.fillStyle = vg
      g.fillRect(0, 0, Wpx, Hpx)
    }
    g.globalAlpha = 1
  }

  // ── Crumpling the finished picture into a ball ───────────────────────────────
  // Creases: three sizes of fold. Each layer is the minimum over a set of tilted shallow bowls, which meet in straight folds.
  // The flat sheet wrinkles along the large folds; on the ball the sheet is seen magnified, so the two smaller layers give it its broken edges.
  function* bakeCrease(aspect, key) {
    const TW = aspect >= 1 ? 768 : Math.max(192, Math.round(768 * aspect)),
      TH = aspect >= 1 ? Math.max(192, Math.round(768 / aspect)) : 768
    const layers = [
      [4.4, 1, 11],
      [10.5, -0.5, 23],
      [24, 0.22, 37]
    ].map(([f, w, seed]) => {
      const rnd = mulberry32(seed),
        nx = Math.ceil(aspect * f) + 6,
        ny = Math.ceil(f) + 6,
        n = nx * ny
      const cx = new Float32Array(n),
        cy = new Float32Array(n),
        gx = new Float32Array(n),
        gy = new Float32Array(n),
        o = new Float32Array(n)
      for (let j = 0; j < ny; j++)
        for (let i = 0; i < nx; i++) {
          const k = j * nx + i,
            a = rnd() * TAU,
            m = 0.35 + rnd() * 0.55
          cx[k] = i - 3 + 0.5 + (rnd() - 0.5) * 0.76
          cy[k] = j - 3 + 0.5 + (rnd() - 0.5) * 0.76
          gx[k] = Math.cos(a) * m
          gy[k] = Math.sin(a) * m
          o[k] = rnd() * 0.55
        }
      return { f, w, nx, cx, cy, gx, gy, o }
    })
    const data = new Float32Array(TW * TH * 4),
      lo4 = [1e9, 1e9, 1e9, 1e9],
      hi4 = [-1e9, -1e9, -1e9, -1e9],
      val = [0, 0, 0]
    for (let y = 0; y < TH; y++) {
      for (let x = 0; x < TW; x++) {
        const px = ((x + 0.5) / TW) * aspect,
          py = (y + 0.5) / TH
        for (let li = 0; li < 3; li++) {
          const L = layers[li],
            X = px * L.f,
            Y = py * L.f,
            ci = Math.floor(X) + 3,
            cj = Math.floor(Y) + 3
          let best = 1e9
          for (let dj = -2; dj <= 2; dj++)
            for (let di = -2; di <= 2; di++) {
              const k = (cj + dj) * L.nx + ci + di,
                ddx = X - L.cx[k],
                ddy = Y - L.cy[k],
                v = L.o[k] + L.gx[k] * ddx + L.gy[k] * ddy + 0.5 * (ddx * ddx + ddy * ddy)
              if (v < best) best = v
            }
          val[li] = best
        }
        const o = (y * TW + x) * 4,
          h = layers[0].w * val[0] + layers[1].w * val[1] + layers[2].w * val[2]
        data[o] = val[0]
        data[o + 1] = val[1]
        data[o + 2] = h
        data[o + 3] = val[2]
        for (let q = 0; q < 4; q++) {
          const v = data[o + q]
          if (v < lo4[q]) lo4[q] = v
          if (v > hi4[q]) hi4[q] = v
        }
      }
      if ((y & 7) === 7) {
        yield
        if (key !== wantCrease || disposed) return
      }
    }
    for (let o = 0; o < data.length; o += 4)
      for (let q = 0; q < 4; q++) data[o + q] = (data[o + q] - lo4[q]) / (hi4[q] - lo4[q])
    // Coarse shape: the same map at one sixteenth, for the lumps of the ball.
    const LW = TW >> 4,
      LT = TH >> 4,
      lo = new Float32Array(LW * LT * 4)
    for (let y = 0; y < LT; y++)
      for (let x = 0; x < LW; x++)
        for (let q = 0; q < 4; q++) {
          let s = 0
          for (let j = 0; j < 16; j++) for (let i = 0; i < 16; i++) s += data[((y * 16 + j) * TW + x * 16 + i) * 4 + q]
          lo[(y * LW + x) * 4 + q] = s / 256
        }
    if (key === wantCrease) crease = { key, TW, TH, data, LW, LT, lo }
  }
  function createCrumpler(canvas) {
    let gl = null
    try {
      gl = canvas.getContext('webgl2', { alpha: true, premultipliedAlpha: true, antialias: true, depth: true })
    } catch (e) {
      gl = null
    }
    if (!gl) return null
    const DEFORM = `
uniform sampler2D uCrease, uCreaseLo;
uniform float uA, uWr, uG, uR;
// Where the sheet point uv is right now: flat → wrinkled → edges drawn back into a dome → tightened into a ball.
vec3 deform(vec2 uv) {
  const vec2 c0 = vec2(0., -.24);
  vec2 fl = (uv * 2. - 1.) * vec2(uA, 1.) - c0;
  vec4 c = textureLod(uCrease, uv, 0.), cl = textureLod(uCreaseLo, uv, 0.);
  float h = c.b - .5, hl = cl.b - .5;
  float r = length(fl);
  vec2 d = r > 1e-5 ? fl / r : vec2(1., 0.);
  float redge = min(uA / max(abs(d.x), 1e-5), (d.y > 0. ? 1. - c0.y : 1. + c0.y) / max(abs(d.y), 1e-5));
  float rn = min(r / redge, 1.);
  float e = clamp(uG * 1.45 - (.3 * (1. - rn) + .22 * (cl.r - .5) + .04), 0., 1.);
  e = max(e * e * (3. - 2. * e), 1e-4);
  float s = smoothstep(.2, 1., e);
  float th = rn * 3. * e, hs = sin(th * .5);
  float Rr = mix(redge / (3. * e), uR, s);
  float k = smoothstep(.1, 1., e);
  float bh = (c.r - .5) * .4 + (.5 - c.g) * .34 + (c.a - .5) * .2 + hl * .25;
  float disp = h * uWr * (1. - s) + uR * bh * k;
  vec3 er = vec3(sin(th) * d, cos(th)), et = vec3(cos(th) * d, -sin(th)), ep = vec3(-d.y, d.x, 0.);
  vec3 slide = (et * (c.g - .5) + ep * (c.a - .5)) * uR * .2 * k;
  return vec3(d * sin(th) * Rr + c0 * (1. - s), Rr * (s - 2. * hs * hs)) + er * disp + slide;
}`
    const VS = `#version 300 es
precision highp float; precision highp sampler2D;
layout(location = 0) in vec2 aUv;
uniform mat4 uPV, uM;
out vec2 vUv; out vec3 vPos;${DEFORM}
void main() { vec4 w = uM * vec4(deform(aUv), 1.); vPos = w.xyz; vUv = aUv; gl_Position = uPV * w; }`
    const FS = `#version 300 es
precision highp float; precision highp sampler2D;
in vec2 vUv; in vec3 vPos;
uniform sampler2D uTex;
uniform mat4 uM;
uniform vec3 uCam, uPaper;
uniform vec2 uTexel;
uniform float uLit, uAlpha, uSheen;
out vec4 o;${DEFORM}
void main() {
  // The normal comes from the deformation itself, so folds stay sharp whatever the mesh density.
  vec3 p0 = deform(vUv), px = deform(vUv + vec2(uTexel.x, 0.)), py = deform(vUv + vec2(0., uTexel.y));
  vec3 n = normalize(mat3(uM) * cross(px - p0, py - p0));
  vec3 V = normalize(uCam - vPos);
  bool back = dot(n, V) < 0.;
  if (back) n = -n;
  vec3 L = normalize(vec3(-.42, .58, .7));
  float d = max(dot(n, L), 0.);
  float sh = .4 + .6 * d / L.z;
  vec4 cr = texture(uCrease, vUv);
  float ao = 1. - .5 * smoothstep(.6, .15, mix(cr.b, .5 + (cr.r - .5) * .5 + (.5 - cr.g) * .5 + (cr.a - .5) * .4, uG));
  vec3 base = texture(uTex, vUv).rgb;
  if (back) base = mix(base, uPaper, .45);
  vec3 col = base * mix(1., sh * ao, uLit) + uSheen * uLit * (pow(d, 5.) + 1.6 * uG * pow(1. - dot(n, V), 3.)) * vec3(1., .96, .88);
  o = vec4(col * uAlpha, uAlpha);
}`
    const SVS = `#version 300 es
void main() { vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)); gl_Position = vec4(p * 2. - 1., 0., 1.); }`
    const SFS = `#version 300 es
precision highp float;
uniform vec2 uC, uRad; uniform float uS;
out vec4 o;
void main() { vec2 d = (gl_FragCoord.xy - uC) / uRad; o = vec4(0., 0., 0., uS * exp(-dot(d, d) * 1.6)); }`
    const build = (vs, fs) => {
      const p = gl.createProgram()
      for (const [type, src] of [
        [gl.VERTEX_SHADER, vs],
        [gl.FRAGMENT_SHADER, fs]
      ]) {
        const s = gl.createShader(type)
        gl.shaderSource(s, src)
        gl.compileShader(s)
        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
          console.warn('[tavern-entrance] shader', gl.getShaderInfoLog(s))
          return null
        }
        gl.attachShader(p, s)
      }
      gl.linkProgram(p)
      if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
        console.warn('[tavern-entrance] link', gl.getProgramInfoLog(p))
        return null
      }
      const u = {}
      for (let i = 0, n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS); i < n; i++) {
        const name = gl.getActiveUniform(p, i).name
        u[name] = gl.getUniformLocation(p, name)
      }
      return { p, u }
    }
    const paper = build(VS, FS),
      shadow = build(SVS, SFS)
    if (!paper || !shadow) return null
    let lost = false
    canvas.addEventListener('webglcontextlost', (e) => {
      e.preventDefault()
      lost = true
    })
    const vao = gl.createVertexArray(),
      vbo = gl.createBuffer(),
      ibo = gl.createBuffer(),
      empty = gl.createVertexArray()
    const tex = [gl.createTexture(), gl.createTexture(), gl.createTexture()]
    let meshKey = '',
      count = 0,
      creaseKey = '',
      A = 1,
      dark = true,
      texel = [0, 0],
      pw = 1,
      ph = 1
    const CAM = 3.75
    const mul = (a, b) => {
      const o = new Float32Array(16)
      for (let c = 0; c < 4; c++)
        for (let r = 0; r < 4; r++)
          o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3]
      return o
    }
    const rot = (ax, a) => {
      const c = Math.cos(a),
        s = Math.sin(a),
        m = new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1])
      if (ax === 0) {
        m[5] = c
        m[6] = s
        m[9] = -s
        m[10] = c
      } else if (ax === 1) {
        m[0] = c
        m[2] = -s
        m[8] = s
        m[10] = c
      } else {
        m[0] = c
        m[1] = s
        m[4] = -s
        m[5] = c
      }
      return m
    }
    function load(src, cr, theme) {
      if (lost || gl.isContextLost()) return false
      pw = src.width
      ph = src.height
      A = pw / ph
      dark = theme === 0
      canvas.width = pw
      canvas.height = ph
      gl.viewport(0, 0, pw, ph)
      const nx = A >= 1 ? 220 : Math.max(60, Math.round(220 * A)),
        ny = A >= 1 ? Math.max(60, Math.round(220 / A)) : 220,
        key = nx + 'x' + ny
      if (key !== meshKey) {
        const v = new Float32Array((nx + 1) * (ny + 1) * 2),
          idx = new Uint32Array(nx * ny * 6)
        let k = 0
        for (let j = 0; j <= ny; j++)
          for (let i = 0; i <= nx; i++) {
            v[k++] = i / nx
            v[k++] = j / ny
          }
        k = 0
        for (let j = 0; j < ny; j++)
          for (let i = 0; i < nx; i++) {
            const a = j * (nx + 1) + i,
              b = a + 1,
              c = a + nx + 1,
              d = c + 1
            idx[k++] = a
            idx[k++] = b
            idx[k++] = c
            idx[k++] = b
            idx[k++] = d
            idx[k++] = c
          }
        gl.bindVertexArray(vao)
        gl.bindBuffer(gl.ARRAY_BUFFER, vbo)
        gl.bufferData(gl.ARRAY_BUFFER, v, gl.STATIC_DRAW)
        gl.enableVertexAttribArray(0)
        gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0)
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, ibo)
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, idx, gl.STATIC_DRAW)
        gl.bindVertexArray(null)
        meshKey = key
        count = idx.length
      }
      // The picture: exactly the frame that is on screen now.
      gl.activeTexture(gl.TEXTURE0)
      gl.bindTexture(gl.TEXTURE_2D, tex[0])
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true)
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src)
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false)
      gl.generateMipmap(gl.TEXTURE_2D)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
      if (cr.key !== creaseKey) {
        for (const [unit, w, h, data] of [
          [1, cr.TW, cr.TH, cr.data],
          [2, cr.LW, cr.LT, cr.lo]
        ]) {
          gl.activeTexture(gl.TEXTURE0 + unit)
          gl.bindTexture(gl.TEXTURE_2D, tex[unit])
          gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, w, h, 0, gl.RGBA, gl.FLOAT, data)
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
          gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
        }
        creaseKey = cr.key
        texel = [1 / cr.TW, 1 / cr.TH]
      }
      return gl.getError() === gl.NO_ERROR
    }
    // s: seconds since crumpling began. 0 wrinkle → .2 gather → 1.3 a squeeze → 1.44 thrown away.
    function draw(s) {
      if (lost) return false
      const wr = 0.1 * ss(0, 0.4, s),
        lit = ss(0, 0.3, s),
        gu = clamp((s - 0.2) / 1.1, 0, 1),
        G = gu * gu * (3 - 2 * gu)
      const sq = s > 1.3 && s < 1.46 ? Math.sin((Math.PI * (s - 1.3)) / 0.16) : 0,
        u = clamp((s - 1.44) / 0.46, 0, 1),
        fly = Math.pow(u, 2.2)
      const sc = 1 - 0.1 * sq,
        alpha = 1 - ss(0.72, 1, u),
        R = 0.3
      let M = new Float32Array([sc, 0, 0, 0, 0, sc, 0, 0, 0, 0, sc, 0, 0, 0, 0, 1])
      M = mul(rot(2, 0.9 * u), M)
      M = mul(rot(0, -0.28 * G - 1.6 * u), M)
      M = mul(rot(1, 0.55 * G + 3.2 * u), M)
      M[12] = 0.1 * u
      M[13] = 0.55 * u - 0.25 * u * u
      M[14] = -15 * fly
      const f = CAM,
        n = 0.1,
        far = 60,
        PV = new Float32Array([
          f / A,
          0,
          0,
          0,
          0,
          f,
          0,
          0,
          0,
          0,
          (far + n) / (n - far),
          -1,
          0,
          0,
          (2 * far * n) / (n - far) + (CAM * (far + n)) / (far - n),
          CAM
        ])
      gl.clearColor(0, 0, 0, 0)
      gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
      gl.enable(gl.BLEND)
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
      // The shadow the sheet casts once it lifts off the screen.
      const depth = CAM - M[14],
        cxp = (((M[12] * f) / A / depth) * 0.5 + 0.5) * pw,
        cyp = (((M[13] * f) / depth) * 0.5 + 0.5) * ph,
        ext = ((lerp(Math.max(A, 1), R * 1.2, G) * sc * f) / depth) * ph * 0.5
      gl.disable(gl.DEPTH_TEST)
      gl.useProgram(shadow.p)
      gl.bindVertexArray(empty)
      gl.uniform2f(shadow.u.uC, cxp + ph * 0.018 * (1 + 3 * u), cyp - ph * 0.03 * (1 + 3 * u))
      gl.uniform2f(shadow.u.uRad, ext * 1.18, ext * 1.18)
      gl.uniform1f(shadow.u.uS, (dark ? 0.5 : 0.17) * ss(0.2, 0.9, G) * alpha * (1 - 0.6 * u))
      gl.drawArrays(gl.TRIANGLES, 0, 3)
      gl.enable(gl.DEPTH_TEST)
      gl.useProgram(paper.p)
      gl.bindVertexArray(vao)
      const U1 = paper.u
      gl.uniformMatrix4fv(U1.uPV, false, PV)
      gl.uniformMatrix4fv(U1.uM, false, M)
      gl.uniform1i(U1.uTex, 0)
      gl.uniform1i(U1.uCrease, 1)
      gl.uniform1i(U1.uCreaseLo, 2)
      gl.uniform1f(U1.uA, A)
      gl.uniform1f(U1.uWr, wr)
      gl.uniform1f(U1.uG, G)
      gl.uniform1f(U1.uR, R)
      gl.uniform3f(U1.uCam, 0, 0, CAM)
      if (dark) gl.uniform3f(U1.uPaper, 0.24, 0.28, 0.32)
      else gl.uniform3f(U1.uPaper, 0.93, 0.91, 0.86)
      gl.uniform2f(U1.uTexel, texel[0], texel[1])
      gl.uniform1f(U1.uLit, lit)
      gl.uniform1f(U1.uAlpha, alpha)
      gl.uniform1f(U1.uSheen, dark ? 0.16 : 0.05)
      gl.drawElements(gl.TRIANGLES, count, gl.UNSIGNED_INT, 0)
      gl.bindVertexArray(null)
      return true
    }
    function clear() {
      if (!lost) {
        gl.clearColor(0, 0, 0, 0)
        gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
      }
    }
    // The picture texture is the large one; shrink it when done.
    function release() {
      if (lost) return
      gl.activeTexture(gl.TEXTURE0)
      gl.bindTexture(gl.TEXTURE_2D, tex[0])
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, null)
      canvas.width = canvas.height = 1
    }
    function destroy() {
      const ext = gl.getExtension('WEBGL_lose_context')
      if (ext) ext.loseContext()
    }
    return { load, draw, clear, release, destroy }
  }

  // ── Flow ─────────────────────────────────────────────────────────────────────
  let crumpler,
    run = false,
    T = 0,
    Tw = 0,
    last = 0,
    speed = 1,
    committed = false,
    captured = false,
    wipe2d = false,
    fading = false,
    hold = false,
    raf = 0,
    fadeTimer = 0,
    releaseTimer = 0
  function commit() {
    committed = true
    if (hooks.onCommit) hooks.onCommit()
  }
  function end(reason) {
    run = false
    fading = false
    hold = false
    cancelAnimationFrame(raf)
    clearTimeout(fadeTimer)
    budget = 6
    g.setTransform(1, 0, 0, 1, 0, 0)
    g.clearRect(0, 0, cv.width, cv.height)
    if (crumpler) crumpler.clear()
    glcv.style.visibility = 'hidden'
    cv.style.opacity = glcv.style.opacity = ''
    // The window-sized images are not kept: released if the tavern is not entered again soon.
    clearTimeout(releaseTimer)
    releaseTimer = setTimeout(release, 45000)
    if (reason && hooks.onDone) hooks.onDone(reason)
  }
  function release() {
    if (run) return
    pano = null
    wantPano = ''
    cv.width = cv.height = up.width = up.height = tmp.width = tmp.height = wb.width = wb.height = 1
    if (crumpler) crumpler.release()
  }
  function fadeOut(reason) {
    fading = true
    cv.style.opacity = glcv.style.opacity = '0'
    fadeTimer = setTimeout(() => end(reason), 260)
  }
  const gate = createFrameGate()

  function frame(now) {
    if (!run) return
    raf = requestAnimationFrame(frame)
    // Honour Settings → 渲染帧率上限.
    if (!gate.shouldDraw(now)) return
    const t = now / 1000,
      dt = last ? Math.min(0.05, t - last) : 0
    last = t
    if (!hold && !fading) {
      Tw += dt * speed
      if (pano) T = Math.min(T + dt * speed, crease ? T_END : T_CRUMPLE) // The paper darkens straight away; the drawing waits until the picture is ready.
      if (!committed && Tw >= T_COMMIT) commit()
    }
    if (!captured) {
      paint(t, Math.min(T, T_CRUMPLE), ss(0, 0.5, Tw))
      if (T >= T_CRUMPLE && pano && crease && !fading) {
        // Hand this frame to the sheet: the flat sheet covers it first, the 2D picture underneath is cleared a frame later, so the app never shows through.
        if (crumpler && crumpler.load(cv, crease, th) && crumpler.draw(0)) {
          captured = true
          wipe2d = true
          glcv.style.visibility = 'visible'
        } else {
          fadeOut('done')
          return
        }
      }
    } else {
      if (wipe2d) {
        wipe2d = false
        g.setTransform(1, 0, 0, 1, 0, 0)
        g.clearRect(0, 0, Wpx, Hpx)
      }
      if (!crumpler.draw(T - T_CRUMPLE) && !fading) {
        fadeOut('done')
        return
      }
    }
    if (T >= T_END && !hold && !fading) end('done')
  }
  // x: where it starts, in CSS pixels. dark: whether the shell is dark. fast: the short version for repeat entries.
  function start(opts = {}) {
    if (disposed || !g) return false
    if (run) end(null)
    clearTimeout(releaseTimer)
    if (crumpler === undefined) crumpler = createCrumpler(glcv)
    th = opts.dark === false ? 1 : 0
    measure()
    ox = clamp(opts.x == null ? W / 2 : opts.x, 0, W) * dpr
    oy = waterY - layH * 0.2
    speed = opts.fast ? FAST : 1
    T = 0
    Tw = 0
    last = 0
    committed = false
    captured = false
    wipe2d = false
    run = true
    budget = 10
    prepare()
    const pk = th + '|' + Wpx + 'x' + Hpx,
      ck = (W / H).toFixed(3)
    if (!pano || pano.key !== pk) {
      pano = null
      if (wantPano !== pk) {
        wantPano = pk
        enqueue(buildPano(th, pk))
      }
    }
    if (!crease || crease.key !== ck) {
      crease = null
      if (wantCrease !== ck) {
        wantCrease = ck
        enqueue(bakeCrease(W / H, ck))
      }
    }
    raf = requestAnimationFrame(frame)
    return true
  }
  // Leave early: picture and sheet fade out together. The caller decides whether that is a cancel or a skip.
  function leave(reason) {
    if (run && !fading) fadeOut(reason || 'skipped')
  }
  function dispose() {
    if (run) end(null)
    disposed = true
    clearTimeout(releaseTimer)
    release()
    if (crumpler) crumpler.destroy()
  }
  function seek(v, opts) {
    start(opts)
    hold = true
    T = clamp(v, 0, T_END)
    Tw = Math.max(T, 0.5)
    if (T >= T_COMMIT) commit()
  }
  const api = {
    prepare,
    start,
    leave,
    seek,
    dispose,
    get running() {
      return run
    },
    get committed() {
      return committed
    }
  }

  return api
}

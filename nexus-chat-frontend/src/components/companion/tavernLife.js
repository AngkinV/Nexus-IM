const smooth = (x) => x * x * (3 - 2 * x)
const noise = (time, seed) => {
  const at = Math.floor(time)
  const hash = (n) => { const x = Math.sin(n * 127.1 + seed * 311.7) * 43758.5453; return (x - Math.floor(x)) * 2 - 1 }
  return hash(at) + (hash(at + 1) - hash(at)) * smooth(time - at)
}

export function createTavernLife(random = Math.random) {
  let time = 0
  let blinkAt = 1.5 + random() * 2
  let blinkStart = -1
  let blinkDuration = 0.18
  let doubleBlink = false
  let gazeAt = 0
  let eyeX = 0
  let eyeY = 0
  let breathPhase = 0
  const seed = random() * 100
  return {
    update(delta, reducedMotion = false, breathingRate = 1) {
      if (reducedMotion) return { blink: 0, breath: 0, sway: 0, drift: 0, eyeX: 0, eyeY: 0 }
      time += delta
      breathPhase += delta * (1.35 + noise(time * 0.12, seed) * 0.2) * breathingRate
      let blink = 0
      if (time >= blinkAt && blinkStart < 0) {
        blinkStart = time
        blinkDuration = 0.14 + random() * 0.09
      }
      if (blinkStart >= 0) {
        const p = (time - blinkStart) / blinkDuration
        blink = p < 0.4 ? smooth(p / 0.4) : 1 - smooth(Math.min(1, (p - 0.4) / 0.6))
        if (p >= 1) {
          blinkStart = -1
          const repeat = !doubleBlink && random() < 0.12
          blinkAt = time + (repeat ? 0.12 : 2.6 + random() * 4)
          doubleBlink = repeat
        }
      }
      if (time >= gazeAt) {
        eyeX = (random() - 0.5) * 0.065
        eyeY = (random() - 0.5) * 0.035
        gazeAt = time + 0.7 + random() * 1.7
      }
      return {
        blink,
        breath: Math.sin(breathPhase) * 0.009,
        sway: noise(time * 0.075, seed + 1) * 0.018,
        drift: noise(time * 0.22, seed + 2) * 0.009,
        eyeX, eyeY
      }
    }
  }
}

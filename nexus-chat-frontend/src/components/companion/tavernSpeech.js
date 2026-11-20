export const MOUTHS = ['aa', 'ih', 'ou', 'ee', 'oh']

const NARRATION_CLOSE = { '（': '）', '(': ')', '*': '*' }
const GESTURES = [
  ['chin', /托腮|托着.*下巴|撑着.*下巴|rest.*chin|chin.*hand/i],
  ['sip', /喝了|喝一口|抿.*[茶酒水杯]|啜|饮了一口|\bsips?\b/i],
  ['wipe', /擦.*杯|擦拭|wip.*(?:glass|cup)/i],
  ['wave', /挥.*手|挥了挥|\bwav(?:e|es|ing)\b/i],
  ['shrug', /耸.*肩|\bshrug/i],
  ['cover', /掩.*嘴|捂.*嘴|cover.*mouth/i],
  ['cross', /抱.*臂|双臂交叉|cross.*arms/i],
  ['tuck', /拢.*发|撩.*发|拨.*发|tuck.*hair/i]
]

export function narrationGesture(text) {
  if (/没有|并未|不要|不曾|didn't|doesn't|without/i.test(text)) return null
  return GESTURES.find(([, pattern]) => pattern.test(text))?.[0] || null
}

export function createTavernSpeech() {
  let queue = []
  let current = null
  let elapsed = 0
  let closers = []
  let narration = ''
  let index = 0
  let finishing = false
  let finishLeft = 0
  const neutral = () => ({ mouth: null, open: 0, emphasis: 0, gesture: null })

  const reset = () => {
    queue = []
    current = null
    elapsed = 0
    closers = []
    narration = ''
    index = 0
    finishing = false
    finishLeft = 0
  }
  const enqueue = (beat, silent) => {
    if (silent) return
    queue.push(beat)
    // Visual speech must not accumulate minutes of animation behind a fast stream.
    if (queue.length > 96) queue.splice(0, queue.length - 96)
  }

  return {
    reset,
    pause() {
      queue = []
      current = null
      elapsed = 0
    },
    push(text, silent = false) {
      if (finishing) return
      for (const char of text) {
        if (closers.length) {
          if (char === closers.at(-1)) {
            closers.pop()
            if (!closers.length) {
              enqueue({ duration: 0.18, gesture: narrationGesture(narration) }, silent)
              narration = ''
            }
          } else {
            if (char !== '*' && NARRATION_CLOSE[char]) closers.push(NARRATION_CLOSE[char])
            if (narration.length < 240) narration += char
          }
          continue
        }
        if (NARRATION_CLOSE[char]) {
          closers.push(NARRATION_CLOSE[char])
          enqueue({ duration: 0.08 }, silent)
        } else if (/[。.!！?？…\n]/u.test(char)) {
          enqueue({ duration: /[!！?？]/u.test(char) ? 0.22 : 0.3, emphasis: /[!！?？]/u.test(char) ? 0.16 : 0 }, silent)
        } else if (/[，,、;；:：\s]/u.test(char)) {
          enqueue({ duration: 0.1 }, silent)
        } else if (/[\p{L}\p{N}]/u.test(char)) {
          const vowel = { a: 'aa', i: 'ih', u: 'ou', e: 'ee', o: 'oh' }[char.toLowerCase()]
          // Without audio/phonemes this is text-paced articulation, not lip synchronization.
          const mouth = vowel || MOUTHS[index++ % MOUTHS.length]
          enqueue({ duration: /\p{Script=Han}/u.test(char) ? 0.095 : 0.055, mouth }, silent)
        }
      }
    },
    finish() {
      finishing = true
      finishLeft = 1.2
      closers = []
      narration = ''
    },
    update(delta) {
      let gesture = null
      if (finishing) {
        finishLeft -= delta
        if (finishLeft <= 0) {
          reset()
          return neutral()
        }
      }
      elapsed += delta * Math.min(3, 1 + queue.length / 22)
      while (!current || elapsed >= current.duration) {
        if (current) elapsed -= current.duration
        current = queue.shift() || null
        if (!current) {
          elapsed = 0
          return { ...neutral(), gesture }
        }
        if (current.gesture) gesture = current.gesture
      }
      const envelope = Math.pow(Math.max(0, Math.sin(Math.PI * elapsed / current.duration)), 0.7)
      return {
        mouth: current.mouth || null,
        open: current.mouth ? envelope * 0.48 : 0,
        emphasis: (current.emphasis || 0) * envelope,
        gesture
      }
    },
    snapshot() {
      return { queued: queue.length, mouth: current?.mouth || null, narrating: closers.length > 0, finishing }
    }
  }
}

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

// Small reactions she narrates about herself. Head and torso only.
const BEAT_CUES = [
  ['shake', /摇(?:了)?(?:摇)?头|shakes?\s+(?:her\s+|his\s+|their\s+)?head/i],
  ['nod', /点(?:了)?(?:点)?头|颔首|\bnods?\b/i],
  ['tilt', /歪(?:了|着)?(?:歪)?(?:头|脑袋)|偏(?:了|着)?头|侧(?:了|着)?头|tilts?\s+(?:her\s+|his\s+|their\s+)?head/i],
  ['sigh', /叹(?:了)?(?:一)?(?:口)?气|叹息|\bsighs?\b/i],
  ['laugh', /笑出|轻笑|低笑|失笑|噗嗤|笑了(?:笑|起来|一下|一声|出来)|\b(?:chuckles?|laughs?|giggles?)\b/i],
  ['smile', /微笑|笑着|笑了|莞尔|抿嘴一笑|嘴角.{0,4}(?:扬|弯)|\bsmiles?\b/i],
  ['lookAway', /[看望](?:向|着)?(?:窗|雨|远处|别处|炉火|壁炉|门口|吊灯)|移开(?:了)?(?:视线|目光)|别过(?:头|脸)|looks?\s+(?:away|out|toward)|glances?\s+(?:away|at\s+the)/i],
  ['lookDown', /低(?:下|着|了)?头|垂(?:下|着)?(?:眼|眸|目光|视线)|[看盯](?:着|向)?(?:杯|手里|桌面)|looks?\s+down|lowers?\s+(?:her\s+|his\s+|their\s+)?(?:gaze|eyes|head)/i],
  ['leanIn', /[凑靠倾](?:近|过来|向前)|前倾|俯身|身子.{0,3}前|leans?\s+(?:in|forward|closer)/i],
  ['leanBack', /后仰|往后(?:靠|仰)|向后(?:靠|仰)|leans?\s+back/i],
  ['perk', /愣(?:了|住)|一怔|眨(?:了)?(?:眨)?眼|睁大|抬(?:起)?(?:头|眼)|\bblinks?\b|looks?\s+up|startled/i]
]
// What she says, not what she does: only words that are themselves a reaction.
const SPOKEN_OPENERS = [
  ['nod', /^(?:嗯+|对|对啊|是的|是啊|好|好的|好啊|当然|没错|yes|yeah|sure|right|of course)$/i],
  ['shake', /^(?:不|不是|不行|不对|没有|no|nope)$/i],
  ['sigh', /^(?:唉+|哎+)$/]
]
const SPOKEN_LAUGH = /(?:哈哈|呵呵|嘿嘿|嘻嘻|haha|hehe)$/i

/** Narration that is not an instruction for this character's body, right now. */
function notHerOwnAction(text) {
  if (/没有|并未|不要|不曾|没再|不会|didn't|doesn't|did not|does not|without|not going/i.test(text)) return true
  if (/曾经|昨天|刚才|回忆|当时|以前|打算|想要|也许|如果|准备喝|yesterday|earlier|used to|would|might|will\s/i.test(text)) return true
  // This parser sees only current-role narration. Explicit other subjects or
  // quoted recollections are not instructions for this character's body.
  return /他(?:们)?|对方|客人|用户|那个人|那个女孩|[“”「」]|\b(?:he|they|you|someone|guest)\b/i.test(text)
}

export function narrationGesture(text) {
  if (notHerOwnAction(text)) return null
  return GESTURES.find(([, pattern]) => pattern.test(text))?.[0] || null
}

export function narrationBeat(text) {
  if (notHerOwnAction(text)) return null
  return BEAT_CUES.find(([, pattern]) => pattern.test(text))?.[0] || null
}

/** A reaction carried by the spoken sentence so far, e.g. an opening "嗯". */
export function spokenBeat(sentence) {
  const text = sentence.trim()
  if (!text || text.length > 6) return null
  return SPOKEN_OPENERS.find(([, pattern]) => pattern.test(text))?.[0] || null
}

export function createTavernSpeech() {
  let queue = []
  let current = null
  let elapsed = 0
  let closers = []
  let discardClosers = []
  let narration = ''
  let index = 0
  let finishing = false
  let finishLeft = 0
  let sentence = ''
  let tail = ''
  const neutral = () => ({ mouth: null, open: 0, emphasis: 0, gesture: null, beat: null, spoken: null })

  const reset = () => {
    queue = []
    current = null
    elapsed = 0
    closers = []
    discardClosers = []
    narration = ''
    index = 0
    finishing = false
    finishLeft = 0
    sentence = ''
    tail = ''
  }
  const enqueue = (beat, silent) => {
    if (silent) return
    queue.push(beat)
    // Visual speech must not accumulate minutes of animation behind a fast stream.
    if (queue.length > 96) queue.splice(0, queue.length - 96)
  }

  return {
    reset,
    cancel: reset,
    pause() {
      const boundary = discardClosers.length ? [...discardClosers] : [...closers]
      reset()
      discardClosers = boundary
    },
    push(text, silent = false) {
      if (silent) {
        const boundary = discardClosers.length ? [...discardClosers] : [...closers]
        reset()
        discardClosers = boundary
        for (const char of text) {
          if (char === discardClosers.at(-1)) discardClosers.pop()
          else if (NARRATION_CLOSE[char] && discardClosers.length < 12) discardClosers.push(NARRATION_CLOSE[char])
        }
        return
      }
      if (finishing) return
      for (const char of text) {
        if (discardClosers.length) {
          if (char === discardClosers.at(-1)) discardClosers.pop()
          else if (NARRATION_CLOSE[char] && discardClosers.length < 12) discardClosers.push(NARRATION_CLOSE[char])
          continue
        }
        if (closers.length) {
          if (char === closers.at(-1)) {
            closers.pop()
            if (!closers.length) {
              enqueue({ duration: 0.18, gesture: narrationGesture(narration), beat: narrationBeat(narration) }, silent)
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
          const opener = spokenBeat(sentence)
          enqueue({ duration: /[!！?？]/u.test(char) ? 0.22 : 0.3, emphasis: /[!！?？]/u.test(char) ? 0.16 : 0,
            spoken: opener || (/[?？]/u.test(char) ? 'tilt' : null) }, silent)
          sentence = ''
          tail = ''
        } else if (/[，,、;；:：\s]/u.test(char)) {
          const opener = /\s/u.test(char) ? null : spokenBeat(sentence)
          enqueue({ duration: 0.1, spoken: opener }, silent)
          if (!/\s/u.test(char)) sentence = ''
          else if (sentence.length < 24) sentence += char
        } else if (/[\p{L}\p{N}]/u.test(char)) {
          if (sentence.length < 24) sentence += char
          tail = (tail + char).slice(-6)
          const laughing = SPOKEN_LAUGH.test(tail)
          const vowel = { a: 'aa', i: 'ih', u: 'ou', e: 'ee', o: 'oh' }[char.toLowerCase()]
          // Without audio/phonemes this is text-paced articulation, not lip synchronization.
          const mouth = vowel || MOUTHS[index++ % MOUTHS.length]
          enqueue({ duration: /\p{Script=Han}/u.test(char) ? 0.095 : 0.055, mouth, spoken: laughing ? 'laugh' : null }, silent)
          if (laughing) tail = ''
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
      let gesture = null, beat = null, spoken = null
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
          return { ...neutral(), gesture, beat, spoken }
        }
        if (current.gesture) gesture = current.gesture
        if (current.beat) beat = current.beat
        if (current.spoken) spoken = current.spoken
      }
      const envelope = Math.pow(Math.max(0, Math.sin(Math.PI * elapsed / current.duration)), 0.7)
      return {
        mouth: current.mouth || null,
        open: current.mouth ? envelope * 0.48 : 0,
        emphasis: (current.emphasis || 0) * envelope,
        gesture, beat, spoken
      }
    },
    snapshot() {
      return { queued: queue.length, mouth: current?.mouth || null, narrating: closers.length > 0, discarding: discardClosers.length > 0, finishing }
    }
  }
}

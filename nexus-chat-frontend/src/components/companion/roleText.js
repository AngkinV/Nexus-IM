/**
 * How a companion's text is read: cut into speech and narration, reduced to the
 * one line a conversation row shows, and matched against the list's search.
 *
 * Pure text handling, no Vue. The conversation view and the role list both read
 * a line through `segmentsOf`, so what counts as narration can never differ
 * between the bubble and the row that previews it.
 */

// Narration — （…）, (…) or *…* — is set apart from what is said aloud.
const NARRATION = /（[^（）]*）|\([^()]*\)|\*[^*\n]+\*/g

export function segmentsOf(content) {
  const text = content || ''
  const out = []
  let last = 0
  for (const match of text.matchAll(NARRATION)) {
    if (match.index > last) out.push({ narration: false, text: text.slice(last, match.index) })
    out.push({ narration: true, text: match[0] })
    last = match.index + match[0].length
  }
  if (last < text.length) out.push({ narration: false, text: text.slice(last) })
  return out
}

const oneLine = (text) => text.replace(/\s+/g, ' ').trim()

/**
 * What a role says aloud in a message, on one line: narration dropped, the
 * stretches of speech around it joined by a space. A message that is narration
 * throughout — or leaves nothing but punctuation once it is removed — is kept
 * as written, so a row never ends up blank or as a lone full stop.
 */
export function spokenLine(content) {
  const spoken = segmentsOf(content)
    .filter((seg) => !seg.narration)
    .map((seg) => oneLine(seg.text))
    .filter(Boolean)
    .join(' ')
  return /[\p{L}\p{N}]/u.test(spoken) ? spoken : oneLine(content)
}

/**
 * The line under a role's name, and the full text behind it.
 *
 * Only a line the server attributes to the role is reduced to its speech. The
 * user's own words, a system line and a line with no known sender are shown as
 * written: brackets and asterisks there are maths or emphasis, not narration.
 * With nothing said yet the role's greeting stands in, labelled as one; without
 * a greeting the row says so. What the role is doing or sounds like is not a
 * message and is never shown as one.
 *
 * `labels` are already localised: { you, greeting, empty }.
 * Returns { text, title, line, full } — `text` is the row's line, `title` the
 * original text with narration and line breaks intact (null when there is
 * none), `line` and `full` the same two without their label, for searching.
 */
export function previewOf(role, labels) {
  const said = role.lastMessage || ''
  if (said.trim()) {
    const label = role.lastMessageSender === 'user' ? labels.you : ''
    const line = role.lastMessageSender === 'role' ? spokenLine(said) : oneLine(said)
    return { text: label + line, title: label + said, line, full: said }
  }
  const greeting = (role.greeting || '').trim()
  if (greeting) {
    const line = spokenLine(greeting)
    return { text: labels.greeting + line, title: labels.greeting + greeting, line, full: greeting }
  }
  return { text: labels.empty, title: null, line: '', full: '' }
}

const fold = (text) => oneLine(text || '').toLocaleLowerCase()

/**
 * True when the query occurs in any of the texts, ignoring case and treating
 * any run of whitespace (line breaks included) as one space. An empty query
 * matches everything.
 */
export function matchesQuery(query, ...texts) {
  const needle = fold(query)
  return !needle || texts.some((text) => fold(text).includes(needle))
}

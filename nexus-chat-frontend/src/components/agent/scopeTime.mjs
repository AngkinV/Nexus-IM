// Wall-clock time for the chat reading scope. The wire format stays what
// normalizeScope accepts (UTC ISO ending in Z); this file only translates between
// that and a date-time read in the scope's own IANA zone, and never guesses: a
// wall clock the zone skipped or repeated is refused, not moved.
const DAY = 86_400_000
const WALL = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d)(?::(\d\d))?$/

// code: zone | invalid | nonexistent | ambiguous | order | empty; field: zone | from | to | range.
export class ScopeTimeError extends Error {
  constructor(code, field) { super(code); this.code = code; this.field = field }
}

// Java's ZoneId is case-sensitive, so the canonical spelling is what is sent. An
// offset such as +08:00 is refused: Java would save it, and the agent, which checks
// the same value against IANA names only, would then deny every run that carries it.
export function canonicalZone(input) {
  const name = String(input ?? '').trim()
  if (!name || /^[+-]/.test(name)) throw new ScopeTimeError('zone', 'zone')
  try { return new Intl.DateTimeFormat('en', { timeZone: name }).resolvedOptions().timeZone }
  catch { throw new ScopeTimeError('zone', 'zone') }
}

const formats = new Map()
// The zone's wall clock at an instant, as the UTC milliseconds that print the same digits.
function wallAt(ms, timeZone) {
  if (!formats.has(timeZone)) formats.set(timeZone, new Intl.DateTimeFormat('en', { timeZone, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }))
  const part = Object.fromEntries(formats.get(timeZone).formatToParts(ms).map(({ type, value }) => [type, Number(value)]))
  return Date.UTC(part.year, part.month - 1, part.day, part.hour, part.minute, part.second)
}
function parseWall(wall, field) {
  const match = WALL.exec(wall)
  if (!match) throw new ScopeTimeError('invalid', field)
  const ms = Date.UTC(match[1], match[2] - 1, match[3], match[4], match[5], match[6] ?? 0)
  // Date.UTC rolls 02-30 into March; a wall clock that does not print back is not a date.
  if (new Date(ms).toISOString().slice(0, 19) !== `${wall.slice(0, 16)}:${match[6] ?? '00'}`) throw new ScopeTimeError('invalid', field)
  return ms
}
// Every instant at which the zone's wall clock reads `wall`: none inside a
// spring-forward gap, two inside a fall-back overlap. An offset is under a day, so
// the instant lies within a day of the same digits read as UTC and uses an offset
// in force somewhere in that window.
function instants(wall, timeZone) {
  const offsets = new Set([wall - DAY, wall, wall + DAY].map(ms => wallAt(ms, timeZone) - ms))
  return [...offsets].map(offset => wall - offset).filter(ms => wallAt(ms, timeZone) === wall).sort((a, b) => a - b)
}
function instantOf(wall, timeZone, field) {
  const found = instants(parseWall(wall, field), timeZone)
  if (!found.length) throw new ScopeTimeError('nonexistent', field)
  if (found.length > 1) throw new ScopeTimeError('ambiguous', field)
  return found[0]
}

// 'YYYY-MM-DDTHH:mm[:ss]' read in `timeZone` -> UTC ISO.
export function wallToUtc(wall, timeZone) { return new Date(instantOf(wall, timeZone)).toISOString() }
// UTC ISO -> 'YYYY-MM-DDTHH:mm:ss' as read in `timeZone`.
export function utcToWall(iso, timeZone) { return new Date(wallAt(Date.parse(iso), timeZone)).toISOString().slice(0, 19) }

// The half-open window [from, to) the two wall clocks describe. An empty side is unbounded.
export function wallRange(from, to, timeZone) {
  const zone = canonicalZone(timeZone)
  const start = from ? instantOf(from, zone, 'from') : null, end = to ? instantOf(to, zone, 'to') : null
  if (start != null && end != null && start >= end) throw new ScopeTimeError('order', 'range')
  return { fromInclusive: start == null ? null : new Date(start).toISOString(), toExclusive: end == null ? null : new Date(end).toISOString(), timeZone: zone }
}

const second = now => Math.floor(now / 1000) * 1000
// Presets are fixed boundaries taken at the click, not a window that keeps sliding.
export function lastDay(now) {
  return { fromInclusive: new Date(second(now) - DAY).toISOString(), toExclusive: new Date(second(now)).toISOString() }
}
// Monday 00:00 in `timeZone` up to the click, and never past it.
export function weekToDate(now, timeZone) {
  const wall = wallAt(second(now), timeZone)
  const monday = wall - wall % DAY - (new Date(wall).getUTCDay() + 6) % 7 * DAY
  // A local day starts at its first instant: the earlier midnight when the clock
  // repeats it, and the jump itself when midnight is skipped. The wall clock only
  // moves forward across that instant, so it is found by bisection.
  let before = monday - DAY, start = monday + DAY
  while (start - before > 1000) {
    const middle = before + Math.round((start - before) / 2000) * 1000
    if (wallAt(middle, timeZone) >= monday) start = middle
    else before = middle
  }
  // Within the week's first second the whole second before the click is Monday
  // midnight itself, so the click keeps its milliseconds: the window ends at the
  // click, not at a second that has not happened yet. A click on the first instant
  // has no window to give, and is refused instead of being stretched into the future.
  const end = second(now) > start ? second(now) : now
  if (end <= start) throw new ScopeTimeError('empty', 'range')
  return { fromInclusive: new Date(start).toISOString(), toExclusive: new Date(end).toISOString() }
}

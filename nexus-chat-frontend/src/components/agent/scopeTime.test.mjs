import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ScopeTimeError, canonicalZone, lastDay, utcToWall, wallRange, wallToUtc, weekToDate } from './scopeTime.mjs'
import { normalizeScope } from './scopeSelection.mjs'

const refused = (code, field) => error => error instanceof ScopeTimeError && error.code === code && (field === undefined || error.field === field)

test('a wall clock is read in its own zone and sent as UTC Z, and reads back the same', () => {
  assert.equal(wallToUtc('2026-11-25T00:00:00', 'Asia/Shanghai'), '2026-11-24T16:00:00.000Z')
  assert.equal(wallToUtc('2026-11-25T00:00', 'Asia/Shanghai'), '2026-11-24T16:00:00.000Z', 'a picker may omit zero seconds')
  assert.equal(wallToUtc('2026-11-25T00:00:00', 'UTC'), '2026-11-25T00:00:00.000Z')
  assert.equal(wallToUtc('2026-01-15T09:30:15', 'America/New_York'), '2026-01-15T14:30:15.000Z')
  assert.equal(wallToUtc('2026-07-15T09:30:15', 'America/New_York'), '2026-07-15T13:30:15.000Z', 'summer offset')
  assert.equal(wallToUtc('2026-06-01T12:00:00', 'Asia/Kolkata'), '2026-06-01T06:30:00.000Z', 'half-hour offset')
  assert.equal(wallToUtc('2026-06-01T12:00:00', 'Pacific/Kiritimati'), '2026-05-31T22:00:00.000Z', '+14:00')
  for (const [iso, zone] of [['2026-11-24T16:00:00.000Z', 'Asia/Shanghai'], ['2026-03-08T07:30:00.000Z', 'America/New_York'], ['2026-10-24T23:30:00.000Z', 'Europe/London']]) {
    assert.equal(wallToUtc(utcToWall(iso, zone), zone), iso)
  }
  assert.equal(utcToWall('2026-11-24T16:00:00Z', 'Asia/Shanghai'), '2026-11-25T00:00:00')
  assert.equal(utcToWall('2026-11-24T16:00:00.750Z', 'UTC'), '2026-11-24T16:00:00', 'the wall clock is whole seconds')
})

test('a wall clock the zone skipped at spring-forward is refused, not moved', () => {
  // New York 2026-03-08: 02:00 -> 03:00.
  assert.equal(wallToUtc('2026-03-08T01:59:59', 'America/New_York'), '2026-03-08T06:59:59.000Z')
  for (const wall of ['2026-03-08T02:00:00', '2026-03-08T02:30:00', '2026-03-08T02:59:59']) {
    assert.throws(() => wallToUtc(wall, 'America/New_York'), refused('nonexistent'), wall)
  }
  assert.equal(wallToUtc('2026-03-08T03:00:00', 'America/New_York'), '2026-03-08T07:00:00.000Z')
  // London 2026-03-29: 01:00 -> 02:00. Lord Howe 2026-10-04: 02:00 -> 02:30, a half-hour gap.
  assert.throws(() => wallToUtc('2026-03-29T01:30:00', 'Europe/London'), refused('nonexistent'))
  assert.throws(() => wallToUtc('2026-10-04T02:15:00', 'Australia/Lord_Howe'), refused('nonexistent'))
  assert.equal(wallToUtc('2026-10-04T02:30:00', 'Australia/Lord_Howe'), '2026-10-03T15:30:00.000Z')
})

test('a wall clock the zone repeated at fall-back is refused as ambiguous, never resolved silently', () => {
  // New York 2026-11-01: 02:00 EDT -> 01:00 EST, so 01:00-01:59:59 happens twice.
  assert.equal(wallToUtc('2026-11-01T00:59:59', 'America/New_York'), '2026-11-01T04:59:59.000Z')
  for (const wall of ['2026-11-01T01:00:00', '2026-11-01T01:30:00', '2026-11-01T01:59:59']) {
    assert.throws(() => wallToUtc(wall, 'America/New_York'), refused('ambiguous'), wall)
  }
  assert.equal(wallToUtc('2026-11-01T02:00:00', 'America/New_York'), '2026-11-01T07:00:00.000Z')
  assert.throws(() => wallToUtc('2026-10-25T01:30:00', 'Europe/London'), refused('ambiguous'))
  // Both instants of a repeated hour still read back as the same wall clock.
  assert.equal(utcToWall('2026-11-01T05:30:00Z', 'America/New_York'), '2026-11-01T01:30:00')
  assert.equal(utcToWall('2026-11-01T06:30:00Z', 'America/New_York'), '2026-11-01T01:30:00')
  // A zone without daylight saving has neither case on the same dates.
  assert.equal(wallToUtc('2026-03-08T02:30:00', 'Asia/Shanghai'), '2026-03-07T18:30:00.000Z')
  assert.equal(wallToUtc('2026-11-01T01:30:00', 'Asia/Shanghai'), '2026-10-31T17:30:00.000Z')
})

test('text that is not a real date-time is refused', () => {
  for (const wall of ['', '2026-11-25', '2026-11-25 00:00:00', '2026-02-30T00:00:00', '2026-13-01T00:00:00', '2026-11-25T24:00:00', '2026-11-25T00:60:00', '2026-11-24T16:00:00Z']) {
    assert.throws(() => wallToUtc(wall, 'UTC'), refused('invalid'), wall)
  }
  assert.equal(wallToUtc('2028-02-29T00:00:00', 'UTC'), '2028-02-29T00:00:00.000Z', 'a leap day is a date')
})

test('only an IANA zone is a zone, sent in its canonical spelling', () => {
  assert.equal(canonicalZone('Asia/Shanghai'), 'Asia/Shanghai')
  assert.equal(canonicalZone('  asia/shanghai '), 'Asia/Shanghai')
  assert.equal(canonicalZone('UTC'), 'UTC')
  for (const zone of ['', '   ', null, 'Mars/Phobos', 'Asia/Shangha', '+08:00', '-05:00']) assert.throws(() => canonicalZone(zone), refused('zone', 'zone'), String(zone))
})

test('a range is half-open: the end is excluded, so an empty or reversed window is refused', () => {
  const day = wallRange('2026-11-25T00:00:00', '2026-11-26T00:00:00', 'Asia/Shanghai')
  assert.deepEqual(day, { fromInclusive: '2026-11-24T16:00:00.000Z', toExclusive: '2026-11-25T16:00:00.000Z', timeZone: 'Asia/Shanghai' })
  // Two consecutive days share exactly one instant: the end of one is the start of the next.
  assert.equal(wallRange('2026-11-26T00:00:00', '2026-11-27T00:00:00', 'Asia/Shanghai').fromInclusive, day.toExclusive)
  assert.deepEqual(wallRange('2026-11-25T00:00:00', '2026-11-25T00:00:01', 'UTC'), { fromInclusive: '2026-11-25T00:00:00.000Z', toExclusive: '2026-11-25T00:00:01.000Z', timeZone: 'UTC' })
  assert.throws(() => wallRange('2026-11-25T00:00:00', '2026-11-25T00:00:00', 'UTC'), refused('order', 'range'), 'start equal to end holds nothing')
  assert.throws(() => wallRange('2026-11-26T00:00:00', '2026-11-25T00:00:00', 'UTC'), refused('order', 'range'))
  // An empty side is unbounded, and the result is what the wire validation accepts unchanged.
  assert.deepEqual(wallRange('', '', 'utc'), { fromInclusive: null, toExclusive: null, timeZone: 'UTC' })
  assert.deepEqual(wallRange('2026-11-25T00:00:00', '', 'Asia/Shanghai'), { fromInclusive: '2026-11-24T16:00:00.000Z', toExclusive: null, timeZone: 'Asia/Shanghai' })
  assert.deepEqual(wallRange('', '2026-11-25T00:00:00', 'Asia/Shanghai'), { fromInclusive: null, toExclusive: '2026-11-24T16:00:00.000Z', timeZone: 'Asia/Shanghai' })
  assert.deepEqual(normalizeScope({ targets: [], timeRange: day }).timeRange, day)
  // A day that holds a clock change is 23 or 25 hours long, not 24.
  const hours = range => (Date.parse(range.toExclusive) - Date.parse(range.fromInclusive)) / 3_600_000
  assert.equal(hours(wallRange('2026-03-08T00:00:00', '2026-03-09T00:00:00', 'America/New_York')), 23)
  assert.equal(hours(wallRange('2026-11-01T00:00:00', '2026-11-02T00:00:00', 'America/New_York')), 25)
})

test('each side of a range reports its own error', () => {
  assert.throws(() => wallRange('2026-03-08T02:30:00', '2026-03-09T00:00:00', 'America/New_York'), refused('nonexistent', 'from'))
  assert.throws(() => wallRange('2026-03-07T00:00:00', '2026-03-08T02:30:00', 'America/New_York'), refused('nonexistent', 'to'))
  assert.throws(() => wallRange('2026-11-01T01:30:00', '', 'America/New_York'), refused('ambiguous', 'from'))
  assert.throws(() => wallRange('2026-10-31T00:00:00', '2026-11-01T01:30:00', 'America/New_York'), refused('ambiguous', 'to'))
  assert.throws(() => wallRange('2026-02-30T00:00:00', '', 'UTC'), refused('invalid', 'from'))
  assert.throws(() => wallRange('2026-11-25T00:00:00', '2026-11-26T00:00:00', 'Nowhere/Land'), refused('zone', 'zone'))
})

test('the last 24 hours are a fixed half-open window that ends at the click', () => {
  assert.deepEqual(lastDay(Date.parse('2026-11-25T08:15:30.640Z')), { fromInclusive: '2026-11-24T08:15:30.000Z', toExclusive: '2026-11-25T08:15:30.000Z' })
})

test('this week starts on Monday 00:00 in the chosen zone, across a month and a year boundary', () => {
  // Wednesday 2026-07-01 in Shanghai: the Monday is 29 June.
  assert.deepEqual(weekToDate(Date.parse('2026-07-01T03:00:00Z'), 'Asia/Shanghai'), { fromInclusive: '2026-06-28T16:00:00.000Z', toExclusive: '2026-07-01T03:00:00.000Z' })
  // Friday 2027-01-01: the Monday is 28 December of the year before.
  assert.equal(weekToDate(Date.parse('2027-01-01T12:00:00Z'), 'UTC').fromInclusive, '2026-12-28T00:00:00.000Z')
  // Sunday is the last day of the week, Monday its first second.
  assert.equal(weekToDate(Date.parse('2026-11-29T23:59:59Z'), 'UTC').fromInclusive, '2026-11-23T00:00:00.000Z')
  assert.equal(weekToDate(Date.parse('2026-11-30T00:00:00.001Z'), 'UTC').fromInclusive, '2026-11-30T00:00:00.000Z')
  // One second earlier it is still Sunday, in the week before.
  assert.equal(weekToDate(Date.parse('2026-11-29T23:59:59.999Z'), 'UTC').fromInclusive, '2026-11-23T00:00:00.000Z')
  // The same instant is Monday in Shanghai and still Sunday in UTC and New York.
  const now = Date.parse('2026-06-28T17:00:00Z')
  assert.equal(weekToDate(now, 'Asia/Shanghai').fromInclusive, '2026-06-28T16:00:00.000Z')
  assert.equal(weekToDate(now, 'UTC').fromInclusive, '2026-06-22T00:00:00.000Z')
  assert.equal(weekToDate(now, 'America/New_York').fromInclusive, '2026-06-22T04:00:00.000Z')
  for (const zone of ['Asia/Shanghai', 'UTC', 'America/New_York']) {
    const week = weekToDate(now, zone)
    assert.ok(week.fromInclusive < week.toExclusive)
    assert.equal(utcToWall(week.fromInclusive, zone).slice(11), '00:00:00')
    assert.equal(new Date(`${utcToWall(week.fromInclusive, zone)}Z`).getUTCDay(), 1)
  }
})

test('this week never ends after the click: the first second ends at the click to the millisecond, the first instant is refused', () => {
  // Within the week's first second the end is the click itself. It used to be pushed
  // to the next whole second, which let a request read up to 999 ms that had not happened.
  for (const [now, zone, from, to] of [
    ['2026-11-30T00:00:00.001Z', 'UTC', '2026-11-30T00:00:00.000Z', '2026-11-30T00:00:00.001Z'],
    ['2026-11-30T00:00:00.400Z', 'UTC', '2026-11-30T00:00:00.000Z', '2026-11-30T00:00:00.400Z'],
    ['2026-11-29T16:00:00.999Z', 'Asia/Shanghai', '2026-11-29T16:00:00.000Z', '2026-11-29T16:00:00.999Z'],
    ['2026-11-30T05:00:00.250Z', 'America/New_York', '2026-11-30T05:00:00.000Z', '2026-11-30T05:00:00.250Z'],
    // From the next second on, the end is the last whole second before the click.
    ['2026-11-30T00:00:01.000Z', 'UTC', '2026-11-30T00:00:00.000Z', '2026-11-30T00:00:01.000Z'],
    ['2026-11-30T00:00:01.400Z', 'UTC', '2026-11-30T00:00:00.000Z', '2026-11-30T00:00:01.000Z'],
    ['2026-11-30T00:00:02.400Z', 'UTC', '2026-11-30T00:00:00.000Z', '2026-11-30T00:00:02.000Z']
  ]) {
    const week = weekToDate(Date.parse(now), zone)
    assert.deepEqual(week, { fromInclusive: from, toExclusive: to }, `${now} ${zone}`)
    assert.deepEqual(normalizeScope({ targets: [], timeRange: { ...week, timeZone: zone } }).timeRange, { ...week, timeZone: zone }, 'what the wire validation accepts unchanged')
  }
  // On the first instant of the week nothing has happened in it yet. The window is
  // refused, not stretched into the future and not sent as the empty [Monday, Monday).
  for (const [now, zone] of [['2026-11-30T00:00:00.000Z', 'UTC'], ['2026-11-29T16:00:00.000Z', 'Asia/Shanghai'], ['2026-11-30T05:00:00.000Z', 'America/New_York'], ['2021-03-21T20:30:00.000Z', 'Asia/Tehran']]) {
    assert.throws(() => weekToDate(Date.parse(now), zone), refused('empty', 'range'), `${now} ${zone}`)
  }
  // No click, in any zone, gets a boundary later than itself: every millisecond of the
  // two seconds around the start of a week, and a spread of clicks through the rest of
  // it, in weeks that are 167 hours long because of a clock change or a skipped midnight.
  const starts = [['2026-11-30T00:00:00.000Z', 'UTC'], ['2026-11-29T16:00:00.000Z', 'Asia/Shanghai'], ['2026-11-30T05:00:00.000Z', 'America/New_York'],
    ['2026-03-02T05:00:00.000Z', 'America/New_York'], ['2026-03-09T04:00:00.000Z', 'America/New_York'], ['2021-03-21T20:30:00.000Z', 'Asia/Tehran'], ['2026-11-29T18:15:00.000Z', 'Asia/Kathmandu']]
  const clicks = starts.flatMap(([iso, zone]) => {
    const start = Date.parse(iso)
    return [...Array.from({ length: 1999 }, (_, ms) => start + 1 + ms), ...Array.from({ length: 400 }, (_, step) => start + 2000 + step * 1_499_999)].map(now => [now, zone, start])
  })
  assert.equal(clicks.length, 7 * 2399)
  for (const [now, zone, start] of clicks) {
    const week = weekToDate(now, zone), from = Date.parse(week.fromInclusive), to = Date.parse(week.toExclusive)
    assert.equal(from, start, `${new Date(now).toISOString()} ${zone} starts its own week`)
    assert.ok(to <= now, `${new Date(now).toISOString()} ${zone} ends at ${week.toExclusive}, after the click`)
    assert.ok(from < to, `${new Date(now).toISOString()} ${zone} is not empty`)
    assert.ok(now - to < 1000, `${new Date(now).toISOString()} ${zone} ends within a second of the click`)
    assert.equal(to, now - start < 1000 ? now : Math.floor(now / 1000) * 1000)
  }
  // The other preset obeys the same rule.
  for (const now of [Date.parse('2026-11-25T08:15:30.000Z'), Date.parse('2026-11-25T08:15:30.640Z'), Date.parse('2026-11-25T08:15:30.999Z')]) {
    assert.ok(Date.parse(lastDay(now).toExclusive) <= now)
  }
})

test('a week that holds a clock change still starts on Monday midnight; a skipped midnight starts at the jump', () => {
  // New York changed on Sunday 2026-03-08; the Monday before it was still on winter time.
  assert.equal(weekToDate(Date.parse('2026-03-08T12:00:00Z'), 'America/New_York').fromInclusive, '2026-03-02T05:00:00.000Z')
  assert.equal(weekToDate(Date.parse('2026-03-11T12:00:00Z'), 'America/New_York').fromInclusive, '2026-03-09T04:00:00.000Z')
  // Tehran skipped 00:00 on Monday 2021-03-22 (00:00 +03:30 became 01:00 +04:30).
  assert.throws(() => wallToUtc('2021-03-22T00:00:00', 'Asia/Tehran'), refused('nonexistent'))
  const tehran = weekToDate(Date.parse('2021-03-24T12:00:00Z'), 'Asia/Tehran')
  assert.equal(tehran.fromInclusive, '2021-03-21T20:30:00.000Z')
  assert.equal(utcToWall(tehran.fromInclusive, 'Asia/Tehran'), '2021-03-22T01:00:00')
})

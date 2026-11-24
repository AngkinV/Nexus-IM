// The pixel workroom shown inside the thinking process. Every figure, prop and
// handed-over paper is one reported node or edge of the run; nothing here adds
// a role, a step, a line of dialogue or progress the backend did not report.

const PALETTE = [
  { main: '#5b8cff', dark: '#2f4fb3' }, { main: '#3ecf8e', dark: '#1f7a53' },
  { main: '#f5a524', dark: '#9a6510' }, { main: '#c084fc', dark: '#7440b0' },
  { main: '#ff6b81', dark: '#a83848' }, { main: '#22d3ee', dark: '#12808f' },
  { main: '#f97316', dark: '#9a4409' }, { main: '#a3e635', dark: '#5f8a14' }
]
// The roles the backend reports today keep one colour everywhere they appear.
const KNOWN = { assistant: 0, code: 1, doc: 2, arch: 3, synth: 4 }

export function agentColor(agentId) {
  if (typeof agentId !== 'string' || !agentId) return null
  if (Object.hasOwn(KNOWN, agentId)) return PALETTE[KNOWN[agentId]]
  let hash = 0
  for (const ch of agentId) hash = (Math.imul(hash, 31) + ch.codePointAt(0)) >>> 0
  return PALETTE[hash % PALETTE.length]
}

// How a figure stands follows the state its node reported.
const POSE = {
  running: 'working', waiting_tool: 'waiting', waiting_approval: 'waiting', queued: 'idle', idle: 'idle',
  succeeded: 'done', partial: 'limited', limited: 'limited', failed: 'failed',
  cancelled: 'stopped', stop_requested: 'stopped', interrupted: 'stopped', skipped: 'skipped', unknown: 'unknown'
}
export const poseOf = state => POSE[state] || 'unknown'

const ICON = {
  context: 'shelf', knowledge: 'book', attachments: 'paper', web_search: 'globe', tool: 'terminal', tools: 'toolbox',
  collaboration: 'table', validate_output: 'check', mock: 'flask', model: 'chip'
}
// Pixel drawings of the reported step kinds, on a 16 by 16 grid.
export const PROP_ICONS = {
  shelf: 'M2 2h12v12H2zM3 3v4h10V3zM3 8v5h10V8zM4 4h1v3H4zM6 3h2v4H6zM9 4h1v3H9zM11 3h1v4h-1zM4 9h2v4H4zM7 10h1v3H7zM9 9h3v4H9z',
  book: 'M2 3h5v1h2V3h5v10H9v1H7v-1H2zM3 4v8h4V4zM9 4v8h4V4zM4 6h2v1H4zM4 8h2v1H4zM10 6h2v1h-2zM10 8h2v1h-2z',
  paper: 'M4 2h6v1h1v1h1v10H4zM5 3v10h6V5H9V3zM6 7h4v1H6zM6 9h4v1H6zM6 11h3v1H6z',
  globe: 'M5 2h6v1h2v2h1v6h-1v2h-2v1H5v-1H3v-2H2V5h1V3h2zM5 3v1H4v1H3v2h3V3zM7 3v4h2V3zM10 3v4h3V5h-1V4h-1V3zM3 9v2h1v1h1v1h1V9zM7 9v4h2V9zM10 9v4h1v-1h1v-1h1V9z',
  terminal: 'M2 3h12v9h-4v1h2v1H4v-1h2v-1H2zM3 4v7h10V4zM4 5h1v1h1v1h1v1H6v1H5v1H4V9h1V8h1V7H5V6H4zM8 9h3v1H8z',
  toolbox: 'M5 3h6v2h3v8H2V5h3zM6 4v1h4V4zM3 6v2h4V7h2v1h4V6zM3 9v3h10V9H9v1H7V9z',
  table: 'M6 3h4v3h4v2h-1v5h-2V8H5v5H3V8H2V6h4zM7 4v2h2V4z',
  check: 'M2 3h12v10H2zM3 4v8h10V4zM11 6h1v1h-1v1h-1v1H9v1H8v1H7v-1H6V9H5V8h1v1h1v1h1V9h1V8h1V7h1z',
  flask: 'M6 2h4v1H9v3h1v2h1v2h1v2h1v2H3v-2h1v-2h1V8h1V6h1V3H6zM6 9v1H5v2h6v-2h-1V9z',
  chip: 'M6 2h1v2h2V2h1v2h2v2h2v1h-2v2h2v1h-2v2h-2v2H9v-2H7v2H6v-2H4v-2H2V9h2V7H2V6h2V4h2zM5 5v6h6V5zM7 7h2v2H7z',
  crate: 'M2 4h12v9H2zM3 5v7h4V5zM9 5v7h4V5z'
}
const FINISHED = new Set(['succeeded', 'skipped'])
const BUSY = new Set(['running', 'waiting_tool', 'waiting_approval'])

// The model's own latest words, shortened. Null when it reported none.
function tail(text, size = 96) {
  const line = typeof text === 'string' ? text.replace(/\s+/g, ' ').trim() : ''
  if (!line) return null
  const chars = [...line]
  return chars.length > size ? '…' + chars.slice(-size).join('') : line
}

const isActor = node => !!node?.agentId && (node.kind === 'model' || node.kind === 'task')

export function buildPixelScene(view, { max = 12 } = {}) {
  const all = view?.nodes || []
  // The map shows the latest attempt; earlier ones stay in the details below.
  const attempt = all.at(-1)?.attemptKey
  const nodes = all.filter(node => node.attemptKey === attempt)
  const byId = new Map(nodes.map(node => [node.id, node]))
  const edges = (view?.edges || []).filter(edge => byId.has(edge.from) && byId.has(edge.to))
  const parents = new Map()
  for (const edge of edges) parents.set(edge.to, [...(parents.get(edge.to) || []), byId.get(edge.from)])

  let cells = []
  const actors = new Map()
  for (const node of nodes) {
    if (isActor(node)) {
      let actor = actors.get(node.agentId)
      if (!actor) {
        actor = { type: 'actor', key: 'actor:' + node.agentId, agentId: node.agentId, color: agentColor(node.agentId), calls: 0 }
        actors.set(node.agentId, actor)
        cells.push(actor)
      }
      // A figure is its agent; what it is doing is its most recent reported step.
      const from = [...new Set((parents.get(node.id) || []).map(parent => parent.agentId).filter(id => id && id !== node.agentId))]
      Object.assign(actor, {
        nodeId: node.id, label: node.role || node.agentId, state: node.state, pose: poseOf(node.state), calls: actor.calls + 1,
        kind: node.kind, title: node.reportedTitle || node.model || null, ms: node.ms ?? null,
        saying: node.state === 'running' ? tail(node.reasoning) : null,
        handoffs: from.map(id => ({ agentId: id, color: agentColor(id) })), inputs: [], at: null, trouble: 0, stance: poseOf(node.state)
      })
    } else {
      cells.push({
        type: 'prop', key: node.id, nodeId: node.id, kind: node.kind, icon: ICON[node.kind] || 'crate',
        label: node.kind === 'tool' ? node.toolName || node.title || null : node.kind === 'model' ? node.model || null : null,
        state: node.state, pose: poseOf(node.state), agentId: node.agentId || null, color: agentColor(node.agentId),
        reused: node.reused === true, ms: node.ms ?? null, stance: poseOf(node.state)
      })
    }
  }

  // A long turn keeps its figures and anything unfinished; the oldest finished props fold into one counter.
  if (cells.length > max) {
    const foldable = cells.filter(cell => cell.type === 'prop' && FINISHED.has(cell.state))
    const folded = foldable.slice(0, Math.min(foldable.length, cells.length - max + 1))
    if (folded.length > 1) {
      const at = cells.indexOf(folded[0])
      cells = cells.filter(cell => !folded.includes(cell))
      cells.splice(at, 0, { type: 'more', key: 'more', nodeId: folded[0].nodeId, count: folded.length })
    }
  }

  // Which place on the map a reported node is drawn at: its agent's desk, its own station, or the folded pile.
  const placed = new Set(cells.map(cell => cell.key))
  const cellOf = id => { const node = byId.get(id); return isActor(node) ? 'actor:' + node.agentId : placed.has(id) ? id : placed.has('more') ? 'more' : null }

  // Roads are the reported dependencies between those places, one road per pair. A road is live
  // while the step at its receiving end is under way. Nothing is joined that was not reported.
  const links = []
  const join = (from, to, live, agentId) => {
    if (!from || !to || from === to) return
    const known = links.find(link => (link.from === from && link.to === to) || (link.from === to && link.to === from))
    if (!known) { links.push({ from, to, live, back: false, agentId: agentId || null }); return }
    if (live && !known.live) Object.assign(known, { live: true, back: known.from !== from, agentId: agentId || known.agentId })
  }
  for (const edge of edges) {
    const to = byId.get(edge.to)
    join(cellOf(edge.from), cellOf(edge.to), BUSY.has(to.state), to.agentId || byId.get(edge.from).agentId)
  }
  // A step that names its agent belongs to that agent even when it reported no parent.
  for (const cell of cells) if (cell.type === 'prop' && cell.agentId && actors.has(cell.agentId)) join('actor:' + cell.agentId, cell.key, BUSY.has(cell.state), cell.agentId)

  for (const actor of actors.values()) {
    // Where its current step takes its input from, in the order reported.
    actor.inputs = [...new Set((parents.get(actor.nodeId) || []).map(parent => cellOf(parent.id)).filter(key => key && key !== actor.key))]
    // A figure stands at a step of its own that is still under way until the result is reported.
    const own = cells.filter(cell => cell.type === 'prop' && cell.agentId === actor.agentId)
    const pending = actor.pose === 'working' ? null : own.filter(cell => BUSY.has(cell.state)).at(-1)
    actor.at = pending ? { key: pending.key, nodeId: pending.nodeId, kind: pending.kind, label: pending.label } : null
    // Its last call has ended, yet it is not finished while a step it asked for is still running.
    actor.stance = pending ? 'away' : actor.pose
    // Its own failed steps are counted, not guessed.
    actor.trouble = own.filter(cell => cell.state === 'failed').length
  }

  // What the figures that are working right now last said, in the model's own words.
  const lines = [...actors.values()].filter(actor => actor.saying)
    .map(actor => ({ agentId: actor.agentId, label: actor.label, color: actor.color, text: actor.saying }))
  return { cells, links, lines, earlier: all.length - nodes.length, working: cells.some(cell => BUSY.has(cell.state)) }
}

// --- The map -------------------------------------------------------------------------------
// Places are laid out by how far along the reported dependencies they are: sources first, then
// whoever uses them, then what those asked for. On a wide map that runs left to right, on a
// narrow one top to bottom. Places at the same depth spread across the other direction and
// neighbouring depths are pushed apart, so a run is never drawn as one straight line.
// Everything follows from the report and the width given; nothing is random.
export const MAP = { site: 84, art: 36, tag: 40, head: 12, pad: 6, pitchMin: 92, pitchMax: 240, row: 80, rowPitch: 96, spread: 120, zig: 14, cap: 3, beside: 28 }
// How fast a figure walks, in pixels per second.
export const SPEED = 120

const same = (a, b) => Math.abs(a[0] - b[0]) < 1 && Math.abs(a[1] - b[1]) < 1
const tidy = points => points.filter((point, i) => i === 0 || !same(point, points[i - 1]))
export const pathLength = points => points.slice(1).reduce((sum, point, i) => sum + Math.abs(point[0] - points[i][0]) + Math.abs(point[1] - points[i][1]), 0)
export const pathData = points => points.length < 2 ? `M${points[0][0]} ${points[0][1]}h.01` : 'M' + points.map(point => point[0] + ' ' + point[1]).join('L')
// The point a given share of the way along a path made of straight stretches.
export function pointAt(points, share) {
  let left = Math.min(1, Math.max(0, share)) * pathLength(points)
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1], b = points[i], length = Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1])
    if (left <= length) return length ? [Math.round(a[0] + (b[0] - a[0]) * left / length), Math.round(a[1] + (b[1] - a[1]) * left / length)] : [...a]
    left -= length
  }
  return [...points.at(-1)]
}

// The way between two points: out along the direction of travel, one turn halfway, and in the same way.
export function routeBetween(a, b, sideways = true) {
  const mid = sideways ? Math.round((a[0] + b[0]) / 2) : Math.round((a[1] + b[1]) / 2)
  return tidy(sideways ? [a, [mid, a[1]], [mid, b[1]], b] : [a, [a[0], mid], [b[0], mid], b])
}

export function layoutMap(scene, width = 320) {
  const { site, art, tag, head, pad, pitchMin, pitchMax, row, rowPitch, spread, zig, cap, beside } = MAP
  const cells = scene?.cells || [], links = scene?.links || []
  const inner = Math.max(site, width - pad * 2)
  const order = new Map(cells.map((cell, i) => [cell.key, i]))

  // Depth: one more than the deepest earlier place it is joined to. A desk that reported no
  // such tie still stands after the sources that came before it; that is placement, not a road.
  const depth = new Map()
  let sources = false
  for (const cell of cells) {
    let deepest = cell.type === 'actor' && sources ? 1 : 0
    for (const link of links) {
      const other = link.to === cell.key ? link.from : link.from === cell.key ? link.to : null
      if (other != null && order.get(other) < order.get(cell.key)) deepest = Math.max(deepest, depth.get(other) + 1)
    }
    depth.set(cell.key, deepest)
    if (cell.type !== 'actor' && !cell.agentId) sources = true
  }
  const layers = []
  for (const cell of cells) (layers[depth.get(cell.key)] ||= []).push(cell)
  const split = (size) => layers.filter(Boolean).flatMap(layer => Array.from({ length: Math.ceil(layer.length / size) }, (_, i) => layer.slice(i * size, i * size + size)))

  const top = art + head
  const spots = {}
  let lines = split(cap), sideways = (lines.length - 1) * pitchMin + site <= inner, height = top + tag + 2
  if (sideways) {
    const pitch = lines.length > 1 ? Math.min(pitchMax, (inner - site) / (lines.length - 1)) : 0
    const first = pad + (inner - (lines.length - 1) * pitch - site) / 2 + site / 2
    const tallest = Math.max(1, ...lines.map(line => line.length)), middle = top + zig + (tallest - 1) * row / 2
    lines.forEach((line, c) => line.forEach((cell, i) => {
      spots[cell.key] = { x: Math.round(first + c * pitch), y: Math.round(middle + (i - (line.length - 1) / 2) * row + (c % 2 ? zig : -zig)), line: c }
    }))
    height = top + zig * 2 + (tallest - 1) * row + tag + 2
  } else {
    lines = split(Math.max(1, Math.floor((inner + 4) / (site + 4))))
    lines.forEach((line, r) => {
      const gap = line.length > 1 ? Math.min(spread, (inner - site) / (line.length - 1)) : 0
      const slack = (inner - (line.length - 1) * gap - site) / 2, lean = Math.min(zig, slack) * (r % 2 ? 1 : -1)
      line.forEach((cell, i) => { spots[cell.key] = { x: Math.round(pad + inner / 2 + lean + (i - (line.length - 1) / 2) * gap), y: top + r * rowPitch, line: r } })
    })
    height = top + Math.max(0, lines.length - 1) * rowPitch + tag + 2
  }
  const ground = key => spots[key] && [spots[key].x, spots[key].y]

  const elbow = (a, b) => routeBetween(a, b, sideways)
  // A visitor follows the same road and stops beside the place, on the side it came from.
  const visit = (home, place) => {
    const side = place[0] > home[0] ? -1 : 1, stop = [place[0] + side * beside, place[1]]
    if (sideways) return same([home[0], 0], [place[0], 0]) ? tidy([home, [stop[0], home[1]], stop]) : tidy([home, [Math.round((home[0] + place[0]) / 2), home[1]], [Math.round((home[0] + place[0]) / 2), stop[1]], stop])
    const turn = Math.round((home[1] + place[1]) / 2)
    return tidy([home, [home[0], turn], [stop[0], turn], stop])
  }

  const roads = links.filter(link => spots[link.from] && spots[link.to]).map(link => ({
    key: link.from + '>' + link.to, from: link.from, to: link.to, live: link.live, back: link.back,
    color: agentColor(link.agentId), points: elbow(ground(link.from), ground(link.to))
  })).map(road => ({ ...road, d: pathData(road.points) }))

  // Where each figure is headed. At its desk by default; beside a step it is waiting on; and
  // while its step is under way, to and fro between its desk and the places that step draws on.
  const plans = {}
  for (const cell of cells) {
    if (cell.type !== 'actor') continue
    const home = ground(cell.key), waiting = cell.stance === 'away' && cell.at ? ground(cell.at.key) : null
    const sources = cell.stance === 'working' ? cell.inputs.map(ground).filter(Boolean).slice(0, 3) : []
    if (waiting) plans[cell.key] = { mode: 'at', points: visit(home, waiting), rest: 1 }
    else if (sources.length) {
      // It starts where its input came from, carries it to the desk, then goes round the others.
      let points = visit(home, sources[0]).reverse()
      const arrive = pathLength(points)
      for (const place of sources.slice(1)) { const out = visit(home, place); points = [...points, ...out.slice(1), ...out.slice(0, -1).reverse()] }
      plans[cell.key] = { mode: 'loop', points, rest: arrive / pathLength(points) }
    } else plans[cell.key] = { mode: 'home', points: [home], rest: 1 }
  }
  return { sideways, width, height, spots, roads, plans }
}

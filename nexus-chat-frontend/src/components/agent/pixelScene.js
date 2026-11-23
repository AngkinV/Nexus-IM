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
const FINISHED = new Set(['succeeded', 'skipped'])
const BUSY = new Set(['running', 'waiting_tool', 'waiting_approval'])

// The model's own latest words, shortened. Null when it reported none.
function tail(text, size = 96) {
  const line = typeof text === 'string' ? text.replace(/\s+/g, ' ').trim() : ''
  if (!line) return null
  const chars = [...line]
  return chars.length > size ? '…' + chars.slice(-size).join('') : line
}

export function buildPixelScene(view, { max = 12 } = {}) {
  const all = view?.nodes || []
  // The room shows the latest attempt; earlier ones stay in the details below.
  const attempt = all.at(-1)?.attemptKey
  const nodes = all.filter(node => node.attemptKey === attempt)
  const byId = new Map(nodes.map(node => [node.id, node]))
  const parents = new Map()
  for (const edge of view?.edges || []) {
    if (byId.has(edge.from) && byId.has(edge.to)) parents.set(edge.to, [...(parents.get(edge.to) || []), byId.get(edge.from)])
  }

  let cells = []
  const actors = new Map()
  for (const node of nodes) {
    if (node.agentId && (node.kind === 'model' || node.kind === 'task')) {
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
        saying: node.state === 'running' ? tail(node.reasoning) : null,
        handoffs: from.map(id => ({ agentId: id, color: agentColor(id) }))
      })
    } else {
      cells.push({
        type: 'prop', key: node.id, nodeId: node.id, kind: node.kind, icon: ICON[node.kind] || 'crate',
        label: node.kind === 'tool' ? node.toolName || node.title || null : node.kind === 'model' ? node.model || null : null,
        state: node.state, pose: poseOf(node.state), agentId: node.agentId || null, color: agentColor(node.agentId),
        reused: node.reused === true
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

  // What the figures that are working right now last said, in the model's own words.
  const lines = [...actors.values()].filter(actor => actor.saying)
    .map(actor => ({ agentId: actor.agentId, label: actor.label, color: actor.color, text: actor.saying }))
  return { cells, lines, earlier: all.length - nodes.length, working: cells.some(cell => BUSY.has(cell.state)) }
}

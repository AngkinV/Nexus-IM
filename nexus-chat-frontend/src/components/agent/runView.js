const stages = new Set(['model', 'tool', 'tools', 'context', 'knowledge', 'web_search', 'attachments', 'mock', 'collaboration', 'validate_output'])
const pending = state => state === 'running' || state === 'queued'
const single = items => items.length === 1 ? items[0] : null
const quantity = value => Number.isFinite(value) && value >= 0 ? value : null

function eventOf(entry) {
  if (!entry || typeof entry !== 'object') return {}
  const { data, ...frame } = entry
  const e = data && typeof data === 'object'
    ? { ...data, ...frame, kind: frame.name || frame.event || frame.kind || data.kind }
    : entry
  if (e.kind === 'stage') return { ...e, kind: e.phase }
  if (e.kind === 'tool_call') return { ...e, kind: 'tool', status: e.status || 'running' }
  if (e.kind === 'tool_result') return { ...e, kind: 'tool', status: e.status || 'unknown' }
  return e
}

function tokenUsage(e, previous = null) {
  if (!e || e.usageReported === false) return previous
  const input = quantity(e.inputTokens), output = quantity(e.outputTokens), total = quantity(e.totalTokens)
  if (input === null && output === null && total === null) return previous
  // Old providers emitted all-zero totals even when usage was unavailable.
  if (e.usageReported !== true && !input && !output && (total === 0 || (input === 0 && output === 0))) return previous
  const inputTokens = input ?? previous?.inputTokens ?? null
  const outputTokens = output ?? previous?.outputTokens ?? null
  const sum = inputTokens !== null && outputTokens !== null ? inputTokens + outputTokens : null
  const totalTokens = total ?? (input !== null && output !== null ? sum : previous?.totalTokens ?? sum)
  return { inputTokens, outputTokens, totalTokens }
}

function stateOf(value) {
  switch (String(value || '').toLowerCase()) {
    case 'queued': case 'pending': return 'queued'
    case 'start': case 'running': case 'streaming': return 'running'
    case 'failed': case 'error': case 'timeout': case 'invalid_output': return 'error'
    case 'cancel': case 'aborted': case 'cancelled': case 'canceled': return 'cancelled'
    case 'partial': case 'interrupted': return 'interrupted'
    case 'length': case 'limit': case 'limited': case 'truncated': case 'max_tokens': case 'max_output_tokens':
    case 'max_iterations': case 'content_filter': return 'limited'
    // The tool catalog could not be read, so the turn went on without tools.
    case 'skipped': case 'unavailable': return 'skipped'
    case 'success': case 'done': case 'stop': case 'completed': case 'tool_calls': return 'done'
    default: return null
  }
}

function stageState(e) {
  const status = stateOf(e.status), finish = stateOf(e.finishReason)
  if (e.error || status === 'error' || finish === 'error') return 'error'
  if (finish || status) return finish || status
  if (e.status != null || e.finishReason != null) return 'unknown'
  if (e.kind === 'knowledge' && e.chunks === null) return 'skipped'
  if (e.kind === 'knowledge' && quantity(e.chunks) !== null) return 'done'
  if (e.kind === 'context' && [e.shortTerm, e.recalled, e.ms].some(Number.isFinite)) return 'done'
  return null
}

function mergeById(map, items) {
  for (const item of items || []) if (item?.id) map.set(item.id, item)
}

function matchesModel(n, e) {
  return (!e.agentId || n.agentId === e.agentId) && (e.iteration == null || n.iteration === e.iteration)
}

// Sequences restart on resume. Route an explicitly tagged late frame to its own
// attempt, but never let a replay select the active attempt or clear a terminal.
function groupAttempts(trace, options) {
  const events = trace.map(eventOf)
  const attempts = [], byRun = new Map()
  const defaultRun = options.runId ?? single([...new Set(events.map(e => e.runId).filter(id => id != null))])
  for (const e of events) {
    const runId = e.runId ?? attempts.at(-1)?.runId ?? defaultRun
    const group = byRun.get(runId) || []
    const id = e.attemptId ?? e.traceId
    let a = id == null ? group.at(-1) : group.find(a => a.attemptId === id || (!e.attemptId && a.meta.traceId === id))
    if (id == null && e.kind === 'meta' && e.resumed && !(a?.meta.resumed && e.sequence != null && a.meta.sequence === e.sequence)) a = null
    if (e.sequence != null && a?.seen.has(e.sequence)) continue
    if (!a) {
      a = { runId, attemptId: id, meta: {}, seen: new Set(), events: [] }
      group.push(a); byRun.set(runId, group); attempts.push(a)
    }
    if (e.sequence != null) a.seen.add(e.sequence)
    if (e.kind === 'meta') a.meta = { ...a.meta, ...e }
    a.events.push(e)
  }
  return attempts
}

function elapsedClock(startedAt) {
  // Public `at` is already elapsed ms. Only legacy wall-clock timestamps need a baseline.
  const epoch = value => typeof value === 'string' ? Date.parse(value) : value
  let origin = epoch(startedAt)
  return value => {
    const at = quantity(epoch(value))
    if (at === null || at < 1e11) return at
    if (!(origin >= 1e11)) origin = at
    return Math.max(0, at - origin)
  }
}

function projectAttempt({ runId, attemptId, meta, events }, options, usedIds) {
  const nodes = [], models = [], annotations = [], unattributed = []
  const byNode = new Map(), byCall = new Map(), sources = new Map(), artifacts = new Map()
  const elapsed = elapsedClock(meta.startedAt ?? options.startedAt)
  let terminal = null, failure = null, usage = null, elapsedMs = null

  for (const [index, e] of events.entries()) {
    const at = elapsed(e.at)
    if (at !== null) elapsedMs = Math.max(elapsedMs ?? 0, at)
    if (e.kind === 'sources') { mergeById(sources, e.items); continue }
    if (e.kind === 'artifact') {
      if (e.artifact?.id) {
        const item = { ...e.artifact }
        for (const key of ['nodeId', 'agentId', 'parentIds', 'sourceIds', 'callIds']) if (item[key] == null && e[key] != null) item[key] = e[key]
        if (runId != null) item.runId ??= runId
        if (attemptId != null) item.attemptId ??= attemptId
        artifacts.set(item.id, item)
      }
      continue
    }
    if (e.kind === 'usage') { usage = tokenUsage(e, usage); continue }
    if (e.kind === 'done') { terminal = stateOf(e.finishReason) || 'interrupted'; continue }
    if (['cancel', 'cancelled', 'canceled'].includes(e.kind)) { terminal = 'cancelled'; continue }
    if (e.kind === 'error') {
      const matches = e.nodeId || e.callId || !e.agentId ? [] : models.filter(n => matchesModel(n, e))
      const target = e.nodeId ? byNode.get(e.nodeId) : e.callId ? byCall.get(e.callId) : single(matches.filter(n => pending(n.state))) || single(matches)
      const detail = e.message || e.code || 'error'
      if (target) Object.assign(target, { state: 'error', detail })
      const role = e.agentId || target?.agentId
      // A transport fault (a stream that ended early) is not the run's own verdict: the
      // recorded status already says interrupted or error, and only that decides.
      if (!e.transport && ((!role && !e.nodeId && !e.callId) || (target?.kind !== 'tool' && !e.callId && ['assistant', 'synth'].includes(role)))) failure = detail
      continue
    }
    if (e.kind === 'reasoning' || e.kind === 'agent_output') {
      if (typeof e.text === 'string' && e.text) annotations.push({ kind: e.kind, nodeId: e.nodeId, agentId: e.agentId, iteration: e.iteration, text: e.text, at, index })
      continue
    }
    if (!stages.has(e.kind) && !(e.nodeId && e.phase === e.kind && e.phase)) continue

    let n = byNode.get(e.nodeId)
    if (!n && e.kind === 'tool' && e.callId) n = byCall.get(e.callId)
    const state = stageState(e)
    if (!n && !e.nodeId && e.kind === 'model' && !pending(state)) {
      const open = models.filter(m => pending(m.state) && matchesModel(m, e))
      if (open.length > 1) continue // An unaddressed completion is not evidence of an additional call.
      n = single(open)
    }
    if (!n) {
      n = { kind: e.kind, agentId: e.agentId || (e.kind === 'model' ? 'assistant' : null), parentIds: [],
        at, ms: null, state: 'unknown', title: '', reasoning: '', output: '', detail: '', usage: null }
      nodes.push(n)
      if (n.kind === 'model') models.push(n)
    }
    if (e.nodeId) { n.nodeId = e.nodeId; byNode.set(e.nodeId, n) }
    if (e.kind === 'tool' && e.callId) byCall.set(e.callId, n)
    if (Array.isArray(e.parentIds)) n.parentIds = [...new Set([...n.parentIds, ...e.parentIds])]
    for (const key of ['agentId', 'model', 'provider', 'toolName', 'callId', 'args', 'iteration', 'shortTerm', 'recalled', 'chunks', 'finishReason']) {
      if (e[key] != null) n[key] = e[key]
    }
    n.title = e.toolName || e.model || e.title || n.title || e.provider || e.phase || e.kind
    n.detail = e.error || e.message || n.detail
    n.ms = e.ms ?? e.latencyMs ?? n.ms
    n.usage = tokenUsage(e, n.usage)
    if (state && n.state !== 'error' && (state !== 'unknown' || pending(n.state))) n.state = state
  }

  // Resolve annotations against actual calls, including persisted frames whose
  // model stage arrives later. Text by itself never creates a model or agent.
  let lastUnattributed = -2
  for (const e of annotations) {
    const n = e.nodeId ? byNode.get(e.nodeId) : single(models.filter(m => matchesModel(m, e)))
    if (n?.kind === 'model' && matchesModel(n, e)) {
      n[e.kind === 'reasoning' ? 'reasoning' : 'output'] += e.text
      continue
    }
    const previous = unattributed.at(-1)
    if (lastUnattributed + 1 === e.index && ['kind', 'nodeId', 'agentId', 'iteration'].every(key => previous[key] === e[key])) previous.text += e.text
    else unattributed.push({ kind: e.kind, text: e.text, at: e.at, nodeId: e.nodeId, agentId: e.agentId, iteration: e.iteration })
    lastUnattributed = e.index
  }
  for (const [index, n] of nodes.entries()) {
    const base = n.nodeId || (n.kind === 'tool' && n.callId ? `tool:${n.callId}` : `${n.kind}:${index}`)
    let id = base, suffix = 1
    while (usedIds.has(id)) id = `${base}#${++suffix}`
    usedIds.add(id); n.id = id
  }
  for (const n of nodes) n.parentIds = n.parentIds.map(id => byNode.get(id)).filter(p => p && p !== n).map(p => p.id)
  return { runId, attemptId, meta, nodes, byNode, byCall, sources, artifacts, unattributed, terminal, failure, elapsedMs, usage }
}

// Shared projection for the run and its artifact inspector. Only the latest
// attempt can be live; a subsequent successful run cannot finish older nodes.
export function traceAttempts(trace = [], options = {}) {
  const usedIds = new Set()
  const attempts = groupAttempts(trace, options).map(a => projectAttempt(a, options, usedIds))
  for (const a of attempts) {
    const latest = a === attempts.at(-1)
    const fallback = latest && (stateOf(options.finishReason) || stateOf(options.status))
    a.status = a.failure ? 'error' : a.terminal || (fallback && !pending(fallback) ? fallback : null) ||
      (latest && (options.live ?? pending(fallback)) ? 'running' : 'interrupted')
    if (!pending(a.status)) for (const n of a.nodes) if (pending(n.state)) {
      n.state = ['cancelled', 'limited'].includes(a.status) ? a.status : 'interrupted'
    }
  }
  return attempts
}

export function buildRunView(trace = [], options = {}) {
  const attempts = traceAttempts(trace, options), current = attempts.at(-1)
  const sources = new Map(), artifacts = new Map(), agentsById = new Map()
  mergeById(sources, options.sources); mergeById(artifacts, options.artifacts)
  const nodes = attempts.flatMap(a => a.nodes), edges = []
  let usage = tokenUsage(options.usage)
  for (const a of attempts) {
    mergeById(sources, a.sources.values()); mergeById(artifacts, a.artifacts.values())
    if (a.runId === current.runId && a.usage) usage = a.usage
  }
  for (const n of nodes) {
    for (const parent of n.parentIds) edges.push({ from: parent, to: n.id })
    if (n.kind === 'model' && n.agentId) {
      if (!agentsById.has(n.agentId)) agentsById.set(n.agentId, { id: n.agentId, nodes: [] })
      const a = agentsById.get(n.agentId)
      a.nodes.push(n); a.state = n.state; a.model = n.model || a.model
    }
  }
  const agents = [...agentsById.values()]
  for (const agent of agents) if (agent.nodes.some(n => n.state === 'running')) agent.state = 'running'
  const unattributed = attempts.flatMap(a => a.unattributed)
  const reasoning = attempts.flatMap(a => [...a.nodes.map(n => n.reasoning), ...a.unattributed.filter(e => e.kind === 'reasoning').map(e => e.text)]).filter(Boolean).join('\n\n')
  return {
    nodes, edges, steps: nodes, agents, meta: current?.meta || {}, usage, unattributed, reasoning,
    artifacts: [...artifacts.values()], sources: [...sources.values()],
    summary: {
      status: current?.status || stateOf(options.finishReason) || stateOf(options.status) || (options.live ? 'running' : 'idle'),
      error: current?.failure ?? null, steps: nodes.length, calls: nodes.filter(n => n.kind === 'model').length,
      tools: nodes.filter(n => n.kind === 'tool').length, agents: agents.length,
      runningAgents: agents.filter(a => a.state === 'running').length, elapsedMs: quantity(options.elapsedMs) ?? current?.elapsedMs ?? null,
      totalTokens: usage?.totalTokens ?? null, finishedSteps: nodes.filter(n => n.state === 'done').length
    }
  }
}

export function formatTraceNodes(nodes) {
  const lines = []
  for (const n of nodes) {
    lines.push([n.at == null ? '' : `${n.at}ms`, n.agentId, n.title, n.state, n.ms == null ? '' : `${n.ms}ms`].filter(Boolean).join(' · '))
    if (n.args) lines.push(JSON.stringify(n.args))
    if (n.detail) lines.push(n.detail)
    if (n.reasoning) lines.push(n.reasoning)
    if (n.output) lines.push(n.output)
  }
  return lines.join('\n')
}

export function formatTraceText(trace, options = {}) {
  const view = buildRunView(trace, options)
  return [view.meta.traceId && `Trace: ${view.meta.traceId}`, formatTraceNodes(view.nodes), ...view.unattributed.map(e => e.text),
    view.summary.totalTokens != null && `${view.summary.totalTokens} tokens`, view.summary.error].filter(Boolean).join('\n')
}

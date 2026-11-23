const stages = new Set(['model', 'tool', 'tools', 'context', 'knowledge', 'web_search', 'attachments', 'mock', 'collaboration', 'validate_output'])
const pending = state => ['running', 'queued', 'waiting_tool', 'waiting_approval', 'stop_requested'].includes(state)
const single = items => items.length === 1 ? items[0] : null
const quantity = value => Number.isFinite(value) && value >= 0 ? value : null

function eventOf(entry) {
  if (!entry || typeof entry !== 'object') return {}
  const { data, ...frame } = entry
  const e = data && typeof data === 'object'
    ? { ...data, ...frame, kind: frame.name || frame.event || frame.kind || data.kind }
    : { ...entry }
  if (e.kind === 'error' && (e.errorScope === 'transport' || e.scope === 'transport')) e.transport = true
  // Legacy flattened `at` may be a browser fallback. Only original wire data
  // or an explicit upstream provenance marker is eligible for T6 elapsed UI.
  e.reportedAt = !e.local && !e.transport && !e.fallbackAt && e.atReported !== false && !['local', 'browser', 'frontend', 'fallback'].includes(e.atSource) ? quantity(data && typeof data === 'object' ? data.at : e.atSource === 'backend' ? e.at : null) : null
  e.backendFrame = !!(data && typeof data === 'object' && !e.local && !e.transport)
  if (e.kind === 'stage') return { ...e, kind: e.phase }
  if (e.kind === 'tool_call') return { ...e, kind: 'tool', status: e.status || 'running' }
  if (e.kind === 'tool_result') return { ...e, kind: 'tool', status: e.status || 'unknown' }
  if (['node', 'agent_node'].includes(e.kind) && e.nodeId) return { ...e, kind: e.phase || e.nodeKind || 'task', phase: e.phase || e.nodeKind || 'task' }
  return e
}

function tokenUsage(e, previous = null) {
  if (!e) return previous
  if (e.usageReported === false) return previous
  const input = quantity(e.inputTokens), output = quantity(e.outputTokens), total = quantity(e.totalTokens)
  if (input === null && output === null && total === null) return previous
  const inputTokens = input ?? previous?.inputTokens ?? null
  const outputTokens = output ?? previous?.outputTokens ?? null
  const totalTokens = total ?? previous?.totalTokens ?? null
  return { inputTokens, outputTokens, totalTokens }
}

function stateOf(value) {
  switch (String(value || '').toLowerCase()) {
    case 'queued': case 'pending': return 'queued'
    case 'start': case 'running': case 'streaming': return 'running'
    case 'failed': case 'error': case 'invalid_output': return 'error'
    case 'timeout': return 'interrupted'
    case 'stop_requested': return 'stop_requested'
    case 'cancel': case 'aborted': case 'cancelled': case 'canceled': return 'cancelled'
    case 'partial': return 'partial'
    case 'waiting_tool': case 'waiting_approval': case 'stop_requested': case 'unknown': return String(value).toLowerCase()
    case 'interrupted': return 'interrupted'
    case 'length': case 'limit': case 'limited': case 'truncated': case 'max_tokens': case 'max_output_tokens':
    case 'max_iterations': case 'content_filter': return 'limited'
    // The tool catalog could not be read, so the turn went on without tools.
    case 'skipped': case 'unavailable': return 'skipped'
    case 'succeeded': case 'success': case 'done': case 'stop': case 'completed': case 'tool_calls': return 'done'
    default: return null
  }
}

function stageState(e) {
  const status = stateOf(e.status), finish = stateOf(e.finishReason)
  // A role's explicit partial/wait/cancel verdict is not the vendor's generic
  // finishReason=stop. Error detail also must not erase a cancelled/limited state.
  // "unknown" is the absence of a verdict, so a reported error still decides.
  if (status && !['done', 'error', 'unknown'].includes(status)) return status
  if (e.status != null && !status) return 'unknown'
  if (e.error || status === 'error' || finish === 'error') return 'error'
  if (finish || status) return finish || status
  // Statistics are not a reported node verdict. Missing status stays unknown.
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
    const actualId = e.attemptId ?? null, traceId = e.traceId ?? null
    let a = actualId != null ? group.find(item => item.reportedAttemptId === actualId) : traceId != null ? single(group.filter(item => item.traceIds.has(traceId))) : group.at(-1)
    let linked = false
    // Only a frame carrying BOTH identities can associate the two namespaces.
    if (actualId != null && traceId != null) {
      const legacy = single(group.filter(item => item.reportedAttemptId == null && item.traceIds.has(traceId)))
      if (legacy && a && legacy !== a) {
        const sequenced = new Map(), unsequenced = []
        for (const event of [...legacy.events, ...a.events]) {
          if (event.sequence == null) unsequenced.push(event)
          else sequenced.set(event.sequence, event)
        }
        a.events = [...sequenced.values()].sort((x, y) => x.sequence - y.sequence).concat(unsequenced)
        a.seen = new Set(sequenced.keys()); a.meta = { ...legacy.meta, ...a.meta }
        legacy.traceIds.forEach(id => a.traceIds.add(id))
        group.splice(group.indexOf(legacy), 1); attempts.splice(attempts.indexOf(legacy), 1)
        linked = true
      } else if (!a && legacy) { a = legacy; linked = true }
    }
    if (actualId == null && traceId == null && e.kind === 'meta' && e.resumed && !(a?.meta.resumed && e.sequence != null && a.meta.sequence === e.sequence)) a = null
    if (!a) {
      a = { runId, attemptId: actualId ?? traceId, reportedAttemptId: actualId, traceIds: new Set(traceId == null ? [] : [traceId]), meta: {}, seen: new Set(), events: [] }
      group.push(a); byRun.set(runId, group); attempts.push(a)
    }
    if (actualId != null) { a.reportedAttemptId = actualId; a.attemptId = actualId }
    if (traceId != null) a.traceIds.add(traceId)
    if (e.sequence != null && a.seen.has(e.sequence)) {
      if (linked && e.kind === 'meta') a.meta = { ...a.meta, ...e }
      continue
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

function projectAttempt({ runId, attemptId, reportedAttemptId, meta, events }, options, usedIds) {
  const nodes = [], models = [], annotations = [], unattributed = []
  const byNode = new Map(), byCall = new Map(), nodeMembers = new Map(), sources = new Map(), artifacts = new Map()
  const elapsed = elapsedClock(meta.startedAt ?? options.startedAt)
  let terminal = null, localTerminal = null, failure = null, usage = null, elapsedMs = null
  const scopedEvidence = item => {
    if (!item?.id || item.runId != null && runId != null && item.runId !== runId || item.attemptId != null && item.attemptId !== reportedAttemptId || item.traceId != null && meta.traceId != null && item.traceId !== meta.traceId) return null
    const result = { ...item }
    if (runId != null) result.runId ??= runId
    if (reportedAttemptId != null) result.attemptId ??= reportedAttemptId
    else if (meta.traceId != null) result.traceId ??= meta.traceId
    return result
  }

  for (const [index, e] of events.entries()) {
    const at = elapsed(e.at)
    if (at !== null) elapsedMs = Math.max(elapsedMs ?? 0, at)
    if (e.kind === 'sources') { mergeById(sources, (Array.isArray(e.items) ? e.items : []).map(scopedEvidence).filter(Boolean)); continue }
    if (e.kind === 'artifact') {
      if (e.artifact?.id) {
        const item = scopedEvidence(e.artifact)
        if (!item) continue
        for (const key of ['nodeId', 'agentId', 'parentIds', 'sourceIds', 'callIds']) if (item[key] == null && e[key] != null) item[key] = e[key]
        if (runId != null) item.runId ??= runId
        if (reportedAttemptId != null) item.attemptId ??= reportedAttemptId
        else if (meta.traceId != null) item.traceId ??= meta.traceId
        artifacts.set(item.id, item)
      }
      continue
    }
    if (e.kind === 'usage') { usage = tokenUsage(e, usage); continue }
    if (e.kind === 'done') {
      if (!e.local && !e.transport) terminal = stageState(e) || 'interrupted'
      else if (e.local) localTerminal = stageState(e) || 'interrupted'
      continue
    }
    if (['cancel', 'cancelled', 'canceled'].includes(e.kind)) {
      if (!e.local && !e.transport) terminal = 'cancelled'
      else if (e.local) localTerminal = 'cancelled'
      continue
    }
    if (e.kind === 'error') {
      const matches = e.nodeId || e.callId || !e.agentId ? [] : models.filter(n => matchesModel(n, e))
      const target = e.callId ? byCall.get(e.callId) : e.nodeId ? byNode.get(e.nodeId) : single(matches.filter(n => pending(n.state))) || single(matches)
      const detail = e.message || e.code || 'error'
      if (target && !e.transport && !e.local) Object.assign(target, { state: 'error', reportedState: 'error', detail })
      const role = e.agentId || target?.agentId
      // A transport fault (a stream that ended early) is not the run's own verdict: the
      // recorded status already says interrupted or error, and only that decides.
      const tier = typeof e.errorScope === 'string' ? e.errorScope : typeof e.scope === 'string' && ['run', 'agent', 'role', 'tool', 'transport'].includes(e.scope) ? e.scope : null
      const runError = tier ? tier === 'run' : ((!role && !e.nodeId && !e.callId) || (target?.kind !== 'tool' && !e.callId && ['assistant', 'synth'].includes(role)))
      if (!e.transport && !e.local && runError) failure = detail
      continue
    }
    if (e.kind === 'reasoning' || e.kind === 'agent_output') {
      if (typeof e.text === 'string' && e.text) annotations.push({ kind: e.kind, nodeId: e.nodeId, callId: e.callId, agentId: e.agentId, iteration: e.iteration, text: e.text, at, index })
      continue
    }
    if (!stages.has(e.kind) && !(e.nodeId && e.phase === e.kind && e.phase)) continue

    let n = e.callId ? byCall.get(e.callId) : null
    if (n && n.kind !== e.kind) n = null
    if (!n && e.nodeId) {
      const candidates = [...(nodeMembers.get(e.nodeId) || [])].filter(node => node.kind === e.kind && (!e.callId || !node.callId || node.callId === e.callId))
      n = single(candidates)
      if (!e.callId && candidates.length > 1) continue // Ambiguous completion is not another invocation.
    }
    const state = stageState(e)
    if (!n && !e.nodeId && e.kind === 'model' && !pending(state)) {
      const open = models.filter(m => pending(m.state) && matchesModel(m, e))
      if (open.length > 1) continue // An unaddressed completion is not evidence of an additional call.
      n = single(open)
    }
    if (!n) {
      n = { kind: e.kind, agentId: e.agentId ?? null, reportedAgentId: e.agentId ?? null, parentIds: [],
        runId: runId ?? null, attemptId: reportedAttemptId ?? null, traceId: meta.traceId ?? null, usageReports: [],
        at, reportedAt: e.reportedAt != null && e.reportedAt < 1e11 ? e.reportedAt : null, ms: null, state: 'unknown', title: '', reasoning: '', output: '', detail: '', usage: null }
      nodes.push(n)
      if (n.kind === 'model') models.push(n)
    }
    if (e.nodeId) {
      n.nodeId = e.nodeId
      if (!nodeMembers.has(e.nodeId)) nodeMembers.set(e.nodeId, new Set())
      nodeMembers.get(e.nodeId).add(n)
      const members = [...nodeMembers.get(e.nodeId)], domainNodes = members.filter(node => node.kind !== 'tool')
      const canonical = single(domainNodes) || (domainNodes.length === 0 ? single(members) : null)
      if (canonical) byNode.set(e.nodeId, canonical)
      else byNode.delete(e.nodeId)
    }
    if (e.callId) byCall.set(e.callId, n)
    if (Array.isArray(e.parentIds)) n.parentIds = [...new Set([...n.parentIds, ...e.parentIds])]
    for (const key of ['agentId', 'model', 'provider', 'toolName', 'callId', 'args', 'iteration', 'shortTerm', 'recalled', 'chunks', 'finishReason', 'role', 'agentName', 'serverId', 'sourceIds', 'artifactIds', 'callIds', 'reused', 'producerAttemptId']) {
      if (e[key] != null) n[key] = e[key]
    }
    if (state) n.reportedState = state
    if (e.agentId != null) n.reportedAgentId = e.agentId
    if (e.title != null) n.reportedTitle = e.title
    if (state === 'cancelled' && (e.backendFrame || e.stateSource === 'backend')) n.backendStateReported = true
    if (e.usage || ['inputTokens', 'outputTokens', 'totalTokens'].some(key => e[key] != null)) {
      const report = e.usage ? { ...e.usage, usageReported: e.usageReported ?? e.usage.usageReported } : { inputTokens: e.inputTokens, outputTokens: e.outputTokens, totalTokens: e.totalTokens, usageReported: e.usageReported }
      // Only an explicit upstream legacy-accumulator provenance marker permits
      // zero-to-unknown conversion. Missing identity alone is not provenance.
      if (e.kind === 'model' && (report.usageSource === 'legacy_accumulator' || e.usageSource === 'legacy_accumulator') && report.usageReported == null) {
        for (const key of ['inputTokens', 'outputTokens', 'totalTokens']) if (report[key] === 0) report[key] = null
      }
      n.usageReports.push(report)
    }
    n.title = e.toolName || e.model || e.title || n.title || e.provider || e.phase || e.kind
    n.detail = e.error || e.message || n.detail
    for (const key of ['reused', 'producerAttemptId', 'producerNodeId', 'sourceAttemptId', 'sourceNodeId']) {
      if (e[key] != null) n[key] = e[key]
    }
    n.ms = e.ms ?? e.latencyMs ?? n.ms
    n.usage = tokenUsage(n.usageReports.at(-1) ?? e, n.usage)
    if (state && n.state !== 'error' && (state !== 'unknown' || pending(n.state))) n.state = state
  }

  // Resolve annotations against actual calls, including persisted frames whose
  // model stage arrives later. Text by itself never creates a model or agent.
  let lastUnattributed = -2
  for (const e of annotations) {
    const n = e.callId ? byCall.get(e.callId) : e.nodeId ? byNode.get(e.nodeId) : single(models.filter(m => matchesModel(m, e)))
    if (n?.kind === 'model' && matchesModel(n, e)) {
      n[e.kind === 'reasoning' ? 'reasoning' : 'output'] += e.text
      continue
    }
    const previous = unattributed.at(-1)
    if (lastUnattributed + 1 === e.index && ['kind', 'nodeId', 'callId', 'agentId', 'iteration'].every(key => previous[key] === e[key])) previous.text += e.text
    else unattributed.push({ kind: e.kind, text: e.text, at: e.at, nodeId: e.nodeId, callId: e.callId, agentId: e.agentId, iteration: e.iteration })
    lastUnattributed = e.index
  }
  for (const [index, n] of nodes.entries()) {
    const base = n.nodeId || (n.kind === 'tool' && n.callId ? `tool:${n.callId}` : `${n.kind}:${index}`)
    let id = base, suffix = 1
    while (usedIds.has(id)) id = `${base}#${++suffix}`
    usedIds.add(id); n.id = id
  }
  for (const n of nodes) {
    n.reportedParentIds = [...n.parentIds]
    n.unresolvedParentIds = n.parentIds.filter(id => !byNode.has(id) || byNode.get(id) === n)
    n.parentIds = n.parentIds.map(id => byNode.get(id)).filter(p => p && p !== n).map(p => p.id)
  }
  return { runId, attemptId, reportedAttemptId, meta, events, nodes, byNode, byCall, sources, artifacts, unattributed, terminal: terminal ?? (localTerminal === 'cancelled' ? 'stop_requested' : localTerminal), backendTerminal: terminal, failure, elapsedMs, usage }
}

// Shared projection for the run and its artifact inspector. Only the latest
// attempt can be live; a subsequent successful run cannot finish older nodes.
export function traceAttempts(trace = [], options = {}) {
  const usedIds = new Set()
  const attempts = groupAttempts(trace, options).map(a => projectAttempt(a, options, usedIds))
  for (const a of attempts) {
    const latest = a === attempts.at(-1)
    const fallback = latest && (stateOf(options.finishReason) || stateOf(options.status))
    a.status = a.failure ? 'error' : a.terminal || (fallback && (!pending(fallback) || ['waiting_tool', 'waiting_approval', 'stop_requested'].includes(fallback)) ? fallback : null) ||
      (latest && (options.live ?? pending(fallback)) ? 'running' : 'interrupted')
    if (!a.backendTerminal && !a.failure && fallback === 'cancelled' && options.statusSource !== 'backend') a.status = 'stop_requested'
    if (a.status === 'stop_requested' && !a.backendTerminal) for (const n of a.nodes) if (n.state === 'cancelled' && !n.backendStateReported) n.state = 'stop_requested'
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
  let usage = current?.usage ?? ((!current || !current.meta.resumed && attempts.length === 1 || options.usageAttemptId != null && options.usageAttemptId === current.reportedAttemptId) ? tokenUsage(options.usage) : null)
  for (const a of attempts) {
    mergeById(sources, a.sources.values()); mergeById(artifacts, a.artifacts.values())
  }
  for (const n of nodes) {
    for (const parent of n.parentIds) edges.push({ from: parent, to: n.id })
    if (n.kind === 'model' && n.agentId) {
      if (!agentsById.has(n.agentId)) agentsById.set(n.agentId, { id: n.agentId, nodes: [] })
      const a = agentsById.get(n.agentId)
      a.nodes.push(n); a.state = n.state; a.model = n.model || a.model
    }
  }
  const counters = {}
  for (const a of groupAttempts(trace, options)) for (const e of a.events) {
    if (e.kind !== 'usage' && e.kind !== 'meta') continue
    for (const key of ['modelCalls', 'toolCallCount', 'reusedToolCallCount', 'usageReported', 'usageComplete']) {
      if (Object.hasOwn(e, key)) counters[key] = e[key]
    }
  }
  for (const key of ['modelCalls', 'toolCallCount', 'reusedToolCallCount', 'usageReported', 'usageComplete']) {
    if (Object.hasOwn(options, key)) counters[key] = options[key]
    else if (Object.hasOwn(options.usage || {}, key)) counters[key] = options.usage[key]
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
      error: current?.failure ?? null, steps: nodes.length, calls: quantity(counters.modelCalls), modelNodes: nodes.filter(n => n.kind === 'model').length,
      tools: quantity(counters.toolCallCount), toolNodes: nodes.filter(n => n.kind === 'tool').length,
      modelCalls: quantity(counters.modelCalls), toolCallCount: quantity(counters.toolCallCount),
      reusedToolCallCount: quantity(counters.reusedToolCallCount),
      usageReported: counters.usageReported ?? null, usageComplete: counters.usageComplete ?? null, agents: agents.length,
      runningAgents: agents.filter(a => a.state === 'running').length, elapsedMs: quantity(options.elapsedMs) ?? current?.elapsedMs ?? null,
      totalTokens: usage?.totalTokens ?? null, finishedSteps: nodes.filter(n => n.state === 'done').length
    }
  }
}

export function formatTraceNodes(nodes) {
  const lines = []
  for (const n of nodes) {
    lines.push([n.reportedAt == null ? '' : `${n.reportedAt}ms`, n.agentId, n.title, n.state, n.ms == null ? '' : `${n.ms}ms`].filter(Boolean).join(' · '))
    if (n.args) lines.push(JSON.stringify(n.args))
    if (n.detail) lines.push(n.detail)
    if (n.reasoning) lines.push('Model-native reasoning (not an execution audit):', n.reasoning)
    if (n.output) lines.push(n.output)
  }
  return lines.join('\n')
}

export function formatTraceText(trace, options = {}) {
  const view = buildRunView(trace, options)
  return [view.meta.traceId && `Trace: ${view.meta.traceId}`, formatTraceNodes(view.nodes), ...view.unattributed.map(e => e.text),
    view.summary.totalTokens != null && `${view.summary.totalTokens} tokens`, view.summary.error].filter(Boolean).join('\n')
}

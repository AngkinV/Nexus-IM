import { buildRunView, traceAttempts } from './runView.js'

// Read-only T6 contract. Input is the existing Java SSE/persisted trace or its
// legacy store projection. No requests, scheduling, clock, or recovery mutations.
// Missing reports stay null. A DOM key is not a backend recovery identifier.
const list = value => Array.isArray(value) ? value : []
const quantity = value => Number.isFinite(value) && value >= 0 ? value : null
const text = value => typeof value === 'string' && value.trim() ? value : null
const own = (value, key) => value != null && Object.hasOwn(value, key)
const states = new Set(['queued', 'running', 'waiting_tool', 'waiting_approval', 'stop_requested', 'succeeded', 'partial', 'failed', 'cancelled', 'unknown', 'interrupted', 'limited', 'skipped', 'idle'])

export function displayState(value) {
  const raw = String(value || '').toLowerCase()
  if (states.has(raw)) return raw
  if (['start', 'streaming'].includes(raw)) return 'running'
  if (['done', 'success', 'stop', 'completed', 'tool_calls'].includes(raw)) return 'succeeded'
  if (['error', 'timeout', 'invalid_output'].includes(raw)) return 'failed'
  if (['pending'].includes(raw)) return 'queued'
  if (['canceled', 'cancel', 'aborted'].includes(raw)) return 'cancelled'
  if (['length', 'max_tokens', 'max_output_tokens', 'max_iterations', 'truncated', 'content_filter', 'limit'].includes(raw)) return 'limited'
  if (raw === 'unavailable') return 'skipped'
  return 'unknown'
}

// Unlike the legacy projection, do not derive a missing total or borrow fields
// from another attempt. Explicit reported zero is different from absent usage.
export function reportedUsage(value, previous = null) {
  if (!value || value.usageReported === false) return previous
  const payload = value.usage && typeof value.usage === 'object' ? { ...value.usage, usageReported: value.usageReported ?? value.usage.usageReported } : value
  if (payload.usageReported === false) return previous
  const keys = ['inputTokens', 'outputTokens', 'totalTokens']
  const counts = keys.map(key => quantity(payload[key]))
  if (counts.every(count => count === null)) return previous
  const result = {}
  keys.forEach((key, i) => { result[key] = counts[i] ?? previous?.[key] ?? null })
  return result
}

export function evidenceText(value) {
  if (value == null) return null
  if (typeof value === 'string') return value || null
  try {
    return JSON.stringify(value, (key, item) => /^(?:api[_-]?key|authorization|pass(?:word|wd)|access[_-]?token|refresh[_-]?token|secret|client[_-]?secret|credential)$/i.test(key) ? '[redacted]' : item, 2)
  } catch { return null }
}

function sameScope(item, attempt) {
  return (item.runId == null || item.runId === attempt.runId) &&
    (item.attemptId == null || item.attemptId === attempt.reportedAttemptId) &&
    (item.traceId == null || item.traceId === attempt.meta.traceId)
}

function actualAttemptKey(attempt, index) {
  return JSON.stringify([attempt.runId ?? null, attempt.reportedAttemptId ?? null,
    attempt.reportedAttemptId == null ? attempt.meta.traceId ?? `legacy-attempt:${index}` : null])
}

function workIdentity(node, attempt, attemptKey) {
  const stableNode = text(node.nodeId) || (node.kind === 'tool' ? text(node.callId) : null)
  const identityComplete = attempt.runId != null && attempt.reportedAttemptId != null && stableNode != null
  return { id: JSON.stringify([attemptKey, node.kind, node.nodeId ? 'node' : node.callId ? 'call' : 'legacy', stableNode ?? node.id, node.callId ?? null]),
    identityComplete, runId: attempt.runId ?? null, attemptId: attempt.reportedAttemptId ?? null,
    traceId: attempt.meta.traceId ?? null, nodeId: node.nodeId ?? null, callId: node.callId ?? null }
}

// Topological depth uses only resolved, reported parent edges. Missing parents
// and cyclic reports remain visible; neither becomes a fabricated task node.
export function dependencyLayers(nodes = [], edges = []) {
  const byId = new Map(nodes.map(node => [node.id, node]))
  const incoming = new Map(nodes.map(node => [node.id, new Set()]))
  const outgoing = new Map(nodes.map(node => [node.id, new Set()]))
  for (const edge of edges) {
    if (!byId.has(edge.from) || !byId.has(edge.to) || edge.from === edge.to) continue
    incoming.get(edge.to).add(edge.from); outgoing.get(edge.from).add(edge.to)
  }
  const pending = new Map([...incoming].map(([id, parents]) => [id, parents.size]))
  const depth = new Map(), visited = new Set()
  const queue = nodes.filter(node => pending.get(node.id) === 0).map(node => node.id)
  for (let i = 0; i < queue.length; i++) {
    const id = queue[i]; visited.add(id)
    const level = Math.max(0, ...[...incoming.get(id)].map(parent => (depth.get(parent) ?? -1) + 1))
    depth.set(id, level)
    for (const child of outgoing.get(id)) {
      pending.set(child, pending.get(child) - 1)
      if (pending.get(child) === 0) queue.push(child)
    }
  }
  const groups = new Map()
  for (const node of nodes) {
    if (!visited.has(node.id)) continue
    const level = depth.get(node.id)
    if (!groups.has(level)) groups.set(level, [])
    groups.get(level).push(node)
  }
  const layers = [...groups].sort(([a], [b]) => a - b).map(([level, items]) => ({ key: `layer:${level}`, level, nodes: items, unresolved: false }))
  const unresolved = nodes.filter(node => !visited.has(node.id))
  if (unresolved.length) layers.push({ key: 'unresolved-cycle', level: null, nodes: unresolved, unresolved: true })
  return { layers, cyclicNodeIds: unresolved.map(node => node.id) }
}

function cancellationEvidence(attempt, options) {
  const events = attempt?.events || []
  const confirmed = events.some(event => !event.local && !event.transport &&
    ((event.kind === 'done' && displayState(event.finishReason || event.status) === 'cancelled') ||
      ['cancelled', 'canceled'].includes(event.kind))) ||
    (options.statusSource === 'backend' && displayState(options.status) === 'cancelled')
  const local = events.some(event => event.local && (event.kind === 'done' || ['cancel', 'cancelled'].includes(event.kind)))
  const transport = events.some(event => event.transport)
  const settled = attempt?.backendTerminal != null || !!attempt?.failure || events.some(event => !event.local && !event.transport && event.kind === 'done')
  const unconfirmed = !confirmed && !settled && (local || displayState(options.status) === 'cancelled')
  return { confirmed, local, transport, unconfirmed }
}

function reportedElapsed(attempt, options) {
  if (['java_wall', 'backend_wall'].includes(options.elapsedSource) && quantity(options.elapsedMs) !== null) {
    return { ms: options.elapsedMs, basis: 'wall' }
  }
  const offsets = (attempt?.events || []).filter(event => !event.local && !event.transport).map(event => quantity(event.reportedAt)).filter(value => value !== null && value < 1e11)
  return offsets.length ? { ms: Math.max(...offsets), basis: 'event' } : { ms: null, basis: null }
}

export function buildRunPresentation(trace = [], options = {}) {
  const view = buildRunView(trace, options)
  const attempts = traceAttempts(trace, options)
  const current = attempts.at(-1)
  const cancel = cancellationEvidence(current, options)
  const hasLifecycle = !!(current?.backendTerminal || current?.failure || cancel.local || cancel.transport || options.status || options.finishReason || options.live)
  let state = displayState(view.summary.status)
  if (!hasLifecycle || !current && options.live && options.statusSource !== 'backend') state = 'unknown'
  if (current && !current.backendTerminal && !current.failure && state === 'running') {
    const reported = displayState(current.meta.status ?? current.meta.state ?? (options.statusSource === 'backend' ? options.status : null))
    const open = [...new Set(current.nodes.map(node => displayState(node.state)).filter(value => ['running', 'queued', 'waiting_tool', 'waiting_approval', 'stop_requested'].includes(value)))]
    state = reported !== 'unknown' ? reported : open.includes('running') ? 'running' : open.length === 1 ? open[0] : 'unknown'
  }
  if (cancel.transport && !current?.backendTerminal && !current?.failure && options.statusSource !== 'backend') state = 'interrupted'
  if (cancel.unconfirmed) state = cancel.transport ? 'interrupted' : 'stop_requested'
  if (cancel.transport && state === 'cancelled' && !cancel.confirmed) state = 'interrupted'
  const nodes = [], edges = [], artifacts = [], sources = [], roles = new Map()
  const seenArtifacts = new Set(), seenSources = new Set()
  const append = (target, seen, item, key) => { if (!seen.has(key)) { seen.add(key); target.push({ ...item, evidenceKey: key }) } }

  attempts.forEach((attempt, index) => {
    const attemptKey = actualAttemptKey(attempt, index)
    const cancellation = cancellationEvidence(attempt, attempt === current ? options : {})
    const localArtifacts = [...attempt.artifacts.values()].filter(item => sameScope(item, attempt))
    const localSources = [...attempt.sources.values()].filter(item => sameScope(item, attempt))
    // A run-level list has no implicit attempt/node association. Only an explicit
    // scope or a matching recorded item lets it participate in node evidence.
    for (const item of list(options.artifacts)) {
      const explicitlyScoped = item.attemptId != null || item.traceId != null
      if (explicitlyScoped && sameScope(item, attempt) && !localArtifacts.some(found => found.id === item.id)) localArtifacts.push(item)
    }
    for (const item of list(options.sources)) {
      const explicitlyScoped = item.attemptId != null || item.traceId != null
      if (explicitlyScoped && sameScope(item, attempt) && !localSources.some(found => found.id === item.id)) localSources.push(item)
    }
    localArtifacts.forEach(item => append(artifacts, seenArtifacts, item, JSON.stringify([attemptKey, item.id])))
    localSources.forEach(item => append(sources, seenSources, item, JSON.stringify([attemptKey, item.id])))
    const identities = new Map(attempt.nodes.map(node => [node.id, workIdentity(node, attempt, attemptKey)]))
    for (const node of attempt.nodes) {
      const identity = identities.get(node.id)
      const agentId = text(node.reportedAgentId)
      let nodeState = displayState(attempt === current && !hasLifecycle && node.reportedState != null ? node.reportedState : node.state)
      if (cancellation.unconfirmed && nodeState === 'cancelled' && !node.backendStateReported) nodeState = cancellation.transport ? 'interrupted' : 'stop_requested'
      const sourceIds = list(node.sourceIds)
      // Backend extract_artifacts names the associated model node, while callIds
      // contains related TOOL evidence (orchestrator.py _emit_artifacts). These
      // are different link types, never multiple claims of artifact production.
      const nodeArtifacts = localArtifacts.flatMap(artifact => {
        const nodeMatch = node.nodeId != null && artifact.nodeId === node.nodeId && attempt.byNode.get(artifact.nodeId) === node && (!artifact.agentId || agentId == null || artifact.agentId === agentId)
        const callMatch = node.callId != null && list(artifact.callIds).includes(node.callId)
        const unconstrained = artifact.nodeId == null && !list(artifact.callIds).length
        const relation = nodeMatch ? 'node_association' : callMatch ? 'call_association' : unconstrained && list(node.artifactIds).includes(artifact.id) ? 'reported_association' : null
        return relation ? [{ ...artifact, relation }] : []
      })
      const item = { ...node, ...identity, legacyId: node.id, attemptKey, agentId,
        title: text(node.reportedTitle) || text(node.model) || text(node.toolName) || text(node.title),
        role: text(node.role) || text(node.agentName), state: nodeState,
        lastReportedState: node.reportedState == null ? null : displayState(node.reportedState),
        stateBasis: nodeState === displayState(node.reportedState) ? 'reported' : 'run_terminal',
        ms: quantity(node.ms), usage: list(node.usageReports).reduce((usage, report) => reportedUsage(report, usage), null),
        sourceIds, sources: localSources.filter(source => sourceIds.includes(source.id)), artifacts: nodeArtifacts,
        parentIds: node.parentIds.map(id => identities.get(id)?.id).filter(Boolean),
        reportedParentIds: list(node.reportedParentIds), unresolvedParentIds: list(node.unresolvedParentIds),
        cancellationConfirmed: nodeState === 'cancelled' && (cancellation.confirmed || node.backendStateReported === true) }
      nodes.push(item)
      for (const from of item.parentIds) edges.push({ from, to: item.id, evidence: 'parentIds' })
      if (agentId) {
        if (!roles.has(agentId)) roles.set(agentId, { id: agentId, title: item.role || agentId, nodes: [] })
        roles.get(agentId).nodes.push(item)
      }
    }
  })
  // Unattributed lists are still real artifacts/sources, not invented producer
  // nodes. Keep them available without falsely assigning them to a role.
  for (const item of list(options.artifacts)) {
    if (!artifacts.some(found => found.id === item.id && (item.runId == null || item.runId === found.runId))) append(artifacts, seenArtifacts, item, JSON.stringify(['run-list', options.runId ?? null, item.id]))
  }
  for (const item of list(options.sources)) {
    if (!sources.some(found => found.id === item.id && (item.runId == null || item.runId === found.runId))) append(sources, seenSources, item, JSON.stringify(['run-list', options.runId ?? null, item.id]))
  }
  let usage = null
  for (const event of current?.events || []) if (event.kind === 'usage') usage = reportedUsage(event, usage)
  if (!usage && (!current || (!current.meta.resumed && attempts.length === 1) || options.usageAttemptId === current.reportedAttemptId && current.reportedAttemptId != null)) usage = reportedUsage(options.usage)
  const meta = current?.meta || {}
  const task = text(meta.task) || text(meta.input) || text(options.input) || null
  const scope = own(meta, 'scope') ? meta.scope : options.scopeSource === 'backend' && (!current || !current.meta.resumed && attempts.length === 1 || options.scopeAttemptId != null && options.scopeAttemptId === current.reportedAttemptId) ? options.scope ?? null : null
  const dag = dependencyLayers(nodes, edges)
  return { nodes, edges, layers: dag.layers, cyclicNodeIds: dag.cyclicNodeIds,
    roles: [...roles.values()], artifacts, sources, unattributed: view.unattributed,
    reasoning: view.reasoning || null, meta, task, scope,
    summary: { state, cancel, elapsed: reportedElapsed(current, options), usage,
      error: view.summary.error ?? null, recordedNodes: nodes.length,
      recordedModelCalls: nodes.filter(node => node.kind === 'model').length,
      recordedToolCalls: nodes.filter(node => node.kind === 'tool').length,
      reportedModelCalls: quantity(meta.modelCalls ?? meta.stats?.modelCalls),
      roles: roles.size, runningRoles: [...roles.values()].filter(role => role.nodes.some(node => node.state === 'running' && node.attemptKey === actualAttemptKey(current, attempts.length - 1))).length,
      attemptId: current?.reportedAttemptId ?? null, runId: current?.runId ?? options.runId ?? null }
  }
}

export function formatPresentationText(presentation, labels = {}) {
  const lines = []
  for (const node of presentation.nodes) {
    lines.push([node.runId, node.attemptId, node.nodeId || node.callId, node.agentId, node.title, node.state, node.ms == null ? null : `${node.ms}ms`].filter(value => value != null).join(' · '))
    if (node.args != null) lines.push(evidenceText(node.args))
    if (node.detail) lines.push(node.detail)
    if (node.reasoning) lines.push(labels.reasoning || 'Model-native reasoning (not an execution audit):', node.reasoning)
    if (node.output) lines.push(node.output)
  }
  for (const part of presentation.unattributed) {
    if (part.kind === 'reasoning') lines.push(labels.reasoning || 'Model-native reasoning (not an execution audit):')
    lines.push(part.text)
  }
  if (presentation.summary.error) lines.push(presentation.summary.error)
  return lines.filter(Boolean).join('\n')
}

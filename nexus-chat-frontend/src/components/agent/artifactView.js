import { traceAttempts, formatTraceNodes } from './runView.js'

const list = value => Array.isArray(value) ? value : []
const matchesScope = (item, a) => (item.runId == null || item.runId === a.runId) &&
  (item.attemptId == null || item.attemptId === a.attemptId) &&
  (item.traceId == null || item.traceId === (a.meta.traceId ?? a.attemptId))

// A graph without a producer may describe the execution itself. Its recorded
// members (not its name or kind) are the allowlist; the trace supplies the facts.
function graphMembers(item, attempt) {
  if (item.kind !== 'graph') return []
  try {
    const data = JSON.parse(item.content)
    if (!data || !matchesScope(data, attempt) || !Array.isArray(data.edges)) return []
    return list(data.nodes).map(n => n?.id)
  } catch { return [] }
}

function artifactScope(artifact, run) {
  if (!artifact || !run?.trace?.length || (run.runId != null && artifact.runId != null && artifact.runId !== run.runId)) return null
  const attempts = traceAttempts(run.trace, run)
  const candidates = attempts.filter(a => (run.runId == null || a.runId === run.runId) && matchesScope(artifact, a))
  const recorded = candidates.filter(a => a.artifacts.has(artifact.id))
  const scopes = []
  for (const a of recorded.length ? recorded : candidates) {
    const item = { ...artifact, ...a.artifacts.get(artifact.id) }
    if (!matchesScope(item, a)) continue
    const roots = new Set(), byId = new Map(a.nodes.map(n => [n.id, n]))
    const parentIds = list(item.parentIds), callIds = list(item.callIds)
    const producer = a.byNode.get(item.nodeId)
    if (producer && (!item.agentId || producer.agentId === item.agentId)) roots.add(producer.id)
    for (const id of parentIds) if (a.byNode.has(id)) roots.add(a.byNode.get(id).id)
    for (const id of callIds) if (a.byCall.has(id)) roots.add(a.byCall.get(id).id)
    if (!item.nodeId && !parentIds.length && !callIds.length) {
      for (const id of graphMembers(item, a)) if (a.byNode.has(id)) roots.add(a.byNode.get(id).id)
    }
    for (const id of roots) for (const parent of byId.get(id).parentIds) roots.add(parent)
    const nodes = a.nodes.filter(n => roots.has(n.id))
    const edges = nodes.flatMap(n => n.parentIds.map(from => ({ from, to: n.id })))
    const sources = []
    for (const id of new Set(list(item.sourceIds))) {
      const source = a.sources.get(id) || list(run.sources).find(s => s?.id === id && matchesScope(s, a) &&
        (s.attemptId != null || s.traceId != null || !attempts.some(other => other !== a && other.sources.has(id))))
      if (source && matchesScope(source, a)) sources.push(source)
    }
    if (nodes.length || sources.length) scopes.push({ runId: a.runId, nodes, edges, sources })
  }
  // Reused raw IDs alone cannot distinguish two producing attempts.
  return scopes.length === 1 ? scopes[0] : null
}

export function artifactTabs(artifact, run) {
  const ids = artifact?.kind === 'patch' ? ['diff', 'raw'] : ['raw']
  const scope = artifactScope(artifact, run)
  if (scope?.nodes.length) ids.push('log')
  if (scope) ids.push('graph')
  return ids.map(id => ({ id }))
}

export function artifactText(artifact, tab, run = {}) {
  if (!artifact) return ''
  if (tab === 'raw' || (tab === 'diff' && artifact.kind === 'patch')) return artifact.content ?? ''
  if (tab !== 'log' && tab !== 'graph') return ''
  const scope = artifactScope(artifact, run)
  if (!scope) return ''
  if (tab === 'log') return formatTraceNodes(scope.nodes)
  return JSON.stringify({
    runId: scope.runId, artifactId: artifact.id,
    nodes: scope.nodes.map(({ id, kind, agentId, title, state }) => ({ id, kind, agentId, title, state })),
    edges: scope.edges, sources: scope.sources
  }, null, 2)
}

export function artifactFileName(artifact) {
  const filename = String(artifact.name || '').split(/[\\/]/).pop().replace(/[\u0000-\u001f\u007f]/g, '').trim()
  return filename && filename !== '.' && filename !== '..' ? filename : artifact.kind === 'graph' ? 'execution-graph.json' : 'artifact.txt'
}

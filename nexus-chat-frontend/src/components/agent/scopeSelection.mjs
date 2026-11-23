export const emptyScope = () => ({ targets: [], timeRange: { fromInclusive: null, toExclusive: null, timeZone: 'UTC' } })
export function normalizeScope(selection) {
  if (!selection || !Array.isArray(selection.targets)) throw new Error('Missing scope selection')
  const targets = selection.targets.map(({ type, id }) => {
    if (!['contact', 'group'].includes(type) || !['string', 'number'].includes(typeof id) || !String(id).trim()) throw new Error('Invalid scope target ID')
    return { type, id }
  })
  const range = selection.timeRange
  if (!range?.timeZone) throw new Error('Missing time zone')
  new Intl.DateTimeFormat('en', { timeZone: range.timeZone })
  const boundary = value => {
    if (value == null || value === '') return null
    if (!/^\d{4}-\d\d-\d\dT.*Z$/.test(value) || !Number.isFinite(Date.parse(value))) throw new Error('Use ISO UTC boundaries')
    return new Date(value).toISOString()
  }
  const fromInclusive = boundary(range.fromInclusive), toExclusive = boundary(range.toExclusive)
  if (fromInclusive && toExclusive && fromInclusive >= toExclusive) throw new Error('Scope start must precede end')
  return { targets, timeRange: { fromInclusive, toExclusive, timeZone: range.timeZone } }
}
export function scopeRequest(selection, confirmed, version) {
  const scopeSelection = normalizeScope(selection)
  if (scopeSelection.targets.length && confirmed !== true) throw new Error('Confirm the scope before sending')
  if (!Number.isInteger(version) || version < 1) throw new Error('Load the current binding version')
  return { scopeSelection, scopeConfirmed: scopeSelection.targets.length === 0 || confirmed === true, expectedBindingVersion: version }
}
export function createScopeState() {
  let owner, session, generation = 0, selected = emptyScope(), confirmed = false
  return {
    reset(nextOwner, nextSession) { owner = nextOwner; session = nextSession; generation++; selected = emptyScope(); confirmed = false },
    ticket() { return { owner, session, generation } },
    current(ticket) { return ticket.owner === owner && ticket.session === session && ticket.generation === generation },
    select(value) { selected = normalizeScope(value); confirmed = false },
    confirm() { confirmed = true },
    request(version, fresh = false) {
      if (fresh && !confirmed) throw new Error('Confirm this new authorization request')
      const request = scopeRequest(selected, confirmed, version); confirmed = false; return request
    }
  }
}

// Receipt projection only: never mint a grant from a selection or a browser cache.
const positiveId = value => (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) ||
  (typeof value === 'string' && /^[1-9]\d*$/.test(value))
export function authorizedScope(value, binding) {
  if (!value || value.schemaVersion !== 1 || !positiveId(value.actorUserId)) return null
  if (String(value.actorUserId) !== String(binding.actorUserId) || value.sessionId !== binding.sessionId || value.runId !== binding.runId) return null
  if (['grantId', 'sessionId', 'runId'].some(key => typeof value[key] !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(value[key]))) return null
  if (['bindingVersion', 'authorizationVersion', 'sessionGeneration'].some(key => !Number.isSafeInteger(value[key]) || value[key] < 1)) return null
  if (typeof value.scopeKey !== 'string' || !/^[a-f0-9]{64}$/.test(value.scopeKey)) return null
  if (['chatIds', 'contactUserIds'].some(key => !Array.isArray(value[key]) || value[key].length > 200 || value[key].some(id => !positiveId(id)) || new Set(value[key].map(String)).size !== value[key].length)) return null
  if (!value.timeRange || !Object.hasOwn(value.timeRange, 'fromInclusive') || !Object.hasOwn(value.timeRange, 'toExclusive')) return null
  try {
    const timeRange = normalizeScope({ targets: [], timeRange: value.timeRange }).timeRange
    return { ...value, chatIds: [...value.chatIds], contactUserIds: [...value.contactUserIds], timeRange }
  } catch { return null }
}
export function observeScope(message, value, binding, { authoritative = false } = {}) {
  // An absent field says nothing; an explicit null from an authoritative read says
  // "there is no usable receipt for this run".
  if (value === undefined) return false
  if (value === null) {
    if (authoritative) { message.actualScope = null; message.scopeState = 'unknown' }
    return false
  }
  // Lateness/misbinding is not an authorization update for this run.
  if (String(value.actorUserId) !== String(binding.actorUserId) || value.sessionId !== binding.sessionId || value.runId !== binding.runId) return false
  const next = authorizedScope(value, binding)
  const prior = authorizedScope(message.actualScope, binding)
  if (next && prior && next.authorizationVersion < prior.authorizationVersion) return false
  // A malformed receipt cannot upgrade or erase what was already observed.
  if (!next) { message.scopeState = 'unknown'; return false }
  message.actualScope = next
  message.scopeState = 'observed'
  return true
}
// Only an authorization/scope refusal invalidates the binding. A 409 is also
// "a run is still active" (AGENT_RUN_40901), so a named agent code decides by its
// name; the bare HTTP status is used only when no agent code was reported.
export function scopeFailure(error) {
  const code = String(error?.response?.data?.code || error?.code || '')
  if (/AUTHZ|SCOPE/i.test(code)) return true
  if (code.startsWith('AGENT_')) return false
  return [403, 409].includes(error?.status ?? error?.response?.status)
}

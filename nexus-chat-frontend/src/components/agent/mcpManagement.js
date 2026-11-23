// Write-only drafts are component-local, never persisted in a store or logged.
const invalid = () => { throw new Error('MCP_FORM_INVALID') }

export function clearMcpCredential(form) {
  form.secret = ''
  form.clearCredential = false
}

export function mcpPayload(form, creating = false) {
  let url
  try { url = new URL(form.url.trim()) } catch { invalid() }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url['pass' + 'word'] || url.hash || url.search || url.href.includes('?') || url.href.includes('#') || url.href.length > 2048) invalid()
  const label = form.displayName.trim()
  if (!label || label.length > 80 || typeof form.enabled !== 'boolean' ||
      !Number.isInteger(form.timeoutSec) || form.timeoutSec < 1 || form.timeoutSec > 60 ||
      !Number.isInteger(form.resultMaxChars) || form.resultMaxChars < 256 || form.resultMaxChars > 64000) invalid()
  const payload = { displayName: label, url: url.href, transport: 'streamable_http', enabled: form.enabled,
    timeoutSec: form.timeoutSec, resultMaxChars: form.resultMaxChars }
  if (!creating) {
    if (!Array.isArray(form.allowedTools) || form.allowedTools.length > 128 || form.allowedTools.some(tool => typeof tool !== 'string')) invalid()
    payload.allowedTools = [...new Set(form.allowedTools)]
  }
  if (form.clearCredential && form.secret) invalid()
  if (form.clearCredential) payload.clearCredential = true
  else if (form.secret) {
    if (typeof form.secret !== 'string' || form.secret.length > 4000 || !/^[\x20-\x7e]+$/.test(form.secret) || !form.secret.trim()) invalid()
    if (!['bearer', 'header'].includes(form.credentialKind)) invalid()
    if (form.credentialKind === 'header' && !['x-api-key', 'api-key'].includes(form.headerName?.toLowerCase())) invalid()
    payload.credential = { kind: form.credentialKind, secret: form.secret,
      ...(form.credentialKind === 'header' ? { headerName: form.headerName } : {}) }
  }
  return payload
}

export function mcpStateKey(server) {
  const status = server?.status
  const at = typeof status?.checkedAt === 'string' && /(?:Z|[+-]\d{2}:\d{2})$/.test(status.checkedAt) && Number.isFinite(Date.parse(status.checkedAt))
  return status?.configVersion === server?.configVersion && status.state === 'connected' && at ? 'connected'
    : status?.configVersion === server?.configVersion && status.state === 'error' && at ? 'error' : 'unknown'
}

/** View-only projection from a REAL Java run record/event; never from composer options. */
export function publicMcpRunAuthorization(value) {
  if (!value || !['runId', 'attemptId', 'agentId'].every(key => typeof value[key] === 'string' && /^[A-Za-z0-9_-]{1,96}$/.test(value[key])) || !Array.isArray(value.services)) return null
  const services = []
  for (const service of value.services) {
    if (typeof service.serverId !== 'string' || !Number.isSafeInteger(service.configVersion) || service.configVersion < 1 ||
        !Array.isArray(service.toolIds) || service.toolIds.some(tool => typeof tool !== 'string' || tool.length > 256)) return null
    services.push({ serverId: service.serverId, configVersion: service.configVersion, toolIds: [...service.toolIds] })
  }
  return { runId: value.runId, attemptId: value.attemptId, agentId: value.agentId, services }
}

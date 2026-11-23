/** Negative evidence only: false says nothing about authenticity or authorization. */
export function isKnownExpiredJwt(token, now = Date.now()) {
  if (typeof token !== 'string' || !Number.isFinite(now)) return false
  const parts = token.split('.')
  if (parts.length !== 3 || parts.some(part => !/^[A-Za-z0-9_-]+$/.test(part))) return false
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64 + '='.repeat((4 - base64.length % 4) % 4)
    const json = typeof atob === 'function' ? atob(padded) : Buffer.from(padded, 'base64').toString('utf8')
    const payload = JSON.parse(json)
    return typeof payload?.exp === 'number' && Number.isFinite(payload.exp) && payload.exp * 1000 <= now
  } catch { return false }
}

export function isPublicAuthRequest(url) {
  return /^\/(?:api\/)?auth\/(?:login|register|send-code|verify-code)(?:[?#]|$)/.test(String(url))
}

/** Fixed local destination only; never accept a server-controlled redirect. */
export function navigateToLogin() {
  if (typeof window === 'undefined') return
  const hashMode = (typeof __IS_ELECTRON__ !== 'undefined' && __IS_ELECTRON__) || window.location.hash?.startsWith('#/')
  const here = hashMode ? window.location.hash.slice(1) : window.location.pathname
  if (here?.split(/[?#]/)[0] !== '/login') window.location.replace(hashMode ? '#/login' : '/login')
}

/** A late failure from an old account must never revoke the new account's session. */
export function failureBelongsToSession(error, currentToken) {
  if (!currentToken || error?.response?.status !== 401) return false
  const headers = error?.config?.headers
  const sent = typeof headers?.get === 'function'
    ? headers.get('Authorization')
    : headers?.Authorization || headers?.authorization
  return sent === `Bearer ${currentToken}`
}

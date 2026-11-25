function privateHost(hostname) {
  const host = hostname.toLowerCase().replace(/\.$/, '')
  if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local')) return true
  if (host.startsWith('[')) {
    const ipv6 = host.slice(1, -1)
    return ipv6 === '::' || ipv6 === '::1' || ipv6.startsWith('::ffff:')
      || /^(fc|fd)/.test(ipv6) || /^fe[89ab]/.test(ipv6)
  }
  if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) {
    const [first, second] = host.split('.').map(Number)
    return first === 0 || first === 10 || first === 127 || first >= 224
      || (first === 169 && second === 254) || (first === 192 && second === 168)
      || (first === 172 && second >= 16 && second <= 31)
      || (first === 100 && second >= 64 && second <= 127)
  }
  return false
}

function endpoint(value, name, path) {
  if (typeof value !== 'string' || !value.trim() || value.startsWith('/')) {
    throw new Error(`Production backend URLs must be explicitly configured (${name})`)
  }
  let url
  try { url = new URL(value) } catch { throw new Error(`${name} must be an absolute public HTTPS URL ending with ${path}`) }
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || privateHost(url.hostname)
      || url.pathname.replace(/\/$/, '') !== path) {
    throw new Error(`${name} must be a public HTTPS URL ending with ${path}`)
  }
  return url
}

// Used by both the Vite build gate and the browser runtime. SockJS needs HTTPS,
// not WSS; the API, transport and backend-served assets share one public origin.
export function resolvePublicBackendUrls(apiValue, wsValue) {
  const api = endpoint(apiValue, 'VITE_API_BASE_URL', '/api')
  const ws = endpoint(wsValue, 'VITE_WS_URL', '/ws')
  if (api.origin !== ws.origin) throw new Error('Public API and SockJS URLs must share the same origin')
  return { apiBaseUrl: `${api.origin}/api`, wsUrl: `${ws.origin}/ws`, serverBaseUrl: api.origin }
}

/**
 * Only the private backend asset route receives the user's authentication.
 * Arbitrary external models and embedded resource URLs must never receive it.
 */
export function assetRequest(url, serverBase, token) {
  if (typeof url !== 'string' || typeof serverBase !== 'string') throw new Error('Invalid asset URL')
  const base = new URL(serverBase)
  const target = new URL(url, base)
  if (!['http:', 'https:'].includes(target.protocol)) throw new Error('Unsupported asset URL')
  const privatePath = /^\/api\/companion\/user-assets\/file\/(model|motion)\/[a-f0-9-]{36}$/.test(target.pathname)
  const privateAsset = target.origin === base.origin && privatePath
  if (privateAsset && !token) throw new Error('Sign in before loading private assets')
  return {
    url: privateAsset ? target.href : url,
    headers: privateAsset ? { Authorization: `Bearer ${token}` } : {}
  }
}

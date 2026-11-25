function attachmentReference(message, kind, serverBase) {
  if (message.fileId) return `/api/files/download/${encodeURIComponent(message.fileId)}`
  const value = kind === 'preview'
    ? message.previewUrl || message.downloadUrl || message.fileUrl || message.content
    : message.downloadUrl || message.fileUrl
  if (!value || typeof value !== 'string') return null
  let url
  try { url = new URL(value, serverBase) } catch { return null }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null
  const path = url.pathname
  if (/^\/(api\/)?files\/(preview|download)\/[^/]+$/.test(path)) return `/api/files/download/${path.split('/').at(-1)}`
  if (path.startsWith('/uploads/') && !path.startsWith('/uploads/avatars/')) return path
  if (path.startsWith('/uploads/avatars/')) return { publicUrl: new URL(path, serverBase).href }
  if (!value.startsWith('/') && !/^https?:\/\//.test(value)) return null
  return { publicUrl: url.href }
}

export function createMessageFiles({ serverBase, getAccess, onChange = () => {}, now = Date.now, renewBeforeMs = 30_000 }) {
  const base = new URL(serverBase)
  const cache = new Map()
  const pending = new Map()
  let generation = 0
  let references = new Set()

  function reset() {
    generation++
    cache.clear()
    pending.clear()
    references.clear()
    onChange()
  }

  function signedUrl(value, kind) {
    const url = new URL(value, base)
    if (url.username || url.password || url.origin !== base.origin || !url.pathname.startsWith(`/api/files/${kind}/`)) throw new Error('Invalid signed file URL')
    return url.href
  }

  async function ensure(reference) {
    if (cache.get(reference)?.expiresAt > now() + renewBeforeMs) return
    if (pending.has(reference)) return pending.get(reference)
    const current = generation
    const request = Promise.resolve().then(async () => {
      try {
        const result = await getAccess(reference)
        if (current !== generation || !references.has(reference)) return
        if (!Number.isFinite(result.expiresAt) || result.expiresAt <= now()) throw new Error('File link expired')
        cache.set(reference, { expiresAt: result.expiresAt, previewUrl: signedUrl(result.previewUrl, 'preview'), downloadUrl: signedUrl(result.downloadUrl, 'download') })
        onChange()
      } catch {
        if (current === generation) { cache.delete(reference); onChange() }
      } finally {
        if (current === generation) pending.delete(reference)
      }
    })
    pending.set(reference, request)
    return request
  }

  async function refresh(messages) {
    references = new Set(messages.flatMap(message => ['preview', 'download'].map(kind => attachmentReference(message, kind, base)).filter(value => typeof value === 'string')))
    for (const reference of cache.keys()) if (!references.has(reference)) cache.delete(reference)
    await Promise.all([...references].map(ensure))
  }

  function source(message, kind = 'preview') {
    const reference = attachmentReference(message, kind, base)
    if (reference?.publicUrl) return reference.publicUrl
    const entry = cache.get(reference)
    return entry?.expiresAt > now() ? entry[`${kind}Url`] : ''
  }

  return { refresh, source, reset }
}

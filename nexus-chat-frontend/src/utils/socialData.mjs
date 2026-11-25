export const has = (o, k) => Object.prototype.hasOwnProperty.call(o, k) && o[k] !== undefined
export const sameId = (a, b) => a != null && b != null && String(a) === String(b)
export const presence = value => value === true || value === false || value === null ? value : undefined
export function resolveAvatarUrl(value, base = '') {
    if (typeof value !== 'string' || !value.trim()) return ''
    const url = value.trim()
    if (/^https?:\/\//i.test(url)) {
        let absolute
        try { absolute = new URL(url) } catch { return '' }
        if (absolute.username || absolute.password) return ''
        const host = absolute.hostname.toLowerCase().replace(/\.$/, '')
        const legacyBackend = host === 'localhost' || host.endsWith('.localhost') || /^127\./.test(host)
            || host === '[::1]' || host.startsWith('[::ffff:') || host === '47.118.29.122'
        // Old avatar records must not target a visitor's localhost or the retired backend.
        if (base && legacyBackend && absolute.pathname.startsWith('/uploads/')) {
            try { return new URL(absolute.pathname, base).href } catch { return '' }
        }
        return url
    }
    if (/^blob:/i.test(url) || /^data:image\/(?:png|jpeg|jpg|gif|webp|avif|bmp|svg\+xml)(?:;|,)/i.test(url)) return url
    if (/^[a-z][a-z\d+.-]*:/i.test(url) || url.startsWith('//') || /[\u0000-\u001f]/.test(url)) return ''
    return `${base.replace(/\/$/, '')}/${url.replace(/^\/+/, '')}`
}
export function normalizeUser(dto, resolve = resolveAvatarUrl) {
    if (!dto || typeof dto !== 'object') return null
    const id = dto.userId ?? dto.id
    if (id == null) return null
    const out = { id }
    if (has(dto, 'userId')) { out.userId = dto.userId; if (has(dto, 'id')) out.relationId = dto.relationId ?? dto.id }
    for (const k of ['username', 'nickname', 'lastSeen', 'email', 'phone', 'role', 'isAdmin']) if (has(dto, k)) out[k] = dto[k]
    if (has(dto, 'avatarUrl') || has(dto, 'avatar')) {
        const raw = has(dto, 'avatarUrl') ? dto.avatarUrl : dto.avatar
        out.avatarUrl = raw; out.avatar = resolve(raw)
    }
    if (has(dto, 'isOnline') && presence(dto.isOnline) !== undefined) out.isOnline = dto.isOnline
    return out
}
export function normalizeChat(dto, owner, resolve = resolveAvatarUrl) {
    const out = { ...dto }
    for (const k of Object.keys(out)) if (out[k] === undefined) delete out[k]
    const type = String(dto.type || '').toUpperCase()
    if (type) out.type = type
    if (Array.isArray(dto.members)) out.members = dto.members.map(m => normalizeUser(m, resolve)).filter(Boolean)
    if (type === 'GROUP') {
        delete out.online; delete out.isOnline; delete out.status
        out.contactId = null
        if (has(dto, 'avatar') || has(dto, 'avatarUrl')) out.avatar = resolve(has(dto, 'avatar') ? dto.avatar : dto.avatarUrl)
        else out.avatar = ''
    } else if (type === 'DIRECT') {
        // A conversation-level isOnline field is never an authority for its peer.
        // Drop stale/cache aliases and derive presence only from the real member DTO.
        delete out.isOnline
        const peer = out.members?.find(m => !sameId(m.id, owner))
        if (peer) {
            out.contactId = peer.id
            out.name = peer.nickname || peer.username || dto.name || null
            if (has(peer, 'avatar')) out.avatar = peer.avatar
            out.online = peer.isOnline ?? null
            out.status = out.online === true ? 'online' : out.online === false ? 'offline' : null
        } else { out.online = null; out.status = null }
    }
    if (!has(dto, 'memberCount') && Array.isArray(dto.members)) out.memberCount = dto.members.length
    if (has(dto, 'lastMessageTime') || has(dto, 'lastMessageAt') || dto.lastMessage?.createdAt) out.lastMessageTime = dto.lastMessage?.createdAt ?? dto.lastMessageAt ?? dto.lastMessageTime
    return out
}
// Field-level clocks: missing fields never clear other sources; old HTTP only
// loses fields actually patched after its request began. Tokens carry account epoch.
export function createRevisionLedger() {
    let owner = null, epoch = 0, revision = 0
    const clocks = new Map()
    return {
        setOwner(next) { if (!sameId(owner, next)) { owner = next; epoch++; revision = 0; clocks.clear(); return true } return false },
        capture() { return { owner, epoch, revision } },
        valid(token) { return !!token && sameId(token.owner, owner) && token.epoch === epoch },
        merge(key, current, patch, token) {
            if (token && !this.valid(token)) return null
            const stamp = ++revision, fields = clocks.get(String(key)) || {}
            const next = { ...current }
            for (const [field, value] of Object.entries(patch)) {
                if (value === undefined || (token && (fields[field] || 0) > token.revision)) continue
                next[field] = value; fields[field] = stamp
            }
            clocks.set(String(key), fields)
            return next
        }
    }
}
export function parseTypedEvent(message) {
    try {
        const data = typeof message?.body === 'string' ? JSON.parse(message.body) : message
        return data && typeof data.type === 'string' && data.payload && typeof data.payload === 'object' && !Array.isArray(data.payload) ? data : null
    } catch { return null }
}

export const isGroup = type => String(type || '').toUpperCase() === 'GROUP'
export const presence = value => typeof value === 'boolean' ? value : null
export const userId = user => user?.userId ?? user?.id
export function memberRows(members, getIdentity) {
  return members.map(member => {
    const id = userId(member)
    const identity = getIdentity(id)
    // Undefined defaults are not patches; null/empty values are intentional clears.
    const patch = Object.fromEntries(Object.entries(identity || {}).filter(([key, value]) =>
      value !== undefined && !['id', 'userId', 'role', 'isAdmin'].includes(key)))
    const row = { ...member, ...patch, id }
    if (Object.hasOwn(patch, 'avatarUrl')) {
      row.avatarUrl = patch.avatarUrl
      row.avatar = patch.avatarUrl
    } else if (Object.hasOwn(patch, 'avatar')) {
      row.avatarUrl = patch.avatar
      row.avatar = patch.avatar
    }
    row.isOnline = presence(row.isOnline)
    return row
  })
}
export function requestGate() {
  let revision = 0
  let alive = true
  return {
    begin: () => ++revision,
    current: token => alive && token === revision,
    dispose: () => { alive = false; ++revision }
  }
}
export const initialOf = (label, kind) => kind === 'group' ? '▦' : Array.from(String(label || '').trim())[0] || '?'
export const imageVisible = (url, failedUrl) => Boolean(url) && url !== failedUrl

export const chatPresence = chat => presence(chat && Object.prototype.hasOwnProperty.call(chat, 'isOnline') ? chat.isOnline : chat?.online)

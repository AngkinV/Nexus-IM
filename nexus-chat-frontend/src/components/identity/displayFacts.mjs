// Presentation only: never synthesize a photograph or membership data.
export const fallbackColors = Object.freeze(['#2b6085', '#655b87', '#2c746f', '#956145', '#627447'])
export function fallbackColor(entityId, kind = 'user') {
  const key = `${kind}:${entityId == null ? '' : String(entityId)}`
  let hash = 2166136261
  for (const char of key) hash = Math.imul(hash ^ char.codePointAt(0), 16777619) >>> 0
  return fallbackColors[hash % fallbackColors.length]
}
// Only membership facts participate. Profile/presence ticks and array order do not.
export function membershipSignature(chat) {
  const members = Array.isArray(chat?.members) ? chat.members.map(member => [
    String(member.userId ?? member.id ?? ''), member.role ?? null, member.isAdmin ?? null
  ]).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b))) : null
  return JSON.stringify([chat?.memberCount ?? null, members])
}
export function currentGroupRecord(store, id) {
  const matches = chat => id != null && chat?.id != null && String(chat.id) === String(id)
  return store.chats?.find(matches) ?? (matches(store.activeChat) ? store.activeChat : null)
}

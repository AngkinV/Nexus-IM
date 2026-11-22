/** A late failure from an old account must never revoke the new account's session. */
export function failureBelongsToSession(error, currentToken) {
  if (!currentToken || error?.response?.status !== 401) return false
  const headers = error?.config?.headers
  const sent = typeof headers?.get === 'function'
    ? headers.get('Authorization')
    : headers?.Authorization || headers?.authorization
  return sent === `Bearer ${currentToken}`
}

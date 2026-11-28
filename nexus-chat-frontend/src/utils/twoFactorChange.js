import { authErrorCode } from './loginSecurity.js'

/** Retry only a genuine server challenge; never retain its one-use proof or apply a late account response. */
export async function disableTwoFactorWithProof({ disable, askCode, isCurrent }) {
  if (!isCurrent()) return null
  let response
  try {
    response = await disable()
  } catch (error) {
    if (!isCurrent()) return null
    if (authErrorCode(error) !== 'error.auth.2fa.required') throw error
    let code
    try { code = await askCode() } catch (cancel) {
      if (cancel === 'cancel' || cancel === 'close') return null
      throw cancel
    }
    if (!isCurrent() || typeof code !== 'string' || !code.trim()) return null
    response = await disable(code.trim())
  }
  if (!isCurrent()) return null
  if (response?.data?.twoFactorEnabled !== false) throw new Error('Security change was not confirmed')
  return response
}

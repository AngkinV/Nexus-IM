/** Stable server codes are translated locally; arbitrary backend text is never a login instruction. */
const LOGIN_ERRORS = Object.freeze({
  'error.auth.2fa.required': 'auth.twoFactorRequired',
  'error.auth.2fa.invalid.code': 'auth.twoFactorInvalid',
  'error.auth.2fa.code.used': 'auth.twoFactorUsed',
  'error.auth.2fa.not.setup': 'auth.twoFactorNotReady',
  'error.auth.invalid.credentials': 'auth.loginFailed',
  '尝试次数过多，请稍后再试': 'auth.loginLocked'
})
const FACTOR_ERRORS = new Set(['error.auth.2fa.required', 'error.auth.2fa.invalid.code', 'error.auth.2fa.code.used'])
const SIGN_OUT_REASONS = Object.freeze({
  new_login: 'auth.signedInElsewhere',
  password_changed: 'auth.passwordChangedElsewhere',
  session_revoked: 'auth.sessionRevoked',
  logout: 'auth.sessionRevoked'
})
export const SIGN_OUT_STORAGE_KEY = 'nexus.signOutReason'

export function authErrorCode(error) {
  return typeof error === 'string' ? error : error?.response?.data?.messageKey
    || error?.response?.data?.message || error?.message
}
export const needsSecondFactor = error => FACTOR_ERRORS.has(authErrorCode(error))
export const loginErrorKey = error => {
  const message = authErrorCode(error)
  return typeof message === 'string' && Object.hasOwn(LOGIN_ERRORS, message) ? LOGIN_ERRORS[message] : 'auth.loginFailed'
}
export const signOutMessageKey = reason =>
  typeof reason === 'string' && Object.hasOwn(SIGN_OUT_REASONS, reason) ? SIGN_OUT_REASONS[reason] : null

export function rememberSignOut(reason) {
  if (!signOutMessageKey(reason)) return
  try { sessionStorage.setItem(SIGN_OUT_STORAGE_KEY, reason) } catch { /* private-mode storage may be unavailable */ }
}
export function takeSignOutMessage() {
  try {
    const reason = sessionStorage.getItem(SIGN_OUT_STORAGE_KEY)
    sessionStorage.removeItem(SIGN_OUT_STORAGE_KEY)
    return signOutMessageKey(reason)
  } catch { return null }
}

// What the register form has to ask for, as the backend reports it
// (GET /api/auth/registration-policy).

export const DEFAULT_REGISTRATION_POLICY = Object.freeze({ inviteRequired: false, emailCodeRequired: true })

// Anything but a well-formed answer (older backend, network failure) keeps the form
// as it has always been: email code, no invite.
export function normalizeRegistrationPolicy(data) {
  if (!data || typeof data.inviteRequired !== 'boolean' || typeof data.emailCodeRequired !== 'boolean') {
    return DEFAULT_REGISTRATION_POLICY
  }
  return { inviteRequired: data.inviteRequired, emailCodeRequired: data.emailCodeRequired }
}

export function isRegisterFormComplete(form, policy) {
  return Boolean(form.username && form.email && form.nickname && form.password && form.confirmPassword
    && (!policy.emailCodeRequired || form.verificationCode)
    && (!policy.inviteRequired || (form.inviteCode || '').trim()))
}

export function registerPayload(form, policy) {
  const payload = {
    username: form.username,
    email: form.email,
    nickname: form.nickname,
    password: form.password,
    phone: form.phone || null
  }
  if (policy.emailCodeRequired) payload.verificationCode = form.verificationCode
  if (policy.inviteRequired) payload.inviteCode = form.inviteCode.trim()
  return payload
}

// The invite sent along when asking for an email code; nothing when invites are not in use.
export function inviteForEmailCode(form, policy) {
  return policy.inviteRequired ? (form.inviteCode || '').trim() : undefined
}

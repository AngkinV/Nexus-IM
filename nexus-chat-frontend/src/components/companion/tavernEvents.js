/** Local routing metadata, not a new model protocol or a displayed mood. */
export function stageReplyMeta(roleId, reply, liveState, sessionId = null) {
  const live = !!liveState && !!reply?.live && String(reply.roleId) === String(roleId)
  return { roleId, sessionId, turnId: live ? String(reply.id) : null, source: live ? 'state' : 'stored' }
}

export function interruptedStageReply(reply) {
  return !!(reply?.failed || reply?.stopped || reply?.partial || reply?.modelError)
}

import { watch } from 'vue'

/** Selection ownership for settings; shared-asset acknowledgements use owner-only checks. */
export function useRoleModelTarget({ store, getUserId, getRoleId }) {
  let selection = 0, disposed = false
  const stop = watch(getRoleId, () => { selection += 1 }, { flush: 'sync' })
  const capture = () => ({ userId: getUserId(), roleId: getRoleId(), epoch: store.ownerEpoch, selection })
  const isOwner = request => !!request && request.userId != null &&
    String(request.userId) === String(getUserId()) && request.epoch === store.ownerEpoch &&
    store.isUserCurrent(request.userId)
  const isCurrent = request => !disposed && isOwner(request) && request.roleId != null &&
    String(request.roleId) === String(getRoleId()) && request.selection === selection
  const dispose = () => { disposed = true; stop() }
  return { capture, isOwner, isCurrent, dispose }
}

import { computed, ref, watch } from 'vue'
import { tavernModelSupported } from '@/components/companion/modelAssetType'

/** Request-local UI state; no new persisted preference or store. */
export function useTavernInvite({ store, getUserId, getSceneKey, onSuccess = () => {}, onError = () => {}, onUnknown = () => {} }) {
  const models = ref([])
  const visible = ref(false)
  const loading = ref(false)
  const error = ref(null)
  const selectedUrl = ref(null)
  let pickerVersion = 0, inviteVersion = 0, disposed = false

  const context = () => ({ userId: getUserId(), epoch: store.ownerEpoch })
  const owned = (request) => !disposed && request.userId != null &&
    String(getUserId()) === String(request.userId) && request.epoch === store.ownerEpoch &&
    store.isUserCurrent(request.userId)

  const stopAccountWatch = watch(() => [getUserId(), store.ownerEpoch], () => {
    pickerVersion += 1
    inviteVersion += 1
    visible.value = false
    loading.value = false
    models.value = []
    selectedUrl.value = null
    error.value = null
  }, { flush: 'sync' })
  const stopVisibleWatch = watch(visible, value => {
    if (!value) {
      pickerVersion += 1
      loading.value = false
    }
  }, { flush: 'sync' })

  const canConfirm = computed(() => visible.value && !loading.value && !error.value &&
    !store.isInviting && owned(context()) &&
    (selectedUrl.value === null || models.value.some(model => model.fileUrl === selectedUrl.value)))

  async function open() {
    const request = context()
    if (!owned(request) || store.isInviting) return
    visible.value = true
    selectedUrl.value = null
    error.value = null
    // Keep old rows while refreshing, but never submit a stale selection.
    loading.value = true
    const version = ++pickerVersion
    try {
      const list = await store.fetchInviteModels(request.userId)
      if (!owned(request) || version !== pickerVersion || !visible.value) return
      if (!Array.isArray(list)) throw new Error('Invalid model list response')
      models.value = list.filter(tavernModelSupported)
    } catch (cause) {
      if (owned(request) && version === pickerVersion && visible.value) error.value = cause
    } finally {
      if (owned(request) && version === pickerVersion) loading.value = false
    }
  }

  async function confirm() {
    if (!canConfirm.value) return null
    const request = context(), modelUrl = selectedUrl.value, sceneKey = getSceneKey()
    const version = ++inviteVersion
    const current = () => owned(request) && version === inviteVersion
    visible.value = false
    try {
      const role = await store.inviteGuest(request.userId, sceneKey, modelUrl)
      if (!current()) return null
      if (!role?.id || role.kind !== 'guest') {
        throw Object.assign(new Error('Unconfirmed invitation'), { code: 'INVITE_RESULT_UNKNOWN' })
      }
      // The POST response alone proves which guest this invitation created.
      // Neither the newest role nor an ID difference is evidence of success.
      await store.setActiveRole(request.userId, role.id)
      if (!current()) return null
      onSuccess(role)
      return role
    } catch (cause) {
      if (!current()) return null
      const status = Number(cause?.response?.status)
      if (cause?.code === 'INVITE_RESULT_UNKNOWN' || !status || status >= 500) onUnknown(cause)
      else onError(cause)
      return null
    }
  }

  function dispose() {
    disposed = true
    pickerVersion += 1
    inviteVersion += 1
    stopAccountWatch()
    stopVisibleWatch()
  }

  return { models, visible, loading, error, selectedUrl, canConfirm, open, confirm, dispose }
}

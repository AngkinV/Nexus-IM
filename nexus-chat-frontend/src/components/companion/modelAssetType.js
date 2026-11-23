/** 3D asset formats are not the LLM/provider model type. */
export function modelAssetType(model) {
  const declared = String(model?.type || '').toLowerCase()
  const extension = String(model?.fileUrl || model?.fileName || '').split(/[?#]/)[0].split('.').at(-1)?.toLowerCase()
  const raw = declared || extension
  if (raw === 'vrm') return 'vrm'
  if (raw === 'glb' || raw === 'gltf') return 'gltf'
  if (raw === 'fbx') return 'fbx'
  return null
}

/** The tavern stage currently has a VRM loader, not a general 3D loader. */
export function tavernModelSupported(model) {
  return modelAssetType(model) === 'vrm' && typeof model?.fileUrl === 'string' &&
    /^\/models\/[^/\\?#]+\.vrm$/i.test(model.fileUrl) && !model.fileUrl.includes('..')
}

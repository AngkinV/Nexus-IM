/** 3D asset formats are not the LLM/provider model type. */
const FORMATS = { vrm: 'vrm', glb: 'gltf', gltf: 'gltf', fbx: 'fbx' }
const extension = value => /\.([a-z0-9]+)$/i.exec(String(value || '').split(/[?#]/)[0])?.[1]?.toLowerCase() || ''

export function modelAssetType(model) {
  // A model in an account's own library is addressed by id, so its URL has no
  // extension; its file name still does.
  const raw = String(model?.type || '').toLowerCase() || extension(model?.fileUrl) || extension(model?.fileName)
  return FORMATS[raw] || null
}

// The two places a model can live: the shared directory, by file name, and an
// account's own library (imported in settings), by id.
const SHARED_MODEL = /^\/models\/[^/\\?#]+\.vrm$/i
const OWN_MODEL = /^\/api\/companion\/user-assets\/file\/model\/[a-f0-9-]{36}$/

/** The tavern stage currently has a VRM loader, not a general 3D loader. */
export function tavernModelSupported(model) {
  if (modelAssetType(model) !== 'vrm' || typeof model?.fileUrl !== 'string' || model.fileUrl.includes('..')) return false
  return SHARED_MODEL.test(model.fileUrl) || OWN_MODEL.test(model.fileUrl)
}

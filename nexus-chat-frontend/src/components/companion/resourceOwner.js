// Resource ownership is local to one loader/parser, never a global URL/loader patch.
export const resourceCounts = { disposed: 0, errors: 0, bitmapsClosed: 0, retiredLoads: 0 }
export function attempt(fn) {
  try { return fn() } catch { resourceCounts.errors++ }
}
export function createResourceOwner({ ownImages = false } = {}) {
  const resources = new Set(), images = new Set(), roots = new Set(), blobURLs = new Set()
  const disposed = new WeakSet(), closed = new WeakSet()
  let retired = false
  function dispose(value) {
    if (!value || disposed.has(value)) return
    disposed.add(value)
    if (value.boneTexture) {
      const texture = value.boneTexture
      value.boneTexture = null
      dispose(texture)
    }
    attempt(() => value.dispose?.())
    resourceCounts.disposed++
  }
  function trackTextures(value) {
    if (Array.isArray(value)) value.forEach(trackTextures)
    else if (value?.isTexture) track(value)
  }
  function trackImages(value) {
    if (Array.isArray(value)) value.forEach(trackImages)
    else if (value?.close) images.add(value)
  }
  function track(value) {
    if (!value || typeof value !== 'object') return value
    if (Array.isArray(value)) { value.forEach(track); return value }
    if (value.isObject3D) {
      roots.add(value)
      value.traverse(node => {
        track(node.geometry); track(node.material); track(node.skeleton)
      })
    } else if (value.isMaterial) {
      resources.add(value)
      Object.values(value).forEach(trackTextures)
      Object.values(value.uniforms || {}).forEach(v => trackTextures(v?.value))
    } else if (value.isTexture) {
      resources.add(value)
      const image = value.source?.data || value.image
      if (ownImages) trackImages(image)
    } else if (value.dispose) resources.add(value)
    if (retired) flush()
    return value
  }
  function flush() {
    for (const root of roots) attempt(() => root.removeFromParent())
    for (const resource of resources) dispose(resource)
    // All textures (including clones) in this exclusive transaction retire first.
    for (const image of images) if (!closed.has(image)) {
      closed.add(image)
      attempt(() => image.close())
      resourceCounts.bitmapsClosed++
    }
    roots.clear()
    resources.clear()
    images.clear()
  }
  const releaseURLs = () => {
    for (const url of blobURLs) attempt(() => URL.revokeObjectURL(url))
    blobURLs.clear()
  }
  return {
    track,
    releaseURLs,
    async prepareEmbeddedImages(parser) {
      await Promise.all((parser.json.images || []).map(async image => {
        if (image.bufferView === undefined) return
        const buffer = await parser.getDependency('bufferView', image.bufferView)
        const url = URL.createObjectURL(new Blob([buffer], { type: image.mimeType }))
        blobURLs.add(url)
        image.uri = url
        delete image.bufferView
        if (retired) releaseURLs()
      }))
    },
    retire() { if (!retired) { retired = true; resourceCounts.retiredLoads++ }; flush(); releaseURLs() },
    get retired() { return retired },
    snapshot() { return { retired, resources: resources.size, images: images.size, ...resourceCounts } },
    bindParser(parser) {
      // getDependency catches partial successes even when a sibling rejects.
      const dependency = parser.getDependency.bind(parser)
      parser.getDependency = (type, index) => Promise.resolve(dependency(type, index)).then(value => track(value))
      const texture = parser.loadTextureImage.bind(parser)
      parser.loadTextureImage = (...args) => Promise.resolve(texture(...args)).then(value => track(value))
    }
  }
}
// One retirement per renderer; never depend on rAF (it may stop in hidden tabs).
const rendererRetirements = new WeakMap()
export function releaseRenderer(renderer, retireResources = () => {}, scheduler = globalThis) {
  if (!renderer) { attempt(retireResources); return Promise.resolve() }
  if (rendererRetirements.has(renderer)) return rendererRetirements.get(renderer)
  let resolve
  const done = new Promise(r => { resolve = r })
  rendererRetirements.set(renderer, done)
  attempt(() => renderer.setAnimationLoop?.(null))
  // Detach before ANY texture/bitmap/cache/context retirement.
  attempt(() => renderer.domElement?.remove())
  let finished = false, frame = null, afterFrame = null, fallback = null
  const finish = () => {
    if (finished) return
    finished = true
    attempt(() => scheduler.clearTimeout(fallback))
    if (afterFrame !== null) attempt(() => scheduler.clearTimeout(afterFrame))
    if (frame !== null) attempt(() => scheduler.cancelAnimationFrame?.(frame))
    attempt(retireResources)
    attempt(() => renderer.renderLists?.dispose())
    attempt(() => renderer.dispose())
    attempt(() => renderer.forceContextLoss())
    resolve()
  }
  // A task after a frame gives the detached canvas a composition opportunity.
  // Hidden/background: a single 100ms timeout ensures eventual retirement even
  // when rAF never fires. Browser throttling may delay it, but no repeating work.
  fallback = scheduler.setTimeout(finish, 100)
  if (scheduler.requestAnimationFrame) attempt(() => {
    frame = scheduler.requestAnimationFrame(() => {
      if (!finished) afterFrame = scheduler.setTimeout(finish, 0)
    })
  })
  return done
}

const DB_NAME = 'nexus-companion-assets'
const STORE_NAME = 'files'
const DB_VERSION = 1

const openDb = () => new Promise((resolve, reject) => {
  if (!('indexedDB' in window)) {
    reject(new Error('IndexedDB not supported'))
    return
  }
  const request = indexedDB.open(DB_NAME, DB_VERSION)
  request.onupgradeneeded = () => {
    const db = request.result
    if (!db.objectStoreNames.contains(STORE_NAME)) {
      const store = db.createObjectStore(STORE_NAME, { keyPath: 'key' })
      store.createIndex('kind', 'kind', { unique: false })
      store.createIndex('createdAt', 'createdAt', { unique: false })
    }
  }
  request.onsuccess = () => resolve(request.result)
  request.onerror = () => reject(request.error)
})

const runStoreRequest = async (mode, fn) => {
  const db = await openDb()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, mode)
    const store = tx.objectStore(STORE_NAME)
    let result
    let request
    try {
      request = fn(store)
    } catch (error) {
      reject(error)
      return
    }
    request.onsuccess = () => {
      result = request.result
    }
    request.onerror = () => {
      reject(request.error)
    }
    tx.oncomplete = () => resolve(result)
    tx.onerror = () => reject(tx.error)
    tx.onabort = () => reject(tx.error)
  })
}

export const saveAssetFile = async ({ file, kind }) => {
  const key = `${kind}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const record = {
    key,
    kind,
    name: file.name,
    type: file.type || '',
    size: file.size,
    lastModified: file.lastModified || Date.now(),
    createdAt: Date.now(),
    blob: file
  }
  await runStoreRequest('readwrite', (store) => store.put(record))
  return record
}

export const listAssets = async (kind) => {
  return runStoreRequest('readonly', (store) => {
    if (!kind) return store.getAll()
    const index = store.index('kind')
    return index.getAll(kind)
  })
}

export const getAsset = async (key) => {
  if (!key) return null
  return runStoreRequest('readonly', (store) => store.get(key))
}

export const deleteAsset = async (key) => {
  if (!key) return
  await runStoreRequest('readwrite', (store) => store.delete(key))
}

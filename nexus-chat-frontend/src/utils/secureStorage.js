/**
 * AES-GCM encryption for a user's local settings blob.
 *
 * What this protects: someone reading the localStorage dump sees ciphertext
 * instead of plain JSON. What it does not protect: anyone with code execution on
 * this machine, because the key lives in IndexedDB beside the data. It is local
 * at-rest encryption, not end-to-end encryption — the UI says so.
 *
 * Keys are non-extractable CryptoKeys, one per user id. Normal application
 * paths use only the current account's key. This is NOT a security boundary
 * against malicious same-origin code, which can access this IndexedDB. Every function takes the
 * user id explicitly: deriving it from "whoever is signed in right now" is how
 * a write ends up under the wrong account during a sign-out.
 */

const DB_NAME = 'nexus-secure'
const STORE = 'keys'

/** Key id for a user (null = signed-out session). */
export const keyIdFor = (userId) => (userId == null ? 'local-settings.anon' : `local-settings.${userId}`)
/** Identifier of the old shared key; never automatically tried for another account. */
export const LEGACY_KEY_ID = 'local-settings'

/* ── Key store (IndexedDB; replaceable in tests) ────────────────── */

function openDb() {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      reject(new Error('IndexedDB unavailable'))
      return
    }
    const req = indexedDB.open(DB_NAME, 1)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE)
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
    req.onblocked = () => reject(new Error('IndexedDB blocked'))
  })
}

const indexedDbKeyStore = {
  async get(id) {
    const db = await openDb()
    try {
      return await new Promise((resolve, reject) => {
        const req = db.transaction(STORE, 'readonly').objectStore(STORE).get(id)
        req.onsuccess = () => resolve(req.result ?? null)
        req.onerror = () => reject(req.error)
      })
    } finally {
      db.close()
    }
  },
  /**
   * Store `candidate` unless a key already exists, and return whichever key is
   * now stored. Read and write share one transaction, so two callers creating
   * the first key at the same time cannot each keep a different one — which
   * would leave one of them holding ciphertext nobody can read.
   */
  async getOrPut(id, candidate) {
    const db = await openDb()
    try {
      return await new Promise((resolve, reject) => {
        const tx = db.transaction(STORE, 'readwrite')
        const store = tx.objectStore(STORE)
        let result = candidate
        const read = store.get(id)
        read.onsuccess = () => {
          if (read.result) result = read.result
          else store.put(candidate, id)
        }
        tx.oncomplete = () => resolve(result)
        tx.onerror = () => reject(tx.error)
        tx.onabort = () => reject(tx.error || new Error('IndexedDB transaction aborted'))
      })
    } finally {
      db.close()
    }
  },
  async delete(id) {
    const db = await openDb()
    try {
      await new Promise((resolve, reject) => {
        const tx = db.transaction(STORE, 'readwrite')
        tx.objectStore(STORE).delete(id)
        tx.oncomplete = () => resolve()
        tx.onerror = () => reject(tx.error)
      })
    } finally {
      db.close()
    }
  }
}

let keyStore = indexedDbKeyStore
/** In-flight/created keys by id, so concurrent callers share one creation. */
const keyCache = new Map()

/** Test hook: swap the key store (pass nothing to restore IndexedDB). */
export function __setKeyStoreForTests(store) {
  keyStore = store || indexedDbKeyStore
  keyCache.clear()
}

const subtle = () => globalThis.crypto?.subtle

async function existingKey(id) {
  if (keyCache.has(id)) return keyCache.get(id)
  const key = await keyStore.get(id)
  if (key) keyCache.set(id, Promise.resolve(key))
  return key
}

function keyForWriting(id) {
  if (!keyCache.has(id)) {
    const pending = (async () => {
      const found = await keyStore.get(id)
      if (found) return found
      const fresh = await subtle().generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
      return keyStore.getOrPut(id, fresh)
    })()
    keyCache.set(id, pending)
    // A failed creation must not poison the cache for the next attempt.
    pending.catch(() => keyCache.delete(id))
  }
  return keyCache.get(id)
}

/* ── Encoding ───────────────────────────────────────────────────── */

const toBase64 = (buf) => {
  const bytes = new Uint8Array(buf)
  let binary = ''
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i])
  return btoa(binary)
}
const fromBase64 = (str) => Uint8Array.from(atob(str), (c) => c.charCodeAt(0))

export const isEnvelope = (value) =>
  !!value && typeof value === 'object' && value.__encrypted === true &&
  typeof value.iv === 'string' && typeof value.data === 'string'

/* ── Public API ─────────────────────────────────────────────────── */

/** Encrypt a JS value for `userId`. Throws when encryption is unavailable. */
export async function encryptValue(value, userId) {
  if (userId === undefined) throw new Error('explicit owner id required')
  if (!isEncryptionSupported()) throw new Error('encryption unavailable')
  const key = await keyForWriting(keyIdFor(userId))
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12))
  const encoded = new TextEncoder().encode(JSON.stringify(value))
  const owner = userId == null ? 'anon' : String(userId)
  const additionalData = new TextEncoder().encode(`nexus.settings:${owner}`)
  const cipher = await subtle().encrypt({ name: 'AES-GCM', iv, additionalData }, key, encoded)
  return { __encrypted: true, version: 2, owner, iv: toBase64(iv), data: toBase64(cipher) }
}

/**
 * Decrypt an envelope written for `userId`.
 * Returns null when it cannot be read (no key, wrong key, tampered data). It
 * never creates a key: a missing key means the data is unreadable, and minting
 * a new one would only hide that.
 */
export async function decryptValue(envelope, userId) {
  if (userId === undefined || !isEnvelope(envelope) || !subtle()) return null
  const owner = userId == null ? 'anon' : String(userId)
  if (envelope.version !== undefined && envelope.version !== 2) return null
  if (envelope.version === 2 && envelope.owner !== owner) return null
  try {
    const key = await existingKey(keyIdFor(userId))
    if (!key) return null
    // Legacy per-account envelopes have no AAD. They are read only with their
    // own key and upgraded on the next write, never with the shared legacy key.
    const options = { name: 'AES-GCM', iv: fromBase64(envelope.iv) }
    if (envelope.version === 2) options.additionalData = new TextEncoder().encode(`nexus.settings:${owner}`)
    const plain = await subtle().decrypt(options, key, fromBase64(envelope.data))
    return JSON.parse(new TextDecoder().decode(plain))
  } catch {
    return null
  }
}

/** Drop a user's key (used when the user explicitly discards unreadable data). */
export async function forgetKey(userId) {
  const id = keyIdFor(userId)
  keyCache.delete(id)
  await keyStore.delete(id)
}

export function isEncryptionSupported() {
  if (!subtle() || typeof globalThis.crypto.getRandomValues !== 'function') return false
  return keyStore !== indexedDbKeyStore || typeof indexedDB !== 'undefined'
}

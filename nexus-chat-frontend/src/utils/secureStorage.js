/**
 * AES-GCM encryption for the local preferences blob.
 *
 * What this protects: someone reading the localStorage dump sees ciphertext
 * instead of plain JSON. What it does not protect: anyone with code execution on
 * this machine, because the key lives in IndexedDB beside the data. It is local
 * at-rest obfuscation with real crypto, not end-to-end encryption — the UI must
 * say so rather than claiming "zero knowledge".
 *
 * The key is a non-extractable CryptoKey in IndexedDB. It cannot be exported, so
 * copying the ciphertext to another machine yields nothing usable.
 */

const DB_NAME = 'nexus-secure'
const STORE = 'keys'
const KEY_ID = 'local-settings'

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
  })
}

function idbGet(key) {
  return openDb().then(
    (db) =>
      new Promise((resolve, reject) => {
        const tx = db.transaction(STORE, 'readonly')
        const req = tx.objectStore(STORE).get(key)
        req.onsuccess = () => resolve(req.result)
        req.onerror = () => reject(req.error)
      })
  )
}

function idbPut(key, value) {
  return openDb().then(
    (db) =>
      new Promise((resolve, reject) => {
        const tx = db.transaction(STORE, 'readwrite')
        tx.objectStore(STORE).put(value, key)
        tx.oncomplete = () => resolve(true)
        tx.onerror = () => reject(tx.error)
      })
  )
}

async function getKey() {
  const existing = await idbGet(KEY_ID)
  if (existing) return existing
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, [
    'encrypt',
    'decrypt'
  ])
  await idbPut(KEY_ID, key)
  return key
}

const toBase64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)))
const fromBase64 = (str) => Uint8Array.from(atob(str), (c) => c.charCodeAt(0))

/** Encrypt a JS value into a self-describing envelope. */
export async function encryptValue(value) {
  const key = await getKey()
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const encoded = new TextEncoder().encode(JSON.stringify(value))
  const cipher = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, encoded)
  return { __encrypted: true, iv: toBase64(iv), data: toBase64(cipher) }
}

/** Decrypt an envelope. Returns null when it is not a valid envelope. */
export async function decryptValue(envelope) {
  if (!envelope || envelope.__encrypted !== true) return null
  try {
    const key = await getKey()
    const plain = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: fromBase64(envelope.iv) },
      key,
      fromBase64(envelope.data)
    )
    return JSON.parse(new TextDecoder().decode(plain))
  } catch {
    return null
  }
}

export function isEncryptionSupported() {
  return (
    typeof window !== 'undefined' &&
    !!window.crypto?.subtle &&
    typeof indexedDB !== 'undefined'
  )
}

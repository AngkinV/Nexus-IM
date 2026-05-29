/**
 * IndexedDB abstraction layer for Nexus Chat.
 * Provides high-level CRUD operations on cached data.
 * Used by stores for offline-first reads and write-through caching.
 */
import Dexie from 'dexie'
import db from './db'

// Errors that indicate IndexedDB itself is unusable (private mode, blocked
// storage, corrupted DB, quota exhausted, etc). When we see one of these we
// log once and then silently no-op every subsequent call so the app keeps
// working in memory-only mode instead of spamming "DatabaseClosedError" into
// the console and crashing flushes/syncs.
const FATAL_DB_ERRORS = new Set([
  'DatabaseClosedError',
  'InvalidStateError',
  'UnknownError',
  'NotFoundError',
  'QuotaExceededError',
  'VersionError'
])
let dbDisabled = false
let warnedOnce = false

const isFatalDbError = (err) => {
  if (!err) return false
  if (FATAL_DB_ERRORS.has(err.name)) return true
  const inner = err.inner
  if (inner && FATAL_DB_ERRORS.has(inner.name)) return true
  const message = String(err.message || '')
  return message.includes('backing store') || message.includes('database is closed')
}

const safeRead = async (op, fallback) => {
  if (dbDisabled) return fallback
  try {
    return await op()
  } catch (err) {
    if (isFatalDbError(err)) {
      if (!warnedOnce) {
        console.warn('[offlineStore] IndexedDB unavailable, falling back to memory-only mode:', err.message || err)
        warnedOnce = true
      }
      dbDisabled = true
      return fallback
    }
    throw err
  }
}

const safeWrite = async (op) => {
  if (dbDisabled) return
  try {
    await op()
  } catch (err) {
    if (isFatalDbError(err)) {
      if (!warnedOnce) {
        console.warn('[offlineStore] IndexedDB unavailable, falling back to memory-only mode:', err.message || err)
        warnedOnce = true
      }
      dbDisabled = true
      return
    }
    throw err
  }
}

const offlineStore = {

  // ==================== Messages ====================

  /**
   * Get cached messages for a chat, sorted by createdAt ascending.
   * @param {number} chatId
   * @param {number} limit - max messages to return (default 50)
   * @param {number} offset - for pagination
   * @returns {Promise<Array>}
   */
  async getMessages(chatId, limit = 50, offset = 0) {
    return safeRead(() => db.messages
      .where('[chatId+createdAt]')
      .between([chatId, Dexie.minKey], [chatId, Dexie.maxKey])
      .offset(offset)
      .limit(limit)
      .toArray(), [])
  },

  /**
   * Save a message to IndexedDB. Upserts by server id or clientMsgId.
   * @param {Object} message
   */
  async saveMessage(message) {
    if (!message || !message.chatId) return
    await safeWrite(async () => {
      // If message has a server id, check for existing
      if (message.id) {
        const existing = await db.messages.where('id').equals(message.id).first()
        if (existing) {
          await db.messages.update(existing.localId, message)
          return
        }
      }

      // If message has clientMsgId, check for optimistic duplicate
      if (message.clientMsgId) {
        const existing = await db.messages.where('clientMsgId').equals(message.clientMsgId).first()
        if (existing) {
          await db.messages.update(existing.localId, { ...existing, ...message })
          return
        }
      }

      await db.messages.add(message)
    })
  },

  /**
   * Save multiple messages (batch insert for sync).
   * @param {Array} messages
   */
  async saveMessages(messages) {
    if (!messages || messages.length === 0) return
    await safeWrite(() => db.messages.bulkPut(messages))
  },

  /**
   * Get the latest message timestamp for a chat (for delta sync).
   * @param {number} chatId
   * @returns {Promise<string|null>}
   */
  async getLatestMessageTime(chatId) {
    const msgs = await safeRead(() => db.messages
      .where('chatId')
      .equals(chatId)
      .reverse()
      .sortBy('createdAt'), [])
    return msgs.length > 0 ? msgs[0].createdAt : null
  },

  /**
   * Clear all messages for a chat (e.g., on chat delete).
   * @param {number} chatId
   */
  async clearChatMessages(chatId) {
    await safeWrite(() => db.messages.where('chatId').equals(chatId).delete())
  },

  // ==================== Chats ====================

  /**
   * Get all cached chats, sorted by lastMessageAt descending.
   * @returns {Promise<Array>}
   */
  async getChats() {
    return safeRead(() => db.chats.orderBy('lastMessageAt').reverse().toArray(), [])
  },

  /**
   * Save or update a chat.
   * @param {Object} chat
   */
  async saveChat(chat) {
    if (!chat || !chat.id) return
    await safeWrite(() => db.chats.put(chat))
  },

  /**
   * Save multiple chats (batch for initial load).
   * @param {Array} chats
   */
  async saveChats(chats) {
    if (!chats || chats.length === 0) return
    await safeWrite(() => db.chats.bulkPut(chats))
  },

  /**
   * Delete a chat and its messages.
   * @param {number} chatId
   */
  async deleteChat(chatId) {
    await safeWrite(async () => {
      await db.chats.delete(chatId)
      await db.messages.where('chatId').equals(chatId).delete()
    })
  },

  // ==================== Contacts ====================

  /**
   * Get all cached contacts.
   * @returns {Promise<Array>}
   */
  async getContacts() {
    return safeRead(() => db.contacts.toArray(), [])
  },

  /**
   * Save or update contacts (batch).
   * @param {Array} contacts
   */
  async saveContacts(contacts) {
    if (!contacts || contacts.length === 0) return
    await safeWrite(() => db.contacts.bulkPut(contacts))
  },

  /**
   * Remove a contact.
   * @param {number} contactId
   */
  async removeContact(contactId) {
    await safeWrite(() => db.contacts.delete(contactId))
  },

  /**
   * Update online status for a contact.
   * @param {number} contactId
   * @param {boolean} isOnline
   */
  async updateContactStatus(contactId, isOnline) {
    await safeWrite(() => db.contacts.update(contactId, { isOnline }))
  },

  // ==================== Pending Messages (Offline Outbox) ====================

  /**
   * Queue a message for sending when back online.
   * @param {Object} pendingMsg - { chatId, content, messageType, fileUrl, clientMsgId, createdAt, status }
   */
  async queuePendingMessage(pendingMsg) {
    pendingMsg.status = 'pending'
    pendingMsg.createdAt = pendingMsg.createdAt || new Date().toISOString()
    return safeRead(() => db.pendingMessages.add(pendingMsg), null)
  },

  /**
   * Get all pending messages (for retry on reconnect).
   * @returns {Promise<Array>}
   */
  async getPendingMessages() {
    return safeRead(() => db.pendingMessages.where('status').equals('pending').toArray(), [])
  },

  /**
   * Mark a pending message as sent.
   * @param {number} id - local pending message id
   */
  async markPendingAsSent(id) {
    await safeWrite(() => db.pendingMessages.update(id, { status: 'sent' }))
  },

  /**
   * Remove a pending message after successful ACK.
   * @param {number} id
   */
  async removePendingMessage(id) {
    await safeWrite(() => db.pendingMessages.delete(id))
  },

  /**
   * Remove all sent pending messages.
   */
  async clearSentPending() {
    await safeWrite(() => db.pendingMessages.where('status').equals('sent').delete())
  },

  // ==================== Sync Metadata ====================

  /**
   * Get last sync timestamp for an entity type.
   * @param {string} type - 'messages', 'chats', 'contacts'
   * @returns {Promise<string|null>}
   */
  async getLastSyncTime(type) {
    const meta = await safeRead(() => db.syncMeta.get(type), null)
    return meta ? meta.timestamp : null
  },

  /**
   * Update last sync timestamp.
   * @param {string} type
   * @param {string} timestamp - ISO string
   */
  async setLastSyncTime(type, timestamp) {
    await safeWrite(() => db.syncMeta.put({ key: type, timestamp }))
  },

  // ==================== Utility ====================

  /**
   * Clear all data (on logout).
   */
  async clearAll() {
    await safeWrite(async () => {
      await db.messages.clear()
      await db.chats.clear()
      await db.contacts.clear()
      await db.syncMeta.clear()
      await db.pendingMessages.clear()
    })
  },

  /**
   * Whether the local IndexedDB has been disabled (private mode, quota,
   * corrupted store, etc). Callers can use this to skip cache-only work.
   */
  isAvailable() {
    return !dbDisabled
  }
}

export default offlineStore

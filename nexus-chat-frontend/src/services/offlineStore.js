/**
 * IndexedDB abstraction layer for Nexus Chat.
 * Provides high-level CRUD operations on cached data.
 * Used by stores for offline-first reads and write-through caching.
 */
import Dexie from 'dexie'
import db from './db'

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
    return db.messages
      .where('[chatId+createdAt]')
      .between([chatId, Dexie.minKey], [chatId, Dexie.maxKey])
      .offset(offset)
      .limit(limit)
      .toArray()
  },

  /**
   * Save a message to IndexedDB. Upserts by server id or clientMsgId.
   * @param {Object} message
   */
  async saveMessage(message) {
    if (!message || !message.chatId) return

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
  },

  /**
   * Save multiple messages (batch insert for sync).
   * @param {Array} messages
   */
  async saveMessages(messages) {
    if (!messages || messages.length === 0) return
    await db.messages.bulkPut(messages)
  },

  /**
   * Get the latest message timestamp for a chat (for delta sync).
   * @param {number} chatId
   * @returns {Promise<string|null>}
   */
  async getLatestMessageTime(chatId) {
    const msg = await db.messages
      .where('chatId')
      .equals(chatId)
      .reverse()
      .sortBy('createdAt')
    return msg.length > 0 ? msg[0].createdAt : null
  },

  /**
   * Clear all messages for a chat (e.g., on chat delete).
   * @param {number} chatId
   */
  async clearChatMessages(chatId) {
    await db.messages.where('chatId').equals(chatId).delete()
  },

  // ==================== Chats ====================

  /**
   * Get all cached chats, sorted by lastMessageAt descending.
   * @returns {Promise<Array>}
   */
  async getChats() {
    return db.chats.orderBy('lastMessageAt').reverse().toArray()
  },

  /**
   * Save or update a chat.
   * @param {Object} chat
   */
  async saveChat(chat) {
    if (!chat || !chat.id) return
    await db.chats.put(chat)
  },

  /**
   * Save multiple chats (batch for initial load).
   * @param {Array} chats
   */
  async saveChats(chats) {
    if (!chats || chats.length === 0) return
    await db.chats.bulkPut(chats)
  },

  /**
   * Delete a chat and its messages.
   * @param {number} chatId
   */
  async deleteChat(chatId) {
    await db.chats.delete(chatId)
    await db.messages.where('chatId').equals(chatId).delete()
  },

  // ==================== Contacts ====================

  /**
   * Get all cached contacts.
   * @returns {Promise<Array>}
   */
  async getContacts() {
    return db.contacts.toArray()
  },

  /**
   * Save or update contacts (batch).
   * @param {Array} contacts
   */
  async saveContacts(contacts) {
    if (!contacts || contacts.length === 0) return
    await db.contacts.bulkPut(contacts)
  },

  /**
   * Remove a contact.
   * @param {number} contactId
   */
  async removeContact(contactId) {
    await db.contacts.delete(contactId)
  },

  /**
   * Update online status for a contact.
   * @param {number} contactId
   * @param {boolean} isOnline
   */
  async updateContactStatus(contactId, isOnline) {
    await db.contacts.update(contactId, { isOnline })
  },

  // ==================== Pending Messages (Offline Outbox) ====================

  /**
   * Queue a message for sending when back online.
   * @param {Object} pendingMsg - { chatId, content, messageType, fileUrl, clientMsgId, createdAt, status }
   */
  async queuePendingMessage(pendingMsg) {
    pendingMsg.status = 'pending'
    pendingMsg.createdAt = pendingMsg.createdAt || new Date().toISOString()
    return db.pendingMessages.add(pendingMsg)
  },

  /**
   * Get all pending messages (for retry on reconnect).
   * @returns {Promise<Array>}
   */
  async getPendingMessages() {
    return db.pendingMessages.where('status').equals('pending').toArray()
  },

  /**
   * Mark a pending message as sent.
   * @param {number} id - local pending message id
   */
  async markPendingAsSent(id) {
    await db.pendingMessages.update(id, { status: 'sent' })
  },

  /**
   * Remove a pending message after successful ACK.
   * @param {number} id
   */
  async removePendingMessage(id) {
    await db.pendingMessages.delete(id)
  },

  /**
   * Remove all sent pending messages.
   */
  async clearSentPending() {
    await db.pendingMessages.where('status').equals('sent').delete()
  },

  // ==================== Sync Metadata ====================

  /**
   * Get last sync timestamp for an entity type.
   * @param {string} type - 'messages', 'chats', 'contacts'
   * @returns {Promise<string|null>}
   */
  async getLastSyncTime(type) {
    const meta = await db.syncMeta.get(type)
    return meta ? meta.timestamp : null
  },

  /**
   * Update last sync timestamp.
   * @param {string} type
   * @param {string} timestamp - ISO string
   */
  async setLastSyncTime(type, timestamp) {
    await db.syncMeta.put({ key: type, timestamp })
  },

  // ==================== Utility ====================

  /**
   * Clear all data (on logout).
   */
  async clearAll() {
    await db.messages.clear()
    await db.chats.clear()
    await db.contacts.clear()
    await db.syncMeta.clear()
    await db.pendingMessages.clear()
  }
}

export default offlineStore

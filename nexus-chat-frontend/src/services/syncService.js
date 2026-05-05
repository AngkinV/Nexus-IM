/**
 * Sync service for incremental (delta) synchronization.
 * Implements WeChat-style delta sync: fetch only what changed since last sync.
 * Merges server delta into local IndexedDB + Pinia stores.
 */
import apiClient from './api'
import offlineStore from './offlineStore'

const syncService = {

  /**
   * Perform delta sync for all entity types.
   * Called after WebSocket connects and IndexedDB loads.
   *
   * @param {Object} stores - { chatStore, messageStore, contactStore }
   * @returns {Promise<Object>} - { messages, chats, contacts } counts of synced items
   */
  async performDeltaSync(stores) {
    const results = { messages: 0, chats: 0, contacts: 0 }

    try {
      // Get last sync timestamps from IndexedDB
      const lastMessageSync = await offlineStore.getLastSyncTime('messages')
      const lastChatSync = await offlineStore.getLastSyncTime('chats')
      const lastContactSync = await offlineStore.getLastSyncTime('contacts')

      // Build the earliest timestamp to use as 'since' parameter
      const timestamps = [lastMessageSync, lastChatSync, lastContactSync].filter(Boolean)
      const since = timestamps.length > 0
        ? new Date(Math.min(...timestamps.map(t => new Date(t).getTime()))).toISOString()
        : null

      if (!since) {
        // First sync - do a full load instead
        return results
      }

      // Call delta sync endpoint
      const response = await apiClient.get('/sync/delta', {
        params: { since, types: 'messages,chats,contacts' }
      })

      const delta = response.data

      // Merge messages
      if (delta.messages && delta.messages.length > 0) {
        await offlineStore.saveMessages(delta.messages)
        results.messages = delta.messages.length

        // Update Pinia store
        if (stores.messageStore) {
          for (const msg of delta.messages) {
            stores.messageStore.addMessage(msg.chatId, msg)
          }
        }
      }

      // Merge chats
      if (delta.chats && delta.chats.length > 0) {
        await offlineStore.saveChats(delta.chats)
        results.chats = delta.chats.length

        if (stores.chatStore) {
          stores.chatStore.mergeChats(delta.chats)
        }
      }

      // Merge contacts
      if (delta.contacts && delta.contacts.length > 0) {
        await offlineStore.saveContacts(delta.contacts)
        results.contacts = delta.contacts.length

        if (stores.contactStore) {
          stores.contactStore.mergeContacts(delta.contacts)
        }
      }

      // Update sync timestamps
      const now = new Date().toISOString()
      await offlineStore.setLastSyncTime('messages', now)
      await offlineStore.setLastSyncTime('chats', now)
      await offlineStore.setLastSyncTime('contacts', now)

      console.log(`[Sync] Delta sync complete: ${results.messages} messages, ${results.chats} chats, ${results.contacts} contacts`)
    } catch (error) {
      console.error('[Sync] Delta sync failed:', error)
    }

    return results
  },

  /**
   * Flush pending (offline) messages to the server.
   * Called when connection is restored.
   *
   * @param {Function} sendFn - WebSocket send function
   */
  async flushPendingMessages(sendFn) {
    const pending = await offlineStore.getPendingMessages()
    if (pending.length === 0) return

    console.log(`[Sync] Flushing ${pending.length} pending messages`)

    for (const msg of pending) {
      try {
        await sendFn({
          chatId: msg.chatId,
          senderId: msg.senderId,
          content: msg.content,
          messageType: msg.messageType,
          fileUrl: msg.fileUrl,
          clientMsgId: msg.clientMsgId
        })
        await offlineStore.markPendingAsSent(msg.id)
      } catch (error) {
        console.error('[Sync] Failed to flush pending message:', error)
        break // Stop on first failure, retry later
      }
    }

    // Clean up successfully sent messages
    await offlineStore.clearSentPending()
  }
}

export default syncService

/**
 * Dexie.js IndexedDB Schema for Nexus Chat.
 * Provides offline-first data persistence.
 */
import Dexie from 'dexie'

const db = new Dexie('NexusChatDB')

db.version(1).stores({
  // Messages: primary key is localId (auto-increment), indexed by server id, chatId, senderId, createdAt
  // Compound index [chatId+createdAt] for efficient pagination within a chat
  messages: '++localId, id, chatId, senderId, createdAt, clientMsgId, sequenceNumber, [chatId+createdAt]',

  // Chats: primary key is server id
  chats: 'id, lastMessageAt, type',

  // Contacts: primary key is userId
  contacts: 'id, isOnline',

  // Sync metadata: key-value store for tracking last sync timestamps
  syncMeta: 'key',

  // Pending messages (offline outbox): messages queued for sending
  pendingMessages: '++id, chatId, createdAt, status'
})

export default db

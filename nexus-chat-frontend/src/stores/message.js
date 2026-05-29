import { defineStore } from 'pinia'
import { ref } from 'vue'
import offlineStore from '@/services/offlineStore'

export const useMessageStore = defineStore('message', () => {
    const messages = ref({}) // chatId -> messages array
    const typingUsers = ref({}) // chatId -> userId array

    function getMessages(chatId) {
        return messages.value[chatId] || []
    }

    function setMessages(chatId, messageList) {
        messages.value[chatId] = messageList
    }

    /**
     * Add a message with dual deduplication (server id + clientMsgId).
     * Also persists to IndexedDB for offline-first support.
     */
    function addMessage(chatId, message) {
        if (!messages.value[chatId]) {
            messages.value[chatId] = []
        }

        // Deduplicate by server id
        if (message.id && messages.value[chatId].some(m => m.id === message.id)) {
            return
        }

        // Deduplicate by clientMsgId (optimistic message already in list)
        if (message.clientMsgId && messages.value[chatId].some(
            m => m.clientMsgId === message.clientMsgId
        )) {
            return
        }

        messages.value[chatId].push(message)

        // Write-through to IndexedDB (fire-and-forget)
        offlineStore.saveMessage({ ...message, chatId }).catch(() => {})
    }

    /**
     * Replace an optimistic message matched by clientMsgId with the real server message.
     * Returns true if replacement was made.
     */
    function replaceByClientMsgId(chatId, clientMsgId, realMessage) {
        if (!messages.value[chatId] || !clientMsgId) return false

        const idx = messages.value[chatId].findIndex(m => m.clientMsgId === clientMsgId)
        if (idx !== -1) {
            messages.value[chatId][idx] = { ...messages.value[chatId][idx], ...realMessage }
            offlineStore.saveMessage({ ...realMessage, chatId }).catch(() => {})
            return true
        }
        return false
    }

    /**
     * Update a message found by clientMsgId with partial updates (e.g., ACK data).
     */
    function updateMessageByClientMsgId(chatId, clientMsgId, updates) {
        if (!messages.value[chatId] || !clientMsgId) return

        const msg = messages.value[chatId].find(m => m.clientMsgId === clientMsgId)
        if (msg) {
            Object.assign(msg, updates)
            offlineStore.saveMessage({ ...msg, chatId }).catch(() => {})
        }
    }

    function updateMessage(chatId, messageId, updates) {
        if (!messages.value[chatId] || !messageId) return false
        const msg = messages.value[chatId].find(m => m.id === messageId)
        if (!msg) return false
        Object.assign(msg, updates)
        offlineStore.saveMessage({ ...msg, chatId }).catch(() => {})
        return true
    }

    function applyServerMessage(chatId, serverMessage) {
        if (!serverMessage) return false
        return updateMessage(chatId, serverMessage.id, normalizeServerMessage(serverMessage))
    }

    function updateReactions(chatId, messageId, reactions) {
        return updateMessage(chatId, messageId, { reactions: reactions || [] })
    }

    function normalizeServerMessage(payload) {
        return {
            id: payload.id,
            chatId: payload.chatId,
            senderId: payload.senderId,
            senderName: payload.senderNickname,
            senderAvatar: payload.senderAvatar,
            content: payload.content,
            type: payload.messageType?.toUpperCase() || 'TEXT',
            fileUrl: payload.fileUrl,
            fileId: payload.fileId,
            fileName: payload.fileName,
            fileSize: payload.fileSize,
            mimeType: payload.mimeType,
            downloadUrl: payload.downloadUrl,
            previewUrl: payload.previewUrl,
            timestamp: payload.createdAt,
            createdAt: payload.createdAt,
            isRead: payload.isRead,
            isEdited: payload.isEdited,
            editedAt: payload.editedAt,
            editCount: payload.editCount || 0,
            canEdit: payload.canEdit,
            canRecall: payload.canRecall,
            isRecalled: payload.isRecalled,
            recalledAt: payload.recalledAt,
            replyToMessageId: payload.replyToMessageId,
            replyToMessage: payload.replyToMessage,
            reactions: payload.reactions || [],
            deliveredCount: payload.deliveredCount || 0,
            readCount: payload.readCount || 0,
            clientMsgId: payload.clientMsgId,
            sequenceNumber: payload.sequenceNumber
        }
    }

    // Replace temporary message with real message from server (legacy content-based match)
    function replaceTemporaryMessage(chatId, senderId, realMessage) {
        if (!messages.value[chatId]) return false

        const tempIndex = messages.value[chatId].findIndex(m =>
            String(m.id).startsWith('temp-') &&
            m.senderId === senderId &&
            m.content === realMessage.content
        )

        if (tempIndex !== -1) {
            messages.value[chatId][tempIndex] = realMessage
            offlineStore.saveMessage({ ...realMessage, chatId }).catch(() => {})
            return true
        }
        return false
    }

    function prependMessages(chatId, messageList) {
        if (!messages.value[chatId]) {
            messages.value[chatId] = []
        }
        messages.value[chatId] = [...messageList, ...messages.value[chatId]]
    }

    function markMessageAsRead(chatId, messageId) {
        const chatMessages = messages.value[chatId]
        if (chatMessages) {
            const message = chatMessages.find(m => m.id === messageId)
            if (message) {
                message.isRead = true
            }
        }
    }

    /**
     * Mark all messages in a chat as read (batch read receipt).
     */
    function markAllMessagesRead(chatId, userId) {
        const chatMessages = messages.value[chatId]
        if (!chatMessages) return

        chatMessages.forEach(msg => {
            if (msg.senderId !== userId) {
                msg.isRead = true
            }
        })
    }

    /**
     * Mark a message as delivered (double-tick style).
     */
    function markMessageDelivered(chatId, messageId) {
        const chatMessages = messages.value[chatId]
        if (!chatMessages) return

        const msg = chatMessages.find(m => m.id === messageId)
        if (msg) {
            msg.status = 'delivered'
        }
    }

    /**
     * Mark a message as failed by clientMsgId.
     */
    function markMessageFailed(chatId, clientMsgId) {
        const chatMessages = messages.value[chatId]
        if (!chatMessages) return

        const msg = chatMessages.find(m => m.clientMsgId === clientMsgId)
        if (msg) {
            msg.failed = true
            msg.status = 'failed'
        }
    }

    /**
     * Remove a single message (used when resending a failed message).
     */
    function removeMessage(chatId, messageId) {
        const chatMessages = messages.value[chatId]
        if (!chatMessages) return
        const idx = chatMessages.findIndex(m => m.id === messageId)
        if (idx !== -1) chatMessages.splice(idx, 1)
    }

    function addTypingUser(chatId, userId) {
        if (!typingUsers.value[chatId]) {
            typingUsers.value[chatId] = []
        }
        if (!typingUsers.value[chatId].includes(userId)) {
            typingUsers.value[chatId].push(userId)
        }
    }

    function removeTypingUser(chatId, userId) {
        if (typingUsers.value[chatId]) {
            typingUsers.value[chatId] = typingUsers.value[chatId].filter(id => id !== userId)
        }
    }

    function getTypingUsers(chatId) {
        return typingUsers.value[chatId] || []
    }

    // Mark the last temporary message as failed (for send error handling)
    function markLastMessageFailed(chatId) {
        if (!messages.value[chatId]) return

        for (let i = messages.value[chatId].length - 1; i >= 0; i--) {
            const msg = messages.value[chatId][i]
            if (String(msg.id).startsWith('temp-')) {
                msg.failed = true
                msg.status = 'failed'
                break
            }
        }
    }

    // Clear all messages for a chat (used when deleting chat)
    function clearMessages(chatId) {
        delete messages.value[chatId]
        offlineStore.clearChatMessages(chatId).catch(() => {})
    }

    /**
     * Load messages for a chat from IndexedDB (offline-first).
     * Returns the loaded messages.
     */
    async function loadFromCache(chatId, limit = 50) {
        try {
            const cached = await offlineStore.getMessages(chatId, limit)
            if (cached.length > 0) {
                messages.value[chatId] = cached
            }
            return cached
        } catch (e) {
            console.error('[MessageStore] Failed to load from cache:', e)
            return []
        }
    }

    return {
        messages,
        getMessages,
        setMessages,
        addMessage,
        replaceByClientMsgId,
        updateMessageByClientMsgId,
        updateMessage,
        applyServerMessage,
        updateReactions,
        normalizeServerMessage,
        replaceTemporaryMessage,
        prependMessages,
        markMessageAsRead,
        markAllMessagesRead,
        markMessageDelivered,
        markMessageFailed,
        removeMessage,
        markLastMessageFailed,
        clearMessages,
        loadFromCache,
        addTypingUser,
        removeTypingUser,
        getTypingUsers
    }
})

import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { useMessageStore } from '@/stores/message'
import { useChatStore } from '@/stores/chat'
import { useContactStore } from '@/stores/contact'
import { useUserStore } from '@/stores/user'
import { resolveFileUrl } from '@/services/api'
import { WS_URL } from '@/services/runtimeConfig'

/**
 * Call signaling message types
 */
export const CallSignalType = {
    // Call initiation & response
    CALL_INVITE: 'CALL_INVITE',
    CALL_ACCEPT: 'CALL_ACCEPT',
    CALL_REJECT: 'CALL_REJECT',
    CALL_CANCEL: 'CALL_CANCEL',
    CALL_BUSY: 'CALL_BUSY',
    CALL_TIMEOUT: 'CALL_TIMEOUT',
    CALL_END: 'CALL_END',
    // WebRTC signaling
    CALL_OFFER: 'CALL_OFFER',
    CALL_ANSWER: 'CALL_ANSWER',
    CALL_ICE_CANDIDATE: 'CALL_ICE_CANDIDATE',
    // In-call controls
    CALL_MUTE: 'CALL_MUTE',
    CALL_VIDEO_TOGGLE: 'CALL_VIDEO_TOGGLE'
}

/**
 * Generate a UUID v4 for client message deduplication.
 */
function generateClientMsgId() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = Math.random() * 16 | 0
        return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16)
    })
}

class WebSocketService {
    constructor() {
        this.client = null
        this.connected = false
        this.reconnectAttempts = 0
        this.maxReconnectAttempts = Infinity
        this.reconnectDelay = 1000
        this.maxReconnectDelay = 30000
        this.onConnectCallback = null
        this.currentUserId = null
        this.visibilityHandler = null
        this.heartbeatInterval = null
        this.serverHeartbeatInterval = null
        this.lastHeartbeatResponse = Date.now()
        // ACK tracking: clientMsgId -> { resolve, reject, timeout }
        this.pendingAcks = new Map()
        // Call signaling callback
        this.onCallSignal = null
    }

    connect(userId, onConnectCallback = null) {
        if (this.connected) {
            if (onConnectCallback) onConnectCallback()
            return
        }

        this.currentUserId = userId
        this.onConnectCallback = onConnectCallback
        const socket = new SockJS(WS_URL)

        // Get JWT token for authentication
        const token = localStorage.getItem('token')

        this.client = new Client({
            webSocketFactory: () => socket,
            connectHeaders: {
                // Prefer JWT; fallback to userId for backwards compatibility
                ...(token ? { Authorization: `Bearer ${token}` } : { userId: String(userId) })
            },
            reconnectDelay: 0,
            heartbeatIncoming: 10000,
            heartbeatOutgoing: 10000,

            onConnect: () => {
                console.log('WebSocket connected')
                this.connected = true
                this.reconnectAttempts = 0
                this.lastHeartbeatResponse = Date.now()

                // Subscribe to unified user channel (no more per-chat subscriptions)
                this.subscribeToTopics(userId)

                // Send online status
                this.updateUserStatus(userId, true)

                // Start heartbeat monitors
                this.startHeartbeatMonitor()
                this.startServerHeartbeat()

                // Setup page visibility handler
                this.setupVisibilityHandler()

                if (this.onConnectCallback) {
                    this.onConnectCallback()
                }
            },

            onStompError: (frame) => {
                console.error('STOMP error:', frame)
                this.connected = false
            },

            onWebSocketClose: () => {
                console.log('WebSocket closed')
                this.connected = false
                this.stopHeartbeatMonitor()
                this.stopServerHeartbeat()
                this.attemptReconnect(userId)
            }
        })

        this.client.activate()
    }

    /**
     * Subscribe to unified user channel.
     * All messages (chat, typing, read receipts, ACK) come through one channel.
     */
    subscribeToTopics(userId) {
        // Primary unified channel - receives ALL real-time events
        this.client.subscribe(`/topic/user.${userId}.messages`, (message) => {
            this.updateHeartbeat()
            const data = JSON.parse(message.body)

            switch (data.type) {
                case 'CHAT_MESSAGE':
                    this.handleChatMessage(data)
                    break
                case 'MESSAGE_ACK':
                    this.handleMessageAck(data)
                    break
                case 'MESSAGE_DELIVERED':
                    this.handleMessageDelivered(data)
                    break
                case 'MESSAGE_DELIVERY_FAILED':
                    this.handleMessageDeliveryFailed(data)
                    break
                case 'MESSAGE_EDITED':
                case 'MESSAGE_RECALLED':
                    this.handleMessageUpdated(data)
                    break
                case 'MESSAGE_REACTION_UPDATED':
                    this.handleMessageReactionUpdated(data)
                    break
                case 'TYPING':
                    this.handleTypingIndicator(data)
                    break
                case 'MESSAGE_READ':
                    this.handleReadReceipt(data)
                    break
                case 'GROUP_MEMBER_JOINED':
                case 'GROUP_MEMBER_LEFT':
                case 'GROUP_UPDATED':
                case 'GROUP_DELETED':
                case 'GROUP_ADMIN_CHANGED':
                case 'GROUP_OWNERSHIP_TRANSFERRED':
                    this.handleChatEvent({ body: JSON.stringify(data) })
                    break
                // Call signaling
                case 'CALL_INVITE':
                case 'CALL_ACCEPT':
                case 'CALL_REJECT':
                case 'CALL_CANCEL':
                case 'CALL_BUSY':
                case 'CALL_TIMEOUT':
                case 'CALL_END':
                case 'CALL_OFFER':
                case 'CALL_ANSWER':
                case 'CALL_ICE_CANDIDATE':
                case 'CALL_MUTE':
                case 'CALL_VIDEO_TOGGLE':
                    this.handleCallSignal(data)
                    break
                case 'ERROR':
                    this.handleError(data)
                    break
                default:
                    console.log('Unhandled message type via user channel:', data.type)
            }
        })

        // Contact events channel
        this.client.subscribe(`/topic/user.${userId}.contacts`, (message) => {
            this.updateHeartbeat()
            this.handleContactEvent(message)
        })

        // Chat events (new chats created via /user/queue/chats)
        this.client.subscribe('/user/queue/chats', (message) => {
            this.updateHeartbeat()
            this.handleChatEvent(message)
        })
    }

    handleChatMessage(data) {
        const messageStore = useMessageStore()
        const chatStore = useChatStore()
        const userStore = useUserStore()

        const payload = data.payload
        const currentUserId = userStore.currentUser?.id
        const isSelf = payload.senderId === currentUserId

        const msg = {
            id: payload.id,
            chatId: payload.chatId,
            senderId: payload.senderId,
            senderName: payload.senderNickname,
            senderAvatar: resolveFileUrl(payload.senderAvatar),
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
            isSelf: isSelf,
            sequenceNumber: payload.sequenceNumber,
            clientMsgId: payload.clientMsgId,
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
            readCount: payload.readCount || 0
        }

        // If the user previously hid this chat, a new inbound message
        // resurrects it (matches WhatsApp/WeChat behavior).
        if (chatStore.isChatHidden && chatStore.isChatHidden(msg.chatId)) {
            chatStore.unhideChat(msg.chatId)
        }

        // Check if chat exists in the list
        const chatExists = chatStore.chats.some(c => c.id === msg.chatId)
        if (!chatExists) {
            const newChat = {
                id: msg.chatId,
                name: msg.senderName || 'Unknown',
                avatar: resolveFileUrl(msg.senderAvatar),
                lastMessage: msg.content,
                lastMessageTime: msg.createdAt,
                unreadCount: 1,
                online: true,
                status: 'online',
                type: 'DIRECT',
                contactId: msg.senderId
            }
            chatStore.chats.unshift(newChat)
        }

        // Deduplicate using clientMsgId (for self-messages matched to optimistic send)
        if (isSelf && msg.clientMsgId) {
            const replaced = messageStore.replaceByClientMsgId(msg.chatId, msg.clientMsgId, msg)
            if (!replaced) {
                messageStore.addMessage(msg.chatId, msg)
            }
        } else if (isSelf) {
            const replaced = messageStore.replaceTemporaryMessage(msg.chatId, msg.senderId, msg)
            if (!replaced) {
                messageStore.addMessage(msg.chatId, msg)
            }
        } else {
            messageStore.addMessage(msg.chatId, msg)
        }

        // Update last message in chat list
        chatStore.updateChat(msg.chatId, {
            lastMessage: msg.content,
            lastMessageTime: msg.createdAt
        })

        // Increment unread count if not current chat
        if (chatStore.selectedChatId !== msg.chatId) {
            chatStore.incrementUnreadCount(msg.chatId)

            if (window.electronAPI) {
                window.electronAPI.showNotification(msg.senderName, msg.content)
            }
        }
    }

    /**
     * Handle MESSAGE_ACK from server (confirms message was persisted).
     */
    handleMessageAck(data) {
        const { clientMsgId, serverMsgId, chatId, sequenceNumber } = data.payload
        if (!clientMsgId) return

        // Resolve pending ACK promise
        const pending = this.pendingAcks.get(clientMsgId)
        if (pending) {
            clearTimeout(pending.timeout)
            pending.resolve({ serverMsgId, chatId, sequenceNumber })
            this.pendingAcks.delete(clientMsgId)
        }

        // Update the optimistic message with server-assigned ID
        const messageStore = useMessageStore()
        messageStore.updateMessageByClientMsgId(chatId, clientMsgId, {
            id: serverMsgId,
            sequenceNumber,
            status: 'sent'
        })
    }

    /**
     * Handle MESSAGE_DELIVERED (message reached recipient).
     */
    handleMessageDelivered(data) {
        const { messageId, chatId } = data.payload
        const messageStore = useMessageStore()
        messageStore.markMessageDelivered(chatId, messageId)
    }

    /**
     * Handle MESSAGE_DELIVERY_FAILED.
     */
    handleMessageDeliveryFailed(data) {
        const { clientMsgId, chatId, error } = data.payload
        console.error('Message delivery failed:', error)

        const pending = this.pendingAcks.get(clientMsgId)
        if (pending) {
            clearTimeout(pending.timeout)
            pending.reject(new Error(error))
            this.pendingAcks.delete(clientMsgId)
        }

        const messageStore = useMessageStore()
        if (clientMsgId) {
            messageStore.markMessageFailed(chatId, clientMsgId)
        } else {
            messageStore.markLastMessageFailed(chatId)
        }
    }

    handleMessageUpdated(data) {
        const payload = data.payload
        if (!payload?.chatId || !payload?.id) return
        const messageStore = useMessageStore()
        const userStore = useUserStore()
        messageStore.updateMessage(payload.chatId, payload.id, {
            content: payload.content,
            type: payload.messageType?.toUpperCase() || 'TEXT',
            fileUrl: payload.fileUrl,
            isEdited: payload.isEdited,
            editedAt: payload.editedAt,
            editCount: payload.editCount || 0,
            canEdit: payload.canEdit,
            canRecall: payload.canRecall,
            isRecalled: payload.isRecalled,
            recalledAt: payload.recalledAt,
            reactions: payload.reactions || [],
            replyToMessageId: payload.replyToMessageId,
            replyToMessage: payload.replyToMessage,
            isSelf: payload.senderId === userStore.currentUser?.id
        })
    }

    handleMessageReactionUpdated(data) {
        const { chatId, messageId, reactions } = data.payload || {}
        if (!chatId || !messageId) return
        const messageStore = useMessageStore()
        messageStore.updateReactions(chatId, messageId, reactions || [])
    }

    /**
     * Handle typing indicator received via unified channel.
     */
    handleTypingIndicator(data) {
        const { chatId, userId, isTyping } = data.payload
        const messageStore = useMessageStore()
        if (isTyping) {
            messageStore.addTypingUser(chatId, userId)
            // Auto-clear after 5 seconds (matches backend Redis TTL)
            setTimeout(() => messageStore.removeTypingUser(chatId, userId), 5000)
        } else {
            messageStore.removeTypingUser(chatId, userId)
        }
    }

    /**
     * Handle read receipt via unified channel.
     */
    handleReadReceipt(data) {
        const { chatId, userId, messageId } = data.payload
        const messageStore = useMessageStore()
        if (messageId === 'all') {
            messageStore.markAllMessagesRead(chatId, userId)
        } else {
            messageStore.markMessageAsRead(chatId, messageId)
        }
    }

    /**
     * Handle error message.
     */
    handleError(data) {
        const { chatId, error } = data.payload || {}
        console.error('WebSocket error:', error, 'chatId:', chatId)
        if (chatId) {
            const messageStore = useMessageStore()
            messageStore.markLastMessageFailed(chatId)
        }
    }

    /**
     * Handle call signaling message.
     */
    handleCallSignal(data) {
        console.log('Call signal received:', data.type, data.payload)
        if (this.onCallSignal) {
            this.onCallSignal(data)
        }
    }

    /**
     * Set call signaling callback.
     * @param {Function} callback - (data) => void
     */
    setCallSignalCallback(callback) {
        this.onCallSignal = callback
    }

    /**
     * Send call signaling message.
     * @param {string} type - Signal type (e.g., CALL_INVITE, CALL_ACCEPT)
     * @param {Object} payload - Signal payload
     */
    sendCallSignal(type, payload) {
        if (!this.connected) {
            console.error('WebSocket not connected, cannot send call signal')
            return false
        }

        this.client.publish({
            destination: '/app/call.signal',
            body: JSON.stringify({
                type,
                payload,
                timestamp: Date.now()
            })
        })
        return true
    }

    /**
     * Send call invite.
     */
    sendCallInvite(callId, callType, callerId, calleeId) {
        return this.sendCallSignal('CALL_INVITE', {
            callId,
            callType,
            callerId,
            calleeId
        })
    }

    /**
     * Send call accept.
     */
    sendCallAccept(callId, callerId, calleeId) {
        return this.sendCallSignal('CALL_ACCEPT', {
            callId,
            callerId,
            calleeId
        })
    }

    /**
     * Send call reject.
     */
    sendCallReject(callId, callerId, calleeId, reason = 'rejected') {
        return this.sendCallSignal('CALL_REJECT', {
            callId,
            callerId,
            calleeId,
            reason
        })
    }

    /**
     * Send call cancel (caller cancels before answer).
     */
    sendCallCancel(callId, callerId, calleeId) {
        return this.sendCallSignal('CALL_CANCEL', {
            callId,
            callerId,
            calleeId
        })
    }

    /**
     * Send call busy (callee is in another call).
     */
    sendCallBusy(callId, callerId, calleeId) {
        return this.sendCallSignal('CALL_BUSY', {
            callId,
            callerId,
            calleeId
        })
    }

    /**
     * Send call end.
     */
    sendCallEnd(callId, callerId, calleeId, duration = 0) {
        return this.sendCallSignal('CALL_END', {
            callId,
            callerId,
            calleeId,
            duration
        })
    }

    /**
     * Send WebRTC offer.
     */
    sendCallOffer(callId, callerId, calleeId, sdp) {
        return this.sendCallSignal('CALL_OFFER', {
            callId,
            callerId,
            calleeId,
            sdp
        })
    }

    /**
     * Send WebRTC answer.
     */
    sendCallAnswer(callId, callerId, calleeId, sdp) {
        return this.sendCallSignal('CALL_ANSWER', {
            callId,
            callerId,
            calleeId,
            sdp
        })
    }

    /**
     * Send ICE candidate.
     * Note: Backend uses userId/remoteUserId for bidirectional signals like ICE_CANDIDATE
     */
    sendIceCandidate(callId, userId, remoteUserId, candidate) {
        return this.sendCallSignal('CALL_ICE_CANDIDATE', {
            callId,
            userId,
            remoteUserId,
            candidate
        })
    }

    /**
     * Send mute status change.
     */
    sendMuteStatus(callId, userId, remoteUserId, isMuted) {
        return this.sendCallSignal('CALL_MUTE', {
            callId,
            userId,
            remoteUserId,
            isMuted
        })
    }

    /**
     * Send video toggle status.
     */
    sendVideoToggle(callId, userId, remoteUserId, isVideoEnabled) {
        return this.sendCallSignal('CALL_VIDEO_TOGGLE', {
            callId,
            userId,
            remoteUserId,
            isVideoEnabled
        })
    }

    handleUserStatus(message) {
        const data = JSON.parse(message.body)
        const contactStore = useContactStore()
        const chatStore = useChatStore()

        if (data.type === 'USER_ONLINE') {
            contactStore.updateContactStatus(data.payload.userId, true)
            chatStore.updateMemberOnlineStatus(data.payload.userId, true)
        } else if (data.type === 'USER_OFFLINE') {
            contactStore.updateContactStatus(data.payload.userId, false)
            chatStore.updateMemberOnlineStatus(data.payload.userId, false)
        }
    }

    handleContactEvent(message) {
        const data = JSON.parse(message.body)
        const contactStore = useContactStore()

        if (data.type === 'CONTACT_REQUEST') {
            contactStore.handleNewRequest(data.payload)
        } else if (data.type === 'CONTACT_REQUEST_ACCEPTED') {
            contactStore.handleRequestAccepted(data.payload)
        } else if (data.type === 'CONTACT_REQUEST_REJECTED') {
            contactStore.handleRequestRejected(data.payload)
        } else if (data.type === 'CONTACT_ADDED') {
            const contactData = data.payload
            contactStore.addContact({
                id: contactData.userId,
                username: contactData.username,
                nickname: contactData.nickname,
                avatarUrl: resolveFileUrl(contactData.avatarUrl),
                avatar: resolveFileUrl(contactData.avatarUrl),
                isOnline: contactData.isOnline,
                lastSeen: contactData.lastSeen
            })
        } else if (data.type === 'CONTACT_REMOVED') {
            contactStore.removeContact(data.payload.contactId)
        } else if (data.type === 'CONTACT_STATUS_CHANGED') {
            contactStore.updateContactStatus(data.payload.userId, data.payload.isOnline)
            const chatStore = useChatStore()
            chatStore.updateMemberOnlineStatus(data.payload.userId, data.payload.isOnline)
        } else if (data.type === 'CHAT_DISABLED') {
            const chatStore = useChatStore()
            chatStore.handleChatDisabled(data.payload.chatId)
        }
    }

    handleChatEvent(message) {
        const data = JSON.parse(message.body)
        const chatStore = useChatStore()

        if (data.type === 'CHAT_CREATED') {
            const chatData = data.payload
            const isGroup = chatData.type === 'group'
            const memberOnline = chatData.members?.find(m => m.id !== chatData.createdBy)?.isOnline || false

            const newChat = {
                id: chatData.id,
                contactId: isGroup ? null : chatData.members?.find(m => m.id !== chatData.createdBy)?.id,
                name: chatData.name,
                description: chatData.description || '',
                avatar: resolveFileUrl(chatData.avatar || chatData.members?.[0]?.avatarUrl),
                isPrivate: chatData.isPrivate || false,
                lastMessage: chatData.lastMessage?.content || '',
                lastMessageTime: chatData.lastMessageAt || new Date(),
                unreadCount: 0,
                online: memberOnline,
                status: memberOnline ? 'online' : 'offline',
                type: isGroup ? 'GROUP' : 'DIRECT',
                members: chatData.members,
                memberCount: chatData.memberCount || chatData.members?.length || 1
            }

            // CHAT_CREATED is an explicit user action (creating a new chat or
            // being added to one) — treat it as a resurrection of any prior
            // local hide.
            if (chatStore.isChatHidden && chatStore.isChatHidden(newChat.id)) {
                chatStore.unhideChat(newChat.id)
            }

            const exists = chatStore.chats.some(c => c.id === newChat.id)
            if (!exists) {
                chatStore.chats.unshift(newChat)
            }
        } else if (data.type === 'GROUP_UPDATED') {
            const groupData = data.payload
            chatStore.updateChat(groupData.id, {
                name: groupData.name,
                description: groupData.description,
                avatar: resolveFileUrl(groupData.avatar),
                isPrivate: groupData.isPrivate
            })
        } else if (data.type === 'GROUP_MEMBER_JOINED') {
            const { groupId, member, memberCount } = data.payload
            chatStore.addGroupMember(groupId, member)
            if (memberCount !== undefined) {
                chatStore.updateChat(groupId, { memberCount })
            }
        } else if (data.type === 'GROUP_MEMBER_LEFT') {
            const { groupId, memberId, memberCount } = data.payload
            chatStore.removeGroupMember(groupId, memberId)
            if (memberCount !== undefined) {
                chatStore.updateChat(groupId, { memberCount })
            }
        } else if (data.type === 'GROUP_DELETED') {
            const { groupId } = data.payload
            chatStore.removeChat(groupId)
        } else if (data.type === 'GROUP_ADMIN_CHANGED') {
            const { groupId, memberId, isAdmin } = data.payload
            chatStore.updateGroupMemberRole(groupId, memberId, isAdmin ? 'admin' : 'member', isAdmin)
        } else if (data.type === 'GROUP_OWNERSHIP_TRANSFERRED') {
            const { groupId, oldOwnerId, newOwnerId } = data.payload
            chatStore.transferGroupOwnership(groupId, oldOwnerId, newOwnerId)
        }
    }

    /**
     * Send a message with clientMsgId for deduplication and ACK tracking.
     * Returns a Promise that resolves when the server ACKs the message.
     */
    sendMessage(chatId, senderId, content, messageType = 'text', fileUrl = null, replyToMessageId = null) {
        const clientMsgId = generateClientMsgId()

        if (!this.connected) {
            console.error('WebSocket not connected')
            return { clientMsgId, promise: Promise.reject(new Error('Not connected')) }
        }

        this.client.publish({
            destination: '/app/chat.sendMessage',
            body: JSON.stringify({
                chatId,
                senderId,
                content,
                messageType,
                fileUrl,
                replyToMessageId,
                clientMsgId
            })
        })

        // Create ACK promise with 10s timeout
        const promise = new Promise((resolve, reject) => {
            const timeout = setTimeout(() => {
                this.pendingAcks.delete(clientMsgId)
                reject(new Error('Message ACK timeout'))
            }, 10000)

            this.pendingAcks.set(clientMsgId, { resolve, reject, timeout })
        })

        return { clientMsgId, promise }
    }

    sendTypingIndicator(chatId, userId, isTyping) {
        if (!this.connected) return

        this.client.publish({
            destination: '/app/chat.typing',
            body: JSON.stringify({ chatId, userId, isTyping })
        })
    }

    updateUserStatus(userId, isOnline) {
        if (!this.connected) return

        this.client.publish({
            destination: '/app/user.status',
            body: JSON.stringify({ userId, isOnline })
        })
    }

    /**
     * Send heartbeat to server to refresh presence TTL.
     */
    sendHeartbeat() {
        if (!this.connected || !this.currentUserId) return

        this.client.publish({
            destination: '/app/user.heartbeat',
            body: JSON.stringify({ userId: this.currentUserId })
        })
    }

    /**
     * Start sending heartbeats to server every 30s.
     */
    startServerHeartbeat() {
        this.stopServerHeartbeat()
        this.serverHeartbeatInterval = setInterval(() => {
            this.sendHeartbeat()
        }, 30000)
    }

    stopServerHeartbeat() {
        if (this.serverHeartbeatInterval) {
            clearInterval(this.serverHeartbeatInterval)
            this.serverHeartbeatInterval = null
        }
    }

    attemptReconnect(userId) {
        if (this.reconnectAttempts >= this.maxReconnectAttempts) {
            console.error('Max reconnect attempts reached')
            return
        }

        this.reconnectAttempts++
        const delay = Math.min(
            this.reconnectDelay * Math.pow(2, this.reconnectAttempts - 1),
            this.maxReconnectDelay
        )
        console.log(`Reconnecting in ${delay}ms... Attempt ${this.reconnectAttempts}`)

        setTimeout(() => {
            if (this.client) {
                try { this.client.deactivate() } catch (e) { /* ignore */ }
                this.client = null
            }
            this.connect(userId || this.currentUserId, this.onConnectCallback)
        }, delay)
    }

    setupVisibilityHandler() {
        if (this.visibilityHandler) {
            document.removeEventListener('visibilitychange', this.visibilityHandler)
        }

        this.visibilityHandler = () => {
            if (document.visibilityState === 'visible') {
                if (!this.connected && this.currentUserId) {
                    this.reconnectAttempts = 0
                    this.attemptReconnect(this.currentUserId)
                }
            }
        }

        document.addEventListener('visibilitychange', this.visibilityHandler)
    }

    startHeartbeatMonitor() {
        this.stopHeartbeatMonitor()

        this.heartbeatInterval = setInterval(() => {
            const now = Date.now()
            if (this.connected && (now - this.lastHeartbeatResponse > 60000)) {
                console.warn('No heartbeat response for 60s, forcing reconnect...')
                this.connected = false
                if (this.client) {
                    try { this.client.deactivate() } catch (e) { /* ignore */ }
                }
                this.attemptReconnect(this.currentUserId)
            }
        }, 30000)
    }

    stopHeartbeatMonitor() {
        if (this.heartbeatInterval) {
            clearInterval(this.heartbeatInterval)
            this.heartbeatInterval = null
        }
    }

    updateHeartbeat() {
        this.lastHeartbeatResponse = Date.now()
    }

    disconnect() {
        this.stopHeartbeatMonitor()
        this.stopServerHeartbeat()

        if (this.visibilityHandler) {
            document.removeEventListener('visibilitychange', this.visibilityHandler)
            this.visibilityHandler = null
        }

        // Clear pending ACKs
        for (const [, pending] of this.pendingAcks) {
            clearTimeout(pending.timeout)
            pending.reject(new Error('Disconnected'))
        }
        this.pendingAcks.clear()

        if (this.client) {
            this.client.deactivate()
            this.connected = false
            this.currentUserId = null
        }
    }

    isConnected() {
        return this.connected
    }
}

export default new WebSocketService()

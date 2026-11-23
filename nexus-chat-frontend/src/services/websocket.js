import { isKnownExpiredJwt, navigateToLogin } from '@/utils/sessionGuard.js'
import { watch } from 'vue'
import { parseTypedEvent, sameId } from '@/utils/socialData.mjs'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { useMessageStore } from '@/stores/message'
import { useChatStore } from '@/stores/chat'
import { useContactStore } from '@/stores/contact'
import { useUserStore } from '@/stores/user'
import { useConnectionStore } from '@/stores/connection'
import { useSettingsStore } from '@/stores/settings'
import { playMessageSound, playSendSound, playErrorSound } from '@/utils/uiSound'
import { resolveFileUrl } from '@/services/api'
import { WS_URL } from '@/services/runtimeConfig'
import i18n from '@/locales/i18n'

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

/**
 * Decode the session id (`sid`) claim from a JWT without verifying it.
 * Used to tell whether a FORCE_LOGOUT targets this very device.
 */
function decodeJwtSid(token) {
    try {
        const b64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
        const padded = b64 + '='.repeat((4 - b64.length % 4) % 4)
        return JSON.parse(atob(padded)).sid || null
    } catch (e) {
        return null
    }
}

class WebSocketService {
    constructor() {
        this.generation = 0
        this.reconnectTimer = null
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
        // Set when the server force-logs-out this device, to stop reconnects.
        this.forcedLogout = false
    }

    stopForExpiredSession() {
        const token = localStorage.getItem('token')
        if (!isKnownExpiredJwt(token)) return false
        const store = useUserStore()
        const session = store.captureSession()
        // Stop even if storage and memory disagree; never revoke a different login.
        this.disconnect()
        if (store.expireSession(token, session)) navigateToLogin()
        return true
    }

    connect(userId, onConnectCallback = null) {
        if (!sameId(useUserStore().currentUser?.id, userId)) return
        if (this.stopForExpiredSession()) return
        if (this.connected && sameId(this.currentUserId, userId)) {
            if (onConnectCallback) onConnectCallback()
            return
        }

        if (this.client) this.disconnect()
        this.forcedLogout = false
        const generation = ++this.generation
        const current = () => generation === this.generation && sameId(useUserStore().currentUser?.id, userId)
        this.currentUserId = userId
        this.stopOwnerWatch = watch(() => useUserStore().currentUser?.id, owner => {
            if (!sameId(owner, userId)) this.disconnect()
        }, { flush: 'sync' })
        this.onConnectCallback = onConnectCallback
        useConnectionStore().markConnecting()
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
                if (!current()) return
                console.log('WebSocket connected')
                this.connected = true
                useConnectionStore().markConnected()
                this.reconnectAttempts = 0
                this.lastHeartbeatResponse = Date.now()

                // Subscribe to unified user channel (no more per-chat subscriptions)
                this.subscribeToTopics(userId)

                // Presence belongs to authenticated server connection lifecycle.

                // Start heartbeat monitors
                // STOMP negotiated heartbeats detect dead transports.
                this.startServerHeartbeat()

                // Setup page visibility handler
                this.setupVisibilityHandler()

                if (this.onConnectCallback) {
                    Promise.resolve(this.onConnectCallback()).catch(e => console.error('[WebSocket] Snapshot failed', e))
                }
            },

            onStompError: (frame) => {
                if (!current()) return
                useContactStore().invalidatePresence()
                console.error('STOMP error:', frame)
                this.connected = false
                useConnectionStore().markConnecting()
            },

            onWebSocketClose: () => {
                if (!current()) return
                useContactStore().invalidatePresence()
                console.log('WebSocket closed')
                this.connected = false
                useConnectionStore().markConnecting()
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
        const client = this.client, generation = this.generation
        const subscribe = (topic, handler) => client.subscribe(topic, message => {
            if (client !== this.client || generation !== this.generation || !sameId(useUserStore().currentUser?.id, userId)) return
            const data = parseTypedEvent(message)
            if (!data) { console.warn('[WebSocket] Invalid typed event'); return }
            try { handler({ body: JSON.stringify(data) }) } catch (e) { console.error('[WebSocket] Event rejected', data.type, e) }
        })
        // Primary unified channel - receives ALL real-time events
        subscribe(`/topic/user.${userId}.messages`, (message) => {
            this.updateHeartbeat()
            const data = parseTypedEvent(message)
        if (!data) return

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
                case 'USER_PROFILE_UPDATED':
                    useContactStore().updateUserPresence(data.payload.userId ?? data.payload.id, data.payload)
                    break
                case 'USER_STATUS_CHANGED':
                case 'CONTACT_STATUS_CHANGED':
                    useContactStore().updateUserPresence(data.payload.userId, data.payload)
                    break
                case 'GROUP_MEMBER_ADDED':
                case 'GROUP_MEMBER_REMOVED':
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
                case 'FORCE_LOGOUT':
                    this.handleForceLogout(data)
                    break
                default:
                    console.log('Unhandled message type via user channel:', data.type)
            }
        })

        // Contact events channel
        subscribe(`/topic/user.${userId}.contacts`, (message) => {
            this.updateHeartbeat()
            this.handleContactEvent(message)
        })

        // Chat events (new chats created via /user/queue/chats)
        subscribe('/user/queue/chats', (message) => {
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
            // A message alone cannot establish chat type, peer or presence.
            chatStore.fetchChats(currentUserId).catch(e => console.error('[WebSocket] Missing chat snapshot failed', e))
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
        if (!isSelf && chatStore.selectedChatId !== msg.chatId) {
            chatStore.incrementUnreadCount(msg.chatId)

            const settings = useSettingsStore()
            // The settings switch is authoritative: an explicit "off" wins.
            if (window.electronAPI && settings.shouldNotify()) {
                window.electronAPI.showNotification(msg.senderName, msg.content)
            }
            if (settings.ready && settings.soundEnabled) playMessageSound()
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
            const settings = useSettingsStore()
            if (settings.ready && settings.soundEnabled) playSendSound()
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
            const settings = useSettingsStore()
            if (settings.ready && settings.soundEnabled) playErrorSound()
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

        // When a message is recalled, refresh the chat-list preview so it no
        // longer shows the recalled content. Prefer the latest still-valid
        // message. Only recompute when this chat's messages are loaded, so we
        // never clobber a correct preview for a chat we haven't opened.
        if (payload.isRecalled) {
            const chatStore = useChatStore()
            const msgs = messageStore.getMessages(payload.chatId)
            if (msgs && msgs.length) {
                const lastValid = [...msgs].reverse().find(m => !m.isRecalled)
                chatStore.updateChat(payload.chatId, {
                    lastMessage: lastValid ? (lastValid.content || '') : i18n.global.t('chat.messageRecalled')
                })
            }
        }
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
            const settings = useSettingsStore()
            if (settings.ready && settings.soundEnabled) playErrorSound()
            const messageStore = useMessageStore()
            messageStore.markLastMessageFailed(chatId)
        }
    }

    /**
     * Server revoked one of this user's device sessions. If the targeted
     * session is THIS device, sign out immediately and return to login.
     */
    handleForceLogout(data) {
        const token = localStorage.getItem('token')
        const mySid = token ? decodeJwtSid(token) : null
        const targets = data.payload?.targetSids || []
        if (!mySid || !targets.includes(mySid)) return // not this device

        console.warn('This device was signed out remotely')
        this.forcedLogout = true
        localStorage.removeItem('token')
        localStorage.removeItem('user')
        try { this.disconnect() } catch (e) { /* ignore */ }
        const here = window.location.hash || window.location.pathname
        if (!String(here).includes('/login')) {
            window.location.reload()
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
        const data = parseTypedEvent(message)
        if (!data) return
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
        const data = parseTypedEvent(message)
        if (!data) return
        const contactStore = useContactStore()

        if (data.type === 'CONTACT_REQUEST') {
            contactStore.handleNewRequest(data.payload)
        } else if (data.type === 'CONTACT_REQUEST_ACCEPTED') {
            contactStore.handleRequestAccepted(data.payload)
        } else if (data.type === 'CONTACT_REQUEST_REJECTED') {
            contactStore.handleRequestRejected(data.payload)
        } else if (data.type === 'CONTACT_ADDED') {
            contactStore.addContact(data.payload)
        } else if (data.type === 'CONTACT_REMOVED') {
            if (data.payload.userId != null) contactStore.removeContact(data.payload.userId)
            else contactStore.removeContactRelation(data.payload.contactId)
        } else if (data.type === 'CONTACT_STATUS_CHANGED') {
            contactStore.updateUserPresence(data.payload.userId, data.payload)
        } else if (data.type === 'CHAT_DISABLED') {
            const chatStore = useChatStore()
            chatStore.handleChatDisabled(data.payload.chatId)
        }
    }

    handleChatEvent(message) {
        const data = parseTypedEvent(message)
        if (!data) return
        const chatStore = useChatStore()

        if (data.type === 'CHAT_CREATED') {
            const dto = data.payload
            chatStore.unhideChat(dto.id)
            chatStore.mergeChats([dto], useUserStore().currentUser?.id)
        } else if (data.type === 'GROUP_UPDATED') {
            chatStore.updateChat(data.payload.id ?? data.payload.groupId, data.payload)
        } else if ((data.type === 'GROUP_MEMBER_JOINED' || data.type === 'GROUP_MEMBER_ADDED')) {
            const { member, memberCount } = data.payload
            const groupId = data.payload.groupId ?? data.payload.chatId
            if (!member) { chatStore.fetchChats(this.currentUserId).catch(e => console.error('[WebSocket] Member snapshot failed', e)); return }
            chatStore.addGroupMember(groupId, member)
            if (memberCount !== undefined) {
                chatStore.updateChat(groupId, { memberCount })
            }
        } else if ((data.type === 'GROUP_MEMBER_LEFT' || data.type === 'GROUP_MEMBER_REMOVED')) {
            const { memberCount } = data.payload
            const groupId = data.payload.groupId ?? data.payload.chatId
            const memberId = data.payload.memberId ?? data.payload.userId
            if (sameId(memberId, this.currentUserId)) { chatStore.leaveGroup(groupId); return }
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

        if (!sameId(senderId, this.currentUserId) || !sameId(senderId, useUserStore().currentUser?.id)) return { clientMsgId, promise: Promise.reject(new Error('Account changed')) }
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

    updateUserStatus() {
        // Compatibility no-op: clients cannot assert server presence.
    }

    /**
     * Send heartbeat to server to refresh presence TTL.
     */
    sendHeartbeat() {
        if (!this.connected || !sameId(this.currentUserId, useUserStore().currentUser?.id)) return

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
        if (!sameId(useUserStore().currentUser?.id, userId)) return
        if (this.stopForExpiredSession()) return
        if (this.forcedLogout) {
            return // device was signed out remotely; do not reconnect
        }
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

        if (this.reconnectTimer) return
        const generation = this.generation
        this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null
            if (generation !== this.generation || !sameId(userId, useUserStore().currentUser?.id)) return
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
                useConnectionStore().markConnecting()
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
        ++this.generation
        this.stopOwnerWatch?.()
        this.stopOwnerWatch = null
        clearTimeout(this.reconnectTimer)
        this.reconnectTimer = null
        useContactStore().invalidatePresence()
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
        this.client = null
        this.connected = false
        this.currentUserId = null
        useConnectionStore().markDisconnected()
    }

    isConnected() {
        return this.connected
    }
}

export default new WebSocketService()

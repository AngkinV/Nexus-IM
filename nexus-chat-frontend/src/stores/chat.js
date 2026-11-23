import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { chatAPI, resolveFileUrl } from '@/services/api'
import offlineStore from '@/services/offlineStore'
import { AI_ASSISTANT_CHAT_ID, buildAiAssistantChatItem } from '@/stores/agent'
import i18n from '@/locales/i18n'
import { useUserStore } from '@/stores/user'
import { useContactStore } from '@/stores/contact'
import { normalizeChat, normalizeUser, sameId, createRevisionLedger } from '@/utils/socialData.mjs'

// Resolve the chat-list preview text from a backend lastMessage. Guards against
// recalled messages whose content the backend serializes as a JSON blob — those
// must show a "message recalled" placeholder, never raw JSON.
const tryParseJson = (s) => { try { return JSON.parse(s) } catch { return null } }
const resolveServerLastMessage = (lm) => {
    if (!lm) return ''
    if (typeof lm === 'object') {
        if (lm.isRecalled) return i18n.global.t('chat.messageRecalled')
        return typeof lm.content === 'string' ? lm.content : ''
    }
    if (typeof lm === 'string') {
        if (lm.trim().startsWith('{')) {
            const o = tryParseJson(lm)
            if (o && o.isRecalled) return i18n.global.t('chat.messageRecalled')
            if (o && typeof o.content === 'string') return o.content
        }
        return lm
    }
    return ''
}

export const useChatStore = defineStore('chat', () => {
    const activeChat = ref(null)
    const chats = ref([])
    const isLoading = ref(false)
    const loadError = ref(false)
    let lastFetchUserId = null
    const subscribedChatIds = ref(new Set()) // Track subscribed chat rooms

    const messages = ref([])

    // Muted, Pinned, and Hidden chats (persisted in localStorage)
    const mutedChats = ref(new Set(JSON.parse(localStorage.getItem('mutedChats') || '[]')))
    const pinnedChats = ref(new Set(JSON.parse(localStorage.getItem('pinnedChats') || '[]')))
    // hiddenChats: chats the user deleted from the recent-list. They remain on the
    // server but are filtered out of every local data path (fetch / cache / delta /
    // websocket) until a new message arrives, which auto-resurrects them.
    // Stored as a Map<id, type> so we can report e.g. "how many hidden chats are
    // groups" without re-querying anything — needed to keep the profile page's
    // group count in sync with the visible group list.
    const hiddenChats = ref(parseStoredHiddenChats())

    function parseStoredHiddenChats() {
        const raw = localStorage.getItem('hiddenChats')
        if (!raw) return new Map()
        try {
            const parsed = JSON.parse(raw)
            // Current format: Array<[id, type]>
            if (Array.isArray(parsed) && parsed.length > 0 && Array.isArray(parsed[0])) {
                return new Map(parsed)
            }
            // Legacy format: Array<id> — migrate, type unknown.
            if (Array.isArray(parsed)) {
                return new Map(parsed.map(id => [id, null]))
            }
        } catch (e) { /* fall through */ }
        return new Map()
    }

    // Save muted chats to localStorage
    const saveMutedChats = () => {
        localStorage.setItem(`mutedChats:${userStore.currentUser?.id}`,  JSON.stringify([...mutedChats.value]))
    }

    // Save pinned chats to localStorage
    const savePinnedChats = () => {
        localStorage.setItem(`pinnedChats:${userStore.currentUser?.id}`,  JSON.stringify([...pinnedChats.value]))
    }

    const saveHiddenChats = () => {
        localStorage.setItem(`hiddenChats:${userStore.currentUser?.id}`,  JSON.stringify([...hiddenChats.value.entries()]))
    }

    const isChatHidden = (chatId) => hiddenChats.value.has(chatId)

    // Resurrect a previously hidden chat (e.g. when a new message arrives for it).
    const unhideChat = (chatId) => {
        if (hiddenChats.value.delete(chatId)) {
            saveHiddenChats()
            return true
        }
        return false
    }

    // Number of hidden chats that are GROUPs. Used to reconcile the server's
    // groupCount stat with the locally-visible group list on the profile page.
    const hiddenGroupCount = computed(() => {
        let n = 0
        for (const type of hiddenChats.value.values()) {
            if (type === 'GROUP') n++
        }
        return n
    })

    // Self-heal a hidden entry's type when the server later tells us what kind
    // of chat it is. Needed for users who hid chats before we started tracking
    // type — those entries land in the Map with `null` and would otherwise
    // never count toward hiddenGroupCount, leaving the profile stat stuck.
    // Returns true if anything changed (caller may persist).
    const reconcileHiddenType = (chatId, serverType) => {
        if (!hiddenChats.value.has(chatId)) return false
        const stored = hiddenChats.value.get(chatId)
        if (stored === serverType) return false
        hiddenChats.value.set(chatId, serverType)
        return true
    }

    const normalizeServerType = (raw) => {
        if (!raw) return null
        const s = String(raw).toLowerCase()
        if (s === 'direct') return 'DIRECT'
        if (s === 'group') return 'GROUP'
        return raw // already normalized (e.g. 'DIRECT'/'GROUP')
    }

    // Map a backend ChatDTO into the shape the UI expects. For group chats the
    // chat has its OWN avatar (chat.avatar). For direct chats the chat-level
    // avatar is usually null, so we fall back to the other member's avatar.
    // Using the wrong field for groups caused different accounts to render
    // different "other member" avatars and made the creator's freshly-uploaded
    // group avatar appear inconsistent across clients.
    const ledger = createRevisionLedger()
    const membership = new Map()
    const userStore = useUserStore()
    watch(() => userStore.currentUser?.id, owner => {
        if (ledger.setOwner(owner)) {
            isLoading.value = false; loadError.value = false; membership.clear(); chats.value = []; activeChat.value = null; messages.value = []
            subscribedChatIds.value = new Set()
            // Never import the legacy unscoped preference keys into another account.
            const read = key => { try { return JSON.parse(localStorage.getItem(`${key}:${owner}`) || '[]') } catch { return [] } }
            mutedChats.value = new Set(read('mutedChats'))
            pinnedChats.value = new Set(read('pinnedChats'))
            hiddenChats.value = new Map(read('hiddenChats'))
        }
    }, { immediate: true, flush: 'sync' })
    const captureChatRevision = () => ledger.capture()
    const normalizeChatFromServer = (dto, owner) => {
        const out = normalizeChat(dto, owner, resolveFileUrl)
        if (Object.prototype.hasOwnProperty.call(dto, 'lastMessage')) out.lastMessage = resolveServerLastMessage(dto.lastMessage)
        return out
    }
    const applyUserIdentity = (userId, record) => {
        for (const chat of chats.value) {
            const members = chat.members?.map(m => sameId(m.id, userId) ? { ...m, ...record } : m)
            const patch = members ? { members } : {}
            if (chat.type === 'DIRECT' && sameId(chat.contactId, userId)) {
                if ('avatar' in record) patch.avatar = record.avatar
                if ('nickname' in record) patch.name = record.nickname
                if ('lastSeen' in record) patch.lastSeen = record.lastSeen
                if ('isOnline' in record) {
                    patch.online = record.isOnline
                    patch.status = record.isOnline === true ? 'online' : record.isOnline === false ? 'offline' : null
                }
            }
            Object.assign(chat, patch)
            if (sameId(activeChat.value?.id, chat.id)) activeChat.value = chat
        }
    }
    const invalidatePresence = () => {
        for (const chat of chats.value) {
            if (chat.type === 'DIRECT') { chat.online = null; chat.status = null }
            for (const member of chat.members || []) member.isOnline = null
            if (sameId(activeChat.value?.id, chat.id)) activeChat.value = chat
        }
    }

    // Toggle mute for a chat
    const toggleMuteChat = (chatId) => {
        if (chatId === AI_ASSISTANT_CHAT_ID) return false
        if (mutedChats.value.has(chatId)) {
            mutedChats.value.delete(chatId)
        } else {
            mutedChats.value.add(chatId)
        }
        saveMutedChats()
        return mutedChats.value.has(chatId)
    }

    // Check if chat is muted
    const isChatMuted = (chatId) => {
        return mutedChats.value.has(chatId)
    }

    // Toggle pin for a chat
    const togglePinChat = (chatId) => {
        if (chatId === AI_ASSISTANT_CHAT_ID) return true // already always pinned
        if (pinnedChats.value.has(chatId)) {
            pinnedChats.value.delete(chatId)
        } else {
            pinnedChats.value.add(chatId)
        }
        savePinnedChats()
        return pinnedChats.value.has(chatId)
    }

    // Check if chat is pinned
    const isChatPinned = (chatId) => {
        if (chatId === AI_ASSISTANT_CHAT_ID) return true
        return pinnedChats.value.has(chatId)
    }

    // Computed: Get all group chats
    const groupChats = computed(() => {
        return chats.value.filter(chat => chat.type === 'GROUP')
    })

    // Computed: Get all direct chats
    const directChats = computed(() => {
        return chats.value.filter(chat => chat.type === 'DIRECT')
    })

    // Computed: Total unread count
    const totalUnreadCount = computed(() => {
        return chats.value.reduce((sum, chat) => sum + (chat.unreadCount || 0), 0)
    })

    // Computed: Selected chat ID (for convenience)
    const selectedChatId = computed(() => activeChat.value?.id || null)

    // Computed: Sorted chats (AI assistant always first, then pinned, then by last message time)
    const sortedChats = computed(() => {
        const realChats = [...chats.value].sort((a, b) => {
            const aPinned = pinnedChats.value.has(a.id)
            const bPinned = pinnedChats.value.has(b.id)

            // Pinned chats come first
            if (aPinned && !bPinned) return -1
            if (!aPinned && bPinned) return 1

            // Same pinned status, sort by last message time
            return new Date(b.lastMessageTime || 0) - new Date(a.lastMessageTime || 0)
        })
        // Always pin the virtual AI assistant entry to the very top.
        // Touch the locale ref first so this computed re-runs when the user
        // switches language — otherwise the entry keeps the strings it was
        // first built with and the English UI shows a Chinese AI row.
        void i18n.global.locale.value
        return [buildAiAssistantChatItem(i18n.global.t), ...realChats]
    })

    // Update chat properties
    const updateChat = (chatId, updates) => {
        const existing = chats.value.find(c => sameId(c.id, chatId))
        if (existing) {
            const input = { ...existing, ...updates }
            if ('avatarUrl' in updates && !('avatar' in updates)) delete input.avatar
            const combined = normalizeChatFromServer(input, userStore.currentUser?.id)
            const patch = {}
            for (const key of Object.keys(updates)) if (combined[key] !== undefined) patch[key] = combined[key]
            if ('avatarUrl' in updates) patch.avatar = combined.avatar
            if (existing.type === 'GROUP') { delete patch.online; delete patch.status; delete patch.isOnline }
            updates = ledger.merge(chatId, existing, patch)
        }
        const chatIndex = chats.value.findIndex(c => c.id === chatId)
        if (chatIndex !== -1) {
            chats.value[chatIndex] = {
                ...chats.value[chatIndex],
                ...updates
            }
            // Update activeChat if it's the same chat
            if (activeChat.value?.id === chatId) {
                activeChat.value = { ...chats.value[chatIndex] }
            }
        }
    }

    // Increment unread count for a chat
    const incrementUnreadCount = (chatId) => {
        const chatIndex = chats.value.findIndex(c => c.id === chatId)
        if (chatIndex !== -1) {
            chats.value[chatIndex].unreadCount = (chats.value[chatIndex].unreadCount || 0) + 1
        }
    }

    // Mark chat as subscribed
    const markChatSubscribed = (chatId) => {
        subscribedChatIds.value.add(chatId)
    }

    // Check if chat is subscribed
    const isChatSubscribed = (chatId) => {
        return subscribedChatIds.value.has(chatId)
    }

    // Fetch chats from backend
    const fetchChats = async (userId) => {
        lastFetchUserId = userId
        const token = captureChatRevision()
        const identityToken = useContactStore().captureIdentityRevision()
        if (!sameId(token.owner, userId)) return
        isLoading.value = true; loadError.value = false
        try {
            const response = await chatAPI.getUserChats(userId)
            if (!ledger.valid(token)) return
            const list = response.data || []
            for (const c of [...chats.value]) if (!list.some(dto => sameId(dto.id, c.id))) {
                const state = ledger.merge(`chat:${c.id}`, membership.get(String(c.id)) || {}, { present: false }, token)
                membership.set(String(c.id), state)
                if (!state.present) {
                    chats.value = chats.value.filter(x => !sameId(x.id, c.id))
                    if (sameId(activeChat.value?.id, c.id)) activeChat.value = null
                }
            }
            mergeChats(list, userId, token, identityToken)
        } catch (error) {
            if (ledger.valid(token)) loadError.value = true
            throw error
        } finally { if (ledger.valid(token)) isLoading.value = false }
    }

    const retryFetchChats = () => {
        if (lastFetchUserId != null) fetchChats(lastFetchUserId)
    }

    const setActiveChat = (chat) => {
        activeChat.value = chat
        // Mark as read when opening chat
        if (chat) {
            const chatIndex = chats.value.findIndex(c => c.id === chat.id)
            if (chatIndex !== -1) {
                chats.value[chatIndex].unreadCount = 0
            }
        }
    }

    const clearActiveChat = () => {
        activeChat.value = null
    }

    // Toggle chat: if same chat is clicked, close it; otherwise open the new chat
    const toggleActiveChat = (chat) => {
        if (activeChat.value?.id === chat.id) {
            // Same chat clicked, close it
            activeChat.value = null
        } else {
            // Different chat or no chat open, set as active and clear unread
            setActiveChat(chat)
        }
    }

    const sendMessage = (messageData) => {
        const newMessage = {
            id: Date.now(),
            chatId: messageData.chatId,
            senderName: 'Me',
            content: messageData.content,
            timestamp: new Date(),
            type: messageData.type,
            isSelf: true,
            read: false
        }
        messages.value.push(newMessage)

        // Update last message in chat list
        const chatIndex = chats.value.findIndex(c => c.id === messageData.chatId)
        if (chatIndex !== -1) {
            chats.value[chatIndex].lastMessage = messageData.type === 'TEXT' ? messageData.content : '[Image]'
            chats.value[chatIndex].lastMessageTime = new Date()
        }
    }

    const createGroup = async (groupData) => {
        const userId = userStore.currentUser?.id
        const token = captureChatRevision(), identityToken = useContactStore().captureIdentityRevision()
        if (userId == null) throw new Error('No current account')
        const response = await chatAPI.createGroupChat(userId, groupData)
        if (!ledger.valid(token)) throw new Error('Account changed')
        mergeChats([response.data], userId, token, identityToken)
        return chats.value.find(c => sameId(c.id, response.data.id))
    }
    const createDirectChat = async (userId, contact) => {
        const token = captureChatRevision(), identityToken = useContactStore().captureIdentityRevision()
        if (!sameId(token.owner, userId)) throw new Error('Account changed')
        const existing = chats.value.find(c => c.type === 'DIRECT' && sameId(c.contactId, contact.userId ?? contact.id))
        if (existing) { setActiveChat(existing); return existing }
        const response = await chatAPI.createDirectChat(userId, contact.userId ?? contact.id)
        if (!ledger.valid(token)) throw new Error('Account changed')
        mergeChats([response.data], userId, token, identityToken)
        const chat = chats.value.find(c => sameId(c.id, response.data.id))
        setActiveChat(chat)
        return chat
    }

    const updateGroupInfo = (groupId, updates) => updateChat(groupId, updates)

    const leaveGroup = (groupId) => {
        membership.set(String(groupId), ledger.merge(`chat:${groupId}`, {}, { present: false }))
        const chatIndex = chats.value.findIndex(c => c.id === groupId)
        if (chatIndex !== -1) {
            chats.value.splice(chatIndex, 1)
            if (activeChat.value?.id === groupId) {
                activeChat.value = null
            }
        }
    }

    const deleteChat = (chatId) => {
        if (chatId === AI_ASSISTANT_CHAT_ID) return // virtual chat, cannot delete
        // Local-only removal: hide the chat everywhere it could resurface.
        // The chat, members, and messages remain intact on the server; a new
        // inbound message will auto-unhide it via unhideChat().
        const chatIndex = chats.value.findIndex(c => c.id === chatId)
        // Capture the chat type before splicing so hiddenGroupCount can stay
        // accurate even after the chat is gone from chats.value.
        const type = chatIndex !== -1 ? (chats.value[chatIndex]?.type || null) : null
        hiddenChats.value.set(chatId, type)
        saveHiddenChats()

        if (chatIndex !== -1) {
            chats.value.splice(chatIndex, 1)
        }

        // Clear activeChat if it's the deleted one
        if (activeChat.value?.id === chatId) {
            activeChat.value = null
        }

        // Clear pinned and muted status
        pinnedChats.value.delete(chatId)
        mutedChats.value.delete(chatId)
        savePinnedChats()
        saveMutedChats()

        // Remove from subscribed list
        subscribedChatIds.value.delete(chatId)

        // Wipe the IndexedDB cache so loadFromCache() does not bring it back
        // on next launch. Also clears cached messages for that chat.
        offlineStore.deleteChat(chatId).catch(() => {})
    }

    // Handle chat disabled (contact removed)
    const handleChatDisabled = (chatId) => {
        membership.set(String(chatId), ledger.merge(`chat:${chatId}`, {}, { present: false }))
        // Remove from chat list
        const chatIndex = chats.value.findIndex(c => c.id === chatId)
        if (chatIndex !== -1) {
            chats.value.splice(chatIndex, 1)
        }

        // Clear active chat if it's the disabled one
        if (activeChat.value?.id === chatId) {
            activeChat.value = null
        }

        // Remove from subscribed list
        subscribedChatIds.value.delete(chatId)
    }

    const getMessagesForChat = (chatId) => {
        return messages.value.filter(m => m.chatId === chatId)
    }

    // 更新聊天中成员的在线状态（用于WebSocket实时同步）
    const updateMemberOnlineStatus = (userId, isOnline) => useContactStore().updateUserPresence(userId, { isOnline })

    // Add member to group
    const addGroupMember = (groupId, member) => {
        useContactStore().ingestUserSnapshots([member])
        member = { ...normalizeUser(member, resolveFileUrl), ...useContactStore().getUserIdentity(member.userId ?? member.id) }
        const chatIndex = chats.value.findIndex(c => c.id === groupId)
        if (chatIndex !== -1) {
            if (!chats.value[chatIndex].members) {
                chats.value[chatIndex].members = []
            }
            // Check if member already exists
            const exists = chats.value[chatIndex].members.some(m => sameId(m.id, member.id))
            if (exists) Object.assign(chats.value[chatIndex].members.find(m => sameId(m.id, member.id)), member)
            if (!exists) {
                chats.value[chatIndex].members.push(member)
                if (Number.isInteger(chats.value[chatIndex].memberCount)) chats.value[chatIndex].memberCount++
            }
            updateChat(groupId, { members: chats.value[chatIndex].members, memberCount: chats.value[chatIndex].memberCount })
            // Update activeChat if same
            if (activeChat.value?.id === groupId) {
                activeChat.value = { ...chats.value[chatIndex] }
            }
        }
    }

    // Remove member from group
    const removeGroupMember = (groupId, memberId) => {
        const chatIndex = chats.value.findIndex(c => c.id === groupId)
        if (chatIndex !== -1 && chats.value[chatIndex].members) {
            const memberIndex = chats.value[chatIndex].members.findIndex(m => sameId(m.id, memberId))
            if (memberIndex !== -1) {
                chats.value[chatIndex].members.splice(memberIndex, 1)
                if (Number.isInteger(chats.value[chatIndex].memberCount)) chats.value[chatIndex].memberCount = Math.max(chats.value[chatIndex].memberCount - 1, 0)
            }
            updateChat(groupId, { members: chats.value[chatIndex].members, memberCount: chats.value[chatIndex].memberCount })
            // Update activeChat if same
            if (activeChat.value?.id === groupId) {
                activeChat.value = { ...chats.value[chatIndex] }
            }
        }
    }

    // Remove chat (alias for deleteChat, used by WebSocket)
    const removeChat = (chatId) => {
        deleteChat(chatId)
    }

    // Update group member role
    const updateGroupMemberRole = (groupId, memberId, role, isAdmin) => {
        const chatIndex = chats.value.findIndex(c => c.id === groupId)
        if (chatIndex !== -1 && chats.value[chatIndex].members) {
            const member = chats.value[chatIndex].members.find(m => sameId(m.id, memberId))
            if (member) {
                member.role = role
                member.isAdmin = isAdmin
            }
            updateChat(groupId, { members: chats.value[chatIndex].members, memberCount: chats.value[chatIndex].memberCount })
            // Update activeChat if same
            if (activeChat.value?.id === groupId) {
                activeChat.value = { ...chats.value[chatIndex] }
            }
        }
    }

    // Transfer group ownership
    const transferGroupOwnership = (groupId, oldOwnerId, newOwnerId) => {
        const chatIndex = chats.value.findIndex(c => c.id === groupId)
        if (chatIndex !== -1 && chats.value[chatIndex].members) {
            // Update old owner to admin
            const oldOwner = chats.value[chatIndex].members.find(m => m.id === oldOwnerId)
            if (oldOwner) {
                oldOwner.role = 'admin'
                oldOwner.isAdmin = true
            }
            // Update new owner
            const newOwner = chats.value[chatIndex].members.find(m => m.id === newOwnerId)
            if (newOwner) {
                newOwner.role = 'owner'
                newOwner.isAdmin = true
            }
            updateChat(groupId, { members: chats.value[chatIndex].members, memberCount: chats.value[chatIndex].memberCount })
            // Update activeChat if same
            if (activeChat.value?.id === groupId) {
                activeChat.value = { ...chats.value[chatIndex] }
            }
        }
    }

    /**
     * Merge delta-synced chats into the store.
     * Updates existing chats or inserts new ones.
     */
    const mergeChats = (list, currentUserId = userStore.currentUser?.id, token, identityToken) => {
        if (!sameId(currentUserId, userStore.currentUser?.id) || (token && !ledger.valid(token))) return
        const contacts = useContactStore()
        for (const dto of list || []) {
            if (hiddenChats.value.has(dto.id)) continue
            const state = ledger.merge(`chat:${dto.id}`, membership.get(String(dto.id)) || {}, { present: true }, token)
            if (!state?.present) continue
            membership.set(String(dto.id), state)
            contacts.ingestUserSnapshots(dto.members || [], identityToken)
            const idx = chats.value.findIndex(c => sameId(c.id, dto.id))
            const existing = idx < 0 ? {} : chats.value[idx]
            const input = { ...existing, ...dto }
            if ('avatarUrl' in dto && !('avatar' in dto)) delete input.avatar
            const normalized = normalizeChatFromServer(input, currentUserId)
            const merged = ledger.merge(dto.id, existing, normalized, token)
            if (!merged) continue
            if (merged.type === 'GROUP') { delete merged.online; delete merged.status; delete merged.isOnline }
            if (idx < 0) chats.value.push(merged)
            else chats.value[idx] = merged
            for (const member of merged.members || []) {
                const record = contacts.getUserIdentity(member.id)
                if (record) applyUserIdentity(member.id, record)
            }
            if (sameId(activeChat.value?.id, merged.id)) activeChat.value = chats.value.find(c => sameId(c.id, merged.id))
        }
        offlineStore.saveChats(chats.value.map(c => ({ ...c, _socialOwner: currentUserId }))).catch(e => console.warn('[ChatStore] Cache write failed', e))
    }
    const loadFromCache = async () => {
        const token = captureChatRevision(), identityToken = useContactStore().captureIdentityRevision()
        const cached = await offlineStore.getChats()
        if (!ledger.valid(token)) return []
        const safe = cached.filter(c => sameId(c._socialOwner, token.owner)).map(c => ({ ...c, online: null, status: null, members: c.members?.map(m => ({ ...m, isOnline: null })) }))
        mergeChats(safe, token.owner, token, identityToken)
        return safe
    }

    // Reset store - clear all data (used on logout)
    const resetStore = () => {
        ledger.setOwner(null)
        activeChat.value = null
        chats.value = []
        messages.value = []
        subscribedChatIds.value = new Set()
        // hiddenChats is per-account state; clear so the next user does not
        // inherit it. (mutedChats / pinnedChats follow the same pattern via
        // localStorage scoping handled at logout elsewhere if needed.)
        hiddenChats.value = new Map()
        localStorage.removeItem('hiddenChats')
    }

    // Clear subscribed chats tracking (used on reconnect)
    const clearSubscribedChats = () => {
        subscribedChatIds.value = new Set()
    }

    return {
        captureChatRevision, applyUserIdentity, invalidatePresence,
        activeChat,
        chats,
        messages,
        isLoading,
        loadError,
        subscribedChatIds,
        mutedChats,
        pinnedChats,
        hiddenChats,
        hiddenGroupCount,
        groupChats,
        directChats,
        totalUnreadCount,
        selectedChatId,
        sortedChats,
        fetchChats,
        retryFetchChats,
        setActiveChat,
        clearActiveChat,
        toggleActiveChat,
        sendMessage,
        createGroup,
        createDirectChat,
        updateGroupInfo,
        leaveGroup,
        deleteChat,
        removeChat,
        handleChatDisabled,
        getMessagesForChat,
        updateChat,
        incrementUnreadCount,
        markChatSubscribed,
        isChatSubscribed,
        updateMemberOnlineStatus,
        addGroupMember,
        removeGroupMember,
        updateGroupMemberRole,
        transferGroupOwnership,
        toggleMuteChat,
        isChatMuted,
        togglePinChat,
        isChatPinned,
        isChatHidden,
        unhideChat,
        clearSubscribedChats,
        mergeChats,
        loadFromCache,
        resetStore
    }
})

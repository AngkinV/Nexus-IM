import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { contactAPI, userAPI, resolveFileUrl } from '@/services/api'
import offlineStore from '@/services/offlineStore'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { normalizeUser, sameId, createRevisionLedger } from '@/utils/socialData.mjs'

export const useContactStore = defineStore('contact', () => {
    const contacts = ref([])
    const onlineUsers = ref(new Set())
    const pendingRequests = ref([])  // 收到的待处理好友申请
    const sentRequests = ref([])     // 发出的待处理好友申请
    const pendingRequestCount = ref(0)
    const isLoading = ref(false)

    // Computed: Online contacts
    const onlineContacts = computed(() => {
        return contacts.value.filter(c => c.isOnline === true)
    })

    // Computed: Offline contacts
    const offlineContacts = computed(() => {
        return contacts.value.filter(c => c.isOnline === false)
    })

    // Computed: Sorted contacts (online first, then alphabetically)
    const sortedContacts = computed(() => {
        return [...contacts.value].sort((a, b) => {
            const rank = value => value === true ? 0 : value === false ? 1 : 2
            const statusOrder = rank(a.isOnline) - rank(b.isOnline)
            if (statusOrder !== 0) return statusOrder
            return String(a.nickname || a.username || '').localeCompare(String(b.nickname || b.username || ''))
        })
    })

    const identities = ref({})
    const ledger = createRevisionLedger()
    const membership = new Map()
    const userStore = useUserStore()
    watch(() => userStore.currentUser?.id, owner => {
        if (ledger.setOwner(owner)) {
            isLoading.value = false; membership.clear(); contacts.value = []; identities.value = {}; onlineUsers.value = new Set()
            pendingRequests.value = []; sentRequests.value = []; pendingRequestCount.value = 0
        }
    }, { immediate: true, flush: 'sync' })
    function captureIdentityRevision() { return ledger.capture() }
    function isIdentityRevisionCurrent(token) { return ledger.valid(token) }
    function getUserIdentity(userId) { return identities.value[String(userId)] || null }
    function ingestUserSnapshots(users, revisionToken) {
        if (captureIdentityRevision().owner == null || (revisionToken && !ledger.valid(revisionToken))) return false
        for (const dto of users || []) {
            const patch = normalizeUser(dto, resolveFileUrl)
            if (!patch) continue
            // Roles/relationship metadata belong to their source, not global identity.
            const identityPatch = { ...patch }
            for (const k of ['role', 'isAdmin', 'relationId', 'userId']) delete identityPatch[k]
            const key = String(patch.id)
            const record = ledger.merge(key, identities.value[key] || { id: patch.id, avatar: undefined, avatarUrl: undefined, nickname: undefined, lastSeen: undefined, isOnline: null }, identityPatch, revisionToken)
            if (!record) continue
            if (identities.value[key]) Object.assign(identities.value[key], record)
            else identities.value[key] = record
            const contact = contacts.value.find(c => sameId(c.id, patch.id))
            if (contact) Object.assign(contact, record)
            useChatStore().applyUserIdentity(patch.id, record)
        }
        onlineUsers.value = new Set(Object.values(identities.value).filter(u => u.isOnline === true).map(u => u.id))
        return true
    }
    function updateUserPresence(userId, payload) {
        return ingestUserSnapshots([{ ...payload, id: userId, userId: undefined }])
    }
    function invalidatePresence() {
        for (const record of Object.values(identities.value)) updateUserPresence(record.id, { isOnline: null })
        useChatStore().invalidatePresence()
    }
    function setContacts(list, token) {
        if (token && !ledger.valid(token)) return
        mergeContacts(list, token)
    }
    function addContact(dto, token) {
        if (token && !ledger.valid(token)) return
        const patch = normalizeUser(dto, resolveFileUrl)
        if (!patch) return
        const state = ledger.merge(`contact:${patch.id}`, membership.get(String(patch.id)) || {}, { present: true }, token)
        if (!state?.present) return
        membership.set(String(patch.id), state)
        ingestUserSnapshots([dto], token)
        const existing = contacts.value.find(c => sameId(c.id, patch.id))
        const merged = { ...existing, ...patch, ...getUserIdentity(patch.id) }
        if (existing) Object.assign(existing, merged)
        else contacts.value.push(merged)
    }
    function removeContact(id) { membership.set(String(id), ledger.merge(`contact:${id}`, {}, { present: false })); contacts.value = contacts.value.filter(c => !sameId(c.id, id)) }
    function removeContactRelation(id) { for (const c of [...contacts.value]) if (sameId(c.relationId, id)) removeContact(c.id) }
    function updateContactStatus(id, isOnline) { updateUserPresence(id, { isOnline }) }
    function updateContactInfo(id, patch) { updateUserPresence(id, patch) }
    function isUserOnline(id) { return getUserIdentity(id)?.isOnline ?? null }
    function isContact(id) { return contacts.value.some(c => sameId(c.id, id)) }
    async function fetchContacts(userId) {
        const token = captureIdentityRevision()
        if (!sameId(token.owner, userId)) return
        isLoading.value = true
        try {
            const response = await contactAPI.getContacts(userId)
            if (!ledger.valid(token)) return
            const list = response.data || []
            for (const c of [...contacts.value]) {
                if (!list.some(dto => sameId(dto.userId ?? dto.id, c.id))) {
                    const state = ledger.merge(`contact:${c.id}`, membership.get(String(c.id)) || {}, { present: false }, token)
                    membership.set(String(c.id), state)
                    if (!state.present) contacts.value = contacts.value.filter(x => !sameId(x.id, c.id))
                }
            }
            mergeContacts(list, token)
        } finally { if (ledger.valid(token)) isLoading.value = false }
    }

    async function searchUsers(query) {
        const token = captureIdentityRevision()
        const response = await userAPI.searchUsers(query)
        if (!ledger.valid(token)) return []
        // Search does not grant a friendship or presence observation subscription.
        return (response.data || []).map(dto => normalizeUser(dto, resolveFileUrl)).filter(Boolean)
    }

    /**
     * 添加联系人 - 会根据目标用户的隐私设置决定是直接添加还是发送申请
     * @returns { type: 'direct' | 'request', data: ContactDTO | ContactRequestDTO }
     */
    async function addContactRequest(userId, contactUserId, message = null) {
        const token = captureIdentityRevision()
        if (!sameId(token.owner, userId)) return
        try {
            const response = await contactAPI.addContact(userId, contactUserId, message)
            if (!ledger.valid(token)) return
            const result = response.data

            if (result.type === 'direct') {
                // 直接添加成功
                const contactData = result.data
                addContact(contactData, token)
            } else if (result.type === 'request') {
                // 发送了好友申请
                sentRequests.value.unshift(result.data)
            }

            return result
        } catch (error) {
            console.error('Failed to add contact:', error)
            throw error
        }
    }

    async function removeContactRequest(userId, contactUserId) {
        const token = captureIdentityRevision()
        if (!sameId(token.owner, userId)) return
        try {
            await contactAPI.removeContact(userId, contactUserId)
            if (!ledger.valid(token)) return
            removeContact(contactUserId)
        } catch (error) {
            console.error('Failed to remove contact:', error)
            throw error
        }
    }

    function getContactById(contactId) {
        return contacts.value.find(c => sameId(c.id, contactId))
    }

    // ==================== 好友申请相关方法 ====================

    /**
     * 获取待处理的好友申请（收到的）
     */
    async function fetchPendingRequests(userId) {
        const token = captureIdentityRevision()
        if (!sameId(token.owner, userId)) return
        try {
            const response = await contactAPI.getPendingRequests(userId)
            if (!ledger.valid(token)) return
            pendingRequests.value = response.data || []
            pendingRequestCount.value = pendingRequests.value.length
        } catch (error) {
            console.error('Failed to fetch pending requests:', error)
            throw error
        }
    }

    /**
     * 获取已发送的好友申请
     */
    async function fetchSentRequests(userId) {
        const token = captureIdentityRevision()
        if (!sameId(token.owner, userId)) return
        try {
            const response = await contactAPI.getSentRequests(userId)
            if (!ledger.valid(token)) return
            sentRequests.value = response.data || []
        } catch (error) {
            console.error('Failed to fetch sent requests:', error)
            throw error
        }
    }

    /**
     * 获取待处理申请数量
     */
    async function fetchPendingRequestCount(userId) {
        const token = captureIdentityRevision()
        if (!sameId(token.owner, userId)) return
        try {
            const response = await contactAPI.getPendingRequestCount(userId)
            if (!ledger.valid(token)) return
            pendingRequestCount.value = response.data?.count || 0
        } catch (error) {
            console.error('Failed to fetch pending request count:', error)
        }
    }

    /**
     * 接受好友申请
     */
    async function acceptRequest(requestId, userId) {
        const token = captureIdentityRevision()
        if (!sameId(token.owner, userId)) return
        try {
            const response = await contactAPI.acceptRequest(requestId, userId)
            if (!ledger.valid(token)) return
            // 添加到联系人列表
            const contactData = response.data
            addContact(contactData, token)
            // 从待处理列表中移除
            pendingRequests.value = pendingRequests.value.filter(r => r.id !== requestId)
            pendingRequestCount.value = Math.max(0, pendingRequestCount.value - 1)
            return response.data
        } catch (error) {
            console.error('Failed to accept request:', error)
            throw error
        }
    }

    /**
     * 拒绝好友申请
     */
    async function rejectRequest(requestId, userId) {
        const token = captureIdentityRevision()
        if (!sameId(token.owner, userId)) return
        try {
            await contactAPI.rejectRequest(requestId, userId)
            if (!ledger.valid(token)) return
            // 从待处理列表中移除
            pendingRequests.value = pendingRequests.value.filter(r => r.id !== requestId)
            pendingRequestCount.value = Math.max(0, pendingRequestCount.value - 1)
        } catch (error) {
            console.error('Failed to reject request:', error)
            throw error
        }
    }

    /**
     * 处理 WebSocket 收到新的好友申请
     */
    function handleNewRequest(requestData) {
        // 检查是否已存在
        const exists = pendingRequests.value.some(r => r.id === requestData.id)
        if (!exists) {
            pendingRequests.value.unshift(requestData)
            pendingRequestCount.value++
        }
    }

    /**
     * 处理 WebSocket 好友申请被接受
     */
    function handleRequestAccepted(contactData) {
        // 添加到联系人列表
        addContact(contactData)
        // 从已发送列表中移除相关申请
        sentRequests.value = sentRequests.value.filter(
            r => r.toUserId !== contactData.userId
        )
    }

    /**
     * 处理 WebSocket 好友申请被拒绝
     */
    function handleRequestRejected(data) {
        // 从已发送列表中移除
        sentRequests.value = sentRequests.value.filter(
            r => r.id !== data.requestId
        )
    }

    /**
     * Merge delta-synced contacts into the store.
     * Updates existing contacts or inserts new ones.
     */
    function mergeContacts(list, token) {
        if (token && !ledger.valid(token)) return
        for (const dto of list || []) addContact(dto, token)
        const owner = captureIdentityRevision().owner
        offlineStore.saveContacts(contacts.value.map(c => ({ ...c, _socialOwner: owner }))).catch(e => console.warn('[ContactStore] Cache write failed', e))
    }
    async function loadFromCache() {
        const token = captureIdentityRevision()
        const cached = await offlineStore.getContacts()
        if (!ledger.valid(token)) return []
        const safe = cached.filter(c => sameId(c._socialOwner, token.owner)).map(c => ({ ...c, isOnline: null }))
        for (const dto of safe) addContact(dto, token)
        return safe
    }

    return {
        identities, captureIdentityRevision, isIdentityRevisionCurrent, ingestUserSnapshots, getUserIdentity, updateUserPresence, invalidatePresence, removeContactRelation,
        contacts,
        onlineUsers,
        pendingRequests,
        sentRequests,
        pendingRequestCount,
        isLoading,
        onlineContacts,
        offlineContacts,
        sortedContacts,
        setContacts,
        addContact,
        removeContact,
        updateContactStatus,
        updateContactInfo,
        isUserOnline,
        isContact,
        fetchContacts,
        searchUsers,
        addContactRequest,
        removeContactRequest,
        getContactById,
        // 好友申请相关
        fetchPendingRequests,
        fetchSentRequests,
        fetchPendingRequestCount,
        acceptRequest,
        rejectRequest,
        handleNewRequest,
        handleRequestAccepted,
        handleRequestRejected,
        mergeContacts,
        loadFromCache
    }
})

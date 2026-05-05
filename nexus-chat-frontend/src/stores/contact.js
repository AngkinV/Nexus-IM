import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { contactAPI, userAPI, resolveFileUrl } from '@/services/api'
import offlineStore from '@/services/offlineStore'

export const useContactStore = defineStore('contact', () => {
    const contacts = ref([])
    const onlineUsers = ref(new Set())
    const pendingRequests = ref([])  // 收到的待处理好友申请
    const sentRequests = ref([])     // 发出的待处理好友申请
    const pendingRequestCount = ref(0)
    const isLoading = ref(false)

    // Computed: Online contacts
    const onlineContacts = computed(() => {
        return contacts.value.filter(c => c.isOnline)
    })

    // Computed: Offline contacts
    const offlineContacts = computed(() => {
        return contacts.value.filter(c => !c.isOnline)
    })

    // Computed: Sorted contacts (online first, then alphabetically)
    const sortedContacts = computed(() => {
        return [...contacts.value].sort((a, b) => {
            if (a.isOnline !== b.isOnline) {
                return a.isOnline ? -1 : 1
            }
            return a.nickname.localeCompare(b.nickname)
        })
    })

    function setContacts(contactList) {
        contacts.value = contactList
    }

    function addContact(contact) {
        const exists = contacts.value.some(c => c.id === contact.id)
        if (!exists) {
            contacts.value.push({
                ...contact,
                addedAt: new Date().toISOString()
            })
        }
    }

    function removeContact(contactId) {
        contacts.value = contacts.value.filter(c => c.id !== contactId)
    }

    function updateContactStatus(userId, isOnline) {
        if (isOnline) {
            onlineUsers.value.add(userId)
        } else {
            onlineUsers.value.delete(userId)
        }

        // Update contact in list
        const contact = contacts.value.find(c => c.id === userId)
        if (contact) {
            contact.isOnline = isOnline
            if (!isOnline) {
                contact.lastSeen = new Date().toISOString()
            }
        }
    }

    function updateContactInfo(contactId, updates) {
        const contact = contacts.value.find(c => c.id === contactId)
        if (contact) {
            Object.assign(contact, updates)
        }
    }

    function isUserOnline(userId) {
        return onlineUsers.value.has(userId)
    }

    function isContact(userId) {
        return contacts.value.some(c => c.id === userId)
    }

    async function fetchContacts(userId) {
        isLoading.value = true
        try {
            const response = await contactAPI.getContacts(userId)
            // Map backend avatarUrl to frontend avatar field
            contacts.value = (response.data || []).map(contact => ({
                ...contact,
                avatar: resolveFileUrl(contact.avatarUrl || contact.avatar)
            }))
            // Persist to IndexedDB
            offlineStore.saveContacts(contacts.value).catch(() => {})
        } catch (error) {
            console.error('Failed to fetch contacts:', error)
            throw error
        } finally {
            isLoading.value = false
        }
    }

    async function searchUsers(query) {
        try {
            // In real app, implement search API
            // const response = await userAPI.searchUsers(query)
            // return response.data
            return []
        } catch (error) {
            console.error('Failed to search users:', error)
            throw error
        }
    }

    /**
     * 添加联系人 - 会根据目标用户的隐私设置决定是直接添加还是发送申请
     * @returns { type: 'direct' | 'request', data: ContactDTO | ContactRequestDTO }
     */
    async function addContactRequest(userId, contactUserId, message = null) {
        try {
            const response = await contactAPI.addContact(userId, contactUserId, message)
            const result = response.data

            if (result.type === 'direct') {
                // 直接添加成功
                const contactData = result.data
                addContact({
                    id: contactData.userId,
                    username: contactData.username,
                    nickname: contactData.nickname,
                    avatarUrl: resolveFileUrl(contactData.avatarUrl),
                    avatar: resolveFileUrl(contactData.avatarUrl),
                    isOnline: contactData.isOnline,
                    lastSeen: contactData.lastSeen,
                    email: contactData.email,
                    phone: contactData.phone
                })
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
        try {
            await contactAPI.removeContact(userId, contactUserId)
            removeContact(contactUserId)
        } catch (error) {
            console.error('Failed to remove contact:', error)
            throw error
        }
    }

    function getContactById(contactId) {
        return contacts.value.find(c => c.id === contactId)
    }

    // ==================== 好友申请相关方法 ====================

    /**
     * 获取待处理的好友申请（收到的）
     */
    async function fetchPendingRequests(userId) {
        try {
            const response = await contactAPI.getPendingRequests(userId)
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
        try {
            const response = await contactAPI.getSentRequests(userId)
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
        try {
            const response = await contactAPI.getPendingRequestCount(userId)
            pendingRequestCount.value = response.data?.count || 0
        } catch (error) {
            console.error('Failed to fetch pending request count:', error)
        }
    }

    /**
     * 接受好友申请
     */
    async function acceptRequest(requestId, userId) {
        try {
            const response = await contactAPI.acceptRequest(requestId, userId)
            // 添加到联系人列表
            const contactData = response.data
            addContact({
                id: contactData.userId,
                username: contactData.username,
                nickname: contactData.nickname,
                avatarUrl: resolveFileUrl(contactData.avatarUrl),
                avatar: resolveFileUrl(contactData.avatarUrl),
                isOnline: contactData.isOnline,
                lastSeen: contactData.lastSeen,
                email: contactData.email,
                phone: contactData.phone
            })
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
        try {
            await contactAPI.rejectRequest(requestId, userId)
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
        addContact({
            id: contactData.userId,
            username: contactData.username,
            nickname: contactData.nickname,
            avatarUrl: resolveFileUrl(contactData.avatarUrl),
            avatar: resolveFileUrl(contactData.avatarUrl),
            isOnline: contactData.isOnline,
            lastSeen: contactData.lastSeen,
            email: contactData.email,
            phone: contactData.phone
        })
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
    function mergeContacts(deltaContactList) {
        for (const deltaContact of deltaContactList) {
            const idx = contacts.value.findIndex(c => c.id === deltaContact.id)
            if (idx !== -1) {
                contacts.value[idx] = { ...contacts.value[idx], ...deltaContact }
            } else {
                contacts.value.push(deltaContact)
            }
        }
        offlineStore.saveContacts(deltaContactList).catch(() => {})
    }

    /**
     * Load contacts from IndexedDB cache (offline-first).
     */
    async function loadFromCache() {
        try {
            const cached = await offlineStore.getContacts()
            if (cached.length > 0) {
                contacts.value = cached
            }
            return cached
        } catch (e) {
            console.error('[ContactStore] Failed to load from cache:', e)
            return []
        }
    }

    return {
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

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { companionAPI } from '@/services/api'

export const useCompanionStore = defineStore('companion', () => {
    const roles = ref([])
    const activeRoleId = ref(null)
    const messages = ref([])
    const memories = ref([])
    const growth = ref(null)
    const status = ref(null)
    const modelStatus = ref(null)
    const isFallback = ref(false)
    const isLoading = ref(false)

    const activeRole = computed(() => {
        return roles.value.find(r => r.id === activeRoleId.value) || roles.value[0] || null
    })

    const getLocalModelOverrides = () => {
        try {
            return JSON.parse(localStorage.getItem('companionModelOverrides') || '{}')
        } catch (e) {
            return {}
        }
    }

    const applyLocalOverrides = (list) => {
        const overrides = getLocalModelOverrides()
        if (!Array.isArray(list) || !Object.keys(overrides).length) return list

        // Purge stale asset:// overrides (legacy IndexedDB references)
        let dirty = false
        for (const key of Object.keys(overrides)) {
            if (overrides[key]?.modelUrl?.startsWith('asset://')) {
                delete overrides[key]
                dirty = true
            }
        }
        if (dirty) {
            localStorage.setItem('companionModelOverrides', JSON.stringify(overrides))
        }
        if (!Object.keys(overrides).length) return list

        return list.map(role => {
            const local = overrides[role.id]
            return local ? { ...role, ...local } : role
        })
    }

    const ensureInitialized = async (userId) => {
        if (!userId) return
        if (roles.value.length === 0) {
            await initRoles(userId)
        } else {
            await fetchRoles(userId)
        }
        if (!activeRoleId.value && roles.value.length > 0) {
            activeRoleId.value = roles.value[0].id
        }
        if (activeRoleId.value) {
            await loadRoleData(userId, activeRoleId.value)
        }
    }

    const initRoles = async (userId) => {
        isLoading.value = true
        try {
            const response = await companionAPI.initRoles(userId)
            roles.value = applyLocalOverrides(response.data || [])
        } catch (error) {
            console.error('Init companion roles failed:', error)
        } finally {
            isLoading.value = false
        }
    }

    const fetchRoles = async (userId) => {
        isLoading.value = true
        try {
            const response = await companionAPI.getRoles(userId)
            roles.value = applyLocalOverrides(response.data || [])
        } catch (error) {
            console.error('Fetch companion roles failed:', error)
        } finally {
            isLoading.value = false
        }
    }

    const setActiveRole = async (userId, roleId) => {
        activeRoleId.value = roleId
        await loadRoleData(userId, roleId)
    }

    const updateRole = async (userId, roleId, data) => {
        const response = await companionAPI.updateRole(roleId, userId, data)
        const updated = response.data
        const idx = roles.value.findIndex(r => r.id === roleId)
        if (idx >= 0 && updated) {
            const overrides = getLocalModelOverrides()
            const local = overrides[roleId]
            roles.value[idx] = local ? { ...updated, ...local } : updated
        }
        return updated
    }

    const loadRoleData = async (userId, roleId) => {
        if (!roleId) return
        await Promise.all([
            fetchConversation(userId, roleId),
            fetchMemories(userId, roleId),
            fetchGrowth(userId, roleId),
            fetchStatus(userId, roleId),
            fetchModelStatus(userId)
        ])
    }

    const fetchConversation = async (userId, roleId) => {
        try {
            const response = await companionAPI.getConversation(roleId, userId)
            messages.value = response.data || []
        } catch (error) {
            console.error('Fetch companion conversation failed:', error)
        }
    }

    const sendMessage = async (userId, roleId, content) => {
        if (!content || !roleId) return null
        const tempUserMsg = {
            id: `local-${Date.now()}`,
            roleId,
            senderType: 'user',
            content,
            fallback: false,
            createdAt: new Date().toISOString()
        }
        messages.value.push(tempUserMsg)

        try {
            const response = await companionAPI.sendMessage(roleId, userId, content)
            const data = response.data
            if (data?.roleMessage) {
                messages.value = messages.value.filter(m => m.id !== tempUserMsg.id)
                messages.value.push(data.userMessage)
                messages.value.push(data.roleMessage)
                isFallback.value = !!data.fallback
                if (data.status) status.value = data.status
            }
            return data
        } catch (error) {
            console.error('Send companion message failed:', error)
            return null
        }
    }

    const fetchMemories = async (userId, roleId) => {
        try {
            const response = await companionAPI.getMemories(roleId, userId)
            memories.value = response.data || []
        } catch (error) {
            console.error('Fetch memories failed:', error)
        }
    }

    const createMemory = async (userId, roleId, type, content) => {
        const response = await companionAPI.createMemory(userId, { roleId, type, content })
        if (response.data) memories.value.unshift(response.data)
        return response.data
    }

    const confirmMemory = async (userId, id) => {
        const response = await companionAPI.confirmMemory(id, userId)
        const updated = response.data
        if (updated) {
            const idx = memories.value.findIndex(m => m.id === id)
            if (idx >= 0) memories.value[idx] = updated
        }
        return updated
    }

    const deleteMemory = async (userId, id) => {
        await companionAPI.deleteMemory(id, userId)
        memories.value = memories.value.filter(m => m.id !== id)
    }

    const clearMemories = async (userId, roleId) => {
        await companionAPI.clearMemories(roleId, userId)
        memories.value = []
    }

    const fetchGrowth = async (userId, roleId) => {
        try {
            const response = await companionAPI.getGrowth(roleId, userId)
            growth.value = response.data || null
        } catch (error) {
            console.error('Fetch growth failed:', error)
        }
    }

    const fetchStatus = async (userId, roleId) => {
        try {
            const response = await companionAPI.getStatus(roleId, userId)
            status.value = response.data || null
        } catch (error) {
            console.error('Fetch status failed:', error)
        }
    }

    const updateStatus = async (userId, roleId, data) => {
        const response = await companionAPI.updateStatus(roleId, userId, data)
        status.value = response.data
        return response.data
    }

    const saveCredential = async (userId, data) => {
        const response = await companionAPI.saveCredential(userId, data)
        modelStatus.value = response.data
        return response.data
    }

    const fetchModelStatus = async (userId) => {
        try {
            const response = await companionAPI.getCredentialStatus(userId)
            modelStatus.value = response.data
        } catch (error) {
            console.error('Fetch model status failed:', error)
        }
    }

    const bindModel = async (userId, roleId, data) => {
        const response = await companionAPI.bindModel(roleId, userId, data)
        return response.data
    }

    return {
        roles,
        activeRoleId,
        activeRole,
        messages,
        memories,
        growth,
        status,
        modelStatus,
        isFallback,
        isLoading,
        ensureInitialized,
        initRoles,
        fetchRoles,
        setActiveRole,
        updateRole,
        fetchConversation,
        sendMessage,
        fetchMemories,
        createMemory,
        confirmMemory,
        deleteMemory,
        clearMemories,
        fetchGrowth,
        fetchStatus,
        updateStatus,
        saveCredential,
        fetchModelStatus,
        bindModel
    }
})

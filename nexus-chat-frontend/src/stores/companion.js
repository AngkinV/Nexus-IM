import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { companionAPI } from '@/services/api'
import { API_BASE_URL } from '@/services/runtimeConfig'

export const useCompanionStore = defineStore('companion', () => {
    const roles = ref([])
    const activeRoleId = ref(null)
    const messages = ref([])
    const memories = ref([])
    const growth = ref(null)
    const activeSession = ref(null)
    const sessionHistory = ref([])
    const status = ref(null)
    const modelStatus = ref(null)
    const isFallback = ref(false)
    const isLoading = ref(false)

    // Desktop pet: when off, the 3D widget is not mounted at all, so its WebGL
    // context and the loaded VRM release instead of idling in the background.
    // Off is the default — new users, and anyone who never touched the switch,
    // get no model loaded. Only an explicit "true" turns it on, so turning it
    // off and coming back later stays off.
    const PET_VISIBLE_KEY = 'companion.petVisible'
    const petVisible = ref(localStorage.getItem(PET_VISIBLE_KEY) === 'true')

    const setPetVisible = (visible) => {
        petVisible.value = !!visible
        localStorage.setItem(PET_VISIBLE_KEY, String(petVisible.value))
    }

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

    // True while a role's data is being pulled in. The conversation is empty
    // until this finishes, so the view needs it to tell "no messages yet" apart
    // from "still loading" — otherwise the room looks broken for a second.
    const isLoadingRoleData = ref(false)

    const loadRoleData = async (userId, roleId) => {
        if (!roleId) return
        isLoadingRoleData.value = true
        try {
            await Promise.all([
                fetchConversation(userId, roleId),
                fetchMemories(userId, roleId),
                fetchGrowth(userId, roleId),
                fetchStatus(userId, roleId),
                fetchModelStatus(userId)
            ])
        } finally {
            isLoadingRoleData.value = false
        }
    }

    const fetchConversation = async (userId, roleId) => {
        try {
            const response = await companionAPI.getConversation(roleId, userId)
            messages.value = response.data || []
        } catch (error) {
            console.error('Fetch companion conversation failed:', error)
        }
    }

    const sendMessage = async (userId, roleId, content, sceneKey = null) => {
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
            const response = await companionAPI.sendMessage(roleId, userId, content, sceneKey)
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

    /**
     * Streaming send. The reply grows in place while the server writes it, so the
     * tavern reads at the same speed as the AI assistant instead of pausing for
     * several seconds and then dropping a finished paragraph.
     *
     * Resolves to the same shape as `sendMessage`; null means the send failed.
     */
    const sendMessageStream = async (userId, roleId, content, sceneKey = null) => {
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

        // The placeholder the deltas grow into. It has to be the reactive object
        // inside the array — mutating a copy would not repaint.
        const live = {
            id: `live-${Date.now()}`,
            roleId,
            senderType: 'role',
            content: '',
            fallback: false,
            createdAt: new Date().toISOString()
        }
        messages.value.push(live)

        let settled = null
        try {
            const response = await fetch(
                `${API_BASE_URL}/companion/messages/stream?userId=${encodeURIComponent(userId)}`,
                {
                    method: 'POST',
                    headers: buildStreamHeaders(),
                    body: JSON.stringify({ roleId, content, sceneKey })
                }
            )

            if (!response.ok || !response.body) {
                messages.value = messages.value.filter(m => m.id !== live.id && m.id !== tempUserMsg.id)
                return null
            }

            const reader = response.body.getReader()
            const decoder = new TextDecoder('utf-8')
            let buffer = ''

            while (true) {
                const { value, done } = await reader.read()
                if (done) break
                buffer += decoder.decode(value, { stream: true })

                let frameEnd
                while ((frameEnd = buffer.indexOf('\n\n')) !== -1) {
                    const frame = buffer.slice(0, frameEnd)
                    buffer = buffer.slice(frameEnd + 2)
                    const handled = applyStreamFrame(frame, live)
                    if (handled) settled = handled
                }
            }
            if (buffer.trim()) {
                const handled = applyStreamFrame(buffer, live)
                if (handled) settled = handled
            }
        } catch (error) {
            console.error('Stream companion message failed:', error)
            messages.value = messages.value.filter(m => m.id !== live.id && m.id !== tempUserMsg.id)
            return null
        }

        if (!settled?.roleMessage) {
            messages.value = messages.value.filter(m => m.id !== live.id && m.id !== tempUserMsg.id)
            return null
        }

        messages.value = messages.value.filter(m => m.id !== live.id && m.id !== tempUserMsg.id)
        messages.value.push(settled.userMessage)
        messages.value.push(settled.roleMessage)
        isFallback.value = !!settled.fallback
        if (settled.status) status.value = settled.status
        return settled
    }

    const applyStreamFrame = (frame, live) => {
        let event = 'message'
        const dataLines = []
        for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) event = line.slice(6).trim()
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
        }
        if (!dataLines.length) return null
        const raw = dataLines.join('\n')
        let parsed
        try {
            parsed = JSON.parse(raw)
        } catch {
            return null
        }
        if (event === 'delta') {
            live.content += typeof parsed === 'string' ? parsed : ''
            return null
        }
        if (event === 'done') return parsed
        return null
    }

    const buildStreamHeaders = () => {
        const headers = { 'Content-Type': 'application/json', Accept: 'text/event-stream' }
        const token = localStorage.getItem('token')
        if (token) headers.Authorization = `Bearer ${token}`
        return headers
    }

    // ── Tavern sessions ────────────────────────────────────────────────────
    /** The evening in progress, opening one if none. Idempotent on the server. */
    const openSession = async (userId, roleId, sceneKey) => {
        try {
            const response = await companionAPI.openSession(userId, roleId, sceneKey)
            activeSession.value = response.data || null
            return activeSession.value
        } catch (error) {
            console.error('Open tavern session failed:', error)
            return null
        }
    }

    /** Re-read the evening in progress. Used to recover from a timed-out close. */
    const fetchActiveSession = async (userId, roleId) => {
        try {
            const response = await companionAPI.activeSession(userId, roleId)
            activeSession.value = response.status === 204 ? null : (response.data || null)
            return activeSession.value
        } catch (error) {
            return null
        }
    }

    /** Close the evening: last line, plus one sentence kept as memory. */
    const closeSession = async (userId, sessionId) => {
        try {
            const response = await companionAPI.closeSession(sessionId, userId)
            activeSession.value = null
            return response.data || null
        } catch (error) {
            console.error('Close tavern session failed:', error)
            return null
        }
    }

    const fetchSessionHistory = async (userId, roleId) => {
        try {
            const response = await companionAPI.sessionHistory(userId, roleId)
            sessionHistory.value = response.data || []
            return sessionHistory.value
        } catch (error) {
            console.error('Fetch tavern history failed:', error)
            return []
        }
    }

    /** Fill the empty seat with a new guest. One model call when configured. */
    const inviteGuest = async (userId, sceneKey) => {
        try {
            const response = await companionAPI.inviteGuest(userId, sceneKey)
            return response.data || null
        } catch (error) {
            console.error('Invite guest failed:', error)
            return null
        }
    }

    /** Keep a guest: they stop being a passing face. */
    const adoptRole = async (userId, roleId, sessionId = null) => {
        await companionAPI.adoptGuest(roleId, userId, sessionId)
    }

    /** What this role's model channel resolves to. Read-only, no model call. */
    const fetchModelChannel = async (userId, roleId) => {
        try {
            const response = await companionAPI.modelStatus(roleId, userId)
            return response.data || null
        } catch (error) {
            console.error('Model channel lookup failed:', error)
            return null
        }
    }

    /** One tiny round trip through the resolved channel. */
    const testModelChannel = async (userId, roleId) => {
        try {
            const response = await companionAPI.modelTest(roleId, userId)
            return response.data || null
        } catch (error) {
            console.error('Model test failed:', error)
            return null
        }
    }

    /**
     * Her inner monologue, generated only when asked for. Returns
     * { text, generated, reason } — `generated: false` means no model is
     * configured, and the caller should show the figures it already has.
     */
    const fetchInnerVoice = async (userId, roleId) => {
        try {
            const response = await companionAPI.innerVoice(roleId, userId)
            return response.data || null
        } catch (error) {
            console.error('Inner voice failed:', error)
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
        activeSession,
        sessionHistory,
        status,
        modelStatus,
        isFallback,
        isLoading,
        isLoadingRoleData,
        petVisible,
        setPetVisible,
        ensureInitialized,
        initRoles,
        fetchRoles,
        setActiveRole,
        updateRole,
        fetchConversation,
        sendMessage,
        sendMessageStream,
        fetchInnerVoice,
        fetchModelChannel,
        testModelChannel,
        openSession,
        fetchActiveSession,
        closeSession,
        fetchSessionHistory,
        inviteGuest,
        adoptRole,
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

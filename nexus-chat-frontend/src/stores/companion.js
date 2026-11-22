import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { companionAPI } from '@/services/api'
import { API_BASE_URL } from '@/services/runtimeConfig'
import { retryAtOf } from '@/stores/agentProviders'

// Only a positive routing diagnostic for *our* endpoint permits a second POST.
// In particular a model/alias 404, an SSE error, or an ambiguous proxy 405 does not.
function missingStreamRoute(response, body) {
    if (![404, 405].includes(response.status) || response.redirected || body?.modelError || body?.code) return false
    const path = new URL(`${API_BASE_URL}/companion/messages/stream`).pathname
    if (response.url && new URL(response.url).pathname !== path) return false
    if (body?.path !== path || Number(body.status) !== response.status) return false
    const message = body.message || body.detail || ''
    if (response.status === 404) {
        return body.error === 'Not Found' && message.includes(path.slice(1)) &&
            /^(No static resource |No (?:handler|mapping) (?:found )?for )/i.test(message)
    }
    const allow = response.headers.get('Allow')
    return body.error === 'Method Not Allowed' &&
        (/method ['"]?POST['"]? (?:is )?not supported/i.test(message) || (!!allow && !/\bPOST\b/i.test(allow)))
}

function modelFailure(data, headers, status) {
    const error = data?.modelError || (data?.code && data?.summary ? data : null) ||
        (status === 429 ? { code: 'RATE_LIMITED', httpStatus: 429 } : null)
    if (!error) return null
    const header = headers?.get?.('Retry-After') ?? headers?.['retry-after']
    let seconds = error.retryAfterSeconds
    if (seconds == null && header) seconds = /^\d+$/.test(header) ? Number(header) : Math.ceil((Date.parse(header) - Date.now()) / 1000)
    return seconds != null && Number.isFinite(Number(seconds))
        ? { ...error, retryAfterSeconds: Math.max(0, Number(seconds)) } : error
}

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

    // ── What the stage reads ───────────────────────────────────────────────
    // Mood / motion / doing as the model reported them with the reply that is
    // streaming right now. Null between replies; the stored status takes over.
    const liveState = ref(null)
    // True only while reply text is actually arriving.
    const speaking = ref(false)
    // What went into the prompt for the last reply: { sessionId, sceneKey,
    // memoryIds, historyCount, model }. Reported by the server, never assumed.
    const lastContext = ref(null)
    // Which characters have a send in flight. A reply that is still being
    // written for one character must not show as "replying…" under another, nor
    // lock the other's input.
    const sendingRoleIds = ref([])
    const streamAborts = new Map()
    const clearingRoleIds = ref([])
    const closingRoleIds = ref([])
    const openingRoleIds = ref([])
    const historyRevisions = new Map()
    let historyEpoch = 0
    const historyRevision = (roleId) => historyRevisions.get(roleId) || 0
    const invalidateHistory = (roleId) => {
        historyRevisions.set(roleId, historyRevision(roleId) + 1)
        historyEpoch += 1
    }
    const isRoleBusy = (roleId) => sendingRoleIds.value.includes(roleId) ||
        closingRoleIds.value.includes(roleId) || openingRoleIds.value.includes(roleId) ||
        clearingRoleIds.value.includes(roleId)
    const canApplyHistory = (roleId, revision) => isCurrent(roleId) &&
        historyRevision(roleId) === revision && !clearingRoleIds.value.includes(roleId)
    const beginSending = (roleId) => {
        if (!sendingRoleIds.value.includes(roleId)) sendingRoleIds.value = [...sendingRoleIds.value, roleId]
    }
    const endSending = (roleId) => {
        sendingRoleIds.value = sendingRoleIds.value.filter(id => id !== roleId)
    }

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

    const conversationRoles = computed(() => roles.value.filter(r => !r.conversationHidden))
    const activeRole = computed(() => {
        return conversationRoles.value.find(r => r.id === activeRoleId.value) || null
    })

    /** True while the character on screen has a send in flight. */
    const isSending = computed(() => sendingRoleIds.value.includes(activeRoleId.value))

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
        if (!activeRole.value) {
            await setActiveRole(userId, conversationRoles.value[0]?.id || null)
        } else {
            await loadRoleData(userId, activeRoleId.value)
        }
    }

    const initRoles = async (userId) => {
        const epoch = historyEpoch
        isLoading.value = true
        try {
            const response = await companionAPI.initRoles(userId)
            if (epoch !== historyEpoch || clearingRoleIds.value.length || openingRoleIds.value.length) return
            roles.value = applyLocalOverrides(response.data || [])
            if (activeRoleId.value && !activeRole.value) await setActiveRole(userId, null)
        } catch (error) {
            console.error('Init companion roles failed:', error)
        } finally {
            isLoading.value = false
        }
    }

    const fetchRoles = async (userId) => {
        const epoch = historyEpoch
        isLoading.value = true
        try {
            const response = await companionAPI.getRoles(userId)
            if (epoch !== historyEpoch || clearingRoleIds.value.length || openingRoleIds.value.length) return
            roles.value = applyLocalOverrides(response.data || [])
            if (activeRoleId.value && !activeRole.value) await setActiveRole(userId, null)
        } catch (error) {
            console.error('Fetch companion roles failed:', error)
        } finally {
            isLoading.value = false
        }
    }

    const setActiveRole = async (userId, roleId) => {
        if (roleId && !conversationRoles.value.some(r => r.id === roleId)) return
        if (roleId !== activeRoleId.value) {
            // Everything below belongs to the character being left. Clearing it
            // now is what keeps one character's words, state and evening from
            // showing under another's name while the new data loads.
            messages.value = []
            memories.value = []
            growth.value = null
            status.value = null
            activeSession.value = null
            sessionHistory.value = []
            liveState.value = null
            speaking.value = false
            lastContext.value = null
            isFallback.value = false
            isLoadingRoleData.value = false
        }
        activeRoleId.value = roleId
        await loadRoleData(userId, roleId)
    }

    /** A response is only applied if its character is still the one on screen. */
    const isCurrent = (roleId) => activeRoleId.value === roleId

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
                // The evening in progress, if any. Read, never opened: looking
                // at a character must not start an evening with them.
                fetchActiveSession(userId, roleId),
                // Past evenings label the conversation's dividers.
                fetchSessionHistory(userId, roleId)
            ])
        } finally {
            if (isCurrent(roleId)) isLoadingRoleData.value = false
        }
    }

    const fetchConversation = async (userId, roleId) => {
        const revision = historyRevision(roleId)
        try {
            const response = await companionAPI.getConversation(roleId, userId)
            if (!canApplyHistory(roleId, revision)) return
            const stored = response.data || []
            // A half reply left by a model failure is the end of history: tie it to the
            // line it answers, so sending that line again replaces it as a live retry does.
            const half = stored.at(-1), asked = stored.at(-2)
            if (half?.partial === true && half.senderType !== 'user' && asked?.senderType === 'user') {
                stored[stored.length - 2] = { ...asked, partialId: half.id }
            }
            // A send in flight owns its optimistic rows; keep them at the end —
            // except a line the server has already filed, which it now shows
            // itself, and anything that belongs to another character.
            const filed = new Set(stored.map(m => m.clientMsgId).filter(Boolean))
            const pending = messages.value.filter(m =>
                m.pending && m.roleId === roleId && !(m.clientMsgId && filed.has(m.clientMsgId)))
            messages.value = [...stored, ...pending]
        } catch (error) {
            console.error('Fetch companion conversation failed:', error)
        }
    }

    const clearConversation = async (userId, roleId) => {
        if (isRoleBusy(roleId)) {
            const error = new Error('conversation is busy')
            error.response = { status: 409 }
            throw error
        }
        clearingRoleIds.value = [...clearingRoleIds.value, roleId]
        invalidateHistory(roleId)
        let cleared = false
        try {
            const response = await companionAPI.clearConversation(roleId, userId)
            cleared = true
            const seat = roles.value.find(r => r.id === roleId)
            if (seat) {
                seat.lastMessageAt = null
                seat.presence = null
                seat.originSessionId = null
                seat.conversationHidden = true
            }
            if (isCurrent(roleId)) await setActiveRole(userId, null)
            return response.data
        } finally {
            invalidateHistory(roleId)
            clearingRoleIds.value = clearingRoleIds.value.filter(id => id !== roleId)
            if (!cleared && isCurrent(roleId)) await loadRoleData(userId, roleId)
        }
    }

    const restoreConversation = async (userId, roleId) => {
        if (isRoleBusy(roleId)) {
            const error = new Error('conversation is busy')
            error.response = { status: 409 }
            throw error
        }
        openingRoleIds.value = [...openingRoleIds.value, roleId]
        invalidateHistory(roleId)
        try {
            await companionAPI.restoreConversation(roleId, userId)
            const role = roles.value.find(r => r.id === roleId)
            if (role) role.conversationHidden = false
            await setActiveRole(userId, roleId)
        } finally {
            invalidateHistory(roleId)
            openingRoleIds.value = openingRoleIds.value.filter(id => id !== roleId)
        }
    }

    // ── Sending ────────────────────────────────────────────────────────────
    const newClientId = () =>
        (globalThis.crypto?.randomUUID?.() || `c-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`)

    /**
     * The optimistic row for a line the user just sent. It only joins the list
     * on screen when that list belongs to the character being written to — a
     * send for anyone else must not draw its line into this conversation.
     */
    const pushUserRow = (roleId, content, clientMsgId) => {
        const row = {
            id: `local-${clientMsgId}`,
            roleId,
            senderType: 'user',
            content,
            fallback: false,
            createdAt: new Date().toISOString(),
            clientMsgId,
            pending: true,
            failed: false
        }
        if (!isCurrent(roleId)) return row
        messages.value.push(row)
        return messages.value[messages.value.length - 1]
    }

    /**
     * The row a send belongs to. A retry reuses the line already on screen —
     * optimistic or filed — instead of drawing it a second time.
     */
    const userRowFor = (roleId, content, clientMsgId) => {
        const existing = isCurrent(roleId)
            ? messages.value.find(m => m.senderType === 'user' && m.clientMsgId === clientMsgId)
            : null
        if (!existing) return pushUserRow(roleId, content, clientMsgId)
        existing.failed = false
        delete existing.modelError
        delete existing.retryAt
        // A user-initiated retry replaces this interrupted attempt, never its user line.
        if (existing.partialId != null) dropRows(existing.partialId)
        delete existing.partialId
        return existing
    }

    const dropRows = (...ids) => {
        messages.value = messages.value.filter(m => !ids.includes(m.id))
    }

    /** Put the server's version of an exchange in place of the optimistic rows. */
    const settleExchange = (roleId, data, ...optimisticIds) => {
        // The seat list sorts by last exchange and shows what she is doing; both
        // just changed, whichever character is on screen by now.
        const seat = roles.value.find(r => r.id === roleId)
        if (seat && data.roleMessage) {
            seat.conversationHidden = false
            seat.lastMessageAt = data.roleMessage.createdAt
            if (data.status?.summary) seat.presence = data.status.summary
        }
        if (!isCurrent(roleId)) return
        const serverIds = [data.openingMessage?.id, data.userMessage?.id, data.roleMessage?.id].filter(Boolean)
        messages.value = messages.value.filter(m => !optimisticIds.includes(m.id) && !serverIds.includes(m.id))
        if (data.openingMessage) messages.value.push(data.openingMessage)
        if (data.userMessage) messages.value.push(data.userMessage)
        if (data.roleMessage) messages.value.push(data.roleMessage)
        isFallback.value = !!data.fallback
        if (data.status) status.value = data.status
        if (data.sessionId && activeSession.value?.id !== data.sessionId) {
            activeSession.value = {
                id: data.sessionId,
                roleId,
                sceneKey: data.sceneKey || null,
                status: 'active',
                startedAt: data.openingMessage?.createdAt || data.userMessage?.createdAt || new Date().toISOString()
            }
        }
        if (Array.isArray(data.memoryIds)) {
            lastContext.value = { ...(lastContext.value || {}), sessionId: data.sessionId, memoryIds: data.memoryIds }
        }
    }

    /** A send that did not complete: keep the user's line, marked, so it can be retried. */
    const markFailed = (roleId, row, liveId) => {
        if (!isCurrent(roleId)) return
        if (liveId) dropRows(liveId)
        const kept = messages.value.find(m => m.id === row.id)
        if (kept) kept.failed = true
    }

    // A model failure is part of the exchange, not a new chat message or a
    // successful reply. Prefer the persisted partial, retaining delivered deltas
    // if the terminal frame has no roleMessage.
    const settleModelFailure = (roleId, row, live, data, modelError) => {
        const clientMsgId = row.clientMsgId
        const text = data?.roleMessage?.content || live?.content
        const partial = !!data?.partial || !!text
        const roleMessage = text ? {
            ...(data?.roleMessage || live), content: text, live: false, pending: false, partial: true, fallback: false
        } : null
        const userMessage = {
            ...row, ...data?.userMessage, clientMsgId, pending: !data?.userMessage, failed: true, modelError,
            partialId: roleMessage?.id, retryAt: retryAtOf(modelError)
        }
        const result = { ...data, userMessage, roleMessage, fallback: false, modelError, partial, clientMsgId }
        settleExchange(roleId, result, row.id, live?.id)
        return result
    }

    /**
     * Plain (non-streaming) send. Used only when the server has no stream
     * endpoint. Resolves to the exchange, or null when the send failed.
     */
    const sendMessage = async (userId, roleId, content, sceneKey = null, clientMsgId = null) => {
        if (!content || !roleId || isRoleBusy(roleId)) return null
        const clientId = clientMsgId || newClientId()
        const row = userRowFor(roleId, content, clientId)
        beginSending(roleId)
        try {
            const response = await companionAPI.sendMessage(roleId, userId, content, sceneKey, clientId)
            const data = response.data
            const failure = modelFailure(data, response.headers, response.status)
            if (failure) return settleModelFailure(roleId, row, null, data, failure)
            if (!data?.roleMessage) {
                markFailed(roleId, row)
                return null
            }
            settleExchange(roleId, data, row.id)
            return data
        } catch (error) {
            const response = error.response
            const failure = modelFailure(response?.data, response?.headers, response?.status)
            if (failure) return settleModelFailure(roleId, row, null, response?.data, failure)
            markFailed(roleId, row)
            return null
        } finally {
            endSending(roleId)
        }
    }

    /**
     * Streaming send. The reply grows in place while the server writes it.
     *
     * Resolves to one of:
     *   { modelError, partial, clientMsgId, ... }  a terminal model failure
     *   { roleMessage, ... }   the settled exchange
     *   { unsupported: true }  the server has no stream endpoint — and therefore
     *                          did nothing; the only case where the caller may
     *                          send again through the plain endpoint
     *   { stopped: true }      the user stopped the reply
     *   null                   the send failed; the user's line stays, marked,
     *                          and can be retried under the same client id
     *
     * It never falls back by itself. A failed stream used to be re-sent through
     * the plain endpoint even though the server had usually stored the line
     * already, which filed the message twice and called the model twice.
     */
    const sendMessageStream = async (userId, roleId, content, sceneKey = null, clientMsgId = null) => {
        if (!content || !roleId || isRoleBusy(roleId)) return null
        // One reply at a time per character.
        if (streamAborts.has(roleId)) return null
        const clientId = clientMsgId || newClientId()
        const row = userRowFor(roleId, content, clientId)

        // The placeholder the deltas grow into. It has to be the reactive object
        // inside the array — mutating a copy would not repaint.
        let live = {
            id: `live-${clientId}`,
            roleId,
            senderType: 'role',
            content: '',
            fallback: false,
            createdAt: new Date().toISOString(),
            pending: true,
            live: true
        }
        if (isCurrent(roleId)) {
            messages.value.push(live)
            live = messages.value[messages.value.length - 1]
        }

        const abort = new AbortController()
        streamAborts.set(roleId, abort)
        beginSending(roleId)
        if (isCurrent(roleId)) liveState.value = null
        let settled = null
        let serverError = false
        let failure = null
        let responseHeaders

        const handle = (frame) => {
            const event = parseStreamFrame(frame)
            if (!event) return
            // Words for a character who is no longer on screen go nowhere. The
            // server still files the exchange; it shows up when they are opened.
            const here = isCurrent(roleId)
            if (event.name === 'delta') {
                if (typeof event.data === 'string') {
                    live.content += event.data
                    if (here) speaking.value = true
                }
            } else if (event.name === 'state') {
                live.state = event.data
                if (here) liveState.value = event.data
            } else if (event.name === 'context') {
                if (here) {
                    lastContext.value = event.data
                    if (event.data?.sessionId && activeSession.value?.id !== event.data.sessionId) {
                        activeSession.value = {
                            id: event.data.sessionId,
                            roleId,
                            sceneKey: event.data.sceneKey || null,
                            status: 'active',
                            startedAt: new Date().toISOString()
                        }
                    }
                }
            } else if (event.name === 'opening') {
                // Her first line of a new evening goes in above the user's.
                if (here && event.data?.id && !messages.value.some(m => m.id === event.data.id)) {
                    const at = messages.value.findIndex(m => m.id === row.id)
                    messages.value.splice(at < 0 ? messages.value.length : at, 0, event.data)
                }
            } else if (event.name === 'done' || event.name === 'error') {
                settled = event.data
                failure = modelFailure(settled, responseHeaders)
                serverError = event.name === 'error'
            }
        }

        try {
            const response = await fetch(
                `${API_BASE_URL}/companion/messages/stream?userId=${encodeURIComponent(userId)}`,
                {
                    method: 'POST',
                    headers: buildStreamHeaders(),
                    body: JSON.stringify({ roleId, content, sceneKey, clientMsgId: clientId }),
                    signal: abort.signal
                }
            )

            responseHeaders = response.headers
            if (!response.ok || response.headers.get('content-type')?.includes('json')) {
                const body = await response.json().catch(() => null)
                abort.signal.throwIfAborted()
                const error = modelFailure(body, response.headers, response.status)
                if (error) return settleModelFailure(roleId, row, live, body, error)
                if (missingStreamRoute(response, body)) {
                    if (isCurrent(roleId)) dropRows(live.id)
                    return { unsupported: true, clientMsgId: clientId }
                }
                markFailed(roleId, row, live.id)
                return null
            }

            const reader = response.body.getReader()
            const decoder = new TextDecoder('utf-8')
            let buffer = ''
            try {
                while (!settled && !serverError) {
                    const { value, done } = await reader.read()
                    abort.signal.throwIfAborted()
                    if (done) break
                    buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n')
                    let frameEnd
                    while (!settled && !serverError && (frameEnd = buffer.indexOf('\n\n')) !== -1) {
                        handle(buffer.slice(0, frameEnd))
                        buffer = buffer.slice(frameEnd + 2)
                    }
                }
                if (!settled && !serverError && buffer.trim()) handle(buffer)
            } finally {
                await reader.cancel().catch(() => {})
                reader.releaseLock()
            }
        } catch (error) {
            if (abort.signal.aborted) {
                // Stopped by the user. Keep what she had said so far on screen.
                // The server keeps what it had sent when it noticed, which can
                // run a few words past this; the next load shows its version.
                if (isCurrent(roleId)) {
                    if (live.content.trim()) {
                        live.live = false
                        live.pending = false
                        live.stopped = true
                        row.pending = false
                    } else {
                        dropRows(live.id)
                        row.failed = true
                    }
                }
                return { stopped: true, clientMsgId: clientId }
            }
            console.error('Stream companion message failed:', error)
            markFailed(roleId, row, live.id)
            return null
        } finally {
            streamAborts.delete(roleId)
            endSending(roleId)
            // The stage's live readings belong to whoever is on screen; a reply
            // finishing elsewhere must not blank them.
            if (isCurrent(roleId)) {
                speaking.value = false
                liveState.value = null
            }
        }

        if (failure) return settleModelFailure(roleId, row, live, settled, failure)
        if (serverError || !settled?.roleMessage) {
            markFailed(roleId, row, live.id)
            return null
        }
        settleExchange(roleId, settled, row.id, live.id)
        return settled
    }

    /** Stop the reply being written for the character on screen, if there is one. */
    const stopStreaming = () => {
        streamAborts.get(activeRoleId.value)?.abort()
    }

    const parseStreamFrame = (frame) => {
        let name = 'message'
        const dataLines = []
        for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) name = line.slice(6).trim()
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
        }
        if (!dataLines.length) return null
        try {
            return { name, data: JSON.parse(dataLines.join('\n')) }
        } catch {
            return null
        }
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
        if (isRoleBusy(roleId)) return null
        const revision = historyRevision(roleId)
        openingRoleIds.value = [...openingRoleIds.value, roleId]
        try {
            const response = await companionAPI.openSession(userId, roleId, sceneKey)
            if (!canApplyHistory(roleId, revision)) return null
            activeSession.value = response.data || null
            return activeSession.value
        } catch (error) {
            console.error('Open tavern session failed:', error)
            return null
        } finally {
            openingRoleIds.value = openingRoleIds.value.filter(id => id !== roleId)
        }
    }

    /** Re-read the evening in progress. Used to recover from a timed-out close. */
    const fetchActiveSession = async (userId, roleId) => {
        const revision = historyRevision(roleId)
        try {
            const response = await companionAPI.activeSession(userId, roleId)
            const found = response.status === 204 ? null : (response.data || null)
            if (!canApplyHistory(roleId, revision)) return null
            activeSession.value = found
            return found
        } catch (error) {
            return null
        }
    }

    /** Close the evening: last line, plus one sentence kept as memory. */
    const closeSession = async (userId, sessionId) => {
        const roleId = activeSession.value?.roleId || activeRoleId.value
        if (isRoleBusy(roleId)) return null
        const revision = historyRevision(roleId)
        closingRoleIds.value = [...closingRoleIds.value, roleId]
        try {
            const response = await companionAPI.closeSession(sessionId, userId)
            if (canApplyHistory(roleId, revision) && activeSession.value?.id === sessionId) {
                activeSession.value = null
            }
            return response.data || null
        } catch (error) {
            console.error('Close tavern session failed:', error)
            return null
        } finally {
            closingRoleIds.value = closingRoleIds.value.filter(id => id !== roleId)
        }
    }

    const fetchSessionHistory = async (userId, roleId) => {
        const revision = historyRevision(roleId)
        try {
            const response = await companionAPI.sessionHistory(userId, roleId)
            if (!canApplyHistory(roleId, revision)) return []
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
            if (!isCurrent(roleId)) return
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
            if (!isCurrent(roleId)) return
            growth.value = response.data || null
        } catch (error) {
            console.error('Fetch growth failed:', error)
        }
    }

    const fetchStatus = async (userId, roleId) => {
        const revision = historyRevision(roleId)
        try {
            const response = await companionAPI.getStatus(roleId, userId)
            if (!canApplyHistory(roleId, revision)) return
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
        conversationRoles,
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
        isSending,
        speaking,
        liveState,
        lastContext,
        petVisible,
        setPetVisible,
        ensureInitialized,
        initRoles,
        fetchRoles,
        setActiveRole,
        updateRole,
        fetchConversation,
        clearConversation,
        restoreConversation,
        clearingRoleIds,
        isRoleBusy,
        sendMessage,
        sendMessageStream,
        stopStreaming,
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

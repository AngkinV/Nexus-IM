import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { companionAPI } from '@/services/api'
import { useUserStore } from '@/stores/user'
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

/**
 * A role handed back on its own (after an edit), about to replace its list row.
 * A server that predates lastMessageSender answers without the preview line at
 * all, and putting that in place blanked the row until the next list read. Only
 * then — no sender field, no line, the same visible conversation and exchange time —
 * the row keeps the line it already knew, with the sender that came with that
 * same line. An answer carrying its own line is taken whole: its sender is what
 * the server said, or unknown, never borrowed from the row it replaces.
 */
export function withKnownPreview(row, updated) {
    if ('lastMessageSender' in updated || updated.lastMessage || !updated.lastMessageAt || !row.lastMessage ||
        row.id !== updated.id || row.lastMessageAt !== updated.lastMessageAt || updated.conversationHidden) return updated
    return { ...updated, lastMessage: row.lastMessage, lastMessageSender: row.lastMessageSender ?? null }
}

export const useCompanionStore = defineStore('companion', () => {
    const roles = ref([])
    const rolesOwnerId = ref(null)
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
    const isLoadingRoleData = ref(false)
    const userStore = useUserStore()
    const ownerUserId = ref(null)
    const ownerEpoch = ref(0)
    const isInviting = ref(false)
    let rolesReadSequence = 0
    let selectionEpoch = 0
    const readSequences = new Map()
    const previewMessageIds = new Map()
    const pendingTurns = new Map()

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
    // Desktop-pet visibility is owned by the per-account settings store.
    // No second machine-wide persisted value is kept here.

    const conversationRoles = computed(() => roles.value.filter(r => !r.conversationHidden))
    const activeRole = computed(() => {
        return conversationRoles.value.find(r => r.id === activeRoleId.value) || null
    })

    /** True while the character on screen has a send in flight. */
    const isSending = computed(() => sendingRoleIds.value.includes(activeRoleId.value))

    // Server role bindings are the only model source. Legacy local overrides
    // remain inert; no init/refresh/update path merges them into real data.

    const accountKey = (id) => id == null ? null : String(id)

    function resetOwner(id) {
        const next = accountKey(id)
        if (next === ownerUserId.value) return
        ownerEpoch.value += 1
        ownerUserId.value = next
        for (const abort of streamAborts.values()) abort.abort()
        streamAborts.clear()
        pendingTurns.clear()
        previewMessageIds.clear()
        readSequences.clear()
        historyRevisions.clear()
        historyEpoch += 1
        rolesReadSequence += 1
        roles.value = []
        rolesOwnerId.value = null
        activeRoleId.value = null
        messages.value = []
        memories.value = []
        growth.value = null
        status.value = null
        modelStatus.value = null
        activeSession.value = null
        sessionHistory.value = []
        liveState.value = null
        lastContext.value = null
        speaking.value = false
        isFallback.value = false
        isLoading.value = false
        isLoadingRoleData.value = false
        isInviting.value = false
        sendingRoleIds.value = []
        clearingRoleIds.value = []
        closingRoleIds.value = []
        openingRoleIds.value = []
    }

    watch(() => userStore.currentUser?.id, resetOwner, { immediate: true, flush: 'sync' })
    watch(activeRoleId, () => { selectionEpoch += 1 }, { flush: 'sync' })

    const isUserCurrent = (userId) => accountKey(userId) != null &&
        accountKey(userId) === ownerUserId.value &&
        accountKey(userStore.currentUser?.id) === ownerUserId.value
    const captureOwner = (userId) => isUserCurrent(userId)
        ? { userId: accountKey(userId), epoch: ownerEpoch.value } : null
    const owns = (request) => !!request && request.epoch === ownerEpoch.value &&
        isUserCurrent(request.userId)
    const staleError = () => Object.assign(new Error('Account context changed'), { code: 'STALE_USER_CONTEXT' })

    function captureRead(userId, kind, roleId) {
        const owner = captureOwner(userId)
        if (!owner) return null
        const key = `${kind}:${roleId ?? ''}`
        const sequence = (readSequences.get(key) || 0) + 1
        readSequences.set(key, sequence)
        return { ...owner, key, sequence, roleId, revision: historyRevision(roleId), selection: selectionEpoch }
    }

    function ownsRead(request, view = true) {
        return owns(request) && readSequences.get(request.key) === request.sequence &&
            historyRevision(request.roleId) === request.revision &&
            !clearingRoleIds.value.includes(request.roleId) &&
            (!view || (isCurrent(request.roleId) && request.selection === selectionEpoch))
    }

    const persistedId = (message) => {
        const id = String(message?.id ?? '')
        return /^[1-9]\d*$/.test(id) ? BigInt(id) : null
    }
    const newestPersisted = (list) => list.reduce((newest, message) => {
        const id = persistedId(message)
        return id != null && (newest == null || id > persistedId(newest)) ? message : newest
    }, null)

    // The row's preview is the newest filed message that says something — the
    // one the server picks for the list — so a blank reply is passed over for
    // the line before it.
    const latestLine = (list) => newestPersisted(list.filter(m => typeof m?.content === 'string' && m.content.trim()))

    function orderedMessages(list) {
        const before = new Map(), tail = []
        let anchor = null
        // Unfiled user rows stay before the persisted answer to their request;
        // sorting every nonnumeric ID to the tail reverses partial exchanges.
        for (let index = list.length - 1; index >= 0; index -= 1) {
            const message = list[index], id = persistedId(message)
            if (id != null) anchor = String(id)
            else if (anchor == null) tail.unshift(message)
            else {
                if (!before.has(anchor)) before.set(anchor, [])
                before.get(anchor).unshift(message)
            }
        }
        const confirmed = list.filter(m => persistedId(m) != null).sort((a, b) =>
            persistedId(a) < persistedId(b) ? -1 : persistedId(a) > persistedId(b) ? 1 : 0)
        return [...confirmed.flatMap(m => [...(before.get(String(persistedId(m))) || []), m]), ...tail]
    }

    /** Move a row's preview to a filed message picked by {@link latestLine}; never backwards. */
    function applyPreview(roleId, message, empty = false) {
        const seat = roles.value.find(role => role.id === roleId)
        if (!seat) return
        if (!message) {
            if (empty) {
                seat.lastMessage = null
                seat.lastMessageSender = null
                seat.lastMessageAt = null
                previewMessageIds.delete(roleId)
            }
            return
        }
        const id = persistedId(message)
        if (id < (previewMessageIds.get(roleId) || 0n)) return
        previewMessageIds.set(roleId, id)
        // The line and who said it are read off one message so they always
        // belong together. A message with no senderType (an older server)
        // leaves the sender unknown rather than keeping whoever spoke before.
        seat.lastMessage = message.content
        seat.lastMessageSender = message.senderType ?? null
        // A missing server timestamp is not an excuse to substitute the
        // browser clock for persisted history.
        if (message.createdAt && Number.isFinite(new Date(message.createdAt).getTime())) {
            seat.lastMessageAt = message.createdAt
        }
    }

    async function reconcilePreview(userId, roleId, owner = captureOwner(userId)) {
        if (!owns(owner)) return
        const request = captureRead(userId, 'preview', roleId)
        try {
            const response = await companionAPI.getConversation(roleId, userId)
            if (!ownsRead(request, false)) return
            if (!Array.isArray(response.data)) return
            applyPreview(roleId, latestLine(response.data), response.data.length === 0)
        } catch {
            // Keep the last confirmed preview if read-only reconciliation fails.
            // A failed or stopped POST must never be silently retried.
        }
    }

    const ensureInitialized = async (userId) => {
        const owner = captureOwner(userId)
        if (!owner) return
        if (roles.value.length === 0) await initRoles(userId)
        else await fetchRoles(userId)
        if (!owns(owner)) return
        if (!activeRole.value) await setActiveRole(userId, conversationRoles.value[0]?.id || null)
        else await loadRoleData(userId, activeRoleId.value)
    }

    async function readRoles(userId, initialize = false) {
        const owner = captureOwner(userId)
        if (!owner) return false
        const epoch = historyEpoch
        const sequence = ++rolesReadSequence
        isLoading.value = true
        try {
            const response = initialize ? await companionAPI.initRoles(userId) : await companionAPI.getRoles(userId)
            if (!owns(owner) || sequence !== rolesReadSequence || epoch !== historyEpoch ||
                clearingRoleIds.value.length || openingRoleIds.value.length) return false
            if (!Array.isArray(response.data)) throw new Error('Invalid role list response')
            roles.value = response.data
            rolesOwnerId.value = userId
            if (activeRoleId.value && !activeRole.value) await setActiveRole(userId, null)
            return true
        } catch (error) {
            if (owns(owner)) console.error('Fetch companion roles failed:', error)
            return false
        } finally {
            if (owns(owner) && sequence === rolesReadSequence) isLoading.value = false
        }
    }
    const initRoles = (userId) => readRoles(userId, true)
    const fetchRoles = (userId) => readRoles(userId)

    const setActiveRole = async (userId, roleId) => {
        if (!captureOwner(userId)) return
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
        const owner = captureOwner(userId)
        if (!owner) throw staleError()
        const epoch = historyEpoch
        const sequence = (readSequences.get(`update:${roleId}`) || 0) + 1
        readSequences.set(`update:${roleId}`, sequence)
        const response = await companionAPI.updateRole(roleId, userId, data)
        if (!owns(owner)) throw staleError()
        const updated = response.data
        if (!updated || updated.id !== roleId) throw new Error('Invalid role update response')
        const idx = roles.value.findIndex(r => r.id === roleId)
        if (idx >= 0 && sequence === readSequences.get(`update:${roleId}`)) {
            const current = roles.value[idx]
            // The server's answer takes the row's place whole, so its preview is
            // never mixed with the one it replaces.
            const next = withKnownPreview(current, updated)
            // A reply or deletion completed while settings was saving: the
            // slower settings response may update the shape but not rewind chat.
            roles.value[idx] = epoch === historyEpoch ? next : {
                ...next,
                lastMessage: current.lastMessage,
                lastMessageSender: current.lastMessageSender,
                lastMessageAt: current.lastMessageAt,
                conversationHidden: current.conversationHidden,
                presence: current.presence
            }
            return roles.value[idx]
        }
        return updated
    }

    // Called only after the server acknowledges a shared asset rename. Keep
    // every currently cached reference consistent, without changing previews.
    const syncRenamedModel = (userId, oldFileName, newUrl) => {
        if (!isUserCurrent(userId) || !oldFileName || typeof newUrl !== 'string') return
        rolesReadSequence += 1
        isLoading.value = false
        const oldUrl = `/models/${oldFileName}`
        for (const role of roles.value) {
            if (role.modelUrl === oldUrl || role.modelUrl === oldFileName) role.modelUrl = newUrl
        }
    }

    // Only the current selection owns its loading state.
    const loadRoleData = async (userId, roleId) => {
        const owner = captureOwner(userId)
        if (!owner || !roleId) return
        const selection = selectionEpoch
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
            if (owns(owner) && isCurrent(roleId) && selection === selectionEpoch) isLoadingRoleData.value = false
        }
    }

    const fetchConversation = async (userId, roleId) => {
        const request = captureRead(userId, 'conversation', roleId)
        if (!request) return
        try {
            const response = await companionAPI.getConversation(roleId, userId)
            if (!ownsRead(request, false) || !Array.isArray(response.data)) return
            const stored = [...response.data]
            applyPreview(roleId, latestLine(stored), stored.length === 0)
            if (!ownsRead(request)) return
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
            const turn = pendingTurns.get(roleId)
            const inFlight = turn && owns(turn.owner) ? [turn.row, turn.live].filter(Boolean) : []
            const pending = [...messages.value.filter(m => m.pending), ...inFlight]
                .filter((m, index, all) => m.roleId === roleId &&
                    all.findIndex(other => other.id === m.id) === index &&
                    (m.senderType !== 'user' || !(m.clientMsgId && filed.has(m.clientMsgId))))
            messages.value = [...stored, ...pending]
        } catch (error) {
            console.error('Fetch companion conversation failed:', error)
        }
    }

    const clearConversation = async (userId, roleId) => {
        const owner = captureOwner(userId)
        if (!owner) throw staleError()
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
            if (!owns(owner)) throw staleError()
            cleared = true
            const seat = roles.value.find(r => r.id === roleId)
            if (seat) {
                seat.lastMessageAt = null
                // The preview line belongs to the deleted conversation; leaving it
                // behind would show a sentence from a chat that no longer exists.
                seat.lastMessage = null
                seat.lastMessageSender = null
                previewMessageIds.delete(roleId)
                seat.presence = null
                seat.originSessionId = null
                seat.conversationHidden = true
            }
            if (isCurrent(roleId)) await setActiveRole(userId, null)
            return response.data
        } finally {
            if (owns(owner)) {
                invalidateHistory(roleId)
                clearingRoleIds.value = clearingRoleIds.value.filter(id => id !== roleId)
                if (!cleared && isCurrent(roleId)) await loadRoleData(userId, roleId)
            }
        }
    }

    const restoreConversation = async (userId, roleId) => {
        const owner = captureOwner(userId)
        if (!owner) throw staleError()
        if (isRoleBusy(roleId)) {
            const error = new Error('conversation is busy')
            error.response = { status: 409 }
            throw error
        }
        openingRoleIds.value = [...openingRoleIds.value, roleId]
        invalidateHistory(roleId)
        try {
            await companionAPI.restoreConversation(roleId, userId)
            if (!owns(owner)) throw staleError()
            const role = roles.value.find(r => r.id === roleId)
            if (role) role.conversationHidden = false
            await setActiveRole(userId, roleId)
        } finally {
            if (owns(owner)) {
                invalidateHistory(roleId)
                openingRoleIds.value = openingRoleIds.value.filter(id => id !== roleId)
            }
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
        invalidateHistory(roleId)
        // The seat list sorts by last exchange and previews its last line, and
        // the dossier shows what she is doing; all of it just changed, whichever
        // character is on screen by now. Without this the list keeps showing the
        // previous exchange until the roles are re-fetched — the timestamp moves
        // but the sentence under it does not, which reads as stale.
        const latest = latestLine([data.openingMessage, data.userMessage, data.roleMessage])
        applyPreview(roleId, latest)
        const seat = roles.value.find(r => r.id === roleId)
        if (seat && latest) {
            seat.conversationHidden = false
            if (data.status?.summary) seat.presence = data.status.summary
        }
        if (!isCurrent(roleId)) return
        const serverIds = [data.openingMessage?.id, data.userMessage?.id, data.roleMessage?.id].filter(Boolean)
        const merged = messages.value.filter(m => !optimisticIds.includes(m.id) && !serverIds.includes(m.id))
        merged.push(...[data.openingMessage, data.userMessage, data.roleMessage].filter(Boolean))
        // A replay of an earlier client message must not move its old answer to
        // the end of history, or rewind the left-hand conversation preview.
        messages.value = orderedMessages(merged)
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
        // What a failed exchange ended on may not be filed: a half reply that
        // only reached the screen, or a line the server never acknowledged. The
        // row still shows it — the half reply as hers, the unanswered line as
        // the user's and not under the sender of the sentence it replaces,
        // stamped as the server stamps the conversation — until the read-only
        // reconciliation after a failed send puts the filed history in its place.
        const last = [roleMessage, userMessage].find(m => m?.content?.trim())
        const seat = roles.value.find(r => r.id === roleId)
        if (seat && last && persistedId(last) == null) {
            seat.lastMessage = last.content
            seat.lastMessageSender = last.senderType
            seat.lastMessageAt = last.createdAt
        }
        return result
    }

    /**
     * Plain (non-streaming) send. Used only when the server has no stream
     * endpoint. Resolves to the exchange, or null when the send failed.
     */
    const sendMessage = async (userId, roleId, content, sceneKey = null, clientMsgId = null) => {
        const owner = captureOwner(userId)
        if (!owner || !content || !roleId || isRoleBusy(roleId)) return null
        const clientId = clientMsgId || newClientId()
        const row = userRowFor(roleId, content, clientId)
        const turn = { owner, row }
        pendingTurns.set(roleId, turn)
        let needsReconcile = false
        beginSending(roleId)
        try {
            const response = await companionAPI.sendMessage(roleId, userId, content, sceneKey, clientId)
            if (!owns(owner)) return null
            const data = response.data
            const failure = modelFailure(data, response.headers, response.status)
            if (failure) {
                needsReconcile = true
                return settleModelFailure(roleId, row, null, data, failure)
            }
            if (!data?.roleMessage) {
                needsReconcile = true
                markFailed(roleId, row)
                return null
            }
            settleExchange(roleId, data, row.id)
            return data
        } catch (error) {
            if (!owns(owner)) return null
            needsReconcile = true
            const response = error.response
            const failure = modelFailure(response?.data, response?.headers, response?.status)
            if (failure) return settleModelFailure(roleId, row, null, response?.data, failure)
            markFailed(roleId, row)
            return null
        } finally {
            if (pendingTurns.get(roleId) === turn) pendingTurns.delete(roleId)
            if (owns(owner)) {
                endSending(roleId)
                if (needsReconcile) await reconcilePreview(userId, roleId, owner)
            }
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
        const owner = captureOwner(userId)
        if (!owner || !content || !roleId || isRoleBusy(roleId)) return null
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
            live: true,
            clientMsgId: clientId
        }
        if (isCurrent(roleId)) {
            messages.value.push(live)
            live = messages.value[messages.value.length - 1]
        }

        const abort = new AbortController()
        const turn = { owner, row, live }
        pendingTurns.set(roleId, turn)
        let needsReconcile = false
        streamAborts.set(roleId, abort)
        beginSending(roleId)
        if (isCurrent(roleId)) liveState.value = null
        let settled = null
        let serverError = false
        let failure = null
        let responseHeaders

        const handle = (frame) => {
            if (!owns(owner)) return
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

            if (!owns(owner)) return null
            responseHeaders = response.headers
            if (!response.ok || response.headers.get('content-type')?.includes('json')) {
                const body = await response.json().catch(() => null)
                abort.signal.throwIfAborted()
                if (!owns(owner)) return null
                const error = modelFailure(body, response.headers, response.status)
                if (error) {
                    needsReconcile = true
                    return settleModelFailure(roleId, row, live, body, error)
                }
                if (missingStreamRoute(response, body)) {
                    if (isCurrent(roleId)) dropRows(live.id)
                    return { unsupported: true, clientMsgId: clientId }
                }
                needsReconcile = true
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
            if (!owns(owner)) return null
            if (failure) {
                needsReconcile = true
                return settleModelFailure(roleId, row, live, settled, failure)
            }
            if (serverError || !settled?.roleMessage) {
                needsReconcile = true
                markFailed(roleId, row, live.id)
                return null
            }
            settleExchange(roleId, settled, row.id, live.id)
            return settled
        } catch (error) {
            if (!owns(owner)) return null
            needsReconcile = true
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
            if (streamAborts.get(roleId) === abort) streamAborts.delete(roleId)
            if (pendingTurns.get(roleId) === turn) pendingTurns.delete(roleId)
            if (owns(owner)) {
                endSending(roleId)
                if (isCurrent(roleId)) {
                    speaking.value = false
                    liveState.value = null
                }
                if (needsReconcile) await reconcilePreview(userId, roleId, owner)
            }
        }
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
        const request = captureRead(userId, 'activeSession', roleId)
        if (!request || isRoleBusy(roleId)) return null
        openingRoleIds.value = [...openingRoleIds.value, roleId]
        try {
            const response = await companionAPI.openSession(userId, roleId, sceneKey)
            if (!ownsRead(request)) return null
            activeSession.value = response.data || null
            return activeSession.value
        } catch (error) {
            console.error('Open tavern session failed:', error)
            return null
        } finally {
            if (owns(request)) openingRoleIds.value = openingRoleIds.value.filter(id => id !== roleId)
        }
    }

    /** Re-read the evening in progress. Used to recover from a timed-out close. */
    const fetchActiveSession = async (userId, roleId) => {
        const request = captureRead(userId, 'activeSession', roleId)
        if (!request) return null
        try {
            const response = await companionAPI.activeSession(userId, roleId)
            const found = response.status === 204 ? null : (response.data || null)
            if (!ownsRead(request)) return null
            activeSession.value = found
            return found
        } catch (error) {
            return null
        }
    }

    /** Close the evening: last line, plus one sentence kept as memory. */
    const closeSession = async (userId, sessionId) => {
        const roleId = activeSession.value?.roleId || activeRoleId.value
        const request = captureRead(userId, 'activeSession', roleId)
        if (!request || !roleId || isRoleBusy(roleId)) return null
        closingRoleIds.value = [...closingRoleIds.value, roleId]
        try {
            const response = await companionAPI.closeSession(sessionId, userId)
            if (!owns(request)) return null
            if (ownsRead(request) && activeSession.value?.id === sessionId) activeSession.value = null
            return response.data || null
        } catch (error) {
            console.error('Close tavern session failed:', error)
            return null
        } finally {
            if (owns(request)) closingRoleIds.value = closingRoleIds.value.filter(id => id !== roleId)
        }
    }

    const fetchSessionHistory = async (userId, roleId) => {
        const request = captureRead(userId, 'sessions', roleId)
        if (!request) return []
        try {
            const response = await companionAPI.sessionHistory(userId, roleId)
            if (!ownsRead(request)) return []
            sessionHistory.value = response.data || []
            return sessionHistory.value
        } catch (error) {
            console.error('Fetch tavern history failed:', error)
            return []
        }
    }

    /**
     * Fill the empty seat with a new guest. One model call when configured.
     * Pass a modelUrl to choose the 3D model; omit it and the tavern picks one
     * no other character is wearing.
     */
    const inviteGuest = async (userId, sceneKey, modelUrl = null) => {
        const owner = captureOwner(userId)
        if (!owner) throw staleError()
        if (isInviting.value) {
            const error = new Error('Invitation is already in progress')
            error.response = { status: 409 }
            throw error
        }
        isInviting.value = true
        try {
            const response = await companionAPI.inviteGuest(userId, sceneKey, modelUrl)
            if (!owns(owner)) throw staleError()
            const role = response.data
            if (!role || !/^[1-9]\d*$/.test(String(role.id)) || role.kind !== 'guest') {
                throw Object.assign(new Error('Invitation result was not confirmed'), { code: 'INVITE_RESULT_UNKNOWN' })
            }
            // Only this POST's real response identifies the invited character.
            // A failed list refresh must not make us "recover" some other guest.
            invalidateHistory(role.id)
            const index = roles.value.findIndex(r => r.id === role.id)
            if (index >= 0) roles.value[index] = role
            else roles.value.push(role)
            return role
        } finally {
            if (owns(owner)) isInviting.value = false
        }
    }

    /** GET errors are not an empty model library. The caller must show retry. */
    const fetchInviteModels = async (userId) => {
        const owner = captureOwner(userId)
        if (!owner) throw staleError()
        const response = await companionAPI.inviteModels(userId)
        if (!owns(owner)) throw staleError()
        if (!Array.isArray(response.data) || response.data.some(model =>
            !model || typeof model.fileName !== 'string' || typeof model.fileUrl !== 'string')) {
            throw new Error('Invalid model list response')
        }
        return response.data
    }

    /** Keep a guest, without applying another account's late result. */
    const adoptRole = async (userId, roleId, sessionId = null) => {
        const owner = captureOwner(userId)
        if (!owner) throw staleError()
        await companionAPI.adoptGuest(roleId, userId, sessionId)
        if (!owns(owner)) throw staleError()
    }

    async function readRoleResource(userId, roleId, kind, action, apply) {
        const request = captureRead(userId, kind, roleId)
        if (!request) return null
        try {
            const response = await action()
            if (!ownsRead(request)) return null
            if (apply) apply(response.data)
            return response.data ?? null
        } catch (error) {
            if (owns(request)) console.error(`Fetch companion ${kind} failed:`, error)
            return null
        }
    }

    /** These optional model calls are still only made on explicit UI actions. */
    const fetchModelChannel = (userId, roleId) => readRoleResource(userId, roleId, 'modelChannel',
        () => companionAPI.modelStatus(roleId, userId))
    const testModelChannel = (userId, roleId) => readRoleResource(userId, roleId, 'modelTest',
        () => companionAPI.modelTest(roleId, userId))
    const fetchInnerVoice = (userId, roleId) => readRoleResource(userId, roleId, 'innerVoice',
        () => companionAPI.innerVoice(roleId, userId))
    const fetchMemories = (userId, roleId) => readRoleResource(userId, roleId, 'memories',
        () => companionAPI.getMemories(roleId, userId), data => { memories.value = Array.isArray(data) ? data : [] })
    const fetchGrowth = (userId, roleId) => readRoleResource(userId, roleId, 'growth',
        () => companionAPI.getGrowth(roleId, userId), data => { growth.value = data || null })
    const fetchStatus = (userId, roleId) => readRoleResource(userId, roleId, 'status',
        () => companionAPI.getStatus(roleId, userId), data => { status.value = data || null })

    const createMemory = async (userId, roleId, type, content) => {
        const owner = captureOwner(userId), selection = selectionEpoch
        if (!owner) throw staleError()
        const response = await companionAPI.createMemory(userId, { roleId, type, content })
        if (!owns(owner)) throw staleError()
        if (response.data && isCurrent(roleId) && selection === selectionEpoch) memories.value.unshift(response.data)
        return response.data
    }

    const confirmMemory = async (userId, id) => {
        const owner = captureOwner(userId)
        if (!owner) throw staleError()
        const response = await companionAPI.confirmMemory(id, userId)
        if (!owns(owner)) throw staleError()
        const updated = response.data
        if (updated) {
            const idx = memories.value.findIndex(m => m.id === id)
            if (idx >= 0) memories.value[idx] = updated
        }
        return updated
    }

    const deleteMemory = async (userId, id) => {
        const owner = captureOwner(userId)
        if (!owner) throw staleError()
        await companionAPI.deleteMemory(id, userId)
        if (!owns(owner)) throw staleError()
        memories.value = memories.value.filter(m => m.id !== id)
    }

    const clearMemories = async (userId, roleId) => {
        const owner = captureOwner(userId), selection = selectionEpoch
        if (!owner) throw staleError()
        await companionAPI.clearMemories(roleId, userId)
        if (!owns(owner)) throw staleError()
        if (isCurrent(roleId) && selection === selectionEpoch) memories.value = []
    }

    const updateStatus = async (userId, roleId, data) => {
        const owner = captureOwner(userId), selection = selectionEpoch
        if (!owner) throw staleError()
        const response = await companionAPI.updateStatus(roleId, userId, data)
        if (!owns(owner)) throw staleError()
        if (isCurrent(roleId) && selection === selectionEpoch) status.value = response.data
        return response.data
    }

    const saveCredential = async (userId, data) => {
        const owner = captureOwner(userId)
        if (!owner) throw staleError()
        const response = await companionAPI.saveCredential(userId, data)
        if (!owns(owner)) throw staleError()
        modelStatus.value = response.data
        return response.data
    }

    const fetchModelStatus = async (userId) => {
        const request = captureRead(userId, 'credential')
        if (!request) return
        try {
            const response = await companionAPI.getCredentialStatus(userId)
            if (ownsRead(request, false)) modelStatus.value = response.data
        } catch (error) {
            if (owns(request)) console.error('Fetch model status failed:', error)
        }
    }

    const bindModel = async (userId, roleId, data) => {
        const owner = captureOwner(userId)
        if (!owner) throw staleError()
        const response = await companionAPI.bindModel(roleId, userId, data)
        if (!owns(owner)) throw staleError()
        return response.data
    }

    return {
        roles,
        rolesOwnerId,
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
        ownerEpoch,
        isUserCurrent,
        isInviting,
        isFallback,
        isLoading,
        isLoadingRoleData,
        isSending,
        speaking,
        liveState,
        lastContext,
        ensureInitialized,
        initRoles,
        fetchRoles,
        setActiveRole,
        updateRole,
        syncRenamedModel,
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
        fetchInviteModels,
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

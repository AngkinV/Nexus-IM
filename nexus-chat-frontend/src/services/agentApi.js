import axios from 'axios'
import { API_BASE_URL } from './runtimeConfig'
import i18n from '@/locales/i18n'

const apiClient = axios.create({
    baseURL: API_BASE_URL,
    headers: { 'Content-Type': 'application/json' }
})

apiClient.interceptors.request.use(config => {
    const token = localStorage.getItem('token')
    const capturedAuthorization = config.headers?.get?.('Authorization') || config.headers?.Authorization
    if (token && !capturedAuthorization) {
        config.headers.Authorization = `Bearer ${token}`
    }
    return config
})

export const AGENT_OP = Object.freeze({
    ASSISTANT_CHAT: 'ASSISTANT_CHAT',
    CHAT_SUMMARY: 'CHAT_SUMMARY',
    TODO_EXTRACT: 'TODO_EXTRACT',
    REPLY_SUGGEST: 'REPLY_SUGGEST'
})

const sessionPath = sid => `/agent/sessions/${encodeURIComponent(sid)}`
const artifactPath = (sid, id) => `${sessionPath(sid)}/artifacts/${encodeURIComponent(id)}`

/** Axios rejects HTTP errors; a non-OK AgentCommonResponse isn't a success either. */
export function agentData(response) {
    const envelope = response?.data
    if (envelope?.code !== 'OK' || !Object.hasOwn(envelope, 'data')) {
        throw Object.assign(new Error(envelope?.message || 'Invalid agent response'), { code: envelope?.code })
    }
    return envelope.data
}

export function agentOwnerId() {
    try {
        if (!localStorage.getItem('token')) return null
        const id = JSON.parse(localStorage.getItem('user'))?.id
        return (typeof id === 'number' || typeof id === 'string') && String(id).trim() ? String(id) : null
    } catch { return null }
}

function mcpAuth() {
    // Capture identity before Axios's async interceptor; switching accounts cannot retarget a secret write.
    const token = localStorage.getItem('token')
    if (!token || !agentOwnerId()) throw Object.assign(new Error('MCP sign-in required'), { code: 'MCP_SIGN_IN' })
    return { headers: { Authorization: `Bearer ${token}` } }
}

export const agentAPI = {
    scopeResources: () => apiClient.get('/agent/scope/resources'),
    getScope: sid => apiClient.get(`${sessionPath(sid)}/scope`),
    putScope: (sid, expectedBindingVersion, selection) => apiClient.put(`${sessionPath(sid)}/scope`, { expectedBindingVersion, selection }),
    // User-level MCP management: Java BFF only; no session or external connection.
    listMcpServers: () => apiClient.get('/agent/mcp/servers', mcpAuth()),
    createMcpServer: payload => apiClient.post('/agent/mcp/servers', payload, mcpAuth()),
    patchMcpServer: (id, payload) => apiClient.patch(`/agent/mcp/servers/${encodeURIComponent(id)}`, payload, mcpAuth()),
    deleteMcpServer: (id, expectedConfigVersion) => apiClient.delete(`/agent/mcp/servers/${encodeURIComponent(id)}`, { ...mcpAuth(), data: { expectedConfigVersion } }),
    testMcpServer: (id, expectedConfigVersion) => apiClient.post(`/agent/mcp/servers/${encodeURIComponent(id)}/test`, { expectedConfigVersion }, mcpAuth()),
    mcpServerTools: id => apiClient.get(`/agent/mcp/servers/${encodeURIComponent(id)}/tools`, mcpAuth()),
    refreshMcpServerTools: (id, expectedConfigVersion) => apiClient.post(`/agent/mcp/servers/${encodeURIComponent(id)}/tools/refresh`, { expectedConfigVersion }, mcpAuth()),
    mcpServerStatus: id => apiClient.get(`/agent/mcp/servers/${encodeURIComponent(id)}/status`, mcpAuth()),

    /** Create a new Agent session (Mode A: AI 助手会话). */
    createSession: ({ entryMode = AGENT_OP.ASSISTANT_CHAT, title = i18n.global.t('agent.title'), boundChatId = null } = {}) =>
        apiClient.post('/agent/sessions', { entryMode, title, boundChatId }),

    /** List the current user's sessions (history dropdown). */
    listSessions: () => apiClient.get('/agent/sessions'),

    /** Rename a session. */
    renameSession: (sessionId, title) =>
        apiClient.patch(`${sessionPath(sessionId)}`, { title }),

    /** Delete a session (clears short-term memory too). */
    deleteSession: (sessionId) =>
        apiClient.delete(`${sessionPath(sessionId)}`),

    /** Fetch persisted messages of a session (currently from Redis short-term memory). */
    sessionMessages: (sessionId) =>
        apiClient.get(`${sessionPath(sessionId)}/messages`),
    pendingRuns: sid => apiClient.get(`${sessionPath(sid)}/runs/pending`),
    cancelRun: (sid, runId, token) => apiClient.post(`${sessionPath(sid)}/runs/${encodeURIComponent(runId)}/cancel`, {},
        token ? { headers: { Authorization: `Bearer ${token}` } } : undefined),
    listRuns: sid => apiClient.get(`${sessionPath(sid)}/runs`),
    listAttachments: sid => apiClient.get(`${sessionPath(sid)}/attachments`),
    listTasks: sid => apiClient.get(`${sessionPath(sid)}/tasks`),
    uploadAttachment: (sid, file) => {
        const form = new FormData()
        form.append('file', file)
        return apiClient.post(`${sessionPath(sid)}/attachments`, form, { headers: { 'Content-Type': undefined } })
    },
    removeAttachment: (sid, id) => apiClient.delete(`${sessionPath(sid)}/attachments/${encodeURIComponent(id)}`),
    previewPatch: (sid, id, baseAttachmentId) => apiClient.post(`${artifactPath(sid, id)}/patch-preview`, { baseAttachmentId }),
    applyPatch: (sid, id, { baseAttachmentId, baseHash, patchHash }) =>
        apiClient.post(`${artifactPath(sid, id)}/apply`, { baseAttachmentId, baseHash, patchHash, confirmed: true }),
    createTasks: (sid, id) => apiClient.post(`${artifactPath(sid, id)}/tasks`),
    updateTask: (sid, id, status) => apiClient.patch(`${sessionPath(sid)}/tasks/${encodeURIComponent(id)}`, { status }),
    saveArtifact: (sid, id) => apiClient.post(`${artifactPath(sid, id)}/save`),

    /** Non-streaming chat (kept as a fallback when SSE fails). */
    chat: (sessionId, payload) =>
        apiClient.post(`${sessionPath(sessionId)}/chat`, payload),

    /** Clear short-term Redis memory for a session. */
    clearSessionMemory: (sessionId) =>
        apiClient.delete(`${sessionPath(sessionId)}/memory`),

    /** Clear all long-term memory for the current user. */
    resetLongTermMemory: () =>
        apiClient.post('/agent/memory/reset', {}),

    /** Mode B endpoints — exposed but not wired in the first iteration. */
    summarize: (chatId, payload) => apiClient.post(`/agent/chats/${chatId}/summarize`, payload),
    todoExtract: (chatId, payload) => apiClient.post(`/agent/chats/${chatId}/todo-extract`, payload),
    replySuggest: (chatId, payload) => apiClient.post(`/agent/chats/${chatId}/reply-suggest`, payload),
    replyPublish: (chatId, payload) => apiClient.post(`/agent/chats/${chatId}/reply-publish`, payload),

    // ---- Provider management (Step-1 multi-provider feature) ----
    listProviders: (purpose) => apiClient.get('/agent/providers', {
        params: purpose ? { purpose } : undefined
    }),
    listProviderModels: (id) => apiClient.get(`/agent/providers/${encodeURIComponent(id)}/models`),
    upsertProvider: (payload) => apiClient.post('/agent/providers', payload),
    deleteProvider: (id) => apiClient.delete(`/agent/providers/${id}`),
    setDefaultProvider: (id) => apiClient.post(`/agent/providers/${id}/default`),
    testProvider: (id) => apiClient.post(`/agent/providers/${id}/test`)
}

/**
 * Streaming chat over SSE.
 *
 * The browser's native EventSource does not support POST + Authorization headers,
 * so we use fetch + ReadableStream and parse SSE frames manually.
 *
 * Returns an object with `cancel()` to abort the stream and `done` Promise.
 *
 * onEvent({name, data, id}) observes each valid wire frame once. Transport errors go to
 * onTransportError and legacy onError, never to onEvent. onClose also runs for a rejected POST.
 * Legacy handlers:
 *   onMeta(payload)
 *   onStage(payload)        // payload: { phase, ... } — context / knowledge / mock / model
 *   onReasoning(payload)    // payload: { iteration, text } — the model's own thinking
 *   onToolCall(payload)
 *   onToolResult(payload)
 *   onDelta(payload)        // payload: { text }
 *   onUsage(payload)
 *   onDone(payload)         // payload: { finishReason }
 *   onError(payload)        // payload: { code, message }
 */
export function streamAgentChat(sessionId, payload, handlers = {}) {
    return streamAgent(`${API_BASE_URL}/agent/sessions/${encodeURIComponent(sessionId)}/chat/stream`, payload, handlers)
}

export function streamAgentResume(sessionId, runId, payload, handlers = {}) {
    return streamAgent(`${API_BASE_URL}/agent/sessions/${encodeURIComponent(sessionId)}/runs/${encodeURIComponent(runId)}/resume/stream`, payload, handlers)
}

const eventHandlers = new Map(Object.entries({
    meta: 'onMeta', stage: 'onStage', reasoning: 'onReasoning', tool_call: 'onToolCall', tool_result: 'onToolResult',
    agent_output: 'onAgentOutput', sources: 'onSources', artifact: 'onArtifact', delta: 'onDelta', usage: 'onUsage',
    done: 'onDone', error: 'onError'
}))
const streamError = (code, message) => Object.assign(new Error(message), { code })

function parseFrame(frame) {
    let name = 'message', id = null
    const lines = []
    for (const line of frame.split('\n')) {
        const colon = line.indexOf(':')
        const field = colon < 0 ? line : line.slice(0, colon)
        const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
        if (field === 'event') name = value || 'message'
        if (field === 'id' && !value.includes('\0')) id = value
        if (field === 'data') lines.push(value)
    }
    if (!lines.length) return null // comment/heartbeat
    let data
    try { data = JSON.parse(lines.join('\n')) } catch { /* fail closed below */ }
    if (!data || typeof data !== 'object' || Array.isArray(data)) {
        throw streamError('STREAM_INVALID_FRAME', `Invalid JSON object in ${name} event`)
    }
    return { name, data, id }
}

function streamAgent(url, payload, handlers) {
    const controller = new AbortController()
    const { signal } = controller
    const headers = { 'Content-Type': 'application/json', Accept: 'text/event-stream' }
    const token = localStorage.getItem('token')
    if (token) headers.Authorization = `Bearer ${token}`
    let reader, closing
    const closeReader = () => { if (reader) closing ??= reader.cancel().catch(() => {}) }
    signal.addEventListener('abort', closeReader, { once: true })

    const done = (async () => {
        let response, completed = false, errorEvent = false, runId = null, attemptId = null
        try {
            response = await fetch(url, { method: 'POST', headers, body: JSON.stringify(payload), signal, credentials: 'include' })
            if (signal.aborted) return
            if (!response.ok) {
                const body = await response.json().catch(() => null)
                throw Object.assign(streamError(body?.code || `HTTP_${response.status}`, body?.message || `HTTP ${response.status}`), { status: response.status })
            }
            if (!/^text\/event-stream(?:\s*;|$)/i.test(response.headers.get('content-type') || '') || !response.body) {
                throw streamError('STREAM_CONTENT_TYPE', 'Expected a text/event-stream response')
            }
            reader = response.body.getReader()
            const decoder = new TextDecoder('utf-8', { fatal: true })
            let buffer = '', afterCR = false
            const retiredAttempts = new Set()
            while (!completed && !signal.aborted) {
                const part = await reader.read()
                if (signal.aborted) break
                let chunk = decoder.decode(part.value, { stream: !part.done })
                if (chunk) {
                    if (afterCR && chunk.startsWith('\n')) chunk = chunk.slice(1)
                    afterCR = chunk.endsWith('\r')
                    buffer += chunk.replace(/\r\n?/g, '\n')
                }
                let end
                while (!completed && !signal.aborted && (end = buffer.indexOf('\n\n')) !== -1) {
                    const frame = parseFrame(buffer.slice(0, end))
                    buffer = buffer.slice(end + 2)
                    if (!frame) continue
                    // Keep the wire observer lossless; unrelated frames cannot close this POST or reach legacy handlers.
                    handlers.onEvent?.(frame)
                    if (frame.data.runId && runId && frame.data.runId !== runId) continue
                    runId ??= frame.data.runId
                    const attempt = frame.data.attemptId ?? frame.data.traceId
                    if (attempt && attemptId && attempt !== attemptId && frame.name !== 'meta') continue
                    if (frame.name === 'meta' && attempt) {
                        // Only a previously unseen meta can announce a new attempt, not a replay.
                        if (retiredAttempts.has(attempt)) continue
                        if (attemptId && attempt !== attemptId) retiredAttempts.add(attemptId)
                        attemptId = attempt
                    }
                    completed = frame.name === 'done'
                    if (frame.name === 'error' && (frame.data.scope === 'run' || (!frame.data.scope && !frame.data.agentId))) errorEvent = true
                    const handler = eventHandlers.get(frame.name)
                    if (handler) handlers[handler]?.(frame.data)
                }
                if (part.done) break // SSE dispatch requires a blank line, not just EOF.
            }
            if (!completed && !errorEvent && !signal.aborted) {
                throw streamError('STREAM_INCOMPLETE', i18n.global.t('agent.streamInterrupted'))
            }
        } catch (err) {
            if (!signal.aborted) {
                const diagnostic = { status: err.status, code: err.code || (response ? 'STREAM' : 'NETWORK'), message: err.message }
                // Transport failures are not fabricated server events. Existing onError subscribers still receive both.
                handlers.onTransportError?.(diagnostic)
                handlers.onError?.(diagnostic)
            }
        } finally {
            signal.removeEventListener('abort', closeReader)
            if (reader) { closeReader(); await closing; reader.releaseLock() }
            else if (response?.body && !response.bodyUsed) await response.body.cancel().catch(() => {})
            handlers.onClose?.({ completed, cancelled: signal.aborted })
        }
    })()
    return { cancel: () => controller.abort(), done }
}

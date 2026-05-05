import axios from 'axios'
import { API_BASE_URL } from './runtimeConfig'

const apiClient = axios.create({
    baseURL: API_BASE_URL,
    headers: { 'Content-Type': 'application/json' }
})

apiClient.interceptors.request.use(config => {
    const token = localStorage.getItem('token')
    if (token) {
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

export const agentAPI = {
    /** Create a new Agent session (Mode A: AI 助手会话). */
    createSession: ({ entryMode = AGENT_OP.ASSISTANT_CHAT, title = 'AI 助手', boundChatId = null } = {}) =>
        apiClient.post('/agent/sessions', { entryMode, title, boundChatId }),

    /** List the current user's sessions (history dropdown). */
    listSessions: () => apiClient.get('/agent/sessions'),

    /** Rename a session. */
    renameSession: (sessionId, title) =>
        apiClient.patch(`/agent/sessions/${sessionId}`, { title }),

    /** Delete a session (clears short-term memory too). */
    deleteSession: (sessionId) =>
        apiClient.delete(`/agent/sessions/${sessionId}`),

    /** Fetch persisted messages of a session (currently from Redis short-term memory). */
    sessionMessages: (sessionId) =>
        apiClient.get(`/agent/sessions/${sessionId}/messages`),

    /** Non-streaming chat (kept as a fallback when SSE fails). */
    chat: (sessionId, payload) =>
        apiClient.post(`/agent/sessions/${sessionId}/chat`, payload),

    /** Clear short-term Redis memory for a session. */
    clearSessionMemory: (sessionId) =>
        apiClient.delete(`/agent/sessions/${sessionId}/memory`),

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
 * Handlers:
 *   onMeta(payload)
 *   onToolCall(payload)
 *   onToolResult(payload)
 *   onDelta(payload)        // payload: { text }
 *   onUsage(payload)
 *   onDone(payload)         // payload: { finishReason }
 *   onError(payload)        // payload: { code, message }
 */
export function streamAgentChat(sessionId, payload, handlers = {}) {
    const controller = new AbortController()
    const url = `${API_BASE_URL}/agent/sessions/${sessionId}/chat/stream`
    const token = localStorage.getItem('token')

    const headers = {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream'
    }
    if (token) headers.Authorization = `Bearer ${token}`

    const done = (async () => {
        let response
        try {
            response = await fetch(url, {
                method: 'POST',
                headers,
                body: JSON.stringify(payload),
                signal: controller.signal,
                credentials: 'include'
            })
        } catch (err) {
            if (err.name !== 'AbortError') {
                handlers.onError?.({ code: 'NETWORK', message: err.message })
            }
            return
        }

        if (!response.ok) {
            let errBody = {}
            try { errBody = await response.json() } catch { /* non-json */ }
            handlers.onError?.({
                code: errBody.code || 'HTTP_' + response.status,
                message: errBody.message || `HTTP ${response.status}`
            })
            return
        }

        const reader = response.body.getReader()
        const decoder = new TextDecoder('utf-8')
        let buffer = ''

        try {
            while (true) {
                const { value, done: streamDone } = await reader.read()
                if (streamDone) break
                buffer += decoder.decode(value, { stream: true })

                // SSE frames are separated by blank lines
                let frameEnd
                while ((frameEnd = buffer.indexOf('\n\n')) !== -1) {
                    const rawFrame = buffer.slice(0, frameEnd)
                    buffer = buffer.slice(frameEnd + 2)
                    dispatchFrame(rawFrame, handlers)
                }
            }
            if (buffer.trim()) dispatchFrame(buffer, handlers)
        } catch (err) {
            if (err.name !== 'AbortError') {
                handlers.onError?.({ code: 'STREAM', message: err.message })
            }
        }
    })()

    return {
        cancel: () => controller.abort(),
        done
    }
}

function dispatchFrame(rawFrame, handlers) {
    let event = 'message'
    const dataLines = []
    for (const line of rawFrame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
        // id: lines are intentionally ignored — we don't yet support Last-Event-ID reconnect
    }
    if (dataLines.length === 0) return

    const dataStr = dataLines.join('\n')
    let parsed = dataStr
    try { parsed = JSON.parse(dataStr) } catch { /* leave as string */ }

    switch (event) {
        case 'meta': handlers.onMeta?.(parsed); break
        case 'tool_call': handlers.onToolCall?.(parsed); break
        case 'tool_result': handlers.onToolResult?.(parsed); break
        case 'delta': handlers.onDelta?.(parsed); break
        case 'usage': handlers.onUsage?.(parsed); break
        case 'done': handlers.onDone?.(parsed); break
        case 'error': handlers.onError?.(parsed); break
        default: /* ignore unknown */ break
    }
}

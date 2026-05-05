import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { agentAPI, streamAgentChat, AGENT_OP } from '@/services/agentApi'

export const AI_ASSISTANT_CHAT_ID = 'ai-assistant'
const SESSION_STORAGE_KEY = 'agent.sessionId'
const ACTIVE_PROVIDER_STORAGE_KEY = 'agent.activeProviderId'
const SESSION_MESSAGES_STORAGE_KEY = 'agent.sessionMessages'
// Cap per-session cache to keep localStorage from blowing up on long convos.
const MAX_CACHED_MESSAGES_PER_SESSION = 200

function loadCachedSessionMessages() {
    try {
        const raw = localStorage.getItem(SESSION_MESSAGES_STORAGE_KEY)
        if (!raw) return {}
        const parsed = JSON.parse(raw)
        return parsed && typeof parsed === 'object' ? parsed : {}
    } catch {
        return {}
    }
}

/**
 * Agent (AI Assistant) store.
 *
 * Owns the Mode A "AI 助手" virtual conversation:
 *   - one persistent agent session per logged-in user (created on demand)
 *   - a flat message list { role, content, status, toolCalls? }
 *   - streaming state machine driven by SSE events
 */
export const useAgentStore = defineStore('agent', () => {
    const sessionId = ref(localStorage.getItem(SESSION_STORAGE_KEY) || null)
    const activeProviderId = ref(parseStoredProviderId())
    const messages = ref([])           // [{ id, role, content, toolCalls, status, createdAt }]
    const streaming = ref(false)
    const lastError = ref(null)
    let activeStream = null            // { cancel, done }

    // Per-session message cache. Survives switching between sessions and
    // page refreshes via localStorage, so the user sees their full history
    // even if Redis short-term memory has been evicted on the server.
    const messagesBySessionId = ref(loadCachedSessionMessages())

    // History sessions (the "history conversations" dropdown)
    const sessions = ref([])           // [{ sessionId, title, createdAt, updatedAt }]
    const sessionsLoading = ref(false)

    // Hydrate the live messages array from cache for whichever session was
    // active at boot, so a hard refresh doesn't show an empty bubble list
    // before the (slower) server fetch completes.
    if (sessionId.value && Array.isArray(messagesBySessionId.value[sessionId.value])) {
        messages.value = messagesBySessionId.value[sessionId.value].map(m => ({ ...m }))
    }

    function persistMessageCache() {
        try {
            localStorage.setItem(
                SESSION_MESSAGES_STORAGE_KEY,
                JSON.stringify(messagesBySessionId.value)
            )
        } catch (err) {
            // Quota / private-mode failures are non-fatal — the in-memory cache
            // still works for the rest of the tab's lifetime.
            console.warn('persist agent message cache failed', err)
        }
    }

    /** Snapshot the current messages list into the per-session cache. */
    function saveMessagesToCache(sid = sessionId.value, list = messages.value) {
        if (!sid) return
        const trimmed = list.length > MAX_CACHED_MESSAGES_PER_SESSION
            ? list.slice(list.length - MAX_CACHED_MESSAGES_PER_SESSION)
            : list
        // Strip any non-serialisable refs / cancel handles before persisting.
        messagesBySessionId.value[sid] = trimmed.map(m => ({
            id: m.id,
            role: m.role,
            content: m.content,
            toolCalls: Array.isArray(m.toolCalls) ? m.toolCalls.map(tc => ({ ...tc })) : [],
            createdAt: m.createdAt,
            status: m.status === 'streaming' ? 'done' : (m.status || 'done'),
            error: m.error || null,
            usage: m.usage || null
        }))
        persistMessageCache()
    }

    function dropCachedSession(sid) {
        if (!sid) return
        if (sid in messagesBySessionId.value) {
            delete messagesBySessionId.value[sid]
            persistMessageCache()
        }
    }

    const hasMessages = computed(() => messages.value.length > 0)
    const activeSession = computed(() =>
        sessions.value.find(s => s.sessionId === sessionId.value) || null
    )

    function parseStoredProviderId() {
        const raw = localStorage.getItem(ACTIVE_PROVIDER_STORAGE_KEY)
        if (!raw) return null
        const num = Number(raw)
        return Number.isFinite(num) ? num : null
    }

    function setActiveProviderId(id) {
        activeProviderId.value = id || null
        if (id) {
            localStorage.setItem(ACTIVE_PROVIDER_STORAGE_KEY, String(id))
        } else {
            localStorage.removeItem(ACTIVE_PROVIDER_STORAGE_KEY)
        }
    }

    function setSessionId(id) {
        sessionId.value = id
        if (id) {
            localStorage.setItem(SESSION_STORAGE_KEY, id)
        } else {
            localStorage.removeItem(SESSION_STORAGE_KEY)
        }
    }

    async function ensureSession() {
        if (sessionId.value) return sessionId.value
        const response = await agentAPI.createSession({
            entryMode: AGENT_OP.ASSISTANT_CHAT,
            title: 'AI 助手'
        })
        const id = response.data?.data?.sessionId
        if (!id) throw new Error('Failed to create agent session')
        setSessionId(id)
        return id
    }

    function _newMsgId() {
        return `m-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    }

    /**
     * Send a user message and stream the assistant's reply.
     * Resolves when the SSE stream closes.
     */
    async function sendMessage(text, options = {}) {
        const trimmed = (text || '').trim()
        if (!trimmed || streaming.value) return
        lastError.value = null

        let sid
        try {
            sid = await ensureSession()
        } catch (err) {
            lastError.value = { code: 'SESSION', message: err.message || String(err) }
            return
        }

        const userMsg = {
            id: _newMsgId(),
            role: 'user',
            content: trimmed,
            createdAt: new Date().toISOString(),
            status: 'sent'
        }
        const assistantMsg = {
            id: _newMsgId(),
            role: 'assistant',
            content: '',
            toolCalls: [],
            createdAt: new Date().toISOString(),
            status: 'streaming'
        }
        messages.value.push(userMsg, assistantMsg)
        streaming.value = true

        const payload = {
            operationType: AGENT_OP.ASSISTANT_CHAT,
            input: trimmed,
            chatContext: options.chatId ? { chatId: options.chatId } : undefined,
            providerId: activeProviderId.value || undefined,
            // Module B: when the user has bound this session to a knowledge base
            // (via KnowledgeBaseSelector in the chat header), forward the kbId
            // so the Python orchestrator runs knowledge_rag.retrieve before
            // building the prompt.
            linkedKbId: options.linkedKbId || undefined,
            options: {
                visibility: 'PRIVATE_ONLY',
                maxOutputTokens: 1024,
                temperature: 0.2,
                maxIterations: 6
            }
        }

        const handlers = {
            onMeta: () => { /* no-op, traceId logged on server */ },
            onToolCall: ({ toolName, args }) => {
                assistantMsg.toolCalls.push({
                    toolName,
                    args,
                    status: 'running',
                    latencyMs: null
                })
            },
            onToolResult: ({ toolName, status, latencyMs, error }) => {
                const last = [...assistantMsg.toolCalls].reverse().find(t => t.toolName === toolName && t.status === 'running')
                if (last) {
                    last.status = status || 'SUCCESS'
                    last.latencyMs = latencyMs ?? null
                    if (error) last.error = error
                }
            },
            onDelta: ({ text: delta }) => {
                if (delta) assistantMsg.content += delta
            },
            onUsage: (usage) => {
                assistantMsg.usage = usage
            },
            onDone: () => {
                assistantMsg.status = 'done'
            },
            onError: (err) => {
                lastError.value = err
                assistantMsg.status = 'error'
                assistantMsg.error = err
                if (!assistantMsg.content) {
                    assistantMsg.content = `[请求失败：${err.message || err.code || 'unknown'}]`
                }
            }
        }

        activeStream = streamAgentChat(sid, payload, handlers)
        try {
            await activeStream.done
        } finally {
            activeStream = null
            streaming.value = false
            if (assistantMsg.status === 'streaming') {
                assistantMsg.status = 'done'
            }
            // Snapshot the just-completed turn so switching away and back
            // (or refreshing) preserves the conversation locally.
            saveMessagesToCache(sid)
            // After each turn, refresh the history dropdown so the new title /
            // updated_at order is reflected. Best-effort, never blocks the user.
            loadSessions().catch(() => {})
        }
    }

    /** Abort the in-flight stream (if any). */
    function cancelStream() {
        if (activeStream) {
            activeStream.cancel()
            activeStream = null
        }
        streaming.value = false
    }

    /**
     * Clear local + server-side short-term memory for the current session.
     * After this, the next message starts a clean context.
     */
    async function clearConversation() {
        cancelStream()
        const id = sessionId.value
        messages.value = []
        if (id) {
            // Wipe the local cache too, otherwise the next switchSession()
            // would silently restore the cleared bubbles from disk.
            dropCachedSession(id)
            try {
                await agentAPI.clearSessionMemory(id)
            } catch (err) {
                console.warn('clear session memory failed', err)
            }
        }
    }

    /** Discard everything — session id, messages, both client-side and server-side. */
    async function resetAll() {
        await clearConversation()
        setSessionId(null)
    }

    // ---------- History sessions (dropdown) ----------

    async function loadSessions() {
        sessionsLoading.value = true
        try {
            const r = await agentAPI.listSessions()
            sessions.value = r.data?.data || []
        } catch (err) {
            console.warn('load agent sessions failed', err)
            sessions.value = []
        } finally {
            sessionsLoading.value = false
        }
    }

    /** Switch to a different session: load its persisted messages and replace local state. */
    async function switchSession(sid) {
        if (!sid || sid === sessionId.value) return
        cancelStream()
        // Snapshot the outgoing session before we clobber `messages` so the
        // user can switch back later and still see what they had typed.
        if (sessionId.value && messages.value.length) {
            saveMessagesToCache(sessionId.value)
        }
        setSessionId(sid)
        // Show the cached transcript immediately for snappy switching; then
        // the server fetch below will reconcile / extend it.
        const cached = messagesBySessionId.value[sid]
        if (Array.isArray(cached) && cached.length) {
            messages.value = cached.map(m => ({ ...m }))
        } else {
            messages.value = []
        }
        try {
            const r = await agentAPI.sessionMessages(sid)
            const list = r.data?.data?.messages || []
            // Server is the source of truth when it has anything to say. If
            // it returns an empty list (Redis evicted the short-term memory),
            // keep the local cache so the user does not lose their history.
            if (list.length) {
                messages.value = list.map(m => ({
                    id: _newMsgId(),
                    role: m.role || 'user',
                    content: m.content || '',
                    toolCalls: [],
                    createdAt: new Date().toISOString(),
                    status: 'done'
                }))
                saveMessagesToCache(sid)
            }
        } catch (err) {
            console.warn('load session messages failed', err)
        }
    }

    /**
     * Load persisted messages for whichever session is currently active. Used
     * on AgentChatView mount so that a page refresh / re-open does not show an
     * empty conversation while the sessionId in localStorage still points at a
     * real history. If `messages` already holds anything, do nothing — Pinia
     * already has the live state and a re-fetch would clobber in-flight tool
     * calls / streaming bubbles.
     */
    async function hydrateActiveSession() {
        if (!sessionId.value) return
        if (messages.value.length > 0) return
        // Local cache first — it covers the common "Redis evicted the
        // history but the user is still in the same browser" case.
        const cached = messagesBySessionId.value[sessionId.value]
        if (Array.isArray(cached) && cached.length) {
            messages.value = cached.map(m => ({ ...m }))
        }
        try {
            const r = await agentAPI.sessionMessages(sessionId.value)
            const list = r.data?.data?.messages || []
            if (list.length) {
                messages.value = list.map(m => ({
                    id: _newMsgId(),
                    role: m.role || 'user',
                    content: m.content || '',
                    toolCalls: [],
                    createdAt: new Date().toISOString(),
                    status: 'done'
                }))
                saveMessagesToCache(sessionId.value)
            }
        } catch (err) {
            console.warn('hydrate active session failed', err)
        }
    }

    /** Create a new session, switch to it, and clear the visible messages. */
    async function newSession(title) {
        cancelStream()
        const r = await agentAPI.createSession({
            entryMode: AGENT_OP.ASSISTANT_CHAT,
            title: title || 'AI 助手'
        })
        const sid = r.data?.data?.sessionId
        if (!sid) throw new Error('Failed to create session')
        setSessionId(sid)
        messages.value = []
        await loadSessions()
        return sid
    }

    async function renameSession(sid, title) {
        await agentAPI.renameSession(sid, title)
        await loadSessions()
    }

    async function deleteSession(sid) {
        await agentAPI.deleteSession(sid)
        // Forget the local cache for this session — it would otherwise
        // resurrect the deleted history if the user (or some other tab)
        // recreated a session with the same id.
        dropCachedSession(sid)
        // If the deleted one was active, drop the local pointer; the dropdown will pick another.
        if (sessionId.value === sid) {
            setSessionId(null)
            messages.value = []
        }
        await loadSessions()
    }

    return {
        sessionId,
        activeProviderId,
        messages,
        streaming,
        lastError,
        sessions,
        sessionsLoading,
        activeSession,
        hasMessages,
        ensureSession,
        sendMessage,
        cancelStream,
        clearConversation,
        resetAll,
        setActiveProviderId,
        loadSessions,
        switchSession,
        hydrateActiveSession,
        newSession,
        renameSession,
        deleteSession
    }
})

/** Static descriptor for the virtual "AI 助手" chat list entry. */
export function buildAiAssistantChatItem(t) {
    return {
        id: AI_ASSISTANT_CHAT_ID,
        type: 'AI',
        name: t ? t('agent.title') : 'AI 助手',
        avatar: '',
        lastMessage: t ? t('agent.lastMessageHint') : '随时为你服务',
        lastMessageTime: null,
        unreadCount: 0,
        online: true,
        status: 'online',
        members: [],
        memberCount: 0,
        contactId: null,
        isAi: true
    }
}

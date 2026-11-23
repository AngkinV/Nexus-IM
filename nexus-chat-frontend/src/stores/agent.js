import { defineStore } from 'pinia'
import { ref, computed, watch, onScopeDispose } from 'vue'
import { agentAPI, agentData, agentOwnerId, streamAgentChat, streamAgentResume, AGENT_OP } from '@/services/agentApi'
import { useUserStore } from '@/stores/user'
import { retryAtOf } from '@/stores/agentProviders'
import i18n from '@/locales/i18n'
import { ElMessageBox } from 'element-plus'
import { useAgentWorkspaceStore } from './agentWorkspace'
import { normalizeScope, authorizedScope, observeScope, scopeFailure } from '@/components/agent/scopeSelection.mjs'

export const AI_ASSISTANT_CHAT_ID = 'ai-assistant'
const MAX_CACHED_MESSAGES = 200
const uid = prefix => `${prefix}_${globalThis.crypto.randomUUID()}`
const now = () => new Date().toISOString()
const changed = () => new Error('The active agent session has changed')
const defaultOptions = { visibility: 'PRIVATE_ONLY', temperature: 0.2, maxIterations: 6, maxOutputTokens: 1024,
    collaborationEnabled: false, webSearchEnabled: false }

function turnSettings(input) {
    const options = { ...defaultOptions }
    for (const [key, min, max] of [['temperature', 0, 2], ['maxIterations', 1, 32], ['maxOutputTokens', 1, 32768]]) {
        const value = input[key] ?? options[key]
        if (!Number.isFinite(value) || (key !== 'temperature' && !Number.isInteger(value)) || value < min || value > max) {
            throw new RangeError(`Invalid ${key}: expected ${min}–${max}`)
        }
        options[key] = value
    }
    for (const key of ['collaborationEnabled', 'webSearchEnabled']) {
        if (input[key] == null) continue
        if (typeof input[key] !== 'boolean') throw new TypeError(`Invalid ${key}`)
        options[key] = input[key]
    }
    const ids = input.attachmentIds ?? []
    if (!Array.isArray(ids) || ids.length > 4 || new Set(ids).size !== ids.length || ids.some(id => typeof id !== 'string' || !id.trim())) {
        throw new RangeError('Select up to four distinct attachment IDs')
    }
    return { options, attachmentIds: [...ids], linkedKbId: input.linkedKbId ?? null, chatId: input.chatId ?? null }
}

function assistant(fields) {
    return { id: uid('m'), role: 'assistant', content: '', status: 'interrupted', createdAt: null,
        runId: null, providerId: null, elapsedMs: null, finishReason: null, usage: null, options: null,
        attachmentIds: [], artifacts: [], sources: [], trace: [], toolCalls: [], error: null, retryAt: null, resumable: false, ...fields }
}
function question(content, runId, createdAt) {
    return { id: uid('m'), role: 'user', content, runId, createdAt, status: 'sent' }
}
function terminalStatus(reason, error) {
    if (reason === 'stop') return error ? 'error' : 'done'
    if (['length', 'max_tokens', 'max_output_tokens', 'max_iterations', 'truncated', 'content_filter'].includes(reason)) return 'truncated'
    if (reason === 'canceled') return 'cancelled'
    if (reason === 'timeout') return 'interrupted'
    if (['cancelled', 'interrupted'].includes(reason)) return reason
    return 'error'
}
function upsertById(list, items) {
    for (const item of items || []) {
        if (!item?.id) continue
        const index = list.findIndex(current => current.id === item.id)
        if (index < 0) list.push(item)
        else list[index] = item
    }
}
function closeOpenSteps(msg, status) {
    for (const entry of [...msg.trace, ...msg.toolCalls]) {
        if (entry.status === 'running' || entry.status === 'queued') entry.status = status
    }
}

function mergeStep(entry, next) {
    const { at, sequence, id, parentIds, ...fields } = next
    Object.assign(entry, fields)
    if (at != null) entry.lastAt = at
    if (sequence != null) entry.lastSequence = sequence
    if (id != null) entry.lastId = id
    if (parentIds) entry.parentIds = [...new Set([...(entry.parentIds || []), ...parentIds])]
}

/** The same projection consumes wire frames and persisted Java {name,data,id} trace entries. */
function eventReducer(msg, binding = {}) {
    const seen = new Set(), blocks = new Map(), retired = new Set()
    // The server may assign the run id on the first frame; until then nothing is bound.
    let acceptedRunId = null, attemptId = null, legacy = 0, traceStart = msg.trace.length, callsStart = msg.toolCalls.length
    return ({ name, data, id }, fallbackAt) => {
        if (!data || typeof data !== 'object') return false
        if (data.runId && acceptedRunId && data.runId !== acceptedRunId) return false
        if (data.runId) acceptedRunId = msg.runId = data.runId // explicit legacy gateway assignment
        let incoming = data.attemptId ?? data.traceId ?? attemptId ?? 'legacy-attempt'
        if (name === 'meta' && data.resumed && !data.attemptId && !data.traceId) incoming = `legacy-attempt:${++legacy}`
        if (retired.has(incoming)) return false
        if (attemptId && incoming !== attemptId && name !== 'meta') return false
        if (name === 'meta' && incoming !== attemptId) {
            if (attemptId) retired.add(attemptId)
            blocks.clear(); traceStart = msg.trace.length; callsStart = msg.toolCalls.length
        }
        attemptId = incoming
        const identity = JSON.stringify([msg.runId, incoming, data.sequence])
        if (Number.isInteger(data.sequence)) {
            if (seen.has(identity)) return false
            seen.add(identity)
        }
        const line = { ...data, ...(id != null && { id }), runId: msg.runId, at: data.at ?? fallbackAt }
        const step = (kind, fields) => msg.trace.push({ ...line, ...fields, kind })
        if (name === 'meta') {
            if (data.resumed) { msg.content = ''; msg.error = null; msg.retryAt = null; msg.finishReason = null; msg.status = 'streaming' }
            observeScope(msg, data.scope, { ...binding, runId: msg.runId })
            if (data.providerId != null) msg.providerId = data.providerId
            step('meta')
        } else if (name === 'stage') {
            const kind = data.phase || 'stage'
            const status = data.status === 'start' ? 'running' : data.status
            const open = msg.trace.findLast((s, index) => index >= traceStart && s.kind === kind && s.agentId === data.agentId &&
                (s.status === 'queued' || s.status === 'running') &&
                (data.nodeId ? s.nodeId === data.nodeId : s.iteration === data.iteration))
            if (open) mergeStep(open, { ...line, status })
            else step(kind, { status })
        } else if (name === 'reasoning' || name === 'agent_output' || name === 'delta') {
            if (typeof data.text !== 'string') return
            const kind = name === 'delta' ? 'answer' : name
            const key = JSON.stringify([kind, data.nodeId, data.agentId, data.callId, data.iteration])
            if (data.replayed && name === 'delta') { msg.content = ''; blocks.delete(key) }
            if (name === 'delta') msg.content += data.text
            // Interleaved analyst streams still update one reactive block per actual call, not one per token.
            const block = blocks.get(key)
            if (block) {
                block.text += data.text
                if (data.sequence != null) block.lastSequence = data.sequence
                if (line.id != null) block.lastId = line.id
                if (line.at != null) block.lastAt = line.at
                if (name === 'delta') block.chars = msg.content.length
            } else {
                step(kind, name === 'delta' ? { chars: msg.content.length } : {})
                blocks.set(key, msg.trace.at(-1))
            }
        } else if (name === 'tool_call' || name === 'tool_result') {
            const match = s => data.callId ? s.callId === data.callId : !s.callId && s.toolName === data.toolName &&
                s.agentId === data.agentId && s.status === 'running'
            const fields = { ...line, status: name === 'tool_call' ? data.status || 'running' : data.status }
            const call = msg.toolCalls.findLast((s, index) => index >= callsStart && match(s))
            const entry = msg.trace.findLast((s, index) => index >= traceStart && s.kind === 'tool' && match(s))
            if (call) mergeStep(call, fields)
            else msg.toolCalls.push({ latencyMs: null, ...fields })
            if (entry) mergeStep(entry, fields)
            else step('tool', fields)
        } else if (name === 'sources') {
            upsertById(msg.sources, data.items); step(name)
        } else if (name === 'artifact') {
            upsertById(msg.artifacts, [data.artifact]); step(name)
        } else if (name === 'usage') {
            msg.usage = { ...data }
            for (const key of ['modelCalls', 'toolCallCount', 'reusedToolCallCount', 'usageReported', 'usageComplete']) {
                if (Object.hasOwn(data, key)) msg[key] = data[key]
            }
            step(name)
        } else if (name === 'error') {
            if (scopeFailure(data)) msg.scopeState = 'unknown'
            step(name)
            const roleFailure = !data.scope && !data.transport && !data.callId && (!data.agentId || ['assistant', 'synth'].includes(data.agentId))
            // retryAt is the backend's own Retry-After; without one the turn can be continued at once.
            if (roleFailure || data.scope === 'run') { msg.error = data; msg.status = 'error'; msg.retryAt = retryAtOf(data) }
        } else if (name === 'done') {
            msg.finishReason = data.finishReason ?? null
            msg.status = terminalStatus(msg.finishReason, msg.error)
            msg.resumable = data.resumable === true && msg.status !== 'done'
            if (Number.isFinite(data.at)) msg.elapsedMs = data.at
            step(name)
        }
        return true
    }
}

// Merge projected steps by wire identity, never by question/answer text. A durable checkpoint
// can contain fewer events than the browser observed (especially after an interrupted write).
function mergeTrace(local, remote) {
    function groups(trace) {
        const result = new Map()
        let attempt = '', n = 0
        for (const step of trace) {
            if (step.kind === 'meta') attempt = step.attemptId ?? step.traceId ?? String(++n)
            if (!result.has(attempt)) result.set(attempt, [])
            result.get(attempt).push(step)
        }
        return result
    }
    function identity(e) {
        if (e.sequence != null) return `seq:${e.sequence}`
        if (e.id != null) return `sse:${e.id}`
        // Older public frames have no sequence. Match occurrences only within this run/attempt/call.
        return JSON.stringify([e.kind, e.callId, e.nodeId, e.agentId, e.iteration, e.artifact?.id, e.items?.map(i => i.id)])
    }
    const before = groups(local), after = groups(remote), result = []
    for (const attempt of new Set([...before.keys(), ...after.keys()])) {
        const rows = [...(after.get(attempt) || [])], positions = new Map()
        rows.forEach((e, i) => {
            const key = identity(e)
            if (!positions.has(key)) positions.set(key, [])
            positions.get(key).push(i)
        })
        for (const e of before.get(attempt) || []) {
            const i = positions.get(identity(e))?.shift()
            if (i == null) rows.push(e)
            else {
                const remote = rows[i]
                // Keep a folded block/result that was checkpointed locally beyond the server's last sequence.
                rows[i] = (e.lastSequence ?? -1) > (remote.lastSequence ?? remote.sequence ?? -1)
                    ? { ...remote, ...e } : { ...e, ...remote }
            }
        }
        rows.sort((a, b) => Number.isFinite(a.sequence) && Number.isFinite(b.sequence) ? a.sequence - b.sequence
            : Number.isFinite(a.at) && Number.isFinite(b.at) ? a.at - b.at : 0)
        result.push(...rows)
    }
    return result
}

function restoredRun(run, cached, binding) {
    let selection = null
    try { selection = normalizeScope(run.scopeSelection ?? cached?.scopeSelection) } catch { /* Legacy/malformed snapshots cannot authorize resume. */ }
    if (run.scopeState === 'unavailable') {
        // A redacted Java read view is authoritative over private browser results.
        // Copy only retry identity/input/selection; never spread the cached assistant.
        return assistant({ id: cached?.id || uid('m'), runId: run.runId,
            userMessageId: cached?.userMessageId, input: run.input ?? cached?.input,
            scopeSelection: selection, expectedBindingVersion: run.expectedBindingVersion ?? cached?.expectedBindingVersion ?? null,
            operationType: run.operationType, sessionId: run.sessionId,
            createdAt: run.startedAt ?? cached?.createdAt ?? null, finishedAt: run.finishedAt ?? null,
            retryOfRunId: run.retryOfRunId ?? cached?.retryOfRunId ?? null,
            status: run.status, scopeState: 'unavailable', receiptState: 'unknown', actualScope: null,
            reasoning: '', resumable: false })
    }
    const msg = assistant({ ...cached, runId: run.runId, input: run.input ?? cached?.input,
        providerId: run.providerRef?.providerId ?? run.providerId ?? cached?.providerId ?? null,
        scopeSelection: selection, expectedBindingVersion: run.expectedBindingVersion ?? cached?.expectedBindingVersion ?? null,
        options: run.options ?? cached?.options ?? null, linkedKbId: run.linkedKbId ?? cached?.linkedKbId ?? null,
        attachmentIds: run.attachmentIds ?? run.attachmentRefs?.map(a => a.id) ?? cached?.attachmentIds ?? [],
        createdAt: run.startedAt ?? cached?.createdAt ?? null, retryOfRunId: run.retryOfRunId ?? cached?.retryOfRunId ?? null })
    if (run.trace?.length) {
        Object.assign(msg, { trace: [], toolCalls: [], content: '', finishReason: null, error: null, usage: null, artifacts: [], sources: [] })
        const accept = eventReducer(msg, binding)
        for (const frame of run.trace) accept(frame)
        if (cached?.trace?.length) {
            msg.trace = mergeTrace(cached.trace, msg.trace)
            msg.toolCalls = msg.trace.filter(e => e.kind === 'tool').map(e => ({ ...e }))
            upsertById(msg.sources, cached.sources?.filter(item => !msg.sources.some(s => s.id === item.id)))
            upsertById(msg.artifacts, cached.artifacts?.filter(item => !msg.artifacts.some(a => a.id === item.id)))
        }
    }
    // A listed run is an authoritative server read: a redacted receipt is a fact, and
    // it must be recorded as "no usable receipt" rather than left absent.
    if (run.scopeState === 'unavailable') {
        msg.actualScope = null
        msg.scopeState = 'unavailable'
    } else {
        observeScope(msg, run.scope, { ...binding, runId: run.runId }, { authoritative: true })
    }
    if (scopeFailure(run.error)) {
        msg.scopeState = 'unknown'
    }
    msg.content = run.answer ?? (msg.content || cached?.content || '')
    // The terminal wire event gives a more specific cause (e.g. timeout) than a summary column.
    msg.finishReason = msg.finishReason && msg.finishReason !== 'stop' && (!run.finishReason || run.finishReason === 'stop')
        ? msg.finishReason : run.finishReason ?? msg.finishReason
    msg.error = run.error ?? msg.error
    const reported = run.status?.toLowerCase()
    const status = ['running', 'streaming', 'pending'].includes(reported) ? 'interrupted' : reported
    const terminal = msg.finishReason ? terminalStatus(msg.finishReason, msg.error) : null
    // A success summary can never upgrade a recorded failure/cancellation/truncation.
    msg.status = terminal && terminal !== 'done' ? terminal : status || terminal || msg.status
    if (!msg.finishReason && cached?.status === 'interrupted' && !run.finishReason) msg.status = 'interrupted'
    if (msg.error && msg.status === 'done') msg.status = msg.error.code === 'STREAM_INCOMPLETE' ? 'interrupted' : 'error'
    msg.usage = run.usage ?? msg.usage
    for (const key of ['modelCalls', 'toolCallCount', 'reusedToolCallCount', 'usageReported', 'usageComplete', 'producerAttemptId']) {
        if (Object.hasOwn(run, key)) msg[key] = run[key]
    }
    msg.elapsedMs = run.elapsedMs ?? msg.elapsedMs
    msg.resumable = msg.status !== 'done' && (run.resumable ?? msg.resumable)
    upsertById(msg.artifacts, run.artifacts); upsertById(msg.sources, run.sources)
    if (msg.status !== 'done') closeOpenSteps(msg, msg.status)
    return msg
}

function reconcile(cached, runs, binding) {
    runs = runs.filter(run => (run.sessionId == null || run.sessionId === binding.sessionId) &&
        (run.actorUserId == null || String(run.actorUserId) === String(binding.actorUserId)) &&
        (run.userId == null || String(run.userId) === String(binding.actorUserId)))
    const result = cached.slice()
    const byRun = new Map(result.filter(m => m.role === 'assistant' && m.runId).map(m => [m.runId, m]))
    // The cache is capped; an earlier server turn can precede the first locally cached turn.
    let anchor = result.length
    const insertionPoints = runs.map(() => result.length)
    for (let i = runs.length - 1; i >= 0; --i) {
        const known = byRun.get(runs[i].runId)
        if (known) {
            const pos = result.indexOf(known)
            const q = result.findIndex(m => m.role === 'user' && (m.id === known.userMessageId || m.runId === known.runId))
            anchor = Math.min(anchor, q >= 0 ? q : result[pos - 1]?.role === 'user' ? pos - 1 : pos)
        }
        insertionPoints[i] = cached[anchor]?.id
    }
    for (const [i, run] of runs.entries()) {
        if (!run.runId) continue
        const prior = byRun.get(run.runId), msg = restoredRun(run, prior, binding)
        const parent = byRun.get(msg.retryOfRunId)
        let user = result.find(m => m.role === 'user' && (m.id === (msg.userMessageId || parent?.userMessageId) || m.runId === run.runId))
        if (!user && prior) {
            // Old cached assistants had run IDs but the preceding user bubble did not.
            const adjacent = result[result.indexOf(prior) - 1]
            if (adjacent?.role === 'user' && !adjacent.runId) user = adjacent
        }
        if (!user && typeof msg.input === 'string') {
            user = question(msg.input, msg.retryOfRunId || msg.runId, msg.createdAt)
            const next = result.findIndex(m => m.id === insertionPoints[i])
            result.splice(prior ? result.indexOf(prior) : next >= 0 ? next : result.length, 0, user)
        }
        if (user) msg.userMessageId = user.id
        if (prior) result[result.indexOf(prior)] = msg
        else {
            const next = result.findIndex(m => m.id === insertionPoints[i])
            result.splice(next >= 0 ? next : result.length, 0, msg)
        }
        byRun.set(msg.runId, msg)
    }
    return result
}

export const useAgentStore = defineStore('agent', () => {
    const sessionId = ref(null), activeProviderId = ref(null), messages = ref([]), messagesBySessionId = ref({})
    const streaming = ref(false), lastError = ref(null), switching = ref(false), switchingSessionId = ref(null)
    const sessions = ref([]), sessionsLoading = ref(false)
    const activeSession = computed(() => sessions.value.find(s => s.sessionId === sessionId.value) || null)
    const hasMessages = computed(() => messages.value.length > 0)
    let owner, selection = 0, streamVersion = 0, historyVersion = 0, listVersion = 0
    let activeStream = null, creation = null
    const clearing = new Map()

    // A global legacy cache has no proof of ownership. Never import its pointer or transcripts.
    // Only an assistant turn can carry a scope receipt; a user message is never one.
    function projectRestored(msg, sid) {
        // Restoring re-validates a receipt; it never invents one. A message without an
        // actualScope field comes back exactly as it was stored.
        const copy = { ...msg }
        if (msg.role !== 'assistant' || !Object.hasOwn(msg, 'actualScope')) return copy
        copy.actualScope = authorizedScope(msg.actualScope, { actorUserId: owner, sessionId: sid, runId: msg.runId })
        return copy
    }
    const owned = () => owner === agentOwnerId()
    const key = name => `agent.${name}:${owner}`
    function read(name) { return owner !== null ? localStorage.getItem(key(name)) : null }
    function write(name, value) {
        if (!owner || !owned()) return
        try {
            if (value == null) localStorage.removeItem(key(name))
            else localStorage.setItem(key(name), value)
        } catch (err) { console.warn('Could not persist agent cache', err) }
    }
    function cacheMessages() {
        if (!sessionId.value || !owned()) return
        // Detach the snapshot from live proxies before a switch/resume can mutate it.
        messagesBySessionId.value[sessionId.value] = messages.value.slice(-MAX_CACHED_MESSAGES).map(m => ({
            ...m, status: m.status === 'streaming' ? 'interrupted' : m.status
        }))
        write('sessionMessages', JSON.stringify(messagesBySessionId.value))
    }
    function forget(sid) {
        delete messagesBySessionId.value[sid]
        write('sessionMessages', JSON.stringify(messagesBySessionId.value))
    }
    function selectSession(sid) {
        sessionId.value = sid
        // The view owns workspace switching (load/select); doing it here as well would
        // fire the same session change twice.
        write('sessionId', sid)
        messages.value = (messagesBySessionId.value[sid] || []).map(msg => projectRestored(msg, sid))
    }
    function syncOwner() {
        const next = agentOwnerId()
        if (next === owner) return
        ++selection; ++historyVersion; ++listVersion
        leaveStream()
        owner = next
        creation = null; clearing.clear()
        lastError.value = null; switching.value = sessionsLoading.value = false; switchingSessionId.value = null; sessions.value = []
        let cached
        try { cached = JSON.parse(read('sessionMessages')) } catch { /* malformed/obsolete browser data */ }
        messagesBySessionId.value = cached && typeof cached === 'object' && !Array.isArray(cached) ? cached : {}
        activeProviderId.value = Number(read('activeProviderId')) || null
        selectSession(read('sessionId'))
    }
    syncOwner()
    // Pinia outlives login views. Drop the previous account even when no action is invoked in this view.
    const auth = useUserStore()
    watch(() => [auth.currentUser?.id, auth.token], syncOwner, { flush: 'sync' })
    globalThis.window?.addEventListener('storage', syncOwner)
    onScopeDispose(() => { globalThis.window?.removeEventListener('storage', syncOwner); leaveStream() })

    function setActiveProviderId(id) {
        syncOwner(); activeProviderId.value = id || null; write('activeProviderId', id ? String(id) : null)
    }
    async function createSession(title, version) {
        const data = agentData(await agentAPI.createSession({ entryMode: AGENT_OP.ASSISTANT_CHAT, title: title || i18n.global.t('agent.title') }))
        if (!owned() || version !== selection) throw changed()
        if (!data?.sessionId) throw new Error('Failed to create agent session')
        selectSession(data.sessionId)
        return data.sessionId
    }
    async function ensureSession() {
        syncOwner()
        if (sessionId.value) return sessionId.value
        if (!creation) {
            const version = selection
            creation = createSession(null, version).finally(() => { if (version === selection) creation = null })
        }
        return creation
    }
    async function newSession(title) {
        syncOwner(); leaveStream(); cacheMessages()
        const version = ++selection
        ++historyVersion; creation = null
        switching.value = true; switchingSessionId.value = null
        try {
            const sid = await createSession(title, version)
            await loadSessions()
            return sid
        } finally { if (version === selection) switching.value = false }
    }

    function executeStream(sid, live, start, user) {
        const version = ++streamVersion, started = performance.now(), reduce = eventReducer(live, { actorUserId: owner, sessionId: sid })
        const current = () => version === streamVersion && owned() && sessionId.value === sid
        const elapsed = () => Math.round(performance.now() - started)
        let ended = false, announced = false, lastSave = 0
        streaming.value = true; ++historyVersion
        live.scopeState = 'unknown'
        live.status = 'streaming'; live.error = null; live.retryAt = null; live.finishReason = null; live.elapsedMs = null
        lastError.value = null
        cacheMessages()
        const transportError = error => {
            if (!current()) return
            if (scopeFailure(error)) {
                live.scopeState = 'unknown'
                const workspace = useAgentWorkspaceStore(); workspace.invalidateScope(); workspace.scopeError = error.message
            }
            lastError.value = error
            if (!live.finishReason) live.status = 'interrupted'
            live.trace.push({ kind: 'error', scope: 'transport', transport: true, ...error, runId: live.runId, at: elapsed() })
        }
        const stream = start({
            onEvent: frame => {
                if (!current()) return
                const time = elapsed()
                const previousLength = live.content.length
                if (!reduce(frame, time)) return
                if (frame.name === 'meta') { announced = true; if (user) user.runId = live.runId }
                if (frame.name === 'done') ended = live.status !== 'streaming'
                if (live.error) {
                    lastError.value = live.error
                    if (scopeFailure(live.error)) { const workspace = useAgentWorkspaceStore(); workspace.invalidateScope(); workspace.scopeError = live.error.message }
                }
                const checkpoint = !['delta', 'reasoning', 'agent_output'].includes(frame.name) || (frame.name === 'delta' && !previousLength)
                if (checkpoint || time - lastSave >= 500) { cacheMessages(); lastSave = time }
            },
            onTransportError: transportError
        })
        const token = localStorage.getItem('token')
        const handle = { ...stream, live, sid, token, current, elapsed, ended: () => ended, cancellation: null }
        activeStream = handle
        return stream.done.catch(err => transportError({ code: 'STREAM', message: err.message })).finally(() => {
            if (!current()) return
            live.elapsedMs ??= elapsed()
            if (!ended) {
                if (live.status === 'streaming') live.status = 'interrupted'
                live.resumable = announced && live.status === 'interrupted'
            }
            if (live.status !== 'done') closeOpenSteps(live, live.status)
            activeStream = null; streaming.value = false; cacheMessages()
            loadSessions().catch(() => {})
        }).then(() => live)
    }
    function detachStream() {
        const handle = activeStream
        ++streamVersion
        if (creation) { ++selection; creation = null }
        if (handle) {
            if (!handle.live.finishReason) handle.live.status = 'interrupted'
            handle.cancel(); cacheMessages()
        }
        activeStream = null; streaming.value = false
    }
    function cancelStream() {
        const handle = activeStream
        if (!handle) { detachStream(); return Promise.resolve() }
        if (handle.cancellation) return handle.cancellation
        if (!handle.live.finishReason) handle.live.status = 'stop_requested'
        cacheMessages()
        handle.cancellation = (async () => {
            try {
                const result = agentData(await agentAPI.cancelRun(handle.sid, handle.live.runId, handle.token))
                if (result?.runId !== handle.live.runId || !['cancelled', 'not_active'].includes(result.status)) {
                    throw new Error('Invalid cancellation confirmation')
                }
                if (result.status === 'cancelled' && handle.current() && !handle.ended()) {
                    handle.live.status = 'cancelled'; handle.live.finishReason = 'cancelled'
                    cacheMessages()
                } else if (result.status === 'not_active' && handle.current() && handle.live.status === 'stop_requested') {
                    handle.live.status = 'streaming' // no cancellation proof; await the true footer
                }
                // Allow the actual usage/done footer to drain; confirmation, not reader consumption, releases resources.
                let timer
                await Promise.race([handle.done, new Promise(resolve => { timer = setTimeout(resolve, 1500) })])
                clearTimeout(timer)
                if (handle.current() && activeStream === handle) detachStream()
                await handle.done
            } catch (err) {
                if (handle.current()) {
                    if (handle.live.status === 'stop_requested') handle.live.status = 'streaming'
                    handle.cancellation = null
                    lastError.value = { code: 'CANCEL_FAILED', message: err.message }
                    cacheMessages()
                }
                throw err
            }
        })()
        return handle.cancellation
    }
    function leaveStream() {
        if (activeStream) cancelStream().catch(() => {})
        detachStream()
    }
    function startTurn(sid, user, settings, providerId, prior = null) {
        const runId = uid('run'), retryOfRunId = prior?.runId || null
        const fields = { ...settings, runId, input: user.content, providerId, retryOfRunId, userMessageId: user.id, createdAt: now() }
        const msg = assistant(fields)
        if (prior) messages.value.splice(messages.value.indexOf(prior) + 1, 0, msg)
        else { user.runId = runId; messages.value.push(user, msg) }
        const live = messages.value.find(m => m.id === msg.id)
        const payload = { operationType: AGENT_OP.ASSISTANT_CHAT, input: user.content, runId, providerId: providerId ?? undefined,
            options: settings.options, attachmentIds: settings.attachmentIds, linkedKbId: settings.linkedKbId ?? undefined,
            scopeSelection: settings.scopeSelection, scopeConfirmed: settings.scopeConfirmed, expectedBindingVersion: settings.expectedBindingVersion, retryOfRunId: retryOfRunId ?? undefined }
        return executeStream(sid, live, handlers => streamAgentChat(sid, payload, handlers), prior ? null : user)
    }
    async function sendMessage(text, options = {}) {
        syncOwner()
        const input = (text || '').trim()
        if (!input || streaming.value || switching.value || clearing.has(sessionId.value)) return
        if (input.length > 4000) throw new RangeError('Agent input exceeds 4000 characters')
        const workspace = useAgentWorkspaceStore()
        const firstIntent = !sessionId.value ? workspace.captureFirstScope() : null
        const settings = turnSettings(options), providerId = activeProviderId.value
        const version = ++streamVersion
        streaming.value = true; lastError.value = null
        try {
            const sid = await ensureSession()
            if (!owned() || version !== streamVersion || sid !== sessionId.value) throw changed()
            if (workspace.bindingVersion == null) {
                // Reloading the binding (after a clear or a refused turn) replaces the
                // on-screen selection. What the user confirmed for this turn must be
                // sent as confirmed or not at all — never swapped for the saved binding.
                const mine = !firstIntent && workspace.sessionId === sid
                const chosen = mine ? normalizeScope(workspace.scopeSelection) : null
                const confirmed = mine && workspace.scopeConfirmed
                if (chosen?.targets.length && !confirmed) throw new Error('Confirm the scope before sending')
                await workspace.loadScope(sid)
                if (!owned() || version !== streamVersion || sid !== sessionId.value) throw changed()
                if (confirmed) { workspace.setScope(chosen); workspace.confirmScope() }
            }
            if (!owned() || version !== streamVersion || sid !== sessionId.value) throw changed()
            if (firstIntent) workspace.restoreFirstScope(firstIntent, sid)
            Object.assign(settings, workspace.takeScope())
            return await startTurn(sid, question(input, null, now()), settings, providerId)
        } catch (err) {
            if (owned() && version === streamVersion) { lastError.value = { code: 'SESSION', message: err.message }; streaming.value = false }
            throw err
        }
    }
    async function confirmRunScope(run, sid) {
        const account = owner, version = selection, workspace = useAgentWorkspaceStore()
        if (!run.scopeSelection) throw new Error(i18n.global.t('agentScope.legacy'))
        const original = normalizeScope(run.scopeSelection)
        await workspace.loadScope(sid)
        if (!owned() || owner !== account || version !== selection || sessionId.value !== sid) throw changed()
        // No new selection on retry/resume: the original scope is the maximum permitted range.
        const summary = original.targets.map(target => {
            const resource = [...workspace.scopeResources.contacts, ...workspace.scopeResources.groups].find(item => item.type === target.type && String(item.id) === String(target.id))
            return `${i18n.global.t('agentScope.' + target.type)} · ${resource?.label || '#' + target.id} (ID: ${target.id})`
        }).join('\n') || i18n.global.t('agentScope.none')
        const range = original.timeRange
        const ticket = workspace.scopeTicket()
        // Element Plus returns a promise in the browser; a non-promise here must still be
        // treated as a real user decision rather than awaited into undefined.
        await Promise.resolve(ElMessageBox.confirm(`${summary}\n${i18n.global.t('agentScope.fromLabel')}: ${range.fromInclusive ?? '—'}\n${i18n.global.t('agentScope.toLabel')}: ${range.toExclusive ?? '—'}\n${i18n.global.t('agentScope.zone')}: ${range.timeZone}\n${i18n.global.t('agentScope.version')}: ${workspace.bindingVersion}`, i18n.global.t('agentScope.reauthorize'), {
            confirmButtonText: i18n.global.t('agentScope.confirm'), cancelButtonText: i18n.global.t('common.cancel')
        }))
        if (!owned() || owner !== account || version !== selection || sessionId.value !== sid || !workspace.scopeCurrent(ticket)) throw changed()
        workspace.setScope(original); workspace.confirmScope()
        return workspace.takeScope(true)
    }
    async function retryMessage(messageId) {
        syncOwner()
        const sid = sessionId.value, prior = messages.value.find(m => m.id === messageId && m.role === 'assistant')
        if (!sid || !prior || streaming.value || switching.value || clearing.has(sid)) return
        const adjacent = messages.value[messages.value.indexOf(prior) - 1]
        const user = messages.value.find(m => m.role === 'user' && (m.id === prior.userMessageId || (prior.runId && m.runId === prior.runId)))
            || (adjacent?.role === 'user' && !adjacent.runId ? adjacent : null)
        if (!user) throw new Error('The original question is unavailable')
        const settings = turnSettings({ ...prior.options, attachmentIds: prior.attachmentIds, linkedKbId: prior.linkedKbId, chatId: prior.chatId })
        Object.assign(settings, await confirmRunScope(prior, sid))
        if (streaming.value || switching.value) throw changed()
        return startTurn(sid, user, settings, prior.providerId ?? null, prior)
    }
    async function resumeMessage(messageId) {
        syncOwner()
        const sid = sessionId.value, live = messages.value.find(m => m.id === messageId && m.role === 'assistant')
        if (!sid || !live?.runId || !live.resumable || streaming.value || switching.value || clearing.has(sid)) return
        const authorization = await confirmRunScope(live, sid)
        if (streaming.value || switching.value) throw changed()
        Object.assign(live, authorization)
        return executeStream(sid, live, handlers => streamAgentResume(sid, live.runId, { scopeConfirmed: authorization.scopeConfirmed, expectedBindingVersion: authorization.expectedBindingVersion, providerId: live.providerId ?? undefined }, handlers))
    }

    async function loadSessions() {
        syncOwner()
        const version = ++listVersion
        sessionsLoading.value = true
        try {
            const list = agentData(await agentAPI.listSessions())
            if (version === listVersion && owned()) sessions.value = list
        } catch (err) {
            if (version === listVersion && owned()) lastError.value = { code: 'SESSIONS', message: err.message }
            throw err
        } finally { if (version === listVersion) sessionsLoading.value = false }
    }
    function applyPending(runs = [], finished = [], finishedScopes = {}) {
        const available = new Set(runs.filter(run => !run.active).map(run => run.runId))
        // Runs that ended on the server while this page was not listening: Python still keeps
        // their outcome, so a turn that never saw `done` can fetch it by resuming.
        const kept = new Set(finished)
        for (const msg of messages.value) {
            if (msg.role !== 'assistant' || !msg.runId) continue
            if (msg.scopeState === 'unavailable') { msg.resumable = false; continue }
            const receipt = runs.find(run => run.runId === msg.runId)?.scope ?? finishedScopes[msg.runId]
            if (receipt !== undefined) observeScope(msg, receipt, { actorUserId: owner, sessionId: sessionId.value, runId: msg.runId })
            const pending = available.delete(msg.runId)
            msg.resumable = (pending && msg.status !== 'done') || (!msg.finishReason && kept.has(msg.runId))
        }
        for (const runId of available) {
            const msg = assistant({ runId, resumable: true })
            observeScope(msg, runs.find(run => run.runId === runId)?.scope, { actorUserId: owner, sessionId: sessionId.value, runId })
            messages.value.push(msg)
        }
    }
    function isHistoryCurrent(sid, version) {
        return owned() && historyVersion === version && sid === sessionId.value && !streaming.value && !clearing.has(sid)
    }
    async function loadPendingRuns(sid = sessionId.value, version = historyVersion) {
        if (!sid || !isHistoryCurrent(sid, version)) return
        try {
            const data = agentData(await agentAPI.pendingRuns(sid))
            if (isHistoryCurrent(sid, version)) { applyPending(data.runs, data.finished, data.finishedScopes); cacheMessages() }
        } catch (err) {
            // Checkpoints come from Python; an outage must not erase Java's durable transcript or a cached resume handle.
            if (isHistoryCurrent(sid, version)) {
                if (scopeFailure(err)) for (const msg of messages.value) msg.scopeState = 'unknown'
                lastError.value = { code: 'PENDING_RUNS', message: err.message }
            }
        }
    }
    async function refreshActiveSession() {
        syncOwner()
        const sid = sessionId.value, version = ++historyVersion
        if (!sid || streaming.value || clearing.has(sid)) return
        try {
            let data
            try { data = agentData(await agentAPI.listRuns(sid)) }
            catch (err) {
                // Older Java deployments have only the authenticated /messages route. Never fall back on auth/network failures.
                if (err.response?.status !== 404) throw err
                data = []
            }
            if (!isHistoryCurrent(sid, version)) return
            const runs = Array.isArray(data) ? data : data?.runs
            if (!Array.isArray(runs)) throw new Error('Invalid run history')
            if (runs.length) messages.value = reconcile(messages.value, runs, { actorUserId: owner, sessionId: sid })
            else {
                const legacy = agentData(await agentAPI.sessionMessages(sid)).messages
                if (!isHistoryCurrent(sid, version)) return
                // A Redis transcript is a role/content snapshot with no turn identity. Replace only an imported
                // legacy snapshot (not a rich or longer cache); never attach previous traces by matching text.
                if (legacy?.length > messages.value.length && messages.value.every(m => m.legacySnapshot)) {
                    messages.value = legacy.map(m => ({ ...(m.role === 'assistant'
                        ? assistant({ content: m.content, status: 'done' }) : { ...question(m.content, null, null), role: m.role || 'user' }),
                        legacySnapshot: true }))
                }
            }
            cacheMessages()
            await loadPendingRuns(sid, version)
        } catch (err) {
            if (!isHistoryCurrent(sid, version)) return
            if (scopeFailure(err)) {
                for (const msg of messages.value) msg.scopeState = 'unknown'
                const workspace = useAgentWorkspaceStore(); workspace.invalidateScope(); workspace.scopeError = err.message
            }
            lastError.value = { code: 'HISTORY', message: err.message }
            throw err
        }
    }
    async function switchSession(sid) {
        syncOwner()
        if (!sid || sid === sessionId.value) return
        leaveStream(); cacheMessages()
        const version = ++selection
        switching.value = true; switchingSessionId.value = sid; lastError.value = null
        selectSession(sid)
        try { await refreshActiveSession() }
        finally { if (selection === version) { switching.value = false; switchingSessionId.value = null } }
    }
    const hydrateActiveSession = refreshActiveSession

    async function clearConversation() {
        syncOwner()
        const sid = sessionId.value, version = selection, account = owner
        if (clearing.has(sid)) return clearing.get(sid)
        const pending = cancelStream()
        useAgentWorkspaceStore().invalidateScope()
        ++historyVersion
        const done = (async () => {
            try {
                await pending
                if (!owned() || owner !== account) throw changed()
                if (sid) { agentData(await agentAPI.clearSessionMemory(sid)); if (owned() && owner === account) forget(sid) }
                if (owned() && owner === account && sessionId.value === sid) messages.value = []
            } catch (err) {
                if (owned() && selection === version) lastError.value = { code: 'CLEAR_FAILED', message: err.message }
                throw err
            } finally { if (clearing.get(sid) === done) clearing.delete(sid) }
        })()
        clearing.set(sid, done)
        return done
    }
    async function resetAll() {
        const version = selection
        await clearConversation()
        if (owned() && version === selection) { ++selection; selectSession(null) }
    }
    async function renameSession(sid, title) {
        syncOwner(); const account = owner
        agentData(await agentAPI.renameSession(sid, title))
        if (owned() && owner === account) await loadSessions()
    }
    async function deleteSession(sid) {
        syncOwner(); const account = owner
        agentData(await agentAPI.deleteSession(sid))
        if (!owned() || owner !== account) return
        if (sid === sessionId.value) {
            leaveStream(); ++selection; ++historyVersion; switching.value = false; switchingSessionId.value = null
            selectSession(null)
        }
        forget(sid); await loadSessions()
    }
    return { sessionId, activeProviderId, messages, streaming, lastError, switching, switchingSessionId, sessions, sessionsLoading,
        messagesBySessionId, activeSession, hasMessages, ensureSession, sendMessage, retryMessage, resumeMessage, loadPendingRuns,
        cancelStream, clearConversation, resetAll, setActiveProviderId, loadSessions, switchSession, hydrateActiveSession,
        refreshActiveSession, newSession, renameSession, deleteSession }
})

/** Static descriptor for the virtual AI-assistant chat list entry. */
export function buildAiAssistantChatItem(t = i18n.global.t) {
    return {
        id: AI_ASSISTANT_CHAT_ID,
        type: 'AI',
        name: t('agent.title'),
        avatar: '',
        lastMessage: t('agent.lastMessageHint'),
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

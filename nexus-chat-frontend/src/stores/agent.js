import { defineStore } from 'pinia'
import { ref, computed, watch, onScopeDispose } from 'vue'
import { agentAPI, agentData, agentOwnerId, streamAgentChat, streamAgentResume, AGENT_OP } from '@/services/agentApi'
import { useUserStore } from '@/stores/user'
import { retryAtOf } from '@/stores/agentProviders'
import i18n from '@/locales/i18n'

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
    if (['length', 'max_tokens', 'max_output_tokens', 'max_iterations', 'truncated'].includes(reason)) return 'truncated'
    if (reason === 'canceled') return 'cancelled'
    if (['cancelled', 'interrupted', 'timeout'].includes(reason)) return reason
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
function eventReducer(msg) {
    const seen = new Set(), blocks = new Map()
    let acceptedRunId = null, attemptId = null, traceStart = msg.trace.length, callsStart = msg.toolCalls.length
    return ({ name, data, id }, fallbackAt) => {
        if (!data || typeof data !== 'object') return false
        if (data.runId && acceptedRunId && data.runId !== acceptedRunId) return false
        if (data.runId) acceptedRunId = msg.runId = data.runId // older gateways assign the ID themselves
        if (name === 'meta') {
            const attempt = data.attemptId ?? data.traceId
            if (data.resumed && (attempt !== attemptId || !seen.has(data.sequence))) {
                seen.clear(); blocks.clear(); traceStart = msg.trace.length; callsStart = msg.toolCalls.length
            }
            attemptId = attempt
        }
        if (Number.isInteger(data.sequence)) {
            if (seen.has(data.sequence)) return false
            seen.add(data.sequence)
        }
        const line = { ...data, ...(id != null && { id }), runId: msg.runId, at: data.at ?? fallbackAt }
        const step = (kind, fields) => msg.trace.push({ ...line, ...fields, kind })
        if (name === 'meta') {
            if (data.resumed) { msg.content = ''; msg.error = null; msg.retryAt = null; msg.finishReason = null; msg.status = 'streaming' }
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
            msg.usage = { ...data }; step(name)
        } else if (name === 'error') {
            step(name)
            const roleFailure = !data.callId && (!data.agentId || ['assistant', 'synth'].includes(data.agentId))
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

function restoredRun(run, cached) {
    const msg = assistant({ ...cached, runId: run.runId, input: run.input ?? cached?.input,
        providerId: run.providerRef?.providerId ?? run.providerId ?? cached?.providerId ?? null,
        options: run.options ?? cached?.options ?? null, linkedKbId: run.linkedKbId ?? cached?.linkedKbId ?? null,
        attachmentIds: run.attachmentIds ?? run.attachmentRefs?.map(a => a.id) ?? cached?.attachmentIds ?? [],
        createdAt: run.startedAt ?? cached?.createdAt ?? null, retryOfRunId: run.retryOfRunId ?? cached?.retryOfRunId ?? null })
    if (run.trace?.length) {
        Object.assign(msg, { trace: [], toolCalls: [], content: '', finishReason: null, error: null, usage: null, artifacts: [], sources: [] })
        const accept = eventReducer(msg)
        for (const frame of run.trace) accept(frame)
        if (cached?.trace?.length) {
            msg.trace = mergeTrace(cached.trace, msg.trace)
            msg.toolCalls = msg.trace.filter(e => e.kind === 'tool').map(e => ({ ...e }))
            upsertById(msg.sources, cached.sources?.filter(item => !msg.sources.some(s => s.id === item.id)))
            upsertById(msg.artifacts, cached.artifacts?.filter(item => !msg.artifacts.some(a => a.id === item.id)))
        }
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
    if (msg.error && msg.status === 'done') msg.status = msg.error.code === 'STREAM_INCOMPLETE' ? 'interrupted' : 'error'
    msg.usage = run.usage ?? msg.usage
    msg.elapsedMs = run.elapsedMs ?? msg.elapsedMs
    msg.resumable = msg.status !== 'done' && (run.resumable ?? msg.resumable)
    upsertById(msg.artifacts, run.artifacts); upsertById(msg.sources, run.sources)
    if (msg.status !== 'done') closeOpenSteps(msg, msg.status)
    return msg
}

function reconcile(cached, runs) {
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
        const prior = byRun.get(run.runId), msg = restoredRun(run, prior)
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
        write('sessionId', sid)
        messages.value = messagesBySessionId.value[sid]?.slice() || []
    }
    function syncOwner() {
        const next = agentOwnerId()
        if (next === owner) return
        ++selection; ++historyVersion; ++listVersion
        cancelStream()
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
    watch(() => [auth.currentUser?.id, auth.token], syncOwner)
    globalThis.window?.addEventListener('storage', syncOwner)
    onScopeDispose(() => { globalThis.window?.removeEventListener('storage', syncOwner); cancelStream() })

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
        syncOwner(); cancelStream(); cacheMessages()
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
        const version = ++streamVersion, started = performance.now(), reduce = eventReducer(live)
        const current = () => version === streamVersion && owned() && sessionId.value === sid
        const elapsed = () => Math.round(performance.now() - started)
        let ended = false, announced = false, lastSave = 0
        streaming.value = true; ++historyVersion
        live.status = 'streaming'; live.error = null; live.retryAt = null; live.finishReason = null; live.elapsedMs = null
        lastError.value = null
        cacheMessages()
        const transportError = error => {
            if (!current()) return
            live.error = lastError.value = error
            live.status = error.code === 'STREAM_INCOMPLETE' ? 'interrupted' : 'error'
            live.trace.push({ kind: 'error', transport: true, ...error, runId: live.runId, at: elapsed() })
        }
        const stream = start({
            onEvent: frame => {
                if (!current()) return
                const time = elapsed()
                const previousLength = live.content.length
                if (!reduce(frame, time)) return
                if (frame.name === 'meta') { announced = true; if (user) user.runId = live.runId }
                if (frame.name === 'done') ended = live.status !== 'streaming'
                if (live.error) lastError.value = live.error
                const checkpoint = !['delta', 'reasoning', 'agent_output'].includes(frame.name) || (frame.name === 'delta' && !previousLength)
                if (checkpoint || time - lastSave >= 500) { cacheMessages(); lastSave = time }
            },
            onTransportError: transportError
        })
        activeStream = { ...stream, stop() {
            if (!ended && live.status === 'streaming') {
                live.status = 'cancelled'; live.finishReason = 'cancelled'; live.resumable = announced
                live.trace.push({ kind: 'done', runId: live.runId, finishReason: 'cancelled', at: elapsed(), local: true })
            }
            live.elapsedMs ??= elapsed()
            closeOpenSteps(live, live.status); cacheMessages(); stream.cancel()
        } }
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
    function cancelStream() {
        ++streamVersion
        if (creation) { ++selection; creation = null }
        const pending = activeStream?.done
        activeStream?.stop(); activeStream = null; streaming.value = false
        return pending
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
            chatContext: settings.chatId ? { chatId: settings.chatId } : undefined, retryOfRunId: retryOfRunId ?? undefined }
        return executeStream(sid, live, handlers => streamAgentChat(sid, payload, handlers), prior ? null : user)
    }
    async function sendMessage(text, options = {}) {
        syncOwner()
        const input = (text || '').trim()
        if (!input || streaming.value || switching.value || clearing.has(sessionId.value)) return
        if (input.length > 4000) throw new RangeError('Agent input exceeds 4000 characters')
        const settings = turnSettings(options), providerId = activeProviderId.value
        const version = ++streamVersion
        streaming.value = true; lastError.value = null
        try {
            const sid = await ensureSession()
            if (!owned() || version !== streamVersion || sid !== sessionId.value) throw changed()
            return await startTurn(sid, question(input, null, now()), settings, providerId)
        } catch (err) {
            if (owned() && version === streamVersion) { lastError.value = { code: 'SESSION', message: err.message }; streaming.value = false }
            throw err
        }
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
        return startTurn(sid, user, settings, prior.providerId ?? null, prior)
    }
    async function resumeMessage(messageId) {
        syncOwner()
        const sid = sessionId.value, live = messages.value.find(m => m.id === messageId && m.role === 'assistant')
        if (!sid || !live?.runId || !live.resumable || streaming.value || switching.value || clearing.has(sid)) return
        return executeStream(sid, live, handlers => streamAgentResume(sid, live.runId, { providerId: live.providerId ?? undefined }, handlers))
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
    function applyPending(runs, finished = []) {
        const available = new Set(runs.filter(run => !run.active).map(run => run.runId))
        // Runs that ended on the server while this page was not listening: Python still keeps
        // their outcome, so a turn that never saw `done` can fetch it by resuming.
        const kept = new Set(finished)
        for (const msg of messages.value) {
            if (msg.role !== 'assistant' || !msg.runId) continue
            const pending = available.delete(msg.runId)
            msg.resumable = (pending && msg.status !== 'done') || (!msg.finishReason && kept.has(msg.runId))
        }
        for (const runId of available) messages.value.push(assistant({ runId, resumable: true }))
    }
    function isHistoryCurrent(sid, version) {
        return owned() && historyVersion === version && sid === sessionId.value && !streaming.value && !clearing.has(sid)
    }
    async function loadPendingRuns(sid = sessionId.value, version = historyVersion) {
        if (!sid || !isHistoryCurrent(sid, version)) return
        try {
            const data = agentData(await agentAPI.pendingRuns(sid))
            if (isHistoryCurrent(sid, version)) { applyPending(data.runs, data.finished); cacheMessages() }
        } catch (err) {
            // Checkpoints come from Python; an outage must not erase Java's durable transcript or a cached resume handle.
            if (isHistoryCurrent(sid, version)) lastError.value = { code: 'PENDING_RUNS', message: err.message }
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
            if (runs.length) messages.value = reconcile(messages.value, runs)
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
            lastError.value = { code: 'HISTORY', message: err.message }
            throw err
        }
    }
    async function switchSession(sid) {
        syncOwner()
        if (!sid || sid === sessionId.value) return
        cancelStream(); cacheMessages()
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
            cancelStream(); ++selection; ++historyVersion; switching.value = false; switchingSessionId.value = null
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

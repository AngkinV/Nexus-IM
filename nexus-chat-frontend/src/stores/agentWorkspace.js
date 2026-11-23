import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { useUserStore } from './user'
import { emptyScope, normalizeScope, scopeRequest } from '@/components/agent/scopeSelection.mjs'
import { agentAPI, agentData, agentOwnerId } from '@/services/agentApi'

function items(data, field) {
    const list = Array.isArray(data) ? data : data?.[field]
    if (!Array.isArray(list)) throw new Error(`Missing workspace ${field}`)
    return list
}

function upsert(list, item) {
    if (!item?.id) throw new Error('Missing workspace record ID')
    const index = list.findIndex(existing => existing.id === item.id)
    if (index === -1) list.push(item)
    else list[index] = item
}

export const useAgentWorkspaceStore = defineStore('agentWorkspace', () => {
    const sessionId = ref(null), attachments = ref([]), tasks = ref([])
    const scopeSelection = ref(emptyScope()), scopeConfirmed = ref(false), bindingVersion = ref(null), sessionGeneration = ref(null)
    const scopeResources = ref({ contacts: [], groups: [] }), scopeError = ref(''), scopeLoading = ref(false)
    let scopeRead = 0, scopeEdit = 0, firstIntent = null
    function scopeTicket() { return { owner: agentOwnerId(), session: sessionId.value, edit: scopeEdit } }
    function scopeCurrent(ticket) { return ticket.owner === agentOwnerId() && ticket.session === sessionId.value && ticket.edit === scopeEdit }
    // A first-send intent belongs to the selection the user confirmed before any
    // session existed. It is not tied to a stale workspace session pointer: the
    // caller is about to create the session this intent will apply to.
    function captureFirstScope() {
        if (owner !== agentOwnerId()) throw new Error('The workspace has changed')
        const selection = normalizeScope(scopeSelection.value)
        if (selection.targets.length && !scopeConfirmed.value) throw new Error('Confirm the scope before sending')
        firstIntent = { owner: agentOwnerId(), edit: scopeEdit, selection, confirmed: scopeConfirmed.value }
        return firstIntent
    }
    function restoreFirstScope(intent, sid) {
        if (firstIntent !== intent || intent.owner !== agentOwnerId() || !sid || sessionId.value !== sid || intent.edit !== scopeEdit) throw new Error('The workspace has changed')
        setScope(intent.selection); scopeConfirmed.value = intent.confirmed; firstIntent = null
    }
    watch(scopeSelection, () => { scopeConfirmed.value = false; ++scopeEdit }, { deep: true, flush: 'sync' })
    function invalidateScope() { scopeConfirmed.value = false; bindingVersion.value = null }
    function setScope(value) { scopeSelection.value = normalizeScope(value); scopeConfirmed.value = false }
    function confirmScope() { scopeSelection.value = normalizeScope(scopeSelection.value); scopeConfirmed.value = true }
    function takeScope(fresh = false) {
        if (fresh && !scopeConfirmed.value) throw new Error('Confirm this new authorization request')
        const request = scopeRequest(scopeSelection.value, scopeConfirmed.value, bindingVersion.value)
        scopeConfirmed.value = false
        return request
    }
    async function loadScope(sid) {
        select(sid || null)
        const version = generation, read = ++scopeRead, edit = scopeEdit
        scopeLoading.value = true; scopeError.value = ''; invalidateScope()
        try {
            const resources = agentData(await agentAPI.scopeResources())
            if (!current(version) || read !== scopeRead) return
            if (!Array.isArray(resources?.contacts) || !Array.isArray(resources?.groups)) throw new Error('Invalid scope resources')
            scopeResources.value = resources
            if (!sid) return
            const binding = agentData(await agentAPI.getScope(sid))
            if (!current(version) || read !== scopeRead || edit !== scopeEdit) return
            if (!Number.isInteger(binding.bindingVersion) || binding.bindingVersion < 1) throw new Error('Load the current binding version')
            const intent = firstIntent?.session === sid && firstIntent.edit === scopeEdit ? firstIntent : null
            setScope(binding.selection); bindingVersion.value = binding.bindingVersion; sessionGeneration.value = binding.sessionGeneration
            if (intent) intent.edit = scopeEdit
        } catch (error) {
            if (current(version) && read === scopeRead && edit === scopeEdit) { invalidateScope(); scopeError.value = error?.response?.data?.message || error.message }
            throw error
        } finally { if (current(version) && read === scopeRead) scopeLoading.value = false }
    }
    async function saveScope(sid) {
        if (!sid || sessionId.value !== sid || owner !== agentOwnerId()) throw new Error('The workspace has changed')
        const request = takeScope(true), version = generation, read = ++scopeRead, edit = scopeEdit
        try {
            const binding = agentData(await agentAPI.putScope(sid, request.expectedBindingVersion, request.scopeSelection))
            if (!current(version) || read !== scopeRead || edit !== scopeEdit) throw new Error('The workspace has changed')
            if (!Number.isInteger(binding.bindingVersion) || binding.bindingVersion < 1) throw new Error('Load the current binding version')
            const intent = firstIntent?.session === sid && firstIntent.edit === scopeEdit ? firstIntent : null
            setScope(binding.selection); bindingVersion.value = binding.bindingVersion; sessionGeneration.value = binding.sessionGeneration
            if (intent) intent.edit = scopeEdit
        } catch (error) {
            if (current(version) && read === scopeRead && edit === scopeEdit) { invalidateScope(); scopeError.value = error?.response?.data?.message || error.message }
            throw error
        }
    }
    let owner = agentOwnerId(), generation = 0, readVersion = 0, writeVersion = 0

    function select(sid) {
        const nextOwner = agentOwnerId()
        if (owner !== nextOwner || sessionId.value !== sid) {
            const intent = firstIntent && firstIntent.owner === nextOwner && firstIntent.session == null && firstIntent.edit === scopeEdit ? firstIntent : null
            ++generation
            owner = nextOwner
            sessionId.value = sid
            attachments.value = []
            tasks.value = []
            ++scopeRead; invalidateScope(); scopeSelection.value = emptyScope(); sessionGeneration.value = null
            scopeResources.value = { contacts: [], groups: [] }; scopeError.value = ''; scopeLoading.value = false
            firstIntent = intent
            if (intent) { intent.session = sid; intent.edit = scopeEdit }
        }
    }
    const current = version => version === generation && owner === agentOwnerId()

    async function load(sid) {
        select(sid || null)
        if (!sid) return
        const version = generation, read = ++readVersion
        for (;;) {
            const writes = writeVersion
            const data = agentData(await agentAPI.listAttachments(sid))
            if (!current(version) || read !== readVersion) return
            const files = items(data, 'attachments')
            // The combined workspace endpoint needs only one read. A split list uses the owned task endpoint.
            const savedTasks = Array.isArray(data?.tasks) ? data.tasks : items(agentData(await agentAPI.listTasks(sid)), 'tasks')
            if (!current(version) || read !== readVersion) return
            // A write committed while this list was in flight: the list predates it. Read again
            // rather than drop the list (losing what was there before) or apply it (losing the write).
            if (writes !== writeVersion) continue
            attachments.value = files
            tasks.value = savedTasks
            return { attachments: files, tasks: savedTasks }
        }
    }

    async function operation(sid, request, commit) {
        if (!sid) throw new Error('A session is required')
        if (owner !== agentOwnerId() || !sessionId.value) select(sid)
        if (sessionId.value !== sid) throw new Error('The workspace has changed')
        const version = generation
        const data = agentData(await request())
        if (!current(version)) throw new Error('The workspace has changed')
        if (commit) { commit(data); ++writeVersion }
        return data
    }
    const saveFile = data => upsert(attachments.value, data)
    const upload = (sid, file) => operation(sid, () => agentAPI.uploadAttachment(sid, file), saveFile)
    const removeAttachment = (sid, id) => operation(sid, () => agentAPI.removeAttachment(sid, id), () => {
        attachments.value = attachments.value.filter(item => item.id !== id)
    })
    const previewPatch = (sid, id, baseId) => operation(sid, () => agentAPI.previewPatch(sid, id, baseId))
    const applyPatch = (sid, id, preview) => operation(sid, () => {
        if (!preview?.baseAttachmentId || !preview.baseHash || !preview.patchHash) throw new Error('A patch preview is required')
        return agentAPI.applyPatch(sid, id, preview)
    }, saveFile)
    const createTasks = (sid, id) => operation(sid, () => agentAPI.createTasks(sid, id), data => {
        for (const task of items(data, 'tasks')) upsert(tasks.value, task)
    })
    const updateTask = (sid, id, status) => operation(sid, () => agentAPI.updateTask(sid, id, status), data => upsert(tasks.value, data))
    const saveArtifact = (sid, id) => operation(sid, () => agentAPI.saveArtifact(sid, id), saveFile)

    const auth = useUserStore()
    watch(() => [auth.currentUser?.id, auth.token], () => { ++generation; owner = agentOwnerId(); ++scopeRead; invalidateScope(); scopeSelection.value = emptyScope(); scopeResources.value = { contacts: [], groups: [] }; sessionGeneration.value = null; scopeError.value = ''; scopeLoading.value = false }, { flush: 'sync' })
    // Independent user-level branch. Session selection never resets MCP state.
    const mcp = ref({ owner: agentOwnerId(), servers: [], catalogs: {}, error: null, loading: false })
    let mcpGeneration = 0, mcpReadVersion = 0, mcpWriteVersion = 0
    const mcpRequests = new Map()
    const mcpError = code => Object.assign(new Error('MCP management request failed'), { code })
    function syncMcpOwner() {
        const next = agentOwnerId()
        if (mcp.value.owner !== next) {
            ++mcpGeneration; ++mcpReadVersion; ++mcpWriteVersion; mcpRequests.clear()
            mcp.value = { owner: next, servers: [], catalogs: {}, error: null, loading: false }
        }
        return next
    }
    function mcpTicket(key) {
        if (!syncMcpOwner()) throw mcpError('MCP_SIGN_IN')
        const serial = (mcpRequests.get(key) || 0) + 1
        mcpRequests.set(key, serial)
        return { owner: mcp.value.owner, generation: mcpGeneration, writes: mcpWriteVersion, key, serial }
    }
    function mcpCurrent(ticket) {
        syncMcpOwner()
        return ticket.owner === mcp.value.owner && ticket.generation === mcpGeneration && mcpRequests.get(ticket.key) === ticket.serial
    }
    function safeMcpFailure(error, ticket) {
        // Never retain a raw backend error/message/payload (may include credentials).
        const code = error?.response?.status === 409 || /CONFLICT|VERSION/.test(error?.code || error?.response?.data?.code || '')
            ? 'MCP_CONFLICT' : error?.code === 'MCP_STALE' ? 'MCP_STALE' : error?.code === 'MCP_PROBE_FAILED' ? 'MCP_PROBE_FAILED' : 'MCP_UNAVAILABLE'
        if (mcpCurrent(ticket)) mcp.value.error = code
        return mcpError(code)
    }
    function mcpServer(id) { return mcp.value.servers.find(server => server.serverId === id) }
    function publicMcpServer(data) {
        if (!data?.serverId || String(data.ownerUserId) !== mcp.value.owner || !Number.isInteger(data.configVersion) || data.configVersion < 1) throw mcpError('MCP_INVALID')
        // Allowlist public fields; secrets never enter reactive state.
        const keys = ['serverId', 'ownerUserId', 'namespace', 'displayName', 'transport', 'url', 'enabled', 'configVersion', 'authorizationVersion', 'catalogVersion', 'timeoutSec', 'resultMaxChars', 'allowedTools', 'credentialConfigured', 'credentialKind', 'credentialHeaderName', 'registrationState', 'createdAt', 'updatedAt']
        const server = Object.fromEntries(keys.map(key => [key, data[key]]))
        server.allowedTools = Array.isArray(data.allowedTools) ? [...data.allowedTools] : []
        server.registrationState = data.registrationState === 'registered' ? 'registered' : 'pending'
        server.status = publicMcpStatus(data.status, data.configVersion)
        return server
    }
    function publicMcpStatus(status, configVersion) {
        const valid = status?.configVersion === configVersion
        const state = valid && ['unknown', 'connected', 'error'].includes(status.state) ? status.state : 'unknown'
        // A timestamp must be real ISO evidence, not just a truthy value.
        const at = valid && typeof status.checkedAt === 'string' && /(?:Z|[+-]\d{2}:\d{2})$/.test(status.checkedAt) && Number.isFinite(Date.parse(status.checkedAt)) ? status.checkedAt : null
        const verified = state === 'unknown' || at !== null
        return { state: verified ? state : 'unknown', configVersion, checkedAt: verified ? at : null,
            latencyMs: valid && Number.isSafeInteger(status.latencyMs) && status.latencyMs >= 0 ? status.latencyMs : null,
            error: state === 'error' && verified ? { code: 'MCP_PROBE_FAILED' } : null }
    }
    function commitMcpServer(data) {
        const server = publicMcpServer(data), old = mcpServer(server.serverId)
        if (old && old.configVersion > server.configVersion) throw mcpError('MCP_STALE')
        const index = mcp.value.servers.findIndex(item => item.serverId === server.serverId)
        if (index < 0) mcp.value.servers.push(server)
        else mcp.value.servers[index] = server
        if (!old || old.configVersion !== server.configVersion || old.catalogVersion !== server.catalogVersion) delete mcp.value.catalogs[server.serverId]
        ++mcpWriteVersion
        return server
    }
    async function loadMcpServers() {
        const ticket = mcpTicket('list'), read = ++mcpReadVersion
        mcp.value.loading = true; mcp.value.error = null
        try {
            for (;;) {
                const writes = mcpWriteVersion
                const data = agentData(await agentAPI.listMcpServers())
                if (!mcpCurrent(ticket) || read !== mcpReadVersion) return
                if (writes !== mcpWriteVersion) continue
                const servers = items(data, 'servers').map(publicMcpServer)
                if (servers.some(server => (mcpServer(server.serverId)?.configVersion || 0) > server.configVersion)) throw mcpError('MCP_STALE')
                mcp.value.servers = servers
                for (const id of Object.keys(mcp.value.catalogs)) {
                    const server = mcpServer(id), catalog = mcp.value.catalogs[id]
                    if (!server || server.configVersion !== catalog.configVersion || server.catalogVersion !== catalog.catalogVersion) delete mcp.value.catalogs[id]
                }
                return servers
            }
        } catch (error) { throw safeMcpFailure(error, ticket) }
        finally { if (mcpCurrent(ticket) && read === mcpReadVersion) mcp.value.loading = false }
    }
    async function mcpOperation(id, version, request, commit) {
        const ticket = mcpTicket(id || 'create')
        if (id && mcpServer(id)?.configVersion !== version) throw mcpError('MCP_CONFLICT')
        mcp.value.error = null
        try {
            const data = agentData(await request())
            if (!mcpCurrent(ticket) || (id && mcpServer(id)?.configVersion !== version)) throw mcpError('MCP_STALE')
            return commit(data)
        } catch (error) { throw safeMcpFailure(error, ticket) }
    }
    const createMcpServer = payload => mcpOperation(null, null, () => agentAPI.createMcpServer(payload), commitMcpServer)
    const patchMcpServer = (id, version, payload) => mcpOperation(id, version,
        () => agentAPI.patchMcpServer(id, { ...payload, expectedConfigVersion: version }), commitMcpServer)
    const deleteMcpServer = (id, version) => mcpOperation(id, version, () => agentAPI.deleteMcpServer(id, version), data => {
        if (data?.deleted !== true || data.serverId !== id || data.configVersion !== version + 1) throw mcpError('MCP_INVALID')
        mcp.value.servers = mcp.value.servers.filter(server => server.serverId !== id)
        delete mcp.value.catalogs[id]; ++mcpWriteVersion
        return data
    })
    function commitMcpCatalog(id, version, data) {
        if (data?.serverId !== id || data.configVersion !== version || !Array.isArray(data.tools)) throw mcpError('MCP_INVALID')
        const tools = data.tools.map(tool => {
            if (tool.serverId !== id || !tool.toolId || !tool.inputSchema || typeof tool.inputSchema !== 'object' || Array.isArray(tool.inputSchema)) throw mcpError('MCP_INVALID')
            return Object.fromEntries(['toolId', 'serverId', 'name', 'remoteName', 'description', 'inputSchema', 'risk', 'readOnlyHint', 'timeoutSec', 'resultMaxChars'].map(key => [key, tool[key]]))
        })
        mcp.value.catalogs[id] = { serverId: id, configVersion: version, catalogVersion: data.catalogVersion, tools }
        mcpServer(id).catalogVersion = data.catalogVersion
        ++mcpWriteVersion
        return mcp.value.catalogs[id]
    }
    const loadMcpTools = (id, version) => mcpOperation(id, version, () => agentAPI.mcpServerTools(id), data => commitMcpCatalog(id, version, data))
    const loadMcpStatus = (id, version) => mcpOperation(id, version, () => agentAPI.mcpServerStatus(id), data => {
        if (data?.serverId !== id || data.configVersion !== version) throw mcpError('MCP_INVALID')
        const server = mcpServer(id)
        server.status = publicMcpStatus(data.status, version)
        server.registrationState = data.registrationState === 'registered' ? 'registered' : 'pending'
        ++mcpWriteVersion
        return data
    })
    function probeMcpServer(id, version, refresh = false) {
        return mcpOperation(id, version, () => refresh ? agentAPI.refreshMcpServerTools(id, version) : agentAPI.testMcpServer(id, version), data => {
            if (data?.serverId !== id || data.configVersion !== version) throw mcpError('MCP_INVALID')
            // Validate catalog before changing the previous probe. No synthetic tools.
            if (Array.isArray(data.tools)) commitMcpCatalog(id, version, data)
            mcpServer(id).status = publicMcpStatus(data.status, version)
            ++mcpWriteVersion
            if (data.ok !== true || mcpServer(id).status.state !== 'connected') {
                mcp.value.error = 'MCP_PROBE_FAILED'
                throw mcpError('MCP_PROBE_FAILED')
            }
            return { serverId: id, configVersion: version, ok: true, status: mcpServer(id).status, ...mcp.value.catalogs[id] }
        })
    }

    return { mcp, syncMcpOwner, loadMcpServers, createMcpServer, patchMcpServer, deleteMcpServer, loadMcpTools, loadMcpStatus, probeMcpServer,
        scopeTicket, scopeCurrent, captureFirstScope, restoreFirstScope, scopeSelection, scopeConfirmed, bindingVersion, sessionGeneration, scopeResources, scopeError, scopeLoading, select, setScope, confirmScope, takeScope, loadScope, saveScope, invalidateScope, sessionId, attachments, tasks, load, upload, removeAttachment, previewPatch, applyPatch, createTasks, updateTask, saveArtifact }
})

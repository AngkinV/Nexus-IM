import { defineStore } from 'pinia'
import { ref } from 'vue'
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
    let owner = agentOwnerId(), generation = 0, readVersion = 0, writeVersion = 0

    function select(sid) {
        const nextOwner = agentOwnerId()
        if (owner !== nextOwner || sessionId.value !== sid) {
            ++generation
            owner = nextOwner
            sessionId.value = sid
            attachments.value = []
            tasks.value = []
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

    return { sessionId, attachments, tasks, load, upload, removeAttachment, previewPatch, applyPatch, createTasks, updateTask, saveArtifact }
})

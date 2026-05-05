import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { knowledgeAPI } from '@/services/knowledgeApi'

/**
 * Knowledge-base state for the Agent module.
 *
 * State shape:
 *   kbs              : KbView[] (current user's knowledge bases, sorted by updated_at desc)
 *   documentsByKb    : { [kbId]: DocumentView[] }
 *   activeKbId       : string | null — the KB the chat path will be linked to (Day 14)
 *   pollersByDocId   : { [docId]: number } — internal, the timer id for in-flight status polls
 *
 * The store does not eagerly fetch on first import; callers (Manager dialog,
 * chat header selector) call fetchKbs() when they open. That keeps the
 * agent module out of the cold-start critical path.
 */
export const useKnowledgeStore = defineStore('knowledge', () => {
    const kbs = ref([])
    const documentsByKb = ref({})
    const loading = ref(false)
    const lastError = ref(null)
    const activeKbId = ref(null)

    // Internal: per-document polling timers so a freshly-uploaded doc can
    // refresh its status until it leaves the PROCESSING state.
    const pollersByDocId = new Map()

    const activeKb = computed(() =>
        activeKbId.value ? (kbs.value.find(k => k.kbId === activeKbId.value) || null) : null
    )

    function setActiveKb(kbId) {
        activeKbId.value = kbId || null
    }

    async function fetchKbs() {
        loading.value = true
        lastError.value = null
        try {
            const resp = await knowledgeAPI.listKbs()
            kbs.value = resp.data?.data?.items || []
        } catch (err) {
            lastError.value = err
            kbs.value = []
        } finally {
            loading.value = false
        }
    }

    async function createKb(payload) {
        const resp = await knowledgeAPI.createKb(payload)
        await fetchKbs()
        return resp.data?.data
    }

    async function updateKb(kbId, payload) {
        await knowledgeAPI.updateKb(kbId, payload)
        await fetchKbs()
    }

    async function removeKb(kbId) {
        await knowledgeAPI.deleteKb(kbId)
        delete documentsByKb.value[kbId]
        if (activeKbId.value === kbId) activeKbId.value = null
        await fetchKbs()
    }

    async function fetchDocuments(kbId) {
        const resp = await knowledgeAPI.listDocuments(kbId)
        documentsByKb.value = {
            ...documentsByKb.value,
            [kbId]: resp.data?.data?.items || []
        }
        // Auto-resume polling for any docs still mid-ingest after a refresh
        // (e.g. user closed the dialog with a long PDF still processing).
        for (const d of documentsByKb.value[kbId]) {
            if (d.status === 'PENDING' || d.status === 'PROCESSING') {
                schedulePoll(kbId, d.docId)
            }
        }
        return documentsByKb.value[kbId]
    }

    async function uploadDocument(kbId, file, onProgress) {
        const resp = await knowledgeAPI.uploadDocument(kbId, file, onProgress)
        await fetchDocuments(kbId)
        await fetchKbs()
        const created = resp.data?.data
        if (created?.docId) {
            // Server returns PENDING; kick off status polling so the row
            // turns green/red as the async ingestion completes.
            schedulePoll(kbId, created.docId)
        }
        return created
    }

    async function removeDocument(kbId, docId) {
        cancelPoll(docId)
        await knowledgeAPI.deleteDocument(kbId, docId)
        await fetchDocuments(kbId)
        await fetchKbs()
    }

    async function refreshDocumentStatus(kbId, docId) {
        const resp = await knowledgeAPI.documentStatus(kbId, docId)
        const status = resp.data?.data
        if (!status) return null
        const list = documentsByKb.value[kbId] || []
        const target = list.find(d => d.docId === docId)
        if (target) {
            target.status = status.status
            target.chunkCount = status.chunkCount
            target.errorMessage = status.errorMessage
            target.updatedAt = status.updatedAt
        }
        return status
    }

    /** Poll the status endpoint every 3s while the doc is still mid-ingest. */
    function schedulePoll(kbId, docId) {
        cancelPoll(docId)
        const tick = async () => {
            try {
                const s = await refreshDocumentStatus(kbId, docId)
                if (!s || s.status === 'READY' || s.status === 'FAILED') {
                    cancelPoll(docId)
                    if (s?.status === 'READY') {
                        // Refresh KB-level counters so chunk_count badge updates.
                        fetchKbs().catch(() => {})
                    }
                    return
                }
            } catch {
                // Network blip — keep polling; eventual consistency.
            }
            const id = setTimeout(tick, 3000)
            pollersByDocId.set(docId, id)
        }
        const id = setTimeout(tick, 3000)
        pollersByDocId.set(docId, id)
    }

    function cancelPoll(docId) {
        const id = pollersByDocId.get(docId)
        if (id) {
            clearTimeout(id)
            pollersByDocId.delete(docId)
        }
    }

    function cancelAllPolls() {
        for (const id of pollersByDocId.values()) clearTimeout(id)
        pollersByDocId.clear()
    }

    return {
        kbs,
        documentsByKb,
        loading,
        lastError,
        activeKbId,
        activeKb,
        setActiveKb,
        fetchKbs,
        createKb,
        updateKb,
        removeKb,
        fetchDocuments,
        uploadDocument,
        removeDocument,
        refreshDocumentStatus,
        cancelAllPolls
    }
})

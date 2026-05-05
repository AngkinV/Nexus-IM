import axios from 'axios'
import { API_BASE_URL } from './runtimeConfig'

/**
 * Module B: knowledge-base management endpoints. Mirrors the contract in
 * RAG扩展实施方案.md §4.1. All requests carry the same JWT used by /agent/*
 * — no separate auth surface.
 */
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

export const knowledgeAPI = {
    listKbs: () => apiClient.get('/agent/knowledge'),
    getKb: (kbId) => apiClient.get(`/agent/knowledge/${kbId}`),
    createKb: (payload) => apiClient.post('/agent/knowledge', payload),
    updateKb: (kbId, payload) => apiClient.patch(`/agent/knowledge/${kbId}`, payload),
    deleteKb: (kbId) => apiClient.delete(`/agent/knowledge/${kbId}`),

    listDocuments: (kbId) => apiClient.get(`/agent/knowledge/${kbId}/documents`),
    deleteDocument: (kbId, docId) =>
        apiClient.delete(`/agent/knowledge/${kbId}/documents/${docId}`),
    documentStatus: (kbId, docId) =>
        apiClient.get(`/agent/knowledge/${kbId}/documents/${docId}/status`),

    /**
     * Multipart upload. The backend caps individual files at 50 MB and
     * accepts pdf / md / markdown / txt / docx; size/type pre-checks are
     * mirrored client-side so the user gets immediate feedback.
     */
    uploadDocument: (kbId, file, onUploadProgress) => {
        const form = new FormData()
        form.append('file', file)
        return apiClient.post(`/agent/knowledge/${kbId}/documents`, form, {
            headers: { 'Content-Type': 'multipart/form-data' },
            onUploadProgress
        })
    }
}

export const KB_FILE_MAX_BYTES = 50 * 1024 * 1024
export const KB_ALLOWED_EXT = Object.freeze(['pdf', 'md', 'markdown', 'txt', 'docx'])

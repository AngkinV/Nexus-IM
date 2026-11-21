import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { agentAPI } from '@/services/agentApi'
import { useUserStore } from '@/stores/user'
import i18n from '@/locales/i18n'

export const PROVIDER_PURPOSE = Object.freeze({ CHAT: 'chat', EMBEDDING: 'embedding' })

// Errors the user fixes in the channel settings; everything else is retried as is.
const SETTINGS_ERRORS = [
    'AUTH', 'PERMISSION', 'MODEL_NOT_FOUND', 'NOT_FOUND', 'CONFIG', 'CONFIG_CHANGED',
    'no_credential', 'no_provider', 'endpoint_mismatch', 'unreadable_api_key', 'no_model'
]
const RETRY_ERRORS = [
    'PYTHON_UNAVAILABLE', 'INTERNAL_AUTH', 'TIMEOUT', 'RATE_LIMIT', 'CONTEXT_LENGTH', 'BAD_REQUEST', 'NETWORK',
    'UPSTREAM', 'CONTENT_FILTER', 'OUTPUT_LIMIT', 'EMPTY_RESPONSE', 'INCOMPLETE', 'INVALID_RESPONSE',
    'AGENT_RUN_40901', 'AGENT_RUN_40401', 'AGENT_TOOL_50301', 'AGENT_STREAM_50003', 'AGENT_SYS_50001', 'AGENT_MODEL_50202'
]

/**
 * One reading of a backend model error for both the tavern ({code, summary, httpStatus})
 * and the AI session ({code, message, reason?, httpStatus?}). `reason` is the more
 * specific of the two, so it wins. Unknown codes fall back to the backend's own text.
 */
export function describeModelError(error) {
    const code = [error.reason, error.code]
        .map(c => c === 'RATE_LIMITED' ? 'RATE_LIMIT' : c)
        .find(c => SETTINGS_ERRORS.includes(c) || RETRY_ERRORS.includes(c))
    return {
        key: code ? `modelError.${code}` : null,
        fallback: error.summary || error.message || error.code || '',
        detail: [error.code, error.reason, error.httpStatus && `HTTP ${error.httpStatus}`].filter(Boolean).join(' · '),
        settings: SETTINGS_ERRORS.includes(code)
    }
}

/** When the backend's own retryAfterSeconds allows another attempt; null when it gave none. */
export const retryAtOf = error => error.retryAfterSeconds > 0 ? Date.now() + error.retryAfterSeconds * 1000 : null

/**
 * What to say about a typed model id against the channel catalog. A capability of
 * null means the vendor did not say, which is not a reason to doubt a listed model.
 */
export function modelCatalogNote(models, modelId, purpose) {
    const id = modelId.trim()
    if (!id) return null
    const listed = models.find(m => m.id === id)
    if (!listed) return 'unconfirmed'
    return listed[purpose] === false ? 'incompatible' : null
}

// Connection templates only. A model ID is never inferred from a provider name.
export const PROVIDER_PRESETS = [
    { key: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', kind: 'openai-compatible' },
    { key: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com', kind: 'openai-compatible' },
    { key: 'moonshot', label: 'Moonshot 月之暗面', labelEn: 'Moonshot', baseUrl: 'https://api.moonshot.cn/v1', kind: 'openai-compatible' },
    { key: 'zhipu', label: '智谱 GLM', labelEn: 'Zhipu GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', kind: 'openai-compatible' },
    { key: 'tongyi', label: '通义千问 (兼容模式)', labelEn: 'Qwen (compatible mode)', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', kind: 'openai-compatible' },
    { key: 'together', label: 'Together AI', baseUrl: 'https://api.together.xyz/v1', kind: 'openai-compatible' },
    { key: 'groq', label: 'Groq', baseUrl: 'https://api.groq.com/openai/v1', kind: 'openai-compatible' },
    { key: 'ollama', label: 'Ollama (local)', baseUrl: 'http://localhost:11434/v1', kind: 'openai-compatible' },
    { key: 'anthropic', label: 'Anthropic Claude', baseUrl: 'https://api.anthropic.com', kind: 'anthropic' },
    { key: 'gemini', label: 'Google Gemini', baseUrl: '', kind: 'gemini' },
    { key: 'custom', label: '自定义 (OpenAI 兼容)', labelEn: 'Custom (OpenAI-compatible)', baseUrl: '', kind: 'openai-compatible' }
]

export const EMBEDDING_PRESETS = [
    { key: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1' },
    { key: 'tongyi', label: '阿里 DashScope', labelEn: 'Alibaba DashScope', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
    { key: 'zhipu', label: '智谱 BigModel', labelEn: 'Zhipu BigModel', baseUrl: 'https://open.bigmodel.cn/api/paas/v4' },
    { key: 'baichuan', label: '百川 Embedding', labelEn: 'Baichuan Embedding', baseUrl: 'https://api.baichuan-ai.com/v1' },
    { key: 'siliconflow', label: 'SiliconFlow (BGE / GTE)', baseUrl: 'https://api.siliconflow.cn/v1' },
    { key: 'jina', label: 'Jina AI', baseUrl: 'https://api.jina.ai/v1' },
    { key: 'voyage', label: 'Voyage AI', baseUrl: 'https://api.voyageai.com/v1' },
    { key: 'ollama', label: 'Ollama (local)', baseUrl: 'http://localhost:11434/v1' },
    { key: 'custom', label: '自定义 (OpenAI 兼容)', labelEn: 'Custom (OpenAI-compatible)', baseUrl: '' }
]

export function presetLabel(preset) {
    if (!preset) return ''
    return i18n.global.locale.value === 'zh' ? preset.label : (preset.labelEn || preset.label)
}

const CATALOG_TTL_MS = 120_000
const capability = value => typeof value === 'boolean' ? value : null
function normalizeModels(models) {
    const unique = new Map()
    for (const m of Array.isArray(models) ? models : []) {
        const id = typeof m?.id === 'string' && m.id.trim()
        if (id && !unique.has(id)) unique.set(id, { id, chat: capability(m.chat), embedding: capability(m.embedding) })
    }
    return [...unique.values()]
}

export const useAgentProvidersStore = defineStore('agentProviders', () => {
    const user = useUserStore()
    const accountId = computed(() => user.currentUser?.id ?? null)
    const providers = ref([])
    const loading = ref(false)
    const lastError = ref(null)
    const catalogs = ref({})
    const inflight = new Map()
    let session = 0, listRequest = 0

    const chatProviders = computed(() => providers.value.filter(p => !p.purpose || p.purpose === PROVIDER_PURPOSE.CHAT))
    const embeddingProviders = computed(() => providers.value.filter(p => p.purpose === PROVIDER_PURPOSE.EMBEDDING))
    const defaultProvider = computed(() => chatProviders.value.find(p => p.isDefault) || null)
    const defaultEmbeddingProvider = computed(() => embeddingProviders.value.find(p => p.isDefault) || null)

    function invalidate(id) {
        delete catalogs.value[id]
        inflight.delete(String(id))
    }

    watch(accountId, () => {
        ++session
        ++listRequest
        providers.value = []
        catalogs.value = {}
        inflight.clear()
        loading.value = false
        lastError.value = null
    }, { flush: 'sync' })

    // updatedAt also partitions catalogs on older provider list responses.
    const catalogKey = p => JSON.stringify([accountId.value, String(p.id), p.purpose || 'chat', p.revision ?? p.updatedAt ?? null])

    async function fetchAll() {
        if (accountId.value == null) return
        const request = ++listRequest
        loading.value = true
        lastError.value = null
        try {
            const response = await agentAPI.listProviders()
            if (request !== listRequest) return
            const rows = (response.data?.data || []).map(p => ({ ...p, isDefault: p.default ?? p.isDefault }))
            const nextKeys = new Map(rows.map(p => [String(p.id), catalogKey(p)]))
            for (const p of providers.value) {
                if (nextKeys.get(String(p.id)) !== catalogKey(p)) invalidate(p.id)
            }
            providers.value = rows
        } catch (err) {
            if (request !== listRequest) return
            lastError.value = err
            providers.value = []
            catalogs.value = {}
            inflight.clear()
        } finally {
            if (request === listRequest) loading.value = false
        }
    }

    function getById(id) {
        return id == null ? null : providers.value.find(p => String(p.id) === String(id)) || null
    }

    function getCatalog(id) {
        const p = getById(id)
        const entry = catalogs.value[id]
        return p && entry?.key === catalogKey(p) ? entry : null
    }

    function fetchModels(id, { force = false } = {}) {
        const p = getById(id)
        if (!p || accountId.value == null) return Promise.resolve(null)
        const key = catalogKey(p), slot = String(p.id)
        const pending = inflight.get(slot)
        if (pending?.key === key) return pending.promise
        const cached = getCatalog(id)
        if (!force && cached?.expiresAt > Date.now()) return Promise.resolve(cached)

        const request = { key }
        inflight.set(slot, request)
        catalogs.value[slot] = { key, status: 'loading', models: [] }
        const current = () => inflight.get(slot) === request && getById(id) && catalogKey(getById(id)) === key
        request.promise = (async () => {
            try {
                const response = await agentAPI.listProviderModels(p.id)
                if (!current()) return null
                const data = response.data?.data
                if (!data || String(data.providerId) !== slot || data.purpose !== (p.purpose || 'chat') ||
                    (p.revision != null && String(data.revision) !== String(p.revision))) {
                    throw new Error('Catalog does not match the saved connection')
                }
                const status = String(data.status).toLowerCase()
                const models = !data.error && status === 'ready'
                    ? normalizeModels(data.models) : []
                catalogs.value[slot] = {
                    key, revision: data.revision, fetchedAt: data.fetchedAt, truncated: !!data.truncated,
                    status, models, error: data.error,
                    expiresAt: Date.now() + CATALOG_TTL_MS
                }
            } catch (err) {
                if (!current()) return null
                catalogs.value[slot] = { key, status: 'error', models: [], error: err.response?.data?.data?.error || err.response?.data?.message, expiresAt: Date.now() + CATALOG_TTL_MS }
            } finally {
                if (inflight.get(slot) === request) inflight.delete(slot)
            }
            return getCatalog(id)
        })()
        return request.promise
    }

    async function upsert(payload) {
        const owner = session
        const response = await agentAPI.upsertProvider(payload)
        if (session !== owner) return null
        const saved = response.data?.data
        if (saved?.id != null) invalidate(saved.id)
        await fetchAll()
        return owner === session ? saved : null
    }

    async function remove(id) {
        const owner = session
        await agentAPI.deleteProvider(id)
        if (session !== owner) return
        invalidate(id)
        providers.value = providers.value.filter(p => String(p.id) !== String(id))
        await fetchAll()
    }

    async function setDefault(id) {
        const owner = session
        await agentAPI.setDefaultProvider(id)
        if (session !== owner) return
        invalidate(id)
        await fetchAll()
    }

    async function test(id) {
        const owner = session
        const response = await agentAPI.testProvider(id)
        if (session !== owner) return null
        await fetchAll() // refresh the probe status, never use its results as a catalog
        return owner === session ? response.data?.data : null
    }

    return {
        accountId, providers, loading, lastError, chatProviders, embeddingProviders, defaultProvider, defaultEmbeddingProvider,
        fetchAll, upsert, remove, setDefault, test, getById, getCatalog, fetchModels,
        getModelsForProvider: id => getCatalog(id)?.models || []
    }
})

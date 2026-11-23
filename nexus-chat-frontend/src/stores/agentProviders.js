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

export const API_FORMAT = Object.freeze({ OPENAI: 'openai-compatible', ANTHROPIC: 'anthropic', GEMINI: 'gemini' })
export const VENDOR_GROUPS = Object.freeze(['official', 'aggregator', 'local', 'custom'])
const OPENAI = API_FORMAT.OPENAI

// Connection templates only. A model ID is never inferred from a provider name.
// `key` is the vendor recorded on the channel; `format` is the wire protocol its
// endpoint speaks. A custom channel chooses its own protocol and address.
export const PROVIDER_PRESETS = [
    { key: 'openai', group: 'official', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', format: OPENAI },
    { key: 'anthropic', group: 'official', label: 'Anthropic Claude', baseUrl: 'https://api.anthropic.com', format: API_FORMAT.ANTHROPIC },
    { key: 'gemini', group: 'official', label: 'Google Gemini', baseUrl: 'https://generativelanguage.googleapis.com', format: API_FORMAT.GEMINI },
    { key: 'deepseek', group: 'official', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com', format: OPENAI },
    { key: 'xai', group: 'official', label: 'xAI Grok', baseUrl: 'https://api.x.ai/v1', format: OPENAI },
    { key: 'mistral', group: 'official', label: 'Mistral AI', baseUrl: 'https://api.mistral.ai/v1', format: OPENAI },
    { key: 'moonshot', group: 'official', label: 'Moonshot 月之暗面', labelEn: 'Moonshot', baseUrl: 'https://api.moonshot.cn/v1', format: OPENAI },
    { key: 'zhipu', group: 'official', label: '智谱 GLM', labelEn: 'Zhipu GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', format: OPENAI },
    { key: 'tongyi', group: 'official', label: '通义千问 (兼容模式)', labelEn: 'Qwen (compatible mode)', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', format: OPENAI },
    { key: 'doubao', group: 'official', label: '豆包 (火山方舟)', labelEn: 'Doubao (Volcengine Ark)', baseUrl: 'https://ark.cn-beijing.volces.com/api/v3', format: OPENAI },
    { key: 'hunyuan', group: 'official', label: '腾讯混元', labelEn: 'Tencent Hunyuan', baseUrl: 'https://api.hunyuan.cloud.tencent.com/v1', format: OPENAI },
    { key: 'qianfan', group: 'official', label: '百度千帆', labelEn: 'Baidu Qianfan', baseUrl: 'https://qianfan.baidubce.com/v2', format: OPENAI },
    { key: 'stepfun', group: 'official', label: '阶跃星辰', labelEn: 'StepFun', baseUrl: 'https://api.stepfun.com/v1', format: OPENAI },
    { key: 'openrouter', group: 'aggregator', label: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1', format: OPENAI },
    { key: 'siliconflow', group: 'aggregator', label: '硅基流动 SiliconFlow', labelEn: 'SiliconFlow', baseUrl: 'https://api.siliconflow.cn/v1', format: OPENAI },
    { key: 'together', group: 'aggregator', label: 'Together AI', baseUrl: 'https://api.together.xyz/v1', format: OPENAI },
    { key: 'groq', group: 'aggregator', label: 'Groq', baseUrl: 'https://api.groq.com/openai/v1', format: OPENAI },
    { key: 'fireworks', group: 'aggregator', label: 'Fireworks AI', baseUrl: 'https://api.fireworks.ai/inference/v1', format: OPENAI },
    { key: 'ollama', group: 'local', label: 'Ollama', baseUrl: 'http://localhost:11434/v1', format: OPENAI, keyOptional: true },
    { key: 'lmstudio', group: 'local', label: 'LM Studio', baseUrl: 'http://localhost:1234/v1', format: OPENAI, keyOptional: true },
    { key: 'custom', group: 'custom', label: '自定义服务商', labelEn: 'Custom provider', baseUrl: '', format: OPENAI, formats: [OPENAI, API_FORMAT.ANTHROPIC, API_FORMAT.GEMINI] }
]

export const EMBEDDING_PRESETS = [
    { key: 'openai', group: 'official', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', format: OPENAI },
    { key: 'gemini', group: 'official', label: 'Google Gemini', baseUrl: 'https://generativelanguage.googleapis.com', format: API_FORMAT.GEMINI },
    { key: 'tongyi', group: 'official', label: '阿里 DashScope', labelEn: 'Alibaba DashScope', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', format: OPENAI },
    { key: 'zhipu', group: 'official', label: '智谱 BigModel', labelEn: 'Zhipu BigModel', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', format: OPENAI },
    { key: 'baichuan', group: 'official', label: '百川 Embedding', labelEn: 'Baichuan Embedding', baseUrl: 'https://api.baichuan-ai.com/v1', format: OPENAI },
    { key: 'jina', group: 'official', label: 'Jina AI', baseUrl: 'https://api.jina.ai/v1', format: OPENAI },
    { key: 'voyage', group: 'official', label: 'Voyage AI', baseUrl: 'https://api.voyageai.com/v1', format: OPENAI },
    { key: 'siliconflow', group: 'aggregator', label: 'SiliconFlow (BGE / GTE)', baseUrl: 'https://api.siliconflow.cn/v1', format: OPENAI },
    { key: 'ollama', group: 'local', label: 'Ollama', baseUrl: 'http://localhost:11434/v1', format: OPENAI, keyOptional: true },
    { key: 'lmstudio', group: 'local', label: 'LM Studio', baseUrl: 'http://localhost:1234/v1', format: OPENAI, keyOptional: true },
    // Anthropic's protocol has no embedding endpoint, so it is not offered here.
    { key: 'custom', group: 'custom', label: '自定义服务商', labelEn: 'Custom provider', baseUrl: '', format: OPENAI, formats: [OPENAI, API_FORMAT.GEMINI] }
]

export const presetsFor = purpose => purpose === PROVIDER_PURPOSE.EMBEDDING ? EMBEDDING_PRESETS : PROVIDER_PRESETS

export function presetLabel(preset) {
    if (!preset) return ''
    return i18n.global.locale.value === 'zh' ? preset.label : (preset.labelEn || preset.label)
}

/**
 * The catalogue entry a saved channel belongs to. The backend reports the vendor it
 * recorded; channels saved before vendors existed only have their id, which began
 * with the preset's key ("deepseek-personal"). Anything unrecognised is a custom one.
 */
export function vendorOf(channel, purpose = channel?.purpose) {
    const presets = presetsFor(purpose), custom = presets.find(p => p.key === 'custom')
    const vendor = String(channel?.vendor || '').toLowerCase(), id = String(channel?.provider || '').toLowerCase()
    return presets.find(p => p.key === vendor)
        || (!channel?.vendor || vendor === id ? presets.filter(p => p.key !== 'custom').find(p => id.startsWith(p.key)) : null)
        || custom
}

/** The protocol a channel speaks, for a backend that does not report one yet. */
export const formatOf = channel => channel?.apiFormat || vendorOf(channel).format

/** Whether a channel may be used without an API key (a server on the user's machine). */
export const keyOptionalFor = channel => channel?.keyOptional ?? String(channel?.provider || '').toLowerCase() === 'ollama'

/** Why a chat channel cannot be used for a turn right now: 'model', 'key', or null when it can. */
export function channelIssue(channel) {
    if (!channel) return 'model'
    if (!String(channel.defaultModel || '').trim()) return 'model'
    if (!channel.hasApiKey && !keyOptionalFor(channel)) return 'key'
    return null
}

/**
 * An id for a new channel that no saved channel of the same purpose holds:
 * "deepseek", then "deepseek-2", ... The server keeps ids unique as well; sending a
 * free one means adding a channel can never land on an existing one.
 */
export function freeChannelId(vendorKey, channels) {
    const taken = new Set(channels.map(c => String(c.provider || '').toLowerCase()))
    const stem = String(vendorKey || 'custom').toLowerCase()
    if (!taken.has(stem)) return stem
    for (let n = 2; ; n++) if (!taken.has(`${stem}-${n}`)) return `${stem}-${n}`
}

/** Saved channels matching a search over name, vendor, model, id and address. */
export function filterChannels(channels, query) {
    const words = String(query || '').toLowerCase().split(/\s+/).filter(Boolean)
    if (!words.length) return channels
    return channels.filter(c => {
        const vendor = vendorOf(c)
        const text = [c.displayName, c.provider, c.defaultModel, c.baseUrl, vendor.label, vendor.labelEn, vendor.key]
            .filter(Boolean).join(' ').toLowerCase()
        return words.every(w => text.includes(w))
    })
}

/**
 * Saved channels as the switcher lists them: grouped by where they come from, the
 * default channel first and the rest by name, each with its vendor's name and the
 * reason it cannot be used yet (null when it can). Empty groups are left out.
 */
export function groupChannels(channels, query = '') {
    const rows = filterChannels(channels, query).map(c => {
        const vendor = vendorOf(c)
        return { ...c, issue: channelIssue(c), vendorLabel: presetLabel(vendor), group: vendor.group }
    })
    const name = c => String(c.displayName || c.provider || '')
    rows.sort((a, b) => (b.isDefault ? 1 : 0) - (a.isDefault ? 1 : 0) || name(a).localeCompare(name(b)))
    return VENDOR_GROUPS.map(key => ({ key, items: rows.filter(r => r.group === key) })).filter(g => g.items.length)
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

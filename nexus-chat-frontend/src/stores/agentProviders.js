import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { agentAPI } from '@/services/agentApi'

export const PROVIDER_PURPOSE = Object.freeze({
    CHAT: 'chat',
    EMBEDDING: 'embedding'
})

/**
 * Catalog of supported provider templates for chat (LLM completions).
 * The backend accepts any string for the `provider` field; this list is
 * purely a UX hint that prefills baseUrl / suggested model when the user
 * picks one.
 */
export const PROVIDER_PRESETS = [
    {
        key: 'openai',
        label: 'OpenAI',
        baseUrl: 'https://api.openai.com/v1',
        suggestedModels: ['gpt-4.1-mini', 'gpt-4o-mini', 'gpt-4.1', 'gpt-4o'],
        kind: 'openai-compatible'
    },
    {
        key: 'deepseek',
        label: 'DeepSeek',
        baseUrl: 'https://api.deepseek.com',
        suggestedModels: ['deepseek-chat', 'deepseek-reasoner'],
        kind: 'openai-compatible'
    },
    {
        key: 'moonshot',
        label: 'Moonshot 月之暗面',
        baseUrl: 'https://api.moonshot.cn/v1',
        suggestedModels: ['moonshot-v1-8k', 'moonshot-v1-32k', 'moonshot-v1-128k'],
        kind: 'openai-compatible'
    },
    {
        key: 'zhipu',
        label: '智谱 GLM',
        baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
        suggestedModels: ['glm-4-plus', 'glm-4-air', 'glm-4-flash'],
        kind: 'openai-compatible'
    },
    {
        key: 'tongyi',
        label: '通义千问 (兼容模式)',
        baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        suggestedModels: ['qwen-plus', 'qwen-max', 'qwen-turbo'],
        kind: 'openai-compatible'
    },
    {
        key: 'together',
        label: 'Together AI',
        baseUrl: 'https://api.together.xyz/v1',
        suggestedModels: ['meta-llama/Llama-3.3-70B-Instruct-Turbo', 'Qwen/Qwen2.5-72B-Instruct-Turbo'],
        kind: 'openai-compatible'
    },
    {
        key: 'groq',
        label: 'Groq',
        baseUrl: 'https://api.groq.com/openai/v1',
        suggestedModels: ['llama-3.3-70b-versatile', 'llama-3.1-8b-instant'],
        kind: 'openai-compatible'
    },
    {
        key: 'ollama',
        label: 'Ollama (local)',
        baseUrl: 'http://localhost:11434/v1',
        suggestedModels: ['llama3.2', 'qwen2.5', 'deepseek-r1'],
        kind: 'openai-compatible'
    },
    {
        key: 'anthropic',
        label: 'Anthropic Claude',
        baseUrl: 'https://api.anthropic.com',
        suggestedModels: ['claude-sonnet-4-5', 'claude-opus-4-1', 'claude-haiku-4-5'],
        kind: 'anthropic'
    },
    {
        key: 'gemini',
        label: 'Google Gemini',
        baseUrl: '',
        suggestedModels: ['gemini-2.0-flash', 'gemini-2.5-pro'],
        kind: 'gemini'
    },
    {
        key: 'custom',
        label: '自定义 (OpenAI 兼容)',
        baseUrl: '',
        suggestedModels: [],
        kind: 'openai-compatible'
    }
]

/**
 * Catalog of providers that actually expose an OpenAI-compatible /embeddings
 * endpoint. Different list from the chat presets because most BYOK chat
 * providers (DeepSeek / Moonshot / Groq / aggregator "国产模型" groups) don't
 * implement /embeddings — quietly listing them would set users up for the
 * exact "no available channel for text-embedding-3-small" failure this
 * feature is designed to prevent.
 */
export const EMBEDDING_PRESETS = [
    {
        key: 'openai',
        label: 'OpenAI',
        baseUrl: 'https://api.openai.com/v1',
        suggestedModels: ['text-embedding-3-small', 'text-embedding-3-large', 'text-embedding-ada-002']
    },
    {
        key: 'tongyi',
        label: '阿里 DashScope',
        baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        suggestedModels: ['text-embedding-v3', 'text-embedding-v2', 'text-embedding-v1']
    },
    {
        key: 'zhipu',
        label: '智谱 BigModel',
        baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
        suggestedModels: ['embedding-3', 'embedding-2']
    },
    {
        key: 'baichuan',
        label: '百川 Embedding',
        baseUrl: 'https://api.baichuan-ai.com/v1',
        suggestedModels: ['Baichuan-Text-Embedding']
    },
    {
        key: 'siliconflow',
        label: 'SiliconFlow (BGE / GTE)',
        baseUrl: 'https://api.siliconflow.cn/v1',
        suggestedModels: ['BAAI/bge-large-zh-v1.5', 'BAAI/bge-m3', 'Pro/BAAI/bge-m3']
    },
    {
        key: 'jina',
        label: 'Jina AI',
        baseUrl: 'https://api.jina.ai/v1',
        suggestedModels: ['jina-embeddings-v3', 'jina-embeddings-v2-base-zh']
    },
    {
        key: 'voyage',
        label: 'Voyage AI',
        baseUrl: 'https://api.voyageai.com/v1',
        suggestedModels: ['voyage-3', 'voyage-3-large', 'voyage-multilingual-2']
    },
    {
        key: 'ollama',
        label: 'Ollama (local)',
        baseUrl: 'http://localhost:11434/v1',
        suggestedModels: ['nomic-embed-text', 'bge-m3', 'mxbai-embed-large']
    },
    {
        key: 'custom',
        label: '自定义 (OpenAI 兼容)',
        baseUrl: '',
        suggestedModels: []
    }
]

export const useAgentProvidersStore = defineStore('agentProviders', () => {
    // All credentials, regardless of purpose. The settings UI displays this list
    // grouped by purpose tab.
    const providers = ref([])         // [{ id, provider, purpose, displayName, baseUrl, defaultModel, hasApiKey, apiKeyMask, isDefault, status }]
    const loading = ref(false)
    const lastError = ref(null)
    const modelsByProviderId = ref({}) // { [providerId: string]: string[] }

    const chatProviders = computed(() =>
        providers.value.filter(p => !p.purpose || p.purpose === PROVIDER_PURPOSE.CHAT)
    )
    const embeddingProviders = computed(() =>
        providers.value.filter(p => p.purpose === PROVIDER_PURPOSE.EMBEDDING)
    )
    const defaultProvider = computed(() =>
        chatProviders.value.find(p => p.isDefault) || null
    )
    const defaultEmbeddingProvider = computed(() =>
        embeddingProviders.value.find(p => p.isDefault) || null
    )

    async function fetchAll() {
        loading.value = true
        lastError.value = null
        try {
            const response = await agentAPI.listProviders()
            providers.value = response.data?.data || []
            // Prune cached model catalogs for providers that no longer exist.
            const liveIds = new Set(providers.value.map(p => String(p.id)))
            modelsByProviderId.value = Object.fromEntries(
                Object.entries(modelsByProviderId.value).filter(([id]) => liveIds.has(id))
            )
        } catch (err) {
            lastError.value = err
            providers.value = []
            modelsByProviderId.value = {}
        } finally {
            loading.value = false
        }
    }

    async function upsert(payload) {
        const response = await agentAPI.upsertProvider(payload)
        await fetchAll()
        return response.data?.data
    }

    async function remove(id) {
        await agentAPI.deleteProvider(id)
        await fetchAll()
    }

    async function setDefault(id) {
        await agentAPI.setDefaultProvider(id)
        await fetchAll()
    }

    async function test(id) {
        const response = await agentAPI.testProvider(id)
        const result = response.data?.data
        if (result?.availableModels?.length) {
            modelsByProviderId.value = {
                ...modelsByProviderId.value,
                [String(id)]: normalizeModels(result.availableModels)
            }
        }
        // refresh status afterwards (server persists ok/invalid)
        await fetchAll()
        return result
    }

    function getById(id) {
        if (!id) return null
        return providers.value.find(p => p.id === Number(id)) || null
    }

    function getModelsForProvider(id) {
        if (!id) return []
        return modelsByProviderId.value[String(id)] || []
    }

    function normalizeModels(models) {
        return Array.from(
            new Set((models || []).map(m => (m || '').toString().trim()).filter(Boolean))
        )
    }

    return {
        providers,
        loading,
        lastError,
        modelsByProviderId,
        chatProviders,
        embeddingProviders,
        defaultProvider,
        defaultEmbeddingProvider,
        fetchAll,
        upsert,
        remove,
        setDefault,
        test,
        getById,
        getModelsForProvider
    }
})

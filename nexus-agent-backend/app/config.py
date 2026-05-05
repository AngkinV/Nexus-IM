"""Configuration loaded from environment variables.

See `agent开发文档/Agent 设计说明（推理、工具、记忆、安全）.md` §15 for the canonical list.
"""
from __future__ import annotations

import logging
from functools import lru_cache
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

log = logging.getLogger(__name__)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        protected_namespaces=("settings_",),
    )

    # ----- Service identity -----
    service_name: str = "nexus-agent-backend"
    service_port: int = 8100

    # ----- Model provider -----
    model_provider: str = "openai"
    openai_api_key: str | None = None
    openai_base_url: str = "https://api.openai.com/v1"
    model_name: str = "gpt-4.1-mini"

    # ----- Agent loop -----
    max_iterations: int = 6
    model_timeout_sec: int = 20
    tool_timeout_sec: int = 3
    enable_parallel_tool_calls: bool = True

    # ----- Java internal API (for tools) -----
    java_internal_base_url: str = "http://localhost:8080/internal/agent"
    java_internal_token: str = "dev-internal-token-change-me"

    # ----- HMAC verification (Java -> Python) -----
    internal_signing_secret: str = "dev-signing-secret-change-me"
    expected_caller: str = "nexus-chat-backend"
    nonce_skew_ms: int = 5 * 60 * 1000

    # ----- Memory -----
    redis_url: str = "redis://localhost:6379/2"
    memory_short_ttl_sec: int = 7 * 24 * 60 * 60
    memory_short_max_turns: int = 20
    memory_long_top_k: int = 8
    memory_write_confidence_threshold: float = 0.75
    enable_long_term_memory: bool = True

    # ----- Context budget -----
    context_max_tokens: int = 12_000
    context_recent_turns_keep: int = 6
    max_output_tokens: int = 1024

    # ----- Behavior switches -----
    # When false, the orchestrator returns deterministic mock answers (no API key needed).
    # Useful for development before real OpenAI credentials are wired.
    use_real_model: bool = Field(default=False)

    # ----- RAG (Module A: memory RAG; Module B: knowledge base) -----
    # ChromaDB persistent store path. Created on first use.
    chroma_persist_dir: str = "./data/chroma"
    # OpenAI embedding model used for both memory and knowledge base collections.
    embedding_model: str = "text-embedding-3-small"
    # Dedicated embedding credentials. Real-world deployments often pay
    # OpenAI/DashScope for embeddings even when chat traffic is BYOK to a
    # provider that doesn't expose embeddings (DeepSeek/Moonshot/Groq/...).
    # When unset, get_embeddings() falls back to openai_api_key / openai_base_url.
    # The BYOK chat key is no longer auto-passed to the embedding endpoint —
    # ingest_document() / memory_rag explicitly opt out of it.
    embedding_api_key: str | None = None
    embedding_base_url: str | None = None

    # ----- Embedding provider presets -----
    # When ``embedding_provider`` is set to a recognised key (see _EMB_PRESETS
    # below), get_settings() copies the matching ``<provider>_emb_*`` triple
    # into ``embedding_api_key/base_url/model`` — so swapping vendors is a
    # one-line change in .env. Explicit ``EMBEDDING_API_KEY/BASE_URL`` values
    # in .env still win on a per-field basis (use this to override just the
    # key while keeping the preset's URL/model). Set to "custom" or leave
    # blank to skip preset resolution entirely.
    embedding_provider: str | None = None

    # OpenAI direct
    openai_emb_api_key: str | None = None
    openai_emb_base_url: str = "https://api.openai.com/v1"
    openai_emb_model: str = "text-embedding-3-small"

    # 阿里云 DashScope (OpenAI 兼容端点)
    dashscope_emb_api_key: str | None = None
    dashscope_emb_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    dashscope_emb_model: str = "text-embedding-v3"

    # 智谱 BigModel
    zhipu_emb_api_key: str | None = None
    zhipu_emb_base_url: str = "https://open.bigmodel.cn/api/paas/v4"
    zhipu_emb_model: str = "embedding-3"

    # 硅基流动 SiliconFlow
    siliconflow_emb_api_key: str | None = None
    siliconflow_emb_base_url: str = "https://api.siliconflow.cn/v1"
    siliconflow_emb_model: str = "BAAI/bge-m3"

    # 本地 Ollama (key 不校验，留个占位即可)
    ollama_emb_api_key: str = "ollama"
    ollama_emb_base_url: str = "http://localhost:11434/v1"
    ollama_emb_model: str = "bge-m3"

    # new-api / one-api 中转 (无默认 URL — 不同部署各自不同)
    newapi_emb_api_key: str | None = None
    newapi_emb_base_url: str | None = None
    newapi_emb_model: str | None = None

    # Module A switches.
    memory_rag_enabled: bool = True
    memory_rag_top_k: int = 3
    # Memory chunks shorter than this token-equivalent are skipped (cuts noise).
    memory_rag_min_chars: int = 20
    # Module B switches.
    knowledge_rag_enabled: bool = True
    knowledge_chunk_size: int = 512
    knowledge_chunk_overlap: int = 64
    knowledge_rag_top_k: int = 4

    # ----- Orchestration engine (Module C) -----
    # "handcrafted" — the original ReAct loop in orchestrator.py
    # "langgraph"   — the LangGraph StateGraph implementation in orchestrator_langgraph.py
    engine: str = "handcrafted"


# Maps EMBEDDING_PROVIDER value -> (key_attr, url_attr, model_attr) on Settings.
# Add a new vendor here + a matching block of fields above to wire it up.
_EMB_PRESETS: dict[str, tuple[str, str, str]] = {
    "openai":      ("openai_emb_api_key",      "openai_emb_base_url",      "openai_emb_model"),
    "dashscope":   ("dashscope_emb_api_key",   "dashscope_emb_base_url",   "dashscope_emb_model"),
    "zhipu":       ("zhipu_emb_api_key",       "zhipu_emb_base_url",       "zhipu_emb_model"),
    "siliconflow": ("siliconflow_emb_api_key", "siliconflow_emb_base_url", "siliconflow_emb_model"),
    "ollama":      ("ollama_emb_api_key",      "ollama_emb_base_url",      "ollama_emb_model"),
    "newapi":      ("newapi_emb_api_key",      "newapi_emb_base_url",      "newapi_emb_model"),
}


def _apply_embedding_preset(s: "Settings") -> None:
    """Resolve EMBEDDING_PROVIDER into the active (api_key, base_url, model)
    triple. Per-field precedence: explicit EMBEDDING_* env vars > preset >
    legacy fallback in get_embeddings(). Unknown providers are logged and
    ignored so a typo doesn't silently break embeddings."""
    provider = (s.embedding_provider or "").strip().lower()
    if not provider or provider == "custom":
        return
    if provider not in _EMB_PRESETS:
        log.warning(
            "EMBEDDING_PROVIDER=%r is not a known preset; ignored. "
            "Supported: %s (or 'custom' to use raw EMBEDDING_* vars).",
            provider, ", ".join(sorted(_EMB_PRESETS)),
        )
        return
    key_attr, url_attr, model_attr = _EMB_PRESETS[provider]
    if not s.embedding_api_key:
        s.embedding_api_key = getattr(s, key_attr)
    if not s.embedding_base_url:
        s.embedding_base_url = getattr(s, url_attr)
    preset_model = getattr(s, model_attr)
    # Preset's model wins over the hard-coded class default. Users who want
    # to override per-field can set EMBEDDING_MODEL explicitly *and* pick a
    # provider — explicit env always wins because pydantic loaded it before
    # this resolver runs, and we only overwrite when the field still equals
    # the class default.
    if preset_model and s.embedding_model == "text-embedding-3-small":
        s.embedding_model = preset_model


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    s = Settings()
    if s.openai_api_key:
        s.use_real_model = True
    _apply_embedding_preset(s)
    return s

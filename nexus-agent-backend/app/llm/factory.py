"""Pick the right LLM adapter based on the provider's name."""
from __future__ import annotations

from .base import LLMClient, ProviderConfig
from .openai_like import OpenAILikeClient

# Provider names that ship with built-in OpenAI-compatible defaults if base_url is omitted.
OPENAI_COMPATIBLE_DEFAULT_BASE_URLS: dict[str, str] = {
    "openai": "https://api.openai.com/v1",
    "deepseek": "https://api.deepseek.com",
    "moonshot": "https://api.moonshot.cn/v1",
    "zhipu": "https://open.bigmodel.cn/api/paas/v4",
    "tongyi": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "together": "https://api.together.xyz/v1",
    "groq": "https://api.groq.com/openai/v1",
    "ollama": "http://localhost:11434/v1",
    "openai-compatible": "",  # user-supplied
    "custom": "",
}

ANTHROPIC_NAMES = {"anthropic", "claude"}
GEMINI_NAMES = {"gemini", "google"}

AVAILABLE_PROVIDERS = sorted(
    list(OPENAI_COMPATIBLE_DEFAULT_BASE_URLS.keys()) + list(ANTHROPIC_NAMES) + list(GEMINI_NAMES)
)


def is_anthropic(name: str) -> bool:
    return (name or "").lower() in ANTHROPIC_NAMES


def is_gemini(name: str) -> bool:
    return (name or "").lower() in GEMINI_NAMES


def build_client(cfg: ProviderConfig) -> LLMClient:
    """Return an LLMClient for the given provider config.

    For Anthropic / Gemini the optional SDKs are imported lazily inside the adapter,
    so they don't need to be installed unless the user actually picks those.
    """
    name = (cfg.name or "openai").lower()

    # Fill base_url default if the user didn't override
    if not cfg.base_url and name in OPENAI_COMPATIBLE_DEFAULT_BASE_URLS:
        default = OPENAI_COMPATIBLE_DEFAULT_BASE_URLS[name]
        if default:
            cfg = ProviderConfig(name=cfg.name, base_url=default, model=cfg.model, api_key=cfg.api_key)

    if is_anthropic(name):
        from .anthropic_client import AnthropicClient
        return AnthropicClient(cfg)

    if is_gemini(name):
        from .gemini_client import GeminiClient
        return GeminiClient(cfg)

    return OpenAILikeClient(cfg)

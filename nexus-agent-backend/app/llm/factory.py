"""Pick the right LLM adapter based on the provider's name."""
from __future__ import annotations

from .base import LLMClient, ProviderConfig
from .openai_like import OpenAILikeClient

# Provider names that ship with built-in OpenAI-compatible defaults if base_url is omitted.
# When a request comes through the Java gateway, X-Model-Base-URL is already a
# versioned OpenAI-compatible root;
# these defaults only apply to direct/local invocations without that header.
OPENAI_COMPATIBLE_DEFAULT_BASE_URLS: dict[str, str] = {
    "openai": "https://api.openai.com/v1",
    "deepseek": "https://api.deepseek.com/v1",
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


def model_endpoint(provider: str, configured: str | None) -> str:
    from urllib.parse import urlsplit

    name = provider.lower()
    native = is_anthropic(name) or is_gemini(name)
    default = ("https://api.anthropic.com" if is_anthropic(name) else "https://generativelanguage.googleapis.com"
               if is_gemini(name) else OPENAI_COMPATIBLE_DEFAULT_BASE_URLS.get(name))
    value = (configured or default or "").strip().rstrip('/')
    url = urlsplit(value)
    if (url.scheme not in ('http', 'https') or not url.hostname or url.username is not None or url.password is not None
            or url.query or url.fragment or url.port == 0 or any(ch.isspace() or ch == '\\' or ord(ch) < 32 for ch in value)):
        raise ValueError('Invalid or missing provider endpoint')
    path = url.path.rstrip('/')
    if native:
        for suffix in ('/v1/messages', '/v1beta/models', '/v1/models', '/v1beta', '/v1'):
            if path.endswith(suffix):
                path = path[:-len(suffix)]
                break
    else:
        for suffix in ('/chat/completions', '/embeddings', '/models'):
            if path.endswith(suffix):
                path = path[:-len(suffix)]
                break
        segment = path.rsplit('/', 1)[-1]
        if not (segment.startswith('v') and segment[1:].isdigit()):
            path += '/v1'
    return url._replace(path=path).geturl()


def build_client(cfg: ProviderConfig) -> LLMClient:
    """Return an LLMClient for the given provider config."""
    name = (cfg.name or "openai").lower()

    cfg = ProviderConfig(name=cfg.name, base_url=model_endpoint(name, cfg.base_url), model=cfg.model, api_key=cfg.api_key)

    if is_anthropic(name):
        from .anthropic_client import AnthropicClient
        return AnthropicClient(cfg)

    if is_gemini(name):
        from .gemini_client import GeminiClient
        return GeminiClient(cfg)

    return OpenAILikeClient(cfg)

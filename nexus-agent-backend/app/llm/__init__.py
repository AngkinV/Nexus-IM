"""LLM provider abstraction.

Why this layer exists:
  Different providers use different SDKs and message/tool-call formats. The
  orchestrator should not care which one it talks to. Each adapter implements
  the same iterator protocol so the agent loop can stay provider-agnostic.

Usage:
    from .llm import build_client, ProviderConfig
    cfg = ProviderConfig(name="openai", base_url=..., model=..., api_key=...)
    client = build_client(cfg)
    async for chunk in client.complete(messages, tools, options):
        ...
"""
from .base import (
    LLMClient,
    ProviderConfig,
    LLMChunk,
    LLMUsage,
    LLMToolCall,
)
from .factory import build_client, AVAILABLE_PROVIDERS, is_anthropic, is_gemini

__all__ = [
    "LLMClient",
    "ProviderConfig",
    "LLMChunk",
    "LLMUsage",
    "LLMToolCall",
    "build_client",
    "AVAILABLE_PROVIDERS",
    "is_anthropic",
    "is_gemini",
]

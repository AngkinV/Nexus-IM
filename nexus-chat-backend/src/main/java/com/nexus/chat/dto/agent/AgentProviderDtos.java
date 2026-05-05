package com.nexus.chat.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public final class AgentProviderDtos {

    private AgentProviderDtos() {}

    /** Catalog hint shown in the UI; the canonical list is also kept in code on the frontend side. */
    public enum ProviderKind {
        OPENAI,
        DEEPSEEK,
        MOONSHOT,
        ZHIPU,
        TONGYI,
        TOGETHER,
        GROQ,
        OLLAMA,
        ANTHROPIC,
        GEMINI,
        CUSTOM
    }

    @Data
    public static class UpsertProviderRequest {
        /** A short identifier unique per user, e.g. "openai", "deepseek-1", "ollama-local". */
        @NotBlank
        @Size(max = 50)
        private String provider;

        /**
         * What this credential is for: "chat" (LLM completions) or "embedding" (vector embeddings).
         * Defaults to "chat" for backward compatibility. The (provider, purpose) pair is the unique key
         * per user — same provider id can hold separate chat and embedding rows with different keys/models.
         */
        @Size(max = 20)
        private String purpose;

        /** Friendly label shown to the user. Defaults to provider id when blank. */
        @Size(max = 80)
        private String displayName;

        /** Base URL of the OpenAI-compatible API (or native API for Anthropic/Gemini). */
        @Size(max = 255)
        private String baseUrl;

        /** Default model id used when the user hasn't picked one for the request. */
        @Size(max = 120)
        private String defaultModel;

        /**
         * API key in plaintext. Optional on update — when null/blank the existing stored key is kept.
         * The server encrypts before persistence and only ever returns a masked preview.
         */
        private String apiKey;

        /** When true, becomes the user's default provider; previous default is cleared. */
        private boolean makeDefault;
    }

    @Data
    public static class ProviderView {
        private Long id;
        private String provider;
        /** "chat" or "embedding". Frontend filters credential pickers by this. */
        private String purpose;
        private String displayName;
        private String baseUrl;
        private String defaultModel;
        private String apiKeyMask;          // e.g. "sk-***wXyZ"
        private boolean hasApiKey;
        private boolean isDefault;
        private String status;              // ok / invalid / unknown
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class ProviderTestResult {
        private boolean ok;
        private String message;
        private Long latencyMs;
        private String model;
        private List<String> availableModels;
        /** Vector dimension returned by the embedding probe (only set for purpose=embedding). */
        private Integer embeddingDimension;
    }
}

package com.nexus.chat.dto.agent;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.JsonNode;

public final class AgentProviderDtos {

    private AgentProviderDtos() {}

    @Data
    public static class UpsertProviderRequest {
        /**
         * The saved channel to change. Absent when adding a channel, so adding can
         * never overwrite one that already exists.
         */
        private Long id;
        /**
         * The channel's id, unique per user and purpose, e.g. "openai", "deepseek-2".
         * Optional when adding: the server derives one from the vendor and keeps it unique.
         * Fixed once saved.
         */
        @Size(max = 50)
        private String provider;
        /** Catalogue entry the channel comes from ("openai", "deepseek", "custom", ...). */
        @Size(max = 40)
        private String vendor;
        /** Wire protocol: "openai-compatible", "anthropic" or "gemini". */
        @Size(max = 30)
        private String apiFormat;

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
        /** Catalogue entry the channel comes from; for older rows this is the channel id. */
        private String vendor;
        /** Wire protocol in effect: "openai-compatible", "anthropic" or "gemini". */
        private String apiFormat;
        /** True when the channel works without an API key (a local server). */
        private boolean keyOptional;
        /** "chat" or "embedding". Frontend filters credential pickers by this. */
        private String purpose;
        private String displayName;
        private String baseUrl;
        private String defaultModel;
        private String apiKeyMask;          // e.g. "sk-***wXyZ"
        private boolean hasApiKey;
        private boolean isDefault;
        private String status;              // ok / invalid / unknown
        private String revision;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class ProviderTestResult {
        private boolean ok;
        private String message;
        private Long latencyMs;
        private String model;
        private JsonNode error;
        /** Vector dimension returned by the embedding probe (only set for purpose=embedding). */
        private Integer embeddingDimension;
    }
}

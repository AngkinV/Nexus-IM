package com.nexus.chat.dto.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

public final class AgentSessionAndChatDtos {

    private AgentSessionAndChatDtos() {}

    @Data
    public static class CreateSessionRequest {
        @NotNull
        private AgentEnums.OperationType entryMode;
        @NotBlank
        private String title;
        private Long boundChatId;
    }

    @Data
    public static class SessionChatRequest {
        @NotNull
        private AgentEnums.OperationType operationType;
        @NotBlank
        private String input;
        @Valid
        private ChatContext chatContext;
        @Valid
        private Options options;
        /** Optional: override the user's default LLM provider for this request. */
        private Long providerId;
        /**
         * Module B: optional knowledge base bound to this turn. When set, the
         * Python orchestrator runs the user's query through knowledge_rag.retrieve
         * (filtered by both kbId and userId) and injects the top-K chunks into
         * the system prompt as a citable reference block.
         */
        private String linkedKbId;
        /**
         * Client-chosen identity of this turn. Optional: when absent the gateway makes one.
         * Its shape is validated so it can be used as a checkpoint key.
         */
        @Pattern(regexp = "^[A-Za-z0-9_-]{8,64}$", message = "must be 8-64 of A-Z a-z 0-9 _ -")
        private String runId;
        /** The run this one retries; the old answer stays. */
        @Pattern(regexp = "^[A-Za-z0-9_-]{8,64}$", message = "must be 8-64 of A-Z a-z 0-9 _ -")
        private String retryOfRunId;
        private com.nexus.chat.service.agent.scope.ScopeDtos.Selection scopeSelection;
        private Boolean scopeConfirmed;
        private Long expectedBindingVersion;
        @JsonIgnore
        private com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope authorizedScope;
        /** Attachments the client picked; Java resolves them into {@link #resolvedAttachments}. */
        @Size(max = 4, message = "at most 4 attachments")
        private List<String> attachmentIds;
        /**
         * Filled in by the server from the owned workspace. Ignored when a client sends it,
         * so an upload's text can only come from what was actually uploaded.
         */
        @JsonIgnore
        private List<Map<String, Object>> resolvedAttachments;
    }

    @Data
    public static class ResumeRequest {
        private Boolean scopeConfirmed;
        private Long expectedBindingVersion;
        private Long providerId;
    }

    @Data
    public static class ChatContext {
        private Long chatId;
    }

    @Data
    public static class Options {
        private AgentEnums.Visibility visibility = AgentEnums.Visibility.PRIVATE_ONLY;
        @Min(value = 1, message = "must be at least 1")
        @Max(value = 8192, message = "must be at most 8192")
        private Integer maxOutputTokens = 1024;
        @DecimalMin(value = "0.0", message = "must be between 0 and 2")
        @DecimalMax(value = "2.0", message = "must be between 0 and 2")
        private Double temperature = 0.2;
        @Min(value = 1, message = "must be at least 1")
        @Max(value = 32, message = "must be at most 32")
        private Integer maxIterations = 6;
        /** Opt-in: three analysts run concurrently and one synthesis answers. */
        private Boolean collaborationEnabled = false;
        /** Opt-in: the turn may search the web. */
        private Boolean webSearchEnabled = false;
    }

    @Data
    public static class ToolCallSummary {
        private String toolName;
        private String status;
        private Integer latencyMs;
    }

    @Data
    public static class TokenUsage {
        private Integer inputTokens;
        private Integer outputTokens;
        private Integer totalTokens;
    }

    @Data
    public static class ChatAnswerData {
        private String answer;
        private AgentEnums.OperationType operationType;
        private List<ToolCallSummary> toolCalls;
        private TokenUsage usage;
        private String finishReason;
        private Map<String, Object> raw;
    }
}

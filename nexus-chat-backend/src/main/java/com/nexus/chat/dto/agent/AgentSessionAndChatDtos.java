package com.nexus.chat.dto.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
    }

    @Data
    public static class ChatContext {
        private Long chatId;
    }

    @Data
    public static class Options {
        private AgentEnums.Visibility visibility = AgentEnums.Visibility.PRIVATE_ONLY;
        private Integer maxOutputTokens = 1024;
        private Double temperature = 0.2;
        private Integer maxIterations = 6;
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

package com.nexus.chat.dto.agent;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

public final class AgentModeBDtos {

    private AgentModeBDtos() {}

    @Data
    public static class SummarizeRequest {
        @NotNull
        private AgentEnums.SummaryRangeType summaryRangeType;
        private Integer rangeValue;
        private String outputStyle = "BULLET";
        private AgentEnums.Visibility visibility = AgentEnums.Visibility.PRIVATE_ONLY;
    }

    @Data
    public static class TodoExtractRequest {
        @NotNull
        private AgentEnums.SummaryRangeType summaryRangeType;
        private Integer rangeValue;
        private AgentEnums.Visibility visibility = AgentEnums.Visibility.PRIVATE_ONLY;
    }

    @Data
    public static class ReplySuggestRequest {
        @NotNull
        private Long targetMessageId;
        private String tone = "PROFESSIONAL";
        private String length = "SHORT";
        private AgentEnums.Visibility visibility = AgentEnums.Visibility.PRIVATE_ONLY;
    }

    @Data
    public static class ReplyPublishRequest {
        @NotNull
        private String draft;
        private String source = "AGENT_REPLY_SUGGEST";
    }

    @Data
    public static class TodoItem {
        private String owner;
        private String task;
        private String dueAt;
        private Double confidence;
    }

    @Data
    public static class ReplySuggestData {
        private String draft;
        private List<String> alternatives;
    }
}

package com.nexus.chat.dto.agent;

public final class AgentEnums {

    private AgentEnums() {}

    public enum OperationType {
        ASSISTANT_CHAT,
        CHAT_SUMMARY,
        TODO_EXTRACT,
        REPLY_SUGGEST
    }

    public enum Visibility {
        PRIVATE_ONLY,
        PUBLISH_TO_CHAT
    }

    public enum SummaryRangeType {
        LAST_N_MESSAGES,
        LAST_24H
    }
}

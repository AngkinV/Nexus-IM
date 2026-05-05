package com.nexus.chat.dto.agent;

import jakarta.validation.constraints.Size;
import lombok.Data;

public final class AgentSessionDtos {

    private AgentSessionDtos() {}

    @Data
    public static class CreateSessionRequest {
        @Size(max = 100)
        private String title;
    }

    @Data
    public static class RenameRequest {
        @Size(max = 100)
        private String title;
    }

    @Data
    public static class SessionView {
        private String sessionId;
        private String title;
        private String createdAt;
        private String updatedAt;
    }
}

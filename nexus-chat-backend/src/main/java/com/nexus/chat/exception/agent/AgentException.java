package com.nexus.chat.exception.agent;

import lombok.Getter;

import java.util.Map;

@Getter
public class AgentException extends RuntimeException {
    private final AgentErrorCode code;
    private final Map<String, Object> details;

    public AgentException(AgentErrorCode code) {
        super(code.getDefaultMessage());
        this.code = code;
        this.details = Map.of();
    }

    public AgentException(AgentErrorCode code, String message) {
        super(message);
        this.code = code;
        this.details = Map.of();
    }

    public AgentException(AgentErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }
}

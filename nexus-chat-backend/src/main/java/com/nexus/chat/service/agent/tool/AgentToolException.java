package com.nexus.chat.service.agent.tool;

import lombok.Getter;

/** A tool failure the model is allowed to see, reported as an MCP tool error. */
@Getter
public class AgentToolException extends RuntimeException {

    public enum Code { FORBIDDEN, NOT_FOUND, BAD_ARG, NO_ACTOR, INTERNAL }

    private final Code code;

    public AgentToolException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public static AgentToolException notFound(String message) {
        return new AgentToolException(Code.NOT_FOUND, message);
    }

    public static AgentToolException badArg(String message) {
        return new AgentToolException(Code.BAD_ARG, message);
    }
}

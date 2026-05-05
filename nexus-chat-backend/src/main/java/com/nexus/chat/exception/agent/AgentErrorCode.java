package com.nexus.chat.exception.agent;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum AgentErrorCode {
    AGENT_AUTH_40101(HttpStatus.UNAUTHORIZED, "JWT invalid or expired"),
    AGENT_AUTHZ_40301(HttpStatus.FORBIDDEN, "No permission for this chat"),
    AGENT_PARAM_40001(HttpStatus.BAD_REQUEST, "Parameter validation failed"),
    AGENT_SESSION_40401(HttpStatus.NOT_FOUND, "Session not found"),
    AGENT_KB_40402(HttpStatus.NOT_FOUND, "Knowledge base not found"),
    AGENT_KB_DOC_40403(HttpStatus.NOT_FOUND, "Knowledge document not found"),
    AGENT_KB_FILE_40002(HttpStatus.BAD_REQUEST, "Unsupported or invalid uploaded file"),
    AGENT_KB_QUOTA_42902(HttpStatus.TOO_MANY_REQUESTS, "Knowledge base quota exceeded"),
    AGENT_KB_VALIDATION_42201(HttpStatus.UNPROCESSABLE_ENTITY, "Knowledge base validation failed"),
    AGENT_KB_INGEST_50203(HttpStatus.BAD_GATEWAY, "Knowledge ingestion failed"),
    AGENT_TOOL_50201(HttpStatus.BAD_GATEWAY, "Tool call failed"),
    AGENT_MODEL_50202(HttpStatus.BAD_GATEWAY, "Model call failed"),
    AGENT_TIMEOUT_50401(HttpStatus.GATEWAY_TIMEOUT, "Agent timeout"),
    AGENT_STREAM_40901(HttpStatus.CONFLICT, "Stream replay window expired"),
    AGENT_RATE_42901(HttpStatus.TOO_MANY_REQUESTS, "Rate limited"),
    AGENT_SYS_50001(HttpStatus.INTERNAL_SERVER_ERROR, "System error");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    AgentErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}

package com.nexus.chat.controller.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Scoped errors: never serialize a raw JSON/credential/request exception. */
@RestControllerAdvice(assignableTypes = {McpManagementController.class, InternalMcpRegistryController.class})
public class McpManagementErrors {
    private final ObjectMapper json;
    public McpManagementErrors(ObjectMapper json) { this.json = json; }

    private ResponseEntity<ObjectNode> response(int status, String code) {
        ObjectNode result = json.createObjectNode().put("code", code)
                .put("message", "MCP request could not be completed")
                .put("requestId", "tr_" + UUID.randomUUID().toString().replace("-", ""));
        result.putNull("data");
        return ResponseEntity.status(status).body(result);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ObjectNode> controlled(ResponseStatusException error) {
        String reason = error.getReason();
        String code = reason != null && reason.matches("MCP_[A-Z0-9_]{1,64}") ? reason : "MCP_REQUEST_FAILED";
        return response(error.getStatusCode().value(), code);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ObjectNode> conflict() {
        return response(HttpStatus.CONFLICT.value(), "MCP_CONFIG_CONFLICT");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ObjectNode> invalidJson() {
        return response(HttpStatus.BAD_REQUEST.value(), "MCP_INVALID_REQUEST");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ObjectNode> unavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE.value(), "MCP_CREDENTIAL_OR_DATA_UNAVAILABLE");
    }
}

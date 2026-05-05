package com.nexus.chat.exception.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class AgentExceptionHandler {

    @ExceptionHandler(AgentException.class)
    public ResponseEntity<Map<String, Object>> handleAgentException(AgentException ex) {
        log.warn("agent exception: code={}, message={}", ex.getCode().name(), ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode().name());
        body.put("message", ex.getMessage());
        body.put("requestId", UUID.randomUUID().toString());
        body.put("timestamp", OffsetDateTime.now().toString());
        if (!ex.getDetails().isEmpty()) {
            body.put("details", ex.getDetails());
        }
        return ResponseEntity.status(ex.getCode().getHttpStatus()).body(body);
    }
}

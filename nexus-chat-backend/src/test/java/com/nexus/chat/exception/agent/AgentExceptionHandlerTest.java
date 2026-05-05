package com.nexus.chat.exception.agent;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentExceptionHandlerTest {

    private final AgentExceptionHandler handler = new AgentExceptionHandler();

    @Test
    void handlesAuthorizationException() {
        AgentException ex = new AgentException(AgentErrorCode.AGENT_AUTHZ_40301, "no access", Map.of("chatId", 42L));
        ResponseEntity<Map<String, Object>> response = handler.handleAgentException(ex);

        assertEquals(403, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("AGENT_AUTHZ_40301", body.get("code"));
        assertEquals("no access", body.get("message"));
        assertNotNull(body.get("requestId"));
        assertNotNull(body.get("timestamp"));
        assertEquals(Map.of("chatId", 42L), body.get("details"));
    }

    @Test
    void omitsDetailsWhenEmpty() {
        AgentException ex = new AgentException(AgentErrorCode.AGENT_TIMEOUT_50401, "boom");
        ResponseEntity<Map<String, Object>> response = handler.handleAgentException(ex);

        assertEquals(504, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertFalse(body.containsKey("details"));
    }
}

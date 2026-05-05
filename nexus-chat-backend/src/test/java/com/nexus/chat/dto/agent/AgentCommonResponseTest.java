package com.nexus.chat.dto.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentCommonResponseTest {

    @Test
    void okBuildsStandardEnvelope() {
        AgentCommonResponse<String> resp = AgentCommonResponse.ok("req-1", "hello");
        assertEquals("OK", resp.getCode());
        assertEquals("success", resp.getMessage());
        assertEquals("req-1", resp.getRequestId());
        assertEquals("hello", resp.getData());
        assertNotNull(resp.getTimestamp());
    }
}

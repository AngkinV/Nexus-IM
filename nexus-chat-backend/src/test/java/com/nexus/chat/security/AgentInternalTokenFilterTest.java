package com.nexus.chat.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.config.AgentMcpConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentInternalTokenFilterTest {
    private static final String TOKEN = "fixture-internal-token";

    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    private void send(String configuredToken, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", AgentMcpConfig.MCP_PATH);
        if (authorization != null) request.addHeader("Authorization", authorization);
        new AgentInternalTokenFilter(configuredToken).doFilter(request, response, chain);
    }

    private void assertRejectedBeforeTheServlet() throws Exception {
        assertNull(chain.getRequest(), "the servlet must not be reached");
        assertEquals(401, response.getStatus());
        assertNull(response.getErrorMessage(), "sendError would forward to /error and come back as 403");
        assertNull(response.getForwardedUrl());
        assertEquals("AGENT_AUTH_40101", new ObjectMapper().readTree(response.getContentAsString()).path("code").asText());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertFalse(response.getContentAsString().contains(TOKEN));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "Bearer", "Bearer ", "Bearer wrong-token", "Bearer fixture-internal-toke",
            "Bearer fixture-internal-token-and-more", "Basic fixture-internal-token", "fixture-internal-token",
            "bearer fixture-internal-token"})
    void aMissingOrWrongBearerIsUnauthorized(String authorization) throws Exception {
        send(TOKEN, authorization);
        assertRejectedBeforeTheServlet();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void anUnconfiguredTokenRejectsEverything(String configured) throws Exception {
        send(configured, "Bearer " + configured);
        assertRejectedBeforeTheServlet();
    }

    @Test
    void anUnconfiguredTokenRejectsARealLookingBearerToo() throws Exception {
        send("", "Bearer " + TOKEN);
        assertRejectedBeforeTheServlet();
    }

    @Test
    void theRightBearerPasses() throws Exception {
        send(TOKEN, "Bearer " + TOKEN);
        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
        assertEquals("", response.getContentAsString());
    }

    @Test
    void theFilterGuardsTheInternalAgentPathsAndRunsFirst() {
        FilterRegistrationBean<AgentInternalTokenFilter> registration = new AgentMcpConfig().agentMcpTokenFilter(TOKEN);
        // The scope-validate endpoint is internal too, so it shares the same bearer gate.
        assertEquals(List.of(AgentMcpConfig.MCP_PATH, "/internal/agent/scope/validate"),
                List.copyOf(registration.getUrlPatterns()));
        assertEquals(Ordered.HIGHEST_PRECEDENCE, registration.getOrder());
    }
}

package com.nexus.chat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards the agent MCP servlet with the shared internal bearer token. The
 * servlet is not a Spring MVC controller and {@code /internal/agent/**} is
 * {@code permitAll} in Spring Security, so this filter is the only gate.
 */
public class AgentInternalTokenFilter extends HttpFilter {

    private final String configuredToken;

    public AgentInternalTokenFilter(String configuredToken) {
        this.configuredToken = configuredToken;
    }

    /** Constant-time check of an {@code Authorization: Bearer} header. A blank configured token never matches. */
    public static boolean bearerMatches(String configuredToken, String authorization) {
        if (configuredToken == null || configuredToken.isBlank()
                || authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        return MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                authorization.substring(7).trim().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (bearerMatches(configuredToken, request.getHeader("Authorization"))) {
            chain.doFilter(request, response);
            return;
        }
        // Written directly: sendError would forward to /error, which Spring Security turns into a 403.
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"AGENT_AUTH_40101\",\"message\":\"Internal token invalid\"}");
    }
}

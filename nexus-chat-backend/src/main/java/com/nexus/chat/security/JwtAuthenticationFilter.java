package com.nexus.chat.security;

import com.nexus.chat.repository.UserSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT authentication filter for REST API endpoints.
 * Extracts and validates JWT from Authorization header, sets SecurityContext.
 * Also enforces session revocation: a signature-valid token whose session was
 * revoked (its row deleted) is rejected with 401 so the device is logged out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserSessionRepository sessionRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (jwtTokenProvider.validateToken(token)) {
                String sessionToken = jwtTokenProvider.getSessionTokenFromToken(token);

                // Session-backed tokens: if the session was revoked, reject (401).
                // Legacy tokens without a session id are left alone for compatibility.
                if (sessionToken != null && !sessionRepository.existsBySessionToken(sessionToken)) {
                    log.debug("会话已被吊销, 拒绝请求: {}", request.getRequestURI());
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":\"SESSION_REVOKED\",\"message\":\"session revoked\"}");
                    return;
                }

                Long userId = jwtTokenProvider.getUserIdFromToken(token);
                String username = jwtTokenProvider.getUsernameFromToken(token);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userId, null, Collections.emptyList());
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);

                // Expose the session token so endpoints can identify "this device".
                if (sessionToken != null) {
                    request.setAttribute("sessionToken", sessionToken);
                }

                log.debug("JWT 认证成功: userId={}, username={}", userId, username);
            } else {
                log.debug("JWT token 无效: {}", request.getRequestURI());
            }
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Skip JWT filter for auth endpoints, WebSocket, and internal Agent API (which uses its own Bearer token)
        return path.startsWith("/api/auth/") || path.startsWith("/ws") || path.startsWith("/internal/agent/");
    }
}

package com.nexus.chat.config;

import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.PresenceService;
import com.nexus.chat.service.WebSocketSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * WebSocket Channel Interceptor for JWT-based user authentication.
 * Also handles distributed session registration on CONNECT/DISCONNECT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final WebSocketSessionRegistry sessionRegistry;
    private final PresenceService presenceService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            handleConnect(accessor);
        } else if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            handleDisconnect(accessor);
        }

        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        Long userId = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtTokenProvider.validateToken(token)) {
                userId = jwtTokenProvider.getUserIdFromToken(token);
                accessor.setUser(new StompPrincipal(String.valueOf(userId)));
                log.info("WebSocket JWT 认证成功: userId={}", userId);
            } else {
                log.warn("WebSocket JWT token 无效");
                throw new MessageDeliveryException("Invalid JWT token");
            }
        } else {
            // Fallback: accept userId header for backwards compatibility during migration
            String userIdStr = accessor.getFirstNativeHeader("userId");
            if (userIdStr != null && !userIdStr.isEmpty()) {
                accessor.setUser(new StompPrincipal(userIdStr));
                userId = Long.parseLong(userIdStr);
                log.warn("WebSocket 使用不安全的 userId 头连接: userId={} (应迁移到 JWT)", userId);
            } else {
                throw new MessageDeliveryException("Missing authentication: provide Authorization header with Bearer token");
            }
        }

        // Register session in distributed registry
        if (userId != null) {
            String sessionId = accessor.getSessionId();
            sessionRegistry.registerSession(userId, sessionId);
            presenceService.userConnected(userId, sessionId);
        }
    }

    private void handleDisconnect(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user != null) {
            try {
                Long userId = Long.parseLong(user.getName());
                String sessionId = accessor.getSessionId();
                sessionRegistry.unregisterSession(userId, sessionId);
                presenceService.userDisconnected(userId, sessionId);
                log.info("WebSocket 断开: userId={}, sessionId={}", userId, sessionId);
            } catch (NumberFormatException e) {
                log.warn("WebSocket 断开时无法解析 userId: {}", user.getName());
            }
        }
    }

    /**
     * Simple Principal implementation for WebSocket users
     */
    static class StompPrincipal implements Principal {
        private final String name;

        public StompPrincipal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}

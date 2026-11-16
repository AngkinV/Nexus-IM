package com.nexus.chat.config;

import com.nexus.chat.repository.UserSessionRepository;
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
    private final UserSessionRepository sessionRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        // STOMP is the 1.2 alias of CONNECT and opens a session the same way.
        if (StompCommand.CONNECT.equals(accessor.getCommand()) || StompCommand.STOMP.equals(accessor.getCommand())) {
            handleConnect(accessor);
        } else if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            handleDisconnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand()) || StompCommand.SEND.equals(accessor.getCommand())) {
            authorizeDestination(accessor);
        }

        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new MessageDeliveryException("Missing authentication: provide Authorization header with Bearer token");
        }
        String token = authHeader.substring(7);
        if (!jwtTokenProvider.validateToken(token)) {
            log.warn("WebSocket JWT token 无效");
            throw new MessageDeliveryException("Invalid JWT token");
        }
        // Enforce session revocation: a revoked device can't (re)connect.
        String sid = jwtTokenProvider.getSessionTokenFromToken(token);
        if (sid != null && !sessionRepository.existsBySessionToken(sid)) {
            log.warn("WebSocket 会话已被吊销, 拒绝连接");
            throw new MessageDeliveryException("Session revoked");
        }
        Long userId = jwtTokenProvider.getUserIdFromToken(token);
        accessor.setUser(new StompPrincipal(String.valueOf(userId)));
        log.info("WebSocket JWT 认证成功: userId={}", userId);

        // Register session in distributed registry
        sessionRegistry.registerSession(userId, accessor.getSessionId());
        presenceService.userConnected(userId, accessor.getSessionId());
    }

    private void authorizeDestination(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        String destination = accessor.getDestination();
        // A frame sent without a prior authenticated CONNECT has no user.
        if (user == null || destination == null) {
            throw new MessageDeliveryException("Not authenticated");
        }
        boolean allowed = StompCommand.SEND.equals(accessor.getCommand())
                // Clients publish to handlers only; a SEND straight to /topic, /queue or /user/{id}
                // would reach the broker and forge events for other users.
                ? destination.startsWith("/app/")
                // Allow-list, not a check on "/topic/user.{other}": the simple broker matches Ant
                // patterns, so "/topic/**" would otherwise receive every user's events.
                // "/user/..." is resolved by Spring to a queue private to this session.
                : destination.startsWith("/user/") || destination.startsWith("/topic/user." + user.getName() + ".");
        if (!allowed) {
            log.warn("WebSocket {} 被拒绝: user={}, destination={}", accessor.getCommand(), user.getName(), destination);
            throw new MessageDeliveryException("Destination not allowed: " + destination);
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

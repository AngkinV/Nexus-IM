package com.nexus.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * WebSocket Session Registry backed by Redis.
 * Tracks which sessions belong to which users across all instances.
 * Enables multi-device support and targeted message delivery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketSessionRegistry {

    private final RedisCacheService redisCacheService;

    /**
     * Register a new WebSocket session for a user.
     */
    public void registerSession(Long userId, String sessionId) {
        redisCacheService.registerWsSession(userId, sessionId);
        log.debug("注册 WS 会话: userId={}, sessionId={}", userId, sessionId);
    }

    /**
     * Unregister a WebSocket session when disconnected.
     */
    public void unregisterSession(Long userId, String sessionId) {
        redisCacheService.unregisterWsSession(userId, sessionId);
        log.debug("注销 WS 会话: userId={}, sessionId={}", userId, sessionId);
    }

    /**
     * Get all active sessions for a user.
     */
    public Set<String> getUserSessions(Long userId) {
        return redisCacheService.getWsSessions(userId);
    }

    /**
     * Check if a user has any active sessions.
     */
    public boolean hasActiveSessions(Long userId) {
        Set<String> sessions = getUserSessions(userId);
        return sessions != null && !sessions.isEmpty();
    }
}

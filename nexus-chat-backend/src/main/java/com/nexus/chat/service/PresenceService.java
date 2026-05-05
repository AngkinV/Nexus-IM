package com.nexus.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Redis-backed Presence system.
 * Replaces MySQL-based online status with 90s TTL + 30s heartbeat.
 * Supports multi-device (multiple sessions per user).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private final RedisCacheService redisCacheService;

    /**
     * Register a user as online when they connect via WebSocket.
     */
    public void userConnected(Long userId, String sessionId) {
        redisCacheService.registerWsSession(userId, sessionId);
        // instanceId is "default" for single-instance; Phase 5 will pass actual instance ID
        redisCacheService.setUserOnline(userId, sessionId, "default");
        log.info("用户上线: userId={}, sessionId={}", userId, sessionId);
    }

    /**
     * Handle heartbeat - refresh presence TTL.
     * Called every 30 seconds by the client.
     */
    public void refreshPresence(Long userId) {
        redisCacheService.refreshPresence(userId);
    }

    /**
     * Handle WebSocket disconnection.
     * Only marks user as offline if this was their last active session.
     */
    public boolean userDisconnected(Long userId, String sessionId) {
        redisCacheService.unregisterWsSession(userId, sessionId);
        Set<String> remainingSessions = redisCacheService.getWsSessions(userId);

        if (remainingSessions == null || remainingSessions.isEmpty()) {
            redisCacheService.setUserOffline(userId, sessionId);
            log.info("用户下线 (最后一个会话): userId={}, sessionId={}", userId, sessionId);
            return true; // User went fully offline
        }

        log.info("用户断开一个会话 (仍有 {} 个活跃会话): userId={}, sessionId={}",
                remainingSessions.size(), userId, sessionId);
        return false; // User still has other sessions
    }

    /**
     * Check if a specific user is online.
     */
    public boolean isUserOnline(Long userId) {
        return redisCacheService.isUserOnline(userId);
    }

    /**
     * Batch check online statuses for multiple users.
     * Used when loading contacts list.
     */
    public Map<Long, Boolean> getOnlineStatuses(Collection<Long> userIds) {
        return redisCacheService.getOnlineStatuses(userIds);
    }

    /**
     * Get all active sessions for a user (multi-device support).
     */
    public Set<String> getUserSessions(Long userId) {
        return redisCacheService.getWsSessions(userId);
    }
}

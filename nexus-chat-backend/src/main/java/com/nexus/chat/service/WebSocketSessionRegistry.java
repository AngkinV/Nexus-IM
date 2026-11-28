package com.nexus.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Distributed presence plus this instance's actual transports, keyed separately from login sessions. */
@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class WebSocketSessionRegistry {
    private final RedisCacheService redisCacheService;
    private final AuthSessionValidator authValidator;
    private final Map<String, WebSocketSession> transports = new ConcurrentHashMap<>();
    private final Map<String, LoginIdentity> identities = new ConcurrentHashMap<>();

    /** Compatibility for local callers; the Spring constructor always uses the configured policy. */
    public WebSocketSessionRegistry(RedisCacheService redisCacheService) {
        this(redisCacheService, new AuthSessionValidator(null, new PublicAuthPolicy(false, false)));
    }

    private record LoginIdentity(Long userId, String token, Instant expiresAt) {
        @Override public String toString() { return "LoginIdentity[redacted]"; }
    }

    public synchronized void trackTransport(WebSocketSession session) {
        transports.put(session.getId(), session);
    }

    public void removeTransport(String sessionId) {
        LoginIdentity identity;
        synchronized (this) {
            transports.remove(sessionId);
            identity = identities.remove(sessionId);
        }
        if (identity != null) removeDistributed(identity.userId(), sessionId);
    }

    /** Binding and post-commit revocation are serialized, so a concurrent CONNECT cannot escape closing. */
    public void registerAuthenticatedSession(Long userId, String sessionId, String token,
                                             Instant expiresAt, Runnable onConnected) {
        if (sessionId == null || userId == null || token == null || token.isBlank() || expiresAt == null) {
            closeSession(sessionId, "session_revoked");
            throw new MessageDeliveryException("Missing login session");
        }
        RuntimeException failure = null;
        synchronized (this) {
            if (!transports.containsKey(sessionId)) {
                throw new MessageDeliveryException("Connection transport is unavailable");
            }
            if (identities.containsKey(sessionId)) {
                // A transport cannot change authenticated identity by sending another CONNECT.
                throw new MessageDeliveryException("Connection is already authenticated");
            }
            identities.put(sessionId, new LoginIdentity(userId, token, expiresAt));
            if (!isSessionAuthorized(sessionId)) {
                failure = new MessageDeliveryException("Session revoked");
            } else {
                try {
                    registerSession(userId, sessionId);
                    onConnected.run();
                } catch (RuntimeException unavailable) {
                    failure = new MessageDeliveryException("Session unavailable");
                }
            }
        }
        if (failure != null) {
            closeSession(sessionId, "session_revoked");
            throw failure;
        }
    }

    public boolean enforced() { return authValidator.enforced(); }

    /** Checked on both inbound and outbound frames, not just on the initial CONNECT. */
    public boolean isSessionAuthorized(String sessionId) {
        if (!authValidator.enforced()) return true;
        LoginIdentity identity = sessionId == null ? null : identities.get(sessionId);
        if (identity == null || !identity.expiresAt().isAfter(Instant.now())) return false;
        try { return authValidator.active(identity.userId(), identity.token()); }
        catch (RuntimeException unavailable) { return false; }
    }

    public void closeLoginSessions(Long userId, Collection<String> tokens, String reason) {
        if (!authValidator.enforced() || userId == null || tokens == null || tokens.isEmpty()) return;
        List<String> sessions;
        synchronized (this) {
            sessions = identities.entrySet().stream()
                    .filter(entry -> userId.equals(entry.getValue().userId())
                            && tokens.contains(entry.getValue().token()))
                    .map(Map.Entry::getKey).toList();
        }
        // Transport I/O stays outside the registry lock; a slow client cannot block other registrations.
        sessions.forEach(session -> closeSession(session, reason));
    }

    public void closeSession(String sessionId, String reason) {
        if (sessionId == null) return;
        WebSocketSession transport;
        LoginIdentity identity;
        synchronized (this) {
            identity = identities.remove(sessionId);
            transport = transports.remove(sessionId);
        }
        String safeReason = reason != null && Set.of("new_login", "password_changed", "logout", "session_revoked").contains(reason)
                ? reason : "session_revoked";
        if (transport != null && transport.isOpen()) {
            try { transport.close(new CloseStatus(4001, safeReason)); }
            catch (Exception failed) { log.warn("Could not close a revoked transport ({})", failed.getClass().getSimpleName()); }
        }
        if (identity != null) removeDistributed(identity.userId(), sessionId);
    }

    /** Cross-instance pub/sub is immediate; this bounded sweep also handles JWT expiry or a lost relay. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void closeInvalidSessions() {
        if (!authValidator.enforced()) return;
        List<String> candidates = List.copyOf(identities.keySet());
        candidates.stream().filter(id -> !isSessionAuthorized(id)).forEach(id -> closeSession(id, "session_revoked"));
    }

    private void removeDistributed(Long userId, String sessionId) {
        try { redisCacheService.unregisterWsSession(userId, sessionId); }
        catch (RuntimeException unavailable) { log.warn("Could not clear socket presence ({})", unavailable.getClass().getSimpleName()); }
    }

    public void registerSession(Long userId, String sessionId) {
        redisCacheService.registerWsSession(userId, sessionId);
    }

    public void unregisterSession(Long userId, String sessionId) {
        identities.remove(sessionId);
        redisCacheService.unregisterWsSession(userId, sessionId);
    }

    public Set<String> getUserSessions(Long userId) { return redisCacheService.getWsSessions(userId); }

    public boolean hasActiveSessions(Long userId) {
        Set<String> sessions = getUserSessions(userId);
        return sessions != null && !sessions.isEmpty();
    }
}

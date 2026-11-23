package com.nexus.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import com.nexus.chat.repository.UserPrivacySettingsRepository;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Display authority: authenticated transport sessions, never HTTP login flags. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {
    private final RedisCacheService redisCacheService;
    private final UserPrivacySettingsRepository privacyRepository;
    private final ApplicationEventPublisher events;
    // Only CONNECT authentication may populate this registry. Lease expiry does not
    // remove a still-connected transport, allowing its next authenticated heartbeat to recover.
    private final Map<String, Long> liveSessions = new ConcurrentHashMap<>();
    public record Changed(Long userId, boolean online) {}

    public synchronized void userConnected(Long userId, String sessionId) {
        if (userId == null || sessionId == null) return;
        Long owner = liveSessions.putIfAbsent(sessionId, userId);
        if (owner != null && !owner.equals(userId)) return;
        refreshPresence(userId, sessionId);
    }

    public synchronized void refreshPresence(Long userId, String sessionId) {
        if (sessionId == null || !Objects.equals(userId, liveSessions.get(sessionId))) return;
        transition(userId, sessionId, "refresh");
        try { redisCacheService.recordOnlineHeartbeat(userId); }
        catch (RuntimeException e) { log.warn("Presence accounting unavailable for {}", userId); }
    }

    /** Compatibility query only: HTTP/user-only calls cannot extend a WS lease. */
    public void refreshPresence(Long userId) { isUserOnline(userId); }

    public synchronized boolean userDisconnected(Long userId, String sessionId) {
        if (sessionId == null || !liveSessions.remove(sessionId, userId)) return false;
        return transition(userId, sessionId, "remove") == -1;
    }

    @EventListener
    public void transportDisconnected(SessionDisconnectEvent event) {
        Long owner = liveSessions.get(event.getSessionId());
        if (owner != null) userDisconnected(owner, event.getSessionId());
    }

    private long transition(Long id, String session, String operation) {
        try {
            Long change = redisCacheService.updatePresenceLease(id, session, operation, System.currentTimeMillis());
            if (change != null && change != 0) events.publishEvent(new Changed(id, change > 0));
            return change == null ? 0 : change;
        } catch (RuntimeException e) {
            log.warn("Presence lease unavailable for {}", id);
            return 0;
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void sweepExpiredLeases() {
        try {
            for (Long id : redisCacheService.getLeasedUserIds()) transition(id, null, "sweep");
        } catch (RuntimeException e) { log.warn("Presence expiry sweep unavailable"); }
    }

    public boolean isUserOnline(Long userId) {
        try { return Boolean.TRUE.equals(redisCacheService.hasPresenceLease(userId, System.currentTimeMillis())); }
        catch (RuntimeException e) { return false; }
    }

    public Boolean getVisibleStatus(Long userId) {
        try {
            var privacy = privacyRepository.findByUserId(userId).orElse(null);
            if (privacy != null && !Boolean.TRUE.equals(privacy.getShowOnlineStatus())) return null;
            return redisCacheService.hasPresenceLease(userId, System.currentTimeMillis());
        } catch (RuntimeException e) { return null; }
    }

    public LocalDateTime getVisibleLastSeen(Long userId) {
        try {
            var privacy = privacyRepository.findByUserId(userId).orElse(null);
            if (privacy != null && !Boolean.TRUE.equals(privacy.getShowLastSeen())) return null;
            return redisCacheService.getPresenceLastSeen(userId);
        } catch (RuntimeException e) { return null; }
    }

    public Map<Long, Boolean> getOnlineStatuses(Collection<Long> ids) {
        Map<Long, Boolean> result = new HashMap<>();
        if (ids != null) ids.forEach(id -> result.put(id, getVisibleStatus(id)));
        return result;
    }

    public Set<String> getUserSessions(Long id) {
        Set<String> result = new HashSet<>();
        liveSessions.forEach((session, owner) -> { if (owner.equals(id)) result.add(session); });
        return result;
    }
}

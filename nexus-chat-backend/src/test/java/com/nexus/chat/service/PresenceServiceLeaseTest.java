package com.nexus.chat.service;

import com.nexus.chat.model.UserPrivacySettings;
import com.nexus.chat.repository.UserPrivacySettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No Spring context, database, Redis server or sockets. Stateful lease fake tests
 * lifecycle outputs; execution of the production Lua remains an integration gate. */
class PresenceServiceLeaseTest {
    final RedisCacheService cache = mock(RedisCacheService.class);
    final UserPrivacySettingsRepository privacy = mock(UserPrivacySettingsRepository.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final PresenceService service = new PresenceService(cache, privacy, events);
    final Map<String, Long> leases = new HashMap<>();
    final Set<Long> indexed = new HashSet<>();

    @BeforeEach
    void leases() {
        when(privacy.findByUserId(7L)).thenReturn(Optional.empty());
        when(cache.updatePresenceLease(eq(7L), nullable(String.class), anyString(), anyLong())).thenAnswer(call -> {
            long now = call.getArgument(3);
            int before = leases.size();
            boolean live = leases.values().stream().anyMatch(deadline -> deadline > now);
            leases.values().removeIf(deadline -> deadline <= now);
            String mode = call.getArgument(2), session = call.getArgument(1);
            if (mode.equals("refresh")) leases.put(session, now + 90000);
            if (mode.equals("remove")) leases.remove(session);
            if (leases.isEmpty()) indexed.remove(7L); else indexed.add(7L);
            return !live && !leases.isEmpty() ? 1L : before > 0 && leases.isEmpty() ? -1L : 0L;
        });
        when(cache.hasPresenceLease(eq(7L), anyLong())).thenAnswer(call -> {
            long now = call.getArgument(1);
            return leases.values().stream().anyMatch(deadline -> deadline > now);
        });
        when(cache.getLeasedUserIds()).thenAnswer(call -> new HashSet<>(indexed));
    }

    @Test
    void abnormalTransportCloseUsesSameIdempotentDisconnect() {
        service.userConnected(7L, "a");
        var message = org.springframework.messaging.support.MessageBuilder.withPayload(new byte[0]).build();
        var event = new org.springframework.web.socket.messaging.SessionDisconnectEvent(
                this, message, "a", org.springframework.web.socket.CloseStatus.SERVER_ERROR);
        service.transportDisconnected(event);
        service.transportDisconnected(event);
        assertTrue(leases.isEmpty());
        verify(events, times(1)).publishEvent(new PresenceService.Changed(7L, false));
    }

    @Test
    void redisFailureIsNeverConfirmedOnline() {
        when(cache.hasPresenceLease(eq(7L), anyLong())).thenThrow(new IllegalStateException("redis down"));
        assertNull(service.getVisibleStatus(7L));
        assertFalse(service.isUserOnline(7L));
    }

    @Test
    void multiWindowAndRepeatedDisconnectHaveOnlyFirstAndLastTransitions() {
        service.userConnected(7L, "a");
        service.userConnected(7L, "b");
        assertEquals(2, leases.size());
        assertFalse(service.userDisconnected(7L, "a"));
        assertTrue(service.isUserOnline(7L));
        assertFalse(service.userDisconnected(7L, "a"));
        assertTrue(service.userDisconnected(7L, "b"));
        assertFalse(service.isUserOnline(7L));
        verify(events, times(1)).publishEvent(new PresenceService.Changed(7L, true));
        verify(events, times(1)).publishEvent(new PresenceService.Changed(7L, false));
        assertTrue(indexed.isEmpty());
    }

    @Test
    void expiredLiveTransportHeartbeatRestoresLeaseButForgedTransportCannot() {
        service.userConnected(7L, "a");
        leases.put("a", 0L);
        assertFalse(service.isUserOnline(7L));
        service.refreshPresence(7L, "fake");
        assertEquals(0L, leases.get("a"));
        service.refreshPresence(7L, "a");
        assertTrue(leases.get("a") > System.currentTimeMillis());
        assertTrue(service.isUserOnline(7L));
        service.userDisconnected(7L, "a");
        service.refreshPresence(7L, "a");
        assertTrue(leases.isEmpty());
    }

    @Test
    void sweeperEmitsOfflineOnceAndCleansIndex() {
        service.userConnected(7L, "a");
        leases.put("a", 0L);
        service.sweepExpiredLeases();
        service.sweepExpiredLeases();
        assertTrue(leases.isEmpty());
        assertTrue(indexed.isEmpty());
        verify(events, times(1)).publishEvent(new PresenceService.Changed(7L, false));
    }

    @Test
    void httpRefreshNeverCreatesLeaseAndPrivacyOrBackendFailureIsUnknown() {
        service.refreshPresence(7L);
        assertEquals(Boolean.FALSE, service.getVisibleStatus(7L));
        assertTrue(leases.isEmpty());
        UserPrivacySettings settings = new UserPrivacySettings();
        settings.setShowOnlineStatus(false);
        when(privacy.findByUserId(7L)).thenReturn(Optional.of(settings));
        assertNull(service.getVisibleStatus(7L));
        when(privacy.findByUserId(7L)).thenThrow(new IllegalStateException("unavailable"));
        assertNull(service.getVisibleStatus(7L));
    }
}

package com.nexus.chat.config;

import com.nexus.chat.repository.UserSessionRepository;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.*;
import com.nexus.chat.util.TrustedLogoutNotice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.sockjs.transport.session.WebSocketServerSockJsSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Executes the real registry + STOMP guards; synthetic auth rows and transports, never sockets/Redis. */
class WebSocketSessionSecurityTest {
    private final UserSessionRepository rows = mock(UserSessionRepository.class);
    private final RedisCacheService cache = mock(RedisCacheService.class);
    private final PresenceService presence = mock(PresenceService.class);
    private final JwtTokenProvider jwt = new JwtTokenProvider();
    private final Map<String, Long> active = new ConcurrentHashMap<>();
    private WebSocketSessionRegistry registry;
    private WebSocketAuthChannelInterceptor inbound;
    private WebSocketSessionOutboundInterceptor outbound;

    @BeforeEach void setup() {
        when(rows.existsBySessionToken(anyString())).thenAnswer(call -> active.containsKey(call.getArgument(0)));
        when(rows.isActiveSession(anyLong(), anyString(), any(), eq(true))).thenAnswer(call ->
                call.<Long>getArgument(0).equals(active.get(call.<String>getArgument(1))));
        ReflectionTestUtils.setField(jwt, "jwtSecret", UUID.randomUUID().toString() + UUID.randomUUID());
        ReflectionTestUtils.setField(jwt, "jwtExpiration", 120_000L);
        registry = new WebSocketSessionRegistry(cache, new AuthSessionValidator(rows, new PublicAuthPolicy(true, true)));
        inbound = new WebSocketAuthChannelInterceptor(jwt, registry, presence, rows);
        outbound = new WebSocketSessionOutboundInterceptor(registry);
    }

    private WebSocketSession transport(String wsId, boolean sockJs) {
        WebSocketSession socket = sockJs ? mock(WebSocketServerSockJsSession.class) : mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(wsId);
        when(socket.isOpen()).thenReturn(true);
        registry.trackTransport(socket);
        return socket;
    }
    private StompHeaderAccessor frame(String wsId, StompCommand command, Long owner, String destination) {
        var frame = StompHeaderAccessor.create(command);
        frame.setSessionId(wsId); frame.setLeaveMutable(true);
        if (owner != null) frame.setUser(() -> owner.toString());
        frame.setDestination(destination);
        return frame;
    }
    private Message<?> send(StompHeaderAccessor frame) {
        return inbound.preSend(MessageBuilder.createMessage(new byte[0], frame.getMessageHeaders()), null);
    }
    private void connect(String wsId, Long owner, String sid) {
        active.put(sid, owner);
        var connect = frame(wsId, StompCommand.CONNECT, null, null);
        connect.setNativeHeader("Authorization", "Bearer " + jwt.generateToken(owner, "synthetic", sid));
        connect.setNativeHeader("userId", "9999");
        send(connect);
        assertEquals(owner.toString(), connect.getUser().getName());
    }
    private Message<?> delivery(String wsId) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(wsId);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    @Test void aRevocationClosesBothSockJsAndNativeTransportsNotTheNewOrAnotherOwnersConnection() throws Exception {
        WebSocketSession nativeOld = transport("native-old", false), sockJsOld = transport("sockjs-old", true);
        WebSocketSession next = transport("new", false), other = transport("other", true);
        String oldSid = UUID.randomUUID().toString(), nextSid = UUID.randomUUID().toString(), otherSid = UUID.randomUUID().toString();
        connect("native-old", 7L, oldSid); connect("sockjs-old", 7L, oldSid);
        connect("new", 7L, nextSid); connect("other", 8L, otherSid);
        active.remove(oldSid);
        registry.closeLoginSessions(7L, List.of(oldSid), "new_login");
        verify(nativeOld).close(argThat(status -> status.getCode() == 4001 && status.getReason().equals("new_login")));
        verify(sockJsOld).close(argThat(status -> status.getCode() == 4001));
        verify(next, never()).close(any(CloseStatus.class)); verify(other, never()).close(any(CloseStatus.class));
        assertTrue(registry.isSessionAuthorized("new")); assertTrue(registry.isSessionAuthorized("other"));
        assertNull(outbound.preSend(delivery("native-old"), null));
        assertThrows(MessageDeliveryException.class, () -> send(frame("sockjs-old", StompCommand.SEND, 7L, "/app/chat.sendMessage")));
        assertNotNull(outbound.preSend(delivery("new"), null));
    }

    @Test void revokedFramesAreBlockedEvenBeforeAPubSubNoticeArrivesAndTheSocketIsClosed() throws Exception {
        var socket = transport("revoked", true); String sid = UUID.randomUUID().toString(); connect("revoked", 7L, sid);
        assertNotNull(send(frame("revoked", StompCommand.SUBSCRIBE, 7L, "/topic/user.7.messages")));
        assertNotNull(outbound.preSend(delivery("revoked"), null));
        active.remove(sid);
        assertNull(outbound.preSend(delivery("revoked"), null), "no business delivery after a revoked DB session");
        verify(socket).close(argThat(status -> status.getCode() == 4001));
        for (StompCommand command : List.of(StompCommand.SEND, StompCommand.SUBSCRIBE, StompCommand.ACK)) {
            assertThrows(MessageDeliveryException.class, () -> send(frame("revoked", command, 7L, "/app/chat.sendMessage")));
        }
    }

    @Test void invalidAndLegacyPublicConnectNeverCreatesARegisteredSession() {
        var legacy = frame("legacy", StompCommand.CONNECT, null, null);
        legacy.setNativeHeader("Authorization", "Bearer " + jwt.generateToken(7L, "synthetic"));
        assertThrows(MessageDeliveryException.class, () -> send(legacy));
        verifyNoInteractions(cache, presence);
        assertFalse(registry.isSessionAuthorized("legacy"));
    }

    @Test void jwtExpiryAndUnavailableSessionStoreFailClosedOnAlreadyOpenConnections() throws Exception {
        var socket = transport("expired", false); String sid = UUID.randomUUID().toString(); active.put(sid, 7L);
        assertThrows(MessageDeliveryException.class, () -> registry.registerAuthenticatedSession(7L, "expired", sid,
                Instant.now().minusSeconds(1), () -> fail("expired connection must not become online")));
        verify(socket).close(argThat(status -> status.getCode() == 4001));
        transport("unavailable", false); String next = UUID.randomUUID().toString(); connect("unavailable", 7L, next);
        when(rows.isActiveSession(eq(7L), eq(next), any(), eq(true))).thenThrow(new IllegalStateException("synthetic-store-down"));
        assertNull(outbound.preSend(delivery("unavailable"), null));
    }

    @Test void aConcurrentRevocationDuringConnectIsRejectedAndNeverMarkedOnline() throws Exception {
        var socket = transport("race", false); String sid = UUID.randomUUID().toString(); active.put(sid, 7L);
        when(rows.isActiveSession(eq(7L), eq(sid), any(), eq(true))).thenAnswer(call -> {
            active.remove(sid); return false;
        });
        AtomicBoolean online = new AtomicBoolean();
        assertThrows(MessageDeliveryException.class, () -> registry.registerAuthenticatedSession(7L, "race", sid,
                Instant.now().plusSeconds(60), () -> online.set(true)));
        assertFalse(online.get()); verify(cache, never()).registerWsSession(anyLong(), anyString());
        verify(socket).close(argThat(status -> status.getCode() == 4001));
    }

    @Test void onlyTheServerOwnedLogoutMarkerCanDeliverOneLastNoticeNeverAForgedClientCopy() {
        Message<?> notice = MessageBuilder.createMessage(new byte[0], TrustedLogoutNotice.headers());
        assertSame(notice, outbound.preSend(notice, null));
        var forged = frame("unknown", StompCommand.SEND, 7L, "/topic/user.7.messages");
        forged.setNativeHeader("nexus.internal.logout-notice", "true");
        assertNull(outbound.preSend(MessageBuilder.createMessage(new byte[0], forged.getMessageHeaders()), null));
        assertNull(outbound.preSend(delivery("unknown"), null));
    }

    @Test void configuredTransportDecoratorTracksAndRetiresTheRealNativeAndSockJsSessionObjects() throws Exception {
        WebSocketConfig config = new WebSocketConfig();
        ReflectionTestUtils.setField(config, "sessions", registry);
        var registration = new org.springframework.web.socket.config.annotation.WebSocketTransportRegistration();
        config.configureWebSocketTransport(registration);
        @SuppressWarnings("unchecked")
        var factories = (List<org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory>)
                ReflectionTestUtils.invokeMethod(registration, "getDecoratorFactories");
        assertEquals(1, factories.size());
        var delegate = mock(org.springframework.web.socket.WebSocketHandler.class);
        var handler = factories.get(0).decorate(delegate);
        for (boolean sockJs : List.of(false, true)) {
            String id = "decorated-" + sockJs, sid = UUID.randomUUID().toString();
            WebSocketSession socket = sockJs ? mock(WebSocketServerSockJsSession.class) : mock(WebSocketSession.class);
            when(socket.getId()).thenReturn(id); when(socket.isOpen()).thenReturn(true);
            handler.afterConnectionEstablished(socket);
            connect(id, 7L, sid);
            active.remove(sid); registry.closeLoginSessions(7L, List.of(sid), "new_login");
            verify(socket).close(argThat(status -> status.getCode() == 4001));
            handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
            assertFalse(registry.isSessionAuthorized(id));
            verify(delegate).afterConnectionEstablished(socket); verify(delegate).afterConnectionClosed(socket, CloseStatus.NORMAL);
        }
    }

    @Test void authenticationCannotSwitchIdentityOnAnAlreadyOpenTransportOrRegisterWithoutOne() {
        transport("bound", false); String sid = UUID.randomUUID().toString(); connect("bound", 7L, sid);
        String another = UUID.randomUUID().toString(); active.put(another, 8L);
        assertThrows(MessageDeliveryException.class, () -> registry.registerAuthenticatedSession(8L, "bound", another,
                Instant.now().plusSeconds(60), () -> fail("must not switch identities")));
        assertTrue(registry.isSessionAuthorized("bound"));
        assertThrows(MessageDeliveryException.class, () -> registry.registerAuthenticatedSession(8L, "untracked", another,
                Instant.now().plusSeconds(60), () -> fail("must retain a real transport")));
        assertFalse(registry.isSessionAuthorized("untracked"));
    }

    @Test void removalAndSweepRetireOnlyUnauthorizedConnectionsAndLocalModeKeepsItsBehavior() throws Exception {
        var revoked = transport("revoked", false); var valid = transport("valid", true);
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        connect("revoked", 7L, a); connect("valid", 8L, b); active.remove(a);
        registry.closeInvalidSessions();
        verify(revoked).close(any(CloseStatus.class)); verify(valid, never()).close(any(CloseStatus.class));
        registry.removeTransport("valid"); assertFalse(registry.isSessionAuthorized("valid"));
        var local = new WebSocketSessionRegistry(cache, new AuthSessionValidator(rows, new PublicAuthPolicy(false, false)));
        var localOut = new WebSocketSessionOutboundInterceptor(local);
        Message<?> legacy = delivery("legacy");
        assertSame(legacy, localOut.preSend(legacy, null));
        assertTrue(local.isSessionAuthorized("legacy"));
    }
}

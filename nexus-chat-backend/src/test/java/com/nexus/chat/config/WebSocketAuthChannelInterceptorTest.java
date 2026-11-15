package com.nexus.chat.config;

import com.nexus.chat.repository.UserSessionRepository;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.PresenceService;
import com.nexus.chat.service.WebSocketSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebSocketAuthChannelInterceptorTest {

    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider();
    private final WebSocketSessionRegistry sessionRegistry = mock(WebSocketSessionRegistry.class);
    private final PresenceService presenceService = mock(PresenceService.class);
    private final UserSessionRepository sessionRepository = mock(UserSessionRepository.class);
    private final WebSocketAuthChannelInterceptor interceptor = new WebSocketAuthChannelInterceptor(
            jwtTokenProvider, sessionRegistry, presenceService, sessionRepository);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecret",
                "unit-test-secret-unit-test-secret-unit-test-secret-unit-test-secret-0123456789");
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtExpiration", 60_000L);
    }

    private StompHeaderAccessor frame(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("session-1");
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private Message<?> send(StompHeaderAccessor accessor) {
        return interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null);
    }

    private StompHeaderAccessor frameFrom(StompCommand command, String userId, String destination) {
        StompHeaderAccessor accessor = frame(command);
        accessor.setUser((Principal) () -> userId);
        accessor.setDestination(destination);
        return accessor;
    }

    @Test
    void connectWithValidJwtSetsTokenUserAsPrincipal() {
        StompHeaderAccessor accessor = frame(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + jwtTokenProvider.generateToken(7L, "alice"));
        // A stale userId header must not override the token.
        accessor.setNativeHeader("userId", "8");

        send(accessor);

        assertEquals("7", accessor.getUser().getName());
        verify(sessionRegistry).registerSession(7L, "session-1");
        verify(presenceService).userConnected(7L, "session-1");
    }

    @Test
    void connectWithBareUserIdHeaderIsRejected() {
        StompHeaderAccessor accessor = frame(StompCommand.CONNECT);
        accessor.setNativeHeader("userId", "7");

        assertThrows(MessageDeliveryException.class, () -> send(accessor));

        verifyNoInteractions(sessionRegistry, presenceService);
    }

    @Test
    void connectWithoutAnyCredentialIsRejected() {
        assertThrows(MessageDeliveryException.class, () -> send(frame(StompCommand.CONNECT)));
        assertThrows(MessageDeliveryException.class, () -> send(frame(StompCommand.STOMP)));
    }

    @Test
    void connectWithInvalidJwtIsRejected() {
        StompHeaderAccessor accessor = frame(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer not-a-jwt");

        assertThrows(MessageDeliveryException.class, () -> send(accessor));

        verifyNoInteractions(sessionRegistry, presenceService);
    }

    @Test
    void connectWithRevokedSessionIsRejected() {
        when(sessionRepository.existsBySessionToken("sid-1")).thenReturn(false);
        StompHeaderAccessor accessor = frame(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + jwtTokenProvider.generateToken(7L, "alice", "sid-1"));

        assertThrows(MessageDeliveryException.class, () -> send(accessor));
    }

    // Every destination the web client subscribes to in services/websocket.js.
    @Test
    void ownDestinationsCanBeSubscribed() {
        assertDoesNotThrow(() -> send(frameFrom(StompCommand.SUBSCRIBE, "7", "/topic/user.7.messages")));
        assertDoesNotThrow(() -> send(frameFrom(StompCommand.SUBSCRIBE, "7", "/topic/user.7.contacts")));
        assertDoesNotThrow(() -> send(frameFrom(StompCommand.SUBSCRIBE, "7", "/user/queue/chats")));
    }

    @Test
    void anotherUsersTopicCannotBeSubscribed() {
        assertThrows(MessageDeliveryException.class,
                () -> send(frameFrom(StompCommand.SUBSCRIBE, "7", "/topic/user.8.messages")));
        // 7 is a prefix of 71: the id must match as a whole.
        assertThrows(MessageDeliveryException.class,
                () -> send(frameFrom(StompCommand.SUBSCRIBE, "7", "/topic/user.71.messages")));
    }

    @Test
    void wildcardSubscriptionsAreRejected() {
        assertThrows(MessageDeliveryException.class,
                () -> send(frameFrom(StompCommand.SUBSCRIBE, "7", "/topic/**")));
        assertThrows(MessageDeliveryException.class,
                () -> send(frameFrom(StompCommand.SUBSCRIBE, "7", "/topic/user.*.messages")));
        assertThrows(MessageDeliveryException.class,
                () -> send(frameFrom(StompCommand.SUBSCRIBE, "7", "/queue/chats-usersession-2")));
    }

    @Test
    void subscribeWithoutAuthenticatedConnectIsRejected() {
        StompHeaderAccessor accessor = frame(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/user.7.messages");

        assertThrows(MessageDeliveryException.class, () -> send(accessor));
    }

    @Test
    void clientsCanOnlySendToApplicationHandlers() {
        assertDoesNotThrow(() -> send(frameFrom(StompCommand.SEND, "7", "/app/chat.sendMessage")));
        assertThrows(MessageDeliveryException.class,
                () -> send(frameFrom(StompCommand.SEND, "7", "/topic/user.8.messages")));
        assertThrows(MessageDeliveryException.class,
                () -> send(frameFrom(StompCommand.SEND, "7", "/user/8/queue/chats")));

        StompHeaderAccessor anonymous = frame(StompCommand.SEND);
        anonymous.setDestination("/app/chat.sendMessage");
        assertThrows(MessageDeliveryException.class, () -> send(anonymous));
    }
}

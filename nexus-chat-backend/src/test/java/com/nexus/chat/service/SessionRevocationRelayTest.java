package com.nexus.chat.service;

import com.nexus.chat.dto.WebSocketMessage;
import com.nexus.chat.util.TrustedLogoutNotice;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Tests the actual relay envelope and target handling, without a Redis process or real login tokens. */
class SessionRevocationRelayTest {
    private final RedisCacheService cache = mock(RedisCacheService.class);
    private final SimpMessagingTemplate messages = mock(SimpMessagingTemplate.class);
    private final SimpUserRegistry users = mock(SimpUserRegistry.class);
    private final WebSocketSessionRegistry sockets = mock(WebSocketSessionRegistry.class);
    private final RedisMessageRelay relay = new RedisMessageRelay(mock(RedisMessageListenerContainer.class), cache, messages, users, sockets);

    @Test void revocationAlwaysPublishesToOtherInstancesEvenWhenThisInstanceHasTheUser() {
        when(users.getUser("7")).thenReturn(mock(SimpUser.class));
        String sid = UUID.randomUUID().toString();
        relay.revokeSessions(7L, List.of(sid), "new_login");
        verify(sockets).closeLoginSessions(7L, List.of(sid), "new_login");
        ArgumentCaptor<String> encoded = ArgumentCaptor.forClass(String.class);
        verify(cache).publishMessage(eq("ws:broadcast"), encoded.capture());
        assertTrue(encoded.getValue().contains("session_revocation"));
        WebSocketSessionRegistry remoteSockets = mock(WebSocketSessionRegistry.class);
        RedisMessageRelay remote = new RedisMessageRelay(mock(RedisMessageListenerContainer.class), mock(RedisCacheService.class),
                mock(SimpMessagingTemplate.class), mock(SimpUserRegistry.class), remoteSockets);
        Message event = mock(Message.class);
        when(event.getBody()).thenReturn(encoded.getValue().getBytes(StandardCharsets.UTF_8));
        remote.onMessage(event, null);
        verify(remoteSockets).closeLoginSessions(7L, List.of(sid), "new_login");
    }

    @Test void lastLogoutNoticeUsesTheServerOwnedMarkerAndIsAlsoRelayed() {
        when(users.getUser("7")).thenReturn(mock(SimpUser.class));
        var notice = new WebSocketMessage(WebSocketMessage.MessageType.FORCE_LOGOUT,
                Map.of("targetSids", List.of(UUID.randomUUID().toString()), "reason", "password_changed"));
        relay.sendLogoutNotice(7L, "/topic/user.7.messages", notice);
        ArgumentCaptor<MessageHeaders> headers = ArgumentCaptor.forClass(MessageHeaders.class);
        verify(messages).convertAndSend(eq("/topic/user.7.messages"), eq(notice), headers.capture());
        assertTrue(TrustedLogoutNotice.isNotice(MessageBuilder.createMessage(new byte[0], headers.getValue())));
        ArgumentCaptor<String> encoded = ArgumentCaptor.forClass(String.class);
        verify(cache).publishMessage(eq("ws:broadcast"), encoded.capture());
        assertTrue(encoded.getValue().contains("logout_notice"));
        SimpMessagingTemplate remoteMessages = mock(SimpMessagingTemplate.class);
        RedisMessageRelay remote = new RedisMessageRelay(mock(RedisMessageListenerContainer.class), mock(RedisCacheService.class),
                remoteMessages, users, mock(WebSocketSessionRegistry.class));
        Message event = mock(Message.class);
        when(event.getBody()).thenReturn(encoded.getValue().getBytes(StandardCharsets.UTF_8));
        remote.onMessage(event, null);
        ArgumentCaptor<MessageHeaders> remoteHeaders = ArgumentCaptor.forClass(MessageHeaders.class);
        verify(remoteMessages).convertAndSend(eq("/topic/user.7.messages"), anyString(), remoteHeaders.capture());
        assertTrue(TrustedLogoutNotice.isNotice(MessageBuilder.createMessage(new byte[0], remoteHeaders.getValue())));
    }
}

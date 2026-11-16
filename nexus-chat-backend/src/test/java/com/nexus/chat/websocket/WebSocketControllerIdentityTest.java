package com.nexus.chat.websocket;

import com.nexus.chat.dto.MessageDTO;
import com.nexus.chat.model.Message;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.service.ContactService;
import com.nexus.chat.service.GroupService;
import com.nexus.chat.service.MessageService;
import com.nexus.chat.service.PresenceService;
import com.nexus.chat.service.RedisCacheService;
import com.nexus.chat.service.RedisMessageRelay;
import com.nexus.chat.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebSocketControllerIdentityTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final MessageService messageService = mock(MessageService.class);
    private final ContactService contactService = mock(ContactService.class);
    private final GroupService groupService = mock(GroupService.class);
    private final ChatMemberRepository chatMemberRepository = mock(ChatMemberRepository.class);
    private final PresenceService presenceService = mock(PresenceService.class);
    private final RedisMessageRelay redisMessageRelay = mock(RedisMessageRelay.class);
    private final WebSocketController controller = new WebSocketController(
            messagingTemplate, messageService, mock(UserService.class), contactService, groupService,
            chatMemberRepository, presenceService, mock(RedisCacheService.class), redisMessageRelay);

    private final Principal alice = () -> "7";

    private Map<String, Object> chatMessage(Object senderId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("chatId", 5);
        payload.put("content", "hi");
        payload.put("clientMsgId", "c-1");
        if (senderId != null) {
            payload.put("senderId", senderId);
        }
        return payload;
    }

    @Test
    void sendMessageWithOwnSenderIdIsProcessedAsConnectionUser() {
        MessageDTO saved = new MessageDTO();
        saved.setId(100L);
        when(messageService.sendMessage(5L, 7L, "hi", Message.MessageType.text, null, "c-1", null)).thenReturn(saved);

        controller.sendMessage(chatMessage(7), alice);

        verify(messageService).sendMessage(5L, 7L, "hi", Message.MessageType.text, null, "c-1", null);
    }

    @Test
    void sendMessageWithoutSenderIdUsesConnectionUser() {
        controller.sendMessage(chatMessage(null), alice);

        verify(messageService).sendMessage(5L, 7L, "hi", Message.MessageType.text, null, "c-1", null);
    }

    @Test
    void sendMessageWithAnotherSenderIdIsNotProcessed() {
        controller.sendMessage(chatMessage(8), alice);

        verifyNoInteractions(messageService, messagingTemplate, redisMessageRelay);
    }

    @Test
    void presenceAndHeartbeatCannotBeSentForAnotherUser() {
        controller.updateUserStatus(Map.of("userId", 8, "isOnline", false), alice);
        controller.heartbeat(Map.of("userId", 8), alice);

        verifyNoInteractions(presenceService, contactService);

        controller.heartbeat(Map.of("userId", 7), alice);

        verify(presenceService).refreshPresence(7L);
    }

    @Test
    void typingIsOnlyRelayedForOwnIdAndOwnChats() {
        controller.userTyping(Map.of("chatId", 5, "userId", 8, "isTyping", true), alice);
        when(chatMemberRepository.existsByChatIdAndUserId(5L, 7L)).thenReturn(false);
        controller.userTyping(Map.of("chatId", 5, "userId", 7, "isTyping", true), alice);

        verify(chatMemberRepository, never()).findByChatId(anyLong());
        verifyNoInteractions(redisMessageRelay);
    }

    @Test
    void contactAndGroupActionsRunAsConnectionUser() {
        controller.removeContact(Map.of("userId", 8, "contactUserId", 9), alice);
        controller.leaveGroup(Map.of("groupId", 3, "userId", 8), alice);
        // In group.join the payload userId is the member being added; the operator is adminUserId.
        controller.joinGroup(Map.of("groupId", 3, "userId", 9, "adminUserId", 8), alice);

        verifyNoInteractions(contactService, groupService);

        controller.joinGroup(Map.of("groupId", 3, "userId", 9, "adminUserId", 7), alice);

        verify(groupService).addMembers(3L, 7L, List.of(9L));
    }

    @Test
    void callSignalIsForwardedOnlyBetweenTheSenderAndTheOtherParty() {
        when(presenceService.isUserOnline(anyLong())).thenReturn(true);

        // Forged caller: alice (7) is not a party of a call between 8 and 9.
        controller.handleCallSignal(Map.of("type", "CALL_INVITE",
                "payload", Map.of("callId", "x", "callerId", 8, "calleeId", 9)), alice);
        // Forged sender id on a bidirectional signal.
        controller.handleCallSignal(Map.of("type", "CALL_MUTE",
                "payload", Map.of("callId", "x", "userId", 8, "remoteUserId", 9)), alice);

        verifyNoInteractions(redisMessageRelay);

        controller.handleCallSignal(Map.of("type", "CALL_INVITE",
                "payload", Map.of("callId", "x", "callerId", 7, "calleeId", 9)), alice);
        controller.handleCallSignal(Map.of("type", "CALL_ACCEPT",
                "payload", Map.of("callId", "y", "callerId", 9, "calleeId", 7)), alice);
        controller.handleCallSignal(Map.of("type", "CALL_ICE_CANDIDATE",
                "payload", Map.of("callId", "x", "userId", 7, "remoteUserId", 9)), alice);

        verify(redisMessageRelay, times(3))
                .sendToUser(eq(9L), eq("/topic/user.9.messages"), any());
    }
}

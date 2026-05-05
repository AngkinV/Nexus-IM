package com.nexus.chat.controller;

import com.nexus.chat.dto.MessageDTO;
import com.nexus.chat.dto.WebSocketMessage;
import com.nexus.chat.model.ChatMember;
import com.nexus.chat.model.Message;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.service.MessageService;
import com.nexus.chat.service.PresenceService;
import com.nexus.chat.service.RedisCacheService;
import com.nexus.chat.service.RedisMessageRelay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final ChatMemberRepository chatMemberRepository;
    private final PresenceService presenceService;
    private final RedisCacheService redisCacheService;
    private final RedisMessageRelay redisMessageRelay;

    @PostMapping
    public ResponseEntity<MessageDTO> sendMessage(@RequestBody Map<String, Object> request) {
        try {
            Long chatId = Long.valueOf(request.get("chatId").toString());
            Long senderId = Long.valueOf(request.get("senderId").toString());
            String content = (String) request.get("content");
            String messageTypeStr = (String) request.get("messageType");
            String fileUrl = (String) request.get("fileUrl");
            String clientMsgId = (String) request.get("clientMsgId");

            Message.MessageType messageType = messageTypeStr != null
                    ? Message.MessageType.valueOf(messageTypeStr)
                    : Message.MessageType.text;

            MessageDTO message = messageService.sendMessage(chatId, senderId, content, messageType, fileUrl, clientMsgId);

            // 发送 WebSocket 通知给所有聊天成员
            notifyMessageToMembers(chatId, senderId, message);

            return ResponseEntity.ok(message);
        } catch (RuntimeException e) {
            log.error("发送消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 通过 WebSocket 通知所有聊天成员有新消息
     */
    private void notifyMessageToMembers(Long chatId, Long senderId, MessageDTO message) {
        try {
            WebSocketMessage wsMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.CHAT_MESSAGE,
                    message);

            // 发送 ACK 给发送者
            WebSocketMessage ackMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.MESSAGE_ACK,
                    Map.of(
                            "clientMsgId", message.getClientMsgId() != null ? message.getClientMsgId() : "",
                            "serverMsgId", message.getId(),
                            "chatId", chatId,
                            "sequenceNumber", message.getSequenceNumber() != null ? message.getSequenceNumber() : 0L));
            sendToUserChannel(senderId, ackMessage);

            // 通知所有成员（除发送者外）
            List<ChatMember> members = chatMemberRepository.findByChatId(chatId);
            for (ChatMember member : members) {
                if (!member.getUserId().equals(senderId)) {
                    if (presenceService.isUserOnline(member.getUserId())) {
                        sendToUserChannel(member.getUserId(), wsMessage);
                    } else {
                        redisCacheService.queueOfflineMessage(member.getUserId(), wsMessage);
                    }
                }
            }
            log.debug("WebSocket 通知已发送: chatId={}, senderId={}", chatId, senderId);
        } catch (Exception e) {
            log.error("发送 WebSocket 通知失败: chatId={}, error={}", chatId, e.getMessage());
        }
    }

    /**
     * 发送消息到用户的统一频道
     */
    private void sendToUserChannel(Long userId, Object payload) {
        String destination = "/topic/user." + userId + ".messages";
        redisMessageRelay.sendToUser(userId, destination, payload);
    }

    @GetMapping("/chat/{chatId}")
    public ResponseEntity<List<MessageDTO>> getChatMessages(
            @PathVariable Long chatId,
            @RequestParam Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        try {
            List<MessageDTO> messages = messageService.getChatMessages(chatId, userId, page, size);
            return ResponseEntity.ok(messages);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{messageId}/read")
    public ResponseEntity<Void> markMessageAsRead(
            @PathVariable Long messageId,
            @RequestParam Long userId) {
        try {
            messageService.markMessageAsRead(messageId, userId);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/chat/{chatId}/read")
    public ResponseEntity<Void> markChatMessagesAsRead(
            @PathVariable Long chatId,
            @RequestParam Long userId) {
        try {
            messageService.markChatMessagesAsRead(chatId, userId);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

}

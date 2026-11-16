package com.nexus.chat.websocket;

import com.nexus.chat.config.MessageValidationInterceptor;
import com.nexus.chat.dto.*;
import com.nexus.chat.model.ChatMember;
import com.nexus.chat.model.Message;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * WebSocket Controller for real-time messaging and events.
 *
 * Phase 3 changes:
 * - Unified message channel: only /topic/user.{userId}.messages (removed /topic/chat/{id})
 * - ACK mechanism: server sends MESSAGE_ACK back to sender
 * - Offline queue: messages queued in Redis when recipient is offline
 * - Sequence numbers: monotonic ordering per chat
 * - Typing indicators via user channel (not chat topic)
 * - XSS sanitization on message content
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class WebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final MessageService messageService;
    private final UserService userService;
    private final ContactService contactService;
    private final GroupService groupService;
    private final ChatMemberRepository chatMemberRepository;
    private final PresenceService presenceService;
    private final RedisCacheService redisCacheService;
    private final RedisMessageRelay redisMessageRelay;

    /**
     * Handle sending chat messages (direct and group).
     * Unified channel: delivers to /topic/user.{userId}.messages only.
     * Includes ACK + offline queue + sequence number + deduplication.
     */
    @MessageMapping("/chat.sendMessage")
    public void sendMessage(@Payload Map<String, Object> payload, Principal principal) {
        Long chatId = null;
        Long senderId = null;
        String clientMsgId = null;
        try {
            chatId = Long.valueOf(payload.get("chatId").toString());
            senderId = actingUser(principal, payload.get("senderId"));
            String content = (String) payload.get("content");
            String messageTypeStr = (String) payload.get("messageType");
            String fileUrl = (String) payload.get("fileUrl");
            clientMsgId = (String) payload.get("clientMsgId");
            Long replyToMessageId = payload.get("replyToMessageId") == null
                    ? null
                    : Long.valueOf(payload.get("replyToMessageId").toString());

            // Sanitize content to prevent XSS
            if (content != null) {
                MessageValidationInterceptor.validateTextContent(content);
                content = MessageValidationInterceptor.sanitizeContent(content);
            }
            if (fileUrl != null) {
                MessageValidationInterceptor.validateUrl(fileUrl);
            }

            Message.MessageType messageType = messageTypeStr != null
                    ? Message.MessageType.valueOf(messageTypeStr.toLowerCase())
                    : Message.MessageType.text;

            // Send message with clientMsgId for deduplication, sequence number is generated inside
            MessageDTO message = messageService.sendMessage(chatId, senderId, content, messageType, fileUrl, clientMsgId, replyToMessageId);

            WebSocketMessage wsMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.CHAT_MESSAGE,
                    message);

            // Send ACK to sender first (sequenceNumber is already set in message from service)
            WebSocketMessage ackMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.MESSAGE_ACK,
                    Map.of(
                            "clientMsgId", clientMsgId != null ? clientMsgId : "",
                            "serverMsgId", message.getId(),
                            "chatId", chatId,
                            "sequenceNumber", message.getSequenceNumber() != null ? message.getSequenceNumber() : 0L));
            messagingTemplate.convertAndSend(
                    "/topic/user." + senderId + ".messages", ackMessage);

            // Deliver to each member (unified channel - no more /topic/chat/{id})
            List<ChatMember> members = chatMemberRepository.findByChatId(chatId);
            for (ChatMember member : members) {
                if (!member.getUserId().equals(senderId)) {
                    if (presenceService.isUserOnline(member.getUserId())) {
                        // Online: deliver via relay (supports cross-instance)
                        sendToUserChannel(member.getUserId(), wsMessage);
                        messageService.markMessageDelivered(message.getId(), member.getUserId());
                        sendToUserChannel(senderId, new WebSocketMessage(
                                WebSocketMessage.MessageType.MESSAGE_DELIVERED,
                                Map.of("messageId", message.getId(), "chatId", chatId, "userId", member.getUserId())));
                    } else {
                        // Offline: queue in Redis
                        redisCacheService.queueOfflineMessage(member.getUserId(), wsMessage);
                    }
                }
            }
        } catch (Exception e) {
            log.error("发送消息失败: chatId={}, senderId={}", chatId, senderId, e);
            if (senderId != null) {
                WebSocketMessage errorMsg = new WebSocketMessage(
                        WebSocketMessage.MessageType.MESSAGE_DELIVERY_FAILED,
                        Map.of("chatId", chatId != null ? chatId : 0,
                               "clientMsgId", clientMsgId != null ? clientMsgId : "",
                               "error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                messagingTemplate.convertAndSend("/topic/user." + senderId + ".messages", errorMsg);
            }
        }
    }

    /**
     * Handle user status updates (online/offline).
     * Uses Redis Presence instead of MySQL.
     */
    @MessageMapping("/user.status")
    public void updateUserStatus(@Payload Map<String, Object> payload, Principal principal) {
        try {
            Long userId = actingUser(principal, payload.get("userId"));
            Boolean isOnline = (Boolean) payload.get("isOnline");

            // Update presence in Redis (not MySQL)
            if (Boolean.TRUE.equals(isOnline)) {
                String sessionId = payload.get("sessionId") != null
                        ? payload.get("sessionId").toString() : "ws-" + userId;
                presenceService.userConnected(userId, sessionId);
            } else {
                String sessionId = payload.get("sessionId") != null
                        ? payload.get("sessionId").toString() : "ws-" + userId;
                presenceService.userDisconnected(userId, sessionId);
            }

            // Notify contacts (uses indexed reverse lookup, not full table scan)
            contactService.notifyContactsOfStatusChange(userId, isOnline);

            // Deliver offline messages when user comes online
            if (Boolean.TRUE.equals(isOnline)) {
                deliverOfflineMessages(userId);
            }
        } catch (Exception e) {
            log.error("更新用户状态失败: userId={}", payload.get("userId"), e);
        }
    }

    /**
     * Handle typing indicator.
     * Delivers via user channel to chat members (not /topic/chat/{id}).
     * Uses Redis TTL for auto-expiry (5 seconds).
     */
    @MessageMapping("/chat.typing")
    public void userTyping(@Payload Map<String, Object> payload, Principal principal) {
        try {
            Long chatId = Long.valueOf(payload.get("chatId").toString());
            Long userId = actingUser(principal, payload.get("userId"));
            Boolean isTyping = (Boolean) payload.get("isTyping");
            if (!chatMemberRepository.existsByChatIdAndUserId(chatId, userId)) {
                return;
            }

            WebSocketMessage wsMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.TYPING,
                    Map.of("chatId", chatId, "userId", userId, "isTyping", isTyping));

            // Set/clear typing indicator in Redis (auto-expires in 5s)
            if (Boolean.TRUE.equals(isTyping)) {
                redisCacheService.setTyping(chatId, userId);
            }

            // Broadcast to chat members via user channel (relay-aware)
            List<ChatMember> members = chatMemberRepository.findByChatId(chatId);
            for (ChatMember member : members) {
                if (!member.getUserId().equals(userId)) {
                    sendToUserChannel(member.getUserId(), wsMessage);
                }
            }
        } catch (Exception e) {
            log.error("处理输入状态失败: chatId={}, userId={}", payload.get("chatId"), payload.get("userId"), e);
        }
    }

    /**
     * Handle message read status.
     * Delivers read receipt via user channel.
     */
    @MessageMapping("/message.read")
    public void messageRead(@Payload Map<String, Object> payload, Principal principal) {
        try {
            Long chatId = Long.valueOf(payload.get("chatId").toString());
            Long userId = actingUser(principal, payload.get("userId"));
            Long messageId = payload.get("messageId") != null
                    ? Long.valueOf(payload.get("messageId").toString())
                    : null;
            if (!chatMemberRepository.existsByChatIdAndUserId(chatId, userId)) {
                return;
            }

            WebSocketMessage wsMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.MESSAGE_READ,
                    Map.of("chatId", chatId, "userId", userId, "messageId", messageId != null ? messageId : "all"));

            // Deliver read receipt to chat members via user channel (relay-aware)
            List<ChatMember> members = chatMemberRepository.findByChatId(chatId);
            for (ChatMember member : members) {
                if (!member.getUserId().equals(userId)) {
                    sendToUserChannel(member.getUserId(), wsMessage);
                }
            }
        } catch (Exception e) {
            log.error("处理消息已读状态失败: chatId={}, userId={}", payload.get("chatId"), payload.get("userId"), e);
        }
    }

    /**
     * Handle heartbeat from client (refresh presence TTL).
     */
    @MessageMapping("/user.heartbeat")
    public void heartbeat(@Payload Map<String, Object> payload, Principal principal) {
        try {
            Long userId = actingUser(principal, payload.get("userId"));
            presenceService.refreshPresence(userId);
        } catch (Exception e) {
            log.debug("心跳处理失败: {}", e.getMessage());
        }
    }

    /**
     * Handle group creation
     */
    @MessageMapping("/group.create")
    public void createGroup(@Payload Map<String, Object> payload, Principal principal) {
        try {
            Long userId = actingUser(principal, payload.get("userId"));
            String name = (String) payload.get("name");
            String description = (String) payload.get("description");
            Boolean isPrivate = (Boolean) payload.getOrDefault("isPrivate", false);
            @SuppressWarnings("unchecked")
            List<Long> memberIds = (List<Long>) payload.get("memberIds");

            CreateGroupRequest request = new CreateGroupRequest();
            request.setName(name);
            request.setDescription(description);
            request.setIsPrivate(isPrivate);
            request.setMemberIds(memberIds);

            GroupDTO group = groupService.createGroup(userId, request);

            WebSocketMessage wsMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.GROUP_CREATED,
                    group);

            // Notify creator
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), "/queue/groups", wsMessage);

            // Notify all members
            if (memberIds != null) {
                for (Long memberId : memberIds) {
                    if (!memberId.equals(userId)) {
                        messagingTemplate.convertAndSendToUser(String.valueOf(memberId), "/queue/groups", wsMessage);
                    }
                }
            }
        } catch (Exception e) {
            log.error("创建群组失败: userId={}, name={}", payload.get("userId"), payload.get("name"), e);
            sendError(principal.getName(), "Failed to create group: " + e.getMessage());
        }
    }

    /**
     * Handle joining a group
     */
    @MessageMapping("/group.join")
    public void joinGroup(@Payload Map<String, Object> payload, Principal principal) {
        try {
            Long groupId = Long.valueOf(payload.get("groupId").toString());
            Long userId = Long.valueOf(payload.get("userId").toString());
            Long adminUserId = actingUser(principal, payload.get("adminUserId"));

            groupService.addMembers(groupId, adminUserId, List.of(userId));

            UserDTO user = userService.getUserById(userId);
            WebSocketMessage wsMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.GROUP_MEMBER_JOINED,
                    Map.of("groupId", groupId, "user", user));

            // Broadcast to group members via user channel (relay-aware)
            List<ChatMember> members = chatMemberRepository.findByChatId(groupId);
            for (ChatMember member : members) {
                sendToUserChannel(member.getUserId(), wsMessage);
            }
        } catch (Exception e) {
            log.error("加入群组失败: groupId={}, userId={}", payload.get("groupId"), payload.get("userId"), e);
        }
    }

    /**
     * Handle leaving a group
     */
    @MessageMapping("/group.leave")
    public void leaveGroup(@Payload Map<String, Object> payload, Principal principal) {
        try {
            Long groupId = Long.valueOf(payload.get("groupId").toString());
            Long userId = actingUser(principal, payload.get("userId"));

            // Get members before leaving
            List<ChatMember> members = chatMemberRepository.findByChatId(groupId);

            groupService.leaveGroup(groupId, userId);

            WebSocketMessage wsMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.GROUP_MEMBER_LEFT,
                    Map.of("groupId", groupId, "userId", userId));

            // Notify remaining members via user channel (relay-aware)
            for (ChatMember member : members) {
                sendToUserChannel(member.getUserId(), wsMessage);
            }
        } catch (Exception e) {
            log.error("离开群组失败: groupId={}, userId={}", payload.get("groupId"), payload.get("userId"), e);
        }
    }

    /**
     * Handle group message (unified with chat.sendMessage for groups)
     */
    @MessageMapping("/group.message")
    public void sendGroupMessage(@Payload Map<String, Object> payload, Principal principal) {
        try {
            Long groupId = Long.valueOf(payload.get("groupId").toString());
            Long senderId = actingUser(principal, payload.get("senderId"));
            String content = (String) payload.get("content");
            String messageTypeStr = (String) payload.get("messageType");
            String clientMsgId = (String) payload.get("clientMsgId");

            // Sanitize content
            if (content != null) {
                content = MessageValidationInterceptor.sanitizeContent(content);
            }

            Message.MessageType messageType = messageTypeStr != null
                    ? Message.MessageType.valueOf(messageTypeStr)
                    : Message.MessageType.text;

            // Send message with clientMsgId for deduplication
            MessageDTO message = messageService.sendMessage(groupId, senderId, content, messageType, null, clientMsgId);

            WebSocketMessage wsMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.CHAT_MESSAGE,
                    message);

            // Deliver to all group members via user channel (relay-aware)
            List<ChatMember> members = chatMemberRepository.findByChatId(groupId);
            for (ChatMember member : members) {
                if (presenceService.isUserOnline(member.getUserId())) {
                    sendToUserChannel(member.getUserId(), wsMessage);
                } else {
                    redisCacheService.queueOfflineMessage(member.getUserId(), wsMessage);
                }
            }
        } catch (Exception e) {
            log.error("发送群组消息失败: groupId={}, senderId={}", payload.get("groupId"), payload.get("senderId"), e);
        }
    }

    /**
     * Handle adding contact
     */
    @MessageMapping("/contact.add")
    public void addContact(@Payload Map<String, Object> payload, Principal principal) {
        try {
            Long userId = actingUser(principal, payload.get("userId"));
            Long contactUserId = Long.valueOf(payload.get("contactUserId").toString());
            String message = payload.get("message") != null ? payload.get("message").toString() : null;

            Object result = contactService.addContact(userId, contactUserId, message);

            if (result instanceof ContactDTO) {
                WebSocketMessage wsMessage = new WebSocketMessage(
                        WebSocketMessage.MessageType.CONTACT_ADDED,
                        result);
                messagingTemplate.convertAndSendToUser(String.valueOf(userId), "/queue/contacts", wsMessage);
            }
        } catch (Exception e) {
            log.error("添加联系人失败: userId={}, contactUserId={}", payload.get("userId"), payload.get("contactUserId"), e);
            sendError(principal.getName(), "Failed to add contact: " + e.getMessage());
        }
    }

    /**
     * Handle removing contact
     */
    @MessageMapping("/contact.remove")
    public void removeContact(@Payload Map<String, Object> payload, Principal principal) {
        try {
            Long userId = actingUser(principal, payload.get("userId"));
            Long contactUserId = Long.valueOf(payload.get("contactUserId").toString());

            contactService.removeContact(userId, contactUserId);

            WebSocketMessage wsMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.CONTACT_REMOVED,
                    Map.of("contactId", contactUserId));

            messagingTemplate.convertAndSendToUser(String.valueOf(userId), "/queue/contacts", wsMessage);
        } catch (Exception e) {
            log.error("移除联系人失败: userId={}, contactUserId={}", payload.get("userId"), payload.get("contactUserId"), e);
        }
    }

    /**
     * Handle call signaling (voice/video calls).
     * Forwards signaling messages between caller and callee.
     * Supports: CALL_INVITE, CALL_ACCEPT, CALL_REJECT, CALL_CANCEL,
     *           CALL_BUSY, CALL_TIMEOUT, CALL_END,
     *           CALL_OFFER, CALL_ANSWER, CALL_ICE_CANDIDATE,
     *           CALL_MUTE, CALL_VIDEO_TOGGLE
     */
    @MessageMapping("/call.signal")
    public void handleCallSignal(@Payload Map<String, Object> payload, Principal principal) {
        try {
            String typeStr = (String) payload.get("type");
            @SuppressWarnings("unchecked")
            Map<String, Object> signalPayload = (Map<String, Object>) payload.get("payload");

            if (typeStr == null || signalPayload == null) {
                log.warn("Invalid call signal: missing type or payload");
                return;
            }

            // Parse signal type
            WebSocketMessage.MessageType signalType;
            try {
                signalType = WebSocketMessage.MessageType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown call signal type: {}", typeStr);
                return;
            }

            Long callerId = signalPayload.get("callerId") != null
                    ? Long.valueOf(signalPayload.get("callerId").toString()) : null;
            Long calleeId = signalPayload.get("calleeId") != null
                    ? Long.valueOf(signalPayload.get("calleeId").toString()) : null;

            // For some signal types, use userId/remoteUserId instead
            if (callerId == null) {
                callerId = signalPayload.get("userId") != null
                        ? Long.valueOf(signalPayload.get("userId").toString()) : null;
            }
            if (calleeId == null) {
                calleeId = signalPayload.get("remoteUserId") != null
                        ? Long.valueOf(signalPayload.get("remoteUserId").toString()) : null;
            }

            log.info("Call signal received: type={}, callerId={}, calleeId={}", signalType, callerId, calleeId);

            // The sender must be one of the two parties and may not name someone else as the sender.
            Long senderId = actingUser(principal, signalPayload.get("userId"));
            if (!senderId.equals(callerId) && !senderId.equals(calleeId)) {
                log.warn("Call signal rejected: sender {} is not a party of the call", senderId);
                return;
            }

            // Determine target user based on signal type
            Long targetUserId = determineCallSignalTarget(signalType, callerId, calleeId, signalPayload);

            if (targetUserId == null) {
                log.warn("Cannot determine target user for call signal: type={}", signalType);
                return;
            }
            if (!targetUserId.equals(callerId) && !targetUserId.equals(calleeId)) {
                log.warn("Call signal rejected: target {} is not a party of the call", targetUserId);
                return;
            }

            // Build WebSocket message with call signal type
            WebSocketMessage wsMessage = new WebSocketMessage(signalType, signalPayload);

            // Forward to target user via their unified channel
            if (presenceService.isUserOnline(targetUserId)) {
                sendToUserChannel(targetUserId, wsMessage);
                log.info("Call signal forwarded: type={}, to userId={}", signalType, targetUserId);
            } else {
                // If user is offline, send timeout response to caller
                if (signalType == WebSocketMessage.MessageType.CALL_INVITE && callerId != null) {
                    WebSocketMessage timeoutMsg = new WebSocketMessage(
                            WebSocketMessage.MessageType.CALL_TIMEOUT,
                            Map.of(
                                "callId", signalPayload.get("callId"),
                                "callerId", callerId,
                                "calleeId", calleeId,
                                "reason", "User is offline"
                            ));
                    sendToUserChannel(callerId, timeoutMsg);
                    log.info("Callee offline, sent CALL_TIMEOUT to caller: {}", callerId);
                }
            }
        } catch (Exception e) {
            log.error("处理通话信令失败: {}", e.getMessage(), e);
        }
    }

    /**
     * Determine the target user for a call signal.
     */
    private Long determineCallSignalTarget(WebSocketMessage.MessageType type, Long callerId, Long calleeId, Map<String, Object> payload) {
        switch (type) {
            // Signals from caller to callee
            case CALL_INVITE:
            case CALL_CANCEL:
            case CALL_OFFER:
                return calleeId;

            // Signals from callee to caller
            case CALL_ACCEPT:
            case CALL_REJECT:
            case CALL_BUSY:
            case CALL_ANSWER:
                return callerId;

            // Bidirectional signals - determine by checking who sent it
            case CALL_END:
            case CALL_TIMEOUT:
            case CALL_ICE_CANDIDATE:
            case CALL_MUTE:
            case CALL_VIDEO_TOGGLE:
                // For these, the sender's ID should be in userId, target in remoteUserId
                Long userId = payload.get("userId") != null
                        ? Long.valueOf(payload.get("userId").toString()) : null;
                Long remoteUserId = payload.get("remoteUserId") != null
                        ? Long.valueOf(payload.get("remoteUserId").toString()) : null;

                if (remoteUserId != null) {
                    return remoteUserId;
                }
                // Fallback: send to the other party
                if (userId != null && userId.equals(callerId)) {
                    return calleeId;
                } else if (userId != null && userId.equals(calleeId)) {
                    return callerId;
                }
                // Last resort: try both
                return calleeId != null ? calleeId : callerId;

            default:
                log.warn("Unknown call signal type: {}", type);
                return calleeId;
        }
    }

    /**
     * The acting user is the one authenticated on CONNECT. An id in the payload is only a claim:
     * absent is fine, a different one rejects the message.
     */
    private Long actingUser(Principal principal, Object claimedUserId) {
        Long userId = Long.valueOf(principal.getName());
        if (claimedUserId != null && !userId.equals(Long.valueOf(claimedUserId.toString()))) {
            throw new AccessDeniedException("payload user id does not match the authenticated connection");
        }
        return userId;
    }

    /**
     * Deliver queued offline messages to a user who just came online.
     */
    private void deliverOfflineMessages(Long userId) {
        List<String> offlineMessages = redisCacheService.drainOfflineQueue(userId);
        for (String msgJson : offlineMessages) {
            try {
                sendToUserChannel(userId, msgJson);
            } catch (Exception e) {
                log.error("离线消息投递失败: userId={}", userId, e);
            }
        }
    }

    /**
     * Send error message to user
     */
    private void sendError(Object userId, String errorMessage) {
        if (userId != null) {
            WebSocketMessage wsMessage = new WebSocketMessage(
                    WebSocketMessage.MessageType.ERROR,
                    Map.of("message", errorMessage));
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), "/queue/errors", wsMessage);
        }
    }

    /**
     * Send a WebSocket message to a user via their unified channel.
     * Uses RedisMessageRelay for cross-instance delivery.
     */
    private void sendToUserChannel(Long userId, Object payload) {
        String destination = "/topic/user." + userId + ".messages";
        redisMessageRelay.sendToUser(userId, destination, payload);
    }

}

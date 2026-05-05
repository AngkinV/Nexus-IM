package com.nexus.chat.controller.agent;

import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.Chat;
import com.nexus.chat.model.Message;
import com.nexus.chat.model.User;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.ChatRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.repository.UserRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/agent")
@RequiredArgsConstructor
public class InternalAgentController {

    private final ChatMemberRepository chatMemberRepository;
    private final MessageRepository messageRepository;
    private final ChatRepository chatRepository;
    private final UserRepository userRepository;

    @Value("${agent.internal.token}")
    private String internalToken;

    @GetMapping("/chats/{chatId}/recent-messages")
    public ResponseEntity<Map<String, Object>> recentMessages(
            @PathVariable Long chatId,
            @RequestParam(defaultValue = "80") Integer limit,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Actor-User-Id") Long actorUserId
    ) {
        ensureInternalToken(authorization);
        ensureMember(actorUserId, chatId);
        List<Map<String, Object>> messages = messageRepository
                .findByChatIdOrderByCreatedAtDesc(chatId, PageRequest.of(0, Math.min(limit, 200)))
                .getContent()
                .stream()
                .map(m -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("messageId", m.getId());
                    item.put("senderId", m.getSenderId());
                    item.put("senderName", userRepository.findById(m.getSenderId()).map(User::getUsername).orElse("unknown"));
                    item.put("content", m.getContent());
                    item.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
                    return item;
                })
                .toList();

        return ResponseEntity.ok(Map.of("chatId", chatId, "messages", messages));
    }

    @GetMapping("/chats/{chatId}/profile")
    public ResponseEntity<Map<String, Object>> chatProfile(
            @PathVariable Long chatId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Actor-User-Id") Long actorUserId
    ) {
        ensureInternalToken(authorization);
        ensureMember(actorUserId, chatId);
        Chat chat = chatRepository.findById(chatId).orElseThrow(() -> new IllegalArgumentException("chat not found"));
        List<Long> memberIds = chatMemberRepository.findByChatId(chatId).stream().map(cm -> cm.getUserId()).toList();
        return ResponseEntity.ok(Map.of(
                "chatId", chatId,
                "chatType", chat.getType().name().toLowerCase(),
                "chatName", chat.getName() == null ? "" : chat.getName(),
                "memberIds", memberIds
        ));
    }

    @GetMapping("/users/{userId}/profile")
    public ResponseEntity<Map<String, Object>> userProfile(
            @PathVariable Long userId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Actor-User-Id") Long actorUserId
    ) {
        ensureInternalToken(authorization);
        userRepository.findById(actorUserId).orElseThrow(() -> new IllegalArgumentException("actor user not found"));
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        return ResponseEntity.ok(Map.of(
                "userId", user.getId(),
                "username", user.getUsername(),
                "nickname", user.getUsername(),
                "timezone", "Asia/Shanghai"
        ));
    }

    /**
     * Look up a user by their @username handle (the form regular users see in
     * the IM UI — e.g. "@test00001"). Used by the agent tool
     * {@code find_user_by_username} so users can ask the assistant to find a
     * friend by handle without knowing the numeric DB id.
     */
    @GetMapping("/users/by-username/{username}/profile")
    public ResponseEntity<Map<String, Object>> userProfileByUsername(
            @PathVariable String username,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Actor-User-Id") Long actorUserId
    ) {
        ensureInternalToken(authorization);
        userRepository.findById(actorUserId).orElseThrow(() -> new IllegalArgumentException("actor user not found"));
        String normalized = username == null ? "" : username.trim();
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("username is blank");
        }
        String finalNormalized = normalized;
        User user = userRepository.findByUsername(normalized)
                .orElseThrow(() -> new IllegalArgumentException("user not found: @" + finalNormalized));
        return ResponseEntity.ok(Map.of(
                "userId", user.getId(),
                "username", user.getUsername(),
                "nickname", user.getUsername(),
                "timezone", "Asia/Shanghai"
        ));
    }

    @GetMapping("/messages/{messageId}")
    public ResponseEntity<Map<String, Object>> messageById(
            @PathVariable Long messageId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Actor-User-Id") Long actorUserId
    ) {
        ensureInternalToken(authorization);
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("message not found"));
        ensureMember(actorUserId, message.getChatId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", message.getId());
        body.put("chatId", message.getChatId());
        body.put("senderId", message.getSenderId());
        body.put("senderName", userRepository.findById(message.getSenderId()).map(User::getUsername).orElse("unknown"));
        body.put("content", message.getContent());
        body.put("createdAt", message.getCreatedAt() == null ? null : message.getCreatedAt().toString());
        return ResponseEntity.ok(body);
    }

    /**
     * List the actor's chats with optional fuzzy match by name. Designed for the agent
     * tool {@code list_my_chats} so users can refer to chats by name (e.g. "项目A推进群")
     * instead of having to know the numeric chatId.
     */
    @GetMapping("/me/chats")
    public ResponseEntity<Map<String, Object>> listMyChats(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Actor-User-Id") Long actorUserId
    ) {
        ensureInternalToken(authorization);
        userRepository.findById(actorUserId).orElseThrow(() -> new IllegalArgumentException("actor user not found"));

        String q = query == null ? "" : query.trim().toLowerCase();
        String typeFilter = type == null ? null : type.trim().toLowerCase();

        List<Map<String, Object>> rows = chatRepository.findByUserIdOrderByLastMessageAtDesc(actorUserId).stream()
                .filter(c -> typeFilter == null || c.getType().name().equalsIgnoreCase(typeFilter))
                .map(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("chatId", c.getId());
                    row.put("type", c.getType().name().toLowerCase());
                    String displayName;
                    if (c.getType() == Chat.ChatType.direct) {
                        // Show the *other* member as the chat name for direct chats
                        displayName = chatMemberRepository.findByChatId(c.getId()).stream()
                                .filter(cm -> !actorUserId.equals(cm.getUserId()))
                                .findFirst()
                                .flatMap(cm -> userRepository.findById(cm.getUserId()))
                                .map(u -> "@" + u.getUsername())
                                .orElse(c.getName() == null ? "" : c.getName());
                    } else {
                        displayName = c.getName() == null ? "" : c.getName();
                    }
                    row.put("name", displayName);
                    row.put("lastMessageAt", c.getLastMessageAt() == null ? null : c.getLastMessageAt().toString());
                    return row;
                })
                .filter(row -> q.isEmpty() || ((String) row.get("name")).toLowerCase().contains(q))
                .limit(Math.max(1, Math.min(limit, 50)))
                .toList();

        return ResponseEntity.ok(Map.of("chats", rows));
    }

    /**
     * Find the direct (1-on-1) chat between the actor and a named user. Returns 404 if
     * no such chat exists. Used by the agent tool {@code find_direct_chat_with_user}.
     */
    @GetMapping("/me/chats/with-user/{username}")
    public ResponseEntity<Map<String, Object>> findDirectChatWithUser(
            @PathVariable String username,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Actor-User-Id") Long actorUserId
    ) {
        ensureInternalToken(authorization);
        String trimmed = username == null ? "" : username.trim();
        final String normalized = trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("username is blank");
        }
        User other = userRepository.findByUsername(normalized)
                .orElseThrow(() -> new IllegalArgumentException("user not found: @" + normalized));
        Chat chat = chatRepository.findDirectChatBetweenUsers(actorUserId, other.getId())
                .orElseThrow(() -> new IllegalArgumentException("no direct chat with @" + normalized));
        return ResponseEntity.ok(Map.of(
                "chatId", chat.getId(),
                "type", chat.getType().name().toLowerCase(),
                "otherUserId", other.getId(),
                "otherUsername", other.getUsername()
        ));
    }

    @PostMapping("/messages/publish")
    public ResponseEntity<Map<String, Object>> publish(
            @RequestBody PublishRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Actor-User-Id") Long actorUserId
    ) {
        ensureInternalToken(authorization);
        ensureMember(actorUserId, request.getChatId());
        if (!actorUserId.equals(request.getSenderUserId())) {
            throw new AgentException(AgentErrorCode.AGENT_AUTHZ_40301, "senderUserId must equal actor user");
        }

        Message message = new Message();
        message.setChatId(request.getChatId());
        message.setSenderId(request.getSenderUserId());
        message.setContent(request.getContent());
        message.setMessageType(Message.MessageType.text);
        Message saved = messageRepository.save(message);
        return ResponseEntity.ok(Map.of("messageId", saved.getId(), "chatId", saved.getChatId()));
    }

    private void ensureInternalToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AgentException(AgentErrorCode.AGENT_AUTH_40101, "Internal token missing");
        }
        String token = authorization.substring(7).trim();
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            throw new AgentException(AgentErrorCode.AGENT_AUTH_40101, "Internal token invalid");
        }
    }

    private void ensureMember(Long userId, Long chatId) {
        if (!chatMemberRepository.existsByChatIdAndUserId(chatId, userId)) {
            throw new AgentException(AgentErrorCode.AGENT_AUTHZ_40301, "No permission for this chat", Map.of("chatId", chatId));
        }
    }

    @Data
    public static class PublishRequest {
        @NotNull
        private Long chatId;
        @NotNull
        private Long senderUserId;
        @NotBlank
        private String content;
        private String messageType;
        private String source;
    }
}

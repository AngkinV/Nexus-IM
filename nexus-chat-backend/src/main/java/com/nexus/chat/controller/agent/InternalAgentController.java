package com.nexus.chat.controller.agent;

import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.Message;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.security.AgentInternalTokenFilter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Write-side internal API for the Python agent. The read-only lookups are
 * exposed as MCP tools instead (see {@code AgentToolService}).
 */
@RestController
@RequestMapping("/internal/agent")
@RequiredArgsConstructor
public class InternalAgentController {

    private final ChatMemberRepository chatMemberRepository;
    private final MessageRepository messageRepository;

    @Value("${agent.internal.token}")
    private String internalToken;

    @PostMapping("/messages/publish")
    public ResponseEntity<Map<String, Object>> publish(
            @RequestBody PublishRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Actor-User-Id") Long actorUserId
    ) {
        if (!AgentInternalTokenFilter.bearerMatches(internalToken, authorization)) {
            throw new AgentException(AgentErrorCode.AGENT_AUTH_40101, "Internal token invalid");
        }
        if (!chatMemberRepository.existsByChatIdAndUserId(request.getChatId(), actorUserId)) {
            throw new AgentException(AgentErrorCode.AGENT_AUTHZ_40301, "No permission for this chat",
                    Map.of("chatId", request.getChatId()));
        }
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

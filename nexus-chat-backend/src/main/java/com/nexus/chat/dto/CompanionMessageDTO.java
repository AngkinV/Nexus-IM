package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionMessageDTO {
    private Long id;
    private Long roleId;
    private String senderType;
    private String content;
    private Boolean fallback;
    private LocalDateTime createdAt;
    /** The evening this message belongs to; null for messages older than sessions. */
    private Long sessionId;
    /** mood / motion / doing / gesture as she reported them with this reply, or null. */
    private java.util.Map<String, String> state;
    /**
     * The id the client sent this line under. Lets a client that reloads still
     * retry a line that never got an answer, under the same id.
     */
    private String clientMsgId;
    /**
     * True when this reply was cut short (the model failed or the reader left
     * mid-answer); null otherwise. Survives a reload, so the client can still
     * offer a retry — which regenerates the reply under the same clientMsgId.
     */
    private Boolean partial;
    private com.fasterxml.jackson.databind.JsonNode contextReceipt;

    public CompanionMessageDTO(Long id, Long roleId, String senderType, String content, Boolean fallback,
            LocalDateTime createdAt, Long sessionId, java.util.Map<String,String> state, String clientMsgId, Boolean partial) {
        this(id, roleId, senderType, content, fallback, createdAt, sessionId, state, clientMsgId, partial, null);
    }
}

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
    /** mood / motion / doing as she reported them with this reply, or null. */
    private java.util.Map<String, String> state;
    /**
     * The id the client sent this line under. Lets a client that reloads still
     * retry a line that never got an answer, under the same id.
     */
    private String clientMsgId;
}

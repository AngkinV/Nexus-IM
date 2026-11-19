package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionChatResponse {
    private CompanionMessageDTO userMessage;
    private CompanionMessageDTO roleMessage;
    private Boolean fallback;
    private CompanionStatusDTO status;
    /** Her opening line, when this send opened a new evening and she has one. */
    private CompanionMessageDTO openingMessage;
    /** The evening this exchange was filed under. */
    private Long sessionId;
    private String sceneKey;
    /** Which memories were put in front of the model for this reply. */
    private java.util.List<Long> memoryIds;
    /** True when the client aborted mid-reply and only the part streamed so far was kept. */
    private Boolean partial;

    public CompanionChatResponse(CompanionMessageDTO userMessage, CompanionMessageDTO roleMessage,
                                 Boolean fallback, CompanionStatusDTO status) {
        this.userMessage = userMessage;
        this.roleMessage = roleMessage;
        this.fallback = fallback;
        this.status = status;
    }
}

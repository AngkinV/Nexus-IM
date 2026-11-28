package com.nexus.chat.dto;

import com.fasterxml.jackson.databind.JsonNode;
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
    /** A reader abort or a model failure interrupted this reply. */
    private Boolean partial;
    /** Safe structured gateway error, separate from the role's words. */
    private JsonNode modelError;
    private JsonNode contextReceipt;

    public CompanionChatResponse(CompanionMessageDTO userMessage, CompanionMessageDTO roleMessage,
                                 Boolean fallback, CompanionStatusDTO status) {
        this.userMessage = userMessage;
        this.roleMessage = roleMessage;
        this.fallback = fallback;
        this.status = status;
    }
}

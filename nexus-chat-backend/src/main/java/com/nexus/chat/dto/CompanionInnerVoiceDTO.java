package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A character's inner monologue: what she is thinking, not what she said.
 * `generated` is false when no model is configured — the client then shows the
 * figures it already has instead of pretending a monologue exists.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionInnerVoiceDTO {
    private Long roleId;
    private String text;
    private boolean generated;
    private String reason;
    private com.fasterxml.jackson.databind.JsonNode contextReceipt;
    public CompanionInnerVoiceDTO(Long roleId, String text, boolean generated, String reason) {
        this(roleId, text, generated, reason, null);
    }
}

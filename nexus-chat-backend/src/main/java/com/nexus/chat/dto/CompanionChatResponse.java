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
}

package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageReactionDTO {
    private String emoji;
    private Long count;
    private Boolean reactedByMe;
}

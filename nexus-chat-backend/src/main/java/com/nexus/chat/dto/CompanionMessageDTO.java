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
}

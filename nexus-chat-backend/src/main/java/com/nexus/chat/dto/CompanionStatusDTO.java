package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionStatusDTO {
    private Long roleId;
    private String statusType;
    private String summary;
    private LocalDateTime updatedAt;
}

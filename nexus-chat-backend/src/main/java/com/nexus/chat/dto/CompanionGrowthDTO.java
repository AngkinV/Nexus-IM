package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionGrowthDTO {
    private Long roleId;
    private Integer intimacy;
    private Integer trust;
    private Integer stability;
    private Integer coGrowth;
    private LocalDateTime updatedAt;
}

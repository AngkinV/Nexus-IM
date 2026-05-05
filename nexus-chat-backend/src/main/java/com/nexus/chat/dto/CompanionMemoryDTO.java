package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionMemoryDTO {
    private Long id;
    private Long roleId;
    private String type;
    private String content;
    private Boolean confirmed;
    private Boolean shared;
    private LocalDateTime createdAt;
}

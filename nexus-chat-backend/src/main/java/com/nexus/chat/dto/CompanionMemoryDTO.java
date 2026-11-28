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
    private Long sourceSessionId;
    private Long sourceMessageId;
    private LocalDateTime occurredAt;
    private LocalDateTime sourceCreatedAt;
    private Long correctsId;

    public CompanionMemoryDTO(Long id, Long roleId, String type, String content, Boolean confirmed, Boolean shared, LocalDateTime createdAt) {
        this.id=id; this.roleId=roleId; this.type=type; this.content=content;
        this.confirmed=confirmed; this.shared=shared; this.createdAt=createdAt;
    }
}

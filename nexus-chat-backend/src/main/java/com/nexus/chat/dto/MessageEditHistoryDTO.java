package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageEditHistoryDTO {
    private Long id;
    private Long messageId;
    private Long editorUserId;
    private String previousContent;
    private String newContent;
    private LocalDateTime editedAt;
}

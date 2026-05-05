package com.nexus.chat.dto;

import com.nexus.chat.model.Chat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatDTO {
    private Long id;
    private Chat.ChatType type;
    private String name;
    private String description;
    private String avatar;
    private Boolean isPrivate;
    private Long createdBy;
    private Integer memberCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastMessageAt;
    private MessageDTO lastMessage;
    private Integer unreadCount;
    private List<UserDTO> members;
}

package com.nexus.chat.dto;

import com.nexus.chat.model.Chat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for group information with detailed member list
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupDTO {
    private Long id;
    private String name;
    private String description;
    private String avatar;
    private Chat.ChatType type;
    private Boolean isPrivate;
    private Long creatorId;
    private Integer memberCount;
    private List<GroupMemberDTO> members;
    private String lastMessage;
    private LocalDateTime lastMessageTime;
    private Integer unreadCount;
    private LocalDateTime createdAt;
}

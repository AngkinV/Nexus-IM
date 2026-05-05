package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for group member with role information
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupMemberDTO {
    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private Boolean isOnline;
    private String role;        // owner / admin / member
    private Boolean isAdmin;
    private LocalDateTime joinedAt;
    private LocalDateTime lastSeen;
}

package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for a blocked user (blacklist entry)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlockedUserDTO {
    private Long userId;       // the blocked user's id
    private String username;
    private String nickname;
    private String avatarUrl;
    private LocalDateTime blockedAt;
}

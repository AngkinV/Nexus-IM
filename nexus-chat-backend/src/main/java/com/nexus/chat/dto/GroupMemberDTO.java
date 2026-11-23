package com.nexus.chat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
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
    // Presence values are UTC wall times from Redis epoch milliseconds, not local DB times.
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private LocalDateTime lastSeen;
}

package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for contact information with user details
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContactDTO {
    private Long id;
    private Long userId;
    private String username;
    private String nickname;
    private String email;
    private String phone;
    private String avatarUrl;
    private Boolean isOnline;
    private LocalDateTime lastSeen;
    private LocalDateTime addedAt;
}

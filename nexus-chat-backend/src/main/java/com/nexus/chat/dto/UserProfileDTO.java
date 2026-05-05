package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Extended user profile DTO including email, phone, bio and privacy settings
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDTO {
    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String phone;
    private String avatarUrl;
    private String bio;
    private String profileBackground;
    private Boolean isOnline;
    private LocalDateTime lastSeen;
    private LocalDateTime createdAt;

    // Privacy settings
    private Boolean showOnlineStatus;
    private Boolean showLastSeen;
    private Boolean showEmail;
    private Boolean showPhone;

    // Statistics
    private Long contactCount;
    private Long groupCount;
    private Long messageCount;

    // Social links (platform -> url)
    private Map<String, String> socialLinks;

    // Security status
    private Boolean twoFactorEnabled;
    private Integer passwordStrength;
    private Integer activeSessions;
}

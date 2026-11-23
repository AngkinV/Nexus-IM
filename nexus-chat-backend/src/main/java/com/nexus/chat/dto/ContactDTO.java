package com.nexus.chat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
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
    // Presence values are UTC wall times from Redis epoch milliseconds, not local DB times.
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private LocalDateTime lastSeen;
    private LocalDateTime addedAt;
}

package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivityDTO {
    private Long id;
    private String activityType;
    private String description;
    private Long relatedId;
    private LocalDateTime createdAt;

    // For friend activity feed
    private UserDTO user;
}

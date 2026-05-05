package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for user statistics
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserStatsDTO {
    private Long contactCount;
    private Long groupCount;
    private Long messageCount;
    private Long followingCount;
    private Long followerCount;
    private Long postCount;
}

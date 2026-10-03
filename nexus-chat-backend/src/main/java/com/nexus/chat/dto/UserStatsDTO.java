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

    // Cumulative online time (hours), tracked via the presence heartbeat
    private Double onlineHours;

    // Day-over-day trend (signed percent: >0 up, <0 down, 0 flat, null when no data)
    private Integer messagesDelta;
    private Integer contactsDelta;
    private Integer groupsDelta;
    private Integer onlineDelta;
}

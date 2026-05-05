package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for adding members to a group
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddMembersRequest {
    private List<Long> userIds;
}

package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for updating group information
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGroupRequest {
    private String name;
    private String description;
    private String avatar;
    private Boolean isPrivate;
}

package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateGroupRequest {
    private String name;
    private String description;
    private String avatar;
    private Boolean isPrivate = false;
    private List<Long> memberIds;
}

package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionRoleDTO {
    private Long id;
    private String name;
    private List<String> traits;
    private String tone;
    private String baselineMood;
    private String avatarUrl;
    private String modelUrl;
    private String modelType;
    private Boolean active;
}

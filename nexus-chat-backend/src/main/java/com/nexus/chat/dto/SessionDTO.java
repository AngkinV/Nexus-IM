package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionDTO {
    private Long id;
    private String deviceName;
    private String deviceType;
    private String browser;
    private String location;
    private Boolean isCurrent;
    private LocalDateTime lastActive;
    private LocalDateTime createdAt;
}

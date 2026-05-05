package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SecuritySettingsDTO {
    private Boolean twoFactorEnabled;
    private LocalDateTime passwordChangedAt;
    private Integer passwordStrength;
    private Integer activeSessions;
}

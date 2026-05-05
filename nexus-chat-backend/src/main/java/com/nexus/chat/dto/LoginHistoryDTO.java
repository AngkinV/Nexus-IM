package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginHistoryDTO {
    private Long id;
    private Boolean success;
    private String ipAddress;
    private String location;
    private String device;
    private String browser;
    private String failureReason;
    private LocalDateTime createdAt;
}

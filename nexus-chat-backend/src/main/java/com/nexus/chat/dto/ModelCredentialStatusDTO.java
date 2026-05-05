package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelCredentialStatusDTO {
    private String provider;
    private String status;
    private String maskedKey;
    private LocalDateTime updatedAt;
}

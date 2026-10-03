package com.nexus.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EditMessageRequest {
    @NotBlank
    private String content;
}

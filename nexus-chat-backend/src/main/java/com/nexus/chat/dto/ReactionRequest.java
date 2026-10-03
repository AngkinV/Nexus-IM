package com.nexus.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReactionRequest {
    @NotBlank
    private String emoji;
}

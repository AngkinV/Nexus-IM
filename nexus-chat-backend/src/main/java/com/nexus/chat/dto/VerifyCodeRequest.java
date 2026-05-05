package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerifyCodeRequest {
    private String email;
    private String code;
    private String type; // REGISTER, RESET_PASSWORD, CHANGE_EMAIL
}

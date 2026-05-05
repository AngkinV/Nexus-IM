package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendCodeRequest {
    private String email;
    private String type; // REGISTER, RESET_PASSWORD, CHANGE_EMAIL
}

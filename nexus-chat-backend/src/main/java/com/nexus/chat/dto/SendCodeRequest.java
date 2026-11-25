package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendCodeRequest {
    private String email;
    private String type; // REGISTER, RESET_PASSWORD, CHANGE_EMAIL
    @ToString.Exclude // never logged
    private String inviteCode;
}

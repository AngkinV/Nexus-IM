package com.nexus.chat.dto;

import lombok.Data;
import lombok.ToString;

/** Backward-compatible security request; its one-use proof must never enter request debug logs. */
@Data
public class TwoFactorRequest {
    private Boolean enabled;
    @ToString.Exclude
    private String code;
}

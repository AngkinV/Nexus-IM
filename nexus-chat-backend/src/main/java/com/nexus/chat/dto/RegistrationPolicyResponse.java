package com.nexus.chat.dto;

/** What the register form has to ask for. */
public record RegistrationPolicyResponse(boolean inviteRequired, boolean emailCodeRequired) {
}

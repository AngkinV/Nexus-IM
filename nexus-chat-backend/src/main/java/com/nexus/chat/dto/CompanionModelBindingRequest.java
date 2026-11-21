package com.nexus.chat.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Select an existing chat credential. Neither connection details nor keys are role settings. */
public record CompanionModelBindingRequest(@jakarta.validation.constraints.Positive Long credentialId, String modelName,
                                          Double temperature, Integer personaLock, Integer contextHorizon, Boolean enabled) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object ignored) {
        // Do not echo the supplied field or its value (it may contain a credential).
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported model binding field");
    }
}

package com.nexus.chat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Optional public-install login protections. Local installations keep their existing defaults. */
@Component
public class PublicAuthPolicy {
    private final boolean singleSession;
    private final boolean twoFactorLoginRequired;

    public PublicAuthPolicy(
            @Value("${nexus.auth.single-session:false}") boolean singleSession,
            @Value("${nexus.auth.two-factor-login-required:false}") boolean twoFactorLoginRequired) {
        this.singleSession = singleSession;
        this.twoFactorLoginRequired = twoFactorLoginRequired;
    }

    public boolean singleSession() { return singleSession; }
    public boolean twoFactorLoginRequired() { return twoFactorLoginRequired; }
}

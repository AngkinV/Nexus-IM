package com.nexus.chat.service;

import com.nexus.chat.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** A single source for REST and already-open socket session validity. */
@Component
@RequiredArgsConstructor
public class AuthSessionValidator {
    private final UserSessionRepository sessions;
    private final PublicAuthPolicy policy;

    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
    public boolean active(Long userId, String token) {
        if (!policy.singleSession()) {
            // Preserve the existing local-install legacy-token behavior.
            return token == null || sessions.existsBySessionToken(token);
        }
        return userId != null && token != null && !token.isBlank()
                && sessions.isActiveSession(userId, token, LocalDateTime.now(), true);
    }

    public boolean enforced() { return policy.singleSession(); }
}

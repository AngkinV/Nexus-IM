package com.nexus.chat.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * The acting user of a request always comes from the JWT principal set by
 * {@link JwtAuthenticationFilter}. Ids sent by the client are only claims.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** User id from the token; 401 when the request carries no valid token. */
    public static Long require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticated user required");
        }
        return userId;
    }

    /**
     * User id from the token, after checking the id the client sent as "me".
     * An absent claim is fine; a claim naming someone else is 403.
     * Call this outside any {@code catch (RuntimeException)} block so the status is not swallowed.
     */
    public static Long require(Long claimedUserId) {
        Long userId = require();
        if (claimedUserId != null && !claimedUserId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "user id does not match authenticated user");
        }
        return userId;
    }
}

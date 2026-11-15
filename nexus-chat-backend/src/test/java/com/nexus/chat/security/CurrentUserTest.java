package com.nexus.chat.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentUserTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList()));
    }

    @Test
    void returnsTokenUserWhenNoClaimIsSent() {
        loginAs(7L);

        assertEquals(7L, CurrentUser.require());
        assertEquals(7L, CurrentUser.require(null));
    }

    @Test
    void matchingClaimPasses() {
        loginAs(7L);

        assertEquals(7L, CurrentUser.require(7L));
    }

    @Test
    void mismatchingClaimIsForbidden() {
        loginAs(7L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> CurrentUser.require(8L));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void missingTokenIsUnauthorized() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> CurrentUser.require(7L));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void anonymousAuthenticationIsUnauthorized() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, CurrentUser::require);
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void jwtFilterRunsForAuthEndpointsThatActAsAUser() {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(null, null);

        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/auth/logout")));
        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/auth/change-password")));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/auth/login")));
    }
}

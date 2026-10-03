package com.nexus.chat.controller;

import com.nexus.chat.dto.LoginHistoryDTO;
import com.nexus.chat.dto.SecuritySettingsDTO;
import com.nexus.chat.dto.SessionDTO;
import com.nexus.chat.service.SecurityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Account-security endpoints backing the profile "Security" page: device
 * sessions, login history, security settings and two-factor setup. All values
 * are real, derived from actual login requests. Every endpoint verifies the
 * path user matches the authenticated principal.
 */
@Slf4j
@RestController
@RequestMapping("/api/users/{userId}")
@RequiredArgsConstructor
public class SecurityController {

    private final SecurityService securityService;

    /** GET /api/users/{userId}/sessions */
    @GetMapping("/sessions")
    public ResponseEntity<List<SessionDTO>> getSessions(@PathVariable Long userId, HttpServletRequest request) {
        assertSelf(userId);
        String currentToken = (String) request.getAttribute("sessionToken");
        return ResponseEntity.ok(securityService.listSessions(userId, currentToken));
    }

    /** DELETE /api/users/{userId}/sessions/{sessionId} — revoke one device */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(@PathVariable Long userId, @PathVariable Long sessionId) {
        assertSelf(userId);
        securityService.revokeSession(userId, sessionId);
        return ResponseEntity.ok().build();
    }

    /** DELETE /api/users/{userId}/sessions — sign out everywhere except current */
    @DeleteMapping("/sessions")
    public ResponseEntity<Void> revokeOtherSessions(@PathVariable Long userId) {
        assertSelf(userId);
        securityService.revokeOtherSessions(userId);
        return ResponseEntity.ok().build();
    }

    /** GET /api/users/{userId}/login-history?limit=10 */
    @GetMapping("/login-history")
    public ResponseEntity<List<LoginHistoryDTO>> getLoginHistory(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "10") int limit) {
        assertSelf(userId);
        return ResponseEntity.ok(securityService.getLoginHistory(userId, limit));
    }

    /** GET /api/users/{userId}/security */
    @GetMapping("/security")
    public ResponseEntity<SecuritySettingsDTO> getSecuritySettings(@PathVariable Long userId) {
        assertSelf(userId);
        return ResponseEntity.ok(securityService.getSecuritySettings(userId));
    }

    /** POST /api/users/{userId}/security/two-factor/setup — start TOTP enrollment */
    @PostMapping("/security/two-factor/setup")
    public ResponseEntity<Map<String, Object>> setupTwoFactor(@PathVariable Long userId) {
        assertSelf(userId);
        return ResponseEntity.ok(securityService.beginTwoFactorSetup(userId));
    }

    /** POST /api/users/{userId}/security/two-factor/verify { "code": "123456" } — confirm + enable */
    @PostMapping("/security/two-factor/verify")
    public ResponseEntity<Map<String, Object>> verifyTwoFactor(
            @PathVariable Long userId,
            @RequestBody Map<String, String> body) {
        assertSelf(userId);
        return ResponseEntity.ok(securityService.confirmTwoFactor(userId, body.get("code")));
    }

    /** PUT /api/users/{userId}/security/two-factor { "enabled": false } — disable only */
    @PutMapping("/security/two-factor")
    public ResponseEntity<SecuritySettingsDTO> disableTwoFactor(
            @PathVariable Long userId,
            @RequestBody Map<String, Boolean> body) {
        assertSelf(userId);
        if (Boolean.TRUE.equals(body.get("enabled"))) {
            // Enabling requires the setup + verify flow, not a bare toggle.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use the setup/verify flow to enable 2FA");
        }
        return ResponseEntity.ok(securityService.setTwoFactorEnabled(userId, false));
    }

    /** Reject access to another user's security data. */
    private void assertSelf(Long userId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof Long) || !principal.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}

package com.nexus.chat.service;

import com.nexus.chat.dto.LoginHistoryDTO;
import com.nexus.chat.dto.SecuritySettingsDTO;
import com.nexus.chat.dto.SessionDTO;
import com.nexus.chat.dto.WebSocketMessage;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.LoginHistory;
import com.nexus.chat.model.UserSecuritySettings;
import com.nexus.chat.model.UserSession;
import com.nexus.chat.repository.LoginHistoryRepository;
import com.nexus.chat.repository.UserRepository;
import com.nexus.chat.repository.UserSecuritySettingsRepository;
import com.nexus.chat.repository.UserSessionRepository;
import com.nexus.chat.util.TotpUtil;
import com.nexus.chat.util.UserAgentParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Owns everything behind the account-security page: session tracking, login
 * history, two-factor flag and the cached password-strength score. All values
 * are real — derived from the actual request (User-Agent, IP) and persisted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityService {

    private static final int SESSION_TTL_DAYS = 30;

    private final UserSessionRepository sessionRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final UserSecuritySettingsRepository securityRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedisMessageRelay messageRelay;

    // ---------------------------------------------------------------------
    // Login audit
    // ---------------------------------------------------------------------

    /**
     * Record a successful login: upsert the device session (one row per
     * device/browser/IP) and append a success entry to the login history.
     *
     * @return the session token of the (new or reused) current session, to be
     *         embedded in the issued JWT so later requests can identify it.
     */
    @Transactional
    public String recordSuccessfulLogin(Long userId, String ip, String userAgent) {
        UserAgentParser.Result ua = UserAgentParser.parse(userAgent);
        String location = resolveLocation(ip);

        // The freshest login becomes the only "current" session.
        sessionRepository.clearCurrentSession(userId);

        UserSession session = findMatchingSession(userId, ua, ip);
        if (session == null) {
            session = new UserSession();
            session.setUserId(userId);
            session.setSessionToken(UUID.randomUUID().toString());
        }
        session.setDeviceName(ua.deviceName());
        session.setDeviceType(ua.deviceType());
        session.setBrowser(ua.browser());
        session.setIpAddress(ip);
        session.setLocation(location);
        session.setIsCurrent(true);
        session.setExpiresAt(LocalDateTime.now().plusDays(SESSION_TTL_DAYS));
        sessionRepository.save(session);

        LoginHistory history = new LoginHistory();
        history.setUserId(userId);
        history.setSuccess(true);
        history.setIpAddress(ip);
        history.setLocation(location);
        history.setDevice(ua.deviceName());
        history.setBrowser(ua.browser());
        loginHistoryRepository.save(history);

        return session.getSessionToken();
    }

    /**
     * Record a failed login attempt (wrong password for a known user).
     */
    @Transactional
    public void recordFailedLogin(Long userId, String ip, String userAgent, String reason) {
        UserAgentParser.Result ua = UserAgentParser.parse(userAgent);
        LoginHistory history = new LoginHistory();
        history.setUserId(userId);
        history.setSuccess(false);
        history.setIpAddress(ip);
        history.setLocation(resolveLocation(ip));
        history.setDevice(ua.deviceName());
        history.setBrowser(ua.browser());
        history.setFailureReason(reason);
        loginHistoryRepository.save(history);
    }

    private UserSession findMatchingSession(Long userId, UserAgentParser.Result ua, String ip) {
        return sessionRepository.findByUserIdOrderByLastActiveDesc(userId).stream()
                .filter(s -> Objects.equals(s.getDeviceName(), ua.deviceName())
                        && Objects.equals(s.getBrowser(), ua.browser())
                        && Objects.equals(s.getIpAddress(), ip))
                .findFirst()
                .orElse(null);
    }

    // ---------------------------------------------------------------------
    // Sessions
    // ---------------------------------------------------------------------

    public List<SessionDTO> listSessions(Long userId) {
        return listSessions(userId, null);
    }

    /**
     * List a user's sessions. When {@code currentSessionToken} is provided (from
     * the requester's JWT), the matching row is marked as the real current
     * device and its {@code lastActive} is refreshed, so "current" reflects the
     * device actually viewing the page rather than just the latest login.
     */
    @Transactional
    public List<SessionDTO> listSessions(Long userId, String currentSessionToken) {
        List<UserSession> sessions = sessionRepository.findByUserIdOrderByLastActiveDesc(userId);
        if (currentSessionToken != null) {
            boolean changed = false;
            for (UserSession s : sessions) {
                boolean isThis = currentSessionToken.equals(s.getSessionToken());
                if (isThis) {
                    s.setLastActive(LocalDateTime.now());
                }
                if (!Objects.equals(s.getIsCurrent(), isThis)) {
                    s.setIsCurrent(isThis);
                    changed = true;
                }
                if (isThis) {
                    sessionRepository.save(s);
                }
            }
            if (changed) {
                sessionRepository.saveAll(sessions);
                // Re-read so ordering/flags reflect the update.
                sessions = sessionRepository.findByUserIdOrderByLastActiveDesc(userId);
            }
        }
        return sessions.stream()
                .map(this::toSessionDTO)
                .collect(Collectors.toList());
    }

    /** Revoke a single non-current session belonging to the user. */
    @Transactional
    public void revokeSession(Long userId, Long sessionId) {
        sessionRepository.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId) && !Boolean.TRUE.equals(s.getIsCurrent()))
                .ifPresent(s -> {
                    String token = s.getSessionToken();
                    sessionRepository.deleteById(s.getId());
                    forceLogoutDevices(userId, List.of(token));
                });
    }

    /** Sign out everywhere except the current device. */
    @Transactional
    public void revokeOtherSessions(Long userId) {
        List<UserSession> others = sessionRepository.findByUserIdOrderByLastActiveDesc(userId).stream()
                .filter(s -> !Boolean.TRUE.equals(s.getIsCurrent()))
                .collect(Collectors.toList());
        if (others.isEmpty()) return;
        List<String> tokens = others.stream()
                .map(UserSession::getSessionToken)
                .collect(Collectors.toList());
        sessionRepository.deleteAll(others);
        forceLogoutDevices(userId, tokens);
    }

    /** End the session identified by its token (used on explicit logout). */
    @Transactional
    public void endSession(Long userId, String sessionToken) {
        if (sessionToken == null) return;
        sessionRepository.findBySessionToken(sessionToken)
                .filter(s -> s.getUserId().equals(userId))
                .ifPresent(s -> sessionRepository.deleteById(s.getId()));
    }

    /**
     * Push an immediate force-logout to the affected devices over WebSocket so a
     * revoked device drops out instantly (not just on its next REST call). Each
     * client compares the targeted session tokens against its own and acts only
     * if it matches.
     */
    private void forceLogoutDevices(Long userId, List<String> sessionTokens) {
        try {
            WebSocketMessage msg = new WebSocketMessage(
                    WebSocketMessage.MessageType.FORCE_LOGOUT,
                    Map.of("targetSids", sessionTokens));
            messageRelay.sendToUser(userId, "/topic/user." + userId + ".messages", msg);
        } catch (Exception e) {
            log.warn("Failed to push force-logout to user {}: {}", userId, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Login history
    // ---------------------------------------------------------------------

    public List<LoginHistoryDTO> getLoginHistory(Long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Pageable page = PageRequest.of(0, safeLimit);
        return loginHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId, page).getContent().stream()
                .map(this::toHistoryDTO)
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------------
    // Security settings
    // ---------------------------------------------------------------------

    public SecuritySettingsDTO getSecuritySettings(Long userId) {
        UserSecuritySettings s = securityRepository.findByUserId(userId).orElse(null);
        SecuritySettingsDTO dto = new SecuritySettingsDTO();
        dto.setTwoFactorEnabled(s != null && Boolean.TRUE.equals(s.getTwoFactorEnabled()));
        dto.setPasswordChangedAt(s != null ? s.getPasswordChangedAt() : null);
        dto.setPasswordStrength(s != null && s.getPasswordStrength() != null ? s.getPasswordStrength() : 0);
        dto.setActiveSessions((int) sessionRepository.countByUserId(userId));
        return dto;
    }

    @Transactional
    public SecuritySettingsDTO setTwoFactorEnabled(Long userId, boolean enabled) {
        UserSecuritySettings s = getOrCreateSettings(userId);
        s.setTwoFactorEnabled(enabled);
        if (!enabled) {
            s.setTwoFactorSecret(null);
            s.setBackupCodes(null);
        }
        securityRepository.save(s);
        return getSecuritySettings(userId);
    }

    // ---------------------------------------------------------------------
    // Two-factor (TOTP) setup
    // ---------------------------------------------------------------------

    /**
     * Begin 2FA setup: generate and store a TOTP secret (not yet enabled) and
     * return it together with an otpauth URI for the authenticator app.
     */
    @Transactional
    public Map<String, Object> beginTwoFactorSetup(Long userId) {
        UserSecuritySettings s = getOrCreateSettings(userId);
        if (Boolean.TRUE.equals(s.getTwoFactorEnabled())) {
            throw new BusinessException("error.auth.2fa.already.enabled");
        }
        String secret = TotpUtil.generateSecret();
        s.setTwoFactorSecret(secret);
        securityRepository.save(s);

        String account = userRepository.findById(userId)
                .map(u -> u.getEmail() != null ? u.getEmail() : u.getUsername())
                .orElse("user");

        Map<String, Object> resp = new HashMap<>();
        resp.put("secret", secret);
        resp.put("otpauthUri", TotpUtil.otpauthUri("Nexus Chat", account, secret));
        return resp;
    }

    /**
     * Confirm 2FA: verify a code against the pending secret; on success enable
     * 2FA and return one-time backup codes (stored hashed, returned in plaintext
     * exactly once).
     */
    @Transactional
    public Map<String, Object> confirmTwoFactor(Long userId, String code) {
        UserSecuritySettings s = getOrCreateSettings(userId);
        if (s.getTwoFactorSecret() == null) {
            throw new BusinessException("error.auth.2fa.not.setup");
        }
        if (!TotpUtil.verify(s.getTwoFactorSecret(), code, 1)) {
            throw new BusinessException("error.auth.2fa.invalid.code");
        }
        List<String> plainCodes = generateBackupCodes(10);
        String stored = plainCodes.stream()
                .map(passwordEncoder::encode)
                .collect(Collectors.joining(","));
        s.setTwoFactorEnabled(true);
        s.setBackupCodes(stored);
        securityRepository.save(s);

        Map<String, Object> resp = new HashMap<>();
        resp.put("enabled", true);
        resp.put("backupCodes", plainCodes);
        return resp;
    }

    private List<String> generateBackupCodes(int n) {
        SecureRandom rnd = new SecureRandom();
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        List<String> codes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder(9);
            for (int j = 0; j < 8; j++) {
                if (j == 4) sb.append('-');
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            codes.add(sb.toString());
        }
        return codes;
    }

    /** Persist the password-change time and a freshly computed strength score. */
    @Transactional
    public void recordPasswordChange(Long userId, String newPlaintext) {
        UserSecuritySettings s = getOrCreateSettings(userId);
        s.setPasswordChangedAt(LocalDateTime.now());
        s.setPasswordStrength(computeStrength(newPlaintext));
        securityRepository.save(s);
    }

    @Transactional
    public UserSecuritySettings getOrCreateSettings(Long userId) {
        return securityRepository.findByUserId(userId).orElseGet(() -> {
            UserSecuritySettings s = new UserSecuritySettings();
            s.setUserId(userId);
            s.setTwoFactorEnabled(false);
            return securityRepository.save(s);
        });
    }

    /**
     * Strength heuristic — mirrors the client meter so the score the user saw
     * while typing matches what gets stored.
     */
    public static int computeStrength(String p) {
        if (p == null || p.isEmpty()) return 0;
        int score = 0;
        if (p.length() >= 8) score += 30;
        if (p.length() >= 12) score += 15;
        boolean hasLower = p.chars().anyMatch(Character::isLowerCase);
        boolean hasUpper = p.chars().anyMatch(Character::isUpperCase);
        if (hasLower && hasUpper) score += 20;
        if (p.chars().anyMatch(Character::isDigit)) score += 20;
        if (p.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) score += 15;
        return Math.min(100, score);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Geolocation from IP requires a GeoIP database we don't ship, so this
     * returns null rather than inventing a city. The IP itself is stored and
     * shown truthfully. Extension point for a real lookup later.
     */
    private String resolveLocation(String ip) {
        return null;
    }

    private SessionDTO toSessionDTO(UserSession s) {
        return new SessionDTO(
                s.getId(),
                s.getDeviceName(),
                s.getDeviceType() != null ? s.getDeviceType().name() : UserSession.DeviceType.unknown.name(),
                s.getBrowser(),
                s.getLocation() != null ? s.getLocation() : s.getIpAddress(),
                s.getIsCurrent(),
                s.getLastActive(),
                s.getCreatedAt());
    }

    private LoginHistoryDTO toHistoryDTO(LoginHistory h) {
        return new LoginHistoryDTO(
                h.getId(),
                h.getSuccess(),
                h.getIpAddress(),
                h.getLocation() != null ? h.getLocation() : h.getIpAddress(),
                h.getDevice(),
                h.getBrowser(),
                h.getFailureReason(),
                h.getCreatedAt());
    }
}

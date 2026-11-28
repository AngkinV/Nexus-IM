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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class SecurityService {

    private static final int SESSION_TTL_DAYS = 30;

    private final UserSessionRepository sessionRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final UserSecuritySettingsRepository securityRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedisMessageRelay messageRelay;
    private final PublicAuthPolicy authPolicy;
    private final LoginAuditService loginAudit;
    private final LoginThrottle loginThrottle;

    /** Preserve direct callers; production shares the same failed-authentication counter as login. */
    public SecurityService(UserSessionRepository sessions, LoginHistoryRepository history,
                           UserSecuritySettingsRepository settings, UserRepository users,
                           PasswordEncoder passwords, RedisMessageRelay relay,
                           PublicAuthPolicy policy, LoginAuditService audit) {
        this(sessions, history, settings, users, passwords, relay, policy, audit, new LoginThrottle(0, 15));
    }

    /** Existing direct callers keep local defaults; Spring supplies the public policy. */
    public SecurityService(UserSessionRepository sessions, LoginHistoryRepository history,
                           UserSecuritySettingsRepository settings, UserRepository users,
                           PasswordEncoder passwords, RedisMessageRelay relay) {
        this(sessions, history, settings, users, passwords, relay,
                new PublicAuthPolicy(false, false), new LoginAuditService(history));
    }

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

        List<String> revokedTokens = List.of();
        UserSession session;
        if (authPolicy.singleSession()) {
            lockSecurityUser(userId);
            List<UserSession> previous = sessionRepository.findByUserIdOrderByLastActiveDesc(userId);
            revokedTokens = previous.stream().map(UserSession::getSessionToken)
                    .filter(token -> token != null && !token.isBlank()).toList();
            sessionRepository.deleteAll(previous);
            session = null; // Never reuse a device/browser/IP session in public mode.
        } else {
            sessionRepository.clearCurrentSession(userId);
            session = findMatchingSession(userId, ua, ip);
        }
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
        if (!revokedTokens.isEmpty()) forceLogoutDevices(userId, revokedTokens, "new_login");

        return session.getSessionToken();
    }

    /**
     * Record a failed login attempt (wrong password for a known user).
     */
    @Transactional
    public void recordFailedLogin(Long userId, String ip, String userAgent, String reason) {
        Runnable audit = () -> {
            try { loginAudit.failed(userId, ip, userAgent, reason); }
            catch (RuntimeException unavailable) {
                log.warn("Failed login audit unavailable: {}", unavailable.getClass().getSimpleName());
            }
        };
        // Authentication throws and rolls back. Persist the audit afterwards, in a new transaction,
        // rather than losing it or deadlocking a second transaction against the account row lock.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { audit.run(); }
            });
        } else audit.run();
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
        if (authPolicy.singleSession()) {
            lockSecurityUser(userId);
            requireCurrentSession(userId, currentSessionToken);
        }
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
        if (authPolicy.singleSession()) { revokeSession(userId, sessionId, null); return; }
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
        if (authPolicy.singleSession()) { revokeOtherSessions(userId, null); return; }
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

    @Transactional
    public void revokeSession(Long userId, Long sessionId, String currentToken) {
        if (!authPolicy.singleSession()) { revokeSession(userId, sessionId); return; }
        lockSecurityUser(userId);
        requireCurrentSession(userId, currentToken);
        sessionRepository.findById(sessionId)
                .filter(session -> Objects.equals(session.getUserId(), userId)
                        && !Objects.equals(session.getSessionToken(), currentToken))
                .ifPresent(session -> {
                    sessionRepository.deleteById(session.getId());
                    forceLogoutDevices(userId, List.of(session.getSessionToken()));
                });
    }

    @Transactional
    public void revokeOtherSessions(Long userId, String currentToken) {
        if (!authPolicy.singleSession()) { revokeOtherSessions(userId); return; }
        revokeOtherSessions(userId, currentToken, "session_revoked");
    }

    /** Preserve the JWT request's actual session, never a flag another device may have changed. */
    @Transactional
    public void revokeOtherSessions(Long userId, String currentToken, String reason) {
        lockSecurityUser(userId);
        requireCurrentSession(userId, currentToken);
        List<UserSession> others = sessionRepository.findByUserIdOrderByLastActiveDesc(userId).stream()
                .filter(session -> !Objects.equals(session.getSessionToken(), currentToken)).toList();
        if (others.isEmpty()) return;
        List<String> tokens = others.stream().map(UserSession::getSessionToken).toList();
        sessionRepository.deleteAll(others);
        forceLogoutDevices(userId, tokens, reason);
    }

    public void requireCurrentSession(Long userId, String currentToken) {
        if (authPolicy.singleSession() && (currentToken == null || currentToken.isBlank()
                || !sessionRepository.isActiveSession(userId, currentToken, LocalDateTime.now(), true))) {
            throw new BusinessException("error.auth.session.revoked");
        }
    }

    /** End the session identified by its token (used on explicit logout). */
    @Transactional
    public void endSession(Long userId, String sessionToken) {
        if (sessionToken == null) return;
        sessionRepository.findBySessionToken(sessionToken)
                .filter(s -> s.getUserId().equals(userId))
                .ifPresent(s -> {
                    sessionRepository.deleteById(s.getId());
                    if (authPolicy.singleSession()) forceLogoutDevices(userId, List.of(sessionToken), "logout");
                });
    }

    /**
     * Push an immediate force-logout to the affected devices over WebSocket so a
     * revoked device drops out instantly (not just on its next REST call). Each
     * client compares the targeted session tokens against its own and acts only
     * if it matches.
     */
    private void forceLogoutDevices(Long userId, List<String> sessionTokens) {
        forceLogoutDevices(userId, sessionTokens, "session_revoked");
    }

    private void forceLogoutDevices(Long userId, List<String> sessionTokens, String reason) {
        List<String> targets = sessionTokens.stream().filter(token -> token != null && !token.isBlank()).distinct().toList();
        if (targets.isEmpty()) return;
        Runnable notify = () -> {
            try {
                WebSocketMessage msg = new WebSocketMessage(WebSocketMessage.MessageType.FORCE_LOGOUT,
                        Map.of("targetSids", targets, "reason", reason));
                if (authPolicy.singleSession()) messageRelay.sendLogoutNotice(userId, "/topic/user." + userId + ".messages", msg);
                else messageRelay.sendToUser(userId, "/topic/user." + userId + ".messages", msg);
            } catch (RuntimeException unavailable) {
                log.warn("Logout notice unavailable: {}", unavailable.getClass().getSimpleName());
            }
            if (authPolicy.singleSession()) {
                try { messageRelay.revokeSessions(userId, targets, reason); }
                catch (RuntimeException unavailable) {
                    log.warn("Socket revocation relay unavailable: {}", unavailable.getClass().getSimpleName());
                }
            }
        };
        // Never kick an existing device for a login/password change that subsequently rolls back.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { notify.run(); }
            });
        } else notify.run();
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

    /** Called only after the password matches, in the account-locking authentication transaction. */
    @Transactional
    public void requireLoginSecondFactor(Long userId, String supplied) {
        if (!authPolicy.twoFactorLoginRequired()) return;
        lockSecurityUser(userId);
        UserSecuritySettings settings = securityRepository.findByUserId(userId).orElse(null);
        if (settings == null || !Boolean.TRUE.equals(settings.getTwoFactorEnabled())) return;
        acceptSecondFactor(settings, supplied);
    }

    /** The same atomic verifier serves login and explicitly reauthenticated security changes. */
    private void acceptSecondFactor(UserSecuritySettings settings, String supplied) {
        if (settings.getTwoFactorSecret() == null || settings.getTwoFactorSecret().isBlank()) {
            throw new BusinessException("error.auth.2fa.not.setup");
        }
        if (supplied == null || supplied.isBlank()) throw new BusinessException("error.auth.2fa.required");
        String code = supplied.trim();
        if (code.length() > 32) throw new BusinessException("error.auth.2fa.invalid.code");
        if (code.matches("[0-9]{6}")) {
            Long step = TotpUtil.matchingStep(settings.getTwoFactorSecret(), code, 1);
            if (step == null) throw new BusinessException("error.auth.2fa.invalid.code");
            if (settings.getLastAcceptedTotpStep() != null && step <= settings.getLastAcceptedTotpStep()) {
                throw new BusinessException("error.auth.2fa.code.used");
            }
            settings.setLastAcceptedTotpStep(step);
            securityRepository.save(settings);
            return;
        }
        String compact = code.toUpperCase(java.util.Locale.ROOT).replace("-", "");
        if (!compact.matches("[A-Z2-9]{8}")) throw new BusinessException("error.auth.2fa.invalid.code");
        String recovery = compact.substring(0, 4) + "-" + compact.substring(4);
        List<String> hashes = settings.getBackupCodes() == null || settings.getBackupCodes().isBlank()
                ? new ArrayList<>() : new ArrayList<>(java.util.Arrays.asList(settings.getBackupCodes().split(",")));
        for (int i = 0; i < hashes.size(); i++) {
            boolean matches;
            try { matches = passwordEncoder.matches(recovery, hashes.get(i)); }
            catch (RuntimeException invalidHash) { matches = false; }
            if (matches) {
                hashes.remove(i);
                settings.setBackupCodes(String.join(",", hashes));
                securityRepository.save(settings);
                return;
            }
        }
        throw new BusinessException("error.auth.2fa.invalid.code");
    }

    @Transactional
    public SecuritySettingsDTO setTwoFactorEnabled(Long userId, boolean enabled) {
        return setTwoFactorEnabled(userId, enabled, null);
    }

    @Transactional
    public SecuritySettingsDTO setTwoFactorEnabled(Long userId, boolean enabled, String currentToken) {
        return setTwoFactorEnabled(userId, enabled, currentToken, null);
    }

    @Transactional
    public SecuritySettingsDTO setTwoFactorEnabled(Long userId, boolean enabled, String currentToken, String code) {
        lockSecurityUser(userId);
        requireCurrentSession(userId, currentToken);
        UserSecuritySettings s = getOrCreateSettings(userId);
        if (!enabled && authPolicy.twoFactorLoginRequired() && Boolean.TRUE.equals(s.getTwoFactorEnabled())) {
            String key = "user:" + userId;
            if (code != null && !code.isBlank()) loginThrottle.requireAllowed(key);
            try {
                acceptSecondFactor(s, code);
            } catch (BusinessException invalid) {
                if (!"error.auth.2fa.required".equals(invalid.getMessage())
                        && !"error.auth.2fa.not.setup".equals(invalid.getMessage())) loginThrottle.failed(key);
                throw invalid;
            }
            // Only a fully successful login may reset prior failed sign-ins, not this security action.
        }
        if (enabled && authPolicy.twoFactorLoginRequired()
                && (!Boolean.TRUE.equals(s.getTwoFactorEnabled())
                    || s.getTwoFactorSecret() == null || s.getTwoFactorSecret().isBlank())) {
            throw new BusinessException("error.auth.2fa.confirm.required");
        }
        s.setTwoFactorEnabled(enabled);
        if (!enabled) {
            s.setTwoFactorSecret(null);
            s.setBackupCodes(null);
            s.setLastAcceptedTotpStep(null);
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
        return beginTwoFactorSetup(userId, null);
    }

    @Transactional
    public Map<String, Object> beginTwoFactorSetup(Long userId, String currentToken) {
        lockSecurityUser(userId);
        requireCurrentSession(userId, currentToken);
        UserSecuritySettings s = getOrCreateSettings(userId);
        if (Boolean.TRUE.equals(s.getTwoFactorEnabled())) {
            throw new BusinessException("error.auth.2fa.already.enabled");
        }
        String secret = TotpUtil.generateSecret();
        s.setTwoFactorSecret(secret);
        s.setLastAcceptedTotpStep(null);
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
        return confirmTwoFactor(userId, code, null);
    }

    @Transactional
    public Map<String, Object> confirmTwoFactor(Long userId, String code, String currentToken) {
        lockSecurityUser(userId);
        requireCurrentSession(userId, currentToken);
        UserSecuritySettings s = getOrCreateSettings(userId);
        if (s.getTwoFactorSecret() == null) {
            throw new BusinessException("error.auth.2fa.not.setup");
        }
        if (authPolicy.twoFactorLoginRequired() && Boolean.TRUE.equals(s.getTwoFactorEnabled())) {
            throw new BusinessException("error.auth.2fa.already.enabled");
        }
        Long step = authPolicy.twoFactorLoginRequired() ? TotpUtil.matchingStep(s.getTwoFactorSecret(), code, 1) : null;
        if (authPolicy.twoFactorLoginRequired() ? step == null : !TotpUtil.verify(s.getTwoFactorSecret(), code, 1)) {
            throw new BusinessException("error.auth.2fa.invalid.code");
        }
        if (step != null) s.setLastAcceptedTotpStep(step);
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

    private void lockSecurityUser(Long userId) {
        if (authPolicy.singleSession() || authPolicy.twoFactorLoginRequired()) {
            userRepository.findForSecurityUpdate(userId)
                    .orElseThrow(() -> new BusinessException("error.user.not.found"));
        }
    }

    @Transactional
    public UserSecuritySettings getOrCreateSettings(Long userId) {
        lockSecurityUser(userId);
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

package com.nexus.chat.service;

import com.nexus.chat.dto.AuthResponse;
import com.nexus.chat.dto.LoginRequest;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.security.JwtAuthenticationFilter;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.util.TotpUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real isolated H2 transactions/repositories. No Spring application, ports, Redis or user database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublicLoginSecurityTest {
    private static final String PASSWORD = "synthetic-login-password";
    private static final String RECOVERY = "ABCD-2345";
    private LocalContainerEntityManagerFactoryBean factory;
    private EntityManagerFactory emf;
    private JpaTransactionManager manager;
    private TransactionTemplate transaction;
    private UserRepository users;
    private UserSessionRepository sessions;
    private UserSecuritySettingsRepository settings;
    private LoginHistoryRepository history;
    private final PasswordEncoder passwords = new BCryptPasswordEncoder(4);
    private SecurityService security;
    private AuthService auth;
    private JwtTokenProvider jwt;
    private RedisMessageRelay relay;
    private User user;

    @BeforeAll void database() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:public-login-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of(User.class.getName(), UserSession.class.getName(),
                UserSecuritySettings.class.getName(), LoginHistory.class.getName(), Contact.class.getName()));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.show_sql", "false"));
        factory.afterPropertiesSet();
        emf = factory.getObject();
        manager = new JpaTransactionManager(emf);
        transaction = new TransactionTemplate(manager);
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(emf);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(shared);
        users = repositories.getRepository(UserRepository.class);
        sessions = repositories.getRepository(UserSessionRepository.class);
        settings = repositories.getRepository(UserSecuritySettingsRepository.class);
        history = repositories.getRepository(LoginHistoryRepository.class);
    }

    @BeforeEach void account() {
        relay = mock(RedisMessageRelay.class);
        jwt = spy(new JwtTokenProvider());
        ReflectionTestUtils.setField(jwt, "jwtSecret", UUID.randomUUID().toString() + UUID.randomUUID());
        ReflectionTestUtils.setField(jwt, "jwtExpiration", 120_000L);
        configure(true);
        user = inTransaction(() -> {
            User row = new User();
            row.setUsername("synthetic-" + UUID.randomUUID());
            row.setEmail(row.getUsername() + "@example.test");
            row.setPasswordHash(passwords.encode(PASSWORD));
            row.setNickname("Synthetic");
            return users.saveAndFlush(row);
        });
    }

    private void configure(boolean publicMode) {
        var policy = new PublicAuthPolicy(publicMode, publicMode);
        var audit = transactional(new LoginAuditService(history));
        security = transactional(new SecurityService(sessions, history, settings, users, passwords, relay, policy, audit));
        auth = transactional(new AuthService(users, passwords, jwt, mock(VerificationCodeService.class), security,
                mock(FileAccessService.class), mock(RegistrationPolicy.class), new LoginThrottle(8, 15), policy));
    }

    @SuppressWarnings("unchecked") private <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
    private <T> T inTransaction(Supplier<T> action) { return transaction.execute(status -> action.get()); }
    private AuthResponse login(String factor) {
        return auth.login(new LoginRequest(user.getUsername(), PASSWORD, factor), "synthetic-ip", "Same browser");
    }
    private String sid(AuthResponse result) { return jwt.getSessionTokenFromToken(result.getToken()); }
    private List<UserSession> liveRows() { return inTransaction(() -> sessions.findByUserIdOrderByLastActiveDesc(user.getId())); }
    private boolean active(String sid) {
        return inTransaction(() -> sessions.isActiveSession(user.getId(), sid, LocalDateTime.now(), true));
    }
    private void enrollRecovery() {
        inTransaction(() -> {
            UserSecuritySettings row = new UserSecuritySettings();
            row.setUserId(user.getId()); row.setTwoFactorEnabled(true);
            row.setTwoFactorSecret(TotpUtil.generateSecret());
            row.setBackupCodes(passwords.encode(RECOVERY));
            return settings.saveAndFlush(row);
        });
    }

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }
    @AfterAll void closeDatabase() { if (factory != null) factory.destroy(); }

    @Test void aSuccessCreatesANewSessionEvenForTheSameBrowserAndInvalidatesTheOldJwtAtRest() throws Exception {
        AuthResponse first = login(null), second = login(null);
        assertNotEquals(sid(first), sid(second));
        assertEquals(1, liveRows().size());
        assertFalse(active(sid(first))); assertTrue(active(sid(second)));
        assertTrue(jwt.validateToken(first.getToken()), "signature validity is not session authorization");
        var validator = transactional(new AuthSessionValidator(sessions, new PublicAuthPolicy(true, true)));
        var filter = new JwtAuthenticationFilter(jwt, sessions, validator);
        var request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer " + first.getToken());
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus()); assertNull(chain.getRequest());
        assertTrue(response.getContentAsString().contains("SESSION_REVOKED"));
    }

    @Test void failedPasswordOrMissingAndWrongFactorNeverReplacesTheActiveSession() {
        String original = sid(login(null)); enrollRecovery(); clearInvocations(relay);
        assertThrows(BusinessException.class, () -> auth.login(new LoginRequest(user.getUsername(), "incorrect"), "ip", "browser"));
        assertEquals("error.auth.2fa.required", assertThrows(BusinessException.class, () -> login(null)).getMessage());
        assertEquals("error.auth.2fa.invalid.code", assertThrows(BusinessException.class, () -> login("AAAA-2222")).getMessage());
        assertEquals(original, liveRows().get(0).getSessionToken()); assertTrue(active(original));
        verify(relay, never()).revokeSessions(anyLong(), anyList(), anyString());
        assertEquals(2, inTransaction(() -> history.findByUserIdOrderByCreatedAtDesc(user.getId(),
                org.springframework.data.domain.PageRequest.of(0, 20)).stream().filter(h -> !h.getSuccess()).count()));
    }

    @Test void recoveryIsConsumedOnlyOnceAndARejectedReuseDoesNotKickTheSuccessfulLogin() {
        enrollRecovery(); AuthResponse first = login("abcd2345");
        assertEquals("", inTransaction(() -> settings.findByUserId(user.getId()).orElseThrow().getBackupCodes()));
        assertThrows(BusinessException.class, () -> login(RECOVERY));
        assertTrue(active(sid(first))); assertEquals(1, liveRows().size());
    }

    @Test void rollbackKeepsThePreviousSessionAndRecoveryCodeAndDoesNotSendARevocation() {
        String original = sid(login(null)); enrollRecovery(); clearInvocations(relay);
        doThrow(new IllegalStateException("synthetic-issue-failure")).when(jwt).generateToken(eq(user.getId()), anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> login(RECOVERY));
        assertTrue(active(original)); assertEquals(original, liveRows().get(0).getSessionToken());
        assertFalse(inTransaction(() -> settings.findByUserId(user.getId()).orElseThrow().getBackupCodes()).isEmpty());
        verify(relay, never()).revokeSessions(anyLong(), anyList(), anyString());
        reset(jwt);
        assertTrue(active(sid(login(RECOVERY))));
    }

    @Test void revocationIsObservedOnlyAfterTheNewSessionActuallyCommits() {
        String original = sid(login(null));
        AtomicBoolean observed = new AtomicBoolean();
        doAnswer(call -> {
            TransactionTemplate independent = new TransactionTemplate(manager);
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.execute(status -> {
                assertFalse(sessions.existsBySessionToken(original), "a separate transaction must observe the committed deletion");
                assertEquals(1, sessions.countByUserId(user.getId()));
                observed.set(true); return null;
            });
            return null;
        }).when(relay).revokeSessions(eq(user.getId()), anyList(), eq("new_login"));
        AuthResponse result = login(null);
        assertTrue(observed.get()); assertTrue(active(sid(result)));
    }

    @Test void overlappingLoginsLeaveExactlyTheLaterCommittedSession() throws Exception {
        assertOverlappingLogin(false);
    }
    @Test void overlappingRecoveryAttemptsCannotBothConsumeOneRecoveryCode() throws Exception {
        assertOverlappingLogin(true);
    }
    private void assertOverlappingLogin(boolean recovery) throws Exception {
        if (recovery) enrollRecovery();
        String factor = recovery ? RECOVERY : null;
        CountDownLatch issuing = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean();
        doAnswer(call -> {
            if (once.compareAndSet(false, true)) {
                issuing.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic-timeout");
            }
            return call.callRealMethod();
        }).when(jwt).generateToken(eq(user.getId()), anyString(), anyString());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AuthResponse> first = pool.submit(() -> login(factor));
            assertTrue(issuing.await(5, TimeUnit.SECONDS));
            Future<AuthResponse> second = pool.submit(() -> login(factor));
            assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS), "the account row is still locked");
            release.countDown(); AuthResponse a = first.get(5, TimeUnit.SECONDS);
            if (recovery) {
                ExecutionException rejected = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
                assertInstanceOf(BusinessException.class, rejected.getCause()); assertTrue(active(sid(a)));
            } else {
                AuthResponse b = second.get(5, TimeUnit.SECONDS);
                assertNotEquals(sid(a), sid(b)); assertFalse(active(sid(a))); assertTrue(active(sid(b)));
            }
            assertEquals(1, liveRows().size());
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void passwordChangeUsesTheRequestSessionNotTheCurrentUiFlagAndRejectsStaleActors() {
        AuthResponse first = login(null), second = login(null);
        assertThrows(BusinessException.class, () -> auth.changePassword(user.getId(), PASSWORD, "new-synthetic-password", sid(first)));
        inTransaction(() -> {
            UserSession current = sessions.findBySessionToken(sid(second)).orElseThrow(); current.setIsCurrent(false); sessions.save(current);
            UserSession expired = new UserSession(); expired.setUserId(user.getId()); expired.setSessionToken(UUID.randomUUID().toString());
            expired.setIsCurrent(true); expired.setExpiresAt(LocalDateTime.now().minusDays(1)); sessions.save(expired); return null;
        });
        auth.changePassword(user.getId(), PASSWORD, "new-synthetic-password", sid(second));
        assertTrue(active(sid(second))); assertEquals(1, liveRows().size());
        assertEquals(sid(second), liveRows().get(0).getSessionToken());
        assertTrue(inTransaction(() -> passwords.matches("new-synthetic-password", users.findById(user.getId()).orElseThrow().getPasswordHash())));
    }

    @Test void defaultLocalBehaviorStillReusesTheDeviceAndDoesNotRequireTheSavedSecondFactor() {
        configure(false); enrollRecovery();
        AuthResponse first = login(null), second = login(null);
        assertEquals(sid(first), sid(second)); assertEquals(1, liveRows().size());
        assertFalse(inTransaction(() -> settings.findByUserId(user.getId()).orElseThrow().getBackupCodes()).isEmpty());
        verify(relay, never()).revokeSessions(anyLong(), anyList(), anyString());
    }

    @Test void publicTokensNeedASessionAndCannotUseAnotherOwnersSessionOrAnExpiredOne() {
        var validator = transactional(new AuthSessionValidator(sessions, new PublicAuthPolicy(true, true)));
        assertFalse(validator.active(user.getId(), null));
        String current = sid(login(null)); assertFalse(validator.active(user.getId() + 1000, current));
        inTransaction(() -> { var row = sessions.findBySessionToken(current).orElseThrow(); row.setExpiresAt(LocalDateTime.now().minusSeconds(1)); return sessions.save(row); });
        assertFalse(validator.active(user.getId(), current));
    }

    @Test void aCorrectUnusedTotpCompletesLoginAndTheSameCodeCannotReplaceThatSessionAgain() throws Exception {
        enrollRecovery();
        String secret = inTransaction(() -> settings.findByUserId(user.getId()).orElseThrow().getTwoFactorSecret());
        Method decode = TotpUtil.class.getDeclaredMethod("base32Decode", String.class); decode.setAccessible(true);
        Method generate = TotpUtil.class.getDeclaredMethod("generateCode", byte[].class, long.class); generate.setAccessible(true);
        String code = (String) generate.invoke(null, (byte[]) decode.invoke(null, secret), System.currentTimeMillis() / 30_000);
        AuthResponse result = login(code);
        assertTrue(active(sid(result)));
        assertEquals("error.auth.2fa.code.used", assertThrows(BusinessException.class, () -> login(code)).getMessage());
        assertTrue(active(sid(result))); assertEquals(1, liveRows().size());
        UserSecuritySettings stored = inTransaction(() -> settings.findByUserId(user.getId()).orElseThrow());
        assertFalse(stored.toString().contains(secret)); assertFalse(stored.toString().contains(stored.getBackupCodes()));
    }

    @Test void setupMustBeConfirmedAndAcceptedTotpCannotBeReplayedOrRecoveriesRegeneratedByVerify() throws Exception {
        String current = sid(login(null));
        assertThrows(BusinessException.class, () -> security.setTwoFactorEnabled(user.getId(), true, current));
        String secret = (String) security.beginTwoFactorSetup(user.getId(), current).get("secret");
        Method decode = TotpUtil.class.getDeclaredMethod("base32Decode", String.class); decode.setAccessible(true);
        Method generate = TotpUtil.class.getDeclaredMethod("generateCode", byte[].class, long.class); generate.setAccessible(true);
        String code = (String) generate.invoke(null, (byte[]) decode.invoke(null, secret), System.currentTimeMillis() / 30_000);
        var confirmed = security.confirmTwoFactor(user.getId(), code, current);
        assertEquals(true, confirmed.get("enabled")); assertEquals(10, ((List<?>) confirmed.get("backupCodes")).size());
        assertEquals("error.auth.2fa.code.used", assertThrows(BusinessException.class, () -> login(code)).getMessage());
        assertThrows(BusinessException.class, () -> security.confirmTwoFactor(user.getId(), code, current));
        String recovery = (String) ((List<?>) confirmed.get("backupCodes")).get(0);
        assertTrue(active(sid(login(recovery))));
        assertThrows(BusinessException.class, () -> security.setTwoFactorEnabled(user.getId(), false, current));
    }
}

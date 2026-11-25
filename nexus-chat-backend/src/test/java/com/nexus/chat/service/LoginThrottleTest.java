package com.nexus.chat.service;

import com.nexus.chat.dto.LoginRequest;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.EmailVerificationCode.CodeType;
import com.nexus.chat.model.User;
import com.nexus.chat.repository.EmailVerificationCodeRepository;
import com.nexus.chat.repository.InviteCodeRepository;
import com.nexus.chat.repository.UserRepository;
import com.nexus.chat.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Sign-in lockout and the caps on email codes. Synthetic accounts only. */
class LoginThrottleTest {
    final AtomicLong clock = new AtomicLong(1_000_000);
    final UserRepository users = mock(UserRepository.class);
    final PasswordEncoder passwords = mock(PasswordEncoder.class);
    final SecurityService security = mock(SecurityService.class);

    AuthService service(LoginThrottle throttle) {
        User user = new User();
        user.setId(7L);
        user.setUsername("synthetic");
        user.setPasswordHash("hash");
        when(users.findByUsernameOrEmail(anyString(), anyString())).thenReturn(Optional.empty());
        when(users.findByUsernameOrEmail("synthetic", "synthetic")).thenReturn(Optional.of(user));
        when(users.findByUsernameOrEmail("synthetic@example.test", "synthetic@example.test")).thenReturn(Optional.of(user));
        when(passwords.matches("right", "hash")).thenReturn(true);
        return new AuthService(users, passwords, mock(JwtTokenProvider.class), mock(VerificationCodeService.class), security,
                mock(FileAccessService.class), new RegistrationPolicy(mock(InviteCodeRepository.class), false, true), throttle);
    }

    String login(AuthService service, String name, String password) {
        try {
            service.login(new LoginRequest(name, password), "ip", "agent");
            return "ok";
        } catch (BusinessException refused) {
            return refused.getMessage();
        }
    }

    @Test void whenOffAnyNumberOfFailuresStillEndsWithTheUsualAnswerAndTheRightPasswordWorks() {
        AuthService service = service(new LoginThrottle(0, 15, clock::get));
        for (int i = 0; i < 50; i++) assertEquals("error.auth.invalid.credentials", login(service, "synthetic", "wrong"));
        assertEquals("ok", login(service, "synthetic", "right"));
    }

    @Test void anAccountLocksAfterTheLimitUnderAnyOfItsNamesAndThePasswordIsNoLongerChecked() {
        AuthService service = service(new LoginThrottle(3, 15, clock::get));
        assertEquals("error.auth.invalid.credentials", login(service, "synthetic", "wrong"));
        assertEquals("error.auth.invalid.credentials", login(service, "synthetic@example.test", "wrong"));
        assertEquals("error.auth.invalid.credentials", login(service, "synthetic", "wrong"));
        clearInvocations(passwords);
        assertEquals(LoginThrottle.LOCKED, login(service, "synthetic@example.test", "right"));
        assertEquals(LoginThrottle.LOCKED, login(service, "synthetic", "wrong"));
        verifyNoInteractions(passwords);
        verify(security, times(3)).recordFailedLogin(eq7(), any(), any(), any());

        clock.addAndGet(15 * 60_000L - 1);
        assertEquals(LoginThrottle.LOCKED, login(service, "synthetic", "right"));
        clock.addAndGet(1);
        assertEquals("ok", login(service, "synthetic", "right"));
    }

    private static Long eq7() { return org.mockito.ArgumentMatchers.eq(7L); }

    @Test void aSuccessfulSignInClearsEarlierFailures() {
        AuthService service = service(new LoginThrottle(3, 15, clock::get));
        for (int round = 0; round < 4; round++) {
            assertEquals("error.auth.invalid.credentials", login(service, "synthetic", "wrong"));
            assertEquals("error.auth.invalid.credentials", login(service, "synthetic", "wrong"));
            assertEquals("ok", login(service, "synthetic", "right"));
        }
    }

    @Test void oldFailuresDoNotAddUpAcrossTheLockPeriod() {
        LoginThrottle throttle = new LoginThrottle(2, 15, clock::get);
        throttle.failed("user:7");
        clock.addAndGet(15 * 60_000L);
        throttle.failed("user:7");
        throttle.requireAllowed("user:7");
        throttle.failed("user:7");
        assertThrows(BusinessException.class, () -> throttle.requireAllowed("user:7"));
    }

    @Test void namesWithoutAnAccountAnswerAndLockLikeRealOnesAndDoNotAffectOthers() {
        AuthService service = service(new LoginThrottle(2, 15, clock::get));
        assertEquals("error.auth.invalid.credentials", login(service, " Nobody ", "x"));
        assertEquals("error.auth.invalid.credentials", login(service, "nobody", "x"));
        assertEquals(LoginThrottle.LOCKED, login(service, "NOBODY", "x"));
        assertEquals("ok", login(service, "synthetic", "right"));
    }

    @Test void aFloodOfMadeUpNamesCannotGrowTheTableWithoutBound() {
        LoginThrottle throttle = new LoginThrottle(2, 15, clock::get);
        for (int i = 0; i < LoginThrottle.MAX_TRACKED + 500; i++) throttle.failed("name:" + i);
        @SuppressWarnings("unchecked")
        java.util.Map<String, ?> tracked = (java.util.Map<String, ?>) ReflectionTestUtils.getField(throttle, "attempts");
        assertEquals(LoginThrottle.MAX_TRACKED, tracked.size());
        clock.addAndGet(15 * 60_000L);
        throttle.failed("user:7");
        assertEquals(1, tracked.size());
    }

    VerificationCodeService codes(EmailVerificationCodeRepository repository, EmailService mail, int perEmail, int global) {
        VerificationCodeService service = new VerificationCodeService(repository, mock(UserRepository.class), mail);
        ReflectionTestUtils.setField(service, "expireMinutes", 10);
        ReflectionTestUtils.setField(service, "resendIntervalSeconds", 60);
        ReflectionTestUtils.setField(service, "dailyLimitPerEmail", perEmail);
        ReflectionTestUtils.setField(service, "dailyLimitGlobal", global);
        return service;
    }

    @Test void emailCodesAreCappedPerAddressAndInTotalOnlyWhenConfigured() {
        EmailVerificationCodeRepository repository = mock(EmailVerificationCodeRepository.class);
        when(repository.findTopByEmailAndTypeOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(repository.countByEmailAndCreatedAtAfter(anyString(), any())).thenReturn(5L);
        when(repository.countByCreatedAtAfter(any())).thenReturn(200L);
        EmailService mail = mock(EmailService.class);

        codes(repository, mail, 0, 0).sendVerificationCode("synthetic@example.test", CodeType.REGISTER);
        verify(mail, times(1)).sendVerificationCode(anyString(), anyString());
        codes(repository, mail, 6, 201).sendVerificationCode("synthetic@example.test", CodeType.REGISTER);
        verify(mail, times(2)).sendVerificationCode(anyString(), anyString());

        for (int[] caps : new int[][]{{5, 0}, {0, 200}, {5, 200}}) {
            assertThrows(BusinessException.class,
                    () -> codes(repository, mail, caps[0], caps[1]).sendVerificationCode("synthetic@example.test", CodeType.REGISTER));
        }
        verify(mail, times(2)).sendVerificationCode(anyString(), anyString());
        verify(repository, times(2)).save(any());
    }
}

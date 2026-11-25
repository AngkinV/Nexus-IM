package com.nexus.chat.service;

import com.nexus.chat.dto.RegisterRequest;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.EmailVerificationCode.CodeType;
import com.nexus.chat.model.InviteCode;
import com.nexus.chat.model.User;
import com.nexus.chat.repository.InviteCodeRepository;
import com.nexus.chat.repository.UserRepository;
import com.nexus.chat.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Registration under both policies, with every collaborator mocked. */
class AuthServiceRegistrationTest {
    final UserRepository users = mock(UserRepository.class);
    final VerificationCodeService emailCodes = mock(VerificationCodeService.class);
    final InviteCodeRepository invites = mock(InviteCodeRepository.class);

    AuthService service(boolean inviteRequired, boolean emailCodeRequired) {
        when(users.save(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            user.setId(1L);
            return user;
        });
        return new AuthService(users, mock(PasswordEncoder.class), mock(JwtTokenProvider.class), emailCodes,
                mock(SecurityService.class), mock(FileAccessService.class),
                new RegistrationPolicy(invites, inviteRequired, emailCodeRequired));
    }

    RegisterRequest request(String emailCode, String inviteCode) {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("synthetic@example.test");
        request.setUsername("synthetic");
        request.setPassword("synthetic-password");
        request.setNickname("Synthetic");
        request.setVerificationCode(emailCode);
        request.setInviteCode(inviteCode);
        return request;
    }

    @Test void byDefaultRegistrationNeedsTheEmailCodeAndNoInvite() {
        AuthService service = service(false, true);
        assertEquals("请输入邮箱验证码",
                assertThrows(BusinessException.class, () -> service.register(request(null, null), "ip", "agent")).getMessage());
        when(emailCodes.verifyCode("synthetic@example.test", "123456", CodeType.REGISTER)).thenReturn(false);
        assertEquals("验证码无效或已过期",
                assertThrows(BusinessException.class, () -> service.register(request("123456", null), "ip", "agent")).getMessage());
        verify(users, never()).save(any());

        when(emailCodes.verifyCode("synthetic@example.test", "123456", CodeType.REGISTER)).thenReturn(true);
        assertEquals("synthetic", service.register(request("123456", null), "ip", "agent").getUsername());
        verify(users).save(any());
        verifyNoInteractions(invites);
        assertFalse(service.registrationPolicy().inviteRequired());
        assertTrue(service.registrationPolicy().emailCodeRequired());
    }

    @Test void withInvitesRequiredNothingIsLookedUpBeforeTheInviteIsAccepted() {
        AuthService service = service(true, false);
        for (String code : new String[]{null, "UNKNOWN-CODE"}) {
            assertEquals(RegistrationPolicy.INVITE_REJECTED,
                    assertThrows(BusinessException.class, () -> service.register(request("123456", code), "ip", "agent")).getMessage());
        }
        verifyNoInteractions(users, emailCodes);
    }

    @Test void withInvitesRequiredAUsableInviteRegistersWithoutAnEmailCode() {
        AuthService service = service(true, false);
        when(invites.consume(eq(InviteCode.hash("GOOD-CODE")), any())).thenReturn(1);
        assertEquals("synthetic", service.register(request(null, "good-code"), "ip", "agent").getUsername());
        verify(invites, times(1)).consume(eq(InviteCode.hash("GOOD-CODE")), any());
        verify(users).save(any());
        verifyNoInteractions(emailCodes);
        assertTrue(service.registrationPolicy().inviteRequired());
        assertFalse(service.registrationPolicy().emailCodeRequired());
    }

    @Test void inviteCodesStayOutOfLoggedRequestText() {
        assertFalse(request("123456", "GOOD-CODE").toString().contains("GOOD-CODE"));
    }
}

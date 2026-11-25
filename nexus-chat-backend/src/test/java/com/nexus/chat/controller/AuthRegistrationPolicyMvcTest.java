package com.nexus.chat.controller;

import com.nexus.chat.dto.RegistrationPolicyResponse;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.EmailVerificationCode.CodeType;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.AuthService;
import com.nexus.chat.service.RegistrationPolicy;
import com.nexus.chat.service.VerificationCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The register form's policy endpoint and the gate in front of sending mail. */
class AuthRegistrationPolicyMvcTest {
    final AuthService auth = mock(AuthService.class);
    final VerificationCodeService emailCodes = mock(VerificationCodeService.class);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth, emailCodes, mock(JwtTokenProvider.class))).build();

    @Test void thePolicyEndpointReportsBothSwitches() throws Exception {
        when(auth.registrationPolicy()).thenReturn(new RegistrationPolicyResponse(false, true));
        mvc.perform(get("/api/auth/registration-policy")).andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteRequired").value(false)).andExpect(jsonPath("$.emailCodeRequired").value(true));
        when(auth.registrationPolicy()).thenReturn(new RegistrationPolicyResponse(true, false));
        mvc.perform(get("/api/auth/registration-policy")).andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteRequired").value(true)).andExpect(jsonPath("$.emailCodeRequired").value(false));
    }

    @Test void sendCodeStillSendsWhenThePolicyAllowsIt() throws Exception {
        mvc.perform(post("/api/auth/send-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"synthetic@example.test\",\"type\":\"REGISTER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(auth).requireEmailCodeAllowed(null);
        verify(emailCodes).sendVerificationCode("synthetic@example.test", CodeType.REGISTER);
    }

    @Test void noMailIsSentAndNoCodeIsCheckedWhenThePolicyRefuses() throws Exception {
        doThrow(new BusinessException(RegistrationPolicy.INVITE_REJECTED)).when(auth).requireEmailCodeAllowed("BAD-CODE");
        mvc.perform(post("/api/auth/send-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"synthetic@example.test\",\"type\":\"REGISTER\",\"inviteCode\":\"BAD-CODE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(RegistrationPolicy.INVITE_REJECTED));
        mvc.perform(post("/api/auth/verify-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"synthetic@example.test\",\"code\":\"123456\",\"type\":\"REGISTER\",\"inviteCode\":\"BAD-CODE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(RegistrationPolicy.INVITE_REJECTED));
        verifyNoInteractions(emailCodes);
    }
}

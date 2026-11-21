package com.nexus.chat.controller;

import com.nexus.chat.exception.GlobalExceptionHandler;
import com.nexus.chat.model.CompanionModelBinding;
import com.nexus.chat.model.CompanionRole;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.repository.CompanionModelBindingRepository;
import com.nexus.chat.repository.CompanionRoleRepository;
import com.nexus.chat.repository.ModelCredentialRepository;
import com.nexus.chat.service.CompanionConversationGuard;
import com.nexus.chat.service.CompanionCryptoService;
import com.nexus.chat.service.CompanionModelResolver;
import com.nexus.chat.service.CompanionModelService;
import com.nexus.chat.service.CompanionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class CompanionModelBindingMvcTest {
    private static final long USER = 7, ROLE = 3, CREDENTIAL = 41;
    private static final String URL = "/api/companion/model-";
    @Mock CompanionRoleRepository roles;
    @Mock CompanionModelBindingRepository bindings;
    @Mock ModelCredentialRepository credentials;
    @Mock CompanionModelService model;
    @Mock CompanionCryptoService crypto;
    @Mock com.nexus.chat.util.ModelEndpointGuard endpointGuard;
    @InjectMocks CompanionService service;
    private MockMvc mvc;

    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(USER, null, List.of()));
        ReflectionTestUtils.setField(service, "modelResolver", new CompanionModelResolver(bindings, credentials));
        mvc = MockMvcBuilders.standaloneSetup(new CompanionController(service, new CompanionConversationGuard()))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
    }

    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @ValueSource(strings = {"binding", "test", "status"})
    void foreignOrMissingRoleCannotBindInspectOrProbe(String operation) throws Exception {
        MockHttpServletRequestBuilder request = switch (operation) {
            case "binding" -> bind("{\"credentialId\":41}");
            case "test" -> post(URL + "test/" + ROLE);
            default -> get(URL + "status/" + ROLE);
        };
        mvc.perform(request).andExpect(status().isNotFound());
        verifyNoInteractions(bindings, credentials, model, crypto);
    }

    @Test void bindingRequiresAnOwnedChatCredential() throws Exception {
        ownedRole();
        mvc.perform(bind("{\"credentialId\":999}")).andExpect(status().is4xxClientError());
        var embedding = channel();
        embedding.setPurpose("embedding");
        when(credentials.findByIdAndUserId(CREDENTIAL, USER)).thenReturn(Optional.of(embedding));
        mvc.perform(bind("{\"credentialId\":41}")).andExpect(status().is4xxClientError());
        verify(bindings, never()).save(any());
        verifyNoInteractions(crypto, model);
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"endpoint\":\"https://other.invalid\",\"modelName\":\"other\"}",
            "{\"credentialId\":41,\"endpoint\":\"https://other.invalid\"}",
            "{\"credentialId\":41,\"apiKey\":\"not-a-key\"}"})
    void frontendCannotSupplyAChannelOrSecret(String body) throws Exception {
        mvc.perform(bind(body)).andExpect(status().isBadRequest());
        verify(bindings, never()).save(any());
        verifyNoInteractions(crypto, model);
    }

    @Test void saveAndReloadReturnRoleConfigurationWithoutPinningTheCredentialEndpoint() throws Exception {
        ownedRole();
        var chat = channel();
        when(credentials.findByIdAndUserId(CREDENTIAL, USER)).thenReturn(Optional.of(chat));
        when(credentials.findByUserIdAndProviderAndPurpose(USER, "custom", "chat")).thenReturn(Optional.of(chat));
        var saved = new AtomicReference<CompanionModelBinding>();
        when(bindings.findByUserIdAndRoleId(USER, ROLE)).thenAnswer(inv -> Optional.ofNullable(saved.get()));
        when(bindings.save(any())).thenAnswer(inv -> { saved.set(inv.getArgument(0)); return saved.get(); });
        mvc.perform(bind("""
                {"credentialId":41,"modelName":"   new-role-model   ","temperature":99,
                 "personaLock":-1,"contextHorizon":9999999}
                """)).andExpect(status().isOk());
        assertNull(saved.get().getEndpoint());
        assertEquals("custom", saved.get().getProvider());
        chat.setBaseUrl("https://updated.invalid/root");
        mvc.perform(get(URL + "status/" + ROLE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.usable").value(true)).andExpect(jsonPath("$.borrowed").value(false))
                .andExpect(jsonPath("$.credentialId").value(41)).andExpect(jsonPath("$.provider").value("custom"))
                .andExpect(jsonPath("$.endpoint").value("https://updated.invalid/root"))
                .andExpect(jsonPath("$.modelName").value("new-role-model"))
                .andExpect(jsonPath("$.explicitModelName").value("new-role-model"))
                .andExpect(jsonPath("$.defaultModel").value("saved-model"))
                .andExpect(jsonPath("$.temperature").value(1.5)).andExpect(jsonPath("$.personaLock").value(50))
                .andExpect(jsonPath("$.contextHorizon").value(128000)).andExpect(jsonPath("$.hasApiKey").value(true))
                .andExpect(content().string(not(containsString("test-ciphertext"))));
        mvc.perform(bind("{\"credentialId\":41,\"modelName\":\"  \"}")).andExpect(status().isOk());
        mvc.perform(get(URL + "status/" + ROLE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.modelName").value("saved-model"))
                .andExpect(jsonPath("$.explicitModelName").doesNotExist())
                .andExpect(jsonPath("$.borrowed").value(false)).andExpect(jsonPath("$.temperature").value(1.5));
        verifyNoInteractions(crypto, model);
    }

    @Test void aRoleWithNoBindingCanInspectButDoesNotExplicitlyOwnItsDefaultModel() throws Exception {
        ownedRole();
        when(credentials.findFirstByUserIdAndPurposeAndIsDefaultTrue(USER, "chat")).thenReturn(Optional.of(channel()));
        mvc.perform(get(URL + "status/" + ROLE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialId").value(41)).andExpect(jsonPath("$.borrowed").value(true))
                .andExpect(jsonPath("$.modelName").value("saved-model")).andExpect(jsonPath("$.explicitModelName").doesNotExist())
                .andExpect(content().string(not(containsString("test-ciphertext"))));
        verifyNoInteractions(crypto, model);
    }

    @Test void probeReportsInvalidBindingRatherThanTestingTheAccountDefault() throws Exception {
        ownedRole();
        var binding = new CompanionModelBinding();
        binding.setProvider("removed");
        binding.setUserId(USER);
        binding.setRoleId(ROLE);
        when(bindings.findByUserIdAndRoleId(USER, ROLE)).thenReturn(Optional.of(binding));
        mvc.perform(post(URL + "test/" + ROLE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.missing").value("no_credential")).andExpect(jsonPath("$.error.code").isString());
        verifyNoInteractions(crypto, model);
    }

    private MockHttpServletRequestBuilder bind(String json) {
        return put(URL + "binding/" + ROLE).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private void ownedRole() {
        var role = new CompanionRole();
        role.setId(ROLE);
        role.setUserId(USER);
        when(roles.findByIdAndUserId(ROLE, USER)).thenReturn(Optional.of(role));
    }

    private ModelCredential channel() {
        var credential = new ModelCredential();
        credential.setUserId(USER);
        credential.setId(CREDENTIAL);
        credential.setProvider("custom");
        credential.setBaseUrl("https://stored.invalid/root");
        credential.setDefaultModel("saved-model");
        credential.setApiKeyEncrypted("test-ciphertext");
        return credential;
    }
}

package com.nexus.chat.service;

import com.nexus.chat.model.CompanionModelBinding;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.repository.CompanionModelBindingRepository;
import com.nexus.chat.repository.ModelCredentialRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanionModelResolverTest {
    private static final long USER = 7, ROLE = 3;
    private final CompanionModelBindingRepository bindings = mock(CompanionModelBindingRepository.class);
    private final ModelCredentialRepository credentials = mock(ModelCredentialRepository.class);
    private final CompanionModelResolver resolver = new CompanionModelResolver(bindings, credentials);

    @Test void unboundRoleBorrowsTheWholeDefaultChatChannel() {
        var account = defaultChannel();
        var result = resolver.resolve(USER, ROLE);
        assertTrue(result.borrowed());
        assertTrue(result.usable());
        assertSame(account, result.credential());
        assertEquals("account", result.binding().getProvider());
        assertEquals("https://account.invalid/root", result.binding().getEndpoint());
        assertEquals("account-model", result.binding().getModelName());
        verify(credentials).findFirstByUserIdAndPurposeAndIsDefaultTrue(USER, "chat");
        verifyNoMoreInteractions(credentials);
    }

    @Test void missingBoundCredentialNeverBorrowsAnotherKeyOrEmbedding() {
        bind();
        defaultChannel();
        var embedding = channel("role-channel");
        embedding.setPurpose(ModelCredential.PURPOSE_EMBEDDING);
        when(credentials.findByUserIdAndProvider(USER, "role-channel")).thenReturn(Optional.of(embedding));
        var result = resolver.resolve(USER, ROLE);
        assertFalse(result.usable());
        assertFalse(result.borrowed());
        assertEquals("no_credential", result.missing());
        assertNull(result.credential());
        verify(credentials, never()).findByUserIdAndProvider(any(), any());
        verify(credentials, never()).findFirstByUserIdAndPurposeAndIsDefaultTrue(any(), any());
    }

    @Test void endpointlessBindingUsesItsOwnCredentialsCurrentBaseAndDefaultModel() {
        var binding = bind();
        binding.setModelName("  ");
        var selected = boundChannel();
        defaultChannel();
        var result = resolver.resolve(USER, ROLE);
        assertTrue(result.usable());
        assertFalse(result.borrowed());
        assertSame(selected, result.credential());
        assertEquals("https://role-channel.invalid/root", result.binding().getEndpoint());
        assertEquals("role-channel-model", result.binding().getModelName());
        assertNull(binding.getEndpoint(), "resolution must not write an endpoint back into the binding");
        selected.setBaseUrl("https://changed.invalid/root");
        assertEquals("https://changed.invalid/root", resolver.resolve(USER, ROLE).binding().getEndpoint());
    }

    @Test void mismatchedLegacyEndpointIsRejectedEvenWhenBothChannelsExist() {
        var binding = bind();
        binding.setEndpoint("https://old.invalid/root");
        boundChannel();
        defaultChannel();
        var result = resolver.resolve(USER, ROLE);
        assertFalse(result.usable());
        assertEquals("endpoint_mismatch", result.missing());
        assertFalse(result.borrowed());
    }

    @Test void matchingLegacyEndpointCanUseTheRoleModel() {
        var binding = bind();
        binding.setEndpoint("https://role-channel.invalid/root/");
        boundChannel();
        var result = resolver.resolve(USER, ROLE);
        assertTrue(result.usable());
        assertFalse(result.borrowed());
        assertEquals("override", result.binding().getModelName());
        assertEquals("https://role-channel.invalid/root", result.binding().getEndpoint());
    }

    @Test void anUnboundRoleDoesNotPickAnArbitraryNonDefaultCredential() {
        when(credentials.findFirstByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(USER, "chat"))
                .thenReturn(Optional.of(channel("other")));
        assertFalse(resolver.resolve(USER, ROLE).configured());
    }

    @Test void disabledBindingIsNotAnUnconfiguredRole() {
        bind().setEnabled(false);
        boundChannel();
        defaultChannel();
        var result = resolver.resolve(USER, ROLE);
        assertFalse(result.usable());
        assertEquals("binding_disabled", result.missing());
        assertFalse(result.borrowed());
    }

    @Test void missingModelsAndMissingStoredBaseCannotUseImplicitDefaults() {
        var binding = bind();
        binding.setModelName(null);
        var selected = boundChannel();
        selected.setDefaultModel(null);
        assertEquals("no_model", resolver.resolve(USER, ROLE).missing());
        binding.setModelName("override");
        selected.setBaseUrl(" ");
        assertEquals("no_endpoint", resolver.resolve(USER, ROLE).missing());
    }

    private CompanionModelBinding bind() {
        var binding = new CompanionModelBinding();
        binding.setUserId(USER);
        binding.setRoleId(ROLE);
        binding.setProvider("role-channel");
        binding.setModelName("override");
        when(bindings.findByUserIdAndRoleId(USER, ROLE)).thenReturn(Optional.of(binding));
        return binding;
    }

    private ModelCredential boundChannel() {
        var credential = channel("role-channel");
        when(credentials.findByUserIdAndProviderAndPurpose(USER, "role-channel", "chat"))
                .thenReturn(Optional.of(credential));
        return credential;
    }

    private ModelCredential defaultChannel() {
        var credential = channel("account");
        credential.setIsDefault(true);
        when(credentials.findFirstByUserIdAndPurposeAndIsDefaultTrue(USER, "chat"))
                .thenReturn(Optional.of(credential));
        return credential;
    }

    private ModelCredential channel(String provider) {
        var credential = new ModelCredential();
        credential.setId("account".equals(provider) ? 20L : 21L);
        credential.setUserId(USER);
        credential.setProvider(provider);
        credential.setBaseUrl("https://" + provider + ".invalid/root");
        credential.setDefaultModel(provider + "-model");
        credential.setApiKeyEncrypted("test-ciphertext");
        return credential;
    }
}

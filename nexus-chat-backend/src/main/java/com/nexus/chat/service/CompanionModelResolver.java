package com.nexus.chat.service;

import com.nexus.chat.model.CompanionModelBinding;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.repository.CompanionModelBindingRepository;
import com.nexus.chat.repository.ModelCredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Which model a character talks through.
 *
 * A role binding is the specific answer: the user picked a model for THIS
 * character. A fresh install has no binding at all, so when there is none the
 * account's default chat provider is borrowed — same key, one place to configure.
 *
 * This lives on its own because every model call in the tavern has to agree on
 * it. Closing an evening and inviting a guest used to look bindings up by
 * themselves, missed the borrowing rule, and so never reached a model for anyone
 * who had not bound one per character.
 */
@Component
@RequiredArgsConstructor
public class CompanionModelResolver {

    private final CompanionModelBindingRepository bindingRepository;
    private final ModelCredentialRepository credentialRepository;

    /** The binding to use and the credential to authenticate with. */
    public record Resolved(CompanionModelBinding binding, ModelCredential credential, boolean borrowed) {
        public boolean usable() {
            return binding != null && credential != null
                    && credential.getApiKeyEncrypted() != null
                    && binding.getEndpoint() != null && !binding.getEndpoint().isBlank();
        }

        /** The first missing piece, named, so the UI can say what to fix. */
        public String missing() {
            if (binding == null) return "no_binding";
            if (binding.getEndpoint() == null || binding.getEndpoint().isBlank()) return "no_endpoint";
            if (credential == null) return "no_credential";
            if (credential.getApiKeyEncrypted() == null) return "no_api_key";
            return null;
        }
    }

    /** The channel for one character. */
    public Resolved resolve(Long userId, Long roleId) {
        CompanionModelBinding binding = roleId == null
                ? null
                : bindingRepository.findByUserIdAndRoleId(userId, roleId).orElse(null);
        boolean stored = binding != null;

        ModelCredential credential = null;
        if (binding != null) {
            final String provider = binding.getProvider();
            credential = credentialRepository
                    .findByUserIdAndProviderAndPurpose(userId, provider, ModelCredential.PURPOSE_CHAT)
                    .or(() -> credentialRepository.findByUserIdAndProvider(userId, provider))
                    .orElse(null);
        }

        if (credential != null && binding.getEndpoint() != null && !binding.getEndpoint().isBlank()) {
            return new Resolved(binding, credential, false);
        }

        // No usable binding: borrow the default chat provider (and, if the
        // binding had no endpoint of its own, its base URL too).
        Optional<ModelCredential> fallback = credentialRepository
                .findFirstByUserIdAndPurposeAndIsDefaultTrue(userId, ModelCredential.PURPOSE_CHAT);
        if (fallback.isEmpty()) {
            fallback = credentialRepository
                    .findFirstByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(userId, ModelCredential.PURPOSE_CHAT);
        }
        if (fallback.isEmpty()) {
            return new Resolved(binding, null, !stored);
        }

        ModelCredential borrowed = fallback.get();
        CompanionModelBinding effective = new CompanionModelBinding();
        effective.setUserId(userId);
        effective.setRoleId(roleId);
        effective.setProvider(binding != null && binding.getProvider() != null
                ? binding.getProvider() : borrowed.getProvider());
        effective.setModelName(binding != null && binding.getModelName() != null && !binding.getModelName().isBlank()
                ? binding.getModelName() : borrowed.getDefaultModel());
        effective.setEndpoint(binding != null && binding.getEndpoint() != null && !binding.getEndpoint().isBlank()
                ? binding.getEndpoint() : borrowed.getBaseUrl());
        effective.setEnabled(true);
        if (binding != null) {
            // The sampling knobs belong to the character even when the key is borrowed.
            effective.setTemperature(binding.getTemperature());
            effective.setPersonaLock(binding.getPersonaLock());
            effective.setContextHorizon(binding.getContextHorizon());
        }
        return new Resolved(effective, borrowed, !stored);
    }

    /**
     * The channel for something that has no character yet (inviting a guest):
     * the account's default chat provider, or failing that any character's
     * binding that works.
     */
    public Resolved resolveForUser(Long userId) {
        Resolved byDefault = resolve(userId, null);
        if (byDefault.usable()) return byDefault;
        return bindingRepository.findFirstByUserIdAndEnabledTrue(userId)
                .map(binding -> resolve(userId, binding.getRoleId()))
                .filter(Resolved::usable)
                .orElse(byDefault);
    }
}

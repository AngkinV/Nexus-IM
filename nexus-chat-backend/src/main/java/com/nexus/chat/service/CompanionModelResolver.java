package com.nexus.chat.service;

import com.nexus.chat.model.CompanionModelBinding;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.repository.CompanionModelBindingRepository;
import com.nexus.chat.repository.ModelCredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** A role either selects a single chat channel or inherits the account's whole chat channel. */
@Component
@RequiredArgsConstructor
public class CompanionModelResolver {
    private final CompanionModelBindingRepository bindingRepository;
    private final ModelCredentialRepository credentialRepository;

    /** The effective settings are a detached view, never the managed binding row. */
    public record Resolved(CompanionModelBinding binding, ModelCredential credential, boolean borrowed,
                           String explicitModelName, String missing) {
        public Resolved(CompanionModelBinding binding, ModelCredential credential, boolean borrowed) {
            this(binding, credential, borrowed, borrowed || binding == null ? null : trimmed(binding.getModelName()),
                    missingEffective(binding, credential));
        }

        public boolean usable() { return missing == null; }
        public boolean configured() { return binding != null || credential != null; }
    }

    public Resolved resolve(Long userId, Long roleId) {
        CompanionModelBinding stored = roleId == null ? null : bindingRepository
                .findByUserIdAndRoleId(userId, roleId).orElse(null);
        ModelCredential credential;
        if (stored != null) {
            // (user, provider, purpose) is the credential's unique channel key. Never fall
            // through to an embedding row or an unrelated default if the selection is stale.
            credential = credentialRepository.findByUserIdAndProviderAndPurpose(
                    userId, stored.getProvider(), ModelCredential.PURPOSE_CHAT).orElse(null);
        } else {
            credential = credentialRepository.findFirstByUserIdAndPurposeAndIsDefaultTrue(userId, ModelCredential.PURPOSE_CHAT).orElse(null);
        }
        if (stored == null && credential == null) return new Resolved(null, null, false);

        var effective = new CompanionModelBinding();
        effective.setUserId(userId);
        effective.setRoleId(roleId);
        effective.setProvider(stored != null ? stored.getProvider() : credential.getProvider());
        String explicitModel = stored == null ? null : trimmed(stored.getModelName());
        effective.setModelName(explicitModel != null ? explicitModel
                : credential == null ? null : trimmed(credential.getDefaultModel()));
        effective.setEndpoint(credential == null ? null : trimmed(credential.getBaseUrl()));
        if (stored != null) {
            effective.setId(stored.getId());
            effective.setEnabled(stored.getEnabled());
            effective.setTemperature(stored.getTemperature());
            effective.setPersonaLock(stored.getPersonaLock());
            effective.setContextHorizon(stored.getContextHorizon());
            effective.setReasoningBudget(stored.getReasoningBudget());
            effective.setStreamEnabled(stored.getStreamEnabled());
        }
        String missing = missingEffective(effective, credential);
        if (missing == null && stored != null && !matchesEndpoint(stored.getEndpoint(), credential.getBaseUrl())) {
            missing = "endpoint_mismatch";
        }
        return new Resolved(effective, credential, stored == null, explicitModel, missing);
    }

    /** No character yet (inviting a guest): use the account channel, or an existing role if none exists. */
    public Resolved resolveForUser(Long userId) {
        Resolved account = resolve(userId, null);
        if (account.configured()) return account;
        return bindingRepository.findFirstByUserIdAndEnabledTrue(userId)
                .map(binding -> resolve(userId, binding.getRoleId())).filter(Resolved::usable).orElse(account);
    }

    /** Validate a resolved view. Only the resolver supplies the endpoint; model calls also check the stored base. */
    private static String missingEffective(CompanionModelBinding binding, ModelCredential credential) {
        if (binding == null) return "no_binding";
        if (!Boolean.TRUE.equals(binding.getEnabled())) return "binding_disabled";
        if (trimmed(binding.getProvider()) == null) return "no_provider";
        if (credential == null || !Objects.equals(binding.getUserId(), credential.getUserId())
                || !ModelCredential.PURPOSE_CHAT.equals(credential.getPurpose())
                || !Objects.equals(binding.getProvider(), credential.getProvider())) return "no_credential";
        if (trimmed(binding.getEndpoint()) == null) return "no_endpoint";
        if (trimmed(binding.getModelName()) == null) return "no_model";
        if (trimmed(credential.getApiKeyEncrypted()) == null && !"ollama".equalsIgnoreCase(credential.getProvider())) return "no_api_key";
        return null;
    }

    static boolean matchesEndpoint(String pinned, String current) {
        // A null endpoint follows the selected credential; a legacy endpoint is only a constraint.
        if (pinned == null) return true;
        if (trimmed(pinned) == null || trimmed(current) == null) return false;
        return pinned.trim().replaceFirst("/+$", "").equals(current.trim().replaceFirst("/+$", ""));
    }

    static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.chat.dto.agent.AgentProviderDtos;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.repository.ModelCredentialRepository;
import com.nexus.chat.service.CompanionCryptoService;
import com.nexus.chat.util.ModelEndpointGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AgentProviderService {
    private final ModelCredentialRepository repository;
    private final CompanionCryptoService crypto;
    private final ObjectMapper objectMapper;
    private final ModelEndpointGuard endpointGuard;
    private final PythonModelGateway gateway;

    public List<AgentProviderDtos.ProviderView> listForUser(Long userId) {
        return repository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId).stream().map(this::toView).toList();
    }

    public List<AgentProviderDtos.ProviderView> listForUserByPurpose(Long userId, String purpose) {
        return repository.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(userId, normalizePurpose(purpose))
                .stream().map(this::toView).toList();
    }

    @Transactional
    public AgentProviderDtos.ProviderView upsert(Long userId, AgentProviderDtos.UpsertProviderRequest req) {
        String base = emptyToNull(req.getBaseUrl());
        String model = emptyToNull(req.getDefaultModel());
        String key = emptyToNull(req.getApiKey());
        try { endpointGuard.validate(base); }
        catch (IllegalArgumentException e) { throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, e.getMessage()); }
        String purpose = normalizePurpose(req.getPurpose());
        String provider = req.getProvider().trim();
        ModelCredential entity = repository.findByUserIdAndProviderAndPurpose(userId, provider, purpose).orElseGet(ModelCredential::new);
        if (key != null || !Objects.equals(base, entity.getBaseUrl()) || !Objects.equals(model, entity.getDefaultModel())) {
            entity.setStatus(ModelCredential.CredentialStatus.unknown);
        }
        entity.setUserId(userId);
        entity.setProvider(provider);
        entity.setPurpose(purpose);
        entity.setDisplayName(emptyToNull(req.getDisplayName()));
        entity.setBaseUrl(base);
        entity.setDefaultModel(model);
        if (key != null) entity.setApiKeyEncrypted(crypto.encrypt(key));
        if (req.isMakeDefault()) {
            repository.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(userId, purpose).forEach(m -> {
                if (Boolean.TRUE.equals(m.getIsDefault()) && !Objects.equals(m.getProvider(), provider)) {
                    m.setIsDefault(false);
                    repository.save(m);
                }
            });
            entity.setIsDefault(true);
        } else if (entity.getId() == null) {
            entity.setIsDefault(repository.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(userId, purpose).isEmpty());
        }
        return toView(repository.saveAndFlush(entity));
    }

    @Transactional
    public void delete(Long userId, Long providerId) {
        ModelCredential entity = owned(userId, providerId);
        boolean wasDefault = Boolean.TRUE.equals(entity.getIsDefault());
        String purpose = normalizePurpose(entity.getPurpose());
        repository.delete(entity);
        if (wasDefault) {
            repository.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(userId, purpose).stream().findFirst()
                    .ifPresent(next -> { next.setIsDefault(true); repository.save(next); });
        }
    }

    @Transactional
    public AgentProviderDtos.ProviderView setDefault(Long userId, Long providerId) {
        ModelCredential target = owned(userId, providerId);
        repository.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(userId, normalizePurpose(target.getPurpose())).forEach(m -> {
            boolean selected = Objects.equals(m.getId(), providerId);
            if (Boolean.TRUE.equals(m.getIsDefault()) != selected) {
                m.setIsDefault(selected);
                repository.save(m);
            }
        });
        return toView(target);
    }

    public Optional<ResolvedProvider> resolveForRequest(Long userId, Long providerId) {
        Optional<ModelCredential> selected = providerId == null
                ? repository.findFirstByUserIdAndPurposeAndIsDefaultTrue(userId, ModelCredential.PURPOSE_CHAT)
                : Optional.of(owned(userId, providerId));
        return selected.map(c -> {
            if (!ModelCredential.PURPOSE_CHAT.equals(normalizePurpose(c.getPurpose()))) throw invalidProvider();
            if (emptyToNull(c.getDefaultModel()) == null || (emptyToNull(c.getApiKeyEncrypted()) == null && !"ollama".equalsIgnoreCase(c.getProvider()))) {
                throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Selected channel has no configured chat model or credential");
            }
            return toResolved(c);
        });
    }

    public Optional<ResolvedProvider> resolveEmbeddingCredential(Long userId, Long credentialId) {
        Optional<ModelCredential> selected = credentialId == null
                ? repository.findFirstByUserIdAndPurposeAndIsDefaultTrue(userId, ModelCredential.PURPOSE_EMBEDDING)
                : Optional.of(owned(userId, credentialId));
        return selected.map(c -> {
            if (!ModelCredential.PURPOSE_EMBEDDING.equals(c.getPurpose())) throw invalidProvider();
            return toResolved(c);
        });
    }

    public record ResolvedProvider(Long id, String provider, String displayName, String baseUrl, String defaultModel, String apiKeyPlain) {
        @Override public String toString() { return "ResolvedProvider[id=" + id + ", provider=" + provider + "]"; }
    }

    public ObjectNode catalog(Long userId, Long providerId) {
        ModelCredential channel = owned(userId, providerId);
        String purpose = normalizePurpose(channel.getPurpose());
        JsonNode response = gateway.catalog(userId, toResolved(channel), purpose);
        ObjectNode result = response.deepCopy();
        result.put("providerId", providerId).put("purpose", purpose).put("revision", revision(channel));
        return result;
    }

    public AgentProviderDtos.ProviderTestResult test(Long userId, Long providerId) {
        ModelCredential channel = owned(userId, providerId);
        String purpose = normalizePurpose(channel.getPurpose());
        JsonNode response = gateway.test(userId, toResolved(channel), purpose,
                List.of(Map.of("role", "user", "content", "Reply with one short greeting.")), .2, 1024, 120);
        var result = new AgentProviderDtos.ProviderTestResult();
        result.setOk(response.path("ok").asBoolean(false));
        result.setMessage(response.path("message").asText(result.isOk() ? "ok" : "Model test failed"));
        result.setModel(channel.getDefaultModel());
        result.setError(response.get("error"));
        if (response.path("latencyMs").isNumber()) result.setLatencyMs(response.get("latencyMs").longValue());
        if (response.path("embeddingDimension").isNumber()) result.setEmbeddingDimension(response.get("embeddingDimension").intValue());
        var status = result.isOk() ? ModelCredential.CredentialStatus.ok :
                "AUTH".equals(response.at("/error/code").asText()) ? ModelCredential.CredentialStatus.invalid : null;
        if (status != null && repository.setTestStatus(providerId, userId, channel.getApiKeyEncrypted(), channel.getBaseUrl(),
                channel.getDefaultModel(), channel.getUpdatedAt(), status) == 0) {
            result.setOk(false);
            result.setMessage("Settings changed during the test; test the saved configuration again");
            result.setError(objectMapper.createObjectNode().put("code", "CONFIG_CHANGED").put("summary", result.getMessage()));
        }
        return result;
    }

    private ModelCredential owned(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(AgentProviderService::invalidProvider);
    }

    private static AgentException invalidProvider() {
        return new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Provider unavailable for this user or purpose");
    }

    private ResolvedProvider toResolved(ModelCredential m) {
        String key = m.getApiKeyEncrypted() == null ? null : crypto.decrypt(m.getApiKeyEncrypted());
        return new ResolvedProvider(m.getId(), m.getProvider(), m.getDisplayName(), m.getBaseUrl(), m.getDefaultModel(), key);
    }

    private static String revision(ModelCredential m) {
        String config = String.join("\u0000", Objects.toString(m.getId(), ""), m.getProvider(), normalizePurpose(m.getPurpose()),
                Objects.toString(m.getBaseUrl(), ""), Objects.toString(m.getDefaultModel(), ""), Objects.toString(m.getApiKeyEncrypted(), ""));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(config.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private AgentProviderDtos.ProviderView toView(ModelCredential m) {
        var view = new AgentProviderDtos.ProviderView();
        view.setId(m.getId()); view.setProvider(m.getProvider()); view.setPurpose(normalizePurpose(m.getPurpose()));
        view.setDisplayName(m.getDisplayName() == null ? m.getProvider() : m.getDisplayName());
        view.setBaseUrl(m.getBaseUrl()); view.setDefaultModel(m.getDefaultModel());
        view.setHasApiKey(emptyToNull(m.getApiKeyEncrypted()) != null);
        if (view.isHasApiKey()) {
            String plain = crypto.decrypt(m.getApiKeyEncrypted());
            view.setApiKeyMask(plain != null && plain.length() > 8 ? "***" + plain.substring(plain.length() - 4) : "***");
        }
        view.setDefault(Boolean.TRUE.equals(m.getIsDefault()));
        view.setStatus(m.getStatus() == null ? "unknown" : m.getStatus().name());
        view.setRevision(revision(m)); view.setCreatedAt(m.getCreatedAt()); view.setUpdatedAt(m.getUpdatedAt());
        return view;
    }

    private static String normalizePurpose(String raw) {
        if (raw == null || raw.isBlank()) return ModelCredential.PURPOSE_CHAT;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals(ModelCredential.PURPOSE_CHAT) || value.equals(ModelCredential.PURPOSE_EMBEDDING)) return value;
        throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Unsupported credential purpose");
    }

    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}

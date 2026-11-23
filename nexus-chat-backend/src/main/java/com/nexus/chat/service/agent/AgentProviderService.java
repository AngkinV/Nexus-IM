package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.chat.dto.agent.AgentProviderDtos;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.ModelChannels;
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

    /** Enough for every vendor in the catalogue plus private relays; a bound, not a product limit. */
    static final int MAX_CHANNELS_PER_PURPOSE = 100;

    @Transactional
    public AgentProviderDtos.ProviderView upsert(Long userId, AgentProviderDtos.UpsertProviderRequest req) {
        String base = emptyToNull(req.getBaseUrl());
        String model = emptyToNull(req.getDefaultModel());
        String key = emptyToNull(req.getApiKey());
        try { endpointGuard.validate(base); }
        catch (IllegalArgumentException e) { throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, e.getMessage()); }
        String purpose = normalizePurpose(req.getPurpose());
        String vendor = emptyToNull(req.getVendor());
        if (vendor != null) vendor = vendor.toLowerCase(Locale.ROOT);
        String format;
        try { format = ModelChannels.normalizeFormat(req.getApiFormat()); }
        catch (IllegalArgumentException e) { throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, e.getMessage()); }

        ModelCredential entity = channelToSave(userId, req, purpose, vendor, format);
        String formatBefore = entity.getId() == null ? null : ModelChannels.apiFormat(entity);
        if (vendor != null) entity.setVendor(vendor);
        if (format != null) entity.setApiFormat(format);
        if (key != null || !Objects.equals(base, entity.getBaseUrl()) || !Objects.equals(model, entity.getDefaultModel())
                || (formatBefore != null && !formatBefore.equals(ModelChannels.apiFormat(entity)))) {
            entity.setStatus(ModelCredential.CredentialStatus.unknown);
        }
        entity.setUserId(userId);
        entity.setPurpose(purpose);
        entity.setDisplayName(emptyToNull(req.getDisplayName()));
        entity.setBaseUrl(base);
        entity.setDefaultModel(model);
        if (key != null) entity.setApiKeyEncrypted(crypto.encrypt(key));
        List<ModelCredential> siblings = repository.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(userId, purpose);
        if (req.isMakeDefault()) {
            siblings.forEach(m -> {
                if (Boolean.TRUE.equals(m.getIsDefault()) && !Objects.equals(m.getId(), entity.getId())) {
                    m.setIsDefault(false);
                    repository.save(m);
                }
            });
            entity.setIsDefault(true);
        } else if (entity.getId() == null) {
            entity.setIsDefault(siblings.isEmpty());
        }
        return toView(repository.saveAndFlush(entity));
    }

    /**
     * The row an upsert writes to. A request that names a saved channel changes that
     * channel. One that does not adds a channel under an id nobody else holds. Only a
     * request from a client that predates vendors (no id, no vendor, no protocol) still
     * addresses a channel by its provider id, as that client always has.
     */
    private ModelCredential channelToSave(Long userId, AgentProviderDtos.UpsertProviderRequest req,
                                          String purpose, String vendor, String format) {
        if (req.getId() != null) {
            ModelCredential saved = owned(userId, req.getId());
            if (!purpose.equals(normalizePurpose(saved.getPurpose()))) throw invalidProvider();
            return saved;
        }
        String requested = emptyToNull(req.getProvider());
        if (requested != null && vendor == null && format == null) {
            Optional<ModelCredential> legacy = repository.findByUserIdAndProviderAndPurpose(userId, requested, purpose);
            if (legacy.isPresent()) return legacy.get();
        }
        if (repository.countByUserIdAndPurpose(userId, purpose) >= MAX_CHANNELS_PER_PURPOSE) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001,
                    "This account already has " + MAX_CHANNELS_PER_PURPOSE + " saved channels for this purpose");
        }
        ModelCredential created = new ModelCredential();
        created.setProvider(freeChannelId(userId, purpose,
                requested != null ? requested : vendor != null ? vendor : ModelChannels.CUSTOM_VENDOR));
        return created;
    }

    /** {@code wanted} if nobody holds it, otherwise {@code wanted-2}, {@code wanted-3}, ... */
    private String freeChannelId(Long userId, String purpose, String wanted) {
        String stem = wanted.length() > 44 ? wanted.substring(0, 44) : wanted;
        String candidate = stem;
        for (int n = 2; repository.existsByUserIdAndProviderAndPurpose(userId, candidate, purpose); n++) {
            if (n > MAX_CHANNELS_PER_PURPOSE + 1) throw invalidProvider();
            candidate = stem + "-" + n;
        }
        return candidate;
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
            if (emptyToNull(c.getDefaultModel()) == null || (emptyToNull(c.getApiKeyEncrypted()) == null && !ModelChannels.keyOptional(c))) {
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

    /**
     * A channel ready to be called. {@code provider} is the adapter name the Python
     * model service keys on ({@link ModelChannels#adapterName}), not the channel's id.
     */
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
        return new ResolvedProvider(m.getId(), ModelChannels.adapterName(m, key != null && !key.isBlank()),
                m.getDisplayName(), m.getBaseUrl(), m.getDefaultModel(), key);
    }

    private static String revision(ModelCredential m) {
        String config = String.join("\u0000", Objects.toString(m.getId(), ""), m.getProvider(), normalizePurpose(m.getPurpose()),
                Objects.toString(m.getBaseUrl(), ""), Objects.toString(m.getDefaultModel(), ""), Objects.toString(m.getApiKeyEncrypted(), ""),
                ModelChannels.apiFormat(m), Objects.toString(ModelChannels.vendor(m), ""));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(config.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private AgentProviderDtos.ProviderView toView(ModelCredential m) {
        var view = new AgentProviderDtos.ProviderView();
        view.setId(m.getId()); view.setProvider(m.getProvider()); view.setPurpose(normalizePurpose(m.getPurpose()));
        view.setVendor(ModelChannels.vendor(m)); view.setApiFormat(ModelChannels.apiFormat(m));
        view.setKeyOptional(ModelChannels.keyOptional(m));
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

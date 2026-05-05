package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.dto.agent.AgentProviderDtos;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.ModelCredential;
import com.nexus.chat.repository.ModelCredentialRepository;
import com.nexus.chat.service.CompanionCryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * CRUD + lookup for the per-user model providers backing the Agent.
 *
 * Reuses {@link CompanionCryptoService} for AES-GCM encryption of the API key, since both
 * features share the same encryption boundary and the same secret config
 * ({@code companion.api-key.secret}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentProviderService {

    private final ModelCredentialRepository repository;
    private final CompanionCryptoService crypto;
    private final ObjectMapper objectMapper;

    public List<AgentProviderDtos.ProviderView> listForUser(Long userId) {
        return repository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId).stream()
                .map(this::toView)
                .toList();
    }

    public List<AgentProviderDtos.ProviderView> listForUserByPurpose(Long userId, String purpose) {
        String safe = normalizePurpose(purpose);
        return repository.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(userId, safe).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public AgentProviderDtos.ProviderView upsert(Long userId, AgentProviderDtos.UpsertProviderRequest req) {
        validateBaseUrl(req.getBaseUrl());
        String purpose = normalizePurpose(req.getPurpose());

        ModelCredential entity = repository.findByUserIdAndProviderAndPurpose(userId, req.getProvider(), purpose)
                .orElseGet(() -> {
                    ModelCredential m = new ModelCredential();
                    m.setUserId(userId);
                    m.setProvider(req.getProvider());
                    m.setPurpose(purpose);
                    return m;
                });
        entity.setPurpose(purpose); // covers the path where the row pre-existed without purpose (legacy chat row)

        entity.setDisplayName(emptyToNull(req.getDisplayName()));
        entity.setBaseUrl(emptyToNull(req.getBaseUrl()));
        entity.setDefaultModel(emptyToNull(req.getDefaultModel()));

        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            entity.setApiKeyEncrypted(crypto.encrypt(req.getApiKey().trim()));
            entity.setStatus(ModelCredential.CredentialStatus.unknown); // re-tested explicitly
        }

        if (req.isMakeDefault()) {
            // Clear other defaults for this user *within the same purpose*; chat default and
            // embedding default are independent so a user can have one of each simultaneously.
            repository.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(userId, purpose).forEach(m -> {
                if (Boolean.TRUE.equals(m.getIsDefault()) && !Objects.equals(m.getProvider(), req.getProvider())) {
                    m.setIsDefault(false);
                    repository.save(m);
                }
            });
            entity.setIsDefault(true);
        } else if (entity.getId() == null) {
            // First credential for this purpose becomes default automatically.
            boolean firstOne = repository.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(userId, purpose).isEmpty();
            entity.setIsDefault(firstOne);
        }

        return toView(repository.save(entity));
    }

    @Transactional
    public void delete(Long userId, Long providerId) {
        ModelCredential entity = repository.findByIdAndUserId(providerId, userId)
                .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_PARAM_40001, "provider not found"));
        boolean wasDefault = Boolean.TRUE.equals(entity.getIsDefault());
        String purpose = normalizePurpose(entity.getPurpose());
        repository.delete(entity);
        if (wasDefault) {
            // Promote the most recently updated *same-purpose* credential to the new default,
            // so deleting the chat default doesn't accidentally promote an embedding row.
            repository.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(userId, purpose).stream()
                    .findFirst()
                    .ifPresent(next -> {
                        next.setIsDefault(true);
                        repository.save(next);
                    });
        }
    }

    @Transactional
    public AgentProviderDtos.ProviderView setDefault(Long userId, Long providerId) {
        ModelCredential target = repository.findByIdAndUserId(providerId, userId)
                .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_PARAM_40001, "provider not found"));
        String purpose = normalizePurpose(target.getPurpose());
        repository.findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(userId, purpose).forEach(m -> {
            boolean shouldBeDefault = Objects.equals(m.getId(), providerId);
            if (Boolean.TRUE.equals(m.getIsDefault()) != shouldBeDefault) {
                m.setIsDefault(shouldBeDefault);
                repository.save(m);
            }
        });
        return toView(repository.findById(target.getId()).orElseThrow());
    }

    /**
     * Look up the chat provider that should serve a given Agent invocation.
     * If {@code providerId} is provided and belongs to the user, that one wins; otherwise the
     * user's default chat provider is used. Embedding credentials are intentionally NOT
     * candidates here — use {@link #resolveEmbeddingForKb} for that path.
     * Returns {@link Optional#empty()} if the user has no chat provider configured.
     */
    public Optional<ResolvedProvider> resolveForRequest(Long userId, Long providerId) {
        Optional<ModelCredential> chosen;
        if (providerId != null) {
            chosen = repository.findByIdAndUserId(providerId, userId)
                    .filter(m -> isChatPurpose(m.getPurpose()));
        } else {
            chosen = repository.findFirstByUserIdAndPurposeAndIsDefaultTrue(userId, ModelCredential.PURPOSE_CHAT);
            if (chosen.isEmpty()) {
                // Backward-compat: legacy rows pre-migration may not have purpose set;
                // fall back to the old "any default" behaviour but only when it's chat-shaped.
                chosen = repository.findFirstByUserIdAndIsDefaultTrue(userId)
                        .filter(m -> isChatPurpose(m.getPurpose()));
            }
        }
        return chosen.map(this::toResolved);
    }

    /**
     * Resolve the embedding credential the caller wants to use. Lookup priority:
     *   1. {@code credentialId} (when provided and owned by the user, and its purpose=embedding)
     *   2. user's default credential for purpose=embedding
     *   3. empty — caller is expected to fall back to the Python service-side env defaults
     */
    public Optional<ResolvedProvider> resolveEmbeddingCredential(Long userId, Long credentialId) {
        Optional<ModelCredential> chosen;
        if (credentialId != null) {
            chosen = repository.findByIdAndUserId(credentialId, userId)
                    .filter(m -> ModelCredential.PURPOSE_EMBEDDING.equalsIgnoreCase(m.getPurpose()));
        } else {
            chosen = repository.findFirstByUserIdAndPurposeAndIsDefaultTrue(
                    userId, ModelCredential.PURPOSE_EMBEDDING);
        }
        return chosen.map(this::toResolved);
    }

    /** Decrypted credential ready to be forwarded to Python over the internal channel. */
    public record ResolvedProvider(
            Long id,
            String provider,
            String displayName,
            String baseUrl,
            String defaultModel,
            String apiKeyPlain
    ) {}

    /**
     * Probe the provider with a tiny request to verify the {@code api_key + base_url + model}
     * triple actually works, dispatching by purpose:
     *   - {@code chat} → POST /chat/completions with a single "ping" message
     *   - {@code embedding} → POST /embeddings with a single short input, also captures
     *     the returned vector dimension so the UI can warn before users mix dimensions.
     * Persists the resulting status (ok / invalid). Lightweight, non-streaming.
     */
    @Transactional
    public AgentProviderDtos.ProviderTestResult test(Long userId, Long providerId) {
        ModelCredential entity = repository.findByIdAndUserId(providerId, userId)
                .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_PARAM_40001, "provider not found"));

        if (ModelCredential.PURPOSE_EMBEDDING.equalsIgnoreCase(entity.getPurpose())) {
            return runEmbeddingProbe(entity);
        }
        return runChatProbe(entity);
    }

    @Transactional
    protected AgentProviderDtos.ProviderTestResult runChatProbe(ModelCredential entity) {
        AgentProviderDtos.ProviderTestResult result = new AgentProviderDtos.ProviderTestResult();
        if (entity.getApiKeyEncrypted() == null || entity.getBaseUrl() == null || entity.getBaseUrl().isBlank()) {
            result.setOk(false);
            result.setMessage("missing api_key / base_url");
            entity.setStatus(ModelCredential.CredentialStatus.invalid);
            repository.save(entity);
            return result;
        }

        long started = System.currentTimeMillis();
        try {
            String apiKey = crypto.decrypt(entity.getApiKeyEncrypted());
            String base = stripTrailingSlash(entity.getBaseUrl());
            List<String> availableModels = fetchAvailableModels(base, apiKey);
            if (!availableModels.isEmpty()) {
                result.setAvailableModels(availableModels);
            }
            String configuredModel = emptyToNull(entity.getDefaultModel());
            String probeModel = chooseProbeModel(entity.getDefaultModel(), availableModels);
            if (probeModel == null || probeModel.isBlank()) {
                if (!availableModels.isEmpty()) {
                    result.setOk(true);
                    result.setLatencyMs(System.currentTimeMillis() - started);
                    result.setModel(availableModels.get(0));
                    result.setMessage("ok (models listed)");
                    entity.setDefaultModel(availableModels.get(0));
                    entity.setStatus(ModelCredential.CredentialStatus.ok);
                } else {
                    result.setOk(false);
                    result.setMessage("missing default_model and provider did not return /models");
                    entity.setStatus(ModelCredential.CredentialStatus.invalid);
                }
                repository.save(entity);
                return result;
            }
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", probeModel,
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 8,
                    "temperature", 0.0
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/chat/completions"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = buildHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = System.currentTimeMillis() - started;

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                result.setOk(true);
                result.setLatencyMs(elapsed);
                result.setModel(probeModel);
                if (!Objects.equals(configuredModel, probeModel)) {
                    entity.setDefaultModel(probeModel);
                    result.setMessage("ok (default model adjusted)");
                } else {
                    result.setMessage("ok");
                }
                entity.setStatus(ModelCredential.CredentialStatus.ok);
            } else {
                result.setOk(false);
                result.setLatencyMs(elapsed);
                result.setMessage("HTTP " + resp.statusCode() + " " + safeSnippet(resp.body()));
                entity.setStatus(ModelCredential.CredentialStatus.invalid);
            }
        } catch (Exception e) {
            result.setOk(false);
            result.setMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            entity.setStatus(ModelCredential.CredentialStatus.invalid);
        }
        repository.save(entity);
        return result;
    }

    /**
     * Embedding-specific probe: POST /embeddings with a tiny input, parse the returned
     * vector to surface its dimension. Crucial for the UX so the user discovers a
     * "model_not_found" / "no available channel for ..." error at credential-save time
     * rather than mid-ingestion.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    protected AgentProviderDtos.ProviderTestResult runEmbeddingProbe(ModelCredential entity) {
        AgentProviderDtos.ProviderTestResult result = new AgentProviderDtos.ProviderTestResult();
        if (entity.getApiKeyEncrypted() == null || entity.getBaseUrl() == null || entity.getBaseUrl().isBlank()) {
            result.setOk(false);
            result.setMessage("missing api_key / base_url");
            entity.setStatus(ModelCredential.CredentialStatus.invalid);
            repository.save(entity);
            return result;
        }

        long started = System.currentTimeMillis();
        try {
            String apiKey = crypto.decrypt(entity.getApiKeyEncrypted());
            String base = stripTrailingSlash(entity.getBaseUrl());

            // /models is unreliable for embedding-only providers, but we still try —
            // it lets the UI populate the "model" autocomplete with whatever the
            // backend exposes (which is what /api/agent/providers/{id}/test returns).
            List<String> availableModels = fetchAvailableModels(base, apiKey);
            if (!availableModels.isEmpty()) {
                result.setAvailableModels(availableModels);
            }

            String probeModel = emptyToNull(entity.getDefaultModel());
            if (probeModel == null) {
                // Embedding endpoints REQUIRE a model field — there is no listed-model fallback
                // analog to chat's "first model from /models" because embedding /models lists
                // typically include irrelevant chat models too.
                result.setOk(false);
                result.setMessage("missing default_model: embedding probe needs a concrete model");
                entity.setStatus(ModelCredential.CredentialStatus.invalid);
                repository.save(entity);
                return result;
            }

            String body = objectMapper.writeValueAsString(Map.of(
                    "model", probeModel,
                    "input", "ping"
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/embeddings"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = buildHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = System.currentTimeMillis() - started;

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                result.setOk(true);
                result.setLatencyMs(elapsed);
                result.setModel(probeModel);
                Integer dim = parseEmbeddingDimension(resp.body());
                if (dim != null) {
                    result.setEmbeddingDimension(dim);
                    result.setMessage("ok (dim=" + dim + ")");
                } else {
                    // 200 but couldn't parse — treat as ok with a soft warning.
                    result.setMessage("ok (dimension unknown)");
                }
                entity.setStatus(ModelCredential.CredentialStatus.ok);
            } else {
                result.setOk(false);
                result.setLatencyMs(elapsed);
                result.setMessage("HTTP " + resp.statusCode() + " " + safeSnippet(resp.body()));
                entity.setStatus(ModelCredential.CredentialStatus.invalid);
            }
        } catch (Exception e) {
            result.setOk(false);
            result.setMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            entity.setStatus(ModelCredential.CredentialStatus.invalid);
        }
        repository.save(entity);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Integer parseEmbeddingDimension(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object data = parsed.get("data");
            if (!(data instanceof List<?> rows) || rows.isEmpty()) return null;
            Object first = rows.get(0);
            if (!(first instanceof Map<?, ?> firstMap)) return null;
            Object embedding = firstMap.get("embedding");
            if (!(embedding instanceof List<?> vector)) return null;
            return vector.size();
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return null;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String safeSnippet(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private static String normalizePurpose(String raw) {
        if (raw == null || raw.isBlank()) return ModelCredential.PURPOSE_CHAT;
        String v = raw.trim().toLowerCase();
        if (ModelCredential.PURPOSE_EMBEDDING.equals(v)) return ModelCredential.PURPOSE_EMBEDDING;
        return ModelCredential.PURPOSE_CHAT;
    }

    private static boolean isChatPurpose(String purpose) {
        return purpose == null || purpose.isBlank() || ModelCredential.PURPOSE_CHAT.equalsIgnoreCase(purpose);
    }

    private void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return;
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "baseUrl must start with http:// or https://");
        }
    }

    private ResolvedProvider toResolved(ModelCredential m) {
        String apiKey = m.getApiKeyEncrypted() == null ? null : crypto.decrypt(m.getApiKeyEncrypted());
        return new ResolvedProvider(
                m.getId(),
                m.getProvider(),
                m.getDisplayName(),
                m.getBaseUrl(),
                m.getDefaultModel(),
                apiKey
        );
    }

    private AgentProviderDtos.ProviderView toView(ModelCredential m) {
        AgentProviderDtos.ProviderView view = new AgentProviderDtos.ProviderView();
        view.setId(m.getId());
        view.setProvider(m.getProvider());
        view.setPurpose(m.getPurpose() == null ? ModelCredential.PURPOSE_CHAT : m.getPurpose());
        view.setDisplayName(m.getDisplayName() == null ? m.getProvider() : m.getDisplayName());
        view.setBaseUrl(m.getBaseUrl());
        view.setDefaultModel(m.getDefaultModel());
        view.setHasApiKey(m.getApiKeyEncrypted() != null && !m.getApiKeyEncrypted().isBlank());
        view.setApiKeyMask(maskKey(m));
        view.setDefault(Boolean.TRUE.equals(m.getIsDefault()));
        view.setStatus(m.getStatus() == null ? "unknown" : m.getStatus().name());
        view.setCreatedAt(m.getCreatedAt());
        view.setUpdatedAt(m.getUpdatedAt());
        return view;
    }

    private String maskKey(ModelCredential m) {
        if (m.getApiKeyEncrypted() == null || m.getApiKeyEncrypted().isBlank()) return null;
        try {
            String plain = crypto.decrypt(m.getApiKeyEncrypted());
            if (plain == null || plain.length() < 6) return "***";
            return plain.substring(0, 3) + "***" + plain.substring(plain.length() - 4);
        } catch (Exception e) {
            return "***";
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<String> fetchAvailableModels(String base, String apiKey) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/models"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();

            HttpResponse<String> resp = buildHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300 || resp.body() == null || resp.body().isBlank()) {
                return List.of();
            }

            Map<String, Object> payload = objectMapper.readValue(resp.body(), Map.class);
            Object rawData = payload.get("data");
            if (!(rawData instanceof List<?> rows)) {
                return List.of();
            }

            Set<String> models = new LinkedHashSet<>();
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> m)) continue;
                Object id = m.get("id");
                if (id instanceof String s && !s.isBlank()) {
                    models.add(s.trim());
                }
            }
            if (models.isEmpty()) {
                return List.of();
            }

            List<String> sorted = new ArrayList<>(models);
            sorted.sort(Comparator.naturalOrder());
            return sorted;
        } catch (Exception e) {
            log.info("Fetch provider models failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String chooseProbeModel(String configuredDefault, List<String> availableModels) {
        if (configuredDefault != null && configuredDefault.isBlank()) {
            configuredDefault = null;
        }
        if (availableModels == null || availableModels.isEmpty()) {
            return configuredDefault;
        }
        if (configuredDefault != null && availableModels.stream().anyMatch(configuredDefault::equals)) {
            return configuredDefault;
        }
        return availableModels.get(0);
    }
}

package com.nexus.chat.service.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Java → Python HTTP client for the Module B knowledge endpoints
 * (POST /v1/knowledge/{ingest,delete,query}). Self-contained: builds its
 * own HMAC headers using the same five-element scheme AgentGatewayService
 * uses, with its own RestTemplate so ingestion's longer read timeout
 * doesn't bleed into the chat path.
 *
 * <p><b>Provider headers carry the EMBEDDING credential here</b>, not the
 * chat one. That's a deliberate split from {@link AgentGatewayService}: the
 * Python /v1/knowledge/* routes only need an embedding API key (no chat
 * model is invoked), and the user's chat provider (DeepSeek / Moonshot /
 * "国产模型" aggregators / etc.) often does not expose
 * OpenAI-compatible /embeddings. Routing the chat key here yields the
 * confusing "model_not_found" / "no available channel" 503 chain the user
 * was hitting before this layer was rewritten.
 *
 * <p>The embedding credential is resolved per-call via
 * {@link AgentProviderService#resolveEmbeddingCredential(Long, Long)}; pass
 * the KB's {@code embeddingCredentialId} into {@link IngestRequest} and
 * {@link QueryRequest}. When that field is null the call still goes through
 * (Python falls back to its env-level {@code EMBEDDING_API_KEY}); the row's
 * status will surface "no embedding API key resolvable" if neither side
 * has one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGatewayService {

    private final ObjectMapper objectMapper;
    private final RestTemplateBuilder restTemplateBuilder;
    private final AgentProviderService providerService;

    @Value("${agent.python.base-url:http://localhost:8100}")
    private String pythonBaseUrl;

    @Value("${agent.internal.signing-secret:dev-signing-secret-change-me}")
    private String signingSecret;

    @Value("${agent.python.connect-timeout-ms:3000}")
    private long connectTimeoutMs;

    /** Long enough to swallow a 50 MB PDF embedding pass without false-positive timeouts. */
    @Value("${agent.python.knowledge-ingest-timeout-ms:120000}")
    private long ingestTimeoutMs;

    @Value("${agent.python.knowledge-query-timeout-ms:15000}")
    private long queryTimeoutMs;

    private RestTemplate ingestClient;
    private RestTemplate queryClient;

    private RestTemplate ingestClient() {
        if (ingestClient == null) {
            ingestClient = restTemplateBuilder
                    .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .setReadTimeout(Duration.ofMillis(ingestTimeoutMs))
                    .build();
        }
        return ingestClient;
    }

    private RestTemplate queryClient() {
        if (queryClient == null) {
            queryClient = restTemplateBuilder
                    .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .setReadTimeout(Duration.ofMillis(queryTimeoutMs))
                    .build();
        }
        return queryClient;
    }

    // -------- Public API --------

    public IngestResponse ingest(Long actorUserId, IngestRequest req) {
        return post(actorUserId, req.getEmbeddingCredentialId(), "/v1/knowledge/ingest", req, IngestResponse.class, ingestClient());
    }

    public DeleteResponse delete(Long actorUserId, DeleteRequest req) {
        // Delete doesn't run embeddings; pass null so we don't burden the call with
        // unnecessary credential lookup / signing overhead.
        return post(actorUserId, null, "/v1/knowledge/delete", req, DeleteResponse.class, queryClient());
    }

    public QueryResponse query(Long actorUserId, QueryRequest req) {
        return post(actorUserId, req.getEmbeddingCredentialId(), "/v1/knowledge/query", req, QueryResponse.class, queryClient());
    }

    // -------- Internals --------

    private <Req, Resp> Resp post(Long actorUserId, Long embeddingCredentialId, String path, Req body, Class<Resp> respType, RestTemplate client) {
        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new KnowledgeGatewayException("failed to serialize request: " + e.getMessage(), e);
        }

        // Resolve the embedding credential the KB is bound to. If none is configured
        // for this user/KB, leave provider null — Python will fall back to env defaults
        // and surface a clear "no embedding API key resolvable" message if those are
        // also missing.
        AgentProviderService.ResolvedProvider provider = (actorUserId != null && embeddingCredentialId != null)
                ? providerService.resolveEmbeddingCredential(actorUserId, embeddingCredentialId).orElse(null)
                : null;

        String traceId = "tr_kb_" + UUID.randomUUID().toString().replace("-", "");
        HttpHeaders headers;
        try {
            headers = buildInternalHeaders(actorUserId == null ? 0L : actorUserId, traceId, bodyJson, provider);
        } catch (Exception e) {
            throw new KnowledgeGatewayException("failed to build internal headers: " + e.getMessage(), e);
        }

        HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);
        try {
            ResponseEntity<Resp> response = client.exchange(
                    pythonBaseUrl + path, HttpMethod.POST, entity, respType);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new KnowledgeGatewayException(
                        "python " + path + " returned non-2xx: " + response.getStatusCode());
            }
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            throw new KnowledgeGatewayException(parseErrorMessage(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            log.warn("python {} connect/IO failure: {}", path, e.getMessage());
            throw new KnowledgeGatewayException(
                    "python service unavailable: " + (e.getMessage() == null ? "unknown" : e.getMessage()), e);
        }
    }

    private String parseErrorMessage(String body) {
        if (body == null || body.isBlank()) return "python returned empty error body";
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            Object message = parsed.get("message");
            if (message instanceof String s && !s.isBlank()) {
                return s.length() > 480 ? s.substring(0, 480) : s;
            }
            Object detail = parsed.get("detail");
            if (detail instanceof Map<?, ?> dm) {
                Object inner = dm.get("message");
                if (inner instanceof String s && !s.isBlank()) {
                    return s.length() > 480 ? s.substring(0, 480) : s;
                }
            }
            return body.length() > 480 ? body.substring(0, 480) : body;
        } catch (Exception ex) {
            return body.length() > 480 ? body.substring(0, 480) : body;
        }
    }

    private HttpHeaders buildInternalHeaders(Long actorUserId, String traceId, String bodyJson,
                                             AgentProviderService.ResolvedProvider provider) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String bodyHash = sha256Hex(bodyJson);

        StringBuilder providerSig = new StringBuilder();
        if (provider != null) {
            String safeBaseUrl = provider.baseUrl() == null ? "" : provider.baseUrl();
            String safeModel = provider.defaultModel() == null ? "" : provider.defaultModel();
            String apiKeyB64 = provider.apiKeyPlain() == null
                    ? ""
                    : Base64.getEncoder().encodeToString(provider.apiKeyPlain().getBytes(StandardCharsets.UTF_8));
            headers.set("X-Model-Provider", provider.provider());
            headers.set("X-Model-Base-URL", safeBaseUrl);
            headers.set("X-Model-Name", safeModel);
            headers.set("X-Model-Api-Key", apiKeyB64);
            providerSig.append(".").append(sha256Hex(provider.provider() + "|" + safeBaseUrl + "|" + safeModel + "|" + apiKeyB64));
        }

        String signature = hmacSha256Hex(signingSecret, timestamp + "." + nonce + "." + bodyHash + providerSig);
        headers.set("X-Internal-Service", "nexus-chat-backend");
        headers.set("X-Internal-Timestamp", timestamp);
        headers.set("X-Internal-Nonce", nonce);
        headers.set("X-Internal-Signature", signature);
        headers.set("X-Trace-Id", traceId);
        headers.set("X-Actor-User-Id", String.valueOf(actorUserId));
        return headers;
    }

    private static String sha256Hex(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String hmacSha256Hex(String secret, String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // -------- DTOs --------
    //
    // Field names mirror the Pydantic schemas in nexus-agent-backend/app/schemas.py.
    // Optional fields default to null so they're omitted via JsonInclude.NON_NULL.

    @Data @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IngestRequest {
        private String kbId;
        private String docId;
        private String filePath;
        private String fileType;
        private String fileName;
        private Long userId;
        private Integer chunkSize;
        private Integer chunkOverlap;
        private String embeddingModel;
        /**
         * Used by {@link KnowledgeGatewayService#post} to resolve which decrypted
         * embedding credential gets forwarded as X-Model-* headers. NOT serialized
         * to Python — the Python schema doesn't expect this field, and writing
         * the credential id into the body would also defeat the HMAC-bound
         * provider envelope.
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        private Long embeddingCredentialId;
    }

    @Data
    public static class IngestResponse {
        private String kbId;
        private String docId;
        private Integer chunkCount;
        private String status;
        /** Vector dimension Python observed for this batch; null when no batch ran. */
        private Integer embeddingDimension;
    }

    @Data @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DeleteRequest {
        private String kbId;
        private String docId;
    }

    @Data
    public static class DeleteResponse {
        private String kbId;
        private String docId;
        private Integer deletedCount;
    }

    @Data @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QueryRequest {
        private String kbId;
        private String query;
        private Integer topK;
        private Long userId;
        private String embeddingModel;
        /**
         * Same JsonIgnore semantics as on IngestRequest.embeddingCredentialId — the
         * credential is forwarded via signed X-Model-* headers, not the JSON body.
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        private Long embeddingCredentialId;
    }

    @Data
    public static class QueryResponse {
        private String kbId;
        private String query;
        private List<Chunk> chunks;
    }

    @Data
    public static class Chunk {
        private String chunkId;
        private String text;
        private Double score;
        private Map<String, Object> metadata;
    }

    public static class KnowledgeGatewayException extends RuntimeException {
        public KnowledgeGatewayException(String message) { super(message); }
        public KnowledgeGatewayException(String message, Throwable cause) { super(message, cause); }
    }
}

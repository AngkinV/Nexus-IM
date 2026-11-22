package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.dto.agent.AgentEnums;
import com.nexus.chat.dto.agent.AgentSessionAndChatDtos;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentGatewayService {

    private final ObjectMapper objectMapper;
    private final RestTemplateBuilder restTemplateBuilder;
    private final AgentProviderService providerService;

    @Value("${agent.python.base-url:http://localhost:8100}")
    private String pythonBaseUrl;

    @Value("${agent.internal.signing-secret:dev-signing-secret-change-me}")
    private String signingSecret;

    @Value("${agent.python.connect-timeout-ms:3000}")
    private long connectTimeoutMs;

    @Value("${agent.python.read-timeout-ms:120000}")
    private long readTimeoutMs;

    private RestTemplate restTemplate;

    private RestTemplate restTemplate() {
        if (restTemplate == null) {
            restTemplate = restTemplateBuilder
                    .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                    .build();
        }
        return restTemplate;
    }

    public Map<String, Object> invokeNonStream(Long actorUserId,
                                                String username,
                                                String traceId,
                                                String sessionId,
                                                AgentSessionAndChatDtos.SessionChatRequest request) {
        try {
            Map<String, Object> body = buildInvokeBody(actorUserId, username, traceId, sessionId, request);
            String bodyJson = objectMapper.writeValueAsString(body);
            AgentProviderService.ResolvedProvider provider =
                    resolveChannel(actorUserId, request.getProviderId());
            HttpHeaders headers = buildInternalHeaders(actorUserId, traceId, bodyJson, provider);

            HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);
            ResponseEntity<Map> response = restTemplate().exchange(
                    pythonBaseUrl + "/v1/agent/invoke",
                    HttpMethod.POST,
                    entity,
                    Map.class
            );
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new AgentException(AgentErrorCode.AGENT_MODEL_50202, "Python agent returned non-2xx");
            }
            return response.getBody() == null ? Map.of() : response.getBody();
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            log.error("invoke agent failed", e);
            throw new AgentException(AgentErrorCode.AGENT_MODEL_50202, "Agent service unavailable");
        }
    }

    /**
     * 轻量级一次性调用，封装了一个合成的 SessionChatRequest。
     * 供模式 B 的端点（摘要 / 待办提取 / 回复建议）使用，这些端点无需持久的代理会话，
     * 仅需与模型进行一次往返通信。
     */
    public Map<String, Object> invokeOneShot(Long actorUserId,
                                             String username,
                                             AgentEnums.OperationType operationType,
                                             String input,
                                             Long chatId,
                                             Map<String, Object> extra) {
        AgentSessionAndChatDtos.SessionChatRequest req = new AgentSessionAndChatDtos.SessionChatRequest();
        req.setOperationType(operationType);
        req.setInput(input);
        AgentSessionAndChatDtos.ChatContext ctx = new AgentSessionAndChatDtos.ChatContext();
        ctx.setChatId(chatId);
        req.setChatContext(ctx);
        req.setOptions(new AgentSessionAndChatDtos.Options());

        String traceId = "tr_" + UUID.randomUUID().toString().replace("-", "");
        String ephemeralSessionId = "as_oneshot_" + UUID.randomUUID().toString().replace("-", "");

        Map<String, Object> body = buildInvokeBody(actorUserId, username, traceId, ephemeralSessionId, req);
        if (extra != null && !extra.isEmpty()) {
            Map<String, Object> input2 = new LinkedHashMap<>((Map<String, Object>) body.get("input"));
            input2.putAll(extra);
            body.put("input", input2);
        }

        try {
            String bodyJson = objectMapper.writeValueAsString(body);
            AgentProviderService.ResolvedProvider provider = providerService
                    .resolveForRequest(actorUserId, req.getProviderId())
                    .orElse(null);
            HttpHeaders headers = buildInternalHeaders(actorUserId, traceId, bodyJson, provider);
            HttpEntity<String> entity = new HttpEntity<>(bodyJson, headers);
            ResponseEntity<Map> response = restTemplate().exchange(
                    pythonBaseUrl + "/v1/agent/invoke",
                    HttpMethod.POST,
                    entity,
                    Map.class
            );
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new AgentException(AgentErrorCode.AGENT_MODEL_50202, "Python agent returned non-2xx");
            }
            return response.getBody() == null ? Map.of() : response.getBody();
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            log.error("one-shot agent invoke failed", e);
            throw new AgentException(AgentErrorCode.AGENT_MODEL_50202, "Agent service unavailable");
        }
    }

    public Map<String, Object> pendingRuns(Long actorUserId, String traceId, String sessionId) {
        return postRunRequest("/v1/agent/runs/pending", actorUserId, traceId,
                Map.of("actor", Map.of("userId", actorUserId), "sessionId", sessionId, "traceId", traceId));
    }

    public void purgeRuns(Long actorUserId, String traceId, String sessionId) {
        postRunRequest("/v1/agent/runs/purge", actorUserId, traceId,
                Map.of("actor", Map.of("userId", actorUserId), "sessionId", sessionId, "traceId", traceId));
    }

    private Map<String, Object> postRunRequest(String path, Long actorUserId, String traceId, Map<String, Object> body) {
        try {
            String bodyJson = objectMapper.writeValueAsString(body);
            ResponseEntity<Map> response = restTemplate().exchange(pythonBaseUrl + path, HttpMethod.POST,
                    new HttpEntity<>(bodyJson, buildInternalHeaders(actorUserId, traceId, bodyJson)), Map.class);
            if (response.getBody() == null) {
                throw new AgentException(AgentErrorCode.AGENT_MODEL_50202, "Python returned no response");
            }
            return response.getBody();
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 409) {
                throw new AgentException(AgentErrorCode.AGENT_STREAM_40901, "Session has a running task; stop it before clearing");
            }
            throw new AgentException(AgentErrorCode.AGENT_MODEL_50202, "Python run API returned " + e.getStatusCode().value());
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException(AgentErrorCode.AGENT_MODEL_50202, "Python agent service unavailable");
        }
    }

    public void resumePythonRaw(Long actorUserId, String username, String traceId, String sessionId,
                                String runId, Long providerId, OutputStream out) throws IOException {
        if (runId == null || !runId.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new AgentException(AgentErrorCode.AGENT_PARAM_40001, "Invalid run id");
        }
        Map<String, Object> body = Map.of("actor", Map.of("userId", actorUserId, "username", username),
                "sessionId", sessionId, "runId", runId, "traceId", traceId);
        streamInternal("/v1/agent/runs/resume/stream", actorUserId, traceId, body, providerId, out);
    }

    public void streamPythonRaw(Long actorUserId, String username, String traceId, String sessionId,
                                AgentSessionAndChatDtos.SessionChatRequest request, OutputStream out) throws IOException {
        streamInternal("/v1/agent/invoke/stream", actorUserId, traceId,
                buildInvokeBody(actorUserId, username, traceId, sessionId, request), request.getProviderId(), out);
    }

    private void streamInternal(String path, Long actorUserId, String traceId, Map<String, Object> body,
                                Long providerId, OutputStream out) throws IOException {
        String bodyJson = objectMapper.writeValueAsString(body);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest.Builder rb = HttpRequest.newBuilder().uri(URI.create(pythonBaseUrl + path))
                .timeout(Duration.ofMillis(Math.max(readTimeoutMs, 120_000L)))
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));
        try {
            AgentProviderService.ResolvedProvider provider = resolveChannel(actorUserId, providerId);
            for (Map.Entry<String, java.util.List<String>> entry : buildInternalHeaders(actorUserId, traceId, bodyJson, provider).entrySet()) {
                rb.header(entry.getKey(), entry.getValue().get(0));
            }
        } catch (AgentException e) {
            // The channel cannot be used as configured (e.g. no model set). The response is
            // already an event stream, so say so in-band with the real reason. "CONFIG" is the
            // same reason Python reports for an unusable channel, so the client offers the settings.
            String payload = "event: error\ndata: " + objectMapper.writeValueAsString(
                    Map.of("code", e.getCode().name(), "reason", "CONFIG", "message", e.getMessage())) + "\n\n";
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        } catch (Exception e) {
            throw new IOException("Failed to authenticate internal request", e);
        }
        HttpResponse<InputStream> response;
        try {
            response = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while contacting Python", e);
        } catch (IOException e) {
            writeErrorEvent(out, "PYTHON_UNAVAILABLE", "Python agent service unavailable");
            return;
        }
        try (InputStream in = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                writeErrorEvent(out, "PYTHON_HTTP_" + response.statusCode(), "Python agent returned " + response.statusCode());
                return;
            }
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                out.flush();
            }
        }
    }

    private void writeErrorEvent(OutputStream out, String code, String message) throws IOException {
        String payload = "event: error\ndata: " + objectMapper.writeValueAsString(Map.of("code", code, "message", message)) + "\n\n";
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private Map<String, Object> buildInvokeBody(Long actorUserId,
                                                String username,
                                                String traceId,
                                                String sessionId,
                                                AgentSessionAndChatDtos.SessionChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        // The client's run id when it has one: a retry must be a new run of the same question,
        // and the record of it already exists on this side.
        body.put("runId", request.getRunId() != null && !request.getRunId().isBlank()
                ? request.getRunId() : UUID.randomUUID().toString().replace("-", ""));
        if (request.getRetryOfRunId() != null) {
            body.put("retryOfRunId", request.getRetryOfRunId());
        }
        body.put("traceId", traceId);
        body.put("actor", Map.of("userId", actorUserId, "username", username == null ? "unknown" : username));
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("sessionId", sessionId);
        session.put("operationType", request.getOperationType().name());
        // Module B: forward the optional kbId so the Python orchestrator can
        // decide whether to run knowledge_rag.retrieve. We omit the field
        // when not bound — Pydantic on the other side treats absent as null.
        if (request.getLinkedKbId() != null && !request.getLinkedKbId().isBlank()) {
            session.put("linkedKbId", request.getLinkedKbId());
        }
        body.put("session", session);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", request.getInput());
        input.put("chatId", request.getChatContext() != null ? request.getChatContext().getChatId() : null);
        if (request.getResolvedAttachments() != null && !request.getResolvedAttachments().isEmpty()) {
            // Only what the workspace resolved; a client cannot put its own text here.
            input.put("attachments", request.getResolvedAttachments());
        }
        body.put("input", input);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("maxIterations", request.getOptions() != null && request.getOptions().getMaxIterations() != null ? request.getOptions().getMaxIterations() : 6);
        options.put("maxOutputTokens", request.getOptions() != null && request.getOptions().getMaxOutputTokens() != null ? request.getOptions().getMaxOutputTokens() : 1024);
        options.put("temperature", request.getOptions() != null && request.getOptions().getTemperature() != null ? request.getOptions().getTemperature() : 0.2);
        options.put("collaborationEnabled", request.getOptions() != null && Boolean.TRUE.equals(request.getOptions().getCollaborationEnabled()));
        options.put("webSearchEnabled", request.getOptions() != null && Boolean.TRUE.equals(request.getOptions().getWebSearchEnabled()));
        body.put("options", options);
        return body;
    }

    /**
     * The channel to use. A channel the user explicitly chose must resolve: silently
     * dropping to the service's own default would answer with a different model than
     * the one on screen.
     */
    private AgentProviderService.ResolvedProvider resolveChannel(Long actorUserId, Long providerId) {
        var resolved = providerService.resolveForRequest(actorUserId, providerId);
        if (resolved.isEmpty() && providerId != null) {
            throw new AgentException(AgentErrorCode.AGENT_AUTHZ_40301,
                    "The selected model channel is not available to this user");
        }
        return resolved.orElse(null);
    }

    /**
     * Extract the text of an uploaded file. The bytes go to Python, which owns every
     * document parser, and the answer is returned as it came: no local guessing.
     */
    public Map<String, Object> extractAttachment(Long actorUserId, String fileName, byte[] content) {
        return postInternal(actorUserId, "/v1/agent/attachments/extract",
                Map.of("fileName", fileName, "contentBase64", Base64.getEncoder().encodeToString(content)));
    }

    /** Dry-run a patch against the uploaded text. Nothing is written. */
    public Map<String, Object> previewArtifactPatch(Long actorUserId, String fileName, String original, String patch) {
        return postInternal(actorUserId, "/v1/agent/artifacts/patch-preview",
                Map.of("fileName", fileName, "original", original, "patch", patch));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postInternal(Long actorUserId, String path, Map<String, Object> body) {
        String traceId = "tr_" + UUID.randomUUID().toString().replace("-", "");
        try {
            String bodyJson = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(bodyJson, buildInternalHeaders(actorUserId, traceId, bodyJson));
            ResponseEntity<Map> response = restTemplate().exchange(pythonBaseUrl + path, HttpMethod.POST, entity, Map.class);
            return response.getBody() == null ? Map.of() : (Map<String, Object>) response.getBody();
        } catch (org.springframework.web.client.HttpStatusCodeException ex) {
            // The document service says why (e.g. the old lines do not match); pass that on
            // with its status, and never echo the body it was given.
            String detail = ex.getResponseBodyAsString();
            String reason = "The document service refused the request";
            try {
                Object parsed = objectMapper.readValue(detail, Map.class).get("detail");
                if (parsed instanceof String text && !text.isBlank()) reason = text;
            } catch (Exception ignored) {
                // A body we cannot read leaves the generic reason.
            }
            AgentErrorCode code = ex.getStatusCode().value() == 422
                    ? AgentErrorCode.AGENT_DOCUMENT_42202 : AgentErrorCode.AGENT_PARAM_40001;
            throw new AgentException(code, reason, Map.of("upstreamStatus", ex.getStatusCode().value()));
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            throw new AgentException(AgentErrorCode.AGENT_MODEL_50202, "The document service is unavailable");
        } catch (Exception ex) {
            throw new IllegalStateException("Internal document request failed", ex);
        }
    }

    private HttpHeaders buildInternalHeaders(Long actorUserId, String traceId, String bodyJson) throws Exception {
        return buildInternalHeaders(actorUserId, traceId, bodyJson, null);
    }

    /**
     * Builds the canonical Java->Python internal headers, optionally with provider credentials.
     *
     * Provider headers are included in the HMAC signature input so the Python side can detect
     * tampering. The API key is base64-encoded to be header-safe.
     */
    private HttpHeaders buildInternalHeaders(Long actorUserId,
                                             String traceId,
                                             String bodyJson,
                                             AgentProviderService.ResolvedProvider provider) throws Exception {
        return InternalRequestSigner.headers(signingSecret, actorUserId, traceId, bodyJson, provider);
    }
}

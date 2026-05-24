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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentGatewayService {

    private final ObjectMapper objectMapper;
    private final AgentSseReplayCache replayCache;
    private final RestTemplateBuilder restTemplateBuilder;
    private final AgentProviderService providerService;

    @Value("${agent.python.base-url:http://localhost:8100}")
    private String pythonBaseUrl;

    @Value("${agent.internal.signing-secret:dev-signing-secret-change-me}")
    private String signingSecret;

    @Value("${agent.python.connect-timeout-ms:3000}")
    private long connectTimeoutMs;

    @Value("${agent.python.read-timeout-ms:30000}")
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
            AgentProviderService.ResolvedProvider provider = providerService
                    .resolveForRequest(actorUserId, request.getProviderId())
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

    /**
     * Pipe the Python SSE stream straight to the given output stream.
     *
     * Designed to be invoked from a {@link org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody}
     * so Spring/Tomcat handles chunked-transfer termination correctly. We deliberately avoid
     * SseEmitter + CompletableFuture.runAsync here — that combination caused
     * net::ERR_INCOMPLETE_CHUNKED_ENCODING in browsers because the response was sometimes closed
     * without a proper terminator chunk.
     *
     * The replay cache is bypassed in this path; if Last-Event-ID resume is needed later, a
     * pass-through SSE parser can be reintroduced as a thin layer.
     *
     * 将 Python SSE 流直接传输到给定的输出流。
     *
     * 设计为由 {@link org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody} 调用，
     * 以便 Spring/Tomcat 能正确处理分块传输的终止。我们有意避免在此处使用
     * SseEmitter + CompletableFuture.runAsync 的组合 —— 这种组合会导致浏览器出现
     * net::ERR_INCOMPLETE_CHUNKED_ENCODING 错误，因为响应有时会在没有正确的终止块的情况下关闭。
     *
     * 该路径绕过了重放缓存；如果以后需要基于 Last-Event-ID 的恢复，
     * 可以重新引入一个透传 SSE 解析器作为薄层。
     */
    public void streamPythonRaw(Long actorUserId,
                                String username,
                                String traceId,
                                String sessionId,
                                AgentSessionAndChatDtos.SessionChatRequest request,
                                OutputStream out) throws IOException {
        Map<String, Object> body = buildInvokeBody(actorUserId, username, traceId, sessionId, request);
        String bodyJson = objectMapper.writeValueAsString(body);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(pythonBaseUrl + "/v1/agent/invoke/stream"))
                .timeout(Duration.ofMillis(Math.max(readTimeoutMs, 120_000L)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));

        try {
            for (Map.Entry<String, java.util.List<String>> entry : buildInternalHeaders(actorUserId, traceId, bodyJson, providerService.resolveForRequest(actorUserId, request.getProviderId()).orElse(null)).entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    rb.header(entry.getKey(), entry.getValue().get(0));
                }
            }
        } catch (Exception e) {
            throw new IOException("failed to build internal headers", e);
        }

        HttpResponse<InputStream> response;
        try {
            response = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while contacting agent", e);
        } catch (IOException e) {
            log.warn("agent stream connect failure: {}", e.getMessage());
            writeErrorEvent(out, "AGENT_MODEL_50202", "upstream connect: " + e.getMessage());
            return;
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            writeErrorEvent(out, "AGENT_MODEL_50202", "upstream returned status " + status);
            try (InputStream in = response.body()) { in.readAllBytes(); } catch (IOException ignored) {}
            return;
        }

        try (InputStream in = response.body()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                // Flush after every chunk so each SSE event reaches the browser ASAP.
                out.flush();
            }
        } catch (IOException e) {
            log.warn("agent stream IO failure mid-stream: {}", e.getMessage());
            try {
                writeErrorEvent(out, "AGENT_MODEL_50202", e.getMessage() == null ? "upstream IO failure" : e.getMessage());
            } catch (IOException ignored) {
                // client already gone; nothing to do
            }
        }
    }

    private void writeErrorEvent(OutputStream out, String code, String message) throws IOException {
        String payload = "event: error\ndata: " + objectMapper.writeValueAsString(Map.of("code", code, "message", message)) + "\n\n";
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * @deprecated Kept for backward compatibility with the old SseEmitter wiring; prefer
     * {@link #streamPythonRaw} which avoids chunked-encoding termination issues.
     */
    @Deprecated
    public SseEmitter invokeStream(Long actorUserId,
                                   String username,
                                   String traceId,
                                   String sessionId,
                                   String streamKey,
                                   String lastEventId,
                                   AgentSessionAndChatDtos.SessionChatRequest request) {
        SseEmitter emitter = new SseEmitter(70_000L);
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("code", "AGENT_DEPRECATED", "message", "use streamPythonRaw")));
        } catch (IOException ignored) {
            // best effort
        }
        emitter.complete();
        return emitter;
    }

    private Map<String, Object> buildInvokeBody(Long actorUserId,
                                                String username,
                                                String traceId,
                                                String sessionId,
                                                AgentSessionAndChatDtos.SessionChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
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
        body.put("input", input);
        body.put("options", Map.of(
                "maxIterations", request.getOptions() != null && request.getOptions().getMaxIterations() != null ? request.getOptions().getMaxIterations() : 6,
                "maxOutputTokens", request.getOptions() != null && request.getOptions().getMaxOutputTokens() != null ? request.getOptions().getMaxOutputTokens() : 1024,
                "temperature", request.getOptions() != null && request.getOptions().getTemperature() != null ? request.getOptions().getTemperature() : 0.2
        ));
        return body;
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
                    : java.util.Base64.getEncoder().encodeToString(provider.apiKeyPlain().getBytes(StandardCharsets.UTF_8));
            headers.set("X-Model-Provider", provider.provider());
            headers.set("X-Model-Base-URL", safeBaseUrl);
            headers.set("X-Model-Name", safeModel);
            headers.set("X-Model-Api-Key", apiKeyB64);
            // Hash provider material into the signature
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

    private String sha256Hex(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String hmacSha256Hex(String secret, String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

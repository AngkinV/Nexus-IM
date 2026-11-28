package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class PythonModelGateway {
    private final ObjectMapper mapper;
    private final RestTemplateBuilder clients;
    private final String pythonUrl;
    private final String secret;

    public PythonModelGateway(ObjectMapper mapper, RestTemplateBuilder builder,
            @Value("${agent.python.base-url:http://localhost:8100}") String pythonUrl,
            @Value("${agent.internal.signing-secret:dev-signing-secret-change-me}") String secret,
            @Value("${agent.python.connect-timeout-ms:3000}") long connectTimeoutMs) {
        this.mapper = mapper;
        this.clients = builder.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        this.pythonUrl = pythonUrl.replaceAll("/+$", "");
        this.secret = secret;
    }

    public JsonNode catalog(long actor, AgentProviderService.ResolvedProvider cfg, String purpose) {
        return send(actor, cfg, request(actor, "catalog", purpose), 32, null);
    }

    public JsonNode test(long actor, AgentProviderService.ResolvedProvider cfg, String purpose,
                         List<Map<String, String>> messages, double temperature, int maxTokens, int timeoutSec) {
        return send(actor, cfg, completion(actor, "test", purpose, messages, temperature, maxTokens, timeoutSec), timeoutSec, null);
    }

    public JsonNode complete(long actor, AgentProviderService.ResolvedProvider cfg, List<Map<String, String>> messages,
                             double temperature, int maxTokens, int timeoutSec) {
        return send(actor, cfg, completion(actor, "complete", "chat", messages, temperature, maxTokens, timeoutSec), timeoutSec, null);
    }

    public JsonNode stream(long actor, AgentProviderService.ResolvedProvider cfg, List<Map<String, String>> messages,
                           double temperature, int maxTokens, int timeoutSec, Consumer<String> onDelta) {
        return send(actor, cfg, completion(actor, "complete_stream", "chat", messages, temperature, maxTokens, timeoutSec), timeoutSec, onDelta);
    }

    /** Explicit account embedding channel, signed exactly like chat requests. */
    public JsonNode embed(long actor, AgentProviderService.ResolvedProvider cfg, List<String> inputs, int timeoutSec) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 64
                || inputs.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 8000)
                || inputs.stream().mapToLong(s -> s.getBytes(StandardCharsets.UTF_8).length).sum() > 262144) {
            throw new IllegalArgumentException("Embedding input exceeds bounds");
        }
        int timeout = Math.max(1, Math.min(60, timeoutSec));
        ObjectNode body = request(actor, "embed", "embedding").put("timeoutSec", timeout);
        body.set("inputs", mapper.valueToTree(inputs));
        return send(actor, cfg, body, timeout, null);
    }

    private ObjectNode request(long actor, String operation, String purpose) {
        return mapper.createObjectNode().put("actorUserId", actor).put("traceId", UUID.randomUUID().toString())
                .put("operation", operation).put("purpose", purpose);
    }

    private ObjectNode completion(long actor, String op, String purpose, List<Map<String, String>> messages,
                                  double temperature, int maxTokens, int timeoutSec) {
        ObjectNode body = request(actor, op, purpose).put("temperature", temperature).put("maxOutputTokens", maxTokens)
                .put("timeoutSec", timeoutSec);
        if (!messages.isEmpty()) body.set("messages", mapper.valueToTree(messages));
        return body;
    }

    private JsonNode send(long actor, AgentProviderService.ResolvedProvider cfg, ObjectNode body, int timeoutSec, Consumer<String> onDelta) {
        String op = body.path("operation").asText();
        String route = "complete_stream".equals(op) ? "complete/stream" : op;
        String json = body.toString();
        HttpHeaders headers = InternalRequestSigner.headers(secret, actor, body.path("traceId").asText(), json, cfg);
        RestTemplate client = clients.setReadTimeout(Duration.ofSeconds(timeoutSec + 5L)).build();
        StringBuilder text = new StringBuilder();
        try {
            return client.execute(pythonUrl + "/v1/models/" + route, HttpMethod.POST, request -> {
                request.getHeaders().putAll(headers);
                request.getHeaders().setAccept(List.of(onDelta == null ? MediaType.APPLICATION_JSON : MediaType.TEXT_EVENT_STREAM));
                request.getBody().write(json.getBytes(StandardCharsets.UTF_8));
            }, response -> {
                if (onDelta != null) return readStream(response, cfg, text, onDelta);
                try {
                    JsonNode result = mapper.readTree(response.getBody());
                    if (result != null && result.isObject()) return result;
                } catch (IOException ex) {
                    return failure(cfg, op, "INCOMPLETE", "Invalid response from Python model service", text);
                }
                return failure(cfg, op, "INCOMPLETE", "Python returned an empty response", text);
            });
        } catch (HttpStatusCodeException ex) {
            int status = ex.getStatusCode().value();
            return failure(cfg, op, status == 401 || status == 403 ? "INTERNAL_AUTH" : "PYTHON_HTTP_ERROR",
                    "Python model API returned HTTP " + status, text);
        } catch (ResourceAccessException ex) {
            // Python answered the connection but not in time: that is a slow model, not a missing
            // service. A connect timeout is also a SocketTimeoutException and stays "unavailable".
            Throwable root = ex.getRootCause();
            boolean readTimedOut = root instanceof HttpTimeoutException && !(root instanceof HttpConnectTimeoutException)
                    || root instanceof SocketTimeoutException
                    && !String.valueOf(root.getMessage()).toLowerCase().startsWith("connect");
            return readTimedOut
                    ? failure(cfg, op, "TIMEOUT", "Python model service timed out", text)
                    : failure(cfg, op, "PYTHON_UNAVAILABLE", "Python model service unavailable", text);
        } catch (RestClientException ex) {
            return failure(cfg, op, "INCOMPLETE", "Invalid response from Python model service", text);
        }
    }

    private JsonNode readStream(ClientHttpResponse response, AgentProviderService.ResolvedProvider cfg,
                                StringBuilder text, Consumer<String> onDelta) throws IOException {
        ObjectNode result = mapper.createObjectNode();
        try (var reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
            String event = "", line;
            StringBuilder data = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) event = line.substring(6).trim();
                else if (line.startsWith("data:")) data.append(line.substring(5).stripLeading()).append('\n');
                else if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        JsonNode payload = mapper.readTree(data.toString());
                        switch (event) {
                            case "delta" -> {
                                String piece = payload.path("text").asText("");
                                if (!piece.isEmpty()) { text.append(piece); onDelta.accept(piece); }
                            }
                            case "usage" -> result.set("usage", payload);
                            case "error" -> result.set("error", payload);
                            case "done" -> {
                                String finish = payload.path("finishReason").asText("error");
                                if (result.hasNonNull("error") && "stop".equals(finish)) finish = "error";
                                return result.put("text", text.toString()).put("finishReason", finish)
                                        .put("partial", payload.path("partial").asBoolean(result.hasNonNull("error") && !text.isEmpty()));
                            }
                        }
                    }
                    event = "";
                    data.setLength(0);
                }
            }
        } catch (IOException ex) {
            if (!result.hasNonNull("error")) return failure(cfg, "complete_stream", "INCOMPLETE", "Python stream was interrupted", text);
        }
        if (result.hasNonNull("error")) {
            return result.put("text", text.toString()).put("finishReason", "error").put("partial", !text.isEmpty());
        }
        return failure(cfg, "complete_stream", "INCOMPLETE", "Python stream ended before completion", text);
    }

    private ObjectNode failure(AgentProviderService.ResolvedProvider cfg, String op, String code, String summary, StringBuilder text) {
        ObjectNode result = mapper.createObjectNode().put("ok", false).put("status", "error").put("message", summary)
                .put("text", text.toString()).put("finishReason", "error").put("partial", !text.isEmpty());
        result.putObject("error").put("code", code).put("stage", op).put("summary", summary)
                .put("provider", cfg.provider()).put("model", cfg.defaultModel());
        return result;
    }
}

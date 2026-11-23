package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Signed BFF forwarding only. This is not a Java MCP client or model gateway. */
@Service
public class McpManagementGateway {
    private static final int MAX_RESPONSE_BYTES = 2097152;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String signingSecret;
    private final HttpClient client;

    public McpManagementGateway(ObjectMapper json,
            @Value("${agent.python.base-url:http://localhost:8100}") String baseUrl,
            @Value("${agent.internal.signing-secret:}") String signingSecret) {
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.signingSecret = signingSecret;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
                }).build();
    }

    public JsonNode call(long actor, String trace, String operation, String id, long version, ObjectNode server) {
        try {
            if (signingSecret == null || signingSecret.isBlank() || "dev-signing-secret-change-me".equals(signingSecret)) return unavailable();
            ObjectNode body = json.createObjectNode().put("actorUserId", actor).put("traceId", trace)
                    .put("operation", operation).put("serverId", id).put("expectedConfigVersion", version);
            body.set("server", server);
            String payload = json.writeValueAsString(body);
            String suffix = switch (operation) {
                case "register" -> "/servers";
                case "update", "delete" -> "/servers/" + id;
                case "test" -> "/servers/" + id + "/test";
                case "refresh" -> "/servers/" + id + "/tools/refresh";
                case "status" -> "/servers/" + id + "/status/query";
                case "tools" -> "/servers/" + id + "/tools/query";
                default -> throw new IllegalArgumentException("MCP operation unsupported");
            };
            String method = switch (operation) {
                case "update" -> "PATCH";
                case "delete" -> "DELETE";
                default -> "POST";
            };
            int timeout = operation.equals("test") || operation.equals("refresh")
                    ? Math.min(65, Math.max(6, server.path("timeoutSec").asInt(10) + 5)) : 10;
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/agent/mcp" + suffix))
                    .timeout(Duration.ofSeconds(timeout)).header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            InternalRequestSigner.headers(signingSecret, actor, trace, payload, null)
                    .forEach((name, values) -> values.forEach(value -> request.header(name, value)));
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                if (response.statusCode() != 200) return unavailable();
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) return unavailable();
                JsonNode result = json.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
                return result != null && result.isObject() ? result : unavailable();
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            // No exception cause, remote body, credential or header is exposed or logged.
            return unavailable();
        }
    }

    private JsonNode unavailable() {
        ObjectNode result = json.createObjectNode().put("ok", false);
        result.set("error", json.createObjectNode().put("code", "MCP_UNAVAILABLE")
                .put("summary", "MCP management unavailable"));
        return result;
    }

    @Override public String toString() { return "McpManagementGateway[redacted]"; }
}

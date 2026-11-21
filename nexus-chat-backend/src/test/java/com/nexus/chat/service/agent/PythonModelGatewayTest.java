package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PythonModelGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private HttpServer python;
    private PythonModelGateway gateway;
    private final AgentProviderService.ResolvedProvider channel = new AgentProviderService.ResolvedProvider(
            5L, "custom", "Test", "http://127.0.0.1:1/v1", "example-model", "test-credential");
    private final List<Map<String,String>> messages = List.of(Map.of("role", "user", "content", "你好"));

    @BeforeEach void setup() throws Exception {
        python = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        python.start();
        gateway = new PythonModelGateway(mapper, new RestTemplateBuilder(),
                "http://127.0.0.1:" + python.getAddress().getPort(), "test-signing-secret", 1000);
    }

    @AfterEach void close() { python.stop(0); }

    private void reply(String path, int status, String response, String type) {
        python.createContext(path, ex -> {
            try (ex) {
                count.incrementAndGet();
                request.set(mapper.readTree(ex.getRequestBody()));
                assertNotNull(ex.getRequestHeaders().getFirst("X-Internal-Signature"));
                assertEquals("7", ex.getRequestHeaders().getFirst("X-Actor-User-Id"));
                assertEquals("example-model", ex.getRequestHeaders().getFirst("X-Model-Name"));
                assertEquals(request.get().path("traceId").asText(), ex.getRequestHeaders().getFirst("X-Trace-Id"));
                ex.getResponseHeaders().set("Content-Type", type);
                ex.sendResponseHeaders(status, 0);
                ex.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    @Test void discoversWithoutRunningInference() {
        reply("/v1/models/catalog",200,"{\"status\":\"ready\",\"models\":[{\"id\":\"example-model\"}]}","application/json");
        assertEquals("example-model", gateway.catalog(7, channel, "chat").path("models").get(0).path("id").asText());
        assertEquals("catalog", request.get().path("operation").asText());
        assertEquals(7, request.get().path("actorUserId").intValue());
        assertFalse(request.get().has("messages"));
        assertEquals(1, count.get());
    }

    @Test void relaysOnlyPythonDeltasAndKeepsTypedPartialError() {
        reply("/v1/models/complete/stream",200,
                ": heartbeat\r\n\r\nevent: delta\r\ndata: {\"text\":\"半句\"}\r\n\r\n"
                + "event: error\ndata: {\"code\":\"RATE_LIMIT\",\"httpStatus\":429,\"retryAfterSeconds\":5,\"summary\":\"限流\"}\n\n"
                + "event: done\ndata: {\"finishReason\":\"error\",\"partial\":true}\n\n"
                + "event: delta\ndata: {\"text\":\"ignored\"}\n\n", "text/event-stream");
        List<String> output = new ArrayList<>();
        JsonNode result = gateway.stream(7, channel, messages, .85, 1200, 5, output::add);
        assertEquals(List.of("半句"), output);
        assertEquals("RATE_LIMIT", result.at("/error/code").asText());
        assertEquals(5, result.at("/error/retryAfterSeconds").intValue());
        assertTrue(result.path("partial").asBoolean());
        assertEquals("complete_stream", request.get().path("operation").asText());
        assertEquals("你好", request.get().at("/messages/0/content").asText());
        assertFalse(request.get().toString().contains("test-credential"));
    }

    @Test void eofWithoutDoneIsNeverASuccessfulReply() {
        reply("/v1/models/complete/stream",200,"event: delta\ndata: {\"text\":\"partial\"}\n\n","text/event-stream");
        JsonNode result = gateway.stream(7, channel, messages, .2, 1200, 5, text -> {});
        assertEquals("partial", result.path("text").asText());
        assertEquals("INCOMPLETE", result.at("/error/code").asText());
    }

    @Test void typedProviderErrorSurvivesAnInterruptedErrorResponse() {
        reply("/v1/models/complete/stream",200,
                "event: error\ndata: {\"code\":\"RATE_LIMIT\",\"httpStatus\":429,\"retryAfterSeconds\":7,\"summary\":\"limit\"}\n\n", "text/event-stream");
        JsonNode result = gateway.stream(7, channel, messages, .2, 1200, 5, text -> {});
        assertEquals("RATE_LIMIT", result.at("/error/code").asText());
        assertEquals(7, result.at("/error/retryAfterSeconds").intValue());
    }

    @Test void malformedResponseIsNotMisreportedAsPythonOffline() {
        reply("/v1/models/complete",200,"{broken", "application/json");
        assertEquals("INCOMPLETE", gateway.complete(7, channel, messages, .2, 1200, 5).at("/error/code").asText());
    }

    @Test void internalAuthenticationFailureIsNotAProviderKeyFailure() {
        reply("/v1/models/test",403,"{\"detail\":\"internal signature mismatch\"}","application/json");
        JsonNode result = gateway.test(7, channel, "chat", messages, .2, 1200, 5);
        assertFalse(result.path("ok").asBoolean());
        assertEquals("INTERNAL_AUTH", result.at("/error/code").asText());
        assertEquals(1200, request.get().path("maxOutputTokens").intValue());
    }

    @Test void slowPythonIsATimeoutNotAnOutage() {
        var release = new java.util.concurrent.CountDownLatch(1);
        python.createContext("/v1/models/complete", ex -> {
            try (ex) {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            // The read timeout is timeoutSec + 5s, so -4 keeps this test at one second.
            JsonNode result = gateway.complete(7, channel, messages, .2, 1200, -4);
            assertEquals("TIMEOUT", result.at("/error/code").asText());
            assertFalse(result.path("ok").asBoolean());
        } finally { release.countDown(); }
    }

    @Test void unavailablePythonNeverContactsTheConfiguredProvider() throws Exception {
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger upstream = new AtomicInteger();
        provider.createContext("/", ex -> { upstream.incrementAndGet(); ex.close(); });
        provider.start();
        try {
            var cfg = new AgentProviderService.ResolvedProvider(6L, "custom", "Test",
                    "http://127.0.0.1:" + provider.getAddress().getPort(), "model", "dummy");
            python.stop(0);
            assertEquals("PYTHON_UNAVAILABLE", gateway.complete(7, cfg, messages, .2, 1024, 5).at("/error/code").asText());
            assertEquals(0, upstream.get());
        } finally { provider.stop(0); }
    }
}

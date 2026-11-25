package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** What the agent actually receives from a management call, on a real loopback socket. */
class McpManagementGatewayTransportTest {
    final ObjectMapper json = new ObjectMapper();
    final AtomicReference<Headers> headers = new AtomicReference<>();
    final AtomicReference<String> body = new AtomicReference<>();
    final AtomicReference<String> target = new AtomicReference<>();
    HttpServer agent;

    @BeforeEach void start() throws Exception {
        agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/", exchange -> {
            headers.set(exchange.getRequestHeaders());
            target.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        agent.start();
    }

    @AfterEach void stop() { agent.stop(0); }

    @Test void aManagementCallIsPlainHttp11WithItsSignedBodyAndNoUpgradeOffer() throws Exception {
        McpManagementGateway gateway = new McpManagementGateway(json,
                "http://127.0.0.1:" + agent.getAddress().getPort() + "/", "synthetic-signing-secret-of-enough-length");
        JsonNode result = gateway.call(7L, "tr_synthetic", "test", "server-1", 3L, json.createObjectNode().put("timeoutSec", 5));

        assertTrue(result.path("ok").asBoolean());
        assertEquals("POST /v1/agent/mcp/servers/server-1/test", target.get());
        // An upgrade offer makes the agent drop the body, and the body is what the signature covers.
        assertNull(headers.get().getFirst("Upgrade"));
        assertNull(headers.get().getFirst("HTTP2-Settings"));
        JsonNode sent = json.readTree(body.get());
        assertEquals(7L, sent.path("actorUserId").asLong());
        assertEquals("test", sent.path("operation").asText());
        assertEquals("server-1", sent.path("serverId").asText());
        assertEquals(3L, sent.path("expectedConfigVersion").asLong());
        assertEquals("nexus-chat-backend", headers.get().getFirst("X-Internal-Service"));
        assertNotNull(headers.get().getFirst("X-Internal-Signature"));
    }
}

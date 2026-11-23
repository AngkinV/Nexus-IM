package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.dto.agent.AgentEnums;
import com.nexus.chat.dto.agent.AgentSessionAndChatDtos;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRunGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<Map<String, Object>> requests = new ArrayList<>();
    private final AgentProviderService providers = mock(AgentProviderService.class);
    private boolean finished;
    private HttpServer server;
    private AgentGatewayService gateway;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            try {
                String timestamp = exchange.getRequestHeaders().getFirst("X-Internal-Timestamp");
                String nonce = exchange.getRequestHeaders().getFirst("X-Internal-Nonce");
                String signature = exchange.getRequestHeaders().getFirst("X-Internal-Signature");
                String input = timestamp + "." + nonce + "." + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec("test-signing-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                assertEquals(HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8))), signature);
                requests.add(mapper.readValue(body, Map.class));
                String response = exchange.getRequestURI().getPath().endsWith("/cancel")
                        ? "{\"runId\":\"run_test_001\",\"status\":\"not_active\"}"
                        : finished && exchange.getRequestURI().getPath().endsWith("/pending")
                        ? "{\"finished\":[\"run_test_001\"],\"runs\":[]}"
                        : exchange.getRequestURI().getPath().endsWith("/stream")
                        ? "event: meta\ndata: {\"runId\":\"run_test_001\"}\n\nevent: done\ndata: {\"finishReason\":\"stop\"}\n\n"
                        : "{\"runs\":[{\"runId\":\"run_test_001\",\"active\":false}],\"purged\":1}";
                exchange.getResponseHeaders().set("Content-Type", exchange.getRequestURI().getPath().endsWith("/stream")
                        ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            } finally {
                exchange.close();
            }
        });
        server.start();
        when(providers.resolveForRequest(anyLong(), any())).thenReturn(Optional.empty());
        gateway = new AgentGatewayService(mapper, new RestTemplateBuilder(), providers);
        ReflectionTestUtils.setField(gateway, "pythonBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(gateway, "signingSecret", "test-signing-secret");
        ReflectionTestUtils.setField(gateway, "connectTimeoutMs", 1000L);
        ReflectionTestUtils.setField(gateway, "readTimeoutMs", 1000L);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void startsEachTurnWithServerGeneratedRunId() throws Exception {
        AgentSessionAndChatDtos.SessionChatRequest request = new AgentSessionAndChatDtos.SessionChatRequest();
        request.setOperationType(AgentEnums.OperationType.ASSISTANT_CHAT);
        request.setInput("hello");
        com.nexus.chat.agent.AgentScopeWireFixture.bind(request,7L,"session_001");
        gateway.streamPythonRaw(7L, "alice", "trace_001", "session_001", request, new ByteArrayOutputStream());
        assertTrue(requests.get(0).get("runId") instanceof String);
        assertTrue(((String) requests.get(0).get("runId")).matches("[A-Za-z0-9_-]{8,64}"));
    }

    @Test
    void sendsAuthenticatedResumeWithoutRepostingUserTurn() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        gateway.resumePythonRaw(7L, "alice", "trace_001", "session_001", "run_test_001", null,
                com.nexus.chat.agent.AgentScopeWireFixture.scope(7L,"session_001","run_test_001"), out);
        Map<String, Object> body = requests.get(requests.size() - 1);
        assertEquals("run_test_001", body.get("runId"));
        assertEquals("session_001", body.get("sessionId"));
        assertEquals("trace_001", body.get("traceId"));
        assertEquals(7, ((Map<?, ?>) body.get("actor")).get("userId"));
        assertFalse(body.containsKey("input"));
        assertEquals(2, requests.size(), "Lifecycle ownership query precedes resume");
        assertEquals("session_001", requests.get(0).get("sessionId"));
        assertEquals(7, ((Map<?, ?>) requests.get(0).get("actor")).get("userId"));
        assertFalse(requests.get(0).containsKey("input"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("event: done"));
    }

    @Test
    void unusableChannelIsReportedInTheStreamWithItsRealReason() throws Exception {
        when(providers.resolveForRequest(anyLong(), any())).thenThrow(new AgentException(
                AgentErrorCode.AGENT_PARAM_40001, "Default channel has no model configured"));
        AgentSessionAndChatDtos.SessionChatRequest request = new AgentSessionAndChatDtos.SessionChatRequest();
        request.setOperationType(AgentEnums.OperationType.ASSISTANT_CHAT);
        request.setInput("hello");
        com.nexus.chat.agent.AgentScopeWireFixture.bind(request,7L,"session_001");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        gateway.streamPythonRaw(7L, "alice", "trace_001", "session_001", request, out);

        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        assertEquals("event: error", lines[0]);
        Map<?, ?> data = mapper.readValue(lines[1].substring("data: ".length()), Map.class);
        assertEquals("AGENT_PARAM_40001", data.get("code"));
        assertEquals("CONFIG", data.get("reason"));
        assertEquals(1, data.get("runtimeContractVersion"));
        assertEquals("run", data.get("scope"));
        assertEquals(1, data.get("sequence"));
        assertTrue(data.get("attemptId") instanceof String);
        assertTrue(data.get("runId") instanceof String);
        assertTrue(((Number) data.get("at")).longValue() >= 0);
        assertEquals("Default channel has no model configured", data.get("message"));
        assertTrue(requests.isEmpty(), "Python must not be called for a channel that cannot be used");
        // The run never reached the agent service, so there is nothing to continue.
        Map<?, ?> done = mapper.readValue(lines[4].substring("data: ".length()), Map.class);
        assertEquals("event: done", lines[3]);
        assertEquals(false, done.get("resumable"));
    }

    @Test
    void unusableChannelOnResumeLeavesTheCheckpointResumable() throws Exception {
        when(providers.resolveForRequest(anyLong(), any())).thenThrow(new AgentException(
                AgentErrorCode.AGENT_PARAM_40001, "Default channel has no model configured"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        gateway.resumePythonRaw(7L, "alice", "trace_001", "session_001", "run_test_001", null,
                com.nexus.chat.agent.AgentScopeWireFixture.scope(7L, "session_001", "run_test_001"), out);

        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        Map<?, ?> error = mapper.readValue(lines[1].substring("data: ".length()), Map.class);
        assertEquals("CONFIG", error.get("reason"));
        assertEquals("run", error.get("scope"));
        assertEquals("event: done", lines[3]);
        Map<?, ?> done = mapper.readValue(lines[4].substring("data: ".length()), Map.class);
        assertEquals("error", done.get("finishReason"));
        assertEquals("run_test_001", done.get("runId"));
        // Only the channel was unusable; the unfinished run is still there once it is fixed.
        assertEquals(true, done.get("resumable"));
        assertEquals(1, requests.size(), "only the lifecycle query reached Python");
    }

    @Test
    void readsPendingRunsAndPurgesOnlyOwnersSession() {
        Map<String, Object> pending = gateway.pendingRuns(7L, "trace_001", "session_001");
        assertEquals(1, ((List<?>) pending.get("runs")).size());
        gateway.purgeRuns(7L, "trace_002", "session_001");
        assertEquals(2, requests.size());
        assertEquals("session_001", requests.get(1).get("sessionId"));
    }

    @Test
    void cancelIsSignedAndNeverResolvesAModel() {
        var response = gateway.cancelRun(7L, "trace_cancel", "session_001", "run_test_001");
        assertEquals("not_active", ((Map<?, ?>) response.getBody()).get("status"));
        verifyNoInteractions(providers);
        Map<String, Object> body = requests.get(0);
        assertEquals("run_test_001", body.get("runId"));
        assertEquals("session_001", body.get("sessionId"));
        assertEquals("trace_cancel", body.get("traceId"));
        assertEquals(7, ((Map<?, ?>) body.get("actor")).get("userId"));
    }

    @Test
    void missingPythonNeverFallsBackToProvider() {
        server.stop(0);
        assertThrows(AgentException.class, () -> gateway.pendingRuns(7L, "trace_001", "session_001"));
    }
    @Test
    void finishedHandoverDoesNotNeedAConfiguredChannel() throws Exception {
        finished = true;
        when(providers.resolveForRequest(anyLong(), any())).thenThrow(new AgentException(
                AgentErrorCode.AGENT_PARAM_40001, "No model configured"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        gateway.resumePythonRaw(7L, "alice", "trace_001", "session_001", "run_test_001", 99L,
                com.nexus.chat.agent.AgentScopeWireFixture.scope(7L, "session_001", "run_test_001"), out);
        verifyNoInteractions(providers);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("event: done"));
    }
    @Test
    void unavailableResumeLifecycleIsTransportNotConfigurationOrTerminal() throws Exception {
        server.stop(0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        gateway.resumePythonRaw(7L, "alice", "trace_001", "session_001", "run_test_001", null,
                com.nexus.chat.agent.AgentScopeWireFixture.scope(7L, "session_001", "run_test_001"), out);
        String wire = out.toString(StandardCharsets.UTF_8);
        Map<?, ?> data = mapper.readValue(wire.split("\n")[1].substring(6), Map.class);
        assertEquals("transport", data.get("scope"));
        assertEquals("run_test_001", data.get("runId"));
        assertTrue(data.get("attemptId") instanceof String);
        assertEquals(1, data.get("sequence"));
        assertFalse(wire.contains("event: done"));
        assertFalse(wire.contains("CONFIG"));
        verifyNoInteractions(providers);
    }
}

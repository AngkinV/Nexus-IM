package com.nexus.chat.service.agent;

import com.nexus.chat.dto.agent.AgentEnums;
import com.nexus.chat.dto.agent.AgentSessionAndChatDtos;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.agent.AgentRunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

class AgentRunServiceTest {
    private AgentWorkbenchTestStore store;
    private AgentRunService service;

    @BeforeEach
    void setUp() {
        store = new AgentWorkbenchTestStore();
        service = store.runService();
    }

    @Test
    void restoresCompletePublicTraceAndCanonicalOutputsAfterRestart() throws Exception {
        AgentSessionAndChatDtos.SessionChatRequest request = request();
        request.setProviderId(42L);
        request.setAttachmentIds(List.of("att_test_001"));
        request.setResolvedAttachments(List.of(Map.of("id", "att_test_001", "fileName", "demo.txt", "content", "source", "sha256", hash("source"))));
        request.getOptions().setCollaborationEnabled(true);
        request.getOptions().setWebSearchEnabled(false);
        service.begin(7L, "as_test", "run_test_001", request);
        store.grant(service, "run_test_001");
        service.record(7L, "as_test", "run_test_001", "meta", Map.of("runId", "run_test_001", "sequence", 1, "at", 0, "provider", "openai", "model", "actual-model"));
        service.record(7L, "as_test", "run_test_001", "reasoning", Map.of("nodeId", "model-1", "agentId", "synth", "text", "原生"));
        service.record(7L, "as_test", "run_test_001", "reasoning", Map.of("nodeId", "model-1", "agentId", "synth", "text", "思考"));
        service.record(7L, "as_test", "run_test_001", "agent_output", Map.of("nodeId", "analysis-1", "agentId", "code", "text", "analysis, not the answer"));
        service.record(7L, "as_test", "run_test_001", "delta", Map.of("text", "答"));
        service.record(7L, "as_test", "run_test_001", "delta", Map.of("text", "案"));
        for (String callId : List.of("call_first", "call_second")) {
            service.record(7L, "as_test", "run_test_001", "tool_call", Map.of("callId", callId, "toolName", "search", "nodeId", callId, "args", Map.of("query", callId)));
        }
        service.record(7L, "as_test", "run_test_001", "tool_result", Map.of("callId", "call_second", "toolName", "search", "status", "success", "latencyMs", 12, "result", Map.of("value", "second")));
        service.record(7L, "as_test", "run_test_001", "tool_result", Map.of("callId", "call_first", "toolName", "search", "status", "success", "latencyMs", 17, "result", Map.of("value", "first")));
        service.record(7L, "as_test", "run_test_001", "sources", Map.of("items", List.of(Map.of("id", "A1", "kind", "attachment", "title", "demo.txt", "excerpt", "source", "attachmentId", "att_test_001", "callId", "call_first"))));
        service.record(7L, "as_test", "run_test_001", "artifact", Map.of("artifact", artifact("art_test_001", "json", "{\"items\":[]}")));
        service.record(7L, "as_test", "run_test_001", "usage", Map.of("inputTokens", 11, "outputTokens", 8, "totalTokens", 19));
        service.record(7L, "as_test", "run_test_001", "done", Map.of("finishReason", "stop", "at", 123));
        service.finish(7L, "as_test", "run_test_001");

        Map<String, Object> run = store.runService().list(7L, "as_test").get(0);
        assertEquals("run_test_001", run.get("runId"));
        assertEquals("Question", run.get("input"));
        assertEquals("done", run.get("status"));
        assertEquals("答案", run.get("answer"));
        assertEquals("原生思考", run.get("reasoning"));
        assertNotNull(run.get("startedAt"));
        assertNotNull(run.get("finishedAt"));
        assertTrue(((Number) run.get("elapsedMs")).longValue() >= 0);
        assertEquals(Map.of("inputTokens", 11, "outputTokens", 8, "totalTokens", 19), run.get("usage"));
        assertEquals(42, ((Map<?, ?>) run.get("providerRef")).get("providerId"));
        assertEquals(true, ((Map<?, ?>) run.get("options")).get("collaborationEnabled"));
        assertEquals("att_test_001", ((Map<?, ?>) ((List<?>) run.get("attachmentRefs")).get(0)).get("id"));
        List<?> trace = (List<?>) run.get("trace");
        assertEquals(14, trace.size());
        List<String> resultIds = trace.stream().map(e -> (Map<?, ?>) e)
                .filter(e -> "tool_result".equals(e.get("name")))
                .map(e -> (String) ((Map<?, ?>) e.get("data")).get("callId")).toList();
        assertEquals(List.of("call_second", "call_first"), resultIds);
        Map<?, ?> savedArtifact = (Map<?, ?>) ((List<?>) run.get("artifacts")).get(0);
        assertEquals("{\"items\":[]}", savedArtifact.get("content"));
        assertEquals(hash("{\"items\":[]}"), savedArtifact.get("sha256"));
        assertEquals("run_test_001", savedArtifact.get("runId"));
        assertEquals("A1", ((Map<?, ?>) ((List<?>) run.get("sources")).get(0)).get("id"));
        String payload = store.savedRuns.get("run_test_001").getPayloadJson();
        assertTrue(payload.contains("analysis, not the answer"));
        assertFalse(payload.contains("\"content\":\"source\""), "attachment content belongs to the workspace, not request credentials/history");
    }

    @Test
    void requiresActualOwnedSessionsAndRejectsReusedRunIdsAcrossSessions() {
        assertThrows(AgentException.class, () -> service.begin(7L, "missing", "run_test_001", request()));
        assertThrows(AgentException.class, () -> service.begin(7L, "as_foreign", "run_test_001", request()));
        service.begin(7L, "as_test", "run_test_001", request());
        assertThrows(AgentException.class, () -> service.begin(7L, "as_test", "run_test_001", request()));
        assertThrows(AgentException.class, () -> service.begin(7L, "as_other", "run_test_001", request()));
        assertThrows(AgentException.class, () -> service.begin(8L, "as_foreign", "run_test_001", request()));
        assertThrows(AgentException.class, () -> service.list(8L, "as_test"));
        assertThrows(AgentException.class, () -> service.record(8L, "as_test", "run_test_001", "delta", Map.of("text", "stolen")));
        assertThrows(AgentException.class, () -> service.record(7L, "as_other", "run_test_001", "delta", Map.of("text", "stolen")));
        assertThrows(AgentException.class, () -> service.finish(7L, "as_other", "run_test_001"));
        assertThrows(AgentException.class, () -> service.deleteSession(8L, "as_test"));
        assertEquals(1, store.savedRuns.size());
        assertTrue(service.list(7L, "as_other").isEmpty());
    }

    @Test
    void rejectsEventRunIdMismatchWithoutWritingAnotherRun() {
        service.begin(7L, "as_test", "run_test_001", request());
        store.grant(service, "run_test_001");
        assertThrows(AgentException.class, () -> service.record(7L, "as_test", "run_test_001", "delta", Map.of("runId", "run_someone_else", "text", "wrong")));
        assertEquals("", service.finish(7L, "as_test", "run_test_001").get("answer"));
    }

    @Test
    void eofWithoutTerminalEventIsInterruptedIncludingAfterRestart() {
        service.begin(7L, "as_test", "run_test_001", request());
        store.grant(service, "run_test_001");
        service.record(7L, "as_test", "run_test_001", "delta", Map.of("text", "partial"));
        assertEquals("interrupted", service.finish(7L, "as_test", "run_test_001").get("status"));
        assertEquals("partial", store.runService().list(7L, "as_test").get(0).get("answer"));
        service.begin(7L, "as_test", "run_abandoned", request());
        store.grant(service, "run_abandoned");
        service.record(7L, "as_test", "run_abandoned", "stage", Map.of("phase", "model", "status", "start"));
        List<Map<String, Object>> recovered = store.runService().list(7L, "as_test");
        assertEquals(List.of("run_test_001", "run_abandoned"), recovered.stream().map(r -> r.get("runId")).toList());
        assertEquals("interrupted", recovered.get(1).get("status"));
    }

    @ParameterizedTest
    @CsvSource({"stop,done", "length,truncated", "max_iterations,truncated", "cancelled,cancelled", "interrupted,interrupted", "error,error"})
    void explicitTerminalReasonsAreNeverUpgradedToSuccess(String reason, String expected) {
        service.begin(7L, "as_test", "run_test_001", request());
        store.grant(service, "run_test_001");
        service.record(7L, "as_test", "run_test_001", "done", Map.of("finishReason", reason));
        assertEquals(expected, service.finish(7L, "as_test", "run_test_001").get("status"));
    }

    @Test
    void globalErrorIsTerminalButRoleLocalErrorDoesNotCloseSynthesis() {
        service.begin(7L, "as_test", "run_error_001", request());
        store.grant(service, "run_error_001");
        service.record(7L, "as_test", "run_error_001", "error", Map.of("code", "UPSTREAM", "message", "failed"));
        assertEquals("error", service.finish(7L, "as_test", "run_error_001").get("status"));
        service.begin(7L, "as_test", "run_test_001", request());
        store.grant(service, "run_test_001");
        service.record(7L, "as_test", "run_test_001", "error", Map.of("agentId", "code", "nodeId", "analysis-1", "code", "UPSTREAM", "message", "role failed"));
        service.record(7L, "as_test", "run_test_001", "delta", Map.of("agentId", "synth", "text", "Explained partial result"));
        service.record(7L, "as_test", "run_test_001", "done", Map.of("finishReason", "stop"));
        assertEquals("done", service.finish(7L, "as_test", "run_test_001").get("status"));
    }

    @Test
    void storesOnlyPublicFieldsAndRemovesNestedCredentialAndHeaderMaterial() {
        service.begin(7L, "as_test", "run_test_001", request());
        store.grant(service, "run_test_001");
        service.record(7L, "as_test", "run_test_001", "meta", Map.of("model", "real", "apiKey", "secret-one", "headers", Map.of("X-Model-Api-Key", "secret-two"), "internalToken", "secret-three"));
        service.record(7L, "as_test", "run_test_001", "tool_call", Map.of("toolName", "lookup", "callId", "call_first", "args", Map.of("query", "safe", "authorization", "secret-four", "nested", Map.of("path", "display only", "api_key", "secret-five"))));
        service.record(7L, "as_test", "run_test_001", "tool_result", Map.of("toolName", "lookup", "callId", "call_first", "result", Map.of("value", "actual", "headers", Map.of("Authorization", "secret-six"))));
        service.record(7L, "as_test", "run_test_001", "unknown_private_event", Map.of("secret", "secret-seven"));
        service.finish(7L, "as_test", "run_test_001");
        String saved = store.savedRuns.get("run_test_001").getPayloadJson();
        assertFalse(saved.contains("secret-"));
        assertFalse(saved.contains("api_key"));
        assertFalse(saved.contains("headers"));
        assertTrue(saved.contains("display only"));
        assertTrue(saved.contains("actual"));
        assertEquals(3, ((List<?>) service.list(7L, "as_test").get(0).get("trace")).size());
    }

    @Test
    void persistsAtStagesAndPeriodicallyWithoutSavingEveryTextChunk() throws Exception {
        service.begin(7L, "as_test", "run_test_001", request());
        store.grant(service, "run_test_001");
        service.record(7L, "as_test", "run_test_001", "delta", Map.of("text", "first"));
        service.record(7L, "as_test", "run_test_001", "stage", Map.of("phase", "model", "status", "start", "nodeId", "model-1"));
        Map<?, ?> checkpoint = store.mapper.readValue(store.savedRuns.get("run_test_001").getPayloadJson(), Map.class);
        assertEquals("first", checkpoint.get("answer"));
        int atStage = store.runSaves.get();
        for (int i = 0; i < 200; i++) service.record(7L, "as_test", "run_test_001", "delta", Map.of("text", "x"));
        assertTrue(store.runSaves.get() > atStage + 2, "long live runs must checkpoint before EOF");
        assertTrue(store.runSaves.get() < atStage + 200, "text persistence must be throttled");
        Map<String, Object> ended = service.finish(7L, "as_test", "run_test_001");
        assertEquals("first" + "x".repeat(200), ended.get("answer"));
        assertEquals(202, ((List<?>) ended.get("trace")).size());
    }

    @Test
    void artifactLookupIsScopedAndReturnsAnIndependentCanonicalSnapshot() throws Exception {
        service.begin(7L, "as_test", "run_test_001", request());
        store.grant(service, "run_test_001");
        service.record(7L, "as_test", "run_test_001", "artifact", Map.of("artifact", artifact("art_test_001", "code", "actual bytes")));
        service.finish(7L, "as_test", "run_test_001");
        Map<String, Object> found = service.findArtifact(7L, "as_test", "art_test_001");
        assertEquals("actual bytes", found.get("content"));
        found.put("content", "client mutation");
        assertEquals("actual bytes", service.findArtifact(7L, "as_test", "art_test_001").get("content"));
        assertThrows(AgentException.class, () -> service.findArtifact(8L, "as_test", "art_test_001"));
        assertThrows(AgentException.class, () -> service.findArtifact(7L, "as_other", "art_test_001"));
        service.deleteSession(7L, "as_test");
        assertTrue(service.list(7L, "as_test").isEmpty());
        assertThrows(AgentException.class, () -> service.findArtifact(7L, "as_test", "art_test_001"));
    }

    @Test
    void persistenceFailureIsNotReportedAsASuccessfulBegin() {
        doThrow(new IllegalStateException("storage unavailable")).when(store.runs).saveAndFlush(any(AgentRunRecord.class));
        assertThrows(RuntimeException.class, () -> service.begin(7L, "as_test", "run_test_001", request()));
        assertTrue(store.savedRuns.isEmpty());
    }

    static AgentSessionAndChatDtos.SessionChatRequest request() {
        AgentSessionAndChatDtos.SessionChatRequest request = new AgentSessionAndChatDtos.SessionChatRequest();
        request.setOperationType(AgentEnums.OperationType.ASSISTANT_CHAT);
        request.setInput("Question");
        request.setOptions(new AgentSessionAndChatDtos.Options());
        return request;
    }

    static Map<String, Object> artifact(String id, String kind, String content) throws Exception {
        return Map.ofEntries(Map.entry("id", id), Map.entry("name", "demo." + (kind.equals("patch") ? "patch" : kind.equals("graph") ? "json" : kind)),
                Map.entry("kind", kind), Map.entry("language", kind), Map.entry("content", content),
                Map.entry("sizeBytes", content.getBytes(StandardCharsets.UTF_8).length), Map.entry("sha256", hash(content)),
                Map.entry("runId", "run_test_001"), Map.entry("agentId", "synth"), Map.entry("nodeId", "model-1"),
                Map.entry("sourceIds", List.of("A1")), Map.entry("callIds", List.of("call_first")));
    }

    static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}

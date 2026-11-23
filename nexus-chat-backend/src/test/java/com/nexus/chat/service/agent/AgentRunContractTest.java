package com.nexus.chat.service.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Real wire identities against snapshotting repositories; no live database or model. */
class AgentRunContractTest {
    private static final String SID = "as_test", RUN = "run_contract_001";
    private final AgentWorkbenchTestStore store = new AgentWorkbenchTestStore();

    private AgentRunService begin() {
        AgentRunService service = store.runService();
        service.begin(7L, SID, RUN, AgentRunServiceTest.request());
        store.grant(service, RUN); // a run's output is only readable under a live grant
        return service;
    }
    private Map<String, Object> frame(String attempt, int sequence, Map<String, Object> fields) {
        Map<String, Object> data = new java.util.LinkedHashMap<>(fields);
        data.put("runId", RUN); data.put("attemptId", attempt); data.put("sequence", sequence);
        data.put("at", sequence * 10); data.put("runtimeContractVersion", 1);
        return data;
    }
    private Map<String, Object> read() { return store.runService().list(7L, SID).get(0); }

    @Test void resumedAttemptKeepsSameNumberedFramesAndDeduplicatesOnlyItsOwnReplay() {
        AgentRunService service = begin();
        service.record(7L, SID, RUN, "meta", frame("attempt_a", 1, Map.of("traceId", "trace_a")));
        service.record(7L, SID, RUN, "delta", frame("attempt_a", 2, Map.of("text", "old")));
        service.record(7L, SID, RUN, "done", frame("attempt_a", 3, Map.of("finishReason", "interrupted", "resumable", true)));
        service.finish(7L, SID, RUN);
        service.resume(7L, SID, RUN, "trace_b");
        service.record(7L, SID, RUN, "meta", frame("attempt_b", 1, Map.of("traceId", "trace_b", "resumed", true)));
        service.record(7L, SID, RUN, "delta", frame("attempt_b", 2, Map.of("text", "new", "replayed", true)));
        service.record(7L, SID, RUN, "delta", frame("attempt_b", 2, Map.of("text", "new", "replayed", true)));
        service.record(7L, SID, RUN, "done", frame("attempt_b", 3, Map.of("finishReason", "stop")));
        service.finish(7L, SID, RUN);
        assertEquals("new", read().get("answer"));
        assertEquals("done", read().get("status"));
        assertEquals(6, ((List<?>) read().get("trace")).size());
    }

    @Test void wireIdentityDoesNotIncludeEventName() {
        AgentRunService service = begin();
        service.record(7L, SID, RUN, "meta", frame("attempt_a", 1, Map.of()));
        service.record(7L, SID, RUN, "delta", frame("attempt_a", 2, Map.of("text", "safe")));
        service.record(7L, SID, RUN, "error", frame("attempt_a", 2, Map.of("scope", "run", "code", "DUPLICATE")));
        service.finish(7L, SID, RUN);
        assertEquals(2, ((List<?>) read().get("trace")).size());
        assertNull(read().get("error"));
    }

    @Test void optionalRoleFailureDoesNotMakeSuccessfulSynthesisFailAfterReload() {
        AgentRunService service = begin();
        service.record(7L, SID, RUN, "meta", frame("attempt_a", 1, Map.of()));
        service.record(7L, SID, RUN, "error", frame("attempt_a", 2,
                Map.of("scope", "agent", "agentId", "doc", "nodeId", "analysis_doc", "code", "UPSTREAM")));
        service.record(7L, SID, RUN, "delta", frame("attempt_a", 3, Map.of("text", "joined")));
        service.record(7L, SID, RUN, "done", frame("attempt_a", 4, Map.of("finishReason", "stop")));
        service.finish(7L, SID, RUN);
        assertEquals("done", read().get("status"));
        assertNull(read().get("error"));
        assertEquals(1, ((List<?>) read().get("diagnostics")).size());
    }

    @Test void toolErrorIsLocalEvenWhenTheToolBelongsToTheAssistant() {
        AgentRunService service = begin();
        service.record(7L, SID, RUN, "error", frame("attempt_a", 1,
                Map.of("scope", "tool", "agentId", "assistant", "callId", "call_a", "code", "TOOL_DENIED")));
        service.record(7L, SID, RUN, "done", frame("attempt_a", 2, Map.of("finishReason", "stop")));
        service.finish(7L, SID, RUN);
        assertEquals("done", read().get("status"));
        assertNull(read().get("error"));
    }

    @Test void runErrorCannotBeUpgradedByAnInconsistentStopFrame() {
        AgentRunService service = begin();
        service.record(7L, SID, RUN, "error", frame("attempt_a", 1, Map.of("scope", "run", "code", "UPSTREAM")));
        service.record(7L, SID, RUN, "done", frame("attempt_a", 2, Map.of("finishReason", "stop")));
        service.finish(7L, SID, RUN);
        assertEquals("error", read().get("status"));
        assertNotNull(read().get("error"));
    }

    @Test void cancelledAndLimitedTerminalReasonsStayDistinct() {
        AgentRunService service = begin();
        service.record(7L, SID, RUN, "done", frame("attempt_a", 1, Map.of("finishReason", "content_filter")));
        service.finish(7L, SID, RUN);
        assertEquals("truncated", read().get("status"));
        assertEquals("content_filter", read().get("finishReason"));
    }

    @Test void lateFramesCannotExtendOrReplaceACompletedAnswer() {
        AgentRunService service = begin();
        service.record(7L, SID, RUN, "meta", frame("attempt_a", 1, Map.of()));
        service.record(7L, SID, RUN, "delta", frame("attempt_a", 2, Map.of("text", "answer")));
        service.record(7L, SID, RUN, "done", frame("attempt_a", 3, Map.of("finishReason", "stop")));
        service.record(7L, SID, RUN, "delta", frame("attempt_a", 4, Map.of("text", "late")));
        service.record(7L, SID, RUN, "error", frame("attempt_a", 5, Map.of("scope", "transport", "code", "EOF")));
        service.finish(7L, SID, RUN);
        assertEquals("answer", read().get("answer"));
        assertEquals("done", read().get("status"));
        assertNull(read().get("error"));
    }

    @Test void cumulativeCallCountsAndUsageAvailabilitySurviveReload() {
        AgentRunService service = begin();
        service.record(7L, SID, RUN, "usage", frame("attempt_a", 1,
                Map.of("modelCalls", 4, "toolCallCount", 2, "reusedToolCallCount", 1,
                        "usageReported", false, "usageComplete", false, "usageScope", "run")));
        service.record(7L, SID, RUN, "done", frame("attempt_a", 2, Map.of("finishReason", "stop")));
        service.finish(7L, SID, RUN);
        assertEquals(4, read().get("modelCalls"));
        assertEquals(2, read().get("toolCallCount"));
        assertEquals(false, read().get("usageReported"));
    }

    @Test void secretHeaderNamesAndNestedListsAreRedactedAtEveryDepth() {
        Map<String, Object> cleaned = AgentRunService.sanitize(Map.of("X-Model-Api-Key", "fixture-hidden",
                "nested", List.of(List.of(Map.of("api_key", "fixture-hidden", "value", "safe")))));
        assertFalse(cleaned.containsKey("X-Model-Api-Key"));
        Map<?, ?> nested = (Map<?, ?>) ((List<?>) ((List<?>) cleaned.get("nested")).get(0)).get(0);
        assertFalse(nested.containsKey("api_key"));
        assertEquals("safe", nested.get("value"));
    }

    @Test void sameProviderCallIdInDifferentAgentsDoesNotMergeTheirResults() {
        AgentRunService service = begin();
        service.record(7L, SID, RUN, "tool_call", frame("attempt_a", 1,
                Map.of("agentId", "doc", "nodeId", "doc_tool", "callId", "call_a", "toolName", "get_chat_profile")));
        service.record(7L, SID, RUN, "tool_call", frame("attempt_a", 2,
                Map.of("agentId", "arch", "nodeId", "arch_tool", "callId", "call_a", "toolName", "get_chat_profile")));
        service.record(7L, SID, RUN, "tool_result", frame("attempt_a", 3,
                Map.of("agentId", "doc", "nodeId", "doc_tool", "callId", "call_a", "status", "SUCCESS")));
        service.finish(7L, SID, RUN);
        List<?> calls = (List<?>) read().get("toolCalls");
        assertEquals(2, calls.size());
        assertEquals("doc", ((Map<?, ?>) calls.get(0)).get("agentId"));
        assertEquals("SUCCESS", ((Map<?, ?>) calls.get(0)).get("status"));
        assertEquals("arch", ((Map<?, ?>) calls.get(1)).get("agentId"));
        assertEquals("running", ((Map<?, ?>) calls.get(1)).get("status"));
    }

    /** An attempt that ended but left its checkpoint behind, then a resumed attempt that succeeds. */
    private void resumedAfter(String finishReason, boolean runError) {
        AgentRunService service = store.runService();
        String first = service.beginWriter(7L, SID, RUN, AgentRunServiceTest.request()); store.grant(service, RUN);
        service.record(7L, SID, RUN, first, "meta", frame("attempt_a", 1, Map.of("traceId", "trace_a")));
        service.record(7L, SID, RUN, first, "delta", frame("attempt_a", 2, Map.of("text", "半句")));
        if (runError) {
            service.record(7L, SID, RUN, first, "error", frame("attempt_a", 3, Map.of("scope", "run", "code", "AGENT_MODEL_50202")));
        }
        service.record(7L, SID, RUN, first, "done", frame("attempt_a", 4, Map.of("finishReason", finishReason, "resumable", true)));
        service.finish(7L, SID, RUN, first);
        assertEquals(runError ? "error" : finishReason, read().get("status"));
        assertEquals(true, read().get("resumable"));

        String second = service.resumeWriter(7L, SID, RUN, "trace_b");
        assertNotNull(second, "a kept checkpoint is continued, so its new attempt must be recorded");
        service.record(7L, SID, RUN, second, "meta", frame("attempt_b", 1, Map.of("traceId", "trace_b", "resumed", true)));
        service.record(7L, SID, RUN, second, "delta", frame("attempt_b", 2, Map.of("text", "完整回答", "replayed", true)));
        service.record(7L, SID, RUN, second, "delta", frame("attempt_b", 2, Map.of("text", "完整回答", "replayed", true)));
        service.record(7L, SID, RUN, second, "usage", frame("attempt_b", 3, Map.of("modelCalls", 2, "usageScope", "run")));
        service.record(7L, SID, RUN, second, "done", frame("attempt_b", 4, Map.of("finishReason", "stop", "resumable", false)));
        service.finish(7L, SID, RUN, second);

        Map<String, Object> restored = read();
        assertEquals("done", restored.get("status"));
        assertEquals("stop", restored.get("finishReason"));
        assertEquals("完整回答", restored.get("answer"));
        assertNull(restored.get("error"), "the first attempt's failure stays in its trace, not on the run");
        assertEquals("attempt_b", restored.get("attemptId"));
        assertEquals(2, restored.get("modelCalls"));
        assertEquals(false, restored.get("resumable"));
        List<?> trace = (List<?>) restored.get("trace");
        // Both attempts keep their own frames 1..4; the replayed frame of the second is stored once.
        assertEquals((runError ? 4 : 3) + 4, trace.size());
        List<?> firstFrames = trace.stream().map(item -> (Map<?, ?>) item)
                .filter(item -> "meta".equals(item.get("name")))
                .map(item -> List.of(((Map<?, ?>) item.get("data")).get("attemptId"), ((Map<?, ?>) item.get("data")).get("sequence")))
                .toList();
        assertEquals(List.of(List.of("attempt_a", 1), List.of("attempt_b", 1)), firstFrames);
        // The outcome is sealed now: asking again hands it back and reopens nothing.
        assertNull(service.resumeWriter(7L, SID, RUN, "trace_c"));
        assertEquals(restored, read());
    }

    @Test void failedAttemptThatKeptItsCheckpointRecordsTheResumedAttemptAndDropsTheOldRunError() {
        resumedAfter("error", true);
    }

    @Test void cancelledAttemptThatKeptItsCheckpointRecordsTheResumedAttempt() {
        resumedAfter("cancelled", false);
    }

    @Test void failedAttemptWithoutACheckpointStaysSealed() {
        AgentRunService service = store.runService();
        String first = service.beginWriter(7L, SID, RUN, AgentRunServiceTest.request()); store.grant(service, RUN);
        service.record(7L, SID, RUN, first, "error", frame("attempt_a", 1, Map.of("scope", "run", "code", "AGENT_MODEL_50202")));
        service.record(7L, SID, RUN, first, "done", frame("attempt_a", 2, Map.of("finishReason", "error", "resumable", false)));
        service.finish(7L, SID, RUN, first);
        Map<String, Object> sealed = read();
        assertNull(service.resumeWriter(7L, SID, RUN, "trace_b"));
        assertEquals(sealed, read());
    }

    @Test void outcomeTheRecordMissedIsTakenFromTheHandoverOnceAndRepeatsChangeNothing() {
        AgentRunService service = store.runService();
        String first = service.beginWriter(7L, SID, RUN, AgentRunServiceTest.request()); store.grant(service, RUN);
        service.record(7L, SID, RUN, first, "meta", frame("attempt_a", 1, Map.of("traceId", "trace_a")));
        service.record(7L, SID, RUN, first, "delta", frame("attempt_a", 2, Map.of("text", "完整")));
        // The connection dropped before usage and done arrived; the agent service finished the run.
        service.finish(7L, SID, RUN, first);
        assertEquals("interrupted", read().get("status"));

        for (String trace : List.of("trace_b", "trace_c")) {
            String lease = service.resumeWriter(7L, SID, RUN, trace);
            Map<String, Object> handover = Map.of("traceId", trace, "resumed", true,
                    "deliveryMode", "handover", "producerAttemptId", "attempt_a");
            service.record(7L, SID, RUN, lease, "meta", frame("handover-" + RUN, 1, handover));
            service.record(7L, SID, RUN, lease, "delta", frame("handover-" + RUN, 2, Map.of("text", "完整回答", "replayed", true)));
            service.record(7L, SID, RUN, lease, "usage", frame("handover-" + RUN, 3,
                    Map.of("modelCalls", 1, "toolCallCount", 2, "reusedToolCallCount", 0, "usageScope", "run")));
            service.record(7L, SID, RUN, lease, "done", frame("handover-" + RUN, 4, Map.of("finishReason", "stop", "resumable", false)));
            service.finish(7L, SID, RUN, lease);
        }
        Map<String, Object> restored = read();
        assertEquals("done", restored.get("status"));
        assertEquals("完整回答", restored.get("answer"));
        assertEquals(1, restored.get("modelCalls"));
        assertEquals(2, restored.get("toolCallCount"));
        // The producing attempt's evidence is kept; the handover was stored exactly once.
        assertEquals(2 + 4, ((List<?>) restored.get("trace")).size());
    }

    @Test void activeElapsedDoesNotIncludeAnIdleGapBetweenAttempts() {
        AgentRunService service = begin();
        service.record(7L, SID, RUN, "meta", frame("attempt_a", 1, Map.of()));
        service.record(7L, SID, RUN, "done", frame("attempt_a", 2,
                Map.of("finishReason", "interrupted", "resumable", true)));
        service.finish(7L, SID, RUN);
        store.savedRuns.get(RUN).setStartedAt(java.time.Instant.now().minusSeconds(10800));
        service.resume(7L, SID, RUN, "resume_trace");
        service.record(7L, SID, RUN, "meta", frame("attempt_b", 1, Map.of("resumed", true)));
        service.record(7L, SID, RUN, "done", frame("attempt_b", 2, Map.of("finishReason", "stop")));
        service.finish(7L, SID, RUN);
        assertEquals(40L, read().get("elapsedMs"));
    }
}

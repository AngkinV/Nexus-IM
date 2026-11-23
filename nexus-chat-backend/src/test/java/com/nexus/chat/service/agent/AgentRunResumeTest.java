package com.nexus.chat.service.agent;

import com.nexus.chat.exception.agent.AgentException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunResumeTest {
    private static final String SID = "as_test", RUN = "run_test_001";
    private final AgentWorkbenchTestStore store = new AgentWorkbenchTestStore();

    private void interrupt() {
        AgentRunService service = store.runService();
        service.begin(7L, SID, RUN, AgentRunServiceTest.request());
        store.grant(service, RUN);
        service.record(7L, SID, RUN, "reasoning", Map.of("text", "old reasoning"));
        service.record(7L, SID, RUN, "delta", Map.of("text", "partial"));
        service.finish(7L, SID, RUN);
    }

    @Test
    void confirmedReplayReplacesPartialOutputOnceWithoutReplacingTheRunOrItsTrace() {
        interrupt();
        Long rowId = store.savedRuns.get(RUN).getId();
        AgentRunService service = store.runService();
        service.resume(7L, SID, RUN, "tr_resume");
        assertEquals("partial", service.list(7L, SID).get(0).get("answer"));
        assertThrows(AgentException.class, () -> service.resume(7L, SID, RUN, "tr_race"));
        service.record(7L, SID, RUN, "meta", Map.of("runId", RUN, "resumed", true, "sequence", 1));
        service.record(7L, SID, RUN, "delta", Map.of("text", "partial", "sequence", 2, "replayed", true));
        service.record(7L, SID, RUN, "delta", Map.of("text", "partial", "sequence", 2, "replayed", true));
        service.record(7L, SID, RUN, "reasoning", Map.of("text", "new reasoning"));
        service.record(7L, SID, RUN, "delta", Map.of("text", " answer"));
        service.record(7L, SID, RUN, "done", Map.of("finishReason", "stop"));
        service.finish(7L, SID, RUN);

        List<Map<String, Object>> runs = store.runService().list(7L, SID);
        assertEquals(1, runs.size());
        assertEquals(rowId, store.savedRuns.get(RUN).getId());
        Map<String, Object> restored = runs.get(0);
        assertEquals("partial answer", restored.get("answer"));
        assertEquals("new reasoning", restored.get("reasoning"));
        assertEquals("tr_resume", restored.get("traceId"));
        assertEquals("done", restored.get("status"));
        List<?> trace = (List<?>) restored.get("trace");
        assertEquals(7, trace.size());
        assertEquals("old reasoning", ((Map<?, ?>) ((Map<?, ?>) trace.get(0)).get("data")).get("text"));
    }

    @Test
    void aFinishedRunHandsItsResultBackWithoutBecomingActiveAgain() {
        AgentRunService service = store.runService();
        service.begin(7L, SID, RUN, AgentRunServiceTest.request());
        store.grant(service, RUN);
        service.record(7L, SID, RUN, "delta", Map.of("text", "complete answer"));
        service.record(7L, SID, RUN, "done", Map.of("finishReason", "stop"));
        service.finish(7L, SID, RUN);

        Map<String, Object> again = service.resume(7L, SID, RUN, "tr_after_done");

        assertEquals("done", again.get("status"));
        assertEquals("complete answer", again.get("answer"));
        // The stored outcome is unchanged and the run is not reopened.
        Map<String, Object> restored = service.list(7L, SID).get(0);
        assertEquals("done", restored.get("status"));
        assertEquals("complete answer", restored.get("answer"));
        assertEquals(1, store.savedRuns.size());
    }

    @Test
    void deniedOrUnconfirmedResumeCannotErasePartialOutput() {
        interrupt();
        AgentRunService service = store.runService();
        assertThrows(AgentException.class, () -> service.resume(8L, SID, RUN, "tr_foreign"));
        assertThrows(AgentException.class, () -> service.resume(7L, "as_other", RUN, "tr_wrong_session"));
        service.resume(7L, SID, RUN, "tr_attempt");
        service.record(7L, SID, RUN, "error", Map.of("code", "UPSTREAM", "message", "checkpoint unavailable"));
        Map<String, Object> run = service.finish(7L, SID, RUN);
        assertEquals("error", run.get("status"));
        assertEquals("partial", run.get("answer"));
        assertEquals("old reasoning", run.get("reasoning"));
        assertEquals(1, store.savedRuns.size());
    }
}

package com.nexus.chat.service.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentSseReplayCacheTest {

    @Test
    void replayAfterReturnsOnlyNewerEvents() {
        AgentSseReplayCache cache = new AgentSseReplayCache();
        String key = "u1:s1:trace1";

        cache.append(key, 1L, "meta", "{\"a\":1}");
        cache.append(key, 2L, "delta", "{\"text\":\"hi\"}");
        cache.append(key, 3L, "delta", "{\"text\":\"there\"}");
        cache.append(key, 4L, "done", "{\"finishReason\":\"stop\"}");

        List<AgentSseReplayCache.CachedEvent> after2 = cache.replayAfter(key, 2L);
        assertEquals(2, after2.size());
        assertEquals(3L, after2.get(0).id());
        assertEquals(4L, after2.get(1).id());
        assertEquals("delta", after2.get(0).event());
    }

    @Test
    void replayAfterReturnsEmptyForUnknownStream() {
        AgentSseReplayCache cache = new AgentSseReplayCache();
        assertTrue(cache.replayAfter("nope", 0L).isEmpty());
        assertFalse(cache.hasStream("nope"));
    }

    @Test
    void hasStreamTrueAfterAppend() {
        AgentSseReplayCache cache = new AgentSseReplayCache();
        cache.append("k", 1L, "meta", "{}");
        assertTrue(cache.hasStream("k"));
    }
}

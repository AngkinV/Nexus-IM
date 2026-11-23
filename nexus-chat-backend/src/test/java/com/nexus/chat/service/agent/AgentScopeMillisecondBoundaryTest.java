package com.nexus.chat.service.agent;

import com.nexus.chat.service.agent.scope.AgentScopeService;
import com.nexus.chat.service.agent.scope.ScopeDtos.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression source only. Execution belongs to the original T1 session. */
class AgentScopeMillisecondBoundaryTest {
    @Test void finerBoundaryCannotAliasAnExistingMemoryPartition() {
        for (String value : List.of("2026-11-22T00:00:00.0001Z", "2026-11-22T00:00:00.000001Z", "2026-11-22T00:00:00.000000001Z")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TimeRange(Instant.parse(value), null, "UTC"));
        }
    }

    @Test void exactMillisecondsAndDisplayZonesHaveTheSameAuthoritativeMeaning() {
        TimeRange first = new TimeRange(Instant.parse("2026-11-22T00:00:00.123Z"), null, "UTC");
        TimeRange zeroTail = new TimeRange(Instant.parse("2026-11-22T00:00:00.123000000Z"), null, "Asia/Shanghai");
        TimeRange later = new TimeRange(Instant.parse("2026-11-22T00:00:00.124Z"), null, "UTC");
        String firstKey = AgentScopeService.key(1L, "as_precision", 1, List.of(101L), List.of(), first);
        assertEquals(firstKey, AgentScopeService.key(1L, "as_precision", 1, List.of(101L), List.of(), zeroTail));
        assertNotEquals(firstKey, AgentScopeService.key(1L, "as_precision", 1, List.of(101L), List.of(), later));
        assertEquals(first.fromLocal(ZoneId.of("Asia/Shanghai")), zeroTail.fromLocal(ZoneId.of("Asia/Shanghai")));
    }
}

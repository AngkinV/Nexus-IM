package com.nexus.chat.service.agent.facts;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/** Contract regressions only. Execution is scheduled by original T1. */
class MessageWindowTest {
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void offsetBoundsConvertToLegacyStorageZoneNotDisplayZone() {
        MessageWindow window = MessageWindow.parse("2026-11-22T00:00:00+08:00", "2026-11-23T00:00:00+08:00", "Asia/Shanghai", UTC);
        assertEquals(LocalDateTime.of(2026, 11, 21, 16, 0), window.storedStart());
        assertEquals(LocalDateTime.of(2026, 11, 22, 16, 0), window.storedEnd());
        assertEquals("[start,end)", window.metadata().get("semantics"));
        assertEquals("2026-11-22T00:00:00+08:00", window.timestamp(window.storedStart()));
    }

    @Test
    void rejectsNaiveUnpairedEmptyOrOverPreciseBounds() {
        for (String start : new String[]{"2026-11-22T00:00:00", "2026-11-22T00:00Z", "2026-11-22T00:00:00.0000001Z"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> MessageWindow.parse(start, "2026-11-23T00:00:00Z", "UTC", UTC));
        }
        assertThrows(IllegalArgumentException.class,
                () -> MessageWindow.parse("2026-11-22T00:00:00Z", null, "UTC", UTC));
        assertThrows(IllegalArgumentException.class,
                () -> MessageWindow.parse("2026-11-22T00:00:00Z", "2026-11-22T00:00:00Z", "UTC", UTC));
        assertThrows(IllegalArgumentException.class,
                () -> MessageWindow.parse(null, null, "+08:00", UTC));
    }

    @Test
    void rejectsAmbiguousLegacyDstWindowAndRowsInsteadOfInventingAnOffset() {
        ZoneId ny = ZoneId.of("America/New_York");
        assertThrows(IllegalArgumentException.class,
                () -> MessageWindow.parse("2026-11-01T04:00:00Z", "2026-11-02T05:00:00Z", "America/New_York", ny));
        MessageWindow legacy = MessageWindow.parse(null, null, "America/New_York", ny);
        assertThrows(IllegalArgumentException.class, () -> legacy.timestamp(LocalDateTime.of(2026, 11, 1, 1, 30)));
        assertThrows(IllegalArgumentException.class, () -> legacy.timestamp(LocalDateTime.of(2026, 3, 8, 2, 30)));
    }

    @Test
    void utcStorageSupportsDisplayCalendarDstWithoutLosingInstantEvidence() {
        MessageWindow window = MessageWindow.parse("2026-11-01T04:00:00Z", "2026-11-02T05:00:00Z", "America/New_York", UTC);
        assertEquals("2026-11-01T01:00:00-05:00", window.timestamp(LocalDateTime.of(2026, 11, 1, 6, 0)));
        assertEquals("2026-11-01T01:00:00-04:00", window.timestamp(LocalDateTime.of(2026, 11, 1, 5, 0)));
    }

    @Test
    void microsecondsAreExactAndNanosecondsAreNotSilentlyDropped() {
        MessageWindow window = MessageWindow.parse("2026-11-22T00:00:00.000001Z", "2026-11-23T00:00:00Z", "UTC", UTC);
        assertEquals(1000, window.storedStart().getNano());
        assertThrows(IllegalArgumentException.class, () -> window.timestamp(window.storedStart().withNano(1)));
    }
}

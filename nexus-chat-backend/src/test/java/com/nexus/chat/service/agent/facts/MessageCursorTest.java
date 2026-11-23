package com.nexus.chat.service.agent.facts;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class MessageCursorTest {
    private final MessageWindow window = MessageWindow.parse("2026-11-22T00:00:00Z", "2026-11-23T00:00:00Z", "Asia/Shanghai", ZoneId.of("UTC"));
    private final LocalDateTime position = LocalDateTime.of(2026, 11, 22, 10, 0);

    @Test
    void canonicalRoundTripBindsTieBreakIdAndWatermark() {
        MessageCursor cursor = new MessageCursor(10, window, 8, position, 4);
        assertEquals(cursor, MessageCursor.decode(cursor.encode(), 10, window, 8L, 9));
    }

    @Test
    void mismatchedChatWindowZoneAndWatermarkAreRejected() {
        String token = new MessageCursor(10, window, 8, position, 4).encode();
        assertThrows(IllegalArgumentException.class, () -> MessageCursor.decode(token, 20, window, 8L, 9));
        MessageWindow narrower = MessageWindow.parse("2026-11-22T01:00:00Z", "2026-11-23T00:00:00Z", "Asia/Shanghai", ZoneId.of("UTC"));
        assertThrows(IllegalArgumentException.class, () -> MessageCursor.decode(token, 10, narrower, 8L, 9));
        MessageWindow differentZone = MessageWindow.parse("2026-11-22T00:00:00Z", "2026-11-23T00:00:00Z", "UTC", ZoneId.of("UTC"));
        assertThrows(IllegalArgumentException.class, () -> MessageCursor.decode(token, 10, differentZone, 8L, 9));
        assertThrows(IllegalArgumentException.class, () -> MessageCursor.decode(token, 10, window, 7L, 9));
    }

    @Test
    void malformedOversizedAndFutureWatermarksAreRejected() {
        for (String token : new String[]{"!", "v2", "x".repeat(2049)}) {
            assertThrows(IllegalArgumentException.class, () -> MessageCursor.decode(token, 10, window, null, 9));
        }
        String token = new MessageCursor(10, window, 8, position, 4).encode();
        assertThrows(IllegalArgumentException.class, () -> MessageCursor.decode(token, 10, window, null, 7));
    }

    @Test
    void endBoundaryAndIdsBeyondWatermarkCannotBecomeCursorPositions() {
        assertThrows(IllegalArgumentException.class, () -> new MessageCursor(10, window, 8, window.storedEnd(), 4));
        assertThrows(IllegalArgumentException.class, () -> new MessageCursor(10, window, 8, position, 9));
    }
}

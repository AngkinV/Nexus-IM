package com.nexus.chat.service.agent.facts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.model.Message;
import com.nexus.chat.repository.agent.AgentMessageQueryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic rows prove the packer contract, not a real DB/MCP chain. */
class AgentMessageReaderTest {
    private static final String START = "2026-11-22T00:00:00Z";
    private static final String END = "2026-11-23T00:00:00Z";
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 22, 10, 0);

    private Message message(long id, String content) {
        Message value = new Message();
        value.setId(id);
        value.setChatId(10L);
        value.setSenderId(101L);
        value.setContent(content);
        value.setCreatedAt(TIME);
        value.setIsRecalled(false);
        return value;
    }

    private AgentMessageReader reader(AgentMessageQueryRepository repository) {
        return new AgentMessageReader(repository, new ObjectMapper(), "UTC", 65536);
    }

    private Map<String, Object> read(AgentMessageReader reader, Integer cap, String cursor, Long watermark) {
        return reader.read(10, 2, START, END, "Asia/Shanghai", cursor, watermark, cap,
                ids -> Map.of(101L, "unit sender"));
    }

    private int number(Map<String, Object> result, String field) {
        return ((Number) result.get(field)).intValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void realMaterializedRowsReturnedRowsAndProbeHaveDifferentCounts() throws Exception {
        AgentMessageQueryRepository repository = mock(AgentMessageQueryRepository.class);
        when(repository.highWatermark(10)).thenReturn(3L);
        when(repository.read(eq(10L), any(), eq(3L), isNull(), eq(3)))
                .thenReturn(List.of(message(3, "unit 3"), message(2, "unit 2"), message(1, "unit 1")));
        Map<String, Object> result = read(reader(repository), null, null, null);
        assertEquals(3, number(result, "readCount"));
        assertEquals(2, number(result, "returnedCount"));
        assertEquals(1, number(result, "truncatedCount"));
        assertEquals(1, number(result, "probeCount"));
        assertEquals(true, result.get("hasMore"));
        assertFalse(result.containsKey("totalCount"));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals(3L, messages.get(0).get("messageId"));
        assertEquals(10L, messages.get(0).get("chatId"));
        assertEquals("2026-11-22T18:00:00+08:00", messages.get(0).get("timestamp"));
        assertEquals(new ObjectMapper().writeValueAsBytes(result).length, number(result, "resultBytes"));
    }

    @Test
    void continuationKeepsOriginalWatermarkEvenWhenHigherIdsArrive() {
        AgentMessageQueryRepository repository = mock(AgentMessageQueryRepository.class);
        when(repository.highWatermark(10)).thenReturn(3L, 4L);
        when(repository.read(eq(10L), any(), eq(3L), nullable(MessageCursor.class), eq(3)))
                .thenAnswer(call -> call.getArgument(3) == null
                        ? List.of(message(3, "unit 3"), message(2, "unit 2"), message(1, "unit 1"))
                        : List.of(message(1, "unit 1")));
        AgentMessageReader reader = reader(repository);
        Map<String, Object> first = read(reader, null, null, null);
        Map<String, Object> second = read(reader, null, (String) first.get("nextCursor"), null);
        assertEquals(3, number(second, "watermark"));
        assertEquals(1, number(second, "readCount"));
        assertEquals(1, number(second, "returnedCount"));
        assertEquals(false, second.get("hasMore"));
        assertNull(second.get("nextCursor"));
        ArgumentCaptor<MessageCursor> position = ArgumentCaptor.forClass(MessageCursor.class);
        verify(repository, times(2)).read(eq(10L), any(), eq(3L), position.capture(), eq(3));
        assertEquals(2, position.getAllValues().get(1).messageId());
        assertEquals(TIME, position.getAllValues().get(1).createdAt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void byteCapClipsBodyButNeverSkipsBudgetOmittedMessages() throws Exception {
        AgentMessageQueryRepository repository = mock(AgentMessageQueryRepository.class);
        String body = "中😀".repeat(3000);
        when(repository.highWatermark(10)).thenReturn(3L);
        when(repository.read(eq(10L), any(), eq(3L), isNull(), eq(3)))
                .thenReturn(List.of(message(3, body), message(2, "unit 2"), message(1, "unit 1")));
        Map<String, Object> result = read(reader(repository), 1200, null, null);
        byte[] bytes = new ObjectMapper().writeValueAsBytes(result);
        assertTrue(bytes.length <= 1200);
        assertEquals(bytes.length, number(result, "resultBytes"));
        assertEquals(3, number(result, "readCount"));
        assertEquals(1, number(result, "returnedCount"));
        assertEquals(2, number(result, "truncatedCount"));
        assertEquals(1, number(result, "contentTruncatedCount"));
        assertEquals(true, result.get("hasMore"));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        Map<String, Object> first = messages.get(0);
        assertEquals(body.getBytes(StandardCharsets.UTF_8).length, ((Number) first.get("originalContentBytes")).intValue());
        assertEquals(true, first.get("contentTruncated"));
        String shortened = (String) first.get("content");
        assertTrue(shortened.length() < body.length());
        assertFalse(shortened.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF));
        MessageWindow window = MessageWindow.parse(START, END, "Asia/Shanghai", ZoneId.of("UTC"));
        MessageCursor cursor = MessageCursor.decode((String) result.get("nextCursor"), 10, window, 3L, 3);
        assertEquals(3, cursor.messageId()); // cursor does NOT jump over id 2
    }

    @Test
    void emptyPageIsExplicitAndDoesNotLookUpFakeSenders() {
        AgentMessageQueryRepository repository = mock(AgentMessageQueryRepository.class);
        when(repository.highWatermark(10)).thenReturn(0L);
        when(repository.read(eq(10L), any(), eq(0L), isNull(), eq(3))).thenReturn(List.of());
        AtomicInteger senderCalls = new AtomicInteger();
        Map<String, Object> result = reader(repository).read(10, 2, START, END, "Asia/Shanghai", null, null, null,
                ids -> { senderCalls.incrementAndGet(); return Map.of(); });
        assertEquals(0, senderCalls.get());
        assertEquals(0, number(result, "readCount"));
        assertEquals(0, number(result, "returnedCount"));
        assertEquals(0, number(result, "watermark"));
        assertEquals(false, result.get("hasMore"));
    }

    @Test
    void budgetCannotBeExpandedAndInvalidLimitExecutesNoQuery() {
        AgentMessageQueryRepository repository = mock(AgentMessageQueryRepository.class);
        AgentMessageReader reader = reader(repository);
        assertThrows(IllegalArgumentException.class, () -> reader.read(10, 201, START, END, "UTC", null, null, null, ids -> Map.of()));
        assertThrows(IllegalArgumentException.class, () -> read(reader, 65537, null, null));
        verifyNoInteractions(repository);
    }

    @Test
    void futureWatermarkExecutesNoMessageRead() {
        AgentMessageQueryRepository repository = mock(AgentMessageQueryRepository.class);
        when(repository.highWatermark(10)).thenReturn(3L);
        assertThrows(IllegalArgumentException.class, () -> read(reader(repository), null, null, 4L));
        verify(repository, never()).read(anyLong(), any(), anyLong(), any(), anyInt());
    }

    @Test
    void minimumEvidenceCannotProduceAnEmptyHasMoreLoop() {
        AgentMessageQueryRepository repository = mock(AgentMessageQueryRepository.class);
        when(repository.highWatermark(10)).thenReturn(1L);
        when(repository.read(eq(10L), any(), eq(1L), isNull(), eq(3))).thenReturn(List.of(message(1, "unit")));
        assertThrows(IllegalArgumentException.class, () -> read(reader(repository), 1, null, null));
    }

    @Test
    void faultyRepositoryCannotReturnTheExclusiveEndBoundaryOrOtherChat() {
        AgentMessageQueryRepository repository = mock(AgentMessageQueryRepository.class);
        when(repository.highWatermark(10)).thenReturn(1L);
        Message outside = message(1, "unit");
        outside.setCreatedAt(LocalDateTime.of(2026, 11, 23, 0, 0));
        when(repository.read(eq(10L), any(), eq(1L), isNull(), eq(3))).thenReturn(List.of(outside));
        assertThrows(IllegalStateException.class, () -> read(reader(repository), null, null, null));
        outside.setCreatedAt(TIME);
        outside.setChatId(20L);
        assertThrows(IllegalStateException.class, () -> read(reader(repository), null, null, null));
    }

    @Test
    void unknownJdbcProjectionZoneRefusesReadsBeforeAnyQuery() {
        AgentMessageQueryRepository repository = mock(AgentMessageQueryRepository.class);
        AgentMessageReader reader = new AgentMessageReader(repository, new ObjectMapper(), "", 65536);
        assertThrows(IllegalStateException.class, () -> read(reader, null, null, null));
        verifyNoInteractions(repository);
    }
}

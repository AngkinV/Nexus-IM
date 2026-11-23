package com.nexus.chat.repository.agent;

import com.nexus.chat.model.Message;
import com.nexus.chat.service.agent.facts.MessageCursor;
import com.nexus.chat.service.agent.facts.MessageWindow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifies actual query bindings, not database execution. T1 runs DB regressions. */
class AgentMessageQueryRepositoryTest {
    @Test
    @SuppressWarnings("unchecked")
    void bindsClosedOpenWindowWatermarkAndStrictTieBreakWithBoundedRows() {
        EntityManager manager = mock(EntityManager.class);
        TypedQuery<Message> query = mock(TypedQuery.class);
        when(manager.createQuery(anyString(), eq(Message.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        AgentMessageQueryRepository repository = new AgentMessageQueryRepository();
        ReflectionTestUtils.setField(repository, "entityManager", manager);
        MessageWindow window = MessageWindow.parse("2026-11-22T00:00:00+08:00", "2026-11-23T00:00:00+08:00", "Asia/Shanghai", ZoneId.of("UTC"));
        MessageCursor cursor = new MessageCursor(10, window, 8, LocalDateTime.of(2026, 11, 22, 10, 0), 4);
        repository.read(10, window, 8, cursor, 3);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(manager).createQuery(sql.capture(), eq(Message.class));
        assertTrue(sql.getValue().contains("m.createdAt >= :start and m.createdAt < :end"));
        assertTrue(sql.getValue().contains("m.id <= :watermark"));
        assertTrue(sql.getValue().contains("m.createdAt < :position or (m.createdAt = :position and m.id < :positionId)"));
        assertTrue(sql.getValue().contains("m.isRecalled = false or m.isRecalled is null"));
        assertTrue(sql.getValue().endsWith("order by m.createdAt desc, m.id desc"));
        assertFalse(sql.getValue().toLowerCase().contains("count("));
        verify(query).setParameter("chat", 10L);
        verify(query).setParameter("watermark", 8L);
        verify(query).setParameter("start", window.storedStart());
        verify(query).setParameter("end", window.storedEnd());
        verify(query).setParameter("position", cursor.createdAt());
        verify(query).setParameter("positionId", 4L);
        verify(query).setMaxResults(3);
        verify(query, never()).setFirstResult(anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void highWatermarkIsARealMaxIdNotAChatMessageCount() {
        EntityManager manager = mock(EntityManager.class);
        TypedQuery<Long> high = mock(TypedQuery.class);
        when(manager.createQuery("select max(m.id) from Message m where m.chatId = :chat", Long.class)).thenReturn(high);
        when(high.setParameter("chat", 10L)).thenReturn(high);
        when(high.getSingleResult()).thenReturn(null, 8L);
        AgentMessageQueryRepository repository = new AgentMessageQueryRepository();
        ReflectionTestUtils.setField(repository, "entityManager", manager);
        assertEquals(0, repository.highWatermark(10));
        assertEquals(8, repository.highWatermark(10));
        verify(high, times(2)).setParameter("chat", 10L);
    }

    @Test
    void invalidBoundsAndMismatchedCursorDoNotExecuteAnyQuery() {
        EntityManager manager = mock(EntityManager.class);
        AgentMessageQueryRepository repository = new AgentMessageQueryRepository();
        ReflectionTestUtils.setField(repository, "entityManager", manager);
        MessageWindow window = MessageWindow.parse(null, null, "UTC", ZoneId.of("UTC"));
        assertThrows(IllegalArgumentException.class, () -> repository.read(10, window, 8, null, 202));
        assertThrows(IllegalArgumentException.class, () -> repository.highWatermark(0));
        MessageCursor cursor = new MessageCursor(20, window, 8, LocalDateTime.of(2026, 11, 22, 10, 0), 4);
        assertThrows(IllegalArgumentException.class, () -> repository.read(10, window, 8, cursor, 3));
        verifyNoInteractions(manager);
    }
}

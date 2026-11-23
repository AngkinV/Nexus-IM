package com.nexus.chat.repository.agent;

import com.nexus.chat.model.Message;
import com.nexus.chat.service.agent.facts.MessageCursor;
import com.nexus.chat.service.agent.facts.MessageWindow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Bounded keyset query only: no Page, offset or chat-wide count. Caller owns scope gate. */
@Repository
public class AgentMessageQueryRepository {
    @PersistenceContext private EntityManager entityManager;

    public long highWatermark(long chatId) {
        if (chatId < 1) throw new IllegalArgumentException("chatId must be positive");
        Long value = entityManager.createQuery("select max(m.id) from Message m where m.chatId = :chat", Long.class)
                .setParameter("chat", chatId).getSingleResult();
        return value == null ? 0 : value;
    }

    public List<Message> read(long chatId, MessageWindow window, long watermark, MessageCursor cursor, int maxRows) {
        if (chatId < 1 || watermark < 0 || maxRows < 1 || maxRows > 201 || window == null)
            throw new IllegalArgumentException("invalid bounded query");
        if (cursor != null && (cursor.chatId() != chatId || !cursor.window().equals(window) || cursor.watermark() != watermark))
            throw new IllegalArgumentException("cursor does not match the bounded query");
        String jpql = "select m from Message m where m.chatId = :chat and m.id <= :watermark "
                + "and (m.isRecalled = false or m.isRecalled is null) and m.createdAt is not null";
        if (window.start() != null) jpql += " and m.createdAt >= :start and m.createdAt < :end";
        if (cursor != null) jpql += " and (m.createdAt < :position or (m.createdAt = :position and m.id < :positionId))";
        jpql += " order by m.createdAt desc, m.id desc";
        TypedQuery<Message> q = entityManager.createQuery(jpql, Message.class)
                .setParameter("chat", chatId).setParameter("watermark", watermark);
        if (window.start() != null) q.setParameter("start", window.storedStart()).setParameter("end", window.storedEnd());
        if (cursor != null) q.setParameter("position", cursor.createdAt()).setParameter("positionId", cursor.messageId());
        return q.setMaxResults(maxRows).getResultList();
    }
}

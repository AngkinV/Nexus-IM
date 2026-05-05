package com.nexus.chat.repository;

import com.nexus.chat.model.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    Page<Message> findByChatIdOrderByCreatedAtDesc(Long chatId, Pageable pageable);

    Page<Message> findByChatId(Long chatId, Pageable pageable);

    List<Message> findByChatIdOrderByCreatedAtDesc(Long chatId);

    List<Message> findByChatIdOrderByCreatedAtAsc(Long chatId);

    Long countByChatId(Long chatId);

    /**
     * Count messages sent by a user
     */
    long countBySenderId(Long senderId);

    /**
     * Get only the last message for a single chat (replaces loading ALL messages)
     */
    Optional<Message> findFirstByChatIdOrderByCreatedAtDesc(Long chatId);

    /**
     * Batch get last messages for multiple chats in one query
     * Eliminates N+1 when loading chat list
     */
    @Query("SELECT m FROM Message m WHERE m.id IN " +
           "(SELECT MAX(m2.id) FROM Message m2 WHERE m2.chatId IN :chatIds GROUP BY m2.chatId)")
    List<Message> findLastMessagesByChatIds(@Param("chatIds") List<Long> chatIds);

    /**
     * Find messages updated after a timestamp for delta sync
     */
    @Query("SELECT m FROM Message m WHERE m.chatId IN :chatIds AND m.createdAt > :since ORDER BY m.createdAt ASC")
    List<Message> findByChatIdInAndCreatedAtAfter(
            @Param("chatIds") List<Long> chatIds,
            @Param("since") LocalDateTime since);

    /**
     * Check if a message with the given clientMessageId already exists (for deduplication)
     */
    boolean existsByClientMessageId(String clientMessageId);

    /**
     * Find messages by sequence number range (for gap detection and sync)
     */
    @Query("SELECT m FROM Message m WHERE m.chatId = :chatId AND m.sequenceNumber > :fromSeq ORDER BY m.sequenceNumber ASC")
    List<Message> findByChatIdAndSequenceNumberGreaterThan(
            @Param("chatId") Long chatId,
            @Param("fromSeq") Long fromSeq);

}

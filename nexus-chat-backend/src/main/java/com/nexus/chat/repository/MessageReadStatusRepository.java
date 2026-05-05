package com.nexus.chat.repository;

import com.nexus.chat.model.MessageReadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageReadStatusRepository extends JpaRepository<MessageReadStatus, Long> {

    Optional<MessageReadStatus> findByMessageIdAndUserId(Long messageId, Long userId);

    List<MessageReadStatus> findByMessageId(Long messageId);

    Long countByUserIdAndIsReadFalse(Long userId);

    /**
     * Bulk mark all unread messages in a chat as read for a user (single query).
     * Replaces the loop that loaded ALL messages then saved each read status individually.
     * For 1000 messages: 2000+ queries -> 1 query.
     */
    @Transactional
    @Modifying
    @Query("UPDATE MessageReadStatus mrs SET mrs.isRead = true, mrs.readAt = :readAt " +
           "WHERE mrs.userId = :userId AND mrs.isRead = false AND mrs.messageId IN " +
           "(SELECT m.id FROM Message m WHERE m.chatId = :chatId AND m.senderId != :userId)")
    int bulkMarkAsRead(@Param("chatId") Long chatId,
                       @Param("userId") Long userId,
                       @Param("readAt") LocalDateTime readAt);

}

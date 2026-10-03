package com.nexus.chat.repository;

import com.nexus.chat.model.MessageDeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageDeliveryStatusRepository extends JpaRepository<MessageDeliveryStatus, Long> {

    Optional<MessageDeliveryStatus> findByMessageIdAndUserId(Long messageId, Long userId);

    List<MessageDeliveryStatus> findByMessageId(Long messageId);

    List<MessageDeliveryStatus> findByMessageIdIn(Collection<Long> messageIds);

    @Transactional
    @Modifying
    @Query(value = "UPDATE message_delivery_status SET state = 'delivered', delivered_at = :deliveredAt " +
            "WHERE message_id = :messageId AND user_id = :userId AND state <> 'delivered'",
            nativeQuery = true)
    int markDelivered(@Param("messageId") Long messageId,
                      @Param("userId") Long userId,
                      @Param("deliveredAt") LocalDateTime deliveredAt);
}

package com.nexus.chat.repository;

import com.nexus.chat.model.MessageReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageReactionRepository extends JpaRepository<MessageReaction, Long> {

    Optional<MessageReaction> findByMessageIdAndUserIdAndEmoji(Long messageId, Long userId, String emoji);

    List<MessageReaction> findByMessageId(Long messageId);

    List<MessageReaction> findByMessageIdIn(Collection<Long> messageIds);

    void deleteByMessageIdAndUserIdAndEmoji(Long messageId, Long userId, String emoji);

    @Query("SELECT r.emoji AS emoji, COUNT(r.id) AS count FROM MessageReaction r WHERE r.messageId = :messageId GROUP BY r.emoji")
    List<ReactionCount> countByEmoji(@Param("messageId") Long messageId);

    interface ReactionCount {
        String getEmoji();
        Long getCount();
    }
}

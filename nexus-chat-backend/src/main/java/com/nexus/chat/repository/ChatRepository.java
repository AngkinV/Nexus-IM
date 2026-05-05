package com.nexus.chat.repository;

import com.nexus.chat.model.Chat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRepository extends JpaRepository<Chat, Long> {

    @Query("SELECT c FROM Chat c JOIN ChatMember cm ON c.id = cm.chatId WHERE cm.userId = :userId ORDER BY c.lastMessageAt DESC")
    List<Chat> findByUserIdOrderByLastMessageAtDesc(@Param("userId") Long userId);

    /**
     * Find all group chats for a user
     */
    @Query("SELECT c FROM Chat c JOIN ChatMember cm ON c.id = cm.chatId WHERE cm.userId = :userId AND c.type = 'group' ORDER BY c.lastMessageAt DESC")
    List<Chat> findUserGroups(@Param("userId") Long userId);

    /**
     * Count groups for a user
     */
    @Query("SELECT COUNT(c) FROM Chat c JOIN ChatMember cm ON c.id = cm.chatId WHERE cm.userId = :userId AND c.type = 'group'")
    long countUserGroups(@Param("userId") Long userId);

    /**
     * Find groups created by a user
     */
    List<Chat> findByCreatedByAndType(Long createdBy, Chat.ChatType type);

    /**
     * Find by type
     */
    List<Chat> findByType(Chat.ChatType type);

    /**
     * Find direct chat between two users
     */
    @Query("SELECT c FROM Chat c WHERE c.type = 'direct' AND c.id IN " +
           "(SELECT cm1.chatId FROM ChatMember cm1 WHERE cm1.userId = :userId1) AND c.id IN " +
           "(SELECT cm2.chatId FROM ChatMember cm2 WHERE cm2.userId = :userId2)")
    Optional<Chat> findDirectChatBetweenUsers(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    /**
     * Find chats updated after a timestamp for delta sync.
     * Returns chats where the user is a member and lastMessageAt > since.
     */
    @Query("SELECT c FROM Chat c JOIN ChatMember cm ON c.id = cm.chatId " +
           "WHERE cm.userId = :userId AND c.lastMessageAt > :since " +
           "ORDER BY c.lastMessageAt DESC")
    List<Chat> findByUserIdAndLastMessageAtAfter(
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since);

    /**
     * Find chats by IDs (batch load for optimization)
     */
    List<Chat> findByIdIn(List<Long> ids);

}

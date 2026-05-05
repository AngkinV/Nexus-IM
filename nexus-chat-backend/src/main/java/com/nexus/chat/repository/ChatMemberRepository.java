package com.nexus.chat.repository;

import com.nexus.chat.model.ChatMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMemberRepository extends JpaRepository<ChatMember, Long> {

    List<ChatMember> findByChatId(Long chatId);

    List<ChatMember> findByUserId(Long userId);

    Optional<ChatMember> findByChatIdAndUserId(Long chatId, Long userId);

    boolean existsByChatIdAndUserId(Long chatId, Long userId);

    /**
     * Delete member from chat
     */
    @Transactional
    @Modifying
    void deleteByChatIdAndUserId(Long chatId, Long userId);

    /**
     * Delete all members of a chat
     */
    @Transactional
    @Modifying
    void deleteByChatId(Long chatId);

    /**
     * Count members in a chat
     */
    long countByChatId(Long chatId);

    /**
     * Find admins of a chat
     */
    List<ChatMember> findByChatIdAndIsAdminTrue(Long chatId);

    /**
     * Find owner of a chat
     */
    @Query("SELECT cm FROM ChatMember cm WHERE cm.chatId = :chatId AND cm.role = 'owner'")
    Optional<ChatMember> findOwnerByChatId(@Param("chatId") Long chatId);

    /**
     * Batch find members for multiple chats (eliminates N+1)
     */
    List<ChatMember> findByChatIdIn(List<Long> chatIds);

    /**
     * Reset unread count for a user in a chat (single query instead of find+save)
     */
    @Transactional
    @Modifying
    @Query("UPDATE ChatMember cm SET cm.unreadCount = 0 WHERE cm.chatId = :chatId AND cm.userId = :userId")
    void resetUnreadCount(@Param("chatId") Long chatId, @Param("userId") Long userId);

    /**
     * Increment unread count for all members except sender (batch operation)
     */
    @Transactional
    @Modifying
    @Query("UPDATE ChatMember cm SET cm.unreadCount = cm.unreadCount + 1 " +
           "WHERE cm.chatId = :chatId AND cm.userId != :senderId")
    void incrementUnreadForOthers(@Param("chatId") Long chatId, @Param("senderId") Long senderId);

}

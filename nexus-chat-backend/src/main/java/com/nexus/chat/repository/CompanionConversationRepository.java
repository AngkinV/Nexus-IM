package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanionConversationRepository extends JpaRepository<CompanionConversation, Long> {
    Optional<CompanionConversation> findByUserIdAndRoleId(Long userId, Long roleId);
    List<CompanionConversation> findByUserId(Long userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update CompanionConversation c set c.lastMessageAt = null where c.userId = :userId and c.roleId = :roleId")
    int clearLastMessageAt(Long userId, Long roleId);
}

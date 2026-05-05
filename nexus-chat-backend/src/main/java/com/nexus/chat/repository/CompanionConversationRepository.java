package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanionConversationRepository extends JpaRepository<CompanionConversation, Long> {
    Optional<CompanionConversation> findByUserIdAndRoleId(Long userId, Long roleId);
}

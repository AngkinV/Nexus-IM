package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanionRoleRepository extends JpaRepository<CompanionRole, Long> {
    List<CompanionRole> findByUserIdOrderByIdAsc(Long userId);
    Optional<CompanionRole> findByIdAndUserId(Long id, Long userId);
    long countByUserId(Long userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update CompanionRole r set r.originSessionId = null, r.conversationHidden = true where r.userId = :userId and r.id = :roleId")
    int hideConversation(Long userId, Long roleId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update CompanionRole r set r.conversationHidden = false where r.userId = :userId and r.id = :roleId")
    int showConversation(Long userId, Long roleId);
}

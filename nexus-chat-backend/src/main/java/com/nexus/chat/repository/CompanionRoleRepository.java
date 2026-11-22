package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanionRoleRepository extends JpaRepository<CompanionRole, Long> {
    List<CompanionRole> findByUserIdOrderByIdAsc(Long userId);
    Optional<CompanionRole> findByIdAndUserId(Long id, Long userId);
    long countByUserId(Long userId);

    // Assets are a shared catalogue. A file rename must preserve all existing
    // references to that same file, including legacy bare filenames.
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update CompanionRole r set r.modelUrl = :nextUrl where r.modelUrl = :oldUrl or r.modelUrl = :oldName")
    int rebindModelAsset(@org.springframework.data.repository.query.Param("oldUrl") String oldUrl,
                         @org.springframework.data.repository.query.Param("oldName") String oldName,
                         @org.springframework.data.repository.query.Param("nextUrl") String nextUrl);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update CompanionRole r set r.originSessionId = null, r.conversationHidden = true where r.userId = :userId and r.id = :roleId")
    int hideConversation(Long userId, Long roleId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update CompanionRole r set r.conversationHidden = false where r.userId = :userId and r.id = :roleId")
    int showConversation(Long userId, Long roleId);
}

package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanionMemoryRepository extends JpaRepository<CompanionMemory, Long> {
    List<CompanionMemory> findByUserIdAndRoleIdOrderByCreatedAtDesc(Long userId, Long roleId);
    Optional<CompanionMemory> findByIdAndUserId(Long id, Long userId);
    @org.springframework.data.jpa.repository.Query("select m from CompanionMemory m where m.userId=:userId and m.roleId=:roleId and (m.deleted=false or m.deleted is null) order by m.confirmed desc, m.createdAt desc, m.id desc")
    List<CompanionMemory> findActive(Long userId, Long roleId, org.springframework.data.domain.Pageable pageable);
    boolean existsByUserIdAndRoleIdAndSourceHash(Long userId, Long roleId, String sourceHash);
    @org.springframework.data.jpa.repository.Query("select count(m) from CompanionMemory m where m.userId=:userId and m.roleId=:roleId and (m.deleted=false or m.deleted is null)")
    long countActive(Long userId, Long roleId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update CompanionMemory m set m.deleted=true, m.content='', m.embeddingVector=null, m.embeddingRevision=null, m.version=coalesce(m.version,0)+1 where m.userId=:userId and m.roleId=:roleId")
    int forgetAll(Long userId, Long roleId);

    void deleteByUserIdAndRoleId(Long userId, Long roleId);
}

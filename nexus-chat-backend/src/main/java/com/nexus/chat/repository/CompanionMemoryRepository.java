package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanionMemoryRepository extends JpaRepository<CompanionMemory, Long> {
    List<CompanionMemory> findByUserIdAndRoleIdOrderByCreatedAtDesc(Long userId, Long roleId);
    Optional<CompanionMemory> findByIdAndUserId(Long id, Long userId);
    void deleteByUserIdAndRoleId(Long userId, Long roleId);
}

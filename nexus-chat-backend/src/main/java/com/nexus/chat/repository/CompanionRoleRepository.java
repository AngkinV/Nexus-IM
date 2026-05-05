package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanionRoleRepository extends JpaRepository<CompanionRole, Long> {
    List<CompanionRole> findByUserIdOrderByIdAsc(Long userId);
    Optional<CompanionRole> findByIdAndUserId(Long id, Long userId);
    long countByUserId(Long userId);
}

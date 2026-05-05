package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionGrowth;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanionGrowthRepository extends JpaRepository<CompanionGrowth, Long> {
    Optional<CompanionGrowth> findByUserIdAndRoleId(Long userId, Long roleId);
}

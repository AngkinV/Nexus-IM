package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanionStatusRepository extends JpaRepository<CompanionStatus, Long> {
    Optional<CompanionStatus> findByUserIdAndRoleId(Long userId, Long roleId);
    List<CompanionStatus> findByUserId(Long userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from CompanionStatus s where s.userId = :userId and s.roleId = :roleId")
    int deleteHistory(Long userId, Long roleId);
}

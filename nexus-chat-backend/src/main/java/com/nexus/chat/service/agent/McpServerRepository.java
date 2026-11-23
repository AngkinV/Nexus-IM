package com.nexus.chat.service.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpServerRepository extends JpaRepository<McpServerEntity, String> {
    List<McpServerEntity> findByOwnerUserIdAndDeletedFalseOrderByCreatedAtAsc(long owner);
    Optional<McpServerEntity> findByServerIdAndOwnerUserIdAndDeletedFalse(String id, long owner);
    Optional<McpServerEntity> findByServerIdAndOwnerUserId(String id, long owner);
    long countByOwnerUserIdAndDeletedFalse(long owner);
}

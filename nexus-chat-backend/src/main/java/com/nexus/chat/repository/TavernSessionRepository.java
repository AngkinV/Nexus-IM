package com.nexus.chat.repository;

import com.nexus.chat.model.TavernSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TavernSessionRepository extends JpaRepository<TavernSession, Long> {

    Optional<TavernSession> findByIdAndUserId(Long id, Long userId);

    /** The evening currently in progress for this role, if any. */
    Optional<TavernSession> findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(
            Long userId, Long roleId, TavernSession.Status status);

    /** Closed evenings, newest first — the archive. */
    List<TavernSession> findByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(
            Long userId, Long roleId, TavernSession.Status status);
}

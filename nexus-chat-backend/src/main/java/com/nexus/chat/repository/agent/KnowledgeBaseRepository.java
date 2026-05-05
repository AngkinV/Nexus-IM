package com.nexus.chat.repository.agent;

import com.nexus.chat.model.agent.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link KnowledgeBase}. All ownership-sensitive lookups go
 * through {@code findByKbIdAndUserId} so a forged {@code kbId} from another
 * user can never be resolved at the controller layer.
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    Optional<KnowledgeBase> findByKbId(String kbId);

    Optional<KnowledgeBase> findByKbIdAndUserId(String kbId, Long userId);

    List<KnowledgeBase> findByUserIdOrderByUpdatedAtDesc(Long userId);

    boolean existsByKbId(String kbId);

    long countByUserId(Long userId);
}

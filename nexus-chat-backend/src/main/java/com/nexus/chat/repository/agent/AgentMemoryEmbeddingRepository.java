package com.nexus.chat.repository.agent;

import com.nexus.chat.model.agent.AgentMemoryEmbedding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the Java-side metadata mirror of Module A memory chunks.
 *
 * <p>Reads are typed by user/session because the corresponding ChromaDB
 * collection is filtered by userId at query time — keeping the indexes
 * symmetric on both sides.
 */
@Repository
public interface AgentMemoryEmbeddingRepository extends JpaRepository<AgentMemoryEmbedding, Long> {

    Optional<AgentMemoryEmbedding> findByChunkId(String chunkId);

    List<AgentMemoryEmbedding> findByUserIdAndSessionIdOrderByCreatedAtDesc(Long userId, String sessionId);

    Page<AgentMemoryEmbedding> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByUserId(Long userId);

    void deleteByUserIdAndSessionId(Long userId, String sessionId);
}

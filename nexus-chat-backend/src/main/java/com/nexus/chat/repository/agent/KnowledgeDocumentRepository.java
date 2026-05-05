package com.nexus.chat.repository.agent;

import com.nexus.chat.model.agent.KnowledgeDocument;
import com.nexus.chat.model.agent.KnowledgeDocument.IngestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link KnowledgeDocument}.
 *
 * <p>Reads are typed by {@code kbId} because the document list view always
 * scopes to one knowledge base; mutating ops scoped to {@code kbId} are
 * marked {@link Modifying} so JPA flushes them outside the read cache.
 */
@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findByDocId(String docId);

    Optional<KnowledgeDocument> findByKbIdAndDocId(String kbId, String docId);

    List<KnowledgeDocument> findByKbIdOrderByCreatedAtDesc(String kbId);

    List<KnowledgeDocument> findByKbIdAndStatusOrderByCreatedAtDesc(String kbId, IngestionStatus status);

    boolean existsByDocId(String docId);

    long countByKbId(String kbId);

    long countByKbIdAndStatus(String kbId, IngestionStatus status);

    @Modifying
    @Transactional
    @Query("delete from KnowledgeDocument d where d.kbId = :kbId")
    int deleteAllByKbId(String kbId);
}

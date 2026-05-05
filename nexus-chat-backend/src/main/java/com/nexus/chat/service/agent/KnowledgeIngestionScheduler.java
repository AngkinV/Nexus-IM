package com.nexus.chat.service.agent;

import com.nexus.chat.model.agent.KnowledgeBase;
import com.nexus.chat.model.agent.KnowledgeDocument;
import com.nexus.chat.repository.agent.KnowledgeBaseRepository;
import com.nexus.chat.repository.agent.KnowledgeDocumentRepository;
import com.nexus.chat.service.agent.KnowledgeGatewayService.DeleteRequest;
import com.nexus.chat.service.agent.KnowledgeGatewayService.IngestRequest;
import com.nexus.chat.service.agent.KnowledgeGatewayService.IngestResponse;
import com.nexus.chat.service.agent.KnowledgeGatewayService.KnowledgeGatewayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Async bridge between the synchronous Java upload/delete CRUD and the
 * Python {@code /v1/knowledge/*} routes.
 *
 * <p>Why async: ingesting a 50 MB PDF can take 10-60 s (page extraction +
 * embedding). The user-visible upload endpoint must return promptly with
 * status=PENDING so the frontend can render the row and start polling
 * {@code GET .../documents/{docId}/status}. This scheduler runs the actual
 * Python call in another thread and flips the row to READY/FAILED via
 * {@link KnowledgeBaseService#markDocumentReady} /
 * {@link KnowledgeBaseService#markDocumentFailed}.
 *
 * <p>Why call the scheduler from the controller (not from
 * {@link KnowledgeBaseService}): the upload service method is
 * {@code @Transactional}; the row is only visible to other threads
 * <i>after commit</i>. Calling the scheduler from the controller, after
 * the service method has returned, sidesteps the
 * "@Async sees uncommitted state" trap without registering a
 * TransactionSynchronization callback inside the service.
 *
 * <p>Status writes go through the injected {@link KnowledgeBaseService}
 * proxy so {@code @Transactional} actually kicks in — calling
 * {@code this.markXxx()} directly would bypass the AOP advice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionScheduler {

    private final KnowledgeGatewayService gateway;
    private final KnowledgeBaseService kbService;
    private final KnowledgeBaseRepository kbRepo;
    private final KnowledgeDocumentRepository docRepo;

    @Async
    public void scheduleIngestion(Long userId, String kbId, String docId) {
        Optional<KnowledgeDocument> docOpt = docRepo.findByDocId(docId);
        if (docOpt.isEmpty()) {
            log.warn("scheduleIngestion: doc not found, kb={}, doc={}", kbId, docId);
            return;
        }
        KnowledgeDocument doc = docOpt.get();
        Optional<KnowledgeBase> kbOpt = kbRepo.findByKbId(kbId);
        if (kbOpt.isEmpty()) {
            log.warn("scheduleIngestion: kb not found, kb={}", kbId);
            kbService.markDocumentFailed(docId, "knowledge base no longer exists");
            return;
        }
        KnowledgeBase kb = kbOpt.get();

        kbService.markDocumentProcessing(docId);

        IngestRequest req = IngestRequest.builder()
                .kbId(kbId)
                .docId(docId)
                .filePath(toAbsolute(doc.getFilePath()))
                .fileType(doc.getFileType())
                .fileName(doc.getFileName())
                .userId(userId)
                .chunkSize(kb.getChunkSize())
                .chunkOverlap(kb.getChunkOverlap())
                .embeddingModel(kb.getEmbeddingModel())
                .embeddingCredentialId(kb.getEmbeddingCredentialId())
                .build();

        try {
            IngestResponse resp = gateway.ingest(userId, req);
            int produced = resp.getChunkCount() == null ? 0 : resp.getChunkCount();
            kbService.markDocumentReady(docId, produced, resp.getEmbeddingDimension());
            log.info("ingestion done: kb={}, doc={}, chunks={}, dim={}",
                    kbId, docId, produced, resp.getEmbeddingDimension());
        } catch (KnowledgeGatewayException ex) {
            log.warn("ingestion failed via python: kb={}, doc={}, err={}", kbId, docId, ex.getMessage());
            kbService.markDocumentFailed(docId, ex.getMessage());
        } catch (Exception ex) {
            log.error("ingestion failed unexpectedly: kb={}, doc={}", kbId, docId, ex);
            kbService.markDocumentFailed(docId,
                    "internal error: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    /**
     * Fire-and-forget vector cleanup. {@code docId == null} means the
     * whole KB was removed; Python's /v1/knowledge/delete handles both
     * shapes. Failures here are logged but do not surface to the user —
     * orphan vectors don't return wrong answers (the kbId/docId no
     * longer match anything Java will accept), so eventual consistency
     * is acceptable.
     */
    @Async
    public void scheduleDeletion(Long userId, String kbId, String docId) {
        DeleteRequest req = DeleteRequest.builder().kbId(kbId).docId(docId).build();
        try {
            gateway.delete(userId, req);
        } catch (KnowledgeGatewayException ex) {
            log.warn("vector cleanup failed: kb={}, doc={}, err={}", kbId, docId, ex.getMessage());
        } catch (Exception ex) {
            log.error("vector cleanup failed unexpectedly: kb={}, doc={}", kbId, docId, ex);
        }
    }

    private static String toAbsolute(String filePath) {
        if (filePath == null || filePath.isBlank()) return filePath;
        Path p = Paths.get(filePath);
        return p.isAbsolute() ? p.toString() : p.toAbsolutePath().toString();
    }
}

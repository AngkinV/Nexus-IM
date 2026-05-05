package com.nexus.chat.service.agent;

import com.nexus.chat.dto.agent.KnowledgeBaseDtos;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.agent.KnowledgeBase;
import com.nexus.chat.model.agent.KnowledgeDocument;
import com.nexus.chat.model.agent.KnowledgeDocument.IngestionStatus;
import com.nexus.chat.repository.agent.KnowledgeBaseRepository;
import com.nexus.chat.repository.agent.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Knowledge-base CRUD + per-document upload/list/delete.
 *
 * <p>Files are stored under {@code uploads/agent-kb/<kbId>/<docId>.<ext>} so a
 * KB delete can {@code rm -r} cleanly without scanning the date-partitioned
 * tree the regular FileUploadController uses.
 *
 * <p>Python ingestion (text extraction → chunking → embedding → ChromaDB
 * write) is wired in Day 11. Until then a fresh document row stays in
 * {@link IngestionStatus#PENDING} and the {@code chunkCount} counters on the
 * parent KB stay at the value they had before the upload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private static final String UPLOAD_BASE = "uploads/agent-kb";
    private static final long MAX_DOC_SIZE = 50L * 1024 * 1024; // 50 MB
    private static final int MAX_KB_PER_USER = 50;
    private static final int MAX_DOCS_PER_KB = 200;
    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "md", "markdown", "txt", "docx", "doc");

    private final KnowledgeBaseRepository kbRepo;
    private final KnowledgeDocumentRepository docRepo;
    private final AgentProviderService providerService;

    // --------------- KB CRUD ---------------

    public List<KnowledgeBaseDtos.KbView> listForUser(Long userId) {
        return kbRepo.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toKbView)
                .toList();
    }

    public KnowledgeBaseDtos.KbView get(Long userId, String kbId) {
        return toKbView(mustOwn(userId, kbId));
    }

    @Transactional
    public KnowledgeBaseDtos.KbView create(Long userId, KnowledgeBaseDtos.CreateKbRequest req) {
        if (kbRepo.countByUserId(userId) >= MAX_KB_PER_USER) {
            throw new AgentException(AgentErrorCode.AGENT_KB_QUOTA_42902,
                    "knowledge base count exceeds per-user quota",
                    Map.of("max", MAX_KB_PER_USER));
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setUserId(userId);
        kb.setKbId(generateKbId());
        kb.setName(req.getName().trim());
        kb.setDescription(blankToNull(req.getDescription()));
        if (req.getEmbeddingModel() != null && !req.getEmbeddingModel().isBlank()) {
            kb.setEmbeddingModel(req.getEmbeddingModel().trim());
        }
        if (req.getEmbeddingCredentialId() != null) {
            // Verify the credential exists, belongs to the user, and is purpose=embedding.
            providerService.resolveEmbeddingCredential(userId, req.getEmbeddingCredentialId())
                    .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_PARAM_40001,
                            "embeddingCredentialId not found or not an embedding credential"));
            kb.setEmbeddingCredentialId(req.getEmbeddingCredentialId());
        }
        if (req.getChunkSize() != null) kb.setChunkSize(req.getChunkSize());
        if (req.getChunkOverlap() != null) kb.setChunkOverlap(req.getChunkOverlap());
        return toKbView(kbRepo.save(kb));
    }

    @Transactional
    public KnowledgeBaseDtos.KbView update(Long userId, String kbId, KnowledgeBaseDtos.UpdateKbRequest req) {
        KnowledgeBase kb = mustOwn(userId, kbId);
        if (req.getName() != null && !req.getName().isBlank()) {
            kb.setName(req.getName().trim());
        }
        if (req.getDescription() != null) {
            kb.setDescription(blankToNull(req.getDescription()));
        }
        // Embedding credential / model can only change when no documents have produced
        // vectors yet — once embedding_dimension is set, Chroma has locked the collection's
        // vector dim and a swap would corrupt subsequent retrieval.
        boolean wantsCredentialChange = req.getEmbeddingCredentialId() != null
                && !Objects.equals(req.getEmbeddingCredentialId(), kb.getEmbeddingCredentialId());
        boolean wantsModelChange = req.getEmbeddingModel() != null
                && !req.getEmbeddingModel().trim().isEmpty()
                && !req.getEmbeddingModel().trim().equals(kb.getEmbeddingModel());
        if ((wantsCredentialChange || wantsModelChange) && kb.getEmbeddingDimension() != null) {
            throw new AgentException(AgentErrorCode.AGENT_KB_VALIDATION_42201,
                    "cannot change embedding configuration after the KB has ingested documents — "
                            + "create a new KB instead");
        }
        if (wantsCredentialChange) {
            providerService.resolveEmbeddingCredential(userId, req.getEmbeddingCredentialId())
                    .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_PARAM_40001,
                            "embeddingCredentialId not found or not an embedding credential"));
            kb.setEmbeddingCredentialId(req.getEmbeddingCredentialId());
        }
        if (wantsModelChange) {
            kb.setEmbeddingModel(req.getEmbeddingModel().trim());
        }
        return toKbView(kbRepo.save(kb));
    }

    @Transactional
    public void delete(Long userId, String kbId) {
        KnowledgeBase kb = mustOwn(userId, kbId);
        // Snapshot file paths before the row delete so we can clean disk after
        // the DB transaction commits.
        List<KnowledgeDocument> docs = docRepo.findByKbIdOrderByCreatedAtDesc(kbId);
        docRepo.deleteAllByKbId(kbId);
        kbRepo.delete(kb);
        for (KnowledgeDocument d : docs) {
            deleteFileQuietly(d.getFilePath());
        }
        deleteDirectoryQuietly(kbDir(kbId));
        // Python vector cleanup is fire-and-forget; wired in Day 11 via
        // POST /v1/knowledge/delete with body { kbId }.
    }

    // --------------- Document operations ---------------

    public List<KnowledgeBaseDtos.DocumentView> listDocuments(Long userId, String kbId) {
        mustOwn(userId, kbId);
        return docRepo.findByKbIdOrderByCreatedAtDesc(kbId).stream()
                .map(KnowledgeBaseService::toDocView)
                .toList();
    }

    @Transactional
    public KnowledgeBaseDtos.DocumentView uploadDocument(Long userId, String kbId, MultipartFile file) {
        KnowledgeBase kb = mustOwn(userId, kbId);
        if (file == null || file.isEmpty()) {
            throw new AgentException(AgentErrorCode.AGENT_KB_FILE_40002, "file is empty");
        }
        if (file.getSize() > MAX_DOC_SIZE) {
            throw new AgentException(AgentErrorCode.AGENT_KB_FILE_40002,
                    "file exceeds size limit",
                    Map.of("size", file.getSize(), "max", MAX_DOC_SIZE));
        }
        if (docRepo.countByKbId(kbId) >= MAX_DOCS_PER_KB) {
            throw new AgentException(AgentErrorCode.AGENT_KB_QUOTA_42902,
                    "document count exceeds per-kb quota",
                    Map.of("max", MAX_DOCS_PER_KB));
        }

        String originalName = sanitizeOriginalName(file.getOriginalFilename());
        String ext = extractExtension(originalName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new AgentException(AgentErrorCode.AGENT_KB_FILE_40002,
                    "unsupported file type",
                    Map.of("ext", ext, "allowed", ALLOWED_EXT));
        }

        String docId = generateDocId();
        Path target = kbDir(kbId).resolve(docId + "." + ext);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.error("failed to persist uploaded kb document: kb={}, doc={}", kbId, docId, ex);
            throw new AgentException(AgentErrorCode.AGENT_KB_FILE_40002, "failed to persist file");
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(kbId);
        doc.setDocId(docId);
        doc.setFileName(originalName);
        doc.setFilePath(target.toString());
        doc.setFileSize(file.getSize());
        doc.setFileType(normalizeFileType(ext));
        doc.setStatus(IngestionStatus.PENDING);
        doc = docRepo.save(doc);

        kb.setDocumentCount(kb.getDocumentCount() + 1);
        kbRepo.save(kb);

        // Python ingestion call is wired in Day 11; row stays PENDING until
        // the upload-callback endpoint flips it.
        return toDocView(doc);
    }

    @Transactional
    public void deleteDocument(Long userId, String kbId, String docId) {
        KnowledgeBase kb = mustOwn(userId, kbId);
        KnowledgeDocument doc = docRepo.findByKbIdAndDocId(kbId, docId)
                .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_KB_DOC_40403,
                        "knowledge document not found"));
        docRepo.delete(doc);
        kb.setDocumentCount(Math.max(0, kb.getDocumentCount() - 1));
        kb.setChunkCount(Math.max(0, kb.getChunkCount() - (doc.getChunkCount() == null ? 0 : doc.getChunkCount())));
        kbRepo.save(kb);
        deleteFileQuietly(doc.getFilePath());
        // Python vector cleanup deferred to Day 11.
    }

    public KnowledgeBaseDtos.DocumentStatusView getDocumentStatus(Long userId, String kbId, String docId) {
        mustOwn(userId, kbId);
        KnowledgeDocument doc = docRepo.findByKbIdAndDocId(kbId, docId)
                .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_KB_DOC_40403,
                        "knowledge document not found"));
        KnowledgeBaseDtos.DocumentStatusView v = new KnowledgeBaseDtos.DocumentStatusView();
        v.setDocId(doc.getDocId());
        v.setStatus(doc.getStatus().name());
        v.setChunkCount(doc.getChunkCount());
        v.setErrorMessage(doc.getErrorMessage());
        v.setUpdatedAt(doc.getUpdatedAt() == null ? null : doc.getUpdatedAt().toString());
        return v;
    }

    // --------------- Ingestion-status reconciliation ---------------
    //
    // Called by KnowledgeIngestionScheduler from a different thread once the
    // Python /v1/knowledge/ingest call completes. They live here (not on the
    // scheduler) to dodge the Spring AOP self-invocation trap: @Transactional
    // on a `this.method()` call from within an @Async method is silently
    // ignored, but going through an injected proxy bean works correctly.

    @Transactional
    public void markDocumentProcessing(String docId) {
        docRepo.findByDocId(docId).ifPresent(d -> {
            d.setStatus(IngestionStatus.PROCESSING);
            d.setErrorMessage(null);
            docRepo.save(d);
        });
    }

    @Transactional
    public void markDocumentReady(String docId, int chunkCount) {
        markDocumentReady(docId, chunkCount, null);
    }

    /**
     * Variant called by the ingestion scheduler when Python reports the vector dimension
     * produced by the configured embedding model. Set once on the KB at the first successful
     * ingestion; subsequent calls leave it untouched (Chroma collections lock dim at first write,
     * so the value is also fixed).
     */
    @Transactional
    public void markDocumentReady(String docId, int chunkCount, Integer embeddingDimension) {
        docRepo.findByDocId(docId).ifPresent(d -> {
            int prev = d.getChunkCount() == null ? 0 : d.getChunkCount();
            d.setStatus(IngestionStatus.READY);
            d.setChunkCount(chunkCount);
            d.setErrorMessage(null);
            docRepo.save(d);

            kbRepo.findByKbId(d.getKbId()).ifPresent(kb -> {
                int delta = chunkCount - prev;
                kb.setChunkCount(Math.max(0, kb.getChunkCount() + delta));
                if (kb.getEmbeddingDimension() == null && embeddingDimension != null && embeddingDimension > 0) {
                    kb.setEmbeddingDimension(embeddingDimension);
                }
                kbRepo.save(kb);
            });
        });
    }

    @Transactional
    public void markDocumentFailed(String docId, String reason) {
        docRepo.findByDocId(docId).ifPresent(d -> {
            d.setStatus(IngestionStatus.FAILED);
            d.setErrorMessage(reason == null ? "unknown failure" : truncate(reason, 500));
            docRepo.save(d);
        });
    }

    // --------------- Internals ---------------

    private KnowledgeBase mustOwn(Long userId, String kbId) {
        return kbRepo.findByKbIdAndUserId(kbId, userId)
                .orElseThrow(() -> new AgentException(AgentErrorCode.AGENT_KB_40402,
                        "knowledge base not found"));
    }

    private static Path kbDir(String kbId) {
        return Paths.get(UPLOAD_BASE, kbId);
    }

    private static String generateKbId() {
        return "kb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String generateDocId() {
        return "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String sanitizeOriginalName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unnamed";
        }
        // Strip any path component that may have leaked through (browsers usually
        // do this already, but the IE family historically did not).
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Normalise the on-disk extension to the short suffix the schema documents. */
    private static String normalizeFileType(String ext) {
        return "markdown".equals(ext) ? "md" : ext;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static void deleteFileQuietly(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        try {
            Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException ex) {
            log.warn("failed to delete kb document file: {}", filePath, ex);
        }
    }

    private static void deleteDirectoryQuietly(Path dir) {
        try {
            if (!Files.exists(dir)) return;
            Files.walk(dir)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ex) {
            log.warn("failed to delete kb directory: {}", dir, ex);
        }
    }

    private KnowledgeBaseDtos.KbView toKbView(KnowledgeBase kb) {
        KnowledgeBaseDtos.KbView v = new KnowledgeBaseDtos.KbView();
        v.setKbId(kb.getKbId());
        v.setName(kb.getName());
        v.setDescription(kb.getDescription());
        v.setEmbeddingModel(kb.getEmbeddingModel());
        v.setEmbeddingCredentialId(kb.getEmbeddingCredentialId());
        v.setEmbeddingDimension(kb.getEmbeddingDimension());
        if (kb.getEmbeddingCredentialId() != null) {
            providerService.resolveEmbeddingCredential(kb.getUserId(), kb.getEmbeddingCredentialId())
                    .ifPresent(p -> {
                        String label = p.displayName() == null || p.displayName().isBlank()
                                ? p.provider() : p.displayName();
                        v.setEmbeddingProviderLabel(label);
                    });
        }
        v.setChunkSize(kb.getChunkSize());
        v.setChunkOverlap(kb.getChunkOverlap());
        v.setDocumentCount(kb.getDocumentCount());
        v.setChunkCount(kb.getChunkCount());
        v.setCreatedAt(kb.getCreatedAt() == null ? null : kb.getCreatedAt().toString());
        v.setUpdatedAt(kb.getUpdatedAt() == null ? null : kb.getUpdatedAt().toString());
        return v;
    }

    private static KnowledgeBaseDtos.DocumentView toDocView(KnowledgeDocument d) {
        KnowledgeBaseDtos.DocumentView v = new KnowledgeBaseDtos.DocumentView();
        v.setDocId(d.getDocId());
        v.setKbId(d.getKbId());
        v.setFileName(d.getFileName());
        v.setFileType(d.getFileType());
        v.setFileSize(d.getFileSize());
        v.setChunkCount(d.getChunkCount());
        v.setStatus(d.getStatus() == null ? IngestionStatus.PENDING.name() : d.getStatus().name());
        v.setErrorMessage(d.getErrorMessage());
        v.setCreatedAt(d.getCreatedAt() == null ? null : d.getCreatedAt().toString());
        v.setUpdatedAt(d.getUpdatedAt() == null ? null : d.getUpdatedAt().toString());
        return v;
    }
}

package com.nexus.chat.dto.agent;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Wire DTOs for the Module B knowledge-base endpoints.
 *
 * <p>Sizes mirror the column definitions in {@code 2026_05_15_agent_rag.sql};
 * keeping them in sync prevents the controller from accepting a value JPA
 * would later reject on flush.
 */
public final class KnowledgeBaseDtos {

    private KnowledgeBaseDtos() {}

    @Data
    public static class CreateKbRequest {
        @NotBlank
        @Size(max = 120)
        private String name;

        @Size(max = 500)
        private String description;

        /** Optional override; defaults to {@code text-embedding-3-small} server-side. */
        @Size(max = 80)
        private String embeddingModel;

        /**
         * FK to {@code model_credentials.id} with {@code purpose=embedding}. When supplied,
         * the user's stored embedding credential (api_key + base_url) is forwarded to the
         * Python service over the signed internal channel. When null, the server falls back
         * to environment-level defaults — UI should warn the user about that path.
         */
        private Long embeddingCredentialId;

        /** Token-equivalent chunk size; clamped to a sensible range to keep retrieval useful. */
        @Min(64)
        @Max(2048)
        private Integer chunkSize;

        @Min(0)
        @Max(512)
        private Integer chunkOverlap;
    }

    @Data
    public static class UpdateKbRequest {
        @Size(max = 120)
        private String name;

        @Size(max = 500)
        private String description;

        /**
         * Replace the embedding credential. Only allowed when the KB has not yet locked
         * a {@code embeddingDimension} (i.e. no documents ingested) — service layer
         * enforces this and throws AGENT_KB_VALIDATION_42201 otherwise.
         */
        private Long embeddingCredentialId;

        /** Same lock-after-first-write rule as embeddingCredentialId. */
        @Size(max = 80)
        private String embeddingModel;
    }

    @Data
    public static class KbView {
        private String kbId;
        private String name;
        private String description;
        private String embeddingModel;
        private Long embeddingCredentialId;
        /** Echoed for UI convenience: friendly name of the embedding credential, when set. */
        private String embeddingProviderLabel;
        /** Vector dim locked at first ingestion; non-null disables credential changes. */
        private Integer embeddingDimension;
        private Integer chunkSize;
        private Integer chunkOverlap;
        private Integer documentCount;
        private Integer chunkCount;
        private String createdAt;
        private String updatedAt;
    }

    @Data
    public static class DocumentView {
        private String docId;
        private String kbId;
        private String fileName;
        private String fileType;
        private Long fileSize;
        private Integer chunkCount;
        /** PENDING / PROCESSING / READY / FAILED. */
        private String status;
        private String errorMessage;
        private String createdAt;
        private String updatedAt;
    }

    @Data
    public static class DocumentStatusView {
        private String docId;
        private String status;
        private Integer chunkCount;
        private String errorMessage;
        private String updatedAt;
    }

    @Data
    public static class KbListView {
        private List<KbView> items;
        private Integer total;
    }

    @Data
    public static class DocumentListView {
        private String kbId;
        private List<DocumentView> items;
        private Integer total;
    }
}

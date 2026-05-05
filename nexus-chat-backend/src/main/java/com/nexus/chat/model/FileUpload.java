package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "file_uploads")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_id", unique = true, nullable = false, length = 36)
    private String fileId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "original_name")
    private String originalName;

    @Column(name = "stored_name")
    private String storedName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "md5_hash", length = 32)
    private String md5Hash;

    @Column(name = "thumbnail_path", length = 500)
    private String thumbnailPath;

    @Column(name = "uploader_id")
    private Long uploaderId;

    @Column(name = "file_path", nullable = false, columnDefinition = "TEXT")
    private String filePath;

    @Column(name = "chunk_count")
    private Integer chunkCount = 1;

    @Column(name = "upload_complete")
    private Boolean uploadComplete = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

}

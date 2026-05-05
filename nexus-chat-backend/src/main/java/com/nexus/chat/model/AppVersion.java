package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 应用版本实体
 */
@Entity
@Table(name = "app_versions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 版本显示名，如 "1.0.1" */
    @Column(name = "version_name", nullable = false, length = 32)
    private String versionName;

    /** 版本号数字，用于比较大小，如 2 */
    @Column(name = "version_code", nullable = false)
    private Integer versionCode;

    /** APK 下载地址 */
    @Column(name = "download_url", nullable = false, length = 512)
    private String downloadUrl;

    /** 更新日志 */
    @Column(name = "update_log", columnDefinition = "TEXT")
    private String updateLog;

    /** 文件大小（字节） */
    @Column(name = "file_size")
    private Long fileSize;

    /** 是否强制更新 */
    @Column(name = "force_update", nullable = false)
    private Boolean forceUpdate = false;

    /** 是否启用 */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}

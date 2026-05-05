package com.nexus.chat.service;

import com.nexus.chat.model.FileUpload;
import com.nexus.chat.repository.FileUploadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件清理服务
 * 定期清理过期文件和未完成上传的临时文件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileCleanupService {

    private static final String UPLOAD_DIR = "uploads/";

    private final FileUploadRepository fileUploadRepository;

    /**
     * 每天凌晨3点执行清理任务
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanupExpiredFiles() {
        log.info("开始执行文件清理任务...");

        int deletedCount = 0;
        int failedCount = 0;

        try {
            // 1. 清理过期文件（超过30天）
            List<FileUpload> expiredFiles = fileUploadRepository.findByExpiresAtBefore(LocalDateTime.now());
            log.info("找到 {} 个过期文件待清理", expiredFiles.size());

            for (FileUpload fileUpload : expiredFiles) {
                try {
                    // 删除物理文件
                    File file = new File(UPLOAD_DIR + fileUpload.getFilePath());
                    if (file.exists()) {
                        if (file.delete()) {
                            log.debug("已删除文件: {}", fileUpload.getFilePath());
                        } else {
                            log.warn("删除文件失败: {}", fileUpload.getFilePath());
                            failedCount++;
                            continue;
                        }
                    }

                    // 删除缩略图（如果有）
                    if (fileUpload.getThumbnailPath() != null) {
                        File thumbnail = new File(UPLOAD_DIR + fileUpload.getThumbnailPath());
                        if (thumbnail.exists()) {
                            thumbnail.delete();
                        }
                    }

                    // 删除数据库记录
                    fileUploadRepository.delete(fileUpload);
                    deletedCount++;

                } catch (Exception e) {
                    log.error("清理文件失败: fileId={}", fileUpload.getFileId(), e);
                    failedCount++;
                }
            }

            // 2. 清理未完成上传的临时文件（超过24小时）
            LocalDateTime oneDayAgo = LocalDateTime.now().minusDays(1);
            List<FileUpload> incompleteUploads = fileUploadRepository
                    .findByUploadCompleteAndCreatedAtBefore(false, oneDayAgo);

            log.info("找到 {} 个未完成上传待清理", incompleteUploads.size());

            for (FileUpload fileUpload : incompleteUploads) {
                try {
                    // 删除临时分片目录
                    File chunksDir = new File(UPLOAD_DIR + "chunks/" + fileUpload.getFileId());
                    if (chunksDir.exists()) {
                        deleteDirectory(chunksDir);
                    }

                    fileUploadRepository.delete(fileUpload);
                    deletedCount++;

                } catch (Exception e) {
                    log.error("清理未完成上传失败: fileId={}", fileUpload.getFileId(), e);
                    failedCount++;
                }
            }

            // 3. 清理孤立的临时分片目录
            cleanupOrphanedChunks();

        } catch (Exception e) {
            log.error("文件清理任务执行异常", e);
        }

        log.info("文件清理任务完成: 已删除 {} 个文件, 失败 {} 个", deletedCount, failedCount);
    }

    /**
     * 清理孤立的临时分片目录
     */
    private void cleanupOrphanedChunks() {
        File chunksBaseDir = new File(UPLOAD_DIR + "chunks/");
        if (!chunksBaseDir.exists()) {
            return;
        }

        File[] chunkDirs = chunksBaseDir.listFiles(File::isDirectory);
        if (chunkDirs == null) {
            return;
        }

        int cleanedCount = 0;
        long oneDayAgoMillis = System.currentTimeMillis() - (24 * 60 * 60 * 1000);

        for (File dir : chunkDirs) {
            // 如果目录修改时间超过24小时，删除它
            if (dir.lastModified() < oneDayAgoMillis) {
                deleteDirectory(dir);
                cleanedCount++;
            }
        }

        if (cleanedCount > 0) {
            log.info("清理了 {} 个孤立的临时分片目录", cleanedCount);
        }
    }

    /**
     * 手动触发清理（供管理员调用）
     */
    public void triggerCleanup() {
        log.info("手动触发文件清理任务");
        cleanupExpiredFiles();
    }

    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
}

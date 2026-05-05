package com.nexus.chat.repository;

import com.nexus.chat.model.FileUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {

    Optional<FileUpload> findByMessageId(Long messageId);

    Optional<FileUpload> findByFileId(String fileId);

    Optional<FileUpload> findByMd5Hash(String md5Hash);

    List<FileUpload> findByUploaderId(Long uploaderId);

    List<FileUpload> findByUploadCompleteAndCreatedAtBefore(Boolean uploadComplete, LocalDateTime before);

    // 查找过期文件（用于30天清理）
    List<FileUpload> findByExpiresAtBefore(LocalDateTime dateTime);

    // 查找用户在指定聊天中的文件（用于权限校验）
    @Query("SELECT f FROM FileUpload f JOIN Message m ON f.messageId = m.id WHERE m.chatId = :chatId")
    List<FileUpload> findByChatId(@Param("chatId") Long chatId);

}

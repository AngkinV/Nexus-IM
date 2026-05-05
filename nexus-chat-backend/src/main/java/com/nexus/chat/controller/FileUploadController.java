package com.nexus.chat.controller;

import com.nexus.chat.model.FileUpload;
import com.nexus.chat.repository.FileUploadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileUploadController {

    private static final String UPLOAD_DIR = "uploads/";
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final int EXPIRY_DAYS = 30;

    private final FileUploadRepository fileUploadRepository;

    /**
     * 单文件上传（小于5MB）
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "uploaderId", required = false) Long uploaderId) {

        log.debug("文件上传请求: filename={}, size={}, uploaderId={}",
                file.getOriginalFilename(), file.getSize(), uploaderId);

        try {
            // 文件大小校验
            if (file.getSize() > MAX_FILE_SIZE) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "文件大小超过限制",
                        "maxSize", MAX_FILE_SIZE));
            }

            // 计算MD5
            String md5Hash = calculateMD5(file.getBytes());

            // 检查是否已存在相同文件（秒传）
            Optional<FileUpload> existing = fileUploadRepository.findByMd5Hash(md5Hash);
            if (existing.isPresent() && existing.get().getUploadComplete()) {
                FileUpload existingFile = existing.get();
                log.info("文件秒传: md5={}, fileId={}", md5Hash, existingFile.getFileId());
                return ResponseEntity.ok(buildResponse(existingFile));
            }

            // 创建按日期分类的目录
            String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            File uploadDir = new File(UPLOAD_DIR + dateDir);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }

            // 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String extension = getExtension(originalFilename);
            String fileId = UUID.randomUUID().toString();
            String storedName = fileId + extension;
            String filePath = dateDir + "/" + storedName;

            // 保存文件
            Path targetPath = Paths.get(UPLOAD_DIR + filePath);
            Files.write(targetPath, file.getBytes());

            // 保存到数据库
            FileUpload fileUpload = new FileUpload();
            fileUpload.setFileId(fileId);
            fileUpload.setFilename(originalFilename);
            fileUpload.setOriginalName(originalFilename);
            fileUpload.setStoredName(storedName);
            fileUpload.setFileSize(file.getSize());
            fileUpload.setMimeType(file.getContentType());
            fileUpload.setMd5Hash(md5Hash);
            fileUpload.setUploaderId(uploaderId);
            fileUpload.setFilePath(filePath);
            fileUpload.setChunkCount(1);
            fileUpload.setUploadComplete(true);
            fileUpload.setExpiresAt(LocalDateTime.now().plusDays(EXPIRY_DAYS));

            FileUpload savedFile = fileUploadRepository.save(fileUpload);

            log.info("文件上传成功: fileId={}, filename={}, size={}",
                    fileId, originalFilename, file.getSize());

            return ResponseEntity.ok(buildResponse(savedFile));

        } catch (IOException e) {
            log.error("文件上传失败: filename={}", file.getOriginalFilename(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "文件上传失败"));
        }
    }

    /**
     * 分片上传
     */
    @PostMapping("/upload/chunk")
    public ResponseEntity<Map<String, Object>> uploadChunk(
            @RequestParam("file") MultipartFile chunk,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam("fileId") String fileId,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "totalSize", required = false) Long totalSize,
            @RequestParam(value = "uploaderId", required = false) Long uploaderId) {

        log.debug("分片上传请求: fileId={}, chunkIndex={}/{}", fileId, chunkIndex, totalChunks);

        try {
            // 创建临时分片目录
            File chunksDir = new File(UPLOAD_DIR + "chunks/" + fileId);
            if (!chunksDir.exists()) {
                chunksDir.mkdirs();
            }

            // 保存分片
            Path chunkPath = Paths.get(chunksDir.getPath() + "/chunk_" + chunkIndex);
            Files.write(chunkPath, chunk.getBytes());

            Map<String, Object> response = new HashMap<>();
            response.put("chunkIndex", chunkIndex);
            response.put("uploaded", true);

            // 如果是最后一个分片，合并文件
            if (chunkIndex == totalChunks - 1) {
                String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
                File uploadDir = new File(UPLOAD_DIR + dateDir);
                if (!uploadDir.exists()) {
                    uploadDir.mkdirs();
                }

                String extension = getExtension(filename);
                String storedName = fileId + extension;
                String filePath = dateDir + "/" + storedName;

                // 合并分片
                mergeChunks(fileId, totalChunks, UPLOAD_DIR + filePath);

                // 计算合并后文件的MD5
                byte[] fileBytes = Files.readAllBytes(Paths.get(UPLOAD_DIR + filePath));
                String md5Hash = calculateMD5(fileBytes);

                // 保存到数据库
                FileUpload fileUpload = new FileUpload();
                fileUpload.setFileId(fileId);
                fileUpload.setFilename(filename);
                fileUpload.setOriginalName(filename);
                fileUpload.setStoredName(storedName);
                fileUpload.setFileSize(totalSize != null ? totalSize : fileBytes.length);
                fileUpload.setMimeType(getMimeType(filename));
                fileUpload.setMd5Hash(md5Hash);
                fileUpload.setUploaderId(uploaderId);
                fileUpload.setFilePath(filePath);
                fileUpload.setChunkCount(totalChunks);
                fileUpload.setUploadComplete(true);
                fileUpload.setExpiresAt(LocalDateTime.now().plusDays(EXPIRY_DAYS));

                FileUpload savedFile = fileUploadRepository.save(fileUpload);

                response.put("complete", true);
                response.putAll(buildResponse(savedFile));

                log.info("分片上传完成: fileId={}, filename={}", fileId, filename);
            } else {
                response.put("complete", false);
            }

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("分片上传失败: fileId={}, chunkIndex={}", fileId, chunkIndex, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "分片上传失败"));
        }
    }

    /**
     * 获取文件信息
     */
    @GetMapping("/{fileId}/info")
    public ResponseEntity<Map<String, Object>> getFileInfo(@PathVariable String fileId) {
        Optional<FileUpload> fileOpt = fileUploadRepository.findByFileId(fileId);
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(buildResponse(fileOpt.get()));
    }

    /**
     * 下载文件
     */
    @GetMapping("/download/{fileId}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileId) {

        Optional<FileUpload> fileOpt = fileUploadRepository.findByFileId(fileId);
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FileUpload file = fileOpt.get();

        // 检查文件是否过期
        if (file.getExpiresAt() != null && file.getExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(410).build(); // Gone
        }

        try {
            Path filePath = Paths.get(UPLOAD_DIR + file.getFilePath());
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            String encodedFilename = URLEncoder.encode(file.getOriginalName(), StandardCharsets.UTF_8)
                    .replace("+", "%20");

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.getMimeType() != null
                            ? file.getMimeType()
                            : "application/octet-stream"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encodedFilename)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.getFileSize()))
                    .body(resource);

        } catch (Exception e) {
            log.error("文件下载失败: fileId={}", fileId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 在线预览文件
     */
    @GetMapping("/preview/{fileId}")
    public ResponseEntity<Resource> previewFile(@PathVariable String fileId) {

        Optional<FileUpload> fileOpt = fileUploadRepository.findByFileId(fileId);
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FileUpload file = fileOpt.get();

        // 检查文件是否过期
        if (file.getExpiresAt() != null && file.getExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(410).build(); // Gone
        }

        try {
            Path filePath = Paths.get(UPLOAD_DIR + file.getFilePath());
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.getMimeType() != null
                            ? file.getMimeType()
                            : "application/octet-stream"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .body(resource);

        } catch (Exception e) {
            log.error("文件预览失败: fileId={}", fileId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== 辅助方法 ====================

    private Map<String, Object> buildResponse(FileUpload file) {
        Map<String, Object> response = new HashMap<>();
        response.put("fileId", file.getFileId());
        response.put("fileUrl", "/uploads/" + file.getFilePath());
        response.put("downloadUrl", "/files/download/" + file.getFileId());
        response.put("previewUrl", "/files/preview/" + file.getFileId());
        response.put("filename", file.getOriginalName());
        response.put("originalName", file.getOriginalName());
        response.put("size", file.getFileSize());
        response.put("mimeType", file.getMimeType());
        response.put("expiresAt", file.getExpiresAt());
        return response;
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf("."));
    }

    private String getMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        String ext = getExtension(filename).toLowerCase();
        return switch (ext) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".pdf" -> "application/pdf";
            case ".doc" -> "application/msword";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".xls" -> "application/vnd.ms-excel";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".ppt" -> "application/vnd.ms-powerpoint";
            case ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ".mp4" -> "video/mp4";
            case ".webm" -> "video/webm";
            case ".mp3" -> "audio/mpeg";
            case ".wav" -> "audio/wav";
            case ".zip" -> "application/zip";
            case ".rar" -> "application/x-rar-compressed";
            case ".txt" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    private String calculateMD5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private void mergeChunks(String fileId, int totalChunks, String targetPath) throws IOException {
        Path mergedPath = Paths.get(targetPath);

        for (int i = 0; i < totalChunks; i++) {
            Path chunkPath = Paths.get(UPLOAD_DIR + "chunks/" + fileId + "/chunk_" + i);
            byte[] chunkData = Files.readAllBytes(chunkPath);
            Files.write(mergedPath, chunkData,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        }

        // 删除临时分片目录
        deleteDirectory(new File(UPLOAD_DIR + "chunks/" + fileId));
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

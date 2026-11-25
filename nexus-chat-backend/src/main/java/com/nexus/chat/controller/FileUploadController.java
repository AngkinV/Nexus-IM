package com.nexus.chat.controller;

import com.nexus.chat.model.FileUpload;
import com.nexus.chat.repository.FileUploadRepository;
import com.nexus.chat.storage.UploadStorage;
import com.nexus.chat.security.CurrentUser;
import com.nexus.chat.service.FileAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final int EXPIRY_DAYS = 30;

    private final FileUploadRepository fileUploadRepository;
    private final UploadStorage storage;
    private final FileAccessService fileAccessService;

    /**
     * 单文件上传（小于5MB）
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "uploaderId", required = false) Long claimedUploaderId) {
        Long uploaderId = CurrentUser.require(claimedUploaderId);

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
            Optional<FileUpload> existing = fileUploadRepository.findFirstByMd5HashAndUploaderIdAndUploadCompleteTrue(md5Hash, uploaderId)
                    .filter(candidate -> (candidate.getExpiresAt() == null || candidate.getExpiresAt().isAfter(LocalDateTime.now()))
                            && Files.isRegularFile(storage.resolve(candidate.getFilePath())));
            if (existing.isPresent() && existing.get().getUploadComplete()) {
                FileUpload existingFile = existing.get();
                log.info("文件秒传: md5={}, fileId={}", md5Hash, existingFile.getFileId());
                return ResponseEntity.ok(buildResponse(existingFile));
            }

            // 创建按日期分类的目录
            String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            File uploadDir = storage.resolve(dateDir).toFile();
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
            Path targetPath = storage.resolve(filePath);
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
            @RequestParam(value = "uploaderId", required = false) Long claimedUploaderId) {
        Long uploaderId = CurrentUser.require(claimedUploaderId);
        try {
            UUID.fromString(fileId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid upload id");
        }
        if (fileId.length() != 36 || totalChunks < 1 || totalChunks > 20 || chunkIndex < 0 || chunkIndex >= totalChunks
                || chunk.getSize() > 5 * 1024 * 1024 || (totalSize != null && (totalSize < 0 || totalSize > MAX_FILE_SIZE))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid upload chunk");
        }
        fileUploadRepository.findByFileId(fileId).ifPresent(existing -> {
            if (!uploaderId.equals(existing.getUploaderId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Upload belongs to another user");
            if (Boolean.TRUE.equals(existing.getUploadComplete())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload already complete");
        });

        log.debug("分片上传请求: fileId={}, chunkIndex={}/{}", fileId, chunkIndex, totalChunks);

        try {
            // 创建临时分片目录
            File chunksDir = storage.resolve("chunks/" + uploaderId + "/" + fileId).toFile();
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
                File uploadDir = storage.resolve(dateDir).toFile();
                if (!uploadDir.exists()) {
                    uploadDir.mkdirs();
                }

                String extension = getExtension(filename);
                String storedName = fileId + extension;
                String filePath = dateDir + "/" + storedName;

                // 合并分片
                mergeChunks(uploaderId, fileId, totalChunks, totalSize, storage.resolve(filePath));

                // 计算合并后文件的MD5
                byte[] fileBytes = Files.readAllBytes(storage.resolve(filePath));
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
    @GetMapping("/access")
    public ResponseEntity<Map<String, Object>> getFileAccess(@RequestParam String reference) {
        Long actor = CurrentUser.require();
        FileUpload file = fileAccessService.findByReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(fileAccessService.signedAccess(file, actor));
    }

    @GetMapping("/{fileId}/info")
    public ResponseEntity<Map<String, Object>> getFileInfo(@PathVariable String fileId) {
        Long actor = CurrentUser.require();
        Optional<FileUpload> fileOpt = fileUploadRepository.findByFileId(fileId);
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        fileAccessService.requireReadable(fileOpt.get(), actor);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(buildResponse(fileOpt.get()));
    }

    /**
     * 下载文件
     */
    @GetMapping("/download/{fileId}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileId,
            @RequestParam(required = false) Long expiresAt,
            @RequestParam(required = false) Long viewerId,
            @RequestParam(required = false) String signature) {

        Optional<FileUpload> fileOpt = fileUploadRepository.findByFileId(fileId);
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FileUpload file = fileOpt.get();
        fileAccessService.requireRequestAccess(file, expiresAt, viewerId, signature);

        // 检查文件是否过期
        if (file.getExpiresAt() != null && file.getExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(410).build(); // Gone
        }

        try {
            Path candidate = storage.resolve(file.getFilePath());
            if (!Files.isRegularFile(candidate)) return ResponseEntity.notFound().build();
            Path filePath = candidate.toRealPath();
            if (!filePath.startsWith(storage.root().toRealPath())) return ResponseEntity.notFound().build();
            Resource resource = new UrlResource(filePath.toUri());

            String encodedFilename = URLEncoder.encode(file.getOriginalName(), StandardCharsets.UTF_8)
                    .replace("+", "%20");

            return ResponseEntity.ok()
                    .contentType(FileAccessService.safeContentType(file.getMimeType()))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "sandbox")
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
    public ResponseEntity<Resource> previewFile(@PathVariable String fileId,
            @RequestParam(required = false) Long expiresAt,
            @RequestParam(required = false) Long viewerId,
            @RequestParam(required = false) String signature) {

        Optional<FileUpload> fileOpt = fileUploadRepository.findByFileId(fileId);
        if (fileOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FileUpload file = fileOpt.get();
        fileAccessService.requireRequestAccess(file, expiresAt, viewerId, signature);

        // 检查文件是否过期
        if (file.getExpiresAt() != null && file.getExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(410).build(); // Gone
        }

        try {
            Path candidate = storage.resolve(file.getFilePath());
            if (!Files.isRegularFile(candidate)) return ResponseEntity.notFound().build();
            Path filePath = candidate.toRealPath();
            if (!filePath.startsWith(storage.root().toRealPath())) return ResponseEntity.notFound().build();
            Resource resource = new UrlResource(filePath.toUri());

            return ResponseEntity.ok()
                    .contentType(FileAccessService.safeContentType(file.getMimeType()))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "sandbox")
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
        String extension = filename.substring(filename.lastIndexOf("."));
        return extension.matches("\\.[A-Za-z0-9]{1,12}") ? extension : "";
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

    private void mergeChunks(Long uploaderId, String fileId, int totalChunks, Long totalSize, Path mergedPath) throws IOException {
        Path chunks = storage.resolve("chunks/" + uploaderId + "/" + fileId);
        long size = 0;
        for (int i = 0; i < totalChunks; i++) {
            Path chunk = chunks.resolve("chunk_" + i);
            if (!Files.isRegularFile(chunk)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload chunks missing");
            size += Files.size(chunk);
        }
        if (size > MAX_FILE_SIZE || (totalSize != null && size != totalSize)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid total upload size");
        }
        try (var out = Files.newOutputStream(mergedPath, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            for (int i = 0; i < totalChunks; i++) Files.copy(chunks.resolve("chunk_" + i), out);
        }
        deleteDirectory(chunks.toFile());
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

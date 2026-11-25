package com.nexus.chat.controller;

import com.nexus.chat.model.FileUpload;
import com.nexus.chat.repository.FileUploadRepository;
import com.nexus.chat.service.FileAccessService;
import com.nexus.chat.storage.UploadStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

@RestController
@RequiredArgsConstructor
public class UploadResourceController {
    private final UploadStorage storage;
    private final FileUploadRepository files;
    private final FileAccessService access;

    @GetMapping("/uploads/{*path}")
    public ResponseEntity<Resource> readUpload(@PathVariable String path,
            @RequestParam(required = false) Long expiresAt,
            @RequestParam(required = false) Long viewerId,
            @RequestParam(required = false) String signature) throws IOException {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        Path target;
        try {
            target = storage.resolve(relative);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        }
        String url = "/uploads/" + relative;
        Optional<FileUpload> file = files.findByFilePath(relative);
        boolean avatar = relative.matches("avatars/avatar_[0-9]+_[a-f0-9-]{36}\\.(png|jpg|gif|webp)") || access.isPublicAvatar(url);
        if (!avatar) {
            FileUpload upload = file.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found"));
            access.requireRequestAccess(upload, expiresAt, viewerId, signature);
            if (upload.getExpiresAt() != null && upload.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new ResponseStatusException(HttpStatus.GONE, "Resource expired");
            }
        }
        if (!Files.isRegularFile(target) || !target.toRealPath().startsWith(storage.root().toRealPath())) {
            return ResponseEntity.notFound().build();
        }
        String type = file.map(FileUpload::getMimeType).orElseGet(() -> {
            try { return Files.probeContentType(target); } catch (IOException e) { return null; }
        });
        return ResponseEntity.ok().contentType(FileAccessService.safeContentType(type))
                .header(HttpHeaders.CACHE_CONTROL, avatar ? "public, max-age=300" : "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .body(new UrlResource(target.toUri()));
    }
}

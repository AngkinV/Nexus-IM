package com.nexus.chat.service;

import com.nexus.chat.model.FileUpload;
import com.nexus.chat.repository.ChatRepository;
import com.nexus.chat.repository.FileUploadRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.repository.UserRepository;
import com.nexus.chat.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class FileAccessService {
    private static final long URL_LIFETIME_MS = 10 * 60 * 1000;
    private static final Set<String> AVATAR_TYPES = Set.of("image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp");
    private final FileUploadRepository files;
    private final MessageRepository messages;
    private final UserRepository users;
    private final ChatRepository chats;
    private final SecretKeySpec signingKey;

    public FileAccessService(FileUploadRepository files, MessageRepository messages, UserRepository users,
                             ChatRepository chats, @Value("${jwt.secret}") String secret) {
        this.files = files;
        this.messages = messages;
        this.users = users;
        this.chats = chats;
        this.signingKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public Optional<FileUpload> findByReference(String reference) {
        String path = referencePath(reference);
        if (path.startsWith("/uploads/")) return files.findByFilePath(path.substring("/uploads/".length()));
        if (path.matches("^/(api/)?files/(download|preview)/[A-Za-z0-9_-]+$")) {
            return files.findByFileId(path.substring(path.lastIndexOf('/') + 1));
        }
        return Optional.empty();
    }

    public void requireReadable(FileUpload file, Long actor) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (!Boolean.TRUE.equals(file.getUploadComplete())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not ready");
        if (Objects.equals(actor, file.getUploaderId())) {
            requireNotExpired(file);
            return;
        }
        String id = file.getFileId();
        List<String> references = List.of("/uploads/" + file.getFilePath(), "/files/download/" + id,
                "/files/preview/" + id, "/api/files/download/" + id, "/api/files/preview/" + id);
        if (!messages.existsReadableFileReference(actor, references)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "File access denied");
        }
        requireNotExpired(file);
    }

    private void requireNotExpired(FileUpload file) {
        if (file.getExpiresAt() != null && !file.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "File expired");
        }
    }

    public String authorizeAttachment(String reference, Long actor) {
        if (reference == null || reference.isBlank()) return reference;
        String path = referencePath(reference);
        if (!path.startsWith("/uploads/") && !path.startsWith("/files/") && !path.startsWith("/api/files/")) return reference;
        if (isPublicAvatar(path)) return path;
        FileUpload file = findByReference(path).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));
        requireReadable(file, actor);
        return "/api/files/download/" + file.getFileId();
    }

    public boolean isPublicAvatar(String path) {
        return path.startsWith("/uploads/") && (users.existsByAvatarUrl(path) || chats.existsByAvatarUrl(path));
    }

    public String authorizeAvatar(String reference, Long actor) {
        if (reference == null || reference.isBlank()) return reference;
        String path = referencePath(reference);
        if (!path.startsWith("/uploads/") && !path.startsWith("/files/") && !path.startsWith("/api/files/")) return reference;
        if (isPublicAvatar(path)) return path;
        if (actor != null && path.matches("^/uploads/avatars/avatar_" + actor + "_[a-f0-9-]{36}\\.(png|jpg|gif|webp)$")) return path;
        FileUpload file = findByReference(path).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Avatar access denied"));
        String avatar = "/uploads/" + file.getFilePath();
        if (isPublicAvatar(avatar)) return avatar;
        if (actor == null || !Objects.equals(actor, file.getUploaderId()) || !Boolean.TRUE.equals(file.getUploadComplete()) || !AVATAR_TYPES.contains(file.getMimeType())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Avatar must be your own image upload");
        }
        return avatar;
    }

    public Map<String, Object> signedAccess(FileUpload file, Long actor) {
        requireReadable(file, actor);
        long expiresAt = System.currentTimeMillis() + URL_LIFETIME_MS;
        String query = "?expiresAt=" + expiresAt + "&viewerId=" + actor + "&signature=" + sign(file, actor, expiresAt);
        return Map.of("fileId", file.getFileId(), "expiresAt", expiresAt,
                "previewUrl", "/api/files/preview/" + file.getFileId() + query,
                "downloadUrl", "/api/files/download/" + file.getFileId() + query);
    }

    public void requireRequestAccess(FileUpload file, Long expiresAt, Long viewerId, String signature) {
        if (expiresAt == null && viewerId == null && signature == null) {
            requireReadable(file, CurrentUser.require());
            return;
        }
        long now = System.currentTimeMillis();
        if (expiresAt == null || viewerId == null || signature == null || expiresAt <= now || expiresAt > now + URL_LIFETIME_MS
                || !MessageDigest.isEqual(sign(file, viewerId, expiresAt).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "File link invalid or expired");
        }
        requireReadable(file, viewerId);
    }

    public static MediaType safeContentType(String type) {
        if (type != null && (AVATAR_TYPES.contains(type) || type.equals("application/pdf") || type.equals("text/plain")
                || type.matches("(audio|video)/[A-Za-z0-9.+-]+"))) return MediaType.parseMediaType(type);
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private String sign(FileUpload file, Long actor, long expiresAt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            // Domain separation prevents file capabilities from becoming JWT credentials.
            String message = "nexus-file-access-v1\n" + file.getFileId() + "\n" + file.getFilePath() + "\n" + actor + "\n" + expiresAt;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("File signing unavailable", e);
        }
    }

    private String referencePath(String reference) {
        try {
            String path = URI.create(reference).getPath();
            if (path == null || path.contains("\\") || path.contains("%") || path.contains(";") || path.contains("\u0000")
                    || List.of(path.split("/")).contains("..")) throw new IllegalArgumentException();
            return path;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file reference");
        }
    }
}

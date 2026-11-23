package com.nexus.chat.storage;

import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.FileUpload;
import com.nexus.chat.model.User;
import com.nexus.chat.repository.ChatRepository;
import com.nexus.chat.repository.FileUploadRepository;
import com.nexus.chat.repository.UserRepository;
import com.nexus.chat.service.FileCleanupService;
import com.nexus.chat.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** An avatar a user or a group set must still be on disk, and at the same URL, later. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AvatarStorageTest {
    private static final long USER = 7L;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};

    @TempDir static Path dir;
    @Mock UserRepository users;
    @Spy UploadStorage storage = new UploadStorage(dir.toString());
    @InjectMocks UserService service;
    private User user;

    @BeforeEach void user() {
        user = new User();
        user.setId(USER);
        when(users.findById(USER)).thenReturn(Optional.of(user));
    }

    private static String dataUri(String type, byte[] bytes) {
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    @Test void theDirectoryDoesNotFollowTheWorkingDirectory() {
        assertTrue(storage.root().isAbsolute());
        assertEquals(dir.toAbsolutePath().normalize(), storage.root());
        assertEquals(storage.root().resolve("2026/11/22/a.jpeg"), storage.fileOf("/uploads/2026/11/22/a.jpeg"));
        assertNull(storage.fileOf("/uploads/../../etc/passwd"));
        assertNull(storage.fileOf("https://elsewhere.invalid/a.png"));
        assertNull(storage.fileOf("data:image/png;base64,AAAA"));
        assertThrows(IllegalArgumentException.class, () -> storage.resolve("../outside"));
    }

    @Test void aPastedImageIsSavedAsAFileAndOnlyItsUrlIsKept() throws Exception {
        String url = service.uploadAvatarBase64(USER, dataUri("image/png", PNG));

        assertTrue(url.matches("/uploads/avatars/avatar_7_[0-9a-f-]{36}\\.png"), url);
        assertEquals(url, user.getAvatarUrl());
        assertArrayEquals(PNG, Files.readAllBytes(storage.fileOf(url)));
        verify(users).save(user);
    }

    @Test void replacingAnAvatarRemovesOnlyTheFileThisServiceWrote() throws Exception {
        String first = service.uploadAvatarBase64(USER, dataUri("image/jpeg", PNG));
        String second = service.uploadAvatarBase64(USER, dataUri("image/webp", PNG));
        assertFalse(Files.exists(storage.fileOf(first)));
        assertTrue(Files.exists(storage.fileOf(second)));
        assertTrue(second.endsWith(".webp"));

        Path attachment = storage.resolve("2026/11/22/shared.png");
        Files.createDirectories(attachment.getParent());
        Files.write(attachment, PNG);
        user.setAvatarUrl("/uploads/2026/11/22/shared.png");
        service.uploadAvatarBase64(USER, dataUri("image/png", PNG));
        assertTrue(Files.exists(attachment), "an attachment has its own record and lifetime");
    }

    @Test void removingAnAvatarClearsTheRowAndTheFile() throws Exception {
        String url = service.uploadAvatarBase64(USER, dataUri("image/png", PNG));
        service.deleteAvatar(USER);
        assertNull(user.getAvatarUrl());
        assertFalse(Files.exists(storage.fileOf(url)));
    }

    @Test void whatIsNotASmallRasterImageIsRefusedAndTheOldAvatarStays() {
        user.setAvatarUrl("/uploads/avatars/kept.png");
        for (String bad : List.of(
                dataUri("image/svg+xml", "<svg onload='x'/>".getBytes()),
                dataUri("text/html", PNG),
                "https://elsewhere.invalid/a.png",
                "data:image/png;base64,***not-base64***",
                dataUri("image/png", new byte[0]),
                dataUri("image/png", new byte[5 * 1024 * 1024 + 1]))) {
            assertThrows(BusinessException.class, () -> service.uploadAvatarBase64(USER, bad));
        }
        assertEquals("/uploads/avatars/kept.png", user.getAvatarUrl());
        verify(users, never()).save(any());
    }

    @Test void anExpiredUploadIsKeptWhileItIsSomeonesAvatar() throws Exception {
        FileUploadRepository files = mock(FileUploadRepository.class);
        ChatRepository chats = mock(ChatRepository.class);
        FileCleanupService cleanup = new FileCleanupService(files, users, chats, storage);
        FileUpload groupAvatar = expired("2026/11/22/group.jpeg"), userAvatar = expired("2026/11/22/user.png"),
                attachment = expired("2026/11/22/attachment.png");
        when(files.findByExpiresAtBefore(any())).thenReturn(List.of(groupAvatar, userAvatar, attachment));
        when(files.findByUploadCompleteAndCreatedAtBefore(any(), any())).thenReturn(List.of());
        when(chats.existsByAvatarUrl("/uploads/2026/11/22/group.jpeg")).thenReturn(true);
        when(users.existsByAvatarUrl("/uploads/2026/11/22/user.png")).thenReturn(true);

        cleanup.cleanupExpiredFiles();

        assertTrue(Files.exists(storage.resolve(groupAvatar.getFilePath())));
        assertTrue(Files.exists(storage.resolve(userAvatar.getFilePath())));
        assertFalse(Files.exists(storage.resolve(attachment.getFilePath())));
        verify(files).delete(attachment);
        verify(files, never()).delete(groupAvatar);
        verify(files, never()).delete(userAvatar);
    }

    private FileUpload expired(String path) throws Exception {
        Path file = storage.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, PNG);
        FileUpload upload = new FileUpload();
        upload.setFileId(path);
        upload.setFilePath(path);
        upload.setExpiresAt(LocalDateTime.now().minusDays(1));
        return upload;
    }
}

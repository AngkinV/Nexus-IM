package com.nexus.chat.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.exception.GlobalExceptionHandler;
import com.nexus.chat.model.FileUpload;
import com.nexus.chat.repository.ChatRepository;
import com.nexus.chat.repository.FileUploadRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.repository.UserRepository;
import com.nexus.chat.security.CurrentUser;
import com.nexus.chat.service.FileAccessService;
import com.nexus.chat.storage.UploadStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileAccessMvcTest {
    @TempDir Path directory;
    @TempDir Path outside;
    private final FileUploadRepository files = mock(FileUploadRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ChatRepository chats = mock(ChatRepository.class);
    private FileAccessService access;
    private UploadStorage storage;
    private MockMvc mvc;
    private FileUpload upload;
    private final ObjectMapper json = new ObjectMapper();
    private static final String ID = "236ac4e3-5789-48ca-91d0-fbd4ef4ca2b5";

    @BeforeEach void setup() throws Exception {
        storage = new UploadStorage(directory.toString());
        access = new FileAccessService(files, messages, users, chats, "isolated-test-secret-not-used-outside-this-test");
        upload = new FileUpload();
        upload.setFileId(ID);
        upload.setUploaderId(1L);
        upload.setFilePath("2026/11/25/" + ID + ".txt");
        upload.setUploadComplete(true);
        upload.setOriginalName("fixture.txt");
        upload.setFileSize(7L);
        upload.setMimeType("text/plain");
        upload.setExpiresAt(LocalDateTime.now().plusDays(1));
        Path path = storage.resolve(upload.getFilePath());
        Files.createDirectories(path.getParent());
        Files.writeString(path, "private");
        when(files.findByFileId(ID)).thenReturn(Optional.of(upload));
        when(files.findByFilePath(upload.getFilePath())).thenReturn(Optional.of(upload));
        when(files.save(any())).thenAnswer(call -> call.getArgument(0));
        when(messages.existsReadableFileReference(eq(2L), anyList())).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new FileUploadController(files, storage, access), new UploadResourceController(storage, files, access))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private void login(long user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()));
    }

    private String download() { return "/api/files/download/" + ID; }
    private String preview() { return "/api/files/preview/" + ID; }

    @Test void anonymousPrivateReadsAndCapabilityIssuanceAreDenied() throws Exception {
        mvc.perform(get(download())).andExpect(status().isUnauthorized());
        mvc.perform(get(preview())).andExpect(status().isUnauthorized());
        mvc.perform(get("/uploads/" + upload.getFilePath())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/files/access").param("reference", download())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/files/" + ID + "/info")).andExpect(status().isUnauthorized());
    }

    @Test void onlyUploaderAndActualChatMemberCanRead() throws Exception {
        for (long actor : new long[]{1, 2}) {
            login(actor);
            mvc.perform(get(download())).andExpect(status().isOk()).andExpect(content().bytes("private".getBytes()));
            mvc.perform(get(preview())).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        }
        login(3);
        mvc.perform(get(download())).andExpect(status().isForbidden());
        mvc.perform(get(preview())).andExpect(status().isForbidden());
        mvc.perform(get("/api/files/" + ID + "/info")).andExpect(status().isForbidden());
    }

    @Test void capabilityIsShortLivedAndCannotChangeViewerOrFile() throws Exception {
        login(1);
        JsonNode signed = json.readTree(mvc.perform(get("/api/files/access").param("reference", "/files/download/" + ID))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString());
        String link = signed.get("downloadUrl").asText();
        assertFalse(link.contains("Bearer"));
        SecurityContextHolder.clearContext();
        mvc.perform(get(link)).andExpect(status().isOk());
        mvc.perform(get(link.replace("viewerId=1", "viewerId=3"))).andExpect(status().isUnauthorized());
        mvc.perform(get(download()).param("expiresAt", "1").param("viewerId", "1").param("signature", "expired"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(preview()).param("signature", "incomplete")).andExpect(status().isUnauthorized());
    }

    @Test void capabilityRechecksMembershipAfterRevocation() throws Exception {
        login(2);
        String link = (String) access.signedAccess(upload, CurrentUser.require()).get("downloadUrl");
        SecurityContextHolder.clearContext();
        when(messages.existsReadableFileReference(eq(2L), anyList())).thenReturn(false);
        mvc.perform(get(link)).andExpect(status().isForbidden());
    }

    @Test void privateFileCannotBePublishedByAvatarAliasesOrForgedMessageReference() {
        for (String reference : new String[]{download(), preview(), "/files/download/" + ID, "/uploads/" + upload.getFilePath()}) {
            ResponseStatusException denied = assertThrows(ResponseStatusException.class, () -> access.authorizeAvatar(reference, 3L));
            assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
            assertThrows(ResponseStatusException.class, () -> access.authorizeAvatar(reference, 1L));
        }
        assertThrows(ResponseStatusException.class, () -> access.authorizeAttachment(download(), 3L));
        verify(messages, never()).save(any());
        upload.setMimeType("image/png");
        assertEquals("/uploads/" + upload.getFilePath(), access.authorizeAvatar(download(), 1L));
        assertThrows(ResponseStatusException.class, () -> access.authorizeAvatar(download(), null));
    }

    @Test void establishedPublicAvatarAndGeneratedRasterAvatarStillLoadAnonymously() throws Exception {
        when(chats.existsByAvatarUrl("/uploads/" + upload.getFilePath())).thenReturn(true);
        upload.setMimeType("image/png");
        mvc.perform(get("/uploads/" + upload.getFilePath())).andExpect(status().isOk());
        String path = "avatars/avatar_1_" + UUID.randomUUID() + ".png";
        Files.createDirectories(storage.resolve("avatars"));
        Files.write(storage.resolve(path), new byte[]{1, 2, 3});
        mvc.perform(get("/uploads/" + path)).andExpect(status().isOk());
        assertEquals("/uploads/" + upload.getFilePath(), access.authorizeAvatar(download(), null));
    }

    @Test void allFileEndpointsRefuseSymlinksOutsideUploadRoot() throws Exception {
        Path secret = outside.resolve("not-an-upload.txt");
        Files.writeString(secret, "outside");
        Path candidate = storage.resolve(upload.getFilePath());
        Files.delete(candidate);
        Files.createSymbolicLink(candidate, secret);
        login(1);
        mvc.perform(get(download())).andExpect(status().isNotFound());
        mvc.perform(get(preview())).andExpect(status().isNotFound());
        mvc.perform(get("/uploads/" + upload.getFilePath())).andExpect(status().isNotFound());
    }

    @Test void unknownDirectoriesAndActiveContentAreNotPublic() throws Exception {
        mvc.perform(get("/uploads/chunks/1/unknown/chunk_0")).andExpect(status().isNotFound());
        assertEquals("application/octet-stream", FileAccessService.safeContentType("text/html").toString());
        assertEquals("application/octet-stream", FileAccessService.safeContentType("image/svg+xml").toString());
        login(1);
        upload.setMimeType("text/html");
        mvc.perform(get(preview())).andExpect(status().isOk()).andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andExpect(header().string("Content-Security-Policy", "sandbox"));
    }

    @Test void deduplicationUsesTheAuthenticatedUploaderOnly() throws Exception {
        login(2);
        mvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", "fixture.txt", "text/plain", "private".getBytes())))
                .andExpect(status().isOk());
        verify(files).findFirstByMd5HashAndUploaderIdAndUploadCompleteTrue(anyString(), eq(2L));
        verify(files, never()).findByMd5Hash(anyString());
        verify(files).save(argThat(file -> file.getUploaderId().equals(2L) && !file.getFileId().equals(ID)));
    }

    @Test void expiredDedupCandidateCannotReturnAnUnusableOldLink() throws Exception {
        login(1);
        upload.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(files.findFirstByMd5HashAndUploaderIdAndUploadCompleteTrue(anyString(), eq(1L))).thenReturn(Optional.of(upload));
        String body = mvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", "fixture.txt", "text/plain", "private".getBytes())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNotEquals(ID, json.readTree(body).get("fileId").asText());
    }

    @Test void expiredPrivateFilesDoNotIssueFreshCapabilitiesOrMetadata() throws Exception {
        upload.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        for (long actor : new long[]{1, 2}) {
            login(actor);
            mvc.perform(get("/api/files/access").param("reference", download())).andExpect(status().isGone());
            mvc.perform(get("/api/files/" + ID + "/info")).andExpect(status().isGone());
            mvc.perform(get(download())).andExpect(status().isGone());
        }
        login(3);
        mvc.perform(get(download())).andExpect(status().isForbidden());
    }

    @Test void chunkPathsCannotBeSharedAcrossAccountsOrEscapeStorage() throws Exception {
        login(2);
        mvc.perform(multipart("/api/files/upload/chunk").file(new MockMultipartFile("file", new byte[]{1}))
                .param("fileId", ID).param("chunkIndex", "0").param("totalChunks", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/files/upload/chunk").file(new MockMultipartFile("file", new byte[]{1}))
                .param("fileId", "../../outside").param("chunkIndex", "0").param("totalChunks", "1"))
                .andExpect(status().isBadRequest());
        String pending = UUID.randomUUID().toString();
        login(1);
        mvc.perform(multipart("/api/files/upload/chunk").file(new MockMultipartFile("file", new byte[]{1}))
                .param("fileId", pending).param("chunkIndex", "0").param("totalChunks", "2").param("totalSize", "2"))
                .andExpect(status().isOk());
        login(2);
        mvc.perform(multipart("/api/files/upload/chunk").file(new MockMultipartFile("file", new byte[]{2}))
                .param("fileId", pending).param("chunkIndex", "1").param("totalChunks", "2").param("totalSize", "2"))
                .andExpect(status().isBadRequest());
        assertTrue(Files.exists(storage.resolve("chunks/1/" + pending + "/chunk_0")));
        verify(files, never()).save(any());
    }
}

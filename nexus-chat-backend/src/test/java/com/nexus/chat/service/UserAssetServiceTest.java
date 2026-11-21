package com.nexus.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real filesystem persistence and owner separation; does not touch the user's library. */
class UserAssetServiceTest {
    @TempDir Path directory;
    UserAssetService assets;

    @BeforeEach void setup() {
        assets = newService();
    }
    UserAssetService newService() {
        UserAssetService service = new UserAssetService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "rootDir", directory.resolve("owned").toString());
        ReflectionTestUtils.setField(service, "builtInModelsDir", directory.resolve("builtins").toString());
        ReflectionTestUtils.setField(service, "builtInMotionsConfig", directory.resolve("motions.json").toString());
        return service;
    }
    MockMultipartFile motion() {
        return new MockMultipartFile("file", "walk.fbx", "application/octet-stream",
                "Kaydara FBX Binary  \0\032\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    @Test void ownedBytesAndRecordPersistAcrossServiceRecreation() throws Exception {
        Map<String, Object> uploaded = assets.upload(7L, "motion", motion());
        String id = (String) uploaded.get("id");
        UserAssetService restarted = newService();
        assertArrayEquals(motion().getBytes(), Files.readAllBytes(restarted.file(7L, "motion", id)));
        assertEquals(1, ((java.util.List<?>) restarted.listMotions(7L).get("motions")).size());
        assertEquals(0, ((java.util.List<?>) restarted.listMotions(8L).get("motions")).size());
        assertThrows(ResponseStatusException.class, () -> restarted.file(8L, "motion", id));
    }
    @Test void renameChangesLabelNotStableFileBinding() throws Exception {
        Map<String, Object> original = assets.upload(7L, "motion", motion());
        Map<String, Object> renamed = assets.rename(7L, "motion", (String) original.get("id"), "New display name");
        assertEquals(original.get("fileUrl"), renamed.get("fileUrl"));
        assertEquals("New display name", renamed.get("fileName"));
        assertThrows(ResponseStatusException.class, () -> assets.rename(8L, "motion", (String) original.get("id"), "stolen"));
    }
    @Test void deleteOnlyAffectsTheOwnersMotion() throws Exception {
        Map<String, Object> uploaded = assets.upload(7L, "motion", motion());
        String id = (String) uploaded.get("id");
        assertThrows(ResponseStatusException.class, () -> assets.deleteMotion(8L, id));
        assertTrue(Files.exists(assets.file(7L, "motion", id)));
        assets.deleteMotion(7L, id);
        assertThrows(ResponseStatusException.class, () -> assets.file(7L, "motion", id));
    }
    @Test void filenamesCannotEscapeTheUserDirectory() {
        MockMultipartFile malicious = new MockMultipartFile("file", "../other.fbx", "application/octet-stream", new byte[]{1});
        assertThrows(ResponseStatusException.class, () -> assets.upload(7L, "motion", malicious));
        assertThrows(ResponseStatusException.class, () -> assets.upload(7L, "unknown", motion()));
        assertThrows(ResponseStatusException.class, () -> assets.upload(7L, "model", motion()));
    }
    @Test void legacySharedUploadsAreNotAssignedToAnAccount() throws Exception {
        Files.writeString(directory.resolve("motions.json"), "{\"motions\":[{\"key\":\"walk\",\"file\":\"walk.fbx\"},{\"key\":\"old-upload\",\"file\":\"old.fbx\",\"uploaded\":true}]}");
        var motions = (java.util.List<?>) assets.listMotions(7L).get("motions");
        assertEquals(1, motions.size());
        assertEquals("walk", ((Map<?, ?>) motions.get(0)).get("key"));
    }
}

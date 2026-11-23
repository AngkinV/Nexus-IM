package com.nexus.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.repository.CompanionRoleRepository;
import com.nexus.chat.service.CompanionModelAssetCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Only temporary files and mocked database transactions; no application startup. */
class CompanionAssetSafetyTest {
    @TempDir Path root;
    Path models;
    CompanionModelAssetCatalog catalog;
    CompanionAssetController controller;
    CompanionRoleRepository roles;
    PlatformTransactionManager transactions;

    @BeforeEach void setup() throws Exception {
        models = Files.createDirectory(root.resolve("models"));
        catalog = new CompanionModelAssetCatalog();
        ReflectionTestUtils.setField(catalog, "modelsDir", models.toString());
        roles = mock(CompanionRoleRepository.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        controller = new CompanionAssetController(new ObjectMapper(), catalog, roles, transactions);
        ReflectionTestUtils.setField(controller, "modelsDir", models.toString());
    }

    byte[] vrm() {
        return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546C67).putInt(2).putInt(24).putInt(4).putInt(0x4E4F534A)
                .put(new byte[]{'{', '}', ' ', ' '}).array();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.vrm", "/tmp/outside.vrm", "a\\b.vrm", "%2e%2e%2foutside.vrm"})
    void maliciousOldNamesCannotMoveOutsideFiles(String name) throws Exception {
        Path outside = root.resolve("outside.vrm");
        Files.write(outside, vrm());
        var result = controller.renameModel(Map.of("oldFileName", name, "newFileName", "b.vrm"));
        assertEquals(400, result.getStatusCode().value());
        assertTrue(Files.exists(outside));
        assertFalse(Files.exists(models.resolve("b.vrm")));
        verifyNoInteractions(roles, transactions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.vrm", "/tmp/outside.vrm", "x.vrm?file=1", "x.fbx"})
    void maliciousNewNameAndExtensionChangesAreRejected(String name) throws Exception {
        Files.write(models.resolve("a.vrm"), vrm());
        assertEquals(400, controller.renameModel(Map.of("oldFileName", "a.vrm", "newFileName", name)).getStatusCode().value());
        assertTrue(Files.exists(models.resolve("a.vrm")));
        verifyNoInteractions(roles, transactions);
    }

    @Test void symlinkSourceCannotTouchItsTarget() throws Exception {
        Path outside = root.resolve("outside.vrm");
        Files.write(outside, vrm());
        Files.createSymbolicLink(models.resolve("alias.vrm"), outside);
        assertEquals(404, controller.renameModel(Map.of("oldFileName", "alias.vrm", "newFileName", "b.vrm")).getStatusCode().value());
        assertArrayEquals(vrm(), Files.readAllBytes(outside));
        verifyNoInteractions(roles);
    }

    @Test void legalRenameUpdatesReferencesAndKeepsTheFile() throws Exception {
        Files.write(models.resolve("a.vrm"), vrm());
        var result = controller.renameModel(Map.of("oldFileName", "a.vrm", "newFileName", "b.vrm"));
        assertEquals(200, result.getStatusCode().value());
        assertFalse(Files.exists(models.resolve("a.vrm")));
        assertArrayEquals(vrm(), Files.readAllBytes(models.resolve("b.vrm")));
        verify(roles).rebindModelAsset("/models/a.vrm", "a.vrm", "/models/b.vrm");
        verify(transactions).commit(any());
    }

    @Test void failedReferenceUpdateRollsBackTheFileMove() throws Exception {
        Files.write(models.resolve("a.vrm"), vrm());
        when(roles.rebindModelAsset(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("test transaction failure"));
        assertEquals(500, controller.renameModel(Map.of("oldFileName", "a.vrm", "newFileName", "b.vrm")).getStatusCode().value());
        assertArrayEquals(vrm(), Files.readAllBytes(models.resolve("a.vrm")));
        assertFalse(Files.exists(models.resolve("b.vrm")));
        verify(transactions).rollback(any());
    }

    @Test void namingCollisionDoesNotOverwriteAnExistingAsset() throws Exception {
        Files.write(models.resolve("a.vrm"), vrm());
        byte[] original = {1, 2, 3};
        Files.write(models.resolve("b.vrm"), original);
        var result = controller.renameModel(Map.of("oldFileName", "a.vrm", "newFileName", "b.vrm"));
        assertEquals(200, result.getStatusCode().value());
        assertEquals("b-1.vrm", result.getBody().get("newFileName"));
        assertArrayEquals(original, Files.readAllBytes(models.resolve("b.vrm")));
    }

    @Test void malformedUploadIsRejectedAndRemoved() throws Exception {
        var bad = new MockMultipartFile("file", "bad.vrm", "application/octet-stream", new byte[]{1, 2, 3});
        assertEquals(400, controller.uploadModel(bad).getStatusCode().value());
        assertFalse(Files.exists(models.resolve("bad.vrm")));
    }

    @Test void duplicateUploadNameUsesANewFileRatherThanOverwrite() throws Exception {
        Files.write(models.resolve("a.vrm"), vrm());
        var upload = new MockMultipartFile("file", "a.vrm", "application/octet-stream", vrm());
        var result = controller.uploadModel(upload);
        assertEquals(200, result.getStatusCode().value());
        assertEquals("a-1.vrm", result.getBody().get("fileName"));
        assertArrayEquals(vrm(), Files.readAllBytes(models.resolve("a.vrm")));
    }

    @Test void aBadCandidateDoesNotHideOtherValidModels() throws Exception {
        Files.write(models.resolve("good.vrm"), vrm());
        Files.write(models.resolve("bad.vrm"), new byte[]{1, 2, 3});
        assertEquals(2, catalog.list().size());
        assertEquals("good.vrm", catalog.listForStage().get(0).fileName());
        assertEquals(1, catalog.listForStage().size());
    }
}

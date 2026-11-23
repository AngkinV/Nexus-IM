package com.nexus.chat.service;

import com.nexus.chat.dto.CompanionRoleDTO;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompanionShapeBindingTest {
    @TempDir Path models;
    CompanionService service;
    CompanionRole role;
    CompanionRoleRepository roles;
    CompanionMessageRepository messages;
    UserAssetService userAssets;

    @BeforeEach void setup() throws Exception {
        var catalog = new CompanionModelAssetCatalog();
        ReflectionTestUtils.setField(catalog, "modelsDir", models.toString());
        byte[] container = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546C67).putInt(2).putInt(24).putInt(4).putInt(0x4E4F534A)
                .put(new byte[]{'{', '}', ' ', ' '}).array();
        Files.write(models.resolve("a.vrm"), container);
        Files.write(models.resolve("b.glb"), new byte[]{1, 2, 3});
        roles = mock(CompanionRoleRepository.class);
        messages = mock(CompanionMessageRepository.class);
        userAssets = mock(UserAssetService.class);
        service = new CompanionService(roles, mock(CompanionGrowthRepository.class), mock(CompanionMemoryRepository.class),
                mock(CompanionConversationRepository.class), messages, mock(CompanionModelBindingRepository.class),
                mock(ModelCredentialRepository.class), mock(CompanionStatusRepository.class), new CompanionFallbackService(),
                mock(CompanionModelService.class), mock(CompanionCryptoService.class), mock(TavernSessionRepository.class),
                mock(CompanionModelResolver.class), catalog, userAssets);
        role = new CompanionRole();
        role.setId(41L); role.setUserId(7L); role.setName("original");
        role.setModelUrl("/models/a.vrm"); role.setModelType("vrm");
        when(roles.findByIdAndUserId(41L, 7L)).thenReturn(Optional.of(role));
        var message = new CompanionMessage();
        message.setContent("keep preview"); message.setCreatedAt(LocalDateTime.of(2026, 11, 21, 19, 8));
        when(messages.findLatestForRole(7L, 41L)).thenReturn(Optional.of(message));
    }

    CompanionRoleDTO update(String url, String type) {
        var update = new CompanionRoleDTO(); update.setModelUrl(url); update.setModelType(type);
        return update;
    }

    @Test void bareReferencesAreCanonicalAndPreviewRemainsReal() {
        var result = service.updateRole(7L, 41L, update(" a.vrm ", "vrm"));
        assertEquals("/models/a.vrm", result.getModelUrl());
        assertEquals("vrm", result.getModelType());
        assertEquals("keep preview", result.getLastMessage());
        assertEquals(LocalDateTime.of(2026, 11, 21, 19, 8), result.getLastMessageAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.vrm", "file:///tmp/x.vrm", "javascript:alert(1)", "/models/%2e%2e/x.vrm", "/models/missing.vrm"})
    void invalidBindingsCannotModifyTheRole(String url) {
        var update = update(url, "vrm"); update.setName("must not apply");
        var failure = assertThrows(ResponseStatusException.class, () -> service.updateRole(7L, 41L, update));
        assertEquals(400, failure.getStatusCode().value());
        assertEquals("original", role.getName());
        assertEquals("/models/a.vrm", role.getModelUrl());
        verify(roles, never()).save(any());
    }

    @Test void modelTypeMustMatchActualLocalFormat() {
        assertThrows(ResponseStatusException.class, () -> service.updateRole(7L, 41L, update("/models/b.glb", "vrm")));
        var result = service.updateRole(7L, 41L, update("/models/b.glb", "gltf"));
        assertEquals("gltf", result.getModelType());
    }

    @Test void existingHttpModelsRemainSupportedWithoutFetchingThem() {
        var result = service.updateRole(7L, 41L, update("https://example.invalid/shape.glb", "gltf"));
        assertEquals("https://example.invalid/shape.glb", result.getModelUrl());
        assertEquals("gltf", result.getModelType());
    }

    @Test void aModelFromTheUsersOwnLibraryIsBoundAsItIsWhenTheLibraryHoldsIt() throws Exception {
        String owned = "/api/companion/user-assets/file/model/0b0e6f0e-3c1d-4d55-9a53-0c2b8b6a7f11";
        var result = service.updateRole(7L, 41L, update(owned, "vrm"));
        assertEquals(owned, result.getModelUrl());
        assertEquals("vrm", result.getModelType());
        assertEquals("keep preview", result.getLastMessage());
        // Ownership is the library's to judge, for this user and this id.
        verify(userAssets).file(7L, "model", "0b0e6f0e-3c1d-4d55-9a53-0c2b8b6a7f11");
    }

    @Test void aLibraryModelThisUserDoesNotHoldCannotModifyTheRole() throws Exception {
        when(userAssets.file(7L, "model", "0b0e6f0e-3c1d-4d55-9a53-0c2b8b6a7f11"))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Asset not found"));
        var update = update("/api/companion/user-assets/file/model/0b0e6f0e-3c1d-4d55-9a53-0c2b8b6a7f11", "vrm");
        update.setName("must not apply");
        var failure = assertThrows(ResponseStatusException.class, () -> service.updateRole(7L, 41L, update));
        assertEquals(400, failure.getStatusCode().value());
        assertEquals(CompanionModelAssetCatalog.MODEL_NOT_FOUND, failure.getReason());
        assertEquals("original", role.getName());
        assertEquals("/models/a.vrm", role.getModelUrl());
        verify(roles, never()).save(any());
    }

    @Test void credentialBearingUrlsAreRejected() {
        assertThrows(ResponseStatusException.class, () -> service.updateRole(7L, 41L,
                update("https://identity@example.invalid/a.vrm", "vrm")));
        verify(roles, never()).save(any());
    }

    @Test void clearingShapeKeepsHistoryAndDoesNotDeleteTheRole() {
        var result = service.updateRole(7L, 41L, update("", "vrm"));
        assertNull(result.getModelUrl()); assertNull(result.getModelType());
        assertEquals("keep preview", result.getLastMessage());
        verify(roles).save(role); verify(roles, never()).delete(any());
    }

    @Test void ordinaryProfileUpdateDoesNotRevalidateOrRewriteAnExistingShape() {
        var update = new CompanionRoleDTO(); update.setTone("new tone");
        var result = service.updateRole(7L, 41L, update);
        assertEquals("/models/a.vrm", result.getModelUrl());
        assertEquals("new tone", result.getTone());
        assertEquals("keep preview", result.getLastMessage());
    }
}

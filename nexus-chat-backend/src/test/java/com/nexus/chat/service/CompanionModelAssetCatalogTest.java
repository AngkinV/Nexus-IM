package com.nexus.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The catalogue is what keeps an invited guest from arriving with a model URL
 * that points nowhere, so the path rules here are the security boundary: only a
 * plain file name inside the models directory is ever accepted.
 */
class CompanionModelAssetCatalogTest {

    @TempDir
    Path modelsDir;

    private CompanionModelAssetCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new CompanionModelAssetCatalog();
        Field field = CompanionModelAssetCatalog.class.getDeclaredField("modelsDir");
        field.setAccessible(true);
        field.set(catalog, modelsDir.toString());
    }

    private void touch(String name) throws IOException {
        Files.write(modelsDir.resolve(name), new byte[] {1, 2, 3});
    }

    @Test
    void listsOnlyModelFilesSortedByName() throws IOException {
        touch("b.vrm");
        touch("a.vrm");
        touch("notes.txt");
        touch("c.glb");

        List<CompanionModelAssetCatalog.ModelAsset> found = catalog.list();

        assertEquals(List.of("a.vrm", "b.vrm", "c.glb"),
                found.stream().map(CompanionModelAssetCatalog.ModelAsset::fileName).toList());
        assertEquals("/models/a.vrm", found.get(0).fileUrl());
        assertEquals("vrm", found.get(0).type());
        assertEquals("glb", found.get(2).type());
    }

    @Test
    void emptyDirectoryListsNothingRatherThanFailing() {
        assertTrue(catalog.list().isEmpty());
    }

    @Test
    void missingDirectoryListsNothingRatherThanFailing() throws Exception {
        Field field = CompanionModelAssetCatalog.class.getDeclaredField("modelsDir");
        field.setAccessible(true);
        field.set(catalog, modelsDir.resolve("does-not-exist").toString());
        assertTrue(catalog.list().isEmpty());
        assertNull(catalog.pickForGuest(Set.of()));
    }

    @Test
    void acceptsAnExistingModelByUrlOrBareName() throws IOException {
        touch("a.vrm");
        assertTrue(catalog.exists("/models/a.vrm"));
        assertTrue(catalog.exists("a.vrm"));
        assertTrue(catalog.exists("  /models/a.vrm  "));
    }

    @Test
    void rejectsMissingFilesAndNonModels() throws IOException {
        touch("notes.txt");
        assertFalse(catalog.exists("/models/missing.vrm"));
        assertFalse(catalog.exists("/models/notes.txt"));
        assertFalse(catalog.exists(null));
        assertFalse(catalog.exists(""));
    }

    @Test
    void rejectsTraversalAndNestedPaths() throws IOException {
        touch("a.vrm");
        // A file that really exists one level up must still be unreachable.
        Files.write(modelsDir.getParent().resolve("outside.vrm"), new byte[] {1});

        assertFalse(catalog.exists("/models/../outside.vrm"));
        assertFalse(catalog.exists("../outside.vrm"));
        assertFalse(catalog.exists("/models/sub/a.vrm"));
        assertFalse(catalog.exists("/etc/passwd"));
    }

    @Test
    void picksAnUnusedModelFirst() throws IOException {
        touch("a.vrm");
        touch("b.vrm");

        CompanionModelAssetCatalog.ModelAsset picked = catalog.pickForGuest(Set.of("/models/a.vrm"));

        assertNotNull(picked);
        assertEquals("/models/b.vrm", picked.fileUrl());
    }

    @Test
    void reusesAModelWhenEveryOneIsTaken() throws IOException {
        touch("a.vrm");
        touch("b.vrm");

        CompanionModelAssetCatalog.ModelAsset picked =
                catalog.pickForGuest(Set.of("/models/a.vrm", "/models/b.vrm"));

        assertNotNull(picked);
        assertTrue(Set.of("/models/a.vrm", "/models/b.vrm").contains(picked.fileUrl()));
    }

    @Test
    void consecutiveInvitesDoNotAllLandOnTheSameFace() throws IOException {
        touch("a.vrm");
        touch("b.vrm");
        touch("c.vrm");

        // Three invites in a row, each told what is already worn.
        Set<String> inUse = new java.util.HashSet<>();
        for (int i = 0; i < 3; i++) {
            CompanionModelAssetCatalog.ModelAsset picked = catalog.pickForGuest(inUse);
            assertNotNull(picked);
            inUse.add(picked.fileUrl());
        }

        assertEquals(3, inUse.size(), "three invites should wear three different models");
    }

    @Test
    void returnsNullWhenNoModelHasEverBeenUploaded() {
        assertNull(catalog.pickForGuest(Set.of()));
    }
}

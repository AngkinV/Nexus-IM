package com.nexus.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The catalogue is what keeps an invited guest from arriving with a model URL
 * that points nowhere, so the path rules here are the security boundary: only a
 * plain file name inside the models directory is ever accepted.
 *
 * Everything runs against JUnit temp directories. The ".vrm" fixtures are a
 * 24-byte binary glTF header with an empty JSON chunk — enough for the container
 * check, not a model any loader could render.
 */
class CompanionModelAssetCatalogTest {

    @TempDir
    Path modelsDir;

    @TempDir
    Path outsideDir;

    private CompanionModelAssetCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new CompanionModelAssetCatalog();
        pointCatalogAt(modelsDir);
    }

    private void pointCatalogAt(Path dir) throws Exception {
        Field field = CompanionModelAssetCatalog.class.getDeclaredField("modelsDir");
        field.setAccessible(true);
        field.set(catalog, dir.toString());
    }

    /** A minimal binary glTF container: header, then one JSON chunk holding "{}". */
    static byte[] glb() {
        byte[] json = "{}  ".getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(20 + json.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546C67).putInt(2).putInt(20 + json.length)
                .putInt(json.length).putInt(0x4E4F534A).put(json)
                .array();
    }

    /** VRM fixtures get a real container header; everything else is opaque bytes. */
    private void touch(String name) throws IOException {
        Files.write(modelsDir.resolve(name),
                name.toLowerCase().endsWith(".vrm") ? glb() : new byte[] {1, 2, 3});
    }

    private static void assertRejected(HttpStatus status, String code, Runnable call) {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(status, failure.getStatusCode());
        assertEquals(code, failure.getReason());
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
        assertEquals(24L, found.get(0).size());
        // GLB is binary glTF: the frontend loads both through the glTF loader.
        assertEquals("gltf", found.get(2).type());
    }

    @Test
    void settingsListKeepsEveryTypeAndMapsGlbAndGltfToGltf() throws IOException {
        touch("a.glb");
        touch("b.gltf");
        touch("c.fbx");
        touch("d.VRM");

        List<CompanionModelAssetCatalog.ModelAsset> found = catalog.list();

        assertEquals(List.of("gltf", "gltf", "fbx", "vrm"),
                found.stream().map(CompanionModelAssetCatalog.ModelAsset::type).toList());
        assertEquals(List.of(false, false, false, true),
                found.stream().map(CompanionModelAssetCatalog.ModelAsset::stageReady).toList());
    }

    @Test
    void stageListHoldsOnlyVrmFilesThatOpenAsAContainer() throws IOException {
        touch("a.vrm");
        touch("b.glb");
        touch("c.fbx");
        // Right extension, wrong bytes.
        Files.write(modelsDir.resolve("short.vrm"), new byte[] {1, 2, 3});
        Files.write(modelsDir.resolve("text.vrm"), "this is not a model at all".getBytes(StandardCharsets.US_ASCII));
        // A real header cut off mid-upload: it declares more bytes than the file has.
        byte[] truncated = glb();
        ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 4096);
        Files.write(modelsDir.resolve("truncated.vrm"), truncated);
        // glTF 1.0 container: not something the VRM loader reads.
        byte[] oldVersion = glb();
        ByteBuffer.wrap(oldVersion).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 1);
        Files.write(modelsDir.resolve("v1.vrm"), oldVersion);

        assertEquals(List.of("/models/a.vrm"),
                catalog.listForStage().stream().map(CompanionModelAssetCatalog.ModelAsset::fileUrl).toList());
        // The settings list still shows what is really on disk.
        assertEquals(7, catalog.list().size());
    }

    @Test
    void emptyDirectoryListsNothingRatherThanFailing() {
        assertTrue(catalog.list().isEmpty());
        assertTrue(catalog.listForStage().isEmpty());
    }

    @Test
    void missingDirectoryListsNothingRatherThanFailing() throws Exception {
        pointCatalogAt(modelsDir.resolve("does-not-exist"));
        assertTrue(catalog.list().isEmpty());
        assertNull(catalog.pickForGuest(Set.of()));
    }

    @Test
    void aDirectoryThatCannotBeReadIsAnErrorNotAnEmptyCatalogue() throws Exception {
        // The configured path exists but is a file: Files.list fails.
        Path notADirectory = outsideDir.resolve("models-is-a-file");
        Files.write(notADirectory, new byte[] {1});
        pointCatalogAt(notADirectory);

        String code = CompanionModelAssetCatalog.CATALOG_UNAVAILABLE;
        assertRejected(HttpStatus.INTERNAL_SERVER_ERROR, code, () -> catalog.list());
        assertRejected(HttpStatus.INTERNAL_SERVER_ERROR, code, () -> catalog.listForStage());
        assertRejected(HttpStatus.INTERNAL_SERVER_ERROR, code, () -> catalog.pickForGuest(Set.of()));
        assertRejected(HttpStatus.INTERNAL_SERVER_ERROR, code, () -> catalog.exists("/models/a.vrm"));
        assertRejected(HttpStatus.INTERNAL_SERVER_ERROR, code, () -> catalog.requireStageAsset("/models/a.vrm"));
    }

    @Test
    void aDirectoryWithoutReadPermissionIsAnErrorNotAnEmptyCatalogue() throws Exception {
        touch("a.vrm");
        Files.setPosixFilePermissions(modelsDir, PosixFilePermissions.fromString("---------"));
        try {
            assertRejected(HttpStatus.INTERNAL_SERVER_ERROR, CompanionModelAssetCatalog.CATALOG_UNAVAILABLE,
                    () -> catalog.list());
        } finally {
            Files.setPosixFilePermissions(modelsDir, PosixFilePermissions.fromString("rwx------"));
        }
        assertEquals(1, catalog.list().size(), "readable again once the permission is back");
    }

    @Test
    void anUnreadableCandidateIsRejectedWithoutHidingOtherModels() throws Exception {
        touch("a.vrm");
        touch("b.vrm");
        Path locked = modelsDir.resolve("a.vrm");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assertRejected(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_FILE_INVALID,
                    () -> catalog.requireStageAsset("/models/a.vrm"));
            assertEquals(List.of("/models/b.vrm"), catalog.listForStage().stream()
                    .map(CompanionModelAssetCatalog.ModelAsset::fileUrl).toList());
            assertEquals(2, catalog.list().size());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void acceptsAnExistingModelByUrlOrBareName() throws IOException {
        touch("a.vrm");
        assertTrue(catalog.exists("/models/a.vrm"));
        assertTrue(catalog.exists("a.vrm"));
        assertTrue(catalog.exists("  /models/a.vrm  "));
    }

    @Test
    void everyAcceptedSpellingCanonicalisesToTheSameUrl() {
        assertEquals("/models/a.vrm", catalog.canonicalUrl("/models/a.vrm"));
        assertEquals("/models/a.vrm", catalog.canonicalUrl("a.vrm"));
        assertEquals("/models/a.vrm", catalog.canonicalUrl("  a.vrm \n"));
        assertEquals("/models/春雪 v2-final_01.vrm", catalog.canonicalUrl("/models/春雪 v2-final_01.vrm"));
        assertEquals("/models/Scene.GLB", catalog.canonicalUrl("Scene.GLB"));
        assertNull(catalog.canonicalUrl(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "/models/", "/models/ ",
            // traversal
            "../outside.vrm", "/models/../outside.vrm", "/models/..", "..", "a..vrm",
            // nested and absolute paths
            "/models/sub/a.vrm", "sub/a.vrm", "/models//a.vrm", "/models/a.vrm/", "/etc/passwd", "/a.vrm",
            "/models/models/a.vrm", "/Models/a.vrm",
            // percent-encoded separators and dots
            "/models/%2e%2e%2foutside.vrm", "%2e%2e/outside.vrm", "/models/sub%2Fa.vrm", "/models/..%2Foutside.vrm",
            "/models/%252e%252e%252foutside.vrm", "a%00.vrm",
            // remote and scheme URLs
            "https://evil.example/models/a.vrm", "http://127.0.0.1/a.vrm", "//evil.example/a.vrm",
            "file:///etc/a.vrm", "data:model/vrm;base64,AAAA.vrm", "javascript:a.vrm",
            // backslashes
            "\\models\\a.vrm", "/models/..\\outside.vrm", "/models/sub\\a.vrm", "C:\\models\\a.vrm", "..\\outside.vrm",
            // query, fragment, hidden files, non-models
            "a.vrm?x=1", "/models/a.vrm#frag", ".vrm", "/models/.hidden.vrm",
            "/models/notes.txt", "/models/a", "a.vrm.exe"
    })
    void rejectsAnythingThatIsNotAPlainModelFileName(String hostile) throws IOException {
        // Real files exist at the places these spellings aim for, so a rejection
        // is the rule holding, not the file being absent.
        touch("a.vrm");
        Files.createDirectories(modelsDir.resolve("sub"));
        Files.write(modelsDir.resolve("sub").resolve("a.vrm"), glb());
        Files.write(modelsDir.getParent().resolve("outside.vrm"), glb());

        assertNull(catalog.canonicalUrl(hostile));
        assertFalse(catalog.exists(hostile));
        assertRejected(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_URL_INVALID,
                () -> catalog.requireStageAsset(hostile));
    }

    @Test
    void rejectsControlCharactersInsideAName() {
        // Kept out of the parameter list above: NUL does not survive a test report.
        for (char control : new char[] {0x00, '\t', '\n', '\r', 0x1F, 0x7F}) {
            assertNull(catalog.canonicalUrl("a" + control + ".vrm"), "U+" + Integer.toHexString(control));
        }
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
    void aSymbolicLinkIsNeverAModelEvenWhenItsTargetIsOne() throws IOException {
        Path target = outsideDir.resolve("real.vrm");
        Files.write(target, glb());
        Files.createSymbolicLink(modelsDir.resolve("link.vrm"), target);
        // A linked directory must not open a second models directory either.
        Files.createSymbolicLink(modelsDir.resolve("linked"), outsideDir);

        assertTrue(catalog.list().isEmpty());
        assertTrue(catalog.listForStage().isEmpty());
        assertFalse(catalog.exists("/models/link.vrm"));
        assertFalse(catalog.exists("/models/linked/real.vrm"));
        assertNull(catalog.pickForGuest(Set.of()));
        assertRejected(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_NOT_FOUND,
                () -> catalog.requireStageAsset("/models/link.vrm"));
        assertRejected(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_URL_INVALID,
                () -> catalog.requireStageAsset("/models/linked/real.vrm"));
    }

    @Test
    void aNameThatDiffersOnlyByCaseIsNotTheSameModel() throws IOException {
        // On a case-insensitive disk the path would resolve; the directory entry does not match.
        touch("a.vrm");
        assertFalse(catalog.exists("/models/A.vrm"));
        assertRejected(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_NOT_FOUND,
                () -> catalog.requireStageAsset("/models/A.vrm"));
    }

    @Test
    void aChosenModelIsReturnedInCanonicalFormOrRefusedWithItsReason() throws IOException {
        touch("a.vrm");
        touch("b.glb");
        touch("c.fbx");
        Files.write(modelsDir.resolve("broken.vrm"), new byte[] {1, 2, 3});

        for (String spelling : List.of("/models/a.vrm", "a.vrm", "  /models/a.vrm ")) {
            CompanionModelAssetCatalog.ModelAsset asset = catalog.requireStageAsset(spelling);
            assertEquals("/models/a.vrm", asset.fileUrl());
            assertEquals("vrm", asset.type());
        }
        assertRejected(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_TYPE_UNSUPPORTED,
                () -> catalog.requireStageAsset("/models/b.glb"));
        assertRejected(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_TYPE_UNSUPPORTED,
                () -> catalog.requireStageAsset("c.fbx"));
        assertRejected(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_NOT_FOUND,
                () -> catalog.requireStageAsset("/models/missing.vrm"));
        assertRejected(HttpStatus.BAD_REQUEST, CompanionModelAssetCatalog.MODEL_FILE_INVALID,
                () -> catalog.requireStageAsset("/models/broken.vrm"));
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
    void aFullTavernDoesNotSeatTheSameFaceEveryTime() throws IOException {
        touch("a.vrm");
        touch("b.vrm");
        Set<String> everyModel = Set.of("/models/a.vrm", "/models/b.vrm");

        // The in-use set never changes here, which is exactly when the old
        // index-by-set-size pick returned one fixed file forever.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            seen.add(catalog.pickForGuest(everyModel).fileUrl());
        }

        // A fair pick misses one of two files 64 times in a row with probability 2^-63.
        assertEquals(everyModel, seen);
    }

    @Test
    void neverPicksAModelTheStageCannotShow() throws IOException {
        touch("b.glb");
        touch("c.fbx");
        Files.write(modelsDir.resolve("broken.vrm"), new byte[] {1, 2, 3});
        assertNull(catalog.pickForGuest(Set.of()), "no stage-ready model: the guest gets none");

        touch("a.vrm");
        for (int i = 0; i < 16; i++) {
            // Taken, but still the only one a guest can wear: reuse it rather than hand out a GLB.
            assertEquals("/models/a.vrm", catalog.pickForGuest(Set.of("/models/a.vrm")).fileUrl());
        }
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

package com.nexus.chat.service.agent;

import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.model.agent.AgentWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.nexus.chat.service.agent.AgentRunServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentWorkspaceServiceTest {
    private static final String ORIGINAL = "a\nb\n";
    private static final String PATCH = "--- a/demo.txt\n+++ b/demo.txt\n@@ -1,2 +1,2 @@\n a\n-b\n+c\n";
    private AgentWorkbenchTestStore store;
    private AgentRunService runs;
    private com.nexus.chat.agent.AgentScopeTestDouble authority;
    private AgentGatewayService python;
    private AgentWorkspaceService service;

    @BeforeEach
    void setUp() throws Exception {
        store = new AgentWorkbenchTestStore();
        runs = store.runService();
        // Artifact reads are authorized per run; these fixtures carry a real grant.
        authority = new com.nexus.chat.agent.AgentScopeTestDouble();
        org.springframework.test.util.ReflectionTestUtils.setField(runs, "scopes", authority.service());
        python = mock(AgentGatewayService.class);
        when(python.extractAttachment(eq(7L), anyString(), any(byte[].class))).thenAnswer(inv -> {
            byte[] bytes = inv.getArgument(2);
            return Map.of("fileName", inv.getArgument(1), "content", new String(bytes, StandardCharsets.UTF_8), "truncated", false, "sizeBytes", bytes.length);
        });
        when(python.previewArtifactPatch(eq(7L), eq("demo.txt"), eq(ORIGINAL), eq(PATCH)))
                .thenReturn(Map.of("content", "a\nc\n", "baseHash", hash(ORIGINAL), "patchHash", hash(PATCH)));
        service = workspaceService();
    }

    @Test
    void uploadsExtractedUtf8AndRestoresOwnedAttachmentMetadata() throws Exception {
        Map<String, Object> attachment = upload("demo.txt", "你好\n");
        assertEquals("demo.txt", attachment.get("fileName"));
        assertEquals("你好\n", attachment.get("content"));
        assertEquals(hash("你好\n"), attachment.get("sha256"));
        assertEquals(7, attachment.get("sizeBytes"));
        assertEquals(false, attachment.get("truncated"));
        assertEquals("A1", attachment.get("sourceId"));
        assertTrue(((String) attachment.get("id")).matches("[A-Za-z0-9_-]{8,64}"));
        Map<String, Object> restored = workspaceService().listAttachments(7L, "as_test").get(0);
        assertEquals(attachment, restored);
        assertTrue(store.savedWorkspaces.get("as_test").getAttachmentsJson().contains("你好"));
    }

    @Test
    void forwardsPdfAndDocxBytesToPythonRatherThanPretendingTheyAreUtf8() {
        for (String fileName : List.of("document.pdf", "document.docx")) {
            byte[] binary = new byte[]{0x50, 0x4b, (byte) 0xff, 0x00};
            when(python.extractAttachment(eq(7L), eq(fileName), aryEq(binary)))
                    .thenReturn(Map.of("fileName", fileName, "content", "real extracted text", "truncated", true, "sizeBytes", 4));
            Map<String, Object> attachment = service.upload(7L, "as_test", new MockMultipartFile("file", fileName, "application/octet-stream", binary));
            assertEquals("real extracted text", attachment.get("content"));
            assertEquals(true, attachment.get("truncated"));
            assertEquals(4, attachment.get("sizeBytes"));
        }
        assertEquals(2, workspaceService().listAttachments(7L, "as_test").size());
    }

    @Test
    void keepsOriginalUploadBytesWhenExtractedModelTextIsCapped() throws Exception {
        String text = "a".repeat(31_999) + "\uD83D\uDE80";
        String original = text + "last line";
        Map<String, Object> attachment = upload("long.txt", original);
        assertEquals(true, attachment.get("truncated"));
        assertEquals(original.getBytes(StandardCharsets.UTF_8).length, attachment.get("sizeBytes"));
        String id = (String) attachment.get("id");
        Map<String, Object> resolved = workspaceService().resolveAttachments(7L, "as_test", List.of(id)).get(0);
        assertEquals(text, resolved.get("content"));
        assertEquals(hash(text), resolved.get("sha256"));
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), store.savedWorkspaces.get("as_test").getUploadBytes().get(id));
        service.deleteAttachment(7L, "as_test", id);
        assertFalse(store.savedWorkspaces.get("as_test").getUploadBytes().containsKey(id));
    }

    @Test
    void rejectsOversizeEmptyUnsupportedAndPathLikeUploadsBeforeRemoteExtraction() {
        List<MockMultipartFile> badFiles = List.of(
                new MockMultipartFile("file", "demo.txt", "text/plain", new byte[5 * 1024 * 1024 + 1]),
                new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]),
                new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1}),
                new MockMultipartFile("file", "../demo.txt", "text/plain", new byte[]{1}),
                new MockMultipartFile("file", "C:\\demo.txt", "text/plain", new byte[]{1}));
        for (MockMultipartFile file : badFiles) assertThrows(AgentException.class, () -> service.upload(7L, "as_test", file));
        verifyNoInteractions(python);
        assertTrue(store.savedWorkspaces.isEmpty());
    }

    @Test
    void malformedOrFailedExtractionCannotCreateAnAttachment() {
        when(python.extractAttachment(eq(7L), eq("demo.txt"), any(byte[].class)))
                .thenReturn(Map.of("fileName", "demo.txt", "content", "text", "truncated", false, "sizeBytes", 999));
        assertThrows(AgentException.class, () -> upload("demo.txt", "text"));
        assertTrue(store.savedWorkspaces.isEmpty());
        when(python.extractAttachment(eq(7L), eq("demo.txt"), any(byte[].class)))
                .thenThrow(new AgentException(AgentErrorCode.AGENT_PARAM_40001, "corrupt document"));
        assertThrows(AgentException.class, () -> upload("demo.txt", "text"));
        assertTrue(store.savedWorkspaces.isEmpty());
    }

    @Test
    void everyWorkspaceLookupEnforcesUserAndSessionIsolation() throws Exception {
        Map<String, Object> attachment = upload("demo.txt", ORIGINAL);
        String id = (String) attachment.get("id");
        addArtifact("art_patch_001", "patch", PATCH);
        assertThrows(AgentException.class, () -> service.upload(8L, "as_test", new MockMultipartFile("file", "demo.txt", "text/plain", new byte[]{1})));
        assertThrows(AgentException.class, () -> service.listAttachments(8L, "as_test"));
        assertThrows(AgentException.class, () -> service.deleteAttachment(8L, "as_test", id));
        assertThrows(AgentException.class, () -> service.deleteAttachment(7L, "as_other", id));
        assertThrows(AgentException.class, () -> service.resolveAttachments(7L, "as_other", List.of(id)));
        assertThrows(AgentException.class, () -> service.previewPatch(8L, "as_test", "art_patch_001", id));
        assertThrows(AgentException.class, () -> service.previewPatch(7L, "as_other", "art_patch_001", id));
        assertThrows(AgentException.class, () -> service.createTasks(7L, "as_other", "art_patch_001"));
        assertThrows(AgentException.class, () -> service.saveArtifact(7L, "as_other", "art_patch_001"));
        assertThrows(AgentException.class, () -> service.listTasks(8L, "as_test"));
        assertThrows(AgentException.class, () -> service.deleteSession(8L, "as_test"));
        assertEquals(1, service.listAttachments(7L, "as_test").size());
        verify(python, never()).previewArtifactPatch(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void resolvesOnlyOwnedIdsInRequestOrderWithNoClientPathsOrExtraFields() {
        Map<String, Object> first = upload("demo.txt", "first");
        Map<String, Object> second = upload("other.py", "second");
        List<String> ids = List.of((String) second.get("id"), (String) first.get("id"));
        List<Map<String, Object>> resolved = service.resolveAttachments(7L, "as_test", ids);
        assertEquals(ids, resolved.stream().map(a -> (String) a.get("id")).toList());
        assertEquals("second", resolved.get(0).get("content"));
        assertEquals(java.util.Set.of("id", "fileName", "content", "sha256"), resolved.get(0).keySet());
        assertThrows(AgentException.class, () -> service.resolveAttachments(7L, "as_test", List.of("att_missing")));
        assertThrows(AgentException.class, () -> service.resolveAttachments(7L, "as_test", List.of((String) first.get("id"), (String) first.get("id"))));
        assertThrows(AgentException.class, () -> service.resolveAttachments(7L, "as_test", List.of("att_one_001", "att_two_001", "att_three_001", "att_four_001", "att_five_001")));
        service.deleteAttachment(7L, "as_test", (String) second.get("id"));
        Map<String, Object> third = upload("third.txt", "third");
        assertEquals("A3", third.get("sourceId"), "deleted source identities must not be reused");
        assertThrows(AgentException.class, () -> service.resolveAttachments(7L, "as_test", List.of((String) second.get("id"))));
    }

    @Test
    void previewDoesNotMutateOriginalAndConfirmedApplyCreatesANewVersion() throws Exception {
        Map<String, Object> original = upload("demo.txt", ORIGINAL);
        String baseId = (String) original.get("id");
        addArtifact("art_patch_001", "patch", PATCH);
        Map<String, Object> preview = service.previewPatch(7L, "as_test", "art_patch_001", baseId);
        assertEquals(ORIGINAL, preview.get("before"));
        assertEquals("a\nc\n", preview.get("after"));
        assertEquals(PATCH, preview.get("diff"));
        assertEquals(hash(ORIGINAL), preview.get("baseHash"));
        assertEquals(hash(PATCH), preview.get("patchHash"));
        assertEquals(baseId, preview.get("baseAttachmentId"));
        assertEquals(List.of(original), service.listAttachments(7L, "as_test"));
        Map<String, Object> version = service.applyPatch(7L, "as_test", "art_patch_001", baseId, hash(ORIGINAL), hash(PATCH), true);
        assertNotEquals(baseId, version.get("id"));
        assertEquals(baseId, version.get("baseAttachmentId"));
        assertEquals(2, version.get("version"));
        assertEquals("a\nc\n", version.get("content"));
        assertEquals(hash("a\nc\n"), version.get("sha256"));
        List<Map<String, Object>> restored = workspaceService().listAttachments(7L, "as_test");
        assertEquals(original, restored.get(0));
        assertEquals(version, restored.get(1));
        verify(python, times(2)).previewArtifactPatch(7L, "demo.txt", ORIGINAL, PATCH);
    }

    @Test
    void unconfirmedOrStaleHashesCannotApplyAPatch() throws Exception {
        String baseId = (String) upload("demo.txt", ORIGINAL).get("id");
        addArtifact("art_patch_001", "patch", PATCH);
        assertThrows(AgentException.class, () -> service.applyPatch(7L, "as_test", "art_patch_001", baseId, hash(ORIGINAL), hash(PATCH), false));
        assertThrows(AgentException.class, () -> service.applyPatch(7L, "as_test", "art_patch_001", baseId, "0".repeat(64), hash(PATCH), true));
        assertThrows(AgentException.class, () -> service.applyPatch(7L, "as_test", "art_patch_001", baseId, hash(ORIGINAL), "0".repeat(64), true));
        assertEquals(1, service.listAttachments(7L, "as_test").size());
        service.deleteAttachment(7L, "as_test", baseId);
        assertThrows(AgentException.class, () -> service.applyPatch(7L, "as_test", "art_patch_001", baseId, hash(ORIGINAL), hash(PATCH), true));
    }

    @Test
    void invalidPatchAndUntrustedPythonHashesAreRejectedWithoutWritingAVersion() throws Exception {
        Map<String, Object> original = upload("demo.txt", ORIGINAL);
        String baseId = (String) original.get("id");
        addArtifact("art_patch_001", "patch", PATCH);
        when(python.previewArtifactPatch(7L, "demo.txt", ORIGINAL, PATCH))
                .thenReturn(Map.of("content", "a\nc\n", "baseHash", "0".repeat(64), "patchHash", hash(PATCH)));
        assertThrows(AgentException.class, () -> service.previewPatch(7L, "as_test", "art_patch_001", baseId));
        assertThrows(AgentException.class, () -> service.applyPatch(7L, "as_test", "art_patch_001", baseId, hash(ORIGINAL), hash(PATCH), true));
        when(python.previewArtifactPatch(7L, "demo.txt", ORIGINAL, PATCH))
                .thenThrow(new AgentException(AgentErrorCode.AGENT_PARAM_40001, "old lines do not match"));
        assertThrows(AgentException.class, () -> service.previewPatch(7L, "as_test", "art_patch_001", baseId));
        assertEquals(List.of(original), service.listAttachments(7L, "as_test"));
    }

    @Test
    void tasksComeFromCanonicalJsonAndRepeatCreationPreservesIdsAndDoneStatus() throws Exception {
        addArtifact("art_tasks_001", "json", "{\"todos\":[{\"title\":\"Review source\",\"status\":\"done\"},{\"title\":\"Write tests\"}]}");
        List<Map<String, Object>> tasks = service.createTasks(7L, "as_test", "art_tasks_001");
        assertEquals(List.of("Review source", "Write tests"), tasks.stream().map(t -> t.get("title")).toList());
        assertTrue(tasks.stream().allMatch(t -> "pending".equals(t.get("status"))));
        assertTrue(tasks.stream().allMatch(t -> "art_tasks_001".equals(t.get("sourceArtifactId")) && t.get("createdAt") != null));
        assertNotEquals(tasks.get(0).get("id"), tasks.get(1).get("id"));
        Map<String, Object> done = service.updateTask(7L, "as_test", (String) tasks.get(0).get("id"), "done");
        assertEquals("done", done.get("status"));
        List<Map<String, Object>> repeated = workspaceService().createTasks(7L, "as_test", "art_tasks_001");
        assertEquals(tasks.stream().map(t -> t.get("id")).toList(), repeated.stream().map(t -> t.get("id")).toList());
        assertEquals("done", repeated.get(0).get("status"));
        assertEquals(2, workspaceService().listTasks(7L, "as_test").size());
        assertThrows(AgentException.class, () -> service.updateTask(8L, "as_test", (String) tasks.get(0).get("id"), "pending"));
        assertThrows(AgentException.class, () -> service.updateTask(7L, "as_other", (String) tasks.get(0).get("id"), "pending"));
        assertThrows(AgentException.class, () -> service.updateTask(7L, "as_test", (String) tasks.get(0).get("id"), "complete"));
        assertEquals("done", workspaceService().listTasks(7L, "as_test").get(0).get("status"));
    }

    @Test
    void theProjectsOwnTodoFieldIsAlsoATaskTitle() throws Exception {
        addArtifact("art_todos_002", "json",
                "{\"todos\":[{\"owner\":\"张三\",\"task\":\"交付初稿\",\"dueAt\":null,\"confidence\":0.8}]}");
        List<Map<String, Object>> tasks = service.createTasks(7L, "as_test", "art_todos_002");
        assertEquals(List.of("交付初稿"), tasks.stream().map(t -> t.get("title")).toList());
    }

    @Test
    void supportsItemsButRejectsInvalidJsonOrTaskMembersAtomically() throws Exception {
        addArtifact("art_items_001", "json", "{\"items\":[\"Actual item\",{\"title\":\"Another\"}]}");
        assertEquals(2, service.createTasks(7L, "as_test", "art_items_001").size());
        for (String content : List.of("not json", "{}", "{\"todos\":[{\"title\":\"valid\"},{}]}", "{\"items\":[\"\"]}")) {
            String artifactId = "art_bad_" + Math.abs(content.hashCode());
            addArtifact(artifactId, "json", content);
            assertThrows(AgentException.class, () -> service.createTasks(7L, "as_test", artifactId));
        }
        assertEquals(2, service.listTasks(7L, "as_test").size());
    }

    @Test
    void savingArtifactStoresActualGraphBytesNotADecorativeTopology() throws Exception {
        String graph = "{\"nodes\":[{\"id\":\"model-1\"}],\"edges\":[]}";
        addArtifact("art_graph_001", "graph", graph);
        Map<String, Object> saved = service.saveArtifact(7L, "as_test", "art_graph_001");
        assertEquals(graph, saved.get("content"));
        assertEquals(hash(graph), saved.get("sha256"));
        assertEquals(graph.getBytes(StandardCharsets.UTF_8).length, saved.get("sizeBytes"));
        assertEquals("art_graph_001", saved.get("sourceArtifactId"));
        assertEquals(saved, workspaceService().listAttachments(7L, "as_test").get(0));
        verifyNoInteractions(python);
    }

    @Test
    void storageFailurePropagatesAndSessionCleanupOnlyDeletesItsOwnData() throws Exception {
        upload("demo.txt", ORIGINAL);
        service.upload(7L, "as_other", new MockMultipartFile("file", "demo.txt", "text/plain", ORIGINAL.getBytes(StandardCharsets.UTF_8)));
        addArtifact("art_tasks_001", "json", "{\"items\":[\"task\"]}");
        doThrow(new IllegalStateException("storage failed")).when(store.workspaces).saveAndFlush(any(AgentWorkspace.class));
        assertThrows(RuntimeException.class, () -> service.createTasks(7L, "as_test", "art_tasks_001"));
        assertTrue(service.listTasks(7L, "as_test").isEmpty());
        service.deleteSession(7L, "as_test");
        assertTrue(service.listAttachments(7L, "as_test").isEmpty());
        assertEquals(1, service.listAttachments(7L, "as_other").size());
    }

    private AgentWorkspaceService workspaceService() {
        return new AgentWorkspaceService(store.mapper, store.workspaces, store.sessions, runs, python);
    }

    private Map<String, Object> upload(String name, String text) {
        return service.upload(7L, "as_test", new MockMultipartFile("file", name, "text/plain", text.getBytes(StandardCharsets.UTF_8)));
    }

    private void addArtifact(String id, String kind, String content) throws Exception {
        String runId = "run_" + id;
        var request = request();
        request.setScopeConfirmed(true);
        request.setExpectedBindingVersion(1L);
        runs.begin(7L, "as_test", runId, request);
        // The run itself carries a real, currently-valid grant for this actor/session.
        runs.replaceScope(7L, "as_test", runId, authority.grantFor(7L, "as_test", runId, 9L));
        Map<String, Object> canonical = new LinkedHashMap<>(artifact(id, kind, content));
        canonical.put("runId", runId);
        runs.record(7L, "as_test", runId, "artifact", Map.of("artifact", canonical));
        runs.record(7L, "as_test", runId, "done", Map.of("finishReason", "stop"));
        runs.finish(7L, "as_test", runId);
    }
}

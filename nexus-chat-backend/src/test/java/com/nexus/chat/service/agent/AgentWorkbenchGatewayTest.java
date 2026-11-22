package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.controller.agent.AgentController;
import com.nexus.chat.dto.agent.AgentEnums;
import com.nexus.chat.dto.agent.AgentSessionAndChatDtos.ResumeRequest;
import com.nexus.chat.dto.agent.AgentSessionAndChatDtos.SessionChatRequest;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.agent.memory.AgentMemoryService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AgentWorkbenchGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentProviderService providers = mock(AgentProviderService.class);
    private final RestTemplate transport = new RestTemplate();
    private final MockRestServiceServer python = MockRestServiceServer.bindTo(transport).build();
    private final AgentGatewayService gateway = new AgentGatewayService(mapper, new RestTemplateBuilder(), providers);

    AgentWorkbenchGatewayTest() {
        ReflectionTestUtils.setField(gateway, "restTemplate", transport);
        ReflectionTestUtils.setField(gateway, "pythonBaseUrl", "http://python");
        ReflectionTestUtils.setField(gateway, "signingSecret", "test-key");
    }

    @Test
    void publicRequestValidatesBoundsAndNeverBindsExtractedContent() throws Exception {
        var request = mapper.readValue("""
                {"operationType":"ASSISTANT_CHAT","input":"hello","runId":"bad/","retryOfRunId":"short",
                 "attachmentIds":["att_test_1","att_test_2","att_test_3","att_test_4","att_test_5"],
                 "resolvedAttachments":[{"content":"forged"}],
                 "options":{"maxIterations":0,"temperature":2.1,"maxOutputTokens":8193}}
                """, SessionChatRequest.class);
        assertFalse(mapper.valueToTree(request).has("resolvedAttachments"));
        assertTrue(request.getResolvedAttachments() == null || request.getResolvedAttachments().isEmpty());
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var violations = validators.getValidator().validate(request);
            assertEquals(Set.of("runId", "retryOfRunId", "attachmentIds", "options.maxIterations", "options.temperature", "options.maxOutputTokens"),
                    violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet()));
            request.setRunId("run_test_001");
            request.setRetryOfRunId(null);
            request.setAttachmentIds(List.of("att_test_1"));
            request.getOptions().setMaxIterations(12);
            request.getOptions().setTemperature(0.0);
            request.getOptions().setMaxOutputTokens(8192);
            assertTrue(validators.getValidator().validate(request).isEmpty());
        }
    }

    @Test
    void invokeCarriesOwnedAttachmentsOptionsAndRetryLineage() throws Exception {
        var request = mapper.readValue("""
                {"operationType":"ASSISTANT_CHAT","input":"hello","runId":"run_new_001","retryOfRunId":"run_old_001",
                 "attachmentIds":["att_test_1"],"options":{"maxIterations":3,"temperature":0,"maxOutputTokens":300,
                 "collaborationEnabled":true,"webSearchEnabled":false}}
                """, SessionChatRequest.class);
        request.setResolvedAttachments(List.of(Map.of("id", "att_test_1", "content", "所有", "fileName", "demo.txt", "sha256", "a".repeat(64))));
        python.expect(requestTo("http://python/v1/agent/invoke")).andExpect(r -> {
            JsonNode body = mapper.readTree(((MockClientHttpRequest) r).getBodyAsBytes());
            assertEquals("run_new_001", body.path("runId").asText());
            assertEquals("run_old_001", body.path("retryOfRunId").asText());
            assertEquals(mapper.valueToTree(request.getResolvedAttachments()), body.at("/input/attachments"));
            assertEquals(mapper.readTree("{\"maxIterations\":3,\"temperature\":0.0,\"maxOutputTokens\":300,\"collaborationEnabled\":true,\"webSearchEnabled\":false}"), body.get("options"));
        }).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        gateway.invokeNonStream(7L, "alice", "trace_001", "as_test", request);
        python.verify();
    }

    @Test
    void parsesOnlyCompletePublicFramesWithoutChangingOrBufferingTheWire() throws Exception {
        var forwarded = new ByteArrayOutputStream();
        var events = new ArrayList<Map<String, Object>>();
        var stream = new SseRecordingOutputStream(forwarded, (name, data) -> events.add(Map.of("name", name, "data", data)));
        String wire = ": ping\r\nid: 3\r\nevent: delta\r\ndata: {\"text\":\r\ndata: \"你好\"}\r\n\r\n"
                + "event: private\ndata: {}\n\nevent: done\ndata: broken\n\nevent: usage\ndata: []\n\n"
                + "event: usage\ndata: {\"totalTokens\":3}\n\nevent: done\ndata: {\"finishReason\":\"stop\"}\n";
        byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) stream.write(b);
        stream.flush();
        assertArrayEquals(bytes, forwarded.toByteArray());
        assertEquals(List.of(Map.of("name", "delta", "data", Map.of("text", "你好")),
                Map.of("name", "usage", "data", Map.of("totalTokens", 3))), events);
        stream.close();
        assertEquals(2, events.size(), "EOF is not an SSE frame delimiter");
    }

    @Test
    void recordingFailureDoesNotInjectAnErrorOrStopTheBytePipe() throws Exception {
        byte[] bytes = "event: delta\ndata: {\"text\":\"x\"}\n\nevent: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8);
        var forwarded = new ByteArrayOutputStream();
        var stream = new SseRecordingOutputStream(forwarded, (name, data) -> { throw new IllegalStateException("private storage detail"); });
        stream.write(bytes, 0, 5);
        stream.flush();
        assertEquals(5, forwarded.size());
        stream.write(bytes, 5, bytes.length - 5);
        stream.flush();
        assertArrayEquals(bytes, forwarded.toByteArray());
    }

    @Test
    void workspaceCallsSignExactUtf8BytesWithoutSendingProviderCredentials() throws Exception {
        expectSigned("/v1/agent/attachments/extract", Map.of("fileName", "附件.pdf", "contentBase64", "AP8="), "{\"content\":\"读\"}");
        expectSigned("/v1/agent/artifacts/patch-preview", Map.of("fileName", "附件.txt", "original", "原", "patch", "差"), "{\"content\":\"新\"}");
        assertEquals("读", gateway.extractAttachment(7L, "附件.pdf", new byte[]{0, (byte) 255}).get("content"));
        assertEquals("新", gateway.previewArtifactPatch(7L, "附件.txt", "原", "差").get("content"));
        python.verify();
        verifyNoInteractions(providers);
    }

    private void expectSigned(String path, Map<String, String> expected, String response) {
        python.expect(requestTo("http://python" + path)).andExpect(method(HttpMethod.POST)).andExpect(r -> {
            byte[] body = ((MockClientHttpRequest) r).getBodyAsBytes();
            assertEquals(mapper.valueToTree(expected), mapper.readTree(body));
            var headers = r.getHeaders();
            assertEquals("7", headers.getFirst("X-Actor-User-Id"));
            assertNull(headers.getFirst("X-Model-Api-Key"));
            try {
                String canonical = headers.getFirst("X-Internal-Timestamp") + "." + headers.getFirst("X-Internal-Nonce") + "."
                        + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec("test-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                assertEquals(HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))), headers.getFirst("X-Internal-Signature"));
            } catch (java.security.GeneralSecurityException e) { throw new AssertionError(e); }
        }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    @Test
    void unavailableExplicitProviderNeverFallsBackToThePythonDefault() {
        var request = new SessionChatRequest();
        request.setOperationType(AgentEnums.OperationType.ASSISTANT_CHAT);
        request.setInput("hello");
        request.setProviderId(19L);
        var failure = assertThrows(AgentException.class, () -> gateway.invokeNonStream(7L, "alice", "trace_001", "as_test", request));
        assertEquals(HttpStatus.FORBIDDEN, failure.getCode().getHttpStatus());
        python.verify();
    }

    @Nested
    class Controller {
        private final AgentGatewayService rpc = mock(AgentGatewayService.class);
        private final AgentRunService runs = mock(AgentRunService.class);
        private final AgentWorkspaceService workspace = mock(AgentWorkspaceService.class);
        private final AgentSessionService sessions = mock(AgentSessionService.class);
        private final AgentMemoryService memory = mock(AgentMemoryService.class);
        private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        private final AgentController controller = new AgentController(rpc, jwt, mock(ChatMemberRepository.class), mock(MessageRepository.class), memory, sessions, runs, workspace);
        private final MockHttpServletRequest http = new MockHttpServletRequest();
        private final MockHttpServletResponse response = new MockHttpServletResponse();
        private final SessionChatRequest chat = new SessionChatRequest();

        @BeforeEach
        void setUp() {
            http.addHeader("Authorization", "Bearer token");
            when(jwt.getUserIdFromToken("token")).thenReturn(7L);
            when(jwt.getUsernameFromToken("token")).thenReturn("alice");
            chat.setOperationType(AgentEnums.OperationType.ASSISTANT_CHAT);
            chat.setInput("question");
        }

        @Test
        void retryRequiresAnOwnedParentBeforeSendingOrAppendingHistory() {
            chat.setRetryOfRunId("run_foreign");
            assertThrows(AgentException.class, () -> controller.chatStream("as_test", chat, null, http, response));
            assertThrows(AgentException.class, () -> controller.chat("as_test", chat, http));
            verify(runs, never()).begin(anyLong(), anyString(), anyString(), any());
            verifyNoInteractions(rpc, memory);
        }

        @Test
        void registersOneOwnedRetryAndFinishesEvenOnDisconnect() throws Exception {
            chat.setAttachmentIds(List.of("att_test_1"));
            chat.setRetryOfRunId("run_old_001");
            when(runs.list(7L, "as_test")).thenReturn(List.of(Map.of("runId", "run_old_001", "input", "question")));
            var attachments = List.of(Map.<String, Object>of("id", "att_test_1", "fileName", "demo.txt", "content", "真实", "sha256", "a".repeat(64)));
            when(workspace.resolveAttachments(7L, "as_test", chat.getAttachmentIds())).thenReturn(attachments);
            doAnswer(inv -> {
                OutputStream out = inv.getArgument(5);
                out.write("event: delta\ndata: {\"text\":\"x\"}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertEquals(attachments, chat.getResolvedAttachments());
                verify(runs).record(7L, "as_test", chat.getRunId(), "delta", Map.of("text", "x"));
                throw new IOException("disconnect");
            }).when(rpc).streamPythonRaw(eq(7L), eq("alice"), anyString(), eq("as_test"), same(chat), any());
            assertThrows(IOException.class, () -> controller.chatStream("as_test", chat, null, http, response));
            assertTrue(chat.getRunId().matches("[A-Za-z0-9_-]{8,64}"));
            assertNotEquals(chat.getRetryOfRunId(), chat.getRunId());
            var order = inOrder(sessions, workspace, runs, rpc);
            order.verify(sessions).mustOwn(7L, "as_test");
            order.verify(workspace).resolveAttachments(7L, "as_test", chat.getAttachmentIds());
            order.verify(runs).begin(7L, "as_test", chat.getRunId(), chat);
            order.verify(rpc).streamPythonRaw(eq(7L), eq("alice"), anyString(), eq("as_test"), same(chat), any());
            order.verify(runs).finish(7L, "as_test", chat.getRunId());
            verifyNoInteractions(memory);
        }

        @Test
        void resumeRecordsExistingRunButLeavesLegacyCheckpointsOnTheirOwnedRawPipe() throws Exception {
            String frame = "event: done\r\ndata: {\"finishReason\":\"stop\"}\r\n\r\n";
            var selection = new ResumeRequest();
            selection.setProviderId(19L);
            when(runs.list(7L, "as_test")).thenReturn(List.of(Map.of("runId", "run_local_01")));
            doAnswer(inv -> { ((OutputStream) inv.getArgument(6)).write(frame.getBytes(StandardCharsets.UTF_8)); return null; })
                    .when(rpc).resumePythonRaw(eq(7L), eq("alice"), anyString(), eq("as_test"), anyString(), eq(19L), any());
            controller.resumeStream("as_test", "run_local_01", selection, http, response);
            verify(runs).resume(7L, "as_test", "run_local_01", response.getHeader("X-Trace-Id"));
            verify(runs).record(7L, "as_test", "run_local_01", "done", Map.of("finishReason", "stop"));
            verify(runs).finish(7L, "as_test", "run_local_01");
            var legacy = new MockHttpServletResponse();
            controller.resumeStream("as_test", "run_legacy01", selection, http, legacy);
            assertArrayEquals(frame.getBytes(StandardCharsets.UTF_8), legacy.getContentAsByteArray());
            verify(sessions, times(2)).mustOwn(7L, "as_test");
            verify(runs, never()).finish(7L, "as_test", "run_legacy01");
            verify(runs, never()).begin(anyLong(), anyString(), anyString(), any());
        }

        @Test
        void ownedListDoesNotReplaceLegacyMessages() {
            var rows = List.of(Map.<String, Object>of("runId", "run_test_01", "answer", "answer"));
            when(runs.list(7L, "as_test")).thenReturn(rows);
            assertEquals(rows, controller.listRuns("as_test", http).getBody().getData().get("runs"));
            when(sessions.mustOwn(7L, "as_other")).thenThrow(new AgentException(AgentErrorCode.AGENT_AUTHZ_40301));
            assertThrows(AgentException.class, () -> controller.listRuns("as_other", http));
            verify(runs, never()).list(7L, "as_other");
            var messages = List.of(Map.<String, Object>of("role", "user", "content", "question"));
            when(sessions.messagesOf(7L, "as_test")).thenReturn(messages);
            assertEquals(messages, controller.sessionMessages("as_test", http).getBody().getData().get("messages"));
        }

        @Test
        void clearAndDeletePurgeBeforeRemovingOwnedWorkspaceOrRuns() {
            controller.clearSessionMemory("as_clear", http);
            controller.deleteSession("as_delete", http);
            for (String sid : List.of("as_clear", "as_delete")) {
                var order = inOrder(sessions, rpc, runs, workspace);
                order.verify(sessions).mustOwn(7L, sid);
                order.verify(rpc).purgeRuns(eq(7L), anyString(), eq(sid));
                verify(runs).deleteSession(7L, sid);
                verify(workspace).deleteSession(7L, sid);
            }
            doThrow(new AgentException(AgentErrorCode.AGENT_STREAM_40901)).when(rpc).purgeRuns(eq(7L), anyString(), eq("as_busy"));
            assertThrows(AgentException.class, () -> controller.clearSessionMemory("as_busy", http));
            assertThrows(AgentException.class, () -> controller.deleteSession("as_busy", http));
            verify(workspace, never()).deleteSession(7L, "as_busy");
            verify(runs, never()).deleteSession(7L, "as_busy");
        }
    }

    @Test
    void validationErrorsGiveAUsefulReasonWithoutEchoingInputs() {
        python.expect(requestTo("http://python/v1/agent/artifacts/patch-preview")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON).body("{\"detail\":\"old lines do not match\",\"input\":\"private file bytes\"}"));
        var failure = assertThrows(AgentException.class, () -> gateway.previewArtifactPatch(7L, "x.txt", "old", "patch"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failure.getCode().getHttpStatus());
        assertTrue(failure.getMessage().toLowerCase().contains("match"));
        assertFalse(failure.getMessage().contains("private"));
        python.verify();
    }
}

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
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
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
        com.nexus.chat.agent.AgentScopeWireFixture.bind(request,7L,"as_test");
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

        private final com.nexus.chat.service.agent.scope.AgentScopeService scopes=mock(com.nexus.chat.service.agent.scope.AgentScopeService.class);
        @BeforeEach
        void setUp() {
            ReflectionTestUtils.setField(controller,"scopeService",scopes);
            when(scopes.authorize(anyLong(),anyString(),anyString(),any(),anyBoolean(),any(),any(),anyBoolean()))
                .thenAnswer(i->com.nexus.chat.agent.AgentScopeWireFixture.scope(i.getArgument(0),i.getArgument(1),i.getArgument(2)));
            // The binding version must match what the client confirmed, or the turn is a 409.
            when(scopes.get(anyLong(),anyString()))
                .thenAnswer(i->new com.nexus.chat.service.agent.scope.ScopeDtos.Binding(1L,1L,
                    new com.nexus.chat.service.agent.scope.ScopeDtos.Selection(List.of(),
                        com.nexus.chat.service.agent.scope.ScopeDtos.TimeRange.unbounded())));
            when(scopes.checkedSelection(anyLong(),any()))
                .thenAnswer(i->i.getArgument(1));
            http.addHeader("Authorization", "Bearer token");
            when(jwt.getUserIdFromToken("token")).thenReturn(7L);
            when(jwt.getUsernameFromToken("token")).thenReturn("alice");
            chat.setOperationType(AgentEnums.OperationType.ASSISTANT_CHAT);
            chat.setInput("question");
            // A turn must carry the confirmed selection and the binding version it saw.
            chat.setScopeConfirmed(true);
            chat.setExpectedBindingVersion(1L);
        }

        @Test
        void retryRequiresAnOwnedParentBeforeSendingOrAppendingHistory() {
            chat.setRetryOfRunId("run_foreign");
            assertThrows(AgentException.class, () -> controller.chatStream("as_test", chat, null, http, response));
            assertThrows(AgentException.class, () -> controller.chat("as_test", chat, http));
            verify(runs, never()).beginWriter(anyLong(), anyString(), anyString(), any());
            verifyNoInteractions(rpc, memory);
        }

        @Test
        void registersOneOwnedRetryAndFinishesEvenOnDisconnect() throws Exception {
            chat.setAttachmentIds(List.of("att_test_1"));
            chat.setRetryOfRunId("run_old_001");
            when(runs.list(7L, "as_test")).thenReturn(List.of(Map.of("runId", "run_old_001", "input", "question")));
            var attachments = List.of(Map.<String, Object>of("id", "att_test_1", "fileName", "demo.txt", "content", "真实", "sha256", "a".repeat(64)));
            when(workspace.resolveAttachments(7L, "as_test", chat.getAttachmentIds())).thenReturn(attachments);
            // The handler writes under the lease it was given when the run was registered.
            when(runs.beginWriter(eq(7L), eq("as_test"), anyString(), same(chat))).thenReturn("lease_a");
            doAnswer(inv -> {
                OutputStream out = inv.getArgument(5);
                out.write("event: delta\ndata: {\"text\":\"x\"}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertEquals(attachments, chat.getResolvedAttachments());
                verify(runs).record(7L, "as_test", chat.getRunId(), "lease_a", "delta", Map.of("text", "x"));
                throw new IOException("disconnect");
            }).when(rpc).streamPythonRaw(eq(7L), eq("alice"), anyString(), eq("as_test"), same(chat), any());
            assertThrows(IOException.class, () -> controller.chatStream("as_test", chat, null, http, response));
            assertTrue(chat.getRunId().matches("[A-Za-z0-9_-]{8,64}"));
            assertNotEquals(chat.getRetryOfRunId(), chat.getRunId());
            var order = inOrder(sessions, workspace, runs, rpc);
            order.verify(sessions).mustOwn(7L, "as_test");
            order.verify(workspace).resolveAttachments(7L, "as_test", chat.getAttachmentIds());
            order.verify(runs).beginWriter(7L, "as_test", chat.getRunId(), chat);
            order.verify(rpc).streamPythonRaw(eq(7L), eq("alice"), anyString(), eq("as_test"), same(chat), any());
            order.verify(runs).finish(7L, "as_test", chat.getRunId(), "lease_a");
            verifyNoInteractions(memory);
        }

        @Test
        void resumeReauthorizesOwnedRunUnderItsLeaseAndRejectsLegacyCheckpoint() throws Exception {
            String frame = "event: done\r\ndata: {\"finishReason\":\"stop\"}\r\n\r\n";
            var selection = new ResumeRequest();
            selection.setProviderId(19L); selection.setScopeConfirmed(true); selection.setExpectedBindingVersion(1L);
            var receipt = com.nexus.chat.agent.AgentScopeWireFixture.scope(7L, "as_test", "run_local_01");
            when(runs.list(7L, "as_test")).thenReturn(List.of(Map.of("runId", "run_local_01")));
            when(runs.resumeWriter(eq(7L), eq("as_test"), eq("run_local_01"), anyString(), any()))
                    .thenReturn(new AgentRunService.ResumeLease("lease_b", receipt));
            doAnswer(i -> { ((OutputStream) i.getArgument(7)).write(frame.getBytes(StandardCharsets.UTF_8)); return null; })
                    .when(rpc).resumePythonRaw(eq(7L), eq("alice"), anyString(), eq("as_test"), anyString(), eq(19L), eq(receipt), any());
            controller.resumeStream("as_test", "run_local_01", selection, http, response);
            // The grant is rotated by the run service, inside the resume it accepted — never by the controller first.
            verify(runs).resumeWriter(eq(7L), eq("as_test"), eq("run_local_01"), eq(response.getHeader("X-Trace-Id")), any());
            verify(runs, never()).replaceScope(anyLong(), anyString(), anyString(), any());
            verify(runs).record(7L, "as_test", "run_local_01", "lease_b", "done", Map.of("finishReason", "stop"));
            verify(runs).finish(7L, "as_test", "run_local_01", "lease_b");
            assertThrows(AgentException.class, () -> controller.resumeStream("as_test", "run_legacy01", selection, http, new MockHttpServletResponse()));
            verify(rpc, never()).resumePythonRaw(eq(7L), eq("alice"), anyString(), eq("as_test"), eq("run_legacy01"), any(), any(com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope.class), any());
            verify(runs, never()).resumeWriter(eq(7L), eq("as_test"), eq("run_legacy01"), anyString(), any());
            verify(runs, never()).beginWriter(anyLong(), anyString(), anyString(), any());
        }

        @Test
        void replySuggestProjectionRejectsBWithoutReadingBodyAndLegalAHasGroundedInput() {
            var members=(ChatMemberRepository)ReflectionTestUtils.getField(controller,"chatMemberRepository");
            var messages=(MessageRepository)ReflectionTestUtils.getField(controller,"messageRepository");
            when(members.existsByChatIdAndUserId(5L,7L)).thenReturn(true);
            var request=new com.nexus.chat.dto.agent.AgentModeBDtos.ReplySuggestRequest();request.setTargetMessageId(202L);
            var b=mock(MessageRepository.ScopeAttribution.class);when(b.getChatId()).thenReturn(6L);when(b.getCreatedAt()).thenReturn(java.time.LocalDateTime.of(2026,11,20,8,0));
            when(messages.findScopeAttribution(202L)).thenReturn(java.util.Optional.of(b));
            assertThrows(AgentException.class,()->controller.replySuggest(5L,request,http));
            verify(messages,never()).findById(any());verifyNoInteractions(rpc);
            var a=mock(MessageRepository.ScopeAttribution.class);when(a.getChatId()).thenReturn(5L);when(a.getCreatedAt()).thenReturn(java.time.LocalDateTime.of(2026,11,20,8,0));
            when(messages.findScopeAttribution(101L)).thenReturn(java.util.Optional.of(a));request.setTargetMessageId(101L);
            when(rpc.invokeOneShot(eq(7L),eq("alice"),eq(AgentEnums.OperationType.REPLY_SUGGEST),anyString(),eq(5L),anyMap()))
                .thenAnswer(i->{String input=i.getArgument(3);Map<?,?> extra=i.getArgument(5);assertTrue(input.contains("get_message_by_id"));assertTrue(input.contains("101"));assertFalse(extra.containsKey("targetMessageContent"));return Map.of("result",Map.of("draft","A_ONLY_REPLY"));});
            assertEquals("A_ONLY_REPLY",controller.replySuggest(5L,request,http).getBody().getData().getDraft());
            verify(messages,never()).findById(any());
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
                if(sid.equals("as_clear")) order.verify(sessions).mustOwn(7L, sid);
                order.verify(rpc).purgeRuns(eq(7L), anyString(), eq(sid));
                verify(runs).deleteSession(7L, sid);
                verify(workspace).deleteSession(7L, sid);
            }
            doThrow(new AgentException(AgentErrorCode.AGENT_STREAM_40901)).when(rpc).purgeRuns(eq(7L), anyString(), eq("as_busy"));
            assertThrows(AgentException.class, () -> controller.clearSessionMemory("as_busy", http));
            assertThrows(AgentException.class, () -> controller.deleteSession("as_busy", http));
            // A failed agent purge leaves business history untouched (baseline contract).
            verify(workspace, never()).deleteSession(7L, "as_busy");
            verify(runs, never()).deleteSession(7L, "as_busy");
            verify(scopes).invalidate(7L,"as_busy",false);verify(scopes).invalidate(7L,"as_busy",true);
        }
    }

    @Test
    void oneShotHasNonemptyInputMatchingScopeAndAlwaysRevokesWithoutMaskingFailure() {
        var scopes=mock(com.nexus.chat.service.agent.scope.AgentScopeService.class);gateway.setScopeService(scopes);
        when(scopes.authorizeOneShotChat(eq(7L),anyString(),anyString(),eq(5L))).thenAnswer(i->{
            String session=i.getArgument(1),run=i.getArgument(2);var range=com.nexus.chat.service.agent.scope.ScopeDtos.TimeRange.unbounded();
            return new com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope(1,"one_grant",7L,session,run,1,1,1,List.of(5L),List.of(),range,
                com.nexus.chat.service.agent.scope.AgentScopeService.key(7L,session,1,List.of(5L),List.of(),range));
        });
        var sessions=new ArrayList<String>();
        python.expect(requestTo("http://python/v1/agent/invoke")).andExpect(r->{
            var body=mapper.readTree(((MockClientHttpRequest)r).getBodyAsBytes());
            assertEquals(body.at("/session/sessionId"),body.at("/scope/sessionId"));assertEquals(body.get("runId"),body.at("/scope/runId"));
            assertEquals(7,body.at("/scope/actorUserId").asInt());assertEquals(5,body.at("/scope/chatIds/0").asInt());
            assertFalse(body.at("/input/text").asText().isBlank());assertFalse(body.at("/input").has("targetMessageContent"));
            sessions.add(body.at("/session/sessionId").asText());
        }).andRespond(withSuccess("{\"result\":{\"answer\":\"A_ONLY_NONEMPTY\"}}",MediaType.APPLICATION_JSON));
        python.expect(requestTo("http://python/v1/agent/runs/purge")).andRespond(withServerError());
        var result=gateway.invokeOneShot(7L,"alice",AgentEnums.OperationType.REPLY_SUGGEST,"读取 messageId=101 后回复",5L,Map.of("targetMessageId",101L));
        assertEquals("A_ONLY_NONEMPTY",((Map<?,?>)result.get("result")).get("answer"));
        verify(scopes).invalidate(7L,sessions.get(0),true);verify(scopes).revokeSession(7L,sessions.get(0));python.verify();
        assertThrows(AgentException.class,()->gateway.invokeOneShot(7L,"alice",AgentEnums.OperationType.REPLY_SUGGEST,"input",5L,Map.of("scope","forged")));
        verify(scopes,times(1)).authorizeOneShotChat(eq(7L),anyString(),anyString(),eq(5L));
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

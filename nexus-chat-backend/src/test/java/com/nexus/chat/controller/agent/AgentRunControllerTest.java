package com.nexus.chat.controller.agent;

import com.nexus.chat.dto.agent.AgentSessionAndChatDtos;
import com.nexus.chat.exception.agent.AgentErrorCode;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.agent.AgentGatewayService;
import com.nexus.chat.service.agent.AgentRunService;
import com.nexus.chat.service.agent.AgentSessionService;
import com.nexus.chat.service.agent.AgentWorkspaceService;
import com.nexus.chat.service.agent.memory.AgentMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRunControllerTest {
    private final AgentGatewayService gateway = mock(AgentGatewayService.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final AgentSessionService sessions = mock(AgentSessionService.class);
    private final AgentMemoryService memory = mock(AgentMemoryService.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private AgentController controller;

    @BeforeEach
    void setup() {
        request.addHeader("Authorization", "Bearer test-token");
        when(jwt.getUserIdFromToken("test-token")).thenReturn(7L);
        when(jwt.getUsernameFromToken("test-token")).thenReturn("alice");
        controller = new AgentController(gateway, jwt, mock(ChatMemberRepository.class),
                mock(MessageRepository.class), memory, sessions, runs,
                mock(com.nexus.chat.service.agent.AgentWorkspaceService.class));
        // Session deletion/clear revoke through the authority when one is wired.
        var scopes = mock(com.nexus.chat.service.agent.scope.AgentScopeService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "scopeService", scopes);
    }

    @Test
    void unauthorizedSessionNeverReachesPython() {
        when(sessions.mustOwn(7L, "foreign-session")).thenThrow(
                new AgentException(AgentErrorCode.AGENT_AUTHZ_40301, "not owner"));
        assertThrows(AgentException.class, () -> controller.pendingRuns("foreign-session", request));
        assertThrows(AgentException.class, () -> controller.resumeStream("foreign-session", "run_test_001",
                new AgentSessionAndChatDtos.ResumeRequest(), request, new MockHttpServletResponse()));
        assertThrows(AgentException.class, () -> controller.clearSessionMemory("foreign-session", request));
        assertThrows(AgentException.class, () -> controller.deleteSession("foreign-session", request));
        verifyNoInteractions(gateway);
        verifyNoInteractions(memory);
    }

    @Test
    void clearPurgesCheckpointBeforeBusinessHistory() {
        controller.clearSessionMemory("session_001", request);
        var order = inOrder(sessions, gateway, memory);
        order.verify(sessions).mustOwn(7L, "session_001");
        order.verify(gateway).purgeRuns(eq(7L), anyString(), eq("session_001"));
        // Session purge now also disables scoped long memory, not just the short-term keys.
        order.verify(memory).purgeSession(7L, "session_001");
    }

    @Test
    void purgeFailureDoesNotDeleteOrClearBusinessHistory() {
        doThrow(new AgentException(AgentErrorCode.AGENT_MODEL_50202, "Python unavailable"))
                .when(gateway).purgeRuns(eq(7L), anyString(), eq("session_001"));
        assertThrows(AgentException.class, () -> controller.deleteSession("session_001", request));
        assertThrows(AgentException.class, () -> controller.clearSessionMemory("session_001", request));
        verify(sessions, never()).delete(anyLong(), anyString());
        verifyNoInteractions(memory);
    }
    @Test
    void cancelRequiresJwtSessionAndRunMatchBeforeLifecycleRpc() {
        when(runs.list(7L, "session_001")).thenReturn(java.util.List.of(java.util.Map.of("runId", "run_test_001")));
        doReturn(org.springframework.http.ResponseEntity.ok(java.util.Map.of("runId", "run_test_001", "status", "not_active")))
                .when(gateway).cancelRun(eq(7L), anyString(), eq("session_001"), eq("run_test_001"));
        controller.cancelRun("session_001", "run_test_001", request);
        var order = inOrder(sessions, runs, gateway);
        order.verify(sessions).mustOwn(7L, "session_001");
        order.verify(runs).list(7L, "session_001");
        order.verify(gateway).cancelRun(eq(7L), anyString(), eq("session_001"), eq("run_test_001"));
        assertThrows(AgentException.class, () -> controller.cancelRun("session_001", "run_foreign", request));
        verify(gateway, times(1)).cancelRun(anyLong(), anyString(), anyString(), anyString());
    }


    @Test
    void actualHandlersKeepResumeLiveWhenChatFinallyArrivesLate() throws Exception {
        var store=new com.nexus.chat.service.agent.AgentWorkbenchTestStore();
        AgentRunService service=store.runService();
        AgentController real=new AgentController(gateway,jwt,mock(ChatMemberRepository.class),
                mock(MessageRepository.class),memory,sessions,service,mock(AgentWorkspaceService.class));
        // The real authority: the turn and its resume are each authorized, with an explicitly confirmed empty scope.
        org.springframework.test.util.ReflectionTestUtils.setField(real,"scopeService",store.authority.service());
        store.authority.bindOnly(7L,"as_test");
        var aReady=new java.util.concurrent.CountDownLatch(1);
        var bReady=new java.util.concurrent.CountDownLatch(1);
        var releaseA=new java.util.concurrent.CountDownLatch(1);
        var releaseB=new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv->{
            java.io.OutputStream out=inv.getArgument(5);
            out.write(("event: meta\ndata: {\"attemptId\":\"A\",\"sequence\":1}\n\n"+
                    "event: delta\ndata: {\"attemptId\":\"A\",\"sequence\":2,\"text\":\"old\"}\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            aReady.countDown(); org.junit.jupiter.api.Assertions.assertTrue(releaseA.await(5,java.util.concurrent.TimeUnit.SECONDS)); return null;
        }).when(gateway).streamPythonRaw(eq(7L),anyString(),anyString(),eq("as_test"),any(),any());
        doAnswer(inv->{
            java.io.OutputStream out=inv.getArgument(7);
            String trace=inv.getArgument(2);
            out.write(("event: meta\ndata: {\"attemptId\":\"B\",\"sequence\":1,\"resumed\":true,\"traceId\":\""+trace+"\"}\n\n"+
                    "event: delta\ndata: {\"attemptId\":\"B\",\"sequence\":2,\"text\":\"new\"}\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bReady.countDown(); org.junit.jupiter.api.Assertions.assertTrue(releaseB.await(5,java.util.concurrent.TimeUnit.SECONDS));
            out.write("event: done\ndata: {\"attemptId\":\"B\",\"sequence\":3,\"finishReason\":\"stop\"}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));return null;
        }).when(gateway).resumePythonRaw(eq(7L),anyString(),anyString(),eq("as_test"),eq("run_test_001"),nullable(Long.class),
                any(com.nexus.chat.service.agent.scope.ScopeDtos.AuthorizedScope.class),any());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var chat=new AgentSessionAndChatDtos.SessionChatRequest(); chat.setRunId("run_test_001");chat.setInput("fixture");
        chat.setScopeConfirmed(true);chat.setExpectedBindingVersion(1L);
        var again=new AgentSessionAndChatDtos.ResumeRequest();again.setScopeConfirmed(true);again.setExpectedBindingVersion(1L);
        var aResponse=new MockHttpServletResponse();var bResponse=new MockHttpServletResponse();
        try {
            var a=pool.submit(()->{real.chatStream("as_test",chat,"a",request,aResponse);return null;});
            org.junit.jupiter.api.Assertions.assertTrue(aReady.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var b=pool.submit(()->{real.resumeStream("as_test","run_test_001",again,request,bResponse);return null;});
            org.junit.jupiter.api.Assertions.assertTrue(bReady.await(5,java.util.concurrent.TimeUnit.SECONDS));
            releaseA.countDown();a.get(5,java.util.concurrent.TimeUnit.SECONDS);
            var live=service.list(7L,"as_test").get(0);
            org.junit.jupiter.api.Assertions.assertEquals("running",live.get("status"));
            org.junit.jupiter.api.Assertions.assertEquals("new",live.get("answer"));
            org.junit.jupiter.api.Assertions.assertNull(live.get("finishedAt"));
            releaseB.countDown();b.get(5,java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertEquals("done",service.list(7L,"as_test").get(0).get("status"));
            org.junit.jupiter.api.Assertions.assertTrue(bResponse.getContentAsString().contains("event: done"));
        } finally {releaseA.countDown();releaseB.countDown();pool.shutdownNow();}
    }
}

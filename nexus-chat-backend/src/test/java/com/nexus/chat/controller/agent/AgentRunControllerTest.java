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
                mock(MessageRepository.class), memory, sessions, mock(com.nexus.chat.service.agent.AgentRunService.class),
                mock(com.nexus.chat.service.agent.AgentWorkspaceService.class));
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
        order.verify(memory).clearShortTerm(7L, "session_001");
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
}

package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.agent.AgentScopeTestDouble;
import com.nexus.chat.controller.agent.AgentController;
import com.nexus.chat.exception.agent.AgentException;
import com.nexus.chat.repository.ChatMemberRepository;
import com.nexus.chat.repository.MessageRepository;
import com.nexus.chat.repository.agent.AgentLongMemoryRepository;
import com.nexus.chat.repository.agent.AgentMemoryAuditRepository;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.agent.memory.AgentMemoryService;
import com.nexus.chat.service.agent.scope.ScopeDtos;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AgentScopeAuditRegressionTest {

    private static final ScopeDtos.Selection CONTACT = new ScopeDtos.Selection(
            List.of(new ScopeDtos.Target("contact", 9L)), ScopeDtos.TimeRange.unbounded());

    @Test
    void retryIsANewRunAndNeverRegrantsAnExistingRun() {
        var scopes = new AgentScopeTestDouble();
        var original = scopes.grantFor(7L, "as_audit", "run_source_01", 9L);
        var other = scopes.grantFor(7L, "as_audit", "run_other_001", 9L);

        assertThrows(ResponseStatusException.class, () -> scopes.service()
                .authorize(7L, "as_audit", "run_other_001", CONTACT, true, 1L, "run_source_01", false));
        // The refused retry rotated nothing: both existing receipts are still the live ones.
        assertEquals(original, scopes.service().validate(7L, "as_audit", "run_source_01", original));
        assertEquals(other, scopes.service().validate(7L, "as_audit", "run_other_001", other));

        var retried = scopes.service()
                .authorize(7L, "as_audit", "run_retry_001", CONTACT, true, 1L, "run_source_01", false);
        assertEquals(1L, retried.authorizationVersion());
        assertEquals(original.scopeKey(), retried.scopeKey());
    }

    @Test
    void deleteAndClearRefuseBeforeAnyCleanupWhenScopeAuthorityIsMissing() {
        var gateway = mock(AgentGatewayService.class);
        var jwt = mock(JwtTokenProvider.class);
        var sessions = mock(AgentSessionService.class);
        var memory = mock(AgentMemoryService.class);
        var runs = mock(AgentRunService.class);
        var workspace = mock(AgentWorkspaceService.class);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer test-token");
        when(jwt.getUserIdFromToken("test-token")).thenReturn(7L);
        // scopeService deliberately left unwired.
        var controller = new AgentController(gateway, jwt, mock(ChatMemberRepository.class),
                mock(MessageRepository.class), memory, sessions, runs, workspace);

        assertThrows(AgentException.class, () -> controller.deleteSession("as_audit", request));
        assertThrows(AgentException.class, () -> controller.clearSessionMemory("as_audit", request));

        verifyNoInteractions(gateway, memory, runs, workspace);
        verify(sessions, never()).delete(anyLong(), anyString());
    }

    @Test
    void sessionPurgeAlsoRemovesPreScopeTranscriptKeys() {
        var redis = mock(StringRedisTemplate.class);
        var service = new AgentMemoryService(redis, new ObjectMapper(),
                mock(AgentLongMemoryRepository.class), mock(AgentMemoryAuditRepository.class));

        service.clearShortTerm(7L, "as_audit");

        verify(redis).delete(List.of("agent:ctx:7:as_audit:messages", "agent:ctx:7:as_audit", "agent:tool:7:as_audit"));
    }
}

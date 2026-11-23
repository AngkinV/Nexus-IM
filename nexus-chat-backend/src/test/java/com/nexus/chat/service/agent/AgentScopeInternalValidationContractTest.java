package com.nexus.chat.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.controller.agent.InternalAgentScopeController;
import com.nexus.chat.model.*;
import com.nexus.chat.model.agent.*;
import com.nexus.chat.repository.*;
import com.nexus.chat.repository.agent.*;
import com.nexus.chat.service.agent.scope.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression source only; execution belongs to the original T1 session.
 *
 * <p>Locks the internal wire contract the Python agent parses strictly:
 * success is a bare {@code {valid:true,scope}} map, the actor header must match,
 * and a committed-call check never returns bodies or projection data.
 */
class AgentScopeInternalValidationContractTest {

    static final long ACTOR = 1L;
    static final String SESSION = "as_wire";
    static final String RUN = "run_wire01";

    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final AgentScopeBindingRepository bindings = mock(AgentScopeBindingRepository.class);
    final AgentScopeGrantRepository grants = mock(AgentScopeGrantRepository.class);
    final ChatMemberRepository members = mock(ChatMemberRepository.class);
    final ChatRepository chats = mock(ChatRepository.class);
    final ContactRepository contacts = mock(ContactRepository.class);
    final MessageRepository messages = mock(MessageRepository.class);
    final Map<String, AgentScopeBinding> bs = new HashMap<>();
    final Map<String, AgentScopeGrant> gs = new HashMap<>();
    final AgentScopeService scopes = new AgentScopeService(bindings, grants, members, chats, contacts, json);
    final InternalAgentScopeController controller =
            new InternalAgentScopeController(scopes, new AgentScopeToolGuard(scopes, messages));

    AgentScopeInternalValidationContractTest() {
        when(bindings.lockBySessionId(anyString())).thenAnswer(i -> Optional.ofNullable(bs.get(i.getArgument(0))));
        when(bindings.saveAndFlush(any())).thenAnswer(i -> {
            AgentScopeBinding b = i.getArgument(0); bs.put(b.sessionId, b); return b;
        });
        when(grants.findById(anyString())).thenAnswer(i -> Optional.ofNullable(gs.get(i.getArgument(0))));
        when(grants.findBySessionIdAndRunId(anyString(), any())).thenAnswer(i -> gs.values().stream()
                .filter(g -> g.sessionId.equals(i.getArgument(0)) && Objects.equals(g.runId, i.getArgument(1))).toList());
        when(grants.findBySessionId(anyString())).thenAnswer(i -> gs.values().stream()
                .filter(g -> g.sessionId.equals(i.getArgument(0))).toList());
        when(grants.save(any())).thenAnswer(i -> { AgentScopeGrant g = i.getArgument(0); gs.put(g.grantId, g); return g; });
        when(grants.saveAndFlush(any())).thenAnswer(i -> { AgentScopeGrant g = i.getArgument(0); gs.put(g.grantId, g); return g; });
        for (long id : List.of(101L, 202L)) {
            var c = new Chat(); c.setId(id); c.setType(Chat.ChatType.group);
            when(chats.findById(id)).thenReturn(Optional.of(c));
            when(members.existsByChatIdAndUserId(id, ACTOR)).thenReturn(true);
        }
        var a = mock(MessageRepository.ScopeAttribution.class);
        when(a.getChatId()).thenReturn(101L);
        when(a.getCreatedAt()).thenReturn(LocalDateTime.parse("2026-11-22T10:00:00"));
        when(messages.findScopeAttribution(11L)).thenReturn(Optional.of(a));
        scopes.create(ACTOR, SESSION, new ScopeDtos.Selection(
                List.of(new ScopeDtos.Target("group", 101L)), ScopeDtos.TimeRange.unbounded()));
    }

    ScopeDtos.AuthorizedScope grant() {
        return scopes.authorize(ACTOR, SESSION, RUN, null, true, 1L, null, false);
    }

    @Test
    void successIsABareMapWithExactlyValidAndScope() {
        var scope = grant();
        var body = new ScopeDtos.Validation(ACTOR, SESSION, RUN, scope, null);
        Map<String, Object> out = controller.validate(Long.toString(ACTOR), body);
        assertEquals(Set.of("valid", "scope"), out.keySet());
        assertEquals(Boolean.TRUE, out.get("valid"));
        assertEquals(scope.grantId(), ((ScopeDtos.AuthorizedScope) out.get("scope")).grantId());
        // Deserializes back to the strict Python shape (no envelope).
        assertEquals(scope, json.convertValue(out.get("scope"), ScopeDtos.AuthorizedScope.class));
    }

    @Test
    void actorHeaderMustMatchTheBody() {
        var scope = grant();
        var body = new ScopeDtos.Validation(ACTOR, SESSION, RUN, scope, null);
        var ex = assertThrows(ResponseStatusException.class, () -> controller.validate("2", body));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void committedCallCheckReturnsNoBodyOrProjection() throws Exception {
        var scope = grant();
        var body = new ScopeDtos.Validation(ACTOR, SESSION, RUN, scope,
                new ScopeDtos.ToolCall("get_message_by_id", Map.of("message_id", 11)));
        Map<String, Object> out = controller.validate(Long.toString(ACTOR), body);
        assertEquals(Set.of("valid", "scope"), out.keySet());
        assertFalse(json.writeValueAsString(out).contains("messageId"));
        verify(messages, times(1)).findScopeAttribution(11L);
        verify(messages, never()).findById(anyLong());
    }

    @Test
    void committedCallOutsideScopeIsRefusedWithoutBodyRead() {
        var scope = grant();
        var body = new ScopeDtos.Validation(ACTOR, SESSION, RUN, scope,
                new ScopeDtos.ToolCall("get_recent_messages", Map.of("chat_id", 202)));
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.validate(Long.toString(ACTOR), body));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(messages, never()).findScopedMessages(eq(202L), any(), any(), any());
        verify(messages, never()).findById(anyLong());
    }

    @Test
    void toolCallRejectsNullArgumentsInsteadOfSilentlyDroppingThem() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScopeDtos.ToolCall("get_recent_messages", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ScopeDtos.ToolCall(" ", Map.of()));
    }
}
